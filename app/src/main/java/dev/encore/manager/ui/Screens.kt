package dev.encore.manager.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.encore.manager.GameEntry
import dev.encore.manager.LogExport
import dev.encore.manager.MainViewModel
import dev.encore.manager.gamebar.GameBarDiag
import dev.encore.manager.R
import dev.encore.manager.RootState

// ====================================================================== root

@Composable
fun EncoreRoot(vm: MainViewModel) {
    val root by vm.root.collectAsState()
    when (root) {
        RootState.Checking -> Box(
            Modifier.fillMaxSize().background(EncoreColors.TrueBlack),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = EncoreColors.NeonRed)
        }
        RootState.Denied -> RootDeniedScreen(onRetry = { vm.checkRoot() })
        RootState.Granted -> XArenaRoot(vm)
    }
}

@Composable
private fun RootDeniedScreen(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(EncoreColors.TrueBlack).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.encore_sleeping),
            contentDescription = null,
            modifier = Modifier.size(140.dp),
        )
        Spacer(Modifier.size(20.dp))
        Icon(Icons.Filled.Warning, contentDescription = null, tint = EncoreColors.NeonRed, modifier = Modifier.size(32.dp))
        Spacer(Modifier.size(8.dp))
        Text("ROOT ACCESS REQUIRED", style = MaterialTheme.typography.headlineSmall, color = EncoreColors.TextPrimary)
        Spacer(Modifier.size(8.dp))
        Text(
            "Encore Manager edits files under /data/adb. Open KernelSU Next, go to the " +
                "Superuser tab, allow Encore Manager, then tap Retry.",
            style = MaterialTheme.typography.bodyMedium,
            color = EncoreColors.TextLabel,
        )
        Spacer(Modifier.size(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = EncoreColors.NeonRed, contentColor = Color.Black),
            shape = CutCornerShape(10.dp),
        ) { Text("RETRY", fontWeight = FontWeight.Bold) }
    }
}

// ====================================================================== shared bits

@Composable
fun ScreenTitle(text: String) {
    Column {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            color = EncoreColors.TextPrimary,
        )
        Box(
            Modifier
                .padding(top = 4.dp)
                .width(42.dp)
                .size(3.dp)
                .background(EncoreColors.NeonRed),
        )
    }
}

/** Profile → accent color, used for the hero badge and the tuning tab's chips. */
internal fun profileAccent(profile: String): Color = when (profile) {
    "Performance" -> EncoreColors.ElectricRed
    "Balanced" -> EncoreColors.Amber
    "Powersave" -> EncoreColors.Cyan
    else -> EncoreColors.TextLabel
}

// ====================================================================== home

