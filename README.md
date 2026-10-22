# Renderify 2.0 — HTML → MP4 (Android, offline)

**Android Studio / GitHub Actions project, NOT a compiled APK.**

Paste HTML into the editor or choose an `.html` file. Preview it locally, pick the
aspect ratio (9:16, 16:9, 1:1), output size (540p, 720p, 1080p), frame rate
(24/30/60 FPS), duration (2–120 seconds), and encoder preference (Auto,
Hardware, Software). Start the export. MP4/H.264 files are saved in
`Movies/Renderify` via Android MediaStore. The built-in demo animates a 10-second payment.

## What's changed from v1

- Replaced the **MediaProjection / MediaRecorder screen-recording service** with
  frame capture of **only Renderify's own visible WebView**, using Android
  `PixelCopy.request(Window, Rect, Bitmap, ...)`.
- Encodes via `Bitmap → OpenGL ES 2 texture → EGL input surface →
  MediaCodec (hardware H.264 where available) → MediaMuxer → MP4`.
- No screen recording dialog. No screen/mediaProjection/Internet permission.
- Keeps rendering locally in the foreground; leaving this screen cancels export.
- Auto prefers a hardware H.264 encoder, with software fallback if the device
  offers a compatible surface-input encoder. The Hardware preference also
  falls back to software for compatibility; the Software preference selects
  a non-hardware encoder. A codec may not support every size/FPS.
- Correctly renamed the former custom `setImmersive` helper to
  `applyFullscreenMode` to avoid an Android Activity Java conflict.
- Preview, paste editor, file chooser and example HTML remain available.

## How to build through GitHub Actions

1. Extract the ZIP **first**. Upload its **contents** to your repo root
   (not inside another nested folder). The root should contain `app/`,
   `.github/workflows/build-apk.yml`, `settings.gradle`, etc.
2. Commit and push to `main`, or visit **Actions → Build Android APK → Run workflow**.
3. On successful completion download artifact `Renderify-Android-debug`, unzip
   it and install `app-debug.apk`.

This project uses Java 17, Android Gradle Plugin 8.10.1, Gradle 8.11.1,
compile/target SDK 35, min SDK 29, and no third-party runtime dependencies.

## Render behavior and limitations (important)

- **Not a full headless desktop Chromium renderer.** Android WebView is
  Chromium-based, rendered *on the Android display* and sampled using PixelCopy.
  This is not Playwright and is not desktop Chromium's deterministic virtual-time capture.
- **Not FFmpeg.** We intentionally use Android's native `MediaCodec + MediaMuxer`
  instead of bundling FFmpeg for H.264 hardware acceleration, smaller APK,
  and simpler builds. An FFmpeg native pipeline for optional post-processing
  could be a separate later feature, but is not in this release.
- A **GPU assists WebView drawing and OpenGL transfer**, but PixelCopy involves
  an intermediate Bitmap and CPU/GPU memory traffic. It is not zero-copy.
- Frame capture is **real-time**, not frame-perfect deterministic rendering.
  If the phone cannot sample every requested frame at the requested rate,
  the last sampled image is **repeated** to fill skipped frame indices, keeping
  nominal MP4 duration and CFR. Fast animation may appear to stutter.
- Output *pixel dimensions* can be 1080p, but source detail comes from the
  physical size of the visible WebView; upscaling cannot create extra detail.
  Landscape mode rotates the activity for a larger visible viewport.
- HTML supports inline CSS/JavaScript. Local files referenced by relative paths,
  external URLs, remote fonts, and network media are not imported or fetched.
  The file picker imports the HTML **text only**, maximum 1 MB.
- Autoplay CSS animation starts at page load; JS actions triggered via the
  custom script start just before capture. Captured first frame timing is
  approximate; page load plus capture-start setup introduces a small offset.
- WebGL, video tags, DRM, high frame rate, 1080p/60, and heavy animations are
  **device-dependent** and require testing on real hardware.
- Audio tracks and transparent MP4 are **not supported** in this initial version.
- For privacy, Renderify cancels capture if it moves to background; keep screen
  on and application visible throughout the export.

## Source tree

```
app/src/main/java/id/danzku/renderify/
  MainActivity.java       HTML editor, import, preview, render settings
  RenderActivity.java     Full-screen WebView, output and lifecycle
  FrameRenderEngine.java  PixelCopy sampler and timing
  GpuFrameEncoder.java    EGL/OpenGL ES texture → H.264 → MP4
  Ui.java                 Responsive Android UI helpers
app/src/main/assets/payment_demo.html
.github/workflows/build-apk.yml
validate_project.py
branding/
```

## Testing checklist

Run `python3 validate_project.py`; GitHub Actions compiles the Java code to an APK.
On a real **Android 10+** phone, test 9:16 720p/30 then 16:9 540p/24 then
1:1 1080p/30 and 1080p/60 if supported. Confirm exported MP4 playback,
length, rotation, no view overlays, output `Movies/Renderify`, interruption on
Back/Home, software fallback, and support for the chosen HTML animation.

**Build and device runtime have not been verified in the authoring environment**
(no Android SDK/emulator connected); successful pre-build checks are not a
claim that it is already installable or that every target phone is supported.
