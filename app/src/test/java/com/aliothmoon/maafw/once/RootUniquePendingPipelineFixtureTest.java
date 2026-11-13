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

/** v38 refined rules and new active UTC only; original v37 test remains frozen. Non-live, synthetic pixels and fake native pointers/actors; actual PNG/OwnedFrame,
 * audit, private issuer/completion, prepared adoption and Run state machines. */
public final class RootUniquePendingPipelineFixtureTest {
    private static final int W=IndependentCurrentCardTest.W,H=IndependentCurrentCardTest.H;
    private enum Picture { EMPTY, PENDING, COMPLETE, QUESTION, MULTI_PENDING, RISK, LOW_SCORE, CONSUMED_A, CONSUMED_B, QUOTE, PUZZLE, CHECKIN, CHECKIN_EN, BAD_RATIO, AFTER_BAD_RATIO, FULL_SKIP }
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
            assertEquals((int)Math.floor(expectedIndicator.centerX()),t.x());assertEquals((int)Math.floor(expectedIndicator.centerY()),t.y());assertTrue(t.y()>=1109&&t.y()<1437);}
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
        final AtomicLong now=new AtomicLong(1000),utc=new AtomicLong(RootUniqueRefinedScope.STARTS_UTC+1000);
        final RealCaptureClaim.ProcessEpoch epoch=new RealCaptureClaim.ProcessEpoch();final CaptureSessionRegistry registry=new CaptureSessionRegistry();
        final RootBoundedBusinessIntent intent=VisibleCaptureAuthority.fixtureUniquePendingIntent(visible,request,epoch,root,now.get(),utc::get);
        final Ports ports=new Ports(now);
        final NativeAutonomousCardRun run=NativeAutonomousCardRun.fixtureUniquePending(now::get,utc::get,ports,intent.sessionEnd());
        final AtomicInteger burns=new AtomicInteger(),opens=new AtomicInteger(),decodes=new AtomicInteger(),queries=new AtomicInteger();
        final Set<String> burned=new HashSet<>();final List<OnceBackend.Owner> owners=new ArrayList<>();final Set<Object> sources=Collections.newSetFromMap(new IdentityHashMap<>());
        final AtomicInteger maxDiagnostic=new AtomicInteger(),maxMaa=new AtomicInteger(),maxPng=new AtomicInteger(),maxActor=new AtomicInteger(),maxCombinedChars=new AtomicInteger(),maxCombinedBytes=new AtomicInteger();final long[] ends=new long[2];
        Job(boolean browser){this(browser,false);}
        Job(boolean browser,boolean search){ports.browserAfterTap=browser;ports.internalSearchAfterTap=search;intent.bindRun(run);assertTrue(run.fixtureOnly()&&run.uniquePendingOnly()&&!run.selectedPendingOnly());assertFalse(intent.live());}
        void begin(NativeRewardCardSession.Phase phase){run.reserve(phase);ends[phase.ordinal()]=run.deadline(now.get());}
    }
    private record Data(byte[] gray,List<FrameStructureEvidence.Line> rows) {}
    private static Data data(Picture picture){
        if(picture==Picture.EMPTY){byte[] p=new byte[W*H];Arrays.fill(p,(byte)244);return new Data(p,new ArrayList<>(List.of(
            new FrameStructureEvidence.Line("Search",10,20,90,20,.99),new FrameStructureEvidence.Line("Home",10,H-50,90,20,.99))));}
        boolean after=picture==Picture.COMPLETE||picture==Picture.AFTER_BAD_RATIO;
        byte[] p=after?UniquePendingBusinessTest.afterPixels():UniquePendingBusinessTest.beforePixels();
        List<FrameStructureEvidence.Line> rows=new ArrayList<>(after?UniquePendingBusinessTest.afterRows():UniquePendingBusinessTest.beforeRows());
        // The four skips (all five AFTER) keep only their own number and real synthetic tick pixels.
        // No completed title, known A/B name or readonly fact is a positive business anchor.
        rows.removeIf(q->q.y()>=661&&!(q.text().equals("5"))&&(after||q.y()<1109||q.y()>=1437));
        if(picture==Picture.PENDING)UniquePendingBusinessTest.title(rows,"Check the latest news",.99);else if(picture==Picture.QUESTION)UniquePendingBusinessTest.title(rows,"Try these quiz questions",.99);
        else if(picture==Picture.RISK)UniquePendingBusinessTest.title(rows,"Verify your account",.99);
        else if(picture==Picture.LOW_SCORE)UniquePendingBusinessTest.title(rows,"Read the latest news",.1);
        else if(picture==Picture.CONSUMED_A||picture==Picture.CONSUMED_B){UniquePendingBusinessTest.title(rows,AutonomousCardFixture.TITLE,.99);IndependentCurrentCardTest.replace(rows,1245,picture==Picture.CONSUMED_A?AutonomousCardFixture.A:AutonomousCardFixture.B,.99);}
        else if(picture==Picture.QUOTE)UniquePendingBusinessTest.title(rows,"Quote of the day",.99);
        else if(picture==Picture.PUZZLE)UniquePendingBusinessTest.title(rows,"Complete this puzzle",.99);
        else if(picture==Picture.CHECKIN)UniquePendingBusinessTest.title(rows,"每日签到",.99);else if(picture==Picture.CHECKIN_EN)UniquePendingBusinessTest.title(rows,"Check-in rewards",.99);
        else if(picture==Picture.BAD_RATIO)IndependentCurrentCardTest.replace(rows,450,"25/0",.99);
        else if(picture==Picture.FULL_SKIP)IndependentCurrentCardTest.replace(rows,450,"25/25",.99);
        else if(picture==Picture.AFTER_BAD_RATIO)IndependentCurrentCardTest.replace(rows,450,"20/25",.99);
        else if(picture==Picture.MULTI_PENDING){
            IndependentCurrentCardTest.fill(p,114,661,1326,1069,255);IndependentCurrentCardTest.fill(p,1022,793,1278,937,80);
            rows.removeIf(q->q.y()>=661&&q.y()<1069);
            rows.add(IndependentCurrentCardTest.r("Read another story",.99,360,712,900,784));
            rows.add(IndependentCurrentCardTest.r("Open another reading item",.99,360,877,913,941));
            rows.add(IndependentCurrentCardTest.r("+5",.99,1090,826,1207,898));IndependentCurrentCardTest.replace(rows,450,"15/25",.99);
        }
        return new Data(p,rows);
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
            grant=VisibleCaptureAuthority.fixtureUniquePendingGrant(job.intent,phase,attempt,job.epoch,requestOwner,lower,deadline,previous);
            assertFalse(RealCaptureClaim.live(grant));var pre=navigation(BingNavigationSnapshot.Kind.TEMPLATE,lower);
            assertEquals(BingNavigationSnapshot.Status.READY,pre.status());assertTrue(job.intent.sourceMatches(pre.ready()));
            prepared=job.run.startPrepared(phase,attempt,requestOwner,lower,pre.ready());assertNotNull(prepared);
            assertEquals(lower,prepared.acquiredLowerBound());assertSame(requestOwner,prepared.requestOwner());assertEquals(phase,prepared.phase());assertEquals(attempt,prepared.attempt());
            Data material=data(picture);byte[] gray=material.gray();rows=material.rows();
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
        void adopt(){if(phase==NativeRewardCardSession.Phase.BEFORE){assertTrue(observation.uniquePendingOnly()&&observation.uniqueProofReady()&&!observation.selectedPendingOnly());if(observation.status()==LiveRewardCardObservation.Status.SECTION_COMPLETE_SKIP)assertNull(observation.configuredTarget());else{assertNotNull(observation.configuredTarget());assertFalse(observation.configuredTarget().configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()));job.ports.expectedIndicator=observation.configuredTarget().indicatorBox();}}completion=proof();summary=job.run.acceptPreparedAfterCleanup(prepared,observation,completion,()->true,localCancelled::get);assertNotNull(summary);
            assertTrue(summary.contains("source=FIXTURE_NATIVE_UNIQUE_PENDING"));assertTrue(summary.contains("sourceStartElapsed="+lower));assertTrue(summary.contains("frameId="+prepared.sequence()));assertEquals(lower,prepared.acquiredLowerBound());
            assertEquals(1,ownOpens.get());assertEquals(1,ownQueries.get());assertNull(completion.consume(job.run,prepared,observation,job.now.get()));measureSafeReport(summary);closeRoot();}
        void closeRoot(){assertEquals(OnceBackend.Cleanup.CONFIRMED,binding.backend().snapshot().cleanup());assertTrue(job.run.resourcesKnownClosed());released=true;job.epoch.release(this);epochHeld=false;job.registry.releaseIfClosed(entry);assertFalse(job.registry.retaining());assertFalse(job.epoch.retaining());}
        CaptureSessionRuntime.BoundedTransition next(){return CaptureSessionRuntime.BoundedTransition.fixture(completion,job.registry,job.epoch,job.now.get());}
        @Override public void close(){
            if(epochHeld){job.run.cancelPrepared(prepared);if(uncertainNative&&!nativeReleased){destroyNative();nativeAudit=audit.result(true,true,true);binding.backend().refreshEvidence(owner);}closeRoot();}
            Arrays.fill(png,(byte)0);
        }
    }
    private static long scalar(String summary,String key){var m=java.util.regex.Pattern.compile("(?:^|\\s)"+key+"=(-?\\d+)(?:\\s|$)").matcher(summary);assertTrue(key,m.find());return Long.parseLong(m.group(1));}
    @Test public void freshUniqueRawBusinessProofAdoptsSameFrameThenSuccessfulFiveSecondBackAndMoreSet()throws Exception{
        Job job=new Job(true,true);job.begin(NativeRewardCardSession.Phase.BEFORE);CaptureSessionRuntime.BoundedTransition next;long beforeEnd=job.ends[0];
        assertTrue(job.intent.uniquePending());assertFalse(job.intent.selectedPending());assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureSelectedPendingGrant(job.intent,NativeRewardCardSession.Phase.BEFORE,1,job.epoch,new Object(),job.now.get(),beforeEnd,null));
        try(Frame empty=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.EMPTY,false)){empty.observe();empty.retire();next=empty.next();}
        job.now.incrementAndGet();
        try(Frame before=new Frame(job,NativeRewardCardSession.Phase.BEFORE,2,next,Picture.PENDING,false)){before.observe();assertTrue(before.observation.uniquePendingOnly()&&before.observation.uniqueProofReady());assertTrue(before.observation.frame().uniqueBefore());assertFalse(before.observation.selectedPendingOnly());
            assertEquals(5,before.nativeAudit.sameFrameCv().cardTotal());job.now.addAndGet(47);before.adopt();assertEquals(beforeEnd,before.deadline);assertEquals(700,job.ports.successfulAt-job.ports.tapAt);assertEquals(5000,job.ports.backAt-job.ports.successfulAt);assertEquals(2,job.ports.pauses);assertEquals(0,job.ports.earlyGuardReads);assertEquals(2,job.ports.guards);for(long observed:job.ports.guardTimes)assertEquals(job.ports.successfulAt+5000,observed);assertEquals(5000,scalar(before.summary,"elapsedSinceSuccessfulTapAtBackGate"));assertTrue(job.ports.timedPostReads>0);next=before.next();}
        job.now.incrementAndGet();job.begin(NativeRewardCardSession.Phase.AFTER);long afterEnd=job.ends[1];
        try(Frame empty=new Frame(job,NativeRewardCardSession.Phase.AFTER,1,next,Picture.EMPTY,false)){empty.observe();empty.retire();next=empty.next();}
        job.now.incrementAndGet();
        try(Frame after=new Frame(job,NativeRewardCardSession.Phase.AFTER,2,next,Picture.COMPLETE,false)){after.observe();assertTrue(after.observation.frame().uniqueAllComplete());after.adopt();assertEquals(afterEnd,after.deadline);
            assertTrue(after.summary.contains("outcome=OBSERVED_VISIBLE_MORE_SET_COMPLETE"));assertTrue(after.summary.contains("reason=VISIBLE_MORE_SET_TICKS"));assertTrue(after.summary.contains("causalAttribution=UNPROVEN"));assertFalse(job.run.needsAfter());assertThrows(SecurityException.class,after::next);}
        assertEquals(4,job.burns.get());assertEquals(4,job.opens.get());assertEquals(4,job.queries.get());assertEquals(4,job.sources.size());assertEquals(1,job.ports.taps.get());assertEquals(1,job.ports.backs.get());
    }
    @Test public void multipleRiskLowConsumedCategoriesAndMalformedRatioCannotBecomeInput()throws Exception{
        for(Picture picture:List.of(Picture.MULTI_PENDING,Picture.RISK,Picture.LOW_SCORE,Picture.CONSUMED_A,Picture.CONSUMED_B,Picture.QUOTE,Picture.PUZZLE,Picture.CHECKIN,Picture.CHECKIN_EN,Picture.BAD_RATIO)){
            Job job=new Job(true);job.begin(NativeRewardCardSession.Phase.BEFORE);
            try(Frame frame=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,picture,false)){frame.observe();assertFalse(picture.name(),frame.observation.frame().uniqueBefore());assertThrows(picture.name(),SecurityException.class,frame::proof);
                if(picture==Picture.MULTI_PENDING){assertEquals(5,frame.nativeAudit.sameFrameCv().cardTotal());assertTrue(frame.observation.uniqueEvidenceSummary(),frame.observation.uniqueEvidenceSummary().contains("PENDING_COUNT"));}
                assertEquals(0,job.ports.taps.get());assertEquals(0,job.ports.backs.get());assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureUniquePendingGrant(job.intent,NativeRewardCardSession.Phase.BEFORE,2,job.epoch,new Object(),job.now.get(),job.ends[0],null));}
        }
    }
    @Test public void fullRatioSkipTraversesRuntimeClassificationAndPrivateCompletionWithoutInput()throws Exception{
        Job job=new Job(true);job.begin(NativeRewardCardSession.Phase.BEFORE);
        try(Frame frame=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.FULL_SKIP,false)){frame.observe();assertEquals(LiveRewardCardObservation.Status.SECTION_COMPLETE_SKIP,frame.observation.status());assertTrue(frame.observation.uniqueProofReady());
            assertEquals(CaptureSessionRuntime.BoundedCompletion.Disposition.POSITIVE,CaptureSessionRuntime.boundedDisposition(frame.observation,frame.binding.imageAudit(),frame.nativeAudit,frame.markers));frame.adopt();
            assertTrue(frame.summary.contains("outcome=SECTION_ALREADY_COMPLETE_SKIP"));assertEquals(0,job.ports.tapBurns.get());assertEquals(0,job.ports.taps.get());assertEquals(0,job.ports.backBurns.get());assertEquals(0,job.ports.backs.get());assertFalse(job.run.needsAfter());assertThrows(SecurityException.class,frame::next);}
    }
    @Test public void quizOnlyOpensAndReturnsWhilePendingOrContradictoryAfterNeverRepeatsInput()throws Exception{
        for(Picture afterPicture:List.of(Picture.PENDING,Picture.AFTER_BAD_RATIO)){Job job=new Job(true);job.begin(NativeRewardCardSession.Phase.BEFORE);CaptureSessionRuntime.BoundedTransition next;
            try(Frame before=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.QUESTION,false)){before.observe();before.adopt();next=before.next();}
            job.now.incrementAndGet();job.begin(NativeRewardCardSession.Phase.AFTER);
            try(Frame after=new Frame(job,NativeRewardCardSession.Phase.AFTER,1,next,afterPicture,false)){after.observe();
                if(afterPicture==Picture.PENDING){after.adopt();assertTrue(after.summary.contains("outcome=HOLD"));assertFalse(job.run.needsAfter());}
                else assertThrows(SecurityException.class,after::proof);
                assertThrows(SecurityException.class,after::next);}
            assertEquals(1,job.ports.taps.get());assertEquals(1,job.ports.backs.get());assertEquals(2,job.queries.get());
        }
    }
    @Test public void timerAuthorityLossOrNativeCleanupUnknownBlocksContinuation()throws Exception{
        for(Fault fault:List.of(Fault.CANCEL,Fault.PERMISSION,Fault.FOREIGN_TASK,Fault.FOREIGN_PACKAGE,Fault.UNKNOWN_GUARD,Fault.CLEANUP_UNKNOWN)){
            Job job=new Job(true);job.begin(NativeRewardCardSession.Phase.BEFORE);Frame frame=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.PENDING,false);
            try{frame.observe();frame.completion=frame.proof();job.ports.expectedIndicator=frame.observation.configuredTarget().indicatorBox();job.ports.fault=fault;job.ports.cancel=()->frame.localCancelled.set(true);
                job.run.acceptPreparedAfterCleanup(frame.prepared,frame.observation,frame.completion,()->true,frame.localCancelled::get);assertEquals(fault.name(),0,job.ports.backs.get());assertFalse(job.run.needsAfter());assertThrows(SecurityException.class,frame::next);
                if(fault==Fault.CLEANUP_UNKNOWN){assertFalse(job.run.resourcesKnownClosed());assertTrue(job.registry.retaining()&&job.epoch.retaining());}else frame.closeRoot();
            }finally{job.ports.closeFakeMetadata();Arrays.fill(frame.png,(byte)0);if(fault==Fault.CLEANUP_UNKNOWN)assertTrue(job.registry.retaining()&&job.epoch.retaining());}
        }
        Job job=new Job(true);job.begin(NativeRewardCardSession.Phase.BEFORE);try(Frame frame=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.PENDING,true)){frame.observe();assertThrows(SecurityException.class,frame::proof);assertTrue(job.registry.retaining()&&job.epoch.retaining());assertEquals(0,job.ports.taps.get());}
    }
    @Test public void oldCallbackCannotCancelNewUniqueCandidateAndThirdEmptyCannotMintFourth()throws Exception{
        Job job=new Job(true);job.begin(NativeRewardCardSession.Phase.BEFORE);AtomicReference<Throwable> failure=new AtomicReference<>();
        try(Frame old=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.EMPTY,false)){old.observe();old.cancelEntered=new CountDownLatch(1);old.cancelResume=new CountDownLatch(1);
            Thread timer=new Thread(()->{try{job.registry.request(old.entry);}catch(Throwable e){failure.set(e);}},"fixture-v38-old-timer");timer.start();assertTrue(old.cancelEntered.await(1,TimeUnit.SECONDS));
            try{old.retire();var previous=old.next();job.now.incrementAndGet();try(Frame fresh=new Frame(job,NativeRewardCardSession.Phase.BEFORE,2,previous,Picture.QUESTION,false)){old.cancelResume.countDown();timer.join(2000);assertFalse(timer.isAlive());assertNull(failure.get());assertFalse(fresh.localCancelled.get());assertTrue(job.intent.current(job.now.get()));fresh.observe();fresh.adopt();assertEquals(1,job.ports.taps.get());assertEquals(1,job.ports.backs.get());}}
            finally{old.cancelResume.countDown();timer.join(2000);}}
        Job cap=new Job(true);cap.begin(NativeRewardCardSession.Phase.BEFORE);long end=cap.ends[0];CaptureSessionRuntime.BoundedTransition previous=null;
        for(int n=1;n<=3;n++){try(Frame frame=new Frame(cap,NativeRewardCardSession.Phase.BEFORE,n,previous,Picture.EMPTY,false)){frame.observe();assertEquals(end,frame.deadline);
            if(n<3){frame.retire();previous=frame.next();}else{frame.completion=frame.proof();assertFalse(cap.run.retireUnpresented(frame.prepared,frame.observation,frame.completion));frame.closeRoot();assertThrows(SecurityException.class,frame::next);}}cap.now.incrementAndGet();}
        assertEquals(0,cap.ports.taps.get());assertEquals(0,cap.ports.backs.get());
    }
}
