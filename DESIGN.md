# DESIGN.md — RideLogger: Android Motorcycle Telemetry Logger (MVP)

Status: Draft 1.0 · Owner: Chris · Last updated: 2026-07-11

---

## 1. Purpose & scope

### 1.1 Goal

Collect **research-grade raw sensor data** from a phone rigidly mounted on a motorcycle
(Yamaha MT-07), sufficient to develop and validate offline sensor-fusion algorithms for:

- longitudinal acceleration / deceleration
- lean (roll) angle
- wheelie (pitch) angle

The app is a **data logger only**. All fusion, calibration solving, and metric computation
happens offline in Python against the exported ride files.

**Target device: Google Pixel 8** (stock Android, currently 16). Implications: no OEM
background-killer; dual-frequency GNSS (L1+L5) with full raw `GnssMeasurements` support —
the `gnss_raw` table is expected to populate; barometer present. Design remains
device-agnostic, but the §9 checklist is validated against this device.

### 1.2 Non-goals (MVP)

- No on-device fusion, lean-angle display, or live telemetry.
- No iOS version, no cloud sync, no accounts.
- No map rendering; GPS is logged, not visualized.
- No battery optimization beyond "don't be stupid" (accepted cost: ~15–25 %/h).

### 1.3 Why raw logging first

Fusion filters (Madgwick/Mahony/EKF, GPS-aided roll estimation) need tuning against real data.
Logging raw streams lets every filter iteration be re-run over the same rides. The final
cross-platform app is built only after the offline filter is validated.

---

## 2. System overview

