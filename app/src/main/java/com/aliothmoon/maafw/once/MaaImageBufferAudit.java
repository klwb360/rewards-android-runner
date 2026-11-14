package com.aliothmoon.maafw.once;

import com.sun.jna.Pointer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;

/** Same-worker diagnostic of this exact SetEncoded-created CV_8UC3 buffer only.
 * Not capture, OCR, image export, task identity, readiness or input authority.
 * Borrowed native pointers are never written, freed, or retained in results.
 * The v5.15 ImageBuffer SetEncoded path creates an imdecode COLOR Mat; its raw
 * representation is packed BGR. This is not a generic Mat/ROI stride reader. */
final class MaaImageBufferAudit {
    public interface ImageApi {
        Pointer MaaImageBufferGetRawData(Pointer image);
        int MaaImageBufferWidth(Pointer image);
        int MaaImageBufferHeight(Pointer image);
        int MaaImageBufferChannels(Pointer image);
        int MaaImageBufferType(Pointer image);
    }
    enum Status { NOT_RUN, READING, VERIFIED_EQUAL, PIXEL_MISMATCH, FORMAT_REJECTED,
        IDENTITY_REJECTED, CANCELED, UNKNOWN }
    record Sample(Status status,int width,int height,int channels,int cvType,long comparedPixels,
                  long mismatchedPixels,long mismatchedChannels,int maxAbsDiff,boolean expectedBgrSha256Equal) {
        static Sample notRun(){return new Sample(Status.NOT_RUN,-1,-1,-1,-1,0,0,0,-1,false);}
        static Sample state(Status value){return new Sample(value,-1,-1,-1,-1,0,0,0,-1,false);}
    }
    record CvSample(boolean attempted,RewardRegionLocator.Status status,int cardTotal,
                    List<RewardRegionLocator.Box> fullFrameBoxes,boolean truncated){
        CvSample{fullFrameBoxes=List.copyOf(fullFrameBoxes);}
        static CvSample notRun(){return new CvSample(false,null,-1,List.of(),false);}
    }
    record OcrRowBox(int x,int y,int width,int height,double confidence) { }
    record OcrGeometry(boolean observed,int nativeRows,int reported,boolean truncated,List<OcrRowBox> originalXywh){
        OcrGeometry{originalXywh=List.copyOf(originalXywh);}
        static OcrGeometry notRun(){return new OcrGeometry(false,-1,0,false,List.of());}
        @Override public String toString(){
            StringBuilder s=new StringBuilder("{observed=").append(observed).append(" nativeRows=").append(nativeRows)
                .append(" reported=").append(reported).append(" truncated=").append(truncated)
                .append(" coordinateSpace=FULL_FRAME_XYWH rows=[");
            for(int i=0;i<originalXywh.size();i++){
                if(i>0)s.append(';');var r=originalXywh.get(i);
                s.append(r.x()).append(',').append(r.y()).append(',').append(r.width()).append(',').append(r.height())
                    .append('@').append(r.confidence());
            }
            return s.append("]}").toString();
        }
    }
    enum StatsStatus { NOT_RUN, OBSERVED, EMPTY_BAND, CANCELED, UNKNOWN }
    enum Check { NOT_OBSERVED, YES, NO }
    record BandStats(StatsStatus status,int sourceWidth,int sourceHeight,int left,int top,int right,int bottom,
                     long count,int min,int max,double mean,double populationVariance,long flatCount){
        static BandStats notRun(){return new BandStats(StatsStatus.NOT_RUN,-1,-1,0,0,0,0,0,-1,-1,-1,-1,0);}
        static BandStats state(StatsStatus state,int w,int h){return new BandStats(state,w,h,0,h/4,w,3*h/4,0,-1,-1,-1,-1,0);}
    }
    /** Two immutable private digest arrays only. No image/row/pointer or digest accessor. */
    static final class Fingerprint {
        private final int width,height;
        private final byte[] whole,middle;
        private Fingerprint(int w,int h,byte[] whole,byte[] middle){
            if(w<=0||h<=0||whole==null||middle==null||whole.length!=32||middle.length!=32)
                throw new IllegalArgumentException("fingerprint shape");
            width=w;height=h;this.whole=whole.clone();this.middle=middle.clone();
        }
        @Override public String toString(){return "PRIVATE_GRAY_DIGEST_PAIR";}
    }
    record Comparison(Check sameDimensions,Check wholeEqual,Check middleEqual) { }
    static Comparison compare(Fingerprint reference,Fingerprint current){
        if(reference==null||current==null)return new Comparison(Check.NOT_OBSERVED,Check.NOT_OBSERVED,Check.NOT_OBSERVED);
        if(reference.width!=current.width||reference.height!=current.height)
            return new Comparison(Check.NO,Check.NOT_OBSERVED,Check.NOT_OBSERVED);
        return new Comparison(Check.YES,MessageDigest.isEqual(reference.whole,current.whole)?Check.YES:Check.NO,
            MessageDigest.isEqual(reference.middle,current.middle)?Check.YES:Check.NO);
    }
    /** Compare privately; no native frame, row, or retained digest is exposed. */
    static boolean matchesGraySha256(Fingerprint actual,int width,int height,byte[] expected){
        return actual!=null&&actual.width==width&&actual.height==height
            &&expected!=null&&expected.length==32&&MessageDigest.isEqual(actual.whole,expected);
    }
    record Result(Status status,Sample afterSetEncoded,Sample afterQueryQuiet,CvSample sameFrameCv,OcrGeometry allOcrGeometry,
                  BandStats bandStats,Fingerprint fingerprint,int queryPosts,
                  boolean exactBindings,boolean positiveTaskBound,boolean queryQuiet,
                  boolean javaScratchWiped,boolean imageDestroyed,boolean taskerDestroyed,
                  boolean cleanupQualified) {
        Result(Status status,Sample before,Sample after,CvSample cv,OcrGeometry ocr,int posts,boolean exact,
               boolean task,boolean quiet,boolean scratch,boolean image,boolean tasker,boolean clean){
            this(status,before,after,cv,ocr,BandStats.notRun(),null,posts,exact,task,quiet,scratch,image,tasker,clean);
        }
        /** Opaque comparisons are unavailable for incomplete, canceled, malformed or unclean samples. */
        @Override public Fingerprint fingerprint(){
            return cleanupQualified&&(status==Status.VERIFIED_EQUAL||status==Status.PIXEL_MISMATCH)
                &&bandStats.status()==StatsStatus.OBSERVED?fingerprint:null;
        }
        /** Checkpoint-safe audit status only. Never serializes OCR rows or CV geometry. */
        String compactSummary(){return "MaaImageBufferAudit diagnosticOnly=true rowMetadata=SUPPRESSED cvGeometry=SUPPRESSED"
            +" sourceFramesAdded=0 ocrQueriesAdded=0 status="+status+" queryPosts="+queryPosts
            +" exactBindings="+exactBindings+" positiveTaskBound="+positiveTaskBound+" queryQuiet="+queryQuiet
            +" javaScratchWiped="+javaScratchWiped+" imageDestroyed="+imageDestroyed+" taskerDestroyed="+taskerDestroyed
            +" cleanupQualified="+cleanupQualified+" afterSetEncoded="+afterSetEncoded+" afterQueryQuiet="+afterQueryQuiet
            +" nativeScratchWipe=UNPROVEN cvInternalScratchWipe=UNPROVEN opaqueFingerprint="+(fingerprint()==null?"NOT_OBSERVED":"AVAILABLE_PRIVATE");}
        String summary(){return "MaaImageBufferAudit diagnosticOnly=true sourceFramesAdded=0 ocrQueriesAdded=0"
            +" nativeScratchWipe=UNPROVEN cvInternalScratchWipe=UNPROVEN ownScratch=ROW_HISTOGRAM_AND_TEMP_DIGEST_ARRAYS controllerCache=NOT_USED taskResultRaw=NOT_OBSERVED rawLayout=SET_ENCODED_PACKED_BGR8 status="+status
            +" queryPosts="+queryPosts+" exactBindings="+exactBindings+" positiveTaskBound="+positiveTaskBound
            +" queryQuiet="+queryQuiet+" javaScratchWiped="+javaScratchWiped+" imageDestroyed="+imageDestroyed
            +" taskerDestroyed="+taskerDestroyed+" cleanupQualified="+cleanupQualified
            +" afterSetEncoded="+afterSetEncoded+" afterQueryQuiet="+afterQueryQuiet
            +" sameFrameCvDiagnosticOnly="+sameFrameCv+" allOcrGeometryDiagnosticOnly="+allOcrGeometry
            +" bandStats="+bandStats+" opaqueFingerprint="+(fingerprint()==null?"NOT_OBSERVED":"AVAILABLE_PRIVATE");}
    }
    /** Each reference is compared by identity. Backend owner and request owner are distinct. */
    record Binding(Object recognizer,Object backendOwner,Object requestOwner,Object holder,
                   BackendPorts.FrameView frame,Pointer tasker,Pointer image) { }
    private final Binding expected;
    private final boolean seriesEnabled;
    private final int width,height;
    private final long threadId=Thread.currentThread().getId();
    private volatile Sample pre=Sample.notRun(),post=Sample.notRun();
    private volatile boolean exact=true,scratchClean=true,quiet;
    private volatile CvSample cv=CvSample.notRun();
    private volatile OcrGeometry ocr=OcrGeometry.notRun();
    private volatile BandStats stats=BandStats.notRun();
    private volatile Fingerprint fingerprint;
    private volatile int posts;
    private volatile long acceptedTask;
    private boolean beforeStarted,afterStarted;

