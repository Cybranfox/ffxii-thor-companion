package local.thor.ffxiicompanion.data;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded SRYK directory parser. Header only; archive size supplied by trusted receipt. */
public final class VbfIndex {
    public static final int MAX_HEADER = 64*1024*1024, MAX_ENTRIES = 500000;
    public static final class Entry {
        public final String name;
        public final long size, offset, storedSize;
        private final int[] codes;
        Entry(String n,long s,long o,int[] c,long physical) { name=n;size=s;offset=o;codes=c;storedSize=physical; }
        public int[] blockCodes() { return codes.clone(); }
    }
    private final Map<String,Entry> entries;
    public final long archiveSize;
    private VbfIndex(Map<String,Entry> e,long s) { entries=Collections.unmodifiableMap(e);archiveSize=s; }
    public Entry find(String name) { return entries.get(name); }
    public Set<String> names() { return entries.keySet(); }
    public int size() { return entries.size(); }
    private static void require(boolean ok,String reason) throws IOException { if(!ok)throw new IOException(reason); }
    private static long u32(ByteBuffer b,int at) { return Integer.toUnsignedLong(b.getInt(at)); }
    public static VbfIndex parse(byte[] header,long archiveSize) throws IOException {
        require(header.length>=20 && header.length<=MAX_HEADER && archiveSize>=header.length,"header length");
        ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        require(b.getInt(0)==0x4b595253,"magic");
        long end=u32(b,4), count=b.getLong(8);
        require(end==header.length && count>=0 && count<=MAX_ENTRIES && 20+48*count<=end,"directory extent");
        int rows=(int)(16+16*count), namesBase=(int)(16+48*count);
        long namesLength=u32(b,namesBase);
        require(namesLength>=4 && namesLength<=end-namesBase,"names extent");
        int namesStart=namesBase+4, namesEnd=(int)(namesBase+namesLength), blocksStart=namesEnd;
        require(((header.length-blocksStart)&1)==0,"block extent");
        int blockCount=(header.length-blocksStart)/2;
        Map<String,Entry> result=new HashMap<>();
        CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        long referenceCount=0;
        for(int i=0;i<(int)count;i++) {
            int at=rows+32*i;long first=u32(b,at),length=b.getLong(at+8),offset=b.getLong(at+16),no=b.getLong(at+24);
            require(length>=0 && length<=archiveSize && offset>=0 && no>=0 && no<namesEnd-namesStart,"entry integers");
            int p=namesStart+(int)no,z=p,scanEnd=p+Math.min(1025,namesEnd-p);
            while(z<scanEnd && header[z]!=0)z++;
            require(z>p && z<namesEnd && z-p<=1024,"terminated bounded nonempty name");
            String name;
            try { name=decoder.decode(ByteBuffer.wrap(header,p,z-p)).toString(); } catch(CharacterCodingException ex) { throw new IOException("name UTF8",ex); }
            List<String> segments=Arrays.asList(name.split("/",-1));
            require(!name.startsWith("/") && !name.contains("\\") && !name.contains(":") && !segments.contains("..") && !segments.contains(".") && !segments.contains(""),"unsafe name");
            long n=length/65536+(length%65536==0?0:1);
            require(n<=blockCount && first<=blockCount-n,"block span");
            require(n<=blockCount-referenceCount,"aggregate block reference budget");
            referenceCount+=n;
            int[] codes=new int[(int)n];long stored=0;
            for(int j=0;j<codes.length;j++) { int c=Short.toUnsignedInt(b.getShort(blocksStart+2*(int)(first+j)));codes[j]=c;stored+=c==0?65536:c; }
            require(length==0 || (offset>=end && offset<=archiveSize && stored<=archiveSize-offset),"archive span");
            require(!result.containsKey(name),"duplicate name");
            result.put(name,new Entry(name,length,offset,codes,stored));
        }
        return new VbfIndex(result,archiveSize);
    }

    /** FFXII_TZA.vbf's own layout: the entries' stored data, then a 16-byte footer (the MD5 of the header). */
    public static final int FOOTER=16;

    /**
     * The index plus the archive's size from the header alone (the raw copy has the header, not the file's size):
     * the end of the last entry's stored data, plus the footer. True for the shipped archive; a repacked one with
     * extra unreferenced bytes would read as smaller, which only changes the size the atlas records.
     */
    public static VbfIndex parseCanonical(byte[] header) throws IOException {
        VbfIndex open=parse(header,Long.MAX_VALUE/2);
        long end=header.length;
        for(Entry e:open.entries.values())if(e.size>0)end=Math.max(end,e.offset+e.storedSize);
        return new VbfIndex(open.entries,end+FOOTER);
    }
}
