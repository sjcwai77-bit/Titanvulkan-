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
    // An active microphone foreground service does not by itself keep the CPU
    // awake after display lock. Hold a CPU-only lock while listening.
    private PowerManager.WakeLock listeningCpuLock;
    private long listenStartedAt = 0L;
    private long lastRecoveryAt = 0L;
    private int recoveryCount = 0;
    private final Runnable audioWatchdog = new Runnable() {
        @Override public void run() {
            if (stopping) return;
            if (!paused && engine != null) {
                long now = System.currentTimeMillis();
                android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
                long recent = Math.max(listenStartedAt, p.getLong("last_audio_at", 0));
                boolean stale = recent > 0 && now - recent > 15000;
                String stage = p.getString("engine_stage", "");
                boolean failed = "ERROR".equals(stage) || "Microphone stopped".equals(stage);
                if ((stale || failed) && now - lastRecoveryAt > 12000 && recoveryCount < 12) {
                    lastRecoveryAt = now;
                    recoveryCount++;
                    setStatus("Audio stalled; restarting listener (" + recoveryCount + ")");
                    p.edit().putString("recovery_status", "Recovery #" + recoveryCount +
                        ", cause=" + (stale ? "stale microphone" : stage)).apply();
                    engine.restart();
                    listenStartedAt = now;
                }
                if (!stale && "Listening".equals(stage)) recoveryCount = 0;
                PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
                p.edit().putString("screen_state", pm != null && pm.isInteractive() ?
                    "Screen on" : "Screen off")
                    .putBoolean("cpu_wake_lock", listeningCpuLock != null &&
                        listeningCpuLock.isHeld()).apply();
            }
            handler.postDelayed(this, 4000);
        }
    };
    private void holdCpuWhileListening() {
        if (listeningCpuLock == null) {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            listeningCpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "SJCWHeyMate:ActiveOfflineWakeRecognizer");
            listeningCpuLock.setReferenceCounted(false);
        }
        if (!listeningCpuLock.isHeld()) listeningCpuLock.acquire();
    }
    private void releaseCpuLock() {
        if (listeningCpuLock != null && listeningCpuLock.isHeld())
            listeningCpuLock.release();
    }

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
        handler.removeCallbacks(audioWatchdog);
        handler.postDelayed(audioWatchdog, 4000);
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
        holdCpuWhileListening();
        listenStartedAt = System.currentTimeMillis();
        engine.start();
    }

    private void setStatus(String value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(STATUS, value).apply();
    }

    private void onWake() {
        if(stopping || paused) return;
        paused = true;
        engine.pause();
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
    public static void voiceStarted() { WakeService w=activeInstance; if(w!=null) w.handler.post(() -> { w.paused=true; w.releaseCpuLock(); w.setStatus("Home shortcut tapped; ChatGPT Voice should now open"); w.showForeground(); }); }
    public static void voiceEnded() { WakeService w=activeInstance; if(w!=null) w.handler.post(w::resumeListening); }

    private void stopCompletely(String reason) {
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        if (engine != null) engine.shutdown();
        releaseCpuLock();
        if (reason != null) setStatus(reason);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(RUNNING,false).putBoolean("voice_pending",false)
            .putBoolean("voice_active",false).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        if(activeInstance == this) activeInstance = null;
        if(engine != null) engine.shutdown();
        releaseCpuLock();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(RUNNING,false).apply();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
