package app.sjcw.torrent;
import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import com.frostwire.jlibtorrent.*;
public class MainActivity extends Activity {
 LinearLayout root,items; EditText query; TextView headline; Handler handler=new Handler(Looper.getMainLooper());
 ExecutorService io=Executors.newSingleThreadExecutor(); ExecutorService searchWorkers=Executors.newFixedThreadPool(2); volatile int searchId=0; HashSet<String> paused=new HashSet<>();
 int bg=0xff101A27, panel=0xff1C3041, ink=0xffF5F8FF;
 TextView label(String s,int sp){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(ink);t.setPadding(14,13,14,13);return t;}
 Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
 void alert(String msg){runOnUiThread(()->new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK",null).show());}
 @Override public void onCreate(Bundle b){super.onCreate(b);
  getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
  ScrollView sc=new ScrollView(this);root=new LinearLayout(this);root.setPadding(20,20,20,24);root.setOrientation(1);root.setBackgroundColor(bg);sc.addView(root);setContentView(sc);
  root.addView(label("SJCW TORRENT",28));root.addView(label("Archive + community torrent search • Magnet links • BitTorrent",13));
  query=new EditText(this);query.setSingleLine();query.setTextColor(ink);query.setHintTextColor(0xffA9B6C5);query.setHint("Search torrents or paste a magnet link");root.addView(query);
  LinearLayout controls=new LinearLayout(this);root.addView(controls);
  Button search=button("Search");Button add=button("Add magnet");controls.addView(search,new LinearLayout.LayoutParams(0,-2,1));controls.addView(add,new LinearLayout.LayoutParams(0,-2,1));
  search.setOnClickListener(v->search(query.getText().toString().trim()));add.setOnClickListener(v->add(query.getText().toString().trim()));
  Button view=button("View downloads");root.addView(view);view.setOnClickListener(v->{headline.setText("Downloads");refresh();});
  Button open=button("Open .torrent file");root.addView(open);open.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,4);});
  headline=label("Downloads",20);root.addView(headline);items=new LinearLayout(this);items.setOrientation(1);root.addView(items);
  startForegroundService(new Intent(this,TorrentService.class));
  Uri data=getIntent().getData();if(data!=null&&"magnet".equals(data.getScheme()))add(data.toString());
  handler.postDelayed(this::refresh,1000);
 }
 void add(String src){TorrentService s=TorrentService.live;if(s==null){alert("Starting torrent service. Please retry.");return;}if(!(src.startsWith("magnet:?")||new File(src).isFile())){alert("Enter a magnet link or select a .torrent file.");return;}s.add(src);query.setText("");}
 @Override protected void onActivityResult(int req,int result,Intent intent){super.onActivityResult(req,result,intent);if(req==4&&result==RESULT_OK&&intent!=null){
  io.execute(()->{try(InputStream in=getContentResolver().openInputStream(intent.getData())){File f=new File(getFilesDir(),"import_"+System.currentTimeMillis()+".torrent");try(OutputStream out=new FileOutputStream(f)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}runOnUiThread(()->add(f.getAbsolutePath()));}catch(Exception e){alert(e.toString());}});
 }}

 String readUrl(String location)throws Exception {
  HttpURLConnection c=(HttpURLConnection)new URL(location).openConnection();
  c.setConnectTimeout(11000);c.setReadTimeout(13000);
  c.setRequestProperty("User-Agent","SJCWTorrent/0.2 (Android)");
  try {
   int status=c.getResponseCode();
   if(status!=200)throw new IOException("HTTP "+status);
   ByteArrayOutputStream buf=new ByteArrayOutputStream();
   try(InputStream in=c.getInputStream()){
    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){
     if(buf.size()+n>3000000)throw new IOException("Search response too large");
     buf.write(b,0,n);
    }
   }
   return buf.toString("UTF-8");
  }finally{c.disconnect();}
 }
 void search(String q){
  if(q.isEmpty())return;
  if(q.startsWith("magnet:")){add(q);return;}
  final int token=++searchId;
  headline.setText("Search: "+q);items.removeAllViews();
  items.addView(label("Results depend on reachable indexers. Use only torrents you're authorised to download.",12));
  items.addView(label("COMMUNITY TORRENTS",18));
  LinearLayout community=new LinearLayout(this);community.setOrientation(1);items.addView(community);
  community.addView(label("Searching community catalogue…",14));
  items.addView(label("INTERNET ARCHIVE",18));
  LinearLayout archive=new LinearLayout(this);archive.setOrientation(1);items.addView(archive);
  archive.addView(label("Searching public archive…",14));
  searchWorkers.execute(()->{
   try{
    String url="https://apibay.org/q.php?q="+URLEncoder.encode(q,"UTF-8")+"&cat=0";
    JSONArray results=new JSONArray(readUrl(url));
    runOnUiThread(()->{
     if(token!=searchId)return;
     community.removeAllViews();int count=0;
     for(int i=0;i<results.length()&&count<35;i++){
      JSONObject obj=results.optJSONObject(i);if(obj==null)continue;
      String hash=obj.optString("info_hash","");
      if(!hash.matches("[a-fA-F0-9]{40}"))continue;
      String name=obj.optString("name","Untitled torrent");
      if(name.length()>240)name=name.substring(0,240);
      int seeds=obj.optInt("seeders",0);long size=obj.optLong("size",0);
      String magnet="magnet:?xt=urn:btih:"+hash+"&dn="+Uri.encode(name)+"&tr="+Uri.encode("udp://tracker.opentrackr.org:1337/announce");
      TextView line=label(name+"\n"+(size>0?(size/1048576)+" MB • ":"")+"Seeds: "+seeds+" • Apibay",14);
      line.setBackgroundColor(panel);community.addView(line);
      Button take=button("Add magnet");community.addView(take);
      take.setOnClickListener(v->{add(magnet);alert("Torrent added. Open View downloads to monitor it.");});
      count++;
     }
     if(count==0)community.addView(label("No matches in this community index.",14));
    });
   }catch(Exception e){
    runOnUiThread(()->{if(token!=searchId)return;community.removeAllViews();community.addView(label("Community index unavailable: "+e.getMessage()+"\nYour ISP or network may block the provider.",13));});
   }
  });
  searchWorkers.execute(()->{
   try{
    String term=q.replaceAll("[^\\p{L}\\p{N} ]"," ").trim();
    String condition="("+term+") AND mediatype:(movies OR audio OR texts OR software)";
    String url="https://archive.org/advancedsearch.php?q="+URLEncoder.encode(condition,"UTF-8")+"&fl%5B%5D=identifier&fl%5B%5D=title&rows=20&page=1&output=json";
    JSONArray docs=new JSONObject(readUrl(url)).getJSONObject("response").getJSONArray("docs");
    runOnUiThread(()->{
     if(token!=searchId)return;
     archive.removeAllViews();
     if(docs.length()==0){archive.addView(label("No archive matches.",14));return;}
     for(int i=0;i<docs.length();i++){
      JSONObject d=docs.optJSONObject(i);if(d==null)continue;
      String id=d.optString("identifier"),name=d.optString("title",id);
      TextView line=label(name+"\nInternet Archive",14);line.setBackgroundColor(panel);archive.addView(line);
      Button b=button("Try archive torrent");archive.addView(b);b.setOnClickListener(v->downloadArchive(id));
     }
    });
   }catch(Exception e){runOnUiThread(()->{if(token!=searchId)return;archive.removeAllViews();archive.addView(label("Internet Archive search failed: "+e.getMessage(),13));});}
  });
 }
 void downloadArchive(String id){io.execute(()->{
 try{String safe=Uri.encode(id);URL u=new URL("https://archive.org/download/"+safe+"/"+safe+"_archive.torrent");HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(20000);
 File f=new File(getFilesDir(),"archive_"+System.currentTimeMillis()+".torrent");
 try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(f)){byte[] a=new byte[8192];int n;while((n=in.read(a))!=-1)out.write(a,0,n);}
 runOnUiThread(()->{add(f.getAbsolutePath());alert("Added archive torrent.");});
 }catch(Exception e){alert("Archive has no available torrent: "+e.getMessage());}
 });}
 void refresh(){TorrentService s=TorrentService.live;
 if(s!=null&&!"Downloads".contentEquals(headline.getText())){handler.postDelayed(this::refresh,2500);return;}
 items.removeAllViews();if(s==null){items.addView(label("Starting service…",14));handler.postDelayed(this::refresh,2500);return;}
 if(!TorrentService.error.isEmpty())items.addView(label("Engine: "+TorrentService.error,13));
 try{List<TorrentHandle> hs=s.handles();if(hs.isEmpty())items.addView(label("No active torrents. Enter a magnet link or search the archive.",14));
 for(TorrentHandle h:hs){
 TorrentStatus st=h.status();String hash=h.infoHash().toHex();
 LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(12,10,12,10);card.setBackgroundColor(panel);items.addView(card);
 card.addView(label(st.name(),16));card.addView(label(Math.round(st.progress()*100)+"% • ↓ "+(st.downloadRate()/1024)+" KiB/s • "+st.numPeers()+" peers",13));
 LinearLayout buttons=new LinearLayout(this);card.addView(buttons);Button toggle=button(paused.contains(hash)?"Resume":"Pause");buttons.addView(toggle,new LinearLayout.LayoutParams(0,-2,1));toggle.setOnClickListener(v->{io.execute(()->{if(paused.contains(hash)){h.resume();paused.remove(hash);}else{h.pause();paused.add(hash);} });});
 Button del=button("Remove");buttons.addView(del,new LinearLayout.LayoutParams(0,-2,1));del.setOnClickListener(v->{io.execute(()->s.control(hash,true));});
 }}catch(Throwable t){items.addView(label(t.toString(),13));}
 handler.postDelayed(this::refresh,2500);
 }
 @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);io.shutdownNow();searchWorkers.shutdownNow();super.onDestroy();}
}