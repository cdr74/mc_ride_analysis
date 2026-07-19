package dev.cdr74.ridelogger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File

/**
 * Ride-display version UI (docs/ui-mockup.md, ADR 0005): three-state startup
 * (Initializing… → START / error screen), live-dimension slot picker, screen-mode
 * choice, the live bar display while recording, and the ride list.
 * v0.5.0: dark/light theme system (RideLoggerTheme), per-dimension identity colors,
 * SegmentBar LED-strip bars.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val uiPrefs = remember { UiPrefs(this) }
            var themeOverride by remember { mutableStateOf(uiPrefs.themeOverride) }
            RideLoggerTheme(override = themeOverride) {
                MainScreen(
                    themeOverride = themeOverride,
                    onToggleTheme = {
                        val next = when (themeOverride) {
                            ThemeOverride.SYSTEM -> ThemeOverride.DARK
                            ThemeOverride.DARK   -> ThemeOverride.LIGHT
                            ThemeOverride.LIGHT  -> ThemeOverride.SYSTEM
                        }
                        themeOverride = next
                        uiPrefs.themeOverride = next
                    },
                )
            }
        }
    }
}

private enum class ScreenMode { LIVE, BACKGROUND }

/** Persisted UI choices (no settings screen — every choice lives where it's used). */
private class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ridelogger_ui", Context.MODE_PRIVATE)

    var slots: List<Dimension?>
        get() = listOf(
            Dimension.fromName(prefs.getString("slot0", Dimension.LEAN.name)),
            Dimension.fromName(prefs.getString("slot1", Dimension.SPEED.name)),
        )
        set(v) = prefs.edit()
            .putString("slot0", v.getOrNull(0)?.name)
            .putString("slot1", v.getOrNull(1)?.name)
            .apply()

    var screenMode: ScreenMode
        get() = if (prefs.getString("screen_mode", ScreenMode.LIVE.name) == ScreenMode.BACKGROUND.name) {
            ScreenMode.BACKGROUND
        } else {
            ScreenMode.LIVE
        }
        set(v) = prefs.edit().putString("screen_mode", v.name).apply()

    var themeOverride: ThemeOverride
        get() = ThemeOverride.entries.firstOrNull { it.name == prefs.getString("theme", ThemeOverride.SYSTEM.name) }
            ?: ThemeOverride.SYSTEM
        set(v) = prefs.edit().putString("theme", v.name).apply()
}

