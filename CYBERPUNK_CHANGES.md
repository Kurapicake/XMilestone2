# Encore Manager — Cyberpunk AMOLED Neon Red overhaul

This is the full project with the UI/UX overhaul applied. Unzip it over your existing
`EncoreManager/` checkout (or just open it as-is in Android Studio) and build normally —
nothing else needs to change.

## What changed

### Theme (`ui/Theme.kt`, `res/values/colors.xml`, `res/values/themes.xml`)
- New fixed palette: true `#000000` AMOLED backgrounds, carbon/obsidian elevated surfaces
  (`#0D0D10` / `#141419`), neon red (`#FF1E38`) / electric red (`#FF003C`) / crimson (`#99001A`)
  accents, white (`#F5F5F7`) primary text and slate-grey (`#8E8E93`) labels.
- Angular geometry everywhere via `CutCornerShape`-based Material3 `Shapes` and a reusable
  `NeonPanel` composable (chamfered card + glowing border).
- `PulseDot` gives a soft pulse animation to "live" status indicators (daemon online, overlay
  on, etc.).
- The theme no longer follows the system light/dark setting or dynamic color — it's always the
  same cyberpunk palette, which also happens to be the most battery-friendly choice for AMOLED.

### Webroot assets bundled into `res/drawable/`
Converted from the module ZIP's `webroot/` (AVIF/WebP → PNG, since `minSdk 29` predates
guaranteed AVIF decode support on every device):
- `encore_happy.png`, `encore_sleeping.png` — the mascot, shown live in the Home hero card.
- `app_icon_fallback.png` — now the fallback artwork in `AppIcon` (Games tab) whenever a
  package's real launcher icon can't be loaded, instead of a blank circle.
- `encore_banner.png`, `lite_mode_poster.png`, `device_mitigation_poster.png` — bundled and
  ready to use (e.g. an About screen or a Settings header) but not forced into the current
  screens, to avoid cluttering small text rows with giant illustrations.

### Main app (`ui/Screens.kt`, `ui/AppIcon.kt`)
- **Hero Status Card**: live mascot (happy while the daemon is actively driving a non-idle
  profile, asleep when offline/initializing/Powersave), a pulsing daemon-online indicator, a
  color-coded profile badge, the global Lite Mode switch, and a Device Mitigation indicator.
- Games tab: angular neon-bordered cards, chamfered search field, `app_icon_fallback` fallback.
- Settings tab: now also shows whether `WRITE_SETTINGS` ("Modify system settings") is granted,
  since the GameSpace brightness slider needs it.
- Every screen restyled with the neon palette; no behavior changed versus the original
  `MainViewModel` calls.

### Tuning tab (`ui/TuningScreen.kt`) — "Tactical Tuning Deck"
Reorganized into collapsible, chamfered HUD panels: **CPU Clusters**, **GPU / Memory**,
**Lite Floor** (Lite profile only) and **Process Boost**. Note: `TuningRepository` /
`TuningConf` only expose CPU/GPU/DDR frequency caps, the Lite floor and the process-boost flag
— there's no governor or I/O-scheduler field in `tuning.conf`'s grammar, so no controls for
those were added (adding them would mean writing variables the profiler never reads).

### GameSpace overlay (`gamebar/GameBarOverlay.kt` + new helper classes)
- Minimized pill: black-and-crimson, draggable, docks to the chosen screen edge. Now also
  shows GPU temperature (toggle in Live Monitor) alongside FPS / battery / battery temp / CPU
  temp.
- Expanded panel is a `#050508` 90%-alpha AMOLED "Command Center" with four tabs:
  - **Tweaks** — this game's Lite Mode / Do Not Disturb, plus a global **Disable Tweaks**
    emergency switch.
  - **Hardware** *(new)* — brightness slider + a real Auto-Brightness toggle
    (`Settings.System.SCREEN_BRIGHTNESS` / `_MODE`, via the new `BrightnessController`), media
    volume slider + Quick Mute (`AudioManager.STREAM_MUSIC`, via `VolumeController`, flag `0` so
    it never pops the system volume UI), and the crosshair controls below.
  - **Live Monitor** — choose what the pill shows (now including GPU temp).
  - **Quick Actions** — screenshot, clear background apps (unchanged).
- A live telemetry strip (FPS / CPU load / RAM / Temp) sits under the header at all times.
  CPU load is a real `/proc/stat` delta sample, RAM from `/proc/meminfo` (both new
  `MetricParsers` functions + `MetricsSampler` methods, same "parse → sampler → null-safe"
  pattern as the existing FPS/temperature code).
- **Crosshair overlay** *(new, `gamebar/CrosshairView.kt`)*: an independent window, Dot / Cross
  / Circle-Dot style, 8–64dp size, Neon Red / Cyan / Lime color — toggled and configured live
  from the Hardware tab. Its window flags are
  `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`, deliberately different from the handle/pill/panel
  (`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`), so it is the one overlay that **never**
  intercepts a touch, even inside its own full-screen bounds — a game underneath it always gets
  every touch.
