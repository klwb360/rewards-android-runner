package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Strict, streaming PNG subset for screencap stdout. No Android, file or process API. */
final class BoundedPngGray {
    static final class Cancelled extends IOException { private static final long serialVersionUID=1L; }
    enum Stage { NOT_STARTED, SIGNATURE, IHDR, FORMAT, CHUNK_HEADER, CHUNK_CRC, IDAT, ROW_FILTER, PIPE_READ, EOF, DEADLINE, CANCELLED, COMPLETE }
    enum Chunk { NONE, IHDR, IDAT, IEND, SRGB, GAMA, CHRM, PHYS, SBIT, OTHER }
    /** Only safe bounded numeric/enum evidence, never PNG bytes or chunk text. */
    record Diagnostic(Stage stage,long width,long height,int bitDepth,int colorType,Chunk chunk,long chunkCode) { }
    static final int MAX_ENCODED_BYTES = 18 * 1024 * 1024;
    static final int MAX_DIMENSION = 4096, MAX_PIXELS = 4 * 1024 * 1024;
    static final long MAX_BUDGET_NANOS = 5_000_000_000L;
    private static final byte[] SIGNATURE = {(byte)137,80,78,71,13,10,26,10};
    interface Input {
        /** Read at most len bytes, -1 EOF, 0 timeout. Must return within waitMillis.
         * Android implementation uses poll + O_NONBLOCK, never InputStream.available(). */
        int read(byte[] bytes, int off, int len, int waitMillis) throws Exception;
    }
    interface Sink { void begin(int width, int height); void row(byte[] bytes); }
    interface Stop { boolean requested(); }
    record Geometry(int width, int height) {
        Geometry {
            if(width<=0 || height<=0 || width>MAX_DIMENSION || height>MAX_DIMENSION
                    || (long)width*height>MAX_PIXELS) throw new IllegalArgumentException("geometry");
        }
    }
    record Receipt(Geometry geometry, long encodedBytes, int rows) { }

    private final Input input;
    private final Sink sink;
    private final Stop stop;
    private final LongSupplier clock;
    private final long started, budget;
    private final Geometry expected;
    private final byte[] header = new byte[13], io = new byte[8192], small = new byte[8];
    private byte[] scanline, previous, gray;
    private Inflater inflater;
    private long total;
    private int bpp, rowFill, rows;
    private boolean idat, idatEnded;
    private Stage stage=Stage.NOT_STARTED;
    private Chunk chunk=Chunk.NONE;
    private long reportedWidth=-1,reportedHeight=-1,chunkCode=-1;
    private int bitDepth=-1,colorType=-1;
    Diagnostic diagnostic(){return new Diagnostic(stage,reportedWidth,reportedHeight,bitDepth,colorType,chunk,chunkCode);}

