package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Row;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Origin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.OverviewFrameResult.Status;
import static com.aliothmoon.maafw.once.OverviewFrameResult.Reason;
import static com.aliothmoon.maafw.once.OverviewUiRegions.Role;

/** Synthetic raster/OCR inputs only; STATIC_OCR is a declared format, not real provenance. */
public class OverviewRegionRecognizerTest {
    static final int W=523,H=688;
    static Row row(String text,int l,int t,int r,int b) { return new Row(text,new Box(l,t,r,b),.99,Origin.STATIC_OCR); }
    static byte[] activityPixels() { return new RewardRegionLocatorTest().pixels(); }
    static List<Row> activityRows() { return new ArrayList<>(List.of(
            row("更多活动",25,110,135,135),row("0/20",388,110,480,135),
            row("A synthetic activity",55,170,320,195),row("+5",414,231,465,257))); }
    static OverviewUiRegions.Region activityRegion() {
        return new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,new Box(10,95,519,355),
                new Box(20,105,150,140),new Box(380,105,485,140),true,0,20);
    }
    static OverviewFrameResult activity(byte[] image,List<Row> rows,OverviewUiRegions.Region r) {
        return OverviewRegionRecognizer.observe(1,List.of(r),image,null,W,H,rows,()->false);
    }
    static OverviewFrameResult.Item only(OverviewFrameResult result) {
        assertFalse("frame failed "+result.frameFailure(),result.failed());assertEquals(1,result.items().size());return result.items().get(0);
    }
    @Test public void genericActivityUsesPixelBoundaryAndPlusBadgeNotCardCenter() {
        var result=activity(activityPixels(),activityRows(),activityRegion());var item=only(result);
        assertEquals(Status.CANDIDATE,item.status());assertEquals(Reason.PLUS_NUMBER,item.reason());
        assertEquals(new Box(25,149,509,340),item.boundary());assertEquals(new Box(388,216,490,273),item.target());
        var points=OverviewMockPlan.points(result,true);assertEquals(1,points.size());assertEquals(439,points.get(0).x());assertEquals(244,points.get(0).y());
        assertFalse(result.actionAuthority());assertFalse(result.counterAuthority());
    }
    @Test public void croppedRoiCoordinatesTranslateBackToTheOriginalFrame() {
        int width=700,height=900,dx=64,dy=100;byte[] frame=new byte[width*height];Arrays.fill(frame,(byte)235);
        byte[] original=activityPixels();for(int y=0;y<H;y++)System.arraycopy(original,y*W,frame,(y+dy)*width+dx,W);
        List<Row> rows=new ArrayList<>();for(Row row:activityRows())rows.add(new Row(row.text(),shift(row.box(),dx,dy),row.confidence(),row.origin()));
        var base=activityRegion();var r=new OverviewUiRegions.Region(base.role(),shift(base.coarse(),dx,dy),shift(base.heading(),dx,dy),shift(base.counter(),dx,dy),true,0,20);
        var result=OverviewRegionRecognizer.observe(24,List.of(r),frame,null,width,height,rows,()->false);
        assertEquals(24,result.frameId());assertEquals(shift(new Box(25,149,509,340),dx,dy),only(result).boundary());
        var point=OverviewMockPlan.points(result,true).get(0);assertEquals(439+dx,point.x());assertEquals(244+dy,point.y());
    }
    static Box shift(Box b,int x,int y){return new Box(b.left()+x,b.top()+y,b.right()+x,b.bottom()+y);}
    @Test public void completeCounterSuppressesAllTargetsEvenWhenPlusRemains() {
        var rows=activityRows();rows.set(1,row("20/20",388,110,480,135));
        var r=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,activityRegion().coarse(),activityRegion().heading(),activityRegion().counter(),true,20,20);
        var result=activity(activityPixels(),rows,r);assertEquals(Status.REGION_COMPLETE_SKIP,only(result).status());assertTrue(OverviewMockPlan.points(result,true).isEmpty());
    }
    @Test public void counterDisagreementHoldsInsteadOfTrustingTheUiNumber() {
        var rows=activityRows();rows.set(1,row("1/20",388,110,480,135));
        assertEquals(Reason.COUNTER_CONFLICT,only(activity(activityPixels(),rows,activityRegion())).reason());
    }
    @Test public void malformedAndZeroCounterCannotBecomeIncomplete() {
        for(String text:List.of("0/0","21/20","0/20 pts","0 / ?","0\u200b/20")) {
            var rows=activityRows();rows.set(1,row(text,388,110,480,135));
            assertEquals(Reason.COUNTER_INVALID,only(activity(activityPixels(),rows,activityRegion())).reason());
        }
    }
    @Test public void missingCounterAndTwoCounterRowsHold() {
        var rows=activityRows();rows.remove(1);assertEquals(Reason.COUNTER_MISSING,only(activity(activityPixels(),rows,activityRegion())).reason());
        rows=activityRows();rows.add(row("0/20",389,111,479,134));assertEquals(Reason.COUNTER_AMBIGUOUS,only(activity(activityPixels(),rows,activityRegion())).reason());
    }
    @Test public void completeHeaderDoesNotNeedUntrustedTargetRows() {
        var rows=activityRows();rows.set(1,row("20/20",388,110,480,135));rows.set(3,new Row("+5",rows.get(3).box(),.01,Origin.STATIC_OCR));
        var r=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,activityRegion().coarse(),activityRegion().heading(),activityRegion().counter(),true,20,20);
        assertEquals(Status.REGION_COMPLETE_SKIP,only(activity(activityPixels(),rows,r)).status());
    }
    @Test public void unknownCounterIsExplicitlyAbsentNotMalformed() {
        var rows=activityRows();rows.remove(1);var r=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,activityRegion().coarse(),activityRegion().heading(),null,true,-1,-1);
        var item=only(activity(activityPixels(),rows,r));assertEquals(Status.CANDIDATE,item.status());assertEquals(-1,item.current());assertEquals(-1,item.total());
    }
    @Test public void regionLongerThanTheViewportDoesNotRejectCompleteVisibleCards() {
        var r=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,activityRegion().coarse(),activityRegion().heading(),activityRegion().counter(),false,0,20);
        var result=activity(activityPixels(),activityRows(),r);assertEquals(Status.CANDIDATE,only(result).status());assertEquals(1,OverviewMockPlan.points(result,true).size());
    }
    @Test public void theActuallyClippedCardStillHasNoPoint() {
        var r=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,new Box(10,95,519,300),activityRegion().heading(),activityRegion().counter(),false,0,20);
        var result=activity(activityPixels(),activityRows(),r);assertEquals(Reason.CARD_PARTIAL,only(result).reason());assertTrue(OverviewMockPlan.points(result,true).isEmpty());
    }
    @Test public void allFourCoarseEdgeContactsHoldDespiteDetectorPadding() {
        for(Box coarse:List.of(new Box(25,140,519,355),new Box(10,149,519,355),
                new Box(10,140,509,355),new Box(10,140,519,340))) {
            var rows=activityRows();rows.remove(1);rows.remove(0);
            var r=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,coarse,null,null,false,-1,-1);
            var result=activity(activityPixels(),rows,r);
            assertEquals("coarse="+coarse,Reason.CARD_PARTIAL,only(result).reason());
            assertTrue(OverviewMockPlan.points(result,true).isEmpty());
        }
    }
    @Test public void offscreenSectionHeadingUsesOnlyCurrentStructureAndPerCardEvidence() {
        var rows=activityRows();rows.remove(1);rows.remove(0);
        var r=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,new Box(10,140,519,355),null,null,false,-1,-1);
        var result=activity(activityPixels(),rows,r);assertEquals(Status.CANDIDATE,only(result).status());
        assertEquals(-1,only(result).current());assertEquals(-1,only(result).total());
    }
    @Test public void nativeSizeLongSectionYieldsOnlyThreeWholeCardsBetweenClippedEdges() {
        int width=1440,height=2560;byte[] pixels=new byte[width*height];Arrays.fill(pixels,(byte)247);
        List<Row> rows=new ArrayList<>();
        int[] tops={-250,300,900,1500,2100};
        for(int top:tops) {
            fillLarge(pixels,width,height,112,top,1328,top+500,255);
            fillLarge(pixels,width,height,1032,top+150,1288,top+350,80);
            if(top>=0) {rows.add(row("Synthetic visible task",350,top+50,900,top+90));rows.add(row("+5",1110,top+215,1200,top+265));}
        }
        var region=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,new Box(96,0,1344,2400),null,null,false,-1,-1);
        var result=OverviewRegionRecognizer.observe(2,List.of(region),pixels,null,width,height,rows,()->false);
        assertFalse(result.failed());assertEquals(5,result.items().size());assertEquals(3,OverviewMockPlan.points(result,true).size());
        assertEquals(Reason.CARD_PARTIAL,result.items().get(0).reason());assertEquals(Reason.CARD_PARTIAL,result.items().get(4).reason());
        for(int i=1;i<=3;i++)assertEquals(Status.CANDIDATE,result.items().get(i).status());
    }
    static void fillLarge(byte[] pixels,int width,int height,int left,int top,int right,int bottom,int value) {
        for(int y=Math.max(0,top);y<Math.min(height,bottom);y++)Arrays.fill(pixels,y*width+left,y*width+right,(byte)value);
    }
    @Test public void lowConfidencePointCannotBePromotedByStructure() {
        var rows=activityRows();rows.set(3,new Row("+5",rows.get(3).box(),.84,Origin.STATIC_OCR));
        assertEquals(Reason.LOW_CONFIDENCE,only(activity(activityPixels(),rows,activityRegion())).reason());
    }
    @Test public void lowConfidenceOnOneCardDoesNotEraseAnotherSafeCard() {
        var fixture=new RewardRegionLocatorTest();byte[] image=fixture.tickPixels(true,false);
        var rows=new ArrayList<Row>(List.of(row("更多活动",25,110,135,135),row("0/20",388,110,480,135),
                row("One task",55,170,320,195),row("+5",414,225,465,250),
                row("Two task",55,345,320,365),new Row("+5",new Box(414,380,465,405),.2,Origin.STATIC_OCR),
                row("Done task",55,488,320,510),row("10",433,502,457,519)));
        var r=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,new Box(10,95,519,562),activityRegion().heading(),activityRegion().counter(),true,0,20);
        var result=activity(image,rows,r);assertEquals(Status.CANDIDATE,result.items().get(0).status());
        assertEquals(Reason.LOW_CONFIDENCE,result.items().get(1).reason());assertEquals(1,OverviewMockPlan.points(result,true).size());
    }
    @Test public void mixedPlusTextAndTwoPlusRowsAreNotSplitOrMerged() {
        var rows=activityRows();rows.set(3,row("+5 pts",414,231,465,257));assertEquals(Reason.TOKEN_AMBIGUOUS,only(activity(activityPixels(),rows,activityRegion())).reason());
        rows=activityRows();rows.add(row("+10",414,278,465,300));assertEquals(Reason.TOKEN_AMBIGUOUS,only(activity(activityPixels(),rows,activityRegion())).reason());
    }
    @Test public void missingTitleCannotCreateAPointFromPlusAlone() {
        var rows=activityRows();rows.remove(2);assertEquals(Reason.TITLE_MISSING,only(activity(activityPixels(),rows,activityRegion())).reason());
    }
    @Test public void titleInLowerHalfDoesNotCountAsUpperLeftEvidence() {
        var rows=activityRows();rows.set(2,row("A synthetic activity",55,300,320,325));
        assertEquals(Reason.TITLE_MISSING,only(activity(activityPixels(),rows,activityRegion())).reason());
    }
    @Test public void overlappingAndCrossBoundaryRowsHold() {
        var rows=activityRows();rows.add(row("overlap",60,175,100,190));assertEquals(Reason.ROW_OVERLAP,only(activity(activityPixels(),rows,activityRegion())).reason());
        rows=activityRows();rows.add(row("cross",20,180,80,205));assertEquals(Reason.ROW_CROSSES_CARD,only(activity(activityPixels(),rows,activityRegion())).reason());
    }
    @Test public void stateWarningInCardCannotBeDiscarded() {
        var rows=activityRows();rows.add(row("明天",55,270,100,290));assertEquals(Reason.STATE_CONFLICT,only(activity(activityPixels(),rows,activityRegion())).reason());
    }
    @Test public void geometryIsRequiredAndUiBoundsDoNotSubstitute() {
        byte[] image=activityPixels();Arrays.fill(image,(byte)247);assertEquals(Reason.NO_BOUNDARY,only(activity(image,activityRows(),activityRegion())).reason());
    }
    @Test public void sameActivityLogicSupportsDailySetWithoutSearchReadingRoles() {
        var rows=activityRows();rows.set(0,row("每日活动",25,110,135,135));
        var r=new OverviewUiRegions.Region(Role.DAILY_ACTIVITIES,activityRegion().coarse(),activityRegion().heading(),activityRegion().counter(),true,0,20);
        assertEquals(Status.CANDIDATE,only(activity(activityPixels(),rows,r)).status());
        assertEquals(6,Role.values().length);
    }
    @Test public void regionOwnershipOverlapSuppressesBothActionableCandidates() {
        var r=activityRegion();var result=OverviewRegionRecognizer.observe(1,List.of(r,r),activityPixels(),null,W,H,activityRows(),()->false);
        assertEquals(2,result.items().size());for(var item:result.items())assertEquals(Reason.REGION_OVERLAP,item.reason());assertTrue(OverviewMockPlan.points(result,true).isEmpty());
    }
    @Test public void summariesNeverGeneratePointsEvenWhenNumbersAreIncomplete() {
        var rows=activityRows();rows.set(0,row("今日积分",25,110,135,135));
        var r=new OverviewUiRegions.Region(Role.POINTS_SUMMARY,activityRegion().coarse(),activityRegion().heading(),activityRegion().counter(),true,0,20);
        var result=activity(activityPixels(),rows,r);assertEquals(Status.OBSERVED,only(result).status());assertEquals(Reason.READ_ONLY_SUMMARY,only(result).reason());assertTrue(OverviewMockPlan.points(result,true).isEmpty());
    }
    @Test public void completedLocalNumberAndTickSuppressOnlyThatCard() {
        var fixture=new RewardRegionLocatorTest();byte[] image=fixture.tickPixels(true,false);
        var rows=new ArrayList<Row>(List.of(row("更多活动",25,110,135,135),row("0/20",388,110,480,135),
                row("One task",55,170,320,195),row("+5",414,225,465,250),
                row("Two task",55,345,320,365),row("+5",414,380,465,405),
                row("Done task",55,488,320,510),row("10",433,502,457,519)));
        var r=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,new Box(10,95,519,562),activityRegion().heading(),activityRegion().counter(),true,0,20);
        var result=activity(image,rows,r);assertEquals(3,result.items().size());
        assertEquals(Status.CANDIDATE,result.items().get(0).status());assertEquals(Status.CANDIDATE,result.items().get(1).status());
        assertEquals(Status.ITEM_COMPLETE_SKIP,result.items().get(2).status());assertEquals(2,OverviewMockPlan.points(result,true).size());
    }
    @Test public void completedCardTouchingCoarseBottomIsHeldNotMarkedComplete() {
        var fixture=new RewardRegionLocatorTest();byte[] image=fixture.tickPixels(true,false);
        var rows=new ArrayList<Row>(List.of(row("更多活动",25,110,135,135),row("0/20",388,110,480,135),
                row("One task",55,170,320,195),row("+5",414,225,465,250),
                row("Two task",55,345,320,365),row("+5",414,380,465,405),
                row("Done task",55,488,320,510),row("10",433,502,457,519)));
        var r=new OverviewUiRegions.Region(Role.MORE_ACTIVITIES,new Box(10,95,519,547),activityRegion().heading(),activityRegion().counter(),false,0,20);
        var result=activity(image,rows,r);assertEquals(3,result.items().size());
        assertEquals(Status.HOLD,result.items().get(2).status());assertEquals(Reason.CARD_PARTIAL,result.items().get(2).reason());
        assertEquals(2,OverviewMockPlan.points(result,true).size());
    }
    static final int CW=552,CH=201;
    static OverviewUiRegions.Region checkRegion(Role role) { return new OverviewUiRegions.Region(role,new Box(10,17,536,193),new Box(25,35,90,70),null,true,-1,-1); }
    static List<Row> checkRows() { List<Row> out=new ArrayList<>();for(Row r:DailyCheckInRegionTest.rows())out.add(new Row(r.text(),r.box(),r.confidence(),Origin.STATIC_OCR));return out; }
    static int[] colors(byte[] gray,boolean yellow) {
        int[] rgb=new int[gray.length];for(int i=0;i<gray.length;i++){int v=gray[i]&255;rgb[i]=0xff000000|(v<<16)|(v<<8)|v;
            if(v==190) {rgb[i]=yellow?0xffffb914:0xff1470ff;int c=rgb[i];gray[i]=(byte)((77*((c>>>16)&255)+150*((c>>>8)&255)+29*(c&255)+128)>>>8);}}
        return rgb;
    }
    static OverviewFrameResult check(byte[] gray,int[] rgb,List<Row> rows,Role role) { return OverviewRegionRecognizer.observe(1,List.of(checkRegion(role)),gray,rgb,CW,CH,rows,()->false); }
    @Test public void checkInUsesCurrentYellowRingNumeralAndAcceptsOtherPointValues() {
        for(String value:List.of("5","10","15","50","100","9999")) {
            byte[] gray=DailyCheckInRegionTest.pixels();int[] rgb=colors(gray,true);var rows=checkRows();rows.set(8,row(value,264,109,280,122));
            var result=check(gray,rgb,rows,Role.CHECK_IN);var item=only(result);assertEquals("value="+value+" reason="+item.reason(),Status.CANDIDATE,item.status());
            assertEquals(Integer.parseInt(value),item.displayedPoints());var point=OverviewMockPlan.points(result,true).get(0);assertEquals(272,point.x());assertEquals(115,point.y());
        }
    }
    @Test public void grayRingAloneCannotProveYellow() {
        byte[] gray=DailyCheckInRegionTest.pixels();assertEquals(Reason.COLOR_UNAVAILABLE,only(check(gray,null,checkRows(),Role.CHECK_IN)).reason());
    }
    @Test public void differentlyColoredCurrentRingCannotBecomeYellow() {
        byte[] gray=DailyCheckInRegionTest.pixels();int[] rgb=colors(gray,false);assertEquals(Reason.COLOR_NOT_YELLOW,only(check(gray,rgb,checkRows(),Role.CHECK_IN)).reason());
    }
    @Test public void futureFilledCircleWithoutCurrentOutlineHasNoPoint() {
        byte[] gray=DailyCheckInRegionTest.pixels();DailyCheckInRegionTest.fill(gray,245,88,300,143,250);DailyCheckInRegionTest.disk(gray,272,115,17,230);
        int[] rgb=colors(gray,true);assertEquals(Reason.NO_CURRENT_RING,only(check(gray,rgb,checkRows(),Role.CHECK_IN)).reason());
    }
    @Test public void twoCurrentOutlinesAreAmbiguousEvenWithOneNumeral() {
        byte[] gray=DailyCheckInRegionTest.pixels();DailyCheckInRegionTest.ring(gray,342);int[] rgb=colors(gray,true);
        assertEquals(Reason.AMBIGUOUS_RING,only(check(gray,rgb,checkRows(),Role.CHECK_IN)).reason());
    }
    @Test public void checkInTickOverTheCurrentRingNeverGetsAPoint() {
        byte[] gray=DailyCheckInRegionTest.pixels();DailyCheckInRegionTest.tick(gray,272);int[] rgb=colors(gray,true);
        var result=check(gray,rgb,checkRows(),Role.CHECK_IN);assertEquals(Reason.TICK_CONFLICT,only(result).reason());assertTrue(OverviewMockPlan.points(result,true).isEmpty());
    }
    @Test public void mixedAndZeroCheckInNumeralsAreNotNormalized() {
        for(String value:List.of("0","10 pts","1 0","+10","１０")) {
            byte[] gray=DailyCheckInRegionTest.pixels();int[] rgb=colors(gray,true);var rows=checkRows();rows.set(8,row(value,264,109,280,122));
            assertEquals(Reason.NUMBER_INVALID,only(check(gray,rgb,rows,Role.CHECK_IN)).reason());
        }
    }
    @Test public void circleNumeralMustBeInsideInset() {
        byte[] gray=DailyCheckInRegionTest.pixels();int[] rgb=colors(gray,true);var rows=checkRows();rows.set(8,row("10",245,109,280,122));
        assertEquals(Reason.NUMBER_INVALID,only(check(gray,rgb,rows,Role.CHECK_IN)).reason());
    }
    @Test public void weeklyDailyProgressDoesNotBecomeCheckIn() {
        byte[] gray=DailyCheckInRegionTest.pixels();int[] rgb=colors(gray,true);var rows=checkRows();rows.set(0,row("每日活动",30,42,79,65));
        var result=check(gray,rgb,rows,Role.DAILY_PROGRESS);assertEquals(Reason.READ_ONLY_WEEK_PROGRESS,only(result).reason());assertTrue(OverviewMockPlan.points(result,true).isEmpty());
    }
    @Test public void checkInTitleCannotBeRewrittenFromAnotherRole() {
        byte[] gray=DailyCheckInRegionTest.pixels();int[] rgb=colors(gray,true);var rows=checkRows();rows.set(0,row("每日活动",30,42,79,65));
        assertEquals(Reason.HEADER_MISSING,only(check(gray,rgb,rows,Role.CHECK_IN)).reason());
    }
    @Test public void inputPixelsAndOriginalRowObjectsRemainUnchanged() {
        byte[] pixels=activityPixels(),copy=pixels.clone();var rows=activityRows();var snapshot=List.copyOf(rows);
        activity(pixels,rows,activityRegion());assertArrayEquals(copy,pixels);for(int i=0;i<rows.size();i++)assertSame(snapshot.get(i),rows.get(i));
    }
    @Test public void cancellationDiscardsEarlierCandidates() {
        AtomicInteger polls=new AtomicInteger();var result=OverviewRegionRecognizer.observe(1,List.of(activityRegion()),activityPixels(),null,W,H,activityRows(),()->polls.incrementAndGet()>8);
        assertTrue(result.failed());assertEquals(Reason.CANCELED,result.frameFailure());assertTrue(result.items().isEmpty());
    }
    @Test public void brokenCancellationSupplierDoesNotLeakPartialPoints() {
        var result=OverviewRegionRecognizer.observe(1,List.of(activityRegion()),activityPixels(),null,W,H,activityRows(),()->{throw new IllegalStateException();});
        assertEquals(Reason.DETECTOR_UNAVAILABLE,result.frameFailure());assertTrue(OverviewMockPlan.points(result,true).isEmpty());
    }
    @Test public void sourceQualificationIsRequiredForAllDiagramPoints() {
        var result=activity(activityPixels(),activityRows(),activityRegion());assertEquals(Status.CANDIDATE,only(result).status());
        assertTrue(OverviewMockPlan.points(result,false).isEmpty());assertTrue(OverviewMockPlan.points(null,true).isEmpty());
    }
    @Test public void malformedAndNonNativeDeclaredRowsRejectWholeFrame() {
        var rows=activityRows();rows.set(2,new Row("Title",rows.get(2).box(),.99,Origin.MANUAL_ANNOTATION));assertEquals(Reason.INVALID_ROWS,activity(activityPixels(),rows,activityRegion()).frameFailure());
        rows=activityRows();rows.set(2,new Row("Title",rows.get(2).box(),Double.NaN,Origin.STATIC_OCR));assertEquals(Reason.INVALID_ROWS,activity(activityPixels(),rows,activityRegion()).frameFailure());
    }
    @Test public void impossibleFrameShapeRejectsBeforeRoiWork() {
        var result=OverviewRegionRecognizer.observe(1,List.of(activityRegion()),new byte[1],null,W,H,activityRows(),()->false);
        assertEquals(Reason.INVALID_IMAGE,result.frameFailure());assertTrue(result.items().isEmpty());
    }
    @Test public void outputHasNoTextOrHashFieldsAndNoDispatchMethod() {
        for(var type:List.of(OverviewFrameResult.Item.class,OverviewMockPlan.Point.class))for(var component:type.getRecordComponents())assertNotEquals(String.class,component.getType());
        for(var method:OverviewMockPlan.class.getDeclaredMethods())assertFalse(method.getName().matches("(?i).*(dispatch|tap|click|input).*"));
    }
    @Test public void failedFrameCannotCarryPointsEvenIfACallerSuppliesCandidateItems() {
        var accepted=activity(activityPixels(),activityRows(),activityRegion());
        var failed=new OverviewFrameResult(1,accepted.items(),Reason.DETECTOR_UNAVAILABLE);
        assertTrue(OverviewMockPlan.points(failed,true).isEmpty());
    }
}
