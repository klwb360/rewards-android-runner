package com.aliothmoon.maafw.trial;

import io.github.klwb360.rewards.core.RewardsCore.Task;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static com.aliothmoon.maafw.trial.SingleTrialCoordinator.*;

/**
 * One-attempt orchestration independent of Android. The production device driver, durable
 * cross-process store, and legacy-state ownership adapter are not implemented here.
 */
public final class SingleTrialExecutor {
    public interface Clock {
        long epochMillis();
        long elapsedMillis();
    }

    public interface Driver {
        CounterEvidence readBaseline(Task task) throws Exception;

        /** Check mayContinue immediately before each setup action; do not submit a search here. */
        SurfaceEvidence prepareAction(Task task, String searchTerm, BooleanSupplier mayContinue) throws Exception;

        /**
         * Dispatch at most one action, without retry. Immediately before dispatch, reacquire the
         * verified target, check stopRequested, foreground identity and obstruction again.
         * Return true only when delivery is confirmed, not when points are credited.
         */
        boolean dispatchOnce(Task task, String searchTerm, Permit permit, SurfaceEvidence evidence,
                             BooleanSupplier mayDispatch) throws Exception;

        /** Return to the task page without another earning action and read a fresh task counter. */
        CounterEvidence readAfter(Task task, BooleanSupplier mayContinue) throws Exception;
    }

    private final SingleTrialCoordinator coordinator;
    private final Driver driver;
    private final Clock clock;
    private final AtomicBoolean used = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final Object dispatchLock = new Object();

    public SingleTrialExecutor(SingleTrialCoordinator coordinator, Driver driver, Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.driver = Objects.requireNonNull(driver);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Requesting stop cannot undo an event already sent; no later action should be sent. */
    public void requestStop() {
        stopRequested.set(true);
        synchronized (dispatchLock) { /* wait only for an in-flight dispatch to return */ }
    }

    public Report run(Task task, String searchTerm) {
        if (!used.compareAndSet(false, true)) return blocked("executor already used");
        if (stopRequested.get()) return coordinator.stop();
        CounterEvidence baseline;
        try { baseline = driver.readBaseline(task); }
        catch (Exception failure) { return blocked("baseline unavailable"); }
        if (stopRequested.get()) return coordinator.stop();

        Report reserved;
        try { reserved = coordinator.reserve(task, baseline, searchTerm, clock.epochMillis(), clock.elapsedMillis()); }
        catch (RuntimeException failure) { return blocked("clock or reservation unavailable"); }
        if (reserved.outcome() != Outcome.RESERVED) return reserved;
        if (stopRequested.get()) return coordinator.stop();

        SurfaceEvidence surface;
        try {
            surface = driver.prepareAction(task, searchTerm, () -> {
                if (stopRequested.get()) return false;
                try { return coordinator.preparationAllowed(task, searchTerm, clock.epochMillis(), clock.elapsedMillis()); }
                catch (RuntimeException failure) { return false; }
            });
        }
        catch (Exception failure) { return coordinator.stop(); }
        if (stopRequested.get()) return coordinator.stop();

        Report delivery;
        synchronized (dispatchLock) {
            if (stopRequested.get()) return coordinator.stop();
            Claim claim;
            try { claim = coordinator.claim(surface, clock.epochMillis(), clock.elapsedMillis()); }
            catch (RuntimeException failure) { return coordinator.stop(); }
            if (claim.permit() == null) return claim.report();
            if (stopRequested.get()) return coordinator.stop();
            boolean confirmed;
            try {
                confirmed = driver.dispatchOnce(task, searchTerm, claim.permit(), surface, () -> {
                    if (stopRequested.get()) return false;
                    try { return coordinator.dispatchAllowed(claim.permit(), task, searchTerm, clock.epochMillis(), clock.elapsedMillis()); }
                    catch (RuntimeException failure) { return false; }
                });
            }
            catch (Exception failure) { confirmed = false; }
            try { delivery = coordinator.delivered(claim.permit(), confirmed, clock.elapsedMillis()); }
            catch (RuntimeException failure) { return coordinator.stop(); }
        }
        if (delivery.outcome() != Outcome.WAITING_VERIFICATION) return delivery;
        if (stopRequested.get()) return coordinator.stop();

        CounterEvidence after;
        try {
            after = driver.readAfter(task, () -> {
                if (stopRequested.get()) return false;
                try { return coordinator.verificationNavigationAllowed(task, searchTerm, clock.epochMillis(), clock.elapsedMillis()); }
                catch (RuntimeException failure) { return false; }
            });
        }
        catch (Exception failure) { return coordinator.stop(); }
        if (stopRequested.get()) return coordinator.stop();
        try { return coordinator.verify(after, clock.epochMillis(), clock.elapsedMillis()); }
        catch (RuntimeException failure) { return coordinator.stop(); }
    }

    private static Report blocked(String detail) {
        return new Report(Outcome.BLOCKED, null, null, null, null, detail);
    }
}
