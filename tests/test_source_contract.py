"""Source-level regression tests for errors reported from Android devices.
These do not replace Gradle compilation and a real-phone MediaCodec test.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/id/danzku/renderify'

class RenderifySourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.encoder = (JAVA / 'GpuFrameEncoder.java').read_text()
        cls.render = (JAVA / 'RenderActivity.java').read_text()
        cls.ui = (JAVA / 'MainActivity.java').read_text()
        cls.engine = (JAVA / 'FrameRenderEngine.java').read_text()

    def test_avc_candidates_all_codecs(self):
        self.assertIn('MediaCodecList.ALL_CODECS', self.encoder)
        self.assertIn('MIMETYPE_VIDEO_AVC', self.encoder)
        self.assertIn('getVideoCapabilities().areSizeAndRateSupported(width, height, fps)', self.encoder)

    def test_yuv_fallback_has_plane_and_bytebuffer(self):
        for token in ('COLOR_FormatSurface', 'COLOR_FormatYUV420Flexible',
                      'COLOR_FormatYUV420Planar', 'COLOR_FormatYUV420SemiPlanar',
                      'codec.getInputImage(index)', 'codec.getInputBuffer(index)',
                      'codec.queueInputBuffer(index', 'codec.signalEndOfInputStream()'):
            with self.subTest(token=token):
                self.assertIn(token, self.encoder)

    def test_mode_does_not_force_hw_in_auto(self):
        self.assertIn('MODE_SOFTWARE.equals(mode)', self.encoder)
        self.assertIn('MODE_HARDWARE.equals(mode)', self.encoder)
        self.assertIn('info.isSoftwareOnly()', self.encoder)
        self.assertIn('Collections.sort(options', self.encoder)

    def test_2k_4k_and_60(self):
        for quality in ('540', '720', '1080', '1440', '2160'):
            self.assertRegex(self.ui, rf'\b{quality}\b')
        self.assertIn('60 FPS', self.ui)
        self.assertIn('requestedQuality == 1440 || requestedQuality == 2160', self.render)
        self.assertIn('HorizontalScrollView', self.ui)

    def test_check_codec_available_in_ui(self):
        self.assertIn('Periksa encoder HP', self.ui)
        self.assertIn('GpuFrameEncoder.diagnose(', self.ui)
        self.assertIn('compatibleSize(', self.render)

    def test_egl_failure_recovers_before_next_codec(self):
        self.assertIn('releaseGl();\n                releaseCodecOnly();', self.encoder)
        self.assertIn('for (Candidate c : options)', self.encoder)
        self.assertIn('diagnostics.append(c.info.getName())', self.encoder)

    def test_alignment_diagnostics(self):
        self.assertIn('closeSize = compatibleSize(width, height, fps, mode)', self.encoder)
        self.assertIn('Aplikasi akan mencoba otomatis saat render.', self.encoder)

    def test_github_action_runs_tests(self):
        workflow = (ROOT / '.github/workflows/build-apk.yml').read_text()
        self.assertIn('python3 validate_project.py', workflow)
        self.assertIn('python3 -m unittest discover -s tests -v', workflow)
        self.assertIn('gradle --no-daemon --stacktrace assembleDebug', workflow)

    def test_no_media_projection(self):
        for path in JAVA.glob('*.java'):
            text = path.read_text()
            self.assertNotIn('import android.media.projection.', text)
            self.assertNotIn('createScreenCaptureIntent', text)
        self.assertFalse((JAVA / 'CaptureService.java').exists())

    def test_4k_warning_not_promise(self):
        self.assertIn('belum menjamin detail 2K/4K asli', self.ui)
        self.assertIn('60 FPS adalah target', self.ui)

    def test_encoder_end_of_stream_for_both_input_types(self):
        self.assertIn('if (surfaceInput) codec.signalEndOfInputStream()', self.encoder)
        self.assertIn('MediaCodec.BUFFER_FLAG_END_OF_STREAM', self.encoder)

    def test_no_lossy_automatic_720p_downgrade(self):
        self.assertIn('Math.abs(size[0] - width) > 32', self.encoder)
        self.assertIn('aspectError > 0.01', self.encoder)

if __name__ == '__main__':
    unittest.main()
