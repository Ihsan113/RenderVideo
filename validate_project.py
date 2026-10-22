#!/usr/bin/env python3
"""Pre-build checks. Not a substitute for Gradle and an Android device test."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
src = root / 'app/src/main/java/id/danzku/renderify'
required = [
    '.github/workflows/build-apk.yml', 'settings.gradle', 'build.gradle',
    'app/build.gradle', 'app/src/main/AndroidManifest.xml',
    'app/src/main/assets/payment_demo.html',
    'app/src/main/res/values/styles.xml',
    'app/src/main/res/drawable/ic_launcher_logo.xml',
    'app/src/main/res/drawable/ic_logo_small.xml',
]
required += [str((src / f'{name}.java').relative_to(root)) for name in
             ('MainActivity', 'RenderActivity', 'GpuFrameEncoder', 'FrameRenderEngine', 'Ui')]
for name in required:
    p = root / name
    assert p.is_file() and p.stat().st_size, f'Missing or empty: {name}'
    print('OK', name)

for path in root.rglob('*.xml'):
    ET.parse(path)
print('OK Android XML')

manifest = (root/'app/src/main/AndroidManifest.xml').read_text()
assert 'hardwareAccelerated="true"' in manifest
for forbidden in ('android.permission.INTERNET', 'MEDIA_PROJECTION', 'FOREGROUND_SERVICE', 'CaptureService'):
    assert forbidden not in manifest, f'Unexpected permission/service: {forbidden}'
assert 'android:exported="false"' in manifest
print('OK offline and no-screen-capture permissions')

main = (src/'MainActivity.java').read_text()
render = (src/'RenderActivity.java').read_text()
frames = (src/'FrameRenderEngine.java').read_text()
codec = (src/'GpuFrameEncoder.java').read_text()
assert all(x in main for x in ('ACTION_OPEN_DOCUMENT', 'htmlEditor', 'updatePreview',
                               'setBlockNetworkLoads(true)', 'EXTRA_RATIO', 'EXTRA_ENCODER', '60 FPS'))
assert all(x in render for x in ('applyFullscreenMode', 'makeOutputUri', 'FrameRenderEngine',
                                 'getLocationInWindow', 'IS_PENDING'))
assert 'PixelCopy.request' in frames and 'totalFrames' in frames and 'encoder.frame' in frames
assert all(x in codec for x in ('MediaCodec', 'MediaMuxer', 'EGL14', 'EGLExt', 'GLUtils',
                                'isHardwareAccelerated', 'signalEndOfInputStream'))
for f in src.glob('*.java'):
    text = f.read_text()
    assert 'setImmersive(' not in text, f'Activity.setImmersive collision: {f.name}'
    assert 'import android.media.projection.' not in text and 'createScreenCaptureIntent' not in text, f.name
assert not (src/'CaptureService.java').exists()
print('OK Java features + removed old capture service')

html = (root/'app/src/main/assets/payment_demo.html').read_text()
assert '<!DOCTYPE html>' in html and 'function start10SecPayment()' in html
assert len(html.encode()) < 1024 * 1024
print('OK demo HTML')
print('\nPre-build checks passed; GitHub Actions must still compile and a phone must test recording.')
