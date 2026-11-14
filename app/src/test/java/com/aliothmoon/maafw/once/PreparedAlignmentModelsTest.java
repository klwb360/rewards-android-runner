package com.aliothmoon.maafw.once;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fake stores only: these tests do not establish Android file IO or native readiness. */
public class PreparedAlignmentModelsTest {
    private static final Path ROOT = Paths.get("model-preparation-test-root");

    private static final class FakeStore implements PreparedAlignmentModels.Store {
        int prepares, closes;
        final List<Integer> writes = new ArrayList<>();
        Exception prepareFailure, writeFailure, closeFailure;
        Error prepareError, closeError;
        Runnable afterWrite = () -> { };
        @Override public void prepare() throws Exception {
            prepares++;
            if (prepareError != null) throw prepareError;
            if (prepareFailure != null) throw prepareFailure;
        }
        @Override public void write(int index, InputStream input) throws Exception {
            writes.add(index);
            assertEquals(index + 1, input.read());
            if (writeFailure != null) throw writeFailure;
            afterWrite.run();
        }
        @Override public String bundlePath() { return "fixed-fake-bundle"; }
        @Override public void close() throws Exception {
            closes++;
            if (closeError != null) throw closeError;
            if (closeFailure != null) throw closeFailure;
        }
    }
    private static final class Fixture {
        final Object runtime = new Object();
        final FakeStore store = new FakeStore();
        final AtomicInteger opens = new AtomicInteger(), assetsOpened = new AtomicInteger(), assetsClosed = new AtomicInteger();
        final PreparedAlignmentModels handle = PreparedAlignmentModels.create(runtime, path -> {
            assertEquals(ROOT, path); opens.incrementAndGet(); return store;
        });
        final PreparedAlignmentModels.Assets assets = index -> {
            assetsOpened.incrementAndGet();
            return new ByteArrayInputStream(new byte[]{(byte) (index + 1)}) {
                @Override public void close() throws IOException { assetsClosed.incrementAndGet(); super.close(); }
            };
        };
        boolean prepare() { return handle.prepare(runtime, ROOT, assets, () -> false); }
    }

    @Test public void preparationIsOneShotAndAdoptionPreservesExactStore() throws Exception {
        Fixture f = new Fixture();
        assertEquals(0, f.opens.get());
        assertEquals(PreparedAlignmentModels.State.NEW, f.handle.state());
        assertFalse(f.handle.knownReleased());
        assertTrue(f.prepare());
        assertEquals(List.of(0, 1, 2), f.store.writes);
        assertEquals(3, f.assetsOpened.get());
        assertEquals(3, f.assetsClosed.get());
        assertThrows(IllegalStateException.class, f::prepare);
        Object backend = new Object();
        assertSame(f.store, f.handle.claim(f.runtime, backend));
        assertTrue(f.handle.awaitingBackendCleanup());
        assertFalse(f.handle.knownReleased());
        assertThrows(IllegalStateException.class, () -> f.handle.claim(f.runtime, backend));
        f.handle.closeBeforeAdoption(f.runtime);
        assertEquals(0, f.store.closes);
        f.store.close();
        assertFalse("Actual backend close must still be acknowledged", f.handle.knownReleased());
        f.handle.confirmClosed(f.store);
        assertTrue(f.handle.knownReleased());
        assertFalse(f.handle.awaitingBackendCleanup());
        assertThrows(IllegalStateException.class, () -> f.handle.confirmClosed(f.store));
        f.handle.closeBeforeAdoption(f.runtime);
        assertEquals(1, f.store.closes);
    }

    @Test public void uiFailureBeforeAdoptionClosesPreparedStoreExactlyOnce() {
        Fixture f = new Fixture(); assertTrue(f.prepare());
        f.handle.closeBeforeAdoption(f.runtime); f.handle.closeBeforeAdoption(f.runtime);
        assertEquals(1, f.store.closes); assertTrue(f.handle.knownReleased());
        assertThrows(IllegalStateException.class, () -> f.handle.claim(f.runtime, new Object()));
    }

    @Test public void cancellationBeforeOpenConsumesHandleWithoutOpeningAnything() {
        Fixture f = new Fixture();
        assertFalse(f.handle.prepare(f.runtime, ROOT, f.assets, () -> true));
        assertEquals(0, f.opens.get()); assertEquals(0, f.store.closes);
        assertTrue(f.handle.knownReleased());
        assertEquals(PreparedAlignmentModels.Failure.CANCELED, f.handle.failure());
        assertThrows(IllegalStateException.class, f::prepare);
    }

