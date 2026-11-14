package com.aliothmoon.maafw.once;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Read-only identity-mapped image candidate. Admission and actual cleanup remain with PNG binding. */
final class UiImageAlignmentCapture {
    private final UiImageGeometryBinding geometry;
    private final BingNavigationSnapshot.Ready imageBefore;
    private final long lower, deadline;
    private final BooleanSupplier revoked;
    private boolean transportClaimed, prepared, sealAttempted;
    private Candidate candidate;
    private BingNavigationSnapshot.Ready imageAfter;
    private long sealedAt = -1;
    private long preparedObservedAt = -1;

    private UiImageAlignmentCapture(UiImageGeometryBinding geometry, BingNavigationSnapshot.Ready imageBefore,
            long lower, long deadline, BooleanSupplier revoked) {
        this.geometry = geometry;
        this.imageBefore = imageBefore;
        this.lower = lower;
        this.deadline = deadline;
        this.revoked = revoked;
    }

    static UiImageAlignmentCapture begin(UiImageGeometryBinding geometry, BingNavigationSnapshot.Ready imageBefore,
            long lower, long deadline, BooleanSupplier revoked) {
        return geometry != null && geometry.claimFrame(imageBefore, lower, deadline, revoked)
                ? new UiImageAlignmentCapture(geometry, imageBefore, lower, deadline, revoked) : null;
    }

    Object owner() { return geometry.owner(); }
    int width() { return geometry.width(); }
    int height() { return geometry.height(); }
    int rotation() { return geometry.rotation(); }
    long lower() { return lower; }
    long deadline() { return deadline; }
    boolean matchesOwners(Object owner, Object session, Object epoch) { return geometry.matchesOwners(owner, session, epoch); }
    boolean captureCurrent(long now) { return current(now); }
    synchronized boolean claimTransport() {
        boolean first = !transportClaimed && !prepared && !sealAttempted;
        transportClaimed = true;
        return first;
    }

    synchronized Candidate prepare(Object recognizer, OnceBackend.Owner owner, BackendPorts.FrameView actualFrame,
            byte[] gray, List<FrameStructureEvidence.Line> rows, BooleanSupplier cancel, long observedAt) {
        boolean first = !prepared;
        prepared = true;
        if (!first || !transportClaimed || sealAttempted || recognizer == null || owner == null || actualFrame == null || gray == null
                || rows == null || stopped(cancel) || !current(observedAt)) return null;
        try {
            long size = (long) width() * height();
            if (actualFrame.width() != width() || actualFrame.height() != height()
                    || actualFrame.size() != size || gray.length != size) return null;
            // The digest and candidate must describe these exact bytes, not a same-sized caller image.
            for (int i = 0; i < gray.length; i++) {
                if ((i & 4095) == 0 && (stopped(cancel) || stopped(revoked))) return null;
                int actual = actualFrame.unsignedByte(i);
                if (actual < 0 || actual > 255 || actual != (gray[i] & 255)) return null;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(gray);
            var result = UiRegionImageRefiner.observe(geometry.regions(), gray, width(), height(), rows,
                    () -> stopped(cancel) || stopped(revoked));
            if (result == null || stopped(cancel) || !current(observedAt)) return null;
            candidate = new Candidate(this, recognizer, owner, actualFrame, observedAt, cancel, digest, result);
            return candidate;
        } catch (RuntimeException | NoSuchAlgorithmException invalid) { return null; }
    }

    /** Metadata bracket only; not a receipt of source cleanup, atomic pixels or action authority. */
    boolean sourceMatchesAfter(BingNavigationSnapshot.Ready after, long afterCapture, long now) {
        return after != null && afterCapture >= lower && afterCapture <= now
                && after.lower() >= afterCapture && after.fresh(now) && imageBefore.sameSource(after)
                && geometry.sourceMatches(after) && current(now);
    }

    /** Binding supplies its actual source envelope and candidate after checking actual cleanup. No retry. */
    synchronized boolean sealSource(Candidate exact, Object recognizer, OnceBackend.Owner owner,
            long sourceEnter, long sourceReturned, BingNavigationSnapshot.Ready after, long now) {
        boolean first = !sealAttempted;
        sealAttempted = true;
        Candidate pending = candidate;
        try {
            if (!first || exact == null || pending != exact || !exact.matches(this, recognizer, owner)
                    || sourceEnter < lower || sourceReturned < sourceEnter || exact.observedAt < sourceReturned
                    || !sourceMatchesAfter(after, exact.observedAt, now) || stopped(exact.cancel)) return false;
            imageAfter = after;
            preparedObservedAt = exact.observedAt;
            sealedAt = now;
            return true;
        } finally {
            if (first) {
                // Actual binding checks terminal backend cleanup before issuing a completion. Backend
                // stop no longer changes after terminal; the joint-session revoked gate remains live.
                if (pending != null) pending.discardIdentity();
                candidate = null;
            }
        }
    }

    synchronized boolean publicationCurrent(long now) {
        return sealedAt >= 0 && now >= sealedAt && preparedObservedAt >= 0
                && sourceMatchesAfter(imageAfter, preparedObservedAt, now);
    }

    private boolean current(long now) {
        return now >= lower && now < deadline && NativeProgressPublication.frameFresh(now, lower)
                && geometry.fresh(now) && !stopped(revoked);
    }
    private static boolean stopped(BooleanSupplier stop) { return UiImageGeometryBinding.stopped(stop); }

    static final class Candidate {
        private UiImageAlignmentCapture capture;
        private Object recognizer;
        private OnceBackend.Owner owner;
        private Object frameIdentity;
        private final long observedAt;
        private BooleanSupplier cancel;
        private final byte[] graySha256;
        private final UiRegionImageRefiner.Result result;

        private Candidate(UiImageAlignmentCapture capture, Object recognizer, OnceBackend.Owner owner,
                BackendPorts.FrameView frame, long observedAt, BooleanSupplier cancel, byte[] digest,
                UiRegionImageRefiner.Result result) {
            this.capture = capture;
            this.recognizer = recognizer;
            this.owner = owner;
            this.frameIdentity = frame;
            this.observedAt = observedAt;
            this.cancel = cancel;
            this.graySha256 = digest.clone();
            this.result = result;
        }
        synchronized boolean matches(UiImageAlignmentCapture capture, Object recognizer, OnceBackend.Owner owner) {
            return this.capture != null && this.capture == capture && this.recognizer == recognizer
                    && this.owner == owner && frameIdentity != null;
        }
        synchronized boolean matches(UiImageAlignmentCapture capture, Object recognizer, OnceBackend.Owner owner,
                BackendPorts.FrameView frame) {
            return matches(capture, recognizer, owner) && this.frameIdentity == frame;
        }
        private synchronized void discardIdentity() {
            capture = null;recognizer = null;owner = null;frameIdentity = null;cancel = null;
        }
        long observedAt() { return observedAt; }
        byte[] graySha256() { return graySha256.clone(); }
        boolean graySha256Matches(byte[] digest) { return digest != null && MessageDigest.isEqual(graySha256, digest); }
        UiRegionImageRefiner.Result result() { return result; }
    }
}
