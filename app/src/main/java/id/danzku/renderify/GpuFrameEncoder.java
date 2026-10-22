package id.danzku.renderify;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.content.ContentResolver;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Encodes PixelCopy bitmaps without MediaProjection/MediaRecorder.
 * GL texture -> EGL window surface -> hardware/software MediaCodec -> MediaMuxer.
 * All public methods must be called from the one render HandlerThread.
 */
final class GpuFrameEncoder implements AutoCloseable {
    static final String MODE_AUTO = "auto", MODE_HARDWARE = "hardware", MODE_SOFTWARE = "software";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final String VERT = "attribute vec4 aPosition; attribute vec2 aTex; varying vec2 vTex;" +
            "void main(){gl_Position=aPosition;vTex=aTex;}";
    private static final String FRAG = "precision mediump float; varying vec2 vTex; uniform sampler2D tex;" +
            "void main(){gl_FragColor=texture2D(tex,vTex);}";
    // Vertex order: triangle strip; texture vertical axis flipped for Android Bitmap rows.
    private static final float[] VERTICES = {
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
    };

    private final ContentResolver resolver;
    private final Uri output;
    private final int width, height, fps;
    private final String mode;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private ParcelFileDescriptor descriptor;
    private Surface codecSurface;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int texture, program;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(VERTICES.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private boolean textureInitialized, muxerStarted, finalized;
    private int trackIndex = -1, framesWritten;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private String encoderName;

    GpuFrameEncoder(ContentResolver resolver, Uri output, int width, int height, int fps, String mode) {
        this.resolver = resolver;
        this.output = output;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.mode = mode;
        vertices.put(VERTICES).position(0);
    }

    String start() throws Exception {
        descriptor = resolver.openFileDescriptor(output, "w");
        if (descriptor == null) throw new IllegalStateException("Tidak dapat membuka file MP4");
        muxer = new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        Exception lastFailure = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean supportsAvc = false;
            for (String t : info.getSupportedTypes()) if (MIME.equalsIgnoreCase(t)) supportsAvc = true;
            if (!supportsAvc) continue;
            // AUTO/HARDWARE tries hardware first; SOFTWARE exclusively tries software.
            if (MODE_SOFTWARE.equals(mode) && !info.isSoftwareOnly()) continue;
            if (MODE_HARDWARE.equals(mode) && !info.isHardwareAccelerated()) continue;
            if (MODE_AUTO.equals(mode) && !info.isHardwareAccelerated()) continue;
            if (!supportsSurfaceInput(info)) continue;
            try {
                setupCodec(info);
                encoderName = info.getName();
                break;
            } catch (Exception e) {
                lastFailure = e;
                releaseCodecOnly();
            }
        }
        // Automatic fallback to a software encoder where available.
        if (codec == null && !MODE_SOFTWARE.equals(mode)) {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder() || !info.isSoftwareOnly() || !supportsSurfaceInput(info)) continue;
                try {
                    setupCodec(info);
                    encoderName = info.getName();
                    break;
                } catch (Exception e) {
                    lastFailure = e;
                    releaseCodecOnly();
                }
            }
        }
        if (codec == null) throw new IllegalStateException("Encoder H.264 yang sesuai tidak tersedia: " +
                (lastFailure == null ? "tidak ada codec" : lastFailure.getMessage()));
        initEgl();
        return encoderName;
    }

    private boolean supportsSurfaceInput(MediaCodecInfo info) {
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME);
            boolean surface = false;
            for (int color : caps.colorFormats) {
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
            }
            return surface && caps.getVideoCapabilities().areSizeAndRateSupported(width, height, fps);
        } catch (Exception e) {
            return false;
        }
    }

    private void setupCodec(MediaCodecInfo info) throws Exception {
        codec = MediaCodec.createByCodecName(info.getName());
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_000_000, width * height * fps / 7));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codecSurface = codec.createInputSurface();
        codec.start();
    }

    private void initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, new int[2], 0, new int[2], 0))
            throw new IllegalStateException("EGL display tidak tersedia");
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] < 1)
            throw new IllegalStateException("Konfigurasi EGL recordable tidak didukung");
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("Gagal membuat EGL context");
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], codecSurface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("EGL surface tidak tersedia");
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
            throw new IllegalStateException("Gagal mengaktifkan EGL context");
        program = makeProgram(VERT, FRAG);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texture = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glViewport(0, 0, width, height);
    }

    private static int makeShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("Shader GPU gagal: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static int makeProgram(String v, String f) {
        int vs = makeShader(GLES20.GL_VERTEX_SHADER, v);
        int fs = makeShader(GLES20.GL_FRAGMENT_SHADER, f);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("Program GPU gagal: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    void frame(Bitmap bitmap, long presentationNs) {
        if (finalized) throw new IllegalStateException("Encoder sudah dihentikan");
        drain(false);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        if (!textureInitialized) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            textureInitialized = true;
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
        }
        GLES20.glUseProgram(program);
        int pos = GLES20.glGetAttribLocation(program, "aPosition");
        int uv = GLES20.glGetAttribLocation(program, "aTex");
        vertices.position(0);
        GLES20.glEnableVertexAttribArray(pos);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, vertices);
        vertices.position(2);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        GLES20.glDisableVertexAttribArray(uv);
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationNs);
        if (!EGL14.eglSwapBuffers(display, eglSurface)) throw new IllegalStateException("GPU gagal mengirim frame");
        framesWritten++;
        drain(false);
    }

    private void drain(boolean end) {
        if (end) codec.signalEndOfInputStream();
        int idle = 0;
        while (true) {
            int status = codec.dequeueOutputBuffer(bufferInfo, end ? 10000 : 0);
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end) return;
                if (++idle > 500) throw new IllegalStateException("Encoder tidak mengakhiri stream");
                continue;
            }
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IllegalStateException("Format encoder berubah dua kali");
                trackIndex = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
            } else if (status >= 0) {
                ByteBuffer data = codec.getOutputBuffer(status);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0;
                if (bufferInfo.size > 0) {
                    if (!muxerStarted || data == null) throw new IllegalStateException("Muxer belum siap");
                    data.position(bufferInfo.offset);
                    data.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(trackIndex, data, bufferInfo);
                }
                boolean eof = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(status, false);
                if (eof) return;
            }
        }
    }

    void finish() {
        if (framesWritten == 0) throw new IllegalStateException("Tidak ada frame berhasil dienkode");
        drain(true);
        if (!muxerStarted) throw new IllegalStateException("MP4 belum memiliki track video");
        finalized = true;
    }

    private void releaseCodecOnly() {
        if (codecSurface != null) { codecSurface.release(); codecSurface = null; }
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            codec.release(); codec = null;
        }
    }

    @Override public void close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
            eglSurface = EGL14.EGL_NO_SURFACE;
            context = EGL14.EGL_NO_CONTEXT;
        }
        releaseCodecOnly();
        if (muxer != null) {
            if (muxerStarted) { try { muxer.stop(); } catch (Exception ignored) {} }
            muxer.release();
            muxer = null;
        }
        if (descriptor != null) {
            try { descriptor.close(); } catch (Exception ignored) {}
            descriptor = null;
        }
    }
}
