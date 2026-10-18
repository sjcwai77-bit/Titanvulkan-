package com.sjcw.heymate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One microphone session without Android SpeechRecognizer's silence timeout.
 * Audio remains on-device after the optional English model is downloaded.
 */
final class OfflineWakeEngine implements RecognitionListener {
    interface Callbacks {
        void status(String s);
        void heard(String text);
        void trigger();
    }
    static final String MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    static final String DIR = "vosk-en-small";
    private static final Pattern WAKE = Pattern.compile("(^|\\s)(hey|hi|hay)\\s+(mate|mates)(\\s|$)");
    private final Context app;
    private final Callbacks callbacks;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Model model;
    private SpeechService speech;
    private Recognizer recognizer;
    private boolean wanted = false;
    private boolean initializing = false;
    private boolean stopped = false;
    private boolean triggered = false;
    private long lastProgressAt = 0L;

    OfflineWakeEngine(Context ctx, Callbacks cb) {
        app = ctx.getApplicationContext();
        callbacks = cb;
    }
    static boolean modelReady(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        return new File(dir, "am").isDirectory()
            && new File(dir, "conf").isDirectory()
            && new File(dir, "graph").isDirectory();
    }
    void start() {
        if(stopped) return;
        wanted = true;
        if(speech != null || initializing) return;
        initializing = true;
        callbacks.status(modelReady(app) ? "Preparing silent offline microphone…" : "Preparing offline English voice model…");
        worker.execute(() -> {
            try {
                if(!modelReady(app)) {
                    boolean allowed = app.getSharedPreferences(WakeService.PREFS, Context.MODE_PRIVATE)
                        .getBoolean("model_download_approved",false);
                    if(!allowed) throw new IOException("Model download needs your approval in Hey Mate");
                    downloadAndInstall();
                }
                if(!wanted || stopped) { initializing=false; return; }
                if(model == null) model = new Model(new File(app.getFilesDir(), DIR).getAbsolutePath());
                main.post(() -> {
                    initializing = false;
                    if(wanted && !stopped) startMicrophone();
                });
            } catch(Exception ex) {
                main.post(() -> {
                    initializing = false;
                    callbacks.status("Offline setup failed: "+ex.getMessage());
                });
            }
        });
    }
    private void startMicrophone() {
        if(!wanted || stopped || speech != null || model == null) return;
        try {
            triggered = false;
            recognizer = new Recognizer(model, 16000f,
                "[\"hey mate\", \"hi mate\", \"hey mates\", \"[unk]\"]");
            speech = new SpeechService(recognizer,16000f);
            if(!speech.startListening(this)) throw new IOException("Microphone unavailable");
            callbacks.status("Listening continuously · offline · no timeout chimes");
        } catch (Exception e) {
            releaseMic();
            callbacks.status("Microphone start failed: "+e.getMessage());
        }
    }
    void pause() {
        wanted = false;
        releaseMic();
    }
    void shutdown() {
        wanted = false;
        stopped = true;
        releaseMic();
        worker.execute(() -> {
            if(model != null) {
                model.close();
                model = null;
            }
        });
        worker.shutdown();
    }
    private void releaseMic() {
        SpeechService s = speech;
        speech = null;
        if(s != null) {
            try { s.cancel(); } catch(Exception ignored){}
            try { s.shutdown(); } catch(Exception ignored){}
        }
        if(recognizer != null) {
            try { recognizer.close(); } catch(Exception ignored){}
            recognizer = null;
        }
    }
    private void parse(String json, String key) {
        if(stopped || !wanted || triggered) return;
        try {
            String recognized = new JSONObject(json).optString(key,"").trim().toLowerCase(Locale.UK);
            if(recognized.isEmpty())return;
            if(WAKE.matcher(recognized).find()) {
                triggered = true;
                callbacks.heard(recognized);
                callbacks.trigger();
            }
        } catch(Exception ignored) {}
    }
    @Override public void onPartialResult(String hypothesis){parse(hypothesis,"partial");}
    @Override public void onResult(String hypothesis){parse(hypothesis,"text");}
    @Override public void onFinalResult(String hypothesis){parse(hypothesis,"text");}
    @Override public void onTimeout(){}
    @Override public void onError(Exception error){
        if(!wanted || stopped)return;
        callbacks.status("Audio input error: "+error.getMessage()+" · tap Resume");
    }

    private void downloadAndInstall() throws IOException {
        File target = new File(app.getFilesDir(),DIR);
        File stage = new File(app.getFilesDir(),"vosk-model-installing");
        deleteTree(stage);
        if(!stage.mkdirs()) throw new IOException("Not enough space for model folder");
        HttpURLConnection conn = (HttpURLConnection)new URL(MODEL_URL).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(40000);
        conn.setInstanceFollowRedirects(true);
        int written = 0;
        long total = conn.getContentLengthLong();
        try {
            if(conn.getResponseCode()!=200)throw new IOException("Model download HTTP "+conn.getResponseCode());
            try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(conn.getInputStream()))) {
                ZipEntry entry;
                byte[] buffer=new byte[32768];
                long uncompressed=0;
                while((entry=zip.getNextEntry()) != null) {
                    if(stopped)throw new IOException("Setup interrupted");
                    String path=entry.getName().replace('\\','/');
                    int slash=path.indexOf('/');
                    if(slash<0) continue;
                    path=path.substring(slash+1);
                    if(path.length()==0)continue;
                    File out = new File(stage,path);
                    String base = stage.getCanonicalPath()+File.separator;
                    if(!out.getCanonicalPath().startsWith(base)) throw new IOException("Unsafe model archive");
                    if(entry.isDirectory()) {
                        if(!out.exists()&&!out.mkdirs())throw new IOException("Cannot create model directory");
                    } else {
                        File parent=out.getParentFile();
                        if(!parent.exists()&&!parent.mkdirs())throw new IOException("Cannot write model files");
                        try(FileOutputStream output=new FileOutputStream(out)) {
                            int n;
                            while((n=zip.read(buffer))>0) {
                                output.write(buffer,0,n);
                                uncompressed += n;
                                if(uncompressed > 160L*1024*1024)
                                    throw new IOException("Model exceeded extraction size limit");
                            }
                        }
                    }
                    long now=System.currentTimeMillis();
                    if(now-lastProgressAt>3000) {
                        lastProgressAt=now;
                        long mb = uncompressed/1048576;
                        main.post(()->callbacks.status("Downloading/unpacking offline model · "+mb+" MB unpacked"));
                    }
                    zip.closeEntry();
                }
            }
            if(!new File(stage,"am").isDirectory()
                ||!new File(stage,"conf").isDirectory()
                ||!new File(stage,"graph").isDirectory())
                throw new IOException("Downloaded archive is incomplete");
            deleteTree(target);
            if(!stage.renameTo(target))throw new IOException("Could not finish model installation");
        } catch(IOException e) {
            deleteTree(stage);
            throw e;
        } finally { conn.disconnect(); }
    }
    private static void deleteTree(File file) {
        if(file == null || !file.exists())return;
        File[] sub=file.listFiles();
        if(sub!=null)for(File child:sub)deleteTree(child);
        file.delete();
    }
}
