# Renderify v3 — validation checklist

## Automated (source/build)

- [ ] `python3 scripts/clean_legacy_sources.py`
- [ ] `python3 validate_project.py`
- [ ] `python3 -m unittest discover -s tests -v`
- [ ] GitHub Actions `gradle --no-daemon --stacktrace assembleDebug`
- [ ] Check `app-debug.apk` appears in workflow Artifacts

## Android device — codec and playback

- [ ] `Periksa encoder HP` lists compatible AVC codec candidates for Auto, Hardware, and Software presets
- [ ] 9:16 at 720p 30 FPS Auto exports playable H.264 MP4 without screen recording permission
- [ ] 540p 30 FPS Hardware and 720p 30 FPS Software either succeed or give an informative diagnostic
- [ ] 1080p 30 FPS Auto; 1080p 60 FPS if reported supported
- [ ] QHD/2K 30 FPS; 4K UHD 30 and 60 FPS *only on devices that support those combinations*
- [ ] Video orientation, target dimensions, duration, progress, and visible source animation are correct
- [ ] Capture failures, Back/Home cancellation, and out-of-memory remove incomplete MediaStore entries
- [ ] Imported HTML and pasted HTML both render; URL/network media correctly remain blocked
- [ ] Video inspection detects blurry upscaling and repeated frames where PixelCopy cannot keep up

**Known limitations:** visible WebView resolution, PixelCopy upscaling, real-time frame pacing, variable codec support, no audio. 4K/60 is not guaranteed on all devices.
