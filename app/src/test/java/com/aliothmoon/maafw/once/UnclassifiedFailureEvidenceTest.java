package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.AutonomousCardFixture.*;

/** Synthetic geometry only. Never a reconstruction of wiped v24 native OCR text. */
public class UnclassifiedFailureEvidenceTest {
    private static final String PRIVATE_TEXT="SYNTHETIC_TOKEN_NOT_TO_BE_LOGGED";
    private static Box targetBox(){return observe(pixels(false,false),rows(false,false)).diagnostic().targetCard();}
    private static Box box(FrameStructureEvidence.Line r){return new Box(r.x(),r.y(),r.x()+r.width(),r.y()+r.height());}
    private static List<FrameStructureEvidence.Line> sixRows(int extraUnmodeled){
        var lines=rows(false,false);lines.removeIf(r->r.text().equals(A)||r.text().equals(TITLE)&&r.y()<400);
        lines.add(MoreCardFixture.line("Do you know the",123,270,177,18));lines.add(MoreCardFixture.line("answer?",123,291,92,18));
        lines.add(MoreCardFixture.line("Challenge yourself with",123,319,240,12));lines.add(MoreCardFixture.line("these trivia questions",123,335,240,12));
        Box card=targetBox();int x=card.left()+12,right=(int)Math.floor(card.left()+.161*card.width())+1;
        lines.add(new FrameStructureEvidence.Line(PRIVATE_TEXT,x,card.top()+105,right-x,8,.31));
        for(int i=0;i<extraUnmodeled;i++)lines.add(new FrameStructureEvidence.Line(PRIVATE_TEXT+"_"+i,card.left()+5,card.top()+120+i*10,20,4,.91+i*.01));
        return lines;
    }
    private static RewardRegionLocator.FailureDiagnostic failure(LiveRewardCardObservation.Frame f){
        assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());assertEquals(LiveRewardCardObservation.Reason.MORE_ASSOCIATION_BLOCKED,f.reason());
        assertTrue(f.cards().isEmpty());assertEquals(LiveRewardCardObservation.AdapterGate.FAMILY_RESULT,f.diagnostic().adapterFirstGate());
        assertTrue(f.diagnostic().familyItemDiagnostics().isEmpty());assertTrue(f.diagnostic().auxiliaryDiagnostics().isEmpty());
        assertEquals(-1,f.diagnostic().unexplainedCardsSeen());assertEquals(-1,f.diagnostic().unexplainedRowsSeen());
        var d=f.diagnostic().familyFailure();assertNotNull(d);assertEquals(RewardRegionLocator.FailureGate.PARTITION_CROSSING,d.gate());return d;
    }
    private static void completeOriginalRows(RewardRegionLocator.FailureDiagnostic d,List<FrameStructureEvidence.Line> lines){
        var local=new ArrayList<FrameStructureEvidence.Line>();for(var row:lines)if(d.cardBox().overlaps(box(row)))local.add(row);
        assertEquals(local.size(),d.localRows());assertEquals(local.size(),d.localRowDiagnostics().size());assertFalse(d.localRowsTruncated());
        Set<Box> seen=new HashSet<>();for(var scalar:d.localRowDiagnostics()){
            assertTrue(seen.add(scalar.box()));var source=local.stream().filter(r->box(r).equals(scalar.box())).findFirst().orElseThrow();
            assertEquals(source.score(),scalar.confidence(),0);
        }
    }
    private static void safeFullSummary(LiveRewardCardObservation.Frame frame){
        String summary=frame.diagnostic().summary();
        assertTrue("bounded summary leaves 2KiB for fixed runtime/native envelope",summary.length()+2048<8192);
        assertFalse(summary.contains(PRIVATE_TEXT));assertFalse(summary.contains(TITLE));assertFalse(summary.contains(A));assertFalse(summary.contains(B));
        for(var row:frame.diagnostic().familyFailure().localRowDiagnostics())assertTrue(summary.contains(row.toString()));
    }
    @Test public void firstFailingSixRowCardKeepsEveryOriginalBoxScoreAndGeometricRole(){
        var lines=sixRows(0);var frame=observe(pixels(false,false),lines);var d=failure(frame);completeOriginalRows(d,lines);
        assertEquals(6,d.localRows());assertEquals(0,d.unmodeledRows());assertEquals(4,d.centralRows());assertEquals(1,d.rightRows());assertEquals(1,d.unclassifiedRows());
        var original=lines.stream().filter(r->r.text().equals(PRIVATE_TEXT)).findFirst().orElseThrow();
        assertEquals(box(original),d.unclassifiedBox1());assertEquals(.31,d.unclassifiedScore1(),0);
        assertEquals(1,d.reportedUnclassified());assertFalse(d.unclassifiedTruncated());assertNull(d.unclassifiedBox2());assertEquals(-1,d.unclassifiedScore2(),0);
        assertNotEquals("NOT_AVAILABLE",d.unclassifiedBoundary1().name());assertEquals("LOCATOR_INPUT",d.localCoordinateSpace().name());
        assertEquals(d.cardBox().left()+.161*d.cardBox().width(),d.uBoundary(),1e-9);
        assertEquals(d.cardBox().left()+.18*d.cardBox().width(),d.cBoundary(),1e-9);
        assertEquals(d.cardBox().left()+.75*d.cardBox().width(),d.rBoundary(),1e-9);
        assertEquals(5,d.uniquePointsValue());assertEquals(.95,d.uniquePointsScore(),0); // Candidate scalars are not passed guards.
        Map<String,Integer> roles=new HashMap<>();for(var row:d.localRowDiagnostics())roles.merge(row.region().name(),1,Integer::sum);
        assertEquals(Integer.valueOf(4),roles.get("C_REGION"));assertEquals(Integer.valueOf(1),roles.get("R_REGION"));assertEquals(Integer.valueOf(1),roles.get("UNCLASSIFIED"));
        safeFullSummary(frame);
    }
    @Test public void allEightRowsAreRenderedWithoutTruncatingTheFirstCardEvidence(){
        var lines=sixRows(2);var frame=observe(pixels(false,false),lines);var d=failure(frame);completeOriginalRows(d,lines);
        assertEquals(8,d.localRowDiagnostics().size());assertFalse(d.localRowsTruncated());safeFullSummary(frame);
    }
    @Test public void ninthRowIsExplicitlyTruncatedWithoutChangingFirstGateOrTotal(){
        var lines=sixRows(3);var frame=observe(pixels(false,false),lines);var d=failure(frame);
        assertEquals(9,d.localRows());assertEquals(8,d.localRowDiagnostics().size());assertTrue(d.localRowsTruncated());
        assertEquals(1,d.unclassifiedRows());assertEquals(1,d.reportedUnclassified());safeFullSummary(frame);
        assertThrows(UnsupportedOperationException.class,()->d.localRowDiagnostics().clear());
    }
    @Test public void centerRightCrossingAndDiagnosticsNeverEditTheOriginalBatchOrPixels(){
        var lines=rows(false,false);Box card=targetBox();int split=(int)(card.left()+.75*card.width());
        var original=new FrameStructureEvidence.Line(PRIVATE_TEXT,split-4,card.top()+110,10,8,.94);lines.add(original);
        var originalRows=List.copyOf(lines);byte[] image=pixels(false,false),copy=image.clone();var frame=observe(image,lines);var d=failure(frame);
        assertEquals(box(original),d.unclassifiedBox1());assertEquals(.94,d.unclassifiedScore1(),0);
        assertEquals(originalRows,lines);assertArrayEquals(copy,image);safeFullSummary(frame);
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,observe(pixels(false,false),rows(false,false)).status());
    }
}