package com.aliothmoon.maafw.once;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.AutonomousCardFixture.*;
public class AutonomousObservationTest {
    @Test public void nativeTwoBodyFamilyIsDistinctAndPuzzleBeforeQuoteIsExplained(){
        var frame=observe(pixels(false,false),rows(false,false));assertEquals(LiveRewardCardObservation.Status.OBSERVED,frame.status());
        assertEquals(2,frame.cards().size());int configured=0;
        for(var card:frame.cards())if(card.configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()))configured++;
        assertEquals(1,configured);assertFalse(frame.cards().get(0).sameTask(frame.cards().get(1)));
    }
    @Test public void reliableWrappedBodyIsJoinedOnlyInGeometricReadingOrder(){
        var lines=rows(false,false);lines.removeIf(r->r.text().equals(A));
        lines.add(MoreCardFixture.line("these trivia questions",123,321,240,12));lines.add(MoreCardFixture.line("Challenge yourself with",123,305,240,12));
        var f=observe(pixels(false,false),lines);assertEquals(LiveRewardCardObservation.Status.OBSERVED,f.status());
        assertTrue(f.cards().get(0).configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()));
        lines=rows(false,false);lines.removeIf(r->r.text().equals(A));
        lines.add(MoreCardFixture.line("these trivia questions",123,305,240,12));lines.add(MoreCardFixture.line("Challenge yourself with",123,321,240,12));
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
    }
    @Test public void omittedUnknownOrDuplicateSiblingBodyStopsWholeKnownFamily(){
        for(String bad:List.of("MISSING","Unknown new obligation",A)){
            var lines=rows(false,false);if(bad.equals("MISSING"))lines.removeIf(r->r.text().equals(B));
            else MoreVisibleCardObservationTest.replace(lines,B,bad,.95);
            assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        }
        var lines=rows(false,false);MoreVisibleCardObservationTest.replace(lines,B,B,.4);
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
    }
    @Test public void missingOrLowSameTitleCannotMakeTheBrotherDisappear(){
        var lines=rows(false,false);lines.removeIf(r->r.text().equals(TITLE)&&r.y()>400);
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        lines=rows(false,false);for(int i=0;i<lines.size();i++){var r=lines.get(i);if(r.text().equals(TITLE)&&r.y()>400)lines.set(i,new FrameStructureEvidence.Line(r.text(),r.x(),r.y(),r.width(),r.height(),.3));}
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
    }
    @Test public void unrecognizedInterveningCompletedCardCannotBeIgnored(){
        var lines=rows(false,false);MoreVisibleCardObservationTest.replace(lines,"Complete this puzzle","Unknown completed item",.95);
        assertEquals(LiveRewardCardObservation.Reason.MORE_FAMILY_COVERAGE,observe(pixels(false,false),lines).reason());
    }
    @Test public void missingBothNativeCompletedBoundariesStopsCoverage(){
        var lines=rows(false,false);lines.removeIf(r->r.text().equals("Complete this puzzle")||r.text().equals("Quote of the day"));
        assertEquals(LiveRewardCardObservation.Reason.MORE_FAMILY_COVERAGE,observe(pixels(false,false),lines).reason());
    }
    @Test public void unexplainedGapRowOrUnknownIndicatorTokenStopsFamily(){
        var lines=rows(false,false);lines.add(MoreCardFixture.line("Unknown",55,414,265,10));
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        lines=rows(false,false);lines.add(MoreCardFixture.line("Later",466,325,19,12));
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
    }
    @Test public void nativeCompletedBodylessObservationDoesNotInventSelectedIdentity(){
        var f=observe(pixels(true,false),rows(true,false));assertEquals(LiveRewardCardObservation.Status.OBSERVED,f.status());
        assertEquals(2,f.cards().size());assertTrue(f.cards().get(0).complete());
        assertFalse(f.cards().get(0).configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()));
    }
}
