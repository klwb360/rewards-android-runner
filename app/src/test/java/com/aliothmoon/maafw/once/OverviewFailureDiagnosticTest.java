package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.OverviewFailureDiagnostic.Checkpoint;

/** Synthetic fixed-state diagnostics, not capture or device acceptance. */
public class OverviewFailureDiagnosticTest {
    private static OnceBackend.Result nativeResult(OnceBackend.Phase phase,OnceBackend.Failure failure){
        return new OnceBackend.Result(null,987654321L,phase,OnceBackend.Admission.CONSUMED,
                OnceBackend.Cancellation.NOT_REQUESTED,true,failure,OnceBackend.Cleanup.CONFIRMED,
                OnceBackend.Quiescence.CONFIRMED,OnceBackend.Memory.WIPED);
    }
    @Test public void imageSourceSealAndPublicationFailuresStayDistinctAfterCleanup(){
        for(var point:new Checkpoint[]{Checkpoint.IMAGE_BACKEND,Checkpoint.SOURCE_AFTER,Checkpoint.SEAL,Checkpoint.PUBLICATION}){
            var d=new OverviewFailureDiagnostic();d.beginFrame(1);d.at(point);d.failed();
            String original=d.summary();d.at(Checkpoint.CLEANUP);d.failed(Checkpoint.CLEANUP);d.beginFrame(2);
            assertEquals(original,d.summary());assertTrue(original.contains("failureCheckpoint="+point));
            assertTrue(original.contains("diagnosticFrame=1"));
        }
    }
    @Test public void backendFailureKeepsActualOutcomeWithoutOwnerRunIdOrHit(){
        var d=new OverviewFailureDiagnostic();d.beginFrame(1);d.at(Checkpoint.IMAGE_BACKEND);
        d.nativeOutcome(nativeResult(OnceBackend.Phase.FAILED,OnceBackend.Failure.SOURCE_FAILURE));d.failed();
        String s=d.summary();assertTrue(s.contains("nativePhase=FAILED nativeFailure=SOURCE_FAILURE"));
        assertTrue(s.contains("nativeCleanup=CONFIRMED nativeQuiescence=CONFIRMED nativeMemory=WIPED"));
        assertFalse(s.contains("987654321"));assertFalse(s.contains("owner"));assertFalse(s.contains("hit="));
    }
    @Test public void verifiedNativePixelsWithoutRequiredFingerprintRemainVisibleAsSuch(){
        var d=new OverviewFailureDiagnostic();d.beginFrame(1);
        d.nativeOutcome(nativeResult(OnceBackend.Phase.COMPLETED,OnceBackend.Failure.NONE));
        d.imageAudits(PngImageAudit.Status.MATCH,MaaImageBufferAudit.Status.VERIFIED_EQUAL,false,true);
        d.at(Checkpoint.SEAL);d.failed();String s=d.summary();
        assertTrue(s.contains("failureCheckpoint=SEAL"));assertTrue(s.contains("pngAuditStatus=MATCH"));
        assertTrue(s.contains("maaAuditStatus=VERIFIED_EQUAL maaFingerprintPresent=false"));
        d.imageAudits(PngImageAudit.Status.MATCH,MaaImageBufferAudit.Status.VERIFIED_EQUAL,true,true);
        assertEquals(s,d.summary());
    }
    @Test public void nextFrameCannotBorrowEarlierSuccessfulNativeEvidence(){
        var d=new OverviewFailureDiagnostic();d.beginFrame(1);
        d.nativeOutcome(nativeResult(OnceBackend.Phase.COMPLETED,OnceBackend.Failure.NONE));
        d.imageAudits(PngImageAudit.Status.MATCH,MaaImageBufferAudit.Status.VERIFIED_EQUAL,true,true);
        d.beginFrame(2);d.at(Checkpoint.MODEL_PREPARATION);d.failed();String s=d.summary();
        assertTrue(s.contains("diagnosticFrame=2"));assertTrue(s.contains("nativePhase=NOT_OBSERVED"));
        assertTrue(s.contains("imageAuditRead=NOT_OBSERVED"));assertTrue(s.contains("pngAuditStatus=NOT_OBSERVED"));
        assertTrue(s.contains("maaAuditStatus=NOT_OBSERVED maaFingerprintPresent=false"));
    }
    @Test public void unavailableAuditReadIsNotConvertedToSuccessfulPixelEvidence(){
        var d=new OverviewFailureDiagnostic();d.imageAudits(PngImageAudit.Status.MATCH,null,true,false);
        String s=d.summary();assertTrue(s.contains("imageAuditRead=UNAVAILABLE"));
        assertTrue(s.contains("maaAuditStatus=NOT_OBSERVED maaFingerprintPresent=false"));
        assertTrue(s.matches("[A-Za-z0-9_= ]{1,1400}"));
    }
}
