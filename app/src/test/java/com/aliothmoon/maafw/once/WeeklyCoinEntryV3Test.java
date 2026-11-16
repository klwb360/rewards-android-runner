package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fixed entry identities and generated native-shaped rows; never live input. */
public class WeeklyCoinEntryV3Test {
    private static RemainingCardChoice v3(){return new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0,true,true);}
    private static RemainingCardPlanTest.Fixture prepared(){
        var f=RemainingCardPlanTest.weekly();
        return RemainingCardPlanTest.prepared(f.gray().clone(),f.argb().clone(),f.width(),f.height(),v3());
    }
    @Test public void v3HasADistinctDirectoryProfileAndVisibleConfirmation(){
        var v1=new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0);
        var v2=new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0,true);
        var v3=v3();assertFalse(v1.repair());assertFalse(v1.coinRepair());assertFalse(v2.coinRepair());
        assertEquals("rewards-remaining-cards-v1-daily_progress-0",v1.directory());
        assertEquals("REMAINING_CARDS_V1",v1.profile());
        assertEquals("rewards-card-repair-v2-daily_progress-0",v2.directory());assertEquals("CARD_REPAIR_V2",v2.profile());
        assertEquals("rewards-weekly-coins-v3-daily_progress-0",v3.directory());assertEquals("WEEKLY_COINS_V3",v3.profile());
        assertEquals("修复 V3 · 每日活动进度卡（只读标记）",v3.label());
        assertEquals("打开七日硬币修复 V3（尚不采集）",RemainingCardsUi.OPEN_COINS);
        assertEquals("确认七日硬币修复模拟",RemainingCardsUi.RUN_COINS);
        assertNotEquals(v1,v3);assertNotEquals(v2,v3);assertEquals(v1.key(),v3.key());
    }
    @Test public void coinRepairCannotNameAnotherTaskOrSkipTheV2RepairGates(){
        for(var role:new OverviewUiRegions.Role[]{OverviewUiRegions.Role.DAILY_ACTIVITIES,OverviewUiRegions.Role.MORE_ACTIVITIES}){
            try{new RemainingCardChoice(role,1,true,true);fail("Only weekly is admitted");}catch(IllegalArgumentException expected){}
        }
        try{new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0,false,true);fail("Repair gates required");}
        catch(IllegalArgumentException expected){}
    }
    @Test public void v3RetainsTheV2QueryBoxesWhenNoConnectorNeedsSeparation(){
        var source=RemainingCardPlanTest.weekly();var v2=RemainingCardPlanTest.prepared(source.gray().clone(),source.argb().clone(),
                source.width(),source.height(),new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0,true));
        var v3=prepared();assertTrue(v3.plan().reason(),v3.plan().admitted());assertEquals(v2.plan().rois(),v3.plan().rois());
        var out=v3.plan().finish(RemainingCardPlanTest.weeklyBatches(v3),()->false);
        assertEquals("VISUAL_PREVIEW_ONLY",out.status());assertEquals(0,out.hypotheticalClicks());assertEquals(0,out.realExecutions());
    }
    @Test public void wholeNewQueryFallbackStillFailsStrictContainment(){
        for(int trim:new int[]{0,1}){
            var f=prepared();Box b=f.plan().rois().get(1);var rows=RemainingCardPlanTest.weeklyBatches(f);
            rows.set(1,List.of(new FrameStructureEvidence.Line("30",b.left(),b.top(),b.width()-trim,b.height()-trim,.99)));
            var result=f.plan().finish(rows,()->false);assertEquals("ROW_TOUCHES_ROI",result.reason());
            assertEquals(0,result.hypotheticalClicks());assertEquals(0,result.realExecutions());
        }
    }
    @Test public void additionalColumnSupportDoesNotWeakenConfidenceOrOwnerBinding(){
        var f=prepared();var rows=RemainingCardPlanTest.weeklyBatches(f);Box b=f.plan().rois().get(1);
        rows.set(1,List.of(RemainingCardPlanTest.inside("30",b,.849)));
        assertEquals("ROI_ROW_SHAPE_OR_CONFIDENCE",f.plan().finish(rows,()->false).reason());
        var canceled=prepared();canceled.receipt().cancel();assertFalse(canceled.plan().admitted());
        assertEquals("CANCELED",canceled.plan().finish(RemainingCardPlanTest.weeklyBatches(canceled),()->false).reason());
    }
}
