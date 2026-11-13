package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Single-use owner-thread acquisition. An IPC exception or timeout is not exit evidence. */
final class PngCaptureSession {
    interface Process {
        /** Adopt descriptor handles before checking stop. No screenshot bytes via Binder. */
        BoundedPngGray.Input adoptStdout() throws Exception;
        boolean waitForExit(int millis) throws Exception;
        int exitCode() throws Exception;
        void destroy() throws Exception;
        /** Close each acquired FD once. Return only on known successful closure. */
        boolean closeDescriptors() throws Exception;
    }
    interface Transport { Process open() throws Exception; }
    interface Gate { void requireCurrent() throws Exception; }
    interface InputDecorator { BoundedPngGray.Input decorate(BoundedPngGray.Input input) throws Exception; }
    enum State { NEW, OPENING, READING, CLEANING, CLOSED, UNKNOWN }
    enum Stage { NOT_STARTED, LOCAL_GATE, FIXED_ARGV_OPEN, PFD_TRANSFER, PNG_READ, CHILD_EXIT, CHILD_STATUS, CLEANUP, COMPLETE }
    enum Failure { NONE, LOCAL_GATE, OPEN, PFD, PNG_OR_PIPE, CHILD_EXIT, CHILD_STATUS, DEADLINE, CANCELLED, CLEANUP_UNKNOWN, OTHER }
    enum AbortState { NOT_REQUESTED, RUNNING, RETURNED, START_UNKNOWN }
    record Snapshot(State state,Stage stage,Failure failure,boolean acquisitionAttempted,boolean processHandleKnown,
            boolean childExitObserved,boolean exitStatusObserved,int childExitStatus,
            boolean clientPfdAllClosed,AbortState abortState,boolean captureBodyReturned,
            boolean ownerQuiet,boolean pngAccepted,BoundedPngGray.Diagnostic png) { }
    private static final Set<PngCaptureSession> RETAINED=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final int MAX_RETAINED=4;
    // This thread only requests cancellation and starts a separate abort worker.
    // It never makes Binder calls, so one stuck destroy cannot block later timers.
    private static final ScheduledThreadPoolExecutor DEADLINES=deadlines();
    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor e=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"png-source-deadline");t.setDaemon(true);return t;});
        e.setRemoveOnCancelPolicy(true);return e;
    }
    private final Transport transport;
    private final Gate gate;
    private final LongSupplier clock;
    private final BoundedPngGray.Geometry geometry;
    private State state=State.NEW;
    private Process process;
    private boolean exitConfirmed, descriptorsClosed, destroyAttempted, active;
    private boolean stopRequested, abortClosed, abortActive, abortStartUnknown;
    private CountDownLatch abortReturned;
    private Stage lastStage=Stage.NOT_STARTED;
    private Failure failure=Failure.NONE;
    private boolean acquisitionAttempted,exitStatusAttempted,exitStatusObserved,captureBodyReturned,pngAccepted;
    private BoundedPngGray.Diagnostic pngDiagnostic=new BoundedPngGray.Diagnostic(BoundedPngGray.Stage.NOT_STARTED,-1,-1,-1,-1,BoundedPngGray.Chunk.NONE,-1);
    private int exitCode;

    PngCaptureSession(Transport transport, Gate gate, LongSupplier clock, BoundedPngGray.Geometry geometry) {
        if(transport==null||gate==null||clock==null||geometry==null) throw new IllegalArgumentException();
        this.transport=transport;this.gate=gate;this.clock=clock;this.geometry=geometry;
    }
    synchronized State state() { return state; }
    synchronized boolean quietAndReleased() { return state==State.CLOSED&&!active&&exitConfirmed&&descriptorsClosed&&!abortActive&&!abortStartUnknown; }
    private synchronized void state(State s) { state=s; }
    private synchronized boolean stopped() { return stopRequested; }
    private synchronized void stage(Stage value){lastStage=value;}
    synchronized Snapshot snapshot() {
        AbortState abort=abortStartUnknown?AbortState.START_UNKNOWN:abortActive?AbortState.RUNNING:
                destroyAttempted?AbortState.RETURNED:AbortState.NOT_REQUESTED;
        return new Snapshot(state,lastStage,failure,acquisitionAttempted,process!=null,acquisitionAttempted&&process!=null&&exitConfirmed,exitStatusObserved,
                exitStatusObserved?exitCode:Integer.MIN_VALUE,descriptorsClosed,abort,captureBodyReturned,!active&&captureBodyReturned,pngAccepted,pngDiagnostic);
    }
    /** Safe for UI/deadline threads: no Binder or FD call on this caller.
     * In-flight newProcess without a returned handle cannot be killed by guessing. */
    void requestStop() {
        Process target;
        CountDownLatch returned;
        synchronized(this) {
            if(abortClosed) return;
            stopRequested=true;
            if(process==null||destroyAttempted) return;
            destroyAttempted=true;abortActive=true;
            abortReturned=new CountDownLatch(1);returned=abortReturned;target=process;
        }
        try {
            Thread abort=new Thread(()->{
                try { target.destroy(); }
                catch(Exception ignored) { /* Exit acknowledgement is still required. */ }
                finally {
                    synchronized(PngCaptureSession.this){abortActive=false;}
                    returned.countDown();
                }
            },"png-source-abort");
            abort.setDaemon(true);abort.start();
        } catch(Throwable failedStart) {
            synchronized(this){abortActive=false;abortStartUnknown=true;}
            returned.countDown(); // A failed dispatch is never retried.
        }
    }
    private void publish(Process acquired) {
        boolean abort;
        synchronized(this){process=acquired;abort=stopRequested;}
        if(abort) requestStop();
    }
    private static int cleanupMillis(long end) {
        long left=end-System.nanoTime();
        return left<=0?0:(int)Math.min(1000,(left+999_999)/1_000_000);
    }
    private boolean awaitAbort(long end) {
        CountDownLatch returned;
        synchronized(this){returned=abortReturned;}
        if(returned==null)return true;
        try { return returned.await(cleanupMillis(end),TimeUnit.MILLISECONDS); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
    }
    private void check(BoundedPngGray.Stop stop,long start) throws IOException {
        long elapsed=clock.getAsLong()-start;
        if(elapsed<0||elapsed>=BoundedPngGray.MAX_BUDGET_NANOS){failure=Failure.DEADLINE;throw new IOException("deadline");}
        if(stop.requested()||stopped()){failure=Failure.CANCELLED;throw new BoundedPngGray.Cancelled();}
    }
    BoundedPngGray.Receipt capture(BoundedPngGray.Sink sink, BoundedPngGray.Stop stop) throws Exception {
        return capture(sink,stop,null);
    }
    BoundedPngGray.Receipt capture(BoundedPngGray.Sink sink, BoundedPngGray.Stop stop,InputDecorator decorator) throws Exception {
        synchronized(this) {
            if(state!=State.NEW) throw new IllegalStateException("single use, no retry");
            synchronized(RETAINED) {
                if(RETAINED.size()>=MAX_RETAINED) {
                    // Consumed pre-acquisition rejection, not unknown OS ownership.
                    state=State.CLOSED;exitConfirmed=true;descriptorsClosed=true;abortClosed=true;captureBodyReturned=true;
                    throw new IOException("retained capture limit");
                }
                RETAINED.add(this);
            }
            active=true;state=State.OPENING;
        }
        boolean invoked=false, success=false;
        BoundedPngGray.Receipt receipt=null;
        long start=0;
        ScheduledFuture<?> deadline=null;
        try {
            start=clock.getAsLong();
            deadline=DEADLINES.schedule(this::requestStop,5,TimeUnit.SECONDS);
            stage(Stage.LOCAL_GATE);
            gate.requireCurrent();
            check(stop,start);
            invoked=true; // A thrown or late Binder result must never be retried.
            acquisitionAttempted=true;stage(Stage.FIXED_ARGV_OPEN);
            publish(transport.open());
            if(process==null) throw new IOException("missing process handle");
            stage(Stage.PFD_TRANSFER);BoundedPngGray.Input input=process.adoptStdout();
            if(decorator!=null)input=decorator.decorate(input);
            state(State.READING);
            stage(Stage.PNG_READ);
            BoundedPngGray decoder=new BoundedPngGray(input,sink,()->stop.requested()||stopped(),clock,start,BoundedPngGray.MAX_BUDGET_NANOS,geometry);
            try{receipt=decoder.decode();pngAccepted=true;}finally{pngDiagnostic=decoder.diagnostic();}
            stage(Stage.LOCAL_GATE);
            gate.requireCurrent();
            check(stop,start);
            long left=BoundedPngGray.MAX_BUDGET_NANOS-(clock.getAsLong()-start);
            if(left<=0 || left>BoundedPngGray.MAX_BUDGET_NANOS) throw new IOException("deadline");
            stage(Stage.CHILD_EXIT);exitConfirmed=process.waitForExit((int)Math.max(1,Math.min(1000,(left+999_999)/1_000_000)));
            if(!exitConfirmed) throw new IOException("exit not observed");
            check(stop,start);
            stage(Stage.CHILD_STATUS);exitStatusAttempted=true;exitCode=process.exitCode();exitStatusObserved=true;
            check(stop,start);
            if(exitCode!=0) throw new IOException("capture process failed");
            stage(Stage.LOCAL_GATE);gate.requireCurrent();check(stop,start);success=true;
        } catch(Exception failed) {
            if(failure==Failure.NONE) {
                failure=pngDiagnostic.stage()==BoundedPngGray.Stage.DEADLINE?Failure.DEADLINE:
                        failed instanceof BoundedPngGray.Cancelled?Failure.CANCELLED:
                        switch(lastStage){case LOCAL_GATE->Failure.LOCAL_GATE;case FIXED_ARGV_OPEN->Failure.OPEN;
                            case PFD_TRANSFER->Failure.PFD;case PNG_READ->Failure.PNG_OR_PIPE;
                            case CHILD_EXIT->Failure.CHILD_EXIT;case CHILD_STATUS->Failure.CHILD_STATUS;default->Failure.OTHER;};
            }
            throw failed;
        } finally {
            state(State.CLEANING);
            final long cleanupEnd=System.nanoTime()+1_000_000_000L;
            try {
                if(!invoked) { exitConfirmed=true;descriptorsClosed=true; }
                else if(process!=null) {
                    if(!exitConfirmed) {
                        requestStop();
                        // Do not queue another Binder wait behind a still-stuck abort.
                        int remaining;
                        if(awaitAbort(cleanupEnd)&&(remaining=cleanupMillis(cleanupEnd))>0)
                            try { exitConfirmed=process.waitForExit(remaining); }
                        catch(Exception ignored) { exitConfirmed=false; }
                    }
                    if(exitConfirmed&&!exitStatusAttempted&&cleanupMillis(cleanupEnd)>0) {
                        exitStatusAttempted=true;
                        try{exitCode=process.exitCode();exitStatusObserved=true;}catch(Exception ignored){/* Exit remains known, status unknown; no retry. */}
                    }
                }
            } catch(Exception ignored) {
                // Unknown destroy is not retried. FDs still receive their one close attempt.
            } finally {
                synchronized(this){abortClosed=true;}
                if(deadline!=null)deadline.cancel(false); // Late callback sees abortClosed, cannot dispatch.
                boolean abortQuiet=awaitAbort(cleanupEnd);
                if(process!=null) {
                    try { descriptorsClosed=process.closeDescriptors(); }
                    catch(Exception ignored) { descriptorsClosed=false; }
                }
                synchronized(this) {
                    active=false;
                    captureBodyReturned=true;
                    state=exitConfirmed&&descriptorsClosed&&abortQuiet&&!abortActive&&!abortStartUnknown?State.CLOSED:State.UNKNOWN;
                }
                if(quietAndReleased()) synchronized(RETAINED) { RETAINED.remove(this); }
            }
        }
        if(!success || !quietAndReleased()){if(failure==Failure.NONE)failure=Failure.CLEANUP_UNKNOWN;throw new IOException("capture cleanup unconfirmed");}
        check(stop,start); // Late Binder/gate/cleanup return never publishes a fresh result.
        stage(Stage.COMPLETE);
        return receipt;
    }
    synchronized boolean destroyAttempted() { return destroyAttempted; }
}
