package com.aliothmoon.maafw.remote;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/** Production tasker ownership. No Android dependencies; all native calls occur outside the monitor.
 * This protects tasker handles only. It does not establish capture, Binder or GPU quiescence.
 */
public final class TaskerStopCoordinator<H> {
    public interface NativeOps<H> {
        void postStop(H handle);
        boolean running(H handle);
        void destroy(H handle);
    }
    public interface Submission<H, T> { T call(H handle); }
    public static final class Run {
        private final Object coordinator;
        private boolean stopped;
        private boolean sealed;
        private Run(Object coordinator) { this.coordinator = coordinator; }
    }
    public static final class Handle<H> {
        private final Object coordinator;
        private final H value;
        private Run run;
        private boolean retired;
        private boolean quiet;
        private boolean destroyed;
        private boolean destroying;
        private boolean failed;
        private int submissions;
        private long revision;
        private Job job;
        private Handle(Object coordinator, H value, Run run) {
            this.coordinator = coordinator; this.value = value; this.run = run;
        }
        public H value() { return value; }
    }
    private enum State { QUEUED, RUNNING, FINISHED, CANCELLED }
    private interface Job extends Runnable { void rejectOrCancelQueued(); boolean pending(); }
    private final ExecutorService control;
    private final NativeOps<H> nativeOps;
    private final Set<Handle<H>> owners = Collections.newSetFromMap(new IdentityHashMap<>());
    private Handle<H> current;
    private Run active;
    private boolean closed;
    private boolean failed;

    public TaskerStopCoordinator(ExecutorService control, NativeOps<H> nativeOps) {
        this.control = Objects.requireNonNull(control);
        this.nativeOps = Objects.requireNonNull(nativeOps);
    }
    public synchronized Run beginRun() {
        if (closed || failed || active != null) return null;
        for (Handle<H> owner : owners) if (owner != current) return null;
        if (current != null && (!current.quiet || current.retired || current.failed || pending(current))) return null;
        active = new Run(this);
        if (current != null) {
            current.run = active; current.quiet = false; current.job = null; current.revision++;
        }
        return active;
    }
    public synchronized boolean isRunning() { return active != null; }
    public synchronized boolean isStopRequested() { return active != null && active.stopped; }
    public synchronized boolean isFailed() { return failed; }
    public synchronized H currentValue() { return current == null ? null : current.value; }

    /** Worker-only publication, including late creation after stop/destroy. Late owners are retained. */
    public Handle<H> install(H value) {
        Job dispatch = null;
        Handle<H> owner;
        synchronized (this) {
            if (active == null || active.sealed || current != null) throw new IllegalStateException("Invalid tasker publication");
            owner = new Handle<>(this, Objects.requireNonNull(value), active);
            owners.add(owner); current = owner;
            if (active.stopped || closed) dispatch = stopJob(owner);
        }
        dispatch(dispatch);
        return owner;
    }
    /** Only latches and queues: no library load, Binder call or native call on the caller. */
    public boolean requestStop() {
        Job dispatch = null;
        synchronized (this) {
            if (active != null && !active.sealed) active.stopped = true;
            if (active != null && !active.sealed && current != null) dispatch = stopJob(current);
        }
        dispatch(dispatch);
        synchronized (this) { return !failed; }
    }
    /** Serializes admission with stop. PostStop waits for admitted PostTask calls to return,
     * but never waits for MaaTaskerWait. A blocked PostTask remains an explicit unresolved owner. */
    public <T> T submit(Run run, Submission<H, T> operation) {
        Handle<H> owner;
        synchronized (this) {
            checkRun(run);
            if (failed || closed || run.stopped || run.sealed || current == null || current.retired) return null;
            owner = current; owner.submissions++; owner.quiet = false; owner.revision++;
        }
        try { return operation.call(owner.value); }
        catch (RuntimeException | Error exception) {
            synchronized (this) { fail(owner); }
            throw exception;
        } finally {
            synchronized (this) { owner.submissions--; notifyAll(); }
        }
    }
    public Handle<H> retireCurrent() {
        Handle<H> owner;
        Job dispatch;
        synchronized (this) {
            owner = current;
            if (owner == null) return null;
            current = null; owner.retired = true; owner.revision++;
            dispatch = stopJob(owner);
        }
        dispatch(dispatch);
        return owner;
    }
    /** Worker-only. False means retain the tasker AND every dependent resource/controller/agent. */
    public boolean destroyRetired(Handle<H> owner, Runnable detachAgents) {
        synchronized (this) {
            checkOwner(owner);
            if (!owner.retired || owner.destroyed || owner.destroying) return false;
            owner.destroying = true;
        }
        if (!awaitQuiet(owner)) return false;
        try {
            detachAgents.run();
            nativeOps.destroy(owner.value);
        } catch (RuntimeException | Error exception) {
            synchronized (this) { fail(owner); }
            return false;
        }
        synchronized (this) {
            owner.destroyed = true; owners.remove(owner); notifyAll(); return true;
        }
    }
    /** Worker-only native check. Retains the active run during subsequent wrapper cleanup. */
    public boolean awaitRunQuiescence(Run run) {
        Handle<H> owner;
        synchronized (this) {
            checkRun(run);
            if (failed) return false;
            owner = current;
            for (Handle<H> candidate : owners) if (candidate != owner) { fail(candidate); return false; }
        }
        return owner == null || awaitQuiet(owner);
    }
    public boolean finishRun(Run run) { return finishRun(run, () -> {}); }

