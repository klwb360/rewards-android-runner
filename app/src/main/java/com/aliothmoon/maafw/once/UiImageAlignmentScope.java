package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Fixed, independent one-attempt storage and quotas. Neither one grants live capture authority. */
final class UiImageAlignmentScope {
    static final String DIRECTORY = "rewards-ui-image-alignment-v1";
    static final String DIAGNOSTIC_DIRECTORY = "rewards-ui-image-alignment-diagnostic-v2";
    enum Calibration { FIRST, CONFIRMATION }
    static final String CALIBRATION_DIRECTORY = "rewards-ui-image-alignment-calibration-v3";
    static final String CONFIRMATION_DIRECTORY = "rewards-ui-image-alignment-calibration-confirm-v3";
    private enum Profile {
        ORIGINAL(DIRECTORY, "ui-image-alignment-v1"),
        DIAGNOSTIC(DIAGNOSTIC_DIRECTORY, "ui-image-alignment-diagnostic-v2"),
        CALIBRATION(CALIBRATION_DIRECTORY, "ui-image-alignment-calibration-v3"),
        CONFIRMATION(CONFIRMATION_DIRECTORY, "ui-image-alignment-calibration-confirm-v3");
        final String directory, marker;
        Profile(String directory, String marker) { this.directory = directory; this.marker = marker; }
    }
    static final long SESSION_TTL = 180_000L, CAPTURE_TTL = 90_000L;
    static final int MAX_UI_READS = 1, MAX_FRAMES = 1, MAX_OCR_PER_FRAME = 1;
    // Fixed read-only source metadata is separate from navigation or input.
    static final int MAX_NAVIGATION = 0, MAX_INPUT = 0;

    static long sessionDeadline(long now) {
        if (now < 0 || now > Long.MAX_VALUE - SESSION_TTL) {
            throw new SecurityException("Alignment session time unavailable");
        }
        return now + SESSION_TTL;
    }

    static long captureDeadline(long now, long sessionEnd) {
        if (now < 0 || now > Long.MAX_VALUE - CAPTURE_TTL || sessionEnd <= now) {
            throw new SecurityException("Alignment capture time unavailable");
        }
        return Math.min(now + CAPTURE_TTL, sessionEnd);
    }

    static void burnRun(Path files) throws IOException { burn(files, Slot.RUN, Profile.ORIGINAL); }
    static void burnUi(Path files) throws IOException { burn(files, Slot.UI, Profile.ORIGINAL); }
    static void burnDiagnosticRun(Path files) throws IOException { burn(files, Slot.RUN, Profile.DIAGNOSTIC); }
    static void burnDiagnosticUi(Path files) throws IOException { burn(files, Slot.UI, Profile.DIAGNOSTIC); }
    private static Profile calibrationProfile(Calibration attempt) {
        return switch (java.util.Objects.requireNonNull(attempt)) {
            case FIRST -> Profile.CALIBRATION;
            case CONFIRMATION -> Profile.CONFIRMATION;
        };
    }
    static void burnCalibrationRun(Path files, Calibration attempt) throws IOException { burn(files, Slot.RUN, calibrationProfile(attempt)); }
    static void burnCalibrationUi(Path files, Calibration attempt) throws IOException { burn(files, Slot.UI, calibrationProfile(attempt)); }
    static RealCaptureClaim.BurnStore calibrationFrameStore(Path files, Calibration attempt) {
        return frameStore(files, calibrationProfile(attempt));
    }

    /** The existing real grant consumes this store; fixture grants are never created here. */
    static RealCaptureClaim.BurnStore frameStore(Path files) {
        return frameStore(files, Profile.ORIGINAL);
    }
    static RealCaptureClaim.BurnStore diagnosticFrameStore(Path files) {
        return frameStore(files, Profile.DIAGNOSTIC);
    }
    private static RealCaptureClaim.BurnStore frameStore(Path files, Profile profile) {
        requireRootPath(files);
        return new RealCaptureClaim.BurnStore() {
            private boolean attempted;
            @Override public synchronized void burn(String id, String metadata) throws IOException {
                if (attempted) throw new SecurityException("Alignment frame attempt already spent");
                attempted = true;
                if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                        || metadata == null || metadata.length() > 2048) {
                    throw new SecurityException("Exact alignment frame claim required");
                }
                // The grant's UUID, owner/epoch metadata and arbitrary caller text are not persisted.
                UiImageAlignmentScope.burn(files, Slot.FRAME, profile);
            }
        };
    }

    private enum Slot { RUN, UI, FRAME }

    private static Path requireRootPath(Path files) {
        if (files == null || !files.isAbsolute() || !files.equals(files.normalize())) {
            throw new SecurityException("Exact private files directory required");
        }
        return files;
    }

    private static Object directoryIdentity(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.fileKey() == null) {
            throw new IOException("Alignment directory identity unconfirmed");
        }
        return attributes.fileKey();
    }

    private static void sameDirectories(Path files, Object rootIdentity, Path directory,
            Object scopeIdentity) throws IOException {
        if (!rootIdentity.equals(directoryIdentity(files))
                || !scopeIdentity.equals(directoryIdentity(directory))) {
            throw new IOException("Alignment directory changed");
        }
    }

    /** A partial/existing marker or unconfirmed force is retained. No recovery or deletion API. */
    private static void burn(Path files, Slot slot, Profile profile) throws IOException {
        Path root = requireRootPath(files);
        Object rootIdentity = directoryIdentity(root);
        Path directory = root.resolve(profile.directory);
        try { Files.createDirectory(directory); }
        catch (FileAlreadyExistsException existing) { /* The exact directory is checked below. */ }
        Object scopeIdentity = directoryIdentity(directory);
        sameDirectories(root, rootIdentity, directory, scopeIdentity);
        Path marker = directory.resolve(slot.name() + ".burned");
        try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(profile.marker + " slot=" + slot.name()
                    + " maxUiReads=1 maxFrames=1 maxOcrPerFrame=1 maxNavigation=0 maxInput=0\n");
            while (bytes.hasRemaining()) {
                if (channel.write(bytes) <= 0) throw new IOException("Alignment marker write unconfirmed");
            }
            channel.force(true);
        }
        sameDirectories(root, rootIdentity, directory, scopeIdentity);
        forceDirectory(directory);
        forceDirectory(root);
        sameDirectories(root, rootIdentity, directory, scopeIdentity);
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    /** Reservations only. The runtime must separately observe each durable burn and cleanup. */
    static final class Budget {
        private int next, attempted;
        private boolean rejected;

        synchronized boolean claimRun() { return claim(0); }
        synchronized boolean claimUi() { return claim(1); }
        synchronized boolean claimFrame() { return claim(2); }
        synchronized boolean claimOcr() { return claim(3); }

        private boolean claim(int expected) {
            attempted |= 1 << expected;
            if (rejected || next != expected) {
                rejected = true;
                return false;
            }
            next++;
            return true;
        }

        synchronized String summary() {
            return "alignmentBudgetRejected=" + rejected
                    + " runAttempted=" + ((attempted & 1) != 0) + " runReserved=" + (next >= 1)
                    + " uiAttempted=" + ((attempted & 2) != 0) + " uiReserved=" + (next >= 2)
                    + " frameAttempted=" + ((attempted & 4) != 0) + " frameReserved=" + (next >= 3)
                    + " ocrAttempted=" + ((attempted & 8) != 0) + " ocrReserved=" + (next >= 4);
        }
    }

    private UiImageAlignmentScope() { }
}
