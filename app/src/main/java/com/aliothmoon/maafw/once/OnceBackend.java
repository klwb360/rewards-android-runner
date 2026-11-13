package com.aliothmoon.maafw.once;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Single-admission, single-frame coordinator specification. Public construction is
 * permanently UNBOUND. Only package-local synthetic tests can inject adapter ports.
 * No live capture, thread creation, native call, I/O, logging or permission code.
 */
public final class OnceBackend {
    public enum Phase {
        UNBOUND, IDLE, CLAIMING, QUEUED, SOURCE, RECOGNIZER, CLEANING,
        CLEANUP_UNCONFIRMED, COMPLETED, FAILED, CANCELLED
    }
    public enum Admission { NOT_ADMITTED, CONSUMED }
    public enum Cancellation { NOT_REQUESTED, REQUESTED }
    public enum Failure {
        NONE, APPROVAL_DENIED, DUPLICATE_CLAIM, CLAIM_UNCERTAIN, STALE_CLAIM,
        DISPATCH_UNCERTAIN, FRAME_BOUNDS, INVALID_FRAME, SOURCE_FAILURE,
        RECOGNIZER_FAILURE, INVALID_RESULT, STALE_RESULT, CLEANUP_FAILURE,
        EVIDENCE_UNCERTAIN, STALE_COMPLETION, OBSERVATION_TIMEOUT
    }
    public enum Cleanup { NOT_NEEDED, PENDING, CONFIRMED, UNCONFIRMED }
    public enum Quiescence { NOT_INVOKED, UNCONFIRMED, CONFIRMED }
    public enum Memory { NOT_ALLOCATED, RETAINED, WIPED }

    /** Identity fence only. Not a secret, credential, durable key or approval token. */
    public static final class Owner { private Owner() { } }

    /** Only metadata, one boolean and bounded enums cross the public result boundary. */
    public record Result(Owner owner, long runId, Phase phase, Admission admission,
            Cancellation cancellation, boolean hit, Failure failure,
            Cleanup cleanup, Quiescence quiescence, Memory memory) {
        public boolean terminal() {
            return phase == Phase.COMPLETED || phase == Phase.FAILED || phase == Phase.CANCELLED;
        }
    }

    private final Object lock = new Object();
    private final CleanupEvidence.Ledger receipts = new CleanupEvidence.Ledger();
    private final BackendPorts.ClaimBoundary claim;
    private final BackendPorts.FrameSource source;
    private final BackendPorts.Recognizer recognizer;
    private final Executor executor;
    private Owner owner;
    private Phase phase;
    private Failure failure = Failure.NONE;
    private Cleanup cleanup = Cleanup.NOT_NEEDED;
    private Quiescence quiescence = Quiescence.NOT_INVOKED;
    private boolean stop;
    private boolean entered;
    private boolean sourceInvoked;
    private boolean recognizerInvoked;
    private boolean bodyReturned;
    private boolean querying;
    private boolean pendingHit;
    private OwnedFrame frame;
    private BackendPorts.RevocationFence sourceFence;

    /** Production binding: no dependencies, permissions, enable switch or live path. */
    public OnceBackend() {
        claim = null; source = null; recognizer = null; executor = null;
        phase = Phase.UNBOUND;
    }

    /** Test seam, deliberately not public/exported as an enabling API. */
    OnceBackend(BackendPorts.ClaimBoundary claim, BackendPorts.FrameSource source,
            BackendPorts.Recognizer recognizer, Executor executor) {
        this.claim = Objects.requireNonNull(claim);
        this.source = Objects.requireNonNull(source);
        this.recognizer = Objects.requireNonNull(recognizer);
        this.executor = Objects.requireNonNull(executor);
        phase = Phase.IDLE;
    }

