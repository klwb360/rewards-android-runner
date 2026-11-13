package com.aliothmoon.maafw.trial;

import io.github.klwb360.rewards.core.RewardsCore.State;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * File-backed compare-and-set for the future trial app. A platform adapter must supply a real
 * boot identifier and directory fsync. This implementation has not yet been tested on Android.
 * A write marker survives an interrupted transaction and blocks later reads until reviewed.
 */
public final class AtomicTrialStore implements SingleTrialCoordinator.StateStore {
    public interface BootId { long current() throws IOException; }
    public interface DirectorySync { void sync(Path directory) throws IOException; }

    private static final int MAGIC = 0x4D525431;
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 65_536;
    private final Path directory, stateFile, markerFile, newFile, backupFile, lockFile;
    private final BootId bootId;
    private final DirectorySync sync;

    public AtomicTrialStore(Path privateDirectory, BootId bootId, DirectorySync sync) throws IOException {
        this.directory = Objects.requireNonNull(privateDirectory).toAbsolutePath().normalize();
        this.bootId = Objects.requireNonNull(bootId);
        this.sync = Objects.requireNonNull(sync);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new IOException("private state directory unavailable");
        }
        stateFile = directory.resolve("trial-state.bin");
        markerFile = directory.resolve("trial-state.intent");
        newFile = directory.resolve("trial-state.new");
        backupFile = directory.resolve("trial-state.bak");
        lockFile = directory.resolve("trial-state.lock");
    }

    @Override public State read() throws IOException {
        return locked(() -> readUnlocked(validBoot()));
    }

    /** Initializing a missing state requires a separately verified migration gate. */
    @Override public boolean compareAndSet(State expected, State replacement) throws IOException {
        Objects.requireNonNull(replacement);
        validate(replacement);
        return locked(() -> {
            long boot = validBoot();
            State actual = readUnlocked(boot);
            if (!Objects.equals(expected, actual)) return false;
            byte[] encoded = encode(replacement, boot);
            createAndSync(markerFile, new byte[]{'M', 'R', 'T', '1'});
            sync.sync(directory);
            createAndSync(newFile, encoded);
            Files.move(newFile, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            sync.sync(directory);
            Files.delete(markerFile);
            sync.sync(directory);
            if (validBoot() != boot || !replacement.equals(readUnlocked(boot))) {
                throw new IOException("state readback or boot check failed");
            }
            return true;
        });
    }

    private interface IoCall<T> { T run() throws IOException; }
    private <T> T locked(IoCall<T> action) throws IOException {
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid lock file");
            try (FileLock ignored = channel.lock()) { return action.run(); }
        } catch (OverlappingFileLockException | UnsupportedOperationException failure) {
            throw new IOException("state lock unavailable", failure);
        }
    }

    private long validBoot() throws IOException {
        long boot = bootId.current();
        if (boot < 0) throw new IOException("boot identity unavailable");
        return boot;
    }

    private State readUnlocked(long boot) throws IOException {
        requireAbsent(markerFile); requireAbsent(newFile); requireAbsent(backupFile);
        if (Files.notExists(stateFile, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid state file");
        byte[] bytes;
        try (FileChannel input = FileChannel.open(stateFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = input.size();
            if (size < 1 || size > MAX_BYTES) throw new IOException("invalid state length");
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                int read = input.read(buffer);
                if (read < 0) throw new EOFException("short state file");
                if (read == 0) throw new IOException("state read made no progress");
            }
            if (input.size() != size) throw new IOException("state changed during read");
            bytes = buffer.array();
        }
        return decode(bytes, boot);
    }

    private static void requireAbsent(Path path) throws IOException {
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("state recovery artifact present or inaccessible");
    }

    private static void createAndSync(Path path, byte[] bytes) throws IOException {
        try (FileChannel output = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (output.write(buffer) == 0) throw new IOException("state write made no progress");
            }
            output.force(true);
        }
    }

    static byte[] encode(State state, long boot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(boot); out.writeUTF(state.day());
            out.writeLong(state.runStartedAt()); out.writeLong(state.readingStartedAt());
            out.writeInt(state.readingAttempts()); out.writeInt(state.readingNoCredit());
            out.writeInt(state.searchAttempts()); out.writeInt(state.searchNoCredit());
            List<String> hashes = new ArrayList<>(state.searchHashes()); hashes.sort(String::compareTo);
            out.writeInt(hashes.size()); for (String hash : hashes) out.writeUTF(hash);
            out.writeUTF(state.pendingTask()); out.writeInt(state.pendingBefore());
            out.writeInt(state.pendingTarget()); out.writeUTF(state.pendingHash());
        }
        byte[] body = bytes.toByteArray();
        byte[] checksum = checksum(body);
        if (body.length + checksum.length > MAX_BYTES) throw new IOException("state too large");
        byte[] result = Arrays.copyOf(body, body.length + checksum.length);
        System.arraycopy(checksum, 0, result, body.length, checksum.length);
        return result;
    }

    static State decode(byte[] bytes, long boot) throws IOException {
        if (bytes.length <= 32) throw new IOException("short state checksum");
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        byte[] expectedChecksum = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        if (!MessageDigest.isEqual(checksum(body), expectedChecksum)) throw new IOException("state checksum mismatch");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readLong() != boot) {
                throw new IOException("state schema or boot identity changed");
            }
            String day = in.readUTF(); long started = in.readLong(), readingStarted = in.readLong();
            int readingAttempts = in.readInt(), readingNoCredit = in.readInt();
            int searchAttempts = in.readInt(), searchNoCredit = in.readInt();
            int count = in.readInt();
            if (count < 0 || count > 100) throw new IOException("invalid hash count");
            Set<String> hashes = new HashSet<>();
            for (int i = 0; i < count; i++) if (!hashes.add(in.readUTF())) throw new IOException("duplicate hash");
            State state = new State(day, started, readingStarted, readingAttempts, readingNoCredit,
                    searchAttempts, searchNoCredit, hashes, in.readUTF(), in.readInt(), in.readInt(), in.readUTF());
            if (in.read() != -1) throw new IOException("trailing state data");
            validate(state);
            return state;
        } catch (RuntimeException failure) { throw new IOException("invalid state contents", failure); }
    }

    private static byte[] checksum(byte[] body) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(body); }
        catch (NoSuchAlgorithmException failure) { throw new IOException("SHA-256 unavailable", failure); }
    }

    static void validate(State state) throws IOException {
        if (state.readingAttempts() > 10 || state.searchAttempts() > 100 || state.searchHashes().size() > 100) {
            throw new IOException("state exceeds trial budget bounds");
        }
        if (state.pendingTask().isEmpty()) {
            if (state.pendingBefore() != 0 || state.pendingTarget() != 0 || !state.pendingHash().isEmpty()) {
                throw new IOException("orphaned pending fields");
            }
        } else if (state.pendingTask().equals("SEARCH")) {
            if (!state.pendingHash().matches("[0-9a-f]{64}") || !state.searchHashes().contains(state.pendingHash())) {
                throw new IOException("pending search hash missing");
            }
        } else if (state.pendingTask().equals("READING")) {
            if (state.readingAttempts() < 1 || !state.pendingHash().isEmpty()) {
                throw new IOException("invalid pending reading");
            }
        } else {
            throw new IOException("unsupported pending task");
        }
    }
}
