package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.*;
public class CompletedSplitTitleTest {
 static final int W=523,H=688;static final String T="Do you know the answer?";static final Key K=new Key(Source.SYNTHETIC_FIXTURE,"completed-title",1,"MORE");
 static final Section S=new Section(K,"更多活动",new Box(25,110,135,135),.95,Origin.SYNTHETIC);
 static Row row(String s,Box b,double c){return new Row(s,b,c,Origin.SYNTHETIC);} static void fill(byte[]p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
 static byte[] pix(boolean completed,boolean tick){byte[]p=new byte[W*H];Arrays.fill(p,(byte)247);fill(p,25,149,509,completed?220:313,255);
  if(!completed)fill(p,388,203,490,260,80);
  if(completed&&tick){for(int y=174;y<199;y++)for(int x=465;x<490;x++)if(Math.pow(x+.5-477.5,2)+Math.pow(y+.5-186.5,2)<=156.25)p[y*W+x]=(byte)110;String[]m={"00000000011","00000000111","00000001110","10000011100","11000111000","11101110000","01111100000","00111000000"};for(int y=0;y<8;y++)for(int x=0;x<11;x++)if(m[y].charAt(x)=='1')p[(182+y)*W+472+x]=(byte)255;}
  return p;}
 static TitleFamily observe(boolean completed,boolean tick,double score){
  List<Row> r=new ArrayList<>();r.add(row("更多活动",S.titleBox(),.95));r.add(row("10/20",new Box(388,110,480,135),.95));
  int y=completed?155:210;r.add(row("Do you know the",new Box(55,y,190,y+16),.96));r.add(row("answer?",new Box(55,y+19,190,y+35),.97));
  if(completed)r.add(row("5",new Box(426,169,472,199),score));
  else {r.add(row("+5",new Box(414,218,465,245),score));r.add(row("synthetic body",new Box(55,270,300,295),.95));}
  Geometry g=RewardRegionLocator.observeVisibleMoreGray8(K,pix(completed,tick),W,H,()->false);
  return RewardRegionLocator.observeTitleFamily(g,S,r,T,new VisibleItemCounter(CounterState.HEADER_FRACTION,r.get(1)));
 }
 static void ok(boolean b,String message){assertTrue(message,b);}
 @Test public void onlyIndependentlyCompletedCardsMayExpandHeading(){TitleFamily f=observe(true,true,.95);ok(f.status()==FamilyStatus.OBSERVED&&f.items().size()==1&&f.items().get(0).status()==VisibleItemStatus.ITEM_COMPLETE_SKIP&&f.items().get(0).title().originalParts().size()==2&&f.items().get(0).title().envelope().bottom()==190,"confirmed same-card tick permits bounded split heading past halfway");
  ok(observe(false,false,.95).status()==FamilyStatus.HOLD,"unfinished card cannot use completed-only expansion");
  ok(observe(true,false,.95).status()==FamilyStatus.HOLD,"no tick cannot use expansion");
  ok(observe(true,true,.84).status()==FamilyStatus.HOLD,"low-confidence completed number cannot use expansion");}
}