    @Test public void unusedHandleCanCloseWithoutOpeningOrRearming() {
        Fixture f = new Fixture();
        f.handle.closeBeforeAdoption(f.runtime);
        assertTrue(f.handle.knownReleased()); assertEquals(0, f.opens.get());
        assertThrows(IllegalStateException.class, f::prepare);
    }

    @Test public void cancellationAfterPrepareClosesBeforeFirstAsset() {
        Fixture f = new Fixture(); AtomicInteger checks = new AtomicInteger();
        assertFalse(f.handle.prepare(f.runtime, ROOT, f.assets, () -> checks.incrementAndGet() > 2));
        assertEquals(1, f.store.prepares); assertEquals(0, f.assetsOpened.get());
        assertEquals(1, f.store.closes); assertTrue(f.handle.knownReleased());
    }

    @Test public void cancellationAfterOpenAvoidsCreatingModelDirectories() {
        Fixture f = new Fixture(); AtomicInteger checks = new AtomicInteger();
        assertFalse(f.handle.prepare(f.runtime, ROOT, f.assets, () -> checks.incrementAndGet() > 1));
        assertEquals(1, f.opens.get()); assertEquals(0, f.store.prepares);
        assertEquals(0, f.assetsOpened.get()); assertEquals(1, f.store.closes);
        assertTrue(f.handle.knownReleased());
    }

    @Test public void cancellationBetweenWritesDoesNotReadRemainingAssets() {
        Fixture f = new Fixture(); AtomicBoolean canceled = new AtomicBoolean();
        f.store.afterWrite = () -> canceled.set(true);
        assertFalse(f.handle.prepare(f.runtime, ROOT, f.assets, canceled::get));
        assertEquals(List.of(0), f.store.writes); assertEquals(1, f.assetsClosed.get());
        assertEquals(1, f.store.closes); assertTrue(f.handle.knownReleased());
    }

    @Test public void cancellationAfterFinalWriteCannotExposeReadyStore() {
        Fixture f = new Fixture(); AtomicBoolean canceled = new AtomicBoolean();
        f.store.afterWrite = () -> { if (f.store.writes.size() == 3) canceled.set(true); };
        assertFalse(f.handle.prepare(f.runtime, ROOT, f.assets, canceled::get));
        assertEquals(3, f.assetsClosed.get()); assertTrue(f.handle.knownReleased());
        assertEquals(1, f.store.closes);
        assertThrows(IllegalStateException.class, () -> f.handle.claim(f.runtime, new Object()));
    }

    @Test public void throwingOrNullFactoryCannotClaimUnknownConstructionIsClean() {
        for (boolean nullResult : new boolean[]{false, true}) {
            Object runtime = new Object(); AtomicInteger opens = new AtomicInteger();
            var handle = PreparedAlignmentModels.create(runtime, path -> {
                opens.incrementAndGet(); if (nullResult) return null; throw new IOException("fixed fake failure");
            });
            assertFalse(handle.prepare(runtime, ROOT, index -> new ByteArrayInputStream(new byte[0]), () -> false));
            assertEquals(PreparedAlignmentModels.Failure.OPEN, handle.failure());
            assertEquals(PreparedAlignmentModels.State.UNKNOWN, handle.state());
            handle.closeBeforeAdoption(runtime);
            assertFalse(handle.knownReleased()); assertEquals(1, opens.get());
        }
    }

    @Test public void failedDirectoryPreparationIsQuarantinedWithoutTryingClose() {
        Fixture f = new Fixture(); f.store.prepareFailure = new IOException("created before identity recorded");
        assertFalse(f.prepare());
        assertEquals(PreparedAlignmentModels.Failure.PREPARE, f.handle.failure());
        assertEquals(PreparedAlignmentModels.State.UNKNOWN, f.handle.state());
        f.handle.closeBeforeAdoption(f.runtime);
        assertEquals(0, f.store.closes); assertEquals(0, f.assetsOpened.get());
        assertFalse(f.handle.knownReleased());
        assertThrows(IllegalStateException.class, () -> f.handle.claim(f.runtime, new Object()));
    }

