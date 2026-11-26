package dev.encore.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.encore.manager.HardwareSnapshot
import dev.encore.manager.MainViewModel

/** "N/A" when the reading is unavailable — never a guessed number (Master Prompt §12). */
private fun Int?.orNA(suffix: String = ""): String = this?.let { "$it$suffix" } ?: "N/A"
private fun Float?.orNA(suffix: String = ""): String = this?.let { "%.1f%s".format(it, suffix) } ?: "N/A"
private fun Long?.orNA(suffix: String = ""): String = this?.let { "$it$suffix" } ?: "N/A"

// ============================================================== HOME · DEVICE OVERVIEW

/**
 * Read-only hardware dashboard (Master Prompt §8 HOME). Exactly the eight required metrics —
 * no sliders, switches, selectors, edit fields or tuning actions, and no fabricated history
 * charts. The CPU governor is shown for information only: it is read here, never written.
 */
@Composable
fun DeviceOverviewScreen(vm: MainViewModel) {
    val hw by vm.hardware.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Read-only hardware snapshot, refreshed every few seconds.",
            style = MaterialTheme.typography.bodySmall,
            color = EncoreColors.TextLabel,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.height(420.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(deviceOverviewMetrics(hw)) { (label, value) -> MetricPanel(label, value) }
        }
    }
}

private fun deviceOverviewMetrics(hw: HardwareSnapshot): List<Pair<String, String>> = listOf(
    "CPU GOVERNOR" to (hw.cpuGovernor ?: "N/A"),
    "CPU USAGE" to hw.cpuUsagePercent.orNA("%"),
    "CPU CLOCK" to (hw.cpuClockMhz?.let { "%.2f GHz".format(it / 1000f) } ?: "N/A"),
    "CPU TEMP" to hw.cpuTempC.orNA("°C"),
    "BATTERY TEMP" to hw.batteryTempC.orNA("°C"),
    "BATTERY CYCLES" to hw.batteryCycleCount.orNA(),
    "BATTERY CAPACITY" to hw.batteryFccMah.orNA(" mAh"),
    "BATTERY HEALTH" to hw.batteryHealthPercent.orNA("%"),
)

@Composable
private fun MetricPanel(label: String, value: String) {
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = EncoreColors.TextLabel)
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                style = TelemetryTextStyle.copy(fontSize = 20.sp),
                color = EncoreColors.TextPrimary,
            )
        }
    }
}

// ============================================================== PERFORMANCE

/**
 * Profiles and daemon/service status (Master Prompt §8 PERFORMANCE). No frequency sliders here
 * — those belong to Tuning. Lite Mode is shown READ-ONLY here; the one real switch that turns it
 * on lives in Game Mode, so there is exactly one control for it, not two.
 */
@Composable
fun PerformanceSectionScreen(vm: MainViewModel) {
    val home by vm.home.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val running = home.daemonPid != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NeonPanel(
            Modifier.fillMaxWidth(),
            borderColor = if (running) EncoreColors.NeonRed else EncoreColors.HairlineDim,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(color = if (running) EncoreColors.NeonRed else EncoreColors.HairlineDim, active = running)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (running) "DAEMON ONLINE" else "DAEMON OFFLINE",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(12.dp))
                    ProfileBadge(home.profile)
                }
                if (running) {
                    TelemetryRow("DAEMON PID", home.daemonPid ?: "—")
                }
                HorizontalDivider(color = EncoreColors.HairlineDim)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(160.dp)) {
                        Text("Lite Mode", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Toggle from Game Mode \u2192",
                            style = MaterialTheme.typography.bodySmall,
                            color = EncoreColors.TextLabel,
                        )
                    }
                    Text(
                        if (prefs?.enforceLiteMode == true) "ENFORCED" else "OFF",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (prefs?.enforceLiteMode == true) EncoreColors.NeonRed else EncoreColors.TextLabel,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "PER-APP PROFILES",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            "Performance & Lite are tuned per game \u2014 enable, Lite Mode and Do Not Disturb for each installed game.",
            style = MaterialTheme.typography.bodySmall,
            color = EncoreColors.TextLabel,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        GamesScreen(vm)
    }
}

// ============================================================== GAME MODE

/**
 * Gaming-specific behavior (Master Prompt §8 GAME MODE): the global Lite Mode switch — moved
 * here per your correction, this is now the one real control for it in the whole app — plus the
 * existing Game Bar / Monitor Pill / overlay-position controls, reused unchanged.
 */