    /** Native quiescence is sealed before terminal delivery. Admission remains occupied until
     * that callback returns; a stop arriving after sealing cannot create new old-run native work.
     * Call only after all other wrapper cleanup. Callback failure retains the graph fail-closed. */
    public boolean finishRun(Run run, Runnable terminalCallback) {
        Objects.requireNonNull(terminalCallback);
        for (;;) {
            Handle<H> owner;
            synchronized (this) {
                checkRun(run);
                if (failed || run.sealed) return false;
                owner = current;
                for (Handle<H> candidate : owners) if (candidate != owner) { fail(candidate); return false; }
            }
            if (owner != null && !awaitQuiet(owner)) return false;
            synchronized (this) {
                checkRun(run);
                if (failed) return false;
                if (owner != current || (owner != null && (!owner.quiet || pending(owner)))) continue;
                run.sealed = true;
                break;
            }
        }
        try { terminalCallback.run(); }
        catch (RuntimeException | Error exception) {
            synchronized (this) { failed = true; if (current != null) current.failed = true; notifyAll(); }
            return false;
        }
        synchronized (this) { active = null; notifyAll(); return true; }
    }
    /** Called for unhandled wrapper/setup failures. Uncertain native work is never reusable. */
    public void failRun(Run run) {
        Job dispatch = null;
        synchronized (this) {
            checkRun(run); failed = true; run.stopped = true;
            if (current != null) { current.failed = true; if (!run.sealed) dispatch = stopJob(current); }
            notifyAll();
        }
        dispatch(dispatch);
    }
    public void closeAdmission() {
        synchronized (this) { closed = true; }
        requestStop();
    }
    /** Running calls retain ownership even if shutdown interrupts them. Only QUEUED jobs cancel. */
    public void shutdownControl() {
        closeAdmission();
        List<Runnable> queued = control.shutdownNow();
        for (Runnable runnable : queued) {
            if (runnable instanceof Job) ((Job) runnable).rejectOrCancelQueued();
        }
    }
    private synchronized void checkRun(Run run) {
        if (run == null || run.coordinator != this || active != run) throw new IllegalStateException("Foreign or stale run");
    }
    private void checkOwner(Handle<H> owner) {
        if (owner == null || owner.coordinator != this || !owners.contains(owner)) throw new IllegalStateException("Foreign or spent tasker");
    }
    private boolean pending(Handle<H> owner) { return owner.submissions != 0 || (owner.job != null && owner.job.pending()); }
    private void fail(Handle<H> owner) { owner.failed = true; failed = true; notifyAll(); }
    private Job stopJob(Handle<H> owner) {
        if (owner.destroyed || owner.job != null) return null;
        owner.quiet = false; owner.revision++;
        Job job = new StopJob(owner); owner.job = job; return job;
    }
    private void dispatch(Job job) {
        if (job == null) return;
        try { control.execute(job); }
        catch (RuntimeException exception) { job.rejectOrCancelQueued(); }
    }
    private final class StopJob implements Job {
        private final Handle<H> owner;
        private State state = State.QUEUED;
        StopJob(Handle<H> owner) { this.owner = owner; }
        public boolean pending() { return state == State.QUEUED || state == State.RUNNING; }
        public void rejectOrCancelQueued() {
            synchronized (TaskerStopCoordinator.this) {
                if (state == State.QUEUED) { state = State.CANCELLED; fail(owner); }
                // A hostile/uncertain executor throwing after entry cannot reclaim a live call.
                else if (state == State.RUNNING) fail(owner);
            }
        }
        public void run() {
            synchronized (TaskerStopCoordinator.this) {
                if (state != State.QUEUED) return;
                state = State.RUNNING;
            }
            try {
                synchronized (TaskerStopCoordinator.this) {
                    while (owner.submissions != 0) TaskerStopCoordinator.this.wait();
                }
                nativeOps.postStop(owner.value);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                synchronized (TaskerStopCoordinator.this) { fail(owner); }
            } catch (RuntimeException | Error exception) {
                synchronized (TaskerStopCoordinator.this) { fail(owner); }
            } finally {
                synchronized (TaskerStopCoordinator.this) { state = State.FINISHED; TaskerStopCoordinator.this.notifyAll(); }
            }
        }
    }
    private boolean awaitQuiet(Handle<H> owner) {
        for (;;) {
            long revision;
            synchronized (this) {
                checkOwner(owner);
                try {
                    while (pending(owner) && !owner.failed) wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); fail(owner); return false;
                }
                if (owner.failed || failed) return false;
                revision = owner.revision;
            }
            final boolean running;
            try { running = nativeOps.running(owner.value); }
            catch (RuntimeException | Error exception) { synchronized (this) { fail(owner); } return false; }
            synchronized (this) {
                if (owner.failed || failed) return false;
                if (!running && revision == owner.revision && !pending(owner)) { owner.quiet = true; return true; }
            }
            try { Thread.sleep(20); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); synchronized (this) { fail(owner); } return false; }
        }
    }
}
