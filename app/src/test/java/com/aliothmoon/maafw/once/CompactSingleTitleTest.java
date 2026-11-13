package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.*;
public class CompactSingleTitleTest {
 static final int W=523,H=688;static final String T="Do you know the answer?";static final Key K=new Key(Source.SYNTHETIC_FIXTURE,"compact-single",1,"MORE");
 static final Section S=new Section(K,"更多活动",new Box(25,210,135,235),.95,Origin.SYNTHETIC);
 static Row row(String s,Box b,double c){return new Row(s,b,c,Origin.SYNTHETIC);} static void fill(byte[]p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
 static byte[] pix(boolean completed,boolean tick){byte[]p=new byte[W*H];Arrays.fill(p,(byte)247);fill(p,25,249,509,completed?339:413,255);
  if(!completed)fill(p,388,303,490,360,80);
  if(completed&&tick){for(int y=281;y<306;y++)for(int x=465;x<490;x++)if(Math.pow(x+.5-477.5,2)+Math.pow(y+.5-293.5,2)<=156.25)p[y*W+x]=(byte)110;String[]m={"00000000011","00000000111","00000001110","10000011100","11000111000","11101110000","01111100000","00111000000"};for(int y=0;y<8;y++)for(int x=0;x<11;x++)if(m[y].charAt(x)=='1')p[(289+y)*W+472+x]=(byte)255;}
  return p;}
 static TitleFamily observe(boolean completed,boolean tick,double score){List<Row> r=new ArrayList<>();r.add(row("更多活动",S.titleBox(),.95));r.add(row("10/20",new Box(388,210,480,235),.95));
  r.add(row(T,completed?new Box(55,270,320,295):new Box(55,310,320,335),.97));
  if(completed)r.add(row("5",new Box(426,276,472,306),score));else {r.add(row("+5",new Box(414,318,465,345),score));r.add(row("synthetic body",new Box(55,370,300,395),.95));}
  Geometry g=RewardRegionLocator.observeVisibleMoreGray8(K,pix(completed,tick),W,H,()->false);return RewardRegionLocator.observeTitleFamily(g,S,r,T,new VisibleItemCounter(CounterState.HEADER_FRACTION,r.get(1)));}
 static void ok(boolean b,String m){assertTrue(m,b);}
 @Test public void completedCompactSingleKeepsLegacyGeometry(){TitleFamily f=observe(true,true,.95);ok(f.status()==FamilyStatus.OBSERVED&&f.items().size()==1&&f.items().get(0).status()==VisibleItemStatus.ITEM_COMPLETE_SKIP&&f.items().get(0).title().originalParts().size()==1&&f.items().get(0).title().originalParts().get(0).box().equals(new Box(55,270,320,295)),"completed compact single exactly crosses midpoint by one pixel");
 ok(observe(false,false,.95).status()==FamilyStatus.HOLD,"unfinished single remains half-height bounded");ok(observe(true,false,.95).status()==FamilyStatus.HOLD,"no tick cannot widen completed single");ok(observe(true,true,.84).status()==FamilyStatus.HOLD,"low-score number cannot widen completed single");}
}
