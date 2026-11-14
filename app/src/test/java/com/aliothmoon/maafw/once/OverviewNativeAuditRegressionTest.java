package com.aliothmoon.maafw.once;

import com.sun.jna.Pointer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Generated PNG and fake native ports exercise the actual overview join. No device or input. */
public class OverviewNativeAuditRegressionTest {
    private static final long START=2500,OBSERVED=2800,AFTER=2900,NOW=3000;
    private enum Measurement { OMITTED, SAME_FRAME, DIFFERENT_FRAME }
    private static BingNavigationSnapshot.Ready ready(long now){return UiImageGeometryBindingTest.ready(now);}

    private static final class Fixture implements AutoCloseable {
        final Object owner=new Object(),session=new Object(),epoch=new Object();
        final OverviewFramePreview preview;
        final OverviewFrameCapture capture;
        final PngSourceOfflineTest.Clock clock=new PngSourceOfflineTest.Clock();
        final FakeRecognizer recognizer;
        final OverviewPngBinding binding;
        final int width=OverviewFrameCaptureTest.WIDTH,height=OverviewFrameCaptureTest.HEIGHT;
        final byte[] expectedDigest;
        final byte[] decodedPixels;
        int sourceCalls;

        Fixture(Measurement measurement)throws Exception{
            preview=new OverviewFramePreview(width,height);
            capture=OverviewFrameCapture.begin(1,owner,session,epoch,OverviewFrameCaptureTest.NONCE,
                    OverviewFrameCaptureTest.report(),ready(900),950,ready(2200),ready(2350),2400,9000,
                    ()->false,preview);
            assertNotNull(capture);clock.now=START*1_000_000L;
            recognizer=new FakeRecognizer(capture,clock,measurement);
            decodedPixels=new byte[width*height];
            for(int i=0;i<decodedPixels.length;i++)decodedPixels[i]=(byte)(90+i%13);
            expectedDigest=MessageDigest.getInstance("SHA-256").digest(decodedPixels);
            byte[] rgb=new byte[decodedPixels.length*3];
            byte[] png;
            try{
                for(int i=0;i<decodedPixels.length;i++)Arrays.fill(rgb,i*3,i*3+3,decodedPixels[i]);
                png=PngSourceOfflineTest.png(width,height,3,
                        PngSourceOfflineTest.rows(rgb,width,height,3,0),8192,null);
            }finally{Arrays.fill(rgb,(byte)0);}
            var process=new PngSourceOfflineTest.FakeProcess(png,clock);process.waitAdvance=100_000_000L;
            binding=OverviewPngBinding.fixture(
                    exact->new BackendPorts.ClaimReceipt(exact,BackendPorts.ClaimDecision.CLAIMED),
                    ()->{sourceCalls++;return process;},
                    ()->{if(!capture.current(clock.now/1_000_000L))throw new SecurityException("fixture expired");},
                    new BackendPorts.RevocationFence(),recognizer,Runnable::run,clock,capture,
                    (bytes,length)->new PngImageAudit.Image(){
                        public PngImageAudit.Facts facts(){return new PngImageAudit.Facts(width,height,
                                PngImageAudit.Config.ARGB_8888,0,true,false,false,true,false);}
                        public void argbRow(int y,int[] row){
                            for(int x=0;x<width;x++){
                                int value=decodedPixels[y*width+x]&255;
                                row[x]=0xff000000|(value<<16)|(value<<8)|value;
                            }
                        }
                        public void erase(){Arrays.fill(decodedPixels,(byte)0);}
                        public void recycle(){}
                    });
        }
        MaaImageBufferAudit.Result start()throws Exception{
            var result=binding.backend().start();
            assertEquals(OnceBackend.Phase.COMPLETED,result.phase());
            assertEquals(OnceBackend.Failure.NONE,result.failure());
            assertEquals(OnceBackend.Cancellation.NOT_REQUESTED,result.cancellation());
            assertEquals(OnceBackend.Memory.WIPED,result.memory());
            assertTrue(binding.resourcesClosed());assertEquals(1,sourceCalls);
            assertTrue(recognizer.preparedCandidate);
            var audit=recognizer.overviewAudit(result.owner());
            assertNotNull(audit);assertEquals(MaaImageBufferAudit.Status.VERIFIED_EQUAL,audit.status());
            assertEquals(1,audit.queryPosts());assertTrue(audit.cleanupQualified());
            return audit;
        }
        OverviewPngBinding.Completion completion(){return binding.takeCompletion(ready(AFTER),NOW);}
        @Override public void close(){
            preview.close();Arrays.fill(decodedPixels,(byte)0);Arrays.fill(expectedDigest,(byte)0);
        }
    }

