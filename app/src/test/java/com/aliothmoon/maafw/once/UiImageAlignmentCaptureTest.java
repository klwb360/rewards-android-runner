package com.aliothmoon.maafw.once;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.UiImageGeometryBindingTest.ready;

/** Synthetic byte/metadata tests. An Owner identity here is not a completed PNG receipt. */
public class UiImageAlignmentCaptureTest {
    static OnceBackend.Owner owner() {
        BackendPorts.FrameSource source = new BackendPorts.FrameSource() {
            public void produce(OnceBackend.Owner owner, BackendPorts.FrameSink sink, BackendPorts.StopSignal stop) { throw new AssertionError(); }
            public void release(OnceBackend.Owner owner) { throw new AssertionError(); }
            public BackendPorts.Completion completion(OnceBackend.Owner owner) { throw new AssertionError(); }
        };
        BackendPorts.Recognizer recognizer = new BackendPorts.Recognizer() {
            public BackendPorts.Recognition recognize(OnceBackend.Owner owner, BackendPorts.FrameView frame, BackendPorts.StopSignal stop) { throw new AssertionError(); }
            public void release(OnceBackend.Owner owner) { throw new AssertionError(); }
            public BackendPorts.Completion completion(OnceBackend.Owner owner) { throw new AssertionError(); }
        };
        return new OnceBackend(o -> new BackendPorts.ClaimReceipt(o, BackendPorts.ClaimDecision.DENIED),
                source, recognizer, Runnable::run).start().owner();
    }

    static final class Fixture implements AutoCloseable {
        final UiImageGeometryBindingTest.Fixture ui = new UiImageGeometryBindingTest.Fixture();
        final AtomicBoolean revoked = new AtomicBoolean(), cancelled = new AtomicBoolean();
        final UiImageAlignmentCapture capture = ui.geometry().beginFrame(ready(2350), 2400, 7000, revoked::get);
        final Object recognizer = new Object();
        final OnceBackend.Owner owner = owner();
        final byte[] gray = new byte[capture.width() * capture.height()];
        final OwnedFrame frame = new OwnedFrame();
        Fixture() {
            assertTrue(capture.claimTransport());
            frame.sink().begin(capture.width(), capture.height());
            frame.sink().append(gray, 0, gray.length);
            assertTrue(frame.seal());
        }
        UiImageAlignmentCapture.Candidate prepare() {
            return capture.prepare(recognizer, owner, frame.view(), gray, List.of(), cancelled::get, 2600);
        }
        boolean seal(UiImageAlignmentCapture.Candidate candidate) {
            return capture.sealSource(candidate, recognizer, owner, 2450, 2500, ready(2700), 2800);
        }
        public void close() { frame.wipeAfterQuiescence();Arrays.fill(gray, (byte) 0); }
    }

    private static final class BulkFixture implements AutoCloseable {
        final java.util.ArrayList<String> events = new java.util.ArrayList<>();
        final UiImageGeometryBindingTest.Fixture ui = new UiImageGeometryBindingTest.Fixture();
        final UiImageAlignmentCapture capture;
        final Object recognizer = new Object();
        final OnceBackend.Owner owner = owner();
        final OwnedFrame frame = new OwnedFrame();
        final byte[] gray;
        final BackendPorts.FrameView view;
        byte[] scratch;
        int cancelCalls, revokedCalls, cancelAt = Integer.MAX_VALUE, revokedAt = Integer.MAX_VALUE;
        boolean failCopy;
        BulkFixture() {
            capture = ui.geometry().beginFrame(ready(2350), 2400, 7000, () -> {
                events.add("revoked"); return ++revokedCalls == revokedAt;
            });
            assertNotNull(capture); assertTrue(capture.claimTransport());
            gray = new byte[capture.width() * capture.height()]; Arrays.fill(gray, (byte) 7);
            frame.sink().begin(capture.width(), capture.height()); frame.sink().append(gray, 0, gray.length);
            assertTrue(frame.seal());
            view = new BackendPorts.FrameView() {
                public int width() { return frame.view().width(); }
                public int height() { return frame.view().height(); }
                public int size() { return frame.view().size(); }
                public int unsignedByte(int index) { throw new AssertionError("Expected bounded copy"); }
                public void copyGrayRange(int offset, byte[] target, int targetOffset, int length) {
                    assertTrue(length > 0 && length <= 4096); assertEquals(0, targetOffset);
                    assertEquals(Math.min(4096, gray.length - offset), length);
                    events.add("copy:" + offset); scratch = target;
                    if (failCopy) { target[0] = 7; throw new IllegalStateException("fixture copy failure"); }
                    frame.view().copyGrayRange(offset, target, targetOffset, length);
                }
            };
            events.clear(); revokedCalls = 0;
        }
        UiImageAlignmentCapture.Candidate prepare() {
            return capture.prepare(recognizer, owner, view, gray, List.of(), () -> {
                events.add("cancel"); return ++cancelCalls == cancelAt;
            }, 2600);
        }
        void scratchCleared() { assertNotNull(scratch); assertArrayEquals(new byte[scratch.length], scratch); }
        public void close() { frame.wipeAfterQuiescence(); Arrays.fill(gray, (byte) 0); }
    }

