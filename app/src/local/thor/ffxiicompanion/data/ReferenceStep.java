package local.thor.ffxiicompanion.data;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Personal-use reference data, decoded only from caller-owned captured inputs. Never evaluates Lua. */
public final class ReferenceStep {
    static final String PACK="ps2data/image/ff12/test_battle/us/binaryfile/battle_pack.bin";
    public static final String GEAR="scripts/config/TheInsurgentsDescriptiveInventoryConfig/us.lua";
    public static final String BEST="scripts/config/FoedexClanPrimerTextReplacerConfig/Bestiary/us.lua";
    public static final String TIPS="scripts/config/FoedexClanPrimerTextReplacerConfig/TravelersTips/us.lua";
    // Explicit escape avoids dependence on the javac host's default source encoding.
    static final String SEP=" \u00b7 ";
    static final int MAX_CONFIG=4*1024*1024,MAX_ROWS=8192,MAX_FIELD=1024*1024;
    static final Pattern SPACE=Pattern.compile("[\\x09-\\x0d\\x1c-\\x20\\x85\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+");
    static boolean white(char c){return c>=9&&c<=13||c>=0x1c&&c<=0x20||c==0x85||c==0xa0||c==0x1680||c>=0x2000&&c<=0x200a||c==0x2028||c==0x2029||c==0x202f||c==0x205f||c==0x3000;}
    static String strip(String s){int a=0,b=s.length();while(a<b&&white(s.charAt(a)))a++;while(b>a&&white(s.charAt(b-1)))b--;return s.substring(a,b);}
    static String rstrip(String s){int b=s.length();while(b>0&&white(s.charAt(b-1)))b--;return s.substring(0,b);}
    static LinkedHashMap<String,Object> obj(Object... kv) {LinkedHashMap<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    static int u16(byte[] a,int off)throws IOException {if(off<0||off+2>a.length)throw new IOException("record u16");return(a[off]&255)|((a[off+1]&255)<<8);}
    static String hex(int n){return String.format(Locale.ROOT,"%04x",n);}
    static String sha(byte[] b)throws IOException {try{byte[] d=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte c:d)s.append(String.format(Locale.ROOT,"%02x",c&255));return s.toString();}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
    static Map<String,Object> source(String path,byte[] b)throws IOException{return obj("file",path,"sha256",sha(b),"bytes",b.length);}
    static String utf8(byte[] raw)throws IOException {
        if(raw.length>MAX_CONFIG)throw new IOException("config byte cap");
        try {String s=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            if(s.indexOf(0)>=0)throw new IOException("config NUL");return s.replace("\r\n","\n").replace('\r','\n');
        }catch(CharacterCodingException e){throw new IOException("config UTF8",e);}
    }
    /** Restricted return-table grammar only; all unmatched syntax fails closed. */
    static final class Config {
        final String s;int p,rows,wrapper;String variable;
        Config(byte[] raw)throws IOException{s=utf8(raw);}
        void ws()throws IOException {while(p<s.length()) {char c=s.charAt(p);if(white(c)){p++;continue;}if(s.startsWith("--",p)){int z=s.indexOf('\n',p);if(s.startsWith("--[",p))throw new IOException("unsupported block comment");p=z<0?s.length():z+1;continue;}break;}}
        void take(char c)throws IOException{ws();if(p>=s.length()||s.charAt(p++)!=c)throw new IOException("config punctuation");}
        boolean next(char c)throws IOException{ws();return p<s.length()&&s.charAt(p)==c;}
        void word(String value)throws IOException {ws();if(!s.startsWith(value,p))throw new IOException("config wrapper token");p+=value.length();if(p<s.length()&&(Character.isLetterOrDigit(s.charAt(p))||s.charAt(p)=='_'))throw new IOException("config token boundary");}
        void begin(String kind)throws IOException{
            ws();variable=kind;
            if(s.startsWith("return",p)){word("return");take('{');return;}
            word("local");if(kind.equals("inventory")&&s.startsWith("function",pAfterWhitespace())){wrapper=1;word("function");word("config");take('(');take(')');word("local");}
            else wrapper=2;
            word(kind);take('=');take('{');
        }
        int pAfterWhitespace()throws IOException{ws();return p;}
        void end()throws IOException{take('}');if(wrapper>0){word("return");word(variable);if(wrapper==1){word("end");word("return");word("config");}}ws();if(p!=s.length())throw new IOException("config trailing syntax");}
        int number()throws IOException {ws();int start=p;long n=0;while(p<s.length()&&s.charAt(p)>='0'&&s.charAt(p)<='9'){n=n*10+s.charAt(p++)-'0';if(n>65535)throw new IOException("config id cap");}if(p==start)throw new IOException("decimal id expected");return(int)n;}
        String quoted()throws IOException{take('"');int start=p;StringBuilder b=new StringBuilder();while(p<s.length()) {char c=s.charAt(p++);if(c=='"')return b.toString();if(c=='\n')throw new IOException("quoted newline");b.append(c);if(c=='\\'){if(p>=s.length()||s.charAt(p)=='\n')throw new IOException("escape truncated");b.append(s.charAt(p++));}if(p-start>MAX_FIELD)throw new IOException("config string cap");}throw new IOException("unterminated quoted string");}
        String longText()throws IOException {ws();if(!s.startsWith("[[",p))throw new IOException("long string expected");p+=2;if(p<s.length()&&s.charAt(p)=='\n')p++;int z=s.indexOf("]]",p);if(z<0||z-p>MAX_FIELD)throw new IOException("long string extent");String out=s.substring(p,z);p=z+2;return out;}
        void separator()throws IOException{ws();if(next(','))p++;else if(!next('}'))throw new IOException("entry separator");if(++rows>MAX_ROWS)throw new IOException("config row cap");}
    }
    static List<String> ded(String text)throws IOException {
        text=tags(text.replace("\\n","\n").replace("\\\"","\""),true).replaceAll("\u001f+","\u001f");
        List<String> lines=new ArrayList<>();
        for(String l:text.split("\n",-1)) {List<String> fields=new ArrayList<>();for(String f:l.split("\u001f",-1)){String v=strip(SPACE.matcher(f).replaceAll(" "));if(!v.isEmpty())fields.add(v);}String v=String.join(SEP,fields);if(!v.isEmpty())lines.add(v);}
        return lines;
    }
    static List<String> fctr(String text)throws IOException {
        List<String> out=new ArrayList<>();for(String raw:text.replace("\r","").split("\n",-1)){
            boolean heading=raw.startsWith("{rgb:")&&raw.endsWith("{color:none}");String l=rstrip(tags(raw,false));
            out.add(heading&&!l.isEmpty()?"## "+l:l);
        }
        int begin=0,end=out.size();while(begin<end&&out.get(begin).isEmpty())begin++;while(end>begin&&out.get(end-1).isEmpty())end--;return new ArrayList<>(out.subList(begin,end));
    }
    static String tags(String text,boolean ded)throws IOException {
        StringBuilder out=new StringBuilder();int p=0;
        while(p<text.length()) {int open=text.indexOf('{',p);if(open<0){out.append(text,p,text.length());break;}out.append(text,p,open);int end=text.indexOf('}',open);if(end<0){out.append(text,open,text.length());break;}
            String tag=text.substring(open+1,end),replacement=null;if(tag.indexOf('{')>=0)throw new IOException("nested markup unsupported");
            if(ded){if(tag.equals("widespace"))replacement="\u001f";else if(tag.equals("lowspace"))replacement=" ";else if(tag.startsWith("scale:")||tag.startsWith("rgb:")||tag.startsWith("vpos:")||tag.startsWith("icon:")||tag.startsWith("spacing:"))replacement="";}
            else {if(tag.equals("wait")||tag.equals("alignright")||tag.startsWith("color:")||tag.startsWith("rgb:"))replacement="";else if(tag.startsWith("hpossetcur:")&&tag.substring(11).matches("\\d+"))replacement="  ";}
            if(replacement==null)out.append(text,open,end+1);else out.append(replacement);p=end+1;
        }return out.toString();
    }
    static List<Object> gear(byte[] raw)throws IOException {
        Config c=new Config(raw);c.ws();List<Object> out=new ArrayList<>();if(c.p==c.s.length())return out;c.begin("inventory");
        while(!c.next('}')) {c.take('{');int id=c.number();c.take(',');String value=c.quoted();c.take('}');c.separator();List<String> lines=ded(value);if(lines.isEmpty())continue;
            String[] head=lines.get(0).split(Pattern.quote(SEP),-1);String cat=null;List<String> tags=new ArrayList<>();for(int i=0;i<head.length;i++){if(head[i].startsWith("Category:")){if(cat==null)cat=strip(head[i].substring(head[i].indexOf(':')+1));}else if(i>0)tags.add(head[i]);}
            out.add(obj("id",id,"name",head[0],"category",cat,"tags",tags,"lines",new ArrayList<>(lines.subList(1,lines.size()))));
        }c.end();return out;
    }
    static String nearbyName(String s,int start)throws IOException {
        int floor=Math.max(0,start-8192),end=start;while(end>floor&&white(s.charAt(end-1)))end--;int p=end;
        for(int n=0;n<6&&p>=floor;n++){int begin=p;while(begin>floor&&s.charAt(begin-1)!='\n')begin--;if(begin==floor&&floor>0&&s.charAt(floor-1)!='\n')throw new IOException("comment lookback cap");String line=strip(s.substring(begin,p));if(line.startsWith("--")&&!line.startsWith("--{")){if(line.length()>1026)throw new IOException("comment name cap");return strip(line.substring(2));}if(begin==0)break;p=begin-1;}
        return null;
    }
    static List<String> locations(Map<Integer,List<String>> pages) {
        Set<String> out=new LinkedHashSet<>();for(List<String> lines:pages.values())if(!lines.isEmpty()&&lines.get(0).equals("Items")){String first=null;for(int i=1;i<lines.size();i++)if(!strip(lines.get(i)).isEmpty()){first=lines.get(i);break;}if(first!=null&&!first.startsWith("##")){String loc=strip(locationPrefix(first));if(!loc.isEmpty())out.add(loc);}}return new ArrayList<>(out);
    }
    static String locationPrefix(String s){StringBuilder out=new StringBuilder();int p=0;while(p<s.length()){int open=s.indexOf('[',p);if(open<0){out.append(s,p,s.length());break;}int close=s.indexOf(']',open+1);if(close<0){out.append(s,p,s.length());break;}int q=close+1;while(q<s.length()&&white(s.charAt(q)))q++;if(q<s.length()&&s.charAt(q)==':'){q++;while(q<s.length()&&white(s.charAt(q)))q++;out.append(s,p,open);p=q;}else{out.append(s,p,close+1);p=close+1;}}return out.toString();}
    static List<Object> fctr(byte[] raw,boolean bestiary)throws IOException {
        Config c=new Config(raw);c.ws();if(c.p==c.s.length())return new ArrayList<>();c.begin(bestiary?"bestiary":"travelerTips");TreeMap<Integer,LinkedHashMap<Integer,List<String>>> entries=new TreeMap<>();Map<Integer,String> names=new HashMap<>();
        while(!c.next('}')) {c.ws();int start=c.p;c.take('{');int id=c.number();c.take(',');int page=c.number();c.take(',');String body=c.longText();c.take('}');c.separator();String name=nearbyName(c.s,start);if(name!=null)names.putIfAbsent(id,name);entries.computeIfAbsent(id,k->new LinkedHashMap<>()).put(page,fctr(body));}
        c.end();List<Object> out=new ArrayList<>();for(Map.Entry<Integer,LinkedHashMap<Integer,List<String>>> e:entries.entrySet()){
            LinkedHashMap<String,Object> row=obj("entry",e.getKey(),"name",names.getOrDefault(e.getKey(),(bestiary?"Entry ":"Tip ")+e.getKey()));if(bestiary)row.put("locations",locations(e.getValue()));List<Object> pages=new ArrayList<>();new TreeMap<>(e.getValue()).forEach((k,v)->pages.add(v));row.put("pages",pages);out.add(row);
        }return out;
    }
    static LinkedHashMap<String,Object> conditions(BattlePack pack)throws IOException {
        byte[][] groups=BattlePack.sections(pack.section(61));if(groups.length<=6)throw new IOException("condition group missing");byte[][] text=BattlePack.sections(groups[6]);byte[][] records=pack.records(7);LinkedHashMap<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<records.length;i++){if(records[i].length!=0x20)throw new IOException("condition stride");int tid=u16(records[i],0x14)-0x3000;if(tid<0||tid>=text.length)continue;byte[] bytes=text[tid];int p=0;while(p<bytes.length&&bytes[p]==0)p++;int z=p;while(z<bytes.length&&bytes[z]!=0)z++;String name=BattlePack.decode(Arrays.copyOfRange(bytes,p,z));if(!name.isEmpty())out.put(hex(0x6000+i),name);}
        return out;
    }
    public static LinkedHashMap<String,Object> build(Inputs inputs)throws IOException {
        byte[] bytes=inputs.read(PACK);BattlePack pack=new BattlePack(bytes);TreeMap<Integer,Object> byId=new TreeMap<>();byte[][] equipment=pack.records(13);
        String[] categories={"weapon","offHand","helm","armor","accessory"};
        for(int i=0;i<Math.min(420,equipment.length);i++){byte[] r=equipment[i];String name=pack.text(u16(r,2));if(name==null||name.isEmpty())continue;if(r.length<8)throw new IOException("equipment stride");int slot=(r[7]&255)>>5;byId.put(0x1000+i,obj("id",0x1000+i,"name",name,"category",slot<5?categories[slot]:null,"tags",new ArrayList<>(),"lines",new ArrayList<>()));}
        byte[][] actions=pack.records(14);LinkedHashMap<String,Object> gambits=new LinkedHashMap<>();
        for(int a=0;a<actions.length;a++){byte[] r=actions[a];if(r.length<0x36)throw new IOException("action stride");int kind=r[0x1e]&255,id=u16(r,0x22);String name=pack.text(u16(r,0x34));
            if(name!=null&&!name.isEmpty()&&((kind==1&&id>=0x3000&&id<0x3051)||(kind==2&&id>=0x4000&&id<0x4018)))byId.put(id,obj("id",id,"name",name,"category",kind==1?"magicks":"technicks","tags",new ArrayList<>(),"lines",new ArrayList<>()));
            if(kind<=3&&name!=null&&!name.isEmpty())gambits.put(hex(a),name);
        }
        byte[][] items=pack.records(18);for(int i=0;i<items.length;i++){String name=pack.text(u16(items[i],8));if(name!=null&&!name.isEmpty())byId.put(i,obj("id",i,"name",name,"category","items","tags",new ArrayList<>(),"lines",new ArrayList<>()));}
        LinkedHashMap<String,Object> sources=obj("vanillaBattlePack",source(PACK,bytes));List<Object> best=new ArrayList<>(),tips=new ArrayList<>();
        for(String path:new String[]{GEAR,BEST,TIPS}){byte[] raw=inputs.config(path);if(raw==null)continue;if(path.equals(GEAR)){for(Object value:gear(raw)){Map<?,?> row=(Map<?,?>)value;byId.put((Integer)row.get("id"),value);}}else if(path.equals(BEST))best=fctr(raw,true);else tips=fctr(raw,false);sources.put(path.equals(GEAR)?"gear":path.equals(BEST)?"bestiary":"tips",source(path,raw));}
        return obj("format","ffxii-companion-reference-1","kind","REFERENCE","language","us","note","Built by the FFXII Thor Companion data builder from your own game files. Personal use only.","sources",sources,"gear",new ArrayList<>(byId.values()),"bestiary",best,"tips",tips,"gambitConditions",conditions(pack),"gambitActions",gambits);
    }
    /** Metadata for BUILD_INFO composition; no second input read required. */
    public static LinkedHashMap<String,Object> metadata(Map<String,Object> reference) {
        List<?> gear=(List<?>)reference.get("gear");int descriptions=0;for(Object g:gear)if(!((List<?>)((Map<?,?>)g).get("lines")).isEmpty())descriptions++;
        Map<?,?> sources=(Map<?,?>)reference.get("sources");return obj("names",gear.size(),"gambitConditions",((Map<?,?>)reference.get("gambitConditions")).size(),"gearDescriptions",descriptions,"bestiary",((List<?>)reference.get("bestiary")).size(),"tips",((List<?>)reference.get("tips")).size(),"gearModPresent",sources.containsKey("gear"),"bestiaryModPresent",sources.containsKey("bestiary"));
    }
}
