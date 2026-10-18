package com.sjcw.heymate;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.location.LocationManager;

/** Local-only Wi-Fi identification. Never treats "unknown ssid" as trusted. */
final class HomeWifi {
    static final String PREF_SSID="trusted_wifi_ssid";
    private HomeWifi(){}
    static final class Connection {
        final boolean wifi;
        final String ssid;
        final String diagnostic;
        Connection(boolean isWifi,String network,String info){
            wifi=isWifi;ssid=network;diagnostic=info;
        }
    }
    static String strip(String raw) {
        if(raw==null)return "";
        String s=raw.trim();
        if(s.length()>=2 && s.startsWith("\"") && s.endsWith("\""))
            s=s.substring(1,s.length()-1);
        if(s.equalsIgnoreCase("<unknown ssid>")||s.equalsIgnoreCase("unknown ssid")||
            s.equals("0x")||s.equals("02:00:00:00:00:00"))return "";
        return s;
    }
    static Connection current(Context context){
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return new Connection(false,"","Connectivity unavailable");
        Network active=cm.getActiveNetwork();
        NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
        if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            return new Connection(false,"","Wi-Fi is not the active connection (mobile data / offline)");
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            return new Connection(true,"","Location permission required to identify Wi-Fi");
        LocationManager lm=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        if(lm!=null&&Build.VERSION.SDK_INT>=28 && !lm.isLocationEnabled())
            return new Connection(true,"","Turn on Android Location to identify Wi-Fi network");
        String name="";
        if(Build.VERSION.SDK_INT>=29 && caps.getTransportInfo() instanceof WifiInfo)
            name=strip(((WifiInfo)caps.getTransportInfo()).getSSID());
        if(name.isEmpty()){
            try {
                WifiManager wm=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info=wm==null?null:wm.getConnectionInfo();
                if(info!=null)name=strip(info.getSSID());
            }catch(SecurityException ignored){}
        }
        if(name.isEmpty())return new Connection(true,"","Android did not reveal Wi-Fi name; microphone stays off");
        return new Connection(true,name,"Connected: "+name);
    }
    static boolean trusted(Context c){
        String saved=c.getSharedPreferences(WakeService.PREFS,Context.MODE_PRIVATE)
            .getString(PREF_SSID,"");
        Connection con=current(c);
        return !saved.isEmpty() && con.wifi && saved.equals(con.ssid);
    }
}
