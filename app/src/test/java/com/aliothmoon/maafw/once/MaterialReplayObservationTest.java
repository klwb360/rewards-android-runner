package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionGate.Source;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** OCR rows below are explicit test inputs, never a production OCR fallback. */
public class MaterialReplayObservationTest {
    private final RewardRegionLocatorTest fixture=new RewardRegionLocatorTest();
    private static final int W=RewardRegionLocatorTest.W,H=RewardRegionLocatorTest.H;
    private static FrameStructureEvidence.Line line(String t,int x,int y,int w,int h,double score){return new FrameStructureEvidence.Line(t,x,y,w,h,score);}
    private static List<FrameStructureEvidence.Line> nativeRows(List<Row> rows){
        List<FrameStructureEvidence.Line> out=new ArrayList<>();
        for(Row r:rows)out.add(line(r.text(),r.box().left(),r.box().top(),r.box().width(),r.box().height(),r.confidence()));
        return out;
    }
    private List<FrameStructureEvidence.Line> rows(String counter){
        var out=nativeRows(fixture.rows());out.add(line("每日活动",25,110,110,25,.95));out.add(line(counter,388,110,92,25,.95));return out;
    }
    private MaterialReplayObservation observe(long seq,byte[] p,List<FrameStructureEvidence.Line> rows){
        return MaterialReplayObservation.observe(MaterialReplayObservation.Kind.DAILY,seq,p,W,H,rows,()->false);
    }
    private MaterialReplayObservation before(){return observe(1,fixture.pixels(),rows("0/30"));}
    private byte[] completedPixels(){
        byte[] p=fixture.pixels();fixture.fill(p,388,216,490,273,255);
        for(int y=232;y<257;y++)for(int x=465;x<490;x++)if(Math.pow(x+.5-477.5,2)+Math.pow(y+.5-244.5,2)<=156.25)p[y*W+x]=(byte)110;
        String[] mask={"00000000011","00000000111","00000001110","10000011100","11000111000","11101110000","01111100000","00111000000"};
        for(int y=0;y<8;y++)for(int x=0;x<11;x++)if(mask[y].charAt(x)=='1')p[(240+y)*W+472+x]=(byte)255;
        return p;
    }
    private List<FrameStructureEvidence.Line> completedRows(String counter,String value){
        var r=rows(counter);r.set(1,line(value,433,235,24,17,.95));return r;
    }
    @Test public void nativeRowsBecomeTypedSyntheticScopedCandidates(){
        var a=before();assertEquals(List.of(1),a.candidates());assertEquals(10,a.cards().candidates().get(0).displayedPoints());
        assertEquals(Source.SYNTHETIC_FIXTURE,a.cards().geometry().key().source());
        assertEquals(Origin.STATIC_OCR,a.cards().candidates().get(0).ocrOrigin());
        assertEquals(1,a.sequence());assertEquals(MaterialReplayObservation.Kind.DAILY,a.kind());
        assertTrue(a.summary().contains("NATIVE_OCR_ON_SYNTHETIC_PIXELS"));assertTrue(a.summary().contains("cropOrigin=0,0"));
        assertFalse(a.summary().contains("Upcoming comedy"));assertTrue(a.summary().chars().allMatch(c->c<128));assertTrue(a.summary().length()<=1800);
    }
    @Test public void immutableInputsAndCandidateList(){
        byte[] p=fixture.pixels();var r=rows("0/30");var a=observe(1,p,r);Arrays.fill(p,(byte)0);r.clear();assertEquals(List.of(1),a.candidates());
        try{a.candidates().add(2);fail();}catch(UnsupportedOperationException expected){}
    }
    @Test public void completeGateRequiresObservedAlignedUniqueHeader(){
        assertEquals(Status.SKIP_COMPLETE,observe(1,fixture.pixels(),rows("30/30")).cards().status());
        var r=rows("30/30");r.remove(2);assertTrue(observe(1,fixture.pixels(),r).candidates().isEmpty());assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
        r=rows("30/30");r.set(3,line("30/30",388,50,92,25,.95));assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
        r=rows("30/30");r.add(line("0/30",388,70,92,25,.95));assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
    }
    @Test public void missingOrIncorrectOcrIsNeverFilledFromFixtureTruth(){
        assertTrue(observe(1,fixture.pixels(),List.of()).candidates().isEmpty());
        var r=rows("0/30");r.remove(1);assertEquals(Status.AMBIGUOUS_TOKEN,observe(1,fixture.pixels(),r).cards().status());
        r=rows("0/30");r.set(1,line("+7",414,231,51,26,.95));assertEquals(7,observe(1,fixture.pixels(),r).cards().candidates().get(0).displayedPoints());
        byte[] blank=fixture.pixels();Arrays.fill(blank,(byte)247);assertTrue(observe(1,blank,rows("0/30")).candidates().isEmpty());
    }
    @Test public void badRowsAndScoresFailClosedEvenOnCompleteCounter(){
        List<FrameStructureEvidence.Line> bad=Arrays.asList(null,line(null,0,0,1,1,.99),line("x",0,0,1,1,Double.NaN),
            line("x",0,0,1,1,Double.POSITIVE_INFINITY),line("x",0,0,1,1,1.01),line("x",-1,0,1,1,.99),
            line("x",0,0,Integer.MAX_VALUE,1,.99),line("x",0,0,0,1,.99),line("x".repeat(513),0,0,1,1,.99));
        for(var b:bad){var r=rows("30/30");r.add(b);var a=observe(1,fixture.pixels(),r);assertTrue(a.candidates().isEmpty());assertNull(a.cards());}
        assertTrue(observe(0,fixture.pixels(),rows("0/30")).candidates().isEmpty());
        assertTrue(observe(1,new byte[1],rows("0/30")).candidates().isEmpty());
    }
    @Test public void everyLowConfidenceRowIsRetainedAsFailure(){
        for(double score:List.of(.2,.8,.849999)){var r=rows("0/30");r.add(line("unrelated",10,10,80,20,score));var a=observe(1,fixture.pixels(),r);assertTrue(a.candidates().isEmpty());assertTrue(a.summary().contains("lowConfidence085=1"));}
    }
    @Test public void moreRequiresItsOwnObservedTitle(){
        var r=rows("0/30");var a=MaterialReplayObservation.observe(MaterialReplayObservation.Kind.MORE,1,fixture.pixels(),W,H,r,null);assertTrue(a.candidates().isEmpty());
        r.set(2,line("更多活动",25,110,110,25,.95));a=MaterialReplayObservation.observe(MaterialReplayObservation.Kind.MORE,1,fixture.pixels(),W,H,r,null);assertEquals(List.of(1),a.candidates());
        assertEquals("SCOPE_CHANGE_STOP",before().compare(a,1));
    }
    @Test public void cancellationAndEmptySelectionStop(){
        var a=MaterialReplayObservation.observe(MaterialReplayObservation.Kind.DAILY,1,fixture.pixels(),W,H,rows("0/30"),()->true);
        assertTrue(a.candidates().isEmpty());assertTrue(a.summary().contains("status=CANCELED"));assertEquals("BLOCKED",before().compare(a,1));assertEquals("BLOCKED",before().compare(before(),2));
    }
    @Test public void comparisonNeedsNewPixelsSequenceMatchingValueAndCounter(){
        var a=before();assertEquals("REPEATED_OR_STALE_IMAGE_STOP",a.compare(observe(2,fixture.pixels(),rows("10/30")),1));
        assertEquals("REPEATED_OR_STALE_IMAGE_STOP",a.compare(observe(1,completedPixels(),completedRows("10/30","10")),1));
        assertEquals("OBSERVED_SELECTED_CARD_MARKED_COMPLETE_UNATTRIBUTED",a.compare(observe(2,completedPixels(),completedRows("10/30","10")),1));
        assertEquals("NO_MATCHING_LOCAL_COMPLETION_STOP",a.compare(observe(2,completedPixels(),completedRows("5/30","10")),1));
        assertEquals("NO_MATCHING_LOCAL_COMPLETION_STOP",a.compare(observe(2,completedPixels(),completedRows("10/30","5")),1));
        assertEquals("COUNTER_CONFLICT_STOP",a.compare(observe(2,completedPixels(),completedRows("0/30","10")),1));
        assertEquals("COUNTER_CONFLICT_STOP",a.compare(observe(2,completedPixels(),completedRows("10/40","10")),1));
    }
    @Test public void completionCanReachFullCounterWithoutClaimingCredit(){
        var a=observe(1,fixture.pixels(),rows("20/30"));var b=observe(2,completedPixels(),completedRows("30/30","10"));
        assertEquals(Status.SKIP_COMPLETE,b.cards().status());assertEquals("OBSERVED_SELECTED_CARD_MARKED_COMPLETE_UNATTRIBUTED",a.compare(b,1));
    }
    @Test public void otherCardChangesAreNotHiddenBySelectedCompletion(){
        byte[] p=fixture.pixels(),q=completedPixels();fixture.fill(p,25,380,509,571,255);fixture.fill(q,25,380,509,571,255);
        fixture.fill(p,388,447,490,504,80);fixture.fill(q,388,447,490,504,80);
        var r=rows("0/30");var s=completedRows("10/30","10");
        var title=line("Other activity",55,401,265,25,.95);var points=line("+10",414,462,51,26,.95);
        r.add(title);r.add(points);s.add(title);s.add(points);
        var a=observe(1,p,r);assertEquals(List.of(1,2),a.candidates());assertEquals("OBSERVED_SELECTED_CARD_MARKED_COMPLETE_UNATTRIBUTED",a.compare(observe(2,q,s),1));
        s.set(s.size()-1,line("+10",414,462,51,26,.96));
        assertEquals("OBSERVED_SELECTED_CARD_MARKED_COMPLETE_UNATTRIBUTED",a.compare(observe(2,q,s),1));
        s.set(s.size()-1,line("+10",414,462,51,26,.84));var low=observe(2,q,s);
        assertTrue(low.candidates().isEmpty());assertEquals("BLOCKED",a.compare(low,1));
        s.set(s.size()-1,line("+5",414,462,51,26,.96));assertEquals("OTHER_CARD_OCR_TEXT_CHANGED_STOP",a.compare(observe(2,q,s),1));
        s.set(s.size()-1,line("+10",415,462,51,26,.96));assertEquals("OBSERVED_SELECTED_CARD_MARKED_COMPLETE_UNATTRIBUTED",a.compare(observe(2,q,s),1));
        s.set(s.size()-1,points);q[420*W+60]=80;assertEquals("OTHER_CARD_PIXELS_CHANGED_STOP",a.compare(observe(2,q,s),1));
        q[420*W+60]=(byte)255;fixture.fill(q,388,447,490,504,255);fixture.fill(q,389,447,491,504,80);
        assertEquals("OTHER_CARD_GEOMETRY_CHANGED_STOP",a.compare(observe(2,q,s),1));
    }
    private byte[] twoCardPixels(boolean complete){
        byte[] p=complete?completedPixels():fixture.pixels();
        fixture.fill(p,25,380,509,571,255);fixture.fill(p,388,447,490,504,80);return p;
    }
    private List<FrameStructureEvidence.Line> twoCardRows(boolean complete,String counter){
        var r=complete?completedRows(counter,"10"):rows(counter);
        r.add(line("Other activity",55,401,265,25,.95));r.add(line("+10",414,462,51,26,.95));return r;
    }
    @Test public void stableOtherCardAllowsMeasuredBoxesAndRowOrderButPreservesMultiplicity(){
        var r=twoCardRows(false,"0/30");var s=twoCardRows(true,"10/30");
        r.add(line("detail",55,510,60,15,.95));r.add(line("detail",155,510,60,15,.95));
        s.add(line("detail",56,511,60,15,.96));s.add(line("detail",156,511,60,15,.96));
        var a=observe(1,twoCardPixels(false),r);
        s.set(4,line("Other activity",57,403,260,24,.90));s.set(5,line("+10",416,463,50,25,.90));
        java.util.Collections.reverse(s);
        assertEquals("OBSERVED_SELECTED_CARD_MARKED_COMPLETE_UNATTRIBUTED",a.compare(observe(2,twoCardPixels(true),s),1));
        s.remove(0);assertEquals("OTHER_CARD_OCR_COUNT_OR_OWNERSHIP_CHANGED_STOP",a.compare(observe(2,twoCardPixels(true),s),1));
        s.add(line("different",156,511,60,15,.95));
        assertEquals("OTHER_CARD_OCR_TEXT_CHANGED_STOP",a.compare(observe(2,twoCardPixels(true),s),1));
    }
    @Test public void moreCompleteGateAcceptsSafeMeasurementShiftOnUnchangedOtherCard(){
        var r=twoCardRows(false,"20/30");var s=twoCardRows(true,"30/30");
        r.set(2,line("更多活动",25,110,110,25,.95));s.set(2,r.get(2));
        s.set(4,line("Other activity",57,403,260,24,.95));s.set(5,line("+10",416,463,50,25,.95));
        var a=MaterialReplayObservation.observe(MaterialReplayObservation.Kind.MORE,11,twoCardPixels(false),W,H,r,null);
        var b=MaterialReplayObservation.observe(MaterialReplayObservation.Kind.MORE,12,twoCardPixels(true),W,H,s,null);
        assertEquals(Status.SKIP_COMPLETE,b.cards().status());
        assertEquals("OBSERVED_SELECTED_CARD_MARKED_COMPLETE_UNATTRIBUTED",a.compare(b,1));
    }
    @Test public void ownershipConflictsStopEvenWhenCounterCompletes(){
        for(boolean full:List.of(false,true)){
            var a=observe(1,twoCardPixels(false),twoCardRows(false,full?"20/30":"0/30"));
            String counter=full?"30/30":"10/30";
            var s=twoCardRows(true,counter);s.add(line("overlap",55,401,30,20,.95));
            assertEquals("AFTER_ROW_OVERLAP_STOP",a.compare(observe(2,twoCardPixels(true),s),1));
            s=twoCardRows(true,counter);s.set(4,line("Other activity",20,401,265,25,.95));
            assertEquals("AFTER_ROW_CARD_BOUNDARY_STOP",a.compare(observe(2,twoCardPixels(true),s),1));
            s=twoCardRows(true,counter);s.set(5,line("+10",387,462,51,26,.95));
            assertEquals("AFTER_ROW_BADGE_BOUNDARY_STOP",a.compare(observe(2,twoCardPixels(true),s),1));
            s=twoCardRows(true,counter);s.set(5,line("+10",390,510,51,26,.95));
            assertEquals("AFTER_POINT_BADGE_OWNERSHIP_STOP",a.compare(observe(2,twoCardPixels(true),s),1));
            s=twoCardRows(true,counter);s.add(line("+10",414,510,51,26,.95));
            assertEquals("AFTER_POINT_BADGE_OWNERSHIP_STOP",a.compare(observe(2,twoCardPixels(true),s),1));
            s=twoCardRows(true,counter);s.set(5,line("+10",414,462,51,26,.84));
            assertEquals("BLOCKED",a.compare(observe(2,twoCardPixels(true),s),1));
            s=twoCardRows(true,counter);s.add(line("locked",400,490,45,10,.95));
            var b=observe(2,twoCardPixels(true),s);
            assertEquals("AFTER_ROW_BADGE_ROLE_CONFLICT_STOP",a.compare(b,1));
            if(full)assertEquals(Status.UNKNOWN_PROGRESS,b.cards().status());
        }
    }
    @Test public void unknownAfterAssociationCannotUseIndependentSkipAsSuccess(){
        var s=completedRows("10/30","10");s.set(2,line("更多活动",25,110,110,25,.95));
        var b=observe(2,completedPixels(),s);assertEquals(Status.UNKNOWN_PROGRESS,b.cards().status());
        assertEquals("COUNTER_CONFLICT_STOP",before().compare(b,1));
        s=completedRows("10/30","10");s.set(1,line("10",390,310,24,17,.95));
        assertEquals("AFTER_NUMBER_TICK_ASSOCIATION_STOP",before().compare(observe(2,completedPixels(),s),1));
    }
    @Test public void alreadyCompletedOtherCardRetainsNumberTickAssociation(){
        byte[] p=twoCardPixels(false),q=twoCardPixels(true),done=completedPixels();
        for(int y=149;y<340;y++)for(int x=25;x<509;x++){
            p[(y+231)*W+x]=done[y*W+x];q[(y+231)*W+x]=done[y*W+x];
        }
        var r=twoCardRows(false,"20/30");var s=twoCardRows(true,"30/30");
        r.set(5,line("10",433,466,24,17,.95));s.set(5,line("10",434,467,24,17,.96));
        var a=observe(1,p,r);assertEquals(List.of(1),a.candidates());
        assertEquals("OBSERVED_SELECTED_CARD_MARKED_COMPLETE_UNATTRIBUTED",a.compare(observe(2,q,s),1));
        s.set(5,line("10",433,530,24,17,.96));
        assertEquals("AFTER_NUMBER_TICK_ASSOCIATION_STOP",a.compare(observe(2,q,s),1));
        s.set(5,line("10",434,467,24,17,.96));s.add(line("10",350,530,24,17,.95));
        assertEquals("AFTER_NUMBER_TICK_MULTIPLICITY_STOP",a.compare(observe(2,q,s),1));
    }
    @Test public void checkinUsesActualRowsAndExistingSameSlotComparison(){
        var rows=nativeRows(DailyCheckInRegionTest.rows());byte[] p=DailyCheckInRegionTest.pixels();
        var a=MaterialReplayObservation.observe(MaterialReplayObservation.Kind.CHECKIN,1,p,552,201,rows,null);assertEquals(List.of(4),a.candidates());
        DailyCheckInRegionTest.fill(p,245,88,300,143,250);DailyCheckInRegionTest.tick(p,272);rows.remove(8);
        var b=MaterialReplayObservation.observe(MaterialReplayObservation.Kind.CHECKIN,2,p,552,201,rows,null);
        assertEquals("OBSERVED_SAME_SLOT_MARKED_COMPLETE_UNATTRIBUTED",a.compare(b,4));
        rows.remove(1);assertTrue(MaterialReplayObservation.observe(MaterialReplayObservation.Kind.CHECKIN,3,p,552,201,rows,null).candidates().isEmpty());
        var missing=nativeRows(DailyCheckInRegionTest.rows());missing.remove(8);
        assertTrue(MaterialReplayObservation.observe(MaterialReplayObservation.Kind.CHECKIN,1,DailyCheckInRegionTest.pixels(),552,201,missing,null).candidates().isEmpty());
    }
    @Test public void visualCirclePreviewDoesNotInventDayOrGrantFakeSelection(){
        var r=nativeRows(DailyCheckInRegionTest.rows());
        r.subList(1,8).clear();
        r.add(line("Day 1 Day 2 Day 3 Day 4 Day 5 Day 6 Day 7",35,153,468,25,.95));
        r.add(line("",53,107,17,15,.2));
        var a=MaterialReplayObservation.observe(MaterialReplayObservation.Kind.CHECKIN,1,DailyCheckInRegionTest.pixels(),552,201,r,null);
        assertTrue(a.hasVisualPreview());assertTrue(a.candidates().isEmpty());
        assertTrue(a.summary().contains("VISUAL_CIRCLE_PREVIEW_ONLY"));
        assertTrue(a.summary().contains("dayIdentity=NONE actionAuthority=NONE"));
        assertTrue(a.summary().contains("lowConfidence085=1"));
        assertTrue(a.summary().contains("status=BAD_OCR"));
        assertEquals("BLOCKED",a.compare(a,1));
        var canceled=MaterialReplayObservation.observe(MaterialReplayObservation.Kind.CHECKIN,2,DailyCheckInRegionTest.pixels(),552,201,r,()->true);
        assertFalse(canceled.hasVisualPreview());assertFalse(canceled.summary().contains("VISUAL_CIRCLE_PREVIEW_ONLY"));
        r.add(line("unknown",260,108,20,8,.2));
        assertFalse(MaterialReplayObservation.observe(MaterialReplayObservation.Kind.CHECKIN,3,DailyCheckInRegionTest.pixels(),552,201,r,null).hasVisualPreview());
    }
    private List<FrameStructureEvidence.Line> parentRows(String counter,boolean completed){
        var r=completed?completedRows(counter,"10"):rows(counter);
        r.set(3,line(counter,388,50,92,25,.95));r.add(line("今日积分",25,50,110,25,.95));return r;
    }
    @Test public void observedParentAggregateIsTypedAndComparedWithoutChildAttribution(){
        var a=observe(1,fixture.pixels(),parentRows("45/120",false));
        assertEquals(List.of(1),a.candidates());
        assertEquals(com.aliothmoon.maafw.rewards.RewardRegionGate.CounterRole.AGGREGATE_PROGRESS,a.cards().progress().role());
        var region=a.cards().geometry().key();var parent=a.cards().progress().key();
        assertEquals(region.source(),parent.source());assertEquals(region.materialId(),parent.materialId());assertEquals(region.observation(),parent.observation());assertNotEquals(region.regionId(),parent.regionId());
        assertTrue(a.summary().contains("progressRole=AGGREGATE_PROGRESS fraction=45/120"));
        var b=observe(2,completedPixels(),parentRows("55/120",true));
        assertEquals("OBSERVED_SELECTED_CARD_MARKED_COMPLETE_UNATTRIBUTED",a.compare(b,1));
        assertEquals(Status.SKIP_COMPLETE,observe(2,completedPixels(),parentRows("120/120",true)).cards().status());
    }
    @Test public void parentRequiresActualUniqueAlignedHighConfidenceRows(){
        var r=parentRows("45/120",false);r.remove(4);
        assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
        r=parentRows("45/120",false);r.set(4,line("今日积分",25,50,110,25,.84));
        assertTrue(observe(1,fixture.pixels(),r).candidates().isEmpty());
        r=parentRows("45/120",false);r.set(3,line("45/120",388,80,92,25,.95));
        assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
        r=parentRows("45/120",false);r.add(line("今日积分",25,15,110,25,.95));
        assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
        r=parentRows("45/120",false);r.add(line("0/30",388,110,92,25,.95));
        assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
        r=parentRows("45/120",false);r.set(4,line("今日积分",25,110,110,25,.95));
        assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
    }
    @Test public void parentCannotSilentlyBecomeChildOrMaskCompleteGateBadHierarchy(){
        var r=parentRows("120/120",false);r.set(2,line("每日活动",25,20,110,25,.95));
        assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
        r=parentRows("120/120",false);r.remove(2);
        assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
        r=parentRows("120/120",false);r.set(4,line("Unknown heading",25,50,110,25,.95));
        assertEquals(Status.UNKNOWN_PROGRESS,observe(1,fixture.pixels(),r).cards().status());
        assertEquals(com.aliothmoon.maafw.rewards.RewardRegionGate.CounterRole.REGION_PROGRESS,before().cards().progress().role());
    }
}
