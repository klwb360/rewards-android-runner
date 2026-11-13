package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/** Independent real-capture admission namespace. No synthetic journal, OS token,
 * Intent, pixels or account data is persisted. Live grants require a fresh first-party opaque visible click; this controlled build has a fresh fixed one-attempt namespace. */
final class RealCaptureClaim {
    enum Scope { WHOLE_DISPLAY_READ_ONLY_ONCE, UNVERIFIED_APP_SELECTION }
    enum Outcome { CONSUMED, REJECTED, UNCERTAIN }
    static final class ProcessEpoch {
        private final String id=UUID.randomUUID().toString();
        private Object retained;
        synchronized boolean retain(Object session) { if(retained!=null)return false;retained=session;return true; }
        synchronized void release(Object session) { if(retained==session)retained=null; }
        synchronized boolean retaining() { return retained!=null; }
    }
    interface BurnStore { void burn(String id, String metadata) throws IOException; }
    /** Exclusive creation is the atomic burn, never an overwrite/rename rollback.
     * A partial file, existing file or force failure fails closed forever. No read,
     * recovery, deletion, repair or resume API exists. Directory force must succeed
     * before authority can advance; unsupported platforms therefore fail closed. */
    static final class FileBurnStore implements BurnStore {
        private final Path directory;
        FileBurnStore(Path privateFilesDirectory) {
            directory = Objects.requireNonNull(privateFilesDirectory).resolve("controlled-whole-frame-20261111-fifth-once");
        }
        public void burn(String id, String metadata) throws IOException {
            if (!id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid claim id");
            Files.createDirectories(directory);
            // Fixed CREATE_NEW slot binds this reviewed build to one durable attempt, even after process restart.
            Path file = directory.resolve("attempt.burned");
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(metadata);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
            try (FileChannel parent = FileChannel.open(directory.getParent(), StandardOpenOption.READ)) { parent.force(true); }
        }
    }
    static final class Grant {
        private final ProcessEpoch epoch;
        private final Object owner;
        private final String id = UUID.randomUUID().toString();
        private final long issued, deadline;
        private final Scope scope;
        private final boolean live;
        private boolean attempted;
        private Grant(ProcessEpoch epoch, Object owner, long issued, long deadline, Scope scope, boolean live) {
            this.epoch=Objects.requireNonNull(epoch); this.owner=Objects.requireNonNull(owner);
            this.issued=issued; this.deadline=deadline; this.scope=Objects.requireNonNull(scope); this.live=live;
        }
    }
    /** Test authority can exercise metadata durability, but can never mint a live ticket. */
    static Grant fixture(ProcessEpoch epoch, Object owner, long now, long ttl, Scope scope) {
        if (now < 0 || ttl <= 0 || ttl > 60_000 || now > Long.MAX_VALUE-ttl) throw new IllegalArgumentException();
        return new Grant(epoch, owner, now, now+ttl, scope, false);
    }
    static Grant visible(VisibleCaptureAuthority.Proof proof, ProcessEpoch epoch, Object owner, long now) {
        CaptureReleaseGate.requireConfigured();
        if(proof==null || !proof.consume(epoch,owner,now))throw new SecurityException("Fresh visible proof required");
        return new Grant(epoch,owner,now,proof.deadline(),Scope.WHOLE_DISPLAY_READ_ONLY_ONCE,true);
    }
    static boolean live(Grant grant) { return grant!=null && grant.live; }
    static long deadline(Grant grant) { return grant.deadline; }
    static synchronized Outcome consume(Grant grant, ProcessEpoch epoch, Object owner, long now, BurnStore store) {
        if (grant == null || grant.attempted) return Outcome.REJECTED;
        grant.attempted=true; // Even wrong-owner, expired or uncertain attempts cannot retry.
        if (grant.epoch!=epoch || grant.owner!=owner || now<grant.issued || now>=grant.deadline
                || grant.scope!=Scope.WHOLE_DISPLAY_READ_ONLY_ONCE) return Outcome.REJECTED;
        try {
            store.burn(grant.id, "real-capture-v1\nauthority="+(grant.live?"LIVE_VISIBLE":"FIXTURE_ONLY")+"\n"+grant.id+"\nepoch="+epoch.id+"\nissuedElapsed="+grant.issued+"\ndeadlineElapsed="+grant.deadline+"\nWHOLE_DISPLAY_READ_ONLY_ONCE\nmaxFrames=1\nnoActions=true\n");
            return Outcome.CONSUMED;
        } catch (IOException | RuntimeException failure) { return Outcome.UNCERTAIN; }
    }
    static boolean current(Grant grant, long now) { return now>=grant.issued && now<grant.deadline; }
    private RealCaptureClaim() { }
}
