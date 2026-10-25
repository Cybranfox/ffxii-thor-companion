package local.thor.ffxiicompanion.data;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Synthetic pack covers row-owned IDs and malformed fixed tables. */
public final class StatusTablesTest {
    static int n;
    static void check(boolean ok){if(!ok)throw new AssertionError("table check "+n);n++;}
    static byte[] sections(byte[][] rows){int size=4+4*rows.length;for(byte[] r:rows)size+=r.length;ByteBuffer b=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);b.putInt(rows.length);int p=4+4*rows.length;for(byte[] r:rows){b.putInt(p);p+=r.length;}for(byte[] r:rows)b.put(r);return b.array();}
    static byte[] table(int count,int stride){ByteBuffer b=ByteBuffer.allocate(32+count*stride).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0,0x65327473).putInt(4,count).putInt(8,stride).putInt(12,32);for(int i=0;i<count;i++){b.putShort(32+i*stride,(short)(count-1-i));if(stride>6)b.putShort(38+i*stride,(short)((i%2==0)?0x2000:0));}return b.array();}
    static BattlePack pack(byte[] statuses,byte[] elements,boolean missing)throws Exception {byte[][] rows=new byte[62][];Arrays.fill(rows,new byte[0]);rows[15]=statuses;rows[41]=elements;byte[][] text=new byte[32][];for(int i=0;i<32;i++)text[i]=new byte[]{0,0,(byte)(0x20+i%26),0};if(missing)text[31]=new byte[]{0};rows[61]=sections(new byte[][]{sections(text)});return new BattlePack(sections(rows));}
    interface Body{void run()throws Exception;}
    static void refuses(Body body)throws Exception{try{body.run();throw new AssertionError("accepted bad table");}catch(IOException expected){n++;}}
    public static void main(String[] args)throws Exception {
        byte[] s=table(32,40),e=table(8,2);BattlePack p=pack(s,e,false);
        check(ReferenceStep.statusNames(p).size()==32);check(ReferenceStep.elementNames(p).size()==8);
        check(ReferenceStep.statusNames(p).get("0").equals(p.text(31)));check(ReferenceStep.statusNames(p).get("31").equals(p.text(0)));
        check(Boolean.TRUE.equals(((Map<?,?>)ReferenceStep.statusDisplay(p).get("0")).get("adverse")));check(Boolean.FALSE.equals(((Map<?,?>)ReferenceStep.statusDisplay(p).get("1")).get("adverse")));
        refuses(()->ReferenceStep.statusNames(pack(Arrays.copyOf(s,s.length-1),e,false)));
        refuses(()->ReferenceStep.statusNames(pack(table(31,40),e,false)));
        refuses(()->ReferenceStep.statusNames(pack(table(32,39),e,false)));
        byte[] bad=s.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(12,16);refuses(()->ReferenceStep.statusNames(pack(bad,e,false)));
        refuses(()->ReferenceStep.statusNames(pack(s,e,true)));
        refuses(()->ReferenceStep.elementNames(pack(s,table(7,2),false)));
        refuses(()->ReferenceStep.elementNames(pack(s,Arrays.copyOf(e,47),false)));
        System.out.println("PASS status tables "+n+" checks");
    }
}
