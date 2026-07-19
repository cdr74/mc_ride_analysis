# RideLogger — field manual

How to install the app, record a ride with the live display, review it, and get the
data onto your PC. Calibration is automatic — there is nothing to calibrate by hand.
Target device: Google Pixel 8 (works on any Android 10+ phone, but the field checklist in
`DESIGN.md` §9 is only validated against the Pixel 8).

---

## 1. Install the app

You do **not** build anything. Ready-made APKs are published as GitHub releases.

### 1.1 Download the APK

On the **phone's browser**, open:

> **https://github.com/cdr74/mc_ride_analysis/releases/latest**

and download the `ridelogger-<version>.apk` asset. (You can also download it on the PC
and transfer it to the phone via Drive or USB — but downloading directly on the phone is
the shortest path.)

### 1.2 Install (sideload)

1. Open the downloaded APK from the browser's download notification or the **Files** app.
2. Android asks to allow "install unknown apps" for that app the first time — allow it.
3. If **Play Protect** warns about an unknown developer, choose *Install anyway* — the
   APK comes from this repo's releases, there is no Play Store listing for a throwaway
   research app.

**Updating to a newer release:** install the new APK over the old one; rides are kept.
If Android ever refuses with a signature error, **export all rides first** (§4) — 
uninstalling deletes the app's private storage including any rides not yet exported.

### 1.3 Alternative for developers: install from source

Only needed if you are changing the app. With JDK 17+, Android SDK 35 and USB debugging
enabled on the phone:

```bash
./gradlew installDebug     # or: adb install app/build/outputs/apk/debug/app-debug.apk
```

WSL2 has no USB passthrough — use adb over Wi-Fi (*Wireless debugging* in Developer
options, then `adb pair` + `adb connect`) or run adb from Windows.

### 1.4 First launch — permissions and settings

1. Open **RideLogger**. On first launch the app shows a **"Can't record yet"** card
   listing everything it needs, each with a fix button — grant:
   - **Location → While using the app**, and make sure **Precise** is selected.
   - **Notifications** (Android 13+) — needed for the recording notification.
2. **Critical — microphone privacy toggle:** on Android 12+ the system-wide mic toggle
   being OFF silently caps *all* sensors at 200 Hz, killing the high-rate IMU logging.
   The startup check detects this and shows it as a blocking issue with a settings
   shortcut (Settings → Security & privacy → Privacy controls → **Microphone access = ON**).
3. Battery: nothing to configure. The app runs a foreground service with a wakelock;
   expect roughly 15–25 %/h drain while recording. Do not enable any battery saver that
   restricts background sensors during a ride.

---

## 2. Mount the phone

The calibration and all offline analysis assume the mount from `DESIGN.md` §7:

- **Damped handlebar mount** (e.g. SP Connect + anti-vibration module). A damped mount is
  mandatory — engine vibration can otherwise alias into the data and can physically damage
  the phone camera's OIS.
