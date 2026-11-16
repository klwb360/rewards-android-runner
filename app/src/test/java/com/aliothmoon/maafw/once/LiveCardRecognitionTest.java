package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Synthetic pixels/native-shaped rows; no Android, live screenshot or OCR model is executed. */
public class LiveCardRecognitionTest {
    private static RemainingCardPlanTest.Fixture prepared(RemainingCardPlanTest.Fixture source,CardRecognitionSpec spec){
        return prepared(source.gray().clone(),source.argb().clone(),source.width(),source.height(),spec);
    }
    private static RemainingCardPlanTest.Fixture prepared(byte[] gray,int[] color,int width,int height,CardRecognitionSpec spec){
        Object owner=new Object();byte[] fake=new byte[57];Arrays.fill(fake,(byte)29);
        var receipt=CardNativeMockReceipt.seal(owner,CardNativeMockReceipt.digest(fake),width,height);assertTrue(receipt.claim(owner,fake,width,height));
        var plan=RemainingCardPlan.prepareSpec(gray,color,width,height,receipt,owner,spec,()->false);
        return new RemainingCardPlanTest.Fixture(gray,color,width,height,owner,receipt,plan);
    }
    private static List<List<FrameStructureEvidence.Line>> checkRows(RemainingCardPlanTest.Fixture f,String header){
        var rows=new ArrayList<List<FrameStructureEvidence.Line>>();
        for(int i=0;i<f.plan().roles().size();i++){
            var role=f.plan().roles().get(i);String value=role==RemainingCardPlan.Role.CHECK_IN_TITLE?header:
                    role==RemainingCardPlan.Role.NUMBER?"10":"Day "+(role.ordinal()-RemainingCardPlan.Role.DAY1.ordinal()+1);
            rows.add(List.of(RemainingCardPlanTest.inside(value,f.plan().rois().get(i),.96)));
        }return rows;
    }
    @Test public void everyDailyAndMoreSlotUsesTheSameRefinedNativeRecipeWithoutExpandingOldMenus(){
        for(var role:List.of(OverviewUiRegions.Role.DAILY_ACTIVITIES,OverviewUiRegions.Role.MORE_ACTIVITIES))for(int slot=1;slot<=8;slot++)for(int tone:new int[]{80,246}){
            var original=RemainingCardPlanTest.activity(false);byte[] gray=original.gray().clone();
            for(int pixel=0;pixel<gray.length;pixel++)if((gray[pixel]&255)==80)gray[pixel]=(byte)tone;
            var f=prepared(gray,RemainingCardPlanTest.colors(gray),original.width(),original.height(),CardRecognitionSpec.activity(role,slot));
            assertTrue("slot="+slot+" tone="+tone+" "+f.plan().reason(),f.plan().admitted());
            assertNull(f.plan().choice());assertTrue(f.plan().spec().refined());assertEquals(slot,f.plan().spec().index());
            var out=f.plan().finish(RemainingCardPlanTest.batches(f,"+10"),()->false);
            assertEquals("PREVIEW_ONLY",out.status());assertEquals(1,out.hypotheticalClicks());assertEquals(0,out.realExecutions());
        }
        assertThrows(IllegalArgumentException.class,()->new RemainingCardChoice(OverviewUiRegions.Role.MORE_ACTIVITIES,2,true));
        assertThrows(IllegalArgumentException.class,()->new RemainingCardChoice(OverviewUiRegions.Role.DAILY_ACTIVITIES,8,true));
    }
    @Test public void sharedRecipeKeepsNumericTickSkipAndRawBoxStrictnessForMoreSlotEight(){
        var done=prepared(RemainingCardPlanTest.activity(true),CardRecognitionSpec.activity(OverviewUiRegions.Role.MORE_ACTIVITIES,8));
        assertEquals("ITEM_COMPLETE_SKIP",done.plan().finish(RemainingCardPlanTest.batches(done,"10"),()->false).status());
        var pending=prepared(RemainingCardPlanTest.activity(false),CardRecognitionSpec.activity(OverviewUiRegions.Role.MORE_ACTIVITIES,8));
        var rows=RemainingCardPlanTest.batches(pending,"+10");Box roi=pending.plan().rois().get(1);
        var nativeRow=new FrameStructureEvidence.Line("+10",roi.left(),roi.top()+2,roi.width()-4,roi.height()-4,.99);rows.set(1,List.of(nativeRow));
        assertEquals("ROW_TOUCHES_ROI",pending.plan().finish(rows,()->false).reason());assertSame(nativeRow,rows.get(1).get(0));
    }
    @Test public void checkInRequiresAnExactCheckInHeaderAndKeepsTheWeeklyProgressReadOnly(){
        for(String title:List.of("签入","签到")){
            var f=prepared(RemainingCardPlanTest.weekly(),CardRecognitionSpec.checkIn(0));assertTrue(f.plan().reason(),f.plan().admitted());
            assertEquals(4,f.plan().selectedDay());assertEquals(9,f.plan().rois().size());
            var out=f.plan().finish(checkRows(f,title),()->false);assertEquals("PREVIEW_ONLY",out.status());assertEquals(4,out.day());assertEquals(0,out.realExecutions());
        }
        var wrong=prepared(RemainingCardPlanTest.weekly(),CardRecognitionSpec.checkIn(0));
        assertEquals("ROI_TOKEN_CLASS",wrong.plan().finish(checkRows(wrong,"每日活动"),()->false).reason());
        var old=RemainingCardPlanTest.weekly();assertEquals("VISUAL_PREVIEW_ONLY",old.plan().finish(RemainingCardPlanTest.weeklyBatches(old),()->false).status());
    }
    @Test public void changedCurrentDayDoesNotRetargetAnUncompletedOldCheckIn(){
        var f=prepared(RemainingCardPlanTest.weekly(),CardRecognitionSpec.checkIn(5));
        assertFalse(f.plan().admitted());assertEquals("SELECTED_DAY_NOT_CURRENT",f.plan().reason());
    }
    @Test public void anObservedTickForTheSameRequestedDaySkipsWithoutInventingANumericTarget(){
        var f=prepared(RemainingCardPlanTest.weekly(),CardRecognitionSpec.checkIn(3));
        assertTrue(f.plan().reason(),f.plan().admitted());assertEquals(8,f.plan().rois().size());assertEquals(3,f.plan().selectedDay());
        var out=f.plan().finish(checkRows(f,"签入"),()->false);
        assertEquals("CHECK_IN_COMPLETE_SKIP",out.status());assertEquals(3,out.day());assertNull(out.target());assertEquals(0,out.hypotheticalClicks());
    }
    @Test public void taskTitleHashComesFromAllOriginalSortedRowsWithoutPersistingTheirText(){
        var f=prepared(RemainingCardPlanTest.activity(false),CardRecognitionSpec.activity(OverviewUiRegions.Role.MORE_ACTIVITIES,2));
        Box b=f.plan().rois().get(0);int half=(int)b.centerY();var rows=RemainingCardPlanTest.batches(f,"+10");
        var upper=new FrameStructureEvidence.Line("New title",b.left()+3,b.top()+2,b.width()-6,half-b.top()-3,.95);
        var lower=new FrameStructureEvidence.Line("for today",b.left()+3,half+1,b.width()-6,b.bottom()-half-3,.96);rows.set(0,List.of(lower,upper));
        assertEquals("PREVIEW_ONLY",f.plan().finish(rows,()->false).status());assertEquals(LiveCardIdentity.hashText("New title for today"),f.plan().titleHash());
        assertSame(lower,rows.get(0).get(0));assertSame(upper,rows.get(0).get(1));
    }
}