@Composable
fun GameModeSectionScreen(vm: MainViewModel) {
    val prefs by vm.prefs.collectAsState()
    val prefsError by vm.prefsError.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when {
            prefs == null && prefsError -> Text(
                "config.json is missing or unreadable. Nothing will be written.",
                color = EncoreColors.ElectricRed,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            prefs == null -> Text(
                "Loading\u2026",
                color = EncoreColors.TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            else -> SwitchRow(
                "Enforce Lite Mode",
                "Force the lighter profile for every game",
                prefs!!.enforceLiteMode,
            ) { vm.setEnforceLite(it) }
        }

        NeonDivider()
        GameBarSection(vm)
    }
}

// ============================================================== BATTERY

/** Battery information and health (Master Prompt §8 BATTERY). Detailed temperature lives in Thermals. */
@Composable
fun BatterySectionScreen(vm: MainViewModel) {
    val hw by vm.hardware.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NeonPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TelemetryRow("BATTERY LEVEL", hw.batteryPercent.orNA("%"))
                TelemetryRow(
                    "STATUS",
                    when (hw.batteryCharging) {
                        true -> "Charging"
                        false -> "Discharging"
                        null -> "N/A"
                    },
                )
                TelemetryRow("HEALTH", hw.batteryHealthPercent.orNA("%"))
                TelemetryRow("FULL CHARGE CAPACITY", hw.batteryFccMah.orNA(" mAh"))
                TelemetryRow("DESIGN CAPACITY", hw.batteryDesignMah.orNA(" mAh"))
                TelemetryRow("CYCLE COUNT", hw.batteryCycleCount.orNA())
                TelemetryRow("VOLTAGE", hw.batteryVoltageMv.orNA(" mV"))
                TelemetryRow("CURRENT", hw.batteryCurrentUa.orNA(" \u00b5A"))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Battery temperature is tracked in Thermals. A field reads N/A when the device does " +
                "not expose that node to root \u2014 this is never estimated.",
            style = MaterialTheme.typography.bodySmall,
            color = EncoreColors.TextLabel,
        )
    }
}

// ============================================================== THERMALS

/**
 * Available temperature sensors (Master Prompt §8 THERMALS). CPU and battery are always real
 * readings; GPU shows N/A on builds whose thermal zones don't expose a distinct GPU sensor.
 */
@Composable
fun ThermalsSectionScreen(vm: MainViewModel) {
    val hw by vm.hardware.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NeonPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TelemetryRow("CPU TEMPERATURE", hw.cpuTempC.orNA("°C"))
                TelemetryRow("GPU TEMPERATURE", hw.gpuTempC.orNA("°C"))
                TelemetryRow("BATTERY TEMPERATURE", hw.batteryTempC.orNA("°C"))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Sourced from /sys/class/thermal thermal zones and the battery broadcast. No thermal " +
                "protection is altered or bypassed here.",
            style = MaterialTheme.typography.bodySmall,
            color = EncoreColors.TextLabel,
        )
    }
}

// ============================================================== ADVANCED

/**
 * Technical diagnostics (Master Prompt §8 ADVANCED): daemon/root status, telemetry, log level,
 * export, and the existing Logs view \u2014 all reused, nothing duplicated from Game Mode.
 */
@Composable
fun AdvancedSectionScreen(vm: MainViewModel) {
    val home by vm.home.collectAsState()
    val prefs by vm.prefs.collectAsState()
    var showLogLevel by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NeonPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SYSTEM", style = MaterialTheme.typography.labelMedium)
                TelemetryRow("DAEMON PID", home.daemonPid ?: "offline")
                TelemetryRow("MODULE VERSION", home.moduleVersion)
                TelemetryRow("CHIPSET", home.chipset)
                TelemetryRow("KERNEL", home.kernel)
                TelemetryRow("ANDROID SDK", android.os.Build.VERSION.SDK_INT.toString())
                TelemetryRow("ROOT ACCESS", "Granted")
            }
        }

        NeonDivider()

        if (prefs != null) {
            ClickRow(
                "Log Level",
                "Current: ${LOG_LEVELS.getOrElse(prefs!!.logLevel) { "?" }}",
            ) { showLogLevel = true }
        }
        ClickRow("Save Log", "Export Encore Tweaks operational log") { vm.exportLogs() }
        ClickRow("View Logs", "Last 300 lines of encore.log") {
            vm.loadLogs()
            showLogs = true
        }

        NeonDivider()

        Text(
            "Game Bar permissions and the overlay status live in Game Mode, not here, so there is " +
                "only one place to grant or check them.",
            style = MaterialTheme.typography.bodySmall,
            color = EncoreColors.TextLabel,
        )
    }

    if (showLogLevel && prefs != null) {
        AlertDialog(
            onDismissRequest = { showLogLevel = false },
            containerColor = EncoreColors.Obsidian,
            shape = CutCornerShape(16.dp),
            title = { Text("Log Level", color = EncoreColors.TextPrimary) },
            text = {
                Column {
                    LOG_LEVELS.forEachIndexed { index, name ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    vm.setLogLevel(index)
                                    showLogLevel = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = index == prefs!!.logLevel,
                                onClick = {
                                    vm.setLogLevel(index)
                                    showLogLevel = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = EncoreColors.NeonRed),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = EncoreColors.TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogLevel = false }) {
                    Text("Close", color = EncoreColors.NeonRed)
                }
            },
        )
    }

    if (showLogs) {
        Dialog(onDismissRequest = { showLogs = false }) {
            NeonPanel(Modifier.fillMaxWidth().height(480.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("LOGS", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showLogs = false }) {
                            Text("Close", color = EncoreColors.NeonRed)
                        }
                    }
                    LogsScreen(vm)
                }
            }
        }
    }
}