    BoundedPngGray(Input input, Sink sink, Stop stop, LongSupplier clock,
            long started, long budget, Geometry expected) {
        if(input==null || sink==null || stop==null || clock==null || expected==null
                || budget<=0 || budget>MAX_BUDGET_NANOS) throw new IllegalArgumentException();
        this.input=input;this.sink=sink;this.stop=stop;this.clock=clock;
        this.started=started;this.budget=budget;this.expected=expected;
    }
    private void check() throws IOException {
        long elapsed=clock.getAsLong()-started;
        if(elapsed<0 || elapsed>=budget){stage=Stage.DEADLINE;throw new IOException("deadline");}
        if(stop.requested()){stage=Stage.CANCELLED;throw new Cancelled();}
    }
    private int read(byte[] out,int offset,int length) throws Exception {
        for(;;) {
            check();
            if(total>=MAX_ENCODED_BYTES) throw new IOException("encoded limit");
            int boundedLength=(int)Math.min(length,MAX_ENCODED_BYTES-total);
            long left=budget-(clock.getAsLong()-started);
            int wait=(int)Math.max(1,Math.min(100,(left+999_999)/1_000_000));
            int n;
            try{n=input.read(out,offset,boundedLength,wait);}
            catch(Exception failed){stage=Stage.PIPE_READ;throw failed;}
            check();
            if(n < -1 || n>boundedLength) throw new IOException("invalid pipe read");
            if(n==0) continue;
            if(n>0 && (total+=n)>MAX_ENCODED_BYTES) throw new IOException("encoded limit");
            return n;
        }
    }
    private void exact(byte[] out,int offset,int length) throws Exception {
        while(length>0) {
            int n=read(out,offset,length);
            if(n<0){stage=Stage.EOF;throw new IOException("truncated PNG");}
            offset+=n;length-=n;
        }
    }
    private static long u32(byte[] a,int p) {
        return ((long)(a[p]&255)<<24)|((long)(a[p+1]&255)<<16)|((long)(a[p+2]&255)<<8)|(a[p+3]&255);
    }
    private int length() throws Exception {
        stage=Stage.CHUNK_HEADER;
        exact(small,0,8);
        chunkCode=u32(small,4);
        chunk=type("IHDR")?Chunk.IHDR:type("IDAT")?Chunk.IDAT:type("IEND")?Chunk.IEND:type("sRGB")?Chunk.SRGB:
                type("gAMA")?Chunk.GAMA:type("cHRM")?Chunk.CHRM:type("pHYs")?Chunk.PHYS:type("sBIT")?Chunk.SBIT:Chunk.OTHER;
        long n=u32(small,0);
        if(n>MAX_ENCODED_BYTES || n>MAX_ENCODED_BYTES-total-4L) throw new IOException("chunk limit");
        for(int i=4;i<8;i++) if(!((small[i]>='A'&&small[i]<='Z')||(small[i]>='a'&&small[i]<='z')))
            throw new IOException("chunk type");
        return (int)n;
    }
    private boolean type(String type) {
        for(int i=0;i<4;i++) if(small[4+i]!=type.charAt(i)) return false;
        return true;
    }
    private CRC32 crc() { CRC32 c=new CRC32();c.update(small,4,4);return c; }
    private void crcEnd(CRC32 c) throws Exception {
        stage=Stage.CHUNK_CRC;
        exact(small,0,4);
        if(c.getValue()!=u32(small,0)) throw new IOException("PNG CRC");
    }
    Receipt decode() throws Exception {
        try {
            stage=Stage.SIGNATURE;
            exact(small,0,8);
            if(!Arrays.equals(small,SIGNATURE)) throw new IOException("PNG signature");
            int n=length();
            if(n!=13 || !type("IHDR")) throw new IOException("IHDR required");
            stage=Stage.IHDR;
            CRC32 c=crc();exact(header,0,13);c.update(header);
            reportedWidth=u32(header,0);reportedHeight=u32(header,4);bitDepth=header[8]&255;colorType=header[9]&255;
            crcEnd(c);
            long width=u32(header,0),height=u32(header,4);
            stage=Stage.FORMAT;
            if(width!=expected.width || height!=expected.height) throw new IOException("geometry changed");
            // Only 8-bit, true-color RGB/RGBA, zlib, standard filter, noninterlaced.
            if(header[8]!=8 || (header[9]!=2 && header[9]!=6)
                    || header[10]!=0 || header[11]!=0 || header[12]!=0) throw new IOException("PNG format");
            bpp=header[9]==2?3:4;
            scanline=new byte[expected.width*bpp+1];
            previous=new byte[scanline.length-1];gray=new byte[expected.width];
            inflater=new Inflater();sink.begin(expected.width,expected.height);
            int chunks=0;
            for(;;) {
                check();
                if(++chunks>4096) throw new IOException("chunk count");
                n=length();c=crc();
                if(type("IDAT")) {
                    stage=Stage.IDAT;
                    if(idatEnded) throw new IOException("nonconsecutive IDAT");
                    idat=true;
                    for(int remaining=n;remaining>0;) {
                        check();int take=Math.min(remaining,io.length);exact(io,0,take);c.update(io,0,take);
                        if(inflater.finished() || !inflater.needsInput()) throw new IOException("extra zlib bytes");
                        inflater.setInput(io,0,take);inflate();remaining-=take;
                        Arrays.fill(io,0,take,(byte)0);
                    }
                    crcEnd(c);
                } else if(type("IEND")) {
                    if(n!=0 || !idat || !inflater.finished() || rows!=expected.height || rowFill!=0)
                        throw new IOException("incomplete PNG");
                    crcEnd(c);
                    stage=Stage.EOF;
                    if(read(io,0,1)!=-1) throw new IOException("trailing PNG data");
                    stage=Stage.COMPLETE;
                    return new Receipt(expected,total,rows);
                } else {
                    if(idat) idatEnded=true;
                    // Deliberately small accepted screencap ancillary subset. No text,
                    // palette, ICC compressed metadata, APNG or unknown chunk fallback.
                    boolean accepted=!idat && ((type("sRGB")&&n==1) || (type("gAMA")&&n==4)
                            || (type("cHRM")&&n==32) || (type("pHYs")&&n==9)
                            || (type("sBIT")&&n==bpp));
                    if(!accepted) throw new IOException("unsupported PNG chunk");
                    exact(io,0,n);c.update(io,0,n);crcEnd(c);Arrays.fill(io,0,n,(byte)0);
                }
            }
        } finally {
            if(inflater!=null) inflater.end();
            Arrays.fill(io,(byte)0);Arrays.fill(header,(byte)0);Arrays.fill(small,(byte)0);
            if(scanline!=null) Arrays.fill(scanline,(byte)0);
            if(previous!=null) Arrays.fill(previous,(byte)0);
            if(gray!=null) Arrays.fill(gray,(byte)0);
        }
    }
    private void inflate() throws IOException, DataFormatException {
        while(!inflater.needsInput() && !inflater.finished()) {
            check();
            // One byte is enough to detect decompression beyond the admitted rows.
            int room=rows==expected.height?1:scanline.length-rowFill;
            int n=inflater.inflate(scanline,rowFill,room);
            if(n>0) {
                if(rows==expected.height) throw new IOException("inflated limit");
                rowFill+=n;
                if(rowFill==scanline.length) { row();rowFill=0;rows++; }
            } else if(inflater.needsDictionary() || (!inflater.needsInput()&&!inflater.finished()))
                throw new IOException("zlib stalled/dictionary");
        }
        if(inflater.finished() && inflater.getRemaining()!=0) throw new IOException("extra zlib bytes");
    }
    private static int paeth(int a,int b,int c) {
        int p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);
        return pa<=pb&&pa<=pc?a:pb<=pc?b:c;
    }
    private void row() throws IOException {
        stage=Stage.ROW_FILTER;
        check();int filter=scanline[0]&255;
        if(filter>4) throw new IOException("PNG filter");
        for(int x=0;x<previous.length;x++) {
            int a=x>=bpp?scanline[1+x-bpp]&255:0,b=previous[x]&255,c=x>=bpp?previous[x-bpp]&255:0;
            int prediction=switch(filter){case 0->0;case 1->a;case 2->b;case 3->(a+b)/2;default->paeth(a,b,c);};
            scanline[x+1]=(byte)((scanline[x+1]&255)+prediction);
        }
        for(int x=0;x<expected.width;x++) {
            int p=1+x*bpp,r=scanline[p]&255,g=scanline[p+1]&255,b=scanline[p+2]&255;
            if(bpp==4 && (scanline[p+3]&255)!=255) throw new IOException("nonopaque RGBA");
            gray[x]=(byte)((77*r+150*g+29*b+128)>>>8);
        }
        sink.row(gray);System.arraycopy(scanline,1,previous,0,previous.length);
        Arrays.fill(scanline,(byte)0);Arrays.fill(gray,(byte)0);
    }
}
