package com.aliothmoon.maafw.trial;

import io.github.klwb360.rewards.core.RewardsCore;
import java.io.IOException;
import java.util.Objects;
import static io.github.klwb360.rewards.core.RewardsCore.*;

/**
 * Offline-only accounting kernel for one Search or Reading attempt.
 *
 * This class does not connect to Android, take screenshots, click, type, or submit tasks.
 * A future platform adapter must construct evidence from independently verified, fresh UI
 * observations and must serialize the final recheck with the actual device action.
 */
public final class SingleTrialCoordinator {
    public interface StateStore {
        State read() throws IOException;
        /** Compare and durably write under one cross-process lock; never overwrite a changed state. */
        boolean compareAndSet(State expected, State replacement) throws IOException;
    }

    /** The future adapter must fail closed unless prior-app state and single ownership are verified. */
    public interface StartGate {
        boolean ready(Task task) throws IOException;
    }

    private static final long MAX_EVIDENCE_AGE_MS = 1_500;
    private static final long MAX_RESERVATION_AGE_MS = 30_000;
    private static final long MAX_DELIVERY_WAIT_MS = 10_000;
    private static final long MAX_VERIFICATION_WAIT_MS = 120_000;

    public record CounterEvidence(Observation observation, boolean exactIdentity,
                                  boolean twoFreshSnapshots, boolean unobscured,
                                  boolean exactTaskCard, long observedElapsed) {
        @Override public String toString() { return "CounterEvidence[redacted]"; }
        boolean usable(Task task, long now) {
            return task != null && exactIdentity && twoFreshSnapshots && unobscured && exactTaskCard
                    && fresh(observedElapsed, now)
                    && observation != null && observation.status() == ParseStatus.OK
                    && observation.counter() != null && observation.counter().task() == task;
        }
    }

    public record SurfaceEvidence(Task task, boolean exactIdentity, boolean twoFreshSnapshots,
                                  boolean unobscured, boolean exactActionTarget, long observedElapsed) {
        boolean usable(Task expected, long now) {
            return task == expected && exactIdentity && twoFreshSnapshots && unobscured && exactActionTarget
                    && fresh(observedElapsed, now);
        }
    }

    public enum Outcome {
        RESERVED, CLAIMED, WAITING_VERIFICATION, OBSERVED_INCREASE, OBSERVED_NO_CHANGE,
        ALREADY_COMPLETE, BLOCKED, STORAGE_UNCERTAIN, DELIVERY_UNCONFIRMED, STOPPED
    }

    public record Report(Outcome outcome, Decision decision, Integer before, Integer after,
                         Integer target, String detail) {}

    public static final class Permit {
        private Permit() {}
    }

    public record Claim(Report report, Permit permit) {}

    private enum Phase { IDLE, RESERVED, CLAIMED, WAITING, FINISHED, STOPPED }
    private final StateStore store;
    private final StartGate gate;
    private final Limits limits;
    private Phase phase = Phase.IDLE;
    private Task task;
    private Observation baseline;
    private State reserved;
    private Permit activePermit;
    private long reservedElapsed;
    private long claimedElapsed;
    private long deliveredElapsed;

    public SingleTrialCoordinator(StateStore store, StartGate gate, Limits limits) {
        this.store = Objects.requireNonNull(store);
        this.gate = Objects.requireNonNull(gate);
        this.limits = Objects.requireNonNull(limits);
    }

    /** A validated existing state is required; this class never silently creates a fresh ledger. */
    public synchronized Report reserve(Task requested, CounterEvidence evidence, String term,
                                       long epochNow, long elapsedNow) {
        if (phase != Phase.IDLE) return report(Outcome.BLOCKED, null, null, "trial already started");
        if (requested != Task.SEARCH && requested != Task.READING) {
            phase = Phase.STOPPED;
            return report(Outcome.BLOCKED, null, null, "unsupported trial task");
        }
        task = requested;
        if (!gateReady() || evidence == null || !evidence.usable(task, elapsedNow)) {
            phase = Phase.STOPPED;
            return report(Outcome.BLOCKED, null, null, "migration or baseline evidence unavailable");
        }
        State current;
        try { current = store.read(); }
        catch (IOException | RuntimeException failure) {
            phase = Phase.STOPPED;
            return report(Outcome.STORAGE_UNCERTAIN, null, null, "state read failed");
        }
        if (current == null) {
            phase = Phase.STOPPED;
            return report(Outcome.BLOCKED, null, null, "validated initial state missing");
        }
        Step prepared;
        try {
            prepared = RewardsCore.prepare(current, task, evidence.observation(), term,
                    epochNow, elapsedNow, false, limits);
        } catch (RuntimeException failure) {
            phase = Phase.STOPPED;
            return report(Outcome.BLOCKED, null, null, "invalid preparation input");
        }
        baseline = evidence.observation();
        if (prepared.decision() == Decision.COMPLETE) {
            phase = Phase.FINISHED;
            return report(Outcome.ALREADY_COMPLETE, prepared.decision(), baseline.counter().current(), "already complete");
        }
        if (prepared.decision() != Decision.ALLOW) {
            phase = Phase.STOPPED;
            return report(Outcome.BLOCKED, prepared.decision(), null, "core refused preparation");
        }
        reserved = prepared.state();
        if (!compareAndConfirm(current, reserved)) {
            phase = Phase.STOPPED;
            return report(Outcome.STORAGE_UNCERTAIN, null, null, "reservation was not confirmed");
        }
        reservedElapsed = elapsedNow;
        phase = Phase.RESERVED;
        return report(Outcome.RESERVED, Decision.ALLOW, null, "intent persisted before action");
    }

