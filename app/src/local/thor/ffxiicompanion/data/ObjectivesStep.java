package local.thor.ffxiicompanion.data;

import java.util.*;
import java.nio.*;
import java.nio.charset.*;

/** Java 11 port of objectives_pack.py. Runtime files only; no embedded game text. */
public final class ObjectivesStep {
    static final int MAX = 1 << 20;
    static final long AGGREGATE = 16L << 20;
    static long budget(long used,long extra) {if(extra<0 || used>AGGREGATE-extra)throw bad("aggregate text cap");return used+extra;}
    static final String WORLD = "ps2data/image/ff12/myoshiok/us/worldmap/";
    static final String QUEST = "ps2data/image/ff12/myoshiok/us/quest/";
    static final String FONT = "gamedata/d3d11/artdata/font/us/font00.dat";
    static final String TITLES = "ps2data/image/ff12/test_battle/us/binaryfile/questtitle.bin";
    static final String FLAGS = "ps2data/image/ff12/us/bin/mapjumpgroupflagrom.bin";
    static IllegalArgumentException bad(String s) { return new IllegalArgumentException(s); }
    static void extent(byte[] d, long o, long n) {
        if (o < 0 || n < 0 || o > d.length || n > d.length-o) throw bad("read extent");
    }
    static int u8(byte[] d, int o) { extent(d,o,1); return d[o]&255; }
    static int u16(byte[] d, int o) { extent(d,o,2); return (d[o]&255)|((d[o+1]&255)<<8); }
    static long u32(byte[] d, int o) { extent(d,o,4); return (long)u16(d,o)|((long)u16(d,o+2)<<16); }
    static int index(long n) { if(n<0 || n>Integer.MAX_VALUE) throw bad("integer extent"); return (int)n; }
    static void file(byte[] d,int min,int max,String magic) {
        if(d==null || d.length<min || d.length>max) throw bad("file size");
        if(magic!=null) for(int i=0;i<magic.length();i++) if(u8(d,i)!=magic.charAt(i)) throw bad("file magic");
    }
    static int nul(byte[] d,int start,int end) {
        if(start<0 || end<start || end>d.length) throw bad("text extent");
        for(int i=start;i<end;i++) if(d[i]==0)return i;
        throw bad("unterminated text");
    }
    static byte[] slice(byte[] d,int a,int z) { extent(d,a,(long)z-a);return Arrays.copyOfRange(d,a,z); }
    static LinkedHashMap<String,Object> obj(Object... pairs) {
        LinkedHashMap<String,Object> m=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;
    }
    static boolean printable(int cp) {
        if(cp==32)return true;
        int t=Character.getType(cp);
        return t!=Character.CONTROL && t!=Character.FORMAT && t!=Character.SURROGATE && t!=Character.PRIVATE_USE
            && t!=Character.UNASSIGNED && t!=Character.SPACE_SEPARATOR && t!=Character.LINE_SEPARATOR && t!=Character.PARAGRAPH_SEPARATOR;
    }
    static Map<Integer,String> fontChars(byte[] d) {
        file(d,40,4<<20,null);long count=u32(d,4);
        if(u32(d,0)!=1 || u32(d,36)!=36 || count<1 || count>16384 || d.length!=40+36*count)throw bad("font layout");
        Map<Integer,String> out=new HashMap<>();
        for(int b=32;b<Math.min(256,32+count);b++) {
            int at=40+36*(b-32);if(u32(d,at)!=b-32)throw bad("font glyph order");
            byte[] p=slice(d,at+4,at+8);int z=4;while(z>0 && p[z-1]==0)z--;
            try {
                String s=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(p,0,z)).toString();
                if(s.codePointCount(0,s.length())==1 && printable(s.codePointAt(0)))out.put(b,s);
            } catch(CharacterCodingException ex) { /* same skip as Python font fallback */ }
        }
        return out;
    }
    static String known(int c) {
        if(c>=0x20 && c<=0x39)return Character.toString((char)(c+0x21));
        if(c>=0x3a && c<=0x53)return Character.toString((char)(c+0x27));
        if(c>=0x85 && c<=0x8e)return Character.toString((char)(c-0x85+48));
        switch(c) {
            case 4:return " ";case 0xaa:return ":";case 0xac:return "'";case 0xb2:return "<";
            case 0xa6:return "=";case 0x9e:return "%";case 0xc4:return "\u2265";
            case 0xa5:case 0xa3:return "-";case 0xa4:return "+";case 0xa7:return ",";
            default:return null;
        }
    }
    static boolean space(int c) {
        return (c>=9 && c<=13)||(c>=0x1c && c<=0x20)||c==0x85||Character.isSpaceChar(c);
    }
    static String strip(String s) {
        int a=0,z=s.length();while(a<z && space(s.codePointAt(a)))a+=Character.charCount(s.codePointAt(a));
        while(z>a && space(s.codePointBefore(z)))z-=Character.charCount(s.codePointBefore(z));return s.substring(a,z);
    }
    static String text(byte[] raw,Map<Integer,String> chars) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<raw.length;) {
            if(i+4<=raw.length && (raw[i]&255)==15 && raw[i+1]==32 && (raw[i+2]&255)==0x81){i+=4;continue;}
            int c=raw[i++]&255;if(c==2){out.append('\n');continue;}
            String s=known(c);if(s==null)s=chars.get(c);if(s==null)return null;out.append(s);
        }
        return strip(out.toString());
    }
    static List<int[]> cmap(byte[] d) {
        file(d,16,MAX,"cmap");int first=u16(d,8),tail=u16(d,10);
        if(first<12 || first>tail || tail>=d.length)throw bad("cmap directories");
        int middle=u16(d,tail);if(middle>8192)throw bad("cmap middle table");
        int at=(tail+2+2*middle+3)&~3;int count=index(u32(d,at));
        if(count>4096 || (long)at+4+8L*count>d.length)throw bad("cmap hints");
        List<int[]> rows=new ArrayList<>();int last=-1;
        for(int i=0;i<count;i++) {
            int p=at+4+8*i,threshold=u16(d,p);if(threshold<last)throw bad("cmap order");last=threshold;
            rows.add(new int[]{threshold,u16(d,p+2),u8(d,p+4),u16(d,p+6)});
        }
        return rows;
    }
    static List<byte[]> gmap(byte[] d) {
        file(d,16,MAX,"gmap");int base=u16(d,8),image=u16(d,10),count=u16(d,14);
        if(count>4096 || 16+8L*count>base || base>d.length)throw bad("gmap rows");
        int end=d.length;
        if(image+4<=d.length && d[image]=='T' && d[image+1]=='I' && d[image+2]=='M' && d[image+3]=='2')end=image;
        List<byte[]> out=new ArrayList<>();long used=0;
        for(int i=0;i<count;i++) {
            int off=u16(d,16+8*i+4);if(off==65535){out.add(null);continue;}
            int start=base+off;if(start<base || start>=end)throw bad("gmap text");
            int stop=nul(d,start,end);used=budget(used,stop-start);out.add(slice(d,start,stop));
        }
        return out;
    }
    static Map<Long,byte[]> st2e(byte[] d) {
        file(d,32,MAX,"st2e");int count=index(u32(d,4)),stride=index(u32(d,8)),start=index(u32(d,12));
        if(count<1 || count>65536 || stride!=4 || (long)start+4L*count>d.length)throw bad("st2e rows");
        int outer=index(u32(d,20));if(u32(d,outer)!=1)throw bad("st2e table");
        int base=index((long)outer+u32(d,outer+4)),end=index((long)outer+u32(d,outer+8));
        if(base>=end || end>d.length || u32(d,base)!=count)throw bad("st2e text extent");
        Map<Long,byte[]> out=new LinkedHashMap<>();long used=0;
        for(int i=0;i<count;i++) {
            int s=index((long)base+u32(d,base+4+4*i));if(s<base || s>=end)throw bad("st2e text");
            int stop=nul(d,s,end);used=budget(used,stop-s);out.put(u32(d,start+4*i),slice(d,s,stop));
        }
        return out;
    }
    static List<Object> flags(byte[] d) {
        file(d,10,65536,"FLAGROM1");int count=u16(d,8);
        if(count>4096 || 10+2*count>d.length)throw bad("flags directory");
        List<Object> rows=new ArrayList<>();
        for(int i=0;i<count;i++) {
            int o=u16(d,10+2*i);if(o==0){rows.add(null);continue;}
            if(o<10+2*count || (o&1)!=0)throw bad("flags offset");
            List<Integer> row=new ArrayList<>();boolean ended=false;
            for(int j=0;j<64;j++) { int w=u16(d,o);o+=2;row.add(w);if((w&0x8000)!=0){ended=true;break;} }
            if(!ended)throw bad("flags row too long");rows.add(row);
        }
        return rows;
    }
    static String hex(byte[] d,int a,int z) {
        extent(d,a,(long)z-a);char[] out=new char[(z-a)*2];String h="0123456789abcdef";
        for(int i=a;i<z;i++){out[2*(i-a)]=h.charAt((d[i]>>>4)&15);out[2*(i-a)+1]=h.charAt(d[i]&15);}return new String(out);
    }
    /** 0.17.0: a hunt's map helper (QHD0, 0x473BB0): {kind, entries}; same keys and order as objectives_pack.py. */
    static Map<String,Object> mapHints(byte[] d,int at) {
        if(at<0 || at+12>d.length || d[at]!='Q' || d[at+1]!='H' || d[at+2]!='D' || d[at+3]!='0')throw bad("hunt map helper");
        long n=u32(d,at+4),rel=u32(d,at+8);if(n!=1 || rel<12)throw bad("hunt map directory");
        int r=index(at+rel),count=u8(d,r),kind=u8(d,r+1);
        if(count>64 || kind>1 || (long)r+2+18L*count>d.length)throw bad("hunt map entries");
        List<Object> entries=new ArrayList<>();
        for(int j=0;j<count;j++) {
            int e=r+2+18*j;
            LinkedHashMap<String,Object> row=obj("showMask",hex(d,e+4,e+11),"markerMask",hex(d,e+11,e+18));
            if(kind==0)row.put("regionId",u8(d,e));
            else {row.put("mapId",u16(d,e+2));row.put("markerIndex",u8(d,e+1));}
            entries.add(row);
        }
        return obj("kind",kind,"entries",entries);
    }
    static List<Object> hunts(byte[] menu,byte[] detail,Map<Long,byte[]> titles,Map<Integer,String> chars) {
        file(menu,12,MAX,"qstd");int groups=u16(menu,8),count=u16(menu,10);
        if(groups<1 || groups>16 || count>256 || menu.length!=12+8*count)throw bad("qstd rows");
        file(detail,16,MAX,null);int rows=u16(detail,14),bank2=u16(detail,4),bank3=u16(detail,6),bank5=u16(detail,10);
        if(rows>256 || 16+20*rows>detail.length || bank5+28*rows>detail.length)throw bad("detail rows");
        List<Object> out=new ArrayList<>();int ordinal=0;long used=0;
        for(int i=0;i<count;i++) {
            long title=u32(menu,12+8*i),packed=u32(menu,16+8*i);if((packed&255)!=1)continue;
            int quest=(int)((packed>>>8)&1023);if(ordinal>=rows || quest>255)throw bad("hunt detail row");
            int rel=u16(detail,16+20*ordinal+4);List<Object> paragraphs=new ArrayList<>();
            if(bank2!=65535 && rel!=65535) {
                int s=bank2+rel;if(s<16+20*rows || s>=detail.length)throw bad("hunt narrative");
                int end=nul(detail,s,detail.length),p=s;used=budget(used,end-s);
                for(int j=s;j<=end;j++) if(j==end || detail[j]==3) {paragraphs.add(text(slice(detail,p,j),chars));p=j+1;}
            }
            int helper=u16(detail,16+20*ordinal+6);
            Map<String,Object> hints=bank3!=65535 && helper!=65535?mapHints(detail,bank3+helper):null;
            out.add(obj("questId",quest,"title",text(titles.getOrDefault(title,new byte[0]),chars),
                "phases",hex(detail,bank5+28*ordinal,bank5+28*(ordinal+1)),"paragraphs",paragraphs,"mapHints",hints));
            ordinal++;
        }
        return out;
    }
    public static LinkedHashMap<String,Object> build(Inputs read) throws Exception {
        Map<Integer,String> chars=fontChars(read.read(FONT));List<int[]> rows=cmap(read.read(WORLD+"menumap_common.bin"));
        Map<Integer,List<byte[]>> guides=new TreeMap<>();long guideBytes=0,guideTextBytes=0;
        for(int[] r:rows) if(!guides.containsKey(r[2])) {
            byte[] data=read.read(WORLD+String.format(Locale.ROOT,"menumap_guide%02d.bin",r[2]));
            guideBytes=budget(guideBytes,data.length);List<byte[]> texts=gmap(data);
            for(byte[] raw:texts)if(raw!=null)guideTextBytes=budget(guideTextBytes,raw.length);
            guides.put(r[2],texts);
        }
        List<Object> hints=new ArrayList<>();long hintTextBytes=0;
        for(int[] r:rows) {
            List<byte[]> g=guides.get(r[2]);byte[] raw=r[3]<g.size()?g.get(r[3]):null;
            if(raw!=null)hintTextBytes=budget(hintTextBytes,raw.length);
            hints.add(obj("threshold",r[0],"gate",r[1],"text",raw==null || raw.length==0?null:text(raw,chars)));
        }
        List<Object> huntRows=hunts(read.read(QUEST+"menuquest.dat"),read.read(QUEST+"menuquest01.bin"),st2e(read.read(TITLES)),chars);
        return obj("format","ffxii-companion-objectives-1","hints",hints,"hunts",huntRows,
            "thresholds",flags(read.read(FLAGS)),"note","Built from your own game files for your personal use. Do not share.");
    }
}
