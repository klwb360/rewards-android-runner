package com.aliothmoon.maafw.readingtrust;

import io.github.klwb360.rewards.core.RewardsCore.Limits;
import io.github.klwb360.rewards.core.RewardsCore.State;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import static com.aliothmoon.maafw.readingtrust.OfflineReadingV3Envelope.*;

/** OFFLINE STORAGE EXPERIMENT ONLY. Restricted to NEW synthetic test temp directories.
 * No initialization, migration, recovery, issuer or action API. STORED is never action authority.
 * Clock/allowed/sync are synthetic test ports, not trusted production evidence. */
final class OfflineReadingV3Store {
    static final int MAX_IMAGE = 300000;
    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();
    enum Outcome { HOLD, STORED, STORAGE_UNCERTAIN }
    enum Stage {
        MARKER_PARTIAL, MARKER_FORCED, MARKER_DIRECTORY_SYNCED,
        NEXT_PARTIAL, NEXT_FORCED, NEXT_DIRECTORY_SYNCED,
        BEFORE_REPLACE, AFTER_REPLACE, REPLACEMENT_DIRECTORY_SYNCED,
        AFTER_MARKER_DELETE, FINAL_DIRECTORY_SYNCED, BEFORE_FINAL_READ
    }
    interface Fault { void after(Stage stage) throws IOException; }
    private final Path dir, data, marker, next, backup, lock;
    private final NormalReadingLedger.Clock clock;
    private final NormalReadingLedger.DirectorySync sync;
    private final Limits limits;
    private final BooleanSupplier allowed;
    private final Fault fault;

