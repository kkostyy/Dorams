# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

The repo root is the Android project (originally shipped as `DoramaCutter.zip`, which is git-ignored and outdated — edit the extracted files, not the zip). Pushing to GitHub triggers the APK build.

`build.yml.txt` is a copy of `.github/workflows/build.yml`, kept so the user can paste it by hand when the GitHub web uploader skips the hidden `.github` folder. **Keep the two files identical.**

## Build

The user has no Android Studio or local toolchain. APKs are built by GitHub Actions (`.github/workflows/build.yml`) on every push: JDK 17 (Temurin), Gradle 8.9, `gradle assembleDebug --no-daemon --stacktrace`. The result is uploaded as artifact `DoramaCutter-apk` (`app/build/outputs/apk/debug/app-debug.apk`). The repo has **no Gradle wrapper**, so a local build needs a system Gradle 8.9 plus the Android SDK:

```
gradle assembleDebug --no-daemon --stacktrace
```

There are no tests and no lint setup. The user sends build errors by copying them from the Actions "Build" step, so fixes must compile without being run locally. Double-check Media3 API signatures against the pinned version.

Versions: AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, targetSdk 34, minSdk 29 (scoped storage / `MediaStore.RELATIVE_PATH` with no storage permissions), Media3 1.6.1 (transformer, effect, common), Guava.

## Architecture

The whole app is one file: `app/src/main/java/com/dorama/cutter/MainActivity.kt`. It has no XML layouts, resources, AndroidX AppCompat or coroutines. The UI is built in code inside a `ScrollView`/`LinearLayout`, and background work uses plain `Thread` + `runOnUiThread`. All UI strings and the Gemini prompt are in Russian.

Purpose: cut a long landscape drama episode into TikTok-sized vertical parts with text overlays, plus caption text files.

Flow:
1. **Pick video** (`ACTION_OPEN_DOCUMENT`): reads duration via `MediaMetadataRetriever` and sanitizes the file name into `baseName`.
2. **Plan** (`planParts`): splits by "max minutes per part" (minus 1 s of slack). With AI hooks, it cuts at the highest-`score` cliffhanger within `WINDOW_MS` (10 min) before each limit, otherwise at the limit. The plan is recomputed live as the max-minutes field changes.
3. **Optional AI analysis (Gemini REST, raw `HttpURLConnection`)**:
   - Media3 `Transformer` extracts the audio to AAC/mp4 in `cacheDir`.
   - `toAdts` rewraps it as an ADTS `.aac` stream by hand-building 7-byte headers from `csd-0`.
   - The stream goes up through the resumable Files API upload, then the app polls until the file is `ACTIVE`.
   - `generateContent` runs with a JSON response; `thought` parts are skipped.
   - `applyAi` parses `cliffhangers` (`HH:MM:SS`), `first_hook`, `hashtags` and `summary`.
   - The API key and model name are stored in SharedPreferences `"s"`; the model name is user-editable because model IDs change.
4. **Cutting**: parts run one at a time (`startPart` → `onCompleted` → `saveAndNext` → next). Each part is a clipped `MediaItem` with these effects:
   - rotate 270° → `Presentation` 1080x1920 → `OverlayEffect`.
   - The overlay is `PartOverlay`, which swaps between three pre-rendered full-frame bitmaps by presentation time: intro "ПОВЕРНИТЕ ЭКРАН" + hook for the first `INTRO_S`, a part label in the middle, and an end screen (teaser / "next part" / Telegram) for the last `END_S`.
   - Labels are drawn in landscape coordinates (`landscapeLayer` rotates the canvas 90°) so they line up with the rotated video. The intro text is drawn upright.
5. **Output**: each part is copied from `cacheDir/part.mp4` to MediaStore `Movies/DoramaCutter/<base>_partNN.mp4`. Captions for all parts go to `Download/DoramaCutter/<base>_описания.txt` and are also shown on screen.

State notes: `transformer`, `aiMode`, `running` and `cancelled` are shared between the AI and cut flows. The `poll` runnable reports progress every 500 ms for whichever Transformer is active, so reset these consistently (see `fail` / `onStopClick`). The activity keeps the screen on, and the work does not survive the activity being destroyed. No foreground service is used.
