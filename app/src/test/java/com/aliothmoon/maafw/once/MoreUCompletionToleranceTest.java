package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Geometry-only v26 measurements on synthetic pixels/text. Not a real frame replay. */
public class MoreUCompletionToleranceTest {
    static final int W=1440,H=2560;
    static final Key K=new Key(Source.SYNTHETIC_FIXTURE,"v26-geometry-only",1,"MORE");
    static Row row(String s,double q,Box b){return new Row(s,b,q,Origin.SYNTHETIC);}
    static Geometry geometry(){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
        for(int y=661;y<1069;y++)Arrays.fill(p,y*W+114,y*W+1326,(byte)255);
        for(int y=793;y<937;y++)Arrays.fill(p,y*W+1022,y*W+1278,(byte)80);
        return RewardRegionLocator.observeVisibleMoreGray8(K,p,W,H,()->false);
    }
    static List<Row> rows(){return new ArrayList<>(List.of(
        row("更多活动",.99,new Box(20,500,270,550)),row("10/20",.99,new Box(1100,500,1280,550)),
        row("?",.398219,new Box(177,786,309,920)),
        row("Do you know the",.999976,new Box(360,712,804,784)),row("answer?",.999897,new Box(360,794,592,858)),
        row("Challenge yourself with",.99988,new Box(365,880,900,936)),row("these trivia questions",.999857,new Box(365,957,860,1010)),
        row("+5",.997889,new Box(1090,826,1207,898))));}
    static TitleFamily run(Geometry g,List<Row> rows){Section s=new Section(K,"更多活动",rows.get(0).box(),rows.get(0).confidence(),Origin.SYNTHETIC);
        return RewardRegionLocator.observeTitleFamilyPartitioned(g,s,rows,"Do you know the answer?",new VisibleItemCounter(CounterState.HEADER_FRACTION,rows.get(1)));}
    @Test public void measuredBorderIsAdmittedWithoutReplacingOriginalBoxOrScore(){
        Geometry g=geometry();List<Row> rows=rows();TitleFamily f=run(g,rows);
        assertEquals(FamilyStatus.OBSERVED,f.status());assertEquals(1,f.items().size());
        assertSame(rows.get(2),f.items().get(0).partition().leftUnmodeled().get(0));
        assertEquals(new Box(177,786,309,920),rows.get(2).box());assertEquals(.398219,rows.get(2).confidence(),0);
        assertEquals(4,f.items().get(0).partition().centralIdentity().size());assertEquals(1,f.items().get(0).partition().rightState().size());
    }
    @Test public void oneMorePixelAndCentralOrRightLowScoresRemainHeld(){
        Geometry g=geometry();List<Row> r=rows();r.set(2,row("?",.398219,new Box(177,786,310,920)));
        TitleFamily f=run(g,r);assertEquals(FailureGate.PARTITION_CROSSING,f.failure().gate());assertEquals(309.132,f.failure().uBoundary(),1e-9);assertEquals(332.16,f.failure().cBoundary(),1e-9);
        r=rows();r.set(2,row("?",.398219,new Box(400,1015,532,1050)));assertEquals(FailureGate.PARTITION_LOW_CONFIDENCE,run(g,r).failure().gate());
        r=rows();r.set(2,row("?",.398219,new Box(1100,1015,1232,1050)));assertEquals(FailureGate.PARTITION_LOW_CONFIDENCE,run(g,r).failure().gate());
    }
    @Test public void statusOrRiskInTheBoundedUFieldStillVetoes(){
        Geometry g=geometry();for(String signal:List.of("+5","1/1","√","locked")){
            List<Row> r=rows();r.set(2,row(signal,.398219,new Box(177,786,309,920)));
            assertEquals(signal,FailureGate.TEMPLATE_CONFLICT,run(g,r).failure().gate());
        }
    }
}