- **Orientation is free** — any fixed angle works. The per-ride calibration (§3.2) solves
  the phone→bike rotation from data, so a dash-style tilt (top pointing forward, screen
  angled up like the bike's TFT display) is perfectly fine and even helps GPS reception
  and glanceability. Vertical portrait is *not* required.
- What **is** required: clamped rigidly — the phone must not shift on the mount during the
  ride. A shifted phone invalidates that ride's calibration.
- The mount description in `Config.MOUNT_DESCRIPTION`
  (`app/src/main/kotlin/dev/cdr74/ridelogger/Config.kt`) is written into every ride file
  and read by the analysis — if your physical setup changes, update it (developer change,
  needs a new release).

---

## 3. Record a ride

### 3.1 Start

1. Mount the phone, start the bike, stand somewhere with open sky.
2. On the home screen, pick what you want while riding:
   - **LIVE DISPLAY** — up to two dimensions shown as live bars during the ride:
     **LEAN**, **ACCEL/BRAKE**, **PITCH**, **SPEED** (tap a chip to cycle).
     Each chip shows a colored dot — orange for lean, blue for speed, green for
     accel, teal for pitch — so you never need to read the label.
   - **CAPTURE** — *live* (screen stays on, showing the bars) or
     *background* (screen off, app minimizes; review everything after the ride).
3. The app shows **"Initializing …"** while sensors spin up and GPS acquires — the
   deep-green **START** button appearing *is* the ready signal. If something is
   wrong (permission, location off, sensor rate capped) you get an error card with a
   fix button instead. Tap **START** and ride.
4. **Theme:** the app defaults to dark (follows system dark mode). Tap ☾/☀ in the
   top-right corner of the home screen to lock it dark, lock it light, or return to
   system-follow.

### 3.2 Calibration — automatic, nothing to do

Since 0.3.0 there is **no calibration procedure**. The phone→bike orientation is solved
automatically from normal riding (steady cruising gives "up", straight accelerations
give "forward" — `docs/adr/0004-automatic-calibration.md`). What that means in practice:

- **Normally:** the app reuses the calibration from your previous rides from the first
  second — remounting the phone shifts it by only ~1°, which is fine.
- **Very first ride (or after reinstalling):** the lean / accel / pitch bars show
  "calibrating…" for the first minutes of riding, then start working by themselves once
  the app has seen enough steady cruising and a couple of straight accelerations.
  Speed always works.
- Riding normally is all it takes. No buttons, no colors, no maneuvers on command.

### 3.3 Ride

- **Live display mode:** the screen stays on and shows your two chosen dimensions.
  Each dimension fills its half of the screen: a large numeral in the dimension's
  identity color, and below it an **LED-strip bar** — solid color-blocks, no gaps,
  like a rev counter.

  - The **◆ watermark** marks your session maximum for that dimension; it never
    retreats during the ride.
  - As you approach your own max the bar shifts **amber** (past 70 %) then **red**
    (past 90 %) — a visual warning without any sound or vibration.
  - **Lean shows "—" below 18 km/h** — intentional (the bar-mounted phone turns
    with the bars at low speed; the reading would be garbage above ~15°).

- **Background mode:** the screen is off, logging continues (foreground service +
  wakelock). Opening the app during the ride shows the live display.
- Touching the bars does nothing while moving; only STOP is active.
- The notification shows elapsed time and the drop count. Drops should stay at 0.

### 3.4 Stop & review

Tap **STOP** (bottom of the live display). The app closes the ride file and opens the
**post-ride summary** automatically: distance, duration, and every dimension with its
session extremes, plus an **ELEVATION** row — the ride's altitude profile from the
phone's barometer (post-ride only, meters relative to the start). Tap a dimension for
the full trace over time — pinch to zoom, drag to pan, tap for the value at that
moment; time gridlines label the x-axis and the hint line shows how much of the ride
is on screen ("… 2 min 30 s shown"); the EXTREMES list jumps straight to the deepest
lean or hardest braking. The y-scale fits the visible window but never zooms tighter
than a per-dimension floor (lean ±15°), so the small steering wander every bike has on
a straight doesn't get blown up to look dramatic.

**Pitch is measured against the road, not the horizon** (0.4.0): the road's slope is
subtracted using the barometer, so riding up an 8 % hill shows ~0° — the pitch bar and
trace show only what the *bike* does: fork dive under braking, squat when accelerating,
wheelies. The road itself is in the ELEVATION graph instead. The first open of a ride replays all of its raw data once and
shows "computing…" with a progress percentage — expect ~10–30 s for a long ride — then
the result is cached and every later open is instant. Rides remain in the **Rides** list.

If the app or phone died mid-ride: the file is still readable up to the last committed
batch (≤ 0.5 s of data lost). The validator flags such rides as crash-terminated —
they're usable, just note the missing tail.

---

## 4. Get the data onto the PC

Each ride is one SQLite file, e.g. `ride_20260711T093005Z_0a1b2c3d.db` (UTC start time +
random id). Two ways off the phone, both in the **Rides** list:

### Option A — Share sheet (no cable)

Tap **Share** next to the ride → pick Drive / Gmail / Nearby Share / anything that accepts
a file → download it on the PC. Fine for single rides; a 60-min ride is roughly
50–150 MB.

### Option B — Save to Downloads + USB (best for regular use)

1. Tap **Save** next to the ride → it is copied to **Downloads/RideLogger/** on the phone.
2. On the PC, either:
   - plug in via USB, select *File transfer* on the phone, and copy from
     `Download/RideLogger/` in the file explorer, or
   - with adb: `adb pull /sdcard/Download/RideLogger/ ./rides/`

The in-app copy stays on the phone until you **Delete** it — keep it until the ride has
been validated on the PC.

### 4.1 Validate every ride immediately

```bash
python3 analysis/validate_ride.py ride_20260711T093005Z_0a1b2c3d.db
```

Checks metadata completeness, monotonic timestamps, sensor gaps (> 100 ms fails),
drop percentage (> 0.1 % fails), and GPS rate and speed-accuracy coverage. (It also
warns about untagged calibration on rides from app ≤ 0.2.x — harmless and expected on
0.3.0 rides, which never contain markers.) `PASS` → archive the file; `FAIL` → read the output,
usually it's a crash-terminated ride or a sensor-rate problem worth fixing before the
next ride. The file format itself is documented in `analysis/schema.md`.

---

## 5. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Startup error "Sensor rate capped at 200 Hz" | System mic privacy toggle is OFF — the error card's button takes you to the setting; turn microphone access on and return to the app |
| Stuck on "Initializing … waiting for GPS fix" | Be outdoors with sky view; confirm Location permission is *Precise*; first fix after a long time can take a minute |
| Lean bar shows "—" while riding | Below 18 km/h that's intentional (bar-turn coupling). Above 18 km/h with "calibrating…": first ride on this install — ride steadily for a few minutes, it resolves itself |
| Pitch numbers look too large | Fixed in 0.3.2 (acceleration no longer reads as phantom wheelie) and 0.3.3 (leaned turns no longer read as nose-up spikes). Since 0.4.0 pitch is relative to the road (slope subtracted) — on any normal ride it should hover near 0° with small dive/squat blips. It remains an indicative v1 estimate until validated against real wheelie data |
| Pitch shows hills / ELEVATION row missing | Both mean the phone has no (working) barometer — pitch then falls back to absolute (road slope included) and the elevation graph is hidden. The Pixel 8 has one; other phones may not |
| Speed reads ~5 km/h below the bike's speedometer | The app is right — GPS speed is accurate to ~1 km/h; motorcycle speedometers are required to never read low and typically over-read 5–10 % |
| Rates drop during a long hot ride | Thermal throttling — expected on hot days; the gap analysis in the validator will show it. Shade the phone if possible |
| Dropped events > 0 | Should not happen at MVP write rates — validate the ride; if it recurs, note phone temperature and file an issue |
| Ride missing from the list after a crash | The service never resumes a file after a kill; the file is still in the list (or on disk under `Android/data` files/rides) and readable — validator will report it as crash-terminated |
| Validator: `clean_close missing` | The ride was not stopped via STOP (crash, battery died). Data up to the last half-second is intact |
| Share fails for a huge file | Use **Save** + USB instead (§4 option B) |