    @Test public void bulkComparisonKeepsEveryOriginalCancelAndRevocationBoundaryInOrder() {
        try (var f = new BulkFixture()) {
            var candidate = f.prepare(); assertNotNull(candidate);
            var expected = new java.util.ArrayList<String>(); expected.add("cancel"); expected.add("revoked");
            for (int offset = 0; offset < f.gray.length; offset += 4096) {
                expected.add("cancel"); expected.add("revoked"); expected.add("copy:" + offset);
            }
            assertEquals(expected, f.events.subList(0, expected.size()));
            assertTrue(candidate.matches(f.capture, f.recognizer, f.owner, f.view)); f.scratchCleared();
        }
    }

    @Test public void cancelAtTheNextOriginalBoundaryPreventsTheNextCopyAndStillWipesScratch() {
        try (var f = new BulkFixture()) {
            f.cancelAt = 3; assertNull(f.prepare());
            assertEquals(List.of("cancel", "revoked", "cancel", "revoked", "copy:0", "cancel"), f.events);
            assertEquals(2, f.revokedCalls); f.scratchCleared();
            int count = f.events.size(); assertNull(f.prepare()); assertEquals(count, f.events.size());
        }
    }

    @Test public void revocationAtTheNextOriginalBoundaryPreventsTheNextCopy() {
        try (var f = new BulkFixture()) {
            f.revokedAt = 3; assertNull(f.prepare());
            assertEquals(List.of("cancel", "revoked", "cancel", "revoked", "copy:0", "cancel", "revoked"), f.events);
            f.scratchCleared(); assertNull(f.prepare());
        }
    }

    @Test public void mismatchInTheFinalCopyRejectsAndWipesTheWholeScratch() {
        try (var f = new BulkFixture()) {
            f.gray[f.gray.length - 1] ^= 1; assertNull(f.prepare()); f.scratchCleared();
            assertTrue(f.capture.diagnostics().contains("prepareGate=PIXEL_MISMATCH"));
            long copies = f.events.stream().filter(value -> value.startsWith("copy:")).count();
            assertEquals((f.gray.length + 4095L) / 4096L, copies); assertNull(f.prepare());
        }
    }

    @Test public void copyFailureAfterPartialWriteRejectsWipesAndSpendsPrepare() {
        try (var f = new BulkFixture()) {
            f.failCopy = true; assertNull(f.prepare()); f.scratchCleared();
            assertTrue(f.capture.diagnostics().contains("prepareGate=ERROR"));
            int count = f.events.size(); f.failCopy = false; assertNull(f.prepare()); assertEquals(count, f.events.size());
        }
    }

    @Test public void captureGateWorksBeforeSealWhilePublicationStaysClosed() {
        try (var f = new Fixture()) {
            assertTrue(f.capture.captureCurrent(2400));assertFalse(f.capture.publicationCurrent(2400));
            assertFalse(f.capture.captureCurrent(2399));assertFalse(f.capture.captureCurrent(7000));
            f.revoked.set(true);
            assertFalse(f.capture.captureCurrent(2400));assertFalse(f.capture.publicationCurrent(2400));
        }
    }

