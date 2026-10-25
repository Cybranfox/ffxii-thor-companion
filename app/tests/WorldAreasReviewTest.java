package local.thor.ffxiicompanion.data;
import java.util.*;
/** Synthetic cmap high-byte flags must not become part of the PLMN region id. */
public final class WorldAreasReviewTest {
    static void u16(byte[] b,int o,int v){b[o]=(byte)v;b[o+1]=(byte)(v>>8);}
    static void u32(byte[] b,int o,int v){u16(b,o,v);u16(b,o+2,v>>16);}
    public static void main(String[] args) throws Exception {
        byte[] a=new byte[84],c=new byte[32],p=new byte[122];
        System.arraycopy(new byte[]{'a','m','a','p'},0,a,0,4);
        u16(a,10,16);u16(a,14,72);a[16]=53;a[17+43]=1;a[17+52]=1;
        u32(a,72,2);u32(a,80,60100);
        System.arraycopy(new byte[]{'c','m','a','p'},0,c,0,4);u32(c,12,2);u16(c,16,0x12b);u16(c,24,0x234);
        System.arraycopy(new byte[]{'P','L','M','N'},0,p,0,4);u32(p,8,53);u32(p,12,53);
        List<Object> rows=ObjectivesStep.worldAreas(Arrays.asList(1),a,c,p,Collections.singletonMap(60100,"Synthetic"),Collections.emptyMap());
        List<?> regions=(List<?>)((Map<?,?>)rows.get(0)).get("regions");
        if(regions.size()!=2 || !"sub00".equals(((Map<?,?>)regions.get(0)).get("region"))
                || !"sub01".equals(((Map<?,?>)regions.get(1)).get("region")))throw new AssertionError("low byte");
        System.out.println("PASS worldAreas: 0x12b -> 43, 0x234 -> 52");
        for(byte[] bad:new byte[][]{new byte[0],Arrays.copyOf(c,25)}) {
            try {ObjectivesStep.worldAreas(Arrays.asList(1),a,bad,p,Collections.emptyMap(),Collections.emptyMap());throw new AssertionError("accepted truncated cmap");}
            catch(IllegalArgumentException expected){}
        }
        System.out.println("PASS worldAreas: truncated cmap header and rows refused");
    }
}
