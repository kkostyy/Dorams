# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

The repo root is the Android project, pushed to https://github.com/kkostyy/Dorams. It was originally shipped as `DoramaCutter.zip`, which is git-ignored and outdated, so edit the extracted files, not the zip. Every push triggers the APK build.

`build.yml.txt` is a copy of `.github/workflows/build.yml`, kept so the user can paste it by hand when the GitHub web uploader skips the hidden `.github` folder. **Keep the two files identical.**

The user speaks Russian, and all UI strings and the Gemini prompt are in Russian.

## Build

CI (`.github/workflows/build.yml`) runs on every push: JDK 17, Gradle 8.9, `gradle assembleDebug --no-daemon --stacktrace`. The APK is uploaded as the Actions artifact `DoramaCutter-apk`. The repo has no Gradle wrapper. Build-status checks use the public API without auth (`curl https://api.github.com/repos/kkostyy/Dorams/actions/runs?per_page=1`). `gh` is not installed.

Local build on this machine. It uses Android Studio's JDK 21 and the Gradle 8.14.3 distribution cached in `~/.gradle/wrapper/dists`. `local.properties` points to `%LOCALAPPDATA%/Android/Sdk` and is git-ignored. The system `JAVA_HOME` points to a missing JDK 11, so override it:

```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
"$(ls -d ~/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3)/bin/gradle" assembleDebug --no-daemon -q
```

There are no tests and no lint setup. The local AVD `Medium_Phone_API_36.1` hung in `adb` "offline" during cold boot once, so don't count on it.

Versions: AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, targetSdk 34, minSdk 29 (scoped storage / `MediaStore.RELATIVE_PATH` / `IS_PENDING` without storage permissions), Media3 1.6.1 (transformer, effect, common).

## Architecture

The app uses plain `Activity` and has no AndroidX AppCompat, XML layouts or coroutines. The UI is built in code, and background work uses `Thread` + a main-thread `Handler`. Package: `app/src/main/java/com/dorama/cutter/`.

Purpose: cut a long drama episode into TikTok-sized vertical 1080x1920 parts with text overlays, plus caption text for each part.

- **`Engine`** (singleton): runs the jobs independently of the screen, inside **`CutService`**. The service is a foreground `dataSync` service with a progress notification and a "Stop" action, and it holds a wake lock.
  - The flow is `Engine.startCut/startAi` → `startForegroundService` → `CutService.onStartCommand` → `Engine.attach(service)` → the job runs → `finish()` → `service.finished()` → `stopSelf`.
  - Every job bumps `gen`. Every async callback (Transformer listener, network thread via `post(g)`) checks `g == gen`, so a stopped or restarted job's callbacks become no-ops. Keep this pattern when adding async steps.
  - `stop()` cancels the Transformer and disconnects the tracked `HttpURLConnection` (on a background thread).
  - The engine exposes plain state (`kind`, `progress` with `-1` meaning indeterminate, `status`, `isError`, `captions`, `lastJob` + `resumeFrom`, `aiResult`) plus `listen()` callbacks on the main thread. The activity re-renders from this state, so the activity can be destroyed and recreated mid-job.
- **Cutting**: parts run one at a time (`startPart` → `onCompleted` → `savePart` on a thread → next `startPart`).
  - Effects: a 270° rotation (only when `landscape`), then `Presentation` 1080x1920, then `OverlayEffect(PartOverlay)`. The encoder bitrate is set via `DefaultEncoderFactory` + `VideoEncoderSettings`.
  - `saveToMedia` writes with `IS_PENDING=1` and deletes the entry on failure or cancel.
  - Failures and stops set `resumeFrom`. The UI offers "Продолжить с части N" ("continue from part N") only if the same video and an identical plan (`List<Part>` equality) are still selected.
- **AI** (`Gemini.kt`):
  1. Transformer extracts the audio.
  2. `Gemini.toAdts` rewraps it as ADTS by hand-building 7-byte headers.
  3. The file goes up through the resumable Files API upload with progress, then the app polls until it's `ACTIVE`.
  4. `generateContent` runs with a JSON response; `thought` parts are skipped.
  5. The uploaded file is deleted.
  6. `parseAi` builds the result.
- **Plan** (`Model.kt`):
  - `autoCuts` splits at the "max minutes" limit (minus 1 s). With AI hooks, it cuts at the highest-`score` cliffhanger within `WINDOW_MS` before each limit instead.
  - `partsFrom` turns `Cut`s into `Part`s. A cut's `nextHook` becomes the next part's opening title.
  - Manual edits from the part bottom sheet are stored in `Session.manualCuts`, which overrides `autoCuts` until reset.
  - `Session` (singleton) holds the picked video and plan state. The picked URI gets `takePersistableUriPermission` so the service can still read it after the activity is gone.
- **Overlays** (`Frames.kt`): three pre-rendered bitmaps per part that `PartOverlay` swaps by presentation time: an intro for the first `INTRO_S`, a part label in the middle, and an end screen for the last `END_S`.
  - For landscape sources, labels are drawn in rotated 1920x1080 coordinates and the intro says "ПОВЕРНИТЕ ЭКРАН" ("rotate your screen").
  - Portrait sources are not rotated, and labels stay clear of TikTok's bottom UI.
- **UI** (`Ui.kt`, `MainActivity.kt`) copies the look of the user's other app at `E:\LOGOPED`, a Capacitor web app whose CSS lives in `index.html`.
  - Look: soft background, 14dp rounded cards, uppercase muted section headers, pill chips, full-width 12dp buttons, bottom-sheet dialogs (`Sheet`), a pill toast, and a fixed bottom bar with status, progress and Start/Stop buttons.
  - Colors are tokens in `res/values/colors.xml` and `res/values-night/colors.xml` (LOGOPED palette, automatic dark theme). Use `ui.*` colors and helpers instead of hard-coding colors.

Output: parts go to `Movies/DoramaCutter/<base>_partNN.mp4`, and captions go to `Download/DoramaCutter/<base>_описания.txt`. Settings (title, Telegram, hashtags, max minutes, bitrate, API key, model) persist in SharedPreferences `"s"`.
