package app.sjcw.torrent;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Parses the opaque query component of magnet: URIs without Android Uri.getQueryParameter. */
final class MagnetParser {
 static final class Magnet {
  final String hash;
  final String displayName;
  Magnet(String hash,String displayName){this.hash=hash;this.displayName=displayName;}
 }
 static Magnet parse(String input) {
  if(input==null||!input.regionMatches(true,0,"magnet:?",0,8))
   throw new IllegalArgumentException("Not a magnet link");
  String hash=null,name="";
  for(String field:input.substring(8).split("&")) {
   int at=field.indexOf('=');
   if(at<=0)continue;
   String key=decode(field.substring(0,at));
   String value=decode(field.substring(at+1));
   if(key.equalsIgnoreCase("xt")&&value.regionMatches(true,0,"urn:btih:",0,9)) {
    String candidate=value.substring(9);
    if(candidate.matches("(?i)[0-9a-f]{40}"))hash=candidate.toLowerCase(Locale.ROOT);
    else if(candidate.matches("(?i)[a-z2-7]{32}"))hash=base32Hex(candidate);
   }
   if(key.equalsIgnoreCase("dn")&&name.isEmpty())
    name=value.length()>240?value.substring(0,240):value;
  }
  if(hash==null)throw new IllegalArgumentException("Magnet link must contain a valid BitTorrent v1 hash (xt=urn:btih:...)");
  return new Magnet(hash,name);
 }
 private static String decode(String value){
  try{return URLDecoder.decode(value,StandardCharsets.UTF_8.name());}
  catch(Exception e){throw new IllegalArgumentException("Invalid magnet encoding",e);}
 }
 private static String base32Hex(String input) {
  String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  int buffer=0,bits=0,bytes=0;
  StringBuilder hex=new StringBuilder(40);
  for(char c:input.toUpperCase(Locale.ROOT).toCharArray()) {
   int val=alphabet.indexOf(c);
   if(val<0)throw new IllegalArgumentException("Invalid base32 hash");
   buffer=(buffer<<5)|val;bits+=5;
   if(bits>=8) {
    int b=(buffer>>(bits-8))&0xff;
    hex.append(Character.forDigit(b>>>4,16)).append(Character.forDigit(b&15,16));
    bytes++;bits-=8;
   }
  }
  if(bytes!=20)throw new IllegalArgumentException("Invalid base32 torrent hash");
  return hex.toString();
 }
}
