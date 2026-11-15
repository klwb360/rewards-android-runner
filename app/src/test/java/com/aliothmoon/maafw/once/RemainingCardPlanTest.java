package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic raster and native-shaped rows only; no Android or OCR model is executed. */
public class RemainingCardPlanTest {
    record Fixture(byte[] gray,int[] argb,int width,int height,Object owner,CardNativeMockReceipt receipt,RemainingCardPlan.Plan plan) { }
    static Fixture prepared(byte[] gray,int[] argb,int w,int h,RemainingCardChoice choice){
        Object owner=new Object();byte[] fake=new byte[57];Arrays.fill(fake,(byte)31);
        var receipt=CardNativeMockReceipt.seal(owner,CardNativeMockReceipt.digest(fake),w,h);
        assertTrue(receipt.claim(owner,fake,w,h));
        var plan=RemainingCardPlan.prepare(gray,argb,w,h,receipt,owner,choice,()->false);
        return new Fixture(gray,argb,w,h,owner,receipt,plan);
    }
    static Fixture activity(boolean completed){
        var fixture=new RewardRegionLocatorTest();byte[] source=completed?fixture.tickPixels(true,false):fixture.pixels();
        int left=10,top=completed?465:130,w=509,h=completed?97:220;
        fixture.fill(source,completed?55:140,completed?495:175,320,completed?520:195,60);
        byte[] gray=new byte[w*h];for(int y=0;y<h;y++)System.arraycopy(source,(top+y)*RewardRegionLocatorTest.W+left,gray,y*w,w);
        int[] argb=colors(gray);return prepared(gray,argb,w,h,new RemainingCardChoice(OverviewUiRegions.Role.MORE_ACTIVITIES,1));
    }
    static int[] colors(byte[] gray){int[] out=new int[gray.length];for(int i=0;i<out.length;i++){int g=gray[i]&255;out[i]=0xff000000|(g<<16)|(g<<8)|g;}return out;}
    static FrameStructureEvidence.Line inside(String text,Box b,double score){
        return new FrameStructureEvidence.Line(text,b.left()+3,b.top()+3,b.width()-6,b.height()-6,score);
    }
    static List<List<FrameStructureEvidence.Line>> batches(Fixture f,String points){
        var out=new ArrayList<List<FrameStructureEvidence.Line>>();
        out.add(List.of(inside("Synthetic task title",f.plan().rois().get(0),.96)));
        out.add(List.of(inside(points,f.plan().rois().get(1),.95)));return out;
    }
    static Fixture weekly(){
        int w=DailyCheckInRegionTest.W,h=DailyCheckInRegionTest.H;byte[] gray=DailyCheckInRegionTest.pixels();int[] argb=new int[gray.length];
        for(int i=0;i<gray.length;i++){int g=gray[i]&255;argb[i]=g==190?0xffffaa14:g==200?0xffffc33c:0xff000000|(g<<16)|(g<<8)|g;}
        disk(argb,w,272,115,21,0xffffd778);for(int day=4;day<7;day++)disk(argb,w,62+70*day,115,20,0xffffd778);
        for(int y=109;y<122;y++)for(int x=264;x<280;x++)if(x<268||x>=274)argb[y*w+x]=0xff5a5a5a;
        for(int i=0;i<gray.length;i++){int c=argb[i];gray[i]=(byte)((77*((c>>>16)&255)+150*((c>>>8)&255)+29*(c&255)+128)>>>8);}
        return prepared(gray,argb,w,h,new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0));
    }
    private static void disk(int[] p,int width,int cx,int cy,int radius,int color){for(int y=cy-radius;y<=cy+radius;y++)for(int x=cx-radius;x<=cx+radius;x++)if((x-cx)*(x-cx)+(y-cy)*(y-cy)<=radius*radius)p[y*width+x]=color;}
    static List<List<FrameStructureEvidence.Line>> weeklyBatches(Fixture f){
        List<List<FrameStructureEvidence.Line>> out=new ArrayList<>();
        for(int i=0;i<9;i++)out.add(List.of(inside(i==0?"每日活动":i==1?"30":"Day "+(i-1),f.plan().rois().get(i),.96)));
        return out;
    }
    @Test public void activityRequiresExactlyTwoBoundedNonoverlappingRois(){
        var f=activity(false);var p=f.plan();assertTrue(p.reason(),p.admitted());assertEquals(2,p.rois().size());
        assertEquals(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,p.source());assertFalse(p.rois().get(0).overlaps(p.rois().get(1)));
        for(var roi:p.rois()){assertTrue(p.boundary().contains(roi));assertNotEquals(new Box(0,0,f.width(),f.height()),roi);}
    }
    @Test public void pendingPlusNumberCreatesOneFakeReceiptAndNoRealExecution(){
        var f=activity(false);var result=f.plan().finish(batches(f,"+10"),()->false);
        assertEquals(result.reason(),"PREVIEW_ONLY",result.status());assertEquals(1,result.hypotheticalClicks());assertEquals(0,result.realExecutions());
        assertEquals(10,result.displayedPoints());assertTrue(result.target().contains(new Box(result.x(),result.y(),result.x()+1,result.y()+1)));
        assertEquals("ALREADY_CONSUMED",f.plan().finish(batches(f,"+10"),()->false).reason());
    }
    @Test public void completedNumberAndGeometricTickSkipWithoutAPoint(){
        var f=activity(true);assertTrue(f.plan().reason(),f.plan().admitted());var result=f.plan().finish(batches(f,"10"),()->false);
        assertEquals(result.reason(),"ITEM_COMPLETE_SKIP",result.status());assertEquals(0,result.hypotheticalClicks());assertEquals(0,result.realExecutions());assertNull(result.target());
    }
    @Test public void completedPointsRoiNeverContainsTheTickDiskOrItsCurvedEdge(){
        var f=activity(true);var key=new RewardRegionGate.Key(RewardRegionGate.Source.SYNTHETIC_FIXTURE,"synthetic-only",1,"done-card");
        var g=com.aliothmoon.maafw.rewards.RewardRegionLocator.observeVisibleMoreGray8(key,f.gray(),f.width(),f.height(),()->false);
        var disk=g.cards().get(0).tickMarks().get(0).diskBox();var roi=f.plan().rois().get(1);
        assertTrue(roi.right()<=disk.left()-2);assertFalse(roi.overlaps(disk));
        assertEquals("ITEM_COMPLETE_SKIP",f.plan().finish(batches(f,"10"),()->false).status());
    }
    @Test public void plainNumberCannotTurnAPendingBadgeIntoACandidate(){
        var f=activity(false);assertEquals("POINTS_NOT_PLUS",f.plan().finish(batches(f,"100"),()->false).reason());
    }
    @Test public void plusNumberCannotTurnAGeometricTickIntoAPendingCandidate(){
        var f=activity(true);assertEquals("NUMBER_TICK_ASSOCIATION",f.plan().finish(batches(f,"+10"),()->false).reason());
    }
    @Test public void zeroMultipleAndUnavailablePointsRemainDifferentFailures(){
        for(int n=0;n<3;n++){
            var f=activity(false);var rows=batches(f,"+5");rows.set(1,n==0?List.of():n==1?List.of(rows.get(1).get(0),rows.get(1).get(0)):null);
            var r=f.plan().finish(rows,()->false);assertEquals(n==2?"ROI_ROWS_UNAVAILABLE":"ROI_ROW_COUNT",r.reason());assertEquals(0,r.hypotheticalClicks());
        }
    }
    @Test public void lowScoresAndShiftedNativeBoxesCannotBecomeAPoint(){
        for(double score:new double[]{.849,Double.NaN,1.01}){
            var f=activity(false);var rows=batches(f,"+5");rows.set(1,List.of(inside("+5",f.plan().rois().get(1),score)));
            assertEquals("ROI_ROW_SHAPE_OR_CONFIDENCE",f.plan().finish(rows,()->false).reason());
        }
        var f=activity(false);var rows=batches(f,"+5");var r=rows.get(1).get(0);rows.set(1,List.of(new FrameStructureEvidence.Line(r.text(),r.x()+20,r.y(),r.width(),r.height(),r.score())));
        assertEquals("ROW_OUTSIDE_OWN_ROI",f.plan().finish(rows,()->false).reason());
    }
    @Test public void nativeRowsTouchingTheSearchRoiAreRejected(){
        var f=activity(false);var rows=batches(f,"+5");var b=f.plan().rois().get(1);rows.set(1,List.of(new FrameStructureEvidence.Line("+5",b.left(),b.top()+2,b.width()-4,b.height()-4,.95)));
        assertEquals("ROW_TOUCHES_ROI",f.plan().finish(rows,()->false).reason());
    }
    @Test public void titleCanUseTwoOriginalLinesWithoutCombiningThem(){
        var f=activity(false);var rows=batches(f,"+5");var b=f.plan().rois().get(0);int middle=(int)b.centerY();
        rows.set(0,List.of(new FrameStructureEvidence.Line("First title",b.left()+3,b.top()+2,b.width()-6,middle-b.top()-3,.95),
                new FrameStructureEvidence.Line("Second title",b.left()+3,middle+1,b.width()-6,b.bottom()-middle-3,.94)));
        assertEquals("PREVIEW_ONLY",f.plan().finish(rows,()->false).status());
    }
    @Test public void titleWithFourRowsCannotBeSelectedByDroppingRows(){
        var f=activity(false);var rows=batches(f,"+5");var row=rows.get(0).get(0);rows.set(0,List.of(row,row,row,row));
        assertEquals("ROI_ROW_COUNT",f.plan().finish(rows,()->false).reason());
    }
    @Test public void sameOwnerReceiptMustRemainCurrentAfterTheLastRowIsRead(){
        var f=activity(false);var original=batches(f,"+5");List<List<FrameStructureEvidence.Line>> rows=new java.util.AbstractList<>(){
            public int size(){return 2;}public List<FrameStructureEvidence.Line> get(int i){if(i==1)f.receipt().cancel();return original.get(i);}};
        assertEquals("CANCELED",f.plan().finish(rows,()->false).reason());
    }
    @Test public void cancellationRevokesAlreadyPreparedPlansAndCannotMakeNewPlans(){
        var f=activity(false);f.receipt().cancel();assertFalse(f.plan().admitted());assertEquals(0,f.plan().finish(batches(f,"+5"),()->false).hypotheticalClicks());
        assertFalse(RemainingCardPlan.prepare(f.gray(),f.argb(),f.width(),f.height(),f.receipt(),f.owner(),f.plan().choice(),()->false).admitted());
    }
    @Test public void grayFrameBindingRejectsDifferentPixelsAndDoesNotMutateInput(){
        var f=activity(false);byte[] original=f.gray().clone();assertTrue(f.plan().matchesGray8(f.gray(),f.width(),f.height(),()->false));assertArrayEquals(original,f.gray());
        f.gray()[0]^=1;assertFalse(f.plan().matchesGray8(f.gray(),f.width(),f.height(),()->false));
    }
    @Test public void contradictoryCompletionOrLockTextCannotBecomeAPendingPoint(){
        for(String title:List.of("已完成","Task completed","Tomorrow only","Locked task")){
            var f=activity(false);var rows=batches(f,"+5");rows.set(0,List.of(inside(title,f.plan().rois().get(0),.95)));
            assertEquals("STATE_CONFLICT",f.plan().finish(rows,()->false).reason());
        }
    }
    @Test public void oneLetterTitleIsNotEnoughIdentityEvidence(){
        var f=activity(false);var rows=batches(f,"+5");rows.set(0,List.of(inside("x",f.plan().rois().get(0),.95)));
        assertEquals("ROI_TOKEN_CLASS",f.plan().finish(rows,()->false).reason());
    }
    @Test public void sourceReceiptCannotCreateTwoPlans(){
        var f=activity(false);assertFalse(RemainingCardPlan.prepare(f.gray(),f.argb(),f.width(),f.height(),f.receipt(),f.owner(),f.plan().choice(),()->false).admitted());
    }
    @Test public void weeklyTitleAndSevenDaysStayDistinctFromCheckInAndHaveNoFakeClick(){
        var f=weekly();assertTrue(f.plan().reason(),f.plan().admitted());assertEquals(9,f.plan().rois().size());
        var out=f.plan().finish(weeklyBatches(f),()->false);assertEquals("VISUAL_PREVIEW_ONLY",out.status());assertEquals(0,out.hypotheticalClicks());assertEquals(0,out.realExecutions());
        assertEquals(30,out.displayedPoints());assertEquals(-1,out.day());
    }
    @Test public void signInTitleCannotBeSubstitutedForTheWeeklyActivityTitle(){
        var f=weekly();var rows=weeklyBatches(f);var row=rows.get(0).get(0);rows.set(0,List.of(new FrameStructureEvidence.Line("签入",row.x(),row.y(),row.width(),row.height(),row.score())));
        assertEquals("ROI_TOKEN_CLASS",f.plan().finish(rows,()->false).reason());
    }
    @Test public void weeklyMissingAndExtraNativeRowsCannotMintAPoint(){
        var f=weekly();var rows=weeklyBatches(f);rows.set(4,List.of());assertEquals("ROI_ROW_COUNT",f.plan().finish(rows,()->false).reason());
    }
}
