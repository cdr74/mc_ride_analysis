# DESIGN.md — RideLogger: Android Motorcycle Telemetry Logger (MVP)

Status: Draft 1.1 · Owner: Chris · Last updated: 2026-07-17

> **Direction update (2026-07-12):** the MVP data-collection goal is met, and the
> **ride-display version shipped the same day as v0.3.0** (ADR 0005, UI spec in
> `docs/ui-mockup.md`): calibration is fully automatic from ride phases (ADR 0004 —
> guided flow and the marker concept are gone), live bars + post-ride views are in.
> Field-reviewed 2026-07-17 on two commute rides: lean and speed confirmed good
> (GPS speed cross-checked against position-derived speed to ~1 %; the bike's
> speedometer over-reads ~6–8 %, which is normal); pitch read leaned turns as
> phantom nose-up — fixed in v0.3.3 by Euler pitch kinematics (ADR 0007).
> Since v0.4.x (ADR 0008): displayed pitch is bike-relative-to-road (baro-derived
> grade subtracted), and the road lives in a post-ride-only ELEVATION graph.

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
  *(These MVP non-goals ended with 0.3.0: the offline filter was validated first, then
  ported — phasing in ADR 0005.)*
- No iOS version, no cloud sync, no accounts.
- No map rendering; GPS is logged, not visualized. *(Permanent decision as of ADR 0005:
  a ride is a data series over time, no geo display in any version.)*
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
│    startup checks · live bar display · post-ride views · ride list   │
│         │ StateFlow<SessionStatus> + StateFlow<LiveMetrics>          │
│         ▼                                                            │
│  RideLoggerService (foreground, type=location)                       │
│    ├── SensorPipeline ── HandlerThread ──► RingBuffer ─┐             │
│    │        └─ tap ─► LeanEstimator/AutoCalibrator (live only)       │
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

### 5.2 Schema (schema_version = 2)

v2 change (2026-07-19): `imu` v0–v2/b0–b2 changed from `REAL` to `INTEGER` storing float32
bit patterns (`Float.toBits()`). Sensor values are already float32 from `SensorEvent`; the
previous float64 widening was pure overhead. Read back with `Float.fromBits(cursor.getInt())`.
GPS/GNSS tables unchanged. Saves ~40 % on the dominant table. See `analysis/schema.md` for
the cross-repo reading convention.

