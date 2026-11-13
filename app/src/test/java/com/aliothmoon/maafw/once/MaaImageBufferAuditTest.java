package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.jna.*;
import org.junit.Test;

/** Non-live byte/identity/cleanup tests. No Maa library, Android source, OCR or action. */
public class MaaImageBufferAuditTest {
    private static final BackendPorts.StopSignal GO=()->false;
    private static final class TrackedPointer extends Pointer {
        final List<byte[]> outputs=new ArrayList<>();final byte[] bytes;boolean fail;
        TrackedPointer(byte[] bytes){super(303);this.bytes=bytes;}
        @Override public void read(long offset,byte[] target,int index,int length){
            outputs.add(target);System.arraycopy(bytes,Math.toIntExact(offset),target,index,length);
            if(fail)throw new IllegalStateException("synthetic borrowed read failure");
        }
        void assertWiped(){for(byte[] bytes:outputs)for(byte value:bytes)assertEquals(0,value);}
    }
    private static final class Api implements MaaImageBufferAudit.ImageApi,AutoCloseable {
        int w,h,c=3,type=16,rawCalls,metadataCalls;boolean nullRaw;
        final byte[] bytes;final TrackedPointer pointer;
        Api(byte[] gray,int width,int height){w=width;h=height;bytes=new byte[gray.length*3];pointer=new TrackedPointer(bytes);
            for(int i=0;i<gray.length;i++)for(int k=0;k<3;k++)bytes[i*3+k]=gray[i];}
        @Override public Pointer MaaImageBufferGetRawData(Pointer image){rawCalls++;return nullRaw?null:pointer;}
        @Override public int MaaImageBufferWidth(Pointer image){metadataCalls++;return w;}
        @Override public int MaaImageBufferHeight(Pointer image){metadataCalls++;return h;}
        @Override public int MaaImageBufferChannels(Pointer image){metadataCalls++;return c;}
        @Override public int MaaImageBufferType(Pointer image){metadataCalls++;return type;}
        @Override public void close(){pointer.assertWiped();Arrays.fill(bytes,(byte)0);}
    }
    private static final class Fixture implements AutoCloseable {
        final byte[] gray;final OwnedFrame frame=new OwnedFrame();final Api api;
        final MaaImageBufferAudit.Binding binding;final MaaImageBufferAudit audit;
        Fixture(){this(3,2,new byte[]{0,10,127,(byte)128,(byte)200,(byte)255});}
        Fixture(int width,int height,byte[] values){gray=values;frame.sink().begin(width,height);frame.sink().append(values,0,values.length);assertTrue(frame.seal());
            binding=new MaaImageBufferAudit.Binding(new Object(),new Object(),new Object(),new Object(),frame.view(),new Pointer(101),new Pointer(202));
            audit=new MaaImageBufferAudit(binding);api=new Api(values,width,height);}
        void before()throws Exception{audit.before(api,binding,GO);}
        void after()throws Exception{audit.posted(binding,7);audit.after(api,binding,7,true,GO);}
        MaaImageBufferAudit.Result done(){return audit.result(true,true,true);}
        @Override public void close(){api.close();frame.wipeAfterQuiescence();Arrays.fill(gray,(byte)0);}
    }
    @Test public void exactTwoSamplesOneBoundTaskAndNoPointerLeak()throws Exception{
        try(var f=new Fixture()){f.before();f.after();var r=f.done();assertEquals(MaaImageBufferAudit.Status.VERIFIED_EQUAL,r.status());
            assertEquals(2,f.api.rawCalls);assertEquals(8,f.api.metadataCalls);assertEquals(1,r.queryPosts());assertTrue(r.positiveTaskBound());assertTrue(r.queryQuiet());
            assertEquals(6,r.afterSetEncoded().comparedPixels());assertEquals(0,r.afterQueryQuiet().mismatchedChannels());assertTrue(r.afterQueryQuiet().expectedBgrSha256Equal());
            assertTrue(r.javaScratchWiped());assertTrue(r.cleanupQualified());assertFalse(r.summary().contains("202"));assertFalse(r.summary().contains("com.sun.jna"));f.api.pointer.assertWiped();}
    }
    @Test public void oneChannelMismatchCannotBeHiddenByGrayConversion()throws Exception{
        try(var f=new Fixture()){f.before();f.api.bytes[4]=(byte)13;f.after();var r=f.done();assertEquals(MaaImageBufferAudit.Status.PIXEL_MISMATCH,r.status());
            assertEquals(1,r.afterQueryQuiet().mismatchedPixels());assertEquals(1,r.afterQueryQuiet().mismatchedChannels());assertEquals(3,r.afterQueryQuiet().maxAbsDiff());
            assertFalse(r.afterQueryQuiet().expectedBgrSha256Equal());assertEquals(MaaImageBufferAudit.Status.VERIFIED_EQUAL,r.afterSetEncoded().status());}
    }
    @Test public void formatRejectsWithoutDereferencingRaw()throws Exception{
        for(int mode=0;mode<5;mode++)try(var f=new Fixture()){
            switch(mode){case 0->f.api.w=4;case 1->f.api.h=Integer.MAX_VALUE;case 2->f.api.c=4;case 3->f.api.type=24;default->f.api.type=5;}
            f.before();f.after();assertEquals(MaaImageBufferAudit.Status.FORMAT_REJECTED,f.done().status());assertEquals(0,f.api.rawCalls);assertTrue(f.audit.scratchClean());}
    }
    @Test public void nullRawRemainsRejected()throws Exception{
        try(var f=new Fixture()){f.api.nullRaw=true;f.before();f.after();assertEquals(MaaImageBufferAudit.Status.FORMAT_REJECTED,f.done().status());assertEquals(2,f.api.rawCalls);}
    }
    private static MaaImageBufferAudit.Binding changed(MaaImageBufferAudit.Binding b,int item){
        return new MaaImageBufferAudit.Binding(item==0?new Object():b.recognizer(),item==1?new Object():b.backendOwner(),
            item==2?new Object():b.requestOwner(),item==3?new Object():b.holder(),item==4?new BackendPorts.FrameView(){
                public int width(){return b.frame().width();}public int height(){return b.frame().height();}public int size(){return b.frame().size();}
                public int unsignedByte(int i){return b.frame().unsignedByte(i);}}:b.frame(),
            item==5?new Pointer(102):b.tasker(),item==6?new Pointer(203):b.image());
    }
    @Test public void everyOwnerHolderFrameTaskerAndImageIdentityIsMandatory()throws Exception{
        for(int i=0;i<7;i++)try(var f=new Fixture()){f.before();f.audit.posted(changed(f.binding,i),7);
            f.audit.after(f.api,f.binding,7,true,GO);assertEquals(MaaImageBufferAudit.Status.IDENTITY_REJECTED,f.done().status());assertEquals(1,f.api.rawCalls);}
    }
    @Test public void sameNativePointerValueAllowsOnlySameFullBinding()throws Exception{
        try(var f=new Fixture()){f.before();var b=f.binding;
            var alias=new MaaImageBufferAudit.Binding(b.recognizer(),b.backendOwner(),b.requestOwner(),b.holder(),b.frame(),new Pointer(101),new Pointer(202));
            f.audit.posted(alias,7);f.audit.after(f.api,alias,7,true,GO);assertEquals(MaaImageBufferAudit.Status.VERIFIED_EQUAL,f.done().status());}
    }
    @Test public void postMustMatchPositiveExactTaskAndActuallyQuiet()throws Exception{
        for(int mode=0;mode<3;mode++)try(var f=new Fixture()){f.before();f.audit.posted(f.binding,mode==0?0:7);
            f.audit.after(f.api,f.binding,mode==1?8:7,mode!=2,GO);assertEquals(MaaImageBufferAudit.Status.IDENTITY_REJECTED,f.done().status());assertEquals(1,f.api.rawCalls);}
    }
    @Test public void duplicatePostDoesNotReReadOrTurnIntoSecondOcr()throws Exception{
        try(var f=new Fixture()){f.before();f.audit.posted(f.binding,7);f.audit.posted(f.binding,8);f.audit.after(f.api,f.binding,8,true,GO);
            assertEquals(2,f.done().queryPosts());assertEquals(MaaImageBufferAudit.Status.IDENTITY_REJECTED,f.done().status());assertEquals(1,f.api.rawCalls);}
    }
    @Test public void duplicateSamplesAreNotRepeated()throws Exception{
        try(var f=new Fixture()){f.before();f.after();f.audit.after(f.api,f.binding,7,true,GO);
            assertEquals(MaaImageBufferAudit.Status.IDENTITY_REJECTED,f.done().status());assertEquals(2,f.api.rawCalls);}
        try(var f=new Fixture()){f.before();f.before();f.after();assertEquals(MaaImageBufferAudit.Status.IDENTITY_REJECTED,f.done().status());assertEquals(1,f.api.rawCalls);}
    }
    @Test public void notRunOrPartialNeverVerified()throws Exception{
        try(var f=new Fixture()){assertEquals(MaaImageBufferAudit.Status.NOT_RUN,f.done().status());f.before();assertEquals(MaaImageBufferAudit.Status.UNKNOWN,f.done().status());}
    }
    @Test public void cleanupImageTaskerAndPublicationEachMandatory()throws Exception{
        try(var f=new Fixture()){f.before();f.after();assertEquals(MaaImageBufferAudit.Status.UNKNOWN,f.audit.result(false,true,true).status());
            assertEquals(MaaImageBufferAudit.Status.UNKNOWN,f.audit.result(true,false,true).status());assertEquals(MaaImageBufferAudit.Status.UNKNOWN,f.audit.result(true,true,false).status());
            assertEquals(MaaImageBufferAudit.Status.VERIFIED_EQUAL,f.done().status());}
    }
    @Test public void canceledReadWipesRowsAndCannotVerify()throws Exception{
        try(var f=new Fixture()){var calls=new java.util.concurrent.atomic.AtomicInteger();
            try{f.audit.before(f.api,f.binding,()->calls.incrementAndGet()>=3);fail();}catch(BackendPorts.Cancelled expected){}
            assertEquals(MaaImageBufferAudit.Status.CANCELED,f.done().status());assertTrue(f.audit.scratchClean());f.api.pointer.assertWiped();}
    }
    @Test public void borrowedReadFailureIsUnknownAndTemporaryRowsAreWiped()throws Exception{
        try(var f=new Fixture()){f.api.pointer.fail=true;try{f.before();fail();}catch(IllegalStateException expected){}
            assertEquals(MaaImageBufferAudit.Status.UNKNOWN,f.done().status());assertTrue(f.audit.scratchClean());f.api.pointer.assertWiped();}
    }
    @Test public void anotherThreadCannotReadBoundImage()throws Exception{
        try(var f=new Fixture()){Thread t=new Thread(()->{try{f.audit.before(f.api,f.binding,GO);}catch(Exception e){throw new AssertionError(e);}});t.start();t.join();
            assertEquals(MaaImageBufferAudit.Status.IDENTITY_REJECTED,f.done().status());assertEquals(0,f.api.rawCalls);}
    }
    @Test public void disposedOwnedFrameCannotBeSubstituted()throws Exception{
        try(var f=new Fixture()){f.before();f.frame.wipeAfterQuiescence();try{f.audit.posted(f.binding,7);fail();}catch(IllegalStateException expected){}
            assertEquals(1,f.api.rawCalls);assertEquals(MaaImageBufferAudit.Status.UNKNOWN,f.audit.result(false,false,false).status());}
    }
    @Test public void originalSameFrameCvRunsWithoutOcrHeaderAndKeepsOnlySafeBoxes()throws Exception{
        int w=400,h=600;byte[] gray=new byte[w*h];Arrays.fill(gray,(byte)247);
        for(int y=100;y<260;y++)for(int x=30;x<370;x++)gray[y*w+x]=(byte)255;
        try(var f=new Fixture(w,h,gray)){f.before();f.after();f.audit.observeGeometry(gray,w,h,GO);
            var cv=f.done().sameFrameCv();assertTrue(cv.attempted());assertNotNull(cv.status());assertTrue(cv.cardTotal()>=1);assertTrue(cv.fullFrameBoxes().size()<=8);
            try{cv.fullFrameBoxes().clear();fail();}catch(UnsupportedOperationException expected){}
            assertTrue(f.done().summary().contains("DiagnosticOnly"));assertTrue(f.done().summary().length()<2500);}
    }
    @Test public void boundedCvOverflowReportsTrueTotal()throws Exception{
        int w=400,h=3600;byte[] gray=new byte[w*h];Arrays.fill(gray,(byte)247);
        for(int n=0;n<10;n++)for(int y=70+n*340;y<230+n*340;y++)for(int x=30;x<370;x++)gray[y*w+x]=(byte)255;
        try(var f=new Fixture(w,h,gray)){f.audit.observeGeometry(gray,w,h,GO);var cv=f.done().sameFrameCv();assertTrue(cv.cardTotal()>8);
            assertEquals(8,cv.fullFrameBoxes().size());assertTrue(cv.truncated());assertTrue(f.done().summary().length()<3000);}
    }
    @Test public void allResultFieldsAreSafeAndNoImageOrOriginalTextCanEscape(){
        for(Class<?> type:List.of(MaaImageBufferAudit.Result.class,MaaImageBufferAudit.Sample.class,MaaImageBufferAudit.CvSample.class))
            for(var component:type.getRecordComponents()){
                Class<?> c=component.getType();assertFalse(Pointer.class.isAssignableFrom(c));assertFalse(c.isArray());
                assertFalse(BackendPorts.FrameView.class.isAssignableFrom(c));assertNotEquals(String.class,c);
            }
    }
    @Test public void jnaDeclarationsMatchOfficialCWidths()throws Exception{
        assertEquals(Pointer.class,MaaRecognizer.Api.class.getMethod("MaaImageBufferGetRawData",Pointer.class).getReturnType());
        for(String n:List.of("MaaImageBufferWidth","MaaImageBufferHeight","MaaImageBufferChannels","MaaImageBufferType"))
            assertEquals(int.class,MaaRecognizer.Api.class.getMethod(n,Pointer.class).getReturnType());
        assertEquals(byte.class,MaaRecognizer.Api.class.getMethod("MaaImageBufferSetEncoded",Pointer.class,Pointer.class,long.class).getReturnType());
    }
    @Test public void allOriginalOcrGeometryIncludesLowAndBlankRowsButNoText()throws Exception{
        try(var f=new Fixture()){
            var rows=new ArrayList<FrameStructureEvidence.Line>();
            for(int i=0;i<35;i++)rows.add(new FrameStructureEvidence.Line(i%2==0?"SYNTHETIC_PRIVATE_TEXT":"",i,2*i,1,2,i==0?.01:.9999999999999999));
            f.audit.observeRows(rows);var r=f.done();var o=r.allOcrGeometry();assertTrue(o.observed());assertEquals(35,o.nativeRows());assertEquals(32,o.reported());assertTrue(o.truncated());
            for(int i=0;i<32;i++){var actual=o.originalXywh().get(i);assertEquals(i,actual.x());assertEquals(i*2,actual.y());assertEquals(rows.get(i).score(),actual.confidence(),0);}
            assertFalse(r.summary().contains("SYNTHETIC_PRIVATE_TEXT"));assertTrue(r.summary().contains("FULL_FRAME_XYWH"));
            try{o.originalXywh().clear();fail();}catch(UnsupportedOperationException expected){}
        }
    }
    @Test public void worstCap32GeometryAndEightCvBoxesFitReceiptBudget(){
        var rows=new ArrayList<MaaImageBufferAudit.OcrRowBox>();var boxes=new ArrayList<com.aliothmoon.maafw.rewards.RewardRegionLocator.Box>();
        for(int i=0;i<32;i++)rows.add(new MaaImageBufferAudit.OcrRowBox(4095,4095,4096,4096,.9999999999999999));
        for(int i=0;i<8;i++)boxes.add(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(1000,1000,4096,4096));
        var sample=new MaaImageBufferAudit.Sample(MaaImageBufferAudit.Status.PIXEL_MISMATCH,4096,4096,3,16,4194304,4194304,12582912,255,false);
        var result=new MaaImageBufferAudit.Result(MaaImageBufferAudit.Status.PIXEL_MISMATCH,sample,sample,
            new MaaImageBufferAudit.CvSample(true,com.aliothmoon.maafw.rewards.RewardRegionLocator.Status.OBSERVED,16,boxes,true),
            new MaaImageBufferAudit.OcrGeometry(true,128,32,true,rows),1,true,true,true,true,true,true,true);
        int length=result.summary().length();assertTrue("audit="+length,length<=5200);assertTrue(length+1500+1024<8000);
        System.out.println("SAFE_AUDIT_MAX32_AND_CV8_SUMMARY_CHARS="+length+" WITH_SOURCE1500_AND_ENVELOPE1024="+(length+1500+1024));
    }}