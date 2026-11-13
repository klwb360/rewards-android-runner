package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.*;
public class TitlePartsLocatorTest {
 static final int W=523,H=688;static final String T="Do you know the answer?";static final Key K=new Key(Source.SYNTHETIC_FIXTURE,"title-parts",1,"MORE");
 static final Section SEC=new Section(K,"更多活动",new Box(25,110,135,135),.95,Origin.SYNTHETIC);
 static Row row(String t,Box b,double c){return new Row(t,b,c,Origin.SYNTHETIC);} static void fill(byte[]p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
 static Geometry geometry(){byte[]p=new byte[W*H];Arrays.fill(p,(byte)247);fill(p,25,149,509,313,255);fill(p,388,203,490,260,80);fill(p,25,329,509,493,255);fill(p,388,383,490,440,80);return RewardRegionLocator.observeVisibleMoreGray8(K,p,W,H,()->false);}
 static List<Row> rows(){return new ArrayList<>(List.of(row("更多活动",SEC.titleBox(),.95),row("10/20",new Box(388,110,480,135),.95),row(T,new Box(55,165,300,181),.99),row("First synthetic body",new Box(55,270,300,295),.95),row("+5",new Box(414,218,465,245),.95),row(T,new Box(55,345,300,361),.99),row("Second synthetic body",new Box(55,450,300,475),.95),row("+5",new Box(414,398,465,425),.95)));}
 static TitleFamily run(List<Row> r){return RewardRegionLocator.observeTitleFamily(geometry(),SEC,r,T,new VisibleItemCounter(CounterState.HEADER_FRACTION,r.get(1)));}
 static boolean two(TitleFamily f){return f.status()==FamilyStatus.OBSERVED&&f.items().size()==2;}
 static void ok(boolean b,String s){assertTrue(s,b);}
 static void materialShapeRegression(){
  int w=532,h=745;byte[] p=new byte[w*h];Arrays.fill(p,(byte)247);
  for(int y=250;y<412;y++)Arrays.fill(p,y*w+25,y*w+510,(byte)255);
  for(int y=428;y<592;y++)Arrays.fill(p,y*w+24,y*w+510,(byte)255);
  for(int y=303;y<360;y++)Arrays.fill(p,y*w+388,y*w+490,(byte)80);
  for(int y=482;y<539;y++)Arrays.fill(p,y*w+388,y*w+490,(byte)80);
  Section sec=new Section(K,"更多活动",new Box(25,210,135,235),.95,Origin.SYNTHETIC);
  List<Row> r=List.of(row("更多活动",sec.titleBox(),.95),row("10/20",new Box(390,210,480,235),.95),
   row("Do you know the",new Box(123,270,300,297),.970164),row("answer?",new Box(123,303,215,328),.999939),
   row("Challenge yourself with",new Box(125,337,340,361),.995586),row("these trivia questions",new Box(124,366,321,387),.982215),
   row("+5",new Box(410,315,460,340),.95),
   row("Do you know the",new Box(123,449,300,476),.972045),row("answer?",new Box(123,482,215,508),.999787),
   row("Test your knowledge on",new Box(124,516,344,540),.999858),row("these topics",new Box(123,545,240,569),.999925),
   row("+5",new Box(410,493,460,518),.95));
  Geometry g=RewardRegionLocator.observeVisibleMoreGray8(K,p,w,h,()->false);
  TitleFamily f=RewardRegionLocator.observeTitleFamily(g,sec,r,T,new VisibleItemCounter(CounterState.HEADER_FRACTION,r.get(1)));
  ok(two(f)&&f.items().stream().allMatch(i->i.title().kind()==TitleMatchKind.TWO_LINES&&i.body().size()==2),"synthetic surfaces with supplied historical OCR geometry");
 }
 @Test public void titlePartsAndLegacyGeometryRules(){
  ok(RewardRegionLocator.matchesKnownTitleText("DO YOU know the answer？",T),"public matcher accepts bounded single-row variant");
  ok(!RewardRegionLocator.matchesKnownTitleText("Do you know answer?",T),"public matcher rejects missing word");
  materialShapeRegression();List<Row> r=rows();TitleFamily f=run(r);
  ok(two(f)&&f.diagnostics().stream().allMatch(d->d.kind()==TitleMatchKind.EXACT_SINGLE),"exact singles");
  r=rows();r.set(2,row("DO YOU  know the answer?",r.get(2).box(),.96));f=run(r);ok(two(f)&&f.items().get(0).title().kind()==TitleMatchKind.NORMALIZED_SINGLE,"case and Unicode spaces only");
  r=rows();r.set(2,row("Do you know the answer",r.get(2).box(),.96));f=run(r);ok(two(f)&&f.items().get(0).title().kind()==TitleMatchKind.QUESTION_SINGLE,"missing terminal question");
  r=rows();r.set(2,row("Do you know the answer？",r.get(2).box(),.96));f=run(r);ok(two(f)&&f.items().get(0).title().kind()==TitleMatchKind.QUESTION_SINGLE,"full-width terminal question");
  r=rows();Row a=row("Do you know",new Box(55,165,190,181),.95),b=row("the answer?",new Box(56,184,190,200),.97);r.set(2,a);r.add(b);f=run(r);
  ok(two(f)&&f.items().get(0).title().kind()==TitleMatchKind.TWO_LINES,"two geometrically valid lines");
  ok(f.items().get(0).title().originalParts().equals(List.of(a,b))&&f.items().get(0).title().envelope().equals(new Box(55,165,190,200)),"original rows retained and envelope geometric only");
  ok(f.items().get(0).body().size()==1&&f.items().get(0).body().get(0).text().equals("First synthetic body"),"title parts excluded from body");
  ok(f.diagnostics().get(0).partCount()==2&&f.diagnostics().get(0).rawBoxes().equals(List.of(a.box(),b.box()))&&f.diagnostics().get(0).minConfidence()==.95,"safe diagnostic shape");
  for(String wrong:List.of("Do you the answer?","Do you know the answers?","Do know you the answer?","Do you know the answer??")){r=rows();r.set(2,row(wrong,r.get(2).box(),.95));ok(!two(run(r)),"reject wrong text "+wrong);}
  r=rows();r.set(2,row("Do you know",new Box(55,165,190,181),.95));r.add(row("the answer?",new Box(90,184,225,200),.95));ok(!two(run(r)),"reject misaligned split");
  r=rows();r.set(2,row("Do you know",new Box(55,165,190,181),.95));r.add(row("the answer?",new Box(55,208,190,224),.95));ok(!two(run(r)),"reject distant split");
  r=rows();r.set(2,row("Do you know",new Box(55,165,190,181),.95));r.add(row("the answer?",new Box(55,175,190,191),.95));ok(!two(run(r)),"reject overlapping split");
  r=rows();r.set(2,row("Do you know",new Box(55,165,190,181),.95));r.add(row("the answer?",new Box(55,184,190,200),.84));f=run(r);ok(f.status()==FamilyStatus.HOLD&&f.diagnostics().size()==2,"low score holds with both diagnostics");
  r=rows();r.set(2,row("Do you know",new Box(55,165,190,181),.95));r.add(row("the answer?",new Box(55,345,190,361),.95));ok(!two(run(r)),"never combine different cards");
  r=rows();r.set(2,row("Do you know",new Box(55,165,190,181),.95));r.add(row("the answer?",new Box(55,184,190,194),.95));ok(!two(run(r)),"reject dissimilar text heights");
  r=rows();r.set(2,row("Do you",new Box(55,165,190,177),.95));r.add(row("know the",new Box(55,180,190,192),.95));r.add(row("answer?",new Box(55,195,190,207),.95));ok(!two(run(r)),"reject three-part heading");
  r=rows();r.set(6,row("First synthetic body",r.get(6).box(),.95));ok(run(r).reason()==FamilyReason.DUPLICATE_BODY,"same bodies hold");
  r=rows();r.set(3,row("First synthetic body",new Box(55,170,300,195),.95));ok(run(r).status()==FamilyStatus.HOLD,"overlapping body holds");
 }
}