```
┌─────────────────────────── Android device ───────────────────────────┐
│                                                                      │
│  MainActivity (Compose)                                              │
│    start/stop · marker button · live status (rates, GPS fix, drops)  │
│         │ StateFlow<SessionStatus>                                   │
│         ▼                                                            │
│  RideLoggerService (foreground, type=location)                       │
│    ├── SensorPipeline ── HandlerThread ──► RingBuffer ─┐             │
│    ├── GpsPipeline    ── main looper cb ──────────────►│             │
│    └── RideStore ◄── writer coroutine (drain ≤500 ms) ─┘             │
│              │                                                       │
│              ▼                                                       │
│  /files/rides/ride_<utc-iso>_<id>.db   (SQLite, WAL)                 │
│              │  RideExporter                                         │
│              ▼                                                       │
│  Downloads / share sheet ──► workstation (Python analysis repo)      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. Sensor acquisition

### 3.1 Sensors and rates

| Stream  | Sensor type constant              | Requested rate         | Notes                                   |
|---------|-----------------------------------|------------------------|-----------------------------------------|
| accel   | `TYPE_ACCELEROMETER_UNCALIBRATED` | `SENSOR_DELAY_FASTEST` | fall back to `TYPE_ACCELEROMETER` if absent |
| gyro    | `TYPE_GYROSCOPE_UNCALIBRATED`     | `SENSOR_DELAY_FASTEST` | includes drift/bias estimates            |
| mag     | `TYPE_MAGNETIC_FIELD_UNCALIBRATED`| `SENSOR_DELAY_FASTEST` | expect heavy engine/harness distortion — logged anyway |
| rotvec  | `TYPE_ROTATION_VECTOR`            | `SENSOR_DELAY_GAME`    | OS fusion baseline for comparison only   |
| baro    | `TYPE_PRESSURE`                   | `SENSOR_DELAY_NORMAL`  | optional; elevation cross-check          |

- Declare `HIGH_SAMPLING_RATE_SENSORS` permission (required ≥ API 31 for >200 Hz).
- Prefer **uncalibrated** variants: they expose factory bias estimates separately and do not
  apply the OS's opaque runtime calibration — better for offline work. Log which variant was
  actually used in `meta`.
- Record the **actual delivered rate** per stream (computed over the first 10 s) into `meta`;
  devices differ (typ. 100–500 Hz).

### 3.2 Callback threading

- One dedicated `HandlerThread` (`THREAD_PRIORITY_URGENT_AUDIO`) for all sensor listeners.
- `onSensorChanged` does exactly: copy values + timestamp into a pre-allocated slot of a
  **single-producer/single-consumer ring buffer** (fixed capacity, e.g. 2^16 samples), increment
  drop counter on overflow. No allocation, no I/O, no locks in the callback.

### 3.3 Batching

Do **not** use hardware FIFO batching (`maxReportLatencyUs > 0`) in the MVP: it complicates
timestamp reasoning and some OEMs misbehave. Continuous delivery + ring buffer is sufficient.

---

## 4. GPS / GNSS

- `FusedLocationProviderClient`, `PRIORITY_HIGH_ACCURACY`, interval 0 (max rate; typically 1 Hz).
- Log per fix: elapsedRealtimeNanos, unix time ms, lat, lon, altitude, speed (m/s),
  speedAccuracy, bearing, bearingAccuracy, horizontal/vertical accuracy, provider.
- Register `GnssStatus.Callback`: log satellite count / used-in-fix count at 1 Hz into `gps_status`.
- **Raw GNSS measurements** (`registerGnssMeasurementsCallback`): if supported, log
  availability flag in `meta` and store raw pseudorange rows in `gnss_raw` — cheap to collect,
  potentially valuable later (10 Hz velocity). Degrade silently if unsupported.
- GPS speed and bearing are the fusion anchor for the offline roll estimate
  (φ ≈ atan(v·ψ̇/g)); speed accuracy metadata is therefore mandatory, not optional.

---

## 5. Storage design

### 5.1 File layout

- One SQLite database per ride: `rides/ride_<yyyyMMdd'T'HHmmss'Z'>_<8-hex>.db`
- `PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;`
- Writer coroutine drains the ring buffer and commits a transaction every ≤ 500 ms or
  ≥ 4096 rows, whichever first. Target sustained write load: ~1500 rows/s ≈ trivial for SQLite.

### 5.2 Schema (schema_version = 1)

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

-- one row per IMU event; stream discriminates sensor
CREATE TABLE imu (
  t_ns   INTEGER NOT NULL,          -- SensorEvent.timestamp (elapsedRealtimeNanos)
  stream INTEGER NOT NULL,          -- 0=accel 1=gyro 2=mag 3=rotvec 4=baro
  v0 REAL NOT NULL, v1 REAL, v2 REAL,
  b0 REAL, b1 REAL, b2 REAL,        -- bias fields of *_UNCALIBRATED; NULL otherwise
  acc INTEGER                        -- SensorEvent.accuracy
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
  kind TEXT NOT NULL,                -- 'user' | 'calib_start' | 'calib_end' | 'note'
  note TEXT
);
```

No foreign keys, no updates, insert-only. Rotvec rows use v0–v2 = x,y,z quaternion components
plus `b0` = w (scalar), `b1` = estimated heading accuracy.

### 5.3 `meta` table — mandatory keys

| key | example |
|---|---|
| `schema_version` | `1` |
| `app_version` | `0.3.0 (git 1a2b3c4)` |
| `device` | `Google Pixel 8, Android 15` |
| `ride_start_utc_ms` | `1752230400123` |
| `clock_anchor` | JSON: `{elapsed_ns, utc_ms, gps_utc_ms}` captured at start (`gps_utc_ms` null if no fix yet) |
| `clock_anchor_stop` | same JSON, captured at stop |
| `sensor_accel` | `LSM6DSO uncalibrated, requested FASTEST, measured 417.2 Hz` |
| `sensor_gyro` / `sensor_mag` / ... | same pattern |
| `gnss_raw_supported` | `true/false` |
| `dropped_events` | written at close, per stream |
| `mount` | free text describing the actual mount, e.g. `SP Connect bar mount (+damping module y/n), top forward, screen tilted up ~TFT angle, bars straight at calib`. Orientation is unconstrained (calibration solves R_phone→bike); the description just has to match reality. |
| `ride_end_utc_ms`, `clean_close` | written at close; absence ⇒ crash-terminated ride |

