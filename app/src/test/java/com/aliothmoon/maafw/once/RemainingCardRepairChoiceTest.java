package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import org.junit.Test;

public class RemainingCardRepairChoiceTest {
    @Test public void originalChoicesKeepTheirHistoricalNamesAndProfile(){
        var weekly=new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0);
        assertEquals("rewards-remaining-cards-v1-daily_progress-0",weekly.directory());
        assertEquals("REMAINING_CARDS_V1",weekly.profile());
        assertEquals("每日活动进度卡（只读标记）",weekly.label());
        assertFalse(weekly.repair());
        for(var role:new OverviewUiRegions.Role[]{OverviewUiRegions.Role.DAILY_ACTIVITIES,OverviewUiRegions.Role.MORE_ACTIVITIES}){
            for(int i=1;i<=8;i++)assertFalse(new RemainingCardChoice(role,i).repair());
        }
    }
    @Test public void repairChoiceCannotConsumeAHistoricalChoiceNamespace(){
        for(var original:new RemainingCardChoice[]{
                new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0),
                new RemainingCardChoice(OverviewUiRegions.Role.DAILY_ACTIVITIES,1),
                new RemainingCardChoice(OverviewUiRegions.Role.DAILY_ACTIVITIES,3),
                new RemainingCardChoice(OverviewUiRegions.Role.MORE_ACTIVITIES,1)}){
            var repair=new RemainingCardChoice(original.role(),original.index(),true);
            assertNotEquals(original,repair);
            assertNotEquals(original.directory(),repair.directory());
            assertTrue(repair.directory().startsWith("rewards-card-repair-v2-"));
            assertEquals(original.key(),repair.key());
            assertEquals("CARD_REPAIR_V2",repair.profile());
            assertEquals("修复 V2 · "+original.label(),repair.label());
        }
    }
    @Test public void repairMenuDoesNotAuthorizeAdditionalCardSlots(){
        for(var role:new OverviewUiRegions.Role[]{OverviewUiRegions.Role.DAILY_ACTIVITIES,OverviewUiRegions.Role.MORE_ACTIVITIES}){
            for(int i=1;i<=8;i++){
                boolean allowed=role==OverviewUiRegions.Role.DAILY_ACTIVITIES?(i==1||i==3):i==1;
                if(allowed)continue;
                try{new RemainingCardChoice(role,i,true);fail("Unexpected repair choice");}
                catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}
            }
        }
    }
}
