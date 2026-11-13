package com.aliothmoon.maafw.rewards;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.rewards.RewardRegionLocator.*;

/** Synthetic full-frame coverage only. Never a card selection or completion test. */
public class GlobalKnownTitleEvidenceTest {
    private static final String T="Do you know the answer?";
    private static Row row(String text,int x,int y,int w,int h,double score){
        return new Row(text,new Box(x,y,x+w,y+h),score,Origin.SYNTHETIC);
    }
    @Test public void lowConfidenceNormalizedSingleKeepsOriginalRowAndScore(){
        Row r=row("do you know the answer？",20,5,180,12,.2);
        List<TitleEvidence> e=enumerateKnownTitleEvidence(List.of(r),T);
        assertEquals(1,e.size());assertSame(r,e.get(0).originalParts().get(0));
        assertEquals(.2,e.get(0).minConfidence(),0);assertEquals(r.box(),e.get(0).envelope());
        assertEquals(TitleMatchKind.QUESTION_SINGLE,e.get(0).kind());
    }
    @Test public void lowerOrOutsideCardTwoLineIsRetainedInOriginalOrder(){
        Row first=row("Do you know",10,880,100,10,.91);
        Row second=row("the answer?",11,892,100,10,.23);
        var e=enumerateKnownTitleEvidence(List.of(second,first),T);
        assertEquals(1,e.size());assertEquals(TitleMatchKind.TWO_LINES,e.get(0).kind());
        assertSame(first,e.get(0).originalParts().get(0));assertSame(second,e.get(0).originalParts().get(1));
        assertEquals(new Box(10,880,111,902),e.get(0).envelope());assertEquals(.23,e.get(0).minConfidence(),0);
    }
    @Test public void extraAndDuplicateMatchesAreNotSilentlyDiscarded(){
        Row a=row(T,10,20,170,10,.95),b=row(T,10,80,170,10,.95),c=row(T,10,140,170,10,.1);
        assertEquals(3,enumerateKnownTitleEvidence(List.of(a,b,c),T).size());
        var duplicated=enumerateKnownTitleEvidence(List.of(a,a),T);
        assertEquals(2,duplicated.size());assertSame(a,duplicated.get(0).originalParts().get(0));
        assertSame(a,duplicated.get(1).originalParts().get(0));
    }
    @Test public void nonLiteralAndNonadjacentPairsDoNotBecomeEvidence(){
        Row typo=row("Do you kn0w the answer?",10,20,180,10,.9);
        Row a=row("Do you know",10,100,100,10,.9),b=row("the answer?",10,122,100,10,.9);
        assertTrue(enumerateKnownTitleEvidence(List.of(typo,a,b),T).isEmpty());
    }
    @Test public void knownLiteralFragmentsAreVetoesEvenWhenAnOcrRowIsLowScore(){
        Row prefix=row("Do you know the",10,800,160,10,.2);
        Row suffix=row("answer？",10,820,100,10,.1);
        assertTrue(matchesKnownTitleFragment(prefix.text(),T));
        assertTrue(matchesKnownTitleFragment(suffix.text(),T));
        assertTrue(matchesKnownTitleFragment("  THE   ANSWER？ ",T));
        assertFalse(matchesKnownTitleFragment(T,T));
        assertFalse(matchesKnownTitleFragment("know",T));
        assertFalse(matchesKnownTitleFragment("Do you kn0w the",T));
        assertFalse(matchesKnownTitleFragment("you answer",T));
        assertFalse(matchesKnownTitleFragment("Do you know the answer extra",T));
    }
    @Test public void invalidInputCannotMasqueradeAsNoMatches(){
        try {enumerateKnownTitleEvidence(List.of(row(T,10,10,180,10,Double.NaN)),T);fail();}
        catch(IllegalArgumentException expected) { /* caller must HOLD */ }
    }
}