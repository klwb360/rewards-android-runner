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
public final class RootCurrentReadPipelineFixtureTest {
    private static final int W=AutonomousCardFixture.W,H=AutonomousCardFixture.H;
    private enum Picture { EMPTY, PENDING, NONNATIVE, CONFLICT }
    private static BingNavigationSnapshot.CurrentTemplateResult strictTemplate(long now,BingNavigationSnapshot.Kind kind){
        String w=RootReadOnlyAnimationGuardTest.w(),a=RootReadOnlyAnimationGuardTest.a();
        if(kind==BingNavigationSnapshot.Kind.BROWSER){w=w.replace(BingNavigationSnapshot.TEMPLATE,BingNavigationSnapshot.BROWSER);a=a.replace(BingNavigationSnapshot.TEMPLATE,BingNavigationSnapshot.BROWSER);}
        return BingNavigationSnapshot.parseCurrentTemplate(w,a,BingNavigationFixture.PROFILE,RootReadOnlyAnimationGuardTest.G,now,now);
    }
    /** No input actor, business Run, recovery controller or old return capability exists in this graph. */
    private static final class Job {
        final Object visible=new Object(),request=new Object(),root=new Object();
        final RealCaptureClaim.ProcessEpoch epoch=new RealCaptureClaim.ProcessEpoch();final CaptureSessionRegistry registry=new CaptureSessionRegistry();
        final AtomicLong now=new AtomicLong(1000),utc=new AtomicLong(RootCurrentCardScope.STARTS_UTC+1000);
        final RootReturnReadIntent intent=VisibleCaptureAuthority.fixtureCurrentReadIntent(visible,request,epoch,root,now.get(),utc::get);
        final AtomicInteger burns=new AtomicInteger(),opens=new AtomicInteger(),decodes=new AtomicInteger(),queries=new AtomicInteger();
        final Set<Integer> slots=new HashSet<>();final List<Runnable> timers=new ArrayList<>();final Set<Object> sources=Collections.newSetFromMap(new IdentityHashMap<>());
        void anchor(){assertFalse(intent.live());assertTrue(intent.currentOnly());var proof=strictTemplate(now.get(),BingNavigationSnapshot.Kind.TEMPLATE);
            assertEquals(BingNavigationSnapshot.Status.READY,proof.status());assertNotNull(proof.source());intent.anchorCurrent(proof.source(),now.get());
            assertTrue(intent.navigationSummary().contains("returnInvoked=false"));assertTrue(intent.navigationSummary().contains("taskTaps=0"));}
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
        volatile CountDownLatch cancelEntered,cancelResume;final AtomicBoolean localCancelled=new AtomicBoolean();final BackendPorts.RevocationFence fence=new BackendPorts.RevocationFence();
        final AtomicInteger ownBurns=new AtomicInteger(),ownOpens=new AtomicInteger(),ownDecodes=new AtomicInteger(),ownQueries=new AtomicInteger();
        final ReviewShizukuPngBinding.Binding binding;final CaptureSessionRegistry.Entry entry;
        final byte[] png;final boolean mismatchRaw,unknownNative;final Picture picture;final List<FrameStructureEvidence.Line> rows;MaaRecognizer.RootMarkerDiagnostic markers;boolean epochHeld,released,imageErased,imageRecycled,recognizerReleased;
        int clientCloses;OnceBackend.Owner backendOwner;BackendPorts.FrameView actualView;
        ReadOnlyTwoStageRun.Observation observation;MaaImageBufferAudit audit;MaaImageBufferAudit.Result imageResult;
        FakeMat mat;FakeTasker tasker;CaptureSessionRuntime.ReturnReadCompletion completion;
        Frame(Job job,int index,CaptureSessionRuntime.ReturnReadCompletion previous,Picture picture,boolean mismatchRaw,boolean unknownNative)throws Exception{
            this.job=job;this.index=index;this.mismatchRaw=mismatchRaw;this.unknownNative=unknownNative;this.picture=picture;deadline=job.intent.readDeadline();
            grant=VisibleCaptureAuthority.fixtureCurrentReadGrant(job.intent,index,job.epoch,requestOwner,job.now.get(),previous);
            assertFalse(RealCaptureClaim.live(grant));
            var source=strictTemplate(job.now.get(),BingNavigationSnapshot.Kind.TEMPLATE);
            assertEquals(BingNavigationSnapshot.Status.READY,source.status());assertTrue(job.intent.sourceMatches(source.source().ready()));
            capture=ReadOnlyTwoStageRun.Capture.moreCurrentCards(requestOwner,"fixture-current-frame-"+index,W,H,0,job.now.get(),()->!job.intent.current(job.now.get()));
            byte[] pixels;
            if(picture==Picture.EMPTY||picture==Picture.NONNATIVE){pixels=new byte[W*H];Arrays.fill(pixels,(byte)244);
                rows=new ArrayList<>(List.of(new FrameStructureEvidence.Line("Search",picture==Picture.NONNATIVE?-1:10,20,90,20,.99),new FrameStructureEvidence.Line("Home",10,950,90,20,.99)));}
            else{pixels=AutonomousCardFixture.pixels(false,false);rows=AutonomousCardFixture.rows(false,false);
                if(picture==Picture.CONFLICT)for(int i=0;i<rows.size();i++){var row=rows.get(i);if(row.text().equals(AutonomousCardFixture.A))rows.set(i,new FrameStructureEvidence.Line("Unexpected body",row.x(),row.y(),row.width(),row.height(),row.score()));}}
            try{png=RootAuthorityFixture.png(pixels,W,H);}finally{Arrays.fill(pixels,(byte)0);}
            BackendPorts.ClaimBoundary claim=owner->{
                if(!epochHeld||localCancelled.get()||!job.intent.current(job.now.get()))return new BackendPorts.ClaimReceipt(owner,BackendPorts.ClaimDecision.DENIED);
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
                        MaaImageBufferAudit.Binding exact=new MaaImageBufferAudit.Binding(this,owner,requestOwner,capture,view,new Pointer(10),new Pointer(20));
                        assertSame(view,exact.frame());assertSame(owner,exact.backendOwner());assertSame(requestOwner,exact.requestOwner());
                        audit=new MaaImageBufferAudit(exact,true);Pointer raw=mat.pointer();
                        MaaImageBufferAudit.ImageApi api=new MaaImageBufferAudit.ImageApi(){
                            public Pointer MaaImageBufferGetRawData(Pointer image){assertEquals(20,Pointer.nativeValue(image));return raw;}
                            public int MaaImageBufferWidth(Pointer image){return W;}public int MaaImageBufferHeight(Pointer image){return H;}
                            public int MaaImageBufferChannels(Pointer image){return 3;}public int MaaImageBufferType(Pointer image){return 16;}
                        };
                        audit.before(api,exact,stop);long id=100+index;tasker.post(id);audit.posted(exact,id);job.queries.incrementAndGet();ownQueries.incrementAndGet();
                        tasker.returned(id);audit.after(api,exact,id,tasker.quiet,stop);
                        audit.observeRows(rows);audit.observeGeometry(gray,W,H,stop);markers=MaaRecognizer.rootMarkers(rows);
                        observation=capture.prepare(gray,W,H,rows,stop::requested);
                        assertNotNull(observation);assertEquals(picture!=Picture.NONNATIVE,observation.validNativeShape());assertSame(capture,observation.capture);
                        return new BackendPorts.Recognition(owner,true);
                    }finally{Arrays.fill(gray,(byte)0);}
                }
                public void release(OnceBackend.Owner owner){assertSame(backendOwner,owner);if(!unknownNative){mat.destroy();tasker.destroy();recognizerReleased=true;}
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
            },()->{if(!epochHeld||localCancelled.get()||!job.intent.current(job.now.get())||!RealCaptureClaim.current(grant,job.now.get()))throw new SecurityException("Fixture gate revoked");},
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
            assertTrue(job.sources.add(binding.source()));assertTrue(job.epoch.retain(this));epochHeld=true;
            entry=job.registry.retain(this,job.epoch,deadline,this::cancelFrameOnly,()->{},()->released,task->{task.run();return true;});
            job.registry.arm(entry,(end,task)->job.timers.add(task),job.now::get);
        }
        void cancelFrameOnly(){if(cancelEntered!=null){cancelEntered.countDown();try{if(!cancelResume.await(3,TimeUnit.SECONDS))throw new AssertionError("Old callback not resumed");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}localCancelled.set(true);fence.revoke();binding.source().requestStop();binding.backend().cancel(binding.backend().snapshot().owner());}
        void observe(){observe(true);}
        void observe(boolean releaseNow){
            var result=binding.backend().start();assertEquals(1,ownBurns.get());assertEquals(1,ownOpens.get());assertEquals(1,ownDecodes.get());assertEquals(1,ownQueries.get());assertEquals(1,clientCloses);
            assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(grant,job.epoch,requestOwner,job.now.get(),(id,m)->fail("Repeated claim burn")));
            assertEquals(PngImageAudit.Status.MATCH,binding.imageAudit().status());assertTrue(binding.imageAudit().isCleanupConfirmed());
            assertTrue(imageErased&&imageRecycled);assertTrue(audit.scratchClean());assertNotNull(imageResult);
            if(unknownNative){assertEquals(MaaImageBufferAudit.Status.UNKNOWN,imageResult.status());assertFalse(imageResult.cleanupQualified());assertNull(binding.cleanReceipt());assertEquals(OnceBackend.Memory.RETAINED,result.memory());}
            else{assertEquals(OnceBackend.Phase.COMPLETED,result.phase());assertEquals(OnceBackend.Memory.WIPED,result.memory());assertTrue(imageResult.cleanupQualified());assertNotNull(binding.cleanReceipt());assertThrows(IllegalStateException.class,()->actualView.unsignedByte(0));if(releaseNow)closeRoot();}
        }
        CaptureSessionRuntime.ReturnReadCompletion proof(){return CaptureSessionRuntime.ReturnReadCompletion.fixture(job.intent,index,job.epoch,requestOwner,binding,observation,imageResult,markers,job.now.get());}
        void closeRoot(){assertEquals(OnceBackend.Cleanup.CONFIRMED,binding.backend().snapshot().cleanup());released=true;job.epoch.release(this);epochHeld=false;job.registry.releaseIfClosed(entry);assertFalse(job.registry.retaining());assertFalse(job.epoch.retaining());}
        @Override public void close(){if(epochHeld){assertTrue(unknownNative);mat.destroy();tasker.destroy();recognizerReleased=true;imageResult=audit.result(true,true,true);var closed=binding.backend().refreshEvidence(backendOwner);assertEquals(OnceBackend.Memory.WIPED,closed.memory());closeRoot();}Arrays.fill(png,(byte)0);}
    }
    @Test public void freshTypedTemplateThenThreeAuditedFramesHaveNoInputAuthority()throws Exception{
        Job job=new Job();job.anchor();long deadline=job.intent.readDeadline();CaptureSessionRuntime.ReturnReadCompletion previous=null;
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureReturnReadGrant(job.intent,1,job.epoch,new Object(),job.now.get(),null));
        Set<OnceBackend.Owner> owners=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int i=1;i<=3;i++){try(Frame frame=new Frame(job,i,previous,i<3?Picture.EMPTY:Picture.PENDING,false,false)){
            frame.observe();assertTrue(owners.add(frame.backendOwner));assertEquals(deadline,RealCaptureClaim.deadline(frame.grant));assertEquals(deadline,job.intent.readDeadline());
            assertTrue(frame.capture.currentStatusOnly());assertTrue(job.intent.navigationSummary().contains("returnInvoked=false"));
            assertNotNull(frame.observation.currentCards());
            if(i<3){assertEquals(com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentBatchStatus.HOLD,frame.observation.currentCards().status());previous=frame.proof();}else{assertEquals(com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentBatchStatus.PROCESSED,frame.observation.currentCards().status());assertEquals(ReadOnlyTwoStageRun.CurrentFamilyState.TARGET_EXACT_PENDING,frame.observation.currentFamilyState());assertThrows(SecurityException.class,frame::proof);}
        }job.now.incrementAndGet();}
        assertEquals(3,job.burns.get());assertEquals(3,job.opens.get());assertEquals(3,job.decodes.get());assertEquals(3,job.queries.get());assertEquals(3,job.sources.size());
        var prior=previous;assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureCurrentReadGrant(job.intent,4,job.epoch,new Object(),job.now.get(),prior));
    }
    @Test public void browserUnknownStaleSemanticHoldAndUnknownCleanupCannotAuthorizeFurtherFrames()throws Exception{
        for(int mode=0;mode<3;mode++){Job job=new Job();BingNavigationSnapshot.CurrentTemplateResult proof;
            if(mode==0)proof=strictTemplate(job.now.get(),BingNavigationSnapshot.Kind.BROWSER);
            else if(mode==1)proof=BingNavigationSnapshot.parseCurrentTemplate("","",BingNavigationFixture.PROFILE,RootReadOnlyAnimationGuardTest.G,job.now.get(),job.now.get());
            else{proof=strictTemplate(job.now.get(),BingNavigationSnapshot.Kind.TEMPLATE);job.now.addAndGet(20_000);}
            var actual=proof;assertThrows(SecurityException.class,()->job.intent.anchorCurrent(actual.source(),job.now.get()));
            assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureCurrentReadGrant(job.intent,1,job.epoch,new Object(),job.now.get(),null));assertEquals(0,job.opens.get());
        }
        for(boolean unknown:new boolean[]{false,true}){Job job=new Job();job.anchor();try(Frame frame=new Frame(job,1,null,unknown?Picture.EMPTY:Picture.CONFLICT,false,unknown)){
            frame.observe();assertThrows(SecurityException.class,frame::proof);assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureCurrentReadGrant(job.intent,2,job.epoch,new Object(),job.now.get(),null));
            assertEquals(unknown,job.registry.retaining());assertEquals(unknown,job.epoch.retaining());assertTrue(job.intent.navigationSummary().contains("returnInvoked=false"));
        }}
    }
}