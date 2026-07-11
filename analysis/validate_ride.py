#!/usr/bin/env python3
"""Validate a RideLogger ride file (DESIGN.md §10).

Checks: meta completeness, monotonic timestamps, gap analysis, drop stats, GPS rate.
Exit code 0 = pass, 1 = fail. Stdlib only.

Usage: python3 validate_ride.py ride_20260711T093005Z_0a1b2c3d.db [--gap-ms 100] [--drop-pct 0.1]
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys

STREAMS = {0: "accel", 1: "gyro", 2: "mag", 3: "rotvec", 4: "baro"}
# Core IMU streams that must be present and gap-free; rotvec/baro are best-effort.
CORE_STREAMS = (0, 1, 2)

MANDATORY_META = [
    "schema_version",
    "app_version",
    "device",
    "ride_start_utc_ms",
    "clock_anchor",
    "clock_anchor_stop",
    "mount",
    "gnss_raw_supported",
    "dropped_events",
    "ride_end_utc_ms",
    "clean_close",
]

failures: list[str] = []
warnings: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)
    print(f"  FAIL  {msg}")


def warn(msg: str) -> None:
    warnings.append(msg)
    print(f"  warn  {msg}")


def ok(msg: str) -> None:
    print(f"  ok    {msg}")


def check_meta(db: sqlite3.Connection) -> dict[str, str]:
    print("meta:")
    meta = dict(db.execute("SELECT key, value FROM meta"))
    for key in MANDATORY_META:
        if key in meta:
            ok(f"{key} = {meta[key][:80]}")
        elif key in ("clean_close", "ride_end_utc_ms"):
            fail(f"{key} missing — ride was crash-terminated or not closed cleanly")
        else:
            fail(f"{key} missing")
    if meta.get("schema_version") not in (None, "1"):
        warn(f"schema_version {meta['schema_version']} != 1 — validator may be stale")
    for key in ("clock_anchor", "clock_anchor_stop"):
        if key in meta:
            try:
                anchor = json.loads(meta[key])
                if "elapsed_ns" not in anchor or "utc_ms" not in anchor:
                    fail(f"{key} lacks elapsed_ns/utc_ms: {meta[key]}")
            except json.JSONDecodeError:
                fail(f"{key} is not valid JSON: {meta[key]}")
    return meta


def check_imu(db: sqlite3.Connection, meta: dict[str, str], gap_ms: float, drop_pct: float) -> None:
    print("imu:")
    drops = json.loads(meta.get("dropped_events", "{}"))
    for stream, name in STREAMS.items():
        ts = [r[0] for r in db.execute(
            "SELECT t_ns FROM imu WHERE stream=? ORDER BY rowid", (stream,))]
        if not ts:
            (fail if stream in CORE_STREAMS else warn)(f"{name}: no rows")
            continue

        non_monotonic = sum(1 for a, b in zip(ts, ts[1:]) if b < a)
        if non_monotonic:
            fail(f"{name}: {non_monotonic} non-monotonic timestamp steps")

        span_s = (ts[-1] - ts[0]) / 1e9 if len(ts) > 1 else 0.0
        rate = (len(ts) - 1) / span_s if span_s > 0 else 0.0

        gaps = [(b - a) / 1e6 for a, b in zip(ts, ts[1:]) if (b - a) / 1e6 > gap_ms]
        line = f"{name}: {len(ts)} rows, {rate:.1f} Hz over {span_s:.0f} s"
        if gaps and stream in CORE_STREAMS:
            fail(f"{line}, {len(gaps)} gaps > {gap_ms:.0f} ms (max {max(gaps):.0f} ms)")
        elif gaps:
            warn(f"{line}, {len(gaps)} gaps > {gap_ms:.0f} ms (max {max(gaps):.0f} ms)")
        else:
            ok(line)

        dropped = int(drops.get(name, 0))
        if dropped:
            pct = 100.0 * dropped / (dropped + len(ts))
            (fail if pct > drop_pct else warn)(
                f"{name}: {dropped} dropped events ({pct:.3f} %, limit {drop_pct} %)")


def check_gps(db: sqlite3.Connection) -> None:
    print("gps:")
    rows = db.execute(
        "SELECT COUNT(*), MIN(t_ns), MAX(t_ns), SUM(speed_acc IS NOT NULL) FROM gps").fetchone()
    n, t0, t1, with_speed_acc = rows
    if not n:
        fail("no GPS fixes")
        return
    span_s = (t1 - t0) / 1e9 if n > 1 else 0.0
    rate = (n - 1) / span_s if span_s > 0 else 0.0
    (ok if rate >= 0.9 or n < 5 else fail)(f"{n} fixes, {rate:.2f} Hz over {span_s:.0f} s")
    # speed accuracy is the fusion anchor (DESIGN.md §4) — mandatory, not optional
    frac = (with_speed_acc or 0) / n
    (ok if frac > 0.9 else fail)(f"speed_acc populated on {100 * frac:.0f} % of fixes")

    n_status = db.execute("SELECT COUNT(*) FROM gps_status").fetchone()[0]
    ok(f"{n_status} gps_status rows") if n_status else warn("no gps_status rows")
    n_raw = db.execute("SELECT COUNT(*) FROM gnss_raw").fetchone()[0]
    print(f"  info  {n_raw} gnss_raw rows")


def check_markers(db: sqlite3.Connection) -> None:
    print("markers:")
    kinds = dict(db.execute("SELECT kind, COUNT(*) FROM marker GROUP BY kind"))
    print(f"  info  {kinds or 'none'}")
    starts, ends = kinds.get("calib_start", 0), kinds.get("calib_end", 0)
    if starts == 0:
        warn("no calibration segments tagged")
    elif starts != ends:
        fail(f"unbalanced calibration markers: {starts} start / {ends} end")
    else:
        ok(f"{starts} balanced calibration segment(s)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ride_file")
    parser.add_argument("--gap-ms", type=float, default=100.0,
                        help="max tolerated IMU inter-sample gap (default 100)")
    parser.add_argument("--drop-pct", type=float, default=0.1,
                        help="max tolerated drop percentage per stream (default 0.1)")
    args = parser.parse_args()

    db = sqlite3.connect(f"file:{args.ride_file}?mode=ro", uri=True)
    print(f"validating {args.ride_file}\n")
    meta = check_meta(db)
    check_imu(db, meta, args.gap_ms, args.drop_pct)
    check_gps(db)
    check_markers(db)
    db.close()

    print(f"\n{len(failures)} failure(s), {len(warnings)} warning(s)")
    print("PASS" if not failures else "FAIL")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
