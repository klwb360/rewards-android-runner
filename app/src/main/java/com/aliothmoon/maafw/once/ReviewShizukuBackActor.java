package com.aliothmoon.maafw.once;

import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot fixed Android Back bridge. No keycode or command is accepted from callers. A command exit is not a task-completion claim.
 * The integration gate must validate native provenance/freshness, foreground identity,
 * cancellation and absolute expiry, and durably burn the exact target's action scope.
 * This class never requests permission, retries a dispatch, or falls back to host or an unapproved privilege mode. */
final class ReviewShizukuBackActor {
    static final int NO_EXIT = Integer.MIN_VALUE;
    static final long WAIT_MILLIS = 2_000L;
    private static final AtomicBoolean PROCESS_SPENT = new AtomicBoolean();
    private static volatile Object quarantine;
    private volatile boolean resourcesKnownClean=true;
    boolean knownClean(){return resourcesKnownClean;}

    record BackTarget(int fullDisplayWidth, int fullDisplayHeight, int rotation) {}
    record DisplayShape(int width, int height, int rotation) {}
    enum Kind { REJECTED, COMMAND_EXITED, DISPATCH_UNKNOWN }
    enum Reason {
        INVALID_INPUT, ALREADY_SPENT, PREFLIGHT_UNAVAILABLE, DISPLAY_CHANGED,
        AUTHORITY_REJECTED, AUTHORITY_UNKNOWN, AUTHORITY_STALE, AUTHORITY_RECHECK_UNKNOWN, READY_STATE_LOST,
        COMMAND_EXIT_ZERO, COMMAND_EXIT_NONZERO, DISPATCH_EXCEPTION,
        NO_PROCESS_HANDLE, WAIT_TIMEOUT, WAIT_EXCEPTION, CLEANUP_UNKNOWN
    }
    /** authorityConsumed is conservative: true once gate entry may have burned scope. */
    record Result(Kind kind, int exitCode, boolean invocationAttempted,
                  boolean authorityConsumed, Reason reason) {
        boolean commandExitZero() { return kind == Kind.COMMAND_EXITED && exitCode == 0; }
    }
    interface DispatchGate {
        /** False or exception must leave the outer action scope unusable if burn began. */
        boolean consume(BackTarget exactTarget) throws Exception;
        /** No reburn. Final check must end with local freshness/expiry/cancel checks. */
        boolean stillValid(BackTarget exactTarget) throws Exception;
    }
    interface Backend { Session open() throws Exception; }
    interface Session {
        DisplayShape display() throws Exception;
        boolean stillReady() throws Exception;
        /** Recheck binder/geometry, then run finalGate immediately before newProcess. */
        PendingBack dispatch(BackTarget target, FinalGate finalGate) throws Exception;
    }
    @FunctionalInterface interface FinalGate { void requireValid() throws DispatchBlocked; }
    static final class DispatchBlocked extends Exception {
        private static final long serialVersionUID = 1L;
        final Reason reason;
        DispatchBlocked(Reason reason) { super(reason.name()); this.reason = reason; }
    }
    interface PendingBack {
        boolean waitForExit(long millis) throws Exception;
        int exitCode() throws Exception;
        void destroy() throws Exception;
        void close() throws Exception;
    }

