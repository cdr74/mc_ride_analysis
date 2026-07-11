# CLAUDE.md — RideLogger (Android Sensor Logger MVP)

## What this project is

A **throwaway Android data-collection app** for motorcycle ride telemetry. It logs raw IMU
(accelerometer, gyroscope, magnetometer) at maximum sample rate plus GPS to a per-ride SQLite
file, for **offline sensor-fusion development in Python**. It is NOT the final product.

**Consequences of "throwaway":**
- Optimize for data fidelity and robustness of the logging pipeline, not UI polish or architecture purity.
- No feature that does not serve data collection or field usability.
- The on-device rotation-vector output is logged only as a *comparison baseline* — do not build
  analysis features on it.

The authoritative technical specification is **`DESIGN.md`** in the repo root. Read it before
implementing anything. If code and `DESIGN.md` diverge, `DESIGN.md` wins; update it via a short
ADR note in `docs/adr/` when a decision changes.

## Non-negotiable data-integrity rules

These are the reasons this app exists. Violating any of them makes collected rides worthless.

1. **Never drop or resample sensor events silently.** Log every event delivered by
   `SensorManager`. If the write pipeline can't keep up, count and log drops explicitly
   (`meta` table, `dropped_events` counter) — never block `onSensorChanged`.
2. **Timestamps:** persist `SensorEvent.timestamp` (elapsedRealtimeNanos, monotonic) **unmodified**.
   Never convert to wall clock at write time. Wall-clock and GPS-time mapping is recorded once per
   ride in the `meta` table (see DESIGN.md §5.3).
3. **No filtering, smoothing, or unit conversion on-device.** Raw values, raw units
   (m/s², rad/s, µT), exactly as delivered. Fusion happens offline.
4. **One ride = one SQLite file.** Never append across rides; never mutate a closed ride file.
5. **A ride file must survive process death.** WAL mode, batched transactions ≤ 500 ms apart,
   service crash must lose at most the last uncommitted batch.

## Tech stack & conventions

- **Language:** Kotlin only. JDK 17. Coroutines for concurrency (no RxJava, no threads-by-hand
  except the dedicated `HandlerThread` for sensor callbacks).
- **UI:** Jetpack Compose, single Activity, minimal. Material 3 defaults, no theming work.
- **Min SDK 29, target SDK 35.** Primary test device is the developer's physical phone;
  emulator has no real sensors — do not "verify" sensor behavior on it.
- **Storage:** SQLite via `androidx.sqlite` / raw `SQLiteDatabase` (no Room — schema is fixed and
  performance-critical; hand-written batched inserts, see DESIGN.md §5).
- **Location:** `FusedLocationProviderClient` at highest rate + raw `GnssMeasurements` callback
  where supported (log availability, degrade gracefully).
- **DI:** none. Manual wiring. The app is ~9 small classes; Hilt is overhead.
- **Build:** Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`).

## Commands

```bash
./gradlew assembleDebug          # build APK
./gradlew installDebug           # install on connected device
./gradlew lint testDebugUnitTest # static checks + unit tests
adb shell dumpsys sensorservice  # inspect delivered sensor rates on device
```

**Distribution:** users install the prebuilt APK from GitHub releases, they never build.
After bumping `versionCode`/`versionName`, run tests + `assembleDebug` and publish with
`gh release create v<version> ridelogger-<version>.apk` (process in README.md). Keep
releases debug-signed from the same machine so upgrades keep installing over each other.

## Architecture map (details in DESIGN.md)

```
app/src/main/kotlin/dev/cdr74/ridelogger/
  MainActivity.kt          # Compose UI: start/stop, status, marker, calib card + full-screen calib color overlay
  RideLoggerService.kt     # foreground service; owns session lifecycle
  SensorPipeline.kt        # SensorManager registration, HandlerThread → ring buffer
  GpsPipeline.kt           # fused location + GNSS status logging
  CalibrationGuide.kt      # hands-free calib state machine on GPS speed (ADR 0003)
  RingBuffer.kt            # lock-free SPSC buffer between sensor thread and writer
  RideStore.kt             # SQLite writer: schema, batched transactions, meta table
  RideExporter.kt          # copy closed ride files to Downloads / share sheet
  Config.kt                # all tunables/constants
```

Data flow: sensor callbacks → lock-free ring buffer → single writer coroutine → SQLite (WAL).
UI observes a `StateFlow<SessionStatus>` from the service. No other coupling.

## Testing policy

- **Unit-test:** ring buffer, batch writer (drop counting, transaction boundaries),
  schema creation, exporter file naming. Use in-memory SQLite.
- **Do not attempt to unit-test** `SensorManager`/GPS integration — that is validated by the
  field checklist in DESIGN.md §9 and by the Python-side `validate_ride.py` script.
- Every schema change requires bumping `schema_version` in `meta` and updating
  `analysis/schema.md`.

## Things Claude Code should NOT do here

- Do not add Room, Hilt, Retrofit, Firebase, analytics, or crash reporting.
- Do not implement any on-device fusion (Madgwick/Kalman/lean-angle math). Offline only.
- Do not lower sensor rates "to save battery" — battery cost is accepted for MVP.
- Do not request permissions beyond: `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `HIGH_SAMPLING_RATE_SENSORS`,
  `WAKE_LOCK` (install-time, required by DESIGN.md §6 — see docs/adr/0001).
- Do not "clean up" raw values (clamping, rounding, deduplication).

## Definition of done for the MVP

A 60-minute real ride produces a single ride file that:
1. contains accel/gyro/mag at the device's actual max rate with < 0.1 % counted drops,
2. contains GPS fixes at ≥ 1 Hz with accuracy metadata,
3. contains ≥ 1 calibration segment and user markers,
4. passes `analysis/validate_ride.py` (gap analysis, monotonic timestamps, meta completeness),
5. was recorded with screen off, survived Doze, and exported via the share sheet.
