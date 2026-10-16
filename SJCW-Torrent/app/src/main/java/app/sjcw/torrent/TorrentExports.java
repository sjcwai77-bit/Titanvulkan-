package app.sjcw.torrent;
import android.content.*;
import android.provider.MediaStore;
import android.os.Environment;
import android.net.Uri;
import java.io.*;
import java.util.*;
import com.frostwire.jlibtorrent.*;

public final class TorrentExports {
 static void export(Context ctx,TorrentHandle handle,String displayName)throws Exception {
  TorrentInfo info=handle.torrentFile();
  if(info==null||info.numFiles()==0)throw new IOException("Torrent file list not available.");
  FileStorage storage=info.files();
  File root=new File(handle.savePath()).getCanonicalFile();
  ArrayList<Uri> created=new ArrayList<>();
  long totalCopied=0;
  String group=safe(displayName);
  try{
   for(int i=0;i<storage.numFiles();i++){
    String relative=storage.filePath(i).replace('\\','/');
    File src=new File(root,relative).getCanonicalFile();
    if(!src.toPath().startsWith(root.toPath()))throw new SecurityException("Unsafe torrent path");
    if(!src.isFile())throw new IOException("Source file missing: "+relative);
    String filename=safe(src.getName());
    if(filename.length()==0)filename="file_"+i;
    String mime=ctx.getContentResolver().getType(Uri.fromFile(src));
    if(mime==null)mime="application/octet-stream";
    ContentValues values=new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME,filename);
    values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
    values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/SJCW Torrent/"+group+"/");
    values.put(MediaStore.MediaColumns.IS_PENDING,1);
    Uri dst=ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
    if(dst==null)throw new IOException("Cannot create destination.");
    created.add(dst);
    long copied=0;
    try(InputStream in=new FileInputStream(src);OutputStream out=ctx.getContentResolver().openOutputStream(dst)){
     if(out==null)throw new IOException("Cannot open output.");
     byte[] b=new byte[1024*1024];int n;
     while((n=in.read(b))!=-1){out.write(b,0,n);copied+=n;}
     out.flush();
    }
    if(copied!=src.length())throw new IOException("Copy verification failed.");
    ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);
    ctx.getContentResolver().update(dst,ready,null,null);
    totalCopied+=copied;
   }
  }catch(Exception failure){
   for(Uri target:created){try{ctx.getContentResolver().delete(target,null,null);}catch(Exception ignored){}}
   throw failure;
  }
 }
 static String safe(String input){
  if(input==null)return "Torrent";
  String result=input.replaceAll("[\\\\/\\x00-\\x1F:*?\"<>|]","_").trim();
  return result.length()>90?result.substring(0,90):result.isEmpty()?"Torrent":result;
 }
}