@Composable
private fun HomeScreen(vm: MainViewModel) {
    val info by vm.home.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val running = info.daemonPid != null

    LaunchedEffect(Unit) { vm.loadPrefs() }

    // encore_happy while the daemon is actively driving a non-idle profile, encore_sleeping
    // whenever it is offline, initializing or deliberately in Powersave.
    val mascot = if (running && info.profile != "Powersave" && info.profile != "Initializing") {
        R.drawable.encore_happy
    } else {
        R.drawable.encore_sleeping
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Encore Manager")

        if (!info.moduleInstalled) {
            NeonPanel(
                Modifier.fillMaxWidth(),
                borderColor = EncoreColors.ElectricRed,
                fill = EncoreColors.CrimsonDeep,
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = EncoreColors.ElectricRed)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Encore Tweaks module not found in /data/adb/modules/encore. Install the module first.",
                        color = EncoreColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // ---- Hero status header
        NeonPanel(
            Modifier.fillMaxWidth(),
            borderColor = if (running) EncoreColors.NeonRed else EncoreColors.HairlineDim,
            shape = CutCornerShape(20.dp),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(mascot),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseDot(color = if (running) EncoreColors.NeonRed else EncoreColors.HairlineDim, active = running)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (running) "DAEMON ONLINE" else "DAEMON OFFLINE",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (running) EncoreColors.TextPrimary else EncoreColors.TextLabel,
                            )
                        }
                        Text(
                            if (running) "PID ${info.daemonPid}" else "Check the Logs tab for details",
                            style = MaterialTheme.typography.bodySmall,
                            color = EncoreColors.TextLabel,
                            fontFamily = FontFamily.Monospace,
                        )
                        ProfileBadge(info.profile)
                    }
                }

                HorizontalDivider(color = EncoreColors.HairlineDim)

                // Lite Mode (global) + Device Mitigation indicator
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lite Mode", style = MaterialTheme.typography.bodyLarge, color = EncoreColors.TextPrimary)
                        Text(
                            "Force the lighter profile for every game",
                            style = MaterialTheme.typography.bodySmall,
                            color = EncoreColors.TextLabel,
                        )
                    }
                    Switch(
                        checked = prefs?.enforceLiteMode ?: false,
                        onCheckedChange = { vm.setEnforceLite(it) },
                        enabled = prefs != null,
                        colors = neonSwitchColors(),
                    )
                }
                DeviceMitigationIndicator(active = prefs?.useDeviceMitigation ?: false)
            }
        }

        // ---- Telemetry grid
        NeonPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SYSTEM", style = MaterialTheme.typography.labelMedium)
                TelemetryRow("MODULE VERSION", info.moduleVersion)
                TelemetryRow("CHIPSET", info.chipset)
                TelemetryRow("KERNEL", info.kernel)
                TelemetryRow("ANDROID SDK", android.os.Build.VERSION.SDK_INT.toString())
            }
        }
    }
}

@Composable
internal fun ProfileBadge(profile: String) {
    val accent = profileAccent(profile)
    Row(
        Modifier
            .clip(CutCornerShape(6.dp))
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.6f), CutCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            profile.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun DeviceMitigationIndicator(active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = if (active) EncoreColors.Cyan else EncoreColors.TextLabel,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (active) "Device Mitigation active" else "Device Mitigation off",
            style = MaterialTheme.typography.bodySmall,
            color = if (active) EncoreColors.Cyan else EncoreColors.TextLabel,
        )
    }
}

@Composable
internal fun TelemetryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = EncoreColors.TextLabel)
        Text(
            value,
            style = TelemetryTextStyle.copy(fontSize = 13.sp),
            color = EncoreColors.TextPrimary,
        )
    }
}

@Composable
internal fun neonSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.Black,
    checkedTrackColor = EncoreColors.NeonRed,
    checkedBorderColor = EncoreColors.NeonRed,
    uncheckedThumbColor = EncoreColors.TextLabel,
    uncheckedTrackColor = EncoreColors.Obsidian,
    uncheckedBorderColor = EncoreColors.HairlineDim,
)

// ====================================================================== games

@Composable
internal fun GamesScreen(vm: MainViewModel) {
    val games by vm.games.collectAsState()
    val query by vm.query.collectAsState()
    val loading by vm.gamesLoading.collectAsState()
    val error by vm.gamesError.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenTitle("Games")
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.loadGames() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = EncoreColors.NeonRed)
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { vm.setQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            shape = CutCornerShape(10.dp),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = EncoreColors.TextLabel) },
            placeholder = { Text("Search apps", color = EncoreColors.TextLabel) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = EncoreColors.NeonRed,
                unfocusedBorderColor = EncoreColors.HairlineDim,
                focusedTextColor = EncoreColors.TextPrimary,
                unfocusedTextColor = EncoreColors.TextPrimary,
                cursorColor = EncoreColors.NeonRed,
            ),
        )
        if (error != null) {
            Text(
                error ?: "",
                color = EncoreColors.ElectricRed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when {
            loading && games.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = EncoreColors.NeonRed)
            }
            games.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No apps found", color = EncoreColors.TextLabel)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(games, key = { it.pkg }) { g -> GameCard(g, vm) }
            }
        }
    }
}