The **clock anchor** (monotonic-ns ↔ UTC ↔ GPS time, taken twice) is what lets Python align
IMU and GPS streams and detect clock drift. Without it a ride is unusable — write it before
the first sensor row.

---

## 6. Session lifecycle & robustness

1. **Start:** UI → `startForegroundService`. Service acquires partial wakelock, creates ride
   file, writes meta + first clock anchor, registers sensors, starts GPS, posts persistent
   notification (elapsed time, GPS fix state, drop count).
2. **Running:** screen may be off. Service type `location` + wakelock + FASTEST sensor
   registration keeps delivery alive through Doze; verify on the target device (§9).
3. **Marker:** UI button and notification action insert a `marker(kind='user')` row —
   pressed at ride events worth finding later (wheelie attempt, specific corner).
4. **Stop:** unregister sensors → drain ring buffer → final drop counts + second clock anchor +
   `clean_close=true` → checkpoint WAL → close DB → stop foreground.
5. **Crash/kill:** WAL guarantees the file is readable minus the last uncommitted batch;
   missing `clean_close` flags it. Service is `START_STICKY`; on restart it does **not** resume
   the old file — it finalizes what exists and awaits user action.

---

## 7. Calibration procedure (data protocol, solved offline)

The phone-to-bike rotation matrix is solved in Python from tagged segments. The app only
provides guided tagging that inserts `calib_start`/`calib_end` markers — **hands-free**
(ADR 0003): the rider presses one button while stationary; every later phase transition
(still → hard accel → hard brake) is detected from GPS speed with simple thresholds
(constants in `Config.kt`) and announced with a beep **and a full-screen color cue**
(blue = hold still, green = accelerate, orange = brake, red flash = retry, dark green =
done; the screen is forced on at full brightness for the duration of the run, since
engine/wind noise often masks the speaker). The rider never touches the phone while the
bike is moving and only needs a peripheral glance at the screen color, never a read.
`calib_start` markers are backdated 2 s to cover the
1 Hz detection latency; markers only need to *bracket* the maneuvers — the offline solver
extracts exact segments. This is UI guidance logic only, not on-device fusion.

**Mount is a handlebar mount (damped).** The phone frame therefore moves with the steering
assembly, not the chassis. All calibration steps MUST be performed with the bars dead
straight (front wheel aligned with the frame); the calibration UI text states this explicitly.
The solved R_phone→bike is valid for δ ≈ 0 (steering angle zero); steering-angle coupling is
handled offline (§11), not in the app.

1. **Static level:** bike upright on level ground (center stand / held), bars straight, 10 s
   stationary → gravity vector in phone frame → bike z-axis.
2. **Straight-line accel:** brisk acceleration in a straight line, ~5 s
   → horizontal specific-force direction → bike x-axis (y = z × x).
   Moderate is enough — the detector triggers at 2 m/s²; steady and straight beats strong.
3. **Straight-line brake:** confirms x-axis sign and quality. Released while still
   rolling, **not** braked to a standstill — at a full stop the bars typically get
   turned (foot down), and the phone sits on the steering assembly. The solver
   additionally trims the sub-walking-speed tail of brake segments (see
   `analysis/schema.md`).

Recommended at the start of every ride, **mandatory after remounting the phone** (mount
position varies slightly between remounts). Calibration is never enforced by the app: a
ride without calibration segments is still valid — the offline solver
(`analysis/calibrate.py`) computes R_phone→bike per ride where segments exist and falls
back to the most recent solved calibration for rides without them, at reduced confidence.
The solver discards segments that are too short (aborted holds, false starts).

---

## 8. UI (deliberately minimal)

Single screen, Compose:

- Big **Start / Stop** button (large touch target, glove-friendly).
- Status block: per-stream measured Hz, GPS fix + accuracy + sat count, dropped events,
  elapsed time, file size.
