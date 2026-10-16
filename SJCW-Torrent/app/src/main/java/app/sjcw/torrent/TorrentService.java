package app.sjcw.torrent;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.swig.torrent_flags_t;
public final class TorrentService extends Service {
 static volatile TorrentService live;
 private SessionManager manager;
 private File data;
 static String error="";
 @Override public void onCreate(){
  super.onCreate();live=this;
  NotificationManager nm=getSystemService(NotificationManager.class);
  nm.createNotificationChannel(new NotificationChannel("transfer","Torrent downloads",NotificationManager.IMPORTANCE_LOW));
  Notification n=new Notification.Builder(this,"transfer").setContentTitle("SJCW Torrent").setContentText("Downloads running").setSmallIcon(android.R.drawable.stat_sys_download).build();
  startForeground(7,n);
  data=getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
  new Thread(()->{try{manager=new SessionManager();manager.start();}catch(Throwable t){error=t.toString();}},"torrent-engine").start();
 }
 void add(String src){
  new Thread(()->{
   try{
    if(manager==null){error="Torrent engine still starting";return;}
    if(src.startsWith("magnet:"))manager.download(src,data,new torrent_flags_t());
    else manager.download(new TorrentInfo(new File(src)),data);
    error="";
   }catch(Throwable t){error=t.toString();}
  },"torrent-add").start();
 }
 void control(String hash,boolean remove){
  new Thread(()->{try{
   for(TorrentHandle h:manager.getTorrentHandles())if(h.infoHash().toHex().equals(hash)){if(remove)manager.remove(h);else if(h.flags()!=null)h.pause();break;}
  }catch(Throwable t){error=t.toString();}}).start();
 }
 java.util.List<TorrentHandle> handles(){return manager==null?java.util.Collections.emptyList():java.util.Arrays.asList(manager.getTorrentHandles());}
 @Override public IBinder onBind(Intent i){return null;}
 @Override public int onStartCommand(Intent i,int f,int id){return START_STICKY;}
 @Override public void onDestroy(){live=null;new Thread(()->{if(manager!=null)manager.stop();}).start();super.onDestroy();}
}
