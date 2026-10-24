package local.thor.ffxiicompanion.data;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** US PLMN port; output matches Python ensure_ascii=False compact insertion-order JSON. */
public final class AreasBuilder {
    public static final int MAX_STRING_BYTES=1024, MAX_SCANNED_BYTES=4*1024*1024, MAX_JSON_BYTES=4*1024*1024;
    private static String quote(String s) {
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<s.length();i++) { char c=s.charAt(i);switch(c) {case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;default:if(c<32)b.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}}
        return b.append('"').toString();
    }
    public static byte[] build(byte[] raw) throws IOException {
        if(raw.length<16 || raw.length>1024*1024)throw new IOException("PLMN length");
        ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        long maps=Integer.toUnsignedLong(b.getInt(4)),regions=Integer.toUnsignedLong(b.getInt(8)),total=Integer.toUnsignedLong(b.getInt(12));
        if(b.getInt(0)!=0x4e4d4c50 || maps+regions!=total || total>(raw.length-16)/2)throw new IOException("PLMN table");
        int tableEnd=16+2*(int)total,scanned=0;Map<String,String> names=new LinkedHashMap<>();
        for(int i=0;i<(int)maps;i++) {
            int off=Short.toUnsignedInt(b.getShort(16+2*i));if(off==0)continue;
            if(off<tableEnd || off>=raw.length)throw new IOException("PLMN offset");
            StringBuilder s=new StringBuilder();int p=off;
            while(p<raw.length && raw[p]!=0) {
                if(p-off>=MAX_STRING_BYTES || scanned>=MAX_SCANNED_BYTES)throw new IOException("PLMN string work budget");
                scanned++;int c=raw[p++]&255;
                if(c>=0x20 && c<=0x39)s.append((char)(c+0x21));
                else if(c>=0x3a && c<=0x53)s.append((char)(c+0x27));
                else if(c>=0x85 && c<=0x8e)s.append((char)(c-0x85+48));
                else s.append(c==4?' ':c==0xa3?'_':c==0xa5?'-':c==0xa8?'.':c==0xac?'\'':'?');
            }
            if(p==raw.length)throw new IOException("PLMN terminator");
            if(!s.toString().matches("^[a-z]{3}_.*"))names.put(Integer.toString(i),s.toString());
        }
        StringBuilder json=new StringBuilder("{\"format\":\"ffxii-companion-areas-1\",\"names\":{");boolean comma=false;
        for(Map.Entry<String,String> e:names.entrySet()) {
            String key=quote(e.getKey()),value=quote(e.getValue());
            if(json.length()+key.length()+value.length()+4>MAX_JSON_BYTES)throw new IOException("PLMN JSON budget");
            if(comma)json.append(',');comma=true;json.append(key).append(':').append(value);
        }
        return json.append("}}").toString().getBytes(StandardCharsets.UTF_8);
    }
}
