package id.danzku.renderify;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Window;

/**
 * Samples the visible app Window region containing the Chromium-backed WebView.
 * PixelCopy is NOT MediaProjection: no other app/screen data is requested.
 * Captures in wall-clock time. Missing/late samples are duplicated to preserve a
 * constant MP4 frame rate and the user-selected duration.
 */
final class FrameRenderEngine {
    interface Callback {
        void onReady(String codecName);
        void onProgress(int done, int total);
        void onFinished(boolean success, String message);
    }

    private final Activity activity;
    private final Window window;
    private final Rect crop;
    private final Uri output;
    private final int width, height, seconds, fps, totalFrames;
    private final String mode;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread renderThread;
    private Handler worker;
    private GpuFrameEncoder encoder;
    private Bitmap copyTarget;
    private long startedAtNs;
    private int nextFrame;
    private volatile boolean cancelled, finished;
    private boolean running;
    private boolean pixelCopyPending;
    private String selectedCodec;

    FrameRenderEngine(Activity activity, Rect crop, Uri output,
                      int width, int height, int seconds, int fps, String mode,
                      Callback callback) {
        this.activity = activity;
        this.window = activity.getWindow();
        this.crop = new Rect(crop);
        this.output = output;
        this.width = width;
        this.height = height;
        this.seconds = seconds;
        this.fps = fps;
        this.mode = mode;
        this.callback = callback;
        this.totalFrames = seconds * fps;
    }

    void prepare() {
        renderThread = new HandlerThread("Renderify-FrameEncoder");
        renderThread.start();
        worker = new Handler(renderThread.getLooper());
        worker.post(() -> {
            try {
                if (cancelled) { complete(false, "Render dibatalkan"); return; }
                // Validate/initialize the codec before allocating a potentially large 4K Bitmap.
                encoder = new GpuFrameEncoder(activity.getContentResolver(), output,
                        width, height, fps, mode);
                selectedCodec = encoder.start();
                copyTarget = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                main.post(() -> { if (!cancelled) callback.onReady(selectedCodec); });
            } catch (OutOfMemoryError oom) {
                complete(false, "RAM tidak cukup untuk frame " + width + "×" + height + ". Coba resolusi lebih rendah.");
            } catch (Exception ex) {
                complete(false, "Gagal menyiapkan encoder: " + ex.getMessage());
            }
        });
    }

    /** Called after onReady, once JS start trigger has been issued on the UI thread. */
    void begin() {
        if (worker == null || cancelled) return;
        worker.post(() -> {
            if (finished || cancelled || running) return;
            running = true;
            startedAtNs = SystemClock.elapsedRealtimeNanos();
            requestNext();
        });
    }

    private void requestNext() {
        if (cancelled) { complete(false, "Render dibatalkan"); return; }
        if (nextFrame >= totalFrames) { completeSuccess(); return; }
        // Use actual wall time, with timestamps derived from frame number for fixed CFR.
        long dueNs = startedAtNs + ((long) nextFrame * 1_000_000_000L) / fps;
        long delayMs = Math.max(0, (dueNs - SystemClock.elapsedRealtimeNanos()) / 1_000_000L);
        worker.postDelayed(() -> {
            if (cancelled) { complete(false, "Render dibatalkan"); return; }
            try {
                pixelCopyPending = true;
                PixelCopy.request(window, crop, copyTarget, this::gotFrame, worker);
            } catch (Exception ex) {
                complete(false, "PixelCopy gagal: " + ex.getMessage());
            }
        }, delayMs);
    }

    private void gotFrame(int result) {
        pixelCopyPending = false;
        if (cancelled) { complete(false, "Render dibatalkan"); return; }
        if (result != PixelCopy.SUCCESS) {
            complete(false, "Tidak bisa membaca frame WebView (PixelCopy kode " + result + ")");
            return;
        }
        try {
            long elapsedNs = Math.max(0L, SystemClock.elapsedRealtimeNanos() - startedAtNs);
            int target = (int) Math.min((long) totalFrames - 1,
                    elapsedNs * fps / 1_000_000_000L);
            target = Math.max(target, nextFrame);
            for (int index = nextFrame; index <= target; index++) {
                if (cancelled) { complete(false, "Render dibatalkan"); return; }
                encoder.frame(copyTarget, ((long) index * 1_000_000_000L) / fps);
            }
            nextFrame = target + 1;
            if (nextFrame == totalFrames || nextFrame % Math.max(1, fps / 2) == 0) {
                final int n = nextFrame;
                main.post(() -> { if (!finished) callback.onProgress(n, totalFrames); });
            }
            requestNext();
        } catch (OutOfMemoryError oom) {
            complete(false, "RAM tidak cukup saat mengolah frame video. Coba resolusi/FPS lebih rendah.");
        } catch (Exception ex) {
            complete(false, "Gagal membuat frame MP4: " + ex.getMessage());
        }
    }

    private void completeSuccess() {
        try {
            encoder.finish();
            complete(true, "Video berhasil dibuat dengan " + selectedCodec);
        } catch (Exception e) {
            complete(false, "Gagal menyelesaikan MP4: " + e.getMessage());
        }
    }

    void cancel() {
        cancelled = true;
        if (worker != null) worker.post(() -> {
            // Do not recycle the Bitmap while native PixelCopy still writes into it.
            if (!pixelCopyPending) complete(false, "Render dibatalkan");
            else worker.postDelayed(() -> complete(false, "Render dibatalkan"), 1500);
        });
    }

    private void complete(boolean success, String message) {
        if (finished) return;
        finished = true;
        try { if (encoder != null) encoder.close(); } catch (Exception ignored) {}
        if (copyTarget != null) { copyTarget.recycle(); copyTarget = null; }
        if (!success) {
            try { activity.getContentResolver().delete(output, null, null); } catch (Exception ignored) {}
        } else {
            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                activity.getContentResolver().update(output, values, null, null);
            } catch (Exception e) {
                success = false;
                message = "MP4 selesai, tetapi gagal dipublikasikan: " + e.getMessage();
                try { activity.getContentResolver().delete(output, null, null); } catch (Exception ignored) {}
            }
        }
        final boolean ok = success;
        final String note = message;
        main.post(() -> callback.onFinished(ok, note));
        if (renderThread != null) renderThread.quitSafely();
    }
}