    private static final class FakeRecognizer implements OverviewPngBinding.OverviewRecognizer {
        final OverviewFrameCapture capture;
        final PngSourceOfflineTest.Clock clock;
        final Measurement measurement;
        OverviewFrameCapture.Candidate candidate;
        MaaImageBufferAudit audit;
        OnceBackend.Owner owner;
        byte[] pixels;
        boolean released,preparedCandidate;
        FakeRecognizer(OverviewFrameCapture capture,PngSourceOfflineTest.Clock clock,Measurement measurement){
            this.capture=capture;this.clock=clock;this.measurement=measurement;
        }
        @Override public BackendPorts.Recognition recognize(OnceBackend.Owner exact,
                BackendPorts.FrameView frame,BackendPorts.StopSignal stop)throws Exception{
            owner=exact;int w=frame.width(),h=frame.height();pixels=new byte[frame.size()];
            for(int offset=0;offset<pixels.length;offset+=4096)
                frame.copyGrayRange(offset,pixels,offset,Math.min(4096,pixels.length-offset));
            var bound=new MaaImageBufferAudit.Binding(this,owner,capture.owner(),capture,frame,
                    new Pointer(10),new Pointer(20));
            audit=new MaaImageBufferAudit(bound,true);
            MaaImageBufferAudit.ImageApi api=new MaaImageBufferAudit.ImageApi(){
                public int MaaImageBufferWidth(Pointer p){return w;}
                public int MaaImageBufferHeight(Pointer p){return h;}
                public int MaaImageBufferChannels(Pointer p){return 3;}
                public int MaaImageBufferType(Pointer p){return 16;}
                public Pointer MaaImageBufferGetRawData(Pointer p){return new Pointer(30){
                    @Override public void read(long offset,byte[] out,int start,int length){
                        for(int i=0;i<length;i++)out[start+i]=pixels[Math.toIntExact((offset+i)/3)];
                    }
                };}
            };
            audit.before(api,bound,stop);audit.posted(bound,99);audit.after(api,bound,99,true,stop);
            audit.observeRows(List.of());
            byte[] measured=pixels.clone(),candidatePixels=pixels.clone();
            try{
                if(measurement==Measurement.DIFFERENT_FRAME)measured[0]^=1;
                if(measurement!=Measurement.OMITTED)audit.observeGeometry(measured,w,h,stop);
                clock.now=OBSERVED*1_000_000L;
                candidate=capture.prepare(this,owner,frame,candidatePixels,List.of(),stop::requested,OBSERVED);
                preparedCandidate=candidate!=null;
            }finally{Arrays.fill(measured,(byte)0);Arrays.fill(candidatePixels,(byte)0);}
            return new BackendPorts.Recognition(owner,true);
        }
        @Override public void release(OnceBackend.Owner exact){
            assertSame(owner,exact);Arrays.fill(pixels,(byte)0);released=true;
        }
        @Override public BackendPorts.Completion completion(OnceBackend.Owner exact){
            assertSame(owner,exact);
            return new BackendPorts.Completion(owner,released?BackendPorts.Released.CONFIRMED:BackendPorts.Released.UNKNOWN,
                    released?BackendPorts.Quiet.CONFIRMED:BackendPorts.Quiet.UNKNOWN);
        }
        @Override public OverviewFrameCapture.Candidate takeOverviewCandidate(OnceBackend.Owner exact){
            if(exact!=owner||!released)return null;var value=candidate;candidate=null;return value;
        }
        @Override public MaaImageBufferAudit.Result overviewAudit(OnceBackend.Owner exact){
            return exact==owner&&audit!=null?audit.result(released,released,released):null;
        }
    }