    private final Backend backend;
    private final AtomicBoolean spent;
    /** Exact already-consented root profile, isolated ordinary app process only. */
    static ReviewShizukuBackActor forApprovedBusiness20261111(Context context) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint has no input actor");
        return new ReviewShizukuBackActor(new AndroidReviewShizukuBackBackend(context), PROCESS_SPENT);
    }
    // Test seam is package-private and cannot install a production fallback.
    ReviewShizukuBackActor(Backend backend) { this(backend, new AtomicBoolean()); }
    private ReviewShizukuBackActor(Backend backend, AtomicBoolean spent) {
        if (backend == null) throw new IllegalArgumentException("backend");
        this.backend = backend;
        this.spent = spent;
    }
    Result back(BackTarget target, DispatchGate gate) {
        if (!valid(target) || gate == null) return rejected(false, Reason.INVALID_INPUT);
        // Never restore this budget, even for preflight failure or an uncertain gate.
        if (!spent.compareAndSet(false, true)) return rejected(false, Reason.ALREADY_SPENT);
        final Session session;
        try {
            session = backend.open();
            if (session == null || !session.stillReady()) return rejected(false, Reason.PREFLIGHT_UNAVAILABLE);
            if (!matches(target, session.display())) return rejected(false, Reason.DISPLAY_CHANGED);
        } catch (Exception unavailable) { return rejected(false, Reason.PREFLIGHT_UNAVAILABLE); }
        try {
            if (!gate.consume(target)) return rejected(true, Reason.AUTHORITY_REJECTED);
        } catch (Exception unknown) { return rejected(true, Reason.AUTHORITY_UNKNOWN); }
        // Permission, binder and geometry can change while the durable gate is running.
        try {
            if (!session.stillReady()) return rejected(true, Reason.READY_STATE_LOST);
            if (!matches(target, session.display())) return rejected(true, Reason.DISPLAY_CHANGED);
        } catch (Exception unavailable) { return rejected(true, Reason.READY_STATE_LOST); }
        final PendingBack process;
        try {
            process = session.dispatch(target, () -> {
                try {
                    if (!gate.stillValid(target)) throw new DispatchBlocked(Reason.AUTHORITY_STALE);
                } catch (DispatchBlocked rejected) { throw rejected; }
                catch (Exception unknown) { throw new DispatchBlocked(Reason.AUTHORITY_RECHECK_UNKNOWN); }
            });
        } catch (DispatchBlocked rejected) { return rejected(true, rejected.reason); }
        catch (Exception uncertain) { return unknown(Reason.DISPATCH_EXCEPTION); }
        if (process == null) return unknown(Reason.NO_PROCESS_HANDLE);
        Result result;
        boolean killOwnProcess = true;
        try {
            if (!process.waitForExit(WAIT_MILLIS)) result = unknown(Reason.WAIT_TIMEOUT);
            else {
                int exit = process.exitCode();
                killOwnProcess = false;
                result = new Result(Kind.COMMAND_EXITED, exit, true, true,
                        exit == 0 ? Reason.COMMAND_EXIT_ZERO : Reason.COMMAND_EXIT_NONZERO);
            }
        } catch (Exception uncertain) { result = unknown(Reason.WAIT_EXCEPTION); }
        // Only the process returned by this dispatch is touched, never an external PID.
        try {
            if (killOwnProcess) process.destroy();
        } catch (Exception cleanupUnknown) { result = unknown(Reason.CLEANUP_UNKNOWN); }
        try { process.close(); }
        catch (Exception cleanupUnknown) { result = unknown(Reason.CLEANUP_UNKNOWN); }
        if(result.kind()==Kind.DISPATCH_UNKNOWN)quarantine=process;
        return result;
    }
    private static boolean valid(BackTarget t) {
        return t != null && t.fullDisplayWidth() > 0 && t.fullDisplayHeight() > 0
                && t.fullDisplayWidth() <= 4096 && t.fullDisplayHeight() <= 4096
                && (long)t.fullDisplayWidth() * t.fullDisplayHeight() <= 4L * 1024 * 1024
                && t.rotation() >= 0 && t.rotation() <= 3;
    }
    private static boolean matches(BackTarget t, DisplayShape d) {
        return d != null && t.fullDisplayWidth() == d.width()
                && t.fullDisplayHeight() == d.height() && t.rotation() == d.rotation();
    }
    private static Result rejected(boolean consumed, Reason reason) {
        return new Result(Kind.REJECTED, NO_EXIT, false, consumed, reason);
    }
    private Result unknown(Reason reason) {
        resourcesKnownClean=false;quarantine=new Object();
        return new Result(Kind.DISPATCH_UNKNOWN, NO_EXIT, true, true, reason);
    }
}





