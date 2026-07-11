package dev.cdr74.ridelogger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Single-screen Compose UI (design.md §8): big start/stop, status block, marker,
 * calibration stepper, ride list. Deliberately no theming work.
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

        CalibrationStepper(running = status.running)

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

private val CALIB_STEPS = listOf(
    "static_level" to "1. Static level — upright, level ground, 10 s",
    "accel" to "2. Straight-line hard acceleration, ~5 s",
    "brake" to "3. Straight-line hard braking",
)

@Composable
private fun CalibrationStepper(running: Boolean) {
    val context = LocalContext.current
    var activeStep by remember { mutableIntStateOf(-1) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calibration", style = MaterialTheme.typography.titleMedium)
            Text(
                "Bars DEAD STRAIGHT (front wheel aligned with frame) for every step.",
                style = MaterialTheme.typography.bodySmall,
            )
            CALIB_STEPS.forEachIndexed { i, (key, label) ->
                val isActive = activeStep == i
                OutlinedButton(
                    onClick = {
                        val kind = if (isActive) "calib_end" else "calib_start"
                        context.startService(
                            RideLoggerService.intent(context, RideLoggerService.ACTION_CALIB)
                                .putExtra(RideLoggerService.EXTRA_KIND, kind)
                                .putExtra(RideLoggerService.EXTRA_NOTE, key),
                        )
                        activeStep = if (isActive) -1 else i
                    },
                    enabled = running && (activeStep == -1 || isActive),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(if (isActive) "END: $label" else label)
                }
            }
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
