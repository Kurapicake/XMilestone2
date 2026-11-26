package dev.encore.manager.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.encore.manager.AppSection
import dev.encore.manager.GameEntry
import dev.encore.manager.GameSpaceTab
import dev.encore.manager.MainViewModel
import dev.encore.manager.SettingsSection
import dev.encore.manager.data.GameBanners
import kotlin.math.roundToInt

/**
 * GameSpace: the app's default homepage (Master Prompt §6). Installed-game library on the left,
 * the selected game's banner/info/Start/Quick-Tuning in the center, a live top bar, and a
 * two-item bottom bar (Library / Performance). The gear icon is the only way into the internal
 * Settings Manager — it never launches a second activity or application.
 */
@Composable
fun GameSpaceScreen(vm: MainViewModel) {
    val tab by vm.gameSpaceTab.collectAsState()
    val games by vm.games.collectAsState()
    val selected by vm.selectedGame.collectAsState()

    // Default to the first enabled game once the list loads, so the panel is never empty.
    LaunchedEffect(games) {
        if (selected == null) {
            games.firstOrNull { it.enabled }?.let { vm.selectGame(it.pkg) } ?: games.firstOrNull()?.let { vm.selectGame(it.pkg) }
        }
    }

    Scaffold(
        containerColor = EncoreColors.TrueBlack,
        topBar = { GameSpaceTopBar(vm) },
        bottomBar = { GameSpaceBottomBar(tab) { vm.selectGameSpaceTab(it) } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(EncoreColors.TrueBlack)) {
            when (tab) {
                GameSpaceTab.Library -> GameSpaceLibrary(vm, games, selected)
                GameSpaceTab.Performance -> GameSpaceSessionAnalytics()
            }
        }
    }

    val launchError by vm.launchError.collectAsState()
    if (launchError != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissLaunchError() },
            containerColor = EncoreColors.Obsidian,
            shape = CutCornerShape(16.dp),
            title = { Text("Can't launch game", color = EncoreColors.TextPrimary) },
            text = { Text(launchError ?: "", color = EncoreColors.TextLabel) },
            confirmButton = {
                TextButton(onClick = { vm.dismissLaunchError() }) { Text("OK", color = EncoreColors.NeonRed) }
            },
        )
    }
}

@Composable
private fun GameSpaceTopBar(vm: MainViewModel) {
    val hw by vm.hardware.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(EncoreColors.Carbon.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        XArenaWordmarkCompact()
        Spacer(Modifier.weight(1f))
        TopStat("\u2764", hw.batteryPercent?.let { "$it%" } ?: "N/A", "Battery")
        Spacer(Modifier.width(18.dp))
        TopStat("\u2699", hw.cpuUsagePercent?.let { "$it%" } ?: "N/A", "CPU")
        Spacer(Modifier.width(18.dp))
        TopStat("\u2600", hw.batteryTempC?.let { "${it.roundToInt()}°C" } ?: "N/A", "Battery Temp")
        Spacer(Modifier.width(18.dp))
        IconButton(onClick = { vm.navigate(AppSection.SettingsManager(SettingsSection.Home)) }) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings Manager", tint = EncoreColors.Silver)
        }
    }
}

