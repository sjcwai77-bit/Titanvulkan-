package com.sjcw.heymate;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class OfflineWakeEngine {
    interface Callbacks {
        void status(String text);
        void heard(String text);
        void trigger();
    }
    static final String MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    static final String DIR="vosk-en-small";
    private static final Pattern WAKE=Pattern.compile(
        "(?:^|\\s)(?:hey|hi|hay|hello)\\s+(?:mate|mates|may|made|matt|met|mayte)(?:\\s|$)");
    private final Context app;
    private final Callbacks cb;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean wantsAudio=new AtomicBoolean(false);
    private final AtomicBoolean busy=new AtomicBoolean(false);
    private volatile boolean disposed=false;
    private volatile AudioRecord audio;
    private Model model;
    private volatile long lastDiagnosticAt=0;
    private volatile long lastRecognitionAt=0;
    private volatile String lastPartial="";

    OfflineWakeEngine(Context context,Callbacks callbacks) {
        app=context.getApplicationContext();
        cb=callbacks;
    }
    static boolean modelReady(Context ctx) {
        File d=new File(ctx.getFilesDir(),DIR);
        return new File(d,"am").isDirectory() &&
            new File(d,"conf").isDirectory() &&
            new File(d,"graph").isDirectory();
    }
    private void setStatus(String message) {
        ui.post(()->cb.status(message));
    }
    private void update(String key,String value) {
        app.getSharedPreferences(WakeService.PREFS,Context.MODE_PRIVATE)
            .edit().putString(key,value).apply();
    }
    private void updateLong(String key,long v) {
        app.getSharedPreferences(WakeService.PREFS,Context.MODE_PRIVATE)
            .edit().putLong(key,v).apply();
    }
    void start() {
        if(disposed)return;
        wantsAudio.set(true);
        if(!busy.compareAndSet(false,true))return;
        executor.execute(()->{
            try {
                if(!modelReady(app)) {
                    if(!app.getSharedPreferences(WakeService.PREFS,Context.MODE_PRIVATE)
                        .getBoolean("model_download_approved",false)) {
                        setStatus("Offline model missing. Switch on model download in Hey Mate, then press Resume.");
                        update("engine_stage","Model download not authorised");
                        return;
                    }
                    update("engine_stage","Downloading offline model");
                    setStatus("Downloading offline model (~40 MB); stay connected to the internet");
                    installModel();
                }
                if(!wantsAudio.get()||disposed)return;
                update("engine_stage","Loading Vosk speech model");
                setStatus("Loading English model into memory...");
                if(model==null)model=new Model(new File(app.getFilesDir(),DIR).getAbsolutePath());
                if(!wantsAudio.get()||disposed)return;
                recordLoop();
            } catch(Exception error) {
                String message=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
                update("engine_stage","ERROR");
                update("engine_error",message);
                setStatus("Offline listener error: "+message);
            } finally {
                busy.set(false);
                if(wantsAudio.get()&&!disposed &&
                    "Listening".equals(app.getSharedPreferences(WakeService.PREFS,Context.MODE_PRIVATE)
                        .getString("engine_stage",""))) {
                    update("engine_stage","Microphone stopped");
                }
            }
        });
    }
    void pause() {
        wantsAudio.set(false);
        AudioRecord r=audio;
        if(r!=null) {
            try {r.stop();}catch(Exception ignored){}
        }
    }
    void shutdown() {
        disposed=true;
        wantsAudio.set(false);
        pause();
        executor.execute(()->{
            if(model!=null) {
                try {model.close();}catch(Exception ignored){}
                model=null;
            }
        });
        executor.shutdown();
    }
    private void recordLoop() throws Exception {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        final int rate=16000;
        final int channel=AudioFormat.CHANNEL_IN_MONO;
        final int encoding=AudioFormat.ENCODING_PCM_16BIT;
        int min=AudioRecord.getMinBufferSize(rate,channel,encoding);
        if(min<=0)throw new IOException("16 kHz microphone not supported ("+min+")");
        AudioRecord input=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,channel,encoding,Math.max(min*2,8192));
        if(input.getState()!=AudioRecord.STATE_INITIALIZED) {
            input.release();
            throw new IOException("Microphone could not initialise; close other recording apps");
        }
        audio=input;
        byte[] buffer=new byte[4096];
        lastDiagnosticAt=0;
        lastRecognitionAt=0;
        lastPartial="";
        int positiveReads=0;
        try(Recognizer rec=new Recognizer(model,(float)rate)) {
            input.startRecording();
            if(input.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                throw new IOException("Android did not grant microphone capture");
            update("engine_stage","Listening");
            update("engine_error","");
            setStatus("Microphone live; say 'Hey mate' (no ten-second restarts)");
            while(wantsAudio.get()&&!disposed) {
                int bytes=input.read(buffer,0,buffer.length,AudioRecord.READ_BLOCKING);
                if(!wantsAudio.get()||disposed)break;
                if(bytes<=0) {
                    if(bytes==AudioRecord.ERROR_DEAD_OBJECT)
                        throw new IOException("Microphone disconnected");
                    if(bytes<0)throw new IOException("Audio read error "+bytes);
                    continue;
                }
                positiveReads++;
                long now=System.currentTimeMillis();
                if(now-lastDiagnosticAt>1000) {
                    lastDiagnosticAt=now;
                    long energy=0;
                    int count=bytes/2;
                    for(int i=0;i<count;i++) {
                        int sample=(short)(((buffer[i*2+1]&255)<<8)|(buffer[i*2]&255));
                        energy+=(long)sample*sample;
                    }
                    double rms=Math.sqrt(energy/(double)Math.max(1,count));
                    int level=(int)Math.min(100,Math.round(100*Math.log10(1+rms)/4.2));
                    update("mic_level",Integer.toString(level));
                    updateLong("last_audio_at",now);
                    update("engine_stage","Listening");
                }
                boolean complete=rec.acceptWaveForm(buffer,bytes);
                String hypothesis=new JSONObject(complete?rec.getResult():rec.getPartialResult())
                    .optString(complete?"text":"partial","").toLowerCase(Locale.UK).trim();
                if(!hypothesis.isEmpty()) {
                    if(!hypothesis.equals(lastPartial)) {
                        lastPartial=hypothesis;
                        update("latest_recognition",hypothesis);
                        updateLong("last_recognition_at",now);
                    }
                    if(WAKE.matcher(hypothesis).find()) {
                        update("engine_stage","Wake phrase recognised");
                        update("last_wake_hypothesis",hypothesis);
                        ui.post(()->{ cb.heard(hypothesis);cb.trigger(); });
                        return;
                    }
                }
            }
            if(positiveReads==0 && wantsAudio.get())throw new IOException("No audio received from microphone");
        } finally {
            audio=null;
            try {input.stop();}catch(Exception ignored){}
            input.release();
            update("mic_level","0");
        }
    }

    private void installModel() throws IOException {
        File finalDir=new File(app.getFilesDir(),DIR);
        File staging=new File(app.getFilesDir(),"vosk-installing");
        remove(staging);
        if(!staging.mkdirs())throw new IOException("Could not allocate model storage");
        HttpURLConnection conn=(HttpURLConnection)new URL(MODEL_URL).openConnection();
        conn.setConnectTimeout(25000);
        conn.setReadTimeout(45000);
        conn.setInstanceFollowRedirects(true);
        long bytesWritten=0;
        try {
            int response=conn.getResponseCode();
            if(response!=200)throw new IOException("Model download HTTP "+response);
            try(ZipInputStream zip=new ZipInputStream(
                new BufferedInputStream(conn.getInputStream()))) {
                ZipEntry entry;
                byte[] buf=new byte[32768];
                long lastUpdate=0;
                while((entry=zip.getNextEntry())!=null) {
                    if(!wantsAudio.get()||disposed)throw new IOException("Download cancelled");
                    String p=entry.getName().replace('\\','/');
                    int slash=p.indexOf('/');
                    if(slash<0)continue;
                    p=p.substring(slash+1);
                    if(p.length()==0)continue;
                    File out=new File(staging,p);
                    String safeRoot=staging.getCanonicalPath()+File.separator;
                    if(!out.getCanonicalPath().startsWith(safeRoot))
                        throw new IOException("Invalid model archive");
                    if(entry.isDirectory()) {
                        if(!out.exists()&&!out.mkdirs())
                            throw new IOException("Cannot create model directory");
                    } else {
                        if(!out.getParentFile().exists()&&!out.getParentFile().mkdirs())
                            throw new IOException("Not enough free storage");
                        try(FileOutputStream f=new FileOutputStream(out)) {
                            int n;
                            while((n=zip.read(buf))>0) {
                                if(!wantsAudio.get()||disposed)throw new IOException("Download cancelled");
                                f.write(buf,0,n);
                                bytesWritten+=n;
                                if(bytesWritten>180L*1024*1024)
                                    throw new IOException("Unexpected large model archive");
                            }
                        }
                    }
                    long now=System.currentTimeMillis();
                    if(now-lastUpdate>1000) {
                        lastUpdate=now;
                        final long mb=bytesWritten/1048576;
                        setStatus("Unpacking offline model: "+mb+" MB processed");
                        update("engine_stage","Installing model, "+mb+" MB");
                    }
                    zip.closeEntry();
                }
            }
            if(!new File(staging,"am").isDirectory()||
                !new File(staging,"conf").isDirectory()||
                !new File(staging,"graph").isDirectory()) {
                throw new IOException("Model incomplete: network download may be blocked");
            }
            remove(finalDir);
            if(!staging.renameTo(finalDir))throw new IOException("Cannot finalise model installation");
            update("engine_stage","Model installed");
        } catch(IOException e) {
            remove(staging);
            throw e;
        } finally {
            conn.disconnect();
        }
    }
    private static void remove(File f) {
        if(f==null||!f.exists())return;
        File[] files=f.listFiles();
        if(files!=null)for(File child:files)remove(child);
        f.delete();
    }
}
