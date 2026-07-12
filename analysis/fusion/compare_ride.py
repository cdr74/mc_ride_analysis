#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy", "scipy"]
# ///
"""Roll (lean) estimator comparison on a logged ride (DESIGN.md §10, milestone M5).

Estimators, all in the bike frame (calibration from analysis/calibrate.py):

  madgwick   gravity-based Madgwick IMU filter. Expected to UNDERESTIMATE lean in
             steady corners: coordinated cornering aligns specific force with the
             bike's own vertical, so gravity-based filters read toward zero roll.
  rotvec     Android rotation-vector baseline (logged stream 3) — OS fusion,
             gravity-based as well; comparison target, not ground truth.
  kinematic  GPS-aided lean φ = atan(v·ψ̇ / g) from GPS speed and bike-frame yaw
             rate — the correction signal that makes motorcycle roll observable.
  fused      gyro-integrated roll (bike-frame roll rate) with complementary
             correction toward `kinematic` when moving, toward gravity roll when
             (nearly) stationary. This is the candidate ride-display estimator.

Sign convention: positive roll = lean to the RIGHT (bike y points left).

Lean is NOT PRODUCED below LEAN_MIN_SPEED (5 m/s = 18 km/h): steering-angle coupling on
the bar-mounted phone makes it untrustworthy there (DESIGN.md §11). Below the cutoff the
fused filter corrects toward gravity roll (stays bounded, re-converges instantly above
the cutoff) and the reported fused lean is NaN; metrics use the same mask.

Usage: uv run compare_ride.py ride.db [--calib calib.json] [--npz out.npz]
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from math import atan2, degrees, sqrt
from pathlib import Path

import numpy as np
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from madgwick import Madgwick          # noqa: E402
import calibrate                        # noqa: E402

FS = 100.0            # uniform processing rate, Hz (fusion band is < 10 Hz)
LPF_HZ = 15.0         # anti-alias low-pass before resampling from ~400 Hz
G = 9.80665
LEAN_MIN_SPEED = 5.0  # m/s (18 km/h) — no lean below this: bar-turn coupling (§11)
KIN_LPF_HZ = 1.5      # kinematic-lean bandwidth; yaw spikes above this are not lean
TAU_CORR = 2.0        # s  — complementary-filter correction time constant
BETA = 0.05           # Madgwick gain


def load_stream(db: sqlite3.Connection, stream: int) -> np.ndarray:
    a = np.array(db.execute(
        "SELECT t_ns, v0, v1, v2, b0, b1, b2 FROM imu WHERE stream=? ORDER BY t_ns",
        (stream,)).fetchall(), dtype=np.float64)
    if stream in (0, 1, 2):
        return np.column_stack([a[:, 0], a[:, 1:4] - np.nan_to_num(a[:, 4:7])])
    return a  # rotvec: v0-v2 = x,y,z, b0 = w


def resample(t_ns: np.ndarray, v: np.ndarray, grid_s: np.ndarray,
             lpf: bool = True) -> np.ndarray:
    """Low-pass (zero-phase) then linear-interp columns of v onto grid_s (seconds)."""
    t = t_ns / 1e9
    fs_in = (len(t) - 1) / (t[-1] - t[0])
    if lpf and fs_in > 4 * LPF_HZ:
        sos = signal.butter(4, LPF_HZ, fs=fs_in, output="sos")
        v = signal.sosfiltfilt(sos, v, axis=0)
    return np.column_stack([np.interp(grid_s, t, v[:, i]) for i in range(v.shape[1])])


def roll_pitch_from_down(d: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Roll/pitch (rad) from the world-down unit vector in the bike frame.
    Upright: d = (0,0,-1). Positive roll = right lean (down tilts toward -y = right)."""
    roll = np.arctan2(-d[:, 1], -d[:, 2])
    pitch = np.arcsin(np.clip(d[:, 0], -1, 1))
    return roll, pitch


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("ride_file")
    p.add_argument("--calib", help="calibration JSON (default: solve automatically)")
    p.add_argument("--npz", help="dump aligned traces to this .npz")
    args = p.parse_args()

    db = sqlite3.connect(f"file:{args.ride_file}?mode=ro", uri=True)
    if args.calib:
        R = np.array(json.load(open(args.calib))["R_phone_to_bike"])
    else:
        sol = calibrate.solve_auto(db) or calibrate.solve_tagged(db)
        if not sol:
            print("no calibration available"); return 1
        print(f"calibration solved ({sol['method']})")
        R = np.array(sol["R_phone_to_bike"])

    acc = load_stream(db, 0)
    gyr = load_stream(db, 1)
    rv = load_stream(db, 3)
    gps = np.array(db.execute(
        "SELECT t_ns, speed FROM gps WHERE speed IS NOT NULL ORDER BY t_ns").fetchall(),
        dtype=np.float64)
    db.close()

    t0 = max(acc[0, 0], gyr[0, 0], rv[0, 0]) / 1e9
    t1 = min(acc[-1, 0], gyr[-1, 0], rv[-1, 0]) / 1e9
    grid = np.arange(t0, t1, 1.0 / FS)
    n = len(grid)
    print(f"{args.ride_file}: {n} samples at {FS:.0f} Hz over {(t1 - t0) / 60:.1f} min")

    a = resample(acc[:, 0], acc[:, 1:4], grid)          # phone frame, m/s²
    w = resample(gyr[:, 0], gyr[:, 1:4], grid)          # phone frame, rad/s
    v = np.interp(grid, gps[:, 0] / 1e9, gps[:, 1])     # m/s
    a_b = a @ R.T                                       # bike frame
    w_b = w @ R.T

    # --- madgwick (phone frame), gravity direction rotated into bike frame
    mw = Madgwick(beta=BETA)
    mw.init_from_accel(*a[0])
    down_ph = np.empty((n, 3))
    dt = 1.0 / FS
    for i in range(n):
        mw.update(w[i, 0], w[i, 1], w[i, 2], a[i, 0], a[i, 1], a[i, 2], dt)
        down_ph[i] = mw.down_in_sensor()
    roll_mad, pitch_mad = roll_pitch_from_down(down_ph @ R.T)

    # --- rotvec baseline: q = (w=b0, x=v0, y=v1, z=v2), sensor->world; down in phone frame
    q = resample(rv[:, 0], np.column_stack([rv[:, 4], rv[:, 1:4]]), grid, lpf=False)
    q /= np.linalg.norm(q, axis=1, keepdims=True)
    qw, qx, qy, qz = q.T
    down_rv = np.column_stack([-(2 * (qx * qz - qw * qy)),
                               -(2 * (qy * qz + qw * qx)),
                               -(1 - 2 * (qx * qx + qy * qy))])
    roll_rv, pitch_rv = roll_pitch_from_down(down_rv @ R.T)

    # --- kinematic GPS-aided lean: φ = atan(v·ψ̇/g), ψ̇ ≈ bike-frame yaw rate
    #     positive yaw rate (left turn, z up) → lean left → negative roll.
    #     Low-passed to the physical lean bandwidth: sub-second yaw spikes (bumps,
    #     quick flicks) are transients, not lean — unfiltered they read as ±40°
    #     phantom peaks. (On-device this must become a causal filter.)
    sos_kin = signal.butter(2, KIN_LPF_HZ, fs=FS, output="sos")
    roll_kin = signal.sosfiltfilt(sos_kin, np.arctan2(-v * w_b[:, 2], G))

    # --- fused: integrate bike-frame roll rate, correct toward kinematic (moving)
    #     or gravity roll (stationary); the ride-display candidate
    roll_grav = np.arctan2(-(-a_b[:, 1]), a_b[:, 2])    # accel 'up' → down = -a
    k = dt / TAU_CORR
    fused = np.empty(n)
    phi = roll_grav[0]
    for i in range(n):
        phi += w_b[i, 0] * dt
        target = roll_kin[i] if v[i] > LEAN_MIN_SPEED else roll_grav[i]
        phi += k * (target - phi)
        fused[i] = phi

    # --- metrics; lean does not exist below the speed cutoff
    mask = v > LEAN_MIN_SPEED
    fused_out = np.where(mask, fused, np.nan)
    def rms(x): return degrees(sqrt(float(np.mean(x[mask] ** 2))))
    deg = np.degrees
    print(f"\nlean cutoff: speed > {LEAN_MIN_SPEED:.0f} m/s ({LEAN_MIN_SPEED * 3.6:.0f} km/h) "
          f"covers {100 * mask.mean():.0f} % of samples")
    print(f"{'estimator':<11} {'max L':>7} {'max R':>7} {'RMS vs rotvec':>14} {'RMS vs kin':>11}")
    for name, r in (("madgwick", roll_mad), ("rotvec", roll_rv),
                    ("kinematic", roll_kin), ("fused", fused)):
        rm = deg(r[mask])
        print(f"{name:<11} {rm.min():7.1f} {rm.max():7.1f} "
              f"{rms(r - roll_rv):14.2f} {rms(r - roll_kin):11.2f}")
    print(f"\npitch (madgwick vs rotvec) RMS: {rms(pitch_mad - pitch_rv):.2f}° "
          f"— small-pitch is suspension-contaminated (DESIGN.md §11)")

    if args.npz:
        np.savez_compressed(
            args.npz, t=grid, speed=v, lean_valid=mask,
            roll_madgwick=roll_mad, roll_rotvec=roll_rv,
            roll_kinematic=roll_kin, roll_fused=fused_out, pitch_madgwick=pitch_mad,
            pitch_rotvec=pitch_rv)
        print(f"wrote {args.npz}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
