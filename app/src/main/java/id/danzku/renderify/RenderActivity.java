package id.danzku.renderify;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Visible WebView is sampled using PixelCopy from our own Activity window. */
public class RenderActivity extends Activity {
    static final String EXTRA_HTML_PATH = "html_path";
    static final String EXTRA_DURATION = "duration";
    static final String EXTRA_FPS = "fps";
    static final String EXTRA_QUALITY = "quality";
    static final String EXTRA_SCRIPT = "script";
    static final String EXTRA_RATIO = "ratio";
    static final String EXTRA_ENCODER = "encoder";

    private FrameLayout root;
    private WebView webView;
    private TextView renderStatus;
    private String html, startScript, ratio, encoderMode;
    private int duration, fps, quality, outWidth, outHeight;
    private Uri outputUri;
    private FrameRenderEngine engine;
    private boolean completed, began, loaded;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ratio = getIntent().getStringExtra(EXTRA_RATIO);
        if (!"16:9".equals(ratio) && !"1:1".equals(ratio)) ratio = "9:16";
        setRequestedOrientation("16:9".equals(ratio) ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        applyFullscreenMode(true);
        try {
            String path = getIntent().getStringExtra(EXTRA_HTML_PATH);
            if (path == null) throw new IllegalArgumentException("Path HTML tidak tersedia");
            File input = new File(path);
            if (!input.isFile() || input.length() > 1024 * 1024)
                throw new IllegalArgumentException("HTML tidak tersedia atau melebihi 1 MB");
            html = new String(Files.readAllBytes(input.toPath()), StandardCharsets.UTF_8);
            duration = Math.max(2, Math.min(120, getIntent().getIntExtra(EXTRA_DURATION, 10)));
            int requestedFps = getIntent().getIntExtra(EXTRA_FPS, 30);
            fps = requestedFps == 24 || requestedFps == 60 ? requestedFps : 30;
            int requestedQuality = getIntent().getIntExtra(EXTRA_QUALITY, 720);
            quality = requestedQuality == 540 || requestedQuality == 1080 ? requestedQuality : 720;
            outWidth = "16:9".equals(ratio) ? quality * 16 / 9 : quality;
            outHeight = "9:16".equals(ratio) ? quality * 16 / 9 : quality;
            if ("1:1".equals(ratio)) outWidth = outHeight = quality;
            startScript = getIntent().getStringExtra(EXTRA_SCRIPT);
            if (startScript == null) startScript = "";
            encoderMode = getIntent().getStringExtra(EXTRA_ENCODER);
            if (!GpuFrameEncoder.MODE_HARDWARE.equals(encoderMode) &&
                    !GpuFrameEncoder.MODE_SOFTWARE.equals(encoderMode))
                encoderMode = GpuFrameEncoder.MODE_AUTO;
            createRenderScreen();
        } catch (Exception ex) {
            showError("Gagal membuka proyek: " + ex.getMessage());
        }
    }

