package com.aviationarchive.app;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.DatabaseUtils;
import org.json.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
 private SQLiteDatabase database;
 private LinearLayout list;
 private EditText query;
 private Spinner maker;
 private TextView status;
 private final int navy=Color.rgb(16,33,58),blue=Color.rgb(35,103,169);
 private String currentQuery="",currentMaker="All manufacturers";
 private int offset=0;
 private boolean showingDetails=false;
 private String[] brands={"All manufacturers","Airbus","Boeing","McDonnell Douglas","Douglas","Lockheed","Embraer","Bombardier","Cessna","Piper","Beechcraft","Antonov","Tupolev","Ilyushin","ATR","de Havilland","Fokker","Sukhoi","Saab","Pilatus","Other / unidentified"};

 @Override public void onCreate(Bundle state){super.onCreate(state);try{openArchive();home();}catch(Exception e){TextView t=new TextView(this);t.setText("Unable to open aviation archive:\n"+e);setContentView(t);}}
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(4),dp(5),dp(4),dp(5));return t;}
 private LinearLayout column(int inset){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(inset),dp(inset),dp(inset),dp(inset));return l;}
 private void openArchive() throws IOException {
  File dst=new File(getFilesDir(),"aviation_v2.db");
  if(!dst.exists() || dst.length()<1000000L){
   File temp=new File(getFilesDir(),"aviation_v2.tmp");
   try(InputStream in=getAssets().open("aviation.db");OutputStream out=new FileOutputStream(temp)){
    byte[] buf=new byte[65536];int len;while((len=in.read(buf))!=-1)out.write(buf,0,len);
   }
   if(dst.exists())dst.delete();
   if(!temp.renameTo(dst))throw new IOException("Cannot install offline database");
  }
  database=SQLiteDatabase.openDatabase(dst.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
 }
 private void home(){
  showingDetails=false;
  LinearLayout root=column(12);root.setBackgroundColor(Color.rgb(245,248,251));
  TextView heading=text("AVIATION ARCHIVE",25,navy);heading.setTypeface(null,Typeface.BOLD);root.addView(heading);
  root.addView(text("WORLDWIDE AVIATION OCCURRENCE DATABASE",12,blue));
  long count=DatabaseUtils.longForQuery(database,"SELECT count(*) FROM incidents",null);
  root.addView(text(String.format(Locale.US,"%,d sourced records • Offline • 1902–2026",count),13,Color.DKGRAY));
  root.addView(text("Data from 124 investigating authorities. Some entries have unknown fields; this is not an exhaustive global census.",12,Color.DKGRAY));
  query=new EditText(this);query.setSingleLine(true);query.setTextSize(16);query.setHint("Search airline, aircraft, flight, registration...");query.setText(currentQuery);root.addView(query);
  maker=new Spinner(this);
  ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,brands);maker.setAdapter(adapter);
  int at=Arrays.asList(brands).indexOf(currentMaker);maker.setSelection(Math.max(0,at));root.addView(maker);
  status=text("",13,Color.DKGRAY);root.addView(status);
  ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(1);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
  query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){currentQuery=s.toString();offset=0;refresh();}public void afterTextChanged(Editable e){}});
  maker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){currentMaker=brands[pos];offset=0;refresh();}});
  refresh();
 }
 private String safe(String s){return s==null||s.trim().isEmpty()?"Unknown":s;}
 private void refresh(){
  if(list==null||database==null)return;
  list.removeAllViews();
  String phrase=currentQuery.trim().replaceAll("[^\\p{L}\\p{N}\\- ]"," ");
  String[] words=phrase.split("\\s+");
  ArrayList<String> terms=new ArrayList<>();
  for(String w:words)if(w.length()>0)terms.add(w.replace("-"," ") .trim().replace(" ","* ")+"*");
  StringBuilder sql=new StringBuilder("SELECT id,date,title,manufacturer,aircraft,country,severity,fatalities FROM incidents WHERE 1=1");
  ArrayList<String> args=new ArrayList<>();
  if(!terms.isEmpty()){
   sql.append(" AND id IN (SELECT rowid FROM incidents_fts WHERE incidents_fts MATCH ?)");
   sql=sql;args.add(String.join(" AND ",terms));
  }
  if(!"All manufacturers".equals(currentMaker)){sql.append(" AND manufacturer=?");args.add(currentMaker);}
  sql.append(" ORDER BY date DESC, id DESC LIMIT 61 OFFSET ").append(offset);
  Cursor c=null;int shown=0;boolean more=false;
  try{
   c=database.rawQuery(sql.toString(),args.toArray(new String[0]));
   while(c.moveToNext()){
    if(shown>=60){more=true;break;}
    final long id=c.getLong(0);
    LinearLayout card=column(10);card.setBackgroundColor(Color.WHITE);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(4),0,dp(6));list.addView(card,lp);
    TextView title=text(safe(c.getString(2)),17,navy);title.setTypeface(null,Typeface.BOLD);card.addView(title);
    card.addView(text(safe(c.getString(1))+"   •   "+safe(c.getString(3))+"   •   "+safe(c.getString(4)),12,blue));
    String severity=safe(c.getString(6));String fatalities=c.isNull(7)?"Unknown":String.valueOf(c.getInt(7));
    card.addView(text("Country: "+safe(c.getString(5))+"   •   "+severity+"   •   Fatalities: "+fatalities,12,Color.DKGRAY));
    card.setOnClickListener(v->details(id));
    shown++;
   }
   status.setText((offset+1)+"–"+(offset+shown)+" shown"+(more?" • More available":"")+(terms.isEmpty()?" • newest first":" • matching search"));
   if(shown==0){status.setText("No matching records. Try a different query or select All manufacturers.");list.addView(text("Tip: Manufacturer filters and search terms work together. Select All manufacturers when searching across the entire archive.",14,Color.DKGRAY));}
   if(offset>0){Button prev=new Button(this);prev.setText("Previous results");list.addView(prev);prev.setOnClickListener(v->{offset=Math.max(0,offset-60);refresh();});}
   if(more){Button next=new Button(this);next.setText("Next 60 results");list.addView(next);next.setOnClickListener(v->{offset+=60;refresh();});}
  }catch(Exception e){status.setText("Search error: "+e.getMessage());}
  finally{if(c!=null)c.close();}
 }
 private void section(LinearLayout root,String title,String val){
  TextView t=text(title,14,blue);t.setTypeface(null,Typeface.BOLD);root.addView(t);
  root.addView(text(safe(val),15,navy));
 }
 private void details(long id){
  Cursor c=database.rawQuery("SELECT date,title,operator,manufacturer,aircraft,registration,country,category,severity,occupants,fatalities,summary,report_url,source,verified_extract FROM incidents WHERE id=?",new String[]{String.valueOf(id)});
  if(!c.moveToFirst()){c.close();return;}
  showingDetails=true;
  ScrollView sc=new ScrollView(this);LinearLayout root=column(14);root.setBackgroundColor(Color.rgb(245,248,251));sc.addView(root);
  Button back=new Button(this);back.setText("Back to search");root.addView(back);back.setOnClickListener(v->home());
  TextView heading=text(safe(c.getString(1)),23,navy);heading.setTypeface(null,Typeface.BOLD);root.addView(heading);
  section(root,"Date",c.getString(0));section(root,"Operator",c.getString(2));
  section(root,"Manufacturer and aircraft",safe(c.getString(3))+" • "+safe(c.getString(4)));
  section(root,"Registration",c.getString(5));section(root,"Country (ISO code)",c.getString(6));
  section(root,"Occurrence type / category",c.getString(7));
  section(root,"Severity",c.getString(8));
  section(root,"Occupants",c.isNull(9)?"Unknown":String.valueOf(c.getInt(9)));
  section(root,"Recorded fatalities",c.isNull(10)?"Unknown":String.valueOf(c.getInt(10)));
  section(root,"Case narrative / investigation summary",c.getString(11));
  section(root,"Issuing authority / original source",c.getString(13));
  if(c.getInt(14)==0)root.addView(text("Data caveat: At least one field in this record was extracted or enriched using a language model. Verify important figures against the source.",13,Color.rgb(130,70,20)));
  String link=c.getString(12);
  if(link!=null&&link.startsWith("http")){Button source=new Button(this);source.setText("Open original case source");root.addView(source);source.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(link)));}catch(Exception e){Toast.makeText(this,"No browser available",Toast.LENGTH_SHORT).show();}});}
  root.addView(text("Compilation: FlightFinder / Denys Kolomiiets (2026), CC BY 4.0. DOI: 10.5281/zenodo.22057962. Records reflect varying national coverage and may overlap; do not calculate accident rates from record counts.",12,Color.DKGRAY));
  c.close();setContentView(sc);
 }
 @Override public void onBackPressed(){if(showingDetails)home();else super.onBackPressed();}
 @Override protected void onDestroy(){if(database!=null)database.close();super.onDestroy();}
}
