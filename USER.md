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

### 3.2 Calibrate — hands-free

The phone→bike orientation is re-solved offline from tagged ride segments. The app tags
them for you: **you press one button while stopped, then never touch the phone again** —
each phase is detected automatically from GPS speed, and the app **beeps** at every
transition so you never need to look at the screen.

> **Bars DEAD STRAIGHT throughout.** The phone sits on the steering assembly: if the
> bars are turned, the calibration is garbage. Front wheel aligned with the frame.

1. Stopped, upright (held vertical or center stand — not the side stand), bars straight:
   tap **START CALIBRATION**.
2. **Hold still.** After a moment the app starts a 10 s measurement — *beep* when done.
   (If you move too early you hear a low buzz and it simply restarts — just hold still
   again.)
3. Ride off whenever it's safe and **accelerate hard in a straight line** for ~5 s.
   The app detects the launch by itself — *beep* when captured.
4. When safe, **brake firmly in a straight line** (to a stop or near-stop) —
   *beep-beep*: calibration complete.

There is no time pressure between phases: cruise as long as you like before the
acceleration or the braking — the app waits. Detection only needs to roughly bracket the
maneuvers; the offline solver finds the exact segments. **CANCEL CALIBRATION** aborts
cleanly at any point, and you can redo the whole thing anytime (**RECALIBRATE**) — extra
or aborted segments are harmless.

**Do you have to calibrate every ride?** No — the app never forces it. Calibrate whenever
the phone was taken off / remounted (the mount position shifts slightly each time); for
back-to-back rides with the phone left on the bike, skipping is fine — the analysis falls
back to the most recent calibration, at slightly reduced confidence. When in doubt, do it:
it costs one traffic-light stop.

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
| Calibration stuck at "Hold still" | Phase detection runs on GPS speed — wait until the status card shows a GPS fix before starting calibration |
| No beep at a calibration transition | Check media volume; the cues play on the media stream (helmet speakers / earbuds work) |
| Rates drop during a long hot ride | Thermal throttling — expected on hot days; the gap analysis in the validator will show it. Shade the phone if possible |
| Dropped events > 0 | Should not happen at MVP write rates — validate the ride; if it recurs, note phone temperature and file an issue |
| Ride missing from the list after a crash | The service never resumes a file after a kill; the file is still in the list (or on disk under `Android/data` files/rides) and readable — validator will report it as crash-terminated |
| Validator: `clean_close missing` | The ride was not stopped via STOP (crash, battery died). Data up to the last half-second is intact |
| Share fails for a huge file | Use **Save** + USB instead (§4 option B) |
