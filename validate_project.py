#!/usr/bin/env python3
"""Fast, dependency-free structural checks before the Android Gradle build."""
from pathlib import Path
import xml.etree.ElementTree as ET
import re

ROOT = Path(__file__).resolve().parent
required = [
    '.github/workflows/build-apk.yml',
    'settings.gradle', 'build.gradle', 'app/build.gradle',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/assets/payment_demo.html',
    'app/src/main/res/values/styles.xml',
    'app/src/main/res/drawable/ic_launcher_logo.xml',
    'app/src/main/res/drawable/ic_logo_small.xml',
]
source = ROOT / 'app/src/main/java/id/danzku/renderify'
required += [str((source / (name+'.java')).relative_to(ROOT)) for name in
             ('MainActivity','RenderActivity','CaptureService','Ui')]

for name in required:
    path = ROOT/name
    assert path.is_file() and path.stat().st_size > 0, f'Missing or empty: {name}'
    print('OK', name)

for filename in ['app/src/main/AndroidManifest.xml', 'app/src/main/res/values/styles.xml',
                 'app/src/main/res/drawable/ic_launcher_logo.xml',
                 'app/src/main/res/drawable/ic_logo_small.xml']:
    ET.parse(ROOT / filename)
print('OK Android XML parsing')

manifest = (ROOT/'app/src/main/AndroidManifest.xml').read_text()
assert 'FOREGROUND_SERVICE_MEDIA_PROJECTION' in manifest
assert 'foregroundServiceType="mediaProjection"' in manifest
assert 'android:exported="false"' in manifest
assert 'android.permission.INTERNET' not in manifest
print('OK MediaProjection declarations / offline-only permission policy')

activity = (source/'MainActivity.java').read_text()
assert 'ACTION_OPEN_DOCUMENT' in activity
assert 'htmlEditor' in activity and 'updatePreview' in activity
assert 'setBlockNetworkLoads(true)' in activity
assert 'selectedFps' in activity and 'selectedQuality' in activity
service = (source/'CaptureService.java').read_text()
assert 'MediaRecorder' in service and 'createVirtualDisplay' in service
assert 'IS_PENDING' in service
render = (source/'RenderActivity.java').read_text()
assert 'createScreenCaptureIntent' in render and 'renderServiceRequested' in render
print('OK source feature checkpoints')

html = (ROOT/'app/src/main/assets/payment_demo.html').read_text()
assert '<!DOCTYPE html>' in html and 'function start10SecPayment()' in html
assert len(html.encode()) < 1024*1024
assert 'setTimeout' in html and 'animation' in html
print('OK sample HTML')
print('\nStructural checks passed. A real Gradle / device build is still required.')
