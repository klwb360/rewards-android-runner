package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.RewardCardFixture.*;

public class LiveRewardCardObservationTest {
    @Test public void dynamicallyMapsCropToFullDisplayExactlyOnce(){
        var frame=observe(pixels(false,false),rows(false,false));
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,frame.status());
        assertEquals(new Box(0,209,W,788),frame.sectionBox());assertEquals(1,frame.cards().size());
        var card=frame.cards().get(0);assertEquals(new Box(25,249,509,440),card.cardBox());
        assertEquals(new Box(388,316,490,373),card.indicatorBox());assertEquals(new Box(414,331,465,357),card.numberBox());
        assertEquals("First activity",card.title());assertFalse(card.complete());assertEquals(10,card.displayedPoints());
    }
    @Test public void localNumberAndTickRetainTheSameSemanticTask(){
        var before=observe(pixels(false,false),rows(false,false));var after=observe(pixels(true,false),rows(true,false));
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,after.status());
        assertTrue(after.cards().get(0).complete());assertTrue(before.cards().get(0).sameTask(after.cards().get(0)));
        assertEquals(new Box(465,332,490,357),after.cards().get(0).indicatorBox());
    }
    @Test public void sameTitleOnTwoCardsIsNotTaskIdentity(){
        var r=rows(false,true);r.set(4,line("First activity",55,478,265,25));
        assertEquals(LiveRewardCardObservation.Reason.CARD_IDENTITY_NOT_UNIQUE,observe(pixels(false,true),r).reason());
    }
    @Test public void wrappedOrCompetingTitleRowsStop(){
        var r=rows(false,false);r.add(line("Competing title",55,300,200,15));
        assertEquals(LiveRewardCardObservation.Reason.CARD_IDENTITY_NOT_UNIQUE,observe(pixels(false,false),r).reason());
    }
    @Test public void missingOrLowConfidenceLowerHeadingNeverUsesScreenBottom(){
        var r=rows(false,false);r.remove(r.size()-1);
        assertEquals(LiveRewardCardObservation.Reason.LOWER_BOUNDARY_NOT_UNIQUE,observe(pixels(false,false),r).reason());
        r=rows(false,false);r.set(r.size()-1,new FrameStructureEvidence.Line("更多活动",25,788,110,25,.84));
        assertEquals(LiveRewardCardObservation.Reason.LOWER_BOUNDARY_NOT_UNIQUE,observe(pixels(false,false),r).reason());
    }
    @Test public void pageAggregateCounterCannotStandInForDailyRegion(){
        var r=rows(false,false);r.set(1,line("0/30",388,100,92,25));r.add(line("今日积分",25,100,110,25));
        assertEquals(LiveRewardCardObservation.Reason.COUNTER_NOT_UNIQUE,observe(pixels(false,false),r).reason());
    }
    @Test public void fullNativeLocalBatchCannotDropConflictingOrLowConfidenceRows(){
        var r=rows(false,false);r.add(new FrameStructureEvidence.Line("+5",60,400,40,20,.84));
        assertEquals(LiveRewardCardObservation.Reason.LOCAL_CONFIDENCE,observe(pixels(false,false),r).reason());
        r=rows(false,false);r.add(line("0/20",388,700,92,25));
        assertEquals(LiveRewardCardObservation.Reason.LOCATOR_BLOCKED,observe(pixels(false,false),r).reason());
    }
    @Test public void boundaryCrossingRowsDoNotSilentlyDisappear(){
        var r=rows(false,false);r.add(line("Crosses boundary",60,780,180,20));
        assertEquals(LiveRewardCardObservation.Reason.ROW_CROSSES_SECTION,observe(pixels(false,false),r).reason());
    }
    @Test public void validCompleteRatioSkipsButStillRequiresTaskTickForAnyCardCompletion(){
        var r=rows(false,false);r.set(1,line("30/30",388,210,92,25));
        var frame=observe(pixels(false,false),r);assertEquals(LiveRewardCardObservation.Status.SECTION_COMPLETE_SKIP,frame.status());
        assertTrue(frame.cards().isEmpty());
        r=rows(true,false);r.set(1,line("30/30",388,210,92,25));
        frame=observe(pixels(true,false),r);assertEquals(1,frame.cards().size());assertTrue(frame.cards().get(0).complete());
    }
    @Test public void bodyTextChangesIdentityRatherThanAVisualIndex(){
        var b=rows(false,false);b.add(line("Body anchor",55,390,220,20));
        var a=rows(true,false);a.add(line("Different body",55,390,220,20));
        assertFalse(observe(pixels(false,false),b).cards().get(0).sameTask(observe(pixels(true,false),a).cards().get(0)));
    }
    @Test public void lowOutsideSectionIsNotPromotedToLocalEvidence(){
        var r=rows(false,false);r.add(new FrameStructureEvidence.Line("Unrelated",20,50,100,20,.1));
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,observe(pixels(false,false),r).status());
    }
    @Test public void adapterDoesNotMutatePixelsOrRetainRawBatch(){
        byte[] p=pixels(false,false),copy=p.clone();var rows=new ArrayList<>(rows(false,false));
        var frame=observe(p,rows);rows.clear();assertArrayEquals(copy,p);assertEquals(1,frame.cards().size());
        assertEquals(LiveRewardCardObservation.Reason.CANCELED,
            LiveRewardCardObservation.observe("scope",1,p,W,H,List.of(),()->true).reason());
    }
    @Test public void emptyLocalTextAndExplicitSensitiveOrLockedStatesHold(){
        var r=rows(false,false);r.add(line("",55,390,200,20));
        assertEquals(LiveRewardCardObservation.Reason.LOCAL_TEXT_UNKNOWN,observe(pixels(false,false),r).reason());
        for(String state:List.of("锁定","登录","订阅","付款","隐私授权","subscription","privacy consent")) {
            r=rows(false,false);r.add(line(state,55,390,200,20));
            assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),r).status());
        }
    }}

