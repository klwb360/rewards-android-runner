package com.aliothmoon.maafw.readonly;

/**
 * Code-only contract for a FUTURE reviewed one-shot coordinator. No capture, I/O, thread,
 * timer, permission, persistence or execution is implemented here. Evidence values are
 * trusted coordinator inputs, NOT verification or authority by themselves.
 */
public final class ReadOnlyRecognition {
    public enum Backend { UNBOUND, REVIEWED_READ_ONLY }
    public enum Authorization { NOT_AUTHORIZED, APPROVED_ONCE, CONSUMED }
    public enum Readiness {
        UNSUPPORTED, BACKEND_UNBOUND, RESOURCE_UNVERIFIED, IDENTITY_UNVERIFIED,
        READY_FOR_FUTURE_APPROVAL, AUTHORIZED_AWAITING_DURABLE_CLAIM, CONSUMED
    }
    public enum Phase { IDLE, RUNNING, STOPPING, CLEANUP_UNCONFIRMED, COMPLETED, FAILED, CANCELLED }
    public enum Result { NONE, TEXT_PRESENT, NO_TEXT }
    public enum Error { NONE, BACKEND_FAILURE, INVALID_RESULT, MISSING_RESULT, PROCESS_INTERRUPTED }

    /** Property hints preserve the legacy allowlist, not device attestation or permission. */
    public static boolean matchesLegacyDevice(int sdk, String hardware, String fingerprint) {
        return sdk == 35 && "ranchu".equals(hardware)
            && fingerprint != null && fingerprint.contains("sdk_gphone");
    }

    public static final class Admission {
        public final boolean deviceSupported, resourcePinsVerified, remoteShellIdentityVerified;
        public final Backend backend;
        public final Authorization authorization;
        public Admission(boolean deviceSupported, boolean resourcePinsVerified,
                boolean remoteShellIdentityVerified, Backend backend, Authorization authorization) {
            this.deviceSupported = deviceSupported;
            this.resourcePinsVerified = resourcePinsVerified;
            this.remoteShellIdentityVerified = remoteShellIdentityVerified;
            this.backend = backend == null ? Backend.UNBOUND : backend;
            this.authorization = authorization == null ? Authorization.NOT_AUTHORIZED : authorization;
        }
        public Readiness readiness() {
            if (authorization == Authorization.CONSUMED) return Readiness.CONSUMED;
            if (!deviceSupported) return Readiness.UNSUPPORTED;
            if (backend != Backend.REVIEWED_READ_ONLY) return Readiness.BACKEND_UNBOUND;
            if (!resourcePinsVerified) return Readiness.RESOURCE_UNVERIFIED;
            if (!remoteShellIdentityVerified) return Readiness.IDENTITY_UNVERIFIED;
            return authorization == Authorization.APPROVED_ONCE
                ? Readiness.AUTHORIZED_AWAITING_DURABLE_CLAIM : Readiness.READY_FOR_FUTURE_APPROVAL;
        }
    }

    /** Unique per-instance/per-attempt object. Never compare numeric native addresses or run labels. */
    public static final class Owner { private Owner() {} }

    /** All three facts must refer to this Owner. Worker/Binder death alone does not suffice. */
    public static final class CleanupEvidence {
        public final boolean producerQuiescent, consumersReleased, externalChildrenExited;
        public CleanupEvidence(boolean producerQuiescent, boolean consumersReleased, boolean externalChildrenExited) {
            this.producerQuiescent = producerQuiescent;
            this.consumersReleased = consumersReleased;
            this.externalChildrenExited = externalChildrenExited;
        }
        public boolean complete() { return producerQuiescent && consumersReleased && externalChildrenExited; }
    }

    /** Immutable snapshot for StateFlow/Compose. Only coarse results/errors, never OCR or images. */
    public static final class State {
        public final Phase phase;
        public final Authorization authorization;
        public final Result result;
        public final Error error;
        public final boolean stopRequested, cleanupConfirmed;
        public final Owner owner;
        private State(Phase phase, Authorization authorization, Result result, Error error,
                boolean stopRequested, boolean cleanupConfirmed, Owner owner) {
            this.phase = phase; this.authorization = authorization; this.result = result;
            this.error = error; this.stopRequested = stopRequested;
            this.cleanupConfirmed = cleanupConfirmed; this.owner = owner;
        }
        public boolean terminal() {
            return phase == Phase.COMPLETED || phase == Phase.FAILED || phase == Phase.CANCELLED;
        }
    }

