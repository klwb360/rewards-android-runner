package com.aliothmoon.maafw.once;

import android.view.Surface;
import java.io.IOException;
import java.util.Objects;

/** Dormant resource bridge, not capture authority. The only constructible entry is
 * an explicitly synthetic, consumed-admission host fixture; Android ports reject.
 * No production provider/factory/UI can bind this. Future real admission remains
 * unimplemented. Receipt domain is never upgraded from synthetic to real. */
final class ProjectionSourceSession<P,D> implements PollingFrameSource.ProducerFactory, PollingFrameSource.Producer {
    private final ProjectionLifecycle.Ports<P,D,Surface> ports;
    private final SyntheticAdmission admission;
    private final int width, height, dpi;
    private OnceBackend.Owner owner;
    private Thread thread;
    private ProjectionLifecycle<P,D,Surface> core;
    private ProjectionLifecycle.TargetKey key;
    private boolean opened, produced, closed;
    private ProjectionSourceSession(ProjectionLifecycle.Ports<P,D,Surface> ports, SyntheticAdmission admission, int width, int height, int dpi) {
        if ((Object) ports instanceof AndroidProjectionPorts) throw new SecurityException("Android ports cannot enter synthetic fixture");
        this.ports=Objects.requireNonNull(ports);this.admission=Objects.requireNonNull(admission);
        this.width=width;this.height=height;this.dpi=dpi;
    }
    static <P,D> ProjectionSourceSession<P,D> syntheticFixture(ProjectionLifecycle.Ports<P,D,Surface> ports,
            SyntheticAdmission admission, int width, int height, int dpi) {
        return new ProjectionSourceSession<>(ports, admission, width, height, dpi);
    }
    @Override public void validateOwner(OnceBackend.Owner expected) {
        if (owner != null || !admission.bindSource(expected,this)) throw new SecurityException("Consumed synthetic owner required");
        owner=expected;thread=Thread.currentThread();
    }
    private void require(OnceBackend.Owner expected) {
        if (expected==null || expected!=owner || thread!=Thread.currentThread()) throw new SecurityException("Wrong owner/thread");
    }
    @Override public BackendPorts.RevocationFence revocationFence(OnceBackend.Owner expected) {
        require(expected); return core==null ? null : core.fence();
    }
    @Override public PollingFrameSource.Producer open(Surface surface,BackendPorts.StopSignal stop) {
        throw new SecurityException("Owner-qualified open required");
    }
    @Override public PollingFrameSource.Producer open(OnceBackend.Owner expected,Surface surface,BackendPorts.StopSignal stop) {
        require(expected); if(opened || closed)throw new IllegalStateException(); opened=true;
        // Only local construction here: no external call can escape before outer adoption.
        core=new ProjectionLifecycle<>(owner,ports,surface,width,height,dpi,true,ProjectionLifecycle.Source.SYNTHETIC_OS_FACADE);
        key=core.targetKey(owner,surface); return this;
    }
    @Override public void produce(BackendPorts.StopSignal stop) throws Exception {
        require(owner);if(!opened || produced || closed)throw new IllegalStateException();produced=true;
        if(stop.requested())core.requestStop();core.start(owner);
        if(stop.requested())throw new BackendPorts.Cancelled();
    }
    @Override public boolean ready() { require(owner);return core.geometryConfirmed(); }
    @Override public boolean fullyClosed() { require(owner); return core.evidence(owner).appReferencesDropped(); }
    @Override public void close() throws IOException {
        require(owner);if(closed)throw new IllegalStateException();closed=true;
        core.close(owner);
        // A normal close alone never licenses reader teardown. Check exact private
        // lifecycle receipt, session target key, owner and synthetic source domain.
        if(!core.permitsReaderRelease(core.readerReleaseReceipt(owner),owner,key,
                ProjectionLifecycle.Source.SYNTHETIC_OS_FACADE))throw new IOException("No matching target-detach receipt");
        // Polling owns the current Image/planes. Only after produce/copy returned,
        // and this receipt check returned, does it close Image then ImageReader.
    }
}