    private void createRenderScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 13, 22));
        webView = new WebView(this);
        MainActivity.configureWebView(webView);
        webView.setBackgroundColor(Color.rgb(9, 13, 22));
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                loaded = true;
                // Wait until the visible view has produced a composited frame.
                ui.postDelayed(() -> { if (!isFinishing() && !completed) prepareEngine(); }, 200);
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        renderStatus = Ui.text(this, "Menyiapkan WebView…", 12, Ui.CYAN, true);
        renderStatus.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-1, Ui.dp(this, 34), Gravity.BOTTOM);
        root.addView(renderStatus, statusLp);
        setContentView(root);
        root.post(() -> {
            if (completed || webView == null) return;
            // Reserve 42dp outside the video canvas for progress; it won't enter the MP4.
            int areaW = Math.max(2, root.getWidth() - Ui.dp(this, 14));
            int areaH = Math.max(2, root.getHeight() - Ui.dp(this, 52));
            double targetAspect = (double) outWidth / outHeight;
            int w = areaW;
            int h = (int) Math.floor(w / targetAspect);
            if (h > areaH) { h = areaH; w = (int) Math.floor(h * targetAspect); }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(2, w), Math.max(2, h), Gravity.CENTER);
            lp.bottomMargin = Ui.dp(this, 42);
            webView.setLayoutParams(lp);
            webView.post(() -> webView.loadDataWithBaseURL(
                    "https://renderify.invalid/", html, "text/html", "UTF-8", null));
        });
    }

    private void prepareEngine() {
        if (!loaded || began || completed || webView == null || webView.getWidth() < 2) return;
        began = true;
        int[] pos = new int[2];
        webView.getLocationInWindow(pos);
        Rect capture = new Rect(pos[0], pos[1], pos[0] + webView.getWidth(), pos[1] + webView.getHeight());
        try {
            outputUri = makeOutputUri();
            renderStatus.setText("Menyiapkan encoder GPU…");
            engine = new FrameRenderEngine(this, capture, outputUri, outWidth, outHeight,
                    duration, fps, encoderMode, new FrameRenderEngine.Callback() {
                @Override public void onReady(String codecName) {
                    if (completed || isFinishing() || webView == null) { engine.cancel(); return; }
                    renderStatus.setText("Render • " + codecName);
                    // Start explicit JS animation once the renderer is ready, then begin sampling.
                    if (startScript.trim().isEmpty()) {
                        ui.postDelayed(() -> { if (!completed) engine.begin(); }, 40);
                    } else {
                        String script = "(function(){try{" + startScript + ";}catch(e){console.error(e);}})();";
                        webView.evaluateJavascript(script,
                                ignored -> ui.postDelayed(() -> { if (!completed) engine.begin(); }, 40));
                    }
                }
                @Override public void onProgress(int done, int total) {
                    if (renderStatus != null && !completed)
                        renderStatus.setText("Render MP4  •  " + Math.round(done * 100f / total) + "%");
                }
                @Override public void onFinished(boolean success, String reason) {
                    if (completed) return;
                    completed = true;
                    engine = null;
                    if (isFinishing() || isDestroyed()) return;
                    if (success) showCompleted(reason);
                    else showError(reason);
                }
            });
            engine.prepare();
        } catch (Exception ex) {
            deleteIncompleteOutput();
            showError("Gagal menyiapkan render: " + ex.getMessage());
        }
    }

    private Uri makeOutputUri() {
        String name = "Renderify_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".mp4";
        ContentValues data = new ContentValues();
        data.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        data.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        data.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Renderify");
        data.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, data);
        if (uri == null) throw new IllegalStateException("Gagal membuat file video");
        return uri;
    }

    private void deleteIncompleteOutput() {
        if (outputUri != null) try {
            getContentResolver().delete(outputUri, null, null);
        } catch (Exception ignored) {}
    }

    private void showCompleted(String encoder) {
        cleanupWebView();
        applyFullscreenMode(false);
        LinearLayout page = makeResultPage();
        TextView emblem = Ui.text(this, "✓", 54, Ui.GREEN, true);
        emblem.setGravity(Gravity.CENTER);
        page.addView(emblem);
        page.addView(Ui.spacer(this, 14));
        TextView success = Ui.text(this, "MP4 berhasil dibuat!", 23, Ui.WHITE, true);
        success.setGravity(Gravity.CENTER);
        page.addView(success);
        page.addView(Ui.spacer(this, 14));
        TextView desc = Ui.text(this,
                outWidth + " × " + outHeight + "  •  " + fps + " FPS  •  " + duration + " detik\n" +
                "Tanpa izin perekaman layar\nMovies/Renderify", 12, Ui.MUTED, false);
        desc.setGravity(Gravity.CENTER);
        page.addView(desc);
        page.addView(Ui.spacer(this, 8));
        TextView engineLabel = Ui.text(this, encoder, 10, Ui.CYAN, false);
        engineLabel.setGravity(Gravity.CENTER);
        page.addView(engineLabel);
        page.addView(Ui.spacer(this, 24));
        Button play = Ui.button(this, "▶  Putar MP4", true);
        page.addView(play, Ui.lp(this, -1, 55));
        play.setOnClickListener(v -> {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(outputUri, "video/mp4");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(view); } catch (Exception e) {
                Toast.makeText(this, "Pemutar video tidak ditemukan", Toast.LENGTH_LONG).show();
            }
        });
        page.addView(Ui.spacer(this, 12));
        Button share = Ui.button(this, "↗  Bagikan video", false);
        page.addView(share, Ui.lp(this, -1, 55));
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("video/mp4");
            send.putExtra(Intent.EXTRA_STREAM, outputUri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Bagikan MP4"));
        });
        addBackButton(page);
    }

    private void showError(String message) {
        completed = true;
        cleanupWebView();
        applyFullscreenMode(false);
        LinearLayout page = makeResultPage();
        TextView icon = Ui.text(this, "!", 48, Ui.CYAN, true);
        icon.setGravity(Gravity.CENTER);
        page.addView(icon);
        page.addView(Ui.spacer(this, 12));
        TextView title = Ui.text(this, "Render belum berhasil", 22, Ui.WHITE, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title);
        page.addView(Ui.spacer(this, 12));
        TextView desc = Ui.text(this, message, 13, Ui.MUTED, false);
        desc.setGravity(Gravity.CENTER);
        page.addView(desc);
        addBackButton(page);
    }

    private LinearLayout makeResultPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout center = Ui.vertical(this);
        center.setPadding(Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 24), Ui.dp(this, 18));
        center.setGravity(Gravity.CENTER);
        scroll.addView(center, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        return center;
    }

    private void addBackButton(LinearLayout page) {
        page.addView(Ui.spacer(this, 24));
        Button back = Ui.button(this, "←  Kembali ke editor", false);
        page.addView(back, Ui.lp(this, -1, 55));
        back.setOnClickListener(v -> finish());
    }

    private void cleanupWebView() {
        if (webView != null) {
            webView.stopLoading();
            if (webView.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
    }

    // Custom name avoids colliding with a framework Activity method.
    private void applyFullscreenMode(boolean enabled) {
        getWindow().getDecorView().setSystemUiVisibility(enabled
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                  View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                  View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : 0);
    }

    @Override protected void onStop() {
        super.onStop();
        // A non-foreground renderer must never continue when the app is hidden.
        if (!completed && engine != null) engine.cancel();
    }

    @Override public void onBackPressed() {
        if (!completed && engine != null) engine.cancel();
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (engine != null && !completed) engine.cancel();
        cleanupWebView();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
