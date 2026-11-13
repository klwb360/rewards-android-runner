package com.aliothmoon.maafw.once;

/** Typed product boundary, deliberately not a permission/consent object. UI booleans
 * are presentation only; neither archive nor synthetic admission can bind a real source. */
final class ProductCapabilities {
    enum Source { SYNTHETIC_GENERATED, REAL_SCREEN_UNAVAILABLE }
    enum Permission { SYNTHETIC_ONLY_REAL_DISABLED }
    enum RealBinding { CONNECTED_DEFAULT_OFF }
    enum MissingRealRequirement { REVIEWED_RELEASE_CONFIGURATION, FRESH_SCOPED_AUTHORIZATION, DEVICE_DURABLE_CLAIM_VALIDATION, REAL_OS_AND_NATIVE_LIVE_VALIDATION }
    record RealCapability(RealBinding binding, java.util.List<MissingRealRequirement> missing) { }
    static RealCapability realSource() {
        return new RealCapability(RealBinding.CONNECTED_DEFAULT_OFF, java.util.List.of(MissingRealRequirement.values()));
    }
    enum Recovery { WAIT_FOR_OWNER, ARCHIVE_SYNTHETIC_UNPROVEN, EXPLICIT_NEW_SYNTHETIC, BLOCKED }
    record Snapshot(Source source, Permission permission, Recovery next, boolean realEnabled) { }
    static Snapshot current(boolean active, boolean canArchive, boolean canStart) {
        return new Snapshot(Source.SYNTHETIC_GENERATED, Permission.SYNTHETIC_ONLY_REAL_DISABLED,
                active ? Recovery.WAIT_FOR_OWNER : canArchive ? Recovery.ARCHIVE_SYNTHETIC_UNPROVEN
                : canStart ? Recovery.EXPLICIT_NEW_SYNTHETIC : Recovery.BLOCKED, false);
    }
    private ProductCapabilities() { }
}
