package com.aliothmoon.maafw.once;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.AutonomousCardFixture.*;

/** Three real non-live frame graphs: separate preflight, business BEFORE and fresh AFTER. */
public class RootBusinessFlowFixtureTest {
    private static final class Ports implements NativeAutonomousCardRun.FixturePorts {
        final AtomicLong clock;final AtomicInteger tapBurns=new AtomicInteger(),returnBurns=new AtomicInteger(),taps=new AtomicInteger(),backs=new AtomicInteger();
        BingNavigationSnapshot.Kind kind=BingNavigationSnapshot.Kind.TEMPLATE;
        Ports(AtomicLong clock){this.clock=clock;}
        public BingNavigationSnapshot.Shape shape(){return BingNavigationFixture.SHAPE;}
        public boolean authorityMatches(BingNavigationSnapshot.Shape s){return shape().equals(s);}
        public boolean knownClean(){return true;}
        public BingNavigationSnapshot.Result observe(BingNavigationSnapshot.Shape s){return authorityMatches(s)?BingNavigationFixture.result(kind,clock.get()):BingNavigationSnapshot.Result.unknown();}
        public void consumeTap(ReviewShizukuTapActor.TapTarget t){if(tapBurns.incrementAndGet()!=1)throw new SecurityException("duplicate fixture TAP");}
        public void consumeReturn(ReviewShizukuBackActor.BackTarget t){if(returnBurns.incrementAndGet()!=1)throw new SecurityException("duplicate fixture RETURN");}
        public void tapped(ReviewShizukuTapActor.TapTarget t){taps.incrementAndGet();kind=BingNavigationSnapshot.Kind.BROWSER;}
        public void backed(ReviewShizukuBackActor.BackTarget t){backs.incrementAndGet();kind=BingNavigationSnapshot.Kind.TEMPLATE;}
    }
    private static final class Flow {
        final RootAuthorityFixture preflight;final Object owner=new Object();final RootCaptureIntent intent;
        final Ports ports;final NativeAutonomousCardRun run;final AtomicInteger claims=new AtomicInteger();
        Flow()throws Exception{
            preflight=new RootAuthorityFixture(false);preflight.binding.backend().start();
            assertTrue(preflight.intent.acceptPublished(preflight.publish(),preflight.now.get()));
            var pass=preflight.intent.claimPreflightPass(preflight.epoch,preflight.rootOwner,preflight.now.get());
            intent=VisibleCaptureAuthority.fixtureRootIntent(preflight.visible,preflight.request,preflight.epoch,owner,
                RootCaptureScope.Mode.BUSINESS,preflight.now.get(),preflight.utc::get,pass);
            ports=new Ports(preflight.now);run=NativeAutonomousCardRun.fixture(preflight.now::get,preflight.utc::get,ports);assertTrue(run.fixtureOnly());
        }
        Frame frame(RootCaptureScope.Phase phase,RootCaptureIntent.Continuation continuation,boolean complete,boolean invalidBody)throws Exception{
            NativeRewardCardSession.Phase nativePhase=phase==RootCaptureScope.Phase.FIRST?NativeRewardCardSession.Phase.BEFORE:NativeRewardCardSession.Phase.AFTER;
            run.reserve(nativePhase);long now=preflight.now.get(),deadline=run.deadline(now);Object requestOwner=new Object();
            var grant=VisibleCaptureAuthority.fixtureRootGrant(intent,phase,preflight.epoch,requestOwner,now,deadline,continuation);assertFalse(RealCaptureClaim.live(grant));
            var capture=run.startCapture(nativePhase,W,H);assertNotNull(capture);
            var observation=new AtomicReference<NativeRewardCardSession.Observation>();
            var lines=rows(complete,false);if(invalidBody)for(int i=0;i<lines.size();i++){var r=lines.get(i);if(r.text().equals(A))lines.set(i,new FrameStructureEvidence.Line("Unexpected body",r.x(),r.y(),r.width(),r.height(),r.score()));}
            byte[] png=RootAuthorityFixture.png(pixels(complete,false),W,H);
            BackendPorts.Recognizer recognizer=new BackendPorts.Recognizer(){
                public BackendPorts.Recognition recognize(OnceBackend.Owner exact,BackendPorts.FrameView view,BackendPorts.StopSignal stop){
                    byte[] gray=new byte[view.size()];try{for(int i=0;i<gray.length;i++)gray[i]=(byte)view.unsignedByte(i);
                        var value=capture.prepare(gray,view.width(),view.height(),lines,stop::requested);observation.set(value);
                        return new BackendPorts.Recognition(exact,NativeAutonomousCardRun.nativeObservationEligible(value));
                    }finally{Arrays.fill(gray,(byte)0);}
                }
                public void release(OnceBackend.Owner exact){}public BackendPorts.Completion completion(OnceBackend.Owner exact){return new BackendPorts.Completion(exact,BackendPorts.Released.CONFIRMED,BackendPorts.Quiet.CONFIRMED);}
            };
            var binding=ReviewShizukuPngBinding.fixture(exact->{
                var outcome=RealCaptureClaim.consume(grant,preflight.epoch,requestOwner,preflight.now.get(),(id,m)->claims.incrementAndGet());
                return new BackendPorts.ClaimReceipt(exact,outcome==RealCaptureClaim.Outcome.CONSUMED?BackendPorts.ClaimDecision.CLAIMED:BackendPorts.ClaimDecision.DENIED);
            },()->{ByteArrayInputStream input=new ByteArrayInputStream(png);return new PngCaptureSession.Process(){
                public BoundedPngGray.Input adoptStdout(){return (b,off,len,wait)->input.read(b,off,len);}
                public boolean waitForExit(int millis){return true;}public int exitCode(){return 0;}public void destroy(){}public boolean closeDescriptors()throws Exception{input.close();return true;}
            };},()->{if(!intent.current(preflight.now.get())||!run.current()||!RealCaptureClaim.current(grant,preflight.now.get()))throw new SecurityException("fixture phase expired");},
                new BackendPorts.RevocationFence(),recognizer,Runnable::run,()->0L,W,H);
            var result=binding.backend().start();assertEquals(OnceBackend.Phase.COMPLETED,result.phase());assertNotNull(binding.cleanReceipt());
            String summary=run.acceptAfterCleanup(capture,observation.get(),binding.cleanReceipt().clean(),()->true,deadline);assertNotNull(summary);
            assertTrue(summary.contains("source=FIXTURE_NATIVE_AUTONOMOUS_MORE"));assertFalse(summary.contains("source=REAL_NATIVE_AUTONOMOUS_MORE"));
            var publication=CaptureSessionRuntime.RootPublication.fixtureBusiness(intent,phase,preflight.epoch,owner,requestOwner,deadline,binding.cleanReceipt(),run,preflight.now.get());
            assertTrue(intent.acceptPublished(publication,preflight.now.get()));return new Frame(summary,grant,requestOwner);
        }
    }
    private record Frame(String summary,RealCaptureClaim.Grant grant,Object owner){}
    @Test public void exactClaimSourceRunTapBackContinuationAndFreshAfterShareTheRealPhaseState()throws Exception{
        var f=new Flow();Frame before=f.frame(RootCaptureScope.Phase.FIRST,null,false,false);
        assertTrue(before.summary().contains("outcome=AWAITING_NEW_OBSERVATION"));assertTrue(f.run.returnReady());assertTrue(f.run.needsAfter());
        assertEquals(1,f.ports.taps.get());assertEquals(1,f.ports.backs.get());assertEquals(1,f.ports.tapBurns.get());assertEquals(1,f.ports.returnBurns.get());
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureRootGrant(f.intent,RootCaptureScope.Phase.FIRST,f.preflight.epoch,new Object(),f.preflight.now.get(),RealCaptureClaim.deadline(before.grant()),null));
        var continuation=f.intent.claimOpaqueContinuation(f.preflight.epoch,f.owner,f.preflight.now.get());assertNotNull(continuation);
        assertNull(f.intent.claimOpaqueContinuation(f.preflight.epoch,f.owner,f.preflight.now.get()));
        f.preflight.now.addAndGet(10);Frame after=f.frame(RootCaptureScope.Phase.SECOND,continuation,true,false);
        assertTrue(after.summary().contains("outcome=OBSERVED_TASK_COMPLETE_UNATTRIBUTED"));assertFalse(f.run.needsAfter());assertFalse(f.intent.current(f.preflight.now.get()));
        assertEquals(2,f.claims.get());assertEquals(1,f.ports.taps.get());assertEquals(1,f.ports.backs.get());
        assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(after.grant(),f.preflight.epoch,after.owner(),f.preflight.now.get(),(id,m)->fail("duplicate grant burn")));
    }
    @Test public void semanticFirstHoldCannotMintAContinuationOrACaptureLoop()throws Exception{
        var f=new Flow();Frame before=f.frame(RootCaptureScope.Phase.FIRST,null,false,true);
        assertTrue(before.summary().contains("outcome=HOLD"));assertFalse(f.run.returnReady());assertFalse(f.run.needsAfter());
        assertEquals(0,f.ports.taps.get());assertEquals(0,f.ports.backs.get());assertEquals(0,f.ports.tapBurns.get());assertEquals(0,f.ports.returnBurns.get());
        assertNull(f.intent.claimOpaqueContinuation(f.preflight.epoch,f.owner,f.preflight.now.get()));
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureRootGrant(f.intent,RootCaptureScope.Phase.SECOND,f.preflight.epoch,new Object(),f.preflight.now.get(),f.preflight.now.get()+10_000,null));
        assertEquals(1,f.claims.get());
    }
}