package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.*;
public class MoreScopedPartitionTest {
 static final int W=532,H=745;static final Key K=new Key(Source.SYNTHETIC_FIXTURE,"schema",1,"MORE");
 static Row r(String t,double c,int x,int y,int w,int h){return new Row(t,new Box(x,y,x+w,y+h),c,Origin.STATIC_OCR);}
 static void fill(byte[]p,int l,int t,int rr,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+rr,(byte)v);}
 static Geometry geo(){byte[]p=new byte[W*H];Arrays.fill(p,(byte)247);fill(p,25,102,510,234,255);fill(p,25,250,510,412,255);fill(p,24,428,510,592,255);fill(p,24,608,510,739,255);fill(p,388,138,490,195,80);fill(p,388,300,490,357,80);fill(p,388,478,490,535,80);fill(p,388,650,490,707,80);return RewardRegionLocator.observeVisibleMoreGray8(K,p,W,H,()->false);}
 static List<Row> rows(){return new ArrayList<>(List.of(
  r("更多活动",.999906,1,16,131,35),r("?",.934134,51,478,44,64),r("一",.330426,60,305,37,39),
  r("the image",.999935,121,184,98,29),r("Do you know the",.970164,123,270,177,27),r("answer?",.999939,123,303,92,25),
  r("Do you know the",.972045,123,449,177,27),r("answer?",.999787,123,482,92,26),r("these topics",.999925,123,545,117,24),
  r("quote",.99994,123,693,58,26),r("these trivia questions",.982215,124,366,197,21),r("Test your knowledge on",.999858,124,516,220,24),
  r("Start your day with a",.986439,124,664,189,24),r("Arrange the tiles to reveal",.990941,125,158,234,22),
  r("Challenge yourself with",.995586,125,337,215,24),r("Quote of the day",.993576,125,630,173,24),
  r("Complete this puzzle",.999873,126,124,219,24),r("0/20",.999976,412,18,69,33),r("+5",.998999,414,656,48,32),
  r("+5",.999159,415,152,46,29),r("+5",.999242,415,315,46,29),r("+5",.99913,415,494,46,29)));}
 static TitleFamily run(List<Row> r){Section s=new Section(K,"更多活动",r.get(0).box(),r.get(0).confidence(),Origin.STATIC_OCR);return RewardRegionLocator.observeTitleFamilyPartitioned(geo(),s,r,"Do you know the answer?",new VisibleItemCounter(CounterState.HEADER_FRACTION,r.get(17)));}
 static void ok(boolean b,String s){assertTrue(s,b);} static boolean ready(TitleFamily f){return f.status()==FamilyStatus.OBSERVED&&f.items().size()==2;}
 static void completedSibling(){
  byte[]p=new byte[W*H];Arrays.fill(p,(byte)247);fill(p,25,102,510,234,255);fill(p,25,250,510,382,255);fill(p,24,428,510,592,255);fill(p,24,608,510,739,255);
  fill(p,388,138,490,195,80);fill(p,388,478,490,535,80);fill(p,388,650,490,707,80);
  for(int y=300;y<325;y++)for(int x=465;x<490;x++)if(Math.pow(x+.5-477.5,2)+Math.pow(y+.5-312.5,2)<=156.25)p[y*W+x]=(byte)110;
  String[]m={"00000000011","00000000111","00000001110","10000011100","11000111000","11101110000","01111100000","00111000000"};for(int y=0;y<8;y++)for(int x=0;x<11;x++)if(m[y].charAt(x)=='1')p[(308+y)*W+472+x]=(byte)255;
  List<Row> r=rows();r.set(20,r("5",.99,426,295,46,30));r.remove(14);r.remove(10);Row counter=null;for(Row row:r)if(row.text().equals("0/20"))counter=row;
  Geometry g=RewardRegionLocator.observeVisibleMoreGray8(K,p,W,H,()->false);Section s=new Section(K,"更多活动",r.get(0).box(),r.get(0).confidence(),Origin.STATIC_OCR);
  TitleFamily f=RewardRegionLocator.observeTitleFamilyPartitioned(g,s,r,"Do you know the answer?",new VisibleItemCounter(CounterState.HEADER_FRACTION,counter));
  ok(ready(f)&&f.items().get(0).status()==VisibleItemStatus.ITEM_COMPLETE_SKIP&&f.items().get(0).body().isEmpty()&&f.items().get(1).status()==VisibleItemStatus.CANDIDATE,"completed short title preserves unknown lane and unchanged sibling");
 }
 static void hold(TitleFamily f,FailureGate gate,String text){ok(f.status()==FamilyStatus.HOLD&&f.failure()!=null&&f.failure().gate()==gate,text);}
 @Test public void exactRowsAndPartitionSafetyRules(){completedSibling();List<Row> r=rows();TitleFamily f=run(r);ok(ready(f)&&f.fraction().numerator()==0&&f.fraction().denominator()==20,"all 22 historical OCR rows with synthetic CV surfaces");
  ok(f.items().get(0).partition().leftUnmodeled().get(0)==r.get(2)&&f.items().get(1).partition().leftUnmodeled().get(0)==r.get(1),"original unknown rows retained by identity");
  ok(f.items().stream().allMatch(c->c.partition().centralIdentity().size()==4&&c.partition().rightState().size()==1),"all original fields uniquely partitioned");
  ok(!f.items().get(0).partition().toString().contains("一"),"partition toString excludes text");
  r=rows();r.set(2,r("1",.33,60,305,37,39));ok(ready(run(r)),"isolated left numeric preserved as unknown");
  for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-.1,1.01}){r=rows();r.set(2,r("?",bad,60,305,37,39));hold(run(r),FailureGate.ROW_FORMAT,"malformed unknown score holds");}
  r=rows();r.set(2,new Row("?",new Box(-1,305,97,344),.33,Origin.STATIC_OCR));hold(run(r),FailureGate.ROW_FORMAT,"invalid unknown box holds");
  r=rows();r.set(2,new Row("?",new Box(60,305,97,344),.33,null));hold(run(r),FailureGate.ROW_FORMAT,"missing unknown origin holds");
  r=rows();r.set(2,r(" ",.33,60,305,37,39));hold(run(r),FailureGate.ROW_FORMAT,"empty unknown OCR holds");
  r=rows();r.set(2,r("?",.33,60,305,43,39));ok(ready(run(r)),"last integer pixel inside U accepted");
  r=rows();r.set(2,r("?",.33,60,305,44,39));f=run(r);hold(f,FailureGate.PARTITION_CROSSING,"one pixel beyond U enters gutter and holds");ok(f.failure().unclassifiedRows()==1,"crossing diagnostic count");
  r=rows();r.set(2,r("一",.33,130,390,15,15));hold(run(r),FailureGate.PARTITION_LOW_CONFIDENCE,"low central row holds");
  r=rows();r.set(2,r("一",.33,415,370,15,15));hold(run(r),FailureGate.PARTITION_LOW_CONFIDENCE,"low right row holds");
  r=rows();r.add(r("extra",.95,380,370,10,10));hold(run(r),FailureGate.PARTITION_CROSSING,"C to R boundary crossing holds");
  for(String v:List.of("+5","+","+\t5","1/5","√","✓","锁","🔒","１小时","locked","订阅","Do you know the answer?")){r=rows();r.set(2,r(v,.33,60,305,37,39));hold(run(r),FailureGate.TEMPLATE_CONFLICT,"left unmodeled veto "+v);}
  r=rows();r.set(2,r("Do you know the",.33,60,305,37,20));r.add(r("answer?",.33,60,330,37,20));hold(run(r),FailureGate.TEMPLATE_CONFLICT,"split known title in U holds");
  r=rows();r.set(2,r("Do you know the",.33,60,305,37,15));r.add(r("answer?",.33,60,325,37,15));r.add(r("?",.33,60,345,37,15));hold(run(r),FailureGate.TEMPLATE_CONFLICT,"split known title amid extra U row holds");
  for(String[] pair:new String[][]{{"+","5"},{"sign","in"},{"top","up"},{"place","order"},{"订","阅"},{"已完","成"}}){r=rows();r.set(2,r(pair[0],.33,60,305,37,15));r.add(r(pair[1],.33,60,325,37,15));r.add(r("?",.33,60,345,37,15));hold(run(r),FailureGate.TEMPLATE_CONFLICT,"split risk amid extra U row holds");}
  r=rows();r.set(20,r("+5",.99,388,315,46,29));f=run(r);hold(f,FailureGate.PARTITION_CROSSING,"number crosses C/R boundary");ok(f.failure().numberBox().equals(r.get(20).box())&&f.failure().derivedSafeBox().equals(new Box(390,302,488,355))&&f.failure().pointsInsideSafe()==DiagnosticCheck.NO,"failure reports original number and derived safe boxes");
  r=rows();r.add(r("extra",.95,125,390,120,15));hold(run(r),FailureGate.IDENTITY_BODY,"extra central row holds");
  r=rows();r.add(r("?",.95,430,370,15,15));hold(run(r),FailureGate.RIGHT_EXTRA_ROW,"unknown right state holds");
  r=rows();r.add(r("extra",.95,125,340,40,15));hold(run(r),FailureGate.ROW_OVERLAP,"overlapping original rows hold");
  r=rows();r.set(10,r("these trivia questions",.84,124,366,197,21));hold(run(r),FailureGate.PARTITION_LOW_CONFIDENCE,"low body holds");
  r=rows();r.set(20,r("+5",.84,415,315,46,29));hold(run(r),FailureGate.PARTITION_LOW_CONFIDENCE,"low right numeric holds");
  r=rows();r.set(20,r("+6",.99,415,315,46,29));hold(run(r),FailureGate.POINTS_VALUE,"wrong points value has precise gate");
  r=rows();r.set(20,r("5",.99,415,315,46,29));hold(run(r),FailureGate.POINTS_COUNT,"missing plus has precise gate");
  r=rows();r.set(20,r("+5",.99,389,315,46,29));f=run(r);hold(f,FailureGate.POINTS_OUTSIDE_SAFE,"badge safe edge has precise gate");ok(f.failure().numberBox().equals(r.get(20).box())&&f.failure().pointsInsideSafe()==DiagnosticCheck.NO,"unsafe number diagnostics retain exact box");
  r=rows();r.set(11,r("Challenge yourself with",.95,124,516,220,24));r.set(8,r("these trivia questions",.95,123,545,117,24));hold(run(r),FailureGate.BODY_DUPLICATE,"duplicate full body holds");
 }
}