    public Result snapshot() { synchronized (lock) { return resultLocked(); } }
    BackendPorts.SourceTiming sourceTiming(Owner expected){
        synchronized(lock){observeRevocationLocked();
            if(!currentLocked(expected)||stop||!terminalLocked()||cleanup!=Cleanup.CONFIRMED||quiescence!=Quiescence.CONFIRMED||memoryLocked()==Memory.RETAINED)return null;
            return source.sourceTiming(expected);
        }
    }
    private Result resultLocked() {
        observeRevocationLocked();
        return new Result(owner, owner == null ? 0 : 1, phase,
                owner == null ? Admission.NOT_ADMITTED : Admission.CONSUMED,
                stop ? Cancellation.REQUESTED : Cancellation.NOT_REQUESTED,
                phase == Phase.COMPLETED && pendingHit, failure, cleanup, quiescence, memoryLocked());
    }
    private Memory memoryLocked() {
        if (frame == null || !frame.allocated()) return Memory.NOT_ALLOCATED;
        return frame.wiped() ? Memory.WIPED : Memory.RETAINED;
    }
    private boolean terminalLocked() {
        return phase == Phase.COMPLETED || phase == Phase.FAILED || phase == Phase.CANCELLED;
    }
    private boolean currentLocked(Owner expected) { return expected != null && expected == owner; }
    private void failLocked(Failure value) {
        if (failure == Failure.NONE) failure = value;
        pendingHit = false;
    }
    private Phase outcomeLocked() {
        observeRevocationLocked();
        if (failure != Failure.NONE) return Phase.FAILED;
        return stop ? Phase.CANCELLED : Phase.COMPLETED;
    }

    /** Pure scalar query: no native calls, completion queries, release or wiping. */
    CleanupEvidence.Snapshot evidenceSnapshot(Owner expected) {
        synchronized(lock) {
            if(!currentLocked(expected)) return CleanupEvidence.unmatched();
            Memory memory=memoryLocked();
            receipts.set(CleanupEvidence.Item.FRAME_WIPE, memory==Memory.WIPED?CleanupEvidence.State.CONFIRMED:
                memory==Memory.RETAINED?CleanupEvidence.State.WAITING:CleanupEvidence.State.NOT_INVOKED);
            CleanupEvidence.Snapshot value=receipts.snapshot(expected);
            if(sourceInvoked && source instanceof CleanupEvidence.Provider provider) {
                CleanupEvidence.Snapshot detail=provider.evidenceSnapshot(expected);
                if(detail.ownerMatched()) value=value.plus(detail);
            }
            if(recognizerInvoked && recognizer instanceof CleanupEvidence.Provider provider) {
                CleanupEvidence.Snapshot detail=provider.evidenceSnapshot(expected);
                if(detail.ownerMatched()) value=value.plus(detail);
            }
            return value;
        }
    }
    private void cacheReceipt(BackendPorts.Completion value,CleanupEvidence.Item release,CleanupEvidence.Item quiet) {
        receipts.set(release,value!=null && value.released()==BackendPorts.Released.CONFIRMED?CleanupEvidence.State.CONFIRMED:CleanupEvidence.State.UNKNOWN);
        receipts.set(quiet,value!=null && value.quiet()==BackendPorts.Quiet.CONFIRMED?CleanupEvidence.State.CONFIRMED:CleanupEvidence.State.UNKNOWN);
    }

    /** First call reserves forever, before the claim port. No failure/cancel can rearm. */
    public Result start() {
        final Owner accepted;
        synchronized (lock) {
            if (phase == Phase.UNBOUND || owner != null) return resultLocked();
            owner = new Owner();
            accepted = owner;
            receipts.bind(accepted);
            phase = Phase.CLAIMING;
        }
        BackendPorts.ClaimReceipt receipt;
        try {
            receipt = claim.consume(accepted);
        } catch (Throwable ignored) {
            synchronized (lock) {
                failLocked(Failure.CLAIM_UNCERTAIN);
                phase = Phase.FAILED;
                return resultLocked();
            }
        }
        synchronized (lock) {
            if (receipt == null || receipt.decision() == null) failLocked(Failure.CLAIM_UNCERTAIN);
            else if (receipt.owner() != accepted) failLocked(Failure.STALE_CLAIM);
            else {
                switch (receipt.decision()) {
                    case DENIED -> failLocked(Failure.APPROVAL_DENIED);
                    case DUPLICATE -> failLocked(Failure.DUPLICATE_CLAIM);
                    case UNCERTAIN -> failLocked(Failure.CLAIM_UNCERTAIN);
                    case CLAIMED -> { }
                }
            }
            if (failure != Failure.NONE || stop) {
                phase = outcomeLocked();
                return resultLocked();
            }
            phase = Phase.QUEUED;
        }
        try {
            executor.execute(() -> execute(accepted));
        } catch (Throwable ignored) {
            synchronized (lock) {
                if (!terminalLocked()) {
                    failLocked(Failure.DISPATCH_UNCERTAIN);
                    stop = true;
                    if (!entered) {
                        // Atomically revoke an unentered job, even if execute queued then threw.
                        // This is gate-closure proof, not a timeout/future-exit assumption.
                        entered = true;
                        phase = Phase.FAILED;
                    }
                }
            }
        }
        return snapshot();
    }

