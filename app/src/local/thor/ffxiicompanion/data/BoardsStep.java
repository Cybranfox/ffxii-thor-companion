package local.thor.ffxiicompanion.data;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Vanilla-only boards document; job display names are supplied from private input. */
public final class BoardsStep {
    public static final String PACK="ps2data/image/ff12/test_battle/us/binaryfile/battle_pack.bin";
    private static void require(boolean ok,String why) throws IOException { if(!ok)throw new IOException(why); }
    private static LinkedHashMap<String,Object> map(Object... kv) {
        LinkedHashMap<String,Object> m=new LinkedHashMap<>();
        for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;
    }
    public static String hex(byte[] b) {
        char[] chars=new char[2*b.length],digits="0123456789abcdef".toCharArray();
        for(int i=0;i<b.length;i++){int c=b[i]&255;chars[2*i]=digits[c>>>4];chars[2*i+1]=digits[c&15];}return new String(chars);
    }
    public static String sha256(byte[] b) {
        try{return hex(MessageDigest.getInstance("SHA-256").digest(b));}
        catch(NoSuchAlgorithmException ex){throw new AssertionError(ex);}
    }
    public static LinkedHashMap<String,Object> build(Inputs inputs,List<String> jobNames) throws IOException {
        require(inputs!=null && jobNames!=null && jobNames.size()==12,"twelve private job names required");
        for(String name:jobNames)require(name!=null && !name.isEmpty() && name.length()<=256,"job name length");
        byte[] packBytes=inputs.read(PACK);require(packBytes!=null,"battle pack missing");String packSha=sha256(packBytes);BattlePack pack=new BattlePack(packBytes);
        byte[][] rows=pack.records(12);LinkedHashMap<String,Object> names=new LinkedHashMap<>();
        for(int i=0;i<rows.length;i++) {
            String name=pack.text(BattlePack.u16(rows[i],0));
            if(name!=null && !name.isEmpty())names.put(Integer.toString(i),name);
        }
        List<Object> boards=new ArrayList<>();
        for(int j=0;j<12;j++) {
            String shared="ps2data/image/ff12/test_battle/in/binaryfile/board_"+(j+1)+".bin";
            // The captured validated recipe selects shared files: no western boards exist.
            // A future recipe must explicitly resolve language before invoking this step.
            byte[] raw=inputs.read(shared);
            LinkedHashMap<String,Object> board=parseBoard(raw,rows.length,j,jobNames.get(j));boards.add(board);
        }
        return map("format","ffxii-companion-boards-1","kind","REFERENCE",
            "note","Built in memory from vanilla files; not for redistribution.",
            "packSha256",packSha,"nodeCount",rows.length,"names",names,"boards",boards);
    }
    public static LinkedHashMap<String,Object> parseBoard(byte[] raw,int nodeCount,int job,String name) throws IOException {
        require(raw!=null && raw.length>=8 && raw.length<=2*1024*1024,"board extent");
        require(raw[0]=='l' && raw[1]=='i' && raw[2]=='c' && raw[3]=='d',"board magic");
        int w=BattlePack.u16(raw,4),h=BattlePack.u16(raw,6);
        require(w>0 && h>0 && w<=512 && h<=512 && raw.length==8+2L*w*h,"board dimensions");
        require(nodeCount>0 && nodeCount<=65536,"board node count");
        for(int i=8;i<raw.length;i+=2){int n=BattlePack.u16(raw,i);require(n==65535 || n<nodeCount,"board cell id");}
        byte[] cells=java.util.Arrays.copyOfRange(raw,8,raw.length);
        return map("job",job,"name",name,"w",w,"h",h,"cells",hex(cells));
    }
}
