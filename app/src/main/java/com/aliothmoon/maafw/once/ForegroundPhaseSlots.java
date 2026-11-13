package com.aliothmoon.maafw.once;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Two fixed component slots, not capture authority or a reusable pool. Both spent
 * bits are permanent. Only an exact delivered instance's actual destruction can
 * close a dispatched slot; any uncertain dispatch/stop/wait quarantines it. */
final class ForegroundPhaseSlots<T> {
    enum Phase { BEFORE, AFTER }
    static final class KnownNotDispatched extends IllegalStateException {
        private static final long serialVersionUID=1L;
        KnownNotDispatched(){super("Foreground phase unavailable before OS dispatch");}
    }
    static final class Lease<T> {
        private final ForegroundPhaseSlots<T> origin;
        final Phase phase;
        private T payload;
        private Object service;
        private boolean entered,returned,delivered,closing,destroyed,unknown,closed;
        private Lease(ForegroundPhaseSlots<T> origin,Phase phase,T payload){this.origin=origin;this.phase=phase;this.payload=payload;}
    }
    record Snapshot(Phase phase,boolean entered,boolean returned,boolean delivered,boolean destroyed,boolean closed,boolean unknown){
        boolean confirmedDestruction(){return entered&&returned&&delivered&&destroyed&&closed&&!unknown;}
        String summary(){return "requestedFgs="+phase+" observedFgs="+(delivered?phase:"NONE")+" dispatchEntered="+entered+" dispatchReturned="+returned+" delivered="+delivered+" destroyAck="+destroyed+" fgsClosed="+closed+" fgsUnknown="+unknown;}
    }
    synchronized Snapshot snapshot(Lease<T> lease){own(lease);return new Snapshot(lease.phase,lease.entered,lease.returned,lease.delivered,lease.destroyed,lease.closed,lease.unknown);}
    private final boolean[] spent=new boolean[2];
    private boolean beforeClosed;
    private Lease<T> active;
    synchronized Lease<T> reserve(Phase phase,T payload){
        Objects.requireNonNull(phase);Objects.requireNonNull(payload);
        if(spent[phase.ordinal()] || active!=null || phase==Phase.AFTER&&!beforeClosed)
            throw new KnownNotDispatched();
        spent[phase.ordinal()]=true;
        return active=new Lease<>(this,phase,payload);
    }
    private void own(Lease<T> lease){if(lease==null||lease.origin!=this)throw new SecurityException("Foreign foreground tuple");}
    private void active(Lease<T> lease){own(lease);if(active!=lease||lease.closed)throw new IllegalStateException("Stale foreground tuple");}
    synchronized T payload(Lease<T> lease){own(lease);return lease.payload;}
    synchronized void beginDispatch(Lease<T> lease){
        active(lease);if(lease.entered||lease.closing||lease.unknown)throw new KnownNotDispatched();
        lease.entered=true; // Before the OS call; exceptions from now on may have dispatched.
    }
    synchronized void dispatchReturned(Lease<T> lease){active(lease);lease.returned=true;settle(lease);}
    synchronized void unknown(Lease<T> lease){
        own(lease);if(active==lease&&!lease.closed){lease.unknown=true;notifyAll();}
    }
    synchronized Lease<T> deliver(Phase exactComponent,Object service){
        if(service==null||active==null||active.phase!=exactComponent||!active.entered||active.delivered)return null;
        active.delivered=true;active.service=service;return active;
    }
    synchronized boolean mayStart(Lease<T> lease,Object service){
        own(lease);return active==lease&&lease.delivered&&lease.service==service&&!lease.closing&&!lease.unknown&&!lease.destroyed;
    }
    synchronized void requestClose(Lease<T> lease){
        own(lease);if(lease.closed)return;
        active(lease);lease.closing=true;settle(lease);
    }
    synchronized Object serviceToStop(Lease<T> lease){
        own(lease);return active==lease&&lease.closing&&!lease.destroyed?lease.service:null;
    }
    synchronized boolean isClosed(Lease<T> lease){own(lease);return lease.closed;}
    synchronized boolean isDestroyed(Lease<T> lease){own(lease);return lease.destroyed;}
    synchronized boolean destroyed(Lease<T> lease,Object exactService){
        own(lease);
        if(active!=lease||!lease.delivered||lease.service!=exactService||lease.destroyed)return false;
        lease.destroyed=true;settle(lease);notifyAll();return true;
    }
    private void settle(Lease<T> lease){
        if(lease.closing&&!lease.unknown&&(!lease.entered||lease.returned&&lease.destroyed)){
            lease.closed=true;lease.payload=null;lease.service=null;active=null;
            if(lease.phase==Phase.BEFORE)beforeClosed=true;
            notifyAll();
        }
    }
    /** Called only from the background owner. An actual callback signals directly,
     * never via that same owner's queue. Timeout/interrupt remains UNKNOWN forever. */
    synchronized boolean awaitClosed(Lease<T> lease,long timeoutMillis)throws InterruptedException{
        own(lease);if(timeoutMillis<0||timeoutMillis>5000)throw new IllegalArgumentException();
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try{
            while(!lease.closed&&!lease.unknown){
                long left=deadline-System.nanoTime();
                if(left<=0){unknown(lease);break;}
                TimeUnit.NANOSECONDS.timedWait(this,left);
            }
        }catch(InterruptedException interrupted){unknown(lease);throw interrupted;}
        return lease.closed;
    }
    synchronized boolean retaining(){return active!=null;}
    synchronized boolean spent(Phase phase){return spent[phase.ordinal()];}
}
