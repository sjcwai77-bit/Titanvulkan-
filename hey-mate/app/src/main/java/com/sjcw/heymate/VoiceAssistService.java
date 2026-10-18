package com.sjcw.heymate;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.app.KeyguardManager;
import android.os.PowerManager;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Opt-in, local-only automation for the user's home-screen Voice shortcut.
 *
 * Navigates to the Samsung main home page first. The page pictured by the user
 * contains the distinguishing Halifax, DeepSeek and Proton Mail shortcuts.
 * Never issues a coordinate tap on a page positively identified as a different
 * page. Swipes are bounded. No images or accessibility content are uploaded.
 */
public final class VoiceAssistService extends AccessibilityService {
    private static final String CHATGPT="com.openai.chatgpt";
    private static final String SAMSUNG_HOME="com.sec.android.app.launcher";
    private static final int MAX_RIGHT_SWIPES=6;
    private static final int MAX_LEFT_SWIPES=12;
    private static VoiceAssistService instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean navigating, gestureBusy, scanQueued, tapIssued;
    private boolean chatWasOpened, verifiedVoicePage;
    private int lockWaitAttempts=0;
    private boolean sentUnlockSwipe=false;
    private int noLauncherCount, rightSwipes, leftSwipes;
    private long lastTapAt;
    private final Runnable scanTask=()->{scanQueued=false;locateVoicePage();};

