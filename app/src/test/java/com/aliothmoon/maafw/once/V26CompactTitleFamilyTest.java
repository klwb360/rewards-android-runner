package com.aliothmoon.maafw.once;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic compact layout derived from measured BEFORE proportions; not a native AFTER. */
public class V26CompactTitleFamilyTest {
 static final int W=1440,H=2560;
 static void fill(byte[] p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
 static void tick(byte[] p,int l,int t){int side=64;for(int y=t;y<t+side;y++)for(int x=l;x<l+side;x++)if(Math.pow(x+.5-(l+32),2)+Math.pow(y+.5-(t+32),2)<=1024)p[y*W+x]=(byte)110;
  String[] m={"00000000011","00000000111","00000001110","10000011100","11000111000","11101110000","01111100000","00111000000"};
  for(int y=0;y<8;y++)for(int x=0;x<11;x++)if(m[y].charAt(x)=='1')for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++)p[(t+24+y*2+dy)*W+l+22+x*2+dx]=(byte)255;
 }
 static FrameStructureEvidence.Line line(String text,int x,int y,int w,int h,double q){return new FrameStructureEvidence.Line(text,x,y,w,h,q);}
 static byte[] pixels(boolean after,boolean aTick){byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
  fill(p,114,661,1326,after?917:1069,255);if(after){if(aTick)tick(p,1180,751);}else fill(p,1022,793,1278,937,80);
  fill(p,114,1109,1326,1517,255);fill(p,1022,1241,1278,1385,80);
  fill(p,114,1620,1326,1845,255);tick(p,1180,1700);fill(p,114,1950,1326,2175,255);tick(p,1180,2030);return p;
 }
 static List<FrameStructureEvidence.Line> rows(boolean after,int secondBottom,double titleScore,double numberScore){
  List<FrameStructureEvidence.Line> r=new ArrayList<>();r.add(line("更多活动",20,500,250,50,.99));r.add(line(after?"15/20":"10/20",1100,500,180,50,.99));
  r.add(line("?",177,786,132,after?114:134,.398219));r.add(line("Do you know the",360,712,444,72,titleScore));r.add(line("answer?",360,794,232,secondBottom-794,.999897));
  if(after)r.add(line("5",1080,758,83,53,numberScore));else {r.add(line("Challenge yourself with",365,880,535,56,.99988));r.add(line("these trivia questions",365,957,495,53,.999857));r.add(line("+5",1090,826,117,72,.997889));}
  r.add(line("Do you know the",362,1162,440,64,.999976));r.add(line("answer?",360,1242,232,64,.999897));
  r.add(line("Test your knowledge on",365,1320,535,56,.99988));r.add(line("these topics",365,1397,495,53,.999857));r.add(line("+5",1090,1274,117,72,.997889));
  r.add(line("Complete this puzzle",250,1655,600,45,.99));r.add(line("5",1080,1711,83,53,.99));
  r.add(line("Quote of the day",250,1985,600,45,.99));r.add(line("5",1080,2041,83,53,.99));return r;
 }
 static LiveRewardCardObservation.Frame observe(boolean after,boolean tick,int bottom,double titleScore,double numberScore){
  return LiveRewardCardObservation.observe("synthetic-v26-ratio",after?2:1,pixels(after,tick),W,H,rows(after,bottom,titleScore,numberScore),()->false,LiveRewardCardObservation.TargetSpec.moreTriviaChallenge());
 }
 @Test public void compactTwoPartFiveTickAndStableSiblingAreObserved(){
  var before=observe(false,false,858,.999976,.99);var after=observe(true,true,858,.999976,.99);
  assertEquals(LiveRewardCardObservation.Status.OBSERVED,before.status());assertEquals(LiveRewardCardObservation.Status.OBSERVED,after.status());
  assertEquals(2,after.cards().size());assertTrue(after.cards().get(0).complete());assertFalse(after.cards().get(1).complete());
  assertFalse(after.cards().get(0).configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()));
  assertTrue(before.cards().get(1).sameTask(after.cards().get(1)));
 }
 @Test public void completedScopeCannotBorrowTickChangeNumberOrWidenOtherPaths(){
  var wrong=rows(true,858,.999976,.99);for(int i=0;i<wrong.size();i++){var q=wrong.get(i);if(q.text().equals("5")&&q.y()==758)wrong.set(i,line("10",q.x(),q.y(),q.width(),q.height(),q.score()));}
  assertEquals(LiveRewardCardObservation.Status.HOLD,LiveRewardCardObservation.observe("synthetic-v26-ratio",2,pixels(true,true),W,H,wrong,()->false,LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()).status());
  byte[] misplaced=pixels(true,false);tick(misplaced,1180,1420);
  assertEquals(LiveRewardCardObservation.Status.HOLD,LiveRewardCardObservation.observe("synthetic-v26-ratio",2,misplaced,W,H,rows(true,858,.999976,.99),()->false,LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()).status());
  var firstOutside=rows(true,858,.999976,.99);for(int i=0;i<firstOutside.size();i++){var q=firstOutside.get(i);if(q.text().equals("Do you know the")&&q.y()==712)firstOutside.set(i,line(q.text(),q.x(),q.y(),q.width(),80,q.score()));}
  assertEquals(LiveRewardCardObservation.Status.HOLD,LiveRewardCardObservation.observe("synthetic-v26-ratio",2,pixels(true,true),W,H,firstOutside,()->false,LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()).status());
  var single=rows(true,858,.999976,.99);single.removeIf(q->(q.text().equals("Do you know the")&&q.y()==712)||(q.text().equals("answer?")&&q.y()==794));single.add(line("Do you know the answer?",360,712,444,146,.99));
  assertEquals(LiveRewardCardObservation.Status.HOLD,LiveRewardCardObservation.observe("synthetic-v26-ratio",2,pixels(true,true),W,H,single,()->false,LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()).status());
  assertEquals(LiveRewardCardObservation.Status.HOLD,observe(false,false,866,.999976,.99).status());
 }
 @Test public void beyondEightyPercentWithoutTickOrLowPartRemainsHeld(){
  assertEquals(LiveRewardCardObservation.Status.HOLD,observe(true,true,866,.999976,.99).status());
  assertEquals(LiveRewardCardObservation.Status.HOLD,observe(true,false,858,.999976,.99).status());
  assertEquals(LiveRewardCardObservation.Status.HOLD,observe(true,true,858,.84,.99).status());
  assertEquals(LiveRewardCardObservation.Status.HOLD,observe(true,true,858,.999976,.84).status());
 }
}