- **Marker** button (full-width, high contrast).
- **Calibrate** — one start/cancel button; the hands-free flow of §7 runs on its own,
  beeping on every phase transition and taking over the whole screen with one solid
  color + one huge word per phase (readable at a glance, §7). Screen kept on at full
  brightness only while calibration runs.
- Ride list: closed rides with size/duration, share + delete actions.

No settings screen in MVP. Constants live in one `Config.kt`.

---

## 9. Field validation checklist (per new device / OS update)

- [ ] Measured accel/gyro rate ≥ 100 Hz with screen off for 30 min (check `meta` + gap analysis)
- [ ] No sensor gap > 100 ms during Doze (drive or use `adb shell cmd deviceidle force-idle` walking test)
- [ ] GPS fixes ≥ 1 Hz outdoors, speed_acc populated
- [ ] Process-kill test mid-ride: file opens, data present up to last batch, `clean_close` absent
- [ ] 60-min ride: battery drain noted, thermal throttling check (rate stable over time)
- [ ] PSD of idle + steady-cruise segments: locate SP Connect damper resonance peak; confirm it sits well above the <10 Hz fusion band
- [ ] Export → Python `validate_ride.py` passes

---

## 10. Companion analysis repo (out of app scope, defined here for the contract)

`ridelogger-analysis/` (Python, uv + NumPy/SciPy/pandas/matplotlib):

- `schema.md` — mirror of §5.2; the cross-repo contract. Any schema change bumps
  `schema_version` and updates this file.
- `validate_ride.py` — gap analysis, monotonic timestamps, meta completeness, drop stats.
- `calibrate.py` — solves R_phone→bike from calibration segments.
- `fusion/` — Madgwick/Mahony ports, GPS-aided roll estimator (φ ≈ atan(v·ψ̇/g) correction),
  comparison against logged rotvec baseline.
- `report.py` — per-ride HTML: accel/brake histogram, lean-angle trace, candidate wheelie events.

---

## 11. Risks & open questions

| Risk | Mitigation |
|---|---|
| Mic privacy toggle OFF silently caps sensors at ≤200 Hz (Android 12+, overrides HIGH_SAMPLING_RATE_SENSORS) | check `SensorPrivacyManager` / warn in UI if measured rate ≪ expected; keep mic toggle on during rides |
| OEM throttles sensors with screen off | wakelock + foreground; field checklist §9; fallback: keep screen on dim. (Target device Pixel 8: stock Android, no OEM killer — low risk) |
| Vibration aliasing / mount resonance | **bar mount = worst case on the CP2 twin**: damped mount mandatory; log mount description; inspect PSD offline before trusting any ride |
| Steering angle couples into roll/yaw (bar mount, ~24–25° rake) | negligible at speed (δ of a few degrees ⇒ ~1–2° roll error); **large at slow speed / full lock** — offline: detect via low GPS speed + gyro/GPS-roll disagreement and mask; do NOT trust lean angle in slow-speed segments |
| Fork travel (dive/squat) couples into pitch (bar mount) | wheelie signal (10–40°) dominates dive/squat (~2–4°); treat small-pitch analysis as suspension-contaminated |
| Magnetometer unusable near engine | expected; mag is logged but fusion must not depend on it |
| Thermal throttling on hot days reduces rate | rate re-measured continuously; gap analysis flags it |
| Phone OIS damage from vibration | use damped mount (documented Apple warning; applies to Android OIS too) |
| GPS speed lag corrupts roll correction | log speed_acc + gnss_raw; handle latency in filter, not in app |

Open: whether to add Bluetooth intake for an external reference unit (RaceBox Mini) into the
same ride file — deferred; would validate but not block the MVP.

---

## 12. Milestones

| # | Deliverable | Exit criterion |
|---|---|---|
| M1 | Logging core (service, pipelines, store) + unit tests | 10-min desk log passes validate_ride.py |
| M2 | UI, markers, guided hands-free calibration, export | full workflow on device, gloves on |
| M3 | Field hardening | §9 checklist green on target phone |
| M4 | First instrumented rides + analysis kickoff | 3 rides with calibration segments archived |
