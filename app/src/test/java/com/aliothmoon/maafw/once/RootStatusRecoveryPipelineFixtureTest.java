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
public final class RootStatusRecoveryPipelineFixtureTest {
    private static final int W=IndependentCurrentCardTest.W,H=IndependentCurrentCardTest.H;
    private enum Picture { EMPTY, COMPLETE, NONNATIVE, CONFLICT }
    private enum Nav { NORMAL, INITIAL_UNKNOWN, RESOURCE_UNKNOWN, GATE_SOURCE_CHANGED, RETURN_SOURCE_CHANGED }
    private static final BingNavigationSnapshot.Profile PROFILE=new BingNavigationSnapshot.Profile(10073,10049,new BingNavigationSnapshot.Shape(W,H,0));
    private static String full(String value){return value.replace("Requested w=523 h=1000","Requested w="+W+" h="+H);}
    private static String templateWindows(){return full(RootReadOnlyAnimationGuardTest.w());}
    private static String templateActivities(){return RootReadOnlyAnimationGuardTest.a();}
    private static String browserWindows(boolean changed){
        String base=templateWindows().replace(BingNavigationSnapshot.TEMPLATE,BingNavigationSnapshot.BROWSER).replace("Window{aaa","Window{bbb").replace("ActivityRecord{ddd","ActivityRecord{ccc");
        if(changed)base=base.replace("Window{bbb","Window{beef");
        return base+full(BingNavigationFixture.window(BingNavigationSnapshot.TEMPLATE,"aaa",false))
            +"    mActivityRecord=ActivityRecord{ddd u0 "+BingNavigationSnapshot.TEMPLATE+" t15}\n"+RootReadOnlyAnimationGuardTest.animator(BingNavigationSnapshot.TEMPLATE,true);
    }
    private static String browserActivities(){
        String base=templateActivities().replace(BingNavigationSnapshot.TEMPLATE,BingNavigationSnapshot.BROWSER).replace("ActivityRecord{ddd","ActivityRecord{ccc")
            .replace("visible=true visibleRequested=true mode=fullscreen translucent=false sz=1","visible=true visibleRequested=true mode=fullscreen translucent=false sz=2");
        return base.replace("  * Task{abc #41",BingNavigationFixture.activityBlock(BingNavigationSnapshot.TEMPLATE,"ddd","15",false)+"  * Task{abc #41");
    }
    private static BingNavigationSnapshot.Result template(long now,boolean changed){
        String w=templateWindows();if(changed)w=w.replace("Window{aaa","Window{affe");
        return BingNavigationSnapshot.parseRootReadOnly(w,templateActivities(),PROFILE,RootReadOnlyAnimationGuardTest.G,now,now);
    }
    private static final class FakeBackLease implements AutoCloseable {
        final byte[] scratch=new byte[32];boolean closed;FakeBackLease(){Arrays.fill(scratch,(byte)90);}public void close(){Arrays.fill(scratch,(byte)0);closed=true;}
    }
    private static final class Job {
        final Object visible=new Object(),request=new Object(),root=new Object();
        final RealCaptureClaim.ProcessEpoch epoch=new RealCaptureClaim.ProcessEpoch();final CaptureSessionRegistry registry=new CaptureSessionRegistry();
        final AtomicLong now=new AtomicLong(1000),utc=new AtomicLong(RootStatusRecoveryScope.STARTS_UTC+1000);
        final RootReturnReadIntent intent=VisibleCaptureAuthority.fixtureStatusRecoveryIntent(visible,request,epoch,root,now.get(),utc::get);
        final AtomicInteger burns=new AtomicInteger(),opens=new AtomicInteger(),decodes=new AtomicInteger(),queries=new AtomicInteger(),backBurns=new AtomicInteger(),backs=new AtomicInteger();
        final Set<Integer> slots=new HashSet<>();final List<Runnable> timers=new ArrayList<>();final Set<Object> sources=Collections.newSetFromMap(new IdentityHashMap<>());
        final boolean browser;Nav nav=Nav.NORMAL;boolean afterBack,throwBack;int initialReads;FakeBackLease backLease;
        boolean navigationHeld,navigationClosed;CaptureSessionRegistry.Entry navigationEntry;
        final RootReturnReadController.Ports ports=new RootReturnReadController.Ports(){
            public long now(){return Job.this.now.get();}public boolean current(){return intent.current(now());}
            public BingNavigationSnapshot.RecoveryResult initial(){throw new AssertionError("Legacy recovery initial is forbidden for statusRecovery");}
            public BingNavigationSnapshot.StatusRecoveryInitial statusInitial(){initialReads++;
                if(nav==Nav.INITIAL_UNKNOWN)return BingNavigationSnapshot.parseStatusRecovery("","",PROFILE,RootReadOnlyAnimationGuardTest.G,now(),now());
                return BingNavigationSnapshot.parseStatusRecovery(browser?browserWindows(nav==Nav.GATE_SOURCE_CHANGED&&initialReads>1):templateWindows(),
                    browser?browserActivities():templateActivities(),PROFILE,RootReadOnlyAnimationGuardTest.G,now(),now());
            }
            public BingNavigationSnapshot.Result observe(){return template(now(),nav==Nav.RETURN_SOURCE_CHANGED);}
            public void burnReturn(ReviewShizukuBackActor.BackTarget target){if(!backBurns.compareAndSet(0,1))throw new SecurityException("Independent return already burned");}
            public void pause(){Job.this.now.incrementAndGet();}public String diagnostic(){return "fixtureMetadata=SCALAR_ONLY";}
            public boolean metadataClean(){return nav!=Nav.RESOURCE_UNKNOWN;}
        };
        final ReviewShizukuBackActor actor=new ReviewShizukuBackActor(()->new ReviewShizukuBackActor.Session(){
            public ReviewShizukuBackActor.DisplayShape display(){return new ReviewShizukuBackActor.DisplayShape(W,H,0);}public boolean stillReady(){return true;}
            public ReviewShizukuBackActor.PendingBack dispatch(ReviewShizukuBackActor.BackTarget target,ReviewShizukuBackActor.FinalGate gate)throws Exception{
                gate.requireValid();backs.incrementAndGet();if(throwBack){backLease=new FakeBackLease();throw new AssertionError("Synthetic potential dispatch Error, no real child");}afterBack=true;
                return new ReviewShizukuBackActor.PendingBack(){public boolean waitForExit(long millis){return true;}public int exitCode(){return 0;}public void destroy(){fail("No abort expected");}public void close(){}};
            }
        });
        final RootReturnReadController controller=new RootReturnReadController(intent,ports,actor);
        RootReturnReadController.Result returned;
        Job(boolean browser){this.browser=browser;assertTrue(intent.statusRecovery());assertFalse(intent.currentOnly());assertFalse(intent.live());}
        RootReturnReadController.Result attemptNavigation(){
            assertTrue(epoch.retain(controller));navigationHeld=true;
            navigationEntry=registry.retain(controller,epoch,intent.sessionEnd(),intent::cancel,()->{},()->navigationClosed,r->{r.run();return true;});
            var result=controller.execute();if(controller.resourcesKnown()){navigationClosed=true;epoch.release(controller);navigationHeld=false;registry.releaseIfClosed(navigationEntry);}return result;
        }
        void recover(){returned=attemptNavigation();assertTrue(returned.summary(),returned.readyForRead());assertEquals(browser,returned.returned());assertTrue(controller.resourcesKnown());
            intent.returned(returned,now.get());assertEquals(browser?1:0,backBurns.get());assertEquals(browser?1:0,backs.get());assertFalse(registry.retaining()||epoch.retaining());}
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
            grant=VisibleCaptureAuthority.fixtureStatusRecoveryGrant(job.intent,index,job.epoch,requestOwner,job.now.get(),previous);
            assertFalse(RealCaptureClaim.live(grant));
            var source=template(job.now.get(),false);
            assertEquals(BingNavigationSnapshot.Status.READY,source.status());assertTrue(job.intent.sourceMatches(source.ready()));
            capture=ReadOnlyTwoStageRun.Capture.moreCurrentCards(requestOwner,"fixture-status-frame-"+index,W,H,0,job.now.get(),()->!job.intent.current(job.now.get()));
            byte[] pixels;
            if(picture==Picture.EMPTY||picture==Picture.NONNATIVE){pixels=new byte[W*H];Arrays.fill(pixels,(byte)244);
                rows=new ArrayList<>(List.of(new FrameStructureEvidence.Line("Search",picture==Picture.NONNATIVE?-1:10,20,90,20,.99),new FrameStructureEvidence.Line("Home",10,950,90,20,.99)));}
            else{pixels=SelectedPendingBusinessTest.afterPixels();rows=new ArrayList<>(SelectedPendingBusinessTest.afterRows());
                if(picture==Picture.CONFLICT)rows.add(new FrameStructureEvidence.Line("Do you know the answer?",150,740,600,30,.99));}
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
    @Test public void strictTemplateOrStrictBrowserThenIndependentReadsReportOnlyTwoAnonymousTicks()throws Exception{
        for(boolean browser:new boolean[]{false,true}){Job job=new Job(browser);job.recover();long end=job.intent.sessionEnd(),readEnd=job.intent.readDeadline();CaptureSessionRuntime.ReturnReadCompletion previous=null;
            for(int i=1;i<=3;i++){try(Frame frame=new Frame(job,i,previous,i<3?Picture.EMPTY:Picture.COMPLETE,false,false)){
                frame.observe();assertEquals(readEnd,RealCaptureClaim.deadline(frame.grant));assertEquals(readEnd,job.intent.readDeadline());assertEquals(end,job.intent.sessionEnd());
                if(i<3)previous=frame.proof();else{var cards=frame.observation.currentCards();assertNotNull(cards);
                    long complete=cards.cards().stream().filter(c->c.status()==com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentReadStatus.COMPLETE&&c.identity()==com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentReadIdentity.UNNAMED&&c.points()==5).count();
                    assertEquals(2,complete);assertTrue(cards.summary().contains("completeFamily=false businessEligible=false"));assertThrows(SecurityException.class,frame::proof);}
            }job.now.incrementAndGet();}
            assertEquals(3,job.burns.get());assertEquals(3,job.opens.get());assertEquals(3,job.queries.get());assertEquals(3,job.sources.size());assertEquals(browser?1:0,job.backs.get());
            var prior=previous;assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureStatusRecoveryGrant(job.intent,4,job.epoch,new Object(),job.now.get(),prior));
            assertFalse(job.controller.execute().readyForRead());assertEquals(browser?1:0,job.backs.get());assertThrows(SecurityException.class,()->job.intent.returned(job.returned,job.now.get()));
        }
    }
    @Test public void unknownInitialMetadataOrChangingSourceNeverGrantsReadOrExtraInput(){
        for(Nav nav:List.of(Nav.INITIAL_UNKNOWN,Nav.RESOURCE_UNKNOWN,Nav.GATE_SOURCE_CHANGED,Nav.RETURN_SOURCE_CHANGED)){
            Job job=new Job(true);job.nav=nav;var result=job.attemptNavigation();assertFalse(nav.name(),result.readyForRead());
            assertThrows(SecurityException.class,()->job.intent.returned(result,job.now.get()));assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureStatusRecoveryGrant(job.intent,1,job.epoch,new Object(),job.now.get(),null));
            assertEquals(nav==Nav.RETURN_SOURCE_CHANGED?1:0,job.backs.get());assertEquals(0,job.opens.get());assertFalse(job.controller.execute().readyForRead());assertTrue(job.backs.get()<=1);
            if(nav==Nav.RESOURCE_UNKNOWN)assertTrue(job.registry.retaining()&&job.epoch.retaining());
        }
    }
    @Test public void mismatchedNonnativeNonemptyHoldAndUnknownCleanupNeverMintAnotherRead()throws Exception{
        for(int mode=0;mode<4;mode++){Job job=new Job(false);job.recover();Picture picture=mode==1?Picture.NONNATIVE:mode==2?Picture.CONFLICT:Picture.EMPTY;
            try(Frame frame=new Frame(job,1,null,picture,mode==0,mode==3)){frame.observe();assertThrows(SecurityException.class,frame::proof);
                assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureStatusRecoveryGrant(job.intent,2,job.epoch,new Object(),job.now.get(),null));assertEquals(mode==3,job.registry.retaining());assertEquals(mode==3,job.epoch.retaining());assertEquals(0,job.backs.get());}}
        Job expired=new Job(false);expired.recover();try(Frame frame=new Frame(expired,1,null,Picture.EMPTY,false,false)){frame.observe();var previous=frame.proof();expired.now.set(expired.intent.readDeadline());
            assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureStatusRecoveryGrant(expired.intent,2,expired.epoch,new Object(),expired.now.get(),previous));}
    }
    @Test public void potentialBackErrorStaysUnknownEvenAfterRepeatAndExplicitFakeCleanup(){
        Job job=new Job(true);job.throwBack=true;var result=job.attemptNavigation();
        try{assertFalse(result.readyForRead());assertFalse(result.clean());assertFalse(job.controller.resourcesKnown());assertEquals(1,job.backs.get());assertEquals(1,job.backBurns.get());
            assertTrue(job.registry.retaining()&&job.epoch.retaining());assertThrows(SecurityException.class,()->job.intent.returned(result,job.now.get()));
            assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureStatusRecoveryGrant(job.intent,1,job.epoch,new Object(),job.now.get(),null));
            assertFalse(job.controller.execute().readyForRead());assertFalse(job.controller.resourcesKnown());assertEquals(1,job.backs.get());assertEquals(0,job.opens.get());
        }finally{assertNotNull(job.backLease);job.backLease.close();for(byte b:job.backLease.scratch)assertEquals(0,b);
            // No actor static quarantine is touched by Error; no production pool is reset.
            assertTrue(job.backLease.closed);assertFalse(job.controller.resourcesKnown());assertTrue(job.registry.retaining()&&job.epoch.retaining());}
    }
}