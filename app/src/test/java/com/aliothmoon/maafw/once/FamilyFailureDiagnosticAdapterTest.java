package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.AutonomousCardFixture.*;

/** Synthetic data checks typed diagnostic transport, never reconstructs a prior native frame. */
public class FamilyFailureDiagnosticAdapterTest {
    @Test public void readyFamilyKeepsExistingDecisionAndPublishesOnlyLocatorScalars(){
        var f=observe(pixels(false,false),rows(false,false));var d=f.diagnostic();
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,f.status());
        assertEquals(RewardRegionLocator.FamilyStatus.OBSERVED,d.familyStatus());
        assertEquals(RewardRegionLocator.FamilyReason.READY,d.familyReason());
        assertEquals(2,d.familyItemCount());assertNull(d.familyFailure());
        assertEquals(2,d.titleEvidence().size());assertEquals(2,f.cards().size());
        assertTrue(d.summary().contains("familyStatus=OBSERVED familyReason=READY familyItemCount=2 familyFailure=null"));
    }
    @Test public void splitOriginalTitleDiagnosticsSurviveLaterLocalConfidenceHold(){
        var lines=rows(false,false);lines.removeIf(r->r.text().equals(TITLE));
        for(int top:new int[]{249,427}){
            lines.add(MoreCardFixture.line("Do you know the",123,top+21,177,18));
            lines.add(MoreCardFixture.line("answer?",123,top+42,92,18));
        }
        MoreVisibleCardObservationTest.replace(lines,A,A,.4);
        var f=observe(pixels(false,false),lines);var d=f.diagnostic();
        assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());assertTrue(f.cards().isEmpty());
        assertEquals(LiveRewardCardObservation.Reason.MORE_ASSOCIATION_BLOCKED,f.reason());
        assertEquals(RewardRegionLocator.FamilyStatus.HOLD,d.familyStatus());
        assertEquals(RewardRegionLocator.FamilyReason.AMBIGUOUS_CARD_OR_STATE,d.familyReason());
        assertEquals(0,d.familyItemCount());assertNotNull(d.familyFailure());
        assertEquals(RewardRegionLocator.FailureGate.PARTITION_LOW_CONFIDENCE,d.familyFailure().gate());
        assertEquals(0,d.familyFailure().cardIndex());assertEquals(4,d.familyFailure().localRows());
        assertEquals(1,d.familyFailure().lowConfidenceRows());assertEquals(0,d.familyFailure().crossingRows());
        assertEquals(1,d.familyFailure().pointsCount());assertNotNull(d.familyFailure().cardBox());
        assertEquals(0,d.exactTitleRows());assertEquals(0,d.knownSingleTitleRows());
        assertEquals(2,d.titleEvidence().size());
        for(var t:d.titleEvidence())assertEquals(RewardRegionLocator.TitleMatchKind.TWO_LINES,t.kind());
    }
    @Test public void missingBodyRemainsHoldAndRetainsDistinctLocatorReason(){
        var lines=rows(false,false);lines.removeIf(r->r.text().equals(A));
        var f=observe(pixels(false,false),lines);var d=f.diagnostic();
        assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());
        assertEquals(RewardRegionLocator.FamilyReason.MISSING_BODY,d.familyReason());
        assertEquals(RewardRegionLocator.FailureGate.IDENTITY_BODY,d.familyFailure().gate());
        assertEquals(0,d.familyItemCount());assertNotNull(d.familyFailure());assertTrue(f.cards().isEmpty());
    }
    @Test public void duplicateBodyRemainsHoldAndDoesNotExposeEitherBody(){
        var lines=rows(false,false);MoreVisibleCardObservationTest.replace(lines,B,A,.95);
        var f=observe(pixels(false,false),lines);var d=f.diagnostic();
        assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());
        assertEquals(RewardRegionLocator.FamilyReason.DUPLICATE_BODY,d.familyReason());
        assertEquals(RewardRegionLocator.FailureGate.BODY_DUPLICATE,d.familyFailure().gate());
        assertEquals(0,d.familyItemCount());assertNotNull(d.familyFailure());
        assertFalse(d.summary().contains(A));assertFalse(d.summary().contains(B));
    }
    @Test public void missingCounterCannotInventALocatorFailureOrQualifyAFrame(){
        var lines=rows(false,false);lines.removeIf(r->r.text().equals("10/20"));
        var f=observe(pixels(false,false),lines);var d=f.diagnostic();
        assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());
        assertEquals(LiveRewardCardObservation.Reason.COUNTER_NOT_UNIQUE,f.reason());
        assertNull(d.familyStatus());assertNull(d.familyReason());assertNull(d.familyFailure());
        assertEquals(-1,d.familyItemCount());assertTrue(d.titleEvidence().isEmpty());
    }
    @Test public void fixedSchemaRejectsUnconfiguredBodyBeforeAdapterAdoption(){
        var lines=rows(false,false);MoreVisibleCardObservationTest.replace(lines,A,"Unexpected body",.95);
        var f=observe(pixels(false,false),lines);var d=f.diagnostic();
        assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());assertTrue(f.cards().isEmpty());
        assertEquals(LiveRewardCardObservation.Reason.MORE_ASSOCIATION_BLOCKED,f.reason());
        assertEquals(RewardRegionLocator.FamilyStatus.HOLD,d.familyStatus());
        assertEquals(RewardRegionLocator.FamilyReason.MISSING_BODY,d.familyReason());
        assertEquals(0,d.familyItemCount());assertEquals(RewardRegionLocator.FailureGate.IDENTITY_BODY,d.familyFailure().gate());
        assertEquals(LiveRewardCardObservation.AdapterGate.FAMILY_RESULT,d.adapterFirstGate());
    }
    @Test public void diagnosticTypeCannotCarryRawOcrRowsTextOrPixelBuffers(){
        assertTrue(RewardRegionLocator.FailureDiagnostic.class.isRecord());
        for(var component:RewardRegionLocator.FailureDiagnostic.class.getRecordComponents()){
            Class<?> type=component.getType();
            if(type==List.class){
                assertEquals("localRowDiagnostics",component.getName());
                assertTrue(component.getGenericType() instanceof java.lang.reflect.ParameterizedType);
                var generic=(java.lang.reflect.ParameterizedType)component.getGenericType();
                assertArrayEquals(new java.lang.reflect.Type[]{RewardRegionLocator.LocalRowDiagnostic.class},generic.getActualTypeArguments());
                assertTrue(RewardRegionLocator.LocalRowDiagnostic.class.isRecord());
                for(var scalar:RewardRegionLocator.LocalRowDiagnostic.class.getRecordComponents())
                    assertTrue("unsafe local-row scalar: "+scalar.getName(),scalar.getType().isPrimitive()||scalar.getType().isEnum()||scalar.getType()==RewardRegionLocator.Box.class);
            }else assertTrue("unsafe diagnostic component: "+component.getName(),
                type.isPrimitive()||type.isEnum()||type==RewardRegionLocator.Box.class);
        }
        var lines=rows(false,false);MoreVisibleCardObservationTest.replace(lines,A,A,.4);
        var summary=observe(pixels(false,false),lines).diagnostic().summary();
        for(String raw:List.of(TITLE,A,B,"Do you know the","answer?","更多活动"))assertFalse(summary.contains(raw));
    }
    @Test public void diagnosticCollectionLeavesInputPixelsAndOriginalRowsUntouched(){
        var lines=rows(false,false);MoreVisibleCardObservationTest.replace(lines,A,A,.4);
        var originals=List.copyOf(lines);byte[] p=pixels(false,false),originalPixels=p.clone();
        var f=observe(p,lines);assertNotNull(f.diagnostic().familyFailure());
        assertArrayEquals(originalPixels,p);assertEquals(originals,lines);
    }
}