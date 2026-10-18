package com.sjcw.heymate;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

public final class WakeService extends Service {
    public static final String ACTION_START = "com.sjcw.heymate.START";
    public static final String ACTION_STOP = "com.sjcw.heymate.STOP";
    public static final String PREFS = "hey_mate";
    public static final String ACTION_RESUME = "com.sjcw.heymate.RESUME";
    public static final String RUNNING = "running";
    public static final String STATUS = "status";
    public static final String LAST_HEARD = "last_heard";
    public static final String LAST_TRIGGER = "last_trigger";
    public static final String ALLOW_FALLBACK = "allow_fallback";
    private static final String CHANNEL = "hey_mate_listener";
    private static final String TRIGGER_CHANNEL = "hey_mate_trigger";
    private static final int ONGOING_NOTIFICATION = 201;
    private static final int TRIGGER_NOTIFICATION = 202;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OfflineWakeEngine engine;
    private boolean stopping = false;
    private boolean paused = false;
    private static WakeService activeInstance;
    private NotificationManager notifications;

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        engine = new OfflineWakeEngine(this, new OfflineWakeEngine.Callbacks() {
            @Override public void status(String t) { setStatus(t); }
            @Override public void heard(String t) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LAST_HEARD,t).apply();
            }
            @Override public void trigger() { onWake(); }
        });
        notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Hey Mate — Listening", NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel(TRIGGER_CHANNEL, "Hey Mate — Wake detected", NotificationManager.IMPORTANCE_HIGH));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCompletely("Stopped");
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopCompletely("Microphone permission required");
            return START_NOT_STICKY;
        }
        if (stopping) return START_NOT_STICKY;
        if (ACTION_RESUME.equals(action)) { paused=false; getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("voice_pending", false).putBoolean("voice_active", false).apply(); }
        showForeground();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(RUNNING, true).apply();
        if (!paused) beginListening();
        return START_STICKY;
    }

    private void showForeground() {
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, WakeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 4, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent resume = PendingIntent.getService(this, 5, new Intent(this, WakeService.class).setAction(ACTION_RESUME), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("SJCW Hey Mate")
                .setContentText(paused ? "ChatGPT voice handoff — resume when finished" : "Listening in background for Hey mate")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Resume", resume).build())
                .addAction(new Notification.Action.Builder(null, "Stop listening", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ONGOING_NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else startForeground(ONGOING_NOTIFICATION, n);
    }

    private void beginListening() {
        if (stopping || paused || engine == null) return;
        engine.start();
    }

    private void setStatus(String value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(STATUS, value).apply();
    }

    private void onWake() {
        if(stopping || paused) return;
        paused = true;
        handler.removeCallbacks(retryRunnable);
        listening = false;
        if(recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch(Exception ignored) {}
            recognizer = null;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(LAST_TRIGGER,System.currentTimeMillis())
            .putBoolean("voice_pending",true)
            .putBoolean("voice_active",false).apply();
        showForeground();
        setStatus("Hey mate heard: touching Voice shortcut on home screen");
        if(!VoiceAssistService.startHomeShortcut()) {
            setStatus("Enable Voice Button Access in Hey Mate settings");
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("voice_pending",false).apply();
            showPermissionNotification();
            handler.postDelayed(this::resumeListening,1800);
            return;
        }
        // Recover only when the home button could not be pressed.
        handler.postDelayed(() -> {
            if(paused && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("voice_pending",false)) {
                setStatus("Voice shortcut timed out; listening again");
                resumeListening();
            }
        },15000);
    }

    private void showPermissionNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 42, new Intent(this,MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        notifications.notify(TRIGGER_NOTIFICATION, new Notification.Builder(this,TRIGGER_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hey Mate needs Voice Button Access")
            .setContentText("Tap to enable the accessibility service")
            .setContentIntent(open).setAutoCancel(true).build());
    }

    public static void shortcutFailed() {
        WakeService w = activeInstance;
        if(w!=null)w.handler.post(()->{
            w.setStatus("Home shortcut was not tapped; resuming listening");
            w.resumeListening();
        });
    }
    public static void testHomeShortcut() {
        WakeService w=activeInstance;
        if(w!=null) w.handler.post(w::onWake);
        else VoiceAssistService.startHomeShortcut();
    }

    private void resumeListening() {
        if (stopping) return;
        paused=false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("voice_pending",false).putBoolean("voice_active",false).apply();
        showForeground();
        handler.postDelayed(this::beginListening,700);
    }
    public static void voiceStarted() { WakeService w=activeInstance; if(w!=null) w.handler.post(() -> { w.paused=true; w.setStatus("Home shortcut tapped; ChatGPT Voice should now open"); w.showForeground(); }); }
    public static void voiceEnded() { WakeService w=activeInstance; if(w!=null) w.handler.post(w::resumeListening); }

    private void stopCompletely(String reason) {
        stopping = true;
        listening = false;
        handler.removeCallbacks(retryRunnable);
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        if (reason != null) setStatus(reason);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(RUNNING, false).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (activeInstance == this) activeInstance = null;
        if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) { } recognizer = null; }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(RUNNING, false).apply();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onReadyForSpeech(Bundle params) { consecutiveFailures = 0; }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onError(int error) {
        if (stopping || paused) return;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            setStatus("Recognition failed (error " + error + "). Restart listening.");
            stopCompletely(null);
            return;
        }
        if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) consecutiveFailures = 0;
        else consecutiveFailures++;
        if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_NO_MATCH) {
            setStatus("Reconnecting speech service (error " + error + ")");
        }
        scheduleRetry(Math.min(12000, 600 + (long)consecutiveFailures * 650));
    }
    @Override public void onResults(Bundle results) {
        if (stopping || paused) return;
        if (matched(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))) onWake();
        else scheduleRetry(350);
    }
    @Override public void onPartialResults(Bundle partialResults) {
        if (!stopping && !paused && matched(partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))) onWake();
    }
    @Override public void onEvent(int eventType, Bundle params) { }
}