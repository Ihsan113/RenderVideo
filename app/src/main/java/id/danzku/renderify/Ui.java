package id.danzku.renderify;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small reusable UI primitives; no third-party UI dependencies. */
final class Ui {
    static final int BG = Color.rgb(8, 13, 27);
    static final int PANEL = Color.rgb(18, 28, 47);
    static final int PANEL_ALT = Color.rgb(13, 23, 40);
    static final int BORDER = Color.rgb(42, 59, 84);
    static final int WHITE = Color.rgb(242, 248, 255);
    static final int MUTED = Color.rgb(149, 165, 189);
    static final int CYAN = Color.rgb(72, 201, 248);
    static final int VIOLET = Color.rgb(150, 105, 255);
    static final int GREEN = Color.rgb(55, 220, 155);

    static int dp(Context context, float n) {
        return (int) (n * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable background(Context c, int color, int corner, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, corner));
        if (strokeColor != Color.TRANSPARENT) g.setStroke(dp(c, 1), strokeColor);
        return g;
    }

    static GradientDrawable gradient(Context c) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{CYAN, Color.rgb(78, 155, 250), VIOLET});
        g.setCornerRadius(dp(c, 14));
        return g;
    }

    static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setFontFeatureSettings("kern");
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    static Button button(Context c, String value, boolean primary) {
        Button b = new Button(c);
        b.setText(value);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? Color.rgb(11, 21, 37) : WHITE);
        b.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
        b.setMinHeight(dp(c, 50));
        b.setMinimumHeight(dp(c, 50));
        b.setElevation(0);
        android.graphics.drawable.Drawable normal = primary ? gradient(c)
                : background(c, PANEL_ALT, 13, BORDER);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), normal, null));
        return b;
    }

    static View spacer(Context c, int height) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, height)));
        return v;
    }

    static LinearLayout card(Context c, int padding) {
        LinearLayout l = vertical(c);
        l.setPadding(dp(c, padding), dp(c, padding), dp(c, padding), dp(c, padding));
        l.setBackground(background(c, PANEL, 20, BORDER));
        return l;
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(c, w), h < 0 ? h : dp(c, h));
    }

    private Ui() {}
}
