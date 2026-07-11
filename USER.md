# RideLogger — field manual

How to install the MVP app, calibrate it, record a ride, and get the data onto your PC.
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

1. Open **RideLogger**. The big button reads **GRANT PERMISSIONS** — tap it and grant:
   - **Location → While using the app**, and make sure **Precise** is selected.
   - **Notifications** (Android 13+) — needed for the recording notification and its
     Marker button.
2. **Critical — microphone privacy toggle:** on Android 12+ the system-wide mic toggle
   being OFF silently caps *all* sensors at 200 Hz, killing the high-rate IMU logging.
   Check Settings → Security & privacy → Privacy controls → **Microphone access = ON**.
   The app warns during recording if the measured accel rate looks capped (< 210 Hz).
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
2. Tap **START RIDE**. A persistent notification appears; the status card starts updating
   once per second.
3. Sanity-check the status card before riding off (give it ~15 s):
   - accel/gyro around **400 Hz** (device-dependent, anything ≥ 100 Hz is usable;
     if you see ~200 Hz, check the mic toggle — see §1.4),
   - **GPS: ±N m** with a satellite count, not "no fix",
   - **Dropped events: 0**.

### 3.2 Calibrate — at the start of EVERY ride

The mount position shifts slightly every time, so the phone→bike orientation is re-solved
per ride from tagged segments. The app only tags the segments; the math happens offline.

> **Bars DEAD STRAIGHT for every step.** The phone sits on the steering assembly: if the
> bars are turned, the calibration is garbage. Front wheel aligned with the frame.

In the **Calibration** card, each step is a toggle — tap to start, do the maneuver, tap
again (**END**) to close it:

1. **Static level (10 s):** bike upright on level ground — center stand, or held vertical
   (not on the side stand). Tap step 1, hold everything still for 10 seconds, tap END.
2. **Straight-line acceleration (~5 s):** tap step 2, accelerate hard in a dead-straight
   line for about 5 seconds, tap END when you roll off.
3. **Straight-line braking:** tap step 3, brake firmly in a straight line, tap END.
   (This confirms the direction found in step 2.)

Steps 2 and 3 can be done in the first hundred meters of the ride; it is fine if a
passenger or you at a stop press the buttons — the tap only needs to bracket the maneuver
roughly, the solver finds the exact segment.

### 3.3 Ride

- The screen can be **off**; logging continues (foreground service + wakelock).
- Press **MARKER** — in the app or on the notification — at any moment worth finding
  later: a wheelie attempt, a specific corner, something odd. Markers are timestamped
  rows in the ride file.
- Glance at the notification occasionally: it shows elapsed time and the drop count.
  Drops should stay at 0.

### 3.4 Stop

Tap **STOP**. The app drains its buffers, writes the closing metadata (clock anchor, drop
counts, `clean_close`) and closes the file. The ride then appears in the **Rides** list.

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
drop percentage (> 0.1 % fails), GPS rate and speed-accuracy coverage, and that the
calibration markers are balanced. `PASS` → archive the file; `FAIL` → read the output,
usually it's a crash-terminated ride or a sensor-rate problem worth fixing before the
next ride. The file format itself is documented in `analysis/schema.md`.

---

## 5. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Accel/gyro stuck near 200 Hz, in-app warning shown | System mic privacy toggle is OFF — turn microphone access on (§1.4) and restart the ride |
| "GPS: no fix" for minutes | Be outdoors with sky view; confirm Location permission is *Precise*; first fix after a long time can take a minute |
| Rates drop during a long hot ride | Thermal throttling — expected on hot days; the gap analysis in the validator will show it. Shade the phone if possible |
| Dropped events > 0 | Should not happen at MVP write rates — validate the ride; if it recurs, note phone temperature and file an issue |
| Ride missing from the list after a crash | The service never resumes a file after a kill; the file is still in the list (or on disk under `Android/data` files/rides) and readable — validator will report it as crash-terminated |
| Validator: `clean_close missing` | The ride was not stopped via STOP (crash, battery died). Data up to the last half-second is intact |
| Share fails for a huge file | Use **Save** + USB instead (§4 option B) |
