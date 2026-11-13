package com.aliothmoon.maafw.once;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
/** Only v33 boxes/scores are observed scalars; all words/pixels/tick glyph are manual synthetic literals. */
public class IndependentCurrentCardTest {
 static final int W=1440,H=2560;
 static void fill(byte[] p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
 static void tick(byte[] p,int l,int t){V26CompactTitleFamilyTest.tick(p,l,t);}
 static byte[] pixels(boolean tick){byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
  fill(p,114,661,1326,1069,255);fill(p,1022,793,1278,937,80);
  fill(p,114,1109,1326,1437,255);fill(p,1022,1205,1278,1349,80);
  fill(p,114,1477,1326,1653,255);tick(p,1206,1533);
  fill(p,114,1693,1326,1869,255);if(tick)tick(p,1206,1749);
  fill(p,114,1909,1326,2085,255);tick(p,1206,1965);return p;}
 static FrameStructureEvidence.Line r(String text,double q,int l,int t,int rr,int b){return new FrameStructureEvidence.Line(text,l,t,rr-l,b-t,q);}
 static List<FrameStructureEvidence.Line> rows(){return new ArrayList<>(List.of(
  r("更多活动",.999907,55,440,381,536),r("15/25",.999988,1045,450,1257,536),
  r("SYNTHETIC_UNKNOWN_NOT_RECOVERED",.839853,193,784,288,944),
  r("Do you know the",.999974,360,712,804,784),r("answer?",.999921,357,792,592,864),
  r("Test your knowledge on",.999785,360,877,913,941),r("these topics",.999951,357,949,651,1013),r("+5",.997889,1090,826,1207,898),
  r("Unmodeled card one",.999917,362,1162,812,1226),r("Other body",.999979,360,1245,881,1314),r("+5",.998259,1093,1234,1207,1306),
  r("Unmodeled short card",.999972,158,1533,712,1597),r("5",.999944,1148,1530,1199,1594),
  r("Do you know the answer?",.973144,153,1741,825,1813),r("5",.999901,1146,1744,1201,1810),
  r("Unmodeled short last",.987061,156,1960,592,2032),r("5",.999928,1146,1962,1201,2026)));
 }
 static ReadOnlyTwoStageRun.Observation observe(byte[] p,List<FrameStructureEvidence.Line> rows){return ReadOnlyTwoStageRun.Capture.moreCurrentCards(new Object(),"synthetic-v33-scalars",W,H,0,1000,()->false).prepare(p,W,H,rows,()->false);}
 static RewardRegionLocator.CurrentReadCard card(ReadOnlyTwoStageRun.Observation o,int i){return o.currentCards().cards().stream().filter(c->c.frameCardIndex()==i).findFirst().orElseThrow();}
 static void replace(List<FrameStructureEvidence.Line> rows,int y,String text,double score){for(int i=0;i<rows.size();i++){var r=rows.get(i);if(r.y()==y)rows.set(i,new FrameStructureEvidence.Line(text,r.x(),r.y(),r.width(),r.height(),score));}}
 @Test public void nonconsecutivePendingBAndLeftAlignedAnonymousCompleteAreIndependentFacts(){var o=observe(pixels(true),rows());assertEquals(RewardRegionLocator.CurrentBatchStatus.PROCESSED,o.currentCards().status());assertEquals(5,o.currentCards().cards().size());
  assertEquals(RewardRegionLocator.CurrentReadStatus.PENDING,card(o,0).status());assertEquals(RewardRegionLocator.CurrentReadIdentity.EXACT_B,card(o,0).identity());
  assertEquals(RewardRegionLocator.CurrentReadStatus.COMPLETE,card(o,3).status());assertEquals(RewardRegionLocator.CurrentReadIdentity.UNNAMED,card(o,3).identity());assertEquals(5,card(o,3).points());assertEquals(new RewardRegionLocator.Box(153,1741,825,1813),card(o,3).titleBox());
  for(int i:new int[]{1,2,4})assertEquals(RewardRegionLocator.CurrentReadStatus.UNMODELED,card(o,i).status());
  assertEquals(LiveRewardCardObservation.Status.HOLD,o.frame.status());assertEquals(LiveRewardCardObservation.AdapterGate.FAMILY_RESULT,o.frame.diagnostic().adapterFirstGate());}
 @Test public void noTickLowTitleLowNumberWrongNumberOrRiskOnlyHoldsThatCard(){for(int v=0;v<6;v++){var r=rows();byte[] p=pixels(v!=0);
  if(v==1)replace(r,1741,"Do you know the answer?",.2);if(v==2)replace(r,1744,"5",.2);if(v==3)replace(r,1744,"10",.999901);
  if(v==4)r.add(r("purchase required",.99,153,1820,825,1850));if(v==5)r.add(r("Unexpected low body",.2,153,1820,825,1850));
  var o=observe(p,r);assertEquals("case="+v,RewardRegionLocator.CurrentReadStatus.HOLD,card(o,3).status());assertEquals(RewardRegionLocator.CurrentReadStatus.PENDING,card(o,0).status());}}
 @Test public void extraTitleCrossCardOverlapExtraNumberOrConflictingBodyCannotBecomeCompletion(){for(int v=0;v<5;v++){var r=rows();
  if(v==0)r.add(r("Do you know the answer?",.99,153,1820,825,1850));
  if(v==1)r.add(r("cross boundary",.99,153,1850,825,1880));
  if(v==2)r.add(r("overlap",.99,200,1760,500,1790));
  if(v==3)r.add(r("7",.99,1100,1820,1150,1850));if(v==4)r.add(r("Conflicting body",.99,153,1820,825,1850));
  var o=observe(pixels(true),r);assertEquals("case="+v,RewardRegionLocator.CurrentReadStatus.HOLD,card(o,3).status());assertEquals(RewardRegionLocator.CurrentReadStatus.PENDING,card(o,0).status());}}
 @Test public void duplicateTickOrBadgeConflictCannotBeCompletion(){for(boolean badge:new boolean[]{false,true}){byte[] p=pixels(true);if(badge)fill(p,925,1780,1085,1860,80);else tick(p,1100,1799);var o=observe(p,rows());assertEquals("badge="+badge,RewardRegionLocator.CurrentReadStatus.HOLD,card(o,3).status());}}
 @Test public void highUniqueMoreContextIsStillRequired(){for(int v=0;v<3;v++){var r=rows();if(v==0)replace(r,440,"更多活动",.1);if(v==1)replace(r,450,"15/0",.99);if(v==2)r.add(r("更多活动",.1,20,2150,300,2190));var o=observe(pixels(true),r);assertEquals(RewardRegionLocator.CurrentBatchStatus.HOLD,o.currentCards().status());assertTrue(o.currentCards().cards().isEmpty());}}
 @Test public void dedicatedTypesAreImmutableAndContainNoTextOrBusinessTypes(){var o=observe(pixels(true),rows());String text=o.currentCards().summary();assertFalse(text.contains("Do you know"));assertFalse(text.contains("Test your knowledge"));assertFalse(text.contains("SYNTHETIC_UNKNOWN"));assertTrue(text.length()<3500);assertTrue(text.contains("completeFamily=false businessEligible=false"));assertThrows(UnsupportedOperationException.class,()->o.currentCards().cards().clear());
  for(var f:RewardRegionLocator.CurrentReadCard.class.getDeclaredFields()){assertFalse(f.getType()==String.class);assertFalse(f.getType()==RewardRegionLocator.Row.class);assertFalse(f.getType()==RewardRegionLocator.Candidate.class);assertFalse(f.getType()==RewardRegionLocator.Card.class);}
  var legacy=ReadOnlyTwoStageRun.Capture.moreCurrentStatus(new Object(),"strict-legacy",W,H,0,1000,()->false).prepare(pixels(true),W,H,rows(),()->false);assertNull(legacy.currentCards());assertEquals(LiveRewardCardObservation.Status.HOLD,legacy.frame.status());}
 @Test public void extraKnownTitleFragmentsInPendingUAreNotSilentlyIgnored(){for(String text:List.of("Do you know the","answer?","Do you know the answer?")){var r=rows();r.add(r(text,.1,150,990,280,1020));var o=observe(pixels(true),r);assertEquals(RewardRegionLocator.CurrentReadStatus.HOLD,card(o,0).status());assertEquals(RewardRegionLocator.CurrentReadStatus.COMPLETE,card(o,3).status());}}
 @Test public void outsideKnownSingleOrLowSplitTitleStaysExplicitlyUnassociated(){for(boolean split:new boolean[]{false,true}){var r=rows();if(split){r.add(r("Do you know the",.1,400,2180,800,2220));r.add(r("answer?",.1,400,2230,600,2270));}else r.add(r("Do you know the answer?",.1,400,2180,1000,2220));var o=observe(pixels(true),r);assertTrue(o.currentCards().unassociatedKnownTitles()>0);assertTrue(o.currentCards().summary().contains("familyCoverage=UNESTABLISHED"));assertEquals(RewardRegionLocator.CurrentReadStatus.COMPLETE,card(o,3).status());}}
 @Test public void completeAspectOutsideShortRangeAndCancellationDoNotPublishFacts(){for(int height:new int[]{120,280}){var p=pixels(false);fill(p,114,1693,1326,2085,247);fill(p,114,1693,1326,1693+height,255);tick(p,1206,1749);var input=rows();input.removeIf(q->q.y()>=1909);var result=observe(p,input);assertTrue(result.currentCards().cards().stream().noneMatch(q->q.status()==RewardRegionLocator.CurrentReadStatus.COMPLETE));if(height==280)assertEquals(RewardRegionLocator.FailureGate.CARD_ASPECT,card(result,3).firstGate());}
  var key=new com.aliothmoon.maafw.rewards.RewardRegionGate.Key(com.aliothmoon.maafw.rewards.RewardRegionGate.Source.SYNTHETIC_FIXTURE,"cancel",1,"readonly");var raw=new ArrayList<RewardRegionLocator.Row>();for(var q:rows())raw.add(new RewardRegionLocator.Row(q.text(),new RewardRegionLocator.Box(q.x(),q.y(),q.x()+q.width(),q.y()+q.height()),q.score(),RewardRegionLocator.Origin.SYNTHETIC));
  assertEquals(RewardRegionLocator.CurrentBatchStatus.HOLD,RewardRegionLocator.observeCurrentCardsGray8(key,pixels(true),W,H,raw,null).status());assertEquals(RewardRegionLocator.Status.CANCELED,RewardRegionLocator.observeCurrentCardsGray8(key,pixels(true),W,H,raw,()->true).geometryStatus());}
}

