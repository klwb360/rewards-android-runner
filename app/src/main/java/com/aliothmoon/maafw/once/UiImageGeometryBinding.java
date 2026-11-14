package com.aliothmoon.maafw.once;

import java.util.function.BooleanSupplier;

/** Consumed UI geometry for one prospective image. No capture permission or pixel binding. */
final class UiImageGeometryBinding {
    private final UiRegionSourceEvidence source;
    private final Object owner, session, epoch;
    private final NativeUiRegions.Pair regions;
    private final BingNavigationSnapshot.Shape shape;
    private final long issuedAt;
    private boolean begun;

    private UiImageGeometryBinding(UiRegionSourceEvidence source, Object owner, Object session,
            Object epoch, NativeUiStructureProtocol.Report report, long issuedAt) {
        this.source = source;
        this.owner = owner;
        this.session = session;
        this.epoch = epoch;
        this.regions = report.regions();
        this.shape = source.geometryShape();
        this.issuedAt = issuedAt;
    }

    static UiImageGeometryBinding consume(UiRegionSourceEvidence source, Object owner, Object session,
            Object epoch, NativeUiStructureProtocol.Report report, long now) {
        if (source == null || !source.consume(owner, session, epoch, report, now)) return null;
        return new UiImageGeometryBinding(source, owner, session, epoch, report, now);
    }

    UiImageAlignmentCapture beginFrame(BingNavigationSnapshot.Ready imageBefore, long lower,
            long deadline, BooleanSupplier revoked) {
        return UiImageAlignmentCapture.begin(this, imageBefore, lower, deadline, revoked);
    }

    synchronized boolean claimFrame(BingNavigationSnapshot.Ready imageBefore, long lower,
            long deadline, BooleanSupplier revoked) {
        boolean first = !begun;
        begun = true;
        return first && revoked != null && !stopped(revoked) && lower >= issuedAt
                && deadline > lower && fresh(lower) && imageBefore != null
                && imageBefore.lower() >= source.geometryObserved() && imageBefore.fresh(lower)
                && sourceMatches(imageBefore) && width() <= OwnedFrame.MAX_DIMENSION
                && height() <= OwnedFrame.MAX_DIMENSION
                && (long) width() * height() <= OwnedFrame.MAX_BYTES;
    }

    Object owner() { return owner; }
    int width() { return shape.width(); }
    int height() { return shape.height(); }
    int rotation() { return shape.rotation(); }
    NativeUiRegions.Pair regions() { return regions; }
    boolean matchesOwners(Object owner, Object session, Object epoch) {
        return this.owner == owner && this.session == session && this.epoch == epoch;
    }
    boolean fresh(long now) { return now >= issuedAt && source.geometryFresh(now); }
    boolean sourceMatches(BingNavigationSnapshot.Ready ready) { return source.geometrySourceMatches(ready); }
    static boolean stopped(BooleanSupplier stop) {
        try { return stop == null || stop.getAsBoolean(); }
        catch (RuntimeException unknown) { return true; }
    }
}
