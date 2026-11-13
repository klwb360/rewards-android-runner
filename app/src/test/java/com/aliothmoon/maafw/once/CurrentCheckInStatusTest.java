package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
public final class CurrentCheckInStatusTest {
 @Test public void exactIndependentWindow(){assertEquals(java.time.Instant.parse("2026-11-11T16:00:00Z").toEpochMilli(),CurrentCheckInStatus.STARTS_UTC);assertEquals(java.time.Instant.parse("2026-11-11T17:00:00Z").toEpochMilli(),CurrentCheckInStatus.EXPIRES_UTC);}
 @Test public void newReadOnlyNamespace(){assertEquals("rewards-current-status-20261111-v17",CurrentCheckInStatus.DIRECTORY);}
 @Test public void fixedNinetySeconds(){assertEquals(90123,CurrentCheckInStatus.deadline(123,CurrentCheckInStatus.STARTS_UTC));}
 @Test(expected=SecurityException.class) public void noFinalNinetySecondAdmission(){CurrentCheckInStatus.deadline(1,CurrentCheckInStatus.EXPIRES_UTC-90000);}
 @Test(expected=SecurityException.class) public void noElapsedOverflow(){CurrentCheckInStatus.deadline(Long.MAX_VALUE,CurrentCheckInStatus.STARTS_UTC);}
 @Test public void exactDisplayOnly(){assertTrue(CurrentCheckInStatus.geometryMatches(1440,2560,0));assertFalse(CurrentCheckInStatus.geometryMatches(1440,2560,2));assertFalse(CurrentCheckInStatus.geometryMatches(2560,1440,0));}
 @Test public void onlyUniqueReliableMarkCompletes(){assertTrue(CurrentCheckInStatus.admit(1,1,1,true,false));}
 @Test public void missingOrRepeatedMarkHolds(){assertFalse(CurrentCheckInStatus.admit(1,1,0,true,false));assertFalse(CurrentCheckInStatus.admit(1,1,2,true,false));}
 @Test public void panelOrTitleMismatchHolds(){assertFalse(CurrentCheckInStatus.admit(0,1,1,true,false));assertFalse(CurrentCheckInStatus.admit(1,2,1,true,false));}
 @Test public void uncertainShapeOrConflictHolds(){assertFalse(CurrentCheckInStatus.admit(1,1,1,false,false));assertFalse(CurrentCheckInStatus.admit(1,1,1,true,true));}
 @Test public void checkGlyphCorroborates(){assertFalse(CurrentCheckInStatus.localConflict("√",new Box(690,1800,750,1850),.99));assertFalse(CurrentCheckInStatus.localConflict("✓",new Box(690,1800,750,1850),.99));}
 @Test public void unknownLowConfidenceIsNotPromoted(){assertTrue(CurrentCheckInStatus.localConflict("✓",new Box(690,1800,750,1850),.5));}
 @Test public void localizedNumberAndLockHold(){assertTrue(CurrentCheckInStatus.localConflict("10",new Box(680,1800,760,1870),.99));assertTrue(CurrentCheckInStatus.localConflict("明天",new Box(680,1800,760,1870),.99));}
 @Test public void wideMergedDigitsRemainAuxiliaryOnly(){assertFalse(CurrentCheckInStatus.localConflict("101520251030",new Box(150,1800,1000,1850),.45));assertTrue(CurrentCheckInStatus.localConflict("101520251030",new Box(680,1800,760,1850),.99));}
 @Test public void invalidObservationHoldsWithoutText(){var r=CurrentCheckInStatus.observe(null,1440,2560,java.util.List.of(),()->false);assertEquals(CurrentCheckInStatus.State.HOLD,r.state());assertTrue(r.summary().contains("actionsThisRun=0"));}
 private static FrameStructureEvidence.Line row(String text,int x,int y,int w,int h,double score){return new FrameStructureEvidence.Line(text,x,y,w,h,score);}
 private static java.util.ArrayList<FrameStructureEvidence.Line> modal(){return new java.util.ArrayList<>(java.util.List.of(
  row("成功签入!",520,1300,400,70,.99),
  row("你今天已获得 10 积分!",300,1450,840,55,.98),
  row("连续签到将赢得更多 Rewards 积分。",180,1520,1080,55,.96),
  row("明天提醒我",490,1780,460,65,.97),row("不，谢谢",550,2020,340,65,.98)));}
 private static CurrentCheckInStatus.Result modalResult(java.util.List<FrameStructureEvidence.Line> rows){return CurrentCheckInStatus.successModal(rows,1440,2560,()->false);}
 @Test public void exactNativeModalAnchorsComplete(){var r=modalResult(modal());assertEquals(CurrentCheckInStatus.State.CURRENT_CHECKIN_SUCCESS_MODAL,r.state());assertTrue(r.summary().contains("points=NOT_EVALUATED"));assertTrue(r.summary().contains("reminderOptIn=NOT_SELECTED"));}
 @Test public void nativeModalHasNoRawTextOrAmount(){var r=modalResult(modal());for(var row:modal())assertFalse(r.summary().contains(row.text()));assertFalse(r.summary().contains("10积分"));}
 @Test public void finiteWhitespaceAndPunctuationNormalize(){assertTrue(CheckInSuccessModal.successTitle(CheckInSuccessModal.modalText(" 成功\u3000签入 ！ ")));assertTrue(CheckInSuccessModal.successTitle("成功签到"));assertFalse(CheckInSuccessModal.successTitle("成功签入!!!"));}
 @Test public void negativeOrSubstringTitleNeverMatches(){for(String s:new String[]{"未成功签入!","签到失败","成功签入但未完成","关于成功签入","成功签入?"})assertFalse(CheckInSuccessModal.successTitle(s));}
 @Test public void unknownTitleDoesNotBecomeSuccess(){var r=modal();r.set(0,row("未成功签入!",520,1300,400,70,.99));assertNull(modalResult(r));}
 @Test public void criticalTitleLowHolds(){var r=modal();r.set(0,row("成功签入!",520,1300,400,70,.84));assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void criticalBodyLowHolds(){var r=modal();r.set(2,row("连续签到将赢得更多 Rewards 积分。",180,1520,1080,55,.84));assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void criticalButtonLowHolds(){var r=modal();r.set(3,row("明天提醒我",490,1780,460,65,.84));assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void lowBackgroundDoesNotInvalidateModal(){var r=modal();r.add(row("irrelevant background",80,100,500,40,.1));assertEquals(CurrentCheckInStatus.State.CURRENT_CHECKIN_SUCCESS_MODAL,modalResult(r).state());}
 @Test public void titleAloneCannotConfirmCompletion(){var r=modal();r.remove(2);assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void missingDeclineCannotConfirmModal(){var r=modal();r.remove(4);assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void duplicateSuccessTitleIsAmbiguous(){var r=modal();r.add(row("成功签到",520,1100,400,70,.99));assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void duplicateReminderIsAmbiguous(){var r=modal();r.add(row("明天提醒我",490,2300,460,65,.99));assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void buttonAboveTitleIsNotModalLayout(){var r=modal();r.set(3,row("明天提醒我",490,900,460,65,.99));assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void localNegativeTextHolds(){var r=modal();r.add(row("签到失败",500,1650,400,50,.99));assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void reminderTomorrowIsNotFailure(){assertFalse(CheckInSuccessModal.negativeModalText("明天提醒我"));assertEquals(CurrentCheckInStatus.State.CURRENT_CHECKIN_SUCCESS_MODAL,modalResult(modal()).state());}
 @Test public void rewardAmountIsNotARequiredTen(){var r=modal();r.set(1,row("你今天已获得 25 积分!",300,1450,840,55,.98));assertEquals(CurrentCheckInStatus.State.CURRENT_CHECKIN_SUCCESS_MODAL,modalResult(r).state());}
 @Test public void unrelatedPointsDoNotReplaceCheckinContext(){var r=modal();r.set(2,row("搜索以赚取更多 Rewards 积分。",180,1520,1080,55,.99));assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void invalidNativeBoxCannotMatch(){var r=modal();r.set(1,row("你今天已获得 10 积分!",-1,1450,840,55,.98));assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
 @Test public void cancellationCannotPublishSuccess(){assertEquals(CurrentCheckInStatus.State.HOLD,CurrentCheckInStatus.successModal(modal(),1440,2560,()->true).state());}
 @Test public void modalPrecedesMissingPanelBoundary(){var r=CurrentCheckInStatus.observe(new byte[1440*2560],1440,2560,modal(),()->false);assertEquals(CurrentCheckInStatus.State.CURRENT_CHECKIN_SUCCESS_MODAL,r.state());}
 @Test public void bodyMaySpanThreeNativeLines(){var r=modal();r.set(2,row("连续签到将赢得更多",350,1515,740,40,.96));r.add(3,row("Rewards 积分。",450,1575,540,40,.96));assertEquals(CurrentCheckInStatus.State.CURRENT_CHECKIN_SUCCESS_MODAL,modalResult(r).state());}
 @Test public void contextCannotBridgeLargeUnobservedGap(){var r=modal();r.set(2,row("连续签到将赢得更多 Rewards 积分。",180,1670,1080,55,.96));assertEquals(CurrentCheckInStatus.State.HOLD,modalResult(r).state());}
}
