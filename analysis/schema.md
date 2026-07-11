# Ride file schema — cross-repo contract

Mirror of `DESIGN.md` §5.2. **Any schema change bumps `schema_version` in `meta` and updates
this file.** Current `schema_version = 1`.

One SQLite database per ride: `ride_<yyyyMMdd'T'HHmmss'Z'>_<8-hex>.db`, WAL mode, insert-only.

## Tables

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

-- one row per IMU event; stream discriminates sensor
CREATE TABLE imu (
  t_ns   INTEGER NOT NULL,          -- SensorEvent.timestamp (elapsedRealtimeNanos, monotonic)
  stream INTEGER NOT NULL,          -- 0=accel 1=gyro 2=mag 3=rotvec 4=baro
  v0 REAL NOT NULL, v1 REAL, v2 REAL,
  b0 REAL, b1 REAL, b2 REAL,        -- bias fields of *_UNCALIBRATED; NULL otherwise
  acc INTEGER                       -- SensorEvent.accuracy
);
CREATE INDEX idx_imu ON imu(stream, t_ns);

CREATE TABLE gps (
  t_ns INTEGER NOT NULL, t_utc_ms INTEGER NOT NULL,
  lat REAL, lon REAL, alt REAL,
  speed REAL, speed_acc REAL, bearing REAL, bearing_acc REAL,
  h_acc REAL, v_acc REAL, provider TEXT
);

CREATE TABLE gps_status (t_ns INTEGER, sats_total INTEGER, sats_used INTEGER);

CREATE TABLE gnss_raw (t_ns INTEGER, svid INTEGER, constellation INTEGER,
                       cn0 REAL, prr REAL, prr_unc REAL);  -- optional table

CREATE TABLE marker (
  t_ns INTEGER NOT NULL,
  kind TEXT NOT NULL,               -- 'user' | 'calib_start' | 'calib_end' | 'note'
  note TEXT
);
```

### Calibration marker semantics (guided hands-free flow, app ≥ 0.2.0)

- Calib segments carry `note` = `static_level` | `accel` | `brake`.
- `calib_start` timestamps may be **backdated up to 2 s** relative to insertion order, to
  cover the 1 Hz GPS detection latency — markers bracket the maneuver, they are not exact.
- Aborted holds / false starts produce **short balanced segments**; the solver must filter
  segments by minimum duration (static ≥ 8 s, accel/brake ≥ 2 s) and prefer the last
  complete set in the ride.
- **Brake segments: trim the low-speed tail.** Riders are told to release the brakes
  while still rolling, but if a brake segment runs down to standstill, the solver must
  discard the part below ~1.5 m/s GPS speed — the bars are typically turned in the last
  moment of a full stop, and the phone sits on the steering assembly.
- A ride **may have no calibration segments at all** (rider skipped it); the solver falls
  back to the most recent solved calibration for the same mount.

## Stream conventions

| stream | sensor | units | v0–v2 | b0–b2 |
|---|---|---|---|---|
| 0 | accel (uncalibrated preferred) | m/s² | x, y, z | factory bias x, y, z (NULL if calibrated fallback) |
| 1 | gyro (uncalibrated preferred) | rad/s | x, y, z | drift/bias x, y, z (NULL if calibrated fallback) |
| 2 | mag (uncalibrated preferred) | µT | x, y, z | hard-iron bias x, y, z (NULL if calibrated fallback) |
| 3 | rotation vector (OS fusion, **baseline only**) | quaternion | x, y, z | b0 = w (scalar), b1 = est. heading accuracy (rad), b2 = NULL |
| 4 | barometer | hPa | v0 = pressure, v1/v2 NULL | NULL |

Raw values exactly as delivered by `SensorManager` — no filtering, smoothing, unit conversion,
resampling, or deduplication is performed on-device.

## `meta` — mandatory keys

| key | notes |
|---|---|
| `schema_version` | `1` |
| `app_version` | e.g. `0.1.0 (1)` |
| `device` | manufacturer, model, Android version |
| `ride_start_utc_ms` | epoch ms |
| `clock_anchor` | JSON `{elapsed_ns, utc_ms, gps_utc_ms}` at start (gps_utc_ms null if no fix yet) |
| `clock_anchor_stop` | same, at stop |
| `mount` | free text mount description |
| `sensor_accel` / `sensor_gyro` / `sensor_mag` / `sensor_rotvec` / `sensor_baro` | name, calibrated/uncalibrated variant, requested rate, measured Hz over first 10 s |
| `gnss_raw_supported` | `true`/`false` |
| `dropped_events` | JSON per stream, written at close |
| `ride_end_utc_ms`, `clean_close` | written at close; **absence ⇒ crash-terminated ride** |

The clock anchors (monotonic-ns ↔ UTC ↔ GPS time, taken twice) are what align IMU and GPS
streams offline and expose clock drift. A ride without them is unusable.
