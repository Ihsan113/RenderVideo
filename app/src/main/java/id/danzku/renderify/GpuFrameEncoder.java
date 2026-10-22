package id.danzku.renderify;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private int inputColor = -1;
    private boolean surfaceInput;
    private int[] argb;
    private final StringBuilder diagnostics = new StringBuilder();

    GpuFrameEncoder(ContentResolver resolver, Uri output, int width, int height, int fps, String mode) {
        this.resolver = resolver;
        this.output = output;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.mode = mode;
        vertices.put(VERTICES).position(0);
    }

    /** Probe without opening an encoder. Hardware capability claims are provisional until configure/start. */
    static String diagnose(int width, int height, int fps, String mode) {
        List<Candidate> choices = candidates(width, height, fps, mode);
        int avcCount = avcEncoders().size();
        if (choices.isEmpty()) {
            int[] closeSize = compatibleSize(width, height, fps, mode);
            if (closeSize[0] != width || closeSize[1] != height)
                return "Ukuran " + width + "×" + height + " perlu alignment ke " +
                        closeSize[0] + "×" + closeSize[1] + ". Aplikasi akan mencoba otomatis saat render.";
            return "Tidak ada kandidat AVC untuk " + width + "×" + height + " @ " + fps
                    + " FPS / " + mode + ". Encoder H.264 terdeteksi: " + avcCount
                    + ". Coba Auto, resolusi lebih kecil atau 30 FPS. Sebagian codec tidak mendukung FPS/ukuran ini.";
        }
        StringBuilder b = new StringBuilder("Kandidat " + width + "×" + height + " @" + fps + " FPS: ");
        for (int i = 0; i < Math.min(3, choices.size()); i++) {
            if (i > 0) b.append("; ");
            Candidate c = choices.get(i);
            b.append(c.info.getName()).append(c.color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    ? " [GPU/Surface]" : " [YUV/CPU]");
        }
        b.append(". Keberhasilan tetap perlu diuji saat render.");
        return b.toString();
    }

    /** Some devices require a 16-pixel-aligned width (e.g. 544 instead of 540). */
    static int[] compatibleSize(int width, int height, int fps, String mode) {
        if (!candidates(width, height, fps, mode).isEmpty()) return new int[]{width, height};
        for (int align : new int[]{4, 8, 16, 32}) {
            int upW = ((width + align - 1) / align) * align;
            int downW = (width / align) * align;
            int upH = ((height + align - 1) / align) * align;
            int downH = (height / align) * align;
            int[][] trySizes = {{upW, height}, {width, upH}, {upW, upH},
                    {downW, height}, {width, downH}, {downW, downH}};
            for (int[] size : trySizes) {
                // Preserve the intended aspect to within 1%, never silently downgrade quality.
                double aspectError = Math.abs((double) size[0] / size[1] - (double) width / height)
                        / ((double) width / height);
                if (size[0] < 2 || size[1] < 2 || aspectError > 0.01) continue;
                if (Math.abs(size[0] - width) > 32 || Math.abs(size[1] - height) > 32) continue;
                if (!candidates(size[0], size[1], fps, mode).isEmpty()) return size;
            }
        }
        return new int[]{width, height};
    }

    private static List<MediaCodecInfo> avcEncoders() {
        List<MediaCodecInfo> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || !names.add(info.getName())) continue;
            for (String type : info.getSupportedTypes()) {
                if (MIME.equalsIgnoreCase(type)) { result.add(info); break; }
            }
        }
        return result;
    }

    private static final class Candidate {
        final MediaCodecInfo info;
        final int color, priority;
        Candidate(MediaCodecInfo info, int color, int priority) {
            this.info = info; this.color = color; this.priority = priority;
        }
    }

    private static List<Candidate> candidates(int width, int height, int fps, String mode) {
        List<Candidate> options = new ArrayList<>();
        for (MediaCodecInfo info : avcEncoders()) {
            boolean software = info.isSoftwareOnly();
            if (MODE_SOFTWARE.equals(mode) && !software) continue;
            // Some vendor hardware encoders report isHardwareAccelerated=false; they aren't software.
            if (MODE_HARDWARE.equals(mode) && software) continue;
            MediaCodecInfo.CodecCapabilities caps;
            try {
                caps = info.getCapabilitiesForType(MIME);
                if (!caps.getVideoCapabilities().areSizeAndRateSupported(width, height, fps)) continue;
            } catch (Exception ex) { continue; }
            for (int color : caps.colorFormats) {
                if (color != MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface &&
                    color != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar &&
                    color != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar &&
                    color != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) continue;
                // Prefer a true GPU input, but never reject a software encoder just because it needs YUV.
                int priority = (software ? 10 : 0) +
                        (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface ? 0 :
                         color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ? 2 :
                         color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ? 3 : 4);
                options.add(new Candidate(info, color, priority));
            }
        }
        Collections.sort(options, Comparator.comparingInt(c -> c.priority));
        return options;
    }

    String start() throws Exception {
        descriptor = resolver.openFileDescriptor(output, "w");
        if (descriptor == null) throw new IllegalStateException("Tidak dapat membuka file MP4");
        muxer = new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        List<Candidate> options = candidates(width, height, fps, mode);
        for (Candidate c : options) {
            try {
                setupCodec(c);
                encoderName = c.info.getName() + (surfaceInput ? " [Surface/GPU]" : " [YUV/CPU]");
                if (surfaceInput) initEgl();
                return encoderName;
            } catch (Exception ex) {
                if (diagnostics.length() > 600) diagnostics.delete(0, 250);
                diagnostics.append(c.info.getName()).append(" (format=").append(c.color).append("): ")
                        .append(ex.getClass().getSimpleName()).append(" ").append(ex.getMessage()).append("; ");
                // EGL may fail on one vendor codec even though another one works.
                // Clean its half-created GL state before attempting the next candidate.
                releaseGl();
                releaseCodecOnly();
            }
        }
        if (options.isEmpty()) {
            throw new IllegalStateException("Tak ada codec H.264 yang cocok untuk " + width + "×" + height + " @" + fps
                    + " FPS (mode " + mode + "). Total encoder AVC: " + avcEncoders().size()
                    + ". Coba mode Auto / 720p / 30 FPS. Jangan menganggap GPU rusak.");
        }
        throw new IllegalStateException("Semua " + options.size() + " kandidat codec gagal: " + diagnostics);
    }

    private void setupCodec(Candidate candidate) throws Exception {
        codec = MediaCodec.createByCodecName(candidate.info.getName());
        inputColor = candidate.color;
        surfaceInput = inputColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, inputColor);
        long bitRate = Math.max(800_000L, Math.min(70_000_000L, (long) width * height * fps / 7));
        format.setInteger(MediaFormat.KEY_BIT_RATE, (int) bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        if (surfaceInput) codecSurface = codec.createInputSurface();
        codec.start();
        if (!surfaceInput) argb = new int[width * height];
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
        if (!surfaceInput) {
            inputYuv(bitmap, presentationNs / 1000L);
            framesWritten++;
            drain(false);
            return;
        }
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

    private static byte y(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return (byte) Math.min(255, Math.max(0, ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
    }
    private static byte u(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return (byte) Math.min(255, Math.max(0, ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
    }
    private static byte v(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return (byte) Math.min(255, Math.max(0, ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
    }

    // Average a 2×2 block for chroma instead of sampling a single pixel (reduces color aliasing).
    private int chromaBlock(int left, int top) {
        int a = argb[top * width + left], b = argb[top * width + left + 1];
        int c = argb[(top + 1) * width + left], d = argb[(top + 1) * width + left + 1];
        int r = (((a >> 16) & 255) + ((b >> 16) & 255) + ((c >> 16) & 255) + ((d >> 16) & 255)) / 4;
        int g = (((a >> 8) & 255) + ((b >> 8) & 255) + ((c >> 8) & 255) + ((d >> 8) & 255)) / 4;
        int blue = ((a & 255) + (b & 255) + (c & 255) + (d & 255)) / 4;
        return (r << 16) | (g << 8) | blue;
    }

    /** Supports YUV_420_888 flexible planes and standard I420/NV12 codecs without Surface support. */
    private void inputYuv(Bitmap bitmap, long timestampUs) {
        int index = codec.dequeueInputBuffer(10000);
        long deadline = android.os.SystemClock.uptimeMillis() + 1500;
        while (index < 0 && android.os.SystemClock.uptimeMillis() < deadline) {
            drain(false);
            index = codec.dequeueInputBuffer(10000);
        }
        if (index < 0) throw new IllegalStateException("Antrean input encoder penuh; perangkat mungkin kewalahan");
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        int size = width * height * 3 / 2;
        if (inputColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
            Image image = codec.getInputImage(index);
            if (image == null || image.getFormat() != ImageFormat.YUV_420_888)
                throw new IllegalStateException("Input image YUV_420_888 tidak tersedia");
            try {
                Image.Plane[] planes = image.getPlanes();
                for (int row = 0; row < height; row++) {
                    ByteBuffer out = planes[0].getBuffer();
                    int rowBase = row * planes[0].getRowStride();
                    for (int col = 0; col < width; col++)
                        out.put(rowBase + col * planes[0].getPixelStride(), y(argb[row * width + col]));
                }
                for (int row = 0; row < height / 2; row++) {
                    for (int col = 0; col < width / 2; col++) {
                        int px = chromaBlock(col * 2, row * 2);
                        for (int ch = 1; ch <= 2; ch++) {
                            Image.Plane plane = planes[ch];
                            plane.getBuffer().put(row * plane.getRowStride() + col * plane.getPixelStride(),
                                    ch == 1 ? u(px) : v(px));
                        }
                    }
                }
            } finally { image.close(); }
        } else {
            ByteBuffer out = codec.getInputBuffer(index);
            if (out == null || out.capacity() < size) throw new IllegalStateException("Buffer YUV encoder terlalu kecil");
            out.clear();
            for (int color : argb) out.put(y(color));
            int pixels = width * height;
            for (int row = 0; row < height / 2; row++) {
                for (int col = 0; col < width / 2; col++) {
                    int color = chromaBlock(col * 2, row * 2);
                    int uv = row * width / 2 + col;
                    if (inputColor == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                        out.put(pixels + uv, u(color));
                        out.put(pixels + pixels / 4 + uv, v(color));
                    } else {
                        out.put(pixels + uv * 2, u(color));
                        out.put(pixels + uv * 2 + 1, v(color));
                    }
                }
            }
        }
        codec.queueInputBuffer(index, 0, size, timestampUs, 0);
    }

    private void drain(boolean end) {
        if (end) {
            if (surfaceInput) codec.signalEndOfInputStream();
            else {
                int index = codec.dequeueInputBuffer(10000);
                long deadline = android.os.SystemClock.uptimeMillis() + 2000;
                while (index < 0 && android.os.SystemClock.uptimeMillis() < deadline) {
                    drain(false);
                    index = codec.dequeueInputBuffer(10000);
                }
                if (index < 0) throw new IllegalStateException("Encoder tak menerima penanda akhir input");
                codec.queueInputBuffer(index, 0, 0, (long) framesWritten * 1000000L / fps,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        }
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
        argb = null;
        if (codecSurface != null) { codecSurface.release(); codecSurface = null; }
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            codec.release(); codec = null;
        }
    }

    private void releaseGl() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        try {
            if (context != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE &&
                    EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
                if (program != 0) GLES20.glDeleteProgram(program);
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        } catch (Exception ignored) {
            // Always release the associated codec as well.
        } finally {
            display = EGL14.EGL_NO_DISPLAY;
            eglSurface = EGL14.EGL_NO_SURFACE;
            context = EGL14.EGL_NO_CONTEXT;
            program = 0;
            texture = 0;
            textureInitialized = false;
        }
    }

    @Override public void close() {
        releaseGl();
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
