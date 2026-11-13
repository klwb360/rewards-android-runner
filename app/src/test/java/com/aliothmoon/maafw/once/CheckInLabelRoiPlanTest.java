package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.once.FrameStructureEvidence.Line;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit simulated native results, never promoted to actual native OCR evidence. */
public class CheckInLabelRoiPlanTest {
    static final int W=DailyCheckInRegionTest.W,H=DailyCheckInRegionTest.H;
    static Line line(String text,Box box,double score) {
        return new Line(text,box.left(),box.top(),box.width(),box.height(),score);
    }
    static List<Line> originals() {
        List<Line> rows=new ArrayList<>();
        for(var row:DailyCheckInRegionTest.rows())if(!row.text().startsWith("Day"))
            rows.add(line(row.text(),row.box(),row.confidence()));
        rows.add(new Line("Day 1 Day 2 Day 3 Day 4 Day 5 Day 6 Day 7",40,152,465,19,.42));
        return rows;
    }
    static CheckInLabelRoiPlan.Plan plan(List<Line> rows) {
        return CheckInLabelRoiPlan.prepare(DailyCheckInRegionTest.pixels(),W,H,rows,()->false);
    }
    static List<List<Line>> batches() {
        List<List<Line>> results=new ArrayList<>();
        for(int i=0;i<7;i++)results.add(List.of(new Line("Day "+(i+1),42+70*i,152,40,19,.99)));
        return results;
    }
    static void rejects(List<Line> original,List<List<Line>> results) {
        var replacement=CheckInLabelRoiPlan.replace(plan(original),original,results,()->false);
        assertFalse(replacement.accepted());assertEquals(original,replacement.rows());
        assertEquals(0,replacement.acceptedLabels());
    }
    @Test public void observedBandHasSevenBoundedOverlappingSearches() {
        var p=plan(originals());assertTrue(p.admitted());assertEquals(new Box(10,144,536,193),p.band());
        assertEquals(7,p.rois().size());assertEquals(1,p.lowInBand());assertEquals(0,p.lowOutsideBand());
        for(int i=0;i<7;i++) {
            int left=10+526*i/7,right=10+526*(i+1)/7,margin=(int)Math.ceil(.15*(right-left));
            assertEquals(new Box(Math.max(10,left-margin),139,Math.min(536,right+margin),193),p.rois().get(i));
            if(i>0)assertTrue(p.rois().get(i-1).overlaps(p.rois().get(i)));
        }
    }
    @Test public void nativeBoxMayCrossCoreEdgeButCenterStillOwnsSlot() {
        var rows=originals();var b=batches();
        Line crossing=new Line("Day 1",65,152,30,19,.99);b.set(0,List.of(crossing));
        var result=CheckInLabelRoiPlan.replace(plan(rows),rows,b,()->false);
        assertTrue(result.accepted());assertSame(crossing,result.rows().get(3));
    }
    @Test public void expandedSearchDoesNotPermitWrongCoreOverlapOrWideFallback() {
        var rows=originals();var b=batches();
        b.set(0,List.of(new Line("Day 1",81,152,12,19,.99)));rejects(rows,b);
        b=batches();b.set(0,List.of(new Line("Day 1",64,152,32,19,.99)));
        b.set(1,List.of(new Line("Day 2",86,152,30,19,.99)));rejects(rows,b);
        b=batches();b.set(0,List.of(new Line("Day 1",20,152,64,19,.99)));rejects(rows,b);
        b=batches();b.set(0,List.of(new Line("Day 1",42,140,40,19,.99)));rejects(rows,b);
        b=batches();b.set(1,List.of(new Line("Day 2",42,152,40,19,.99)));rejects(rows,b);
    }
    @Test public void diagnosticIsBoundedAsciiCategoriesNotRawTextOrRescoredRows() {
        var rows=originals();
        Line secret=new Line("private-secret-text",58,112,8,6,.41);
        rows.add(secret);rows.add(new Line("123456789",264,109,16,13,.61));
        rows.add(new Line("another-secret",45,110,20,10,.7));
        rows.add(new Line("outside-secret",1,1,5,5,.7));
        var p=plan(rows);String d=p.rowDiagnostics();
        assertTrue(p.admitted());assertTrue(d.length()<=700);
        assertTrue(d.chars().allMatch(c->c>=32&&c<=126));
        assertTrue(d.startsWith("DAY_TEXT c=LT50"));
        for(String token:List.of("OTHER c=LT50 box=58,112,66,118","tick=IN1","tick=HIT1",
            "DIGITS c=50_84 box=264,109,280,122","ring=IN1","panel=OUT","TITLE"))assertTrue(token,d.contains(token));
        for(String raw:List.of("private-secret-text","123456789","another-secret","outside-secret",
            "Day 1","\u7b7e\u5165"))assertFalse(d.contains(raw));
        assertSame(secret,rows.get(4));assertEquals(.41,secret.score(),0);
        var replacement=CheckInLabelRoiPlan.replace(p,rows,batches(),()->false);
        assertTrue(replacement.accepted());assertTrue(replacement.rows().contains(secret));
        assertEquals("",CheckInLabelRoiPlan.prepare(new byte[1],W,H,rows,()->false).rowDiagnostics());
    }
    @Test public void mergedBandReplacedAtomicallyWithUnchangedNativeGlobalRows() {
        var rows=originals();var batches=batches();var p=plan(rows);
        var result=CheckInLabelRoiPlan.replace(p,rows,batches,()->false);
        assertTrue(result.accepted());assertEquals(7,result.acceptedLabels());assertEquals(10,result.rows().size());
        for(int i=0;i<3;i++)assertSame(rows.get(i),result.rows().get(i));
        for(int i=0;i<7;i++)assertSame(batches.get(i).get(0),result.rows().get(i+3));
    }
    @Test public void fullNativeLabelsSkipSearchWithoutChangingEvidence() {
        var rows=originals();rows.remove(rows.size()-1);
        for(var batch:batches())rows.add(batch.get(0));
        // Minimum threshold remains .85, not .94; other low evidence is retained.
        rows.set(3,new Line("Day 1",42,152,40,19,.85));
        Line low=new Line("unrelated",2,2,5,5,.42);rows.add(low);
        var original=List.copyOf(rows);var p=plan(rows);
        assertTrue(p.admitted());assertEquals("FULL_LABELS_ALREADY_AVAILABLE",p.reason());
        assertEquals(original,rows);assertSame(low,rows.get(rows.size()-1));
        assertEquals(.85,rows.get(3).score(),0);assertEquals(1,p.lowOutsideBand());
        assertEquals(7,p.rois().size());assertFalse(p.rowDiagnostics().isEmpty());
        for(int variant=0;variant<5;variant++) {
            var changed=new ArrayList<>(original);
            switch(variant) {
                case 0 -> changed.remove(3);
                case 1 -> changed.set(3,new Line("Day 1",42,152,40,19,.849));
                case 2 -> changed.add(changed.get(3));
                case 3 -> changed.set(3,new Line("Day 1",130,152,40,19,.99));
                case 4 -> changed.set(3,new Line("Day 1",42,80,40,19,.99));
            }
            assertEquals("READY",plan(changed).reason());
        }
    }
    @Test public void lowOutsideBandRemainsRawAndCannotBeRescored() {
        var rows=originals();Line low=new Line("other",2,2,15,10,.84);rows.add(low);
        var p=plan(rows);assertTrue(p.admitted());assertEquals(1,p.lowOutsideBand());
        var result=CheckInLabelRoiPlan.replace(p,rows,batches(),()->false);
        assertTrue(result.accepted());assertSame(low,result.rows().get(3));assertEquals(.84,result.rows().get(3).score(),0);
    }
    @Test public void everyBatchAndExactlyOneRowRequired() {
        var rows=originals();rejects(rows,null);
        var b=batches();b.remove(6);rejects(rows,b);
        b=batches();b.set(6,List.of());rejects(rows,b);
        b=batches();b.set(0,List.of(b.get(0).get(0),b.get(0).get(0)));rejects(rows,b);
        b=batches();b.set(0,null);rejects(rows,b);
    }
    @Test public void badScoresAndWrongOrDuplicatedDayStop() {
        for(double score:new double[]{.849,Double.NaN,Double.POSITIVE_INFINITY,1.01,-.1}) {
            var b=batches();b.set(0,List.of(new Line("Day 1",42,152,40,19,score)));rejects(originals(),b);
        }
        for(String text:List.of("Day 2","day 1","Day 1 ","1","第2天")) {
            var b=batches();b.set(0,List.of(new Line(text,42,152,40,19,.99)));rejects(originals(),b);
        }
    }
    @Test public void exactAlternateLabelsAndThresholdAreAccepted() {
        var b=batches();b.set(0,List.of(new Line("Day1",42,152,40,19,.85)));
        b.set(1,List.of(new Line("第2天",112,152,40,19,.85)));
        var rows=originals();assertTrue(CheckInLabelRoiPlan.replace(plan(rows),rows,b,()->false).accepted());
    }
    @Test public void roiLocalCoordinatesAreNeverShiftedAndClippedBoxesStop() {
        for(Line invalid:List.of(new Line("Day 2",27,8,40,19,.99),new Line("Day 2",-1,152,40,19,.99),
            new Line("Day 2",530,152,40,19,.99),new Line("Day 2",112,190,40,19,.99),
            new Line("Day 2",112,152,Integer.MAX_VALUE,19,.99),new Line("Day 2",150,152,40,19,.99))) {
            var b=batches();b.set(1,List.of(invalid));rejects(originals(),b);
        }
    }
    @Test public void wholeRoiAndAnyTouchingEdgeAreNotTextLocalization() {
        var rows=originals();Box roi=plan(rows).rois().get(0);
        for(Box actual:List.of(roi,new Box(roi.left(),152,82,171),new Box(42,roi.top(),82,171),
            new Box(42,152,roi.right(),171),new Box(42,152,82,roi.bottom()))) {
            var b=batches();b.set(0,List.of(line("Day 1",actual,.99)));rejects(rows,b);
        }
    }
    @Test public void partialBandIntersectionNeverDropsOriginalEvidence() {
        var rows=originals();rows.add(new Line("partial",42,140,40,12,.4));rejects(rows,batches());
    }
    @Test public void invalidOriginalReturnsSafeEmptyAndMaxRowsIsEnforced() {
        var p=plan(originals());var bad=new ArrayList<Line>();bad.add(null);
        var result=CheckInLabelRoiPlan.replace(p,bad,batches(),()->false);
        assertFalse(result.accepted());assertTrue(result.rows().isEmpty());
        var rows=originals();while(rows.size()<128)rows.add(new Line("other",2,2,2,2,.99));
        rejects(rows,batches());
    }
    @Test public void wrongLowOrDuplicateTitleAndPartialPanelStop() {
        for(String title:List.of("登录","每日活动","签入 ")) {
            var rows=originals();rows.set(0,new Line(title,30,42,49,23,.99));assertFalse(plan(rows).admitted());
        }
        var rows=originals();rows.set(0,new Line("签入",30,42,49,23,.84));assertFalse(plan(rows).admitted());
        rows=originals();rows.add(rows.get(0));assertFalse(plan(rows).admitted());
        byte[] pixels=DailyCheckInRegionTest.pixels();DailyCheckInRegionTest.fill(pixels,0,17,10,193,250);
        assertFalse(CheckInLabelRoiPlan.prepare(pixels,W,H,originals(),()->false).admitted());
        assertFalse(CheckInLabelRoiPlan.prepare(new byte[1],W,H,originals(),()->false).admitted());
        assertFalse(CheckInLabelRoiPlan.prepare(new byte[1],4096,4096,originals(),()->false).admitted());
    }
    @Test public void cancellationBeforeDuringAndAfterStopsWithoutPartialReplacement() {
        var rows=originals();var p=plan(rows);
        assertFalse(CheckInLabelRoiPlan.prepare(DailyCheckInRegionTest.pixels(),W,H,rows,()->true).admitted());
        AtomicInteger count=new AtomicInteger();
        assertFalse(CheckInLabelRoiPlan.prepare(DailyCheckInRegionTest.pixels(),W,H,rows,()->count.incrementAndGet()>4).admitted());
        for(int threshold:new int[]{0,3,8}) {
            AtomicInteger calls=new AtomicInteger();
            var result=CheckInLabelRoiPlan.replace(p,rows,batches(),()->calls.incrementAndGet()>threshold);
            assertFalse(result.accepted());assertEquals(rows,result.rows());assertEquals(0,result.acceptedLabels());
        }
    }
}
