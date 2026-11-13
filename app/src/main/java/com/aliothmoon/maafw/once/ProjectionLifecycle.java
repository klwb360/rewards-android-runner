package com.aliothmoon.maafw.once;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** DORMANT, NOT LIVE VALIDATED. Ownership core, never authorization.
 * One owner thread, one projection handover, one display creation, no retry/rearm.
 * The containing caller must retain this object on uncertainty. No real-source
 * binding exists. A private target-detach receipt permits only app reader teardown. */
final class ProjectionLifecycle<P, D, T> {
    enum Fact { NOT_INVOKED, IN_FLIGHT, RETURNED, THREW }
    enum Source { DORMANT_MEDIA_PROJECTION, SYNTHETIC_OS_FACADE }
    interface Ports<P, D, T> {
        /** Transfer an ALREADY-OWNED handle only; never acquire consent or start OS work. */
        P takeProjection(Lease lease) throws Exception;
        void register(P projection, Anchor anchor, Lease lease) throws Exception;
        D createDisplay(P projection, T target, int width, int height, int dpi, Lease lease) throws Exception;
        void releaseDisplay(D display, Lease lease) throws Exception;
        void stopProjection(P projection, Lease lease) throws Exception;
        void unregister(P projection, Anchor anchor, Lease lease) throws Exception;
    }
    /** Exact source/session/owner/thread identity; not a grant or durable claim. */
    static final class Lease {
        private final Object owner;
        private final Object session;
        private final Thread thread = Thread.currentThread();
        private final Source source;
        private Lease(Object owner, Object session, Source source) { this.owner = owner; this.session = session; this.source = source; }
        Source source() { return source; }
        void require(Object expectedOwner, Object expectedSession) {
            requireThread();
            if (owner != expectedOwner || session != expectedSession) throw new SecurityException("Wrong projection owner/session");
        }
        void requireThread() {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Wrong projection owner thread");
        }
    }
    /** Callback reaches ONLY this scalar tombstone. No owner, Surface, lease,
     * projection, display, Handler, port, Runnable or external call is reachable.
     * Not reused. A queued callback can outlive unregister and the core itself. */
    static final class Anchor {
        private static final int CANCEL = 1, CLOSE = 2, STOP_BEFORE_CLOSE = 4,
                STOP_AFTER_CLOSE_AMBIGUOUS = 8, RESIZED = 16;
        private final AtomicInteger bits = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicLong geometry = new java.util.concurrent.atomic.AtomicLong();
        private final BackendPorts.RevocationFence fence;
        Anchor() { this(new BackendPorts.RevocationFence()); }
        private Anchor(BackendPorts.RevocationFence fence) { this.fence=Objects.requireNonNull(fence); }
        BackendPorts.RevocationFence fence() { return fence; }
        boolean geometryConfirmed() { return geometry.get() != 0; }
        void onStop() { fence.revoke();
            bits.getAndUpdate(old -> old | CANCEL | ((old & CLOSE) == 0
                    ? STOP_BEFORE_CLOSE : STOP_AFTER_CLOSE_AMBIGUOUS));
        }
        void onResize() { onResize(0, 0); }
        void onResize(int width, int height) {
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || (long) width * height > 64L*1024*1024) { rejectGeometry(); return; }
            long value = ((long) width << 32) | height;
            geometry.compareAndSet(0, value);
            if (geometry.get() != value) rejectGeometry();
        }
        private void rejectGeometry() { fence.revoke(); bits.getAndUpdate(old -> old | CANCEL | RESIZED); }
        void requestStop() { fence.revoke(); bits.getAndUpdate(old -> old | CANCEL); }
        void beginOwnClose() { bits.getAndUpdate(old -> old | CLOSE); }
        boolean cancelled() { return fence.revoked(); }
        boolean stopBeforeClose() { return (bits.get() & STOP_BEFORE_CLOSE) != 0; }
        boolean stopAfterCloseAmbiguous() { return (bits.get() & STOP_AFTER_CLOSE_AMBIGUOUS) != 0; }
        boolean resized() { return (bits.get() & RESIZED) != 0; }
    }
    record Evidence(Fact take, Fact register, Fact create, Fact displayRelease,
            Fact projectionStop, Fact unregister, boolean creationUncertain,
            boolean appReferencesDropped, boolean callbackJoinProven,
            boolean driverQuietProven, boolean readerReleasePermitted,
            boolean stopBeforeClose, boolean stopAfterCloseAmbiguous, boolean resized) { }

    static final class TargetKey { private TargetKey() { } }
    static final class ReaderReleaseReceipt {
        private final Lease lease; private final TargetKey key;
        private ReaderReleaseReceipt(Lease lease, TargetKey key) { this.lease = lease; this.key = key; }
    }
    private final TargetKey targetKey = new TargetKey();
    private ReaderReleaseReceipt receipt;
    private final boolean requireInitialGeometry;
    private final Object owner;
    private final Lease lease;
    private final Ports<P, D, T> ports;
    private final Anchor anchor;
    private final int width, height, dpi;
    private T target;
    private P projection;
    private D display;
    private boolean started, closing, active, creationUncertain, appReferencesDropped;
    private Fact take = Fact.NOT_INVOKED, register = Fact.NOT_INVOKED, create = Fact.NOT_INVOKED,
            displayRelease = Fact.NOT_INVOKED, projectionStop = Fact.NOT_INVOKED,
            unregister = Fact.NOT_INVOKED;

    ProjectionLifecycle(Object owner, Ports<P, D, T> ports, T target, int width, int height, int dpi) {
        this(owner, ports, target, width, height, dpi, false, domainOf(ports));
    }
    private static Source domainOf(Object ports) {
        return ports instanceof AndroidProjectionPorts ? Source.DORMANT_MEDIA_PROJECTION : Source.SYNTHETIC_OS_FACADE;
    }
    ProjectionLifecycle(Object owner, Ports<P,D,T> ports, T target, int width, int height, int dpi, boolean requireInitialGeometry, Source domain) {
        this(owner,ports,target,width,height,dpi,requireInitialGeometry,domain,new BackendPorts.RevocationFence());
    }
    ProjectionLifecycle(Object owner, Ports<P,D,T> ports, T target, int width, int height, int dpi, boolean requireInitialGeometry, Source domain, BackendPorts.RevocationFence fence) {
        this.anchor=new Anchor(fence);
        if (domain != domainOf(ports)) throw new SecurityException("Port implementation/source domain mismatch");
        this.requireInitialGeometry = requireInitialGeometry || domain == Source.DORMANT_MEDIA_PROJECTION;
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || (long) width * height > 4L * 1024 * 1024 || dpi <= 0 || dpi > 2000)
            throw new IllegalArgumentException("Invalid bounded projection geometry");
        this.owner = Objects.requireNonNull(owner);
        this.ports = Objects.requireNonNull(ports);
        this.target = Objects.requireNonNull(target);
        this.width = width; this.height = height; this.dpi = dpi;
        this.lease = new Lease(owner, this, Objects.requireNonNull(domain));
    }
    TargetKey targetKey(Object expectedOwner, T expectedTarget) {
        checkOwner(expectedOwner);
        if (target == null || target != expectedTarget) throw new SecurityException("Wrong target");
        return targetKey;
    }
    ReaderReleaseReceipt readerReleaseReceipt(Object expectedOwner) { checkOwner(expectedOwner); return receipt; }
    boolean permitsReaderRelease(ReaderReleaseReceipt value, Object expectedOwner, TargetKey key, Source domain) {
        checkOwner(expectedOwner);
        return value != null && value == receipt && value.lease == lease && value.key == targetKey && key == targetKey && domain == lease.source();
    }
    BackendPorts.RevocationFence fence() { return anchor.fence(); }
    boolean geometryConfirmed() { return !requireInitialGeometry || anchor.geometryConfirmed(); }
    private void checkOwner(Object expected) { lease.require(expected, this); }
    private void checkStop() throws BackendPorts.Cancelled {
        if (anchor.cancelled()) throw new BackendPorts.Cancelled();
    }
    /** Cancellation is scalar only; no cross-thread OS cleanup or native free. */
    void requestStop() { anchor.requestStop(); }
    boolean cancellationRequested() { return anchor.cancelled(); }

    void start(Object expectedOwner) throws Exception {
        checkOwner(expectedOwner);
        if (started || closing) throw new IllegalStateException("Projection cannot replay/rearm");
        started = true;
        checkStop();
        take = Fact.IN_FLIGHT;
        try {
            projection = ports.takeProjection(lease); // Adopt before inspecting stop.
            if (projection == null) throw new IllegalStateException("No projection handle");
            take = Fact.RETURNED;
        } catch (Exception | Error failed) { take = Fact.THREW; creationUncertain = true; throw failed; }
        checkStop();
        register = Fact.IN_FLIGHT;
        try { ports.register(projection, anchor, lease); register = Fact.RETURNED; }
        catch (Exception | Error failed) { register = Fact.THREW; creationUncertain = true; throw failed; }
        checkStop();
        create = Fact.IN_FLIGHT;
        try {
            display = ports.createDisplay(projection, target, width, height, dpi, lease);
            // A late returned handle belongs to THIS owner even when revoked.
            if (display == null) throw new IllegalStateException("No display handle");
            create = Fact.RETURNED;
        } catch (Exception | Error failed) { create = Fact.THREW; creationUncertain = true; throw failed; }
        checkStop();
        active = true;
    }
    /** Only a transient candidate, NOT permission to publish a real result.
     * Polling bridge propagates its scalar fence through OCR and final commit.
     * A revocation not yet delivered cannot be detected here. */
    boolean candidate(Object expectedOwner) {
        checkOwner(expectedOwner);
        return active && !closing && !anchor.cancelled() && geometryConfirmed();
    }
    /** Exactly one owner-thread cleanup attempt. Known handles are attempted even
     * if a prior cleanup throws. A blocked call is not bypassed/retried and keeps
     * the owner alive. Returned, threw, callback join and driver quiet are distinct. */
    void close(Object expectedOwner) {
        checkOwner(expectedOwner);
        if (closing) throw new IllegalStateException("Cleanup cannot retry");
        closing = true; active = false; anchor.beginOwnClose();
        // The port already owns the projection, even when start never ran.
        // Adopt that pending handle for cleanup; cancellation must not strand it.
        if (take == Fact.NOT_INVOKED) {
            take = Fact.IN_FLIGHT;
            try {
                projection = ports.takeProjection(lease);
                if (projection == null) throw new IllegalStateException("No pending projection handle" );
                take = Fact.RETURNED;
            } catch (Exception | Error failed) { take = Fact.THREW; creationUncertain = true; }
        }
        // Unregister before own stop-producing release calls; queued callbacks still revoke.
        if (projection != null) {
            if (register != Fact.NOT_INVOKED) {
                unregister = Fact.IN_FLIGHT;
                try { ports.unregister(projection, anchor, lease); unregister = Fact.RETURNED; }
                catch (Exception | Error failed) { unregister = Fact.THREW; }
            }
        }
        if (display != null) {
            displayRelease = Fact.IN_FLIGHT;
            try { ports.releaseDisplay(display, lease); displayRelease = Fact.RETURNED; display = null; }
            catch (Exception | Error failed) { displayRelease = Fact.THREW; }
        }
        if (projection != null) {
            projectionStop = Fact.IN_FLIGHT;
            try { ports.stopProjection(projection, lease); projectionStop = Fact.RETURNED; }
            catch (Exception | Error failed) { projectionStop = Fact.THREW; }

        }
        boolean returnedKnown = (displayRelease == Fact.RETURNED || displayRelease == Fact.NOT_INVOKED)
                && (projectionStop == Fact.RETURNED || projectionStop == Fact.NOT_INVOKED)
                && (unregister == Fact.RETURNED || unregister == Fact.NOT_INVOKED);
        if (!creationUncertain && returnedKnown) {
            projection = null; target = null; appReferencesDropped = true;

        }
        // Target-specific permission is independent of projection-stop outcome.
        // API return is local cleanup evidence, never a system/driver quiet proof.
        boolean detached = !creationUncertain
                && (displayRelease == Fact.RETURNED || create == Fact.NOT_INVOKED)
                && (unregister == Fact.RETURNED || register == Fact.NOT_INVOKED);
        if (detached) { target = null; receipt = new ReaderReleaseReceipt(lease, targetKey); }
        // No ControlledSourceSession receipt: OS quiescence is unproved even
        // after all ordinary returns. Never close/release a borrowed Surface.
    }
    Evidence evidence(Object expectedOwner) {
        checkOwner(expectedOwner);
        return new Evidence(take, register, create, displayRelease, projectionStop, unregister,
                creationUncertain, appReferencesDropped, false, false, receipt != null,
                anchor.stopBeforeClose(), anchor.stopAfterCloseAmbiguous(), anchor.resized());
    }
}
