package com.aliothmoon.maafw.once;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.RewardCardFixture.*;
import static com.aliothmoon.maafw.once.NativeRewardCardSession.*;

/** Fake authority and typed actor backend only. No Shizuku/device/account action executes. */
public class NativeRewardCardSessionTest {
    private static final class Rig implements Authority,ReviewShizukuTapActor.Backend,
            ReviewShizukuTapActor.Session,ReviewShizukuTapActor.PendingTap {
        long now=1000;boolean scope=true,source=true,burnThrows,dispatchThrows,timeout;
        int burns,starts,exit,opens;Runnable onBurn=()->{};
        @Override public long elapsedRealtime(){return now;}
        @Override public boolean scopeCurrent(){return scope;}
        @Override public boolean sourceStillMatches(int w,int h,int rotation){return source&&w==W&&h==H&&rotation==0;}
        @Override public void consumeOneTap(ReviewShizukuTapActor.TapTarget target)throws Exception{
            burns++;assertEquals(439,target.x());assertEquals(344,target.y());
            onBurn.run();if(burnThrows)throw new Exception("synthetic uncertain durable burn");
        }
        @Override public ReviewShizukuTapActor.Session open(){opens++;return this;}
        @Override public boolean stillReady(){return true;}
        @Override public ReviewShizukuTapActor.DisplayShape display(){return new ReviewShizukuTapActor.DisplayShape(W,H,0);}
        @Override public ReviewShizukuTapActor.PendingTap dispatch(ReviewShizukuTapActor.TapTarget t,ReviewShizukuTapActor.FinalGate gate)throws Exception{
            gate.requireValid();starts++;if(dispatchThrows)throw new Exception("synthetic dispatch unknown");return this;
        }
        @Override public boolean waitForExit(long millis){return !timeout;}
        @Override public int exitCode(){return exit;}
        @Override public void destroy(){}
        @Override public void close(){}
        NativeRewardCardSession session(){return new NativeRewardCardSession("new-synthetic-card-scope",100000,this,new ReviewShizukuTapActor(this));}
    }
    private static Observation prepare(Capture c,boolean complete,boolean second){
        assertNotNull(c);return c.prepare(pixels(complete,second),W,H,rows(complete,second),()->false);
    }
    private static Receipt before(NativeRewardCardSession session,boolean release,boolean hidden){
        var c=session.startCapture(Phase.BEFORE,W,H,0);var o=prepare(c,false,false);
        return session.acceptAfterCleanup(c,o,release,()->hidden,o.incompleteCards().get(0));
    }
    private static Receipt after(NativeRewardCardSession session,boolean complete){
        var c=session.startCapture(Phase.AFTER,W,H,0);var o=prepare(c,complete,false);
        return session.acceptAfterCleanup(c,o,true,()->true,null);
    }
    @Test public void typedTapOnlyAfterCleanupAndNewSameTaskTickCompletes(){
        Rig r=new Rig();var s=r.session();var first=before(s,true,true);
        assertEquals(Outcome.AWAITING_NEW_OBSERVATION,first.outcome());assertEquals(Reason.TAP_EXITED_NOT_COMPLETION,first.reason());
        assertEquals(1,r.burns);assertEquals(1,r.starts);assertTrue(s.needsAfter());assertFalse(first.accountCreditKnown());
        r.now=2000;var result=after(s,true);assertEquals(Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED,result.outcome());
        assertEquals(Reason.SAME_TASK_NATIVE_TICK,result.reason());assertFalse(result.causedByTap());assertFalse(result.accountCreditKnown());
        assertFalse(s.needsAfter());assertNull(s.startCapture(Phase.BEFORE,W,H,0));assertNull(s.startCapture(Phase.AFTER,W,H,0));
        assertEquals(1,r.starts);
    }
    @Test public void noCleanupOrVisibleAppNeverOpensActor(){
        for(boolean released:new boolean[]{false,true}) {
            Rig r=new Rig();var s=r.session();var result=before(s,released,false);
            assertEquals(Outcome.HOLD,result.outcome());assertEquals(0,r.opens);assertEquals(0,r.burns);assertEquals(0,r.starts);
        }
    }
    @Test public void cleanupFalseBlocksEvenHiddenApp(){
        Rig r=new Rig();var result=before(r.session(),false,true);assertEquals(Reason.BAD_CAPTURE_OR_CLEANUP,result.reason());assertEquals(0,r.opens);
    }
    @Test public void foreignObservationCannotBeSubstituted(){
        Rig r=new Rig();var s=r.session();var c=s.startCapture(Phase.BEFORE,W,H,0);var o=prepare(c,false,false);
        Rig other=new Rig();var s2=other.session();var c2=s2.startCapture(Phase.BEFORE,W,H,0);var o2=prepare(c2,false,false);
        assertEquals(Outcome.HOLD,s.acceptAfterCleanup(c,o2,true,()->true,o.incompleteCards().get(0)).outcome());assertEquals(0,r.starts);
    }
    @Test public void sameSemanticCardFromDifferentObservationIsNotActionAuthority(){
        Rig r=new Rig();var s=r.session();var c=s.startCapture(Phase.BEFORE,W,H,0);var o=prepare(c,false,false);
        var other=observe(pixels(false,false),rows(false,false)).cards().get(0);
        assertEquals(Reason.EXACT_SELECTION_REQUIRED,s.acceptAfterCleanup(c,o,true,()->true,other).reason());assertEquals(0,r.starts);
    }
    @Test public void expiredFrameNeverBurnsActionAuthority(){
        Rig r=new Rig();var s=r.session();var c=s.startCapture(Phase.BEFORE,W,H,0);var o=prepare(c,false,false);r.now+=MAX_FRAME_AGE+1;
        assertEquals(Outcome.HOLD,s.acceptAfterCleanup(c,o,true,()->true,o.cards().get(0)).outcome());assertEquals(0,r.burns);
    }
    @Test public void failedOrUncertainDurableBurnIsSpentAndCannotRetry(){
        Rig r=new Rig();r.burnThrows=true;var s=r.session();var result=before(s,true,true);
        assertEquals(Outcome.HOLD,result.outcome());assertEquals(1,r.burns);assertEquals(0,r.starts);assertFalse(s.needsAfter());
        assertNull(s.startCapture(Phase.BEFORE,W,H,0));assertNull(s.startCapture(Phase.AFTER,W,H,0));
    }
    @Test public void cancelOrSourceLossDuringDurableBurnStopsFinalDispatch(){
        for(boolean cancel:new boolean[]{false,true}) {
            Rig r=new Rig();var s=r.session();r.onBurn=()->{if(cancel)s.cancel();else r.source=false;};
            assertEquals(Outcome.HOLD,before(s,true,true).outcome());assertEquals(1,r.burns);assertEquals(0,r.starts);
        }
    }
    @Test public void freshnessMustSurvivePotentiallySlowDurableBurn(){
        Rig r=new Rig();r.onBurn=()->r.now+=MAX_FRAME_AGE+1;var s=r.session();
        assertEquals(Outcome.HOLD,before(s,true,true).outcome());assertEquals(1,r.burns);assertEquals(0,r.starts);
    }
    @Test public void uncertainDispatchNeverBecomesATapRetry(){
        Rig r=new Rig();r.dispatchThrows=true;var s=r.session();var result=before(s,true,true);
        assertEquals(Outcome.HOLD,result.outcome());assertEquals(ReviewShizukuTapActor.Kind.DISPATCH_UNKNOWN,result.actorResult().kind());
        assertEquals(1,r.starts);assertFalse(s.needsAfter());assertNull(s.startCapture(Phase.BEFORE,W,H,0));
    }
    @Test public void commandExitZeroAndNavigationAreNotCompletion(){
        Rig r=new Rig();var s=r.session();assertEquals(Outcome.AWAITING_NEW_OBSERVATION,before(s,true,true).outcome());r.now=2000;
        var c=s.startCapture(Phase.AFTER,W,H,0);var o=c.prepare(pixels(false,false),W,H,List.of(line("Different page",20,20,200,20)),()->false);
        assertEquals(Outcome.HOLD,s.acceptAfterCleanup(c,o,true,()->true,null).outcome());assertEquals(1,r.starts);assertFalse(s.needsAfter());
    }
    @Test public void unchangedTaskAndSectionOnlyRatioIncreaseAreNotTaskCompletion(){
        Rig r=new Rig();var s=r.session();before(s,true,true);r.now=2000;
        var c=s.startCapture(Phase.AFTER,W,H,0);var rows=rows(false,false);rows.set(1,line("10/30",388,210,92,25));
        var o=c.prepare(pixels(false,false),W,H,rows,()->false);
        assertEquals(Outcome.HOLD,s.acceptAfterCleanup(c,o,true,()->true,null).outcome());assertEquals(1,r.starts);
    }
    @Test public void aggregateCompleteWithoutSelectedTaskTickStillHolds(){
        Rig r=new Rig();var s=r.session();before(s,true,true);r.now=2000;
        var c=s.startCapture(Phase.AFTER,W,H,0);var rows=rows(false,false);rows.set(1,line("30/30",388,210,92,25));
        var o=c.prepare(pixels(false,false),W,H,rows,()->false);
        assertEquals(Outcome.HOLD,s.acceptAfterCleanup(c,o,true,()->true,null).outcome());
    }
    @Test public void otherTaskTickOrDifferentBodyCannotCompleteSelectedTask(){
        Rig r=new Rig();var s=r.session();before(s,true,true);r.now=2000;
        var c=s.startCapture(Phase.AFTER,W,H,0);var rows=rows(true,false);rows.set(2,line("Other activity",55,270,265,25));
        var o=c.prepare(pixels(true,false),W,H,rows,()->false);
        assertEquals(Outcome.HOLD,s.acceptAfterCleanup(c,o,true,()->true,null).outcome());assertEquals(1,r.starts);
    }
    @Test public void denominatorOrDailyRosterChangeInvalidatesContext(){
        Rig r=new Rig();var s=r.session();before(s,true,true);r.now=2000;
        var c=s.startCapture(Phase.AFTER,W,H,0);var rows=rows(true,false);rows.set(1,line("10/40",388,210,92,25));
        var o=c.prepare(pixels(true,false),W,H,rows,()->false);
        assertEquals(Reason.CONTEXT_CHANGED,s.acceptAfterCleanup(c,o,true,()->true,null).reason());
        r=new Rig();s=r.session();before(s,true,true);r.now=2000;c=s.startCapture(Phase.AFTER,W,H,0);o=prepare(c,true,true);
        assertEquals(Reason.CONTEXT_CHANGED,s.acceptAfterCleanup(c,o,true,()->true,null).reason());
    }
    @Test public void bothNativeObservationsAreOneUseAndAfterMustFollowAction(){
        Rig r=new Rig();var s=r.session();assertNull(s.startCapture(Phase.AFTER,W,H,0));
        var c=s.startCapture(Phase.BEFORE,W,H,0);var o=prepare(c,false,false);assertNull(c.prepare(pixels(false,false),W,H,rows(false,false),()->false));
        s.acceptAfterCleanup(c,o,true,()->true,o.incompleteCards().get(0));
        assertNull(s.startCapture(Phase.AFTER,W,H,0));assertFalse(s.needsAfter());assertEquals(1,r.starts);
    }
    @Test public void completeBeforeSkipsWithoutOpeningActor(){
        Rig r=new Rig();var s=r.session();var c=s.startCapture(Phase.BEFORE,W,H,0);var o=prepare(c,true,false);
        assertEquals(Outcome.TASK_ALREADY_COMPLETE_SKIP,s.acceptAfterCleanup(c,o,true,()->true,o.cards().get(0)).outcome());assertEquals(0,r.starts);
    }
    @Test public void noDefaultGrantAndSessionLifetimeIsBounded(){
        Rig r=new Rig();r.scope=false;
        try{r.session();fail("No default grant");}catch(SecurityException expected){}
        r.scope=true;
        try{new NativeRewardCardSession("scope",r.now+MAX_SESSION_TTL+1,r,new ReviewShizukuTapActor(r));fail("TTL");}catch(SecurityException expected){}
    }
    @Test public void sourceFrameCanMoveWithoutBecomingAnIndexBasedTaskId(){
        Rig r=new Rig();var s=r.session();before(s,true,true);r.now=2000;
        byte[] source=pixels(true,false),shifted=new byte[W*H];java.util.Arrays.fill(shifted,(byte)247);
        for(int y=0;y<H-50;y++)System.arraycopy(source,y*W,shifted,(y+50)*W,W);
        var shiftedRows=new java.util.ArrayList<FrameStructureEvidence.Line>();
        for(var row:rows(true,false))shiftedRows.add(new FrameStructureEvidence.Line(row.text(),row.x(),row.y()+50,row.width(),row.height(),row.score()));
        var c=s.startCapture(Phase.AFTER,W,H,0);var o=c.prepare(shifted,W,H,shiftedRows,()->false);
        assertEquals(Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED,s.acceptAfterCleanup(c,o,true,()->true,null).outcome());
    }
    @Test public void uiNeedsAfterNeverWaitsBehindWorkerActorGate()throws Exception{
        Rig r=new Rig();var s=r.session();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        r.onBurn=()->{entered.countDown();try{assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS));}
            catch(InterruptedException e){throw new AssertionError(e);}};
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker=new Thread(()->{try{before(s,true,true);}catch(Throwable e){error.set(e);}});
        worker.start();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
        try{
            var result=new java.util.concurrent.atomic.AtomicReference<Boolean>();
            Thread ui=new Thread(()->result.set(s.needsAfter()));ui.start();ui.join(200);
            assertFalse("UI hint must not take worker monitor",ui.isAlive());assertEquals(Boolean.FALSE,result.get());
        }finally{release.countDown();worker.join(2000);}
        assertNull(error.get());assertFalse(worker.isAlive());
    }
    @Test public void actorTimeoutIsUnknownHoldWithNoRetryOrCompletion(){
        Rig r=new Rig();r.timeout=true;var s=r.session();var result=before(s,true,true);
        assertEquals(Outcome.HOLD,result.outcome());assertEquals(ReviewShizukuTapActor.Kind.DISPATCH_UNKNOWN,result.actorResult().kind());
        assertFalse(s.needsAfter());assertNull(s.startCapture(Phase.BEFORE,W,H,0));assertEquals(1,r.starts);
    }}