    OfflineReadingV3Store(Path directory, NormalReadingLedger.Clock clock,
            NormalReadingLedger.DirectorySync sync, Limits limits,
            BooleanSupplier allowed, Fault fault) throws IOException {
        Path requested = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(requested))
            throw new IOException("Existing synthetic directory required");
        dir = requested.toRealPath();
        Path temp = Paths.get(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!dir.startsWith(temp) || !dir.getFileName().toString().startsWith("SYNTHETIC-reading-v3-"))
            throw new IOException("Offline experiment restricted to synthetic temp directories");
        this.clock = Objects.requireNonNull(clock); this.sync = Objects.requireNonNull(sync);
        this.limits = Objects.requireNonNull(limits); this.allowed = Objects.requireNonNull(allowed);
        this.fault = Objects.requireNonNull(fault);
        data = dir.resolve("synthetic-reading-v3.bin"); marker = dir.resolve("synthetic-reading-v3.txn");
        next = dir.resolve("synthetic-reading-v3.new"); backup = dir.resolve("synthetic-reading-v3.bak");
        lock = dir.resolve("synthetic-reading-v3.lock");
    }

    Envelope read() throws IOException {
        if (!HELD.add(dir)) throw new IOException("Store busy");
        try (FileChannel c = openLock(); FileLock held = c.tryLock()) {
            if (held == null) throw new IOException("Store busy");
            long boot = clock.boot(); Envelope value = readUnlocked(boot);
            if (clock.boot() != boot) throw new IOException("Boot changed during read");
            return value;
        } catch (RuntimeException failure) { throw new IOException("Read unavailable", failure); }
        finally { HELD.remove(dir); }
    }

    Outcome compareAndReplace(Envelope expected, Envelope replacement) {
        if (expected == null || replacement == null || !HELD.add(dir)) return Outcome.HOLD;
        boolean writing = false;
        try (FileChannel c = openLock(); FileLock held = c.tryLock()) {
            if (held == null) return Outcome.HOLD;
            validateTransition(expected, replacement, limits);
            long initial = clock.elapsed();
            if (!current(expected, replacement, initial, initial)) return Outcome.HOLD;
            if (!expected.equals(readUnlocked(expected.boot()))) return Outcome.HOLD;
            byte[] oldBytes = encode(expected, limits), newBytes = encode(replacement, limits);
            long beforeWrite = clock.elapsed();
            if (!current(expected, replacement, initial, beforeWrite)) return Outcome.HOLD;
            writing = true;
            create(marker, transactionMarker(oldBytes, newBytes), Stage.MARKER_PARTIAL, Stage.MARKER_FORCED);
            sync.sync(dir); fault.after(Stage.MARKER_DIRECTORY_SYNCED);
            create(next, newBytes, Stage.NEXT_PARTIAL, Stage.NEXT_FORCED);
            sync.sync(dir); fault.after(Stage.NEXT_DIRECTORY_SYNCED);
            long beforeReplace = clock.elapsed();
            if (!current(expected, replacement, beforeWrite, beforeReplace)) return Outcome.STORAGE_UNCERTAIN;
            fault.after(Stage.BEFORE_REPLACE);
            Files.move(next, data, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fault.after(Stage.AFTER_REPLACE);
            sync.sync(dir); fault.after(Stage.REPLACEMENT_DIRECTORY_SYNCED);
            Files.delete(marker); fault.after(Stage.AFTER_MARKER_DELETE);
            sync.sync(dir); fault.after(Stage.FINAL_DIRECTORY_SYNCED);
            fault.after(Stage.BEFORE_FINAL_READ);
            Envelope observed = readUnlocked(expected.boot());
            long finalAt = clock.elapsed();
            if (!replacement.equals(observed) || !current(expected, replacement, beforeReplace, finalAt))
                return Outcome.STORAGE_UNCERTAIN;
            return Outcome.STORED;
        } catch (IOException | RuntimeException failure) {
            return writing ? Outcome.STORAGE_UNCERTAIN : Outcome.HOLD;
        } finally { HELD.remove(dir); }
    }

    // Four monotonic checkpoints: initial, before marker creation, before replacement, final readback.
    private boolean current(Envelope expected, Envelope replacement, long previous, long now) {
        if (now < 0 || now < previous || clock.boot() != expected.boot()
                || replacement.boot() != expected.boot() || !allowed.getAsBoolean()) return false;
        State pending = replacement.phase() == Phase.PENDING ? replacement.reserved().state()
                                                             : expected.reserved().state();
        if (!pending.day().equals(Instant.ofEpochMilli(clock.epoch()).atZone(ZoneId.of("Asia/Shanghai"))
                .toLocalDate().toString()) || now < pending.runStartedAt()
                || now - pending.runStartedAt() >= limits.maxRunMillis()
                || pending.readingStartedAt() <= 0 || now < pending.readingStartedAt()
                || now - pending.readingStartedAt() >= limits.maxReadingMillis()) return false;
        if (replacement.phase() == Phase.PENDING)
            return now >= replacement.reservedAt() && now - replacement.reservedAt() <= 1500;
        var r = replacement.settled().receipt();
        return r != null && now >= r.committedAt() && now >= r.secondAt() && now - r.secondAt() <= 1500;
    }
    private FileChannel openLock() throws IOException {
        FileChannel c = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) { c.close(); throw new IOException("Invalid lock"); }
        return c;
    }
    private Envelope readUnlocked(long boot) throws IOException {
        for (Path p : List.of(marker, next, backup)) absent(p);
        for (String base : List.of("normal-reading-v2", "reading-ledger", "trial-state", "reading-journal"))
            for (String suffix : List.of(".bin", ".txn", ".intent", ".new", ".bak")) absent(dir.resolve(base + suffix));
        if (!Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(data))
            throw new IOException("Existing synthetic v3 image required");
        try (FileChannel c = FileChannel.open(data, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = c.size(); if (size <= 32 || size > MAX_IMAGE) throw new IOException("Image size");
            ByteBuffer b = ByteBuffer.allocate((int)size);
            while (b.hasRemaining()) if (c.read(b) <= 0) throw new IOException("Short image");
            if (c.size() != size) throw new IOException("Image changed");
            return decode(b.array(), boot, limits);
        }
    }
    private static void absent(Path p) throws IOException {
        if (!Files.notExists(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unresolved transaction or legacy image");
    }
    private void create(Path p, byte[] bytes, Stage partial, Stage forced) throws IOException {
        try (FileChannel c = FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            int split = bytes.length / 2;
            write(c, ByteBuffer.wrap(bytes, 0, split)); fault.after(partial);
            write(c, ByteBuffer.wrap(bytes, split, bytes.length - split)); c.force(true); fault.after(forced);
        }
    }
    private static void write(FileChannel c, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) if (c.write(b) <= 0) throw new IOException("Stalled write");
    }
    static byte[] transactionMarker(byte[] oldImage, byte[] nextImage) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeInt(0x4e525433); out.writeInt(3); out.write(sha(oldImage)); out.write(sha(nextImage));
        }
        byte[] body = b.toByteArray(); b.write(sha(body)); return b.toByteArray();
    }
    private static byte[] sha(byte[] b) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
}
