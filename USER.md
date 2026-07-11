# RideLogger — field manual

How to install the MVP app, calibrate it, record a ride, and get the data onto your PC.
Target device: Google Pixel 8 (works on any Android 10+ phone, but the field checklist in
`DESIGN.md` §9 is only validated against the Pixel 8).

---

## 1. Install the app

### 1.1 Build the APK

On the workstation (JDK 17+ and Android SDK Platform 35 required; `local.properties` must
point at the SDK):

```bash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/app-debug.apk
```

### 1.2 Prepare the phone

1. **Enable Developer options:** Settings → About phone → tap *Build number* 7 times.
2. **Enable USB debugging:** Settings → System → Developer options → *USB debugging*.
3. Connect the phone via USB and accept the "Allow USB debugging?" prompt.

### 1.3 Install

With the phone connected:

```bash
./gradlew installDebug          # or: adb install app/build/outputs/apk/debug/app-debug.apk
```

**WSL note:** WSL2 cannot see USB devices directly. Either run adb from Windows, or use adb
over Wi-Fi: on the phone enable *Wireless debugging* (Developer options), then
`adb pair <ip>:<port>` + `adb connect <ip>:<port>` from WSL.

Alternatively, skip adb entirely: copy `app-debug.apk` to the phone (Drive, USB file
transfer), open it in the Files app and allow "install unknown apps" when prompted.

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
- Phone in **portrait, USB port down**, clamped rigidly — it must not move during the ride.
- If your mount setup differs from the description in `Config.MOUNT_DESCRIPTION`
  (`app/src/main/kotlin/dev/cdr74/ridelogger/Config.kt`), update that string before
  building — it is written into every ride file and the analysis reads it.

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
