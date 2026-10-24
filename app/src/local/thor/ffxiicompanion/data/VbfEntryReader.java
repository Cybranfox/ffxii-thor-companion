package local.thor.ffxiicompanion.data;

import java.io.*;
import java.util.zip.*;

/** Reads one captured stored entry, never the full archive. Does not close caller streams. */
public final class VbfEntryReader {
    public static final long MAX_ENTRY=64L*1024*1024;
    public static void inflate(InputStream source,VbfIndex.Entry entry,OutputStream target) throws IOException {
        if(entry.size>MAX_ENTRY)throw new IOException("entry exceeds cap");
        byte[] stored=new byte[65536], decoded=new byte[65537];long remaining=entry.size;
        for(int code:entry.blockCodes()) {
            int n=code==0?65536:code,want=(int)Math.min(65536,remaining),p=0;
            while(p<n) { int r=source.read(stored,p,n-p);if(r<0)throw new EOFException("stored block");if(r==0)throw new IOException("no read progress");p+=r; }
            if(code==0 || code==want)target.write(stored,0,want);
            else {
                Inflater inf=new Inflater();
                try {
                    inf.setInput(stored,0,n);int got=0;
                    while(!inf.finished() && got<=want) {
                        int r=inf.inflate(decoded,got,want+1-got);got+=r;
                        if(r==0 && !inf.finished())throw new IOException("zlib no progress/dictionary/truncation");
                    }
                    if(got!=want || !inf.finished() || inf.getRemaining()!=0)throw new IOException("zlib extent");
                    target.write(decoded,0,got);
                } catch(DataFormatException ex) { throw new IOException("zlib malformed",ex); }
                finally { inf.end(); }
            }
            remaining-=want;
        }
        if(remaining!=0 || source.read()!=-1)throw new IOException("stored extent");
    }
}
