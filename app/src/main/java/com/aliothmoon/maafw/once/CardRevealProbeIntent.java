package com.aliothmoon.maafw.once;

import java.util.concurrent.atomic.AtomicBoolean;

/** One visible probe. UI observations authorize only bounded scrolling or one region crop. */
final class CardRevealProbeIntent {
    private final RealCaptureClaim.ProcessEpoch epoch;
    private final Object owner, visible, request;
    private final long issued, deadline;
    private final boolean live;
    private final AtomicBoolean canceled = new AtomicBoolean();
    private int observations, swipes;
    private UiPermit activeUi, latestUi;
    private ScrollPermit activeScroll;
    private boolean cropSpent, cropDispatched;
    private UiPermit cropSource;

    CardRevealProbeIntent(VisibleCaptureAuthority.CardRevealApproval approval, long now) {
        if (approval == null || !approval.consume(now))
            throw new SecurityException("Fresh card reveal approval required");
        epoch = approval.epoch; owner = approval.owner; visible = approval.visible; request = approval.request;
        issued = approval.issued; deadline = approval.deadline; live = approval.live;
    }
    Object owner() { return owner; }
    RealCaptureClaim.ProcessEpoch epoch() { return epoch; }
    boolean matches(Object expectedOwner, Object expectedVisible, Object expectedRequest) {
        return owner == expectedOwner && visible == expectedVisible && request == expectedRequest;
    }
    long deadline() { return deadline; }
    boolean live() { return live; }
    boolean current(long now) { return !canceled.get() && now >= issued && now < deadline; }
    void cancel() { canceled.set(true); }
    synchronized int uiCount() { return observations; }
    synchronized int swipeCount() { return swipes; }
    synchronized boolean cropSpent() { return cropSpent; }

    synchronized UiPermit claimUi(Object observationOwner, long now) {
        if (!current(now) || observationOwner == null || activeUi != null || activeScroll != null
                || cropSpent || observations >= CardRevealProbeScope.MAX_UI_OBSERVATIONS)
            throw new SecurityException("Next card reveal observation unavailable");
        latestUi = null;
        activeUi = new UiPermit(this, ++observations, epoch, observationOwner, now,
                CardRevealProbeScope.uiDeadline(now, deadline));
        return activeUi;
    }
    synchronized void finishUi(UiPermit exact, boolean clean, boolean qualified, long now) {
        if (exact == null || activeUi != exact || !exact.readerClaimed || !uiCurrent(exact, now)
                || !clean || !qualified) {
            cancel(); throw new SecurityException("Exact clean qualified UI observation required");
        }
        exact.finished = true; activeUi = null; latestUi = exact;
    }
    private synchronized boolean uiCurrent(UiPermit exact, long now) {
        return exact != null && exact.intent == this
                && (activeUi == exact && !exact.finished || latestUi == exact && exact.finished)
                && now >= exact.issued && now < exact.deadline && current(now);
    }
    private synchronized boolean claimReader(UiPermit exact, long now) {
        boolean first = !exact.readerClaimed; exact.readerClaimed = true;
        return first && activeUi == exact && !exact.finished && uiCurrent(exact, now);
    }
    private boolean latest(UiPermit exact, long now) {
        return exact != null && exact == latestUi && exact.intent == this && exact.finished
                && now >= exact.issued && now < exact.deadline && current(now);
    }
    synchronized ScrollPermit claimScroll(UiPermit exact, ReviewShizukuSwipeActor.Direction direction, long now) {
        if (!latest(exact, now) || direction == null || activeUi != null || activeScroll != null || cropSpent
                || swipes >= CardRevealProbeScope.MAX_SWIPES)
            throw new SecurityException("Card reveal scroll unavailable");
        activeScroll = new ScrollPermit(this, ++swipes, exact, direction, now, Math.min(deadline, exact.deadline));
        latestUi = null;
        return activeScroll;
    }
    synchronized void finishScroll(ScrollPermit exact, ReviewShizukuSwipeActor.Result result,
            boolean clean, long now) {
        if (exact == null || activeScroll != exact || !scrollCurrent(exact, now) || !exact.consumed
                || live && !exact.actorClaimed || result == null || !result.commandExitZero()
                || !result.invocationAttempted() || !result.authorityConsumed() || !clean) {
            cancel(); throw new SecurityException("Card reveal scroll outcome unconfirmed");
        }
        exact.finished = true; activeScroll = null;
    }
    private synchronized boolean scrollCurrent(ScrollPermit exact, long now) {
        return exact != null && exact.intent == this && activeScroll == exact && !exact.finished
                && now >= exact.issued && now < exact.deadline && current(now);
    }
    private synchronized boolean claimActor(ScrollPermit exact, long now) {
        boolean first = !exact.actorClaimed; exact.actorClaimed = true;
        return first && live && scrollCurrent(exact, now);
    }
    private synchronized boolean consumeScroll(ScrollPermit exact, long now) {
        boolean first = !exact.consumed; exact.consumed = true;
        return first && (!live || exact.actorClaimed) && scrollCurrent(exact, now);
    }
    synchronized boolean claimCrop(UiPermit exact, long now) {
        boolean first = !cropSpent; cropSpent = true;
        boolean accepted = first && activeUi == null && activeScroll == null && latest(exact, now);
        if (accepted) cropSource = exact;
        return accepted;
    }
    synchronized boolean claimCropDispatch(UiPermit exact, long now) {
        boolean first = !cropDispatched; cropDispatched = true;
        return first && live && cropSpent && cropSource == exact && latest(exact, now)
                && activeUi == null && activeScroll == null;
    }

    static final class UiPermit {
        final CardRevealProbeIntent intent;
        final int index;
        final RealCaptureClaim.ProcessEpoch epoch;
        final Object owner;
        final long issued, deadline;
        private boolean readerClaimed, finished;
        private UiPermit(CardRevealProbeIntent intent, int index, RealCaptureClaim.ProcessEpoch epoch,
                Object owner, long issued, long deadline) {
            this.intent = intent; this.index = index; this.epoch = epoch; this.owner = owner;
            this.issued = issued; this.deadline = deadline;
        }
        boolean current(long now) { return intent.uiCurrent(this, now); }
        boolean current(RealCaptureClaim.ProcessEpoch expectedEpoch, Object expectedOwner, long now) {
            return epoch == expectedEpoch && owner == expectedOwner && current(now);
        }
        boolean claimReader(long now) { return intent.claimReader(this, now); }
    }
    static final class ScrollPermit {
        final CardRevealProbeIntent intent;
        final int index;
        final UiPermit observation;
        final ReviewShizukuSwipeActor.Direction direction;
        final long issued, deadline;
        private boolean actorClaimed, consumed, finished;
        private ScrollPermit(CardRevealProbeIntent intent, int index, UiPermit observation,
                ReviewShizukuSwipeActor.Direction direction, long issued, long deadline) {
            this.intent = intent; this.index = index; this.observation = observation; this.direction = direction;
            this.issued = issued; this.deadline = deadline;
        }
        boolean current(long now) { return intent.scrollCurrent(this, now); }
        boolean claimActor(long now) { return intent.claimActor(this, now); }
        boolean consume(long now) { return intent.consumeScroll(this, now); }
    }
}
