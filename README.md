# NU Rec Widget

Android home-screen widget showing live occupancy for three Northeastern rec spaces:

- Marino Center: 2nd Floor, 3rd Floor Weight Room
- SquashBusters: 4th Floor

Data comes from the unofficial `https://nu-rec-board.netlify.app/api/live` (upstream refreshes about every 30 min).

## What it does

- **Large widget (4×2):** one ring gauge per room, percent inside, "count of capacity" below.
- **Small widget (2×2):** rooms sorted busiest first, big colored percent plus a bar.
- **Colors:** lime < 40%, amber 40–70%, coral > 70%, grey = closed / unknown / offline.
- **Refresh:** automatic every 30 min on any network, plus a tap-to-refresh button.
- **Cooldown:** after any fetch (tap, app button or automatic), refresh is locked for 2 minutes. The widget shows `2m`/`1m` with a draining ring; the app shows `Wait 1:42`.
- **Offline:** the last good response is cached. If a fetch fails or the data is > 2 h old, the chip switches from `LIVE` to `OFFLINE` and the rings go grey. Nothing blanks.

To change which rooms are shown, edit `RoomFilter.SHOWN` in
`app/src/main/java/dev/nurec/widget/data/Models.kt`.

## Build (WSL / Linux)

Requirements: JDK 17, Android SDK (platform 37, build-tools, platform-tools).

```bash
export JAVA_HOME="$HOME/.local/jdk/jdk-17.0.21.1+2"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# once: SDK packages (sdkmanager is now the `android` CLI in cmdline-tools)
android --sdk=$ANDROID_HOME sdk install platform-tools "platforms;android-37.2" "build-tools;37.0.0"

./gradlew testDebugUnitTest    # unit tests (JSON parsing, stale + cooldown logic)
./gradlew assembleDebug        # APK
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

`local.properties` must contain `sdk.dir=/home/<you>/Android/Sdk` (not committed).

## Install

With adb (Windows `adb.exe`, phone over USB, USB debugging on):

```powershell
adb devices
adb install -r \\wsl$\Ubuntu\home\<you>\projects\nu-rec-widget\app\build\outputs\apk\debug\app-debug.apk
```

Without adb: copy `app-debug.apk` to the phone, open it in Files, allow "Install unknown apps" for Files when asked, tap Install.

Then: long-press the home screen → Widgets → NU Rec → drag it out.

## Layout

| Path | What |
|---|---|
| `data/Models.kt` | JSON DTOs (all fields nullable), parser, room allowlist |
| `data/UiState.kt` | Stale check, cooldown, "Updated HH:MM" text (pure Kotlin, unit-tested) |
| `data/Repository.kt` | OkHttp fetch + DataStore cache + cooldown slot |
| `work/RefreshWorker.kt` | WorkManager: 30-min periodic, manual one-shot, cooldown-end redraw |
| `widget/RecWidget.kt` | Glance widget, receiver, refresh action |
| `ui/MainActivity.kt` | Launcher screen (Compose) |
| `ui/Palette.kt` | Colors + ring bitmap for Glance |

## Fonts

Barlow Condensed and Archivo are bundled in `app/src/main/res/font/` under the SIL Open Font
License 1.1 (see `licenses/`). Glance widgets can only use system fonts, so the widget draws its
text to bitmaps with these fonts (`ui/Fonts.kt`, `TextBitmap`); each image carries the text as its
accessibility description.