    @Test public void oneCaptureCannotOpenMoreThanOneTransport() {
        var capture = new UiImageGeometryBindingTest.Fixture().capture();
        assertTrue(capture.claimTransport());assertFalse(capture.claimTransport());
        assertFalse(capture.publicationCurrent(2400));
        var earlySeal = new UiImageGeometryBindingTest.Fixture().capture();
        assertFalse(earlySeal.sealSource(null, new Object(), owner(), 2450, 2500, ready(2700), 2800));
        assertFalse(earlySeal.claimTransport());
    }

    @Test public void candidateBindsExactCaptureRecognizerBackendOwnerAndFrame() throws Exception {
        try (var f = new Fixture()) {
            var candidate = f.prepare();assertNotNull(candidate);assertNotNull(candidate.result());
            assertTrue(candidate.matches(f.capture, f.recognizer, f.owner, f.frame.view()));
            assertFalse(candidate.matches(f.capture, new Object(), f.owner));
            assertFalse(candidate.matches(f.capture, f.recognizer, owner()));
            assertFalse(candidate.matches(new UiImageGeometryBindingTest.Fixture().capture(), f.recognizer, f.owner));
            assertFalse(candidate.matches(f.capture, f.recognizer, f.owner, null));
            assertEquals(2600, candidate.observedAt());
            assertTrue(candidate.graySha256Matches(MessageDigest.getInstance("SHA-256").digest(f.gray)));
            assertFalse(candidate.graySha256Matches(null));assertFalse(candidate.graySha256Matches(new byte[32]));
            byte[] copy = candidate.graySha256();Arrays.fill(copy, (byte) 1);
            assertTrue(candidate.graySha256Matches(MessageDigest.getInstance("SHA-256").digest(f.gray)));
            assertNull(f.prepare());
        }
    }

    @Test public void sameDimensionsCannotPairAnUnrelatedGrayImageWithTheFrame() {
        try (var f = new Fixture()) {
            f.gray[17] = 1;
            assertNull(f.prepare());
            f.gray[17] = 0;
            assertNull(f.prepare());
        }
    }

    @Test public void wrongShapeSizeOrInvalidByteRejectsBeforeAnyCandidate() {
        for (int bad = 0; bad < 4; bad++) try (var f = new Fixture()) {
            final int mode = bad;
            BackendPorts.FrameView view = new BackendPorts.FrameView() {
                public int width() { return mode == 0 ? f.capture.height() : f.capture.width(); }
                public int height() { return f.capture.height(); }
                public int size() { return mode == 1 ? f.gray.length - 1 : f.gray.length; }
                public int unsignedByte(int index) { return mode == 2 ? 256 : mode == 3 ? -1 : 0; }
            };
            assertNull(f.capture.prepare(f.recognizer, f.owner, view, f.gray, List.of(), () -> false, 2600));
        }
    }

    @Test public void missingOrAlreadyWipedFrameCannotProduceCandidate() {
        try (var f = new Fixture()) {
            f.frame.wipeAfterQuiescence();assertNull(f.prepare());
        }
        try (var f = new Fixture()) {
            assertNull(f.capture.prepare(f.recognizer, f.owner, null, f.gray, List.of(), () -> false, 2600));
        }
    }

    @Test public void cancellationAndFailurePredicatesRejectAndCannotRetry() {
        try (var f = new Fixture()) {
            f.cancelled.set(true);assertNull(f.prepare());f.cancelled.set(false);assertNull(f.prepare());
        }
        try (var f = new Fixture()) {
            assertNull(f.capture.prepare(f.recognizer, f.owner, f.frame.view(), f.gray, List.of(),
                    () -> { throw new IllegalStateException(); }, 2600));
        }
    }

    @Test public void sourceEnvelopeAndAfterMetadataSealOnlyOnce() {
        try (var f = new Fixture()) {
            var candidate = f.prepare();assertNotNull(candidate);
            assertFalse(f.capture.publicationCurrent(2800));assertTrue(f.seal(candidate));
            assertTrue(f.capture.publicationCurrent(2800));assertTrue(f.capture.publicationCurrent(2900));
            assertFalse(f.capture.publicationCurrent(2799));assertFalse(f.capture.publicationCurrent(7000));
            assertFalse(f.seal(candidate));
        }
    }