@Composable
private fun MainScreen(themeOverride: ThemeOverride, onToggleTheme: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalRideColors.current
    val status by RideLoggerService.status.collectAsState()

    val uiPrefs = remember { UiPrefs(context) }
    var slots by remember { mutableStateOf(uiPrefs.slots) }
    var screenMode by remember { mutableStateOf(uiPrefs.screenMode) }

    val preflight = remember { Preflight(context) }
    val preflightState by preflight.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { preflight.start() }

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

    var openRide by remember { mutableStateOf<File?>(null) }
    var wasRunning by remember { mutableStateOf(status.running) }
    LaunchedEffect(status.running) {
        if (wasRunning && !status.running) {
            openRide = RideExporter.closedRides(context, null).firstOrNull()
        }
        wasRunning = status.running
    }

    if (status.running) {
        val live by RideLoggerService.live.collectAsState()
        KeepScreenOn(enabled = screenMode == ScreenMode.LIVE)
        LiveDisplayScreen(
            slots = slots,
            metrics = live,
            diagLine = diagLine(status),
            onStop = {
                context.startService(RideLoggerService.intent(context, RideLoggerService.ACTION_STOP))
            },
        )
        return
    }

    openRide?.let { file ->
        if (file.exists()) {
            PostRideScreen(file = file, onClose = { openRide = null })
            return
        }
        openRide = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Theme toggle top-right
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onToggleTheme) {
                Text(
                    text = when (themeOverride) {
                        ThemeOverride.SYSTEM -> "AUTO"
                        ThemeOverride.DARK   -> "☾"
                        ThemeOverride.LIGHT  -> "☀"
                    },
                    color = colors.textMuted,
                    fontSize = 14.sp,
                )
            }
        }

        StartCard(
            state = preflightState,
            onStart = {
                context.startForegroundService(
                    RideLoggerService.intent(context, RideLoggerService.ACTION_START),
                )
                if (screenMode == ScreenMode.BACKGROUND) {
                    (context as? Activity)?.moveTaskToBack(true)
                }
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

        SlotPicker(slots) { slots = it; uiPrefs.slots = it }
        ScreenModeRow(screenMode) { screenMode = it; uiPrefs.screenMode = it }
        Text(
            "tap the chips to change what's shown while riding",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )

        HorizontalDivider(color = colors.divider)
        Text("Rides", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        var rideListVersion by remember { mutableIntStateOf(0) }
        val rides = remember(rideListVersion) {
            RideExporter.closedRides(context, null)
        }
        rides.forEach { file ->
            RideRow(file = file, onOpen = { openRide = file }, onChanged = { rideListVersion++ })
        }
        if (rides.isEmpty()) {
            Text("No closed rides yet.", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun diagLine(status: SessionStatus): String {
    val el = status.elapsedMs / 1000
    val warn = if (status.elapsedMs > 15_000 && status.ratesHz[0] > 0 &&
        status.ratesHz[0] < Config.LOW_RATE_WARN_HZ
    ) {
        " · ⚠ rate capped — mic toggle"
    } else {
        ""
    }
    return "%d:%02d:%02d · %.0f Hz · drops %d · %s%s".format(
        el / 3600, el / 60 % 60, el % 60,
        status.ratesHz[0], status.drops,
        if (status.gps?.hasFix == true) "GPS ✓" else "GPS …",
        warn,
    )
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = (context as? Activity)?.window
        if (enabled && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (enabled && window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/** Two live-display slots; tapping a chip cycles lean → accel → pitch → speed → off. */
@Composable
private fun SlotPicker(slots: List<Dimension?>, onChange: (List<Dimension?>) -> Unit) {
    val colors = LocalRideColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("LIVE", style = MaterialTheme.typography.labelLarge, color = colors.textPrimary)
        slots.forEachIndexed { i, dim ->
            FilterChip(
                selected = dim != null,
                onClick = {
                    val next = when (dim) {
                        Dimension.LEAN -> Dimension.ACCEL
                        Dimension.ACCEL -> Dimension.PITCH
                        Dimension.PITCH -> Dimension.SPEED
                        Dimension.SPEED -> null
                        Dimension.ELEVATION -> null
                        null -> Dimension.LEAN
                    }
                    onChange(slots.toMutableList().also { it[i] = next })
                },
                label = { Text(dim?.label ?: "OFF", fontSize = 14.sp) },
                leadingIcon = dim?.let { d ->
                    {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(d.color, CircleShape),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ScreenModeRow(mode: ScreenMode, onChange: (ScreenMode) -> Unit) {
    val colors = LocalRideColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SCREEN", style = MaterialTheme.typography.labelLarge, color = colors.textPrimary)
        FilterChip(
            selected = mode == ScreenMode.LIVE,
            onClick = { onChange(ScreenMode.LIVE) },
            label = { Text("live display", fontSize = 14.sp) },
        )
        FilterChip(
            selected = mode == ScreenMode.BACKGROUND,
            onClick = { onChange(ScreenMode.BACKGROUND) },
            label = { Text("off (background)", fontSize = 14.sp) },
        )
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
    val colors = LocalRideColors.current
    when (state) {
        is Preflight.State.Ready -> Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(containerColor = colors.startFill),
        ) {
            Text("START", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        is Preflight.State.Initializing -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.surface,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.width(28.dp).height(28.dp))
                Column {
                    Text("Initializing …", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                    Text(
                        "waiting for ${state.waitingFor.joinToString(" · ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                    )
                }
            }
        }

        is Preflight.State.Blocked -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.surface,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Can't record yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                state.issues.forEach { issue ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(issue.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                        Text(issue.detail, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
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
private fun RideRow(file: File, onOpen: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalRideColors.current
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable { onOpen() }) {
            Text(file.name, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
            Text("%.1f MB".format(file.length() / 1e6), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
        TextButton(onClick = { RideExporter.share(context, file) }) {
            Text("Share", color = colors.textMuted)
        }
        TextButton(onClick = {
            val uri = RideExporter.exportToDownloads(context, file)
            Toast.makeText(
                context,
                if (uri != null) "Saved to Downloads/RideLogger" else "Export failed",
                Toast.LENGTH_SHORT,
            ).show()
        }) { Text("Save", color = colors.textMuted) }
        TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = colors.textMuted) }
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
