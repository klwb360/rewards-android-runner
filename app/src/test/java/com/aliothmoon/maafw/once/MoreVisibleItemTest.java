package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic only. Does not establish a real More layout, OCR accuracy, or clickability. */
public class MoreVisibleItemTest {
    static final int W=523,H=1400;
    static final Key K=new Key(Source.SYNTHETIC_FIXTURE,"more",1,"MORE");
    static final Section SEC=new Section(K,"更多活动",new Box(25,110,135,135),.95,Origin.SYNTHETIC);
    static final VisibleItemCounter NONE=new VisibleItemCounter(CounterState.NO_COUNTER,null);
    static void fill(byte[] p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
    static byte[] pixels(boolean shortCard,boolean partialSibling){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
        fill(p,25,149,509,shortCard?220:313,255);
        if(!shortCard)fill(p,388,203,490,260,80);
        if(partialSibling)fill(p,25,1280,509,H,255);
        if(shortCard){
            for(int y=174;y<199;y++)for(int x=465;x<490;x++)
                if(Math.pow(x+.5-477.5,2)+Math.pow(y+.5-186.5,2)<=156.25)p[y*W+x]=(byte)110;
            String[] m={"00000000011","00000000111","00000001110","10000011100",
                "11000111000","11101110000","01111100000","00111000000"};
            for(int y=0;y<8;y++)for(int x=0;x<11;x++)
                if(m[y].charAt(x)=='1')p[(182+y)*W+472+x]=(byte)255;
        }
        return p;
    }
    static Geometry geometry(byte[] p){return RewardRegionLocator.observeVisibleMoreGray8(K,p,W,H,()->false);}
    static Row row(String t,Box b,double c){return new Row(t,b,c,Origin.SYNTHETIC);}
    static List<Row> rows(){return new ArrayList<>(List.of(
        row("更多活动",SEC.titleBox(),.95),
        row("Quote of the day",new Box(55,170,300,195),.95),
        row("Read today's quote",new Box(55,270,300,295),.95),
        row("+5",new Box(414,218,465,245),.95)));}
    static VisibleItemResult associate(Geometry g,List<Row> r,VisibleItemCounter c){
        return RewardRegionLocator.associateVisibleItem(g,SEC,r,"Quote of the day",c);
    }

    @Test public void targetCanBeObservedWithNoCounterAndPartialLowConfidenceSiblings(){
        byte[] p=pixels(false,true);
        fill(p,25,450,509,620,255);fill(p,388,500,490,557,80);
        fill(p,25,650,509,820,255);fill(p,388,700,490,757,80);
        Geometry g=geometry(p);List<Row> r=rows();
        r.add(row("unrelated",new Box(55,1310,300,1335),.3));
        r.add(row("Do you know the answer?",new Box(55,500,300,525),.95));
        r.add(row("Do you know the answer?",new Box(55,700,300,725),.95));
        VisibleItemResult v=associate(g,r,NONE);
        assertEquals(VisibleItemStatus.CANDIDATE,v.status());
        assertEquals(5,v.candidate().displayedPoints());assertEquals(1,v.body().size());
        assertNull(v.fraction());assertEquals(4,g.cards().size());assertFalse(g.cards().get(3).fullyVisible());
    }
    @Test public void duplicateTargetAndLowConfidenceTargetOrHeaderHold(){
        Geometry g=geometry(pixels(false,true));List<Row> r=rows();
        r.add(row("Quote of the day",new Box(55,1310,300,1335),.3));
        assertEquals(VisibleItemStatus.HOLD,associate(g,r,NONE).status());
        r=rows();r.set(3,row("+5",new Box(414,218,465,245),.84));
        assertEquals(VisibleItemStatus.HOLD,associate(g,r,NONE).status());
        r=rows();r.set(0,row("更多活动",SEC.titleBox(),.84));
        assertEquals(VisibleItemStatus.HOLD,associate(g,r,NONE).status());
    }
    @Test public void ambiguousCounterIsNeverNoCounter(){
        Geometry g=geometry(pixels(false,true));List<Row> r=rows();
        r.add(row("?/3",new Box(388,110,480,135),.95));
        assertEquals(VisibleItemStatus.HOLD,associate(g,r,NONE).status());
    }
    @Test public void headerFractionMustBeValidAndControlsRegionSkip(){
        Geometry g=geometry(pixels(false,true));
        for(String text:List.of("0/0","3/2","?/3")){
            List<Row> r=rows();Row counter=row(text,new Box(388,110,480,135),.95);r.add(counter);
            assertEquals(text,VisibleItemStatus.HOLD,
                associate(g,r,new VisibleItemCounter(CounterState.HEADER_FRACTION,counter)).status());
        }
        List<Row> r=rows();Row counter=row("3/3",new Box(388,110,480,135),.95);r.add(counter);
        assertEquals(VisibleItemStatus.REGION_COMPLETE_SKIP,
            associate(g,r,new VisibleItemCounter(CounterState.HEADER_FRACTION,counter)).status());
        r=rows();counter=row("1/3",new Box(388,110,480,135),.95);r.add(counter);
        assertEquals(VisibleItemStatus.CANDIDATE,
            associate(g,r,new VisibleItemCounter(CounterState.HEADER_FRACTION,counter)).status());
    }
    @Test public void selectedPartialCardIsHeld(){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
        fill(p,25,1280,509,H,255);fill(p,388,1330,490,1387,80);
        List<Row> r=List.of(row("更多活动",SEC.titleBox(),.95),
            row("Quote of the day",new Box(55,1300,300,1325),.95),
            row("+5",new Box(414,1340,465,1365),.95));
        assertEquals(VisibleItemStatus.HOLD,associate(geometry(p),r,NONE).status());
    }
    @Test public void sameCardPlainNumberThenTickSkipsCompletedItem(){
        List<Row> r=List.of(row("更多活动",SEC.titleBox(),.95),
            row("Quote of the day",new Box(55,158,300,170),.95),
            row("5",new Box(426,169,472,199),.99995));
        VisibleItemResult result=associate(geometry(pixels(true,false)),r,NONE);
        assertEquals(VisibleItemStatus.ITEM_COMPLETE_SKIP,result.status());assertNotNull(result.skipped());
    }
}
