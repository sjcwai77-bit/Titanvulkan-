package app.sjcw.torrent;

import android.app.*;
import android.content.*;
import android.os.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.swig.*;

public final class TorrentService extends Service {
 static volatile TorrentService live;
 static volatile String error = "";
 static volatile String activity = "";
 private volatile SessionManager manager;
 private File storage;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final LinkedHashMap<String, Entry> queue=new LinkedHashMap<>();
 interface ResultListener { void finished(boolean ok,String result); }
 static class Entry {
  final String hash, source, title;
  Entry(String h,String s,String t){hash=h;source=s;title=t;}
 }
 static class Row {
  String hash, name, phase, location;
  int peers,seeds,downloadRate,uploadRate;
  long done,total;
  boolean complete,paused,hasMetadata;
 }
 @Override public void onCreate(){
  super.onCreate();live=this;
  NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
  nm.createNotificationChannel(new NotificationChannel("transfer","Active downloads",NotificationManager.IMPORTANCE_LOW));
  Notification n=new Notification.Builder(this,"transfer")
   .setSmallIcon(android.R.drawable.stat_sys_download)
   .setContentTitle("SJCW Torrent 2")
   .setContentText("Torrent service running")
   .setOngoing(true).build();
  startForeground(7,n);
  storage=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
  if(storage==null)storage=new File(getFilesDir(),"downloads");
  storage.mkdirs();
  loadQueue();
  worker.execute(()->{
   try{
    SessionManager m=new SessionManager();m.start();manager=m;
    for(Entry e:entries())startTorrent(e);
    error="";
   }catch(Throwable e){error="Cannot start engine: "+e.getMessage();}
  });
 }
 synchronized void loadQueue(){
  queue.clear();
  String serialized=getSharedPreferences("torrent_queue",MODE_PRIVATE).getString("entries","[]");
  try{
   JSONArray a=new JSONArray(serialized);
   for(int i=0;i<a.length();i++){
    JSONObject j=a.optJSONObject(i);if(j==null)continue;
    String hash=j.optString("hash"),src=j.optString("src"),name=j.optString("name");
    if(hash.matches("[0-9a-fA-F]{40}")&&!src.isEmpty())queue.put(hash.toLowerCase(Locale.ROOT),new Entry(hash.toLowerCase(Locale.ROOT),src,name));
   }
  }catch(Exception e){error="Queue recovery failed: "+e.getMessage();}
 }
 synchronized void saveQueue(){
  JSONArray a=new JSONArray();
  for(Entry e:queue.values()){
   JSONObject j=new JSONObject();
   try{j.put("hash",e.hash);j.put("src",e.source);j.put("name",e.title);a.put(j);}catch(Exception ignored){}
  }
  getSharedPreferences("torrent_queue",MODE_PRIVATE).edit().putString("entries",a.toString()).apply();
 }
 synchronized List<Entry> entries(){return new ArrayList<>(queue.values());}
 synchronized String displayName(String hash,String fallback){
  Entry e=queue.get(hash.toLowerCase(Locale.ROOT));
  String name=fallback==null?"":fallback.trim();
  if(!name.isEmpty()&&!name.equalsIgnoreCase(hash))return name;
  return e!=null&&!e.title.isEmpty()?e.title:"Torrent "+hash.substring(0,Math.min(10,hash.length()));
 }
 void add(String src,String friendlyName,ResultListener reply){
  worker.execute(()->{
   try{
    if(manager==null)throw new IOException("Torrent engine is starting");
    String hash; String nameHint=friendlyName;
    if(src.startsWith("magnet:?")){
     Uri uri=Uri.parse(src);
     String xt=uri.getQueryParameter("xt");
     if(xt==null||!xt.toLowerCase(Locale.ROOT).startsWith("urn:btih:"))throw new IOException("Magnet link has no torrent hash");
     hash=xt.substring(9).toLowerCase(Locale.ROOT);
     if(!hash.matches("[a-f0-9]{40}"))throw new IOException("This version requires a 40-character magnet hash");
     if(nameHint==null||nameHint.trim().isEmpty())nameHint=uri.getQueryParameter("dn");
    }else{
     File file=new File(src);
     if(!file.isFile())throw new IOException("Torrent file missing");
     TorrentInfo ti=new TorrentInfo(file);
     hash=ti.infoHashV1().toHex().toLowerCase(Locale.ROOT);
     if(nameHint==null||nameHint.isEmpty())nameHint=ti.name();
    }
    if(queue.containsKey(hash)){reply.finished(false,"This torrent is already in your downloads.");return;}
    Entry e=new Entry(hash,src,nameHint==null?"":nameHint);
    synchronized(this){queue.put(hash,e);saveQueue();}
    startTorrent(e);
    reply.finished(true,"Added "+displayName(hash,""));
   }catch(Throwable t){reply.finished(false,"Could not add torrent: "+t.getMessage());}
  });
 }
 void startTorrent(Entry entry){
  try{
   File dir=new File(storage,entry.hash);
   if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create torrent folder");
   if(entry.source.startsWith("magnet:"))manager.download(entry.source,dir,new torrent_flags_t());
   else manager.download(new TorrentInfo(new File(entry.source)),dir);
  }catch(Throwable t){error="Could not restore "+entry.title+": "+t.getMessage();}
 }
 private TorrentHandle find(String hash){
  SessionManager m=manager;if(m==null)return null;
  for(TorrentHandle h:m.getTorrentHandles())
   if(h.infoHash().toHex().equalsIgnoreCase(hash))return h;
  return null;
 }
 List<Row> rows(){
  ArrayList<Row> result=new ArrayList<>();SessionManager m=manager;
  if(m==null)return result;
  try{
   HashSet<String> seen=new HashSet<>();
   for(TorrentHandle h:m.getTorrentHandles()){
    TorrentStatus st=h.status();String hash=h.infoHash().toHex().toLowerCase(Locale.ROOT);
    Row r=new Row();r.hash=hash;r.name=displayName(hash,st.hasMetadata()?st.name():"");
    r.hasMetadata=st.hasMetadata();r.complete=st.isFinished();r.done=st.totalWantedDone();r.total=st.totalWanted();
    r.downloadRate=st.downloadRate();r.uploadRate=st.uploadRate();
    r.peers=st.numPeers();r.seeds=st.numSeeds();
    r.phase=r.complete?"Completed":!st.hasMetadata()?"Finding torrent details…":r.downloadRate>0?"Downloading":"Waiting for peers";
    r.location=storage.getAbsolutePath();result.add(r);seen.add(hash);
   }
   for(Entry e:entries())if(!seen.contains(e.hash)){
    Row r=new Row();r.hash=e.hash;r.name=displayName(e.hash,"");r.phase="Connecting to peers…";result.add(r);
   }
  }catch(Throwable t){error="Couldn't refresh downloads: "+t.getMessage();}
  return result;
 }
 void pause(String hash,boolean resume,ResultListener reply){
  worker.execute(()->{
   try{TorrentHandle h=find(hash);if(h==null)throw new IOException("Torrent not ready");
    if(resume)h.resume();else h.pause();
    reply.finished(true,resume?"Resumed":"Paused");
   }catch(Throwable t){reply.finished(false,t.getMessage());}
  });
 }
 void remove(String hash,boolean deleteFiles,ResultListener reply){
  worker.execute(()->{
   try{
    TorrentHandle h=find(hash);if(h==null)throw new IOException("Torrent not ready yet");
    if(deleteFiles){
     remove_flags_t flags=session_handle.delete_files.or_(session_handle.delete_partfile);
     manager.remove(h,flags);
    }else manager.remove(h);
    synchronized(this){queue.remove(hash.toLowerCase(Locale.ROOT));saveQueue();}
    reply.finished(true,deleteFiles?"Torrent removed; files scheduled for deletion.":"Removed from list; files kept on phone.");
   }catch(Throwable t){reply.finished(false,"Removal failed: "+t.getMessage());}
  });
 }
 void export(String hash,ResultListener reply){
  worker.execute(()->{
   try{
    TorrentHandle h=find(hash);if(h==null)throw new IOException("Torrent not found");
    TorrentStatus st=h.status();
    if(!st.isFinished())throw new IOException("Wait for the download to finish.");
    String name=displayName(hash,st.name());
    activity="Moving "+name+" to Downloads…";
    h.pause();
    TorrentExports.export(this,h,name);
    manager.remove(h,session_handle.delete_files.or_(session_handle.delete_partfile));
    synchronized(this){queue.remove(hash.toLowerCase(Locale.ROOT));saveQueue();}
    reply.finished(true,"Moved to Internal storage → Download → SJCW Torrent. Original torrent data scheduled for deletion.");
   }catch(Throwable t){reply.finished(false,"Move failed: "+t.getMessage()+"\nThe original files have been kept.");}
   finally{activity="";}
  });
 }
 @Override public int onStartCommand(Intent i,int f,int id){return START_STICKY;}
 @Override public IBinder onBind(Intent intent){return null;}
 @Override public void onDestroy(){live=null;worker.execute(()->{try{if(manager!=null)manager.stop();}catch(Throwable ignored){}});worker.shutdown();super.onDestroy();}
}
