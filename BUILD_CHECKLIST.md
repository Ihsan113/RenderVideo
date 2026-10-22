# Build and verification — Renderify 2.0

1. `python3 validate_project.py` — structural checks (not a compiler).
2. `gradle --no-daemon assembleDebug` — GitHub Actions compiles APK.
3. Android device: paste HTML, import `.html`, preview, render payment demo.
4. Confirm **no** screen recording / MediaProjection consent dialog appears.
5. Confirm `Movies/Renderify/Renderify_*.mp4` is a playable H.264 MP4 with
   requested nominal duration and dimension; inspect orientation 9:16/16:9/1:1.
6. Test 24, 30, 60 FPS and 540p/720p/1080p on a representative device.
7. Test Auto, Hardware and Software codec selections. Some combinations may
   fail cleanly if no suitable surface-input encoder is present.
8. Test slow device / CSS transforms / Canvas / WebGL / fonts / offline assets.
9. Test Back and Home during export; no unfinished MP4 should remain.
10. If Gradle or device errors occur, capture exact logs and fix before release.

CI cannot prove on-device PixelCopy, OpenGL, and hardware encoder compatibility.