    @Test public void prepareErrorKeepsUnknownAndIsNotSwallowed() {
        Fixture f = new Fixture(); f.store.prepareError = new AssertionError("fixed fake error");
        assertSame(f.store.prepareError, assertThrows(AssertionError.class, f::prepare));
        assertEquals(PreparedAlignmentModels.Failure.PREPARE, f.handle.failure());
        f.handle.closeBeforeAdoption(f.runtime);
        assertEquals(0, f.store.closes); assertFalse(f.handle.knownReleased());
    }

    @Test public void checkedWriteFailureClosesPartialStoreAndAssetOnce() {
        Fixture f = new Fixture(); f.store.writeFailure = new IOException("fixed fake write failure");
        assertFalse(f.prepare());
        assertEquals(PreparedAlignmentModels.Failure.WRITE, f.handle.failure());
        assertEquals(1, f.assetsClosed.get()); assertEquals(1, f.store.closes);
        assertTrue(f.handle.knownReleased());
        f.handle.closeBeforeAdoption(f.runtime); assertEquals(1, f.store.closes);
    }

    @Test public void missingAssetClosesPreparedStoreWithoutWriting() {
        for (boolean nullAsset : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            assertFalse(f.handle.prepare(f.runtime, ROOT, index -> {
                if (nullAsset) return null; throw new IOException("fixed missing asset");
            }, () -> false));
            assertEquals(0, f.store.writes.size()); assertEquals(1, f.store.closes);
            assertTrue(f.handle.knownReleased());
        }
    }

    @Test public void assetCloseFailureStillRequiresStoreCleanup() {
        Fixture f = new Fixture();
        assertFalse(f.handle.prepare(f.runtime, ROOT, index -> new ByteArrayInputStream(new byte[]{1}) {
            @Override public void close() throws IOException { throw new IOException("fixed asset close failure"); }
        }, () -> false));
        assertEquals(List.of(0), f.store.writes); assertEquals(1, f.store.closes);
        assertTrue(f.handle.knownReleased());
    }

    @Test public void unexpectedWriteFailureIsNotConvertedToSuccessfulCleanup() {
        Fixture f = new Fixture(); f.store.writeFailure = new IllegalStateException("fixed wrapper failure");
        assertFalse(f.prepare());
        assertEquals(1, f.assetsClosed.get()); assertEquals(0, f.store.closes);
        assertEquals(PreparedAlignmentModels.State.UNKNOWN, f.handle.state());
        f.handle.closeBeforeAdoption(f.runtime); assertEquals(0, f.store.closes);
    }

    @Test public void throwingStopSupplierCannotExposePreparedStore() {
        Fixture f = new Fixture(); AtomicInteger checks = new AtomicInteger();
        assertFalse(f.handle.prepare(f.runtime, ROOT, f.assets, () -> {
            if (checks.incrementAndGet() == 3) throw new IllegalStateException("fixed stop failure");
            return false;
        }));
        assertEquals(1, f.store.prepares); assertEquals(0, f.store.writes.size());
        assertEquals(PreparedAlignmentModels.Failure.UNEXPECTED, f.handle.failure());
        assertEquals(PreparedAlignmentModels.State.UNKNOWN, f.handle.state());
        f.handle.closeBeforeAdoption(f.runtime); assertEquals(0, f.store.closes);
        assertThrows(IllegalStateException.class, () -> f.handle.claim(f.runtime, new Object()));
    }

    @Test public void failedIdentityCheckedCloseIsUnknownAndNeverRetried() {
        Fixture f = new Fixture();
        f.store.writeFailure = new IOException("fixed partial write");
        f.store.closeFailure = new IOException("fixed identity uncertainty");
        assertFalse(f.prepare());
        assertEquals(PreparedAlignmentModels.Failure.CLOSE, f.handle.failure());
        assertFalse(f.handle.knownReleased()); assertEquals(1, f.store.closes);
        f.handle.closeBeforeAdoption(f.runtime); f.handle.closeBeforeAdoption(f.runtime);
        assertEquals(1, f.store.closes);
    }

    @Test public void closeFailureAfterUiRejectionIsAlsoNeverRetried() {
        Fixture f = new Fixture(); assertTrue(f.prepare());
        f.store.closeFailure = new IOException("fixed close failure");
        f.handle.closeBeforeAdoption(f.runtime); f.handle.closeBeforeAdoption(f.runtime);
        assertEquals(1, f.store.closes); assertFalse(f.handle.knownReleased());
        assertFalse(f.handle.awaitingBackendCleanup());
    }

