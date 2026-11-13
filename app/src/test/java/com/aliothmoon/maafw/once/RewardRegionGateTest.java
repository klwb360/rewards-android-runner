package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic OCR strings only. These are not recognition accuracy tests against uploaded pixels. */
public class RewardRegionGateTest {
    private Key key(long n) { return new Key(Source.SYNTHETIC_FIXTURE,"synthetic-series",n,"bounded-region"); }
    private List<Reading> rows(Key k,String s) { return List.of(new Reading(k,s,.95,true,true,CounterRole.REGION_PROGRESS,TextBasis.CONTEXTUAL_OCR)); }
    private Assessment assess(String s) { return RewardRegionGate.assess(key(1),rows(key(1),s)); }
    private void unknown(String s) { assertEquals(Gate.UNKNOWN_STOP,assess(s).gate()); }
    private Session session() { return new Session(RewardRegionGate.prepare(key(1),rows(key(1),"0/20"))); }
    private Difference compare(String before,String after) {
        return RewardRegionGate.compare(RewardRegionGate.assess(key(1),rows(key(1),before)),
            RewardRegionGate.assess(key(2),rows(key(2),after)));
    }
    @Test public void equalPositiveSkipsWithoutPlan() {
        assertEquals(Gate.COMPLETE_SKIP,assess("20/20").gate());
        assertNull(RewardRegionGate.prepare(key(1),rows(key(1),"20/20")));
    }
    @Test public void validIncompleteIncludesZeroNumerator() {
        assertEquals(Gate.INCOMPLETE_CANDIDATE_FLOW,assess("0/20").gate());
        assertEquals(Gate.INCOMPLETE_CANDIDATE_FLOW,assess("19/20").gate());
    }
    @Test public void denominatorMustBePositive() {
        unknown("0/0"); unknown("1/0");
        assertEquals(Reason.ZERO_DENOMINATOR,assess("0/0").reason());
    }
    @Test public void numeratorCannotExceedDenominator() {
        assertEquals(Reason.NUMERATOR_EXCEEDS_DENOMINATOR,assess("21/20").reason());
    }
    @Test public void noSignedDecimalOrGroupedNumbers() {
        for(String s:List.of("-1/20","+1/20","1.0/20","1,000/2000","10000/20000","1e1/20"))unknown(s);
    }
    @Test public void exactBoundedFractionNotTimeDescriptionOrBalance() {
        for(String s:List.of("5 小时 59 分","10积分","总积分 10/20","daily 10/20","0/20 +5","10 / 20 points"))unknown(s);
    }
    @Test public void narrowCompatibilityAcceptsFullwidthAndSeparatorWhitespace() {
        assertEquals(Gate.INCOMPLETE_CANDIDATE_FLOW,assess(" ０ / ２０ ").gate());
        assertEquals(Gate.COMPLETE_SKIP,assess("２０／２０").gate());
        assertEquals(Gate.INCOMPLETE_CANDIDATE_FLOW,assess("\u00a00\u202f/\u200920\u00a0").gate());
    }
    @Test public void noCompatibilityGuessForCircledSuperscriptOrHiddenCharacters() {
        for(String s:List.of("①/20","¹/20","1\u200b/20","1\n/20","1\t/20","1 0/20","1∕20","1⁄20"))unknown(s);
    }
    @Test public void nullEmptyLongAndOverflowStringsStop() {
        unknown(null);unknown("");unknown(" ");unknown("9".repeat(80)+"/99");
    }
    @Test public void missingOrMultipleCountersStopRatherThanChooseOne() {
        assertEquals(Reason.NO_UNIQUE_COUNTER,RewardRegionGate.assess(key(1),List.of()).reason());
        assertEquals(Reason.NO_UNIQUE_COUNTER,RewardRegionGate.assess(key(1),
            List.of(rows(key(1),"0/20").get(0),rows(key(1),"20/20").get(0))).reason());
    }
    @Test public void scopeFrameAndSourceMustMatchExactly() {
        Key other=new Key(Source.USER_STATIC_MATERIAL,"synthetic-series",1,"bounded-region");
        assertEquals(Reason.SOURCE_SCOPE_MISMATCH,RewardRegionGate.assess(key(1),rows(other,"0/20")).reason());
        assertEquals(Reason.SOURCE_SCOPE_MISMATCH,RewardRegionGate.assess(key(1),rows(key(2),"0/20")).reason());
        Key wrong=new Key(Source.SYNTHETIC_FIXTURE,"synthetic-series",1,"other-region");
        assertEquals(Reason.SOURCE_SCOPE_MISMATCH,RewardRegionGate.assess(key(1),rows(wrong,"0/20")).reason());
    }
    @Test public void croppedOrAmbiguousRegionStops() {
        assertEquals(Reason.INCOMPLETE_REGION,RewardRegionGate.assess(key(1),
            List.of(new Reading(key(1),"0/20",.95,false,true,CounterRole.REGION_PROGRESS,TextBasis.CONTEXTUAL_OCR))).reason());
        assertEquals(Reason.AMBIGUOUS_COUNTER_OWNERSHIP,RewardRegionGate.assess(key(1),
            List.of(new Reading(key(1),"0/20",.95,true,false,CounterRole.REGION_PROGRESS,TextBasis.CONTEXTUAL_OCR))).reason());
    }
    @Test public void lowOrInvalidConfidenceStops() {
        for(double c:new double[]{.799,-1,Double.NaN,Double.POSITIVE_INFINITY,1.001}) {
            assertEquals(Reason.LOW_OR_INVALID_CONFIDENCE,RewardRegionGate.assess(key(1),
                List.of(new Reading(key(1),"0/20",c,true,true,CounterRole.REGION_PROGRESS,TextBasis.CONTEXTUAL_OCR))).reason());
        }
        assertEquals(Gate.INCOMPLETE_CANDIDATE_FLOW,RewardRegionGate.assess(key(1),
            List.of(new Reading(key(1),"0/20",.8,true,true,CounterRole.REGION_PROGRESS,TextBasis.CONTEXTUAL_OCR))).gate());
    }
    @Test public void previewIsHypothesisOnlyNoActionCreditOrBinding() {
        Preview p=session().previewExplicitly(key(1),rows(key(1),"0/20"));
        assertEquals(PreviewState.PREVIEW_ONLY,p.state());
        assertEquals("UNBOUND",p.actor());assertEquals(0,p.executedActions());
        assertFalse(p.claimed());assertFalse(p.credited());assertFalse(p.accountProgressKnown());
        assertTrue(p.wouldDo().contains(Step.REQUIRE_NEW_SAME_REGION_COUNTER));
    }
    @Test public void canceledBeforePreviewHasNoSteps() {
        Session s=session();s.cancel();
        Preview p=s.previewExplicitly(key(1),rows(key(1),"0/20"));
        assertEquals(PreviewState.CANCELED,p.state());assertTrue(p.wouldDo().isEmpty());
    }
    @Test public void canceledAfterPreviewCannotVerify() {
        Session s=session();s.previewExplicitly(key(1),rows(key(1),"0/20"));s.cancel();
        assertEquals(Comparison.UNKNOWN_STOP,s.compareNewObservation(key(2),rows(key(2),"5/20")).comparison());
    }
    @Test public void noSecondPreview() {
        Session s=session();s.previewExplicitly(key(1),rows(key(1),"0/20"));
        assertEquals(PreviewState.ALREADY_PREVIEWED,s.previewExplicitly(key(1),rows(key(1),"0/20")).state());
    }
    @Test public void changedFrameOrOcrInvalidatesProposal() {
        Session s=session();
        assertEquals(PreviewState.STALE,s.previewExplicitly(key(1),rows(key(1),"1/20")).state());
        assertEquals(PreviewState.BLOCKED,s.previewExplicitly(key(1),rows(key(1),"0/20")).state());
        assertEquals(PreviewState.STALE,session().previewExplicitly(key(2),rows(key(2),"0/20")).state());
    }
    @Test public void inputListIsSnapshotted() {
        List<Reading> mutable=new ArrayList<>(rows(key(1),"0/20"));
        Session s=new Session(RewardRegionGate.prepare(key(1),mutable));
        mutable.clear();
        assertEquals(PreviewState.STALE,s.previewExplicitly(key(1),mutable).state());
    }
    @Test public void noComparisonBeforeExplicitPreview() {
        assertEquals(Comparison.UNKNOWN_STOP,session().compareNewObservation(key(2),rows(key(2),"5/20")).comparison());
    }
    @Test public void sameCountStopsWithoutRetry() {
        assertEquals(Comparison.SAME_COUNTER_STOP,compare("0/20","0/20").comparison());
    }
    @Test public void counterIncreaseIsUnattributedNotAccountCredit() {
        Difference d=compare("0/20","5/20");
        assertEquals(Comparison.COUNTER_INCREASE_UNATTRIBUTED,d.comparison());
        assertEquals(5,d.observedDelta());assertFalse(d.accountCreditKnown());assertFalse(d.causedByProposedAction());
    }
    @Test public void completeCounterStillIsNotCredited() {
        Difference d=compare("15/20","20/20");
        assertEquals(Comparison.COUNTER_COMPLETE_UNATTRIBUTED,d.comparison());
        assertFalse(d.accountCreditKnown());assertFalse(d.causedByProposedAction());
    }
    @Test public void changingTotalDecreaseOrMalformedAfterStops() {
        assertEquals(Comparison.CONFLICT_STOP,compare("0/20","5/30").comparison());
        assertEquals(Comparison.CONFLICT_STOP,compare("10/20","5/20").comparison());
        assertEquals(Comparison.UNKNOWN_STOP,compare("0/20","?/20").comparison());
        assertEquals(Comparison.UNKNOWN_STOP,compare("20/20","20/20").comparison());
    }
    @Test public void olderObservationOrChangedRegionIsNotVerification() {
        Assessment a=assess("0/20");
        assertEquals(Comparison.CONFLICT_STOP,RewardRegionGate.compare(a,assess("5/20")).comparison());
        Key k=new Key(Source.SYNTHETIC_FIXTURE,"synthetic-series",2,"different");
        assertEquals(Comparison.CONFLICT_STOP,RewardRegionGate.compare(a,RewardRegionGate.assess(k,rows(k,"5/20"))).comparison());
    }
    @Test public void unknownFirstComparisonIsTerminalForThisSession() {
        Session s=session();s.previewExplicitly(key(1),rows(key(1),"0/20"));
        assertEquals(Comparison.UNKNOWN_STOP,s.compareNewObservation(key(2),rows(key(2),"?/20")).comparison());
        assertEquals(Comparison.UNKNOWN_STOP,s.compareNewObservation(key(3),rows(key(3),"5/20")).comparison());
    }
    @Test public void nullOrBlockedPlanCannotPreview() {
        Preview p=new Session(null).previewExplicitly(key(1),rows(key(1),"0/20"));
        assertEquals(PreviewState.BLOCKED,p.state());assertTrue(p.wouldDo().isEmpty());
    }
    @Test public void sourceAndMaterialChangeCannotProveProgress() {
        Key other=new Key(Source.USER_STATIC_MATERIAL,"synthetic-series",2,"bounded-region");
        assertEquals(Comparison.CONFLICT_STOP,RewardRegionGate.compare(assess("0/20"),
            RewardRegionGate.assess(other,rows(other,"5/20"))).comparison());
        Key otherMaterial=new Key(Source.SYNTHETIC_FIXTURE,"other",2,"bounded-region");
        assertEquals(Comparison.CONFLICT_STOP,RewardRegionGate.compare(assess("0/20"),
            RewardRegionGate.assess(otherMaterial,rows(otherMaterial,"5/20"))).comparison());
    }