    private final Admission admission;
    private State state;
    public ReadOnlyRecognition(Admission admission) {
        if (admission == null) throw new IllegalArgumentException("admission required");
        this.admission = admission;
        state = new State(Phase.IDLE, admission.authorization, Result.NONE, Error.NONE, false, false, null);
    }
    public synchronized State snapshot() { return state; }

    /**
     * Future coordinator callback AFTER durable one-use claim commit, before dispatch.
     * This does not persist a claim, issue approval or execute a backend. A false/missing
     * commit never transitions. An uncertain commit must be recovered fail-closed.
     */
    public synchronized State onDurableClaimCommitted(boolean confirmed) {
        if (!confirmed || state.phase != Phase.IDLE || state.authorization != Authorization.APPROVED_ONCE
                || admission.readiness() != Readiness.AUTHORIZED_AWAITING_DURABLE_CLAIM) return state;
        state = new State(Phase.RUNNING, Authorization.CONSUMED, Result.NONE, Error.NONE, false, false, new Owner());
        return state;
    }
    private boolean current(Owner owner) {
        return owner != null && owner == state.owner && !state.terminal() && state.phase != Phase.IDLE;
    }

    /** A result is not completion and cannot close cleanup ownership. Repeated/late results are ignored. */
    public synchronized State onResult(Owner owner, Result result) {
        if (!current(owner) || state.phase != Phase.RUNNING || state.result != Result.NONE) return state;
        if (result == null || result == Result.NONE) return onFailure(owner, Error.INVALID_RESULT);
        state = new State(state.phase, state.authorization, result, state.error, false, false, owner);
        return state;
    }
    public synchronized State onFailure(Owner owner, Error error) {
        if (!current(owner) || state.stopRequested || state.error != Error.NONE) return state;
        Error safeError = error == null || error == Error.NONE ? Error.BACKEND_FAILURE : error;
        Phase next = state.phase == Phase.CLEANUP_UNCONFIRMED ? Phase.CLEANUP_UNCONFIRMED : Phase.STOPPING;
        state = new State(next, state.authorization, Result.NONE, safeError, false, false, owner);
        return state;
    }
    public synchronized State requestStop() {
        if (state.owner == null || state.terminal() || state.stopRequested) return state;
        Phase next = state.phase == Phase.CLEANUP_UNCONFIRMED ? Phase.CLEANUP_UNCONFIRMED : Phase.STOPPING;
        state = new State(next, state.authorization, Result.NONE, state.error, true, false, state.owner);
        return state;
    }
    /** A deadline/connection loss may report uncertainty; it never fabricates stopped/completed. */
    public synchronized State onCleanupUnconfirmed(Owner owner) {
        if (!current(owner)) return state;
        state = new State(Phase.CLEANUP_UNCONFIRMED, state.authorization, state.result,
            state.error, state.stopRequested, false, owner);
        return state;
    }
    public synchronized State onCleanupEvidence(Owner owner, CleanupEvidence evidence) {
        if (!current(owner)) return state;
        if (evidence == null || !evidence.complete()) return onCleanupUnconfirmed(owner);
        Phase next;
        Error error = state.error;
        if (state.stopRequested) next = Phase.CANCELLED;
        else if (error != Error.NONE) next = Phase.FAILED;
        else if (state.result == Result.NONE) { next = Phase.FAILED; error = Error.MISSING_RESULT; }
        else next = Phase.COMPLETED;
        state = new State(next, Authorization.CONSUMED, state.result, error, state.stopRequested, true, owner);
        return state;
    }
    /** Recovery contains no running owner and never dispatches. No claim/result is restored as fresh approval. */
    public static State recoverInterruptedClaim() {
        return new State(Phase.CLEANUP_UNCONFIRMED, Authorization.CONSUMED, Result.NONE,
            Error.PROCESS_INTERRUPTED, true, false, null);
    }
}