    /** A permit can be claimed once only after the adapter revalidates the exact action surface. */
    public synchronized Claim claim(SurfaceEvidence surface, long epochNow, long elapsedNow) {
        if (phase != Phase.RESERVED) return new Claim(report(Outcome.BLOCKED, null, null, "no claimable reservation"), null);
        if (elapsedNow < reservedElapsed || elapsedNow - reservedElapsed > MAX_RESERVATION_AGE_MS) {
            phase = Phase.STOPPED;
            return new Claim(report(Outcome.BLOCKED, Decision.RUN_TIMEOUT, null, "reservation expired or clock reset"), null);
        }
        if (!gateReady() || surface == null || !surface.usable(task, elapsedNow)) {
            phase = Phase.STOPPED;
            return new Claim(report(Outcome.BLOCKED, null, null, "action surface not verified"), null);
        }
        if (!stateMatches(reserved)) {
            phase = Phase.STOPPED;
            return new Claim(report(Outcome.STORAGE_UNCERTAIN, null, null, "durable reservation changed"), null);
        }
        // Evaluate the existing core's clock/day guard without committing its simulated result.
        Step guard;
        try { guard = RewardsCore.verify(reserved, task, baseline, epochNow, elapsedNow, false, limits); }
        catch (RuntimeException failure) {
            phase = Phase.STOPPED;
            return new Claim(report(Outcome.BLOCKED, null, null, "invalid pre-action state"), null);
        }
        if (guard.decision() == Decision.DAY_CHANGED || guard.decision() == Decision.RUN_TIMEOUT
                || guard.decision() == Decision.READING_TIMEOUT || guard.decision() == Decision.CANCELLED
                || guard.decision() == Decision.PENDING || guard.decision() == Decision.UNREADABLE) {
            phase = Phase.STOPPED;
            return new Claim(report(Outcome.BLOCKED, guard.decision(), null, "pre-action guard stopped the trial"), null);
        }
        activePermit = new Permit();
        claimedElapsed = elapsedNow;
        phase = Phase.CLAIMED;
        return new Claim(report(Outcome.CLAIMED, Decision.ALLOW, null, "one action permitted"), activePermit);
    }

    /** Check immediately before each setup/navigation operation. It never changes the ledger. */
    public synchronized boolean preparationAllowed(Task requested, String term, long epochNow, long elapsedNow) {
        return phase == Phase.RESERVED && requested == task && elapsedNow >= reservedElapsed
                && elapsedNow - reservedElapsed <= MAX_RESERVATION_AGE_MS
                && termMatches(term) && preActionChecks(epochNow, elapsedNow);
    }

    /** Check immediately before the one permitted submission, not merely when it was claimed. */
    public synchronized boolean dispatchAllowed(Permit permit, Task requested, String term, long epochNow, long elapsedNow) {
        return phase == Phase.CLAIMED && permit != null && permit == activePermit && requested == task
                && elapsedNow >= claimedElapsed && elapsedNow - claimedElapsed <= MAX_DELIVERY_WAIT_MS
                && termMatches(term) && preActionChecks(epochNow, elapsedNow);
    }

    /** Check before navigation back to a counter after an action was delivered. */
    public synchronized boolean verificationNavigationAllowed(Task requested, String term, long epochNow, long elapsedNow) {
        return phase == Phase.WAITING && requested == task && elapsedNow >= deliveredElapsed
                && elapsedNow - deliveredElapsed <= MAX_VERIFICATION_WAIT_MS
                && termMatches(term) && preActionChecks(epochNow, elapsedNow);
    }

    private boolean termMatches(String term) {
        if (task != Task.SEARCH) return true;
        if (reserved == null || term == null || RewardsCore.normalize(term).isEmpty()) return false;
        try { return reserved.pendingHash().equals(RewardsCore.hashTerm(term)); }
        catch (RuntimeException failure) { return false; }
    }

