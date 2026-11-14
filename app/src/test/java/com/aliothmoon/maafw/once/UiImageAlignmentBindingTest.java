package com.aliothmoon.maafw.once;

import com.sun.jna.Pointer;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

/** Generated pixels and fake native ports only. These tests do not establish live recognition. */
public class UiImageAlignmentBindingTest {
    static final long START=2500,OBSERVED=2800,AFTER=2900,NOW=3000;
    static BingNavigationSnapshot.Ready ready(long now){return UiRegionSourceEvidenceTest.ready(now);}

    static final class Fixture {
        final UiRegionSourceEvidenceTest.Fixture ui=new UiRegionSourceEvidenceTest.Fixture();
        final AtomicBoolean revoked=new AtomicBoolean();
        final UiImageAlignmentCapture capture;
        final PngSourceOfflineTest.Clock clock=new PngSourceOfflineTest.Clock();
        final FakeRecognizer recognizer;
        final ReviewShizukuPngBinding.AlignmentBinding binding;
        final int width,height;
        int sourceCalls;
        Fixture()throws Exception{this(false,false);}
        Fixture(boolean standardMismatch)throws Exception{this(standardMismatch,false);}
        Fixture(boolean standardMismatch,boolean visual)throws Exception{
            var geometry=ui.evidence().consumeGeometry(ui.owner,ui.session,ui.epoch,ui.report,2300);
            assertNotNull(geometry);capture=geometry.beginFrame(ready(2400),START,8000,revoked::get);
            assertNotNull(capture);width=capture.width();height=capture.height();clock.now=START*1_000_000L;
            recognizer=new FakeRecognizer(capture,clock,visual);
            byte[] generated=new byte[width*height];Arrays.fill(generated,(byte)(visual?220:100));
            if(visual){
                for(int y=50;y<190;y++)Arrays.fill(generated,y*width+30,y*width+370,(byte)240);
                for(int y=230;y<390;y++)Arrays.fill(generated,y*width+30,y*width+370,(byte)240);
            }
            byte[] rgb=new byte[width*height*3];
            for(int i=0;i<generated.length;i++)Arrays.fill(rgb,i*3,i*3+3,generated[i]);
            byte[] encoded=PngSourceOfflineTest.png(width,height,3,
                PngSourceOfflineTest.rows(rgb,width,height,3,0),8192,null);
            Arrays.fill(rgb,(byte)0);
            var process=new PngSourceOfflineTest.FakeProcess(encoded,clock);process.waitAdvance=100_000_000L;
            binding=ReviewShizukuPngBinding.fixtureReadOnlyAlignment(
                owner->new BackendPorts.ClaimReceipt(owner,BackendPorts.ClaimDecision.CLAIMED),
                ()->{sourceCalls++;return process;},
                ()->{if(!capture.captureCurrent(clock.now/1_000_000L))throw new SecurityException("fixture expired");},
                new BackendPorts.RevocationFence(),recognizer,Runnable::run,clock,capture,
                (bytes,length)->new PngImageAudit.Image(){
                    public PngImageAudit.Facts facts(){return new PngImageAudit.Facts(width,height,PngImageAudit.Config.ARGB_8888,0,true,false,false,true,false);}
                    public void argbRow(int y,int[] row){
                        for(int x=0;x<width;x++){int value=(generated[y*width+x]&255)+(standardMismatch?1:0);row[x]=0xff000000|(value<<16)|(value<<8)|value;}
                    }
                    public void erase(){Arrays.fill(generated,(byte)0);}public void recycle(){}
                });
        }
        OnceBackend.Result start(){return binding.backend().start();}
        ReviewShizukuPngBinding.AlignmentCompletion receipt(){return binding.takeReceipt(ready(AFTER),NOW);}
    }

