package com.aliothmoon.maafw.once;

import android.view.Surface;
import java.io.IOException;
import java.util.Objects;

/** Internal production seam, not an authorization API or a plugin registry.
 * The sole production binding is the audited synchronous synthetic provider.
 * External calls run on the exact source owner thread, outside metadata locks.
 * Anchored by PollingFrameSource while any outcome is unknown.
 * No OS consent, platform handle, image or OCR content is persisted here. */
final class ControlledSourceSession implements PollingFrameSource.ProducerFactory,
        PollingFrameSource.Producer, CleanupEvidence.Provider {
    enum SourceIdentity { BUILT_IN_GENERATED_SURFACE }
    enum Phase { RESERVED, ATTACHED, OPENING, PRODUCING, BODY_EXITED, CLOSING, APP_CLOSED, UNKNOWN }
    interface Provider {
        Driver open(Lease lease, Surface target, BackendPorts.StopSignal stop) throws Exception;
    }
    interface Driver {
        void produce(Lease lease, BackendPorts.StopSignal stop) throws Exception;
        /** Release this lease's objects, join all app-owned children, drop target
         * references, then return matching evidence. Return alone is not proof.
         * Throw, block, null or foreign receipt retains the reader and ownership. */
        CloseReceipt close(Lease lease) throws Exception;
    }
    /** Reference identity is session-scoped, one-shot and never reused/persisted.
     * This is an ownership fence only, NEVER a real capture grant. */
    static final class Lease {
        private final ControlledSourceSession session;
        private final OnceBackend.Owner owner;
        private final Thread thread;
        private boolean receiptIssued;
        private Lease(ControlledSourceSession session, OnceBackend.Owner owner) {
            this.session = session; this.owner = owner; this.thread = Thread.currentThread();
        }
        SourceIdentity source() { return SourceIdentity.BUILT_IN_GENERATED_SURFACE; }
        boolean belongsTo(OnceBackend.Owner expected) { return owner == expected && expected != null; }
        void requireOwnerThread() {
            if (thread != Thread.currentThread()) throw new IllegalStateException("Wrong source owner thread");
        }
        /** Only inside admitted close. Trusted adapter evidence of synchronous
         * APP-owned resources, NOT proof of OS/driver quiescence. */
        CloseReceipt synchronousAppCleanupReturned() {
            requireOwnerThread();
            if (session.phase != Phase.CLOSING || receiptIssued) throw new IllegalStateException();
            receiptIssued = true;
            return new CloseReceipt(this);
        }
    }
    static final class CloseReceipt {
        private final Lease lease;
        private CloseReceipt(Lease lease) { this.lease = lease; }
    }
    private final Provider provider;
    private final SyntheticAdmission admission;
    private final CleanupEvidence.Ledger receipts = new CleanupEvidence.Ledger();
    private Lease lease;
    private volatile Phase phase = Phase.RESERVED;
    private Surface target;
    private Driver driver;
    private BackendPorts.StopSignal sourceStop;
    private boolean attached, produced, closeClaimed, creationUncertain;

    ControlledSourceSession(Provider provider, SyntheticAdmission admission) {
        this.provider = Objects.requireNonNull(provider);
        this.admission = Objects.requireNonNull(admission);
    }
    @Override public synchronized void validateOwner(OnceBackend.Owner owner) {
        if (lease != null || !admission.bindSource(owner, this)) throw new SecurityException("Consumed matching synthetic owner required");
        lease = new Lease(this, owner);
        receipts.bind(owner);
    }
    @Override public PollingFrameSource.Producer open(Surface ignored, BackendPorts.StopSignal stop) {
        throw new SecurityException("Owner-qualified entry required");
    }
    @Override public PollingFrameSource.Producer open(OnceBackend.Owner owner, Surface surface,
            BackendPorts.StopSignal stop) {
        requireLease();
        if (!lease.belongsTo(owner) || attached || closeClaimed) throw new IllegalStateException();
        target = Objects.requireNonNull(surface);
        sourceStop = Objects.requireNonNull(stop);
        attached = true; phase = Phase.ATTACHED;
        // No provider call. Outer source adopts this owner before checking stop.
        return this;
    }
    private void requireLease() {
        if (lease == null) throw new IllegalStateException();
        lease.requireOwnerThread();
    }
    @Override public void produce(BackendPorts.StopSignal stop) throws Exception {
        requireLease();
        if (!attached || produced || closeClaimed) throw new IllegalStateException();
        produced = true;
        BackendPorts.StopSignal combined = () -> sourceStop.requested() || stop.requested();
        if (combined.requested()) throw new BackendPorts.Cancelled();
        creationUncertain = true; phase = Phase.OPENING;
        receipts.set(CleanupEvidence.Item.SESSION_OPEN_EXIT, CleanupEvidence.State.WAITING);
        try {
            // Adopt BEFORE checking stop. Null/throw cannot establish whether
            // external allocation occurred; no handle does not mean no resource.
            driver = Objects.requireNonNull(provider.open(lease, target, combined));
            creationUncertain = false;
        } finally {
            receipts.set(CleanupEvidence.Item.SESSION_OPEN_EXIT, CleanupEvidence.State.CONFIRMED);
        }
        if (combined.requested()) throw new BackendPorts.Cancelled();
        phase = Phase.PRODUCING;
        receipts.set(CleanupEvidence.Item.SESSION_PRODUCE_EXIT, CleanupEvidence.State.WAITING);
        try { driver.produce(lease, combined); }
        finally {
            receipts.set(CleanupEvidence.Item.SESSION_PRODUCE_EXIT, CleanupEvidence.State.CONFIRMED);
            phase = Phase.BODY_EXITED;
        }
        if (combined.requested()) throw new BackendPorts.Cancelled();
    }
    @Override public void close() throws Exception {
        requireLease();
        if (closeClaimed) throw new IllegalStateException("Cleanup is never retried");
        closeClaimed = true;
        if (creationUncertain) {
            phase = Phase.UNKNOWN;
            receipts.set(CleanupEvidence.Item.SESSION_APP_QUIET, CleanupEvidence.State.UNKNOWN);
            throw new IOException("Producer creation outcome unknown");
        }
        if (driver != null) {
            phase = Phase.CLOSING;
            receipts.set(CleanupEvidence.Item.SESSION_CLOSE_EXIT, CleanupEvidence.State.WAITING);
            receipts.set(CleanupEvidence.Item.SESSION_APP_QUIET, CleanupEvidence.State.WAITING);
            try {
                CloseReceipt returned = driver.close(lease);
                receipts.set(CleanupEvidence.Item.SESSION_CLOSE_EXIT, CleanupEvidence.State.CONFIRMED);
                if (returned == null || returned.lease != lease || !lease.receiptIssued)
                    throw new IOException("Missing matching app cleanup evidence");
                receipts.set(CleanupEvidence.Item.SESSION_APP_QUIET, CleanupEvidence.State.CONFIRMED);
                driver = null;
            } catch (Exception | Error failed) {
                receipts.unknownOutstanding(); phase = Phase.UNKNOWN; throw failed;
            }
        }
        target = null; sourceStop = null; phase = Phase.APP_CLOSED;
    }
    Phase phase() { return phase; }
    @Override public CleanupEvidence.Snapshot evidenceSnapshot(OnceBackend.Owner expected) {
        return receipts.snapshot(expected);
    }
}
