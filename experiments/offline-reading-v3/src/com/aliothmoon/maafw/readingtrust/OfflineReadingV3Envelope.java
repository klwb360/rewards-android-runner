package com.aliothmoon.maafw.readingtrust;

import io.github.klwb360.rewards.core.RewardsCore.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import static com.aliothmoon.maafw.readingtrust.NormalReadingLedger.*;

/** Offline data format only. Stored observations and timestamps are inert data, not
 * evidence, grants or permission to act. No migration, recovery, issuer or live integration.
 * The checksum detects corruption; it does not authenticate the origin of an image. */
final class OfflineReadingV3Envelope {
    private static final int MAGIC = 0x4e524533, VERSION = 3;
    private static final int MAX_BYTES = 300000, MAX_VIEW_BYTES = 131072, DIGEST_BYTES = 32;
    private OfflineReadingV3Envelope() {}

    enum Phase { SETTLED, PENDING }
    record Envelope(long boot, Phase phase, View settled, View reserved,
                    long reservedAt, long reservedEpoch) {
        @Override public String toString() { return "OfflineReadingV3Envelope[redacted]"; }
    }

    static Envelope settled(long boot, View current) {
        return new Envelope(boot, Phase.SETTLED, current, null, 0, 0);
    }

    static Envelope pending(long boot, ReadingReceiptLifecycleModel.Pending p, long epoch) {
        if (p == null) throw new IllegalArgumentException("Missing reservation");
        return new Envelope(boot, Phase.PENDING, p.settled(), p.reserved(), p.reservedAt(), epoch);
    }

    static void validate(Envelope e, Limits limits) throws IOException {
        try {
            if (e == null || limits == null || e.boot() < 0 || e.phase() == null)
                throw new IOException("Missing envelope context");
            validateView(e.settled(), e.boot());
            View old = e.settled(); State s = old.state();
            if (!s.pendingTask().isEmpty() || !old.history().pendingState().isEmpty()
                    || !old.history().pendingArticle().isEmpty()
                    || s.readingAttempts() > limits.maxReadingArticles()
                    || old.generation() != 2L * s.readingAttempts())
                throw new IOException("Invalid settled lifecycle");
            if (s.readingAttempts() == 0) {
                if (old.generation() != 0 || old.receipt() != null || s.readingStartedAt() != 0)
                    throw new IOException("Unexplained seed");
            } else if (old.receipt() == null || s.readingStartedAt() <= 0
                    || s.readingStartedAt() < s.runStartedAt()
                    || old.receipt().receivedTrue() < s.readingStartedAt()) {
                throw new IOException("Unreachable settled timeline");
            }
            if (old.receipt() != null) {
                Receipt r = old.receipt();
                if (r.noCreditBefore() > Math.min(3, r.attempt() - 1)
                        || r.committedAt() < s.runStartedAt()
                        || r.committedAt() - s.runStartedAt() >= limits.maxRunMillis()
                        || r.committedAt() < s.readingStartedAt()
                        || r.committedAt() - s.readingStartedAt() >= limits.maxReadingMillis()
                        || r.committedAt() < r.secondAt() || r.committedAt() - r.secondAt() > 1500)
                    throw new IOException("Unreachable settled receipt bounds");
            }
            if (e.phase() == Phase.SETTLED) {
                if (e.reserved() != null || e.reservedAt() != 0 || e.reservedEpoch() != 0)
                    throw new IOException("Settled envelope contains pending data");
            } else {
                validateView(e.reserved(), e.boot());
                if (e.reserved().receipt() != null || e.reservedAt() <= 0)
                    throw new IOException("Invalid reservation data");
                reconstruct(e, limits);
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid envelope", invalid);
        }
    }

    private static void validateView(View view, long boot) throws IOException {
        NormalReadingLedger.validate(view, boot);
        // The nested decoders also enforce the complete core-state constraints.
        if (!view.equals(NormalReadingLedger.decode(NormalReadingLedger.encode(view, boot), boot)))
            throw new IOException("View does not round trip");
    }

    private static ReadingReceiptLifecycleModel.Pending reconstruct(Envelope e, Limits limits)
            throws IOException {
        State r = e.reserved().state();
        Observation synthetic = new Observation(ParseStatus.OK,
                new Counter(Task.READING, r.pendingBefore(), r.pendingTarget(), true),
                "inert stored reservation");
        var proposal = ReadingReceiptLifecycleModel.reserve(e.settled(), e.boot(), synthetic,
                e.reserved().history().pendingArticle(), e.reservedEpoch(), e.reservedAt(), false, limits);
        if (proposal.decision() != Decision.ALLOW || proposal.pending() == null
                || !proposal.pending().settled().equals(e.settled())
                || !proposal.pending().reserved().equals(e.reserved())
                || proposal.pending().reservedAt() != e.reservedAt())
            throw new IOException("Reservation is not the exact next proposal");
        return proposal.pending();
    }

    static void validateTransition(Envelope expected, Envelope replacement, Limits limits)
            throws IOException {
        validate(expected, limits);
        validate(replacement, limits);
        if (expected.boot() != replacement.boot() || expected.phase() == replacement.phase())
            throw new IOException("Only alternating same-boot transitions are supported");
        long oldGeneration = generation(expected);
        if (oldGeneration == Long.MAX_VALUE || generation(replacement) != oldGeneration + 1)
            throw new IOException("Storage generation must advance exactly once");
        if (expected.phase() == Phase.SETTLED) {
            if (!expected.settled().equals(replacement.settled()))
                throw new IOException("Reservation changed the prior settled snapshot");
        } else {
            ReadingReceiptLifecycleModel.checkSettlement(reconstruct(expected, limits),
                    replacement.settled(), expected.reservedEpoch(),
                    replacement.settled().receipt().committedAt(), false, limits);
        }
    }

    private static long generation(Envelope e) {
        return e.phase() == Phase.SETTLED ? e.settled().generation() : e.reserved().generation();
    }

    static byte[] encode(Envelope e, Limits limits) throws IOException {
        validate(e, limits);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(e.boot());
            out.writeByte(e.phase() == Phase.SETTLED ? 0 : 1);
            writeView(out, e.settled(), e.boot());
            if (e.phase() == Phase.PENDING) writeView(out, e.reserved(), e.boot());
            out.writeLong(e.reservedAt()); out.writeLong(e.reservedEpoch());
        }
        byte[] body = bytes.toByteArray();
        if (body.length > MAX_BYTES - DIGEST_BYTES) throw new IOException("Envelope size");
        byte[] result = Arrays.copyOf(body, body.length + DIGEST_BYTES);
        System.arraycopy(sha(body), 0, result, body.length, DIGEST_BYTES);
        return result;
    }

