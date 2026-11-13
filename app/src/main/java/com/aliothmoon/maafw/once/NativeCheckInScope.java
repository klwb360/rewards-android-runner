package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** A new, visible two-observation/one-action scope. Never touches old capture slots.
 * The capture claims remain read-only; this separate journal budgets the one tap.
 * A failed/partial/existing burn is terminal. No recovery or reset API exists. */
final class NativeCheckInScope {
    static final long STARTS_UTC = 1794409800000L; // 2026-11-11 15:10 UTC, new explicit grant
    static final long EXPIRES_UTC = 1794412800000L; // 2026-11-11 16:00 UTC
    static final long CAPTURE_TTL = 90_000L;
    static final String DIRECTORY = "rewards-native-checkin-20261111-v15";
    static final long SESSION_TTL = 180_000L;
    static final long MAX_FRAME_AGE = 15_000L;
    enum Phase { BEFORE, AFTER }
    static boolean current(long utc) { return utc >= STARTS_UTC && utc < EXPIRES_UTC; }
    static boolean admissionCurrent(long utc) {
        return current(utc) && utc < EXPIRES_UTC - CAPTURE_TTL;
    }
    static void requireAdmissionCurrent() {
        if(!admissionCurrent(System.currentTimeMillis()))throw new SecurityException("Native capture admission expired");
    }
    /** Admission needs a full 90-second margin. This is a new grant, never a renewal. */
    static long captureDeadline(long elapsed,long utc) {
        if(!admissionCurrent(utc)||elapsed<0||elapsed>Long.MAX_VALUE-CAPTURE_TTL)
            throw new SecurityException("Native capture deadline unavailable");
        return elapsed+CAPTURE_TTL;
    }
    static boolean fresh(long capturedLowerBound, long now, long expires) {
        return capturedLowerBound >= 0 && now >= capturedLowerBound
            && now - capturedLowerBound <= MAX_FRAME_AGE && now < expires;
    }
    static void burn(Path privateFiles, String slot, String metadata) throws IOException {
        if (!current(System.currentTimeMillis()) || !slot.matches("BEFORE|AFTER|TAP"))
            throw new SecurityException("Native scope expired or invalid");
        Path dir = privateFiles.resolve(DIRECTORY);
        Files.createDirectories(dir);
        try (FileChannel file = FileChannel.open(dir.resolve(slot + ".burned"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer data = StandardCharsets.UTF_8.encode(
                "native-checkin-v15 slot=" + slot + " expiresUtc=" + EXPIRES_UTC
                + " maxFrames=2 maxAccountActions=1\n" + metadata);
            while (data.hasRemaining()) file.write(data);
            file.force(true);
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) { channel.force(true); }
        try (FileChannel channel = FileChannel.open(privateFiles, StandardOpenOption.READ)) { channel.force(true); }
    }
    static RealCaptureClaim.BurnStore captureStore(Path privateFiles, Phase phase) {
        if (phase == null) throw new IllegalArgumentException();
        return (id, metadata) -> burn(privateFiles, phase.name(), metadata);
    }
    private NativeCheckInScope() {}
}
