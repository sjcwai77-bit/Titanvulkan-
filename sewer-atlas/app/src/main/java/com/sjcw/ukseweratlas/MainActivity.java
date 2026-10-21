package com.sjcw.ukseweratlas;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.graphics.Color;
import android.view.WindowManager;
public class MainActivity extends Activity {
  private WebView web;
  @Override public void onCreate(Bundle b) { super.onCreate(b); getWindow().setStatusBarColor(Color.rgb(7,15,25)); getWindow().setNavigationBarColor(Color.rgb(7,15,25));
    web=new WebView(this); web.setBackgroundColor(Color.rgb(7,15,25)); web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true); web.getSettings().setAllowFileAccess(true);
    web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); web.setWebViewClient(new WebViewClient()); web.setWebChromeClient(new WebChromeClient()); setContentView(web);
    web.loadUrl("file:///android_asset/index.html");
  }
  @Override public void onBackPressed(){if(web.canGoBack()) web.goBack(); else super.onBackPressed();}
}
