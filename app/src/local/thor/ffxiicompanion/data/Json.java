package local.thor.ffxiicompanion.data;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Compact UTF-8 Python-json-compatible writer for this integer-only schema. */
public final class Json {
  public static LinkedHashMap<String,Object> object(Object... pairs) {
    if((pairs.length&1)!=0)throw new IllegalArgumentException("pairs");
    LinkedHashMap<String,Object> m=new LinkedHashMap<>();
    for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);
    return m;
  }
  public static byte[] encode(Object value) throws IOException {
    String s=stringify(value);
    try { ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s));
      byte[] out=new byte[b.remaining()];b.get(out);return out;
    } catch(CharacterCodingException e){throw new IOException("unpaired surrogate",e);}
  }
  public static String stringify(Object value) throws IOException {
    StringBuilder s=new StringBuilder();write(value,s,0);if(s.length()>16*1024*1024)throw new IOException("JSON output cap");return s.toString();
  }
  private static void quote(String v,StringBuilder s) throws IOException {
    if(v.length()>4*1024*1024)throw new IOException("string cap");
    s.append('"');
    for(int i=0;i<v.length();i++){char c=v.charAt(i);switch(c){
      case '"':s.append("\\\"");break;case '\\':s.append("\\\\");break;
      case '\b':s.append("\\b");break;case '\f':s.append("\\f");break;
      case '\n':s.append("\\n");break;case '\r':s.append("\\r");break;case '\t':s.append("\\t");break;
      default:if(c<32){s.append("\\u00");s.append("0123456789abcdef".charAt(c>>4));s.append("0123456789abcdef".charAt(c&15));}
        else if(Character.isHighSurrogate(c)){if(i+1>=v.length()||!Character.isLowSurrogate(v.charAt(i+1)))throw new IOException("unpaired surrogate");s.append(c).append(v.charAt(++i));}
        else if(Character.isLowSurrogate(c))throw new IOException("unpaired surrogate");else s.append(c);
    }}s.append('"');
  }
  private static void write(Object v,StringBuilder s,int depth) throws IOException {
    if(depth>48||s.length()>16*1024*1024)throw new IOException("JSON cap");
    if(v==null){s.append("null");return;}if(v instanceof String){quote((String)v,s);return;}
    if(v instanceof Boolean||v instanceof Byte||v instanceof Short||v instanceof Integer||v instanceof Long){s.append(v);return;}
    if(v instanceof Map){s.append('{');boolean comma=false;for(Object raw:((Map<?,?>)v).entrySet()){
      Map.Entry<?,?> e=(Map.Entry<?,?>)raw;if(comma)s.append(',');comma=true;
      Object k=e.getKey();if(!(k instanceof String)&&!(k instanceof Integer)&&!(k instanceof Long))throw new IOException("key type");
      quote(k.toString(),s);s.append(':');write(e.getValue(),s,depth+1);
    }s.append('}');return;}
    if(v instanceof Iterable){s.append('[');boolean comma=false;for(Object x:(Iterable<?>)v){if(comma)s.append(',');comma=true;write(x,s,depth+1);}s.append(']');return;}
    throw new IOException("unsupported JSON type: "+v.getClass().getSimpleName());
  }
  public static Object parse(byte[] bytes) throws IOException {
    if(bytes.length>4*1024*1024)throw new IOException("JSON input cap");
    String text;
    try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
    catch(CharacterCodingException e){throw new IOException("UTF8",e);}
    Parser p=new Parser(text);Object value=p.value(0);p.ws();if(p.at!=text.length())throw new IOException("trailing JSON");return value;
  }
  private static final class Parser {
    final String s;int at,items;Parser(String s){this.s=s;}
    void ws(){while(at<s.length()&&" \t\r\n".indexOf(s.charAt(at))>=0)at++;}
    char next()throws IOException{if(at==s.length())throw new IOException("short JSON");return s.charAt(at++);}
    Object value(int depth)throws IOException{
      if(depth>48||++items>500000)throw new IOException("JSON budget");ws();char c=next();
      if(c=='"')return string();
      if(c=='{'||c=='['){boolean map=c=='{';Map<String,Object> m=new LinkedHashMap<>();List<Object> l=new ArrayList<>();ws();
        if(at<s.length()&&s.charAt(at)==(map?'}':']')){at++;return map?m:l;}
        while(true){if(map){ws();if(next()!='"')throw new IOException("key");String k=string();ws();if(next()!=':')throw new IOException("colon");if(m.containsKey(k))throw new IOException("duplicate key");m.put(k,value(depth+1));}else l.add(value(depth+1));
          ws();char sep=next();if(sep==(map?'}':']'))break;if(sep!=',')throw new IOException("separator");}
        return map?m:l;
      }
      for(String literal:new String[]{"true","false","null"})if(c==literal.charAt(0)&&s.startsWith(literal.substring(1),at)){at+=literal.length()-1;return literal.equals("null")?null:Boolean.valueOf(literal);}
      if(c=='-'||c>='0'&&c<='9'){int start=at-1;if(c=='-'){c=next();if(c<'0'||c>'9')throw new IOException("number");}
        if(c=='0'&&at<s.length()&&Character.isDigit(s.charAt(at)))throw new IOException("leading zero");
        while(at<s.length()&&s.charAt(at)>='0'&&s.charAt(at)<='9')at++;
        try{return Long.parseLong(s.substring(start,at));}catch(NumberFormatException e){throw new IOException("integer range");}}
      throw new IOException("JSON token");
    }
    String string()throws IOException{StringBuilder b=new StringBuilder();while(true){char c=next();if(c=='"')return b.toString();if(c<32)throw new IOException("control in string");if(c=='\\'){c=next();switch(c){
      case '"':case '\\':case '/':break;case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;
      case 'u':if(at+4>s.length())throw new IOException("unicode escape");try{c=(char)Integer.parseInt(s.substring(at,at+4),16);}catch(NumberFormatException e){throw new IOException("unicode escape");}at+=4;break;default:throw new IOException("escape");}}
      b.append(c);if(b.length()>4*1024*1024)throw new IOException("string cap");}}
  }
}