@Composable
private fun XArenaWordmarkCompact() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(EncoreColors.NeonRed, CutCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text("XARENA", color = EncoreColors.Silver, fontWeight = FontWeight.Black, fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Text("XTENDED", color = EncoreColors.NeonRed, fontWeight = FontWeight.Black, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Text("GAME SPACE", color = EncoreColors.TextLabel, fontSize = 11.sp, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun TopStat(glyph: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = EncoreColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(label, color = EncoreColors.TextLabel, fontSize = 10.sp)
    }
}

@Composable
private fun GameSpaceBottomBar(current: GameSpaceTab, onSelect: (GameSpaceTab) -> Unit) {
    NavigationBar(
        containerColor = EncoreColors.Carbon,
        contentColor = EncoreColors.TextPrimary,
        tonalElevation = 0.dp,
        windowInsets = NavigationBarDefaults.windowInsets,
    ) {
        GameSpaceTab.entries.forEach { t ->
            NavigationBarItem(
                selected = current == t,
                onClick = { onSelect(t) },
                icon = {
                    Icon(
                        if (t == GameSpaceTab.Library) Icons.Filled.PlayArrow else Icons.Filled.Tune,
                        contentDescription = t.title,
                    )
                },
                label = { Text(t.title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.Black,
                    selectedTextColor = EncoreColors.NeonRed,
                    indicatorColor = EncoreColors.NeonRed,
                    unselectedIconColor = EncoreColors.TextLabel,
                    unselectedTextColor = EncoreColors.TextLabel,
                ),
            )
        }
    }
}

@Composable
private fun GameSpaceLibrary(vm: MainViewModel, games: List<GameEntry>, selectedPkg: String?) {
    val query by vm.query.collectAsState()

    Row(Modifier.fillMaxSize()) {
        // ---- Library sidebar
        Column(
            Modifier
                .fillMaxHeight()
                .width(260.dp)
                .background(EncoreColors.Carbon)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("MY GAMES", style = MaterialTheme.typography.labelMedium, color = EncoreColors.TextLabel)
                Spacer(Modifier.weight(1f))
                Text("${games.size}", color = EncoreColors.Silver, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps", color = EncoreColors.TextLabel) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = EncoreColors.TextLabel) },
                singleLine = true,
                shape = CutCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EncoreColors.NeonRed,
                    unfocusedBorderColor = EncoreColors.HairlineDim,
                    focusedTextColor = EncoreColors.TextPrimary,
                    unfocusedTextColor = EncoreColors.TextPrimary,
                    cursorColor = EncoreColors.NeonRed,
                ),
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(games, key = { it.pkg }) { g ->
                    LibraryRow(game = g, selected = g.pkg == selectedPkg, onClick = { vm.selectGame(g.pkg) })
                }
            }
        }

        // ---- Selected game panel
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val game = games.firstOrNull { it.pkg == selectedPkg }
            if (game != null) {
                SelectedGamePanel(vm, game)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No games detected yet", color = EncoreColors.TextLabel)
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(game: GameEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CutCornerShape(10.dp))
            .background(if (selected) EncoreColors.NeonRed.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(game.pkg, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                game.label,
                color = if (selected) EncoreColors.TextPrimary else EncoreColors.TextLabel,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
            )
            if (!game.enabled) {
                Text("Disabled in Performance", color = EncoreColors.TextLabel, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SelectedGamePanel(vm: MainViewModel, game: GameEntry) {
    val context = LocalContext.current
    val bannerVersion by vm.bannerVersion.collectAsState()
    var sizeBytes by remember(game.pkg) { mutableStateOf<Long?>(null) }

    LaunchedEffect(game.pkg) { sizeBytes = vm.appSizeBytes(game.pkg) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setGameBanner(game.pkg, uri)
    }

    Box(Modifier.fillMaxSize()) {
        GameBanner(context, game, bannerVersion)

        // Banner picker / clear shortcut, top-right of the artwork.
        Row(
            Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundIconButton(ImageIcon, "Change banner") {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            RoundIconButton(Icons.Filled.Tune, "Quick Tuning") {
                vm.selectTuningGame(game.pkg)
                vm.navigate(AppSection.SettingsManager(SettingsSection.Tuning))
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                game.label,
                style = MaterialTheme.typography.headlineMedium,
                color = EncoreColors.TextPrimary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sizeBytes?.let { formatBytes(it) } ?: "Size unavailable",
                    color = EncoreColors.TextLabel,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(10.dp))
                Text("\u2022", color = EncoreColors.TextLabel)
                Spacer(Modifier.width(10.dp))
                Text(if (game.liteMode) "Lite Mode" else "Full Performance", color = EncoreColors.TextLabel, fontSize = 13.sp)
            }
            StartGameButton(game.pkg) { message -> vm.reportLaunchFailure(message) }
        }
    }
}

@Composable
private fun GameBanner(context: android.content.Context, game: GameEntry, bannerVersion: Int) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = game.pkg, key2 = bannerVersion) {
        value = runCatching {
            GameBanners.bannerPath(context, game.pkg)?.let { path ->
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
        }.getOrNull()
    }
    val b = bitmap
    if (b != null) {
        Image(
            bitmap = b,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // Readability scrim over the custom banner.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                ),
            ),
        )
    } else {
        // Graceful fallback when no custom banner is set (or it failed to decode): a branded
        // gradient with the game's own launcher icon, never a blank panel.
        Box(Modifier.fillMaxSize().background(EncoreColors.CarbonGradient), contentAlignment = Alignment.Center) {
            AppIcon(game.pkg, modifier = Modifier.size(120.dp))
        }
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CutCornerShape(8.dp))
            .background(EncoreColors.Carbon.copy(alpha = 0.85f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = EncoreColors.Silver, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun StartGameButton(pkg: String, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier
            .clip(CutCornerShape(10.dp))
            .background(EncoreColors.glow())
            .clickable {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    onFailure("This app has no launchable activity, or it may have been uninstalled.")
                }
            }
            .padding(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            Text("START GAME", color = Color.Black, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
    }
}

/**
 * GameSpace's "Performance" bottom-bar destination (Master Prompt §10/§6G): saved gameplay
 * session analytics. True in-game telemetry needs the Game Bar's background service — which
 * keeps sampling while the actual game, not this app, is in the foreground — so capture isn't
 * wired up yet; this honestly says so instead of showing an empty chart or fabricated numbers.
 */
@Composable
private fun GameSpaceSessionAnalytics() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        NeonPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NO SESSIONS YET", style = MaterialTheme.typography.titleMedium, color = EncoreColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Gameplay session analytics (CPU/temperature/battery averages per game) record " +
                        "via the Game Bar Side Panel while you're in a game. This is planned for the " +
                        "next update \u2014 nothing is shown here until it can be measured for real.",
                    color = EncoreColors.TextLabel,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB installed".format(mb / 1024.0) else "%.0f MB installed".format(mb)
}
