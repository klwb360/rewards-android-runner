package com.aliothmoon.maafw.once;

import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot fixed-direction scroll bridge. Factory authority, never target data, selects the direction. No coordinates, duration, keycode or command are accepted from callers. A command exit is not a task-completion claim.
 * The integration gate must validate native provenance/freshness, foreground identity,
 * cancellation and absolute expiry, and durably burn the exact target's action scope.
 * This class never requests permission, retries a dispatch, or falls back to host or an unapproved privilege mode. */
final class ReviewShizukuSwipeActor {
    static final int NO_EXIT = Integer.MIN_VALUE;
    static final long WAIT_MILLIS = 2_000L;
    private static final ProcessFence PROCESS = new ProcessFence();
    static final int MAX_ACTORS = 2;
    static final int DURATION_MILLIS = 400;
    static final class ProcessFence {
        private final java.util.concurrent.atomic.AtomicInteger created=new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger locateCreated=new java.util.concurrent.atomic.AtomicInteger();
        private volatile Object quarantine;
        boolean reserve(){return reserve(Direction.UP);}
        boolean reserve(Direction direction){return direction!=null&&quarantine==null&&reserveFactory(direction==Direction.UP?created:locateCreated);}
    }
    private volatile boolean resourcesKnownClean=true;
    boolean knownClean(){return resourcesKnownClean;}

