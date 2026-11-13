package com.aliothmoon.maafw.readingtrust;

import io.github.klwb360.rewards.core.RewardsCore.Limits;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import static com.aliothmoon.maafw.readingtrust.OfflineReadingV3Envelope.*;

/** OFFLINE, IN-MEMORY DIAGNOSTICS ONLY. No filesystem, recovery, retry or action API.
 * Caller-supplied images/inventory are untrusted observations, not a coherent disk snapshot.
 * A clean-looking image can follow an uncertain write. No result proves success, durability,
 * authenticity, freshness, ownership, absence of a concurrent writer, or permission to act.
 * Never connect these classifications to live dispatch, automatic repair or deletion. */
final class OfflineReadingV3Inspection {
    private static final int MAX_IMAGE = 300000, MARKER_BYTES = 104;
    private OfflineReadingV3Inspection() {}

    enum Inventory { COMPLETE_AS_REPORTED, UNKNOWN, LEGACY_PRESENT, UNEXPECTED_ENTRY }
    enum Status {
        INVALID_CONTEXT, INVENTORY_UNKNOWN, LEGACY_COLLISION, UNEXPECTED_ENTRY,
        UNREADABLE_ARTIFACT, BACKUP_PRESENT, DATA_MISSING, DATA_MALFORMED,
        CLEAN_SETTLED_IMAGE, CLEAN_PENDING_IMAGE, ORPHAN_NEXT,
        MARKER_MALFORMED, MARKER_DEGENERATE, DATA_NOT_BOUND_TO_MARKER,
        OLD_IMAGE_ONLY_WITH_MARKER, OLD_IMAGE_WITH_INVALID_NEXT,
        OLD_IMAGE_WITH_WRONG_NEXT, OLD_IMAGE_WITH_INVALID_TRANSITION,
        OLD_IMAGE_WITH_VALID_NEXT, NEW_IMAGE_WITH_MARKER, NEW_IMAGE_WITH_EXTRA_NEXT
    }

    /** null image means absent; a zero-length image is present and malformed.
     * Oversized images are recorded as unreadable without making an unbounded copy. */
    static final class Image {
        private final byte[] bytes;
        private final boolean present, readable;
        private Image(byte[] bytes, boolean present, boolean readable) {
            this.bytes = bytes; this.present = present; this.readable = readable;
        }
        static Image of(byte[] bytes) {
            if (bytes == null) return new Image(null, false, true);
            if (bytes.length > MAX_IMAGE) return unavailable();
            return new Image(bytes.clone(), true, true);
        }
        static Image unavailable() { return new Image(null, true, false); }
        @Override public String toString() { return "OfflineImage[redacted]"; }
    }

    /** No raw-byte accessor. All Image instances are immutable defensive copies. */
    record Snapshot(Inventory inventory, Image data, Image marker, Image next, Image backup) {
        @Override public String toString() { return "OfflineInspectionSnapshot[redacted]"; }
    }

    static Status inspect(Snapshot snapshot, long expectedBoot, Limits limits) {
        if (snapshot == null || snapshot.inventory() == null || limits == null || expectedBoot < 0
                || snapshot.data() == null || snapshot.marker() == null
                || snapshot.next() == null || snapshot.backup() == null) return Status.INVALID_CONTEXT;
        switch (snapshot.inventory()) {
            case UNKNOWN: return Status.INVENTORY_UNKNOWN;
            case LEGACY_PRESENT: return Status.LEGACY_COLLISION;
            case UNEXPECTED_ENTRY: return Status.UNEXPECTED_ENTRY;
            case COMPLETE_AS_REPORTED: break;
        }
        Image data = snapshot.data(), marker = snapshot.marker(), next = snapshot.next();
        for (Image image : new Image[]{data, marker, next, snapshot.backup()})
            if (!image.readable) return Status.UNREADABLE_ARTIFACT;
        if (snapshot.backup().present) return Status.BACKUP_PRESENT;
        if (!data.present) return Status.DATA_MISSING;
        Envelope current;
        try { current = decode(data.bytes, expectedBoot, limits); }
        catch (IOException | RuntimeException invalid) { return Status.DATA_MALFORMED; }
        if (!marker.present) {
            if (next.present) return Status.ORPHAN_NEXT;
            return current.phase() == Phase.SETTLED ? Status.CLEAN_SETTLED_IMAGE : Status.CLEAN_PENDING_IMAGE;
        }
        final byte[][] hashes;
        try { hashes = parseMarker(marker.bytes); }
        catch (IOException | RuntimeException invalid) { return Status.MARKER_MALFORMED; }
        if (MessageDigest.isEqual(hashes[0], hashes[1])) return Status.MARKER_DEGENERATE;
        try {
            byte[] currentHash = sha(data.bytes);
            if (MessageDigest.isEqual(currentHash, hashes[1])) {
                // Old bytes are not retained: no old-to-new transition can be checked here.
                return next.present ? Status.NEW_IMAGE_WITH_EXTRA_NEXT : Status.NEW_IMAGE_WITH_MARKER;
            }
            if (!MessageDigest.isEqual(currentHash, hashes[0])) return Status.DATA_NOT_BOUND_TO_MARKER;
            if (!next.present) return Status.OLD_IMAGE_ONLY_WITH_MARKER;
            Envelope proposed;
            try { proposed = decode(next.bytes, expectedBoot, limits); }
            catch (IOException | RuntimeException invalid) { return Status.OLD_IMAGE_WITH_INVALID_NEXT; }
            if (!MessageDigest.isEqual(sha(next.bytes), hashes[1])) return Status.OLD_IMAGE_WITH_WRONG_NEXT;
            try { validateTransition(current, proposed, limits); }
            catch (IOException | RuntimeException invalid) { return Status.OLD_IMAGE_WITH_INVALID_TRANSITION; }
            return Status.OLD_IMAGE_WITH_VALID_NEXT;
        } catch (IOException unavailable) { return Status.UNREADABLE_ARTIFACT; }
    }

    private static byte[][] parseMarker(byte[] bytes) throws IOException {
        if (bytes.length != MARKER_BYTES) throw new IOException("Marker size");
        byte[] body = Arrays.copyOf(bytes, MARKER_BYTES - 32);
        if (!MessageDigest.isEqual(sha(body), Arrays.copyOfRange(bytes, body.length, bytes.length)))
            throw new IOException("Marker checksum");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != 0x4e525433 || in.readInt() != 3) throw new IOException("Marker schema");
            byte[] oldHash = new byte[32], newHash = new byte[32];
            in.readFully(oldHash); in.readFully(newHash);
            if (in.read() != -1) throw new IOException("Marker trailing bytes");
            return new byte[][]{oldHash, newHash};
        }
    }

    private static byte[] sha(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException unavailable) { throw new IOException(unavailable); }
    }
}
