package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.AutonomousCardFixture.*;

/** Synthetic layout hypothesis only; no claim about an actual AFTER image. */
public class CompactTwoPartFamilyAdapterTest {
    private static List<FrameStructureEvidence.Line> splitRows(int firstSecondTop){
        var out=rows(true,false);out.removeIf(r->r.text().equals(TITLE));
        out.add(MoreCardFixture.line("Do you know the",123,270,177,18));
        out.add(MoreCardFixture.line("answer?",123,firstSecondTop,92,18));
        out.add(MoreCardFixture.line("Do you know the",123,448,177,18));
        out.add(MoreCardFixture.line("answer?",123,469,92,18));
        return out;
    }
    @Test public void completedCompactTwoPartsAndUnchangedSiblingRemainObservations(){
        var lines=splitRows(291);var frame=observe(pixels(true,false),lines);
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,frame.status());assertEquals(2,frame.cards().size());
        assertTrue(frame.cards().get(0).complete());assertFalse(frame.cards().get(1).complete());
        assertFalse(frame.cards().get(0).configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()));
        assertEquals(LiveRewardCardObservation.BodyClass.EMPTY_COMPLETE,frame.diagnostic().familyItemDiagnostics().get(0).canonicalBody());
        assertEquals(LiveRewardCardObservation.BodyClass.SIBLING,frame.diagnostic().familyItemDiagnostics().get(1).canonicalBody());
        assertEquals(2,frame.diagnostic().titleEvidence().size());
        assertEquals(RewardRegionLocator.TitleMatchKind.TWO_LINES,frame.diagnostic().titleEvidence().get(0).kind());
        assertEquals(List.of(new Box(123,270,300,288),new Box(123,291,215,309)),frame.diagnostic().titleEvidence().get(0).rawBoxes());
        assertEquals(.95,frame.diagnostic().titleEvidence().get(0).minConfidence(),0);
    }
    @Test public void secondPartBeyondEightyPercentRemainsHeld(){
        var frame=observe(pixels(true,false),splitRows(304));
        assertEquals(LiveRewardCardObservation.Status.HOLD,frame.status());
    }
    @Test public void missingIndependentTickCannotUseCompletedGeometry(){
        byte[] p=pixels(true,false);RewardCardFixture.fill(p,465,282,490,307,255);
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(p,splitRows(291)).status());
    }
    @Test public void completedSecondPartAtNewEightyPercentBoundIsTheExplicitNewPositive(){
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,observe(pixels(true,false),splitRows(303)).status());
    }}