    static final class FakeRecognizer implements ReviewShizukuPngBinding.AlignmentRecognizer {
        final UiImageAlignmentCapture capture;final PngSourceOfflineTest.Clock clock;final boolean visual;
        UiImageAlignmentCapture.Candidate candidate;MaaImageBufferAudit audit;OnceBackend.Owner owner;
        byte[] pixels;boolean released,unclean,wrongRecognizer,badCandidateBytes,badFingerprint;
        long observed=OBSERVED;
        FakeRecognizer(UiImageAlignmentCapture capture,PngSourceOfflineTest.Clock clock,boolean visual){this.capture=capture;this.clock=clock;this.visual=visual;}
        public BackendPorts.Recognition recognize(OnceBackend.Owner owner,BackendPorts.FrameView frame,BackendPorts.StopSignal stop)throws Exception{
            this.owner=owner;int w=frame.width(),h=frame.height();pixels=new byte[frame.size()];
            for(int i=0;i<pixels.length;i++)pixels[i]=(byte)frame.unsignedByte(i);
            var exact=new MaaImageBufferAudit.Binding(this,owner,capture.owner(),capture,frame,new Pointer(10),new Pointer(20));
            audit=new MaaImageBufferAudit(exact,true);
            MaaImageBufferAudit.ImageApi api=new MaaImageBufferAudit.ImageApi(){
                public int MaaImageBufferWidth(Pointer p){return w;}public int MaaImageBufferHeight(Pointer p){return h;}
                public int MaaImageBufferChannels(Pointer p){return 3;}public int MaaImageBufferType(Pointer p){return 16;}
                public Pointer MaaImageBufferGetRawData(Pointer p){return new Pointer(30){
                    @Override public void read(long offset,byte[] out,int start,int length){
                        for(int i=0;i<length;i++)out[start+i]=pixels[Math.toIntExact((offset+i)/3)];
                    }};}
            };
            var rows=visual?List.of(new FrameStructureEvidence.Line("搜索以赚取",40,60,90,20,.95),
                new FrameStructureEvidence.Line("阅读以赚取",40,240,90,20,.95)):
                List.of(new FrameStructureEvidence.Line("搜索积分",40,50,70,20,.95));
            audit.before(api,exact,stop);audit.posted(exact,99);audit.after(api,exact,99,true,stop);audit.observeRows(rows);
            byte[] measured=pixels.clone();
            try{if(badFingerprint)measured[0]++;audit.observeGeometry(measured,w,h,stop);}
            finally{Arrays.fill(measured,(byte)0);}
            byte[] candidatePixels=pixels.clone();
            try{
                if(badCandidateBytes)candidatePixels[0]++;
                clock.now=observed*1_000_000L;
                candidate=capture.prepare(wrongRecognizer?new Object():this,owner,frame,candidatePixels,rows,stop::requested,observed);
            }finally{Arrays.fill(candidatePixels,(byte)0);}
            return new BackendPorts.Recognition(owner,true);
        }
        public void release(OnceBackend.Owner expected){assertSame(owner,expected);Arrays.fill(pixels,(byte)0);released=true;}
        public BackendPorts.Completion completion(OnceBackend.Owner expected){
            assertSame(owner,expected);boolean clean=released&&!unclean;
            return new BackendPorts.Completion(owner,clean?BackendPorts.Released.CONFIRMED:BackendPorts.Released.UNKNOWN,
                clean?BackendPorts.Quiet.CONFIRMED:BackendPorts.Quiet.UNKNOWN);
        }
        public UiImageAlignmentCapture.Candidate takeAlignmentCandidate(OnceBackend.Owner expected){
            if(expected!=owner||!released)return null;var value=candidate;candidate=null;return value;
        }
        public MaaImageBufferAudit.Result alignmentAudit(OnceBackend.Owner expected){
            return expected==owner&&audit!=null?audit.result(released&&!unclean,released,released):null;
        }
    }

