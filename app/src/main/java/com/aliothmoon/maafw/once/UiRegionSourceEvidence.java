package com.aliothmoon.maafw.once;

/** One read's before/after metadata binding. This never authenticates image pixels or grants input. */
final class UiRegionSourceEvidence {
    private final Object owner, session, epoch;
    private final String nonce;
    private final BingNavigationSnapshot.Ready before, after;
    private final long beforeAt;
    private final NativeUiStructureProtocol.Report report;
    private boolean consumed;

    private UiRegionSourceEvidence(Pending pending, NativeUiStructureProtocol.Report report,
            BingNavigationSnapshot.Ready after) {
        owner = pending.owner;
        session = pending.session;
        epoch = pending.epoch;
        nonce = pending.nonce;
        before = pending.before;
        beforeAt = pending.beforeAt;
        this.report = report;
        this.after = after;
    }

    static Pending begin(Object owner, Object session, Object epoch, String nonce,
            BingNavigationSnapshot.Ready before, long beforeAt) {
        if (owner == null || session == null || epoch == null
                || !NativeUiStructureProtocol.validNonce(nonce) || before == null
                || before.kind() != BingNavigationSnapshot.Kind.TEMPLATE || !before.fresh(beforeAt)) {
            return null;
        }
        return new Pending(owner, session, epoch, nonce, before, beforeAt);
    }

    static final class Pending {
        private final Object owner, session, epoch;
        private final String nonce;
        private final BingNavigationSnapshot.Ready before;
        private final long beforeAt;
        private boolean completed;

        private Pending(Object owner, Object session, Object epoch, String nonce,
                BingNavigationSnapshot.Ready before, long beforeAt) {
            this.owner = owner;
            this.session = session;
            this.epoch = epoch;
            this.nonce = nonce;
            this.before = before;
            this.beforeAt = beforeAt;
        }

        synchronized UiRegionSourceEvidence complete(NativeUiStructureProtocol.Report report,
                BingNavigationSnapshot.Ready after, long now, boolean cleanup) {
            boolean first = !completed;
            completed = true;
            if (!first || !cleanup || !valid(report, nonce, before, beforeAt, after, now)) return null;
            return new UiRegionSourceEvidence(this, report, after);
        }
    }

    synchronized boolean matches(Object owner, Object session, Object epoch,
            NativeUiStructureProtocol.Report report, long now) {
        return !consumed && this.owner == owner && this.session == session && this.epoch == epoch
                && this.report == report && valid(report, nonce, before, beforeAt, after, now);
    }

    /** Even a rejected append consumes the binding; a new publication cannot replay it. */
    synchronized boolean consume(Object owner, Object session, Object epoch,
            NativeUiStructureProtocol.Report report, long now) {
        boolean matched = matches(owner, session, epoch, report, now);
        consumed = true;
        return matched;
    }

    /** Transfers this read once; UI publication and image alignment cannot both consume it. */
    synchronized UiImageGeometryBinding consumeGeometry(Object owner, Object session, Object epoch,
            NativeUiStructureProtocol.Report report, long now) {
        return UiImageGeometryBinding.consume(this, owner, session, epoch, report, now);
    }

    // Internal post-transfer checks retain the original source lifetime; they do not rearm consumption.
    boolean geometryFresh(long now) { return valid(report, nonce, before, beforeAt, after, now); }
    boolean geometrySourceMatches(BingNavigationSnapshot.Ready other) {
        return before.sameSource(other) && after.sameSource(other);
    }
    BingNavigationSnapshot.Shape geometryShape() { return before.profile().shape(); }
    long geometryObserved() { return after.observed(); }

    private static boolean valid(NativeUiStructureProtocol.Report report, String nonce,
            BingNavigationSnapshot.Ready before, long beforeAt,
            BingNavigationSnapshot.Ready after, long now) {
        if (report == null || report.regions() == null || !report.qualified()
                || !nonce.equals(report.nonce()) || after == null || !before.sameSource(after)
                || before.observed() > beforeAt || beforeAt > report.started()
                || !before.fresh(report.started()) || report.finished() > after.lower()
                || !after.fresh(now) || !NativeProgressPublication.frameFresh(now, report.started())) {
            return false;
        }
        var shape = before.profile().shape();
        return report.displayId() == 0 && report.left() >= 0 && report.top() >= 0
                && report.right() > report.left() && report.bottom() > report.top()
                && report.right() <= shape.width() && report.bottom() <= shape.height()
                && rotationMatches(report.rotationBefore(), shape.rotation())
                && rotationMatches(report.rotationAfter(), shape.rotation())
                && inside(report.regions().search(), shape) && inside(report.regions().reading(), shape);
    }

    private static boolean rotationMatches(int observed, int expected) {
        return observed == -1 || observed == expected;
    }

    private static boolean inside(NativeUiRegions.Region region, BingNavigationSnapshot.Shape shape) {
        return region == null || inside(region.card(), shape)
                && (region.summary() == null || inside(region.summary(), shape));
    }

    private static boolean inside(NativeUiRegions.Rect rect, BingNavigationSnapshot.Shape shape) {
        return rect != null && rect.left() >= 0 && rect.top() >= 0
                && rect.right() <= shape.width() && rect.bottom() <= shape.height();
    }
}