    MaaImageBufferAudit(Binding binding){this(binding,false);}
    MaaImageBufferAudit(Binding binding,boolean seriesEnabled){
        this.seriesEnabled=seriesEnabled;
        if(binding==null||binding.recognizer()==null||binding.backendOwner()==null||binding.requestOwner()==null
                ||binding.holder()==null||binding.frame()==null||!pointer(binding.tasker())||!pointer(binding.image()))
            throw new IllegalArgumentException("audit binding required");
        expected=binding;width=binding.frame().width();height=binding.frame().height();
        if(width<=0||height<=0||width>4096||height>4096||(long)width*height>4L*1024*1024
                ||binding.frame().size()!=(long)width*height)throw new IllegalArgumentException("audit bounds");
    }
    void before(ImageApi api,Binding actual,BackendPorts.StopSignal stop)throws Exception{
        if(beforeStarted||afterStarted||posts!=0||!matches(actual)){
            exact=false;pre=Sample.state(Status.IDENTITY_REJECTED);return;
        }
        beforeStarted=true;pre=Sample.state(Status.READING);
        try{pre=read(api,stop);}
        catch(BackendPorts.Cancelled e){pre=Sample.state(Status.CANCELED);throw e;}
        catch(Exception|Error e){pre=Sample.state(Status.UNKNOWN);throw e;}
    }
    void posted(Binding actual,long task){
        posts++;
        if(!exact||!beforeStarted||posts!=1||afterStarted||task<=0||!matches(actual)){
            exact=false;return;
        }
        acceptedTask=task;
    }
    void after(ImageApi api,Binding actual,long task,boolean taskQuiet,BackendPorts.StopSignal stop)throws Exception{
        if(!exact||afterStarted||!beforeStarted||posts!=1||task<=0||task!=acceptedTask||!taskQuiet||!matches(actual)){
            exact=false;post=Sample.state(Status.IDENTITY_REJECTED);return;
        }
        afterStarted=true;quiet=true;post=Sample.state(Status.READING);
        try{post=read(api,stop);}
        catch(BackendPorts.Cancelled e){post=Sample.state(Status.CANCELED);throw e;}
        catch(Exception|Error e){post=Sample.state(Status.UNKNOWN);throw e;}
    }
    boolean scratchClean(){return scratchClean;}
    Result result(boolean released,boolean imageDestroyed,boolean taskerDestroyed){
        Sample a=pre,b=post;
        boolean qualified=released&&imageDestroyed&&taskerDestroyed&&scratchClean;
        Status status;
        if(!qualified)status=Status.UNKNOWN;
        else if(!exact)status=Status.IDENTITY_REJECTED;
        else if(a.status()==Status.NOT_RUN&&b.status()==Status.NOT_RUN)status=Status.NOT_RUN;
        else if(a.status()==Status.CANCELED||b.status()==Status.CANCELED)status=Status.CANCELED;
        else if(a.status()==Status.UNKNOWN||b.status()==Status.UNKNOWN||a.status()==Status.READING
                ||b.status()==Status.READING||a.status()==Status.NOT_RUN||b.status()==Status.NOT_RUN)status=Status.UNKNOWN;
        else if(a.status()==Status.FORMAT_REJECTED||b.status()==Status.FORMAT_REJECTED)status=Status.FORMAT_REJECTED;
        else if(a.status()==Status.PIXEL_MISMATCH||b.status()==Status.PIXEL_MISMATCH)status=Status.PIXEL_MISMATCH;
        else if(posts==1&&acceptedTask>0&&quiet&&a.status()==Status.VERIFIED_EQUAL&&b.status()==Status.VERIFIED_EQUAL)
            status=Status.VERIFIED_EQUAL;
        else status=Status.UNKNOWN;
        return new Result(status,a,b,cv,ocr,stats,fingerprint,posts,exact,acceptedTask>0,quiet,scratchClean,imageDestroyed,taskerDestroyed,qualified);
    }
    /** Same original all[] callback, before page/header checks. Retains no Row or text. */
    void observeRows(List<FrameStructureEvidence.Line> rows){
        if(Thread.currentThread().getId()!=threadId||ocr.observed()||rows==null)
            throw new IllegalStateException("audit OCR callback binding");
        var boxes=new java.util.ArrayList<OcrRowBox>();
        for(var row:rows){
            if(boxes.size()==32)break;
            boxes.add(new OcrRowBox(row.x(),row.y(),row.width(),row.height(),row.score()));
        }
        ocr=new OcrGeometry(true,rows.size(),boxes.size(),rows.size()>32,boxes);
    }
    /** Uses the already-owned same-frame temporary gray copy. No header or OCR is needed. */
    void observeGeometry(byte[] gray,int w,int h,BackendPorts.StopSignal stop)throws Exception{
        if(Thread.currentThread().getId()!=threadId||cv.attempted()||w!=width||h!=height
                ||gray==null||gray.length!=(long)w*h)throw new IllegalStateException("audit geometry binding");
        if(stop.requested())throw new BackendPorts.Cancelled();
        if(seriesEnabled)observeSeriesStats(gray,w,h,stop);
        var key=new RewardRegionGate.Key(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,"MAA_BUFFER_AUDIT",1,"FULL_FRAME_CV_DIAGNOSTIC");
        var geometry=RewardRegionLocator.observeVisibleMoreGray8(key,gray,w,h,stop::requested);
        var boxes=new java.util.ArrayList<RewardRegionLocator.Box>();
        for(var card:geometry.cards()){if(boxes.size()==8)break;boxes.add(card.box());}
        cv=new CvSample(true,geometry.status(),geometry.cards().size(),boxes,geometry.cards().size()>8);
        if(stop.requested())throw new BackendPorts.Cancelled();
    }
    /** One traversal of the already-owned gray copy; no mode value or histogram escapes. */
    private void observeSeriesStats(byte[] gray,int w,int h,BackendPorts.StopSignal stop)throws Exception{
        int top=h/4,bottom=3*h/4;
        if(top==bottom){stats=BandStats.state(StatsStatus.EMPTY_BAND,w,h);return;}
        long[] histogram=new long[256];byte[] whole=null,middle=null;
        var wholeDigest=MessageDigest.getInstance("SHA-256");var middleDigest=MessageDigest.getInstance("SHA-256");
        long count=0,sum=0,squares=0;int min=256,max=-1;long flat=0;
        scratchClean=false;
        try{
            int begin=Math.multiplyExact(top,w),end=Math.multiplyExact(bottom,w);
            for(int i=0;i<gray.length;i++){
                if((i&4095)==0&&stop.requested())throw new BackendPorts.Cancelled();
                int g=gray[i]&255;wholeDigest.update((byte)g);
                if(i>=begin&&i<end){
                    middleDigest.update((byte)g);count++;sum+=g;squares+=(long)g*g;
                    histogram[g]++;min=Math.min(min,g);max=Math.max(max,g);
                }
            }
            for(long bin:histogram)flat=Math.max(flat,bin);
            // <=4194304 pixels and gray<=255 keep both products below long max.
            long numerator=Math.subtractExact(Math.multiplyExact(squares,count),Math.multiplyExact(sum,sum));
            double variance=numerator/((double)count*count);
            whole=wholeDigest.digest();middle=middleDigest.digest();
            if(stop.requested())throw new BackendPorts.Cancelled();
            fingerprint=new Fingerprint(w,h,whole,middle);
            stats=new BandStats(StatsStatus.OBSERVED,w,h,0,top,w,bottom,count,min,max,sum/(double)count,variance,flat);
        }catch(BackendPorts.Cancelled e){stats=BandStats.state(StatsStatus.CANCELED,w,h);fingerprint=null;throw e;}
        catch(Exception|Error e){stats=BandStats.state(StatsStatus.UNKNOWN,w,h);fingerprint=null;throw e;}
        finally{
            Arrays.fill(histogram,0L);if(whole!=null)Arrays.fill(whole,(byte)0);if(middle!=null)Arrays.fill(middle,(byte)0);
            wholeDigest.reset();middleDigest.reset();scratchClean=true;
        }
    }
    private boolean matches(Binding b){
        boolean same=b!=null&&Thread.currentThread().getId()==threadId
            &&b.recognizer()==expected.recognizer()&&b.backendOwner()==expected.backendOwner()
            &&b.requestOwner()==expected.requestOwner()&&b.holder()==expected.holder()&&b.frame()==expected.frame()
            &&samePointer(b.tasker(),expected.tasker())&&samePointer(b.image(),expected.image());
        if(!same)return false;
        return b.frame().width()==width&&b.frame().height()==height&&b.frame().size()==(long)width*height;
    }
    private Sample read(ImageApi api,BackendPorts.StopSignal stop)throws Exception{
        if(api==null||stop==null)throw new IllegalArgumentException("audit ports");
        if(stop.requested())throw new BackendPorts.Cancelled();
        int w=api.MaaImageBufferWidth(expected.image()),h=api.MaaImageBufferHeight(expected.image());
        int channels=api.MaaImageBufferChannels(expected.image()),type=api.MaaImageBufferType(expected.image());
        // int32 dimensions/type, uint8 BGR, no unbounded read or unknown CV depth.
        if(w!=width||h!=height||channels!=3||type!=16)
            return new Sample(Status.FORMAT_REJECTED,w,h,channels,type,0,0,0,-1,false);
        long rawBytes=Math.multiplyExact(Math.multiplyExact((long)w,h),3L);
        if(rawBytes<=0||rawBytes>12L*1024*1024||rawBytes>Integer.MAX_VALUE)
            return new Sample(Status.FORMAT_REJECTED,w,h,channels,type,0,0,0,-1,false);
        Pointer raw=api.MaaImageBufferGetRawData(expected.image());
        if(!pointer(raw))return new Sample(Status.FORMAT_REJECTED,w,h,channels,type,0,0,0,-1,false);
        int rowBytes=Math.multiplyExact(w,3);
        byte[] actual=null,wanted=null,expectedHash=null,actualHash=null,grayRow=null;
        long mismatchPixels=0,mismatchChannels=0;int max=0;
        MessageDigest expectedDigest=MessageDigest.getInstance("SHA-256"),actualDigest=MessageDigest.getInstance("SHA-256");
        scratchClean=false;
        try{
            actual=new byte[rowBytes];wanted=new byte[rowBytes];grayRow=new byte[w];
            for(int y=0;y<h;y++){
                if(stop.requested())throw new BackendPorts.Cancelled();
                raw.read(Math.multiplyExact((long)y,rowBytes),actual,0,rowBytes);
                for(int offset=0;offset<w;offset+=4096)expected.frame().copyGrayRange(y*w+offset,grayRow,offset,Math.min(4096,w-offset));
                for(int x=0;x<w;x++){
                    int g=grayRow[x]&255;
                    boolean different=false;
                    for(int c=0;c<3;c++){
                        int at=x*3+c,d=Math.abs((actual[at]&255)-g);wanted[at]=(byte)g;
                        if(d!=0){different=true;mismatchChannels++;}max=Math.max(max,d);
                    }
                    if(different)mismatchPixels++;
                }
                expectedDigest.update(wanted);actualDigest.update(actual);
            }
            expectedHash=expectedDigest.digest();actualHash=actualDigest.digest();
            boolean hashEqual=MessageDigest.isEqual(expectedHash,actualHash);
            if(stop.requested())throw new BackendPorts.Cancelled();
            return new Sample(mismatchPixels==0&&hashEqual?Status.VERIFIED_EQUAL:Status.PIXEL_MISMATCH,
                w,h,channels,type,(long)w*h,mismatchPixels,mismatchChannels,max,hashEqual);
        }finally{
            if(actual!=null)Arrays.fill(actual,(byte)0);if(wanted!=null)Arrays.fill(wanted,(byte)0);
            if(grayRow!=null)Arrays.fill(grayRow,(byte)0);
            if(expectedHash!=null)Arrays.fill(expectedHash,(byte)0);if(actualHash!=null)Arrays.fill(actualHash,(byte)0);
            expectedDigest.reset();actualDigest.reset();scratchClean=true;
        }
    }
    private static boolean pointer(Pointer value){return value!=null&&Pointer.nativeValue(value)!=0;}
    private static boolean samePointer(Pointer a,Pointer b){return pointer(a)&&pointer(b)&&Pointer.nativeValue(a)==Pointer.nativeValue(b);}
}
