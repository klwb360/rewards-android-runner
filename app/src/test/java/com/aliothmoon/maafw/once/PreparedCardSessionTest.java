package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.aliothmoon.maafw.once.NativeRewardCardSession.*;

/** Session-only one-shot/adopt contracts; separate full issuer/source fixture owns end-to-end permission tests. */
public class PreparedCardSessionTest {
    private static final class Rig {
        long now=1000;int sourceReads,taps,burns;boolean source=true;
        final NativeRewardCardSession session;
        Rig(){
            var authority=new Authority(){public long elapsedRealtime(){return now;}public boolean scopeCurrent(){return true;}
                public boolean sourceStillMatches(int w,int h,int r){sourceReads++;return source&&w==523&&h==1000&&r==0;}
                public void consumeOneTap(ReviewShizukuTapActor.TapTarget target){burns++;}};
            var actor=new ReviewShizukuTapActor(()->new ReviewShizukuTapActor.Session(){
                public ReviewShizukuTapActor.DisplayShape display(){return new ReviewShizukuTapActor.DisplayShape(523,1000,0);}public boolean stillReady(){return true;}
                public ReviewShizukuTapActor.PendingTap dispatch(ReviewShizukuTapActor.TapTarget target,ReviewShizukuTapActor.FinalGate gate)throws Exception{
                    gate.requireValid();taps++;return new ReviewShizukuTapActor.PendingTap(){public boolean waitForExit(long ms){return true;}public int exitCode(){return 0;}public void destroy(){}public void close(){}};}
            });
            var back=new ReviewShizukuBackActor(()->{throw new AssertionError("No Browser in this unit rig");});
            var returner=new NativeBingReturnController("prepared-session",new NativeBingReturnController.Ports(){
                public long elapsedRealtime(){return now;}public boolean authorityCurrent(BingNavigationSnapshot.Shape s){return true;}
                public BingNavigationSnapshot.Ready originalSource(){return BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,now).ready();}
                public BingNavigationSnapshot.Result observe(BingNavigationSnapshot.Shape s){return BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,now);}
                public void consumeReturn(ReviewShizukuBackActor.BackTarget target){throw new AssertionError("No Back");}public void pollPause(){}
            },back);
            session=new NativeRewardCardSession("prepared-session",60000,authority,actor,LiveRewardCardObservation.TargetSpec.moreTriviaChallengeBounded(),returner);
        }
        PreparedCapture p(Phase phase,int attempt,long seq){return session.prepareCandidate(phase,attempt,new Object(),now,seq,50000,BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,now).ready());}
        PreparedObservation o(PreparedCapture p,boolean a,boolean b){return p.prepare(AutonomousCardFixture.pixels(a,b),523,1000,AutonomousCardFixture.rows(a,b),()->false);}
        Receipt accept(Adopted a){return session.acceptAfterCleanup(a.capture(),a.observation(),true,()->true,a.observation().configuredTarget(),50000);}
    }
    @Test public void detachedPrepareDoesNotReadSourceOrSpendLogicalCapture(){
        var r=new Rig();var p=r.p(Phase.BEFORE,1,1);var o=r.o(p,false,false);assertNotNull(o);assertEquals(0,r.sourceReads);assertEquals(0,r.taps);
        assertTrue(o.validNativeShape());assertTrue(o.nativeNonemptyRows()>0);assertEquals(1,o.nativeMoreCandidates());
        assertNull(p.prepare(AutonomousCardFixture.pixels(false,false),523,1000,AutonomousCardFixture.rows(false,false),()->false));
        var p2=r.p(Phase.BEFORE,2,2);assertNotNull(p2);assertEquals(0,r.sourceReads);}
    @Test public void adoptionPreservesExactFrameCardsLowerAndGlobalSequence(){
        var r=new Rig();var p=r.p(Phase.BEFORE,2,3);var o=r.o(p,false,false);long lower=p.acquiredLowerBound();r.now+=100;
        var a=r.session.adoptPrepared(p,o,()->true);assertNotNull(a);assertSame(o.cards().get(0),a.observation().cards().get(0));
        assertEquals(lower,a.capture().acquiredLowerBound);assertEquals(3,a.capture().sequence);assertEquals(1,r.sourceReads);
        assertNull(r.session.adoptPrepared(p,o,()->true));assertEquals(1,r.sourceReads);assertNull(a.capture().prepare(AutonomousCardFixture.pixels(false,false),523,1000,AutonomousCardFixture.rows(false,false),()->false));}
    @Test public void staleOrLostSourceAdoptionIsSpentAndNeverTapped(){
        var r=new Rig();var p=r.p(Phase.BEFORE,1,1);var o=r.o(p,false,false);r.now+=15001;assertNull(r.session.adoptPrepared(p,o,()->true));
        assertNull(r.session.startCapture(Phase.BEFORE,523,1000,0));assertEquals(0,r.taps);
        r=new Rig();p=r.p(Phase.BEFORE,1,1);o=r.o(p,false,false);r.source=false;assertNull(r.session.adoptPrepared(p,o,()->true));assertEquals(1,r.sourceReads);assertEquals(0,r.taps);}
    @Test public void mismatchedHolderOrSessionCannotAdopt(){
        var r=new Rig();var a=r.p(Phase.BEFORE,1,1);var b=r.p(Phase.BEFORE,2,2);var o=r.o(a,false,false);
        assertNull(r.session.adoptPrepared(b,o,()->true));assertNull(new Rig().session.adoptPrepared(a,o,()->true));assertEquals(0,r.sourceReads);}
    @Test public void currentMixedBodylessSiblingCannotReachActionBoundary(){
        var r=new Rig();var p=r.p(Phase.BEFORE,1,1);var o=r.o(p,false,true);assertEquals(LiveRewardCardObservation.Status.OBSERVED,o.status());
        var a=r.session.adoptPrepared(p,o,()->true);assertNotNull(a);assertEquals(Outcome.HOLD,r.accept(a).outcome());assertEquals(0,r.taps);assertEquals(0,r.burns);}
    @Test public void freshTickWithUnchangedHeaderNumeratorStillCompletes(){
        var r=new Rig();var p=r.p(Phase.BEFORE,1,1);var o=r.o(p,false,false);assertEquals(Outcome.AWAITING_NEW_OBSERVATION,r.accept(r.session.adoptPrepared(p,o,()->true)).outcome());
        assertEquals(1,r.taps);r.now+=1000;var after=r.p(Phase.AFTER,2,5);var rows=AutonomousCardFixture.rows(true,false);MoreVisibleCardObservationTest.replace(rows,"15/20","10/20",.95);
        var next=after.prepare(AutonomousCardFixture.pixels(true,false),523,1000,rows,()->false);var adopted=r.session.adoptPrepared(after,next,()->true);
        assertEquals(Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED,r.session.acceptAfterCleanup(adopted.capture(),adopted.observation(),true,()->true,null,50000).outcome());assertEquals(1,r.taps);}
    @Test public void clearlyUnfinishedAfterIsTerminalAndNotResampled(){
        var r=new Rig();var p=r.p(Phase.BEFORE,1,1);r.accept(r.session.adoptPrepared(p,r.o(p,false,false),()->true));r.now+=1000;
        var after=r.p(Phase.AFTER,1,2);var a=r.session.adoptPrepared(after,r.o(after,false,false),()->true);
        assertEquals(Outcome.HOLD,r.session.acceptAfterCleanup(a.capture(),a.observation(),true,()->true,null,50000).outcome());
        assertNull(r.p(Phase.AFTER,2,3));assertEquals(1,r.taps);}
    @Test public void knownMarkersAreCountedAtAnyConfidence(){
        var r=new Rig();var p=r.p(Phase.BEFORE,1,1);byte[] gray=new byte[523*1000];java.util.Arrays.fill(gray,(byte)244);
        var rows=java.util.List.of(new FrameStructureEvidence.Line("更多活动",10,10,100,20,.1),new FrameStructureEvidence.Line("Root 任务卡原生验证",10,50,200,20,.1));
        var o=p.prepare(gray,523,1000,rows,()->false);assertTrue(o.validNativeShape());assertEquals(1,o.nativeMoreCandidates());assertEquals(1,o.nativeWrongPageCandidates());}
}