    private SharedPreferences prefs() {
        return getSharedPreferences(WakeService.PREFS,MODE_PRIVATE);
    }
    public static boolean isReady(){return instance!=null;}
    public static boolean startHomeShortcut(){
        VoiceAssistService a=instance;
        if(a==null)return false;
        a.handler.post(a::beginNavigation);
        return true;
    }
    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        instance=this;
        update("Accessibility helper ready");
    }
    @Override public void onDestroy(){
        if(instance==this)instance=null;
        navigating=false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    private void update(String msg){
        prefs().edit().putString("shortcut_status",msg).apply();
    }
    private boolean isLauncher(CharSequence pkg){
        if(pkg==null)return false;
        String p=pkg.toString();
        return SAMSUNG_HOME.equals(p)||p.equals("com.google.android.apps.nexuslauncher")||
            p.equals("com.android.launcher3")||p.equals("com.miui.home")||
            p.equals("com.huawei.android.launcher")||p.toLowerCase(Locale.ROOT).contains("launcher");
    }
    private void scheduleScan(long waitMs){
        if(!navigating || scanQueued || gestureBusy)return;
        scanQueued=true;
        handler.postDelayed(scanTask,waitMs);
    }
    private void beginNavigation(){
        if(navigating)return;
        if(!HomeWifi.trusted(this)){
            update("Home Wi-Fi not trusted; screen navigation disabled");
            WakeService.shortcutFailed();
            return;
        }
        navigating=true;
        gestureBusy=false;
        tapIssued=false;
        verifiedVoicePage=false;
        rightSwipes=0;
        leftSwipes=0;
        noLauncherCount=0;
        lockWaitAttempts=0;
        sentUnlockSwipe=false;
        chatWasOpened=false;
        lastTapAt=0;
        update("Waking display and preparing Voice shortcut…");
        wakeDisplayForVoice();
        handler.postDelayed(this::handleTrustedKeyguard,520);
    }

    private void wakeDisplayForVoice(){
        PowerManager pm=(PowerManager)getSystemService(Context.POWER_SERVICE);
        if(pm==null || pm.isInteractive())return;
        try{
            // Used only for a confirmed wake phrase, not to keep the screen on.
            // The OS may block legacy screen-wake locks on some Android builds.
            PowerManager.WakeLock wl=pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "HeyMate:WakeForVoice");
            wl.acquire(1900);
        }catch(Exception e){
            update("Wake request blocked by Android; trying unlock on next screen-on");
        }
    }

    private KeyguardManager keyguard(){
        return (KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
    }
    private void handleTrustedKeyguard(){
        if(!navigating || gestureBusy || tapIssued)return;
        if(!HomeWifi.trusted(this)){
            fail("Home Wi-Fi changed: Voice navigation cancelled");
            return;
        }
        PowerManager pm=(PowerManager)getSystemService(Context.POWER_SERVICE);
        if(pm!=null && !pm.isInteractive()){
            if(++lockWaitAttempts<8) {
                wakeDisplayForVoice();
                handler.postDelayed(this::handleTrustedKeyguard,450);
            } else fail("Android did not turn screen on; unlock manually");
            return;
        }
        KeyguardManager kg=keyguard();
        if(kg==null){fail("Could not verify lock-screen state");return;}
        if(!kg.isKeyguardLocked()){
            goHomeThenTap();
            return;
        }
        // An open padlock / Extend Unlock may leave the keyguard showing;
        // isDeviceLocked() distinguishes it from a real authentication lock.
        if(kg.isDeviceLocked()){
            // Give Extend Unlock a little time to release after screen-on.
            if(++lockWaitAttempts<=5){
                update("Waiting for Samsung Extend Unlock trust…");
                handler.postDelayed(this::handleTrustedKeyguard,420);
            }else fail("PIN or biometric required; leaving phone secure");
            return;
        }
        if(sentUnlockSwipe){
            if(++lockWaitAttempts<10)
                handler.postDelayed(this::handleTrustedKeyguard,310);
            else fail("Trusted lock screen did not dismiss; manual swipe needed");
            return;
        }
        sentUnlockSwipe=true;
        unlockSwipe();
    }

    private void unlockSwipe(){
        if(!navigating || gestureBusy)return;
        KeyguardManager kg=keyguard();
        if(kg==null || kg.isDeviceLocked() || !kg.isKeyguardLocked()){
            handleTrustedKeyguard();return;
        }
        gestureBusy=true;
        update("Trusted screen detected — swiping up once");
        Point size=new Point();
        ((WindowManager)getSystemService(Context.WINDOW_SERVICE))
            .getDefaultDisplay().getRealSize(size);
        Path swipe=new Path();
        swipe.moveTo(size.x*0.5f,size.y*0.84f);
        swipe.lineTo(size.x*0.5f,size.y*0.28f);
        GestureDescription g=new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(swipe,0,390)).build();
        if(!dispatchGesture(g,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription result){
                gestureBusy=false;
                handler.postDelayed(()->{
                    KeyguardManager state=keyguard();
                    if(state!=null && state.isDeviceLocked()){
                        fail("Android now requires a PIN; cannot continue");
                    }else handleTrustedKeyguard();
                },640);
            }
            @Override public void onCancelled(GestureDescription result){
                gestureBusy=false;
                fail("Android cancelled trusted-screen swipe");
            }
        },handler)){
            gestureBusy=false;
            fail("Android did not permit the trusted-screen swipe");
        }
    }
    private void goHomeThenTap(){
        if(!navigating || tapIssued)return;
        KeyguardManager kg=keyguard();
        if(kg!=null && kg.isKeyguardLocked()){
            fail("Lock screen still visible; refusing to tap behind it");
            return;
        }
        update("Lock screen dismissed — returning to Voice home page");
        if(!performGlobalAction(GLOBAL_ACTION_HOME)){
            fail("Android refused Home action");
            return;
        }
        handler.postDelayed(()->{
            if(!navigating || tapIssued)return;
            performGlobalAction(GLOBAL_ACTION_HOME);
            scheduleScan(550);
        },470);
    }
    private static final class HomeMarkers {
        boolean halifax, deepseek, proton, gallery, whatsapp;
        int visibleLabels=0;
        int score(){return (halifax?1:0)+(deepseek?1:0)+(proton?1:0);}
        boolean found(){return score()>=2;}
        boolean any(){return score()>0;}
    }
    private HomeMarkers detectPage(AccessibilityNodeInfo root){
        HomeMarkers m=new HomeMarkers();
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();
        q.add(root);
        int scanned=0;
        while(!q.isEmpty() && scanned++<450){
            AccessibilityNodeInfo n=q.removeFirst();
            if(!n.isVisibleToUser())continue; // exclude adjacent cached launcher pages
            CharSequence t=n.getText(), d=n.getContentDescription();
            String label=((t==null?"":t.toString())+" "+(d==null?"":d.toString())).toLowerCase(Locale.ROOT);
            if(!label.trim().isEmpty())m.visibleLabels++;
            if(label.contains("halifax"))m.halifax=true;
            if(label.contains("deepseek"))m.deepseek=true;
            if(label.contains("proton"))m.proton=true;
            if(label.contains("gallery"))m.gallery=true;
            if(label.contains("whatsapp"))m.whatsapp=true;
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo ch=n.getChild(i);
                if(ch!=null)q.add(ch);
            }
        }
        return m;
    }
    private void locateVoicePage(){
        if(!navigating || gestureBusy || tapIssued)return;
        if(!HomeWifi.trusted(this)){
            fail("Home Wi-Fi disconnected before Voice shortcut tap");
            return;
        }
        KeyguardManager lock=keyguard();
        if(lock!=null && lock.isKeyguardLocked()){
            fail("Lock screen returned; cannot tap Voice shortcut");
            return;
        }
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null || !isLauncher(root.getPackageName())){
            if(++noLauncherCount<=13){
                scheduleScan(240);
            }else{
                fail("Could not access Samsung home screen");
            }
            return;
        }
        noLauncherCount=0;
        HomeMarkers m=detectPage(root);
        if(m.found()){
            verifiedVoicePage=true;
            update("Correct page found (Halifax / DeepSeek / Proton)");
            tapOnce();
            return;
        }
        // Samsung default home fallback ONLY after the two HOME actions and
        // only if no identifying app labels whatsoever can be read.
        if(rightSwipes==0 && leftSwipes==0 && SAMSUNG_HOME.contentEquals(root.getPackageName()) &&
            m.visibleLabels==0 && prefs().getBoolean("samsung_home_fallback",true)) {
            update("Home labels unavailable: using Samsung double-Home fallback");
            tapOnce();
            return;
        }
        if(rightSwipes<MAX_RIGHT_SWIPES){
            rightSwipes++;
            update("Finding Voice page · moving towards earlier home page "+rightSwipes);
            swipePage(true);
        }else if(leftSwipes<MAX_LEFT_SWIPES){
            leftSwipes++;
            update("Finding Voice page · searching later pages "+leftSwipes);
            swipePage(false);
        }else{
            fail("Voice home page not found — no tap sent");
        }
    }
    private void swipePage(boolean right){
        if(gestureBusy || !navigating || tapIssued)return;
        gestureBusy=true;
        Point size=new Point();
        ((WindowManager)getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
        // Keep the gesture away from both the bottom navigation bar and app rows.
        float y=size.y*0.76f;
        float from=right?size.x*0.18f:size.x*0.82f;
        float to=right?size.x*0.82f:size.x*0.18f;
        Path path=new Path();
        path.moveTo(from,y);
        path.lineTo(to,y);
        GestureDescription gesture=new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path,0,310)).build();
        if(!dispatchGesture(gesture,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){
                gestureBusy=false;
                scheduleScan(520);
            }
            @Override public void onCancelled(GestureDescription g){
                gestureBusy=false;
                fail("Android cancelled the home-page swipe");
            }
        },handler)){
            gestureBusy=false;
            fail("Could not swipe to the Voice page");
        }
    }
    private void tapOnce(){
        if(!navigating || tapIssued || gestureBusy)return;
        KeyguardManager kg=keyguard();
        if(!HomeWifi.trusted(this) || (kg!=null && kg.isKeyguardLocked())){
            fail("Home Wi-Fi changed or screen relocked — tap cancelled");return;
        }
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null || !isLauncher(root.getPackageName())) {
            scheduleScan(260);
            return;
        }
        // Positive identification must still be true at the instant of tapping.
        if(verifiedVoicePage && !detectPage(root).found()) {
            update("Home page shifted; locating again");
            scheduleScan(330);
            return;
        }
        tapIssued=true;
        navigating=false;
        handler.removeCallbacks(scanTask);
        Point size=new Point();
        ((WindowManager)getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
        float x=size.x * prefs().getInt("tap_x",50)/100f;
        float y=size.y * prefs().getInt("tap_y",57)/100f;
        Path point=new Path();
        point.moveTo(x,y);
        GestureDescription tap=new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(point,0,110)).build();
        if(!dispatchGesture(tap,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){
                lastTapAt=System.currentTimeMillis();
                update("Voice shortcut tapped once on " +
                    (verifiedVoicePage?"verified home page":"Samsung main home page"));
                WakeService.voiceStarted();
            }
            @Override public void onCancelled(GestureDescription g){
                tapIssued=false;
                fail("Android cancelled the Voice tap");
            }
        },handler)){
            tapIssued=false;
            fail("Android refused Voice tap gesture");
        }
    }
    private void fail(String reason){
        navigating=false;
        gestureBusy=false;
        scanQueued=false;
        handler.removeCallbacks(scanTask);
        update(reason);
        WakeService.shortcutFailed();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null || event.getPackageName()==null)return;
        if(navigating && !gestureBusy && isLauncher(event.getPackageName())){
            scheduleScan(180);
        }
        if(!prefs().getBoolean("voice_active",false))return;
        if(CHATGPT.contentEquals(event.getPackageName())){
            chatWasOpened=true;
            if(lastTapAt>0 && System.currentTimeMillis()-lastTapAt>2200){
                AccessibilityNodeInfo root=getRootInActiveWindow();
                if(root!=null && hasTextComposer(root)&&!hasVoiceControls(root)){
                    update("ChatGPT text composer is visible; resuming listening");
                    resumeAfterVoice();
                }
            }
        } else if(isLauncher(event.getPackageName()) && chatWasOpened && lastTapAt>0
                && System.currentTimeMillis()-lastTapAt>2200){
            update("Back at home; resuming listening");
            resumeAfterVoice();
        }
    }
    private boolean hasVoiceControls(AccessibilityNodeInfo root){
        return includes(root,new String[]{"end voice","exit voice","mute microphone",
            "leave voice","end conversation"});
    }
    private boolean hasTextComposer(AccessibilityNodeInfo root){
        return includes(root,new String[]{"ask anything","message chatgpt","send message"});
    }
    private boolean includes(AccessibilityNodeInfo root,String[] terms){
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();
        q.add(root);
        int visited=0;
        while(!q.isEmpty() && visited++<230){
            AccessibilityNodeInfo n=q.removeFirst();
            CharSequence d=n.getContentDescription(), t=n.getText(), hint=n.getHintText();
            String v=((d==null?"":d)+" "+(t==null?"":t)+" "+
                (hint==null?"":hint)).toLowerCase(Locale.ROOT);
            for(String term:terms)if(v.contains(term))return true;
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo ch=n.getChild(i);
                if(ch!=null)q.add(ch);
            }
        }
        return false;
    }
    private void resumeAfterVoice(){
        chatWasOpened=false;
        tapIssued=false;
        prefs().edit().putBoolean("voice_active",false).apply();
        WakeService.voiceEnded();
    }
    @Override public void onInterrupt(){}
}
