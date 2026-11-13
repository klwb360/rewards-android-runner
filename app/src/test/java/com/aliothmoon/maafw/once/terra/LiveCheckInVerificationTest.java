package com.aliothmoon.maafw.rewards;
import java.util.*;
import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import static com.aliothmoon.maafw.rewards.LiveCheckInVerification.*;
/** In-memory deterministic unit tests, NOT live observations or Android testing. */
public final class LiveCheckInVerificationTest {
 static final int W=552,H=201; static final Object scope=new Object(),fg=new Object();
 static final String[] CHECK={"00000000000111","00000000001110","00000000011100","10000000111000","11100001110000","01110011100000","00111111000000","00011110000000","00001100000000"};
 static void fill(byte[] p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
 static void disk(byte[] p,int x,int y,int radius,int v){for(int yy=y-radius;yy<=y+radius;yy++)for(int xx=x-radius;xx<=x+radius;xx++)if((xx-x)*(xx-x)+(yy-y)*(yy-y)<=radius*radius)p[yy*W+xx]=(byte)v;}
 static void tick(byte[] p,int x){disk(p,x,115,16,200);for(int y=0;y<9;y++)for(int i=0;i<14;i++)if(CHECK[y].charAt(i)=='1')p[(111+y)*W+x-7+i]=(byte)250;}
 static byte[] pixels(){byte[] p=new byte[W*H];Arrays.fill(p,(byte)235);fill(p,10,17,536,193,250);tick(p,62);tick(p,132);tick(p,202);disk(p,272,115,27,190);disk(p,272,115,25,250);disk(p,272,115,17,230);fill(p,264,109,268,122,90);fill(p,274,109,280,122,90);return p;}
 static List<Row> rows(String n){var r=new ArrayList<Row>();r.add(new Row("签入",new Box(30,42,79,65),.99,Origin.STATIC_OCR));if(n!=null)r.add(new Row(n,new Box(264,109,280,122),.99,Origin.STATIC_OCR));return r;}
 static Frame frame(byte[] p,long id,long time,Object s,Object f,List<Row> rows){var key=new Key(Source.REAL_DISPLAY_NATIVE,"scope",id,"CHECKIN");var g=DailyCheckInRegion.observeGray8(key,p,W,H,()->false);return new Frame(s,f,id,time,1000,g,new DailyCheckInRegion.TextEvidence(key,rows));}
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] a){
  byte[] p=pixels(); Frame before=frame(p,1,100,scope,fg,rows("10")); Target t=target(before,120);
  check(t!=null && t.x()==272.5 && t.y()==115.5,"full-display x/y");
  check(target(frame(p,1,100,scope,fg,rows("１０")),120)==null,"ASCII required");
  check(target(frame(p,1,100,scope,fg,rows("20")),120)==null,"only 10");
  check(target(before,1000)==null,"expiry");
  check(matchesForAction(t,scope,fg,1,120)&&!matchesForAction(t,new Object(),fg,1,120)
    &&!matchesForAction(t,scope,fg,1,1000)&&t.width()==W&&t.height()==H,"action freshness and source metadata");
  byte[] done=p.clone();fill(done,245,88,300,143,250);tick(done,272);
  Frame after=frame(done,2,200,scope,fg,rows(null));
  check(compare(t,after,150,250)==Outcome.OBSERVED_SAME_LOCATION_MARKED_COMPLETE_UNATTRIBUTED,"same location tick");
  byte[] newRing=done.clone();disk(newRing,342,115,27,190);disk(newRing,342,115,25,250);
  check(compare(t,frame(newRing,2,200,scope,fg,rows(null)),150,250)==Outcome.OBSERVED_SAME_LOCATION_MARKED_COMPLETE_UNATTRIBUTED,"unreadable next ring does not erase prior mark");
  byte[] retained=p.clone();disk(retained,272,115,25,250);tick(retained,272);
  Frame kept=frame(retained,2,200,scope,fg,rows(null));var retainedResult=compareDetailed(t,kept,150,250);
  check(retainedResult.outcome()==Outcome.OBSERVED_SAME_LOCATION_MARKED_COMPLETE_UNATTRIBUTED
   &&retainedResult.oldRingCount()==1&&retainedResult.targetTickCount()==1&&!retainedResult.localConflict(),"retained ring and new same-position tick");
  byte[] retainedNoTick=p.clone();retainedNoTick[50*W+400]=42;
  Frame unchanged=frame(retainedNoTick,2,200,scope,fg,rows(null));var noMark=compareDetailed(t,unchanged,150,250);
  check(noMark.outcome()==Outcome.NO_LOCAL_COMPLETION&&noMark.oldRingCount()==1&&noMark.targetTickCount()==0,"retained ring without tick is not completion");
  var completedWords=rows(null);completedWords.add(new Row("已签到",new Box(400,46,450,65),.95,Origin.STATIC_OCR));
  check(compare(t,frame(retained,2,200,scope,fg,completedWords),150,250)==Outcome.OBSERVED_SAME_LOCATION_MARKED_COMPLETE_UNATTRIBUTED,"positive completion text is not conflict");
  var tomorrow=rows(null);tomorrow.add(new Row("明天",new Box(400,46,450,65),.95,Origin.STATIC_OCR));
  check(compare(t,frame(retained,2,200,scope,fg,tomorrow),150,250)==Outcome.NO_LOCAL_COMPLETION,"tomorrow conflict blocks");
  var numericConflict=rows("10");var bad=compareDetailed(t,frame(retained,2,200,scope,fg,numericConflict),150,250);
  check(bad.outcome()==Outcome.NO_LOCAL_COMPLETION&&bad.localConflict(),"explicit local unfinished number blocks tick");
  check(compare(t,after,201,250)==Outcome.STALE_OR_SCOPE_CHANGED,"after precedes action");
  check(compare(t,after,150,1000)==Outcome.BLOCKED,"expired comparison");
  check(compare(t,frame(done,1,200,scope,fg,rows(null)),150,250)==Outcome.STALE_OR_SCOPE_CHANGED,"same frame id");
  check(compare(t,frame(p,2,200,scope,fg,rows("10")),150,250)==Outcome.STALE_OR_SCOPE_CHANGED,"identical hash");
  check(compare(t,frame(done,2,200,new Object(),fg,rows(null)),150,250)==Outcome.STALE_OR_SCOPE_CHANGED,"scope");
  check(compare(t,frame(done,2,200,scope,new Object(),rows(null)),150,250)==Outcome.STALE_OR_SCOPE_CHANGED,"foreground");
  byte[] shifted=p.clone();fill(shifted,245,88,300,143,250);tick(shifted,282);
  check(compare(t,frame(shifted,2,200,scope,fg,rows(null)),150,250)==Outcome.NO_LOCAL_COMPLETION,"shifted tick");
  byte[] changedNeighbor=done.clone();fill(changedNeighbor,40,90,85,140,250);
  check(compare(t,frame(changedNeighbor,2,200,scope,fg,rows(null)),150,250)==Outcome.NO_LOCAL_COMPLETION,"neighbor state changed");
  byte[] noTick=p.clone();fill(noTick,245,88,300,143,250);
  check(compare(t,frame(noTick,2,200,scope,fg,rows(null)),150,250)==Outcome.NO_LOCAL_COMPLETION,"no tick");
  byte[] next=p.clone();next[40*W+350]=55;
  check(compare(t,frame(next,2,200,scope,fg,rows("10")),150,250)==Outcome.NO_LOCAL_COMPLETION,"target ring remains");
  var moved=rows(null);moved.set(0,new Row("每日活动",new Box(30,42,79,65),.99,Origin.STATIC_OCR));
  check(compare(t,frame(done,2,200,scope,fg,moved),150,250)==Outcome.NO_LOCAL_COMPLETION,"wrong title");
  check(compare(null,after,150,250)==Outcome.BLOCKED,"missing target");
  System.out.println("PASS 24 deterministic unit assertions (synthetic data, no Android or native source)");
 }
}









