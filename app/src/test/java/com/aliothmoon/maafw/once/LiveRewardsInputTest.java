package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exact permit tests using in-memory journals and synthetic IPC only; never sends Android input. */
public class LiveRewardsInputTest {
    static final class Clock implements LiveRunAuthority.Clock {
        long now=1_000;String day="2026-11-16";
        public long elapsedRealtime(){return now;}public String day(){return day;}
    }
    static final class Rig implements LiveRewardsInput.Ports {
        final Clock clock=new Clock();
        final LiveRunAuthority authority=LiveRunAuthority.synthetic(new Object(),new RealCaptureClaim.ProcessEpoch(),clock,()->true);
        final LiveRewardsLedger ledger;
        final LiveRewardsInput input;
        int taps,backs,visits,pages,rebinds,pauses,templateCalls;long successful=-1,backAt=-1;
        boolean child=true,returned,clean=true,rebind=true,unknownVisit,blocked,cancelDuringWait,changeSourceAtFinal,unknownTap,unknownBack,closeFailure;
        boolean secondRebindFails,backStaysChild,clockBackwards,sameTemplateChild;
        boolean forbidPageReads,dayChangesDuringWait,cleanupLostDuringWait,backCloseFailure,backRotationChanged,cancelAtBackFinal;
        int backExit;
        int task=79,identity;
        Rig()throws Exception{ledger=LiveRewardsLedger.synthetic(clock.day,UUID.randomUUID().toString());authority.attachLedger(ledger);input=LiveRewardsInput.synthetic(authority,this);}
        LiveActionPermit permit(LiveRewardsTypes.Kind kind)throws Exception{
            var taskRecord=new LiveRewardsTypes.Task(kind,LiveRewardsTypes.hash("CARD_"+(++identity)),kind==LiveRewardsTypes.Kind.CHECK_IN?0:identity,
                    LiveRewardsTypes.hash("TITLE_"+identity),10,true,false,kind==LiveRewardsTypes.Kind.CHECK_IN?1:-1);
            var scope=authority.cardScope(taskRecord);var ui=scope.claimUi(new Object(),clock.now);
            assertTrue(ui.claimReader(clock.now));assertTrue(ui.consume(clock.now));scope.finishUi(ui,true,true,clock.now);
            var source=LiveNavigationSnapshotTest.original(task,clock.now);
            var crop=scope.claimCrop(ui,LiveRewardsTypes.Phase.BEFORE,clock.now);
            assertTrue(crop.bindRequest(new Box(20,120,500,520),source.profile().shape(),clock.now));
            assertTrue(crop.consume(clock.now));assertTrue(crop.claimDispatch(clock.now));scope.finishCrop(crop,true,true,clock.now);
            var before=scope.sealObservation(crop,new LiveRewardsTypes.ObservationData(taskRecord,source,clock.now,new Box(20,120,500,520),480,400,
                    new Box(370,150,450,210),.95,LiveRewardsTypes.hash("NATIVE_CROP"),true,true,true,1,120));
            ledger.begin(taskRecord);return scope.actionPermit(before,ledger);
        }
        public long elapsedRealtime(){return clock.now;}
        public void pause(long millis){
            pauses++;assertTrue(millis>0&&millis<=250);
            if(successful>=0&&clock.now<successful+5_000){assertEquals(0,backs);assertEquals(0,visits);assertEquals(0,pages);}
            clock.now+=millis;if(clockBackwards)clock.now-=2*millis;
            if(cancelDuringWait)authority.cancel();
            if(dayChangesDuringWait)clock.day="2026-11-17";
            if(cleanupLostDuringWait)clean=false;
        }
        public boolean current(){return authority.current();}public boolean knownClean(){return clean;}
        public BingNavigationSnapshot.Ready template(){
            if(forbidPageReads)throw new AssertionError("Return must not request a template or page");
            templateCalls++;return LiveNavigationSnapshotTest.original(changeSourceAtFinal&&rebinds>1?task+1:task,clock.now);
        }
        public boolean beforeTap(LiveActionPermit permit){rebinds++;return rebind&&!(secondRebindFails&&rebinds==2);}
        public BingNavigationSnapshot.LiveVisitResult visit(BingNavigationSnapshot.Ready original){
            visits++;if(forbidPageReads)throw new AssertionError("Return must not request navigation");
            return unknownVisit?BingNavigationSnapshot.LiveVisitResult.unknown(BingNavigationSnapshot.LiveVisitGate.STRICT_SOURCE)
                    :LiveNavigationSnapshotTest.visit(task,clock.now,child&&!returned&&!sameTemplateChild,original);
        }
        public LiveRewardsInput.VisitPage page(LiveActionPermit permit,BingNavigationSnapshot.LiveVisitSource source){
            pages++;if(forbidPageReads)throw new AssertionError("Return must not inspect page semantics");
            return blocked?LiveRewardsInput.VisitPage.BLOCKED:child&&!returned?LiveRewardsInput.VisitPage.BOUND_CHILD:LiveRewardsInput.VisitPage.ORIGINAL_REWARDS;
        }
        public ReviewShizukuTapActor tapActor(LiveActionPermit permit){
            return ReviewShizukuTapActor.liveFixture(()->new ReviewShizukuTapActor.Session(){
                public boolean stillReady(){return true;}
                public ReviewShizukuTapActor.DisplayShape display(){return new ReviewShizukuTapActor.DisplayShape(523,1000,0);}
                public ReviewShizukuTapActor.PendingTap dispatch(ReviewShizukuTapActor.TapTarget target,ReviewShizukuTapActor.FinalGate gate)throws Exception{
                    gate.requireValid();taps++;if(unknownTap)throw new Exception("Synthetic unknown dispatch");
                    return new ReviewShizukuTapActor.PendingTap(){
                        public boolean waitForExit(long millis){clock.now+=125;return true;}public int exitCode(){return 0;}
                        public void destroy(){}public void close()throws Exception{if(closeFailure)throw new Exception("Synthetic cleanup");}
                    };
                }
            },permit,()->clock.now);
        }
        public ReviewShizukuBackActor backActor(LiveActionPermit permit){
            return ReviewShizukuBackActor.liveFixture(()->new ReviewShizukuBackActor.Session(){
                public boolean stillReady(){return true;}
                public ReviewShizukuBackActor.DisplayShape display(){return new ReviewShizukuBackActor.DisplayShape(523,1000,backRotationChanged?1:0);}
                public ReviewShizukuBackActor.PendingBack dispatch(ReviewShizukuBackActor.BackTarget target,ReviewShizukuBackActor.FinalGate gate)throws Exception{
                    if(cancelAtBackFinal)authority.cancel();
                    gate.requireValid();backAt=clock.now;backs++;if(unknownBack)throw new Exception("Synthetic unknown return");
                    if(!backStaysChild)returned=true;
                    return new ReviewShizukuBackActor.PendingBack(){
                        public boolean waitForExit(long millis){clock.now+=80;return true;}public int exitCode(){return backExit;}
                        public void destroy(){}public void close()throws Exception{if(backCloseFailure)throw new Exception("Synthetic Back cleanup");}
                    };
                }
            },permit,()->clock.now);
        }
        LiveRewardsInput.TapReceipt tap(LiveActionPermit permit){var receipt=input.tap(permit);successful=receipt.successfulElapsed();return receipt;}
    }
    @Test public void waitsFromSuccessfulCommandThenReturnsOnce()throws Exception{
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);
        assertTrue(tap.success());assertTrue(tap.invocationAttempted());assertEquals(1_125,tap.successfulElapsed());assertEquals(2,r.rebinds);
        int templates=r.templateCalls;r.forbidPageReads=true;
        var returned=r.input.returnAfterTap(p,tap);assertTrue(returned.reason(),returned.commandConfirmed());assertTrue(returned.backAttempted());
        assertEquals("BACK_COMMAND_CONFIRMED",returned.reason());assertNull(returned.source());assertTrue(returned.matchesPermit(p));
        assertEquals(1,r.taps);assertEquals(1,r.backs);assertEquals(tap.successfulElapsed()+5_000,r.backAt);assertTrue(returned.backGateElapsed()>=r.backAt);
        assertEquals(templates,r.templateCalls);assertEquals(0,r.visits);assertEquals(0,r.pages);assertEquals(2,r.rebinds);
        assertFalse(r.input.returnAfterTap(p,tap).backAttempted());assertEquals(1,r.backs);
    }
    @Test public void theApprovedReturnDoesNotInspectOrSpecialCaseAutomaticNavigation()throws Exception{
        var r=new Rig();r.child=false;var p=r.permit(LiveRewardsTypes.Kind.MORE);var tap=r.tap(p);var returned=r.input.returnAfterTap(p,tap);
        assertTrue(returned.commandConfirmed());assertTrue(returned.backAttempted());assertEquals(1,r.backs);assertEquals(tap.successfulElapsed()+5_000,r.backAt);
        assertEquals(0,r.visits);assertEquals(0,r.pages);assertNull(returned.source());
    }
    @Test public void childHtmlInsideOriginalTemplateStillRequiresOneBack()throws Exception{
        var r=new Rig();r.sameTemplateChild=true;var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);var returned=r.input.returnAfterTap(p,tap);
        assertTrue(returned.ready());assertTrue(returned.backAttempted());assertEquals(1,r.backs);
    }
    @Test public void signInCannotCreateAReturnEvenAfterDelay()throws Exception{
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.CHECK_IN);var tap=r.tap(p);assertTrue(tap.success());
        var returned=r.input.returnAfterTap(p,tap);assertFalse(returned.ready());assertEquals(0,r.pauses);assertEquals(0,r.visits);assertEquals(0,r.backs);
    }
    @Test public void distinctPerCardCapabilitiesContinueButSameCardCannotReplay()throws Exception{
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.DAILY);assertTrue(r.tap(p).success());assertFalse(r.input.tap(p).success());
        var next=r.permit(LiveRewardsTypes.Kind.MORE);assertTrue(r.tap(next).success());assertEquals(2,r.taps);
    }
    @Test public void beforeConsumeAndFinalSameCardRebindAreBothRequired()throws Exception{
        var first=new Rig();first.rebind=false;var p=first.permit(LiveRewardsTypes.Kind.DAILY);var receipt=first.tap(p);
        assertFalse(receipt.success());assertFalse(receipt.invocationAttempted());assertEquals(0,first.taps);assertFalse(p.tapValid(first.clock.now));
        var second=new Rig();second.secondRebindFails=true;var q=second.permit(LiveRewardsTypes.Kind.DAILY);var rejected=second.tap(q);
        assertFalse(rejected.success());assertFalse(rejected.invocationAttempted());assertTrue(q.tapAttempted());assertEquals(0,second.taps);
    }
    @Test public void sourceChangedAtFinalDispatchIsSpentAndNeverClicked()throws Exception{
        var r=new Rig();r.changeSourceAtFinal=true;var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);
        assertFalse(tap.success());assertFalse(tap.invocationAttempted());assertEquals(0,r.taps);assertTrue(p.tapAttempted());
    }
    @Test public void returnDoesNotInvokeFormerUnknownSourceOrPageGuards()throws Exception{
        for(boolean unknown:new boolean[]{true,false}){var r=new Rig();r.unknownVisit=unknown;r.blocked=!unknown;
            var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);r.forbidPageReads=true;
            assertTrue(r.input.returnAfterTap(p,tap).commandConfirmed());assertEquals(1,r.backs);assertEquals(0,r.visits);assertEquals(0,r.pages);}
    }
    @Test public void cancelDuringWaitStopsWithoutReadingPage()throws Exception{
        var r=new Rig();r.cancelDuringWait=true;var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);
        assertFalse(r.input.returnAfterTap(p,tap).ready());assertEquals(0,r.visits);assertEquals(0,r.backs);
    }
    @Test public void backwardsClockStopsWithoutInput()throws Exception{
        var r=new Rig();r.clockBackwards=true;var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);
        assertFalse(r.input.returnAfterTap(p,tap).ready());assertEquals(0,r.visits);assertEquals(0,r.backs);
    }
    @Test public void unknownTapAndCleanupAreReportedAsActualAttemptNotZero()throws Exception{
        for(boolean cleanup:new boolean[]{true,false}){var r=new Rig();r.closeFailure=cleanup;r.unknownTap=!cleanup;
            var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);assertFalse(tap.success());assertFalse(tap.knownClean());
            assertTrue(tap.invocationAttempted());assertEquals(1,r.taps);assertFalse(r.input.knownClean());assertFalse(r.input.returnAfterTap(p,tap).ready());assertEquals(0,r.backs);}
    }
    @Test public void unknownOrNonzeroBackNeverHasASecondAttempt()throws Exception{
        for(boolean unknown:new boolean[]{true,false}){var r=new Rig();r.unknownBack=unknown;r.backExit=unknown?0:1;
            var p=r.permit(LiveRewardsTypes.Kind.MORE);var tap=r.tap(p);var returned=r.input.returnAfterTap(p,tap);
            assertFalse(returned.ready());assertTrue(returned.backAttempted());assertEquals(1,r.backs);
            assertFalse(r.input.returnAfterTap(p,tap).backAttempted());assertEquals(1,r.backs);}
    }
    @Test public void nativeMarkedPointUsesCurrentCropOriginAndStrictExactObject()throws Exception{
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.DAILY);assertEquals(430,p.target().x());assertEquals(300,p.target().y());
        var actor=r.tapActor(p);var copied=new ReviewShizukuTapActor.TapTarget(430,300,523,1000,0);
        var result=actor.tap(copied,new ReviewShizukuTapActor.DispatchGate(){public boolean consume(ReviewShizukuTapActor.TapTarget target){fail("Copied coordinates cannot consume");return true;}
            public boolean stillValid(ReviewShizukuTapActor.TapTarget target){return true;}});
        assertFalse(result.invocationAttempted());assertEquals(0,r.taps);
    }
    @Test public void syntheticCapabilitiesCannotEnterProductionFactories()throws Exception{
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.DAILY);
        try{ReviewShizukuTapActor.forLiveRun(null,p);fail("Synthetic production Tap");}catch(SecurityException expected){}
        try{ReviewShizukuBackActor.forLiveRun(null,p);fail("Synthetic production Back");}catch(SecurityException expected){}
        assertTrue(ReadOnlyCheckpoint.blocksLiveInput());
    }
    @Test public void backCapabilityRejects4999AndAccepts5000()throws Exception{
        var early=new Rig();var p=early.permit(LiveRewardsTypes.Kind.DAILY);var tapped=early.tap(p);early.clock.now=tapped.successfulElapsed()+4_999;
        assertFalse(p.claimBackActor(early.clock.now));assertFalse(p.consumeBack(early.clock.now));assertFalse(p.backValid(early.clock.now));
        var due=new Rig();var q=due.permit(LiveRewardsTypes.Kind.MORE);var other=due.tap(q);due.clock.now=other.successfulElapsed()+5_000;
        assertTrue(q.claimBackActor(due.clock.now));assertTrue(q.consumeBack(due.clock.now));assertTrue(q.backValid(due.clock.now));
    }
    @Test public void dayChangeOrCleanupLossDuringTheDwellPreventsBack()throws Exception{
        for(boolean changedDay:new boolean[]{true,false}){
            var r=new Rig();r.dayChangesDuringWait=changedDay;r.cleanupLostDuringWait=!changedDay;
            var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);r.forbidPageReads=true;
            assertFalse(r.input.returnAfterTap(p,tap).commandConfirmed());assertEquals(0,r.backs);assertEquals(0,r.visits);
        }
    }
    @Test public void finalCancelAndDisplayRotationStillPreventDispatch()throws Exception{
        for(boolean rotation:new boolean[]{true,false}){
            var r=new Rig();r.backRotationChanged=rotation;r.cancelAtBackFinal=!rotation;
            var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);r.forbidPageReads=true;
            var returned=r.input.returnAfterTap(p,tap);assertFalse(returned.commandConfirmed());assertFalse(returned.backAttempted());assertEquals(0,r.backs);
        }
    }
    @Test public void interruptedThreadCannotBackEvenIfFiveSecondsAlreadyElapsed()throws Exception{
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.MORE);var tap=r.tap(p);r.clock.now+=5_000;
        Thread.currentThread().interrupt();
        try{assertFalse(r.input.returnAfterTap(p,tap).commandConfirmed());assertEquals(0,r.backs);}
        finally{Thread.interrupted();}
    }
    @Test public void backCleanupUnknownStopsWithoutRepeatingOrCallingPages()throws Exception{
        var r=new Rig();r.backCloseFailure=true;var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);r.forbidPageReads=true;
        var returned=r.input.returnAfterTap(p,tap);assertFalse(returned.commandConfirmed());assertTrue(returned.backAttempted());assertFalse(returned.knownClean());
        assertFalse(r.input.knownClean());assertEquals(1,r.backs);assertFalse(r.input.returnAfterTap(p,tap).backAttempted());assertEquals(1,r.backs);
    }
    @Test public void backCommandConfirmationDoesNotAssertThatThePageReturned()throws Exception{
        var r=new Rig();r.backStaysChild=true;var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);r.forbidPageReads=true;
        var returned=r.input.returnAfterTap(p,tap);assertTrue(returned.commandConfirmed());assertNull(returned.source());assertEquals(1,r.backs);
        assertEquals(0,r.visits);assertEquals(0,r.pages);
    }
    @Test public void mismatchedReceiptAndExpiredRunCannotBack()throws Exception{
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);var q=r.permit(LiveRewardsTypes.Kind.MORE);
        assertFalse(r.input.returnAfterTap(q,tap).commandConfirmed());assertEquals(0,r.backs);
        r.clock.now=r.authority.deadline();assertFalse(r.input.returnAfterTap(p,tap).commandConfirmed());assertEquals(0,r.backs);
    }
    @Test public void aDelayedCallerUsesTheSealedSuccessfulCommandTimeWithoutAnotherDwell()throws Exception{
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tap=r.tap(p);
        r.clock.now=tap.successfulElapsed()+20_000;r.forbidPageReads=true;
        var result=r.input.returnAfterTap(p,tap);assertTrue(result.commandConfirmed());assertEquals(0,r.pauses);
        assertEquals(tap.successfulElapsed()+20_000,r.backAt);assertEquals(tap.successfulElapsed(),p.successfulTapElapsed());
    }
    @Test public void oldProcessSpentObjectsAreNeitherResetNorReplaced()throws Exception{
        var tapField=ReviewShizukuTapActor.class.getDeclaredField("PROCESS_SPENT");tapField.setAccessible(true);
        var backField=ReviewShizukuBackActor.class.getDeclaredField("PROCESS_SPENT");backField.setAccessible(true);
        var tapBudget=(java.util.concurrent.atomic.AtomicBoolean)tapField.get(null);var backBudget=(java.util.concurrent.atomic.AtomicBoolean)backField.get(null);
        boolean priorTap=tapBudget.get(),priorBack=backBudget.get();
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.DAILY);var tapped=r.tap(p);assertTrue(r.input.returnAfterTap(p,tapped).ready());
        assertSame(tapBudget,tapField.get(null));assertSame(backBudget,backField.get(null));assertEquals(priorTap,tapBudget.get());assertEquals(priorBack,backBudget.get());
        assertTrue(ReadOnlyCheckpoint.blocksLiveInput());
    }
    private static LiveVisitGuard.Report guard(LiveVisitGuard.Hint hint,long started,long finished,int rotation){
        var ready=new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,0,0,0,0,0,0);
        var source=new NativeUiStructureProtocol.Report("a".repeat(32),started,started,finished,NativeUiStructureProtocol.Reason.OK,
                NativeUiStructureProtocol.Cleanup.CONFIRMED,10,0,7,0,0,0,523,1000,rotation,rotation,
                NativeUiStructureProtocol.Task.unknown(),NativeUiStructureProtocol.Task.unknown(),ready);
        return new LiveVisitGuard.Report(source,hint,1,0,0);
    }
    @Test public void metadataOriginalRequiresPositiveRewardsSemanticsAndChildNeedsPositiveContainer()throws Exception{
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.DAILY);var before=LiveNavigationSnapshotTest.visit(79,1_000,false,p.source());
        r.clock.now=1_020;var after=LiveNavigationSnapshotTest.visit(79,1_020,false,p.source());
        assertEquals(LiveRewardsInput.VisitPage.ORIGINAL_REWARDS,LiveRewardsInput.classifyVisit(p,before.source(),guard(LiveVisitGuard.Hint.REWARDS,1_001,1_010,0),after,r.clock.now));
        assertEquals(LiveRewardsInput.VisitPage.BOUND_CHILD,LiveRewardsInput.classifyVisit(p,before.source(),guard(LiveVisitGuard.Hint.SERP,1_001,1_010,0),after,r.clock.now));
        assertEquals(LiveRewardsInput.VisitPage.UNKNOWN,LiveRewardsInput.classifyVisit(p,before.source(),guard(LiveVisitGuard.Hint.WEB_CONTAINER,1_001,1_010,0),after,r.clock.now));
        assertEquals(LiveRewardsInput.VisitPage.UNKNOWN,LiveRewardsInput.classifyVisit(p,before.source(),guard(LiveVisitGuard.Hint.UNKNOWN,1_001,1_010,0),after,r.clock.now));
    }
    @Test public void browserContainerIsAllowedOnlyInsideExactSourceBrackets()throws Exception{
        var r=new Rig();var p=r.permit(LiveRewardsTypes.Kind.DAILY);var before=LiveNavigationSnapshotTest.visit(79,1_000,true,p.source());
        r.clock.now=1_020;var after=LiveNavigationSnapshotTest.visit(79,1_020,true,p.source());
        assertEquals(LiveRewardsInput.VisitPage.BOUND_CHILD,LiveRewardsInput.classifyVisit(p,before.source(),guard(LiveVisitGuard.Hint.WEB_CONTAINER,1_001,1_010,0),after,r.clock.now));
        assertEquals(LiveRewardsInput.VisitPage.UNKNOWN,LiveRewardsInput.classifyVisit(p,before.source(),guard(LiveVisitGuard.Hint.REWARDS,1_001,1_010,0),after,r.clock.now));
        assertEquals(LiveRewardsInput.VisitPage.UNKNOWN,LiveRewardsInput.classifyVisit(p,before.source(),guard(LiveVisitGuard.Hint.SERP,999,1_010,0),after,r.clock.now));
        assertEquals(LiveRewardsInput.VisitPage.UNKNOWN,LiveRewardsInput.classifyVisit(p,before.source(),guard(LiveVisitGuard.Hint.SERP,1_001,1_021,0),after,r.clock.now));
        assertEquals(LiveRewardsInput.VisitPage.UNKNOWN,LiveRewardsInput.classifyVisit(p,before.source(),guard(LiveVisitGuard.Hint.SERP,1_001,1_010,1),after,r.clock.now));
        var changed=LiveNavigationSnapshotTest.visit(80,1_020,true,LiveNavigationSnapshotTest.original(80,1_000));
        assertEquals(LiveRewardsInput.VisitPage.UNKNOWN,LiveRewardsInput.classifyVisit(p,before.source(),guard(LiveVisitGuard.Hint.SERP,1_001,1_010,0),changed,r.clock.now));
    }
}