    /** Owner-scoped cooperative request only. Never interrupts, releases or wipes. */
    public Result cancel(Owner expected) {
        synchronized (lock) {
            if (currentLocked(expected) && !terminalLocked()) {
                stop = true;
                pendingHit = false;
            }
            return resultLocked();
        }
    }

    /** Caller observation, NOT a timer, worker-exit signal or cleanup proof. */
    public Result observeTimeout(Owner expected) {
        synchronized (lock) {
            if (currentLocked(expected) && !terminalLocked()) {
                stop = true;
                failLocked(Failure.OBSERVATION_TIMEOUT);
            }
            return resultLocked();
        }
    }

    private void observeRevocationLocked() {
        if (!terminalLocked() && sourceFence != null && sourceFence.revoked()) {
            stop = true; pendingHit = false;
        }
    }
    private boolean stopping() { synchronized (lock) { observeRevocationLocked(); return stop; } }
    private void recordFailure(Failure value) { synchronized (lock) { failLocked(value); } }

    private void execute(Owner accepted) {
        synchronized (lock) {
            if (!currentLocked(accepted) || entered || terminalLocked()) return;
            entered = true;
            if (stop) {
                phase = outcomeLocked();
                return;
            }
            sourceInvoked = true;
 receipts.set(CleanupEvidence.Item.SOURCE_RELEASE, CleanupEvidence.State.WAITING);
 receipts.set(CleanupEvidence.Item.SOURCE_QUIET, CleanupEvidence.State.WAITING);
            phase = Phase.SOURCE;
            cleanup = Cleanup.PENDING;
            quiescence = Quiescence.UNCONFIRMED;
            frame = new OwnedFrame();
        }
        try {
            boolean valid = false;
            try {
                source.produce(accepted, frame.sink(), this::stopping);
                valid = frame.seal();
                if (!valid && (!stopping() || frame.rejected())) recordFailure(Failure.INVALID_FRAME);
            } catch (BackendPorts.Cancelled cancelled) {
                if (frame.rejected()) recordFailure(Failure.INVALID_FRAME);
                else {
                    try {
                        BackendPorts.RevocationFence fence = source.revocationFence(accepted);
                        synchronized (lock) { sourceFence = fence; }
                    } catch (Throwable invalidFence) { recordFailure(Failure.SOURCE_FAILURE); }
                    if (!stopping()) recordFailure(Failure.SOURCE_FAILURE);
                }
            } catch (OwnedFrame.InvalidFrame badFrame) {
                recordFailure(badFrame.failure());
            } catch (Throwable ignored) {
                recordFailure(Failure.SOURCE_FAILURE);
            } finally {
                // Media body/cleanup has actually returned. Adopt the same-owner
                // scalar fence before OCR, including the exceptional source path.
                try {
                    BackendPorts.RevocationFence fence = source.revocationFence(accepted);
                    synchronized (lock) { sourceFence = fence; observeRevocationLocked(); }
                } catch (Throwable invalidFence) { recordFailure(Failure.SOURCE_FAILURE); }
                frame.seal();
            }
            boolean recognize;
            synchronized (lock) {
                observeRevocationLocked();
                recognize = valid && !stop && failure == Failure.NONE;
                if (recognize) {
                    recognizerInvoked = true;
 receipts.set(CleanupEvidence.Item.RECOGNIZER_RELEASE, CleanupEvidence.State.WAITING);
 receipts.set(CleanupEvidence.Item.RECOGNIZER_QUIET, CleanupEvidence.State.WAITING);
                    phase = Phase.RECOGNIZER;
                }
            }
            if (recognize) {
                try {
                    BackendPorts.Recognition recognition = recognizer.recognize(accepted, frame.view(), this::stopping);
                    synchronized (lock) {
                        observeRevocationLocked();
                        if (recognition == null) failLocked(Failure.INVALID_RESULT);
                        else if (recognition.owner() != accepted) failLocked(Failure.STALE_RESULT);
                        else if (!stop && failure == Failure.NONE) pendingHit = recognition.hit();
                    }
                } catch (BackendPorts.Cancelled cancelled) {
                    if (!stopping()) recordFailure(Failure.RECOGNIZER_FAILURE);
                } catch (Throwable ignored) {
                    recordFailure(Failure.RECOGNIZER_FAILURE);
                }
            }
        } finally {
            synchronized (lock) { phase = Phase.CLEANING; }
            if (recognizerInvoked) release(recognizer, accepted);
            release(source, accepted);
            synchronized (lock) {
                bodyReturned = true;
                phase = Phase.CLEANUP_UNCONFIRMED;
            }
            refreshEvidence(accepted);
        }
    }

