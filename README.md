# Renderify v3.0 — HTML → MP4 offline on Android

**Source project (not a precompiled APK).** Build the Android app with the included GitHub Actions workflow. No server account or screen-recording permission is used.

## What's improved in v3

- Repairs the H.264 discovery path that previously rejected codecs because they were not labelled hardware-accelerated or did not accept a Surface.
- Probes **all** advertised AVC encoders and tries candidates in order. Auto prefers a non-software GPU/Surface encoder, then other available AVC candidates. Hardware mode excludes software-only codecs; Software mode uses codecs declared software-only by Android.
- Supports `COLOR_FormatSurface` input through OpenGL ES/EGL, plus YUV420 **planar**, **semiplanar**, and **flexible** input through `MediaCodec` buffer/image APIs when available.
- Cleans up a failed EGL initialization before trying another encoder.
- Adds a **Periksa encoder HP** button, displaying candidate codec names for the chosen size, frame rate, and encoder mode. These are capability *claims*, not guarantees of successful encoding.
- Handles limited **width/height alignment** differences of at most 32 pixels and 1% aspect-ratio difference. No automatic large quality downgrade.
- Adds **QHD / 2K (1440p)** and **4K UHD (2160p)** alongside 540p, 720p, and 1080p; keeps 24, 30, and **60 FPS**, three aspect ratios, and the editor's HTML import and live preview.
- Improved errors for missing encoder, codec configuration, and insufficient frame memory.

## Render pipeline

```text
HTML/CSS/JS → Chromium-based WebView (visible app window)
           → PixelCopy (own WebView region only)
           → Bitmap at requested output pixel dimensions
           → OpenGL/EGL + H.264 Surface input (preferred)
              OR CPU YUV420 conversion + H.264 ByteBuffer input
           → Android MediaCodec + MediaMuxer
           → Movies/Renderify/*.mp4
```

This app deliberately uses native Android **MediaCodec + MediaMuxer** instead of bundling FFmpeg. It does **not** use MediaProjection or screen recording and requires no Internet permission. Android 10+ is required.

## Resolution and performance limitations

**Important:** 2K and 4K are *encoded output dimensions*, **not guaranteed 2K/4K native HTML detail**. The source WebView is a visible surface sized according to the phone's screen. PixelCopy may upscale it. The render currently runs in wall-clock time; if the phone can't capture and encode in time, video frames can repeat. This is not a deterministic frame-by-frame desktop Chromium renderer. **4K/60 is a conditional capability, not a promise**; some phones will fail codec checks, run out of memory, overheat, or produce choppy animation. Software mode can be much slower, especially at 1080p and above.

The app does not import linked external HTML assets or load internet resources. Prefer inline CSS/JS and data URLs; HTML file size max 1 MB. Audio is not rendered. The H.264 output is opaque MP4.

## Build with GitHub Actions

1. Extract this ZIP on your device or PC, then upload **the contents** to the root of `Ihsan113/RenderVideo` (not into an extra folder). Preserve `.github/workflows/build-apk.yml`.
2. Commit changes and allow **Actions → Build Android APK** to run, or trigger manually with **Run workflow**.
3. If successful, download the workflow **Artifacts → Renderify-Android-debug**, unzip and install `app-debug.apk`.
4. App version in this ZIP: `3.0.0` (`versionCode=3`).

Because GitHub's browser uploader doesn't delete old tracked files, remove `app/src/main/java/id/danzku/renderify/CaptureService.java` from your GitHub repository if it remains. The workflow also removes that obsolete file *from the build runner* before validation. Read `MIGRATION_V1_TO_V2.md`.

CI runs Python structural checks and source-level regression tests before Gradle APK compilation.

## First on-device tests

1. Tap **Periksa encoder HP** with **720p, 30 FPS, 9:16, Auto**; verify codec names appear.
2. Render the included payment demo at **720p / 30 FPS / Auto**.
3. Try **540p / Hardware**, **720p / Software**, then **1080p / Auto**.
4. If those work, try **1440p/30** and **2160p/30**; only then try **2160p/60** if diagnostics list capable encoders.
5. Open the saved MP4 in Gallery, inspect duration, aspect ratio, text sharpness, animation timing, and any repeated frames.
6. If a codec fails, share the exact error text and **Periksa encoder HP** result, along with Android version and phone chipset; vendor MediaCodec implementations vary.

## Project files

- `MainActivity.java`: HTML paste/file picker, preview, quality, aspect, FPS, codec selection, and diagnostics.
- `RenderActivity.java`: WebView render screen, timing trigger, MediaStore output, and lifecycle.
- `FrameRenderEngine.java`: PixelCopy and controlled video presentation timestamps.
- `GpuFrameEncoder.java`: codec enumeration, candidate fallback, EGL/OpenGL, YUV420, H.264, muxing.
- `tests/test_source_contract.py`: local source regression tests.
- `validate_project.py`: structural/source verification (not a Java compiler).
- `.github/workflows/build-apk.yml`: build via Java 17, Gradle 8.11.1, Android plugin 8.10.1.

## Test honesty

Source-level validation is not equivalent to Gradle compilation. **No APK compilation, emulator run, or hardware encoding test was performed here**, because Android SDK and a connected device are unavailable in this environment. The GitHub Action must prove compilation, and an actual Android phone must verify render output.
