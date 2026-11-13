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

/** Non-live, synthetic pixels and fake native pointers/actors; actual PNG/OwnedFrame,
 * audit, private issuer/completion, prepared adoption and Run state machines. */
public final class RootBoundedBusinessPipelineFixtureTest {
    private static final int W=AutonomousCardFixture.W,H=AutonomousCardFixture.H;
    private enum Picture { EMPTY, PENDING, COMPLETE, CONFLICT, SIBLING_COMPLETE }
    private static final class Ports implements NativeAutonomousCardRun.FixturePorts {
        final AtomicLong now;final AtomicInteger tapBurns=new AtomicInteger(),backBurns=new AtomicInteger(),taps=new AtomicInteger(),backs=new AtomicInteger();
        BingNavigationSnapshot.Kind kind=BingNavigationSnapshot.Kind.TEMPLATE;
        Ports(AtomicLong now){this.now=now;}
        public BingNavigationSnapshot.Shape shape(){return BingNavigationFixture.SHAPE;}
        public boolean authorityMatches(BingNavigationSnapshot.Shape s){return shape().equals(s);}
        public boolean knownClean(){return true;}
        public BingNavigationSnapshot.Result observe(BingNavigationSnapshot.Shape s){return authorityMatches(s)?BingNavigationFixture.result(kind,now.get()):BingNavigationSnapshot.Result.unknown();}
        public void consumeTap(ReviewShizukuTapActor.TapTarget t){assertEquals(1,tapBurns.incrementAndGet());}
        public void consumeReturn(ReviewShizukuBackActor.BackTarget t){assertEquals(1,backBurns.incrementAndGet());}
        public void tapped(ReviewShizukuTapActor.TapTarget t){taps.incrementAndGet();kind=BingNavigationSnapshot.Kind.BROWSER;}
        public void backed(ReviewShizukuBackActor.BackTarget t){backs.incrementAndGet();kind=BingNavigationSnapshot.Kind.TEMPLATE;}
    }
    private static final class Job {
        final Object visible=new Object(),request=new Object(),root=new Object();
        final AtomicLong now=new AtomicLong(1000),utc=new AtomicLong(RootBoundedBusinessScope.STARTS_UTC+1000);
        final RealCaptureClaim.ProcessEpoch epoch=new RealCaptureClaim.ProcessEpoch();final CaptureSessionRegistry registry=new CaptureSessionRegistry();
        final RootBoundedBusinessIntent intent=VisibleCaptureAuthority.fixtureBoundedIntent(visible,request,epoch,root,now.get(),utc::get);
        final Ports ports=new Ports(now);
        final NativeAutonomousCardRun run=NativeAutonomousCardRun.fixtureBounded(now::get,utc::get,ports,intent.sessionEnd());
        final AtomicInteger burns=new AtomicInteger(),opens=new AtomicInteger(),decodes=new AtomicInteger(),queries=new AtomicInteger();
        final Set<String> burned=new HashSet<>();final List<OnceBackend.Owner> owners=new ArrayList<>();
        final Set<Object> sources=Collections.newSetFromMap(new IdentityHashMap<>());
        final AtomicInteger maxDiagnostic=new AtomicInteger(),maxMaa=new AtomicInteger(),maxPng=new AtomicInteger(),maxActor=new AtomicInteger(),maxCombinedChars=new AtomicInteger(),maxCombinedBytes=new AtomicInteger();
        final long[] ends=new long[2];
        Job(){intent.bindRun(run);assertFalse(intent.live());assertTrue(run.fixtureOnly());}
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
            grant=VisibleCaptureAuthority.fixtureBoundedGrant(job.intent,phase,attempt,job.epoch,requestOwner,lower,deadline,previous);
            assertFalse(RealCaptureClaim.live(grant));var pre=BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,lower);
            assertEquals(BingNavigationSnapshot.Status.READY,pre.status());assertTrue(job.intent.sourceMatches(pre.ready()));
            prepared=job.run.startPrepared(phase,attempt,requestOwner,lower,pre.ready());assertNotNull(prepared);
            assertEquals(lower,prepared.acquiredLowerBound());assertSame(requestOwner,prepared.requestOwner());assertEquals(phase,prepared.phase());assertEquals(attempt,prepared.attempt());
            byte[] gray;
            if(picture==Picture.EMPTY){gray=new byte[W*H];Arrays.fill(gray,(byte)244);rows=new ArrayList<>(List.of(
                new FrameStructureEvidence.Line("Search",10,20,90,20,.99),new FrameStructureEvidence.Line("Home",10,950,90,20,.99)));}
            else{gray=AutonomousCardFixture.pixels(picture==Picture.COMPLETE,picture==Picture.SIBLING_COMPLETE);rows=AutonomousCardFixture.rows(picture==Picture.COMPLETE,picture==Picture.SIBLING_COMPLETE);
                if(picture==Picture.CONFLICT)for(int i=0;i<rows.size();i++){var r=rows.get(i);if(r.text().equals(AutonomousCardFixture.A))rows.set(i,new FrameStructureEvidence.Line("Unexpected body",r.x(),r.y(),r.width(),r.height(),r.score()));}}
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
        void adopt(){completion=proof();summary=job.run.acceptPreparedAfterCleanup(prepared,observation,completion,()->true,localCancelled::get);assertNotNull(summary);
            assertTrue(summary.contains("source=FIXTURE_NATIVE_AUTONOMOUS_MORE"));assertTrue(summary.contains("sourceStartElapsed="+lower));assertTrue(summary.contains("frameId="+prepared.sequence()));assertEquals(lower,prepared.acquiredLowerBound());
            assertEquals(1,ownOpens.get());assertEquals(1,ownQueries.get());assertNull(completion.consume(job.run,prepared,observation,job.now.get()));measureSafeReport(summary);closeRoot();}
        void closeRoot(){assertEquals(OnceBackend.Cleanup.CONFIRMED,binding.backend().snapshot().cleanup());assertTrue(job.run.resourcesKnownClosed());released=true;job.epoch.release(this);epochHeld=false;job.registry.releaseIfClosed(entry);assertFalse(job.registry.retaining());assertFalse(job.epoch.retaining());}
        CaptureSessionRuntime.BoundedTransition next(){return CaptureSessionRuntime.BoundedTransition.fixture(completion,job.registry,job.epoch,job.now.get());}
        @Override public void close(){
            if(epochHeld){job.run.cancelPrepared(prepared);if(uncertainNative&&!nativeReleased){destroyNative();nativeAudit=audit.result(true,true,true);binding.backend().refreshEvidence(owner);}closeRoot();}
            Arrays.fill(png,(byte)0);
        }
    }
    @Test public void emptyBeforeThenSameFrameAdoptAndEmptyAfterThenCompleteWithFourBurns()throws Exception{
        Job job=new Job();job.begin(NativeRewardCardSession.Phase.BEFORE);CaptureSessionRuntime.BoundedTransition next;
        try(Frame empty=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.EMPTY,false)){empty.observe();assertEquals(244,empty.nativeAudit.bandStats().min());assertEquals(244,empty.nativeAudit.bandStats().max());empty.retire();next=empty.next();}
        job.now.addAndGet(1000);
        try(Frame valid=new Frame(job,NativeRewardCardSession.Phase.BEFORE,2,next,Picture.PENDING,false)){valid.observe();valid.adopt();assertTrue(valid.summary.contains("outcome=AWAITING_NEW_OBSERVATION"));next=valid.next();assertTrue(job.run.returnReady()&&job.run.needsAfter());}
        assertEquals(1,job.ports.tapBurns.get());assertEquals(1,job.ports.backBurns.get());assertEquals(1,job.ports.taps.get());assertEquals(1,job.ports.backs.get());
        job.now.addAndGet(1000);job.begin(NativeRewardCardSession.Phase.AFTER);
        try(Frame empty=new Frame(job,NativeRewardCardSession.Phase.AFTER,1,next,Picture.EMPTY,false)){empty.observe();empty.retire();next=empty.next();}
        job.now.addAndGet(1000);
        try(Frame complete=new Frame(job,NativeRewardCardSession.Phase.AFTER,2,next,Picture.COMPLETE,false)){complete.observe();complete.adopt();assertTrue(complete.summary.contains("outcome=OBSERVED_TASK_COMPLETE_UNATTRIBUTED"));assertFalse(job.run.needsAfter());assertThrows(SecurityException.class,complete::next);}
        assertEquals(4,job.burns.get());assertEquals(4,job.opens.get());assertEquals(4,job.decodes.get());assertEquals(4,job.queries.get());assertEquals(4,job.owners.size());assertEquals(4,job.sources.size());assertEquals(1,job.ports.taps.get());assertEquals(1,job.ports.backs.get());
        assertEquals(Set.of("BEFORE_1","BEFORE_2","AFTER_1","AFTER_2"),job.burned);
        System.out.println("V31 fixture safe report lengths diagnostic="+job.maxDiagnostic.get()+" maa="+job.maxMaa.get()+" pngScalars="+job.maxPng.get()+" actorSummary="+job.maxActor.get()+" combinedChars="+job.maxCombinedChars.get()+" combinedUtf8Bytes="+job.maxCombinedBytes.get());
    }
    @Test public void maxThreeEmptyCandidatesNeverAdoptAndCannotStartFourth()throws Exception{
        Job job=new Job();job.begin(NativeRewardCardSession.Phase.BEFORE);CaptureSessionRuntime.BoundedTransition previous=null;
        for(int attempt=1;attempt<=3;attempt++){try(Frame frame=new Frame(job,NativeRewardCardSession.Phase.BEFORE,attempt,previous,Picture.EMPTY,false)){frame.observe();if(attempt<3){frame.retire();previous=frame.next();}else{frame.completion=frame.proof();assertFalse(job.run.retireUnpresented(frame.prepared,frame.observation,frame.completion));assertFalse(job.run.candidateCurrent(frame.prepared));frame.closeRoot();assertThrows(SecurityException.class,frame::next);}}job.now.incrementAndGet();}
        assertEquals(3,job.burns.get());assertEquals(0,job.ports.taps.get());assertEquals(0,job.ports.backs.get());
        var last=previous;assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureBoundedGrant(job.intent,NativeRewardCardSession.Phase.BEFORE,4,job.epoch,new Object(),job.now.get(),job.ends[0],last));
    }
    @Test public void conflictExpiryAndUnknownCleanupNeverMintNext()throws Exception{
        Job conflict=new Job();conflict.begin(NativeRewardCardSession.Phase.BEFORE);
        try(Frame frame=new Frame(conflict,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.CONFLICT,false)){frame.observe();assertNull(CaptureSessionRuntime.boundedDisposition(frame.observation,frame.binding.imageAudit(),frame.nativeAudit,frame.markers));assertThrows(SecurityException.class,frame::proof);assertEquals(0,conflict.ports.taps.get());}
        Job expired=new Job();expired.begin(NativeRewardCardSession.Phase.BEFORE);
        try(Frame frame=new Frame(expired,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.EMPTY,false)){frame.observe();var proof=frame.proof();expired.now.set(frame.deadline);assertFalse(expired.run.retireUnpresented(frame.prepared,frame.observation,proof));assertEquals(0,expired.ports.taps.get());assertThrows(SecurityException.class,()->CaptureSessionRuntime.BoundedTransition.fixture(proof,expired.registry,expired.epoch,expired.now.get()));}
        Job unknown=new Job();unknown.begin(NativeRewardCardSession.Phase.BEFORE);
        try(Frame frame=new Frame(unknown,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.EMPTY,true)){frame.observe();assertTrue(unknown.registry.retaining()&&unknown.epoch.retaining());assertThrows(SecurityException.class,frame::proof);assertEquals(0,unknown.ports.taps.get());assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureBoundedGrant(unknown.intent,NativeRewardCardSession.Phase.BEFORE,2,unknown.epoch,new Object(),unknown.now.get(),unknown.ends[0],null));}
        Job sibling=new Job();sibling.begin(NativeRewardCardSession.Phase.BEFORE);
        try(Frame frame=new Frame(sibling,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.SIBLING_COMPLETE,false)){frame.observe();assertEquals(LiveRewardCardObservation.BodyClass.EMPTY_COMPLETE,frame.observation.diagnostic().familyItemDiagnostics().get(1).canonicalBody());frame.completion=frame.proof();
            assertNull(sibling.run.acceptPreparedAfterCleanup(frame.prepared,frame.observation,frame.completion,()->true,frame.localCancelled::get));assertEquals(0,sibling.ports.taps.get());assertEquals(0,sibling.ports.backs.get());frame.closeRoot();assertThrows(SecurityException.class,frame::next);}
        Job stillPending=new Job();stillPending.begin(NativeRewardCardSession.Phase.BEFORE);CaptureSessionRuntime.BoundedTransition after;
        try(Frame before=new Frame(stillPending,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.PENDING,false)){before.observe();before.adopt();after=before.next();}
        stillPending.now.incrementAndGet();stillPending.begin(NativeRewardCardSession.Phase.AFTER);
        try(Frame frame=new Frame(stillPending,NativeRewardCardSession.Phase.AFTER,1,after,Picture.PENDING,false)){frame.observe();frame.adopt();assertTrue(frame.summary.contains("outcome=HOLD"));assertFalse(stillPending.run.needsAfter());assertThrows(SecurityException.class,frame::next);}
        assertEquals(2,stillPending.opens.get());assertEquals(1,stillPending.ports.taps.get());assertEquals(1,stillPending.ports.backs.get());
    }
    @Test public void oldCallbackPastRegistryCheckCannotRevokeNewCandidateOrParent()throws Exception{
        Job job=new Job();job.begin(NativeRewardCardSession.Phase.BEFORE);AtomicReference<Throwable> failure=new AtomicReference<>();
        try(Frame old=new Frame(job,NativeRewardCardSession.Phase.BEFORE,1,null,Picture.EMPTY,false)){
            old.observe();old.cancelEntered=new CountDownLatch(1);old.cancelResume=new CountDownLatch(1);
            Thread timer=new Thread(()->{try{job.registry.request(old.entry);}catch(Throwable e){failure.set(e);}},"fixture-v31-old-timer");timer.start();assertTrue(old.cancelEntered.await(1,TimeUnit.SECONDS));
            try{old.retire();var previous=old.next();job.now.incrementAndGet();
                try(Frame fresh=new Frame(job,NativeRewardCardSession.Phase.BEFORE,2,previous,Picture.PENDING,false)){
                    old.cancelResume.countDown();timer.join(2000);assertFalse(timer.isAlive());assertNull(failure.get());assertTrue(old.localCancelled.get());assertFalse(fresh.localCancelled.get());assertTrue(job.intent.current(job.now.get()));assertTrue(job.run.candidateCurrent(fresh.prepared));
                    fresh.observe();fresh.adopt();assertEquals(1,job.ports.taps.get());assertEquals(1,job.ports.backs.get());
                }
            }finally{old.cancelResume.countDown();timer.join(2000);}
        }
    }
}
