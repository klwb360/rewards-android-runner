package com.aliothmoon.maafw.once;

/** Fixed scalars recorded by existing decisions. Reading a snapshot never checks authority. */
final class UiImageAlignmentDiagnostics {
    enum Prepare { NOT_ATTEMPTED, DUPLICATE, TRANSPORT_UNCLAIMED, SEAL_ATTEMPTED, IDENTITY_MISSING,
        INPUT_MISSING, CANCELED, CURRENT_REJECTED, SHAPE_MISMATCH, PIXEL_MISMATCH,
        REFINER_UNAVAILABLE, ERROR, ACCEPTED }
    enum Current { NOT_CHECKED, BEFORE_LOWER, DEADLINE, FRAME_STALE, UI_GEOMETRY_REJECTED, REVOKED, ACCEPTED }
    enum After { NOT_CHECKED, MISSING, CANDIDATE_TIME, BEFORE_CANDIDATE, STALE,
        IMAGE_SOURCE_MISMATCH, UI_SOURCE_MISMATCH, CURRENT_REJECTED, ACCEPTED }
    enum Seal { NOT_ATTEMPTED, DUPLICATE, CANDIDATE_MISSING, CANDIDATE_MISMATCH,
        SOURCE_TIMING, AFTER_REJECTED, CANCELED, ACCEPTED }
    enum Publication { NOT_CHECKED, NOT_SEALED, BEFORE_SEAL, CANDIDATE_TIME_MISSING, AFTER_REJECTED, ACCEPTED }
    enum Receipt { NOT_ATTEMPTED, DUPLICATE, BACKEND_PHASE, BACKEND_FAILURE, CANCELED,
        CLEANUP_MISSING, CLEANUP_REJECTED, TRANSPORT_MISMATCH, CANDIDATE_MISSING,
        CANDIDATE_MISMATCH, SOURCE_WINDOW_MISSING, IMAGE_MATCH_REJECTED, READ_ERROR, SEAL_REJECTED, ACCEPTED }
    enum ImageMatch { NOT_CHECKED, PNG_MISSING, PNG_CLEANUP, PNG_STATUS, PNG_SHAPE, PNG_REPLAY,
        MAA_MISSING, MAA_CLEANUP, MAA_STATUS, MAA_POSTS, MAA_BINDING, MAA_TASK, MAA_QUIET,
        MAA_SCRATCH, MAA_IMAGE, MAA_TASKER, CANDIDATE_DIGEST, PNG_DIGEST_FORMAT,
        CANDIDATE_PNG_DIGEST, PNG_REPLAY_DIGEST, PNG_STANDARD_DIGEST, MAA_DIGEST, ACCEPTED }

    private final long uiStarted, uiAfterLower, imageLower;
    private Prepare prepare = Prepare.NOT_ATTEMPTED;
    private Current current = Current.NOT_CHECKED, firstCurrentReject = Current.NOT_CHECKED;
    private After after = After.NOT_CHECKED;
    private Seal seal = Seal.NOT_ATTEMPTED;
    private Publication publication = Publication.NOT_CHECKED;
    private boolean candidateFormed;
    private long observed = -1, sealed = -1;
    private long uiAfterAge = -1, uiFrameAge = -1, imageAge = -1, rejectedUiAfterAge = -1;
    private long sourceDuration = -1, sourceToCandidate = -1, candidateToSeal = -1;
    private long imageAfterRead = -1, imageAfterAge = -1, sealToPublication = -1;

    UiImageAlignmentDiagnostics(long uiStarted, long uiAfterLower, long imageLower) {
        this.uiStarted = uiStarted; this.uiAfterLower = uiAfterLower; this.imageLower = imageLower;
    }
    static long elapsed(long later, long earlier) {
        return earlier >= 0 && later >= earlier ? Math.min(later - earlier, 600_000L) : -1;
    }
    private void ages(long now) {
        uiAfterAge = elapsed(now, uiAfterLower); uiFrameAge = elapsed(now, uiStarted); imageAge = elapsed(now, imageLower);
    }
    synchronized void prepareAttempt(long now) { observed = now; ages(now); }
    synchronized void prepare(Prepare value) { prepare = value; candidateFormed |= value == Prepare.ACCEPTED; }
    synchronized void current(Current value, long now) {
        current = value; ages(now);
        if (value != Current.ACCEPTED && firstCurrentReject == Current.NOT_CHECKED) {
            firstCurrentReject = value; rejectedUiAfterAge = uiAfterAge;
        }
    }
    synchronized void afterAttempt(BingNavigationSnapshot.Ready value, long now) {
        ages(now);
        imageAfterRead = value == null ? -1 : elapsed(value.observed(), value.lower());
        imageAfterAge = value == null ? -1 : elapsed(now, value.lower());
    }
    synchronized void after(After value) { after = value; }
    synchronized void sealAttempt(long enter, long returned, long now) {
        ages(now); sourceDuration = elapsed(returned, enter);
        sourceToCandidate = elapsed(observed, returned); candidateToSeal = elapsed(now, observed);
    }
    synchronized void seal(Seal value, long now) {
        seal = value; if (value == Seal.ACCEPTED) sealed = now;
    }
    synchronized void publication(Publication value, long now) {
        publication = value; ages(now); sealToPublication = elapsed(now, sealed);
    }
    synchronized String summary() {
        return "alignmentDiag=2 prepareGate=" + prepare + " candidateFormed=" + candidateFormed
                + " currentGate=" + current + " firstCurrentReject=" + firstCurrentReject
                + " afterGate=" + after + " sealGate=" + seal + " publicationGate=" + publication
                + " uiAfterAgeMs=" + uiAfterAge + " firstRejectedUiAfterAgeMs=" + rejectedUiAfterAge
                + " uiFrameAgeMs=" + uiFrameAge + " imageAgeMs=" + imageAge
                + " sourceMs=" + sourceDuration + " sourceToCandidateMs=" + sourceToCandidate
                + " candidateToSealMs=" + candidateToSeal + " imageAfterReadMs=" + imageAfterRead
                + " imageAfterAgeMs=" + imageAfterAge + " sealToPublicationMs=" + sealToPublication;
    }

    static final class ReceiptState {
        private Receipt receipt = Receipt.NOT_ATTEMPTED;
        private ImageMatch imageMatch = ImageMatch.NOT_CHECKED;
        private PngImageAudit.Status pngStatus;
        private MaaImageBufferAudit.Status maaStatus;
        synchronized void receipt(Receipt value) { receipt = value; }
        synchronized void imageMatch(ImageMatch value) { imageMatch = value; }
        synchronized void pngStatus(PngImageAudit.Status value) { pngStatus = value; }
        synchronized void maaStatus(MaaImageBufferAudit.Status value) { maaStatus = value; }
        synchronized String summary() {
            return "receiptGate=" + receipt + " imageMatchGate=" + imageMatch
                    + " pngAuditStatus=" + (pngStatus == null ? "NOT_READ" : pngStatus)
                    + " maaAuditStatus=" + (maaStatus == null ? "NOT_READ" : maaStatus);
        }
    }
}
