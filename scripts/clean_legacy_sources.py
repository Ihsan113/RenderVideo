#!/usr/bin/env python3
"""Remove the v1 MediaProjection recorder left in repos migrated to Renderify v2.

When users upload files via github.com, existing files absent from a new ZIP
remain in the repository. The removed v1 source can therefore break the
no-screen-recording assertion and the Gradle Java build. This script deletes
ONLY the explicitly known obsolete source and nothing else.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
legacy = root / 'app/src/main/java/id/danzku/renderify/CaptureService.java'
if legacy.is_file():
    legacy.unlink()
    print('REMOVED obsolete v1 source:', legacy.relative_to(root))
else:
    print('OK: no obsolete CaptureService.java present')
