package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.List;

/** A visible, state-reading probe for the real More post-return UI reader. */
final class LiveMoreUiRecheck {
    enum Step { SHARED_FIRST, SHARED_SECOND, INDEPENDENT_SECOND }
    enum Result { QUALIFIED, INSUFFICIENT_BUDGET, NO_TIME_TO_START, FAILED }
    record Attempt(Step step, LiveRewardsTypes.Phase phase, long started, long deadline, long finished,
                   Result result, String diagnostic) {}

    interface Reader {
        boolean read(LiveRunAuthority.CardScope scope, LiveRewardsTypes.Phase phase) throws Exception;
        String diagnostic();
        boolean knownClean();
    }
    interface Pause { void sleep(long millis) throws InterruptedException; }

    /** A selection exists only during the confirmed, same-activity, single-use button callback. */
    static final class Choice {
        private record Bound(Object owner, boolean recheck, boolean spent) {}
        private final ThreadLocal<Bound> selected = new ThreadLocal<>();

        void confirm(Object activity, boolean recheck, Runnable start) {
            if (activity == null || start == null || selected.get() != null)
                throw new IllegalStateException("Fresh visible recheck selection required");
            selected.set(new Bound(activity, recheck, false));
            try { start.run(); } finally { selected.remove(); }
        }
        boolean pending(Object activity) {
            var bound = selected.get();
            return bound != null && !bound.spent() && bound.recheck() && bound.owner() == activity;
        }
        boolean take(Object activity) {
            var bound = selected.get();
            if (bound == null || bound.spent() || bound.owner() != activity)
                throw new SecurityException("Same unspent read-only selection required");
            selected.set(new Bound(activity, bound.recheck(), true));
            return bound.recheck();
        }
    }

    private final List<Attempt> attempts = new ArrayList<>(3);
    private volatile String step = "NOT_STARTED";
    private volatile String lastResult = "NONE";
    private boolean started, verified;

    String step() { return step; }
    String lastResult() { return lastResult; }
    boolean verified() { return verified; }
    List<Attempt> attempts() { return List.copyOf(attempts); }

    void run(LiveRunAuthority run, Reader reader, Pause pause) throws Exception {
        if (started || run == null || reader == null || pause == null || run.taskInputAllowed() || run.taskScopeCount() != 0)
            throw new SecurityException("Fresh no-input recheck required");
        started = true;
        requireCurrent(run, reader);
        var shared = run.moreUiRecheckScope(false);
        shared.startAfterWindow(run.now());
        var first = read(run, shared, Step.SHARED_FIRST, LiveRewardsTypes.Phase.AFTER_1, reader);
        if (first.result() != Result.QUALIFIED) throw new IllegalStateException("First shared read unavailable");

        step = Step.SHARED_SECOND.name();
        long now = run.now();
        if (now < first.finished()) throw new IllegalStateException("Monotonic read clock required");
        long wait = Math.max(0, LiveRewardsCoordinator.AFTER_SPACING_MILLIS - (now - first.finished()));
        boolean roomForSpacing = wait <= shared.remainingAfterBudget(now);
        if (roomForSpacing && wait > 0) pause.sleep(wait);
        requireCurrent(run, reader);
        now = run.now();
        if (!roomForSpacing || now >= shared.afterDeadline()) {
            attempts.add(new Attempt(Step.SHARED_SECOND, LiveRewardsTypes.Phase.AFTER_2, now, shared.afterDeadline(),
                    now, Result.NO_TIME_TO_START, "NONE"));
            lastResult = Result.NO_TIME_TO_START.name();
        } else {
            if (now - first.finished() < LiveRewardsCoordinator.AFTER_SPACING_MILLIS)
                throw new IllegalStateException("Second shared read started early");
            var second = read(run, shared, Step.SHARED_SECOND, LiveRewardsTypes.Phase.AFTER_2, reader);
            if (second.result() == Result.QUALIFIED) {
                verified = true;
                return;
            }
        }
        // A clean refusal before dispatch or a skipped phase may get one separate fresh observation.
        // It has its own window and cannot be counted as a helper dispatch in the shared window.
        requireCurrent(run, reader);
        var independent = run.moreUiRecheckScope(true);
        independent.startAfterWindow(run.now());
        var second = read(run, independent, Step.INDEPENDENT_SECOND, LiveRewardsTypes.Phase.AFTER_2, reader);
        if (second.result() != Result.QUALIFIED) throw new IllegalStateException("Independent second read unavailable");
        verified = true;
    }

    private Attempt read(LiveRunAuthority run, LiveRunAuthority.CardScope scope, Step which, LiveRewardsTypes.Phase phase,
                         Reader reader) throws Exception {
        requireCurrent(run, reader);
        step = which.name();
        lastResult = "READING";
        long start = run.now();
        long finished = -1;
        Result outcome = Result.FAILED;
        try {
            boolean qualified = reader.read(scope, phase);
            requireCurrent(run, reader);
            finished = run.now();
            if (finished < start || (qualified && finished > scope.afterDeadline()))
                throw new IllegalStateException("Read outside its observation window");
            outcome = qualified ? Result.QUALIFIED : Result.INSUFFICIENT_BUDGET;
        } finally {
            lastResult = outcome.name();
            attempts.add(new Attempt(which, phase, start, scope.afterDeadline(), finished < 0 ? run.now() : finished,
                    outcome, reader.diagnostic()));
        }
        return attempts.get(attempts.size() - 1);
    }

    private static void requireCurrent(LiveRunAuthority run, Reader reader) {
        if (!run.current() || !reader.knownClean()) throw new SecurityException("Current source and clean reader required");
    }
}
