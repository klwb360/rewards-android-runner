package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import com.sun.jna.Pointer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.Test;

/** Non-live issuer→grant burn→actual audited PNG/OwnedFrame→native-audit methods→opaque next.
 * ImageIO, fake CV pointer/query and synthetic rows only. No Android/Maa/device calls. */
public final class RootImageSeriesPipelineFixtureTest {
    private static final int W=AutonomousCardFixture.W,H=AutonomousCardFixture.H;
    private static final class Job {
        final Object visible=new Object(),request=new Object(),root=new Object();
        final RealCaptureClaim.ProcessEpoch epoch=new RealCaptureClaim.ProcessEpoch();
        final CaptureSessionRegistry registry=new CaptureSessionRegistry();
        final AtomicLong now=new AtomicLong(1000),utc=new AtomicLong(RootImageSeriesScope.STARTS_UTC+1000);
        final RootImageSeriesIntent series=VisibleCaptureAuthority.fixtureSeriesIntent(visible,request,epoch,root,now.get(),utc::get);
        final AtomicInteger burns=new AtomicInteger(),opens=new AtomicInteger(),decodes=new AtomicInteger(),queries=new AtomicInteger();
        final Set<Integer> slots=new HashSet<>();final List<Runnable> timers=new ArrayList<>();
        Job(){series.requestHandoff(10);series.hiddenHandoff(20);assertFalse(series.live());}
    }
    private static final class FakeMat {
        final byte[] bgr;boolean destroyed;int reads;
        FakeMat(byte[] gray){bgr=new byte[gray.length*3];for(int i=0;i<gray.length;i++)Arrays.fill(bgr,i*3,i*3+3,gray[i]);}
        Pointer pointer(){return new Pointer(30){@Override public void read(long offset,byte[] out,int start,int length){
            if(destroyed)throw new AssertionError("Read after fake native image destruction");reads++;System.arraycopy(bgr,Math.toIntExact(offset),out,start,length);
        }};}
        void destroy(){assertFalse(destroyed);Arrays.fill(bgr,(byte)0);destroyed=true;}
    }
    private static final class FakeTasker {
        long id;int posts;boolean quiet,destroyed;
        void post(long accepted){assertEquals(0,posts);assertTrue(accepted>0);id=accepted;posts++;}
        void returned(long exact){assertEquals(id,exact);quiet=true;}
        void destroy(){assertTrue(quiet);assertFalse(destroyed);destroyed=true;}
    }
    private static final class Frame implements AutoCloseable {
        final Job job;final int index;final Object requestOwner=new Object(),holder=new Object();
        final long deadline;final RealCaptureClaim.Grant grant;final ReadOnlyTwoStageRun.Capture capture;
        final AtomicBoolean localCancelled=new AtomicBoolean();final BackendPorts.RevocationFence fence=new BackendPorts.RevocationFence();
        final AtomicInteger ownBurns=new AtomicInteger(),ownOpens=new AtomicInteger(),ownDecodes=new AtomicInteger(),ownQueries=new AtomicInteger();
        final ReviewShizukuPngBinding.Binding binding;final CaptureSessionRegistry.Entry entry;
        final byte[] png;final boolean mismatchRaw;boolean epochHeld,released,imageErased,imageRecycled,recognizerReleased;
        int clientCloses;OnceBackend.Owner backendOwner;BackendPorts.FrameView actualView;
        ReadOnlyTwoStageRun.Observation observation;MaaImageBufferAudit audit;MaaImageBufferAudit.Result imageResult;
        FakeMat mat;FakeTasker tasker;CaptureSessionRuntime.SeriesCompletion completion;
        Frame(Job job,int index,CaptureSessionRuntime.SeriesCompletion previous,boolean mismatchRaw)throws Exception{
            this.job=job;this.index=index;this.mismatchRaw=mismatchRaw;deadline=job.now.get()+10_000;
            grant=VisibleCaptureAuthority.fixtureSeriesGrant(job.series,index,job.epoch,requestOwner,job.now.get(),deadline,previous);
            assertFalse(RealCaptureClaim.live(grant));
            var source=BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,job.now.get());
            assertEquals(BingNavigationSnapshot.Status.READY,source.status());assertTrue(job.series.sourceMatches(index,source.ready()));
            capture=ReadOnlyTwoStageRun.Capture.moreFamilyDiagnostic(requestOwner,"fixture-series-frame-"+index,W,H,0,job.now.get(),()->!job.series.current(job.now.get()));
            byte[] pixels=AutonomousCardFixture.pixels(false,false);if(index==2)pixels[0]=42;
            try{png=RootAuthorityFixture.png(pixels,W,H);}finally{Arrays.fill(pixels,(byte)0);}
            BackendPorts.ClaimBoundary claim=owner->{
                if(!epochHeld||localCancelled.get()||!job.series.current(job.now.get()))return new BackendPorts.ClaimReceipt(owner,BackendPorts.ClaimDecision.DENIED);
                RealCaptureClaim.Outcome outcome=RealCaptureClaim.consume(grant,job.epoch,requestOwner,job.now.get(),(id,metadata)->{
                    assertTrue(id.matches("[a-f0-9-]{36}"));assertTrue(metadata.contains("maxFrames=1"));assertTrue(metadata.contains("noActions=true"));
                    assertTrue(job.slots.add(index));job.burns.incrementAndGet();ownBurns.incrementAndGet();
                });
                return new BackendPorts.ClaimReceipt(owner,outcome==RealCaptureClaim.Outcome.CONSUMED?BackendPorts.ClaimDecision.CLAIMED:BackendPorts.ClaimDecision.DENIED);
            };
            BackendPorts.Recognizer recognizer=new BackendPorts.Recognizer(){
                public BackendPorts.Recognition recognize(OnceBackend.Owner owner,BackendPorts.FrameView view,BackendPorts.StopSignal stop)throws Exception{
                    backendOwner=owner;actualView=view;byte[] gray=new byte[view.size()];
                    try{
                        for(int i=0;i<gray.length;i++)gray[i]=(byte)view.unsignedByte(i);
                        mat=new FakeMat(gray);if(mismatchRaw)mat.bgr[0]^=1;tasker=new FakeTasker();
                        MaaImageBufferAudit.Binding exact=new MaaImageBufferAudit.Binding(this,owner,requestOwner,holder,view,new Pointer(10),new Pointer(20));
                        assertSame(view,exact.frame());assertSame(owner,exact.backendOwner());assertSame(requestOwner,exact.requestOwner());
                        audit=new MaaImageBufferAudit(exact,true);Pointer raw=mat.pointer();
                        MaaImageBufferAudit.ImageApi api=new MaaImageBufferAudit.ImageApi(){
                            public Pointer MaaImageBufferGetRawData(Pointer image){assertEquals(20,Pointer.nativeValue(image));return raw;}
                            public int MaaImageBufferWidth(Pointer image){return W;}public int MaaImageBufferHeight(Pointer image){return H;}
                            public int MaaImageBufferChannels(Pointer image){return 3;}public int MaaImageBufferType(Pointer image){return 16;}
                        };
                        audit.before(api,exact,stop);long id=100+index;tasker.post(id);audit.posted(exact,id);job.queries.incrementAndGet();ownQueries.incrementAndGet();
                        tasker.returned(id);audit.after(api,exact,id,tasker.quiet,stop);
                        var rows=AutonomousCardFixture.rows(false,false);audit.observeRows(rows);audit.observeGeometry(gray,W,H,stop);
                        observation=capture.prepare(gray,W,H,rows,stop::requested);
                        assertNotNull(observation);assertTrue(observation.validNativeShape());assertSame(capture,observation.capture);
                        return new BackendPorts.Recognition(owner,true);
                    }finally{Arrays.fill(gray,(byte)0);}
                }
                public void release(OnceBackend.Owner owner){assertSame(backendOwner,owner);mat.destroy();tasker.destroy();recognizerReleased=true;
                    imageResult=audit.result(recognizerReleased,mat.destroyed,tasker.destroyed);}
                public BackendPorts.Completion completion(OnceBackend.Owner owner){assertSame(backendOwner,owner);boolean clean=recognizerReleased&&mat.destroyed&&tasker.destroyed&&audit.scratchClean();
                    return new BackendPorts.Completion(owner,clean?BackendPorts.Released.CONFIRMED:BackendPorts.Released.UNKNOWN,clean?BackendPorts.Quiet.CONFIRMED:BackendPorts.Quiet.UNKNOWN);}
            };
            binding=ReviewShizukuPngBinding.fixtureImageAudit(claim,()->{
                job.opens.incrementAndGet();ownOpens.incrementAndGet();ByteArrayInputStream input=new ByteArrayInputStream(png);
                return new PngCaptureSession.Process(){
                    public BoundedPngGray.Input adoptStdout(){return (bytes,off,len,wait)->input.read(bytes,off,len);}
                    public boolean waitForExit(int millis){return true;}public int exitCode(){return 0;}
                    public void destroy(){fail("Clean fixture should not destroy");}
                    public boolean closeDescriptors()throws Exception{assertEquals(0,clientCloses);input.close();clientCloses++;return true;}
                };
            },()->{if(!epochHeld||localCancelled.get()||!job.series.current(job.now.get())||!RealCaptureClaim.current(grant,job.now.get()))throw new SecurityException("Fixture gate revoked");},
                fence,recognizer,Runnable::run,()->0L,W,H,(encoded,length)->{
                    job.decodes.incrementAndGet();ownDecodes.incrementAndGet();assertEquals(png.length,length);
                    for(int i=0;i<length;i++)assertEquals(png[i],encoded[i]);
                    // Public JDK desktop APIs remain runtime-only: Android's unit-test
                    // bootclasspath deliberately does not expose java.desktop at compile time.
                    Class<?> imageIO=Class.forName("javax.imageio.ImageIO");
                    imageIO.getMethod("setUseCache",boolean.class).invoke(null,false);
                    Object bitmap=imageIO.getMethod("read",InputStream.class).invoke(null,new ByteArrayInputStream(encoded,0,length));assertNotNull(bitmap);
                    Class<?> bufferedImage=Class.forName("java.awt.image.BufferedImage");
                    assertTrue(bufferedImage.isInstance(bitmap));
                    assertEquals(W,bufferedImage.getMethod("getWidth").invoke(bitmap));assertEquals(H,bufferedImage.getMethod("getHeight").invoke(bitmap));
                    Method getRGB=bufferedImage.getMethod("getRGB",int.class,int.class,int.class,int.class,int[].class,int.class,int.class);
                    Method setRGB=bufferedImage.getMethod("setRGB",int.class,int.class,int.class,int.class,int[].class,int.class,int.class);
                    Method flush=bufferedImage.getMethod("flush");
                    return new PngImageAudit.Image(){
                        public PngImageAudit.Facts facts(){return new PngImageAudit.Facts(W,H,PngImageAudit.Config.ARGB_8888,0,true,false,false,true,false);}
                        public void argbRow(int y,int[] row)throws Exception{getRGB.invoke(bitmap,0,y,W,1,row,0,W);}
                        public void erase()throws Exception{assertFalse(imageErased);int[] zeros=new int[W];for(int y=0;y<H;y++)setRGB.invoke(bitmap,0,y,W,1,zeros,0,W);imageErased=true;}
                        public void recycle()throws Exception{assertTrue(imageErased);assertFalse(imageRecycled);flush.invoke(bitmap);imageRecycled=true;}
                    };
                });
            assertTrue(job.epoch.retain(this));epochHeld=true;
            entry=job.registry.retain(this,job.epoch,deadline,this::cancelFrameOnly,()->{},()->released,task->{task.run();return true;});
            job.registry.arm(entry,(end,task)->job.timers.add(task),job.now::get);
        }
        void cancelFrameOnly(){localCancelled.set(true);fence.revoke();binding.source().requestStop();binding.backend().cancel(binding.backend().snapshot().owner());}
        void run(){
            OnceBackend.Result result=binding.backend().start();assertEquals(OnceBackend.Phase.COMPLETED,result.phase());assertEquals(OnceBackend.Memory.WIPED,result.memory());
            assertEquals(1,ownBurns.get());assertEquals(1,ownOpens.get());assertEquals(1,ownDecodes.get());assertEquals(1,ownQueries.get());assertEquals(1,clientCloses);
            assertTrue(imageErased&&imageRecycled&&recognizerReleased);assertTrue(binding.imageAudit().isCleanupConfirmed());assertEquals(PngImageAudit.Status.MATCH,binding.imageAudit().status());
            assertNotNull(binding.cleanReceipt());assertFalse(binding.cleanReceipt().liveTransport());assertSame(backendOwner,result.owner());
            assertThrows(IllegalStateException.class,()->actualView.unsignedByte(0));
            var source=binding.source().cleanupSnapshot(result.owner());assertTrue(source.ownerMatched()&&source.sourceReleased()&&source.sourceQuiet()&&source.ownerQuiet());
            assertNotNull(imageResult);assertTrue(imageResult.cleanupQualified());assertEquals(1,imageResult.queryPosts());assertTrue(imageResult.allOcrGeometry().nativeRows()>0);
            assertTrue(audit.scratchClean());for(byte b:mat.bgr)assertEquals(0,b);
            if(mismatchRaw){assertEquals(MaaImageBufferAudit.Status.PIXEL_MISMATCH,imageResult.status());
                assertThrows(SecurityException.class,()->CaptureSessionRuntime.SeriesCompletion.fixture(job.series,index,job.epoch,requestOwner,deadline,binding,observation,imageResult,job.now.get()));}
            else{assertEquals(MaaImageBufferAudit.Status.VERIFIED_EQUAL,imageResult.status());assertNotNull(imageResult.fingerprint());
                assertTrue(CaptureSessionRuntime.completeSeriesAudits(binding.imageAudit(),imageResult));}
            released=true;job.epoch.release(this);epochHeld=false;job.registry.releaseIfClosed(entry);assertFalse(job.registry.retaining());assertFalse(job.epoch.retaining());
            if(!mismatchRaw){completion=CaptureSessionRuntime.SeriesCompletion.fixture(job.series,index,job.epoch,requestOwner,deadline,binding,observation,imageResult,job.now.get());assertNotNull(completion);}
            String summary=CaptureSessionRuntime.seriesPngSummary(binding.imageAudit());assertFalse(summary.contains(binding.imageAudit().sourceSha256()));assertFalse(summary.contains("Hash="));assertFalse(summary.contains("hash="));
        }
        @Override public void close(){Arrays.fill(png,(byte)0);}
    }
    @Test public void actualThreeFramePipelineAndOpaqueComparisons()throws Exception{
        Job job=new Job();List<OnceBackend.Owner> owners=new ArrayList<>();MaaImageBufferAudit.Fingerprint first=null,previous=null;CaptureSessionRuntime.SeriesCompletion prior=null;
        for(int i=1;i<=3;i++){
            try(Frame frame=new Frame(job,i,prior,false)){
                assertTrue(job.registry.retaining()&&job.epoch.retaining());
                for(int old=0;old<i-1;old++){job.timers.get(old).run();assertTrue(job.series.current(job.now.get()));assertFalse(frame.localCancelled.get());}
                frame.run();assertFalse(owners.contains(frame.backendOwner));owners.add(frame.backendOwner);
                var current=frame.imageResult.fingerprint();if(i==1)first=current;
                else{var f=MaaImageBufferAudit.compare(first,current);var p=MaaImageBufferAudit.compare(previous,current);
                    assertEquals(MaaImageBufferAudit.Check.YES,f.sameDimensions());assertEquals(MaaImageBufferAudit.Check.YES,f.middleEqual());
                    assertEquals(i==2?MaaImageBufferAudit.Check.NO:MaaImageBufferAudit.Check.YES,f.wholeEqual());assertEquals(MaaImageBufferAudit.Check.NO,p.wholeEqual());}
                previous=current;prior=frame.completion;job.now.addAndGet(1000);
            }
        }
        assertEquals(3,job.burns.get());assertEquals(3,job.opens.get());assertEquals(3,job.decodes.get());assertEquals(3,job.queries.get());assertEquals(Set.of(1,2,3),job.slots);
        var previousCompletion=prior;assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureSeriesGrant(job.series,4,job.epoch,new Object(),job.now.get(),job.now.get()+5000,previousCompletion));
    }
    @Test public void rawMismatchCannotProduceNextFrameCompletion()throws Exception{
        Job job=new Job();try(Frame frame=new Frame(job,1,null,true)){frame.run();assertNull(frame.completion);
            assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureSeriesGrant(job.series,2,job.epoch,new Object(),job.now.get()+1,job.now.get()+5000,null));assertEquals(1,job.opens.get());}
    }
    @Test public void oldTimerAlreadyPastRegistryCheckCannotCancelNewSeriesFrame()throws Exception{
        Job job=new Job();Object oldGraph=new Object(),newGraph=new Object();AtomicBoolean oldClosed=new AtomicBoolean(),newClosed=new AtomicBoolean(),oldCancel=new AtomicBoolean(),newCancel=new AtomicBoolean();
        CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);AtomicReference<Throwable> workerFailure=new AtomicReference<>();
        assertTrue(job.epoch.retain(oldGraph));var old=job.registry.retain(oldGraph,job.epoch,2000,()->{
            entered.countDown();try{if(!resume.await(2,TimeUnit.SECONDS))throw new AssertionError("Timed out waiting to release old cancel");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            oldCancel.set(true); // The Runtime fix deliberately grants no parent-cancel capability here.
        },()->{},oldClosed::get,task->{task.run();return true;});
        Thread timer=new Thread(()->{try{job.registry.request(old);}catch(Throwable failed){workerFailure.set(failed);}},"fixture-old-timer");timer.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
        oldClosed.set(true);job.epoch.release(oldGraph);job.registry.releaseIfClosed(old);assertTrue(job.epoch.retain(newGraph));
        var next=job.registry.retain(newGraph,job.epoch,4000,()->newCancel.set(true),()->{},newClosed::get,task->{task.run();return true;});
        resume.countDown();timer.join(2000);assertFalse(timer.isAlive());assertNull(workerFailure.get());assertTrue(oldCancel.get());assertFalse(newCancel.get());assertTrue(job.series.current(job.now.get()));assertTrue(job.registry.retaining());assertTrue(job.epoch.retaining());
        newClosed.set(true);job.epoch.release(newGraph);job.registry.releaseIfClosed(next);assertFalse(job.registry.retaining());
    }
}
