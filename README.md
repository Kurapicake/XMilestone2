# Encore Manager

A native Android front end for the **Encore Tweaks (X6873)** root module, replacing its WebUI.
The module itself (daemon, profiles, scripts) is untouched. This app only does what the WebUI did:
it edits the same JSON files and runs the same few shell commands through a root shell.

Tested target: KernelSU Next on Infinix GT 30 Pro. Should also work on Magisk / KernelSU / APatch.

## Features
- **Home**: daemon status and PID, active profile (Performance / Balanced / Powersave), module version, chipset, kernel
- **Games**: per-app enable switch, Lite Mode, Do Not Disturb, Launch, App info (reads/writes `gamelist.json`)
- **Tuning**: Balanced CPU cluster / GPU / DDR frequency caps (global) saved to `tuning.conf`; Performance and Lite caps
  (and Lite floor) per game, saved in that game's `gamelist.json` entry and mirrored to `game_tuning/<package>.conf`, which the module's
  `encore_profiler` reads for the game in front at the moment it applies a profile (needs the patched module); game-process boost, read-only Preview and Verify
- **Settings**: Enforce Lite Mode, Device Mitigation, Disable Tweaks, Log Level, Save Log, Game Bar options
- **Game Bar**: monitor pill and panel over games from your Encore list. Tabs for Tweaks (per-game Lite Mode, Do Not Disturb),
  Hardware (brightness, volume, crosshair), Live Monitor (choose what the pill shows) and Quick Actions (screenshot,
  clear background apps). Switched on or off in Settings > Game Bar
- **Monitor pill**: small translucent strip with prime-core CPU frequency, FPS, battery temperature, battery level and clock (CPU / GPU temperature optional)
- Drag the pill anywhere, or the panel by its header. Positions are remembered (Settings > Game Bar > Reset positions)
- **Logs**: last 300 lines of `encore.log`
- **Quick Settings tile**: toggles Enforce Lite Mode

## Build

Needs JDK 17 and an Android SDK (platform 35). Easiest options:

1. **GitHub Actions** (no local setup): push this folder to a GitHub repo. The workflow in
   `.github/workflows/build.yml` builds the APK. Download it from the run's *Artifacts*.
2. **Android Studio**: open this folder, then *Build > Build APK(s)*.
3. **Command line**: `./gradlew assembleRelease`, output in `app/build/outputs/apk/release/`.

The APK is signed with the key in `signing/` so later builds update the installed app in place.
The key is public in this repo; generate your own if you ever publish the app.

## Install
1. Keep the Encore Tweaks module installed.
2. Install the APK.
3. Open Encore Manager. KernelSU Next shows a root prompt, or go to **KernelSU Next > Superuser**
   and enable Encore Manager. Then tap Retry in the app.

## Game Bar setup
1. Settings > Game Bar > turn on **Show Game Bar**. The app grants itself overlay, usage access, notification and
   battery-optimization permissions through root (or tap *Permissions* to do it again).
2. Open a game that is enabled in the Games tab. The monitor pill appears; tap it to open the panel. Keep
   Settings > Game Bar > **Monitor pill** on, because turning it off removes the way to open the panel. Its FPS and
   temperature readings are best effort and show `--` when the phone does not give a readable value. Battery numbers always work.
3. Game detection uses Android usage stats, and the bar only appears for packages in `gamelist.json`.
4. The bar restarts after a reboot while the switch is on.

## Tuning tab
Edits `/data/adb/.config/encore/tuning.conf`, the override file documented in `encore_profiler`. Only the values you
change are written, everything else in the file is left as it is. The first time, the original is kept as `tuning.conf.bak`.
Values are 30 to 100 percent. Encore re-reads the file on every profile change, so changes apply the next time a profile
switches. There is no "apply now" button on purpose: running the profiler by hand would skip the environment variables
the daemon sets for it.

## What it touches (same as the WebUI)
| What | How |
|---|---|
| `/data/adb/.config/encore/config.json` | read, edit `preferences.*`, write back in place. Other keys (e.g. `cpu_governor`) are preserved |
| `/data/adb/.config/encore/gamelist.json` | add/remove `pkg`, set `lite_mode` / `enable_dnd`. Unknown fields are preserved |
| `/data/adb/.config/encore/tuning.conf` | edit only the managed variables, atomic write, one-time `.bak` |
| `current_profile`, `soc_recognition`, `module.prop`, `encore.log` | read only |
| `encore_profiler debug` / `verify` | run read-only for Preview / Verify |
| `appops`, `pm grant`, `dumpsys deviceidle`, `screencap`, `am kill-all` | Game Bar permissions and actions |
| `dumpsys SurfaceFlinger --list` / `--latency`, `/sys/class/thermal/*` | monitor pill (FPS, CPU temperature), read only |
| `toybox pidof encored`, `encore_utility save_logs` | executed as root |

A file that is missing or not valid JSON is never overwritten.

## Credits
Encore Tweaks by Rem01Gaming (Apache-2.0). Root access via libsu by topjohnwu (Apache-2.0).
See `NOTICE`.
