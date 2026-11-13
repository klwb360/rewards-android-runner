package com.aliothmoon.maafw.once;

import android.view.Surface;

/** The admitted producer is connected through the existing real session handover.
 * Synthetic Provider.open remains rejecting: a Surface or synthetic lease can
 * never substitute for the single-use admitted projection capability. */
final class DormantMediaProjectionProvider implements ScreenProducerProvider.Provider {
    static OnceBackend bindAdmitted(CaptureAdmissionCoordinator<?> admission, BackendPorts.Recognizer recognizer,
            java.util.concurrent.Executor executor, android.os.Handler callbacks,int width,int height,int dpi) {
        CaptureReleaseGate.requireConfigured();
        return java.util.Objects.requireNonNull(admission).admittedBackend(recognizer,executor,callbacks,width,height,dpi);
    }
    @Override public ScreenProducerProvider.Origin origin() { return ScreenProducerProvider.Origin.REAL_SCREEN; }
    @Override public int width() { return 1024; }
    @Override public int height() { return 512; }
    @Override public ControlledSourceSession.Driver open(ControlledSourceSession.Lease lease, Surface target,
            BackendPorts.StopSignal stop) {
        throw new SecurityException("Synthetic lease cannot authorize a real producer; admitted handover required");
    }
}
