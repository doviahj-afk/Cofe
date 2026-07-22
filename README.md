# Screen Recorder (Galaxy A15)

A screen recorder Android app built with **Capacitor** (JS/HTML frontend + a
small native Kotlin plugin). Features:

- Start/stop screen recording, including a **Quick Settings tile** so you can
  record without opening the app
- Settings: **FPS** (15/30/60), video quality, **storage location**
  (default `Movies/ScreenRecorder`, or a custom folder you pick), **theme**
  (light/dark/system), optional microphone audio
- Built entirely via **GitHub Actions** — no Android Studio required

## How it's structured

```
www/                  → the app UI (plain HTML/CSS/JS, no framework)
native-src/            → the native Kotlin source (source of truth, hand-edited)
scripts/patch-android.js → injects native-src/ into the Capacitor-generated
                           android/ project during CI (android/ itself is
                           NOT committed — it's regenerated every build)
.github/workflows/build.yml → CI: installs deps, generates the android
                              project, patches it, builds a debug APK
```

`android/` is git-ignored on purpose. Capacitor's CLI (`npx cap add android`)
regenerates the whole native shell fresh every time, and the patch script
re-injects your custom plugin/tile/service files into it. This means there's
never a stale, half-hand-edited Android project to fight with — the Kotlin
files in `native-src/` are the only thing you ever need to edit.

## Getting the APK

1. Push this repo to GitHub.
2. Go to the **Actions** tab → the `Build APK` workflow runs automatically
   on every push to `main` (or trigger it manually with "Run workflow").
3. When it finishes, open the run → **Artifacts** → download
   `screen-recorder-debug-apk`. Unzip it to get `app-debug.apk`.
4. Transfer the APK to your Galaxy A15 (e.g. via a GitHub release download
   link, Google Drive, or `adb install`) and install it. You'll need to allow
   "Install unknown apps" for whichever app you use to open the file.

This produces a **debug** APK, which installs and runs fine for personal use.
If you later want a signed **release** APK (smaller, no debug banner), say so
and I'll add a signing step that reads a keystore from GitHub Secrets.

## Local development (optional)

If you ever want to iterate on the `www/` UI without waiting on CI:

```bash
npm install
npx cap add android      # generates the android/ folder locally
node scripts/patch-android.js
npx cap sync android
npx cap open android     # opens in Android Studio, if installed
```

This also works from Termux with the Android SDK command-line tools
installed, though Android Studio on a PC is far less fiddly for local
debugging. For day-to-day use, you don't need this at all — GitHub Actions
does the whole build for you.

## How the pieces fit together (native side)

- `ScreenRecorderPlugin.kt` — the Capacitor bridge exposed to JS as
  `Capacitor.Plugins.ScreenRecorder` (`startRecording`, `stopRecording`,
  `getState`, `getSettings`, `setSettings`, `pickStorageFolder`)
- `RecordingService.kt` — foreground service that owns the actual
  `MediaProjection` → `MediaRecorder` pipeline and writes the MP4, either to
  `MediaStore` (default) or a `DocumentFile` in your chosen SAF folder
  (custom)
- `RecordingTileService.kt` — the Quick Settings tile; reflects
  recording/idle state and starts/stops the service
- `CaptureConsentActivity.kt` — an invisible trampoline activity used only
  when you tap the tile while the app isn't open, since Android requires an
  Activity context to show the "Start recording?" system permission dialog
- `Prefs.kt` — settings storage (`SharedPreferences`), shared by the JS
  plugin and the recording service

## Known limitations / next steps

- The tile always re-prompts the system screen-capture consent dialog when
  starting from a cold state — this is an Android security requirement
  (MediaProjection grants aren't reusable across sessions), not a bug.
- No pause/resume yet — only start/stop.
- No in-app video gallery/player yet — recordings show up in your device's
  Gallery/Files app under `Movies/ScreenRecorder` (or your chosen folder).
- Minimum Android version: 8.0 (API 26). Your A15 ships with Android 14/15,
  so you're well within range.

Let me know if you want pause/resume, an in-app recordings list, or a signed
release build added next.
