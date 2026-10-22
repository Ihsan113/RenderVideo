package id.danzku.renderify;

import android.app.Activity;
import android.media.projection.MediaProjectionConfig;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RenderActivity extends Activity {
    static final String EXTRA_HTML_PATH = "html_path";
    static final String EXTRA_DURATION = "duration";
    static final String EXTRA_FPS = "fps";
    static final String EXTRA_QUALITY = "quality";
    static final String EXTRA_SCRIPT = "script";
    private static final int REQUEST_CAPTURE = 81;

    private FrameLayout root;
    private WebView webView;
    private View instruction;
    private String html, startScript;
    private int duration, fps, quality;
    private Uri outputUri;
    private boolean permissionRequested = false, recording = false, completed = false;
    private boolean renderServiceRequested = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setImmersive(true);

        try {
            File input = new File(getIntent().getStringExtra(EXTRA_HTML_PATH));
            if (!input.isFile() || input.length() > 1024 * 1024) throw new IllegalArgumentException("HTML tidak tersedia / terlalu besar");
            html = new String(Files.readAllBytes(input.toPath()), StandardCharsets.UTF_8);
            duration = Math.max(2, Math.min(120, getIntent().getIntExtra(EXTRA_DURATION, 10)));
            fps = getIntent().getIntExtra(EXTRA_FPS, 30) == 24 ? 24 : 30;
            int requested = getIntent().getIntExtra(EXTRA_QUALITY, 720);
            quality = requested == 540 || requested == 1080 ? requested : 720;
            startScript = getIntent().getStringExtra(EXTRA_SCRIPT);
            if (startScript == null) startScript = "";
            createRecordingUi();
            webView.loadDataWithBaseURL("https://renderify.invalid/", html, "text/html", "UTF-8", null);
        } catch (Exception error) {
            showError("Gagal membaca HTML: " + error.getMessage());
        }
    }

    private void createRecordingUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 13, 22));
        webView = new WebView(this);
        MainActivity.configureWebView(webView);
        webView.setBackgroundColor(Color.rgb(9, 13, 22));
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (!permissionRequested) {
                    permissionRequested = true;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> askScreenCapturePermission(), 300);
                } else if (recording) {
                    if (!startScript.trim().isEmpty()) {
                        view.evaluateJavascript("(function(){try{" + startScript + ";}catch(e){console.error(e);}})();", null);
                    }
                }
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout blocker = Ui.vertical(this);
        blocker.setGravity(Gravity.CENTER);
        blocker.setBackgroundColor(Ui.BG);
        blocker.setPadding(Ui.dp(this, 30), 0, Ui.dp(this, 30), 0);
        TextView title = Ui.text(this, "Menyiapkan render…", 24, Ui.WHITE, true);
        title.setGravity(Gravity.CENTER);
        blocker.addView(title);
        blocker.addView(Ui.spacer(this, 15));
        TextView hint = Ui.text(this, "Izinkan perekaman layar saat diminta.\nTampilan editor tidak ikut direkam.", 14, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        blocker.addView(hint);
        instruction = blocker;
        root.addView(blocker, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private void askScreenCapturePermission() {
        if (isFinishing() || completed) return;
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent request;
            if (Build.VERSION.SDK_INT >= 34) {
                request = manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
            } else {
                request = manager.createScreenCaptureIntent();
            }
            startActivityForResult(request, REQUEST_CAPTURE);
        } catch (Exception e) { showError("Gagal meminta izin screen capture: " + e.getMessage()); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_CAPTURE) return;
        if (result != RESULT_OK || data == null) { showError("Izin perekaman layar ditolak atau dibatalkan."); return; }
        try {
            outputUri = makeOutputUri();
            DisplayMetrics metrics = new DisplayMetrics();
            int screenW, screenH;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
                screenW = Math.min(bounds.width(), bounds.height());
                screenH = Math.max(bounds.width(), bounds.height());
            } else {
                getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                screenW = Math.min(metrics.widthPixels, metrics.heightPixels);
                screenH = Math.max(metrics.widthPixels, metrics.heightPixels);
            }
            int screenDpi = getResources().getConfiguration().densityDpi;
            int maxHeight = quality == 540 ? 960 : quality == 720 ? 1280 : 1920;
            float factor = Math.min(1f, Math.min((float) quality / screenW, (float) maxHeight / screenH));
            int outW = Math.max(2, (((int) (screenW * factor)) / 2) * 2);
            int outH = Math.max(2, (((int) (screenH * factor)) / 2) * 2);
            int bitrate = quality == 540 ? 2_500_000 : quality == 720 ? 5_000_000 : 8_000_000;

            ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
                @Override protected void onReceiveResult(int code, Bundle info) {
                    if (isFinishing() || isDestroyed()) return;
                    if (code == CaptureService.MSG_STARTED) {
                        recording = true;
                        instruction.setVisibility(View.GONE);
                        // Reload at the actual capture start for CSS animations that run on load.
                        webView.loadDataWithBaseURL("https://renderify.invalid/", html, "text/html", "UTF-8", null);
                    } else if (code == CaptureService.MSG_FINISHED) {
                        recording = false;
                        renderServiceRequested = false;
                        completed = true;
                        boolean ok = info != null && info.getBoolean("success", false);
                        String message = info == null ? "Render dihentikan" : info.getString("message", "Render selesai");
                        if (ok) showCompleted(); else showError(message);
                    }
                }
            };
            Intent start = new Intent(this, CaptureService.class);
            start.setAction(CaptureService.ACTION_START);
            start.putExtra("result_code", result);
            start.putExtra("result_data", data);
            start.putExtra("output_uri", outputUri.toString());
            start.putExtra("w", outW);
            start.putExtra("h", outH);
            start.putExtra("density", screenDpi);
            start.putExtra("fps", fps);
            start.putExtra("bitrate", bitrate);
            start.putExtra("seconds", duration);
            start.putExtra("receiver", receiver);
            // Hide all preparatory text before capture starts; only HTML is recorded.
            instruction.setVisibility(View.GONE);
            renderServiceRequested = true;
            startForegroundService(start);
        } catch (Exception error) { deleteIncompleteOutput(); showError("Render gagal dimulai: " + error.getMessage()); }
    }

    private Uri makeOutputUri() {
        String name = "Renderify_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Renderify");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Tidak dapat membuat file video");
        return uri;
    }

    private void deleteIncompleteOutput() {
        if (outputUri != null) {
            try { getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
        }
    }

    private void showCompleted() {
        cleanupWebView();
        setImmersive(false);
        LinearLayout page = makeResultPage();
        page.addView(Ui.spacer(this, 25));
        TextView emblem = Ui.text(this, "✓", 50, Ui.GREEN, true);
        emblem.setGravity(Gravity.CENTER);
        page.addView(emblem);
        page.addView(Ui.spacer(this, 15));
        TextView success = Ui.text(this, "Video berhasil dibuat!", 24, Ui.WHITE, true);
        success.setGravity(Gravity.CENTER);
        page.addView(success);
        page.addView(Ui.spacer(this, 12));
        TextView detail = Ui.text(this, "MP4 / H.264   •   " + duration + " detik   •   " + fps + " FPS\nTersimpan di folder Movies/Renderify", 13, Ui.MUTED, false);
        detail.setGravity(Gravity.CENTER);
        page.addView(detail);
        page.addView(Ui.spacer(this, 28));
        Button play = Ui.button(this, "▶  Putar video", true);
        page.addView(play, Ui.lp(this, -1, 55));
        play.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(outputUri, "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(intent); } catch (Exception e) { Toast.makeText(this, "Aplikasi pemutar tidak ditemukan", Toast.LENGTH_LONG).show(); }
        });
        page.addView(Ui.spacer(this, 10));
        Button share = Ui.button(this, "↗  Bagikan MP4", false);
        page.addView(share, Ui.lp(this, -1, 55));
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("video/mp4");
            send.putExtra(Intent.EXTRA_STREAM, outputUri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Bagikan video"));
        });
        page.addView(Ui.spacer(this, 10));
        Button back = Ui.button(this, "←  Kembali ke editor", false);
        page.addView(back, Ui.lp(this, -1, 52));
        back.setOnClickListener(v -> finish());
    }

    private void showError(String text) {
        completed = true;
        recording = false;
        renderServiceRequested = false;
        cleanupWebView();
        setImmersive(false);
        LinearLayout page = makeResultPage();
        TextView heading = Ui.text(this, "Render tidak selesai", 22, Ui.WHITE, true);
        heading.setGravity(Gravity.CENTER);
        page.addView(heading);
        page.addView(Ui.spacer(this, 15));
        TextView message = Ui.text(this, text, 14, Ui.MUTED, false);
        message.setGravity(Gravity.CENTER);
        page.addView(message);
        page.addView(Ui.spacer(this, 24));
        Button back = Ui.button(this, "Kembali ke editor", true);
        page.addView(back, Ui.lp(this, -1, 54));
        back.setOnClickListener(v -> finish());
    }

    private LinearLayout makeResultPage() {
        LinearLayout page = Ui.vertical(this);
        page.setBackgroundColor(Ui.BG);
        page.setPadding(Ui.dp(this, 26), Ui.dp(this, 40), Ui.dp(this, 26), Ui.dp(this, 22));
        page.setGravity(Gravity.CENTER_VERTICAL);
        setContentView(page);
        return page;
    }

    private void cleanupWebView() {
        if (webView != null) {
            webView.stopLoading();
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
    }

    private void setImmersive(boolean enabled) {
        getWindow().getDecorView().setSystemUiVisibility(enabled
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : 0);
    }

    @Override protected void onStop() {
        super.onStop();
        if (renderServiceRequested && !completed) {
            // Stop immediately if the user leaves this screen: never record other apps.
            Intent stop = new Intent(this, CaptureService.class);
            stop.setAction(CaptureService.ACTION_STOP);
            startService(stop);
        }
    }

    @Override public void onBackPressed() {
        if (renderServiceRequested && !completed) {
            Intent stop = new Intent(this, CaptureService.class);
            stop.setAction(CaptureService.ACTION_STOP);
            startService(stop);
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        cleanupWebView();
        super.onDestroy();
    }
}
