package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.MoreCardFixture.*;

/** Synthetic observations only. Actual Quote title/shape and native counter remain unverified. */
public class MoreVisibleCardObservationTest {
    static void replace(List<FrameStructureEvidence.Line> rows,String old,String text,double score){
        for(int i=0;i<rows.size();i++){var r=rows.get(i);if(r.text().equals(old)){
            rows.set(i,new FrameStructureEvidence.Line(text,r.x(),r.y(),r.width(),r.height(),score));return;}}
        throw new AssertionError("Missing synthetic row "+old);
    }
    @Test public void explicitMoreRouteUsesOneUniqueQuoteDespiteRepeatedLowClippedSiblings(){
        var f=observe(pixels(false),rows(false));
        assertEquals(LiveRewardCardObservation.Route.MORE_VISIBLE_ITEM,f.route());
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,f.status());assertEquals(1,f.cards().size());
        var c=f.cards().get(0);assertEquals("Quote of the day",c.title());assertEquals(5,c.displayedPoints());assertFalse(c.complete());
        assertEquals(new Box(25,605,509,766),c.cardBox());assertEquals(new Box(388,657,490,714),c.indicatorBox());
        assertEquals(new Box(414,672,465,698),c.numberBox());assertEquals(new Box(0,209,W,H),f.sectionBox());
        assertEquals(5,f.fraction().numerator());assertEquals(20,f.fraction().denominator());
    }
    @Test public void fullQuoteNumberAndTickPreserveTitleBodyIdentity(){
        var b=observe(pixels(false),rows(false));var a=observe(pixels(true),rows(true));
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,a.status());assertTrue(a.cards().get(0).complete());
        assertTrue(b.cards().get(0).sameTask(a.cards().get(0)));assertEquals(new Box(465,674,490,699),a.cards().get(0).indicatorBox());
    }
    @Test public void nativeMissingCounterDoesNotBecomeNoCounter(){
        var r=rows(false);r.removeIf(row->row.text().equals("5/20"));
        assertEquals(LiveRewardCardObservation.Reason.COUNTER_NOT_UNIQUE,observe(pixels(false),r).reason());
    }
    @Test public void malformedLowOrSplitHeaderCounterMustHold(){
        for(String text:List.of("5/?","5/0","21/20","5","?")){
            var r=rows(false);replace(r,"5/20",text,.95);assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),r).status());
        }
        var r=rows(false);replace(r,"5/20","5/20",.84);
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),r).status());
        r=rows(false);r.add(line("1/2",388,237,80,10));
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),r).status());
    }
    @Test public void shiftedCounterOrParentAggregateCannotSupplyMoreFraction(){
        var r=rows(false);r.removeIf(row->row.text().equals("5/20"));r.add(line("5/20",200,210,92,25));
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),r).status());
        r=rows(false);r.removeIf(row->row.text().equals("5/20"));r.add(line("5/20",388,100,92,25));
        assertEquals(LiveRewardCardObservation.Reason.COUNTER_NOT_UNIQUE,observe(pixels(false),r).reason());
    }
    @Test public void genuineCompleteHeaderFractionSkipsWithoutUsingCardPlus(){
        var r=rows(false);replace(r,"5/20","20/20",.95);var f=observe(pixels(false),r);
        assertEquals(LiveRewardCardObservation.Status.SECTION_COMPLETE_SKIP,f.status());assertTrue(f.cards().isEmpty());
        assertEquals(20,f.fraction().numerator());
    }
    @Test public void fullFrameLowDuplicateTargetIsStillAmbiguous(){
        var r=rows(false);r.add(new FrameStructureEvidence.Line("Quote of the day",55,50,265,25,.2));
        assertEquals(LiveRewardCardObservation.Reason.TARGET_TITLE_NOT_UNIQUE,observe(pixels(false),r).reason());
    }
    @Test public void lowTargetOrHeaderNeverDisappearsIntoAbsence(){
        var r=rows(false);replace(r,"Quote of the day","Quote of the day",.84);
        assertEquals(LiveRewardCardObservation.Reason.TARGET_TITLE_NOT_UNIQUE,observe(pixels(false),r).reason());
        r=rows(false);replace(r,"更多活动","更多活动",.84);
        assertEquals(LiveRewardCardObservation.Reason.MORE_HEADING_NOT_UNIQUE,observe(pixels(false),r).reason());
    }
    @Test public void localUnknownEmptyAndScopeOutsideObligationsHold(){
        for(String text:List.of("","购买","充值","兑换","下单","安装","下载","purchase","redeem","checkout","install","privacy consent")){
            var r=rows(false);r.add(line(text,55,730,265,20));assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),r).status());
        }
        var r=rows(false);r.add(new FrameStructureEvidence.Line("Unknown",55,730,265,20,.4));
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),r).status());
    }
    @Test public void targetCardMustBeWholeEvenThoughSiblingsMayBeClipped(){
        byte[] p=pixels(false);RewardCardFixture.fill(p,25,766,509,H,255);
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(p,rows(false)).status());
    }
    @Test public void noFallbackToTriviaOrDifferentPoints(){
        var r=rows(false);replace(r,"Quote of the day","Other task",.95);
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),r).status());
        r=rows(false);replace(r,"+5","+10",.95); // first trivia change is irrelevant
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,observe(pixels(false),r).status());
        r=rows(false);for(int i=0;i<r.size();i++)if(r.get(i).text().equals("+5")&&r.get(i).y()>600){var row=r.get(i);r.set(i,line("+10",row.x(),row.y(),row.width(),row.height()));}
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),r).status());
    }
    @Test public void changedBodyChangesTaskIdentity(){
        var r=rows(true);replace(r,"Start your day with a quote","Different body",.95);
        assertFalse(observe(pixels(false),rows(false)).cards().get(0).sameTask(observe(pixels(true),r).cards().get(0)));
    }
    @Test public void dailyRouteDoesNotSilentlyBroadenToMore(){
        assertEquals(LiveRewardCardObservation.Status.HOLD,
            LiveRewardCardObservation.observe("scope",1,pixels(false),W,H,rows(false),()->false).status());
    }
    @Test public void newMoreCropNeverMutatesTheOwnedFrame(){
        byte[] p=pixels(false),original=p.clone();var r=new ArrayList<>(rows(false));var f=observe(p,r);r.clear();
        assertArrayEquals(original,p);assertEquals(1,f.cards().size());
        assertEquals(LiveRewardCardObservation.Reason.CANCELED,LiveRewardCardObservation.observe("scope",1,p,W,H,rows(false),()->true,
            LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay()).reason());
    }
    @Test public void boundedDiagnosticsDescribeActualNativeEvidenceWithoutText(){
        var f=observe(pixels(false),rows(false));var d=f.diagnostic();
        assertNotNull(d);assertEquals(rows(false).size(),d.nativeRows());assertEquals(1,d.headerCount());assertEquals(1,d.targetTitleCount());
        assertEquals(1,d.counterCount());assertEquals(.95,d.counterConfidence(),0);assertEquals(1,d.targetCardMatches());assertTrue(d.targetCardFullyVisible());
        assertEquals(new Box(25,605,509,766),d.targetCard());assertEquals(new Box(388,657,490,714),d.indicator());
        assertFalse(d.summary().contains("Quote"));assertFalse(d.summary().contains("Start your day"));assertTrue(d.summary().contains("5/20"));
    }
    @Test public void counterCvAndAssociationFailuresHaveDifferentBoundedReceipts(){
        var r=rows(false);replace(r,"5/20","5/20",.84);var f=observe(pixels(false),r);
        assertEquals(LiveRewardCardObservation.Reason.BAD_PROGRESS,f.reason());assertEquals(1,f.diagnostic().counterCount());assertEquals(.84,f.diagnostic().counterConfidence(),0);
        byte[] noCards=new byte[W*H];java.util.Arrays.fill(noCards,(byte)247);f=observe(noCards,rows(false));
        assertEquals(LiveRewardCardObservation.Reason.MORE_GEOMETRY_BLOCKED,f.reason());assertEquals(0,f.diagnostic().visualCards());assertEquals(1,f.diagnostic().counterCount());
        r=rows(false);for(int i=0;i<r.size();i++)if(r.get(i).text().equals("+5")&&r.get(i).y()>600){var row=r.get(i);r.set(i,line("5",row.x(),row.y(),row.width(),row.height()));}
        f=observe(pixels(false),r);assertEquals(LiveRewardCardObservation.Reason.MORE_ASSOCIATION_BLOCKED,f.reason());
        assertEquals(com.aliothmoon.maafw.rewards.RewardRegionLocator.VisibleItemStatus.HOLD,f.diagnostic().association());assertEquals(5,f.diagnostic().fraction().numerator());
    }}

