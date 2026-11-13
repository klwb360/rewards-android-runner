package com.aliothmoon.maafw.once;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

/** Synthetic-only, append-only v6 metadata. No raw frames, OCR, consent or real authorization.
 * Archive preserves an interrupted reservation as UNPROVEN, never as cleaned/completed.
 * A fresh process and exclusive OS lock are both required for an explicit archive.
 */
public final class AttemptJournal implements AutoCloseable {
    public enum State { READY, RESERVED, COMPLETED, INTERRUPTED_CLEANUP_UNCONFIRMED, ARCHIVED_UNPROVEN, BLOCKED_UNCERTAIN }
    public record Snapshot(long generation, State state, long archivedCount, long lastArchivedGeneration, long confirmedCount,
            boolean canArchiveInterruptedSynthetic) { }
    interface Store extends AutoCloseable {
        byte[] read() throws IOException;
        void append(byte[] bytes) throws IOException;
        boolean exclusiveLockHeld();
        @Override void close() throws IOException;
    }
    private static final long MAGIC = 0x5257443601000000L;
    private static final long SYNTHETIC = 0x53594e5448455449L;
    private static final int SIZE = 64;
    private static final UUID PROCESS = UUID.randomUUID();
    // Keep uncertain live owners and their locks strongly reachable until process death.
    private static final Set<AttemptJournal> LIVE = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Store store;
    private final UUID incarnation;
    private State state = State.READY;
    private SyntheticAdmission admission;
    private UUID reservedBy;
    private long generation;
    private long archived;
    private long lastArchived;
    private long confirmed;
    private boolean closed;
    private boolean liveReservation;

