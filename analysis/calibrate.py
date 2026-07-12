#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy"]
# ///
"""Solve R_phone→bike (DESIGN.md §7, ADR 0004).

Primary mode is **automatic**: phases are detected in any normal ride —

  bike z (up)      → duration-weighted mean specific force over straight steady-cruise
                     windows (never stops: bars turn at a standstill)
  bike x (forward) → magnitude-weighted horizontal specific-force direction over
                     straight-line acceleration events; y = z × x

Legacy rides tagged by the guided flow (app ≤ 0.2.x, calib_start/calib_end markers) are
additionally solved from their tagged segments and reported as a cross-check; segment
semantics per analysis/schema.md (static ≥ 8 s, accel/brake ≥ 2 s, brake tail below
1.5 m/s trimmed, last complete set wins).

Usage: uv run calibrate.py ride.db [--json out.json]
Exit 0 = solved, 1 = no usable calibration (fall back to the most recent solved one).
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys

import numpy as np

# tagged-segment filters (legacy files)
MIN_STATIC_S = 8.0
MIN_DYNAMIC_S = 2.0
BRAKE_TAIL_MS = 1.5          # m/s — discard brake samples below this GPS speed
ACCEL_THRESH = 1.0           # m/s² GPS-derived accel that counts as "maneuvering"
# automatic phase detection
CRUISE_MIN_MS = 8.0          # m/s — minimum speed of a cruise window
CRUISE_MAX_DVDT = 0.3        # m/s² — steady
CRUISE_MAX_HDG = 1.0         # °/s — straight
CRUISE_MIN_S = 5.0
EVENT_MIN_DVDT = 1.2         # m/s² sustained
EVENT_MIN_S = 2.0
EVENT_MAX_BRG = 10.0         # ° net bearing change
EVENT_MAX_YAW = 0.05         # rad/s mean yaw rate about bike z
EVENT_MIN_H = 0.8            # m/s² minimum horizontal specific force
G = 9.80665


def imu_window(db: sqlite3.Connection, stream: int, t0: int, t1: int) -> np.ndarray:
    """Bias-corrected sensor samples in [t0, t1] as (n, 4) array: t_ns, x, y, z."""
    rows = db.execute(
        "SELECT t_ns, v0, v1, v2, b0, b1, b2 FROM imu "
        "WHERE stream=? AND t_ns BETWEEN ? AND ? ORDER BY t_ns",
        (stream, int(t0), int(t1)))
    a = np.array(rows.fetchall(), dtype=np.float64)
    if a.size == 0:
        return np.empty((0, 4))
    v, b = a[:, 1:4], np.nan_to_num(a[:, 4:7])
    return np.column_stack([a[:, 0], v - b])


def gps_track(db: sqlite3.Connection) -> np.ndarray:
    """(n, 3) array of t_ns, speed, bearing (bearing may be nan)."""
    rows = db.execute(
        "SELECT t_ns, speed, bearing FROM gps WHERE speed IS NOT NULL ORDER BY t_ns"
    ).fetchall()
    return np.array(rows, dtype=np.float64).reshape(-1, 3)


def frame_from(z_up: np.ndarray, x_fwd_h: np.ndarray) -> np.ndarray:
    """Orthonormal R with rows = bike x/y/z in phone coords, v_bike = R v_phone."""
    y = np.cross(z_up, x_fwd_h)
    y /= np.linalg.norm(y)
    return np.vstack([np.cross(y, z_up), y, z_up])


# ---------------------------------------------------------------- automatic solve

def runs(mask: np.ndarray) -> list[tuple[int, int]]:
    """Index ranges [i, j] of consecutive True."""
    out, i = [], 0
    while i < len(mask):
        if mask[i]:
            j = i
            while j + 1 < len(mask) and mask[j + 1]:
                j += 1
            out.append((i, j))
            i = j + 1
        else:
            i += 1
    return out


def solve_auto(db: sqlite3.Connection) -> dict | None:
    g = gps_track(db)
    if len(g) < 30:
        return None
    t, v, brg = g[:, 0], g[:, 1], g[:, 2]
    ts = t / 1e9
    dvdt = np.gradient(v, ts)
    known = ~np.isnan(brg)
    if known.sum() < 10:
        return None
    brg_f = np.where(known, brg, np.interp(t, t[known], brg[known]))
    hdg_rate = np.abs(np.gradient(np.unwrap(np.radians(brg_f)), ts))
    hdg_rate = np.where(known, hdg_rate, np.inf)

    # bike z from straight steady-cruise windows
    cruise = (v > CRUISE_MIN_MS) & (np.abs(dvdt) < CRUISE_MAX_DVDT) \
        & (hdg_rate < np.radians(CRUISE_MAX_HDG))
    F, wsum, n_windows = np.zeros(3), 0.0, 0
    for i, j in runs(cruise):
        dur = (t[j] - t[i]) / 1e9
        if dur < CRUISE_MIN_S:
            continue
        a = imu_window(db, 0, t[i], t[j])
        if len(a) < 400:
            continue
        F += dur * a[:, 1:4].mean(axis=0)
        wsum += dur
        n_windows += 1
    if n_windows == 0:
        return None
    g_mag = float(np.linalg.norm(F / wsum))
    z_up = F / np.linalg.norm(F)

    # bike x from straight-line acceleration events
    H, events = np.zeros(3), []
    for i, j in runs(dvdt > EVENT_MIN_DVDT):
        if (t[j] - t[i]) / 1e9 < EVENT_MIN_S:
            continue
        d_brg = abs((brg_f[j] - brg_f[i] + 180) % 360 - 180)
        gy = imu_window(db, 1, t[i], t[j])
        if len(gy) == 0 or d_brg > EVENT_MAX_BRG \
                or abs(gy[:, 1:4].mean(axis=0) @ z_up) > EVENT_MAX_YAW:
            continue
        f = imu_window(db, 0, t[i], t[j])[:, 1:4].mean(axis=0)
        h = f - (f @ z_up) * z_up
        hn = float(np.linalg.norm(h))
        if hn < EVENT_MIN_H:
            continue
        H += hn * (h / hn)
        events.append(h / hn)
    if not events:
        return None
    x = H - (H @ z_up) * z_up
    x /= np.linalg.norm(x)
    spread = np.degrees([np.arccos(np.clip(d @ x, -1, 1)) for d in events])

    return {
        "method": "auto",
        "R_phone_to_bike": frame_from(z_up, x).tolist(),
        "g_cruise_ms2": g_mag,
        "n_cruise_windows": n_windows,
        "n_accel_events": len(events),
        "x_spread_median_deg": float(np.median(spread)),
        "x_spread_max_deg": float(np.max(spread)),
    }


# ------------------------------------------------------------- tagged solve (legacy)

def load_segments(db: sqlite3.Connection) -> list[dict]:
    """Pair calib_start/calib_end markers into segments, in ride order."""
    rows = db.execute(
        "SELECT t_ns, kind, note FROM marker WHERE kind IN ('calib_start','calib_end') "
        "ORDER BY t_ns").fetchall()
    segments, open_seg = [], None
    for t_ns, kind, note in rows:
        if kind == "calib_start":
            open_seg = {"note": note, "t0": t_ns}
        elif open_seg is not None and note == open_seg["note"]:
            open_seg["t1"] = t_ns
            segments.append(open_seg)
            open_seg = None
    return segments


def gps_window(db: sqlite3.Connection, t0: int, t1: int) -> np.ndarray:
    rows = db.execute(
        "SELECT t_ns, speed FROM gps WHERE t_ns BETWEEN ? AND ? AND speed IS NOT NULL "
        "ORDER BY t_ns", (t0, t1)).fetchall()
    return np.array(rows, dtype=np.float64).reshape(-1, 2)


def refine_dynamic_window(gps: np.ndarray, kind: str) -> tuple[int, int] | None:
    """Markers only bracket the maneuver; find the actual accel/brake interval."""
    if len(gps) < 3:
        return None
    t, v = gps[:, 0], gps[:, 1]
    dvdt = np.gradient(v, t / 1e9)
    active = dvdt > ACCEL_THRESH if kind == "accel" else dvdt < -ACCEL_THRESH
    if kind == "brake":
        active &= v > BRAKE_TAIL_MS
    if not active.any():
        return None
    idx = np.flatnonzero(active)
    return int(t[idx[0]]), int(t[idx[-1]])


def solve_tagged(db: sqlite3.Connection) -> dict | None:
    segments = load_segments(db)
    sets, cur = [], {}
    for s in segments:
        dur = (s["t1"] - s["t0"]) / 1e9
        if s["note"] == "static_level" and dur >= MIN_STATIC_S:
            cur = {"static_level": s}
        elif s["note"] in ("accel", "brake") and dur >= MIN_DYNAMIC_S and "static_level" in cur:
            cur[s["note"]] = s
            if len(cur) == 3:
                sets.append(cur)
                cur = {"static_level": cur["static_level"]}

    for segs in reversed(sets):  # last complete set wins
        st, ac, br = segs["static_level"], segs["accel"], segs["brake"]
        f_static = imu_window(db, 0, st["t0"], st["t1"])[:, 1:4].mean(axis=0)
        g_mag = float(np.linalg.norm(f_static))
        z_up = f_static / g_mag

        win = refine_dynamic_window(gps_window(db, ac["t0"], ac["t1"]), "accel")
        if win is None or (win[1] - win[0]) / 1e9 < MIN_DYNAMIC_S:
            continue
        f = imu_window(db, 0, *win)[:, 1:4].mean(axis=0)
        accel_h = f - (f @ z_up) * z_up

        consistency = None
        win = refine_dynamic_window(gps_window(db, br["t0"], br["t1"]), "brake")
        if win is not None and (win[1] - win[0]) / 1e9 >= MIN_DYNAMIC_S:
            f = imu_window(db, 0, *win)[:, 1:4].mean(axis=0)
            brake_h = f - (f @ z_up) * z_up
            cos = float(accel_h @ brake_h / (np.linalg.norm(accel_h) * np.linalg.norm(brake_h)))
            if cos > 0:  # brake must oppose accel
                continue
            consistency = float(np.degrees(np.arccos(np.clip(cos, -1, 1))))

        x = accel_h / np.linalg.norm(accel_h)
        gyro = imu_window(db, 1, st["t0"], st["t1"])
        return {
            "method": "tagged",
            "R_phone_to_bike": frame_from(z_up, x).tolist(),
            "g_static_ms2": g_mag,
            "accel_brake_angle_deg": consistency,
            "gyro_bias_static_rads": gyro[:, 1:4].mean(axis=0).tolist(),
        }
    return None


# --------------------------------------------------------------------------- main

def rotation_delta_deg(Ra: np.ndarray, Rb: np.ndarray) -> float:
    return float(np.degrees(np.arccos(np.clip((np.trace(Ra @ Rb.T) - 1) / 2, -1, 1))))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ride_file")
    parser.add_argument("--json", help="write solved calibration to this JSON file")
    args = parser.parse_args()

    db = sqlite3.connect(f"file:{args.ride_file}?mode=ro", uri=True)
    auto = solve_auto(db)
    tagged = solve_tagged(db)
    db.close()

    if auto:
        print(f"auto: {auto['n_cruise_windows']} cruise windows, "
              f"{auto['n_accel_events']} accel events, "
              f"x spread median {auto['x_spread_median_deg']:.1f}° "
              f"max {auto['x_spread_max_deg']:.1f}°, "
              f"|g| cruise {auto['g_cruise_ms2']:.3f} m/s²")
    if tagged:
        line = f"tagged (legacy): |g| static {tagged['g_static_ms2']:.3f} m/s²"
        if tagged["accel_brake_angle_deg"]:
            line += f", accel/brake angle {tagged['accel_brake_angle_deg']:.1f}° (ideal 180)"
        print(line)
    if auto and tagged:
        d = rotation_delta_deg(np.array(auto["R_phone_to_bike"]),
                               np.array(tagged["R_phone_to_bike"]))
        print(f"auto vs tagged cross-check: {d:.2f}° apart "
              "(tagged single-maneuver azimuth is the less reliable of the two, ADR 0004)")

    solution = auto or tagged  # auto preferred per ADR 0004
    if not solution:
        print("no usable calibration — fall back to the most recent solved calibration")
        return 1

    R = np.array(solution["R_phone_to_bike"])
    print(f"\nsolved via {solution['method']} — R_phone→bike (rows = bike x/y/z in phone frame):")
    for row, axis in zip(R, "xyz"):
        print(f"  {axis}: [{row[0]:+.4f} {row[1]:+.4f} {row[2]:+.4f}]")
    pz = R @ np.array([0.0, 0.0, 1.0])
    print(f"phone screen normal in bike frame: [{pz[0]:+.3f} {pz[1]:+.3f} {pz[2]:+.3f}] "
          f"(x=fwd y=left z=up)")

    if args.json:
        with open(args.json, "w") as fh:
            json.dump(solution, fh, indent=2)
        print(f"wrote {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
