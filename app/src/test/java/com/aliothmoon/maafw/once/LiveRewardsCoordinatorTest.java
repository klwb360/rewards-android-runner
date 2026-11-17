package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** State-machine tests use only synthetic source grammar, pixels metadata and non-live receipts. */
public class LiveRewardsCoordinatorTest {
    static class Rig implements LiveRewardsCoordinator.Ports {
        final LiveCoreFixture f;
        final LiveRewardsLedger ledger;
        final List<LiveRewardsTypes.Task> tasks;
        final List<LiveRewardsTypes.Kind> reports=new ArrayList<>();
        final List<LiveRewardsTypes.Kind> observedKinds=new ArrayList<>(),tappedKinds=new ArrayList<>();
        final List<String> regionEvents=new ArrayList<>();
        LiveRewardsTypes.Kind incompleteRegion,sourceFailureRegion,foreignRosterRegion;
        boolean completeAfter=true,progress,unknownBefore,unknownAfter,tapFailure,returnFailure,
                lateAfter,compact,ambiguousAfter,regionComplete,cancelAfterTap;
        boolean cleanUiTimeout,cleanCropTimeout;
        boolean replayPriorReturn;
        LiveRewardsInput.ReturnReceipt previousReturn;
        LiveRunAuthority.CardScope expiredScope;
        LiveRunAuthority.UiPermit retiredUi;
        LiveRunAuthority.CropPermit retiredCrop;
        int tapCalls,returnCalls,observeCalls,afterCalls,pauses;
        Rig(List<LiveRewardsTypes.Task> tasks)throws Exception{this(tasks,null);}
        Rig(List<LiveRewardsTypes.Task> tasks,LiveRewardsLedger resumed)throws Exception{
            f=new LiveCoreFixture();this.tasks=tasks;ledger=resumed==null?f.ledger:resumed;
        }
        public LiveRewardsTypes.Preflight preflightRegion(LiveRunAuthority authority,LiveRewardsTypes.Kind kind){
            regionEvents.add("SCAN_"+kind);
            if(kind==sourceFailureRegion)throw new SecurityException("Synthetic source lost");
            var picked=new ArrayList<LiveRewardsTypes.Task>();
            for(var task:tasks)if(task.kind()==kind)picked.add(task);
            if(kind==foreignRosterRegion)picked.add(more("FOREIGN"));
            return new LiveRewardsTypes.Preflight(picked,kind!=incompleteRegion,true,true);
        }
        public boolean knownClean(){return true;}
        public void pause(long millis){assertTrue(millis>=0);f.clock.now+=millis;pauses++;}
        public LiveRewardsTypes.Observation observe(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,LiveRewardsTypes.Task expected)throws Exception{
            observeCalls++;observedKinds.add(expected.kind());
            if(phase==LiveRewardsTypes.Phase.BEFORE){
                if(unknownBefore)return null;
                if(expiredScope!=null){
                    assertNotSame(expiredScope,scope);assertNotSame(expiredScope.owner(),scope.owner());
                    assertTrue(f.authority.current());
                    if(retiredUi!=null){assertFalse(retiredUi.current(f.clock.now));assertFalse(retiredUi.dispatchValid(f.clock.now));}
                    if(retiredCrop!=null){assertFalse(retiredCrop.current(f.clock.now));assertFalse(retiredCrop.dispatchValid(f.clock.now));}
                }
                return f.seal(scope,phase,expected,regionComplete?100:0,100);
            }
            afterCalls++;if(unknownAfter)return null;
            if(afterCalls==1&&(cleanUiTimeout||cleanCropTimeout)){
                expiredScope=scope;
                if(cleanUiTimeout){
                    retiredUi=scope.claimUi(new Object(),f.clock.now);
                    assertTrue(retiredUi.claimReader(f.clock.now));assertTrue(retiredUi.consume(f.clock.now));
                }else{
                    var ui=f.ui(scope);retiredCrop=scope.claimCrop(ui,phase,f.clock.now);
                    assertTrue(retiredCrop.bindRequest(new Box(20,100,420,500),523,1000,0,f.clock.now));
                    assertTrue(retiredCrop.consume(f.clock.now));assertTrue(retiredCrop.claimDispatch(f.clock.now));
                }
                f.clock.now+=5_001;
                if(cleanUiTimeout)scope.discardUi(retiredUi,true);else scope.discardCrop(retiredCrop,true);
                return scope.unreadable(phase,expected,f.source(),f.clock.now,"AFTER_WINDOW_EXPIRED");
            }
            if(lateAfter){f.clock.now+=5_001;return scope.unreadable(phase,expected,f.source(),f.clock.now,"AFTER_WINDOW_EXPIRED");}
            var actual=completeAfter?LiveCoreFixture.completed(expected):expected;
            if(compact)actual=new LiveRewardsTypes.Task(actual.kind(),LiveRewardsTypes.hash("COMPACT_"+expected.key()),
                    Math.min(8,actual.ordinal()+1),actual.titleHash(),actual.points(),true,actual.complete(),actual.day());
            return f.seal(scope,phase,actual,progress?10:0,100,!ambiguousAfter);
        }
        public LiveRewardsInput.TapReceipt tap(LiveActionPermit permit)throws Exception{
            tapCalls++;tappedKinds.add(permit.kind());assertFalse(permit.live());assertTrue(permit.claimTapActor(f.clock.now));assertTrue(permit.consumeTap(f.clock.now));
            f.clock.now+=250;
            var receipt=LiveRewardsInput.TapReceipt.synthetic(permit,!tapFailure,true,f.clock.now);
            if(cancelAfterTap)f.authority.cancel();return receipt;
        }
        public LiveRewardsInput.ReturnReceipt returnAfterTap(LiveActionPermit permit,LiveRewardsInput.TapReceipt tapped)throws Exception{
            returnCalls++;assertNotEquals(LiveRewardsTypes.Kind.CHECK_IN,permit.kind());
            long due=tapped.successfulElapsed()+LiveRewardsInput.RETURN_DELAY_MS;assertEquals(permit.successfulTapElapsed(),tapped.successfulElapsed());
            f.clock.now=Math.max(f.clock.now,due);
            assertTrue(permit.claimBackActor(f.clock.now));assertTrue(permit.consumeBack(f.clock.now));assertTrue(permit.backValid(f.clock.now));
            var receipt=LiveRewardsInput.ReturnReceipt.synthetic(permit,!returnFailure,true,null);
            if(replayPriorReturn&&previousReturn!=null)return previousReturn;
            previousReturn=receipt;return receipt;
        }
        public void report(LiveRewardsTypes.Task task,LiveRewardsTypes.Outcome outcome,String reason,
                LiveRewardsTypes.Observation before,LiveRewardsTypes.Observation after){
            assertTrue(reason.matches("[A-Z][A-Z0-9_]{0,79}"));reports.add(task.kind());
            regionEvents.add("RESULT_"+task.kind()+"_"+outcome);
        }
        LiveRewardsCoordinator.Summary run(){return new LiveRewardsCoordinator(f.authority,ledger,this).run();}
        LiveRewardsCoordinator.Summary runSingleTask(){
            return new LiveRewardsCoordinator(f.authority, ledger, this, true).run();
        }
    }
    private static LiveRewardsTypes.Task daily(String name){return LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,name);}
    private static LiveRewardsTypes.Task more(String name){return LiveCoreFixture.task(LiveRewardsTypes.Kind.MORE,1,name);}
    @SuppressWarnings("unchecked")
    private static java.util.Map<String,String> journalSnapshot(LiveRewardsLedger ledger)throws ReflectiveOperationException{
        var field=LiveRewardsLedger.class.getDeclaredField("store");field.setAccessible(true);var store=field.get(ledger);
        var entries=store.getClass().getDeclaredField("entries");entries.setAccessible(true);
        return java.util.Map.copyOf((java.util.Map<String,String>)entries.get(store));
    }
    @Test public void checkInDailyMoreOrderAndCheckInHasNoReturn()throws Exception{
        var sign=LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN,0,"SIGN");
        var rig=new Rig(List.of(more("M"),daily("D"),sign));var result=rig.run();
        assertFalse(result.stopped());assertTrue(result.preflightPassed());assertEquals(3,result.taps());assertEquals(2,result.backs());
        assertEquals(2,rig.returnCalls);assertEquals(List.of(LiveRewardsTypes.Kind.CHECK_IN,LiveRewardsTypes.Kind.DAILY,LiveRewardsTypes.Kind.MORE),rig.reports);
        assertEquals(Integer.valueOf(3),result.counts().get(LiveRewardsTypes.Outcome.OBSERVED_COMPLETE));
        assertEquals(List.of("SCAN_CHECK_IN","RESULT_CHECK_IN_OBSERVED_COMPLETE","SCAN_DAILY","RESULT_DAILY_OBSERVED_COMPLETE",
                "SCAN_MORE","RESULT_MORE_OBSERVED_COMPLETE"),rig.regionEvents);
    }
    @Test public void singleTaskConfirmsCheckInWithoutEnteringTheActivityRegions() throws Exception {
        var sign = LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN, 0, "SIGN");
        var activity = daily("LATER");
        var rig = new Rig(List.of(activity, sign, more("LAST")));
        var coordinator = new LiveRewardsCoordinator(rig.f.authority, rig.ledger, rig, true);
        var result = coordinator.run();
        assertFalse(result.stopped());
        assertEquals("SINGLE_TASK_FINISHED", result.reason());
        assertEquals(1, result.regionsReady());
        assertEquals(List.of("SCAN_CHECK_IN", "RESULT_CHECK_IN_OBSERVED_COMPLETE"), rig.regionEvents);
        assertEquals(1, rig.afterCalls);
        assertEquals(0, rig.returnCalls);
        assertTrue(rig.ledger.attempted(sign));
        assertFalse(rig.ledger.attempted(activity));
        assertEquals("ALREADY_STARTED", coordinator.run().reason());
        assertEquals(1, rig.tapCalls);
    }

    @Test public void singleTaskSkipsIneligibleCardsAndStopsAfterOneUnconfirmedActivity() throws Exception {
        var sign = LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN, 0, "PREVIOUS_SIGN");
        var done = LiveCoreFixture.completed(daily("DONE"));
        var duplicate = LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY, 2, "DUPLICATE");
        var twin = LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY, 3, "DUPLICATE");
        var eligible = LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY, 4, "ELIGIBLE");
        var later = LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY, 5, "ALSO_ELIGIBLE");
        var last = more("LAST");
        var previous = LiveRewardsLedger.synthetic(LiveCoreFixture.DAY, LiveCoreFixture.RUN);
        previous.begin(sign);
        var today = previous.syntheticNextRun("00000000-0000-4000-8000-000000000002");
        var rig = new Rig(List.of(last, later, eligible, twin, duplicate, done, sign), today);
        rig.completeAfter = false;
        var result = rig.runSingleTask();
        assertFalse(result.stopped());
        assertEquals("SINGLE_TASK_FINISHED", result.reason());
        assertEquals(Integer.valueOf(1), result.counts().get(LiveRewardsTypes.Outcome.ALREADY_ATTEMPTED_SKIP));
        assertEquals(Integer.valueOf(1), result.counts().get(LiveRewardsTypes.Outcome.COMPLETE_SKIP));
        assertEquals(Integer.valueOf(2), result.counts().get(LiveRewardsTypes.Outcome.DUPLICATE_SKIP));
        assertEquals(Integer.valueOf(1), result.counts().get(LiveRewardsTypes.Outcome.UNCONFIRMED));
        assertEquals(List.of(LiveRewardsTypes.Kind.DAILY), rig.tappedKinds);
        assertEquals(1, rig.returnCalls);
        assertEquals(2, rig.afterCalls);
        assertTrue(rig.ledger.attempted(eligible));
        assertFalse(rig.ledger.attempted(later));
        assertFalse(rig.ledger.attempted(last));
        assertFalse(rig.regionEvents.contains("SCAN_MORE"));
    }

    @Test public void singleTaskDoesNotReplaceAReservedTaskWhenItsInputFails() throws Exception {
        var chosen = daily("CHOSEN");
        var next = LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY, 2, "NEXT");
        var rig = new Rig(List.of(chosen, next, more("LAST"))) {
            @Override public LiveRewardsInput.TapReceipt tap(LiveActionPermit permit) {
                tapCalls++;
                throw new SecurityException("Final source check failed before dispatch");
            }
        };
        var result = rig.runSingleTask();
        assertTrue(result.stopped());
        assertEquals("RUN_FAILED", result.reason());
        assertTrue(rig.ledger.attempted(chosen));
        assertFalse(rig.ledger.attempted(next));
        assertEquals(1, rig.tapCalls);
        assertEquals(0, result.taps());
        assertEquals(0, rig.returnCalls);
    }

    @Test public void singleTaskWithNoEligibleTaskFinishesWithoutInput() throws Exception {
        var done = LiveCoreFixture.completed(daily("DONE"));
        var rig = new Rig(List.of(done));
        var result = rig.runSingleTask();
        assertFalse(result.stopped());
        assertEquals("NO_ELIGIBLE_TASK", result.reason());
        assertEquals(3, result.regionsReady());
        assertEquals(0, rig.tapCalls);
        assertEquals(0, rig.returnCalls);
        assertFalse(rig.ledger.attempted(done));
    }
    @Test public void laterSourceFailureKeepsEarlierClicksAndTheirResults()throws Exception{
        var sign=LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN,0,"SIGN");
        var one=daily("FIRST");var two=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,2,"SECOND");var later=more("LATER");
        var rig=new Rig(List.of(later,two,sign,one));rig.sourceFailureRegion=LiveRewardsTypes.Kind.MORE;
        var result=rig.run();
        assertTrue(result.stopped());assertFalse(result.preflightPassed());assertEquals(2,result.regionsReady());
        assertEquals(3,result.planned());assertEquals(3,result.taps());assertEquals(2,result.backs());
        assertEquals(List.of("SCAN_CHECK_IN","RESULT_CHECK_IN_OBSERVED_COMPLETE","SCAN_DAILY","RESULT_DAILY_OBSERVED_COMPLETE",
                "RESULT_DAILY_OBSERVED_COMPLETE","SCAN_MORE"),rig.regionEvents);
        for(var attempted:List.of(sign,one,two))assertTrue(rig.ledger.attempted(attempted));
        assertFalse(rig.ledger.attempted(later));assertEquals(Integer.valueOf(3),result.counts().get(LiveRewardsTypes.Outcome.OBSERVED_COMPLETE));
    }
    @Test public void incompleteOrForeignDailyRosterCannotStartEvenItsFirstCard()throws Exception{
        var one=daily("FIRST");var two=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,2,"SECOND");
        for(boolean foreign:new boolean[]{false,true}){
            var rig=new Rig(List.of(one,two,more("NEXT_REGION")));
            if(foreign)rig.foreignRosterRegion=LiveRewardsTypes.Kind.DAILY;else rig.incompleteRegion=LiveRewardsTypes.Kind.DAILY;
            var result=rig.run();assertTrue(result.stopped());assertEquals("PREFLIGHT_UNQUALIFIED_DAILY",result.reason());
            assertEquals(0,result.taps());assertEquals(0,result.backs());assertEquals(List.of("SCAN_CHECK_IN","SCAN_DAILY"),rig.regionEvents);
            assertFalse(rig.ledger.attempted(one));assertFalse(rig.ledger.attempted(two));
        }
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
        var sign=LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN,0,"SIGN");var rig=new Rig(List.of(sign,daily("LATER"),more("NEXT")));
        rig.completeAfter=false;rig.progress=true;var result=rig.run();
        assertEquals(0,rig.returnCalls);assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.UNCONFIRMED));
        assertTrue(result.stopped());assertEquals("CHECK_IN_NOT_CONFIRMED",result.reason());
        assertEquals(List.of("SCAN_CHECK_IN","RESULT_CHECK_IN_UNCONFIRMED"),rig.regionEvents);
        assertEquals(1,result.taps());assertTrue(rig.ledger.attempted(sign));
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
    @Test public void backCommandWithoutAPageReceiptStillRequiresFreshAfterObservation()throws Exception{
        var rig=new Rig(List.of(more("M")));var result=rig.run();assertFalse(result.stopped());
        assertEquals(1,result.backs());assertEquals(1,result.taps());assertEquals(1,rig.afterCalls);
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
    @Test public void resumedUnreadableCheckInSkipsEveryPriorAttemptStateAndContinuesOtherRegions()throws Exception{
        for(int records=1;records<=3;records++){
            var descriptor=LiveCardIdentity.hashText("CHECK_IN");
            var sign=new LiveRewardsTypes.Task(LiveRewardsTypes.Kind.CHECK_IN,descriptor,0,LiveRewardsTypes.hash("SIGN_TITLE"),5,true,false,2);
            var prior=LiveRewardsLedger.synthetic(LiveCoreFixture.DAY,LiveCoreFixture.RUN);
            prior.begin(sign);
            if(records>=2)prior.burnTap(sign);
            if(records==3)prior.finish(sign,LiveRewardsTypes.Outcome.UNCONFIRMED);
            var previousRecords=journalSnapshot(prior);
            var fresh=prior.syntheticNextRun("00000000-0000-4000-8000-000000000002");
            var seed=new LiveRewardsTypes.Task(LiveRewardsTypes.Kind.CHECK_IN,descriptor,0,"",0,false,false,-1);
            assertTrue(sign.sameKey(seed));assertTrue(fresh.attempted(seed));
            var rig=new Rig(List.of(more("M"),seed,daily("D")),fresh);var result=rig.run();
            assertFalse(result.reason(),result.stopped());assertTrue(result.preflightPassed());
            assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.ALREADY_ATTEMPTED_SKIP));
            assertEquals(Integer.valueOf(0),result.counts().get(LiveRewardsTypes.Outcome.UNREADABLE_SKIP));
            assertEquals(Integer.valueOf(2),result.counts().get(LiveRewardsTypes.Outcome.OBSERVED_COMPLETE));
            assertEquals(List.of(LiveRewardsTypes.Kind.DAILY,LiveRewardsTypes.Kind.MORE),rig.tappedKinds);
            assertFalse(rig.observedKinds.contains(LiveRewardsTypes.Kind.CHECK_IN));
            assertEquals(4,rig.observeCalls);assertEquals(2,rig.returnCalls);assertEquals(2,result.taps());assertEquals(2,result.backs());
            assertTrue(prior.attempted(sign));assertTrue(fresh.attempted(seed));
            try{fresh.begin(seed);fail("A new run cannot replace the previous pending record");}
            catch(java.nio.file.FileAlreadyExistsException expected){assertNotNull(expected.getMessage());}
            assertTrue("Prior pending, tap and result records must remain byte-for-byte",journalSnapshot(prior).entrySet().containsAll(previousRecords.entrySet()));
        }
    }
    @Test public void cleanAfterTimeoutRetiresOldReadCapabilityAndNextScopeCompletes()throws Exception{
        for(boolean crop:new boolean[]{false,true}){
            var rig=new Rig(List.of(daily("D"),more("M")));rig.cleanCropTimeout=crop;rig.cleanUiTimeout=!crop;
            var result=rig.run();assertFalse(result.reason(),result.stopped());assertTrue(rig.f.authority.current());
            assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.UNCONFIRMED));
            assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.OBSERVED_COMPLETE));
            assertEquals(List.of(LiveRewardsTypes.Kind.DAILY,LiveRewardsTypes.Kind.MORE),rig.reports);
            assertEquals(2,rig.tapCalls);assertEquals(2,rig.returnCalls);assertEquals(2,rig.afterCalls);
        }
    }
    @Test public void resumedCheckInAndDailyAttemptsStaySpentWhileFreshDailyAndMoreContinue()throws Exception{
        var sign=new LiveRewardsTypes.Task(LiveRewardsTypes.Kind.CHECK_IN,LiveCardIdentity.hashText("CHECK_IN"),0,
                LiveRewardsTypes.hash("SIGN_TITLE"),5,true,false,2);
        var attemptedDaily=daily("PRIOR_DAILY");var prior=LiveRewardsLedger.synthetic(LiveCoreFixture.DAY,LiveCoreFixture.RUN);
        for(var t:List.of(sign,attemptedDaily)){
            prior.begin(t);prior.burnTap(t);prior.finish(t,t.kind()==LiveRewardsTypes.Kind.CHECK_IN?LiveRewardsTypes.Outcome.UNCONFIRMED:LiveRewardsTypes.Outcome.STOPPED);
        }
        var saved=journalSnapshot(prior);var resumed=prior.syntheticNextRun("00000000-0000-4000-8000-000000000002");
        var signSeed=LivePreflightHistory.checkInSeed();var dailySeed=LivePreflightHistory.unreadable(attemptedDaily);
        var next=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,2,"NEXT_DAILY");
        var rig=new Rig(List.of(signSeed,dailySeed,next,more("NEXT_MORE")),resumed);var result=rig.run();
        assertFalse(result.reason(),result.stopped());assertEquals(Integer.valueOf(2),result.counts().get(LiveRewardsTypes.Outcome.ALREADY_ATTEMPTED_SKIP));
        assertEquals(Integer.valueOf(2),result.counts().get(LiveRewardsTypes.Outcome.OBSERVED_COMPLETE));
        assertEquals(2,rig.tapCalls);assertEquals(2,rig.returnCalls);assertEquals(4,rig.observeCalls);assertFalse(rig.observedKinds.contains(LiveRewardsTypes.Kind.CHECK_IN));
        assertEquals(List.of(LiveRewardsTypes.Kind.DAILY,LiveRewardsTypes.Kind.MORE),rig.tappedKinds);
        assertTrue(journalSnapshot(prior).entrySet().containsAll(saved.entrySet()));
    }
    @Test public void anotherCardsBackReceiptCannotAuthorizeAfterObservation()throws Exception{
        var rig=new Rig(List.of(daily("D"),more("M")));rig.replayPriorReturn=true;
        var result=rig.run();assertTrue(result.stopped());assertEquals("RETURN_UNCONFIRMED",result.reason());
        assertEquals(2,rig.tapCalls);assertEquals(2,rig.returnCalls);assertEquals(1,rig.afterCalls);
        assertEquals(Integer.valueOf(1),result.counts().get(LiveRewardsTypes.Outcome.OBSERVED_COMPLETE));
    }
}