    @Test public void preAdoptionCloseErrorKeepsUnknownWithoutRetryOrFalseRelease() {
        Fixture f = new Fixture(); assertTrue(f.prepare());
        f.store.closeError = new AssertionError("fixed model close error");
        assertSame(f.store.closeError,
                assertThrows(AssertionError.class, () -> f.handle.closeBeforeAdoption(f.runtime)));
        assertEquals(PreparedAlignmentModels.State.UNKNOWN, f.handle.state());
        assertEquals(PreparedAlignmentModels.Failure.CLOSE, f.handle.failure());
        assertFalse(f.handle.knownReleased());
        assertFalse(f.handle.awaitingBackendCleanup());
        assertEquals(1, f.store.closes);
        f.handle.closeBeforeAdoption(f.runtime);
        f.handle.closeBeforeAdoption(f.runtime);
        assertEquals(1, f.store.closes);
        assertEquals(PreparedAlignmentModels.State.UNKNOWN, f.handle.state());
        assertFalse(f.handle.knownReleased());
        assertThrows(IllegalStateException.class, () -> f.handle.claim(f.runtime, new Object()));
        assertThrows(IllegalStateException.class, () -> f.handle.confirmClosed(f.store));
    }

    @Test public void claimedCloseFailureCannotBeCleanedByRuntimeFallback() throws Exception {
        Fixture f = new Fixture(); assertTrue(f.prepare());
        f.handle.claim(f.runtime, new Object()); f.store.closeFailure = new IOException("fixed backend close failure");
        assertThrows(IOException.class, () -> f.store.close());
        f.handle.closeBeforeAdoption(f.runtime);
        assertEquals(1, f.store.closes); assertFalse(f.handle.knownReleased());
        assertTrue(f.handle.awaitingBackendCleanup());
    }

    @Test public void ownershipAndExactStoreAreRequiredForAdoptionAndAcknowledgement() {
        Fixture f = new Fixture();
        assertThrows(SecurityException.class, () -> f.handle.prepare(new Object(), ROOT, f.assets, () -> false));
        assertEquals(0, f.opens.get()); assertTrue(f.prepare());
        assertThrows(SecurityException.class, () -> f.handle.claim(new Object(), new Object()));
        assertThrows(IllegalStateException.class, () -> f.handle.claim(f.runtime, null));
        assertThrows(IllegalStateException.class, () -> f.handle.confirmClosed(f.store));
        f.handle.claim(f.runtime, new Object());
        assertThrows(IllegalStateException.class, () -> f.handle.confirmClosed(new FakeStore()));
        assertFalse(f.handle.knownReleased()); assertTrue(f.handle.awaitingBackendCleanup());
        assertThrows(SecurityException.class, () -> f.handle.closeBeforeAdoption(new Object()));
        assertEquals(0, f.store.closes);
    }

    @Test public void anotherThreadCannotPrepareAdoptAcknowledgeOrClose() throws Exception {
        Fixture f = new Fixture();
        wrongThread(() -> f.prepare()); assertEquals(0, f.opens.get());
        assertTrue(f.prepare());
        wrongThread(() -> f.handle.claim(f.runtime, new Object()));
        wrongThread(() -> f.handle.closeBeforeAdoption(f.runtime));
        f.handle.claim(f.runtime, new Object());
        wrongThread(() -> f.handle.confirmClosed(f.store));
        assertTrue(f.handle.awaitingBackendCleanup()); assertEquals(0, f.store.closes);
    }

    @Test public void interruptedWritePreservesInterruptWhileClosingKnownStore() {
        Fixture f = new Fixture(); f.store.writeFailure = new InterruptedException();
        try {
            assertFalse(f.prepare()); assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, f.store.closes); assertTrue(f.handle.knownReleased());
        } finally { Thread.interrupted(); }
    }

    private static void wrongThread(Runnable operation) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { operation.run(); } catch (Throwable caught) { failure.set(caught); } });
        worker.start(); worker.join(1000);
        assertFalse("Pure fake operation must return", worker.isAlive());
        assertTrue(failure.get() instanceof SecurityException);
    }
}
