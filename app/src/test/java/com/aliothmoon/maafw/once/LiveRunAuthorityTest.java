package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveRunAuthorityTest {
    @Test public void syntheticEvidenceCannotEnableProductionAndCropPointUsesScreenOrigin()throws Exception{
        var f=new LiveCoreFixture();var p=f.permit(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A"));
        assertFalse(p.live());assertEquals(340,p.target().x());assertEquals(225,p.target().y());
        assertSame(p.scope().owner(),p.owner());assertSame(p.before(),p.beforeObservation());
    }
    @Test public void pendingRecordPreventsSameDayReplayAfterRestart()throws Exception{
        var f=new LiveCoreFixture();var t=LiveCoreFixture.task(LiveRewardsTypes.Kind.MORE,1,"A");
        f.ledger.begin(t);assertTrue(f.ledger.attempted(t));
        var next=f.ledger.syntheticNextRun("00000000-0000-4000-8000-000000000002");assertTrue(next.attempted(t));
        try{next.begin(t);fail();}catch(IOException expected){assertNotNull(expected.getMessage());}
    }
    @Test public void taskIdentityDoesNotUseIndexButDoesUseRegionDescriptionAndPoints(){
        var a=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A");
        var moved=new LiveRewardsTypes.Task(a.kind(),a.descriptorHash(),8,a.titleHash(),a.points(),true,false,-1);
        assertEquals(a.key(),moved.key());
        assertNotEquals(a.key(),LiveCoreFixture.task(LiveRewardsTypes.Kind.MORE,1,"A").key());
        assertNotEquals(a.key(),LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"B").key());
        assertNotEquals(a.key(),new LiveRewardsTypes.Task(a.kind(),a.descriptorHash(),1,a.titleHash(),5,true,false,-1).key());
    }
    @Test public void checkInIdentityCanRefineNativeNumberWithoutReopeningDailyAttempt(){
        var a=LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN,0,"SIGN");
        var initial=new LiveRewardsTypes.Task(a.kind(),a.descriptorHash(),0,"",0,false,false,-1);
        assertEquals(a.key(),initial.key());
    }
    @Test public void prematurelyConsumedTapCannotBecomeDurableAfterActorClaim()throws Exception{
        var f=new LiveCoreFixture();var p=f.permit(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A"));
        assertFalse(p.consumeTap(f.clock.now));assertTrue(p.claimTapActor(f.clock.now));
        assertFalse(p.consumeTap(f.clock.now));assertFalse(p.tapValid(f.clock.now));
        try{p.tapSucceeded(f.clock.now);fail();}catch(SecurityException expected){assertNotNull(expected.getMessage());}
    }
    @Test public void successfulTapTimeCannotBeOverwrittenAndReturnCannotBeEarly()throws Exception{
        var f=new LiveCoreFixture();var p=f.permit(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A"));
        assertTrue(p.claimTapActor(f.clock.now));assertTrue(p.consumeTap(f.clock.now));assertTrue(p.tapValid(f.clock.now));
        f.clock.now=1250;p.tapSucceeded(f.clock.now);assertFalse(p.tapValid(f.clock.now));
        try{p.tapSucceeded(1300);fail();}catch(SecurityException expected){assertEquals(1250,p.successfulTapElapsed());}
        f.clock.now=6249;assertFalse(p.claimBackActor(f.clock.now));
        f.clock.now=6250;assertFalse(p.claimBackActor(f.clock.now));assertFalse(p.consumeBack(f.clock.now));assertFalse(p.backValid(f.clock.now));
    }
    @Test public void dueReturnAllowsOneDurableBackEvenAfterTheBeforeFrameExpires()throws Exception{
        var f=new LiveCoreFixture();var p=f.permit(LiveCoreFixture.task(LiveRewardsTypes.Kind.MORE,1,"A"));
        assertTrue(p.claimTapActor(f.clock.now));assertTrue(p.consumeTap(f.clock.now));p.tapSucceeded(f.clock.now);
        f.clock.now=20_000;assertFalse(p.before().fresh(f.clock.now));assertTrue(p.current(f.clock.now));
        assertTrue(p.claimBackActor(f.clock.now));assertTrue(p.consumeBack(f.clock.now));assertTrue(p.backValid(f.clock.now));
        assertFalse(p.claimBackActor(f.clock.now));assertFalse(p.consumeBack(f.clock.now));
    }
    @Test public void signInCanNeverIssueOrConsumeBack()throws Exception{
        var f=new LiveCoreFixture();var p=f.permit(LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN,0,"SIGN"));
        assertTrue(p.claimTapActor(f.clock.now));assertTrue(p.consumeTap(f.clock.now));p.tapSucceeded(f.clock.now);
        f.clock.now+=5000;assertFalse(p.claimBackActor(f.clock.now));assertFalse(p.consumeBack(f.clock.now));assertFalse(p.backValid(f.clock.now));
    }
    @Test public void preflightCropNeverCreatesAnActionPermit()throws Exception{
        var f=new LiveCoreFixture();f.attach();var t=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A");
        var s=f.authority.preflightScope(t);var o=f.seal(s,LiveRewardsTypes.Phase.BEFORE,t,0,100);f.ledger.begin(t);
        try{s.actionPermit(o,f.ledger);fail();}catch(SecurityException expected){assertNotNull(expected.getMessage());}
    }
    @Test public void prematureUiConsumeCannotBeUpgradedToAValidDispatch()throws Exception{
        var f=new LiveCoreFixture();f.attach();var s=f.authority.cardScope(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A"));
        var ui=s.claimUi(new Object(),f.clock.now);assertFalse(ui.consume(f.clock.now));assertTrue(ui.claimReader(f.clock.now));
        assertFalse(ui.consume(f.clock.now));assertFalse(ui.dispatchValid(f.clock.now));
    }
    @Test public void prematureCropDispatchCannotBeUpgradedAfterBurn()throws Exception{
        var f=new LiveCoreFixture();f.attach();var s=f.authority.cardScope(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A"));
        var crop=s.claimCrop(f.ui(s),LiveRewardsTypes.Phase.BEFORE,f.clock.now);
        assertTrue(crop.bindRequest(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(20,100,420,500),523,1000,0,f.clock.now));
        assertFalse(crop.claimDispatch(f.clock.now));assertTrue(crop.consume(f.clock.now));assertFalse(crop.claimDispatch(f.clock.now));assertFalse(crop.dispatchValid(f.clock.now));
    }
    @Test public void dayChangeCancellationAndDeadlineRevokeEveryOutstandingPermit()throws Exception{
        var f=new LiveCoreFixture();var p=f.permit(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A"));
        f.clock.day="2026-11-17";assertFalse(f.authority.current());assertFalse(p.current(f.clock.now));
        var other=new LiveCoreFixture();var q=other.permit(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"B"));
        other.authority.cancel();assertFalse(q.current(other.clock.now));
        var expiry=new LiveCoreFixture();expiry.attach();var s=expiry.authority.cardScope(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"C"));
        expiry.clock.now+=180_000;assertFalse(s.current(expiry.clock.now));
    }
    @Test public void onlyOneBeforeAndTwoAfterSlotsAreAvailable()throws Exception{
        var f=new LiveCoreFixture();f.attach();var t=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A");var s=f.authority.cardScope(t);
        f.seal(s,LiveRewardsTypes.Phase.BEFORE,t,0,100);
        try{s.claimCrop(f.ui(s),LiveRewardsTypes.Phase.BEFORE,f.clock.now);fail();}catch(SecurityException expected){assertNotNull(expected.getMessage());}
        f.seal(s,LiveRewardsTypes.Phase.AFTER_1,t,0,100);f.seal(s,LiveRewardsTypes.Phase.AFTER_2,t,0,100);
    }
    @Test public void discardedCleanCropCanOnlyBecomeUnreadableAndNeverSealPixels()throws Exception{
        var f=new LiveCoreFixture();f.attach();var t=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A");var s=f.authority.cardScope(t);
        var crop=s.claimCrop(f.ui(s),LiveRewardsTypes.Phase.BEFORE,f.clock.now);
        assertTrue(crop.bindRequest(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(20,100,420,500),523,1000,0,f.clock.now));
        assertTrue(crop.consume(f.clock.now));
        assertTrue(crop.claimDispatch(f.clock.now));s.discardCrop(crop,true);
        var o=s.unreadable(LiveRewardsTypes.Phase.BEFORE,t,f.source(),f.clock.now,"SEMANTIC_HOLD");
        assertFalse(o.qualified());assertNull(o.data().localTarget());assertEquals("",o.data().captureHash());
    }
    @Test public void malformedJournalInputsFailBeforeAnyFilesystemOperation()throws Exception{
        try{new LiveRewardsLedger(Paths.get("relative"),LiveCoreFixture.DAY,LiveCoreFixture.RUN);fail();}
        catch(IOException expected){assertNotNull(expected.getMessage());}
        var f=new LiveCoreFixture();try{f.ledger.recordRead("../RAW_TITLE");fail();}catch(IOException expected){assertNotNull(expected.getMessage());}
    }
    @Test public void sealedObservationMustMatchTheExactBoundCaptureOrigin()throws Exception{
        var f=new LiveCoreFixture();f.attach();var t=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A");var s=f.authority.cardScope(t);
        var crop=s.claimCrop(f.ui(s),LiveRewardsTypes.Phase.BEFORE,f.clock.now);
        assertTrue(crop.bindRequest(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(20,100,420,500),523,1000,0,f.clock.now));
        assertTrue(crop.consume(f.clock.now));assertTrue(crop.claimDispatch(f.clock.now));s.finishCrop(crop,true,true,f.clock.now);
        var data=new LiveRewardsTypes.ObservationData(t,f.source(),f.clock.now,
                new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(30,100,430,500),400,400,
                new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(280,100,360,150),.95,
                LiveRewardsTypes.hash("SYNTHETIC_CAPTURE"),true,true,true,0,100);
        try{s.sealObservation(crop,data);fail();}catch(SecurityException expected){assertNotNull(expected.getMessage());}
        var forged=LiveRewardsTypes.Observation.sealed(s,LiveRewardsTypes.Phase.BEFORE,data,"SYNTHETIC");f.ledger.begin(t);
        try{s.actionPermit(forged,f.ledger);fail();}catch(SecurityException expected){assertNotNull(expected.getMessage());}
    }
    @Test public void touchingLocalTargetAndWholeDisplayCropAreRejected()throws Exception{
        var f=new LiveCoreFixture();f.attach();var t=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A");var s=f.authority.cardScope(t);
        var crop=s.claimCrop(f.ui(s),LiveRewardsTypes.Phase.BEFORE,f.clock.now);
        assertFalse(crop.bindRequest(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(0,0,523,1000),523,1000,0,f.clock.now));
        assertTrue(crop.bindRequest(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(20,100,420,500),523,1000,0,f.clock.now));
        assertTrue(crop.consume(f.clock.now));assertTrue(crop.claimDispatch(f.clock.now));s.finishCrop(crop,true,true,f.clock.now);
        var data=new LiveRewardsTypes.ObservationData(t,f.source(),f.clock.now,
                new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(20,100,420,500),400,400,
                new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(0,100,360,150),.95,
                LiveRewardsTypes.hash("SYNTHETIC_CAPTURE"),true,true,true,0,100);
        try{s.sealObservation(crop,data);fail();}catch(SecurityException expected){assertNotNull(expected.getMessage());}
    }
    @Test public void expiredAfterReadCanBeRetiredCleanlyWithoutBecomingAnInputGrant()throws Exception{
        var f=new LiveCoreFixture();f.attach();var t=LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A");var s=f.authority.cardScope(t);
        s.startAfterWindow(f.clock.now);var ui=s.claimUi(new Object(),f.clock.now);
        assertEquals(f.clock.now+5000,ui.deadline());assertTrue(ui.claimReader(f.clock.now));assertTrue(ui.consume(f.clock.now));
        f.clock.now+=5000;assertFalse(ui.current(f.clock.now));s.discardUi(ui,true);
        var o=s.unreadable(LiveRewardsTypes.Phase.AFTER_1,t,f.source(),f.clock.now,"AFTER_WINDOW_EXPIRED");
        assertFalse(o.qualified());assertTrue(f.authority.current());assertEquals(0,s.remainingAfterBudget(f.clock.now));
    }
    @Test public void perRegionScopeLimitCannotBeBypassedWithNewDescriptorsAtSameOrdinal()throws Exception{
        var f=new LiveCoreFixture();f.attach();
        for(int i=0;i<8;i++)f.authority.cardScope(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"A"+i));
        try{f.authority.cardScope(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"NINTH"));fail();}
        catch(SecurityException expected){assertNotNull(expected.getMessage());}
    }
}