    private boolean preActionChecks(long epochNow, long elapsedNow) {
        if (!gateReady() || !stateMatches(reserved)) return false;
        try {
            Step guard = RewardsCore.verify(reserved, task, baseline, epochNow, elapsedNow, false, limits);
            return guard.decision() == Decision.ALLOW || guard.decision() == Decision.COMPLETE
                    || guard.decision() == Decision.SEARCH_NO_CREDIT
                    || guard.decision() == Decision.READING_NO_CREDIT;
        } catch (RuntimeException failure) { return false; }
    }

    /** Delivery is only a UI event result; it is never treated as a score increase. */
    public synchronized Report delivered(Permit permit, boolean confirmed, long elapsedNow) {
        if (phase != Phase.CLAIMED || permit == null || permit != activePermit) {
            return report(Outcome.BLOCKED, null, null, "stale or invalid action permit");
        }
        activePermit = null;
        if (!confirmed || elapsedNow < claimedElapsed || elapsedNow - claimedElapsed > MAX_DELIVERY_WAIT_MS) {
            phase = Phase.STOPPED;
            return report(Outcome.DELIVERY_UNCONFIRMED, null, null, "pending intent retained");
        }
        deliveredElapsed = elapsedNow;
        phase = Phase.WAITING;
        return report(Outcome.WAITING_VERIFICATION, null, null, "delivery is not score evidence");
    }

    /** Only a fresh task-specific counter comparison may finish the attempt. */
    public synchronized Report verify(CounterEvidence evidence, long epochNow, long elapsedNow) {
        if (phase != Phase.WAITING) return report(Outcome.BLOCKED, null, null, "no delivered action to verify");
        if (elapsedNow < deliveredElapsed || elapsedNow - deliveredElapsed > MAX_VERIFICATION_WAIT_MS) {
            phase = Phase.STOPPED;
            return report(Outcome.BLOCKED, Decision.RUN_TIMEOUT, null, "verification expired or clock reset");
        }
        if (!gateReady() || evidence == null || !evidence.usable(task, elapsedNow)) {
            phase = Phase.STOPPED;
            return report(Outcome.BLOCKED, null, null, "verification evidence unavailable");
        }
        if (!stateMatches(reserved)) {
            phase = Phase.STOPPED;
            return report(Outcome.STORAGE_UNCERTAIN, null, null, "durable reservation changed");
        }
        Step verified;
        try { verified = RewardsCore.verify(reserved, task, evidence.observation(), epochNow, elapsedNow, false, limits); }
        catch (RuntimeException failure) {
            phase = Phase.STOPPED;
            return report(Outcome.BLOCKED, null, null, "invalid verification input");
        }
        if (verified.decision() != Decision.ALLOW && verified.decision() != Decision.COMPLETE
                && verified.decision() != Decision.SEARCH_NO_CREDIT
                && verified.decision() != Decision.READING_NO_CREDIT) {
            phase = Phase.STOPPED;
            return report(Outcome.BLOCKED, verified.decision(), null, "verification refused; pending retained");
        }
        if (!verified.state().pendingTask().isEmpty() || !compareAndConfirm(reserved, verified.state())) {
            phase = Phase.STOPPED;
            return report(Outcome.STORAGE_UNCERTAIN, verified.decision(), null, "verification state not confirmed");
        }
        int after = evidence.observation().counter().current();
        phase = Phase.FINISHED;
        return report(after > baseline.counter().current() ? Outcome.OBSERVED_INCREASE : Outcome.OBSERVED_NO_CHANGE,
                verified.decision(), after, "task counter comparison only; causation is not proven");
    }

    /** Stopping or losing the app leaves any durable pending reservation intact. */
    public synchronized Report stop() {
        if (phase == Phase.FINISHED || phase == Phase.STOPPED) return report(Outcome.BLOCKED, null, null, "trial already ended");
        phase = Phase.STOPPED;
        activePermit = null;
        return report(Outcome.STOPPED, null, null, "pending intent, if any, retained");
    }

    private boolean gateReady() {
        try { return gate.ready(task); } catch (IOException | RuntimeException failure) { return false; }
    }

    private static boolean fresh(long observedElapsed, long now) {
        return observedElapsed >= 0 && now >= observedElapsed && now - observedElapsed <= MAX_EVIDENCE_AGE_MS;
    }

    private boolean stateMatches(State expected) {
        try { return expected != null && expected.equals(store.read()); }
        catch (IOException | RuntimeException failure) { return false; }
    }

    private boolean compareAndConfirm(State previous, State expected) {
        try { return store.compareAndSet(previous, expected) && expected.equals(store.read()); }
        catch (IOException | RuntimeException failure) { return false; }
    }

    private Report report(Outcome outcome, Decision decision, Integer after, String detail) {
        Counter c = baseline == null ? null : baseline.counter();
        return new Report(outcome, decision, c == null ? null : c.current(), after,
                c == null ? null : c.target(), detail);
    }
}
