package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Generated rasters and unmodified synthetic native rows only. No device OCR is run. */
public class RemainingCardRepairPlanTest {
    private static final RewardRegionGate.Key KEY=new RewardRegionGate.Key(
            RewardRegionGate.Source.SYNTHETIC_FIXTURE,"repair-plan-synthetic",1,"single-card");
    private static RemainingCardPlanTest.Fixture repair(RemainingCardPlanTest.Fixture old,
            OverviewUiRegions.Role role,int index){
        return RemainingCardPlanTest.prepared(old.gray().clone(),old.argb().clone(),old.width(),old.height(),
                new RemainingCardChoice(role,index,true));
    }
    private static RemainingCardPlanTest.Fixture repairActivity(boolean done){
        return repair(RemainingCardPlanTest.activity(done),OverviewUiRegions.Role.DAILY_ACTIVITIES,3);
    }
    private static RemainingCardPlanTest.Fixture repairWeek(){
        return repair(RemainingCardPlanTest.weekly(),OverviewUiRegions.Role.DAILY_PROGRESS,0);
    }
    private static byte[] shortCard(int divisor){
        int w=1240/divisor,h=352/divisor;byte[] gray=new byte[w*h];Arrays.fill(gray,(byte)247);
        fill(gray,w,14/divisor,12/divisor,1226/divisor,340/divisor,255);
        pill(gray,w,922/divisor,104/divisor,1178/divisor,248/divisor,80);
        fill(gray,w,270/divisor,72/divisor,700/divisor,104/divisor,60);
        return gray;
    }
    private static void fill(byte[] gray,int width,int l,int t,int r,int b,int value){
        for(int y=t;y<b;y++)Arrays.fill(gray,y*width+l,y*width+r,(byte)value);
    }
    private static void pill(byte[] gray,int width,int l,int t,int r,int b,int value){
        double radius=(b-t)/2.0,cy=(t+b)/2.0;
        for(int y=t;y<b;y++)for(int x=l;x<r;x++){
            double cx=Math.max(l+radius,Math.min(r-radius,x+.5));
            if(Math.pow(x+.5-cx,2)+Math.pow(y+.5-cy,2)<=radius*radius)gray[y*width+x]=(byte)value;
        }
    }
    @Test public void singleCardBadgeUsesCardProportionsAcrossTightCropScales(){
        for(int divisor:new int[]{1,2,4}){
            byte[] gray=shortCard(divisor);int width=1240/divisor,height=352/divisor;
            var legacy=RewardRegionLocator.observeVisibleMoreGray8(KEY,gray,width,height,()->false);
            assertEquals(1,legacy.cards().size());assertTrue(legacy.cards().get(0).badges().isEmpty());
            var fixed=RewardRegionLocator.observeSingleCardGray8(KEY,gray,width,height,()->false);
            assertEquals(RewardRegionLocator.Status.OBSERVED,fixed.status());assertEquals(1,fixed.cards().size());
            assertEquals(1,fixed.cards().get(0).badges().size());assertTrue(fixed.cards().get(0).tickMarks().isEmpty());
            var f=RemainingCardPlanTest.prepared(gray,RemainingCardPlanTest.colors(gray),width,height,
                    new RemainingCardChoice(OverviewUiRegions.Role.DAILY_ACTIVITIES,3,true));
            assertTrue(f.plan().reason(),f.plan().admitted());
            var out=f.plan().finish(RemainingCardPlanTest.batches(f,"+10"),()->false);
            assertEquals("PREVIEW_ONLY",out.status());assertEquals(1,out.hypotheticalClicks());assertEquals(0,out.realExecutions());
        }
    }
    @Test public void independentCardModeDoesNotChangeLegacySectionOrViewportObservers(){
        byte[] gray=shortCard(1);
        assertTrue(RewardRegionLocator.observeGray8(KEY,gray,1240,352,()->false).cards().get(0).badges().isEmpty());
        assertTrue(RewardRegionLocator.observeVisibleMoreGray8(KEY,gray,1240,352,()->false).cards().get(0).badges().isEmpty());
        var old=RemainingCardPlanTest.prepared(gray,RemainingCardPlanTest.colors(gray),1240,352,
                new RemainingCardChoice(OverviewUiRegions.Role.DAILY_ACTIVITIES,3));
        assertEquals("BADGE_OR_TICK_AMBIGUOUS",old.plan().reason());
    }
    @Test public void oversizedBadgeAndTwoBadgeCandidatesStillDoNotBecomeAnIndicator(){
        byte[] large=shortCard(1);fill(large,1240,900,90,1200,270,255);pill(large,1240,922,82,1178,270,80);
        var f=RemainingCardPlanTest.prepared(large,RemainingCardPlanTest.colors(large),1240,352,
                new RemainingCardChoice(OverviewUiRegions.Role.DAILY_ACTIVITIES,3,true));
        assertFalse(f.plan().admitted());assertEquals("BADGE_OR_TICK_AMBIGUOUS",f.plan().reason());
        byte[] two=shortCard(1);fill(two,1240,900,90,1200,270,255);
        pill(two,1240,922,40,1178,120,80);pill(two,1240,922,232,1178,312,80);
        var g=RewardRegionLocator.observeSingleCardGray8(KEY,two,1240,352,()->false);
        assertEquals(2,g.cards().get(0).badges().size());
        var ambiguous=RemainingCardPlanTest.prepared(two,RemainingCardPlanTest.colors(two),1240,352,
                new RemainingCardChoice(OverviewUiRegions.Role.DAILY_ACTIVITIES,3,true));
        assertEquals("BADGE_OR_TICK_AMBIGUOUS",ambiguous.plan().reason());
    }
    @Test public void singleCardModeRejectsMultipleCardSurfacesWithoutChoosingOne(){
        byte[] gray=new byte[1240*700];Arrays.fill(gray,(byte)247);
        fill(gray,1240,14,12,1226,340,255);fill(gray,1240,14,360,1226,688,255);
        var g=RewardRegionLocator.observeSingleCardGray8(KEY,gray,1240,700,()->false);
        assertEquals(RewardRegionLocator.Status.INCOMPLETE_CARD,g.status());assertTrue(g.cards().isEmpty());
    }
    @Test public void singleCardObservationKeepsCancellationAndInputOwnership(){
        byte[] gray=shortCard(1),copy=gray.clone();
        RewardRegionLocator.observeSingleCardGray8(KEY,gray,1240,352,()->false);assertArrayEquals(copy,gray);
        assertEquals(RewardRegionLocator.Status.CANCELED,
                RewardRegionLocator.observeSingleCardGray8(KEY,gray,1240,352,()->true).status());
    }
    @Test public void topContextGrowsBeforeQueryWhileBottomAndHorizontalTitleLimitsStayFixed(){
        var old=RemainingCardPlanTest.activity(false);var fixed=repairActivity(false);
        Box previous=old.plan().rois().get(0),query=fixed.plan().rois().get(0);
        assertTrue(query.top()<previous.top());assertEquals(previous.left(),query.left());
        assertEquals(previous.right(),query.right());assertEquals(previous.bottom(),query.bottom());
        assertTrue(fixed.plan().boundary().contains(query));assertFalse(query.overlaps(fixed.plan().rois().get(1)));
    }
    @Test public void titleCanContainPreviouslyTopTouchingNativeBoxWithoutMovingTheBox(){
        var old=RemainingCardPlanTest.activity(false);var f=repairActivity(false);Box b=old.plan().rois().get(0);
        var row=new FrameStructureEvidence.Line("Unmodified synthetic title",b.left()+3,b.top(),b.width()-6,b.height()-4,.95);
        var batches=RemainingCardPlanTest.batches(f,"+10");batches.set(0,List.of(row));
        var out=f.plan().finish(batches,()->false);assertEquals("PREVIEW_ONLY",out.status());
        assertSame(row,batches.get(0).get(0));assertEquals(b.top(),row.y());
    }
    @Test public void titleRowsAtTheNewQueryEdgeAreStillRejected(){
        var f=repairActivity(false);Box b=f.plan().rois().get(0);var rows=RemainingCardPlanTest.batches(f,"+10");
        rows.set(0,List.of(new FrameStructureEvidence.Line("Touches new top",b.left()+3,b.top(),b.width()-6,b.height()-4,.95)));
        assertEquals("ROW_TOUCHES_ROI",f.plan().finish(rows,()->false).reason());
    }
    @Test public void weekNumberQueryAddsContextInsideTheSameObservedPanel(){
        var old=RemainingCardPlanTest.weekly();var f=repairWeek();Box oldQuery=old.plan().rois().get(1),newQuery=f.plan().rois().get(1);
        assertTrue(newQuery.left()<oldQuery.left()&&newQuery.top()<oldQuery.top());
        assertTrue(newQuery.right()>oldQuery.right()&&newQuery.bottom()>oldQuery.bottom());
        assertTrue(f.plan().boundary().contains(newQuery));
        for(int i=0;i<f.plan().rois().size();i++)if(i!=1)assertFalse(newQuery.overlaps(f.plan().rois().get(i)));
        var out=f.plan().finish(RemainingCardPlanTest.weeklyBatches(f),()->false);
        assertEquals("VISUAL_PREVIEW_ONLY",out.status());assertEquals(0,out.hypotheticalClicks());assertEquals(0,out.realExecutions());
    }
    @Test public void wholeNewNumberQueryAndNearWholeEdgeFallbackRemainHolds(){
        for(int trim:new int[]{0,1}){
            var f=repairWeek();Box b=f.plan().rois().get(1);var rows=RemainingCardPlanTest.weeklyBatches(f);
            rows.set(1,List.of(new FrameStructureEvidence.Line("30",b.left(),b.top(),b.width()-trim,b.height()-trim,.99)));
            var out=f.plan().finish(rows,()->false);assertEquals("ROW_TOUCHES_ROI",out.reason());assertEquals(0,out.hypotheticalClicks());
        }
    }
    @Test public void fixedQueryDoesNotChangeLowConfidenceOrRowCountGates(){
        var f=repairWeek();var rows=RemainingCardPlanTest.weeklyBatches(f);Box b=f.plan().rois().get(1);
        rows.set(1,List.of(RemainingCardPlanTest.inside("30",b,.8499)));
        assertEquals("ROI_ROW_SHAPE_OR_CONFIDENCE",f.plan().finish(rows,()->false).reason());
        var other=repairWeek();rows=RemainingCardPlanTest.weeklyBatches(other);
        rows.set(1,List.of(rows.get(1).get(0),rows.get(1).get(0)));
        assertEquals("ROI_ROW_COUNT",other.plan().finish(rows,()->false).reason());
    }
    @Test public void genuineNumberAndTickStillSkipAndPlusNumberCannotOverrideTheTick(){
        var f=repairActivity(true);assertTrue(f.plan().reason(),f.plan().admitted());
        var out=f.plan().finish(RemainingCardPlanTest.batches(f,"10"),()->false);
        assertEquals("ITEM_COMPLETE_SKIP",out.status());assertEquals(0,out.hypotheticalClicks());
        assertNull(out.target());
        var other=repairActivity(true);
        assertEquals("NUMBER_TICK_ASSOCIATION",other.plan().finish(RemainingCardPlanTest.batches(other,"+10"),()->false).reason());
    }
}