    private void release(BackendPorts.Resource resource, Owner accepted) {
        try { resource.release(accepted); }
        catch (Throwable ignored) { recordFailure(Failure.CLEANUP_FAILURE); }
    }

    private BackendPorts.Completion evidence(BackendPorts.Resource resource, Owner accepted) {
        try {
            BackendPorts.Completion evidence = resource.completion(accepted);
            if (evidence == null || evidence.released() == null || evidence.quiet() == null) {
                recordFailure(Failure.EVIDENCE_UNCERTAIN);
                return null;
            }
            if (evidence.owner() != accepted) {
                recordFailure(Failure.STALE_COMPLETION);
                return null;
            }
            return evidence;
        } catch (Throwable ignored) {
            recordFailure(Failure.EVIDENCE_UNCERTAIN);
            return null;
        }
    }

    /**
     * Queries current owner-scoped evidence after both method/release calls return.
     * Never retries release. Unknown or blocked evidence retains ownership forever.
     * No public callback can directly assert result, release, quiet or completion.
     */
    public Result refreshEvidence(Owner expected) {
        final boolean needRecognizer;
        final OwnedFrame owned;
        synchronized (lock) {
            if (!currentLocked(expected) || terminalLocked() || !bodyReturned || querying) return resultLocked();
            querying = true;
            needRecognizer = recognizerInvoked;
            owned = frame;
        }
        try {
            BackendPorts.Completion sourceEvidence = evidence(source, expected);
            BackendPorts.Completion recognizerEvidence = needRecognizer ? evidence(recognizer, expected) : null;
            cacheReceipt(sourceEvidence, CleanupEvidence.Item.SOURCE_RELEASE, CleanupEvidence.Item.SOURCE_QUIET);
            if (needRecognizer) cacheReceipt(recognizerEvidence, CleanupEvidence.Item.RECOGNIZER_RELEASE, CleanupEvidence.Item.RECOGNIZER_QUIET);
            boolean released = sourceEvidence != null && sourceEvidence.released() == BackendPorts.Released.CONFIRMED
                    && (!needRecognizer || recognizerEvidence != null
                    && recognizerEvidence.released() == BackendPorts.Released.CONFIRMED);
            boolean quiet = sourceEvidence != null && sourceEvidence.quiet() == BackendPorts.Quiet.CONFIRMED
                    && (!needRecognizer || recognizerEvidence != null
                    && recognizerEvidence.quiet() == BackendPorts.Quiet.CONFIRMED);
            // Not under the coordinator lock. Port evidence must cover ALL borrowers.
            if (quiet) owned.wipeAfterQuiescence();
            synchronized (lock) {
                cleanup = released ? Cleanup.CONFIRMED : Cleanup.UNCONFIRMED;
                quiescence = quiet ? Quiescence.CONFIRMED : Quiescence.UNCONFIRMED;
                if (quiet && released) {
                    observeRevocationLocked();
                    // Final publication linearization after cleanup and actual wipe.
                    if (!stop && failure == Failure.NONE && sourceFence != null && !sourceFence.tryCommit()) {
                        stop = true; pendingHit = false;
                    }
                    phase = outcomeLocked();
                }
                else phase = Phase.CLEANUP_UNCONFIRMED;
            }
        } finally {
            synchronized (lock) { querying = false; }
        }
        return snapshot();
    }
}