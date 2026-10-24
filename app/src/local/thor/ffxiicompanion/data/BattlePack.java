package local.thor.ffxiicompanion.data;

import java.io.IOException;
import java.util.Arrays;

/** Bounded battle-pack structures and the builder's validated US 8-bit decoder. */
public final class BattlePack {
    public static final int MAX_BYTES=16*1024*1024, MAX_SECTIONS=65536, MAX_NAME_BYTES=4096;
    private final byte[][] data;
    private final byte[][][] texts;

    public BattlePack(byte[] bytes) throws IOException {
        require(bytes!=null && bytes.length<=MAX_BYTES,"battle pack length");
        data=sections(bytes);
        require(data.length>61,"battle pack sections");
        byte[][] groups=sections(data[61]);
        texts=new byte[groups.length][][];
        for(int i=0;i<groups.length;i++)texts[i]=sections(groups[i]);
    }
    private static void require(boolean ok,String why) throws IOException { if(!ok)throw new IOException(why); }
    public static int u16(byte[] b,int p) throws IOException {
        require(b!=null && p>=0 && p<=b.length-2,"u16 extent");
        return (b[p]&255)|((b[p+1]&255)<<8);
    }
    public static long u32(byte[] b,int p) throws IOException {
        require(b!=null && p>=0 && p<=b.length-4,"u32 extent");
        return (b[p]&255L)|((b[p+1]&255L)<<8)|((b[p+2]&255L)<<16)|((b[p+3]&255L)<<24);
    }
    public static byte[][] sections(byte[] bytes) throws IOException {
        require(bytes!=null && bytes.length>=4 && bytes.length<=MAX_BYTES,"section input extent");
        long count=u32(bytes,0), start=4+4*count;
        require(count<=MAX_SECTIONS && start<=bytes.length,"section count");
        byte[][] result=new byte[(int)count][];
        long previous=start;
        for(int i=0;i<(int)count;i++) {
            long a=u32(bytes,4+4*i),z=i+1==(int)count?bytes.length:u32(bytes,8+4*i);
            require(a>=previous && z>=a && z<=bytes.length,"section offsets");
            result[i]=Arrays.copyOfRange(bytes,(int)a,(int)z);previous=z;
        }
        return result;
    }
    public byte[] section(int n) throws IOException {
        require(n>=0 && n<data.length,"section index");return data[n].clone();
    }
    public byte[][] records(int n) throws IOException {
        require(n>=0 && n<data.length,"record section index");byte[] b=data[n];
        require(b.length>=16 && u32(b,0)==0x65327473L,"record header");
        long count=u32(b,4),stride=u32(b,8),offset=u32(b,12);
        require(count<=65536 && stride>0 && stride<=65536 && offset>=16 && offset<=b.length
            && count*stride<=b.length-offset,"record span");
        byte[][] rows=new byte[(int)count][];
        for(int i=0;i<rows.length;i++)rows[i]=Arrays.copyOfRange(b,(int)(offset+i*stride),(int)(offset+(i+1)*stride));
        return rows;
    }
    /** First nonempty NUL-separated component, matching vanilla_fallback.text. */
    public String text(int id) throws IOException {
        require(id>=0 && id<=65535,"text id");int group=id>>>11,row=id&2047;
        if(group>=texts.length || row>=texts[group].length)return null;
        byte[] b=texts[group][row];int p=0;while(p<b.length && b[p]==0)p++;
        if(p==b.length)return null;
        int z=p;while(z<b.length && b[z]!=0)z++;
        require(z-p<=MAX_NAME_BYTES,"text name length");
        return decode(Arrays.copyOfRange(b,p,z)).replace("{a0}","&").replace("{81}","\u00fa").replace("{a8}",".");
    }
    /** Raw gambit_names.decode rules; caller may apply documented substitutions. */
    public static String decode(byte[] bytes) throws IOException {
        require(bytes!=null && bytes.length<=MAX_NAME_BYTES,"decoded string length");StringBuilder s=new StringBuilder();
        final char[] hex="0123456789abcdef".toCharArray();
        for(byte raw:bytes) {
            int c=raw&255;
            if(c>=0x20 && c<=0x39)s.append((char)(c+0x21));
            else if(c>=0x3a && c<=0x53)s.append((char)(c+0x27));
            else if(c>=0x85 && c<=0x8e)s.append((char)(c-0x85+48));
            else switch(c) {
                case 4:s.append(' ');break;case 0xaa:s.append(':');break;case 0xac:s.append('\'');break;
                case 0xb2:s.append('<');break;case 0xa6:s.append('=');break;case 0x9e:s.append('%');break;
                case 0xc4:s.append('\u2265');break;case 0xa5:case 0xa3:s.append('-');break;
                case 0xa4:s.append('+');break;case 0xa7:s.append(',');break;
                default:s.append('{').append(hex[c>>>4]).append(hex[c&15]).append('}');
            }
        }
        return s.toString();
    }
}
