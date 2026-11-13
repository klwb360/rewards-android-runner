package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.*;
public class MoreVisibleFamilyTest {
 static final int W=523,H=1000; static final String T="Same question?";static final Key K=new Key(Source.SYNTHETIC_FIXTURE,"family",1,"MORE");
 static final Section SEC=new Section(K,"更多活动",new Box(25,50,135,75),.95,Origin.SYNTHETIC);
 static void fill(byte[]p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
 static byte[] pixels(boolean after,boolean clipped){byte[]p=new byte[W*H];Arrays.fill(p,(byte)247);
   fill(p,25,149,509,after?220:313,255);if(!after)fill(p,388,203,490,260,80);
   int y=clipped?930:after?240:329;fill(p,25,y,509,clipped?H:y+164,255);if(!clipped)fill(p,388,y+54,490,y+111,80);
   if(after){for(int yy=174;yy<199;yy++)for(int x=465;x<490;x++)if(Math.pow(x+.5-477.5,2)+Math.pow(yy+.5-186.5,2)<=156.25)p[yy*W+x]=(byte)110;String[]m={"00000000011","00000000111","00000001110","10000011100","11000111000","11101110000","01111100000","00111000000"};for(int yy=0;yy<8;yy++)for(int x=0;x<11;x++)if(m[yy].charAt(x)=='1')p[(182+yy)*W+472+x]=(byte)255;}
   return p; }
 static Geometry geo(boolean a,boolean c){return RewardRegionLocator.observeVisibleMoreGray8(K,pixels(a,c),W,H,()->false);}
 static Row row(String t,Box b,double q){return new Row(t,b,q,Origin.SYNTHETIC);}
 static List<Row> rows(boolean after){int y=after?240:329;List<Row> r=new ArrayList<>();r.add(row("更多活动",SEC.titleBox(),.95));r.add(row("10/20",new Box(388,50,480,75),.95));r.add(row(T,new Box(55,after?158:170,300,after?170:195),.95));if(after)r.add(row("5",new Box(426,169,472,199),.95));else{r.add(row("First distinct body",new Box(55,270,300,295),.95));r.add(row("+5",new Box(414,218,465,245),.95));}r.add(row(T,new Box(55,y+21,300,y+46),.95));r.add(row("Second distinct body",new Box(55,y+121,300,y+146),.95));r.add(row("+5",new Box(414,y+69,465,y+96),.95));return r;}
 static VisibleItemFamily result(Geometry g,List<Row> r){return RewardRegionLocator.observeVisibleItems(g,SEC,r,T,new VisibleItemCounter(CounterState.HEADER_FRACTION,r.get(1)));}
 static void ok(boolean b,String s){assertTrue(s,b);}
 @Test public void sameTitleFamilyObservationRules(){Geometry g=geo(false,false);List<Row> r=rows(false);VisibleItemFamily f=result(g,r);
   ok(f.status()==FamilyStatus.OBSERVED&&f.items().size()==2&&f.items().stream().allMatch(i->i.status()==VisibleItemStatus.CANDIDATE),"two same-title distinct-body unfinished cards");
   ok(f.items().get(0).body().get(0).text().equals("First distinct body")&&f.items().get(1).body().get(0).text().equals("Second distinct body"),"raw body rows retained");
   ok(RewardRegionLocator.associateVisibleItem(g,SEC,r,T,new VisibleItemCounter(CounterState.HEADER_FRACTION,r.get(1))).status()==VisibleItemStatus.HOLD,"legacy title-only API rejects duplicate");
   List<Row> dup=rows(false);dup.set(6,row("First distinct body",dup.get(6).box(),.95));ok(result(g,dup).reason()==FamilyReason.DUPLICATE_BODY,"same title and same body hold");
   List<Row> missing=rows(false);missing.remove(6);ok(result(g,missing).reason()==FamilyReason.MISSING_BODY,"missing unfinished body holds family");
   List<Row> low=rows(false);low.set(6,row("Second distinct body",low.get(6).box(),.84));ok(result(g,low).status()==FamilyStatus.HOLD,"low confidence family member holds");
   List<Row> lowtitle=rows(false);lowtitle.set(5,row(T,lowtitle.get(5).box(),.84));ok(result(g,lowtitle).status()==FamilyStatus.HOLD,"low confidence same title holds");
   List<Row> no=rows(false);no.set(1,row("10/0",no.get(1).box(),.95));ok(result(g,no).status()==FamilyStatus.HOLD,"bad header holds");
   List<Row> complete=rows(false);complete.set(1,row("20/20",complete.get(1).box(),.95));ok(result(g,complete).status()==FamilyStatus.REGION_COMPLETE_SKIP,"completed real header skips");
   List<Row> a=rows(true);VisibleItemFamily after=result(geo(true,false),a);ok(after.status()==FamilyStatus.OBSERVED&&after.items().size()==2&&after.items().get(0).status()==VisibleItemStatus.ITEM_COMPLETE_SKIP&&after.items().get(0).body().isEmpty()&&after.items().get(1).status()==VisibleItemStatus.CANDIDATE,"after observes bodyless tick and unchanged sibling without attribution");
   List<Row> o=rows(false);o.set(5,row(T,new Box(55,940,300,965),.95));o.set(6,row("Second distinct body",new Box(55,970,300,995),.95));o.remove(7);ok(result(geo(false,true),o).status()==FamilyStatus.HOLD,"partial same-title sibling holds");
   List<Row> same=rows(false);same.set(5,row(T,new Box(55,200,300,220),.95));ok(result(g,same).status()==FamilyStatus.HOLD,"two same-title rows in one card hold");
 }
}
