package com.sjcw.titanicvulkan;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.google.androidgamesdk.GameActivity;

public class MainActivity extends GameActivity {
    static { System.loadLibrary("titanic_vulkan"); }

    private static native void nativeSetPaused(boolean paused);
    private static native void nativeSetSpeed(float speed);
    private static native void nativeSetCameraPreset(int preset);
    private static native void nativeResetCamera();

    private boolean paused = false;
    private int speedIndex = 0;
    private int viewIndex = 0;
    private final float[] speeds = {1f, 5f, 10f};

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setImmersive();
        addControls();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setImmersive();
    }

    private GradientDrawable panelBackground() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x88060A0F);
        g.setCornerRadius(28f);
        g.setStroke(1, 0x557CA7C5);
        return g;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11f);
        b.setAllCaps(false);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(20, 6, 20, 6);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private void addControls() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(10, 4, 10, 4);
        bar.setBackground(panelBackground());

        Button play = button("Pause");
        Button speed = button("×1");
        Button view = button("View");
        Button reset = button("Reset");

        play.setOnClickListener(v -> {
            paused = !paused;
            nativeSetPaused(paused);
            play.setText(paused ? "Play" : "Pause");
        });
        speed.setOnClickListener(v -> {
            speedIndex = (speedIndex + 1) % speeds.length;
            float s = speeds[speedIndex];
            nativeSetSpeed(s);
            speed.setText("×" + (int)s);
        });
        view.setOnClickListener(v -> {
            viewIndex = (viewIndex + 1) % 4;
            nativeSetCameraPreset(viewIndex);
            String[] names = {"Cine","Broad","Bow","Stern"};
            view.setText(names[viewIndex]);
        });
        reset.setOnClickListener(v -> { viewIndex = 0; nativeResetCamera(); view.setText("Cine"); });

        for (Button b : new Button[]{play,speed,view,reset}) bar.addView(b,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(330), LinearLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(12);
        addContentView(bar, lp);
    }

    private int dp(int v){ return Math.round(v * getResources().getDisplayMetrics().density); }

    private void setImmersive() {
        Window window = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
