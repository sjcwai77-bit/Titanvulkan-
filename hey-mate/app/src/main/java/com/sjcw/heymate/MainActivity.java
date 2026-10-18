package com.sjcw.heymate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Date;

public final class MainActivity extends Activity {
    private static final int REQUEST_MIC = 500;
    private static final int BACKGROUND = 0xff0a1220;
    private static final int PANEL = 0xff152234;
    private static final int MINT = 0xff70e1cd;
    private static final int MUTED = 0xffa6b6c5;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, detail, lastHeard, lastTriggered, listenerMode;
    private Button startStop;
    private SharedPreferences prefs;
    private boolean resumed = false;
    private final Runnable update = new Runnable() {
        @Override public void run() { refresh(); if (resumed) handler.postDelayed(this, 850); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        prefs = getSharedPreferences(WakeService.PREFS, MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(23), dp(25), dp(23), dp(38));
        scroll.addView(page);

        TextView eyebrow = text("SJCW LABS  /  VOICE UTILITY", 11, MINT, Typeface.BOLD);
        eyebrow.setLetterSpacing(.14f);
        page.addView(eyebrow);
        addGap(page, 18);
        page.addView(text("Hey Mate", 38, Color.WHITE, Typeface.BOLD));
        page.addView(text("Your voice. Your shortcut to ChatGPT.", 15, MUTED, Typeface.NORMAL));
        addGap(page, 24);

        LinearLayout hero = panel();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView waveform = text("⌁   ≋   ⌁", 42, MINT, Typeface.BOLD);
        waveform.setGravity(Gravity.CENTER);
        hero.addView(waveform);
        addGap(hero, 4);
        TextView phrase = text("“HEY MATE”", 29, Color.WHITE, Typeface.BOLD);
        phrase.setLetterSpacing(.08f);
        phrase.setGravity(Gravity.CENTER);
        hero.addView(phrase);
        addGap(hero, 8);
        status = text("NOT LISTENING", 12, MINT, Typeface.BOLD);
        status.setGravity(Gravity.CENTER);
        status.setLetterSpacing(.12f);
        hero.addView(status);
        addGap(hero, 8);
        detail = text("Tap Start to enable the wake phrase.", 13, MUTED, Typeface.NORMAL);
        detail.setGravity(Gravity.CENTER);
        hero.addView(detail);
        addGap(hero, 20);
        startStop = button("START LISTENING", MINT, BACKGROUND);
        startStop.setOnClickListener(v -> {
            if (prefs.getBoolean(WakeService.RUNNING, false)) stopServiceNow();
            else attemptStart();
        });
        hero.addView(startStop, new LinearLayout.LayoutParams(-1, dp(57)));
        page.addView(hero);
        addGap(page, 17);

        LinearLayout log = panel();
        log.addView(text("LIVE MONITOR", 12, MINT, Typeface.BOLD));
        addGap(log, 9);
        lastHeard = text("Last recognised speech: —", 13, Color.WHITE, Typeface.NORMAL);
        log.addView(lastHeard);
        addGap(log, 9);
        lastTriggered = text("Last wake-up: —", 13, MUTED, Typeface.NORMAL);
        log.addView(lastTriggered);
        addGap(log, 9);
        TextView tapState = text("Shortcut status: —", 12, MUTED, Typeface.NORMAL);
        log.addView(tapState);
        handler.postDelayed(new Runnable(){ @Override public void run() {
            tapState.setText("Shortcut status: " + prefs.getString("shortcut_status","Not attempted yet"));
            if(!isFinishing())handler.postDelayed(this,1500);
        }},1000);
        addGap(log, 10);
        TextView modelStage = text("Model state: —",13,Color.WHITE,Typeface.NORMAL);
        log.addView(modelStage);
        addGap(log, 6);
        TextView micLevel = text("Mic signal: —",13,MINT,Typeface.BOLD);
        log.addView(micLevel);
        addGap(log, 6);
        TextView latestSpeech = text("Recognised words: —",13,MUTED,Typeface.NORMAL);
        log.addView(latestSpeech);
        addGap(log, 5);
        TextView engineError = text("Listener error: none",12,MUTED,Typeface.NORMAL);
        log.addView(engineError);
        handler.postDelayed(new Runnable(){ @Override public void run() {
            modelStage.setText("Model state: "+prefs.getString("engine_stage","Not started"));
            micLevel.setText("Mic signal: "+prefs.getString("mic_level","0")+" / 100");
            latestSpeech.setText("Recognised words: "+prefs.getString("latest_recognition","—"));
            String err=prefs.getString("engine_error","");
            engineError.setText(err.isEmpty()?"Listener error: none":"Listener error: "+err);
            if(!isFinishing())handler.postDelayed(this,900);
        }},600);
        page.addView(log);
        addGap(page, 16);

        LinearLayout wifi = panel();
        wifi.addView(text("HOME WI-FI SAFETY",12,MINT,Typeface.BOLD));
        addGap(wifi,10);
        TextView wifiState=text("Network: identifying…",13,Color.WHITE,Typeface.NORMAL);
        wifi.addView(wifiState);
        addGap(wifi,8);
        TextView selectedWifi=text("Trusted network: not configured",13,MUTED,Typeface.NORMAL);
        wifi.addView(selectedWifi);
        addGap(wifi,10);
        Button saveWifi=button("TRUST CURRENT WI-FI",MINT,BACKGROUND);
        saveWifi.setOnClickListener(v->{
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},501);return;
            }
            HomeWifi.Connection con=HomeWifi.current(this);
            if(!con.wifi||con.ssid.isEmpty()){
                Toast.makeText(this,con.diagnostic,Toast.LENGTH_LONG).show();return;
            }
            new android.app.AlertDialog.Builder(this)
                .setTitle("Trust home network?")
                .setMessage("Only allow Hey Mate to listen while this Wi-Fi network is active: "+con.ssid+
                    "\n\nWi-Fi names alone aren't proof of physical location. Keep your Samsung PIN enabled.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Trust this Wi-Fi",(d,w)->{
                    prefs.edit().putString(HomeWifi.PREF_SSID,con.ssid).apply();
                    if(prefs.getBoolean(WakeService.RUNNING,false))startService(new Intent(this,WakeService.class).setAction(WakeService.ACTION_RESUME));
                    refresh();
                }).show();
        });
        wifi.addView(saveWifi,new LinearLayout.LayoutParams(-1,dp(49)));
        addGap(wifi,9);
        Button clearWifi=button("CLEAR TRUSTED NETWORK",PANEL,MUTED);
        clearWifi.setOnClickListener(v->{
            prefs.edit().remove(HomeWifi.PREF_SSID).apply();
            if(prefs.getBoolean(WakeService.RUNNING,false))
                startService(new Intent(this,WakeService.class).setAction(WakeService.ACTION_RESUME));
            refresh();
        });
        wifi.addView(clearWifi,new LinearLayout.LayoutParams(-1,dp(47)));
        addGap(wifi,10);
        wifi.addView(text("On any other connection, including mobile data, the microphone pauses. Screen timeout stays unchanged. Samsung's PIN and lock-screen trust are configured separately in Modes and Routines.",12,MUTED,Typeface.NORMAL));
        handler.postDelayed(new Runnable(){@Override public void run(){
            HomeWifi.Connection c=HomeWifi.current(MainActivity.this);
            wifiState.setText("Network: "+c.diagnostic);
            selectedWifi.setText("Trusted network: "+prefs.getString(HomeWifi.PREF_SSID,"Not configured"));
            if(!isFinishing())handler.postDelayed(this,1500);
        }},300);
        page.addView(wifi);
        addGap(page,16);
        LinearLayout settings = panel();
        settings.addView(text("SPEECH RECOGNITION", 12, MINT, Typeface.BOLD));
        addGap(settings, 9);
        listenerMode = text("", 13, Color.WHITE, Typeface.NORMAL);
        settings.addView(listenerMode);
        addGap(settings, 11);
        Switch fallback = new Switch(this);
        fallback.setText("Allow one-time offline model download (~40 MB)");
        fallback.setTextColor(Color.WHITE);
        fallback.setTextSize(14);
        fallback.setChecked(prefs.getBoolean("model_download_approved", false));
        fallback.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("model_download_approved", checked).apply();
            Toast.makeText(this, checked ? "Press Start Listening to download the model" : "Model download disabled", Toast.LENGTH_LONG).show();
            refresh();
        });
        settings.addView(fallback);
        TextView note = text("Default: on-device only. Fallback uses the phone's speech provider, which may send audio online. No recordings are saved by this app.", 12, MUTED, Typeface.NORMAL);
        note.setLineSpacing(dp(2), 1.0f);
        settings.addView(note);
        page.addView(settings);
        addGap(page, 16);

        LinearLayout actions = panel();
        actions.addView(text("CHATGPT HANDOFF", 12, MINT, Typeface.BOLD));
        addGap(actions, 9);
        TextView about = text("Hey Mate searches your Samsung home screens for the page with Halifax, DeepSeek and Proton Mail, then presses the central Voice shortcut once. Tap position remains adjustable.", 13, Color.WHITE, Typeface.NORMAL);
        about.setLineSpacing(dp(2), 1.0f);
        actions.addView(about);
        addGap(actions, 15);
        Button access = button("ENABLE VOICE BUTTON ACCESS", MINT, BACKGROUND);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        actions.addView(access, new LinearLayout.LayoutParams(-1, dp(51)));
        addGap(actions, 12);
        TextView position = text("VOICE SHORTCUT POSITION — 50% across, 57% down", 12, MINT, Typeface.BOLD);
        actions.addView(position);
        addGap(actions, 8);
        android.widget.SeekBar xAdjust = new android.widget.SeekBar(this);
        xAdjust.setMax(100);
        xAdjust.setProgress(prefs.getInt("tap_x", 50));
        actions.addView(xAdjust);
        android.widget.SeekBar yAdjust = new android.widget.SeekBar(this);
        yAdjust.setMax(100);
        yAdjust.setProgress(prefs.getInt("tap_y", 57));
        actions.addView(yAdjust);
        android.widget.SeekBar.OnSeekBarChangeListener move = new android.widget.SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(android.widget.SeekBar s,int v,boolean user){
                if(user) {
                    prefs.edit().putInt("tap_x",xAdjust.getProgress()).putInt("tap_y",yAdjust.getProgress()).apply();
                    position.setText("VOICE SHORTCUT POSITION — "+xAdjust.getProgress()+"% across, "+yAdjust.getProgress()+"% down");
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s){}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s){}
        };
        xAdjust.setOnSeekBarChangeListener(move);
        yAdjust.setOnSeekBarChangeListener(move);
        addGap(actions, 10);
        Button test = button("TEST SINGLE HOME-SCREEN TAP", MINT, BACKGROUND);
        test.setOnClickListener(v -> {
            if(!VoiceAssistService.isReady()){
                Toast.makeText(this,"Enable Voice Button Access first",Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } else WakeService.testHomeShortcut();
        });
        actions.addView(test,new LinearLayout.LayoutParams(-1,dp(50)));
        addGap(actions, 9);
        Button open = button("OPEN CHATGPT", PANEL, MINT);
        open.setOnClickListener(v -> openChatGPT());
        actions.addView(open, new LinearLayout.LayoutParams(-1, dp(50)));
        addGap(actions, 11);
        Button battery = button("BATTERY SETTINGS", PANEL, MUTED);
        battery.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception e) { Toast.makeText(this, "Battery settings unavailable", Toast.LENGTH_SHORT).show(); }
        });
        actions.addView(battery, new LinearLayout.LayoutParams(-1, dp(49)));
        page.addView(actions);
        addGap(page, 19);

        TextView caveat = text("ANDROID NOTE · Begin listening while the app is open. A notification indicates microphone use. On a locked phone, or when Android blocks background launch, you may need to unlock or tap the notification. Listening continuously with the window minimised, without the ten-second Google recogniser timeout. On hearing Hey Mate, it returns to the home screen and taps the configured shortcut once. Listening pauses while ChatGPT Voice has the microphone, then resumes when the conversation closes if detectable; otherwise tap Resume in the notification. Android may still stop microphone access after force-stop or system restrictions.", 12, MUTED, Typeface.NORMAL);
        caveat.setLineSpacing(dp(3), 1f);
        page.addView(caveat);
        setContentView(scroll);
        refresh();
    }

    private void attemptStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_MIC);
            return;
        }
        launchService();
    }
    @Override public void onRequestPermissionsResult(int code, String[] ps, int[] grants) {
        super.onRequestPermissionsResult(code, ps, grants);
        if (code == REQUEST_MIC) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchService();
            else Toast.makeText(this, "Microphone permission is needed", Toast.LENGTH_LONG).show();
        }
    }
    private void launchService() {
        try {
            Intent intent = new Intent(this, WakeService.class).setAction(WakeService.ACTION_START);
            startForegroundService(intent);
            prefs.edit().putString(WakeService.STATUS, "Starting listener…").apply();
            handler.postDelayed(this::refresh, 250);
        } catch (RuntimeException ex) {
            Toast.makeText(this, "Android prevented listener start: " + ex.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }
    private void stopServiceNow() {
        startService(new Intent(this, WakeService.class).setAction(WakeService.ACTION_STOP));
        handler.postDelayed(this::refresh, 300);
    }
    private void openChatGPT() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
        if (launch != null) startActivity(launch);
        else {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.openai.chatgpt"))); }
            catch (Exception e) { Toast.makeText(this, "Install ChatGPT from Google Play", Toast.LENGTH_LONG).show(); }
        }
    }
    private void refresh() {
        if (status == null) return;
        boolean active = prefs.getBoolean(WakeService.RUNNING, false);
        status.setText(active ? "●  LISTENING" : "○  STANDBY");
        detail.setText(prefs.getString(WakeService.STATUS, "Tap Start to enable wake phrase."));
        startStop.setText(active ? "STOP LISTENING" : "START LISTENING");
        startStop.setBackground(tintRounded(active ? 0xffeb7580 : MINT, dp(15)));
        startStop.setTextColor(active ? Color.WHITE : BACKGROUND);
        String heard = prefs.getString(WakeService.LAST_HEARD, "");
        if (heard.length() > 75) heard = heard.substring(0, 75) + "…";
        lastHeard.setText("Last recognised speech: " + (heard.isEmpty() ? "—" : heard));
        long when = prefs.getLong(WakeService.LAST_TRIGGER, 0);
        lastTriggered.setText("Last wake-up: " + (when == 0 ? "—" : DateFormat.getTimeFormat(this).format(new Date(when))));
        boolean ready = OfflineWakeEngine.modelReady(this);
        listenerMode.setText(ready ? "Offline Vosk model: installed ✓ · continuous microphone" :
            "Offline Vosk model: not installed · enable one-time download below");
    }
    private LinearLayout panel() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(19), dp(20), dp(19), dp(20));
        card.setBackground(tintRounded(PANEL, dp(20)));
        return card;
    }
    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(foreground);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(tintRounded(background, dp(15)));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        return b;
    }
    private GradientDrawable tintRounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
    private TextView text(String s, int sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, weight);
        return t;
    }
    private void addGap(LinearLayout p, int h) {
        View spacer = new View(this);
        p.addView(spacer, new LinearLayout.LayoutParams(1, dp(h)));
    }
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    @Override public void onResume() { super.onResume(); resumed = true; handler.post(update); }
    @Override public void onPause() { resumed = false; handler.removeCallbacks(update); super.onPause(); }
}