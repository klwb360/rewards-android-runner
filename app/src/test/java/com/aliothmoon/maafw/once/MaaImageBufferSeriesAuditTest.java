package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.lang.reflect.Modifier;
import java.util.*;
import com.sun.jna.Pointer;
import org.junit.Test;

/** Pure same-frame aggregates and opaque equality only; no Maa/OCR/capture/actions. */
public class MaaImageBufferSeriesAuditTest {
    private static final BackendPorts.StopSignal GO=()->false;
    private static final class Fixture implements AutoCloseable {
        final int w,h;final byte[] gray;final OwnedFrame frame=new OwnedFrame();
        final MaaImageBufferAudit.Binding binding;final MaaImageBufferAudit audit;
        final MaaImageBufferAudit.ImageApi api;
        Fixture(int w,int h,byte[] gray,boolean enabled){this.w=w;this.h=h;this.gray=gray;
            frame.sink().begin(w,h);frame.sink().append(gray,0,gray.length);assertTrue(frame.seal());
            binding=new MaaImageBufferAudit.Binding(new Object(),new Object(),new Object(),new Object(),frame.view(),new Pointer(10),new Pointer(20));
            audit=new MaaImageBufferAudit(binding,enabled);
            api=new MaaImageBufferAudit.ImageApi(){
                public int MaaImageBufferWidth(Pointer p){return w;}public int MaaImageBufferHeight(Pointer p){return h;}
                public int MaaImageBufferChannels(Pointer p){return 3;}public int MaaImageBufferType(Pointer p){return 16;}
                public Pointer MaaImageBufferGetRawData(Pointer p){return new Pointer(30){
                    @Override public void read(long offset,byte[] out,int start,int length){
                        for(int i=0;i<length;i++)out[start+i]=gray[Math.toIntExact((offset+i)/3)];
                    }};}
            };
        }
        MaaImageBufferAudit.Result complete()throws Exception{
            audit.before(api,binding,GO);audit.posted(binding,99);audit.after(api,binding,99,true,GO);
            audit.observeGeometry(gray,w,h,GO);return audit.result(true,true,true);
        }
        @Override public void close(){frame.wipeAfterQuiescence();Arrays.fill(gray,(byte)0);}
    }
    private static byte[] sample(){return new byte[]{7,7,7,7,0,0,2,2,0,0,2,2,9,9,9,9};}
    private static MaaImageBufferAudit.Fingerprint fingerprint(byte[] values,int w,int h)throws Exception{
        try(var f=new Fixture(w,h,values,true)){return f.complete().fingerprint();}
    }
    @Test public void exactFixedMiddleBandPopulationVarianceAndFlatCount()throws Exception{
        try(var f=new Fixture(4,4,sample(),true)){var r=f.complete();var s=r.bandStats();
            assertEquals(MaaImageBufferAudit.StatsStatus.OBSERVED,s.status());assertEquals(0,s.left());assertEquals(1,s.top());assertEquals(4,s.right());assertEquals(3,s.bottom());
            assertEquals(8,s.count());assertEquals(0,s.min());assertEquals(2,s.max());assertEquals(1,s.mean(),0);assertEquals(1,s.populationVariance(),0);assertEquals(4,s.flatCount());
            assertNotNull(r.fingerprint());assertTrue(r.javaScratchWiped());assertFalse(r.summary().contains("histogram=["));assertFalse(r.summary().contains("mode="));}
    }
    @Test public void flatMeansMostCommonValueCountNotRangeOrEqualAdjacentCount()throws Exception{
        byte[] values=sample();values[4]=3;values[5]=3;values[6]=3;values[7]=3;values[8]=3;values[9]=9;values[10]=9;values[11]=9;
        try(var f=new Fixture(4,4,values,true)){var s=f.complete().bandStats();assertEquals(5,s.flatCount());assertEquals(3,s.min());assertEquals(9,s.max());
            assertEquals(5.25,s.mean(),0);assertEquals(8.4375,s.populationVariance(),0);}
    }
    @Test public void unsignedGrayEndpointsAreCorrect()throws Exception{
        byte[] values=new byte[16];for(int i=4;i<12;i++)values[i]=(byte)255;
        try(var f=new Fixture(4,4,values,true)){var s=f.complete().bandStats();assertEquals(255,s.min());assertEquals(255,s.max());assertEquals(255,s.mean(),0);
            assertEquals(0,s.populationVariance(),0);assertEquals(8,s.flatCount());}
    }
    @Test public void oddHeightUsesExactFloorQuarterBounds()throws Exception{
        byte[] gray=new byte[7*7];Arrays.fill(gray,(byte)23);
        try(var f=new Fixture(7,7,gray,true)){var s=f.complete().bandStats();assertEquals(1,s.top());assertEquals(5,s.bottom());assertEquals(28,s.count());}
    }
    @Test public void maxFrameLongAccumulatorsDoNotOverflow()throws Exception{
        int w=1024,h=4096;byte[] gray=new byte[w*h];Arrays.fill(gray,(byte)255);
        try(var f=new Fixture(w,h,gray,true)){f.audit.observeGeometry(gray,w,h,GO);var s=f.audit.result(true,true,true).bandStats();
            assertEquals(2097152,s.count());assertEquals(255,s.mean(),0);assertEquals(0,s.populationVariance(),0);assertEquals(2097152,s.flatCount());assertTrue(f.audit.scratchClean());}
    }
    @Test public void identicalFramesAreBothEqualAfterOriginalFramesHaveBeenWiped()throws Exception{
        var a=fingerprint(sample(),4,4);var b=fingerprint(sample(),4,4);var c=MaaImageBufferAudit.compare(a,b);
        assertEquals(MaaImageBufferAudit.Check.YES,c.sameDimensions());assertEquals(MaaImageBufferAudit.Check.YES,c.wholeEqual());assertEquals(MaaImageBufferAudit.Check.YES,c.middleEqual());}
    @Test public void outsideBandChangeOnlyChangesWholeDigest()throws Exception{
        var a=fingerprint(sample(),4,4);var changed=sample();changed[0]=90;var b=fingerprint(changed,4,4);var c=MaaImageBufferAudit.compare(a,b);
        assertEquals(MaaImageBufferAudit.Check.YES,c.sameDimensions());assertEquals(MaaImageBufferAudit.Check.NO,c.wholeEqual());assertEquals(MaaImageBufferAudit.Check.YES,c.middleEqual());}
    @Test public void insideBandChangeChangesBothDigests()throws Exception{
        var a=fingerprint(sample(),4,4);var changed=sample();changed[4]=1;var c=MaaImageBufferAudit.compare(a,fingerprint(changed,4,4));
        assertEquals(MaaImageBufferAudit.Check.NO,c.wholeEqual());assertEquals(MaaImageBufferAudit.Check.NO,c.middleEqual());}
    @Test public void dimensionsMismatchMakesPixelEqualitiesUnobserved()throws Exception{
        var a=fingerprint(sample(),4,4);var b=fingerprint(sample(),2,8);var c=MaaImageBufferAudit.compare(a,b);
        assertEquals(MaaImageBufferAudit.Check.NO,c.sameDimensions());assertEquals(MaaImageBufferAudit.Check.NOT_OBSERVED,c.wholeEqual());assertEquals(MaaImageBufferAudit.Check.NOT_OBSERVED,c.middleEqual());}
    @Test public void missingEvidenceIsNotFalse()throws Exception{
        for(var c:List.of(MaaImageBufferAudit.compare(null,null),MaaImageBufferAudit.compare(fingerprint(sample(),4,4),null))){
            assertEquals(MaaImageBufferAudit.Check.NOT_OBSERVED,c.sameDimensions());assertEquals(MaaImageBufferAudit.Check.NOT_OBSERVED,c.wholeEqual());assertEquals(MaaImageBufferAudit.Check.NOT_OBSERVED,c.middleEqual());}
    }
    @Test public void legacyV28DoesNotComputeStatisticsOrFingerprint()throws Exception{
        try(var f=new Fixture(4,4,sample(),false)){var r=f.complete();assertEquals(MaaImageBufferAudit.Status.VERIFIED_EQUAL,r.status());
            assertEquals(MaaImageBufferAudit.StatsStatus.NOT_RUN,r.bandStats().status());assertNull(r.fingerprint());}
    }
    @Test public void incompleteCleanupCannotPublishFingerprint()throws Exception{
        try(var f=new Fixture(4,4,sample(),true)){f.complete();assertNull(f.audit.result(false,true,true).fingerprint());assertNull(f.audit.result(true,false,true).fingerprint());
            assertNull(f.audit.result(true,true,false).fingerprint());assertNotNull(f.audit.result(true,true,true).fingerprint());}
    }
    @Test public void missingRawComparisonCannotPublishFingerprint()throws Exception{
        try(var f=new Fixture(4,4,sample(),true)){f.audit.observeGeometry(f.gray,4,4,GO);assertEquals(MaaImageBufferAudit.StatsStatus.OBSERVED,f.audit.result(true,true,true).bandStats().status());
            assertNull(f.audit.result(true,true,true).fingerprint());}
    }
    @Test public void canceledStatisticsHasNoFingerprintAndWipesScratch()throws Exception{
        byte[] gray=new byte[64*64];Arrays.fill(gray,(byte)110);
        try(var f=new Fixture(64,64,gray,true)){f.audit.before(f.api,f.binding,GO);f.audit.posted(f.binding,99);f.audit.after(f.api,f.binding,99,true,GO);
            var calls=new java.util.concurrent.atomic.AtomicInteger();try{f.audit.observeGeometry(gray,64,64,()->calls.incrementAndGet()>=3);fail();}catch(BackendPorts.Cancelled expected){}
            var r=f.audit.result(true,true,true);assertEquals(MaaImageBufferAudit.StatsStatus.CANCELED,r.bandStats().status());assertNull(r.fingerprint());assertTrue(r.javaScratchWiped());}
    }
    @Test public void emptyNormalizedBandIsExplicitlyUnobserved()throws Exception{
        try(var f=new Fixture(4,1,new byte[4],true)){var r=f.complete();assertEquals(MaaImageBufferAudit.StatsStatus.EMPTY_BAND,r.bandStats().status());assertEquals(0,r.bandStats().count());assertNull(r.fingerprint());}
    }
    @Test public void firstAndPreviousComparisonDoNotConfuseReturningFrame()throws Exception{
        var first=fingerprint(sample(),4,4);var values=sample();values[0]=90;var previous=fingerprint(values,4,4);var third=fingerprint(sample(),4,4);
        assertEquals(MaaImageBufferAudit.Check.YES,MaaImageBufferAudit.compare(first,third).wholeEqual());
        assertEquals(MaaImageBufferAudit.Check.NO,MaaImageBufferAudit.compare(previous,third).wholeEqual());
        assertEquals(MaaImageBufferAudit.Check.YES,MaaImageBufferAudit.compare(previous,third).middleEqual());}
    @Test public void fingerprintIsPrivateConstructedAndHasNoFrameRowPointerOrByteAccessor()throws Exception{
        for(var constructor:MaaImageBufferAudit.Fingerprint.class.getDeclaredConstructors())assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        for(var field:MaaImageBufferAudit.Fingerprint.class.getDeclaredFields()){
            assertTrue(Modifier.isPrivate(field.getModifiers()));assertTrue(Modifier.isFinal(field.getModifiers()));
            assertTrue(field.getType()==int.class||field.getType()==byte[].class);
        }
        for(var method:MaaImageBufferAudit.Fingerprint.class.getDeclaredMethods())assertNotEquals(byte[].class,method.getReturnType());
        var fp=fingerprint(sample(),4,4);assertEquals("PRIVATE_GRAY_DIGEST_PAIR",fp.toString());}
    @Test public void maxAllRowCvAndBandStatsSummaryStaysBelow3600()throws Exception{
        var rows=new ArrayList<MaaImageBufferAudit.OcrRowBox>();var boxes=new ArrayList<com.aliothmoon.maafw.rewards.RewardRegionLocator.Box>();
        for(int i=0;i<32;i++)rows.add(new MaaImageBufferAudit.OcrRowBox(4095,4095,4096,4096,.9999999999999999));
        for(int i=0;i<8;i++)boxes.add(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(1000,1000,4096,4096));
        var sample=new MaaImageBufferAudit.Sample(MaaImageBufferAudit.Status.PIXEL_MISMATCH,4096,4096,3,16,4194304,4194304,12582912,255,false);
        var stats=new MaaImageBufferAudit.BandStats(MaaImageBufferAudit.StatsStatus.OBSERVED,4096,4096,0,1024,4096,3072,2097152,0,255,127.99999999999999,16256.24999999999,2097152);
        var result=new MaaImageBufferAudit.Result(MaaImageBufferAudit.Status.PIXEL_MISMATCH,sample,sample,
            new MaaImageBufferAudit.CvSample(true,com.aliothmoon.maafw.rewards.RewardRegionLocator.Status.OBSERVED,16,boxes,true),
            new MaaImageBufferAudit.OcrGeometry(true,128,32,true,rows),stats,fingerprint(sample(),4,4),1,true,true,true,true,true,true,true);
        int length=result.summary().length();assertTrue("seriesAudit="+length,length<=3600);assertTrue(length+1500+1024<7000);
        assertFalse(result.summary().contains("[B@"));System.out.println("SERIES_SAFE_MAX32_CV8_STATS_SUMMARY_CHARS="+length+" WITH_SOURCE1500_ENVELOPE1024="+(length+1500+1024));
    }
}