    static Envelope decode(byte[] bytes, long expectedBoot, Limits limits) throws IOException {
        if (bytes == null || bytes.length <= DIGEST_BYTES || bytes.length > MAX_BYTES
                || expectedBoot < 0) throw new IOException("Envelope bounds");
        // Snapshot caller-owned input once before interpreting it.
        byte[] image = bytes.clone();
        byte[] body = Arrays.copyOf(image, image.length - DIGEST_BYTES);
        if (!MessageDigest.isEqual(sha(body), Arrays.copyOfRange(image, body.length, image.length)))
            throw new IOException("Envelope checksum");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readLong() != expectedBoot)
                throw new IOException("Unknown schema or boot; no implicit migration");
            int code = in.readUnsignedByte();
            if (code != 0 && code != 1) throw new IOException("Unknown phase");
            Phase phase = code == 0 ? Phase.SETTLED : Phase.PENDING;
            View settled = readView(in, expectedBoot);
            View reserved = phase == Phase.PENDING ? readView(in, expectedBoot) : null;
            Envelope result = new Envelope(expectedBoot, phase, settled, reserved,
                    in.readLong(), in.readLong());
            if (in.read() != -1) throw new IOException("Trailing envelope bytes");
            validate(result, limits);
            // Also rejects alternate booleans, UTF encodings or hash ordering in nested views.
            if (!Arrays.equals(image, encode(result, limits)))
                throw new IOException("Noncanonical envelope");
            return result;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid envelope image", invalid);
        }
    }

    private static void writeView(DataOutputStream out, View view, long boot) throws IOException {
        byte[] encoded = NormalReadingLedger.encode(view, boot);
        if (encoded.length <= DIGEST_BYTES || encoded.length > MAX_VIEW_BYTES)
            throw new IOException("View bounds");
        out.writeInt(encoded.length); out.write(encoded);
    }

    private static View readView(DataInputStream in, long boot) throws IOException {
        int length = in.readInt();
        if (length <= DIGEST_BYTES || length > MAX_VIEW_BYTES || length > in.available())
            throw new IOException("View bounds");
        byte[] encoded = new byte[length]; in.readFully(encoded);
        return NormalReadingLedger.decode(encoded, boot);
    }

    private static byte[] sha(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException unavailable) { throw new IOException(unavailable); }
    }
}