    public AttemptJournal(File noBackupDirectory) throws IOException {
        this(new DiskStore(noBackupDirectory.toPath()), PROCESS);
    }
    AttemptJournal(Store store) throws IOException { this(store, PROCESS); }
    // Package-local deterministic process seam, never exposed by Android service/UI.
    AttemptJournal(Store store, UUID incarnation) throws IOException {
        this.store = store;
        this.incarnation = java.util.Objects.requireNonNull(incarnation);
        try {
            if (!store.exclusiveLockHeld()) throw new IOException("Exclusive synthetic journal lock required");
            byte[] data = store.read();
            if (data == null) { store.append(record(0, 0, new UUID(0, 0), new UUID(0, 0))); return; }
            state = State.BLOCKED_UNCERTAIN;
            if (data.length < SIZE || data.length % SIZE != 0) return;
            if (!Arrays.equals(Arrays.copyOfRange(data, 0, SIZE), record(0, 0, new UUID(0, 0), new UUID(0, 0)))) return;
            state = State.READY;
            for (int offset = SIZE; offset < data.length; offset += SIZE) {
                ByteBuffer b = ByteBuffer.wrap(data, offset, SIZE);
                long header = b.getLong(), next = b.getLong(), origin = b.getLong();
                UUID owner = new UUID(b.getLong(), b.getLong());
                UUID actor = new UUID(b.getLong(), b.getLong());
                long crcValue = b.getLong();
                CRC32 crc = new CRC32(); crc.update(data, offset, SIZE - 8);
                int kind = header == MAGIC + 1 ? 1 : header == MAGIC + 2 ? 2 : header == MAGIC + 3 ? 3 : -1;
                boolean idle = state == State.READY || state == State.COMPLETED || state == State.ARCHIVED_UNPROVEN;
                boolean pending = state == State.INTERRUPTED_CLEANUP_UNCONFIRMED;
                boolean valid = crcValue == crc.getValue() && origin == SYNTHETIC && nonzero(owner) && nonzero(actor);
                if (kind == 1) valid &= idle && next == generation + 1 && next <= 20000 && owner.equals(actor);
                else if (kind == 2) valid &= pending && next == generation && owner.equals(reservedBy) && actor.equals(owner);
                else if (kind == 3) valid &= pending && next == generation && owner.equals(reservedBy) && !actor.equals(owner);
                else valid = false;
                if (!valid) { state = State.BLOCKED_UNCERTAIN; return; }
                generation = next;
                reservedBy = owner;
                state = kind == 1 ? State.INTERRUPTED_CLEANUP_UNCONFIRMED : kind == 2 ? State.COMPLETED : State.ARCHIVED_UNPROVEN;
                if (kind == 3) { archived++; lastArchived = generation; }
                if (kind == 2) confirmed++;
            }
        } catch (IOException | RuntimeException failure) {
            try { store.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }
    private static boolean nonzero(UUID id) { return id.getMostSignificantBits() != 0 || id.getLeastSignificantBits() != 0; }
    private static byte[] record(int kind, long generation, UUID owner, UUID actor) {
        ByteBuffer b = ByteBuffer.allocate(SIZE);
        b.putLong(MAGIC + kind).putLong(generation).putLong(SYNTHETIC);
        b.putLong(owner.getMostSignificantBits()).putLong(owner.getLeastSignificantBits());
        b.putLong(actor.getMostSignificantBits()).putLong(actor.getLeastSignificantBits());
        CRC32 crc = new CRC32(); crc.update(b.array(), 0, SIZE - 8);
        b.putLong(crc.getValue()); return b.array();
    }
    private boolean canArchive() {
        return !closed && !liveReservation && admission == null && store.exclusiveLockHeld()
                && state == State.INTERRUPTED_CLEANUP_UNCONFIRMED && reservedBy != null && !reservedBy.equals(incarnation);
    }
    public synchronized Snapshot snapshot() { return new Snapshot(generation, state, archived, lastArchived, confirmed, canArchive()); }
    public synchronized boolean archiveInterruptedSynthetic() {
        if (!canArchive()) return false;
        state = State.BLOCKED_UNCERTAIN;
        try { store.append(record(3, generation, reservedBy, incarnation)); }
        catch (IOException | RuntimeException failure) { return false; }
        archived++; lastArchived = generation; state = State.ARCHIVED_UNPROVEN; return true;
    }
    public synchronized SyntheticAdmission reserveSynthetic() {
        if (closed || !store.exclusiveLockHeld() || generation >= 20000
                || (state != State.READY && state != State.COMPLETED && state != State.ARCHIVED_UNPROVEN)) return null;
        generation++; reservedBy = incarnation; state = State.BLOCKED_UNCERTAIN;
        liveReservation = true;
        synchronized (LIVE) { LIVE.add(this); }
        try { store.append(record(1, generation, incarnation, incarnation)); }
        catch (IOException | RuntimeException failure) { return null; }
        state = State.RESERVED;
        admission = new SyntheticAdmission(this, generation); return admission;
    }
    synchronized boolean valid(SyntheticAdmission candidate) {
        return !closed && store.exclusiveLockHeld() && state == State.RESERVED && candidate != null
                && candidate == admission && candidate.journal == this && candidate.generation == generation;
    }
    public boolean completeSynthetic(SyntheticAdmission candidate, OnceBackend.Result result) {
        boolean ownerMatches = candidate != null && result != null && candidate.owns(result.owner());
        synchronized (this) {
            if (!valid(candidate) || !ownerMatches) return false;
            boolean noSource = result.cleanup() == OnceBackend.Cleanup.NOT_NEEDED
                    && result.quiescence() == OnceBackend.Quiescence.NOT_INVOKED
                    && result.memory() == OnceBackend.Memory.NOT_ALLOCATED;
            boolean cleaned = result.cleanup() == OnceBackend.Cleanup.CONFIRMED
                    && result.quiescence() == OnceBackend.Quiescence.CONFIRMED
                    && (result.memory() == OnceBackend.Memory.WIPED || result.memory() == OnceBackend.Memory.NOT_ALLOCATED);
            if (!result.terminal() || !(noSource || cleaned)) { state = State.BLOCKED_UNCERTAIN; return false; }
            state = State.BLOCKED_UNCERTAIN;
            try { store.append(record(2, generation, incarnation, incarnation)); }
            catch (IOException | RuntimeException failure) { return false; }
            liveReservation = false;
            synchronized (LIVE) { LIVE.remove(this); }
            confirmed++; state = State.COMPLETED; return true;
        }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        if (liveReservation) throw new IOException("Synthetic owner unproven; journal lock retained until process death");
        closed = true; store.close();
    }
    private static final class DiskStore implements Store {
        // POSIX process locks can be lost when another descriptor for the same inode closes.
        // Guard before opening any journal descriptor, not merely before tryLock().
        private static final Set<Path> OPEN_PATHS = new java.util.HashSet<>();
        private final FileChannel channel;
        private final FileLock lock;
        private final boolean fresh;
        private final Path directory;
        DiskStore(Path base) throws IOException {
            directory = base.toRealPath().resolve("rewards_synthetic_v6");
            synchronized (OPEN_PATHS) {
                if (!OPEN_PATHS.add(directory)) throw new IOException("Synthetic journal already open in this process");
            }
            try {
            Files.createDirectories(directory); forceDirectory(base);
            Path file = directory.resolve("attempts.journal");
            FileChannel opened; boolean created;
            try { opened = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE); created = true; }
            catch (java.nio.file.FileAlreadyExistsException exists) {
                opened = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE); created = false;
            }
            FileLock held = null;
            try {
                held = opened.tryLock();
                if (held == null) throw new IOException("Synthetic journal busy");
                opened.force(true); forceDirectory(directory);
            } catch (IOException | RuntimeException failure) {
                if (held != null) held.release(); opened.close(); throw failure;
            }
            channel = opened; lock = held; fresh = created;
            } catch (IOException | RuntimeException failure) {
                synchronized (OPEN_PATHS) { OPEN_PATHS.remove(directory); }
                throw failure;
            }
        }
        private static void forceDirectory(Path directory) throws IOException {
            try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
        }
        public boolean exclusiveLockHeld() { return channel.isOpen() && lock.isValid(); }
        public byte[] read() throws IOException {
            if (fresh) return null;
            long size = channel.size();
            if (size > 2560064) throw new IOException("Synthetic journal too large");
            ByteBuffer bytes = ByteBuffer.allocate((int)size); channel.position(0);
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IOException("Synthetic journal truncated");
            byte[] data = bytes.array();
            // Every append has a durable intent, including archive/complete. A missing record is uncertain.
            if (size % SIZE == 0 && Files.exists(directory.resolve("append-" + (size / SIZE) + ".marker")))
                return Arrays.copyOf(data, data.length + 1);
            return data;
        }
        public void append(byte[] bytes) throws IOException {
            if (!exclusiveLockHeld() || bytes.length != SIZE || channel.size() % SIZE != 0)
                throw new IOException("Synthetic journal append boundary invalid");
            long expected = channel.size();
            Path marker = directory.resolve("append-" + (expected / SIZE) + ".marker");
            try (FileChannel intent = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { intent.force(true); }
            forceDirectory(directory);
            try {
                channel.position(expected); ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            } catch (IOException | RuntimeException failure) {
                try { channel.position(channel.size()); channel.write(ByteBuffer.wrap(new byte[]{0})); channel.force(true); }
                catch (IOException | RuntimeException ignored) { }
                throw failure;
            }
        }
        public void close() throws IOException {
            try { lock.release(); } finally {
                channel.close();
                synchronized (OPEN_PATHS) { OPEN_PATHS.remove(directory); }
            }
        }
    }
}
