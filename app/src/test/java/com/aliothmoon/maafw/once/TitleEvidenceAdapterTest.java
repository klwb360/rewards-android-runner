package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.AutonomousCardFixture.*;

/** Public known literals and synthetic rasters, not a reconstruction of Android v21 rows. */
public class TitleEvidenceAdapterTest {
    private static List<FrameStructureEvidence.Line> splitTitles(){
        var out=rows(false,false);out.removeIf(r->r.text().equals(TITLE));
        for(int top:new int[]{249,427}){
            out.add(MoreCardFixture.line("Do you know the",123,top+21,177,18));
            out.add(MoreCardFixture.line("answer?",123,top+42,92,18));
        }
        return out;
    }
    @Test public void twoOriginalTitleRowsBindEachCardWithoutSyntheticOcrRow(){
        var lines=splitTitles();var frame=observe(pixels(false,false),lines);
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,frame.status());assertEquals(2,frame.cards().size());
        assertEquals(0,frame.diagnostic().exactTitleRows());assertEquals(0,frame.diagnostic().knownSingleTitleRows());
        assertEquals(2,frame.diagnostic().targetTitleCount());assertEquals(2,frame.diagnostic().titleEvidence().size());
        for(var d:frame.diagnostic().titleEvidence()){
            assertEquals(RewardRegionLocator.TitleMatchKind.TWO_LINES,d.kind());assertEquals(2,d.partCount());assertEquals(.95,d.minConfidence(),0);
        }
        assertEquals(List.of(new Box(123,270,300,288),new Box(123,291,215,309)),frame.diagnostic().titleEvidence().get(0).rawBoxes());
        assertTrue(frame.cards().get(0).configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()));
        assertEquals(0,lines.stream().filter(r->r.text().equals(TITLE)).count());
    }
    @Test public void caseUnicodeSpaceAndTerminalQuestionVariantsAreOnlyKnownEquivalences(){
        for(String text:List.of("do you know the answer?"," DO\u00a0you\u2009know the answer？ ","Do you know the answer")){
            var lines=rows(false,false);for(int i=0;i<lines.size();i++){var r=lines.get(i);if(r.text().equals(TITLE))lines.set(i,new FrameStructureEvidence.Line(text,r.x(),r.y(),r.width(),r.height(),r.score()));}
            var f=observe(pixels(false,false),lines);assertEquals(LiveRewardCardObservation.Status.OBSERVED,f.status());assertEquals(2,f.diagnostic().knownSingleTitleRows());
        }
    }
    @Test public void missingLettersWrongWordsOrderOrInternalPunctuationNeverMatch(){
        for(String text:List.of("Do you know the ansver?","Do you know answer?","Do you the know answer?","Do? you know the answer","Doyou know the answer?")){
            var lines=rows(false,false);for(int i=0;i<lines.size();i++){var r=lines.get(i);if(r.text().equals(TITLE))lines.set(i,new FrameStructureEvidence.Line(text,r.x(),r.y(),r.width(),r.height(),r.score()));}
            assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        }
    }
    @Test public void threeTitlePartsAndCrossCardJoinsAreNotAllowed(){
        var lines=rows(false,false);lines.removeIf(r->r.text().equals(TITLE));
        for(int top:new int[]{249,427}){
            lines.add(MoreCardFixture.line("Do you",123,top+21,90,12));lines.add(MoreCardFixture.line("know the",123,top+36,110,12));
            lines.add(MoreCardFixture.line("answer?",123,top+51,100,12));
        }
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        lines=rows(false,false);lines.removeIf(r->r.text().equals(TITLE));
        lines.add(MoreCardFixture.line("Do you know the",123,270,177,18));lines.add(MoreCardFixture.line("answer?",123,448,92,18));
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
    }
    @Test public void lowPartAndExtraHeadingCannotBeDroppedToMakeUniqueEvidence(){
        var lines=splitTitles();for(int i=0;i<lines.size();i++){var r=lines.get(i);if(r.text().equals("answer?")&&r.y()<400)lines.set(i,new FrameStructureEvidence.Line(r.text(),r.x(),r.y(),r.width(),r.height(),.4));}
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        lines=splitTitles();lines.add(MoreCardFixture.line("Do you know the answer?",55,290,265,20));
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
    }
    @Test public void knownSingleOutsideCvCardOrAboveMoreIsStillAFullFrameConflict(){
        var lines=splitTitles();lines.add(MoreCardFixture.line("do you know the answer？",55,50,265,20));
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        byte[] p=pixels(false,false);RewardCardFixture.fill(p,25,25,509,170,255);
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(p,lines).status());
    }
    @Test public void titlePartsNeverBorrowBodyOrRelaxCounterAndLocalUnknownGates(){
        var lines=splitTitles();MoreVisibleCardObservationTest.replace(lines,A,"Wrong body",.95);
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        lines=splitTitles();lines.removeIf(r->r.text().equals("10/20"));assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        lines=splitTitles();lines.add(MoreCardFixture.line("Later",466,325,19,12));assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
    }
    @Test public void fullFrameCopyAndSafeDiagnosticsDoNotChangeOrExposeNativeText(){
        byte[] p=pixels(false,false),original=p.clone();var lines=splitTitles();var originalLines=new ArrayList<>(lines);var f=observe(p,lines);
        assertArrayEquals(original,p);assertEquals(originalLines,lines);String summary=f.diagnostic().summary();
        assertTrue(summary.contains("TWO_LINES"));assertTrue(summary.contains("partCount=2"));
        assertFalse(summary.contains("Do you"));assertFalse(summary.contains("answer?"));assertFalse(summary.contains(A));
    }
}