    @Test public void malformedEnvelopesAndMetadataAreNotRepairableByRetry() {
        for (int bad = 0; bad < 6; bad++) try (var f = new Fixture()) {
            var candidate = f.prepare();assertNotNull(candidate);
            long enter = bad == 0 ? 2399 : 2450;
            long returned = bad == 1 ? 2449 : bad == 2 ? 2601 : 2500;
            var after = bad == 3 ? ready(2599) : bad == 4 ? ready(2801) : bad == 5
                    ? BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE, 2700, "bbb", "ddd", "15").ready() : ready(2700);
            assertFalse(f.capture.sealSource(candidate, f.recognizer, f.owner, enter, returned, after, 2800));
            assertFalse(f.seal(candidate));assertFalse(f.capture.publicationCurrent(2800));
        }
    }

    @Test public void sourceSealRejectsCandidateOrRecognizerFromAnotherCapture() {
        try (var f = new Fixture(); var other = new Fixture()) {
            var candidate = f.prepare();var unrelated = other.prepare();assertNotNull(candidate);assertNotNull(unrelated);
            assertFalse(f.capture.sealSource(unrelated, f.recognizer, f.owner, 2450, 2500, ready(2700), 2800));
            assertFalse(f.seal(candidate));
        }
        try (var f = new Fixture()) {
            assertFalse(f.capture.sealSource(f.prepare(), new Object(), f.owner, 2450, 2500, ready(2700), 2800));
        }
    }

    @Test public void cancellationBeforeSealAndJointRevocationAfterSealReject() {
        try (var f = new Fixture()) {
            var candidate = f.prepare();f.cancelled.set(true);
            assertFalse(f.seal(candidate));assertFalse(f.capture.publicationCurrent(2900));
        }
        try (var f = new Fixture()) {
            assertTrue(f.seal(f.prepare()));f.revoked.set(true);assertFalse(f.capture.publicationCurrent(2900));
        }
    }

    @Test public void sealingDropsFrameRecognizerAndBackendStopGraphEvenWhenRejected() throws Exception {
        for (boolean accepted : new boolean[] {true, false}) try (var f = new Fixture()) {
            var candidate = f.prepare();assertNotNull(candidate);
            if (!accepted) f.cancelled.set(true);
            assertEquals(accepted, f.seal(candidate));
            assertFalse(candidate.matches(f.capture, f.recognizer, f.owner));
            assertNotNull(candidate.result());
            for (String name : new String[] {"capture", "recognizer", "owner", "frameIdentity", "cancel"}) {
                var field = UiImageAlignmentCapture.Candidate.class.getDeclaredField(name);
                field.setAccessible(true);assertNull(name, field.get(candidate));
            }
            var field = UiImageAlignmentCapture.class.getDeclaredField("candidate");
            field.setAccessible(true);assertNull(field.get(f.capture));
        }
        // Arbitrary fixture cancel suppliers can change after sealing. A real completed backend's
        // stop is terminal; publication revocation is checked by the retained joint-session gate.
    }

    @Test public void sourceChecksCannotExtendOriginalUiAgeOrAcceptTimeReversal() {
        var f = new UiImageGeometryBindingTest.Fixture();
        var capture = f.geometry().beginFrame(ready(2350), 2400, Long.MAX_VALUE, () -> false);
        assertTrue(capture.sourceMatchesAfter(ready(2700), 2600, 7200));
        assertFalse(capture.sourceMatchesAfter(ready(2700), 2600, 7201));
        assertFalse(capture.sourceMatchesAfter(ready(2700), 2399, 2800));
        assertFalse(capture.sourceMatchesAfter(ready(2700), 2801, 2800));
        assertFalse(capture.sourceMatchesAfter(null, 2600, 2800));
    }

    @Test public void diagnosticSnapshotDoesNotCallRevocationOrSpendCaptureAuthority() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var geometry = new UiImageGeometryBindingTest.Fixture().geometry();
        var capture = geometry.beginFrame(ready(2350), 2400, 7000, () -> { calls.incrementAndGet(); return false; });
        assertNotNull(capture);assertTrue(capture.captureCurrent(2500));
        int checked = calls.get();String before = capture.diagnostics();
        for (int i = 0; i < 20; i++) assertEquals(before, capture.diagnostics());
        assertEquals(checked, calls.get());assertTrue(capture.claimTransport());assertFalse(capture.claimTransport());
        assertTrue(before.contains("candidateFormed=false"));assertTrue(before.contains("uiAfterAgeMs=300"));
    }

    @Test public void diagnosticSeparatesOldUiAgeFromFreshAfterMetadataWithoutExtendingEither() {
        var capture = new UiImageGeometryBindingTest.Fixture().geometry()
                .beginFrame(ready(2350), 2400, Long.MAX_VALUE, () -> false);
        assertTrue(capture.sourceMatchesAfter(ready(7199), 2600, 7200));
        assertFalse(capture.sourceMatchesAfter(ready(7199), 2600, 7201));
        String diagnostic = capture.diagnostics();
        assertTrue(diagnostic.contains("afterGate=CURRENT_REJECTED"));
        assertTrue(diagnostic.contains("currentGate=UI_GEOMETRY_REJECTED"));
        assertTrue(diagnostic.contains("firstRejectedUiAfterAgeMs=5001"));
        assertTrue(diagnostic.contains("imageAfterAgeMs=2"));
        // A later independent rejection must not erase the first observed freshness failure.
        assertFalse(capture.captureCurrent(Long.MAX_VALUE));
        assertTrue(capture.diagnostics().contains("firstCurrentReject=UI_GEOMETRY_REJECTED"));
        assertTrue(capture.diagnostics().contains("firstRejectedUiAfterAgeMs=5001"));
    }

    @Test public void sealDiagnosticsUseExistingEnvelopeAndDoNotConsumePublication() {
        try (var f = new Fixture()) {
            var candidate = f.prepare();assertNotNull(candidate);assertTrue(f.seal(candidate));
            String before = f.capture.diagnostics();
            assertTrue(before.contains("prepareGate=ACCEPTED candidateFormed=true"));
            assertTrue(before.contains("sealGate=ACCEPTED"));assertTrue(before.contains("sourceMs=50"));
            assertTrue(before.contains("sourceToCandidateMs=100"));assertTrue(before.contains("candidateToSealMs=200"));
            assertEquals(before, f.capture.diagnostics());
            assertTrue(f.capture.publicationCurrent(2900));
            assertTrue(f.capture.diagnostics().contains("sealToPublicationMs=100"));
            assertFalse(f.seal(candidate));assertFalse(f.capture.claimTransport());
        }
    }

    @Test public void diagnosticDistinguishesSourceTimingAndAbsentAfterWithoutExceptionText() {
        try (var f = new Fixture()) {
            assertFalse(f.capture.sealSource(f.prepare(), f.recognizer, f.owner, 2450, 2601, ready(2700), 2800));
            assertTrue(f.capture.diagnostics().contains("sealGate=SOURCE_TIMING"));
            assertTrue(f.capture.diagnostics().contains("afterGate=NOT_CHECKED"));
        }
        try (var f = new Fixture()) {
            assertFalse(f.capture.sealSource(f.prepare(), f.recognizer, f.owner, 2450, 2500, null, 2800));
            assertTrue(f.capture.diagnostics().contains("sealGate=AFTER_REJECTED"));
            assertTrue(f.capture.diagnostics().contains("afterGate=MISSING"));
        }
    }

    @Test public void candidateHasNoRawImageOrOcrGetterAndOnlyPrivateConstruction() {
        for (var constructor : UiImageAlignmentCapture.Candidate.class.getDeclaredConstructors())
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        for (var method : UiImageAlignmentCapture.Candidate.class.getDeclaredMethods()) {
            assertNotEquals(BackendPorts.FrameView.class, method.getReturnType());
            assertFalse(List.class.isAssignableFrom(method.getReturnType()));
        }
        for (var field : UiImageAlignmentCapture.Candidate.class.getDeclaredFields()) {
            assertNotEquals(BackendPorts.FrameView.class, field.getType());
            assertFalse(List.class.isAssignableFrom(field.getType()));
        }
    }
}
