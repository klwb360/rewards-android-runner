package com.aliothmoon.maafw.once;
import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.RewardCardFixture.*;
public class NativeRewardCardScopeRunTest {
 @Test public void absoluteWindowMatchesDisclosedUtc(){assertEquals(Instant.parse("2026-11-11T17:00:00Z").toEpochMilli(),NativeRewardCardScope.STARTS_UTC);assertEquals(Instant.parse("2026-11-11T18:00:00Z").toEpochMilli(),NativeRewardCardScope.EXPIRES_UTC);}
 @Test public void windowIsHalfOpen(){assertFalse(NativeRewardCardScope.current(NativeRewardCardScope.STARTS_UTC-1));assertTrue(NativeRewardCardScope.current(NativeRewardCardScope.STARTS_UTC));assertTrue(NativeRewardCardScope.current(NativeRewardCardScope.EXPIRES_UTC-1));assertFalse(NativeRewardCardScope.current(NativeRewardCardScope.EXPIRES_UTC));}
 @Test public void fullNinetySecondAdmissionMargin(){long e=NativeRewardCardScope.EXPIRES_UTC;assertTrue(NativeRewardCardScope.admissionCurrent(e-90001));assertFalse(NativeRewardCardScope.admissionCurrent(e-90000));}
 @Test public void deadlineUsesMonotonicBudget(){assertEquals(91000,NativeRewardCardScope.deadline(1000,NativeRewardCardScope.STARTS_UTC));}
 @Test public void invalidDeadlineRejected(){for(long elapsed:new long[]{-1,Long.MAX_VALUE-89999})try{NativeRewardCardScope.deadline(elapsed,NativeRewardCardScope.STARTS_UTC);fail();}catch(SecurityException expected){}}
 @Test public void expiredAdmissionRejected(){try{NativeRewardCardScope.deadline(1,NativeRewardCardScope.EXPIRES_UTC);fail();}catch(SecurityException expected){}}
 @Test public void exactlyThreeNewSlots(){assertTrue(NativeRewardCardScope.slotAllowed("BEFORE"));assertTrue(NativeRewardCardScope.slotAllowed("AFTER"));assertTrue(NativeRewardCardScope.slotAllowed("TAP"));for(String x:new String[]{null,"","BASELINE","CURRENT_STATUS","BEFORE2","../TAP"})assertFalse(NativeRewardCardScope.slotAllowed(x));}
 @Test public void independentNamespaceNeverReusesCheckin(){assertEquals("rewards-more-quote-20261111-v18",NativeRewardCardScope.DIRECTORY);assertNotEquals(NativeCheckInScope.DIRECTORY,NativeRewardCardScope.DIRECTORY);assertNotEquals(CurrentCheckInStatus.DIRECTORY,NativeRewardCardScope.DIRECTORY);}
 private NativeRewardCardSession.Observation observation(boolean complete,java.util.List<FrameStructureEvidence.Line> rows){
  NativeRewardCardSession.Authority a=new NativeRewardCardSession.Authority(){
   public long elapsedRealtime(){return 1000;}public boolean scopeCurrent(){return true;}
   public boolean sourceStillMatches(int w,int h,int r){return w==W&&h==H&&r==0;}
   public void consumeOneTap(ReviewShizukuTapActor.TapTarget t){throw new AssertionError("No input in selection test");}
  };
  var actor=new ReviewShizukuTapActor(()->{throw new AssertionError("No actor in selection test");});
  var s=new NativeRewardCardSession("synthetic-selection-only",100000,a,actor);
  return s.startCapture(NativeRewardCardSession.Phase.BEFORE,W,H,0).prepare(pixels(complete,true),W,H,rows,()->false);
 }
 @Test public void dailyCandidatesCannotEnterMoreOnlyConfiguration(){var r=rows(false,true);r.set(2,line("Zulu activity",55,270,265,25));r.set(4,line("Alpha activity",55,478,265,25));var o=observation(false,r);assertEquals(2,o.incompleteCards().size());assertNull(NativeRewardCardRun.select(o));}
 @Test public void dailyTenPointCandidateIsNotTheConfiguredQuote(){var r=rows(false,true);r.set(3,line("+20",414,331,51,26));var o=observation(false,r);assertNull(NativeRewardCardRun.select(o));}
 @Test public void completedCardIsNotSelected(){var o=observation(true,rows(true,true));assertNull(NativeRewardCardRun.select(o));}
 @Test public void noTenPointCardMeansNoSelection(){var r=rows(false,true);r.set(3,line("+20",414,331,51,26));r.set(5,line("+20",414,539,51,26));assertNull(NativeRewardCardRun.select(observation(false,r)));}
 @Test public void absentObservationMeansNoSelection(){assertNull(NativeRewardCardRun.select(null));}
 @Test public void explicitTransactionOrInstallationTaskCannotBeSelected(){
  for(String title:new String[]{"购买礼品", "兑换奖励", "下载应用", "充值账户", "安装应用", "Purchase item", "Buy product", "Redeem reward", "Install software", "Download software", "Top up balance"}){
   var r=rows(false,true);r.set(2,line(title,55,270,265,25));var o=observation(false,r);
   assertEquals(title,LiveRewardCardObservation.Status.HOLD,o.status());assertNull(NativeRewardCardRun.select(o));
  }
 }

 private NativeRewardCardSession.Observation moreObservation(boolean complete,java.util.List<FrameStructureEvidence.Line> rows){
  NativeRewardCardSession.Authority a=new NativeRewardCardSession.Authority(){
   public long elapsedRealtime(){return 1000;}public boolean scopeCurrent(){return true;}
   public boolean sourceStillMatches(int w,int h,int r){return w==MoreCardFixture.W&&h==MoreCardFixture.H&&r==0;}
   public void consumeOneTap(ReviewShizukuTapActor.TapTarget t){throw new AssertionError("No input in selection test");}
  };
  var actor=new ReviewShizukuTapActor(()->{throw new AssertionError("No actor in selection test");});
  var s=new NativeRewardCardSession("synthetic-more-selection-only",100000,a,actor,LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay());
  return s.startCapture(NativeRewardCardSession.Phase.BEFORE,MoreCardFixture.W,MoreCardFixture.H,0)
   .prepare(MoreCardFixture.pixels(complete),MoreCardFixture.W,MoreCardFixture.H,rows,()->false);
 }
 @Test public void configuredFivePointQuoteSelectsExactNativeCard(){
  var o=moreObservation(false,MoreCardFixture.rows(false));assertEquals(LiveRewardCardObservation.Status.OBSERVED,o.status());
  assertEquals(1,o.cards().size());assertSame(o.cards().get(0),NativeRewardCardRun.select(o));
 }
 @Test public void configuredCompleteQuoteIsBoundForSessionSkipNotAnAction(){
  var o=moreObservation(true,MoreCardFixture.rows(true));assertEquals(LiveRewardCardObservation.Status.OBSERVED,o.status());
  assertTrue(o.cards().get(0).complete());assertSame(o.cards().get(0),NativeRewardCardRun.select(o));
 }
 @Test public void wrongQuotePointsCannotBeSelected(){
  var r=MoreCardFixture.rows(false);for(int i=0;i<r.size();i++){var q=r.get(i);if(q.text().equals("+5")&&q.y()>600)r.set(i,new FrameStructureEvidence.Line("+10",q.x(),q.y(),q.width(),q.height(),q.score()));}
  assertNull(NativeRewardCardRun.select(moreObservation(false,r)));
 }
}