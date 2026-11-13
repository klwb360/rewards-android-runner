package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.DailyCheckInRegion;
import com.aliothmoon.maafw.rewards.DailyCheckInRegion.*;
import com.aliothmoon.maafw.rewards.RewardRegionGate.Key;
import com.aliothmoon.maafw.rewards.RewardRegionGate.Source;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Origin;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic pixels and synthetic OCR only; actual-source image runs are separately documented. */
public class DailyCheckInRegionTest {
    static final int W=552,H=201;
    static final String[] CHECK={"00000000000111","00000000001110","00000000011100",
        "10000000111000","11100001110000","01110011100000","00111111000000","00011110000000","00001100000000"};
    static Key key(int n){return new Key(Source.SYNTHETIC_FIXTURE,"synthetic-week",n,"checkin");}
    static void fill(byte[] p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
    static void disk(byte[] p,int cx,int cy,int radius,int value){for(int y=cy-radius;y<=cy+radius;y++)for(int x=cx-radius;x<=cx+radius;x++)if((x-cx)*(x-cx)+(y-cy)*(y-cy)<=radius*radius)p[y*W+x]=(byte)value;}
    static void ring(byte[] p,int cx){disk(p,cx,115,27,190);disk(p,cx,115,25,250);disk(p,cx,115,17,230);fill(p,cx-8,109,cx-4,122,90);fill(p,cx+2,109,cx+8,122,90);}
    static void tick(byte[] p,int cx){disk(p,cx,115,16,200);for(int y=0;y<9;y++)for(int x=0;x<14;x++)if(CHECK[y].charAt(x)=='1')p[(111+y)*W+cx-7+x]=(byte)250;}
    static byte[] pixels(){byte[] p=new byte[W*H];Arrays.fill(p,(byte)235);fill(p,10,17,536,193,250);tick(p,62);tick(p,132);tick(p,202);ring(p,272);return p;}
    static List<Row> rows(){List<Row> r=new ArrayList<>();r.add(new Row("签入",new Box(30,42,79,65),.99,Origin.SYNTHETIC));for(int i=0;i<7;i++)r.add(new Row("Day "+(i+1),new Box(42+70*i,152,82+70*i,171),.99,Origin.SYNTHETIC));r.add(new Row("10",new Box(264,109,280,122),.99,Origin.SYNTHETIC));r.add(new Row("5小时59分",new Box(390,44,510,64),.99,Origin.SYNTHETIC));return r;}
    static Geometry geometry(byte[] p,int n){return DailyCheckInRegion.observeGray8(key(n),p,W,H,()->false);}
    static Result result(byte[] p,int n,List<Row> rows){return DailyCheckInRegion.associate(geometry(p,n),new TextEvidence(key(n),rows));}
    static Result before(){return result(pixels(),1,rows());}
    static Result completed(int n){byte[] p=pixels();fill(p,245,88,300,143,250);tick(p,272);List<Row> r=rows();r.remove(8);return result(p,n,r);}
    @Test public void detectsSurfaceRingAndLocalTicksFromPixels(){Geometry g=geometry(pixels(),1);assertEquals(Status.OBSERVED,g.status());assertEquals(1,g.surfaces().size());assertEquals(new Box(10,17,536,193),g.surfaces().get(0).box());assertEquals(1,g.rings().size());assertEquals(3,g.ticks().size());}
    @Test public void candidateIsDayFourNumeralCenterOnly(){Result r=before();assertEquals(Status.CANDIDATE,r.status());assertEquals(4,r.candidate().day());assertEquals(10,r.candidate().displayedPoints());assertEquals(272,r.candidate().cropX(),0);assertEquals(115.5,r.candidate().cropY(),0);assertFalse(r.candidate().calendarTodayProven());assertFalse(r.candidate().verifiedClickable());assertFalse(r.credited());}
    @Test public void countdownAndUnrelatedRatioAreNotProgressGates(){List<Row> r=rows();r.set(9,new Row("99/100",r.get(9).box(),.99,Origin.SYNTHETIC));assertEquals(Status.CANDIDATE,result(pixels(),1,r).status());}
    @Test public void wrongSectionCannotUseMatchingGeometry(){List<Row> r=rows();r.set(0,new Row("每日活动",r.get(0).box(),.99,Origin.SYNTHETIC));assertEquals(Status.NO_UNIQUE_SECTION,result(pixels(),1,r).status());}
    @Test public void authenticationTitleIsNotCheckin(){List<Row> r=rows();r.set(0,new Row("登录",r.get(0).box(),.99,Origin.SYNTHETIC));assertEquals(Status.NO_UNIQUE_SECTION,result(pixels(),1,r).status());}
    @Test public void missingBoundaryNeverFallsBackToTitleOrDigits(){byte[] p=pixels();Arrays.fill(p,(byte)235);assertEquals(Status.NO_BOUNDARY,result(p,1,rows()).status());}
    @Test public void clippedSurfaceStops(){byte[] p=pixels();fill(p,0,17,10,193,250);assertEquals(Status.INCOMPLETE_REGION,result(p,1,rows()).status());}
    @Test public void twoActiveRingsStop(){byte[] p=pixels();ring(p,342);assertEquals(Status.AMBIGUOUS_CIRCLE,result(p,1,rows()).status());}
    @Test public void paleFutureRingDoesNotBecomeCurrent(){byte[] p=pixels();disk(p,342,115,27,246);disk(p,342,115,26,250);assertEquals(4,result(p,1,rows()).candidate().day());}
    @Test public void lowConfidenceNumberStops(){List<Row> r=rows();r.set(8,new Row("10",r.get(8).box(),.4,Origin.SYNTHETIC));assertEquals(Status.BAD_OCR,result(pixels(),1,r).status());}
    @Test public void missingNumberStops(){List<Row> r=rows();r.remove(8);assertEquals(Status.BAD_NUMBER,result(pixels(),1,r).status());}
    @Test public void fractionalTimerOrUnitsInsideRingCannotBeNumber(){for(String s:List.of("1/10","4:53","+10","10积分","10\u200b","0")){List<Row> r=rows();r.set(8,new Row(s,r.get(8).box(),.99,Origin.SYNTHETIC));assertEquals(Status.BAD_NUMBER,result(pixels(),1,r).status());}}
    @Test public void multipleNumericReadingsStop(){List<Row> r=rows();r.set(8,new Row("1",new Box(261,109,268,122),.99,Origin.SYNTHETIC));r.add(new Row("2",new Box(277,109,284,122),.99,Origin.SYNTHETIC));assertEquals(Status.BAD_NUMBER,result(pixels(),1,r).status());}
    @Test public void numericBoxTouchingOutlineStops(){List<Row> r=rows();r.set(8,new Row("10",new Box(245,109,280,122),.99,Origin.SYNTHETIC));assertEquals(Status.BAD_NUMBER,result(pixels(),1,r).status());}
    @Test public void missingOrDuplicateDayLabelsStop(){List<Row> r=rows();r.remove(6);assertEquals(Status.BAD_DAY_SLOTS,result(pixels(),1,r).status());r=rows();r.set(6,new Row("Day 5",r.get(6).box(),.99,Origin.SYNTHETIC));assertEquals(Status.BAD_DAY_SLOTS,result(pixels(),1,r).status());}
    @Test public void lowConfidenceDayLabelsAreNotSilentlyDiscarded(){List<Row> r=rows();r.set(6,new Row("Day 6",r.get(6).box(),.51,Origin.STATIC_OCR));assertEquals(Status.BAD_OCR,result(pixels(),1,r).status());}
    @Test public void explicitManualEvidenceStaysManual(){List<Row> r=rows();r.set(0,new Row("签入",r.get(0).box(),.99,Origin.MANUAL_ANNOTATION));assertEquals(Origin.MANUAL_ANNOTATION,result(pixels(),1,r).candidate().titleOrigin());}
    @Test public void textFromWrongObservationIsRejected(){assertEquals(Status.BAD_OCR,DailyCheckInRegion.associate(geometry(pixels(),1),new TextEvidence(key(2),rows())).status());}
    @Test public void callerPixelsAreNotChangedAndNoPixelsAreRetained(){byte[] p=pixels(),copy=p.clone();Geometry g=geometry(p,1);assertArrayEquals(copy,p);String h=g.pixelHash();Arrays.fill(p,(byte)0);assertEquals(h,g.pixelHash());assertEquals(1,g.rings().size());}
    @Test public void resourceCeilingAndCancellationFailClosed(){assertEquals(Status.INVALID_INPUT,DailyCheckInRegion.observeGray8(key(1),new byte[1],4096,8192,null).status());assertEquals(Status.CANCELED,DailyCheckInRegion.observeGray8(key(1),pixels(),W,H,()->true).status());AtomicInteger calls=new AtomicInteger();assertEquals(Status.CANCELED,DailyCheckInRegion.observeGray8(key(1),pixels(),W,H,()->calls.incrementAndGet()>4).status());}
    @Test public void fakeBudgetIsOneAndNeverReal(){Session s=new Session(before());Receipt r=s.previewOnce();assertEquals(Outcome.PREVIEW_ONLY,r.outcome());assertEquals(1,r.hypotheticalClicks());assertEquals("UNBOUND",r.actor());assertEquals(0,r.realExecutions());assertFalse(r.creditKnown());assertFalse(r.causalityProven());assertFalse(r.retryAllowed());assertEquals(Outcome.ALREADY_CONSUMED,s.previewOnce().outcome());}
    @Test public void cancelPreventsAllReplay(){Session s=new Session(before());s.cancel();assertEquals(Outcome.CANCELED,s.previewOnce().outcome());assertEquals(Outcome.CANCELED,s.verifyOnce(completed(2)).outcome());}
    @Test public void noNewFrameStopsAndConsumesVerification(){Session s=new Session(before());s.previewOnce();assertEquals(Outcome.NO_LOCAL_COMPLETION_STOP,s.verifyOnce(null).outcome());assertEquals(Outcome.ALREADY_CONSUMED,s.verifyOnce(completed(2)).outcome());}
    @Test public void repeatedPixelsWithNewSequenceStillStop(){assertEquals(Outcome.REPEATED_OR_STALE_IMAGE_STOP,DailyCheckInRegion.compare(before(),result(pixels(),2,rows())).outcome());}
    @Test public void staleObservationStopsEvenIfImageChanged(){assertEquals(Outcome.REPEATED_OR_STALE_IMAGE_STOP,DailyCheckInRegion.compare(before(),completed(1)).outcome());}
    @Test public void sameSlotTickIsObservedButCreditAndCausalityUnknown(){Session s=new Session(before());s.previewOnce();Receipt r=s.verifyOnce(completed(2));assertEquals(Outcome.OBSERVED_SAME_SLOT_MARKED_COMPLETE_UNATTRIBUTED,r.outcome());assertFalse(r.creditKnown());assertFalse(r.causalityProven());assertEquals(0,r.realExecutions());assertEquals(Outcome.ALREADY_CONSUMED,s.previewOnce().outcome());}
    @Test public void timerOnlyChangeIsNotCompletion(){byte[] p=pixels();p[44*W+400]=60;assertEquals(Outcome.NO_LOCAL_COMPLETION_STOP,DailyCheckInRegion.compare(before(),result(p,2,rows())).outcome());}
    @Test public void otherSlotTickCannotCompleteTarget(){byte[] p=pixels();tick(p,342);assertEquals(Outcome.SCOPE_OR_DAY_CHANGE_STOP,DailyCheckInRegion.compare(before(),result(p,2,rows())).outcome());}
    @Test public void weekResetNeighborTickLossStops(){byte[] p=pixels();fill(p,245,88,300,143,250);tick(p,272);fill(p,40,90,85,140,250);List<Row> r=rows();r.remove(8);assertEquals(Outcome.SCOPE_OR_DAY_CHANGE_STOP,DailyCheckInRegion.compare(before(),result(p,2,r)).outcome());}
    @Test public void daySlotMovementStops(){Result a=completed(2);List<Row> r=rows();r.remove(8);r.set(4,new Row("Day 4",new Box(253,152,293,171),.99,Origin.SYNTHETIC));byte[] p=pixels();fill(p,245,88,300,143,250);tick(p,272);assertEquals(Outcome.SCOPE_OR_DAY_CHANGE_STOP,DailyCheckInRegion.compare(before(),result(p,2,r)).outcome());assertNotNull(a);}
    @Test public void noCheckGlyphCannotCompleteTarget(){byte[] p=pixels();fill(p,245,88,300,143,250);disk(p,272,115,16,200);List<Row> r=rows();r.remove(8);assertEquals(Outcome.NO_LOCAL_COMPLETION_STOP,DailyCheckInRegion.compare(before(),result(p,2,r)).outcome());}
    @Test public void verificationBeforePreviewClosesBudget(){Session s=new Session(before());assertEquals(Outcome.BLOCKED,s.verifyOnce(completed(2)).outcome());assertEquals(Outcome.ALREADY_CONSUMED,s.previewOnce().outcome());}
    @Test public void tickElsewhereInSameSlotCannotCompleteOldCircle(){byte[] p=pixels();fill(p,245,88,300,143,250);tick(p,283);List<Row> r=rows();r.remove(8);assertEquals(Outcome.SCOPE_OR_DAY_CHANGE_STOP,DailyCheckInRegion.compare(before(),result(p,2,r)).outcome());}
    @Test public void thinSquareIsNotCurrentCircle(){byte[] p=pixels();fill(p,245,88,300,143,190);fill(p,247,90,298,141,250);assertEquals(Status.NO_CURRENT_TARGET,result(p,1,rows()).status());}
    @Test public void antialiasShiftedCheckStillMatchesWithBoundedTolerance(){byte[] p=pixels();fill(p,245,88,300,143,250);disk(p,272,115,16,200);String[] shifted={"00000000000001","00000000000011","00000000000110","10000000001100","11000000011000","01110000110000","00111011100000","00011111000000","00001100000000"};for(int y=0;y<9;y++)for(int x=0;x<14;x++)if(shifted[y].charAt(x)=='1')p[(111+y)*W+265+x]=(byte)250;List<Row> r=rows();r.remove(8);assertEquals(Outcome.OBSERVED_SAME_SLOT_MARKED_COMPLETE_UNATTRIBUTED,DailyCheckInRegion.compare(before(),result(p,2,r)).outcome());}
    // Native-shaped rows over synthetic pixels exercise the visual-only bridge, not real OCR.
    static List<Row> visualRows(){List<Row> out=new ArrayList<>();for(Row r:rows())out.add(new Row(r.text(),r.box(),r.confidence(),Origin.STATIC_OCR));return out;}
    static VisualTargetResult visual(byte[] p,List<Row> rows){return DailyCheckInRegion.previewVisualTarget(geometry(p,1),new TextEvidence(key(1),rows));}
    @Test public void visualPreviewNeedsNoDayLabelsOrCalendarInference(){
        List<Row> r=visualRows();r.subList(1,8).clear();VisualTargetResult v=visual(pixels(),r);
        assertEquals(Status.CANDIDATE,v.status());assertEquals(new Box(10,17,536,193),v.panel());
        assertEquals(new Box(245,88,300,143),v.ring());assertSame(r.get(1).box(),v.numeral());
        assertEquals(10,v.displayedPoints());assertEquals(272,v.cropX(),0);assertEquals(115.5,v.cropY(),0);
        assertEquals(Origin.STATIC_OCR,v.titleOrigin());assertEquals(Origin.STATIC_OCR,v.numberOrigin());
        assertEquals(1,v.auxiliaryRows());assertTrue(v.previewOnly());assertFalse(v.calendarTodayProven());
        assertFalse(v.verifiedClickable());assertFalse(v.credited());assertEquals(0,v.hypotheticalClicks());
        assertEquals(0,v.realExecutions());assertEquals("CROP_LOCAL_ONLY",v.coordinateSpace());
        for(java.lang.reflect.Method m:VisualTargetResult.class.getMethods())
            assertFalse(m.getName().equals("day")||m.getName().equals("slot")||m.getName().equals("candidate"));
    }
    @Test public void visualPreviewPreservesMergedDayAndLowAuxiliaryRows(){
        List<Row> r=visualRows();r.subList(1,8).clear();
        r.add(new Row("Day 1 Day 2 Day 3 Day 4 Day 5 Day 6 Day 7",new Box(40,150,510,172),.98,Origin.STATIC_OCR));
        r.add(new Row("?",new Box(60,109,67,120),.12,Origin.STATIC_OCR));
        r.add(new Row("Day ?",new Box(42,152,82,171),.20,Origin.STATIC_OCR));
        List<Row> snapshot=new ArrayList<>(r);TextEvidence text=new TextEvidence(key(1),r);
        VisualTargetResult v=DailyCheckInRegion.previewVisualTarget(geometry(pixels(),1),text);
        assertEquals(Status.CANDIDATE,v.status());assertEquals(4,v.auxiliaryRows());
        assertEquals(snapshot,r);assertEquals(snapshot,text.rows());
        for(int i=0;i<r.size();i++){assertSame(snapshot.get(i),r.get(i));assertSame(r.get(i),text.rows().get(i));}
        assertEquals(Status.BAD_OCR,DailyCheckInRegion.associate(geometry(pixels(),1),text).status());
    }
    @Test public void visualPreviewAllowsUnknownAuxiliaryRowsOutsideProtectedZones(){
        List<Row> r=visualRows();r.add(new Row("unreadable",new Box(400,90,430,110),0,Origin.MANUAL_ANNOTATION));
        r.add(new Row("?",new Box(0,0,5,5),.01,Origin.STATIC_OCR));
        assertEquals(Status.CANDIDATE,visual(pixels(),r).status());assertEquals(10,visual(pixels(),r).auxiliaryRows());
    }
    @Test public void visualPreviewRejectsLowUnknownAndNonPlainTargetRows(){
        for(String s:List.of("?","1/10","4:53","+10","10积分","10\u200b","0","10000")){
            List<Row> r=visualRows();r.set(8,new Row(s,r.get(8).box(),.99,Origin.STATIC_OCR));
            assertEquals(s,Status.BAD_NUMBER,visual(pixels(),r).status());}
        List<Row> r=visualRows();r.set(8,new Row("10",r.get(8).box(),.849,Origin.STATIC_OCR));
        assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
        r=visualRows();r.add(new Row("?",new Box(261,125,272,130),.10,Origin.STATIC_OCR));
        assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
    }
    @Test public void visualPreviewUsesExistingBoundedNumberNormalizationWithoutChangingRows(){
        for(String s:List.of("１０"," 10 ","\u00a010\u202f")){
            List<Row> r=visualRows();Row original=new Row(s,r.get(8).box(),.99,Origin.STATIC_OCR);r.set(8,original);
            VisualTargetResult v=visual(pixels(),r);assertEquals(Status.CANDIDATE,v.status());
            assertEquals(10,v.displayedPoints());assertSame(original,r.get(8));assertEquals(s,r.get(8).text());
            assertSame(original.box(),v.numeral());}
    }
    @Test public void visualPreviewRejectsAnyUnresolvedTitleZoneRow(){
        for(double score:new double[]{.1,.99}){
            List<Row> r=visualRows();r.add(new Row("?",new Box(90,42,120,65),score,Origin.STATIC_OCR));
            assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
            r=visualRows();r.add(new Row("?",new Box(60,48,90,69),score,Origin.STATIC_OCR));
            assertEquals(Status.BAD_OCR,visual(pixels(),r).status());}
    }
    @Test public void visualPreviewRejectsPartialPanelRowsEvenOutsideProtectedZones(){
        List<Row> r=visualRows();r.add(new Row("Day 0",new Box(5,152,30,171),.99,Origin.STATIC_OCR));
        assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
    }
    @Test public void visualPreviewRejectsForbiddenPanelTextRegardlessOfConfidence(){
        for(String s:List.of("已锁定","明天再来","请登录","不可用","已领取","Claim complete","LOCKED",
            "tomorrow","Log in","Not Available","not_available","claimed","领取完成","已签到","每日活动")){
            List<Row> r=visualRows();r.set(9,new Row(s,r.get(9).box(),.01,Origin.STATIC_OCR));
            assertEquals(s,Status.NO_UNIQUE_SECTION,visual(pixels(),r).status());}
    }
    @Test public void visualPreviewRejectsMultipleRingsAndTickRingOverlap(){
        byte[] p=pixels();ring(p,342);assertEquals(Status.AMBIGUOUS_CIRCLE,visual(p,visualRows()).status());
        p=pixels();tick(p,272);Geometry g=geometry(p,1);assertEquals(1,g.rings().size());
        assertEquals(4,g.ticks().size());assertEquals(Status.AMBIGUOUS_CIRCLE,visual(p,visualRows()).status());
    }
    @Test public void visualPreviewRequiresUniqueExactNativeHighConfidenceTitle(){
        List<Row> r=visualRows();r.set(0,new Row("签入",r.get(0).box(),.84,Origin.STATIC_OCR));
        assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
        r=visualRows();r.set(0,new Row("签入 ",r.get(0).box(),.99,Origin.STATIC_OCR));
        assertEquals(Status.NO_UNIQUE_SECTION,visual(pixels(),r).status());
        r=visualRows();r.add(r.get(0));assertEquals(Status.NO_UNIQUE_SECTION,visual(pixels(),r).status());
        r=visualRows();r.set(0,new Row("签入",new Box(150,42,199,65),.99,Origin.STATIC_OCR));
        assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
        r=visualRows();for(int i:new int[]{0,8}){Row row=r.get(i);r.set(i,new Row(row.text(),row.box(),.85,row.origin()));}
        assertEquals(Status.CANDIDATE,visual(pixels(),r).status());
    }
    @Test public void visualPreviewDoesNotPromoteManualOrSyntheticTargetOrigins(){
        for(Origin origin:List.of(Origin.MANUAL_ANNOTATION,Origin.SYNTHETIC))for(int index:new int[]{0,8}){
            List<Row> r=visualRows();Row row=r.get(index);r.set(index,new Row(row.text(),row.box(),.99,origin));
            assertEquals(Status.BAD_OCR,visual(pixels(),r).status());}
    }
    @Test public void visualPreviewRejectsDuplicateMissingOrUnsafeNumerals(){
        List<Row> r=visualRows();r.add(r.get(8));assertEquals(Status.BAD_NUMBER,visual(pixels(),r).status());
        r=visualRows();r.set(8,new Row("1",new Box(261,109,268,122),.99,Origin.STATIC_OCR));
        r.add(new Row("2",new Box(277,109,284,122),.99,Origin.STATIC_OCR));
        assertEquals(Status.BAD_NUMBER,visual(pixels(),r).status());
        r=visualRows();r.remove(8);assertEquals(Status.BAD_NUMBER,visual(pixels(),r).status());
        r=visualRows();r.set(8,new Row("10",new Box(245,109,280,122),.99,Origin.STATIC_OCR));
        assertEquals(Status.BAD_NUMBER,visual(pixels(),r).status());
    }
    @Test public void visualPreviewValidatesAllRawRowsIncludingOutsidePanel(){
        for(double score:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-.1,1.01}){
            List<Row> r=visualRows();r.add(new Row("?",new Box(0,0,5,5),score,Origin.STATIC_OCR));
            assertEquals(Status.BAD_OCR,visual(pixels(),r).status());}
        for(Box box:Arrays.asList(null,new Box(-1,0,5,5),new Box(0,0,0,5),new Box(0,0,W+1,5))){
            List<Row> r=visualRows();r.add(new Row("?",box,.99,Origin.STATIC_OCR));
            assertEquals(Status.BAD_OCR,visual(pixels(),r).status());}
        List<Row> r=visualRows();r.add(null);assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
        r=visualRows();r.add(new Row(null,new Box(0,0,5,5),.99,Origin.STATIC_OCR));
        assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
        r=visualRows();r.add(new Row("?",new Box(0,0,5,5),.99,null));
        assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
        r=visualRows();r.add(new Row("x".repeat(129),new Box(0,0,5,5),.99,Origin.STATIC_OCR));
        assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
        r=visualRows();while(r.size()<129)r.add(r.get(9));assertEquals(Status.BAD_OCR,visual(pixels(),r).status());
    }
    @Test public void visualPreviewPreservesCanceledGeometryAndRejectsSourceKeyMismatch(){
        Geometry canceled=DailyCheckInRegion.observeGray8(key(1),pixels(),W,H,()->true);
        VisualTargetResult v=DailyCheckInRegion.previewVisualTarget(canceled,new TextEvidence(key(1),visualRows()));
        assertEquals(Status.CANCELED,v.status());assertSame(canceled,v.geometry());assertNull(v.numeral());
        assertTrue(Double.isNaN(v.cropX()));assertTrue(Double.isNaN(v.cropY()));
        for(Key other:List.of(key(2),new Key(Source.USER_STATIC_MATERIAL,"synthetic-week",1,"checkin"),
            new Key(Source.SYNTHETIC_FIXTURE,"other",1,"checkin"),new Key(Source.SYNTHETIC_FIXTURE,"synthetic-week",1,"other")))
            assertEquals(Status.BAD_OCR,DailyCheckInRegion.previewVisualTarget(geometry(pixels(),1),new TextEvidence(other,visualRows())).status());
        assertEquals(Status.INVALID_INPUT,DailyCheckInRegion.previewVisualTarget(null,null).status());
        assertEquals(Status.BAD_OCR,DailyCheckInRegion.previewVisualTarget(geometry(pixels(),1),null).status());
        assertEquals(Status.BAD_OCR,DailyCheckInRegion.previewVisualTarget(geometry(pixels(),1),new TextEvidence(key(1),null)).status());
    }
    @Test public void visualPreviewRejectsClippedMissingAndMultipleMatchingPanels(){
        byte[] p=pixels();fill(p,0,17,10,193,250);assertEquals(Status.INCOMPLETE_REGION,visual(p,visualRows()).status());
        p=pixels();Arrays.fill(p,(byte)235);assertEquals(Status.NO_BOUNDARY,visual(p,visualRows()).status());
        p=pixels();fill(p,245,88,300,143,250);assertEquals(Status.NO_CURRENT_TARGET,visual(p,visualRows()).status());
        byte[] tall=new byte[W*420];Arrays.fill(tall,(byte)235);System.arraycopy(pixels(),0,tall,0,W*H);
        fill(tall,10,220,536,396,250);Geometry g=DailyCheckInRegion.observeGray8(key(1),tall,W,420,()->false);
        assertEquals(2,g.surfaces().size());
        List<Row> r=visualRows();r.add(new Row("每日活动",new Box(30,245,129,268),.30,Origin.STATIC_OCR));
        assertEquals(Status.CANDIDATE,DailyCheckInRegion.previewVisualTarget(g,new TextEvidence(key(1),r)).status());
        r.set(10,new Row("签入",new Box(30,245,79,268),.99,Origin.STATIC_OCR));
        assertEquals(Status.NO_UNIQUE_SECTION,DailyCheckInRegion.previewVisualTarget(g,new TextEvidence(key(1),r)).status());
    }
    @Test public void xVSlashAndBoxAreNotCheckmarks(){String[][] shapes={
        {"11000000000011","01100000000110","00110000001100","00011000011000","00001100110000","00000111100000","00001100110000","00011000011000","00110000001100"},
        {"11000000000011","01100000000110","00110000001100","00110000001100","00011000011000","00011000011000","00001100110000","00000111100000","00000011000000"},
        {"00000000000111","00000000001110","00000000011100","00000000111000","00000001110000","00000011100000","00000111000000","00001110000000","00011100000000"},
        {"11111111111111","10000000000001","10000000000001","10000000000001","10000000000001","10000000000001","10000000000001","10000000000001","11111111111111"}};
        for(String[] shape:shapes){byte[] p=pixels();fill(p,245,88,300,143,250);disk(p,272,115,16,200);for(int y=0;y<9;y++)for(int x=0;x<14;x++)if(shape[y].charAt(x)=='1')p[(111+y)*W+265+x]=(byte)250;List<Row> r=rows();r.remove(8);assertEquals(Outcome.NO_LOCAL_COMPLETION_STOP,DailyCheckInRegion.compare(before(),result(p,2,r)).outcome());}}
}