    @Test public void countdownWhitelistHallucinationNeverOpensRegion() {
        for(CounterRole role:List.of(CounterRole.COUNTDOWN,CounterRole.STREAK,CounterRole.UNKNOWN)) {
            assertEquals(Reason.NON_PROGRESS_ROLE,RewardRegionGate.assess(key(1),List.of(
                new Reading(key(1),"5/859",.99,true,true,role,TextBasis.INDEPENDENTLY_CROSSCHECKED))).reason());
        }
        assertEquals(Reason.UNCONFIRMED_NUMERIC_OCR,RewardRegionGate.assess(key(1),List.of(
            new Reading(key(1),"5/859",.99,true,true,CounterRole.REGION_PROGRESS,TextBasis.NUMERIC_ONLY_UNCONFIRMED))).reason());
    }
    @Test public void aggregateIsNotChildProgress() {
        Assessment a=RewardRegionGate.assess(key(1),List.of(new Reading(key(1),"0/20",.99,true,true,
            CounterRole.AGGREGATE_PROGRESS,TextBasis.CONTEXTUAL_OCR)));
        assertEquals(CounterRole.AGGREGATE_PROGRESS,a.role());
        assertEquals(Comparison.CONFLICT_STOP,RewardRegionGate.compare(a,
            RewardRegionGate.assess(key(2),rows(key(2),"5/20"))).comparison());
    }
    @Test public void nullNewObservationStopsSession() {
        Session s=session();s.previewExplicitly(key(1),rows(key(1),"0/20"));
        assertEquals(Comparison.UNKNOWN_STOP,s.compareNewObservation(null,null).comparison());
        assertEquals(Comparison.UNKNOWN_STOP,s.compareNewObservation(key(2),rows(key(2),"5/20")).comparison());
    }
}