- The crosshair (and the pill/handle) are hidden for the ~400ms around a screenshot so they
  never show up in the saved PNG, same as the existing handle/pill hide-before-capture logic.

### Honest scope notes
- **No fabricated daemon API.** `encored`'s profile (Performance/Balanced/Powersave) is chosen
  automatically; nothing in the module's scripts exposes a manual "force this profile" command.
  So the Tweaks tab's "quick toggles" are the two real override points the daemon already reads
  — per-game `lite_mode` and the global `disable_tweaks` flag — not a fake 4-state switch. The
  *live, automatically-chosen* profile is shown as a read-only telemetry line instead.
- **Edge-docking, not spring physics.** The handle/pill "edge-snapping" is the existing
  docked-to-one-edge behavior (drag only changes its position along that edge); there's no
  spring-physics fling animation. Said plainly here rather than overclaiming it.
- GPU temperature reuses the same generic `/sys/class/thermal` zone scan as CPU temperature,
  just matching zone names containing "gpu" — like the existing CPU scan, it returns `null`
  (shown as `--`) on a device with no matching zone, rather than guessing.

## New files
```
app/src/main/java/dev/encore/manager/gamebar/BrightnessController.kt
app/src/main/java/dev/encore/manager/gamebar/VolumeController.kt
app/src/main/java/dev/encore/manager/gamebar/CrosshairView.kt
```

## Files changed
```
app/src/main/java/dev/encore/manager/ui/Theme.kt
app/src/main/java/dev/encore/manager/ui/Screens.kt
app/src/main/java/dev/encore/manager/ui/TuningScreen.kt
app/src/main/java/dev/encore/manager/ui/AppIcon.kt
app/src/main/java/dev/encore/manager/gamebar/GameBarOverlay.kt
app/src/main/java/dev/encore/manager/gamebar/GameBarSetup.kt   (writeSettings status + grant,
                                                                 crosshair/pre-mute prefs)
app/src/main/java/dev/encore/manager/gamebar/MetricParsers.kt  (+ RAM, CPU-load, GPU-temp parsing)
app/src/main/java/dev/encore/manager/gamebar/MetricsSampler.kt (+ RAM, CPU-load, GPU-temp sampling)
app/src/main/java/dev/encore/manager/State.kt                  (+ GameBarStatus.writeSettings)
app/src/main/AndroidManifest.xml                                (+ WRITE_SETTINGS permission)
app/src/main/res/values/colors.xml
app/src/main/res/values/themes.xml
app/src/main/res/drawable/ic_launcher_foreground.xml            (white → neon red)
```
`GameBarService.kt`, `MainViewModel.kt`, `EncoreRepository.kt`, `TuningRepository.kt`,
`TuningConf.kt` were **not** modified — everything above builds on their existing public API.

## A note on verification
This was written and carefully reviewed line-by-line (including a manual check of every
Material3 API name used and a bracket/paren balance pass over every file), but this sandbox
has no Android SDK / Gradle network access, so it could not be compiled here. Build it locally
first and let me know if anything doesn't come up clean.

## Overlay refinement (compact HUD pill)

- The red edge handle is gone. The monitor pill is now the only always-on element: drag it to
  move it, tap it to open the Command Center panel (always docked to the right edge).
- The pill is a small, translucent "dark glass" capsule: CPU usage %, FPS,
  temperature (battery) and battery % + time. Cells are toggled under Live Monitor; CPU/GPU
  temperature remain available there as optional extra cells (off by default).
- CPU usage is the real /proc/stat delta from `MetricsSampler.cpuLoadPercent()`; `--` until
  two readings exist or when it cannot be read.
- The Game Bar switch was removed from the Home screen. It is still in Settings → Game Bar.

## Final HUD refinement

- Pill: GPU clock removed; the CPU cell shows real CPU usage instead of the clock speed.
- The pill is removed while the Command Center panel is open and rebuilt at its saved position
  when the panel closes (`openPanel()` / `closePanel()` in `GameBarOverlay.kt`).
- The panel is fixed to the right edge; the Panel edge setting is gone.
- Device Mitigation and Disable Tweaks switches removed from Settings (config keys and the
  panel's own Disable Tweaks switch untouched).

## 1.2.1 fixes
- Game Launch Sound removed (setting, SoundPool code and `res/raw/engine_start_rev.wav`).
- Game Bar service is restarted on app open, after an app update and after boot; root fallback if
  the system refuses the start; background appops granted with the permissions.
- A failed gamelist read is retried instead of being taken as "not a game"; non-game apps are rechecked.
- Overlay creation is retried every few seconds if it failed.
- FPS: a layer whose newest frame stopped moving is no longer reported; the live layer is re-searched.
- Settings > Game Bar status shows service / foreground app / list / overlay / FPS layer / last error.
