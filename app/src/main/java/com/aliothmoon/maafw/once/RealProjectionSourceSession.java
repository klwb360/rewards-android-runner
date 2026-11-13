package com.aliothmoon.maafw.once;

import android.media.projection.MediaProjection;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.view.Surface;
import java.io.IOException;
import java.util.Objects;

/** Ownership-bearing single-use live handover. Gate is checked before construction.
 * Retained by admission until app-local cleanup, never an OS/GPU quiet proof. */
final class RealProjectionSourceSession<P,D> implements PollingFrameSource.ProducerFactory, PollingFrameSource.Producer {
    private final OnceBackend.Owner owner;
    private final Thread thread=Thread.currentThread();
    private P pending;
    private ProjectionLifecycle.Ports<P,D,Surface> ports;
    private final java.util.function.Consumer<P> stopPending;
    private final ProjectionLifecycle.Source domain;

    private final BackendPorts.RevocationFence admissionFence;
    private final int width,height,dpi;
    private ProjectionLifecycle<P,D,Surface> core;
    private ProjectionLifecycle.TargetKey key;
    private boolean bound,opened,produced,closed,clean;
    private PollingFrameSource source;
    static RealProjectionSourceSession<MediaProjection,VirtualDisplay> live(CaptureAdmissionCoordinator.Ticket ticket,OnceBackend.Owner owner,
            MediaProjection projection,Handler callbacks,BackendPorts.RevocationFence fence,int width,int height,int dpi) {
        CaptureReleaseGate.requireConfigured();
        Objects.requireNonNull(ticket).consumeHandover(owner,projection);
        return new RealProjectionSourceSession<>(owner,projection,new AndroidProjectionPorts(projection,callbacks),
                MediaProjection::stop,fence,width,height,dpi,ProjectionLifecycle.Source.DORMANT_MEDIA_PROJECTION);
    }
    static <P,D> RealProjectionSourceSession<P,D> fixture(SyntheticAdmission admission,OnceBackend.Owner owner,P projection,
            ProjectionLifecycle.Ports<P,D,Surface> ports,java.util.function.Consumer<P> stop,
            BackendPorts.RevocationFence fence,int width,int height,int dpi) {
        if((Object)ports instanceof AndroidProjectionPorts)throw new SecurityException("Fixture cannot use Android ports");
        RealProjectionSourceSession<P,D> session=new RealProjectionSourceSession<>(owner,projection,ports,stop,fence,width,height,dpi,ProjectionLifecycle.Source.SYNTHETIC_OS_FACADE);
        if(admission==null || !admission.bindSource(owner,session))throw new SecurityException("Consumed synthetic owner required");
        return session;
    }
    private RealProjectionSourceSession(OnceBackend.Owner owner,P projection,ProjectionLifecycle.Ports<P,D,Surface> ports,
            java.util.function.Consumer<P> stop,BackendPorts.RevocationFence fence,int width,int height,int dpi,ProjectionLifecycle.Source domain) {
        if(width<=0 || height<=0 || width>4096 || height>4096 || (long)width*height>4L*1024*1024 || dpi<=0 || dpi>2000)
            throw new IllegalArgumentException("Invalid output bounds");
        this.owner=Objects.requireNonNull(owner);this.pending=Objects.requireNonNull(projection);
        this.ports=Objects.requireNonNull(ports);this.stopPending=Objects.requireNonNull(stop);this.domain=domain;
        this.admissionFence=Objects.requireNonNull(fence);this.width=width;this.height=height;this.dpi=dpi;
    }
    private void require(OnceBackend.Owner expected) {
        if(expected!=owner || Thread.currentThread()!=thread)throw new SecurityException("Wrong handover owner/thread");
    }
    PollingFrameSource source() { require(owner);if(source!=null)throw new IllegalStateException("One source only");source=new PollingFrameSource(width,height,this);return source; }
    public void validateOwner(OnceBackend.Owner expected) {
        require(expected);if(bound || closed)throw new SecurityException("Handover already used");bound=true;
    }
    public BackendPorts.RevocationFence revocationFence(OnceBackend.Owner expected) {
        require(expected);return core==null?admissionFence:core.fence();
    }
    public PollingFrameSource.Producer open(Surface target,BackendPorts.StopSignal stop) {
        throw new SecurityException("Owner required");
    }
    public PollingFrameSource.Producer open(OnceBackend.Owner expected,Surface target,BackendPorts.StopSignal stop) {
        require(expected);if(!bound || opened || closed)throw new IllegalStateException();

        core=new ProjectionLifecycle<>(owner,ports,target,width,height,dpi,true,domain,admissionFence);
        key=core.targetKey(owner,target);opened=true;return this;
    }
    public void produce(BackendPorts.StopSignal stop) throws Exception {
        require(owner);if(!opened || produced || closed)throw new IllegalStateException();produced=true;
        // core takes the already-owned handle before checking cancellation.
        if(stop.requested() || admissionFence.revoked())core.requestStop();
        core.start(owner);pending=null;
        if(stop.requested() || admissionFence.revoked()) { core.requestStop();throw new BackendPorts.Cancelled(); }
    }
    public boolean ready() { require(owner);if(admissionFence.revoked())core.requestStop();return core.geometryConfirmed(); }
    public boolean fullyClosed() { require(owner);return clean; }
    public void close() throws IOException {
        closeOwned();
        if(core!=null && !core.permitsReaderRelease(core.readerReleaseReceipt(owner),owner,key,
                domain))throw new IOException("Target detach unproved");
    }
    void closeOwned() {
        require(owner);if(closed)return;closed=true;
        if(core!=null) {core.close(owner);clean=core.evidence(owner).appReferencesDropped();if(clean){pending=null;ports=null;}}
        else if(pending!=null) {try {stopPending.accept(pending);pending=null;ports=null;clean=true;}catch(RuntimeException uncertain){clean=false;}}
    }
    boolean appClosed() { require(owner);return clean && (!bound || (source!=null && source.completion(owner).released()==BackendPorts.Released.CONFIRMED)); }
}