    @Test public void realCoordinatorJoinProducesOnlyNonliveOneUseFixtureReceipt()throws Exception{
        var f=new Fixture();assertTrue(f.capture.captureCurrent(START));assertFalse(f.capture.publicationCurrent(START));
        var result=f.start();assertEquals(OnceBackend.Phase.COMPLETED,result.phase());assertEquals(OnceBackend.Memory.WIPED,result.memory());
        var receipt=f.receipt();assertNotNull(receipt);assertFalse(receipt.liveTransport());assertNotNull(receipt.result());
        assertEquals(1,f.sourceCalls);assertEquals(START,receipt.lower());assertEquals(8000,receipt.deadline());
        assertTrue(receipt.consume(f.ui.owner,f.ui.session,f.ui.epoch,NOW));assertFalse(receipt.consume(f.ui.owner,f.ui.session,f.ui.epoch,NOW));
        assertNull(f.receipt());
    }
    @Test public void fixtureCannotPublishAsLiveEvenWithAllCallerGatesTrue()throws Exception{
        var f=new Fixture();f.start();var publication=f.binding.publication(ready(AFTER),NOW,f.ui.owner,f.ui.session,f.ui.epoch,8000);
        String safe=publication.publish(NOW,true,true,true);
        assertTrue(safe.contains("alignmentEvidence=UNQUALIFIED"));assertTrue(safe.contains("regions=SUPPRESSED"));
        assertFalse(safe.contains("SOURCE_CHECKED_NON_ATOMIC"));assertFalse(safe.contains("搜索积分"));assertNull(f.receipt());
    }
    @Test public void generatedVisualCandidatesSurviveActualPngAuditAndOwnerJoinButStayNonlive()throws Exception{
        var f=new Fixture(false,true);assertEquals(OnceBackend.Phase.COMPLETED,f.start().phase());
        var receipt=f.receipt();assertNotNull(receipt);assertFalse(receipt.liveTransport());var result=receipt.result();
        assertEquals(UiRegionImageRefiner.Status.VISUAL_CANDIDATE,result.search().status());
        assertEquals(UiRegionImageRefiner.Status.VISUAL_CANDIDATE,result.reading().status());
        assertEquals(new NativeUiRegions.Rect(30,50,370,190),result.search().cardBoundary());
        assertEquals(new NativeUiRegions.Rect(30,230,370,390),result.reading().cardBoundary());
        assertEquals(new NativeUiRegions.Rect(40,60,130,80),result.search().textBounds());
        assertEquals(new NativeUiRegions.Rect(40,240,130,260),result.reading().textBounds());
        assertNotEquals(result.search().coarse(),result.search().cardBoundary());
        var publication=new UiImageAlignmentPublication(receipt,f.ui.owner,f.ui.session,f.ui.epoch,8000);
        String safe=publication.publish(NOW,true,true,true);
        assertTrue(safe.contains("regions=SUPPRESSED"));assertFalse(safe.contains("VISUAL_CANDIDATE"));
        assertFalse(receipt.consume(f.ui.owner,f.ui.session,f.ui.epoch,NOW));
    }
    @Test public void failedReceiptAttemptCannotBeRetriedAfterStarting()throws Exception{
        var f=new Fixture();assertNull(f.receipt());f.start();assertNull(f.receipt());
    }
    @Test public void unknownNativeCleanupWithholdsReceipt()throws Exception{
        var f=new Fixture();f.recognizer.unclean=true;assertEquals(OnceBackend.Phase.CLEANUP_UNCONFIRMED,f.start().phase());assertNull(f.receipt());assertFalse(f.capture.publicationCurrent(NOW));
    }
    @Test public void candidateMustBelongToThisActualRecognizer()throws Exception{
        var f=new Fixture();f.recognizer.wrongRecognizer=true;f.start();assertNull(f.receipt());
    }
    @Test public void alteredCandidateBytesCannotUseSameSizedOwnedFrame()throws Exception{
        var f=new Fixture();f.recognizer.badCandidateBytes=true;f.start();assertNull(f.receipt());
    }
    @Test public void nativeFingerprintMustEqualCandidateAndPngGray()throws Exception{
        var f=new Fixture();f.recognizer.badFingerprint=true;f.start();assertNull(f.receipt());assertFalse(f.capture.publicationCurrent(NOW));
    }
    @Test public void independentPngDecodeMismatchWithholdsReceipt()throws Exception{
        var f=new Fixture(true);f.start();assertNull(f.receipt());
    }
    @Test public void sourceEnvelopeMustFinishBeforeCandidateWasObserved()throws Exception{
        var f=new Fixture();f.recognizer.observed=START;f.start();assertNull(f.receipt());
    }
    @Test public void changedWindowAtAfterReadCannotSealSameTask()throws Exception{
        var f=new Fixture();f.start();var moved=BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,AFTER,"bbb","ddd","15").ready();
        assertNotNull(moved);assertNull(f.binding.takeReceipt(moved,NOW));
    }
    @Test public void afterMetadataCannotPredateActualNativeObservation()throws Exception{
        var f=new Fixture();f.start();assertNull(f.binding.takeReceipt(ready(2700),NOW));
    }
    @Test public void revocationAfterCleanBackendStillRejectsReceipt()throws Exception{
        var f=new Fixture();f.start();f.revoked.set(true);assertNull(f.receipt());
    }
    @Test public void expiredAfterMetadataWithholdsReceipt()throws Exception{
        var f=new Fixture();f.start();assertNull(f.binding.takeReceipt(ready(AFTER),8000));
    }
    @Test public void wrongPublicationOwnerConsumesRatherThanAllowsReplay()throws Exception{
        var f=new Fixture();f.start();var receipt=f.receipt();assertNotNull(receipt);
        assertFalse(receipt.consume(new Object(),f.ui.session,f.ui.epoch,NOW));assertFalse(receipt.consume(f.ui.owner,f.ui.session,f.ui.epoch,NOW));
    }
    @Test public void sameCaptureCannotCreateSecondSourceBinding()throws Exception{
        var f=new Fixture();
        try{
            ReviewShizukuPngBinding.fixtureReadOnlyAlignment(
                owner->new BackendPorts.ClaimReceipt(owner,BackendPorts.ClaimDecision.CLAIMED),()->null,()->{},
                new BackendPorts.RevocationFence(),f.recognizer,Runnable::run,f.clock,f.capture,(bytes,length)->null);
            fail("second source must be rejected");
        }catch(SecurityException expected){}
        assertEquals(0,f.sourceCalls);f.start();assertEquals(1,f.sourceCalls);
    }
    @Test public void receiptConstructorsArePrivateAndHaveNoRawAccessors(){
        for(var constructor:ReviewShizukuPngBinding.AlignmentCompletion.class.getDeclaredConstructors())assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        for(var method:ReviewShizukuPngBinding.AlignmentCompletion.class.getDeclaredMethods()){
            assertNotEquals(byte[].class,method.getReturnType());assertNotEquals(BackendPorts.FrameView.class,method.getReturnType());
        }
    }
    @Test public void privateNativeDigestComparisonChecksShapeAndBytes()throws Exception{
        var f=new Fixture();f.start();var result=f.recognizer.alignmentAudit(f.binding.backend().snapshot().owner());
        byte[] gray=new byte[f.width*f.height];Arrays.fill(gray,(byte)100);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(gray);Arrays.fill(gray,(byte)0);
        try{
            assertTrue(MaaImageBufferAudit.matchesGraySha256(result.fingerprint(),f.width,f.height,digest));
            assertFalse(MaaImageBufferAudit.matchesGraySha256(result.fingerprint(),f.width+1,f.height,digest));
            digest[0]^=1;assertFalse(MaaImageBufferAudit.matchesGraySha256(result.fingerprint(),f.width,f.height,digest));
            assertFalse(MaaImageBufferAudit.matchesGraySha256(result.fingerprint(),f.width,f.height,new byte[31]));
        }finally{Arrays.fill(digest,(byte)0);}
    }

    @Test public void diagnosticReadDoesNotTakeReceiptAndContainsOnlyFixedScalars()throws Exception{
        var f=new Fixture(false,true);f.start();String before=f.binding.diagnostics();
        assertTrue(before.contains("receiptGate=NOT_ATTEMPTED"));
        assertEquals(before,f.binding.diagnostics());var receipt=f.receipt();assertNotNull(receipt);
        String after=f.binding.diagnostics();assertTrue(after.contains("receiptGate=ACCEPTED"));
        assertTrue(after.contains("imageMatchGate=ACCEPTED"));assertTrue(after.contains("pngAuditStatus=MATCH"));
        assertTrue(after.contains("maaAuditStatus=VERIFIED_EQUAL"));
        for(String field:after.split(" "))assertTrue(field,field.matches("[A-Za-z][A-Za-z0-9]*=(?:[A-Z_]+|true|false|-?[0-9]+)"));
        assertFalse(after.contains("搜索"));assertFalse(after.contains("阅读"));
        assertTrue(receipt.consume(f.ui.owner,f.ui.session,f.ui.epoch,NOW));
        assertFalse(receipt.consume(f.ui.owner,f.ui.session,f.ui.epoch,NOW));assertEquals(1,f.sourceCalls);
    }

    @Test public void successfulBackendWithSlowRecognitionReportsMissingStaleCandidate()throws Exception{
        var f=new Fixture();f.recognizer.observed=7201;
        var result=f.start();assertEquals(OnceBackend.Phase.COMPLETED,result.phase());
        assertEquals(OnceBackend.Failure.NONE,result.failure());assertEquals(OnceBackend.Cleanup.CONFIRMED,result.cleanup());
        assertNull(f.binding.takeReceipt(ready(7300),7400));String diagnostic=f.binding.diagnostics();
        assertTrue(diagnostic.contains("receiptGate=CANDIDATE_MISSING"));
        assertTrue(diagnostic.contains("prepareGate=CURRENT_REJECTED candidateFormed=false"));
        assertTrue(diagnostic.contains("firstCurrentReject=UI_GEOMETRY_REJECTED"));
        assertTrue(diagnostic.contains("firstRejectedUiAfterAgeMs=5001"));
        assertTrue(diagnostic.contains("imageMatchGate=NOT_CHECKED"));assertEquals(1,f.sourceCalls);
        assertNull(f.binding.takeReceipt(ready(AFTER),NOW));assertEquals(1,f.sourceCalls);
    }

    @Test public void freshCandidateThenSlowPostReadReportsSealAgeFailure()throws Exception{
        var f=new Fixture();assertEquals(OnceBackend.Phase.COMPLETED,f.start().phase());
        assertNull(f.binding.takeReceipt(ready(7199),7201));String diagnostic=f.binding.diagnostics();
        assertTrue(diagnostic.contains("receiptGate=SEAL_REJECTED"));assertTrue(diagnostic.contains("imageMatchGate=ACCEPTED"));
        assertTrue(diagnostic.contains("prepareGate=ACCEPTED candidateFormed=true"));
        assertTrue(diagnostic.contains("sealGate=AFTER_REJECTED"));assertTrue(diagnostic.contains("afterGate=CURRENT_REJECTED"));
        assertTrue(diagnostic.contains("firstRejectedUiAfterAgeMs=5001"));assertTrue(diagnostic.contains("imageAfterAgeMs=2"));
        assertNull(f.receipt());assertEquals(1,f.sourceCalls);
    }

    @Test public void nativeHashMismatchIsNotMisreportedAsAStaleOrMissingCandidate()throws Exception{
        var f=new Fixture();f.recognizer.badFingerprint=true;f.start();assertNull(f.receipt());
        String diagnostic=f.binding.diagnostics();assertTrue(diagnostic.contains("receiptGate=IMAGE_MATCH_REJECTED"));
        assertTrue(diagnostic.contains("imageMatchGate=MAA_DIGEST"));
        assertTrue(diagnostic.contains("prepareGate=ACCEPTED candidateFormed=true"));
        assertTrue(diagnostic.contains("firstCurrentReject=NOT_CHECKED"));assertEquals(1,f.sourceCalls);
    }
}
