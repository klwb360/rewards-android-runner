package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * One runtime-owned model preparation, before any UI observation or backend owner exists.
 * The runtime must retain this handle before prepare(), including after an UNKNOWN outcome.
 * No native object, frame, query, approval or source authority is created here.
 */
final class PreparedAlignmentModels {
    interface Store extends AutoCloseable {
        void prepare() throws Exception;
        void write(int index, InputStream input) throws Exception;
        String bundlePath() throws Exception;
        @Override void close() throws Exception;
    }
    @FunctionalInterface interface Factory { Store open(Path root) throws Exception; }
    @FunctionalInterface interface Assets { InputStream open(int index) throws Exception; }

    enum State { NEW, OPENING, PREPARING, WRITING, READY, CLAIMED, CLOSING, RELEASED, UNKNOWN }
    enum Failure { NONE, CANCELED, OPEN, PREPARE, WRITE, CLOSE, UNEXPECTED }

    private final Object runtimeOwner;
    private final Thread ownerThread;
    private final Factory factory;
    private volatile State state = State.NEW;
    private volatile Failure failure = Failure.NONE;
    private Store store;
    private Object backendOwner;
    private boolean active, closeAttempted;

    private PreparedAlignmentModels(Object runtimeOwner, Factory factory) {
        this.runtimeOwner = Objects.requireNonNull(runtimeOwner);
        this.factory = Objects.requireNonNull(factory);
        ownerThread = Thread.currentThread();
    }

    /** Pure construction on the existing owner worker; assign to the runtime before prepare(). */
    static PreparedAlignmentModels create(Object runtimeOwner, Factory factory) {
        return new PreparedAlignmentModels(runtimeOwner, factory);
    }

    /** One attempt. False never authorizes a fallback, another store or another preparation. */
    boolean prepare(Object expectedRuntimeOwner, Path root, Assets assets, BooleanSupplier stop) {
        requireRuntime(expectedRuntimeOwner);
        Objects.requireNonNull(root);
        Objects.requireNonNull(assets);
        Objects.requireNonNull(stop);
        if (active || state != State.NEW) throw new IllegalStateException("Model preparation already used");
        active = true;
        try {
            if (stop.getAsBoolean()) return cancelAndClose();
            state = State.OPENING;
            try {
                store = factory.open(root);
                if (store == null) { unknown(Failure.OPEN); return false; }
            } catch (Exception failed) {
                preserveInterrupt(failed); unknown(Failure.OPEN); return false;
            }
            if (stop.getAsBoolean()) return cancelAndClose();
            // A failed prepare may have created a directory before remembering its identity.
            // Store.close() cannot prove such a partially created graph was fully removed.
            state = State.PREPARING;
            try {
                store.prepare();
            } catch (Exception failed) {
                preserveInterrupt(failed); unknown(Failure.PREPARE); return false;
            } catch (Error failed) {
                unknown(Failure.PREPARE); throw failed;
            }
            state = State.WRITING;
            try {
                for (int index = 0; index < MaaOnceModelPolicy.count(); index++) {
                    if (stop.getAsBoolean()) return cancelAndClose();
                    try (InputStream input = assets.open(index)) {
                        if (input == null) throw new IOException("Model asset unavailable");
                        store.write(index, input);
                    }
                }
                if (stop.getAsBoolean()) return cancelAndClose();
            } catch (RuntimeException failed) {
                unknown(Failure.UNEXPECTED); return false;
            } catch (Exception failed) {
                preserveInterrupt(failed); failure = Failure.WRITE; closeStoreOnce(); return false;
            }
            state = State.READY;
            return true;
        } catch (RuntimeException failed) {
            unknown(Failure.UNEXPECTED); return false;
        } catch (Error failed) {
            if (state != State.UNKNOWN) unknown(Failure.UNEXPECTED);
            throw failed;
        } finally {
            active = false;
        }
    }

    /**
     * Transfer once, only after recognize() has its actual backend owner.
     * This owner identity is bookkeeping, never a capture claim or a cleanup receipt.
     */
    Store claim(Object expectedRuntimeOwner, Object actualBackendOwner) {
        requireRuntime(expectedRuntimeOwner);
        if (active || state != State.READY || actualBackendOwner == null)
            throw new IllegalStateException("Prepared models unavailable");
        backendOwner = actualBackendOwner;
        state = State.CLAIMED;
        return store;
    }

    /**
     * Call only immediately after this exact claimed Store.close() actually returns.
     * The recognizer retains its existing MODELS_CLOSE receipt and native cleanup ordering.
     * A thrown/blocked close must never be acknowledged; this method performs no close or retry.
     */
    void confirmClosed(Store exactStore) {
        requireThread();
        if (active || state != State.CLAIMED || backendOwner == null || exactStore == null || exactStore != store)
            throw new IllegalStateException("Exact claimed model close required");
        store = null;
        backendOwner = null;
        state = State.RELEASED;
    }

    /** Cleanup before transfer. A claimed store belongs exclusively to the backend. */
    void closeBeforeAdoption(Object expectedRuntimeOwner) {
        requireRuntime(expectedRuntimeOwner);
        if (active) throw new IllegalStateException("Model preparation active");
        if (state == State.RELEASED || state == State.UNKNOWN || state == State.CLAIMED) return;
        if (state != State.NEW && state != State.READY)
            throw new IllegalStateException("Model preparation outcome unavailable");
        active = true;
        try { closeStoreOnce(); }
        finally { active = false; }
    }

    // Pure volatile scalar reads must never block another thread behind file IO.
    State state() { return state; }
    Failure failure() { return failure; }
    boolean knownReleased() { return state == State.RELEASED; }
    boolean awaitingBackendCleanup() { return state == State.CLAIMED; }

    private boolean cancelAndClose() {
        failure = Failure.CANCELED;
        closeStoreOnce();
        return false;
    }

    private void closeStoreOnce() {
        if (closeAttempted) { unknown(Failure.CLOSE); return; }
        closeAttempted = true;
        if (store == null) { state = State.RELEASED; return; }
        state = State.CLOSING;
        try {
            // MaaOnceModelStore.close verifies recorded directory/file identities itself.
            store.close();
            store = null;
            state = State.RELEASED;
        } catch (Exception failed) {
            preserveInterrupt(failed); unknown(Failure.CLOSE);
        } catch (Error failed) {
            unknown(Failure.CLOSE); throw failed;
        }
    }

    private void unknown(Failure why) { failure = why; state = State.UNKNOWN; }
    private static void preserveInterrupt(Exception failed) {
        if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    private void requireRuntime(Object expected) {
        requireThread();
        if (expected == null || expected != runtimeOwner) throw new SecurityException("Exact model runtime owner required");
    }
    private void requireThread() {
        if (Thread.currentThread() != ownerThread) throw new SecurityException("Exact model owner worker required");
    }
}