    @Test public void verifiedNativePixelsWithoutFingerprintCannotCompleteOverviewJoin()throws Exception{
        try(var f=new Fixture(Measurement.OMITTED)){
            var audit=f.start();
            assertEquals(MaaImageBufferAudit.StatsStatus.NOT_RUN,audit.bandStats().status());
            assertNull(audit.fingerprint());assertFalse(audit.sameFrameCv().attempted());
            assertFalse(MaaImageBufferAudit.matchesGraySha256(audit.fingerprint(),f.width,f.height,f.expectedDigest));
            assertNull(f.completion());assertFalse(f.capture.publicationCurrent(NOW));
        }
    }
    @Test public void sameOwnedFrameFingerprintCompletesActualPngAndNativeJoinOnlyOnce()throws Exception{
        try(var f=new Fixture(Measurement.SAME_FRAME)){
            var audit=f.start();
            assertEquals(MaaImageBufferAudit.StatsStatus.OBSERVED,audit.bandStats().status());
            assertTrue(audit.sameFrameCv().attempted());assertNotNull(audit.fingerprint());
            assertTrue(MaaImageBufferAudit.matchesGraySha256(audit.fingerprint(),f.width,f.height,f.expectedDigest));
            var completion=f.completion();assertNotNull(completion);assertNull(f.completion());
            assertTrue(f.capture.publicationCurrent(NOW));
            // A complete synthetic join still has no live capture or publication authority.
            assertNull(completion.publish(f.owner,f.session,f.epoch,NOW,true,true,true));
        }
    }
    @Test public void differentMeasuredPixelCannotBeHiddenByOtherwiseVerifiedNativeAudit()throws Exception{
        try(var f=new Fixture(Measurement.DIFFERENT_FRAME)){
            var audit=f.start();assertNotNull(audit.fingerprint());
            assertEquals(MaaImageBufferAudit.StatsStatus.OBSERVED,audit.bandStats().status());
            assertFalse(MaaImageBufferAudit.matchesGraySha256(audit.fingerprint(),f.width,f.height,f.expectedDigest));
            assertNull(f.completion());assertFalse(f.capture.publicationCurrent(NOW));
        }
    }
    @Test public void overviewNativeEntryMeasuresTheSameCopyBeforePreparingCandidate()throws Exception{
        Path path=Path.of(System.getProperty("staging.root",".."),
                "app/src/main/java/com/aliothmoon/maafw/once/MaaRecognizer.java");
        String source=Files.readString(path);
        int start=source.indexOf("private void refineOverview("),end=source.indexOf("private void refineUiImageAlignment(",start);
        assertTrue(start>=0&&end>start);String method=source.substring(start,end);
        int copy=method.indexOf("materialFrame.copyGrayRange("),audit=method.indexOf("imageAudit.observeGeometry(gray,frameWidth,frameHeight,stop)"),
                prepare=method.indexOf("overviewCapture.prepare(this,owner,materialFrame,gray,materialRows,");
        assertTrue(copy>=0&&audit>copy&&prepare>audit);
        assertTrue(method.substring(audit,prepare).contains("checkStop(stop)"));
        assertTrue(method.substring(prepare).contains("checkStop(stop)"));
        assertTrue(method.substring(prepare).contains("Arrays.fill(gray,(byte)0)"));
        int factoryStart=source.indexOf("static MaaRecognizer rootOverviewAudit("),factoryEnd=source.indexOf("takeOverviewCandidate(",factoryStart);
        assertTrue(factoryStart>=0&&factoryEnd>factoryStart);String factory=source.substring(factoryStart,factoryEnd);
        assertTrue(factory.contains("imageAuditEnabled=true"));assertTrue(factory.contains("imageSeriesStatsEnabled=true"));
    }
}
