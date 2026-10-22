package id.danzku.renderify;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.MediaStore;

public class CaptureService extends Service {
    static final String ACTION_START = "id.danzku.renderify.START_CAPTURE";
    static final String ACTION_STOP = "id.danzku.renderify.STOP_CAPTURE";
    static final int MSG_STARTED = 1;
    static final int MSG_FINISHED = 2;
    private static final int NOTIFICATION_ID = 135;
    private static final String CHANNEL_ID = "renderify_recording";

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private ParcelFileDescriptor outputFd;
    private Uri outputUri;
    private ResultReceiver receiver;
    private MediaProjection.Callback projectionCallback;
    private boolean running = false;
    private boolean finishing = false;
    private long startedAt = 0;
    private Runnable finishTask;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopCapture("Render dihentikan", true);
        } else if (ACTION_START.equals(intent.getAction())) {
            if (running || projection != null) return START_NOT_STICKY;
            beginCapture(intent);
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void beginCapture(Intent intent) {
        finishing = false;
        receiver = (ResultReceiver) intent.getParcelableExtra("receiver");
        outputUri = Uri.parse(intent.getStringExtra("output_uri"));
        try {
            createNotificationChannel();
            Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
            PendingIntent stopIntent = PendingIntent.getService(this, 0, stop,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_logo_small)
                    .setContentTitle("Renderify — render sedang berjalan")
                    .setContentText("Perekaman animasi HTML ke MP4")
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_PROGRESS)
                    .addAction(android.R.drawable.ic_media_pause, "Hentikan render", stopIntent)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            Intent data = (Intent) intent.getParcelableExtra("result_data");
            int result = intent.getIntExtra("result_code", Activity.RESULT_CANCELED);
            if (data == null || result != Activity.RESULT_OK) throw new IllegalStateException("Token izin rekam tidak valid");
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(result, data);
            if (projection == null) throw new IllegalStateException("MediaProjection tidak tersedia");
            projectionCallback = new MediaProjection.Callback() {
                @Override public void onStop() {
                    main.post(() -> stopCapture("Izin perekaman dihentikan oleh Android", true));
                }
            };
            projection.registerCallback(projectionCallback, main);

            int width = Math.max(2, intent.getIntExtra("w", 720));
            int height = Math.max(2, intent.getIntExtra("h", 1280));
            int density = Math.max(120, intent.getIntExtra("density", 320));
            int fps = intent.getIntExtra("fps", 30);
            int bitrate = intent.getIntExtra("bitrate", 5_000_000);
            int seconds = Math.max(2, Math.min(120, intent.getIntExtra("seconds", 10)));

            outputFd = getContentResolver().openFileDescriptor(outputUri, "w");
            if (outputFd == null) throw new IllegalStateException("Gagal membuka file video");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(fps);
            recorder.setVideoEncodingBitRate(bitrate);
            recorder.setOutputFile(outputFd.getFileDescriptor());
            recorder.prepare();

            display = projection.createVirtualDisplay("Renderify HDMI-Free Capture", width, height,
                    density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(), null, main);
            if (display == null) throw new IllegalStateException("Virtual display tidak bisa dibuat");
            recorder.start();
            running = true;
            startedAt = SystemClock.elapsedRealtime();
            if (receiver != null) receiver.send(MSG_STARTED, Bundle.EMPTY);
            finishTask = () -> stopCapture("Render selesai", false);
            main.postDelayed(finishTask, seconds * 1000L);
        } catch (Exception e) {
            stopCapture("Render gagal: " + e.getMessage(), true);
        }
    }

    private void stopCapture(String message, boolean early) {
        if (finishing) return;
        finishing = true;
        if (finishTask != null) main.removeCallbacks(finishTask);
        boolean success = !early && running && SystemClock.elapsedRealtime() - startedAt >= 900;
        if (recorder != null) {
            try { if (running) recorder.stop(); }
            catch (RuntimeException e) { success = false; message = "Encoder MP4 gagal menyelesaikan video: " + e.getMessage(); }
        } else success = false;
        running = false;
        try { if (recorder != null) recorder.reset(); } catch (Exception ignored) {}
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        display = null;
        try { if (projection != null && projectionCallback != null) projection.unregisterCallback(projectionCallback); }
        catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        try { if (outputFd != null) outputFd.close(); } catch (Exception ignored) {}
        outputFd = null;

        if (outputUri != null) {
            try {
                if (success) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outputUri, values, null, null);
                } else {
                    getContentResolver().delete(outputUri, null, null);
                }
            } catch (Exception e) { success = false; message = "Gagal menyimpan video: " + e.getMessage(); }
        }
        Bundle response = new Bundle();
        response.putBoolean("success", success);
        response.putString("message", message);
        if (receiver != null) receiver.send(MSG_FINISHED, response);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createNotificationChannel() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Status render MP4", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Notifikasi selama konversi HTML ke video");
        notificationManager.createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        if (!finishing) stopCapture("Proses render dihentikan", true);
        super.onDestroy();
    }
}
