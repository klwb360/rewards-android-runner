package com.aliothmoon.maafw.once;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;
import java.util.Objects;

/** Actual Android API calls, DORMANT / NOT LIVE VALIDATED. No caller in the
 * production UI/service/factories. This INTERNAL adapter does not obtain consent
 * or turn an existing projection into authorization. A future reviewed adapter
 * must first consume a fresh durable scoped claim, start the authorized FGS,
 * and obtain a one-use projection. Dormant acquisition/FGS code exists but no live grant route is configured.
 * Tests pass only host-shim MediaProjection objects, never a device projection. */
final class AndroidProjectionPorts implements ProjectionLifecycle.Ports<MediaProjection, VirtualDisplay, Surface> {
    private MediaProjection pending;
    private ProjectionLifecycle.Lease bound;
    private ProjectionCallback callback;
    private final Handler callbackHandler;
    private boolean taken, registered, created, released, stopped, unregistered;

    AndroidProjectionPorts(MediaProjection acquiredProjection, Handler callbackHandler) {
        if (Build.VERSION.SDK_INT < 29) throw new UnsupportedOperationException("API29+ polling source required");
        this.pending = Objects.requireNonNull(acquiredProjection);
        this.callbackHandler = Objects.requireNonNull(callbackHandler);
    }
    private void require(ProjectionLifecycle.Lease lease) {
        Objects.requireNonNull(lease).requireThread();
        if (lease.source() != ProjectionLifecycle.Source.DORMANT_MEDIA_PROJECTION) throw new SecurityException("Android ports require dormant Android domain");
        if (bound != lease) throw new SecurityException("Wrong projection lease");
    }
    @Override public MediaProjection takeProjection(ProjectionLifecycle.Lease lease) {
        Objects.requireNonNull(lease).requireThread();
        if (lease.source() != ProjectionLifecycle.Source.DORMANT_MEDIA_PROJECTION) throw new SecurityException("Synthetic lease cannot touch Android ports");
        if (taken) throw new SecurityException("Projection handover consumed");
        taken = true; bound = lease;
        MediaProjection result = pending; pending = null;
        return result;
    }
    @Override public void register(MediaProjection projection, ProjectionLifecycle.Anchor anchor, ProjectionLifecycle.Lease lease) {
        require(lease);
        if (registered) throw new IllegalStateException();
        registered = true;
        // Adopt callback BEFORE registration can return late or throw.
        callback = new ProjectionCallback(Objects.requireNonNull(anchor));
        projection.registerCallback(callback, callbackHandler);
    }
    @Override public VirtualDisplay createDisplay(MediaProjection projection, Surface target, int width, int height, int dpi, ProjectionLifecycle.Lease lease) {
        require(lease);
        if (!registered || created) throw new IllegalStateException();
        created = true; // No retry after even an exceptional/uncertain OS call.
        return projection.createVirtualDisplay("Rewards-one-shot-dormant", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, target, null, null);
    }
    @Override public void releaseDisplay(VirtualDisplay display, ProjectionLifecycle.Lease lease) {
        require(lease);
        if (released) throw new IllegalStateException();
        released = true; display.release();
    }
    @Override public void stopProjection(MediaProjection projection, ProjectionLifecycle.Lease lease) {
        require(lease);
        if (stopped) throw new IllegalStateException();
        stopped = true; projection.stop();
    }
    @Override public void unregister(MediaProjection projection, ProjectionLifecycle.Anchor anchor, ProjectionLifecycle.Lease lease) {
        require(lease);
        if (unregistered || callback == null || callback.anchor != anchor) throw new IllegalStateException();
        unregistered = true;
        projection.unregisterCallback(callback);
        callback = null; // Not callback join. Queued callback owns scalar anchor.
    }
    /** Static: no implicit enclosing adapter/resource reference. */
    static final class ProjectionCallback extends MediaProjection.Callback {
        private final ProjectionLifecycle.Anchor anchor;
        ProjectionCallback(ProjectionLifecycle.Anchor anchor) { this.anchor = anchor; }
        @Override public void onStop() { anchor.onStop(); }
        @Override public void onCapturedContentResize(int width, int height) {
            // First natural geometry establishes baseline; changed geometry cancels.
            anchor.onResize(width, height);
        }
    }
}
