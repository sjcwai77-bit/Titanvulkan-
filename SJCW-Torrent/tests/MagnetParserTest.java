package app.sjcw.torrent;
public class MagnetParserTest {
 static void require(boolean v,String why){if(!v)throw new AssertionError(why);}
 public static void main(String[] ignored){
  String hash="0123456789abcdef0123456789abcdef01234567";
  MagnetParser.Magnet m=MagnetParser.parse("magnet:?xt=urn:btih:"+hash+"&dn=Example%20Movie%20File&tr=udp%3A%2F%2Fexample.org%3A80");
  require(hash.equals(m.hash),"hex hash");
  require("Example Movie File".equals(m.displayName),"decode name");
  MagnetParser.Magnet b=MagnetParser.parse("magnet:?dn=Linux&xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
  require("0000000000000000000000000000000000000000".equals(b.hash),"base32 hash");
  boolean invalid=false;
  try{MagnetParser.parse("magnet:?dn=broken");}catch(IllegalArgumentException ok){invalid=true;}
  require(invalid,"reject missing info hash");
  System.out.println("MAGNET_PARSER_TESTS_PASSED");
 }
}
