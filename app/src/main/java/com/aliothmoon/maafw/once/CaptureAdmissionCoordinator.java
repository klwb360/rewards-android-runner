package com.aliothmoon.maafw.once;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** One-shot owner-thread protocol. Fixture authority is structurally separate from
 * Android authority. Cancel is scalar and may arrive during a blocking OS call.
 * No monitor is held across a port call. Uncertain acquisition/cleanup is retained,
 * never retried or equated to system quiescence. No production entry constructs it. */
final class CaptureAdmissionCoordinator<P> {
    enum State { NEW, CLAIMING, WAITING_CONSENT, STARTING_FGS, WAITING_FGS, ACQUIRING, ACQUIRED, CANCELLED, DENIED, UNKNOWN, CLOSED }
    static final class Ticket {
        private final CaptureAdmissionCoordinator<?> session;
        private boolean handoverConsumed;
        private OnceBackend.Owner handoverOwner;
        private Ticket(CaptureAdmissionCoordinator<?> session) { this.session=session; }
        void requireLive() { CaptureReleaseGate.requireConfigured(); if (!RealCaptureClaim.live(session.grant)) throw new SecurityException("Fixture authority is not live"); }
        void requireCurrent() { session.requireCurrent(); }
        void consumeHandover(OnceBackend.Owner owner,Object projection) {
            requireLive();session.requireCurrent();
            if(handoverConsumed || owner==null || projection==null || session.held!=projection || session.state!=State.ACQUIRED) throw new SecurityException("Invalid projection handover capability");
            handoverConsumed=true;handoverOwner=owner;
        }
        void requireActiveScalar() { if(session.cancelled.get() || !RealCaptureClaim.current(session.grant,session.clock.getAsLong())) throw new SecurityException("Expired or cancelled ticket"); }
    }
    static final class Request {
        private final Ticket ticket;
        private final Object nonce = new Object();
        private Request(Ticket ticket) { this.ticket=ticket; }
    }
    interface Ports<P> {
        void launchConsent(Ticket ticket, Request request) throws Exception;
        void startForeground(Ticket ticket) throws Exception;
        P acquire(Ticket ticket, Object ephemeralResult) throws Exception;
        void stop(Ticket ticket, P projection) throws Exception;
        default void finishForeground(Ticket ticket) throws Exception { }
    }
    private final Thread thread = Thread.currentThread();
    private final RealCaptureClaim.ProcessEpoch epoch;
    private final Object owner;
    private final RealCaptureClaim.Grant grant;
    private final RealCaptureClaim.BurnStore store;
    private final LongSupplier clock;
    private final Ports<P> ports;
    private final Ticket ticket = new Ticket(this);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final BackendPorts.RevocationFence fence = new BackendPorts.RevocationFence();
    private State state=State.NEW;
    private Request request;
    private Object consent;
    private Object service;
    private P held;
    private RealProjectionSourceSession<?,?> transferred;
    private OnceBackend backend;
    private boolean admissionUncertain, closeAttempted, acquisitionUncertain, operationInFlight, closeRequested, foregroundAttempted;
    private CaptureAdmissionCoordinator(RealCaptureClaim.ProcessEpoch epoch, Object owner, RealCaptureClaim.Grant grant,
            RealCaptureClaim.BurnStore store, LongSupplier clock, Ports<P> ports) {
        this.epoch=Objects.requireNonNull(epoch);this.owner=Objects.requireNonNull(owner);this.grant=Objects.requireNonNull(grant);
        this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);this.ports=Objects.requireNonNull(ports);
    }
    static <P> CaptureAdmissionCoordinator<P> fixture(RealCaptureClaim.ProcessEpoch epoch,Object owner,RealCaptureClaim.Grant grant,
            RealCaptureClaim.BurnStore store,LongSupplier clock,Ports<P> ports) {
        return new CaptureAdmissionCoordinator<>(epoch,owner,grant,store,clock,ports);
    }
    static <P> CaptureAdmissionCoordinator<P> live(RealCaptureClaim.ProcessEpoch epoch,Object owner,RealCaptureClaim.Grant grant,
            RealCaptureClaim.BurnStore store,LongSupplier clock,Ports<P> ports) {
        CaptureReleaseGate.requireConfigured();
        if (!RealCaptureClaim.live(grant)) throw new SecurityException("Live visible authority required");
        return new CaptureAdmissionCoordinator<>(epoch,owner,grant,store,clock,ports);
    }
    /** Owner-loop only; no raw projection escapes. Construction failure leaves held owned here. */
    RealProjectionSourceSession<?,?> handover(OnceBackend.Owner expected, android.os.Handler callbacks,
            int width,int height,int dpi) {
        ownerThread();ticket.requireLive();requireCurrent();
        if (state!=State.ACQUIRED || transferred!=null || expected==null ||
                !(held instanceof android.media.projection.MediaProjection)) throw new SecurityException("Wrong handover owner/state");
        RealProjectionSourceSession<?,?> next=RealProjectionSourceSession.live(ticket,expected,
                (android.media.projection.MediaProjection)held,callbacks,fence,width,height,dpi);
        transferred=next;held=null;return next;
    }
    /** Must be constructed AND started on the admission owner loop. The executor
     * must dispatch back to that same loop; never runs recognition on the UI. */
    OnceBackend admittedBackend(BackendPorts.Recognizer recognizer, java.util.concurrent.Executor executor,
            android.os.Handler callbacks,int width,int height,int dpi) {
        ownerThread();ticket.requireLive();requireCurrent();
        final class LazySource implements BackendPorts.FrameSource {
            PollingFrameSource delegate;
            OnceBackend.Owner bound;
            public void produce(OnceBackend.Owner o,BackendPorts.FrameSink sink,BackendPorts.StopSignal stop) throws Exception {
                ownerThread();if(o!=bound || delegate==null)throw new SecurityException();
                delegate.produce(o,sink,()->stop.requested() || fence.revoked());
            }
            public BackendPorts.RevocationFence revocationFence(OnceBackend.Owner o) {
                if(o!=bound)throw new SecurityException();return fence;
            }
            public BackendPorts.SourceTiming sourceTiming(OnceBackend.Owner o) {
                ownerThread();return o!=null&&o==bound&&delegate!=null&&!fence.revoked()?delegate.sourceTiming(o):null;
            }
            public void release(OnceBackend.Owner o) throws Exception { ownerThread();delegate.release(o); }
            public BackendPorts.Completion completion(OnceBackend.Owner o) throws Exception { return delegate.completion(o); }
        }
        LazySource lazy=new LazySource();
        if(backend!=null)throw new IllegalStateException("One backend only");
        backend=new OnceBackend(o->{
            ownerThread();RealProjectionSourceSession<?,?> source=handover(o,callbacks,width,height,dpi);
            lazy.bound=o;lazy.delegate=source.source();
            return new BackendPorts.ClaimReceipt(o,BackendPorts.ClaimDecision.CLAIMED);
        },lazy,recognizer,executor);
        return backend;
    }
    private void ownerThread() { if(Thread.currentThread()!=thread)throw new IllegalStateException("Wrong admission owner thread"); }
    private void requireCurrent() {
        ownerThread();
        if(cancelled.get() || !RealCaptureClaim.current(grant,clock.getAsLong())) {
            cancel(); throw new SecurityException("Cancelled or expired admission");
        }
    }
    Request begin() {
        ownerThread(); if(state!=State.NEW)throw new IllegalStateException("No replay");
        if(!epoch.retain(this)) {state=State.DENIED;return null;}
        state=State.CLAIMING;
        RealCaptureClaim.Outcome result=RealCaptureClaim.consume(grant,epoch,owner,clock.getAsLong(),store);
        if(result!=RealCaptureClaim.Outcome.CONSUMED) { admissionUncertain=result==RealCaptureClaim.Outcome.UNCERTAIN;state=result==RealCaptureClaim.Outcome.UNCERTAIN?State.UNKNOWN:State.DENIED;return null; }
        try {
            requireCurrent(); request=new Request(ticket); state=State.WAITING_CONSENT;
            ports.launchConsent(ticket,request); requireCurrent(); return request;
        } catch(Exception failure) { cancel();state=State.UNKNOWN;return null; }
    }
    /** Only the exact in-memory request from this owner/process is accepted. Saved
     * state, Intent extras and integer request codes are not authorization. */
    boolean consentResult(Request incoming, boolean granted, Object ephemeralResult) {
        ownerThread();
        if(state!=State.WAITING_CONSENT || incoming==null || incoming!=request || incoming.ticket!=ticket || incoming.nonce!=request.nonce)return false;
        request=null; // consume before any further action, denial also consumes
        if(!granted || ephemeralResult==null) { cancel();state=State.DENIED;return false; }
        try {
            requireCurrent(); consent=ephemeralResult;state=State.STARTING_FGS;
            foregroundAttempted=true;ports.startForeground(ticket);requireCurrent();state=State.WAITING_FGS;return true;
        } catch(Exception failure) { consent=null;cancel();state=State.UNKNOWN;return false; }
    }
    /** Called by the owning service only after startForeground actually returns.
     * The concrete Android boundary also independently checks service/ticket identity. */
    void foregroundReady(Object serviceIdentity) {
        ownerThread(); if(state!=State.WAITING_FGS || serviceIdentity==null || service!=null)throw new IllegalStateException();
        service=serviceIdentity;
        try {
            requireCurrent();state=State.ACQUIRING;
            Object result=consent;consent=null;acquisitionUncertain=true;
            operationInFlight=true;
            try { held=ports.acquire(ticket,result); } // adopt late handle BEFORE observing cancellation
            finally { operationInFlight=false; }
            if(held==null)throw new IllegalStateException("Unknown acquisition");
            acquisitionUncertain=false;requireCurrent();state=State.ACQUIRED;
        } catch(Exception failure) { consent=null;cancel();state=State.UNKNOWN; }
        if(closeRequested)close();
    }
    void serviceDestroyed(Object expected) { if(service==null || service==expected)cancel(); }
    void cancel() { cancelled.set(true);fence.revoke(); }
    BackendPorts.RevocationFence fence() { return fence; }
    void close() {
        ownerThread();cancel();request=null;consent=null;closeRequested=true;
        if(operationInFlight || closeAttempted)return;
        closeAttempted=true;
        boolean cleanupFailed=false;
        if(held!=null) {
            try { ports.stop(ticket,held);held=null; }
            catch(Exception failure) { cleanupFailed=true; }
        }
        if(transferred!=null) {
            transferred.closeOwned();
            if(!transferred.appClosed()) { state=State.UNKNOWN;return; }
        }
        if(backend!=null) {
            OnceBackend.Result result=backend.snapshot();
            if(result.cleanup()!=OnceBackend.Cleanup.CONFIRMED && result.cleanup()!=OnceBackend.Cleanup.NOT_NEEDED) { state=State.UNKNOWN;return; }
            if(result.quiescence()==OnceBackend.Quiescence.UNCONFIRMED || result.memory()==OnceBackend.Memory.RETAINED) { state=State.UNKNOWN;return; }
        }
        if(foregroundAttempted) {
            try { ports.finishForeground(ticket); } catch(Exception failure) { cleanupFailed=true; }
        }
        state=admissionUncertain || acquisitionUncertain || cleanupFailed?State.UNKNOWN:State.CLOSED;
        if(state==State.CLOSED)epoch.release(this);
    }
    State state() { ownerThread();return state; }
    boolean retainingProjection() { ownerThread();return held!=null; }
}
