package com.aviationarchive.app;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
public class MainActivity extends Activity {
 private final ArrayList<JSONObject> records=new ArrayList<>();
 private LinearLayout list;
 private EditText search;
 private Spinner manufacturer;
 private final int navy=Color.rgb(12,26,47), blue=Color.rgb(35,103,169);
 @Override public void onCreate(Bundle state){super.onCreate(state);load();home();}
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(6),dp(8),dp(6),dp(8));return t;}
 private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(15),dp(15),dp(15),dp(15));return l;}
 private void load(){try(InputStream in=getAssets().open("cases.json")){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);JSONArray a=new JSONArray(b.toString("UTF-8"));for(int i=0;i<a.length();i++)records.add(a.getJSONObject(i));}catch(Exception e){Toast.makeText(this,"Cannot load catalogue: "+e.getMessage(),Toast.LENGTH_LONG).show();}}
 private void home(){
  LinearLayout root=column();root.setBackgroundColor(Color.rgb(246,248,252));
  TextView heading=text("AVIATION ARCHIVE",26,navy);heading.setTypeface(null,1);root.addView(heading);
  root.addView(text("Flight incident reference • offline",13,blue));
  root.addView(text("Starter edition: 3 sourced cases. Not yet a complete accident database.",12,Color.DKGRAY));
  search=new EditText(this);search.setSingleLine(true);search.setHint("Search flight, model, country...");root.addView(search);
  manufacturer=new Spinner(this);
  String[] filters={"All manufacturers","Airbus","Boeing"};
  ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,filters);
  manufacturer.setAdapter(adapter);root.addView(manufacturer);
  ScrollView sc=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(1);sc.addView(list);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
  search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){refresh();}public void afterTextChanged(Editable e){}});
  manufacturer.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){refresh();}});
  refresh();
 }
 private void refresh(){
  list.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT);
  String m=manufacturer.getSelectedItem()==null?"All manufacturers":manufacturer.getSelectedItem().toString();int count=0;
  for(JSONObject ob:records){
   String maker=ob.optString("maker");
   if(!m.equals("All manufacturers")&&!maker.equals(m))continue;
   if(!ob.toString().toLowerCase(Locale.ROOT).contains(q))continue;
   count++;
   LinearLayout tile=column();tile.setBackgroundColor(Color.WHITE);
   LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(6));list.addView(tile,p);
   TextView name=text(ob.optString("flight"),19,navy);name.setTypeface(null,1);tile.addView(name);
   tile.addView(text(ob.optString("date")+" • "+maker+" "+ob.optString("model"),13,blue));
   tile.addView(text(ob.optString("type")+" • "+ob.optString("location"),13,Color.DKGRAY));
   tile.addView(text("Fatalities: "+ob.optInt("fatalities")+" • Aboard: "+ob.optInt("aboard"),13,Color.DKGRAY));
   tile.setOnClickListener(v->details(ob));
  }
  if(count==0)list.addView(text("No records match this search.",16,Color.DKGRAY));
 }
 private void section(LinearLayout root,String title,String body){TextView t=text(title,17,navy);t.setTypeface(null,1);root.addView(t);root.addView(text(body,15,Color.DKGRAY));}
 private void details(JSONObject ob){
  ScrollView sc=new ScrollView(this);LinearLayout root=column();root.setBackgroundColor(Color.rgb(246,248,252));sc.addView(root);
  Button back=new Button(this);back.setText("Back to catalogue");root.addView(back);back.setOnClickListener(v->home());
  TextView heading=text(ob.optString("flight"),25,navy);heading.setTypeface(null,1);root.addView(heading);
  section(root,"Date and aircraft",ob.optString("date")+" • "+ob.optString("maker")+" "+ob.optString("model"));
  section(root,"Location",ob.optString("location"));section(root,"Classification",ob.optString("type"));
  section(root,"People and outcome",ob.optInt("aboard")+" aboard • "+ob.optInt("fatalities")+" fatalities\n"+ob.optString("outcome"));
  section(root,"Event summary",ob.optString("summary"));section(root,"Investigation finding",ob.optString("finding"));
  Button source=new Button(this);source.setText("Open investigation source");root.addView(source);
  source.setOnClickListener(v->{Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(ob.optString("source")));startActivity(intent);});
  root.addView(text("Source: "+ob.optString("sourceTitle")+"\nInternet connection needed to view report.",12,Color.DKGRAY));setContentView(sc);
 }
 @Override public void onBackPressed(){home();}
}