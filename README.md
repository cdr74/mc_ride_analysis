# RideLogger — motorcycle ride telemetry from a bar-mounted phone

RideLogger shows you **how you actually ride**. Clamp your phone to the handlebars,
press START, and ride: afterwards (or live, at a glance) you see your real lean
angles, braking and acceleration, wheelie pitch, speed, and the elevation profile of
the route. No external sensors, no cloud, no account, no map tracking — everything is
measured and stored on the phone, and a ride is *your* data series over time.

Under the hood it is a data-logging research project: the phone records
research-grade raw sensor data, the estimators were developed and checked offline in
Python against real rides, and only that math runs in the app. The numbers are
cross-checked between independent estimation methods and physical sanity checks —
honestly though, never against dedicated reference hardware (a RaceBox-style
reference unit is an open item), so treat them as good estimates, not certified
measurements (details below).

---

## For riders

### Install

1. On the **phone's browser**, open the
   [latest release](https://github.com/cdr74/mc_ride_analysis/releases/latest) and
   download `ridelogger-<version>.apk`.
2. Open the download and allow the install (sideload). Android 10+; the development
   device is the Pixel 8. Updates install straight over the previous version.
3. No build tools, no Play Store, no sign-up.

**Phones differ.** All measurement quality depends on the phone's sensors: rates and
noise vary a lot between models, and not every phone has a barometer — without one
the ELEVATION graph disappears and pitch shows hills instead of being road-relative.
Everything documented here (400 Hz IMU, the accuracy figures) is from the Pixel 8;
on other hardware the app degrades gracefully but the numbers come with less
confidence.

### Ride

1. Mount the phone on the bars (a **damped mount** matters — engine vibration is the
   enemy), start the app, stand under open sky.
2. Pick up to **two live bars** for the ride (lean, accel/brake, pitch, speed — or
   none and ride with the screen off).
3. When the green **START** appears, the app is ready. Ride. Tap **STOP** at the end.
4. The post-ride view opens by itself: distance, extremes, and a zoomable trace for
   every dimension — pinch into any corner of your ride.

**There is no calibration procedure.** The phone can sit on the mount at any angle;
the phone→bike orientation is solved automatically from normal riding and remembered
across rides. On the very first ride the bars show "calibrating…" for a few minutes,
then everything works by itself.

### What the numbers mean

- **LEAN** — your lean angle; four independent estimation methods agree on it within
  a few degrees. Blank below 18 km/h (bar movement corrupts it there — by design,
  not a bug).
- **ACCEL / BRAKE** — longitudinal force in m/s², positive forward.
- **PITCH** — what the *bike* does relative to the *road*: fork dive, squat,
  wheelies. Hills are subtracted using the barometer, so this bar should be boring —
  until it isn't.
- **SPEED** — GPS speed. If it reads ~5 km/h below your speedometer, the app is
  right: speedometers are required by law to never read low.
- **ELEVATION** (post-ride only) — the route's altitude profile, the "road" half of
  the pitch split.

The full field manual — screen-by-screen usage, exporting rides to a PC,
troubleshooting — is **[USER.md](USER.md)**.

---

## Under the hood

### Project phases (ADR 0005)

1. **✅ Raw logger** — never drops or alters a sample.
2. **✅ Offline fusion** (`analysis/`) — calibration + lean estimation, validated on
   real rides; the causal live variant costs 0.35° RMS / 40 ms vs the offline
   reference. Pitch/wheelie validation still awaits real wheelie data.
3. **✅ Ride display** (v0.4.x, spec in `docs/ui-mockup.md`) — live bars with session
   high-watermarks, post-ride analysis in-app, zero interaction while moving. The
   on-device estimator is a verified port of the offline reference (RMS 0.19° on a
   real-ride fixture), field-reviewed and refined on real rides (ADR 0007/0008).
4. *Parked:* a minimal iOS logger writing the same ride-file format (wheelie data
   from a second bike).

`DESIGN.md` is the authoritative spec, `CLAUDE.md` has the working rules,
`docs/adr/` records every decision.

### What gets recorded

One SQLite file per ride (schema in `analysis/schema.md`), written by a foreground
service built around five data-integrity rules (see `CLAUDE.md`): every sensor event
is logged or its loss is *counted*, timestamps are the monotonic
`elapsedRealtimeNanos` exactly as delivered, values are raw (no filtering, rounding,
or unit conversion on-device), one ride = one immutable file, and the file survives
process death (WAL, ≤ 500 ms batches).

| stream | source | rate (Pixel 8) | why |
|---|---|---|---|
| accel / gyro / mag | *uncalibrated* variants + factory bias fields | 400 / 400 / 100 Hz | the fusion inputs, free of opaque OS runtime calibration |
| rotation vector | Android's own fusion | 50 Hz | comparison baseline only — never used as input |
| barometer | raw pressure | ~12 Hz | road grade + elevation profile |
| GPS | fused location: speed + accuracy, position, bearing | 1 Hz | speed is the fusion anchor |
| GNSS raw | pseudorange rates, C/N0 | when supported | future 10 Hz velocity option |

Clock anchors (monotonic ↔ UTC ↔ GPS time, taken at start and stop) make the streams
alignable offline and expose clock drift. `analysis/validate_ride.py` gates every
ride: gap analysis, monotonicity, drop stats, meta completeness — a ride that fails
is not analyzed.

### How the metrics are computed

**Calibration** (`analysis/calibrate.py`, ADR 0004) — everything starts with solving
the rotation R_phone→bike, automatically from ride phases: *bike-up* is the mean
specific force over straight steady-cruise windows (never at stops — the phone sits
on the steering assembly and bars turn at a standstill), *bike-forward* is the
horizontal specific-force direction during straight-line accelerations. Measured
repeatability: ~1.25° across rides **and phone remounts**. The app persists the last
solution so live metrics work from the first second of a ride.

**Lean angle** (`analysis/fusion/`) — the hard one. Gravity-based orientation filters
(Madgwick, and Android's own rotation vector) systematically under-read lean in
corners: in a coordinated turn the specific force aligns with the *bike's* vertical —
that is why bikes lean — so gravity looks centered. The information that makes lean
observable is kinematic: a coordinated corner satisfies **φ = atan(v·ψ̇ / g)** (GPS
speed × yaw rate). The production estimator is a complementary filter: integrate the
gyro roll rate (instantaneous), continuously correct toward the kinematic lean
(band-limited to 1.5 Hz — sub-second yaw spikes from bumps are not lean) while
moving, toward gravity when nearly stopped. Lean is **never reported below 18 km/h**,
where bar-mount steering coupling corrupts it. Checked on real rides: four
independent estimators agree on max lean within a few degrees, and the causal
(real-time-capable) variant tracks the offline reference to 0.35° RMS with ~40 ms
lag. That is agreement *between methods* on the same data — no comparison against
external reference instrumentation has been done (deferred, DESIGN.md §11).

**Acceleration / braking** — longitudinal specific force in the bike frame (m/s², raw
unit), low-passed below the 22–105 Hz engine-vibration band (measured: < 3 % of
vibration power falls inside the < 10 Hz dynamics band on the damped mount).

**Pitch / wheelie** — gravity/gyro pitch in the bike frame, integrated with Euler
kinematics so leaned turns don't read as phantom nose-up (ADR 0007), and displayed
**relative to the road**: the grade estimated from the barometer (climb rate ÷ GPS
speed) is subtracted, so the channel shows dive/squat/wheelies, not hills (ADR 0008).
Fork dive and squat contaminate the small-pitch range (~2–4°), so only the wheelie
band (10–40°) is treated as signal. Awaits real wheelie data (phase 4).

**Elevation** — smoothed barometric altitude relative to the ride start (post-ride
graph only).

**Speed** — GPS, with per-fix accuracy logged. Cross-checked against
position-derived speed to ~1 %.

### Repo layout

- `app/` — Android app (Kotlin, Compose, min SDK 29)
- `analysis/schema.md` — ride-file schema, the app↔Python contract
- `analysis/validate_ride.py` — ride-file validation (gaps, monotonicity, meta, drops)
- `analysis/calibrate.py` — automatic phone→bike calibration (tagged segments in legacy files)
- `analysis/fusion/` — offline estimators: Madgwick port, GPS-aided lean, causal ride-display reference
- `docs/adr/` — decision records
- `docs/ui-mockup.md` — approved UI spec for the ride-display version
- `data/` — local ride files + solved calibrations (gitignored, ~100 MB+ per ride)

### Develop

```bash
./gradlew assembleDebug          # build APK
./gradlew installDebug           # install on connected device
./gradlew lint testDebugUnitTest # static checks + unit tests
```

CI (`.github/workflows/android.yml`) runs the same lint + unit tests + debug build on
every push and PR to `main` and uploads the APK as a build artifact.

### Cut a release

Bump `versionCode`/`versionName` in `app/build.gradle.kts`, commit (docs in sync —
see `CLAUDE.md`), then tag and push:

```bash
git tag v<version>
git push origin main v<version>
```

CI builds the APK, verifies the tag matches `versionName`, and creates the GitHub
release with `ridelogger-<version>.apk` attached (ADR 0006). Edit the auto-generated
release notes on GitHub afterwards if needed.

Releases ship the debug-signed APK (no Play signing). CI signs with the shared debug
keystore stored in the `DEBUG_KEYSTORE_BASE64` repo secret — the same key as local
`installDebug` builds — so the APK signature stays stable across updates and installs
over locally built versions. If the keystore is ever regenerated, re-upload it
(`gh secret set DEBUG_KEYSTORE_BASE64 --body "$(base64 -w0 ~/.android/debug.keystore)"`)
and note that users must uninstall/reinstall once.

### Validate an exported ride

```bash
python3 analysis/validate_ride.py ride_20260711T093005Z_0a1b2c3d.db
```
