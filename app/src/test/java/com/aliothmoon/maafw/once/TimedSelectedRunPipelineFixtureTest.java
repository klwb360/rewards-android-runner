package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import com.sun.jna.Pointer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.Test;

/** Deferred-focus delta: before success+5000ms the synthetic current focus/window is UNKNOWN and must never be read.
 * Non-live, synthetic pixels and fake native pointers/actors; actual PNG/OwnedFrame,
 * audit, private issuer/completion, prepared adoption and Run state machines. */
public final class TimedSelectedRunPipelineFixtureTest {
    private static final int W=IndependentCurrentCardTest.W,H=IndependentCurrentCardTest.H;
    private enum Picture { EMPTY, PENDING, COMPLETE, CONFLICT }
    private enum Fault { NONE, CANCEL, PERMISSION, FOREIGN_TASK, FOREIGN_PACKAGE, UNKNOWN_GUARD, CLEANUP_UNKNOWN, POST_UNKNOWN, TAP_UNKNOWN }
    private static BingNavigationSnapshot.Result navigation(BingNavigationSnapshot.Kind kind,long now){
        String c=kind==BingNavigationSnapshot.Kind.TEMPLATE?BingNavigationSnapshot.TEMPLATE:BingNavigationSnapshot.BROWSER;
        String window=kind==BingNavigationSnapshot.Kind.TEMPLATE?"aaa":"bbb",activity=kind==BingNavigationSnapshot.Kind.TEMPLATE?"ddd":"eee";
        return BingNavigationSnapshot.parse(BingNavigationFixture.windows(c,window,activity,"15").replace("Requested w=523 h=1000","Requested w="+W+" h="+H),
            BingNavigationFixture.activities(c,activity,"15",true),new BingNavigationSnapshot.Profile(10073,10049,new BingNavigationSnapshot.Shape(W,H,0)),now,now);
    }
    private static final class Ports implements NativeAutonomousCardRun.FixturePorts {
        final AtomicLong now;final AtomicInteger tapBurns=new AtomicInteger(),backBurns=new AtomicInteger(),taps=new AtomicInteger(),backs=new AtomicInteger();
        BingNavigationSnapshot.Kind kind=BingNavigationSnapshot.Kind.TEMPLATE;boolean browserAfterTap,internalSearchAfterTap,permission=true,clean=true;
        Fault fault=Fault.NONE;int pauses,guards,earlyGuardReads,postReads,timedPostReads;final List<Long> guardTimes=new ArrayList<>();long tapAt=-1,successfulAt=-1,backAt=-1;Runnable cancel=()->{};
        final byte[] metadataScratch=new byte[32],tapScratch=new byte[32];boolean scratchClosed;
        com.aliothmoon.maafw.rewards.RewardRegionLocator.Box expectedIndicator;
        Ports(AtomicLong now){this.now=now;}
        public BingNavigationSnapshot.Shape shape(){return new BingNavigationSnapshot.Shape(W,H,0);}
        public boolean authorityMatches(BingNavigationSnapshot.Shape s){return permission&&shape().equals(s);}
        public boolean knownClean(){return clean;}
        public BingNavigationSnapshot.Result observe(BingNavigationSnapshot.Shape s){
            if(backs.get()>0){postReads++;if(fault==Fault.POST_UNKNOWN)return BingNavigationSnapshot.Result.unknown();}
            return authorityMatches(s)?navigation(kind,now.get()):BingNavigationSnapshot.Result.unknown();
        }
        public BingNavigationSnapshot.Result observeTimedReturn(BingNavigationSnapshot.Ready original,long observed){
            timedPostReads++;String w=TimedTaskGuardTest.windows().replace("w=523 h=1000","w="+W+" h="+H);
            String a=TimedTaskGuardTest.activities().replaceAll("(?m)^.*topResumedActivity=.*\\n","");assertFalse(a.contains("topResumedActivity="));
            if(fault==Fault.POST_UNKNOWN)w=w.replace("mActivityRecord=ActivityRecord{ddd","missingTemplateBinding=ActivityRecord{ddd");
            return BingNavigationSnapshot.parseTimedReturn(w,a,original.profile(),RootReadOnlyAnimationGuardTest.G,original,observed,observed);
        }
        public void consumeTap(ReviewShizukuTapActor.TapTarget t){assertEquals(1,tapBurns.incrementAndGet());assertNotNull(expectedIndicator);
            assertEquals((int)Math.floor(expectedIndicator.centerX()),t.x());assertEquals((int)Math.floor(expectedIndicator.centerY()),t.y());assertTrue(t.y()>=661&&t.y()<1069);}
        public void consumeReturn(ReviewShizukuBackActor.BackTarget t){assertEquals(1,backBurns.incrementAndGet());assertTrue(now.get()-successfulAt>=5000);}
        public void tapped(ReviewShizukuTapActor.TapTarget t){assertEquals(1,taps.incrementAndGet());tapAt=now.get();now.addAndGet(700);
            if(fault==Fault.TAP_UNKNOWN){Arrays.fill(tapScratch,(byte)90);throw new IllegalStateException("Synthetic dispatch uncertainty after command delay");}
            successfulAt=now.get();kind=browserAfterTap?BingNavigationSnapshot.Kind.BROWSER:BingNavigationSnapshot.Kind.TEMPLATE;}
        public void backed(ReviewShizukuBackActor.BackTarget t){assertTrue(now.get()-successfulAt>=5000);assertEquals(1,backs.incrementAndGet());backAt=now.get();kind=BingNavigationSnapshot.Kind.TEMPLATE;}
        public void pollPause(){
            assertEquals(0,backs.get());assertTrue(successfulAt>=0);assertEquals(0,guards);assertEquals(0,earlyGuardReads);if(pauses==1)assertEquals(successfulAt+4999,now.get());pauses++;now.set(successfulAt+(pauses==1?4999:5000));
            if(pauses==1){if(fault==Fault.CANCEL)cancel.run();if(fault==Fault.PERMISSION)permission=false;if(fault==Fault.CLEANUP_UNKNOWN){clean=false;Arrays.fill(metadataScratch,(byte)90);}}
            assertTrue(pauses<=2);
        }
        public BingNavigationSnapshot.TimedTaskGuard timedTaskGuard(BingNavigationSnapshot.Ready original,long observed){
            guards++;guardTimes.add(observed);if(observed-successfulAt<5000){earlyGuardReads++;return BingNavigationSnapshot.parseTimedTaskGuard("","",original.profile(),RootReadOnlyAnimationGuardTest.G,original,observed,observed);}String w=kind==BingNavigationSnapshot.Kind.BROWSER?TimedTaskGuardTest.browserWindows():TimedTaskGuardTest.windows();
            String a=kind==BingNavigationSnapshot.Kind.BROWSER?TimedTaskGuardTest.browserActivities():TimedTaskGuardTest.activities();
            w=w.replace("w=523 h=1000","w="+W+" h="+H);
            String component=kind==BingNavigationSnapshot.Kind.BROWSER?BingNavigationSnapshot.BROWSER:BingNavigationSnapshot.TEMPLATE;
            if(internalSearchAfterTap&&kind==BingNavigationSnapshot.Kind.BROWSER){String search="com.microsoft.bing/com.microsoft.sapphire.app.search.SearchActivity";
                w=w.replace(component,search);a=a.replace(component,search);component=search;
                if(guards==1)assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parseTimedReturn(w,a,original.profile(),RootReadOnlyAnimationGuardTest.G,original,observed,observed).status());}
            if(pauses>0&&fault==Fault.FOREIGN_TASK){w=w.replace("rootTaskId=15","rootTaskId=16").replace(" t15}"," t16}");a=a.replace("#15","#16").replace(" t15}"," t16}");}
            if(pauses>0&&fault==Fault.FOREIGN_PACKAGE){w=w.replace(component,"other.app/.ForeignActivity");a=a.replace(component,"other.app/.ForeignActivity");}
            if(pauses>0&&fault==Fault.UNKNOWN_GUARD){w="";a="";}
            return BingNavigationSnapshot.parseTimedTaskGuard(w,a,original.profile(),RootReadOnlyAnimationGuardTest.G,original,observed,observed);
        }
        void closeFakeMetadata(){Arrays.fill(metadataScratch,(byte)0);Arrays.fill(tapScratch,(byte)0);scratchClosed=true;}
    }
    private static final class Job {
        final Object visible=new Object(),request=new Object(),root=new Object();
        final AtomicLong now=new AtomicLong(1000),utc=new AtomicLong(RootSelectedPendingScope.STARTS_UTC+1000);
        final RealCaptureClaim.ProcessEpoch epoch=new RealCaptureClaim.ProcessEpoch();final CaptureSessionRegistry registry=new CaptureSessionRegistry();
        final RootBoundedBusinessIntent intent=VisibleCaptureAuthority.fixtureSelectedPendingIntent(visible,request,epoch,root,now.get(),utc::get);
        final Ports ports=new Ports(now);
        final NativeAutonomousCardRun run=NativeAutonomousCardRun.fixtureSelectedPendingTimed(now::get,utc::get,ports,intent.sessionEnd());
        final AtomicInteger burns=new AtomicInteger(),opens=new AtomicInteger(),decodes=new AtomicInteger(),queries=new AtomicInteger();
        final Set<String> burned=new HashSet<>();final List<OnceBackend.Owner> owners=new ArrayList<>();final Set<Object> sources=Collections.newSetFromMap(new IdentityHashMap<>());
        final AtomicInteger maxDiagnostic=new AtomicInteger(),maxMaa=new AtomicInteger(),maxPng=new AtomicInteger(),maxActor=new AtomicInteger(),maxCombinedChars=new AtomicInteger(),maxCombinedBytes=new AtomicInteger();final long[] ends=new long[2];
        Job(boolean browser){this(browser,false);}
        Job(boolean browser,boolean search){ports.browserAfterTap=browser;ports.internalSearchAfterTap=search;intent.bindRun(run);assertTrue(run.fixtureOnly()&&run.selectedPendingOnly());assertFalse(intent.live());}
        void begin(NativeRewardCardSession.Phase phase){run.reserve(phase);ends[phase.ordinal()]=run.deadline(now.get());}
    }
    private static final class FakeMat {
        final byte[] bgr;boolean destroyed;
        FakeMat(byte[] gray){bgr=new byte[gray.length*3];for(int i=0;i<gray.length;i++)Arrays.fill(bgr,i*3,i*3+3,gray[i]);}
        Pointer raw(){return new Pointer(30){@Override public void read(long offset,byte[] out,int start,int length){assertFalse(destroyed);System.arraycopy(bgr,Math.toIntExact(offset),out,start,length);}};}
        void destroy(){assertFalse(destroyed);Arrays.fill(bgr,(byte)0);destroyed=true;}
    }
    private static final class Frame implements AutoCloseable {
        final Job job;final NativeRewardCardSession.Phase phase;final int attempt;final Picture picture;final boolean uncertainNative;
        final Object requestOwner=new Object();final long lower,deadline;final RealCaptureClaim.Grant grant;
        final NativeRewardCardSession.PreparedCapture prepared;final ReviewShizukuPngBinding.Binding binding;final CaptureSessionRegistry.Entry entry;
        final AtomicBoolean localCancelled=new AtomicBoolean();final BackendPorts.RevocationFence fence=new BackendPorts.RevocationFence();
        final AtomicInteger ownBurns=new AtomicInteger(),ownOpens=new AtomicInteger(),ownDecodes=new AtomicInteger(),ownQueries=new AtomicInteger();
        final byte[] png;final List<FrameStructureEvidence.Line> rows;
        volatile CountDownLatch cancelEntered,cancelResume;boolean epochHeld,released,imageErased,imageRecycled,nativeReleased,taskQuiet,taskerDestroyed;
        int fdsClosed;OnceBackend.Owner owner;BackendPorts.FrameView actualFrame;FakeMat mat;MaaImageBufferAudit audit;MaaImageBufferAudit.Result nativeAudit;
        NativeRewardCardSession.PreparedObservation observation;MaaRecognizer.RootMarkerDiagnostic markers;
        CaptureSessionRuntime.BoundedCompletion completion;String summary;
        Frame(Job job,NativeRewardCardSession.Phase phase,int attempt,CaptureSessionRuntime.BoundedTransition previous,Picture picture,boolean uncertainNative)throws Exception{
            this.job=job;this.phase=phase;this.attempt=attempt;this.picture=picture;this.uncertainNative=uncertainNative;lower=job.now.get();deadline=job.ends[phase.ordinal()];
            grant=VisibleCaptureAuthority.fixtureSelectedPendingGrant(job.intent,phase,attempt,job.epoch,requestOwner,lower,deadline,previous);
            assertFalse(RealCaptureClaim.live(grant));var pre=navigation(BingNavigationSnapshot.Kind.TEMPLATE,lower);
            assertEquals(BingNavigationSnapshot.Status.READY,pre.status());assertTrue(job.intent.sourceMatches(pre.ready()));
            prepared=job.run.startPrepared(phase,attempt,requestOwner,lower,pre.ready());assertNotNull(prepared);
            assertEquals(lower,prepared.acquiredLowerBound());assertSame(requestOwner,prepared.requestOwner());assertEquals(phase,prepared.phase());assertEquals(attempt,prepared.attempt());
            byte[] gray;
            if(picture==Picture.EMPTY){gray=new byte[W*H];Arrays.fill(gray,(byte)244);rows=new ArrayList<>(List.of(
                new FrameStructureEvidence.Line("Search",10,20,90,20,.99),new FrameStructureEvidence.Line("Home",10,950,90,20,.99)));}
            else{gray=picture==Picture.COMPLETE?SelectedPendingBusinessTest.afterPixels():SelectedPendingBusinessTest.beforePixels();
                rows=new ArrayList<>(picture==Picture.COMPLETE?SelectedPendingBusinessTest.afterRows():SelectedPendingBusinessTest.beforeRows());
                if(picture==Picture.CONFLICT)rows.add(new FrameStructureEvidence.Line("Do you know the answer?",360,990,600,30,.99));}
            try{png=RootAuthorityFixture.png(gray,W,H);}finally{Arrays.fill(gray,(byte)0);}
            BackendPorts.Recognizer recognizer=new BackendPorts.Recognizer(){
                public BackendPorts.Recognition recognize(OnceBackend.Owner exact,BackendPorts.FrameView view,BackendPorts.StopSignal stop)throws Exception{
                    owner=exact;actualFrame=view;byte[] pixels=new byte[view.size()];
                    try{for(int i=0;i<pixels.length;i++)pixels[i]=(byte)view.unsignedByte(i);mat=new FakeMat(pixels);
                        var exactBinding=new MaaImageBufferAudit.Binding(this,exact,requestOwner,prepared,view,new Pointer(10),new Pointer(20));
                        assertSame(view,exactBinding.frame());assertSame(requestOwner,exactBinding.requestOwner());assertSame(prepared,exactBinding.holder());
                        audit=new MaaImageBufferAudit(exactBinding,true);Pointer raw=mat.raw();
                        MaaImageBufferAudit.ImageApi api=new MaaImageBufferAudit.ImageApi(){
                            public Pointer MaaImageBufferGetRawData(Pointer image){assertEquals(20,Pointer.nativeValue(image));return raw;}
                            public int MaaImageBufferWidth(Pointer image){return W;}public int MaaImageBufferHeight(Pointer image){return H;}
                            public int MaaImageBufferChannels(Pointer image){return 3;}public int MaaImageBufferType(Pointer image){return 16;}
                        };
                        audit.before(api,exactBinding,stop);long task=100+phase.ordinal()*10+attempt;
                        audit.posted(exactBinding,task);job.queries.incrementAndGet();assertEquals(1,ownQueries.incrementAndGet());taskQuiet=true;
                        audit.after(api,exactBinding,task,taskQuiet,stop);audit.observeRows(rows);audit.observeGeometry(pixels,W,H,stop);
                        observation=prepared.prepare(pixels,W,H,rows,stop::requested);markers=MaaRecognizer.rootMarkers(rows);
                        assertNotNull(observation);assertTrue(observation.matches(prepared));assertTrue(observation.validNativeShape());
                        return new BackendPorts.Recognition(exact,true);
                    }finally{Arrays.fill(pixels,(byte)0);}
                }
                public void release(OnceBackend.Owner exact){assertSame(owner,exact);if(!uncertainNative)destroyNative();nativeAudit=audit.result(nativeReleased,mat.destroyed,taskerDestroyed);}
                public BackendPorts.Completion completion(OnceBackend.Owner exact){assertSame(owner,exact);boolean clean=nativeReleased&&mat.destroyed&&taskerDestroyed&&audit.scratchClean();return new BackendPorts.Completion(exact,clean?BackendPorts.Released.CONFIRMED:BackendPorts.Released.UNKNOWN,clean?BackendPorts.Quiet.CONFIRMED:BackendPorts.Quiet.UNKNOWN);}
            };
            binding=ReviewShizukuPngBinding.fixtureImageAudit(exact->{
                var outcome=RealCaptureClaim.consume(grant,job.epoch,requestOwner,job.now.get(),(id,m)->{assertTrue(epochHeld);assertTrue(job.burned.add(phase+"_"+attempt));job.burns.incrementAndGet();assertEquals(1,ownBurns.incrementAndGet());});
                return new BackendPorts.ClaimReceipt(exact,outcome==RealCaptureClaim.Outcome.CONSUMED?BackendPorts.ClaimDecision.CLAIMED:BackendPorts.ClaimDecision.DENIED);
            },()->{job.opens.incrementAndGet();assertEquals(1,ownOpens.incrementAndGet());ByteArrayInputStream input=new ByteArrayInputStream(png);return new PngCaptureSession.Process(){
                public BoundedPngGray.Input adoptStdout(){return (b,off,len,wait)->input.read(b,off,len);}
                public boolean waitForExit(int millis){return true;}public int exitCode(){return 0;}public void destroy(){fail("No abort expected in clean synthetic process");}
                public boolean closeDescriptors()throws Exception{input.close();assertEquals(1,++fdsClosed);return true;}
            };},()->{if(!epochHeld||localCancelled.get()||!job.intent.current(job.now.get())||!RealCaptureClaim.current(grant,job.now.get()))throw new SecurityException("Fixture candidate expired");},
                fence,recognizer,Runnable::run,()->0L,W,H,(encoded,length)->{
                    job.decodes.incrementAndGet();assertEquals(1,ownDecodes.incrementAndGet());assertEquals(png.length,length);
                    for(int i=0;i<length;i++)assertEquals(png[i],encoded[i]);
                    Class<?> io=Class.forName("javax.imageio.ImageIO"),image=Class.forName("java.awt.image.BufferedImage");
                    io.getMethod("setUseCache",boolean.class).invoke(null,false);Object bitmap=io.getMethod("read",InputStream.class).invoke(null,new ByteArrayInputStream(encoded,0,length));assertNotNull(bitmap);
                    assertEquals(W,image.getMethod("getWidth").invoke(bitmap));assertEquals(H,image.getMethod("getHeight").invoke(bitmap));
                    Method get=image.getMethod("getRGB",int.class,int.class,int.class,int.class,int[].class,int.class,int.class),set=image.getMethod("setRGB",int.class,int.class,int.class,int.class,int[].class,int.class,int.class),flush=image.getMethod("flush");
                    return new PngImageAudit.Image(){
                        public PngImageAudit.Facts facts(){return new PngImageAudit.Facts(W,H,PngImageAudit.Config.ARGB_8888,0,true,false,false,true,false);}
                        public void argbRow(int y,int[] row)throws Exception{get.invoke(bitmap,0,y,W,1,row,0,W);}
                        public void erase()throws Exception{assertFalse(imageErased);int[] zeros=new int[W];for(int y=0;y<H;y++)set.invoke(bitmap,0,y,W,1,zeros,0,W);imageErased=true;}
                        public void recycle()throws Exception{assertTrue(imageErased);assertFalse(imageRecycled);flush.invoke(bitmap);imageRecycled=true;}
                    };
                });
            assertTrue(job.sources.add(binding.source()));assertTrue(job.epoch.retain(this));epochHeld=true;entry=job.registry.retain(this,job.epoch,deadline,this::cancelOnly,()->{},()->released,r->{r.run();return true;});
        }
        void cancelOnly(){CountDownLatch entered=cancelEntered,resume=cancelResume;if(entered!=null){entered.countDown();try{if(!resume.await(3,TimeUnit.SECONDS))throw new AssertionError("Old callback not resumed");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
            localCancelled.set(true);fence.revoke();binding.source().requestStop();binding.backend().cancel(binding.backend().snapshot().owner());}
        void destroyNative(){assertTrue(taskQuiet);mat.destroy();taskerDestroyed=true;nativeReleased=true;}
        void observe(){
            var result=binding.backend().start();assertEquals(1,ownBurns.get());assertEquals(1,ownOpens.get());assertEquals(1,ownDecodes.get());assertEquals(1,ownQueries.get());assertEquals(1,fdsClosed);
            assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(grant,job.epoch,requestOwner,job.now.get(),(id,m)->fail("Duplicate candidate claim burn")));
            assertFalse(job.owners.contains(result.owner()));job.owners.add(result.owner());assertSame(owner,result.owner());assertTrue(imageErased&&imageRecycled);
            assertEquals(PngImageAudit.Status.MATCH,binding.imageAudit().status());assertTrue(binding.imageAudit().isCleanupConfirmed());
            assertNotNull(nativeAudit);assertEquals(1,nativeAudit.queryPosts());
            if(uncertainNative){assertEquals(MaaImageBufferAudit.Status.UNKNOWN,nativeAudit.status());assertEquals(OnceBackend.Phase.CLEANUP_UNCONFIRMED,result.phase());assertEquals(OnceBackend.Memory.RETAINED,result.memory());assertFalse(nativeAudit.cleanupQualified());assertNull(binding.cleanReceipt());}
            else{assertEquals(MaaImageBufferAudit.Status.VERIFIED_EQUAL,nativeAudit.status());assertEquals(OnceBackend.Phase.COMPLETED,result.phase());assertEquals(OnceBackend.Memory.WIPED,result.memory());assertTrue(nativeAudit.cleanupQualified());assertNotNull(binding.cleanReceipt());assertTrue(binding.cleanReceipt().clean());assertThrows(IllegalStateException.class,()->actualFrame.unsignedByte(0));}
            assertEquals(lower,prepared.acquiredLowerBound());assertTrue(job.registry.retaining()&&job.epoch.retaining());
            measureSafeReport(null);
        }
        void measureSafeReport(String actor){String d=observation.diagnostic()==null?"":observation.diagnostic().summary(),m=nativeAudit.summary(),p=CaptureSessionRuntime.seriesPngSummary(binding.imageAudit()),a=actor==null?"":actor;
            job.maxDiagnostic.accumulateAndGet(d.length(),Math::max);job.maxMaa.accumulateAndGet(m.length(),Math::max);job.maxPng.accumulateAndGet(p.length(),Math::max);job.maxActor.accumulateAndGet(a.length(),Math::max);
            String all=d+"\n"+m+"\n"+p+"\n"+a;job.maxCombinedChars.accumulateAndGet(all.length(),Math::max);byte[] encoded=all.getBytes(StandardCharsets.UTF_8);job.maxCombinedBytes.accumulateAndGet(encoded.length,Math::max);Arrays.fill(encoded,(byte)0);
        }
        CaptureSessionRuntime.BoundedCompletion proof(){return CaptureSessionRuntime.BoundedCompletion.fixture(job.intent,job.run,prepared,observation,binding,nativeAudit,markers,job.epoch,job.now.get());}
        void retire(){int taps=job.ports.taps.get(),backs=job.ports.backs.get();completion=proof();assertEquals(CaptureSessionRuntime.BoundedCompletion.Disposition.UNPRESENTED,CaptureSessionRuntime.boundedDisposition(observation,binding.imageAudit(),nativeAudit,markers));
            assertTrue(job.run.retireUnpresented(prepared,observation,completion));assertFalse(job.run.candidateCurrent(prepared));assertEquals(taps,job.ports.taps.get());assertEquals(backs,job.ports.backs.get());closeRoot();}
        void adopt(){if(phase==NativeRewardCardSession.Phase.BEFORE){assertTrue(observation.selectedPendingOnly()&&observation.selectedProofReady());assertNotNull(observation.configuredTarget());assertFalse(observation.configuredTarget().configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()));job.ports.expectedIndicator=observation.configuredTarget().indicatorBox();}completion=proof();summary=job.run.acceptPreparedAfterCleanup(prepared,observation,completion,()->true,localCancelled::get);assertNotNull(summary);
            assertTrue(summary.contains("source=FIXTURE_NATIVE_SELECTED_PENDING_B"));assertTrue(summary.contains("sourceStartElapsed="+lower));assertTrue(summary.contains("frameId="+prepared.sequence()));assertEquals(lower,prepared.acquiredLowerBound());
            assertEquals(1,ownOpens.get());assertEquals(1,ownQueries.get());assertNull(completion.consume(job.run,prepared,observation,job.now.get()));measureSafeReport(summary);closeRoot();}
        void closeRoot(){assertEquals(OnceBackend.Cleanup.CONFIRMED,binding.backend().snapshot().cleanup());assertTrue(job.run.resourcesKnownClosed());released=true;job.epoch.release(this);epochHeld=false;job.registry.releaseIfClosed(entry);assertFalse(job.registry.retaining());assertFalse(job.epoch.retaining());}
        CaptureSessionRuntime.BoundedTransition next(){return CaptureSessionRuntime.BoundedTransition.fixture(completion,job.registry,job.epoch,job.now.get());}
        @Override public void close(){
            if(epochHeld){job.run.cancelPrepared(prepared);if(uncertainNative&&!nativeReleased){destroyNative();nativeAudit=audit.result(true,true,true);binding.backend().refreshEvidence(owner);}closeRoot();}
            Arrays.fill(png,(byte)0);
        }
    }
    private static long scalar(String summary,String key){var m=java.util.regex.Pattern.compile("(?:^|\\s)"+key+"=(-?\\d+)(?:\\s|$)").matcher(summary);assertTrue(key,m.find());return Long.parseLong(m.group(1));}
    @Test public void realSuccessfulTapPermitStartsFiveSecondClockAfterCommandDelayThenOneBackAndFreshAfter()throws Exception{
        for(int route=0;route<3;route++){boolean browser=route>0,search=route==2;Job job=new Job(browser,search);job.begin(NativeRewardCardSession.Phase.BEFORE);Frame before;
            CaptureSessionRuntime.BoundedTransition after;
            try(Frame frame=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.PENDING,false)){
                before=frame;frame.observe();job.now.addAndGet(47);frame.adopt();
                assertEquals(frame.lower+47,job.ports.tapAt);assertEquals(700,job.ports.successfulAt-job.ports.tapAt);assertEquals(5000,job.ports.backAt-job.ports.successfulAt);assertEquals(5700,job.ports.backAt-job.ports.tapAt);assertEquals(2,job.ports.pauses);assertEquals(0,job.ports.earlyGuardReads);assertEquals(2,job.ports.guards);for(long observed:job.ports.guardTimes)assertEquals(job.ports.successfulAt+5000,observed);assertTrue(job.ports.timedPostReads>=1);
                assertEquals(job.ports.successfulAt,scalar(frame.summary,"waitStartElapsed"));assertEquals(job.ports.tapAt,scalar(frame.summary,"tapDispatchElapsed"));assertEquals(job.ports.successfulAt,scalar(frame.summary,"successfulTapElapsed"));assertEquals(job.ports.backAt,scalar(frame.summary,"backFinalGateElapsed"));assertEquals(5000,scalar(frame.summary,"elapsedSinceSuccessfulTapAtBackGate"));assertEquals(5700,scalar(frame.summary,"elapsedSinceTapDispatchAtBackGate"));assertTrue(scalar(frame.summary,"backActorReturnedElapsed")>=job.ports.backAt);
                assertTrue(frame.summary.contains("returnPolicy=AFTER_SUCCESSFUL_TAP_5000MS"));assertTrue(frame.summary.contains("browserObserved=false"));assertTrue(job.run.returnReady()&&job.run.needsAfter());after=frame.next();
            }
            job.now.incrementAndGet();job.begin(NativeRewardCardSession.Phase.AFTER);
            try(Frame frame=new Frame(job,NativeRewardCardSession.Phase.AFTER,1,after,Picture.COMPLETE,false)){frame.observe();assertTrue(frame.lower>job.ports.backAt);frame.adopt();
                assertTrue(frame.summary.contains("outcome=OBSERVED_VISIBLE_KNOWN_SET_COMPLETE"));assertFalse(job.run.needsAfter());assertThrows(SecurityException.class,frame::next);}
            assertEquals(2,job.opens.get());assertEquals(2,job.queries.get());assertEquals(1,job.ports.taps.get());assertEquals(1,job.ports.backs.get());
            assertNull(job.run.acceptPreparedAfterCleanup(before.prepared,before.observation,before.completion,()->true,()->false));assertEquals(1,job.ports.backs.get());assertEquals(1,job.ports.taps.get());
            assertThrows(SecurityException.class,()->job.run.reserve(NativeRewardCardSession.Phase.BEFORE));
            System.out.println("timedRun route="+(search?"INTERNAL_SEARCH":browser?"BROWSER":"TEMPLATE")+" dispatchElapsed="+job.ports.tapAt+" successfulElapsed="+job.ports.successfulAt+" fakeBackDispatchElapsed="+job.ports.backAt+" elapsedSinceSuccessfulMs="+(job.ports.backAt-job.ports.successfulAt)+" earlyTaskGuardReads="+job.ports.earlyGuardReads+" taskGuardReads="+job.ports.guards);
        }
    }
    @Test public void cancellationPermissionForeignTaskUnknownAndUncleanMetadataBlockBackAndAfter()throws Exception{
        for(Fault fault:List.of(Fault.CANCEL,Fault.PERMISSION,Fault.FOREIGN_TASK,Fault.FOREIGN_PACKAGE,Fault.UNKNOWN_GUARD,Fault.CLEANUP_UNKNOWN)){
            Job job=new Job(true);job.begin(NativeRewardCardSession.Phase.BEFORE);Frame frame=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.PENDING,false);
            try{frame.observe();frame.completion=frame.proof();job.ports.expectedIndicator=frame.observation.configuredTarget().indicatorBox();job.ports.fault=fault;job.ports.cancel=()->frame.localCancelled.set(true);
                job.run.acceptPreparedAfterCleanup(frame.prepared,frame.observation,frame.completion,()->true,frame.localCancelled::get);
                assertEquals(fault.name(),0,job.ports.backs.get());assertEquals(0,job.ports.backBurns.get());assertEquals(1,job.ports.taps.get());assertEquals(0,job.ports.earlyGuardReads);assertEquals(0,job.ports.timedPostReads);if(fault==Fault.CANCEL||fault==Fault.PERMISSION||fault==Fault.CLEANUP_UNKNOWN){assertEquals(1,job.ports.pauses);assertEquals(job.ports.successfulAt+4999,job.now.get());assertEquals(0,job.ports.guards);}else{assertEquals(2,job.ports.pauses);assertEquals(job.ports.successfulAt+5000,job.now.get());assertEquals(1,job.ports.guards);}assertFalse(job.run.needsAfter());assertThrows(SecurityException.class,frame::next);
                assertNull(job.run.acceptPreparedAfterCleanup(frame.prepared,frame.observation,frame.completion,()->true,frame.localCancelled::get));assertEquals(0,job.ports.backs.get());
                if(fault==Fault.CLEANUP_UNKNOWN){assertFalse(job.run.resourcesKnownClosed());assertTrue(job.registry.retaining()&&job.epoch.retaining());}
                else{assertTrue(job.run.resourcesKnownClosed());frame.closeRoot();}
            }finally{job.ports.closeFakeMetadata();Arrays.fill(frame.png,(byte)0);assertTrue(job.ports.scratchClosed);for(byte value:job.ports.metadataScratch)assertEquals(0,value);
                if(fault==Fault.CLEANUP_UNKNOWN){assertFalse(job.run.resourcesKnownClosed());assertTrue(job.registry.retaining()&&job.epoch.retaining());}}
        }
    }
    @Test public void backExitZeroNeedsPostSourceAndThenNewNativeAfterNotACompletionShortcut()throws Exception{
        Job unknown=new Job(true);unknown.begin(NativeRewardCardSession.Phase.BEFORE);
        try(Frame frame=new Frame(unknown,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.PENDING,false)){frame.observe();frame.completion=frame.proof();unknown.ports.expectedIndicator=frame.observation.configuredTarget().indicatorBox();unknown.ports.fault=Fault.POST_UNKNOWN;
            unknown.run.acceptPreparedAfterCleanup(frame.prepared,frame.observation,frame.completion,()->true,frame.localCancelled::get);
            assertEquals(1,unknown.ports.backs.get());assertEquals(5000,unknown.ports.backAt-unknown.ports.successfulAt);assertTrue(unknown.ports.timedPostReads>=1);assertFalse(unknown.run.returnReady());assertFalse(unknown.run.needsAfter());assertThrows(SecurityException.class,frame::next);frame.closeRoot();}
        Job pending=new Job(true);pending.begin(NativeRewardCardSession.Phase.BEFORE);CaptureSessionRuntime.BoundedTransition next;
        try(Frame frame=new Frame(pending,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.PENDING,false)){frame.observe();frame.adopt();next=frame.next();}
        pending.now.incrementAndGet();pending.begin(NativeRewardCardSession.Phase.AFTER);
        try(Frame frame=new Frame(pending,NativeRewardCardSession.Phase.AFTER,1,next,Picture.PENDING,false)){frame.observe();frame.adopt();assertTrue(frame.summary.contains("outcome=HOLD"));assertFalse(pending.run.needsAfter());assertThrows(SecurityException.class,frame::next);}
        assertEquals(1,pending.ports.taps.get());assertEquals(1,pending.ports.backs.get());assertEquals(2,pending.opens.get());
    }
    @Test public void unknownAndNonzeroTapCannotMintSuccessfulReturnAuthority()throws Exception{
        Job unknown=new Job(true);unknown.begin(NativeRewardCardSession.Phase.BEFORE);Frame frame=new Frame(unknown,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.PENDING,false);
        try{frame.observe();frame.completion=frame.proof();unknown.ports.expectedIndicator=frame.observation.configuredTarget().indicatorBox();unknown.ports.fault=Fault.TAP_UNKNOWN;
            assertNull(unknown.run.acceptPreparedAfterCleanup(frame.prepared,frame.observation,frame.completion,()->true,frame.localCancelled::get));
            assertEquals(-1,unknown.ports.successfulAt);assertEquals(0,unknown.ports.guards);assertEquals(0,unknown.ports.pauses);assertEquals(0,unknown.ports.backs.get());
            assertEquals("returnStatus=NOT_REQUESTED",unknown.run.sessionForContractTest().returnSummary());assertFalse(unknown.run.needsAfter());assertFalse(unknown.run.resourcesKnownClosed());assertTrue(unknown.registry.retaining()&&unknown.epoch.retaining());assertThrows(SecurityException.class,frame::next);
        }finally{unknown.ports.closeFakeMetadata();Arrays.fill(frame.png,(byte)0);for(byte value:unknown.ports.tapScratch)assertEquals(0,value);assertFalse(unknown.run.resourcesKnownClosed());assertTrue(unknown.registry.retaining()&&unknown.epoch.retaining());}

        // Existing Run fixture intentionally fixes exitCode=0. Test the nonzero sealing
        // branch in the actual Session/actor, without adding any production fixture hook.
        AtomicLong clock=new AtomicLong(1000);AtomicInteger burns=new AtomicInteger(),dispatches=new AtomicInteger(),closes=new AtomicInteger(),returnCalls=new AtomicInteger();
        NativeRewardCardSession.Authority authority=new NativeRewardCardSession.Authority(){
            public long elapsedRealtime(){return clock.get();}public boolean scopeCurrent(){return true;}
            public boolean sourceStillMatches(int w,int h,int r){return w==W&&h==H&&r==0;}
            public void consumeOneTap(ReviewShizukuTapActor.TapTarget target){assertEquals(1,burns.incrementAndGet());}
        };
        var tap=new ReviewShizukuTapActor(()->new ReviewShizukuTapActor.Session(){
            public ReviewShizukuTapActor.DisplayShape display(){return new ReviewShizukuTapActor.DisplayShape(W,H,0);}public boolean stillReady(){return true;}
            public ReviewShizukuTapActor.PendingTap dispatch(ReviewShizukuTapActor.TapTarget target,ReviewShizukuTapActor.FinalGate gate)throws Exception{
                gate.requireValid();dispatches.incrementAndGet();clock.addAndGet(700);
                return new ReviewShizukuTapActor.PendingTap(){public boolean waitForExit(long millis){return true;}public int exitCode(){return 13;}public void destroy(){fail("Observed exit needs no destroy");}public void close(){closes.incrementAndGet();}};
            }
        });
        var returning=NativeBingReturnController.timedAfterTap("nonzero-confirmation",new NativeBingReturnController.Ports(){
            public long elapsedRealtime(){return clock.get();}public boolean authorityCurrent(BingNavigationSnapshot.Shape shape){return true;}
            public BingNavigationSnapshot.Ready originalSource(){returnCalls.incrementAndGet();return null;}
            public BingNavigationSnapshot.Result observe(BingNavigationSnapshot.Shape shape){returnCalls.incrementAndGet();return BingNavigationSnapshot.Result.unknown();}
            public void consumeReturn(ReviewShizukuBackActor.BackTarget target){fail("Nonzero tap cannot authorize Back");}
            public void pollPause(){returnCalls.incrementAndGet();}
        },new ReviewShizukuBackActor(()->{throw new AssertionError("Nonzero tap cannot open Back backend");}));
        var session=new NativeRewardCardSession("nonzero-confirmation",50_000,authority,tap,LiveRewardCardObservation.TargetSpec.moreTriviaSelectedPendingB(),returning);
        var capture=session.startCapture(NativeRewardCardSession.Phase.BEFORE,W,H,0);assertNotNull(capture);byte[] gray=SelectedPendingBusinessTest.beforePixels();
        NativeRewardCardSession.Observation observation;try{observation=capture.prepare(gray,W,H,SelectedPendingBusinessTest.beforeRows(),()->false);}finally{Arrays.fill(gray,(byte)0);}
        assertNotNull(observation);assertNotNull(observation.configuredTarget());clock.addAndGet(47);
        var receipt=session.acceptAfterCleanup(capture,observation,true,()->true,observation.configuredTarget(),50_000);
        assertEquals(NativeRewardCardSession.Reason.ACTOR_NOT_CONFIRMED,receipt.reason());assertNotNull(receipt.actorResult());assertEquals(ReviewShizukuTapActor.Kind.COMMAND_EXITED,receipt.actorResult().kind());assertEquals(13,receipt.actorResult().exitCode());
        assertEquals(1,dispatches.get());assertEquals(1,closes.get());assertEquals(0,returnCalls.get());assertEquals("returnStatus=NOT_REQUESTED",session.returnSummary());assertFalse(session.needsAfter());assertNull(session.startCapture(NativeRewardCardSession.Phase.AFTER,W,H,0));
    }
}
