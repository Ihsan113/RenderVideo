package id.danzku.renderify;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int PICK_HTML = 27;
    private static final int MAX_HTML_BYTES = 1024 * 1024;
    private EditText htmlEditor, durationInput, scriptInput;
    private WebView preview;
    private TextView fileLabel, statusLabel;
    private int selectedQuality = 720;
    private int selectedFps = 30;
    private String originalFileName = "Contoh bawaan";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        buildScreen();
        restoreDraft();
        updatePreview();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(Ui.BG);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }
        LinearLayout page = Ui.vertical(this);
        page.setPadding(Ui.dp(this, 18), Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 38));
        scroll.addView(page);
        setContentView(scroll);

        LinearLayout titleRow = Ui.horizontal(this);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_logo);
        titleRow.addView(logo, Ui.lp(this, 54, 54));
        LinearLayout textColumn = Ui.vertical(this);
        textColumn.setPadding(Ui.dp(this, 9), 0, 0, 0);
        textColumn.addView(Ui.text(this, "Renderify", 23, Ui.WHITE, true));
        textColumn.addView(Ui.text(this, "HTML → MP4  •  Studio Android", 11, Ui.MUTED, false));
        titleRow.addView(textColumn, new LinearLayout.LayoutParams(0, -2, 1));
        TextView offline = Ui.text(this, "●  LOKAL", 10, Ui.GREEN, true);
        offline.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        offline.setBackground(Ui.background(this, Ui.PANEL_ALT, 20, Ui.BORDER));
        titleRow.addView(offline);
        page.addView(titleRow);
        page.addView(Ui.spacer(this, 22));

        LinearLayout hero = Ui.card(this, 20);
        hero.addView(Ui.text(this, "Wujudkan animasi HTML\nmenjadi video MP4.", 25, Ui.WHITE, true));
        hero.addView(Ui.spacer(this, 10));
        hero.addView(Ui.text(this,
                "Tempel kode atau pilih file HTML, lihat preview, lalu render langsung di HP.", 13, Ui.MUTED, false));
        hero.addView(Ui.spacer(this, 13));
        TextView chip = Ui.text(this, "  HTML + CSS + JavaScript      •      MP4 / H.264  ", 11, Ui.CYAN, true);
        hero.addView(chip);
        page.addView(hero);
        page.addView(Ui.spacer(this, 22));

        page.addView(Ui.text(this, "01  •  SUMBER ANIMASI", 12, Ui.CYAN, true));
        page.addView(Ui.spacer(this, 9));
        LinearLayout sourceCard = Ui.card(this, 15);
        LinearLayout sourceActions = Ui.horizontal(this);
        Button choose = Ui.button(this, "▣  Pilih file HTML", true);
        Button demo = Ui.button(this, "↻  Isi contoh", false);
        LinearLayout.LayoutParams weighted = new LinearLayout.LayoutParams(0, Ui.dp(this, 49), 1);
        weighted.setMargins(0, 0, Ui.dp(this, 8), 0);
        sourceActions.addView(choose, weighted);
        sourceActions.addView(demo, new LinearLayout.LayoutParams(0, Ui.dp(this, 49), 1));
        sourceCard.addView(sourceActions);
        choose.setOnClickListener(v -> openPicker());
        demo.setOnClickListener(v -> {
            try { htmlEditor.setText(assetHtml()); originalFileName = "Contoh pembayaran 10 detik";
                fileLabel.setText("●  " + originalFileName); durationInput.setText("10");
                scriptInput.setText("document.querySelector('button')?.click()"); updatePreview(); }
            catch (Exception e) { notifyError(e); }
        });
        sourceCard.addView(Ui.spacer(this, 13));
        fileLabel = Ui.text(this, "●  Editor HTML", 12, Ui.MUTED, false);
        sourceCard.addView(fileLabel);
        sourceCard.addView(Ui.spacer(this, 9));
        htmlEditor = new EditText(this);
        htmlEditor.setTextColor(Ui.WHITE);
        htmlEditor.setHintTextColor(Ui.MUTED);
        htmlEditor.setTextSize(12);
        htmlEditor.setTypeface(Typeface.MONOSPACE);
        htmlEditor.setGravity(Gravity.TOP | Gravity.START);
        htmlEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        htmlEditor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        htmlEditor.setPadding(Ui.dp(this, 13), Ui.dp(this, 13), Ui.dp(this, 13), Ui.dp(this, 13));
        htmlEditor.setBackground(Ui.background(this, Ui.PANEL_ALT, 12, Ui.BORDER));
        htmlEditor.setHint("<!DOCTYPE html>\n<html>\n  ...\n</html>");
        htmlEditor.setMinLines(10);
        htmlEditor.setMaxLines(16);
        sourceCard.addView(htmlEditor, new LinearLayout.LayoutParams(-1, -2));
        sourceCard.addView(Ui.spacer(this, 8));
        sourceCard.addView(Ui.text(this, "Mendukung HTML, CSS, dan JS inline. Maksimal 1 MB.", 11, Ui.MUTED, false));
        page.addView(sourceCard);
        page.addView(Ui.spacer(this, 21));

        page.addView(Ui.text(this, "02  •  PREVIEW", 12, Ui.CYAN, true));
        page.addView(Ui.spacer(this, 9));
        LinearLayout previewCard = Ui.card(this, 14);
        LinearLayout previewTop = Ui.horizontal(this);
        TextView previewText = Ui.text(this, "Preview interaktif", 15, Ui.WHITE, true);
        previewTop.addView(previewText, new LinearLayout.LayoutParams(0, -2, 1));
        Button refresh = Ui.button(this, "↻  Perbarui", false);
        previewTop.addView(refresh, Ui.lp(this, 122, 45));
        refresh.setOnClickListener(v -> updatePreview());
        previewCard.addView(previewTop);
        previewCard.addView(Ui.spacer(this, 11));
        preview = new WebView(this);
        configureWebView(preview);
        preview.setBackgroundColor(Color.rgb(9, 13, 22));
        preview.setWebChromeClient(new WebChromeClient());
        preview.setClipToOutline(true);
        preview.setBackground(Ui.background(this, Color.rgb(9, 13, 22), 12, Ui.BORDER));
        previewCard.addView(preview, Ui.lp(this, -1, 310));
        previewCard.addView(Ui.spacer(this, 10));
        previewCard.addView(Ui.text(this, "Kamu bisa menekan tombol animasi langsung di preview.", 11, Ui.MUTED, false));
        page.addView(previewCard);
        page.addView(Ui.spacer(this, 22));

        page.addView(Ui.text(this, "03  •  PENGATURAN RENDER", 12, Ui.CYAN, true));
        page.addView(Ui.spacer(this, 9));
        LinearLayout settings = Ui.card(this, 17);
        settings.addView(Ui.text(this, "Durasi (detik)", 14, Ui.WHITE, true));
        settings.addView(Ui.spacer(this, 8));
        durationInput = input("10", InputType.TYPE_CLASS_NUMBER);
        settings.addView(durationInput, Ui.lp(this, -1, 50));
        settings.addView(Ui.spacer(this, 15));
        settings.addView(Ui.text(this, "Kualitas video", 14, Ui.WHITE, true));
        settings.addView(Ui.spacer(this, 8));
        settings.addView(qualityButtons());
        settings.addView(Ui.spacer(this, 15));
        settings.addView(Ui.text(this, "Frame rate", 14, Ui.WHITE, true));
        settings.addView(Ui.spacer(this, 8));
        settings.addView(fpsButtons());
        settings.addView(Ui.spacer(this, 15));
        settings.addView(Ui.text(this, "JavaScript pemicu animasi (opsional)", 14, Ui.WHITE, true));
        settings.addView(Ui.spacer(this, 7));
        scriptInput = input("document.querySelector('button')?.click()", InputType.TYPE_CLASS_TEXT);
        scriptInput.setTextSize(12);
        scriptInput.setTypeface(Typeface.MONOSPACE);
        settings.addView(scriptInput, Ui.lp(this, -1, 53));
        settings.addView(Ui.spacer(this, 6));
        settings.addView(Ui.text(this, "Kosongkan jika animasi langsung berjalan saat halaman dibuka.", 11, Ui.MUTED, false));
        page.addView(settings);
        page.addView(Ui.spacer(this, 16));

        Button render = Ui.button(this, "▶  RENDER KE MP4", true);
        render.setTextSize(16);
        page.addView(render, Ui.lp(this, -1, 60));
        render.setOnClickListener(v -> startRender());
        page.addView(Ui.spacer(this, 9));
        statusLabel = Ui.text(this, "Izin rekam layar akan diminta oleh Android sebelum render.", 11, Ui.MUTED, false);
        statusLabel.setGravity(Gravity.CENTER);
        page.addView(statusLabel);
        page.addView(Ui.spacer(this, 14));
        TextView footer = Ui.text(this, "Renderify 1.0  •  Danzku Studio\nVideo tersimpan di Movies/Renderify", 11, Ui.MUTED, false);
        footer.setGravity(Gravity.CENTER);
        page.addView(footer);
    }

    private EditText input(String value, int type) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(Ui.WHITE);
        field.setInputType(type);
        field.setText(value);
        field.setSelectAllOnFocus(true);
        field.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        field.setBackground(Ui.background(this, Ui.PANEL_ALT, 12, Ui.BORDER));
        return field;
    }

    private View qualityButtons() {
        LinearLayout row = Ui.horizontal(this);
        String[] labels = {"540p  •  Hemat", "720p", "1080p"};
        int[] values = {540, 720, 1080};
        Button[] bs = new Button[3];
        for (int i = 0; i < 3; i++) {
            final int index = i;
            Button b = Ui.button(this, labels[i], i == 1);
            b.setTextSize(11);
            bs[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1);
            if (i > 0) lp.setMargins(Ui.dp(this, 5), 0, 0, 0);
            row.addView(b, lp);
            b.setOnClickListener(v -> {
                selectedQuality = values[index];
                for (int j = 0; j < bs.length; j++) setSelected(bs[j], j == index);
            });
        }
        return row;
    }

    private View fpsButtons() {
        LinearLayout row = Ui.horizontal(this);
        Button a = Ui.button(this, "24 FPS  •  Hemat", false);
        Button b = Ui.button(this, "30 FPS  •  Halus", true);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, Ui.dp(this, 45), 1);
        left.setMargins(0, 0, Ui.dp(this, 7), 0);
        row.addView(a, left);
        row.addView(b, new LinearLayout.LayoutParams(0, Ui.dp(this, 45), 1));
        a.setOnClickListener(v -> { selectedFps = 24; setSelected(a, true); setSelected(b, false); });
        b.setOnClickListener(v -> { selectedFps = 30; setSelected(a, false); setSelected(b, true); });
        return row;
    }

    private void setSelected(Button b, boolean chosen) {
        b.setTextColor(chosen ? Ui.BG : Ui.WHITE);
        b.setBackground(chosen ? Ui.gradient(this) : Ui.background(this, Ui.PANEL_ALT, 13, Ui.BORDER));
    }

    static void configureWebView(WebView web) {
        WebSettings options = web.getSettings();
        options.setJavaScriptEnabled(true);
        options.setDomStorageEnabled(false);
        options.setAllowFileAccess(false);
        options.setAllowContentAccess(false);
        options.setSupportZoom(false);
        options.setJavaScriptCanOpenWindowsAutomatically(false);
        options.setMediaPlaybackRequiresUserGesture(true);
        options.setBlockNetworkLoads(true); // No INTERNET permission: input stays on-device.
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
    }

    private void updatePreview() {
        if (preview == null || htmlEditor == null) return;
        String html = htmlEditor.getText().toString();
        if (html.trim().isEmpty()) { Toast.makeText(this, "Isi HTML terlebih dahulu", Toast.LENGTH_SHORT).show(); return; }
        preview.loadDataWithBaseURL("https://renderify.invalid/", html, "text/html", "UTF-8", null);
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "text/plain", "application/xhtml+xml", "application/octet-stream"});
        startActivityForResult(intent, PICK_HTML);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_HTML || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            if (in == null) throw new IllegalStateException("File tidak dapat dibaca");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int len;
            while ((len = in.read(buffer)) != -1) {
                if (out.size() + len > MAX_HTML_BYTES) throw new IllegalArgumentException("Ukuran file melebihi 1 MB");
                out.write(buffer, 0, len);
            }
            String raw = out.toString("UTF-8");
            if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
            htmlEditor.setText(raw);
            originalFileName = "File HTML dipilih";
            fileLabel.setText("✓  " + originalFileName);
            updatePreview();
            Toast.makeText(this, "HTML berhasil dimuat", Toast.LENGTH_SHORT).show();
        } catch (Exception error) { notifyError(error); }
    }

    private void startRender() {
        String html = htmlEditor.getText().toString();
        if (html.trim().isEmpty()) { Toast.makeText(this, "HTML masih kosong", Toast.LENGTH_LONG).show(); return; }
        if (html.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES) {
            Toast.makeText(this, "HTML maksimal 1 MB", Toast.LENGTH_LONG).show(); return;
        }
        int seconds;
        try { seconds = Integer.parseInt(durationInput.getText().toString().trim()); }
        catch (NumberFormatException err) { seconds = -1; }
        if (seconds < 2 || seconds > 120) {
            Toast.makeText(this, "Durasi harus antara 2 sampai 120 detik", Toast.LENGTH_LONG).show(); return;
        }
        try {
            File target = new File(getCacheDir(), "render_input.html");
            try (FileOutputStream output = new FileOutputStream(target)) { output.write(html.getBytes(StandardCharsets.UTF_8)); }
            Intent intent = new Intent(this, RenderActivity.class);
            intent.putExtra(RenderActivity.EXTRA_HTML_PATH, target.getAbsolutePath());
            intent.putExtra(RenderActivity.EXTRA_DURATION, seconds);
            intent.putExtra(RenderActivity.EXTRA_FPS, selectedFps);
            intent.putExtra(RenderActivity.EXTRA_QUALITY, selectedQuality);
            intent.putExtra(RenderActivity.EXTRA_SCRIPT, scriptInput.getText().toString());
            startActivity(intent);
        } catch (Exception error) { notifyError(error); }
    }

    private String assetHtml() throws Exception {
        try (InputStream in = getAssets().open("payment_demo.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192]; int n;
            while ((n = in.read(chunk)) != -1) out.write(chunk, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void restoreDraft() {
        try {
            File stored = new File(getFilesDir(), "editor_draft.html");
            if (stored.isFile() && stored.length() <= MAX_HTML_BYTES) {
                byte[] bytes = new byte[(int) stored.length()];
                try (FileInputStream in = new FileInputStream(stored)) {
                    int offset = 0;
                    while (offset < bytes.length) {
                        int n = in.read(bytes, offset, bytes.length - offset);
                        if (n == -1) break;
                        offset += n;
                    }
                }
                htmlEditor.setText(new String(bytes, StandardCharsets.UTF_8));
                fileLabel.setText("✓  Draft tersimpan");
            } else {
                htmlEditor.setText(assetHtml());
                fileLabel.setText("●  Contoh pembayaran 10 detik");
            }
            android.content.SharedPreferences pref = getPreferences(MODE_PRIVATE);
            durationInput.setText(pref.getString("duration", "10"));
            scriptInput.setText(pref.getString("script", "document.querySelector('button')?.click()"));
        } catch (Exception e) { notifyError(e); }
    }

    @Override protected void onPause() {
        super.onPause();
        if (htmlEditor == null) return;
        try (FileOutputStream out = openFileOutput("editor_draft.html", MODE_PRIVATE)) {
            out.write(htmlEditor.getText().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { /* A failed draft save must not interrupt the editor. */ }
        getPreferences(MODE_PRIVATE).edit()
                .putString("duration", durationInput.getText().toString())
                .putString("script", scriptInput.getText().toString()).apply();
    }

    @Override protected void onDestroy() {
        if (preview != null) {
            preview.stopLoading();
            preview.destroy();
        }
        super.onDestroy();
    }

    private void notifyError(Exception error) {
        Toast.makeText(this, "Gagal: " + error.getMessage(), Toast.LENGTH_LONG).show();
    }
}
