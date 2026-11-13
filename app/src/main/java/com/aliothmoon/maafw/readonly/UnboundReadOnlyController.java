package com.aliothmoon.maafw.readonly;

/** Production binding for this candidate. No service/permission/native class is referenced. */
public final class UnboundReadOnlyController {
    private final com.aliothmoon.maafw.once.OnceBackend backend = new com.aliothmoon.maafw.once.OnceBackend();
    private final ReadOnlyRecognition.Admission admission;
    private final ReadOnlyRecognition reducer;
    public UnboundReadOnlyController(int sdk, String hardware, String fingerprint) {
        admission = new ReadOnlyRecognition.Admission(
            ReadOnlyRecognition.matchesLegacyDevice(sdk, hardware, fingerprint), false, false,
            ReadOnlyRecognition.Backend.UNBOUND, ReadOnlyRecognition.Authorization.NOT_AUTHORIZED);
        reducer = new ReadOnlyRecognition(admission);
    }
    public com.aliothmoon.maafw.once.OnceBackend.Result backendSnapshot() { return backend.snapshot(); }
    public ReadOnlyRecognition.Admission admission() { return admission; }
    public ReadOnlyRecognition.State snapshot() { return reducer.snapshot(); }
    /** Defense in depth for a future accidental wiring. Always fails closed, never calls a backend. */
    public ReadOnlyRecognition.State requestRecognition() { return reducer.onDurableClaimCommitted(false); }
}
