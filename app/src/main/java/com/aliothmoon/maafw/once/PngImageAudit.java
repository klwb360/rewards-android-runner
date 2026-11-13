package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.LongSupplier;

/** Diagnostic-only same-PNG audit. No capture, path, actor or authority API. */
final class PngImageAudit {
    enum Status { NOT_INVOKED, MATCH, DIFFERENCE, FAILED, CANCELLED, DEADLINE, UNKNOWN }
    enum Stage { NOT_STARTED, ALLOCATE, TEE, FIRST_GRAY, SOURCE_QUIET, STANDARD_DECODE, STANDARD_ROWS, REPLAY, CLEANUP, BITMAP_ERASE, BITMAP_RECYCLE, CLOSED }
    enum Cleanup { NOT_INVOKED, CONFIRMED, UNKNOWN }
    enum Config { ARGB_8888, HARDWARE, OTHER, UNKNOWN }
    record Facts(int width,int height,Config config,int colorSpaceId,boolean mutable,
            boolean hasAlpha,boolean premultiplied,boolean sRgb,boolean wideGamut) { }
    interface Image {
        Facts facts() throws Exception;
        void argbRow(int y,int[] row) throws Exception;
        void erase() throws Exception;
        void recycle() throws Exception;
    }
    interface Decoder { Image decode(byte[] encoded,int length) throws Exception; }
    static Evidence notInvoked(){return new Evidence(Status.NOT_INVOKED,Stage.NOT_STARTED,Cleanup.NOT_INVOKED,0,0,0,-1,-1,"","","",-1,-1,false,0,null);}
    /** Private constructor: read-only scalars/hashes, never PNG/Bitmap/row getters. */
    static final class Evidence {
        private final Status status;private final Stage stage;private final Cleanup cleanup;
        private final int width,height,encodedBytes,bitDepth,colorType,maxDiff;
        private final String sourceHash,replayHash,standardHash;private final long diffCount,alphaAnomalies;
        private final boolean firstReplayEqual;private final Facts facts;
        private Evidence(Status s,Stage st,Cleanup c,int w,int h,int bytes,int depth,int type,String first,String replay,String standard,
                long count,int max,boolean equal,long alpha,Facts facts){status=s;stage=st;cleanup=c;width=w;height=h;encodedBytes=bytes;
            bitDepth=depth;colorType=type;sourceHash=first;replayHash=replay;standardHash=standard;diffCount=count;maxDiff=max;firstReplayEqual=equal;alphaAnomalies=alpha;this.facts=facts;}
        Status status(){return status;}Stage stage(){return stage;}Cleanup cleanup(){return cleanup;}
        boolean isCleanupConfirmed(){return cleanup==Cleanup.CONFIRMED;}
        int width(){return width;}int height(){return height;}int encodedBytes(){return encodedBytes;}
        int bitDepth(){return bitDepth;}int colorType(){return colorType;}
        String sourceSha256(){return sourceHash;}String replaySha256(){return replayHash;}String standardSha256(){return standardHash;}
        long diffCount(){return diffCount;}int maxDiff(){return maxDiff;}boolean firstReplayEqual(){return firstReplayEqual;}
        Facts platformFacts(){return facts;}
        String safeSummary(){return "imageAudit status="+status+" stage="+stage+" cleanup="+cleanup+" frame="+width+"x"+height
                +" bytes="+encodedBytes+" bitDepth="+bitDepth+" colorType="+colorType+" firstReplayEqual="+firstReplayEqual
                +" diffCount="+diffCount+" maxDiff="+maxDiff+" alphaAnomalies="+alphaAnomalies
                +" sourceHash="+sourceHash+" replayHash="+replayHash+" standardHash="+standardHash+" platform="+facts
                +" platformScratchZeroization=NOT_PROVEN";}
    }
    private static final Set<PngImageAudit> HELD=Collections.newSetFromMap(new IdentityHashMap<>());
    private final BoundedPngGray.Geometry shape;private final Decoder decoder;private final LongSupplier clock;
    private OnceBackend.Owner owner;private BackendPorts.StopSignal stop;
    private byte[] encoded,standardGray;private int[] argb;
    private int count,firstRows,replayRows,bitDepth=-1,colorType=-1,maxDiff;
    private long started,diffCount,alphaAnomalies;private Image image;private Facts facts;
    private MessageDigest firstDigest,replayDigest,standardDigest;
    private String firstHash="",replayHash="",standardHash="";
    private boolean eof,teeIssued,forwardIssued,active,nativeActive,creationUncertain,closeAttempted,closed,held,replayComplete,equal;
    private Status status=Status.NOT_INVOKED;private Stage stage=Stage.NOT_STARTED;
    PngImageAudit(BoundedPngGray.Geometry shape,Decoder decoder,LongSupplier clock){
        if(shape==null||decoder==null||clock==null)throw new IllegalArgumentException();this.shape=shape;this.decoder=decoder;this.clock=clock;
    }
    void begin(OnceBackend.Owner exact,BackendPorts.StopSignal stop)throws Exception{
        synchronized(this){if(owner!=null||exact==null||stop==null)throw new IllegalStateException();owner=exact;this.stop=stop;active=true;stage=Stage.ALLOCATE;status=Status.FAILED;}
        synchronized(HELD){if(!HELD.isEmpty())throw new IOException("Image audit quarantined");HELD.add(this);held=true;}
        started=clock.getAsLong();check();encoded=new byte[BoundedPngGray.MAX_ENCODED_BYTES];
        firstDigest=MessageDigest.getInstance("SHA-256");replayDigest=MessageDigest.getInstance("SHA-256");standardDigest=MessageDigest.getInstance("SHA-256");
    }
    private void check()throws IOException{
        long elapsed=clock.getAsLong()-started;
        if(elapsed<0||elapsed>=BoundedPngGray.MAX_BUDGET_NANOS){status=Status.DEADLINE;throw new IOException("Image audit deadline");}
        if(stop.requested()){status=Status.CANCELLED;throw new BackendPorts.Cancelled();}
    }
    BoundedPngGray.Input tee(BoundedPngGray.Input original){
        if(teeIssued||encoded==null||original==null)throw new IllegalStateException();teeIssued=true;
        return (bytes,offset,length,wait)->{
            check();stage=Stage.TEE;
            int n=original.read(bytes,offset,length,wait);
            if(n< -1||n>length||n>encoded.length-count)throw new IOException("Audit encoded bounds");
            if(n>0){System.arraycopy(bytes,offset,encoded,count,n);count+=n;}else if(n==-1)eof=true;
            check();return n;
        };
    }
    BoundedPngGray.Sink forward(BoundedPngGray.Sink actual){
        if(forwardIssued||actual==null||firstDigest==null)throw new IllegalStateException();forwardIssued=true;
        return new BoundedPngGray.Sink(){
            public void begin(int width,int height){if(width!=shape.width()||height!=shape.height())throw new IllegalArgumentException();actual.begin(width,height);}
            public void row(byte[] row){
                if(row.length!=shape.width()||firstRows>=shape.height())throw new IllegalStateException();
                stage=Stage.FIRST_GRAY;actual.row(row);firstDigest.update(row);firstRows++;
            }
        };
    }
    void compare(PngCaptureSession actual,BoundedPngGray.Receipt receipt)throws Exception{
        try{
            stage=Stage.SOURCE_QUIET;check();var s=actual.snapshot();
            bitDepth=s.png().bitDepth();colorType=s.png().colorType();
            if(!actual.quietAndReleased()||!s.childExitObserved()||!s.exitStatusObserved()||s.childExitStatus()!=0
                    ||receipt==null||receipt.encodedBytes()!=count||!eof||firstRows!=shape.height())throw new IOException("Actual source not closed");
            firstHash=finish(firstDigest);stage=Stage.STANDARD_DECODE;
            creationUncertain=true;nativeActive=true;
            try{image=decoder.decode(encoded,count);creationUncertain=false;}finally{nativeActive=false;}
            check();if(image==null)throw new IOException("Standard decode null");
            facts=image.facts();
            if(facts==null||facts.width()!=shape.width()||facts.height()!=shape.height()||facts.config()!=Config.ARGB_8888||!facts.mutable())
                throw new IOException("Standard bitmap format");
            argb=new int[shape.width()];standardGray=new byte[shape.width()];
            final int[] position={0};stage=Stage.REPLAY;
            BoundedPngGray.Input replay=(bytes,offset,length,wait)->{if(position[0]==count)return -1;int n=Math.min(length,count-position[0]);System.arraycopy(encoded,position[0],bytes,offset,n);position[0]+=n;return n;};
            BoundedPngGray.Sink rows=new BoundedPngGray.Sink(){
                public void begin(int w,int h){if(w!=shape.width()||h!=shape.height())throw new IllegalStateException();}
                public void row(byte[] row){
                    try{
                        check();stage=Stage.STANDARD_ROWS;nativeActive=true;
                        try{image.argbRow(replayRows,argb);}finally{nativeActive=false;}
                        check();
                        for(int x=0;x<argb.length;x++){
                            int c=argb[x];if((c>>>24)!=255)alphaAnomalies++;
                            int gray=(77*((c>>>16)&255)+150*((c>>>8)&255)+29*(c&255)+128)>>>8;
                            standardGray[x]=(byte)gray;int diff=Math.abs(gray-(row[x]&255));if(diff!=0)diffCount++;maxDiff=Math.max(maxDiff,diff);
                        }
                        replayDigest.update(row);standardDigest.update(standardGray);replayRows++;stage=Stage.REPLAY;
                    }catch(Exception failed){throw new RowFailure();}
                    finally{Arrays.fill(argb,0);Arrays.fill(standardGray,(byte)0);}
                }
            };
            new BoundedPngGray(replay,rows,()->stop.requested(),clock,started,BoundedPngGray.MAX_BUDGET_NANOS,shape).decode();
            check();if(replayRows!=shape.height()||alphaAnomalies!=0)throw new IOException("Standard rows/alpha");
            replayHash=finish(replayDigest);standardHash=finish(standardDigest);equal=firstHash.equals(replayHash);replayComplete=true;
            status=equal&&diffCount==0?Status.MATCH:Status.DIFFERENCE;
        }catch(Exception failed){if(status!=Status.CANCELLED&&status!=Status.DEADLINE)status=creationUncertain?Status.UNKNOWN:Status.FAILED;throw failed;}
    }
    private static final class RowFailure extends RuntimeException{private static final long serialVersionUID=1L;RowFailure(){super(null,null,false,false);}}
    private static String finish(MessageDigest digest){byte[] bytes=digest.digest();char[] chars=new char[64];char[] hex="0123456789abcdef".toCharArray();
        try{for(int i=0;i<bytes.length;i++){chars[2*i]=hex[(bytes[i]>>>4)&15];chars[2*i+1]=hex[bytes[i]&15];}return new String(chars);}
        finally{Arrays.fill(bytes,(byte)0);Arrays.fill(chars,'\0');Arrays.fill(hex,'\0');}}
    void closeOnce(){
        if(closeAttempted)throw new IllegalStateException("No audit cleanup retry");closeAttempted=true;
        boolean clean=!nativeActive&&!creationUncertain;Stage failureStage=stage;
        if(nativeActive){status=Status.UNKNOWN;active=false;return;}
        if(argb!=null)Arrays.fill(argb,0);if(standardGray!=null)Arrays.fill(standardGray,(byte)0);
        if(image!=null){
            try{image.erase();}catch(Exception failed){clean=false;failureStage=Stage.BITMAP_ERASE;}
            try{image.recycle();}catch(Exception failed){clean=false;if(failureStage!=Stage.BITMAP_ERASE)failureStage=Stage.BITMAP_RECYCLE;}
            if(clean)image=null;
        }
        if(encoded!=null)Arrays.fill(encoded,(byte)0);
        if(firstDigest!=null)firstDigest.reset();if(replayDigest!=null)replayDigest.reset();if(standardDigest!=null)standardDigest.reset();
        if(clean&&(status==Status.MATCH||status==Status.DIFFERENCE)){
            long elapsed=clock.getAsLong()-started;
            if(elapsed<0||elapsed>=BoundedPngGray.MAX_BUDGET_NANOS)status=Status.DEADLINE;
            else if(stop.requested())status=Status.CANCELLED;
        }
        synchronized(this){active=false;closed=clean;if(!clean)status=Status.UNKNOWN;stage=clean&&(status==Status.MATCH||status==Status.DIFFERENCE)?Stage.CLOSED:failureStage;}
        if(clean&&held)synchronized(HELD){HELD.remove(this);held=false;}
    }
    synchronized boolean quietAndReleased(OnceBackend.Owner exact){return exact!=null&&exact==owner&&closeAttempted&&closed&&!active&&!nativeActive&&!creationUncertain;}
    synchronized Evidence evidence(){
        return new Evidence(status,stage,closeAttempted?(closed?Cleanup.CONFIRMED:Cleanup.UNKNOWN):owner==null?Cleanup.NOT_INVOKED:Cleanup.UNKNOWN,
                shape.width(),shape.height(),count,bitDepth,colorType,firstHash,replayHash,standardHash,
                replayComplete?diffCount:-1,replayComplete?maxDiff:-1,replayComplete&&equal,alphaAnomalies,facts);
    }
}