@Composable
private fun GameCard(g: GameEntry, vm: MainViewModel) {
    val ctx = LocalContext.current
    NeonPanel(
        Modifier.fillMaxWidth(),
        borderColor = if (g.enabled) EncoreColors.NeonRed.copy(alpha = 0.6f) else EncoreColors.HairlineDim,
        fill = if (g.enabled) EncoreColors.Obsidian else EncoreColors.Carbon,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(g.pkg, Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        g.label.ifEmpty { g.pkg },
                        style = MaterialTheme.typography.titleMedium,
                        color = EncoreColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        g.pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = EncoreColors.TextLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = g.enabled, onCheckedChange = { vm.setGameEnabled(g.pkg, it) }, colors = neonSwitchColors())
            }
            if (g.enabled) {
                Spacer(Modifier.size(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NeonFilterChip(selected = g.liteMode, label = "Lite mode") { vm.setGameLiteMode(g.pkg, !g.liteMode) }
                    NeonFilterChip(selected = g.dnd, label = "Do Not Disturb") { vm.setGameDnd(g.pkg, !g.dnd) }
                    IconButton(onClick = { launchApp(ctx, g.pkg) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Launch", tint = EncoreColors.NeonRed)
                    }
                    IconButton(onClick = { openAppInfo(ctx, g.pkg) }) {
                        Icon(Icons.Filled.Info, contentDescription = "App info", tint = EncoreColors.TextLabel)
                    }
                }
            }
        }
    }
}

@Composable
fun NeonFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = CutCornerShape(6.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = EncoreColors.Carbon,
            labelColor = EncoreColors.TextLabel,
            selectedContainerColor = EncoreColors.NeonRed.copy(alpha = 0.18f),
            selectedLabelColor = EncoreColors.NeonRed,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = EncoreColors.HairlineDim,
            selectedBorderColor = EncoreColors.NeonRed,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
    )
}

internal fun launchApp(ctx: Context, pkg: String) {
    ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
        ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

internal fun openAppInfo(ctx: Context, pkg: String) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}

// ====================================================================== logs

@Composable
internal fun LogsScreen(vm: MainViewModel) {
    val text by vm.logText.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenTitle("Logs")
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.loadLogs() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = EncoreColors.NeonRed)
            }
        }
        Text(
            "LAST 300 LINES OF ENCORE.LOG",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        NeonPanel(
            Modifier.fillMaxSize().padding(16.dp),
            fill = EncoreColors.TrueBlack,
        ) {
            Box(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = EncoreColors.Lime,
                    softWrap = false,
                )
            }
        }
    }
}

// ====================================================================== settings

internal val LOG_LEVELS = listOf("Critical", "Error", "Warning", "Info", "Debug", "Trace")

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val prefs by vm.prefs.collectAsState()
    val prefsError by vm.prefsError.collectAsState()
    val export by vm.logExport.collectAsState()
    var showLogLevel by remember { mutableStateOf(false) }
    val p = prefs

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            ScreenTitle("Settings")
        }

        if (p == null) {
            Text(
                if (prefsError) "config.json is missing or unreadable. Nothing will be written."
                else "Loading…",
                modifier = Modifier.padding(16.dp),
                color = if (prefsError) EncoreColors.ElectricRed else EncoreColors.TextPrimary,
            )
        } else {
            SwitchRow(
                "Enforce Lite Mode",
                "Enable Lite Mode by default for all games",
                p.enforceLiteMode,
            ) { vm.setEnforceLite(it) }
            ClickRow(
                "Log Level",
                "Current: ${LOG_LEVELS.getOrElse(p.logLevel) { "?" }}",
            ) { showLogLevel = true }
        }

        NeonDivider()

        GameBarSection(vm)

        NeonDivider()

        ClickRow("Save Log", "Export Encore Tweaks operational log") { vm.exportLogs() }

        NeonDivider()

        Text(
            "Encore Manager 1.2.1 · Cyberpunk Neon Red build. A native front end for Encore Tweaks by Rem01Gaming (Apache-2.0).",
            style = MaterialTheme.typography.bodySmall,
            color = EncoreColors.TextLabel,
            modifier = Modifier.padding(16.dp),
        )
    }

    if (showLogLevel && p != null) {
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
                                selected = p.logLevel == index,
                                onClick = null,
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                    selectedColor = EncoreColors.NeonRed,
                                    unselectedColor = EncoreColors.TextLabel,
                                ),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(name, color = EncoreColors.TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogLevel = false }) { Text("CLOSE", color = EncoreColors.NeonRed) }
            },
        )
    }

    if (export != LogExport.Idle) {
        AlertDialog(
            onDismissRequest = { if (export !is LogExport.Running) vm.dismissExport() },
            containerColor = EncoreColors.Obsidian,
            shape = CutCornerShape(16.dp),
            title = { Text("Save Log", color = EncoreColors.TextPrimary) },
            text = {
                when (val e = export) {
                    LogExport.Idle -> Unit
                    LogExport.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = EncoreColors.NeonRed)
                        Spacer(Modifier.width(16.dp))
                        Text("Packing logs, please wait…", color = EncoreColors.TextPrimary)
                    }
                    is LogExport.Done -> Text("Log saved successfully:\n${e.path}", color = EncoreColors.TextPrimary)
                    is LogExport.Failed -> Text("Failed to save log:\n${e.message}", color = EncoreColors.ElectricRed)
                }
            },
            confirmButton = {
                if (export !is LogExport.Running) {
                    TextButton(onClick = { vm.dismissExport() }) { Text("OK", color = EncoreColors.NeonRed) }
                }
            },
        )
    }
}

