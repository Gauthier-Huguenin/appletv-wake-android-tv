package io.github.gauthierhuguenin.atvwake;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Fallback screen, shown when Home cannot switch to the main input. Needs a D-pad (TV remote or
 * phone remote app): the Apple TV remote only sends volume and mute to the TV.
 */
public class PickerActivity extends Activity {
    private static final long AUTO_CLOSE_MS = 20_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoClose = this::finish;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(12, 12, 16));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        String main = Prefs.string(this, Prefs.MAIN_INPUT);
        String second = Prefs.string(this, Prefs.SECOND_INPUT);
        LinearLayout row = new LinearLayout(this);
        TextView first = null;
        if (main == null) {
            TextView hint = new TextView(this);
            hint.setText("AppleTV Wake is not configured yet.\nRun scripts/setup.sh from a computer.");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            hint.setTextColor(Color.WHITE);
            hint.setGravity(Gravity.CENTER);
            row.addView(hint);
        } else {
            first = card(Prefs.label(this, main), main);
            row.addView(first, cardParams());
            if (second != null) row.addView(card(Prefs.label(this, second), second), cardParams());
        }
        column.addView(row);

        TextView settings = new TextView(this);
        settings.setText("Settings");
        settings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        settings.setTextColor(Color.argb(150, 255, 255, 255));
        settings.setPadding(dp(24), dp(12), dp(24), dp(12));
        settings.setFocusable(true);
        settings.setBackground(focusBackground(dp(24)));
        settings.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(48);
        column.addView(settings, sp);

        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        (first != null ? first : settings).requestFocus();
    }

    private TextView card(String label, String input) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setBackground(focusBackground(dp(28)));
        t.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                .setDuration(120).start());
        t.setOnClickListener(v -> {
            Inputs.tune(this, input);
            finish();
        });
        return t;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(340), dp(200));
        p.leftMargin = dp(24);
        p.rightMargin = dp(24);
        return p;
    }

    private StateListDrawable focusBackground(int radius) {
        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(radius);
        idle.setColor(Color.argb(40, 255, 255, 255));
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(radius);
        focused.setColor(Color.rgb(58, 92, 255));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, focused);
        s.addState(new int[] {}, idle);
        return s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(autoClose);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        handler.removeCallbacks(autoClose);
        handler.postDelayed(autoClose, AUTO_CLOSE_MS);
        return super.dispatchKeyEvent(event);
    }
}
