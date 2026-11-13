package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.AutonomousFlowRig.Step.*;
import static com.aliothmoon.maafw.once.NativeRewardCardSession.*;
public class AutonomousSessionReturnTest {
    @Test public void syntheticNativeTapBackReturnThenFreshSiblingExcludedTickCompletes(){
        var r=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);var b=r.before();
        assertEquals(Outcome.AWAITING_NEW_OBSERVATION,b.outcome());assertEquals(Reason.RETURN_READY_NOT_COMPLETION,b.reason());
        assertTrue(r.session.returnReady());assertTrue(r.session.needsAfter());assertEquals(1,r.taps);assertEquals(1,r.backs);assertEquals(1,r.returnBurns);
        var a=r.after(true,false);assertEquals(Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED,a.outcome());
        assertEquals(Reason.SAME_TASK_ROSTER_COMPACT_NATIVE_TICK,a.reason());assertFalse(a.accountCreditKnown());assertFalse(a.causedByTap());
        assertTrue(r.sourceReads+r.observations<=12);assertFalse(r.session.needsAfter());assertEquals(1,r.taps);assertEquals(1,r.backs);
    }
    @Test public void alreadySourceSnapshotNeverClaimsBrowserObservedOrIssuesBack(){
        var r=new AutonomousFlowRig(TEMPLATE);assertEquals(Outcome.AWAITING_NEW_OBSERVATION,r.before().outcome());
        assertEquals(0,r.backs);assertEquals(0,r.returnBurns);assertTrue(r.session.returnSummary().contains("browserObserved=false"));
        assertEquals(Outcome.HOLD,r.after(false,false).outcome());
    }
    @Test public void transitionsNeedFreshReadyObservationsNotElapsedTime(){
        var r=new AutonomousFlowRig(TRANSITION,BROWSER,BROWSER,TRANSITION,TEMPLATE);
        assertEquals(Outcome.AWAITING_NEW_OBSERVATION,r.before().outcome());assertEquals(5,r.observations);assertEquals(1,r.backs);
        assertEquals(Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED,r.after(true,false).outcome());assertTrue(r.sourceReads+r.observations<=12);
    }
    @Test public void unknownOrDifferentTaskNeverBurnsOrSendsBack(){
        for(var step:new AutonomousFlowRig.Step[]{UNKNOWN,DIFFERENT_TASK}){
            var r=new AutonomousFlowRig(step);assertEquals(Outcome.HOLD,r.before().outcome());assertEquals(0,r.returnBurns);assertEquals(0,r.backs);assertFalse(r.session.needsAfter());
        }
    }
    @Test public void finalBrowserWindowChangeOrUnknownSpendsReturnWithoutBackRetry(){
        for(var step:new AutonomousFlowRig.Step[]{UNKNOWN,DIFFERENT_BROWSER,TEMPLATE}){
            var r=new AutonomousFlowRig(BROWSER,step);assertEquals(Outcome.HOLD,r.before().outcome());assertEquals(1,r.returnBurns);assertEquals(0,r.backs);
            assertFalse(r.session.needsAfter());assertNull(r.session.startCapture(Phase.AFTER,523,1000,0));
        }
    }
    @Test public void backExitZeroWithoutOriginalTemplateReadyCannotOpenAfter(){
        var r=new AutonomousFlowRig(BROWSER,BROWSER,BROWSER,BROWSER);assertEquals(Outcome.HOLD,r.before().outcome());
        assertEquals(1,r.backs);assertFalse(r.session.needsAfter());assertFalse(r.session.returnReady());assertNull(r.session.startCapture(Phase.AFTER,523,1000,0));
    }
    @Test public void uncertainDurableReturnOrBackIsNeverRetried(){
        var r=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);r.returnBurnThrows=true;
        assertEquals(Outcome.HOLD,r.before().outcome());assertEquals(1,r.returnBurns);assertEquals(0,r.backs);assertFalse(r.session.needsAfter());
        r=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);r.backThrows=true;
        assertEquals(Outcome.HOLD,r.before().outcome());assertEquals(1,r.backs);assertFalse(r.backActor.knownClean());assertFalse(r.session.needsAfter());
        r=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);r.backTimeout=true;
        assertEquals(Outcome.HOLD,r.before().outcome());assertEquals(1,r.backs);assertFalse(r.backActor.knownClean());
    }
    @Test public void deadlineOrCancelDuringReturnBurnBlocksTheFinalBack(){
        var r=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);r.onReturnBurn=()->rCancelHolder(r); // local helper uses actual session cancel
        assertEquals(Outcome.HOLD,r.before().outcome());assertEquals(0,r.backs);
        var late=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);late.onReturnBurn=()->late.now+=31_000;
        assertEquals(Outcome.HOLD,late.before().outcome());assertEquals(0,late.backs);
        var deadline=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);assertEquals(Outcome.HOLD,deadline.before(1040).outcome());assertEquals(0,deadline.backs);
    }
    private static void rCancelHolder(AutonomousFlowRig r){r.session.cancel();}
    @Test public void nativeCounterIncreaseWithoutSelectedTickRemainsHold(){
        var r=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);r.before();var rows=AutonomousCardFixture.rows(false,false);
        MoreVisibleCardObservationTest.replace(rows,"10/20","15/20",.95);
        assertEquals(Outcome.HOLD,r.after(AutonomousCardFixture.pixels(false,false),rows).outcome());
    }
    @Test public void multipleChangesOrBothBodylessTicksCannotUseUniqueSiblingExclusion(){
        var r=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);r.before();assertEquals(Outcome.HOLD,r.after(true,true).outcome());
        r=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);r.before();var rows=AutonomousCardFixture.rows(true,false);
        MoreVisibleCardObservationTest.replace(rows,AutonomousCardFixture.B,"New conflicting body",.95);
        assertEquals(Outcome.HOLD,r.after(AutonomousCardFixture.pixels(true,false),rows).outcome());
    }
    @Test public void sourceLossBeforeNativeAfterAcceptanceCannotBeReplacedByReturnHistory(){
        var r=new AutonomousFlowRig(BROWSER,BROWSER,TEMPLATE);r.before();r.now+=1000;
        var c=r.session.startCapture(Phase.AFTER,523,1000,0);var o=c.prepare(AutonomousCardFixture.pixels(true,false),523,1000,AutonomousCardFixture.rows(true,false),()->false);
        r.source=false;assertEquals(Outcome.HOLD,r.session.acceptAfterCleanup(c,o,true,()->true,null,100000).outcome());assertEquals(1,r.taps);assertEquals(1,r.backs);
    }
}
