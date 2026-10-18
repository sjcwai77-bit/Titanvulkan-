package com.sjcw.heymate;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Locale;

public final class VoiceAssistService extends AccessibilityService {
    private static final String CHATGPT="com.openai.chatgpt";
    private final Handler h=new Handler(Looper.getMainLooper());
    private long lastAttempt=0;
    private boolean performed=false;
    private SharedPreferences prefs(){return getSharedPreferences(WakeService.PREFS,MODE_PRIVATE);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event.getPackageName()==null||!CHATGPT.contentEquals(event.getPackageName())) return;
        if(!prefs().getBoolean("voice_pending",false)){performed=false;return;}
        long now=System.currentTimeMillis();
        if(now-lastAttempt<1800||performed)return;
        lastAttempt=now;
        h.postDelayed(this::activateVoice,550);
    }
    private void activateVoice(){
        if(!prefs().getBoolean("voice_pending",false))return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null||root.getPackageName()==null||!CHATGPT.contentEquals(root.getPackageName()))return;
        ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();
        queue.add(root);int visited=0;
        while(!queue.isEmpty()&&visited++<220){
            AccessibilityNodeInfo node=queue.removeFirst();
            CharSequence desc=node.getContentDescription();
            String label=desc==null?"":desc.toString().toLowerCase(Locale.ROOT);
            if((label.contains("voice")&&!label.contains("dictat")&&!label.contains("setting"))
                && (label.contains("start")||label.contains("chat")||label.contains("mode")||label.equals("voice"))){
                for(AccessibilityNodeInfo v=node;v!=null;v=v.getParent()){
                    if(v.isClickable()&&v.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
                        markDone();return;
                    }
                }
            }
            for(int i=0;i<node.getChildCount();i++){
                AccessibilityNodeInfo child=node.getChild(i);
                if(child!=null)queue.add(child);
            }
        }
        if(!prefs().getBoolean("tap_fallback",true)) return;
        android.util.DisplayMetrics m=getResources().getDisplayMetrics();
        float x=m.widthPixels*prefs().getInt("tap_x",92)/100f;
        float y=m.heightPixels*prefs().getInt("tap_y",92)/100f;
        Path path=new Path();path.moveTo(x,y);
        GestureDescription gesture=new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path,0,100)).build();
        dispatchGesture(gesture,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){markDone();}
        },h);
    }
    private void markDone(){
        performed=true;
        prefs().edit().putBoolean("voice_pending",false).putBoolean("voice_active",true).apply();
        WakeService.voiceStarted();
    }
    @Override public void onInterrupt(){}
}