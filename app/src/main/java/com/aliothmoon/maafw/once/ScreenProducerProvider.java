package com.aliothmoon.maafw.once;

import android.view.Surface;

/** Source SPI layered on the existing owner-local PollingFrameSource factory.
 * open/produce/close run on its single owner worker. A late returned producer is
 * adopted before stop is checked. close must join children and drop target refs;
 * throwing or blocking means unknown cleanup, never authorization to release.
 * Origin is provenance only, NOT evidence of trusted real-screen consent. */
final class ScreenProducerProvider {
    enum Origin { SYNTHETIC, REAL_SCREEN }
    interface Provider extends ControlledSourceSession.Provider {
        Origin origin();
        int width();
        int height();
    }
    private ScreenProducerProvider() { }
    static PollingFrameSource bindSynthetic(Provider provider, SyntheticAdmission admission) {
        if (!(provider instanceof SyntheticRewardsProvider) || provider.origin() != Origin.SYNTHETIC
                || admission == null || !admission.isValidReservation())
            throw new SecurityException("Synthetic reservation required");
        return new PollingFrameSource(provider.width(), provider.height(), new ControlledSourceSession(provider, admission));
    }
    /** Deliberately no accepting overload for fake receipts, UI flags, or saved Intents. */
    static PollingFrameSource bindReal(Provider provider, SyntheticAdmission rejected) {
        throw new SecurityException("Fresh real consent and reviewed producer unavailable");
    }
    static OnceBackend bindReal(CaptureAdmissionCoordinator<?> admission, BackendPorts.Recognizer recognizer,
            java.util.concurrent.Executor executor, android.os.Handler callbacks, int width,int height,int dpi) {
        CaptureReleaseGate.requireConfigured();
        return DormantMediaProjectionProvider.bindAdmitted(admission,recognizer,executor,callbacks,width,height,dpi);
    }
    static final class UnavailableRealProvider implements Provider {
        public Origin origin() { return Origin.REAL_SCREEN; }
        public int width() { return 1024; }
        public int height() { return 512; }
        public ControlledSourceSession.Driver open(ControlledSourceSession.Lease lease, Surface target, BackendPorts.StopSignal stop) {
            throw new SecurityException("Real screen disabled");
        }
    }
}
