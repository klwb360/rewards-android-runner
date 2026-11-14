package com.aliothmoon.maafw.once;

import java.util.concurrent.atomic.AtomicBoolean;

/** One visible review session. Every independent frame and scroll is claimed exactly once. */
final class OverviewScanIntent {
    private final RealCaptureClaim.ProcessEpoch epoch;
    private final Object owner, visible, request;
    private final long issued, deadline;
    private final boolean live;
    private final AtomicBoolean canceled = new AtomicBoolean();
    private int frames, swipes;
    private FramePermit pending;
    private ScrollPermit activeScroll;
    private boolean frameFinished, afterSwipe, scanning;
    OverviewScanIntent(VisibleCaptureAuthority.OverviewApproval approval, long now) {
        if (approval == null || !approval.consume(now)) throw new SecurityException("Fresh overview approval required");
        epoch = approval.epoch; owner = approval.owner; visible = approval.visible; request = approval.request;
        issued = approval.issued; deadline = approval.deadline; live = approval.live;
    }
    synchronized boolean matches(Object expectedOwner, Object expectedVisible, Object expectedRequest) {
        return owner == expectedOwner && visible == expectedVisible && request == expectedRequest;
    }
    boolean live() { return live; }
    long deadline() { return deadline; }
    boolean current(long now) { return !canceled.get() && now >= issued && now < deadline; }
    void cancel() { canceled.set(true); }
    synchronized FramePermit claimFrame(Object frameOwner, long now) {
        if (!current(now) || frameOwner == null || pending != null || frames >= OverviewScanScope.MAX_FRAMES
                || frames > 0 && !afterSwipe) throw new SecurityException("Next clean overview frame unavailable");
        afterSwipe = false; frameFinished = false;
        pending = new FramePermit(this, ++frames, epoch, frameOwner, now,
                OverviewScanScope.frameDeadline(now, deadline), live);
        return pending;
    }
    synchronized void frameFinished(FramePermit exact, boolean clean, boolean qualified, long now) {
        if (pending != exact || exact == null || frameFinished || !exact.current(epoch, exact.owner, now) || !clean || !qualified) {
            cancel(); throw new SecurityException("Exact qualified clean frame required");
        }
        frameFinished = true;
    }
    synchronized void beginDownwardScan(FramePermit exact, long now) {
        if (pending != exact || !frameFinished || !current(now)) throw new SecurityException("Current frame required");
        scanning = true;
    }
    synchronized ReviewShizukuSwipeActor.Direction direction() {
        return scanning ? ReviewShizukuSwipeActor.Direction.UP : ReviewShizukuSwipeActor.Direction.DOWN;
    }
    synchronized ScrollPermit claimScroll(FramePermit exact, long now) {
        if (!current(now) || pending != exact || !frameFinished || afterSwipe || activeScroll != null
                || frames >= OverviewScanScope.MAX_FRAMES || swipes >= OverviewScanScope.MAX_SWIPES)
            throw new SecurityException("Overview scroll unavailable");
        activeScroll = new ScrollPermit(this, ++swipes, exact, direction(), now, deadline);
        return activeScroll;
    }
    synchronized void scrollFinished(ScrollPermit permit, ReviewShizukuSwipeActor.Result result, long now) {
        if (permit == null || permit != activeScroll || permit.intent != this || permit.frame != pending || !frameFinished
                || !permit.completeOnce() || !current(now) || result == null || !result.commandExitZero()) {
            cancel(); throw new SecurityException("Overview scroll outcome unconfirmed");
        }
        pending = null; activeScroll = null; frameFinished = false; afterSwipe = true;
    }
    synchronized int frames() { return frames; }
    synchronized int swipes() { return swipes; }
    synchronized boolean budgetRemaining() { return frames < OverviewScanScope.MAX_FRAMES && swipes < OverviewScanScope.MAX_SWIPES; }
    static final class FramePermit {
        final OverviewScanIntent intent;
        final int index;
        final RealCaptureClaim.ProcessEpoch epoch;
        final Object owner;
        final long issued, deadline;
        final boolean live;
        private boolean uiClaimed, proofClaimed;
        private FramePermit(OverviewScanIntent intent, int index, RealCaptureClaim.ProcessEpoch epoch,
                Object owner, long issued, long deadline, boolean live) {
            this.intent = intent; this.index = index; this.epoch = epoch; this.owner = owner;
            this.issued = issued; this.deadline = deadline; this.live = live;
        }
        boolean current(RealCaptureClaim.ProcessEpoch epoch, Object owner, long now) {
            return this.epoch == epoch && this.owner == owner && now >= issued && now < deadline && intent.current(now);
        }
        synchronized boolean claimUi(long now) {
            boolean first = !uiClaimed; uiClaimed = true;
            return first && current(epoch, owner, now);
        }
        synchronized boolean claimProof(long now) {
            boolean first = !proofClaimed; proofClaimed = true;
            return first && current(epoch, owner, now);
        }
    }
    static final class ScrollPermit {
        final OverviewScanIntent intent;
        final int index;
        final FramePermit frame;
        final ReviewShizukuSwipeActor.Direction direction;
        private final long issued, deadline;
        private boolean consumed, completed, actorClaimed;
        private ScrollPermit(OverviewScanIntent intent, int index, FramePermit frame,
                ReviewShizukuSwipeActor.Direction direction, long issued, long deadline) {
            this.intent = intent; this.index = index; this.frame = frame; this.direction = direction;
            this.issued = issued; this.deadline = deadline;
        }
        synchronized boolean consume(long now) {
            boolean first = !consumed; consumed = true;
            return first && current(now);
        }
        synchronized boolean claimActor(long now) {
            boolean first=!actorClaimed;actorClaimed=true;
            return first&&intent.live()&&current(now);
        }
        synchronized boolean current(long now) {
            return !completed && now >= issued && now < deadline && intent.current(now);
        }
        private synchronized boolean completeOnce() {
            boolean first = consumed && !completed; completed = true; return first;
        }
    }
}
