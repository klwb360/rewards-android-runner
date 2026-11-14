package com.aliothmoon.maafw.once;

/** One append of closed native alignment evidence. No pixel, text, input or admission API. */
final class UiImageAlignmentPublication {
    private final ReviewShizukuPngBinding.AlignmentCompletion completion;
    private final Object owner, session, epoch;
    private final long deadline;
    private boolean used;

    UiImageAlignmentPublication(ReviewShizukuPngBinding.AlignmentCompletion completion,
            Object owner, Object session, Object epoch, long deadline) {
        this.completion = completion;
        this.owner = owner;
        this.session = session;
        this.epoch = epoch;
        this.deadline = deadline;
    }

    synchronized String publish(long now, boolean current, boolean hidden, boolean sourceReady) {
        // A failed append also spends its sealed receipt; it cannot be repackaged for a retry.
        boolean consumed = completion != null && completion.consume(owner, session, epoch, now);
        boolean qualified = !used && consumed && completion.liveTransport()
                && current && hidden && sourceReady && now >= 0 && now < deadline
                && now < completion.deadline()
                && NativeProgressPublication.frameFresh(now, completion.lower());
        used = true;
        String head = "alignmentEvidence=" + (qualified ? "SOURCE_CHECKED_NON_ATOMIC" : "UNQUALIFIED")
                + " coordinateSpace=FULL_FRAME_PIXELS mapping=IDENTITY_ONLY captureAtomic=false"
                + " counterAuthority=false actionAuthority=false taskTaps=0";
        if (!qualified) {
            return head + " imageBinding=NOT_ESTABLISHED regions=SUPPRESSED publicationFence=REJECTED_AT_APPEND";
        }
        return head + " imageBinding=SAME_NATIVE_FRAME sourceTiming=PRODUCE_ENVELOPE_ONLY "
                + completion.result().summary(true) + " publicationFence=CURRENT_AT_APPEND";
    }
}
