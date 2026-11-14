package com.aliothmoon.maafw.once;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import static com.aliothmoon.maafw.once.UiImageAlignmentDiagnostics.*;

/** Read-only identity-mapped image candidate. Admission and actual cleanup remain with PNG binding. */
final class UiImageAlignmentCapture {
    private final UiImageGeometryBinding geometry;
    private final BingNavigationSnapshot.Ready imageBefore;
    private final long lower, deadline;
    private final BooleanSupplier revoked;
    private final UiImageAlignmentDiagnostics diagnostics;
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
        diagnostics = new UiImageAlignmentDiagnostics(geometry.diagnosticUiStarted(), geometry.diagnosticUiAfterLower(), lower);
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
    String diagnostics() { return diagnostics.summary(); }
    synchronized boolean claimTransport() {
        boolean first = !transportClaimed && !prepared && !sealAttempted;
        transportClaimed = true;
        return first;
    }

    synchronized Candidate prepare(Object recognizer, OnceBackend.Owner owner, BackendPorts.FrameView actualFrame,
            byte[] gray, List<FrameStructureEvidence.Line> rows, BooleanSupplier cancel, long observedAt) {
        boolean first = !prepared;
        prepared = true;
        if (!first) return rejectPrepare(Prepare.DUPLICATE);
        diagnostics.prepareAttempt(observedAt);
        if (!transportClaimed) return rejectPrepare(Prepare.TRANSPORT_UNCLAIMED);
        if (sealAttempted) return rejectPrepare(Prepare.SEAL_ATTEMPTED);
        if (recognizer == null || owner == null || actualFrame == null) return rejectPrepare(Prepare.IDENTITY_MISSING);
        if (gray == null || rows == null) return rejectPrepare(Prepare.INPUT_MISSING);
        if (stopped(cancel)) return rejectPrepare(Prepare.CANCELED);
        if (!current(observedAt)) return rejectPrepare(Prepare.CURRENT_REJECTED);
        byte[] scratch = null;
        try {
            long size = (long) width() * height();
            if (actualFrame.width() != width() || actualFrame.height() != height()
                    || actualFrame.size() != size || gray.length != size) return rejectPrepare(Prepare.SHAPE_MISMATCH);
            // The digest and candidate must describe these exact bytes, not a same-sized caller image.
            scratch = new byte[Math.min(gray.length, 4096)];
            for (int offset = 0; offset < gray.length; offset += 4096) {
                // Same global 4096-byte boundaries and short circuit as the original scalar loop.
                if (stopped(cancel) || stopped(revoked)) return rejectPrepare(Prepare.CANCELED);
                int length = Math.min(4096, gray.length - offset);
                actualFrame.copyGrayRange(offset, scratch, 0, length);
                for (int i = 0; i < length; i++) {
                    if (scratch[i] != gray[offset + i]) return rejectPrepare(Prepare.PIXEL_MISMATCH);
                }
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(gray);
            var result = UiRegionImageRefiner.observe(geometry.regions(), gray, width(), height(), rows,
                    () -> stopped(cancel) || stopped(revoked));
            if (result == null) return rejectPrepare(Prepare.REFINER_UNAVAILABLE);
            if (stopped(cancel)) return rejectPrepare(Prepare.CANCELED);
            if (!current(observedAt)) return rejectPrepare(Prepare.CURRENT_REJECTED);
            candidate = new Candidate(this, recognizer, owner, actualFrame, observedAt, cancel, digest, result);
            diagnostics.prepare(Prepare.ACCEPTED);
            return candidate;
        } catch (RuntimeException | NoSuchAlgorithmException invalid) { return rejectPrepare(Prepare.ERROR); }
        finally { if (scratch != null) Arrays.fill(scratch, (byte) 0); }
    }
    private Candidate rejectPrepare(Prepare gate) { diagnostics.prepare(gate); return null; }

    /** Metadata bracket only; not a receipt of source cleanup, atomic pixels or action authority. */
    boolean sourceMatchesAfter(BingNavigationSnapshot.Ready after, long afterCapture, long now) {
        diagnostics.afterAttempt(after, now);
        if (after == null) return rejectAfter(After.MISSING);
        if (afterCapture < lower || afterCapture > now) return rejectAfter(After.CANDIDATE_TIME);
        if (after.lower() < afterCapture) return rejectAfter(After.BEFORE_CANDIDATE);
        if (!after.fresh(now)) return rejectAfter(After.STALE);
        if (!imageBefore.sameSource(after)) return rejectAfter(After.IMAGE_SOURCE_MISMATCH);
        if (!geometry.sourceMatches(after)) return rejectAfter(After.UI_SOURCE_MISMATCH);
        if (!current(now)) return rejectAfter(After.CURRENT_REJECTED);
        diagnostics.after(After.ACCEPTED); return true;
    }
    private boolean rejectAfter(After gate) { diagnostics.after(gate); return false; }

    /** Binding supplies its actual source envelope and candidate after checking actual cleanup. No retry. */
    synchronized boolean sealSource(Candidate exact, Object recognizer, OnceBackend.Owner owner,
            long sourceEnter, long sourceReturned, BingNavigationSnapshot.Ready after, long now) {
        boolean first = !sealAttempted;
        sealAttempted = true;
        Candidate pending = candidate;
        try {
            if (!first) return rejectSeal(Seal.DUPLICATE, now);
            diagnostics.sealAttempt(sourceEnter, sourceReturned, now);
            if (exact == null) return rejectSeal(Seal.CANDIDATE_MISSING, now);
            if (pending != exact || !exact.matches(this, recognizer, owner)) return rejectSeal(Seal.CANDIDATE_MISMATCH, now);
            if (sourceEnter < lower || sourceReturned < sourceEnter || exact.observedAt < sourceReturned)
                return rejectSeal(Seal.SOURCE_TIMING, now);
            if (!sourceMatchesAfter(after, exact.observedAt, now)) return rejectSeal(Seal.AFTER_REJECTED, now);
            if (stopped(exact.cancel)) return rejectSeal(Seal.CANCELED, now);
            imageAfter = after;
            preparedObservedAt = exact.observedAt;
            sealedAt = now;
            diagnostics.seal(Seal.ACCEPTED, now);
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
    private boolean rejectSeal(Seal gate, long now) { diagnostics.seal(gate, now); return false; }

    synchronized boolean publicationCurrent(long now) {
        if (sealedAt < 0) return rejectPublication(Publication.NOT_SEALED, now);
        if (now < sealedAt) return rejectPublication(Publication.BEFORE_SEAL, now);
        if (preparedObservedAt < 0) return rejectPublication(Publication.CANDIDATE_TIME_MISSING, now);
        if (!sourceMatchesAfter(imageAfter, preparedObservedAt, now)) return rejectPublication(Publication.AFTER_REJECTED, now);
        diagnostics.publication(Publication.ACCEPTED, now); return true;
    }
    private boolean rejectPublication(Publication gate, long now) { diagnostics.publication(gate, now); return false; }

    private boolean current(long now) {
        if (now < lower) return rejectCurrent(Current.BEFORE_LOWER, now);
        if (now >= deadline) return rejectCurrent(Current.DEADLINE, now);
        if (!NativeProgressPublication.frameFresh(now, lower)) return rejectCurrent(Current.FRAME_STALE, now);
        if (!geometry.fresh(now)) return rejectCurrent(Current.UI_GEOMETRY_REJECTED, now);
        if (stopped(revoked)) return rejectCurrent(Current.REVOKED, now);
        diagnostics.current(Current.ACCEPTED, now); return true;
    }
    private boolean rejectCurrent(Current gate, long now) { diagnostics.current(gate, now); return false; }
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