@Composable
internal fun GameBarSection(vm: MainViewModel) {
    val enabled by vm.gameBarEnabled.collectAsState()
    val pill by vm.monitorPillEnabled.collectAsState()
    val diag by GameBarDiag.state.collectAsState()
    val status by vm.gameBarStatus.collectAsState()

    fun mark(ok: Boolean) = if (ok) "granted" else "missing"

    Text(
        "GAME BAR",
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    SwitchRow(
        "Show Game Bar",
        "A HUD over games from your Encore list: Lite Mode, Do Not Disturb, brightness, volume, crosshair, screenshot, clear background apps",
        enabled,
    ) { vm.setGameBarEnabled(it) }
    SwitchRow(
        "Monitor pill",
        "Small translucent strip with prime-core CPU frequency, FPS, temperature, battery and time. Tap it in a game to open the panel (the pill hides while the panel is open); choose its numbers under Live Monitor. Turning it off also removes the way to open the panel in a game",
        pill,
    ) { vm.setMonitorPillEnabled(it) }
    ClickRow(
        "Reset positions",
        "Drag the pill or the panel header in a game to move them. This puts them back",
    ) { vm.resetGameBarPositions() }
    ClickRow(
        "Game Bar status (tap to restart)",
        buildString {
            append("Service: ${if (diag.serviceRunning) "running" else "stopped"}")
            append("  ·  In front: ${diag.foreground ?: "none"}")
            append("  ·  In game list: ${when (diag.inGameList) { true -> "yes"; false -> "no"; null -> "unknown" }}")
            append("  ·  Overlay: ${if (diag.overlayShown) "shown" else "hidden"}")
            diag.fpsInfo?.let { append("\nFPS: $it") }
            diag.lastError?.let { append("\nError: $it") }
        },
    ) { vm.restartGameBar() }
    ClickRow(
        "Permissions (tap to grant with root)",
        "Overlay: ${mark(status.overlay)}  ·  Usage access: ${mark(status.usage)}  ·  " +
            "Notifications: ${mark(status.notifications)}  ·  Modify system settings: ${mark(status.writeSettings)}",
    ) { vm.grantGameBarPermissions() }
}

@Composable
internal fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = EncoreColors.TextPrimary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = EncoreColors.TextLabel)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = neonSwitchColors())
    }
}

@Composable
internal fun ClickRow(title: String, description: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = EncoreColors.TextPrimary)
        Text(description, style = MaterialTheme.typography.bodySmall, color = EncoreColors.TextLabel)
    }
}

@Composable
internal fun NeonDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = 8.dp),
        color = EncoreColors.HairlineDim,
    )
}
