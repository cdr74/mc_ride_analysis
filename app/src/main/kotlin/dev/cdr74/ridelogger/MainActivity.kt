package dev.cdr74.ridelogger

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

/**
 * Single-screen Compose UI (DESIGN.md §8): big start/stop, status block, marker,
 * calibration card + full-screen guidance overlay, ride list. Deliberately no theming work.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val status by RideLoggerService.status.collectAsState()

    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    var rideListVersion by remember { mutableIntStateOf(0) }
    val rides = remember(status.running, rideListVersion) {
        RideExporter.closedRides(context, status.rideFileName)
    }

    // Full-screen color cues while calibration runs (ADR 0003): beeps are often
    // inaudible over engine/wind noise, so the phase must be readable at a glance.
    val phase = status.calib
    val calibRunning = status.running &&
        phase != CalibrationGuide.Phase.IDLE && phase != CalibrationGuide.Phase.DONE
    var showDoneScreen by remember { mutableStateOf(false) }
    LaunchedEffect(phase, status.running) {
        when {
            calibRunning -> showDoneScreen = true // arm: DONE screen only right after a run
            phase == CalibrationGuide.Phase.DONE && showDoneScreen -> {
                delay(4000)
                showDoneScreen = false
            }
            else -> showDoneScreen = false
        }
    }
    val overlayVisible = calibRunning ||
        (status.running && phase == CalibrationGuide.Phase.DONE && showDoneScreen)

    // The cue is useless on a dark screen: force the screen on at full brightness for
    // the duration of the calibration run only (normal rides keep the screen off).
    DisposableEffect(overlayVisible) {
        val window = (context as? Activity)?.window
        if (overlayVisible && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.also { it.screenBrightness = 1f }
        }
        onDispose {
            if (overlayVisible && window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.attributes = window.attributes.also {
                    it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    if (overlayVisible) {
        CalibrationOverlay(phase = phase, onCancel = {
            context.startService(
                RideLoggerService.intent(context, RideLoggerService.ACTION_CALIB_CANCEL),
            )
        })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        // Big glove-friendly start/stop.
        Button(
            onClick = {
                if (status.running) {
                    context.startService(RideLoggerService.intent(context, RideLoggerService.ACTION_STOP))
                } else if (locationGranted) {
                    context.startForegroundService(
                        RideLoggerService.intent(context, RideLoggerService.ACTION_START),
                    )
                } else {
                    permissionLauncher.launch(requiredPermissions())
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (status.running) Color(0xFFB3261E) else Color(0xFF2E7D32),
            ),
        ) {
            Text(
                text = when {
                    status.running -> "STOP"
                    locationGranted -> "START RIDE"
                    else -> "GRANT PERMISSIONS"
                },
                fontSize = 28.sp,
            )
        }

        StatusBlock(status)

        Button(
            onClick = { context.startService(RideLoggerService.intent(context, RideLoggerService.ACTION_MARKER)) },
            enabled = status.running,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        ) {
            Text("MARKER (${status.markerCount})", fontSize = 22.sp)
        }

        CalibrationCard(status)

        HorizontalDivider()
        Text("Rides", style = MaterialTheme.typography.titleMedium)
        rides.forEach { file ->
            RideRow(file = file, onChanged = { rideListVersion++ })
        }
        if (rides.isEmpty()) {
            Text("No closed rides yet.", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusBlock(status: SessionStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!status.running) {
                Text("Idle", style = MaterialTheme.typography.titleMedium)
                return@Column
            }
            val el = status.elapsedMs / 1000
            Text(
                "%d:%02d:%02d · %s · %.1f MB".format(
                    el / 3600, el / 60 % 60, el % 60,
                    status.rideFileName ?: "-",
                    status.fileBytes / 1e6,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "accel %.0f Hz · gyro %.0f Hz · mag %.0f Hz · rotvec %.0f Hz · baro %.0f Hz".format(
                    status.ratesHz[0], status.ratesHz[1], status.ratesHz[2],
                    status.ratesHz[3], status.ratesHz[4],
                ),
            )
            val gps = status.gps
            Text(
                if (gps?.hasFix == true) {
                    "GPS: ±%.0f m · %.1f m/s · sats %d/%d".format(
                        gps.hAccM ?: -1f, gps.speedMps ?: 0f, gps.satsUsed, gps.satsTotal,
                    )
                } else {
                    "GPS: no fix (sats ${status.gps?.satsUsed ?: 0}/${status.gps?.satsTotal ?: 0})"
                },
            )
            Text("Dropped events: ${status.drops}")
            if (status.elapsedMs > 15_000 && status.ratesHz[0] > 0 &&
                status.ratesHz[0] < Config.LOW_RATE_WARN_HZ
            ) {
                Text(
                    "⚠ Accel rate low — check the microphone privacy toggle (caps sensors at 200 Hz).",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CalibrationCard(status: SessionStatus) {
    val context = LocalContext.current
    val phase = status.calib
    val active = phase != CalibrationGuide.Phase.IDLE && phase != CalibrationGuide.Phase.DONE

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calibration", style = MaterialTheme.typography.titleMedium)
            Text(
                "Hands-free: start it once while stopped, then just ride — the app detects " +
                    "each phase (hold still 10 s → brisk accel → firm brake, released while " +
                    "still rolling). The screen stays on and shows one color per phase: BLUE " +
                    "hold still, GREEN accelerate, ORANGE brake, red flash = retry. Beeps too, " +
                    "if you can hear them. Bars DEAD STRAIGHT throughout. No buttons while moving.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (phase != CalibrationGuide.Phase.IDLE) {
                Text(
                    phase.instruction,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (phase == CalibrationGuide.Phase.DONE) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            Button(
                onClick = {
                    val action = if (active) {
                        RideLoggerService.ACTION_CALIB_CANCEL
                    } else {
                        RideLoggerService.ACTION_CALIB_START
                    }
                    context.startService(RideLoggerService.intent(context, action))
                },
                enabled = status.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Text(
                    when {
                        active -> "CANCEL CALIBRATION"
                        phase == CalibrationGuide.Phase.DONE -> "RECALIBRATE"
                        else -> "START CALIBRATION"
                    },
                    fontSize = 18.sp,
                )
            }
        }
    }
}

/** What the full-screen overlay shows for one calibration phase. */
private data class CalibCue(val bg: Color, val word: String, val sub: String, val capturing: Boolean)