    enum Direction { UP, DOWN }
    record SwipeTarget(int fullDisplayWidth, int fullDisplayHeight, int rotation, Direction direction) {
        SwipeTarget(int fullDisplayWidth,int fullDisplayHeight,int rotation){this(fullDisplayWidth,fullDisplayHeight,rotation,Direction.UP);}
        int x(){return fullDisplayWidth/2;}
        int fromY(){return direction==Direction.DOWN?upperY():lowerY();}
        int toY(){return direction==Direction.DOWN?lowerY():upperY();}
        private int lowerY(){return (int)((long)fullDisplayHeight*4/5);}
        private int upperY(){return (int)((long)fullDisplayHeight*7/20);}
    }
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
        boolean consume(SwipeTarget exactTarget) throws Exception;
        /** No reburn. Final check must end with local freshness/expiry/cancel checks. */
        boolean stillValid(SwipeTarget exactTarget) throws Exception;
    }
    interface Backend { Session open() throws Exception; }
    interface Session {
        DisplayShape display() throws Exception;
        boolean stillReady() throws Exception;
        /** Recheck binder/geometry, then run finalGate immediately before newProcess. */
        PendingSwipe dispatch(SwipeTarget target, FinalGate finalGate) throws Exception;
    }
    @FunctionalInterface interface FinalGate { void requireValid() throws DispatchBlocked; }
    static final class DispatchBlocked extends Exception {
        private static final long serialVersionUID = 1L;
        final Reason reason;
        DispatchBlocked(Reason reason) { super(reason.name()); this.reason = reason; }
    }
    interface PendingSwipe {
        boolean waitForExit(long millis) throws Exception;
        int exitCode() throws Exception;
        void destroy() throws Exception;
        void close() throws Exception;
    }

    private final Backend backend;
    private final AtomicBoolean spent;
    private final boolean production;
    private final Direction allowedDirection;
    private final ProcessFence processFence;
    private OverviewScanIntent.ScrollPermit overviewPermit;
    private boolean quarantined(){return processFence!=null&&processFence.quarantine!=null;}
    private boolean scopeCurrent(){
        if(overviewPermit!=null)return overviewPermit.intent.live()&&overviewPermit.current(android.os.SystemClock.elapsedRealtime());
        if(!production)return true;
        if(ReadOnlyCheckpoint.blocksLiveInput())return false;
        long now=System.currentTimeMillis();
        return allowedDirection==Direction.DOWN?RootProgressLocateScope.ACTIVE&&RootProgressLocateScope.current(now)
            :!RootProgressLocateScope.ACTIVE&&RootProgressScanScope.ACTIVE&&RootProgressScanScope.current(now);
    }
    /** New independent overview authority; old factories, quotas and expired scopes stay unchanged. */
    static ReviewShizukuSwipeActor forOverview(Context context,OverviewScanIntent.ScrollPermit permit){
        if(permit==null||PROCESS.quarantine!=null||!permit.claimActor(android.os.SystemClock.elapsedRealtime()))
            throw new SecurityException("Fresh overview scroll permit required");
        var actor=new ReviewShizukuSwipeActor(new AndroidReviewShizukuSwipeBackend(context),
                new AtomicBoolean(),true,PROCESS,permit.direction);
        actor.overviewPermit=permit;return actor;
    }
    /** Exact already-consented root profile, isolated ordinary app process only. */
    static ReviewShizukuSwipeActor forProgressScan(Context context) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Structure-only build has no navigation actor");
        if(RootProgressLocateScope.ACTIVE||!RootProgressScanScope.ACTIVE||!RootProgressScanScope.current(System.currentTimeMillis())||PROCESS.quarantine!=null)
            throw new SecurityException("Fresh scan scope and clean process required");
        if(!PROCESS.reserve())throw new SecurityException("Scan actor budget exhausted");
        return new ReviewShizukuSwipeActor(new AndroidReviewShizukuSwipeBackend(context),new AtomicBoolean(),true,PROCESS,Direction.UP);
    }
    static ReviewShizukuSwipeActor forProgressLocate(Context context) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Structure-only build has no navigation actor");
        if(!RootProgressLocateScope.ACTIVE||!RootProgressLocateScope.current(System.currentTimeMillis())||PROCESS.quarantine!=null)
            throw new SecurityException("Fresh locate scope and clean process required");
        if(!PROCESS.reserve(Direction.DOWN))throw new SecurityException("Locate actor budget exhausted");
        return new ReviewShizukuSwipeActor(new AndroidReviewShizukuSwipeBackend(context),new AtomicBoolean(),true,PROCESS,Direction.DOWN);
    }
    static boolean reserveFactory(java.util.concurrent.atomic.AtomicInteger budget){
        for(;;){int n=budget.get();if(n<0||n>=MAX_ACTORS)return false;if(budget.compareAndSet(n,n+1))return true;}
    }
    // Non-live test seam: cannot select a production backend or reset process authority.
    ReviewShizukuSwipeActor(Backend backend) { this(backend,new AtomicBoolean(),false,null,Direction.UP); }
    ReviewShizukuSwipeActor(Backend backend,Direction direction) { this(backend,new AtomicBoolean(),false,null,direction); }
    static ReviewShizukuSwipeActor fixtureProcess(Backend backend,ProcessFence isolated){return fixtureProcess(backend,isolated,Direction.UP);}
    static ReviewShizukuSwipeActor fixtureProcess(Backend backend,ProcessFence isolated,Direction direction){
        if(isolated==null||isolated==PROCESS||!isolated.reserve(direction))throw new SecurityException("Isolated fixture process budget unavailable");
        return new ReviewShizukuSwipeActor(backend,new AtomicBoolean(),false,isolated,direction);
    }
    private ReviewShizukuSwipeActor(Backend backend,AtomicBoolean spent,boolean production,ProcessFence processFence,Direction direction) {
        if (backend == null||direction==null) throw new IllegalArgumentException("backend/direction");
        this.backend = backend;this.spent = spent;this.production=production;this.processFence=processFence;this.allowedDirection=direction;
    }
    Result swipe(SwipeTarget target, DispatchGate gate) {
        if (!valid(target) || target.direction()!=allowedDirection || gate == null) return rejected(false, Reason.INVALID_INPUT);
        // Never restore this budget, even for preflight failure or an uncertain gate.
        if (!spent.compareAndSet(false, true)) return rejected(false, Reason.ALREADY_SPENT);
        if(quarantined()||!scopeCurrent())return rejected(false,Reason.PREFLIGHT_UNAVAILABLE);
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
        final PendingSwipe process;
        try {
            process = session.dispatch(target, () -> {
                try {
                    if(quarantined()||!scopeCurrent())throw new DispatchBlocked(Reason.AUTHORITY_STALE);
                    if (!gate.stillValid(target)) throw new DispatchBlocked(Reason.AUTHORITY_STALE);
                    if(quarantined()||!scopeCurrent())throw new DispatchBlocked(Reason.AUTHORITY_STALE);
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
        if(processFence!=null&&result.kind()==Kind.DISPATCH_UNKNOWN)processFence.quarantine=process;
        return result;
    }
    static boolean valid(SwipeTarget t) {
        return t != null && t.direction()!=null && t.fullDisplayWidth() > 0 && t.fullDisplayHeight() > 0
                && t.fullDisplayWidth() <= 4096 && t.fullDisplayHeight() <= 4096
                && (long)t.fullDisplayWidth() * t.fullDisplayHeight() <= 4L * 1024 * 1024
                && t.rotation() == 0 && t.fullDisplayHeight()>t.fullDisplayWidth()
                && t.x()>0&&t.x()<t.fullDisplayWidth()
                && Math.abs(t.fromY()-t.toY())>=200
                && Math.min(t.fromY(),t.toY())>=(long)t.fullDisplayHeight()/5
                && Math.max(t.fromY(),t.toY())<=(long)t.fullDisplayHeight()*17/20;
    }
    private static boolean matches(SwipeTarget t, DisplayShape d) {
        return d != null && t.fullDisplayWidth() == d.width()
                && t.fullDisplayHeight() == d.height() && t.rotation() == d.rotation();
    }
    private static Result rejected(boolean consumed, Reason reason) {
        return new Result(Kind.REJECTED, NO_EXIT, false, consumed, reason);
    }
    private Result unknown(Reason reason) {
        resourcesKnownClean=false;if(processFence!=null)processFence.quarantine=new Object();
        return new Result(Kind.DISPATCH_UNKNOWN, NO_EXIT, true, true, reason);
    }
}





