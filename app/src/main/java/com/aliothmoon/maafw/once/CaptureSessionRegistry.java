package com.aliothmoon.maafw.once;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One process-rooted slot. UNKNOWN is bounded quarantine, not garbage collection.
 * Deadline may only latch cancellation then queue the original owner. It proves
 * no OS/native completion and never interrupts, frees, retries or extends time. */
final class CaptureSessionRegistry {
    interface Dispatcher { boolean post(Runnable task); }
    interface Scheduler { void at(long deadline,Runnable task); }
    static final class Entry {
        final Object identity=new Object();
        final Object graph;
        final RealCaptureClaim.ProcessEpoch epoch;
        final long deadline;
        final Runnable cancel, cleanup;
        final BooleanSupplier closed;
        final Dispatcher owner;
        final AtomicBoolean requested=new AtomicBoolean();
        volatile boolean queueUnknown;
        Entry(Object graph,RealCaptureClaim.ProcessEpoch epoch,long deadline,Runnable cancel,Runnable cleanup,
                BooleanSupplier closed,Dispatcher owner) {
            this.graph=graph;this.epoch=epoch;this.deadline=deadline;this.cancel=cancel;
            this.cleanup=cleanup;this.closed=closed;this.owner=owner;
        }
    }
    private Entry active;
    synchronized Entry retain(Object graph,RealCaptureClaim.ProcessEpoch epoch,long deadline,
            Runnable cancel,Runnable cleanup,BooleanSupplier closed,Dispatcher owner) {
        if(active!=null)throw new IllegalStateException("Capture process quarantined or busy");
        return active=new Entry(Objects.requireNonNull(graph),Objects.requireNonNull(epoch),deadline,
                Objects.requireNonNull(cancel),Objects.requireNonNull(cleanup),Objects.requireNonNull(closed),Objects.requireNonNull(owner));
    }
    void arm(Entry entry,Scheduler scheduler,LongSupplier clock) {
        synchronized(this){if(active!=entry)throw new SecurityException("Stale registry entry");}
        try {scheduler.at(entry.deadline,()->{
            if(clock.getAsLong()<entry.deadline) { request(entry); return; } // early timer fails closed, never extends
            request(entry);
        });} catch(RuntimeException failure){request(entry);}
    }
    void request(Entry entry) {
        synchronized(this){if(active!=entry)return;}
        entry.cancel.run(); // scalar-only callback supplied by runtime, never resource cleanup
        if(!entry.requested.compareAndSet(false,true))return;
        try {if(!entry.owner.post(()->{entry.cleanup.run();releaseIfClosed(entry);})){entry.queueUnknown=true;}}
        catch(RuntimeException failure){entry.queueUnknown=true;}
    }
    void releaseIfClosed(Entry entry) {
        // Evaluated only by original owner, outside registry monitor.
        if(!entry.closed.getAsBoolean())return;
        synchronized(this){if(active==entry)active=null;}
    }
    synchronized boolean retaining(){return active!=null;}
    synchronized boolean unknownQueue(){return active!=null && active.queueUnknown;}
}