private fun calibCue(phase: CalibrationGuide.Phase): CalibCue = when (phase) {
    CalibrationGuide.Phase.WAIT_STATIC ->
        CalibCue(Color(0xFF1565C0), "HOLD STILL", "Bars dead straight · waiting for GPS to confirm", false)
    CalibrationGuide.Phase.STATIC ->
        CalibCue(Color(0xFF1565C0), "HOLD STILL", "Measuring — about 10 s", true)
    CalibrationGuide.Phase.WAIT_ACCEL ->
        CalibCue(Color(0xFF2E7D32), "ACCELERATE", "Brisk, not full throttle · dead straight · when safe — no rush", false)
    CalibrationGuide.Phase.ACCEL ->
        CalibCue(Color(0xFF2E7D32), "KEEP GOING", "Dead straight", true)
    CalibrationGuide.Phase.WAIT_BRAKE ->
        CalibCue(Color(0xFFE65100), "BRAKE", "Firmly · dead straight · when safe · release while still rolling", false)
    CalibrationGuide.Phase.BRAKE ->
        CalibCue(Color(0xFFE65100), "KEEP BRAKING", "Dead straight · don't brake to a standstill", true)
    CalibrationGuide.Phase.DONE ->
        CalibCue(Color(0xFF1B5E20), "DONE ✓", "Calibration complete", false)
    CalibrationGuide.Phase.IDLE -> CalibCue(Color.Black, "", "", false)
}

private val RETRY_CUE =
    CalibCue(Color(0xFFB3261E), "AGAIN", "That didn't count — it restarts by itself", false)

/**
 * Full-screen calibration guidance, readable from peripheral vision: the whole screen
 * is one solid color per phase (blue = hold still, green = accelerate, orange = brake,
 * red flash = retry, dark green = done) plus one huge word. Mirrors the beeps of
 * ADR 0003 for riders who cannot hear the phone over engine/wind noise.
 */
@Composable
private fun CalibrationOverlay(phase: CalibrationGuide.Phase, onCancel: () -> Unit) {
    // A retry is a phase going *backwards* (guide retries silently); flash red so the
    // rider knows the attempt didn't count.
    var prev by remember { mutableStateOf(phase) }
    var retryFlash by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        val retry = (prev == CalibrationGuide.Phase.STATIC && phase == CalibrationGuide.Phase.WAIT_STATIC) ||
            (prev == CalibrationGuide.Phase.ACCEL && phase == CalibrationGuide.Phase.WAIT_ACCEL)
        prev = phase
        retryFlash = retry
        if (retry) {
            delay(1500)
            retryFlash = false
        }
    }
    val cue = if (retryFlash) RETRY_CUE else calibCue(phase)

    val pulse by rememberInfiniteTransition(label = "calibPulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "calibPulseAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cue.bg)
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("CALIBRATION", color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                cue.word,
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                lineHeight = 68.sp,
            )
            Spacer(Modifier.height(16.dp))
            Text(cue.sub, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
            if (cue.capturing) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "● RECORDING",
                    color = Color.White.copy(alpha = pulse),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (phase != CalibrationGuide.Phase.DONE) {
            Button(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    contentColor = Color.White,
                ),
            ) { Text("CANCEL CALIBRATION", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun RideRow(file: File, onChanged: () -> Unit) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodySmall)
            Text("%.1f MB".format(file.length() / 1e6), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { RideExporter.share(context, file) }) { Text("Share") }
        TextButton(onClick = {
            val uri = RideExporter.exportToDownloads(context, file)
            Toast.makeText(
                context,
                if (uri != null) "Saved to Downloads/RideLogger" else "Export failed",
                Toast.LENGTH_SHORT,
            ).show()
        }) { Text("Save") }
        TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ride?") },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = {
                    RideExporter.delete(file)
                    confirmDelete = false
                    onChanged()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