```sql
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

-- one row per IMU event; stream discriminates sensor
-- v0-v2, b0-b2: float32 bit patterns as INTEGER (v2). Float.fromBits(cursor.getInt(col)).
CREATE TABLE imu (
  t_ns   INTEGER NOT NULL,          -- SensorEvent.timestamp (elapsedRealtimeNanos)
  stream INTEGER NOT NULL,          -- 0=accel 1=gyro 2=mag 3=rotvec 4=baro
  v0 INTEGER NOT NULL, v1 INTEGER, v2 INTEGER,
  b0 INTEGER, b1 INTEGER, b2 INTEGER,  -- bias fields of *_UNCALIBRATED; NULL otherwise
  acc INTEGER                          -- SensorEvent.accuracy
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
| `schema_version` | `2` |
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
3. **Markers: removed as of 0.3.0** (ADR 0005). The `marker` table stays in schema v1
   for legacy files; new rides contain no rows.
4. **Stop:** unregister sensors → drain ring buffer → final drop counts + second clock anchor +
   `clean_close=true` → checkpoint WAL → close DB → stop foreground.
5. **Crash/kill:** WAL guarantees the file is readable minus the last uncommitted batch;
   missing `clean_close` flags it. Service is `START_STICKY`; on restart it does **not** resume
   the old file — it finalizes what exists and awaits user action.

---

## 7. Calibration (solved offline — automatic from ride phases)

**Decision 2026-07-12 (ADR 0004): calibration is fully automatic.** The phone-to-bike
rotation R_phone→bike is solved in Python from phases the solver detects in any normal
ride — no rider action, no calibration UI, no markers. Field-validated: reproduces to
~1.25° across rides and phone remounts, vs ~7° azimuth uncertainty for the old guided
single-maneuver flow.

**Mount is a handlebar mount (damped).** The phone frame moves with the steering
assembly, not the chassis. The solver therefore only uses phases where the bars are
straight by construction:

1. **Bike z (up):** duration-weighted mean specific force over **straight steady-cruise
   windows** (GPS speed > 8 m/s, |dv/dt| < 0.3 m/s², heading rate < 1°/s, ≥ 5 s).
   Never from stops — bars are typically turned at a standstill (foot down); a
   stop-based estimate was 16° off in field data.
2. **Bike x (forward):** magnitude-weighted mean horizontal specific-force direction over
   **straight-line acceleration events** (GPS dv/dt > 1.2 m/s² for ≥ 2 s, bearing change
   < 10°, mean yaw rate < 0.05 rad/s), re-orthogonalized against z. y = z × x.
3. Fork dive/squat does not rotate the solved axes (the pitch-induced gravity leak is
   collinear with x after the horizontal projection); it only biases maneuver magnitudes,
   which the solver does not use.

The solved R is valid for δ ≈ 0 (steering angle zero); steering-angle coupling is handled
offline (§11), not in the app.

**Fallback chain:** solve from this ride's phases → too few usable phases (short or
slow ride) → reuse the most recent solved calibration for the same mount, at reduced
confidence. A ride with no usable phases is still valid raw data.

**On-device (M6 ride display):** the app persists the last solved calibration and uses
it from ride start (remount repeatability measured at ~1.25°, 2026-07-12), re-solving
opportunistically during the ride. Calibration-dependent live dimensions (lean,
accel/brake, pitch) stay blank on a never-calibrated device until the first solve.

**Legacy (app ≤ 0.2.x):** rides tagged by the guided hands-free flow (ADR 0003 —
`calib_start`/`calib_end` markers with `static_level`/`accel`/`brake` notes) remain
supported; `analysis/calibrate.py` solves from tagged segments and the tagged result
serves as seed/cross-check for the automatic solve. Segment semantics for those files
are in `analysis/schema.md`.

---

## 8. UI (ride display, shipped in 0.3.0)

The authoritative UI spec is **`docs/ui-mockup.md`** (ADR 0005) — approved mockup-first,
implemented exactly; any UI change goes through the mockup before code. In brief:

- **Startup:** "Initializing…" while sensors probe and GPS acquires → the green START
  button appearing is the ready signal; a comprehensive error card (permissions,
  location off, mic-toggle rate cap) with a fix button per issue otherwise.
- **Home:** START, live-slot picker (two chips cycling lean/accel/pitch/speed/off),
  screen-mode choice (live display / background), ride list (tap = review,
  share/save/delete).
- **Live display:** the two chosen dimensions as huge numerals + fill bars with session
  high-watermark ticks; subtle color shift near the session max; full-width STOP.
  Lean blank below 18 km/h (§11). Pitch is bike-relative-to-road — the baro-derived
  road grade is subtracted (ADR 0008). Bars ignore touches while moving.
- **Post-ride:** opens automatically after STOP — all-dimension summary (plus an
  ELEVATION row: baro altitude profile, post-ride only, ADR 0008), tap-through to
  pinch-zoomable traces with tap readout, an adaptive time axis + visible-span label,
  an extremes jump list, and a per-dimension minimum y-span (calm autoscale floor,
  0.3.4) so straight-line wander is not blown up to full screen height.

No settings screen. Constants live in one `Config.kt`; UI choices persist in
SharedPreferences next to where they're used.

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
- `calibrate.py` — solves R_phone→bike: automatically from ride phases (ADR 0004),
  and from tagged calibration segments in legacy (≤ 0.2.x) files.
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
| Steering angle couples into roll/yaw (bar mount, ~24–25° rake) | negligible at speed (δ of a few degrees ⇒ ~1–2° roll error); **large at slow speed / full lock**. Decided 2026-07-12: lean is **not produced below 18 km/h (5 m/s)** anywhere — fusion outputs NaN, displays blank the lean slot/trace; above that speed bar turn is small enough |
| Fork travel (dive/squat) couples into pitch (bar mount) | wheelie signal (10–40°) dominates dive/squat (~2–4°); treat small-pitch analysis as suspension-contaminated |
| Yaw rate projects into body pitch rate in leaned turns (wy = ψ̇·sinφ ⇒ ~Δheading·sin(lean) phantom nose-up per corner) | live estimator integrates the Euler pitch rate θ̇ = −wy·cosφ + wz·sinφ instead of −wy (ADR 0007, v0.3.3; verified on 2026-07-17 commute rides) |
| Magnetometer unusable near engine | expected; mag is logged but fusion must not depend on it |
| Thermal throttling on hot days reduces rate | rate re-measured continuously; gap analysis flags it |
| Phone OIS damage from vibration | use damped mount (documented Apple warning; applies to Android OIS too) |
| GPS speed lag corrupts roll correction | log speed_acc + gnss_raw; handle latency in filter, not in app |
| Accelerometer scale error (Pixel 8 reads gravity −0.6 %; confirmed engine-off 2026-07-12) | normalize by per-ride measured |g| from quiet windows; direction-based math unaffected |

Open: whether to add Bluetooth intake for an external reference unit (RaceBox Mini) into the
same ride file — deferred; would validate but not block the MVP.

---

## 12. Milestones

| # | Deliverable | Exit criterion |
|---|---|---|
| M1 | Logging core (service, pipelines, store) + unit tests | 10-min desk log passes validate_ride.py ✔ |
| M2 | UI, markers, guided hands-free calibration, export | full workflow on device, gloves on ✔ |
| M3 | Field hardening | §9 checklist green on target phone ✔ (2026-07-12) |
| M4 | First instrumented rides + analysis kickoff | rides archived, validated, calibration solved ✔ (2026-07-12, 2 rides / 54.6 km) |
| M5 | Offline fusion validated (`analysis/fusion/`) | roll estimator agrees with rotvec baseline on real rides (speed > 5 m/s mask); wheelie/pitch plausible. *Status 2026-07-12: four estimators cross-agree (max lean ±23–28° both rides, Madgwick↔rotvec 1.8–2.2° RMS); causal live variant costs 0.35° RMS / 40 ms; pending rider corner-validation; wheelie data outstanding (needs the supermoto dataset)* |
| M6 | Ride-display app version (`docs/ui-mockup.md`, ADR 0004/0005) | post-ride view + live bars on device; guided calib & markers removed. ✔ implemented + released v0.3.0 (2026-07-12); field-reviewed 2026-07-17 (2 commute rides): lean & speed confirmed, pitch turn-coupling found and fixed in v0.3.3 (ADR 0007). **Remaining: pitch wheelie-band validation (supermoto data)** |
