package com.sjcw.heymate;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public final class VoiceAssistService extends AccessibilityService {
    private static final String CHATGPT = "com.openai.chatgpt";
    private static VoiceAssistService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean waitingForHome = false;
    private boolean gestureIssued = false;
    private int homeAttempts = 0;
    private long tapTimestamp = 0;
    private boolean chatWasOpened = false;

    private SharedPreferences prefs() {
        return getSharedPreferences(WakeService.PREFS, MODE_PRIVATE);
    }
    public static boolean isReady() { return instance != null; }

    /** User-authorised Accessibility service: sends HOME, then one tap at the saved shortcut position. */
    public static boolean startHomeShortcut() {
        VoiceAssistService service = instance;
        if (service == null) return false;
        service.handler.post(service::goHomeThenTap);
        return true;
    }
    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs().edit().putString("shortcut_status", "Accessibility helper enabled").apply();
    }
    @Override public void onDestroy() {
        if(instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    private void state(String text) {
        prefs().edit().putString("shortcut_status", text).apply();
    }
    private void goHomeThenTap() {
        waitingForHome = true;
        gestureIssued = false;
        homeAttempts = 0;
        chatWasOpened = false;
        tapTimestamp = 0;
        state("Going home to tap the Voice shortcut");
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
            state("Android denied Home action; tap the shortcut manually");
            waitingForHome = false;
            WakeService.shortcutFailed();
            return;
        }
        handler.postDelayed(this::tryOneHomeTap, 650);
    }
    private boolean isLauncher(CharSequence name) {
        if(name == null) return false;
        String p = name.toString();
        return p.equals("com.sec.android.app.launcher") ||
            p.equals("com.google.android.apps.nexuslauncher") ||
            p.equals("com.android.launcher3") ||
            p.equals("com.miui.home") ||
            p.equals("com.huawei.android.launcher") ||
            p.toLowerCase(java.util.Locale.ROOT).contains("launcher");
    }
    private void tryOneHomeTap() {
        if (!waitingForHome || gestureIssued) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if(root == null || !isLauncher(root.getPackageName())) {
            if (++homeAttempts < 10) {
                handler.postDelayed(this::tryOneHomeTap, 270);
            } else {
                state("Home screen not detected; no blind tap made");
                waitingForHome = false;
                WakeService.shortcutFailed();
            }
            return;
        }
        Point size = new Point();
        ((WindowManager)getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
        float x = size.x * prefs().getInt("tap_x", 50) / 100f;
        float y = size.y * prefs().getInt("tap_y", 57) / 100f;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(
            new GestureDescription.StrokeDescription(path, 0, 105)).build();
        gestureIssued = true;
        waitingForHome = false;
        state("Sending one tap to home-screen Voice shortcut");
        if(!dispatchGesture(gesture, new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g) {
                tapTimestamp = System.currentTimeMillis();
                state("One home-screen tap delivered (" + (int)x + ", " + (int)y + ")");
                WakeService.voiceStarted();
            }
            @Override public void onCancelled(GestureDescription g) {
                state("Android cancelled the home-screen tap");
                WakeService.shortcutFailed();
            }
        },handler)){
            state("Accessibility gesture could not be dispatched");
            WakeService.shortcutFailed();
        }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event == null || event.getPackageName() == null) return;
        if(waitingForHome && isLauncher(event.getPackageName())) {
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(this::tryOneHomeTap, 260);
            return;
        }
        if (!prefs().getBoolean("voice_active", false)) return;
        if (CHATGPT.contentEquals(event.getPackageName())) {
            chatWasOpened = true;
            // Do not steal the microphone from an active ChatGPT voice call.
            if(tapTimestamp > 0 && System.currentTimeMillis()-tapTimestamp > 1800) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && hasTextComposer(root) && !hasVoiceControls(root)) {
                    state("ChatGPT text chat returned; resuming Hey Mate");
                    resetAndResume();
                }
            }
        } else if(isLauncher(event.getPackageName()) && chatWasOpened && tapTimestamp > 0
                && System.currentTimeMillis()-tapTimestamp > 1800) {
            state("Returned to home screen; resuming Hey Mate");
            resetAndResume();
        }
    }
    private boolean hasVoiceControls(AccessibilityNodeInfo root) {
        return containsLabel(root, new String[]{"end voice","exit voice","mute microphone","leave voice","end conversation"});
    }
    private boolean hasTextComposer(AccessibilityNodeInfo root) {
        return containsLabel(root, new String[]{"ask anything","message chatgpt","send message"});
    }
    private boolean containsLabel(AccessibilityNodeInfo root, String[] words) {
        java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        int scanned = 0;
        while (!q.isEmpty() && scanned++ < 200) {
            AccessibilityNodeInfo n = q.removeFirst();
            CharSequence d = n.getContentDescription(), t = n.getText(), hint = n.getHintText();
            String s = ((d==null?"":d) + " " + (t==null?"":t) + " " + (hint==null?"":hint))
                .toLowerCase(java.util.Locale.ROOT);
            for (String term : words) if(s.contains(term)) return true;
            for (int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo ch = n.getChild(i);
                if(ch != null) q.add(ch);
            }
        }
        return false;
    }
    private void resetAndResume() {
        chatWasOpened = false;
        gestureIssued = false;
        prefs().edit().putBoolean("voice_active", false).apply();
        WakeService.voiceEnded();
    }
    @Override public void onInterrupt() {}
}
