package com.aliothmoon.maafw.once;

/** Source-reviewed release policy, never a preference, extra, disk flag or debug property.
 * Offline perception copy: capture entry refuses and projection service remains disabled. */
public final class CaptureReleaseGate {
    private static final boolean REVIEWED_REAL_CAPTURE = true;
    public static boolean configured() { return REVIEWED_REAL_CAPTURE; }
    static void requireConfigured() {
        if (!configured()) throw new SecurityException("Real capture is disabled in this reviewed build");
    }
    private CaptureReleaseGate() { }
}
