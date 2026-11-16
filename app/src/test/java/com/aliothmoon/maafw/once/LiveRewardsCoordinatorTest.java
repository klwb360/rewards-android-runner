package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** State-machine tests use only synthetic source grammar, pixels metadata and non-live receipts. */
public class LiveRewardsCoordinatorTest {
    private static final class Rig implements LiveRewardsCoordinator.Ports {
        final LiveCoreFixture f;
        final List<LiveRewardsTypes.Task> tasks;
        final List<LiveRewardsTypes.Kind> reports=new ArrayList<>();
        boolean completeAfter=true,progress,autoReturned,unknownBefore,unknownAfter,tapFailure,returnFailure,
                lateAfter,compact,ambiguousAfter,regionComplete,cancelAfterTap;
        int tapCalls,returnCalls,observeCalls,afterCalls,pauses;
        Rig(List<LiveRewardsTypes.Task> tasks)throws Exception{f=new LiveCoreFixture();this.tasks=tasks;}
        public LiveRewardsTypes.Preflight preflight(LiveRunAuthority authority){return new LiveRewardsTypes.Preflight(tasks,true,true,true);}
        public boolean knownClean(){return true;}
        public void pause(long millis){assertTrue(millis>=0);f.clock.now+=millis;pauses++;}
        public LiveRewardsTypes.Observation observe(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,LiveRewardsTypes.Task expected)throws Exception{
            observeCalls++;
            if(phase==LiveRewardsTypes.Phase.BEFORE){
                if(unknownBefore)return null;
                return f.seal(scope,phase,expected,regionComplete?100:0,100);
            }
            afterCalls++;if(unknownAfter)return null;
            if(lateAfter){f.clock.now+=5_001;return scope.unreadable(phase,expected,f.source(),f.clock.now,"AFTER_WINDOW_EXPIRED");}
            var actual=completeAfter?LiveCoreFixture.completed(expected):expected;
            if(compact)actual=new LiveRewardsTypes.Task(actual.kind(),LiveRewardsTypes.hash("COMPACT_"+expected.key()),
                    Math.min(8,actual.ordinal()+1),actual.titleHash(),actual.points(),true,actual.complete(),actual.day());
            return f.seal(scope,phase,actual,progress?10:0,100,!ambiguousAfter);
        }
        public LiveRewardsInput.TapReceipt tap(LiveActionPermit permit)throws Exception{
            tapCalls++;assertFalse(permit.live());assertTrue(permit.claimTapActor(f.clock.now));assertTrue(permit.consumeTap(f.clock.now));
            f.clock.now+=250;
            var receipt=LiveRewardsInput.TapReceipt.synthetic(permit,!tapFailure,true,f.clock.now);
            if(cancelAfterTap)f.authority.cancel();return receipt;
        }
        public LiveRewardsInput.ReturnReceipt returnAfterTap(LiveActionPermit permit,LiveRewardsInput.TapReceipt tapped)throws Exception{
            returnCalls++;assertNotEquals(LiveRewardsTypes.Kind.CHECK_IN,permit.kind());
            long due=tapped.successfulElapsed()+3_000;assertEquals(permit.successfulTapElapsed(),tapped.successfulElapsed());
            f.clock.now=Math.max(f.clock.now,due);
            if(!autoReturned){assertTrue(permit.claimBackActor(f.clock.now));assertTrue(permit.consumeBack(f.clock.now));assertTrue(permit.backValid(f.clock.now));}
            return LiveRewardsInput.ReturnReceipt.synthetic(permit,!returnFailure,true,returnFailure?null:f.source());
        }
        public void report(LiveRewardsTypes.Task task,LiveRewardsTypes.Outcome outcome,String reason,
                LiveRewardsTypes.Observation before,LiveRewardsTypes.Observation after){
            assertTrue(reason.matches("[A-Z][A-Z0-9_]{0,79}"));reports.add(task.kind());
        }
        LiveRewardsCoordinator.Summary run(){return new LiveRewardsCoordinator(f.authority,f.ledger,this).run();}
    }
    private static LiveRewardsTypes.Task daily(String name){return LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,name);}
    private static LiveRewardsTypes.Task more(String name){return LiveCoreFixture.task(LiveRewardsTypes.Kind.MORE,1,name);}
    @Test public void checkInDailyMoreOrderAndCheckInHasNoReturn()throws Exception{
        var sign=LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN,0,"SIGN");
        var rig=new Rig(List.of(more("M"),daily("D"),sign));var result=rig.run();
        assertFalse(result.stopped());assertTrue(result.preflightPassed());assertEquals(3,result.taps());assertEquals(2,result.backs());
        assertEquals(2,rig.returnCalls);assertEquals(List.of(LiveRewardsTypes.Kind.CHECK_IN,LiveRewardsTypes.Kind.DAILY,LiveRewardsTypes.Kind.MORE),rig.reports);
        assertEquals(Integer.valueOf(3),result.counts().get(LiveRewardsTypes.Outcome.OBSERVED_COMPLETE));
    }
    @Test public void unclearCompletionContinuesOtherCardsWithoutRetry()throws Exception{
        var rig=new Rig(List.of(daily("D"),more("M")));rig.completeAfter=false;var result=rig.run();
        assertFalse(result.stopped());assertEquals(2,rig.tapCalls);assertEquals(4,rig.afterCalls);
        assertEquals(Integer.valueOf(2),result.counts().get(LiveRewardsTypes.Outcome.UNCONFIRMED));assertEquals(2,rig.pauses);
        assertTrue(rig.f.ledger.attempted(daily("D")));assertTrue(rig.f.ledger.attempted(more("M")));
    }
    @Test public void knownRegionProgressIsDistinctFromSameTaskCompletion()throws Exception{
        var rig=new Rig(List.of(more("M")));rig.completeAfter=false;rig.progress=true;var result=rig.run();
        assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.PROGRESS_CHANGED));
        assertEquals(Integer.valueOf(0),result.counts().get(LiveRewardsTypes.Outcome.OBSERVED_COMPLETE));assertEquals(1,rig.afterCalls);
    }
    @Test public void signInCannotBorrowARegionProgressDeltaAsCompletion()throws Exception{
        var sign=LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN,0,"SIGN");var rig=new Rig(List.of(sign));
        rig.completeAfter=false;rig.progress=true;var result=rig.run();
        assertEquals(0,rig.returnCalls);assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.UNCONFIRMED));
    }
    @Test public void completionCompressionAndNewOrdinalUseUniqueTitleAndValueNotPosition()throws Exception{
        var rig=new Rig(List.of(daily("D")));rig.compact=true;var result=rig.run();
        assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.OBSERVED_COMPLETE));
    }
    @Test public void duplicateDescriptorTasksCannotBeClickedEvenAtDifferentOrdinals()throws Exception{
        var task=daily("D");var sibling=new LiveRewardsTypes.Task(task.kind(),task.descriptorHash(),2,task.titleHash(),task.points(),true,false,-1);
        var rig=new Rig(List.of(task,sibling));var result=rig.run();
        assertEquals(0,rig.tapCalls);assertEquals(0,rig.observeCalls);assertEquals(Integer.valueOf(2),result.counts().get(LiveRewardsTypes.Outcome.DUPLICATE_SKIP));
    }
    @Test public void anAmbiguousCompactCompletionIsNeverCountedAsSameTaskComplete()throws Exception{
        var rig=new Rig(List.of(daily("D")));rig.compact=true;rig.ambiguousAfter=true;var result=rig.run();
        assertEquals(Integer.valueOf(0),result.counts().get(LiveRewardsTypes.Outcome.OBSERVED_COMPLETE));
        assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.UNCONFIRMED));
    }
    @Test public void initiallyCompleteAndUnreadableTasksAreReportedWithoutActions()throws Exception{
        var done=LiveCoreFixture.completed(daily("D"));var unread=more("M");
        unread=new LiveRewardsTypes.Task(unread.kind(),unread.descriptorHash(),1,"",5,false,false,-1);
        var rig=new Rig(List.of(done,unread));var result=rig.run();assertEquals(0,rig.tapCalls);
        assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.COMPLETE_SKIP));
        assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.UNREADABLE_SKIP));
    }
    @Test public void fullRegionIsSkippedBeforeTapAndRemainingRegionCardsAreNotRead()throws Exception{
        var a=daily("A");var b=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,2,"B");var rig=new Rig(List.of(a,b));
        rig.regionComplete=true;var result=rig.run();assertEquals(0,rig.tapCalls);assertEquals(1,rig.observeCalls);
        assertEquals(Integer.valueOf(2),result.counts().get(LiveRewardsTypes.Outcome.REGION_COMPLETE_SKIP));
    }
    @Test public void automaticReturnToRewardsDoesNotSpendBack()throws Exception{
        var rig=new Rig(List.of(more("M")));rig.autoReturned=true;var result=rig.run();assertFalse(result.stopped());
        assertEquals(0,result.backs());assertEquals(1,result.taps());
    }
    @Test public void unknownSourceAndUnconfirmedInputStopTheWholeRun()throws Exception{
        var unknown=new Rig(List.of(daily("D"),more("M")));unknown.unknownBefore=true;
        assertTrue(unknown.run().stopped());assertEquals(0,unknown.tapCalls);
        var input=new Rig(List.of(daily("D"),more("M")));input.tapFailure=true;
        assertTrue(input.run().stopped());assertEquals(1,input.tapCalls);assertEquals(0,input.returnCalls);assertEquals(0,input.afterCalls);
        assertTrue(input.f.ledger.attempted(daily("D")));
    }
    @Test public void returnFailureAndAfterSourceLossNeverAdvanceToNextTask()throws Exception{
        var back=new Rig(List.of(daily("D"),more("M")));back.returnFailure=true;
        assertTrue(back.run().stopped());assertEquals(1,back.tapCalls);assertEquals(1,back.returnCalls);
        var after=new Rig(List.of(daily("D"),more("M")));after.unknownAfter=true;
        assertTrue(after.run().stopped());assertEquals(1,after.tapCalls);
    }
    @Test public void observationBeyondFiveSecondWindowIsNotDeclaredComplete()throws Exception{
        var rig=new Rig(List.of(daily("D"),more("M")));rig.lateAfter=true;var result=rig.run();
        assertFalse(result.stopped());assertEquals(2,rig.tapCalls);assertEquals(2,rig.afterCalls);
        assertEquals(Integer.valueOf(2),result.counts().get(LiveRewardsTypes.Outcome.UNCONFIRMED));
    }
    @Test public void dayChangeOrCancellationPreventsFollowingInput()throws Exception{
        var rig=new Rig(List.of(daily("D"),more("M")));rig.cancelAfterTap=true;
        assertTrue(rig.run().stopped());assertEquals(1,rig.tapCalls);assertEquals(0,rig.returnCalls);
        var day=new Rig(List.of(daily("D")));day.f.clock.day="2026-11-17";
        assertTrue(day.run().stopped());assertEquals(0,day.tapCalls);
    }
    @Test public void atMostSeventeenTapAndSixteenBackScopesCanRun()throws Exception{
        List<LiveRewardsTypes.Task> tasks=new ArrayList<>();tasks.add(LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN,0,"S"));
        for(var kind:new LiveRewardsTypes.Kind[]{LiveRewardsTypes.Kind.DAILY,LiveRewardsTypes.Kind.MORE})
            for(int i=1;i<=8;i++)tasks.add(LiveCoreFixture.task(kind,i,kind+"_"+i));
        var rig=new Rig(tasks);var result=rig.run();assertFalse(result.reason(),result.stopped());assertEquals(17,result.taps());assertEquals(16,result.backs());
    }
    @Test public void sameDayPriorAttemptIsNotReopenedAndSummaryContainsNoRawTitle()throws Exception{
        var task=daily("SYNTHETIC_SECRET_TITLE");var rig=new Rig(List.of(task));rig.f.ledger.begin(task);
        var result=rig.run();assertEquals(0,rig.tapCalls);assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.ALREADY_ATTEMPTED_SKIP));
        assertFalse(result.fixedSummary().contains("SECRET"));
    }
}
