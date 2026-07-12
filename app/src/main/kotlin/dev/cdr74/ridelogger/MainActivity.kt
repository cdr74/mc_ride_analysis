package dev.cdr74.ridelogger

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File

/**
 * Ride-display version UI (docs/ui-mockup.md, ADR 0005). This is step 1 of M6:
 * three-state startup (Initializing… → START / error screen) and the ride list.
 * The live bar display (S2) and post-ride views (S3) land in later steps; while
 * recording, the screen currently shows the status block + STOP.
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

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val status by RideLoggerService.status.collectAsState()

    val preflight = remember { Preflight(context) }
    val preflightState by preflight.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { preflight.start() }

    // Preflight runs only while the app is idle in the foreground: paused on
    // ON_PAUSE and while a ride is recording (the ride owns GPS + sensors then).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, status.running) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!status.running) preflight.start()
                Lifecycle.Event.ON_PAUSE -> preflight.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (status.running) preflight.stop()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            preflight.stop()
        }
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

        if (status.running) {
            Button(
                onClick = {
                    context.startService(RideLoggerService.intent(context, RideLoggerService.ACTION_STOP))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
            ) {
                Text("STOP", fontSize = 28.sp)
            }
            StatusBlock(status)
        } else {
            StartCard(
                state = preflightState,
                onStart = {
                    context.startForegroundService(
                        RideLoggerService.intent(context, RideLoggerService.ACTION_START),
                    )
                },
                onIssueAction = { action ->
                    when (action) {
                        Preflight.Action.REQUEST_PERMISSIONS ->
                            permissionLauncher.launch(Preflight.requiredPermissions())
                        Preflight.Action.OPEN_LOCATION_SETTINGS ->
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        Preflight.Action.OPEN_PRIVACY_SETTINGS ->
                            context.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS))
                        Preflight.Action.NONE -> Unit
                    }
                },
            )
        }

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

/**
 * Three-state startup (ui-mockup S1, Q8): "Initializing…" while sensors and GPS warm
 * up, the START button appears only when everything is ready, and a comprehensive
 * error card explains anything the app cannot access — with a button to fix it.
 */
@Composable
private fun StartCard(
    state: Preflight.State,
    onStart: () -> Unit,
    onIssueAction: (Preflight.Action) -> Unit,
) {
    when (state) {
        is Preflight.State.Ready -> Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
        ) {
            Text("START", fontSize = 28.sp)
        }

        is Preflight.State.Initializing -> Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.height(28.dp))
                Column {
                    Text("Initializing …", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "waiting for ${state.waitingFor.joinToString(" · ")}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        is Preflight.State.Blocked -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Can't record yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                state.issues.forEach { issue ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(issue.title, style = MaterialTheme.typography.titleMedium)
                        Text(issue.detail, style = MaterialTheme.typography.bodyMedium)
                        if (issue.action != Preflight.Action.NONE) {
                            Button(onClick = { onIssueAction(issue.action) }) {
                                Text(
                                    when (issue.action) {
                                        Preflight.Action.REQUEST_PERMISSIONS -> "Grant permissions"
                                        Preflight.Action.OPEN_LOCATION_SETTINGS -> "Open location settings"
                                        Preflight.Action.OPEN_PRIVACY_SETTINGS -> "Open privacy settings"
                                        Preflight.Action.NONE -> ""
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(status: SessionStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
