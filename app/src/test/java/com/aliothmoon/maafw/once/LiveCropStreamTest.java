package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.AndroidCardRegionCapture.FixedStatus;
import static com.aliothmoon.maafw.once.CardRegionCaptureProtocol.Cleanup;
import static com.aliothmoon.maafw.once.CardRegionCaptureProtocol.Status;

public class LiveCropStreamTest {
    private static final long START = 100, DISPATCH = 200;
    private static final LiveCropStream.Window AFTER = LiveCropStream.window(START, 5100, true);
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final Box ROI = new Box(100, 600, 116, 616);

    private static CardRegionCaptureProtocol.Request request(String nonce) {
        return new CardRegionCaptureProtocol.Request(nonce, AFTER.captureDeadline(), ROI, 1440, 2560, 0);
    }

    private static byte[] wire(String nonce, Status status, Cleanup cleanup) throws Exception {
        var req = request(nonce);
        var report = status == Status.CAPTURED
                ? new CardRegionCaptureProtocol.Report(req, 250, 300, status, cleanup, true,
                        ROI.width(), ROI.height(), CardRegionCaptureProtocolTest.png(ROI.width(), ROI.height()))
                : new CardRegionCaptureProtocol.Report(req, 250, 300, status, cleanup, false, 0, 0, null);
        return CardRegionCaptureProtocol.encode(report);
    }

    /** Nonblocking pipe tail, EOF and process exit become available independently. */
    private static final class Transport implements LiveCropStream.Input, LiveCropStream.ProcessExit {
        final byte[] wire;
        long now = 320, tailAt = 390, eofAt = 410, exitAt = 430;
        int consumed, code;

        Transport(byte[] wire) { this.wire = wire; }

        @Override public int read(byte[] bytes, int offset, int length) {
            now += 5;
            int available = now < tailAt ? wire.length / 2 : wire.length;
            if (consumed < available) {
                int count = Math.min(available - consumed, length);
                System.arraycopy(wire, consumed, bytes, offset, count);
                consumed += count;
                return count;
            }
            return consumed == wire.length && now >= eofAt ? -1 : 0;
        }

        @Override public boolean quiet() { now += 5; return now >= exitAt; }
        @Override public int exitValue() {
            assertTrue("Exit value requested while process was running", now >= exitAt);
            return code;
        }

        LiveCropStream drain() throws Exception {
            LiveCropStream stream = new LiveCropStream();
            stream.read(new byte[2048], request(NONCE), DISPATCH, AFTER, () -> now,
                    () -> true, this, this, millis -> now += millis);
            return stream;
        }
    }

    @Test public void zeroExitBeforeTheTailDoesNotTruncateTheBoundCrop() throws Exception {
        var transport = new Transport(wire(NONCE, Status.CAPTURED, Cleanup.CONFIRMED));
        transport.exitAt = 340;
        var stream = transport.drain();
        assertTrue(stream.eof && stream.quiet && stream.exitObserved);
        assertEquals(FixedStatus.CAPTURED, stream.status);
        assertEquals(transport.wire.length, transport.consumed);
        assertArrayEquals(CardRegionCaptureProtocolTest.png(16, 16), stream.png);
        assertEquals(16, stream.pngWidth);
        assertEquals(16, stream.pngHeight);
        assertTrue(stream.released(true));
        assertFalse(stream.released(false));
    }

    @Test public void eofBeforeExitStillRequiresTheActualZeroExitCode() throws Exception {
        var transport = new Transport(wire(NONCE, Status.CAPTURED, Cleanup.CONFIRMED));
        transport.exitAt = 700;
        var stream = transport.drain();
        assertTrue(transport.now >= transport.exitAt);
        assertEquals(FixedStatus.CAPTURED, stream.status);
        assertTrue(stream.released(true));

        var badExit = new Transport(wire(NONCE, Status.CAPTURED, Cleanup.CONFIRMED));
        badExit.code = 3;
        var failed = badExit.drain();
        assertTrue(failed.exitObserved);
        assertEquals(FixedStatus.EXIT_NONZERO, failed.status);
        assertFalse(failed.released(true));
        assertNull(failed.png);
    }

    @Test public void lateBoundCropOnlyProvesCleanupAndNeverReturnsPixels() throws Exception {
        var transport = new Transport(wire(NONCE, Status.CAPTURED, Cleanup.CONFIRMED));
        transport.tailAt = AFTER.captureDeadline() + 20;
        transport.eofAt = transport.tailAt + 20;
        transport.exitAt = transport.eofAt + 20;
        var stream = transport.drain();
        assertTrue(transport.now < AFTER.drainDeadline());
        assertEquals(FixedStatus.READ_TIMEOUT, stream.status);
        assertEquals(Status.CAPTURED, stream.helperStatus);
        assertEquals(Cleanup.CONFIRMED, stream.helperCleanup);
        assertEquals(LiveCropStream.Stage.WIRE_TAIL, stream.expiredAt);
        assertTrue(stream.released(true));
        assertTrue(LivePerceptionBudget.retireCrop(LiveRewardsTypes.Phase.AFTER_1,
                stream.status, stream.helperStatus, stream.released(true), false));
        assertNull(stream.png);
    }

    @Test public void revokedOrExpiredEvidenceCannotAuthorizeNewRecognition() throws Exception {
        var transport = new Transport(wire(NONCE, Status.CAPTURED, Cleanup.CONFIRMED));
        var stream = new LiveCropStream();
        stream.read(new byte[2048], request(NONCE), DISPATCH, AFTER, () -> transport.now,
                () -> false, transport, transport, millis -> transport.now += millis);
        assertNotEquals(FixedStatus.CAPTURED, stream.status);
        assertNull(stream.png);
    }

    @Test public void missingEofOrMissingExitHasNoCleanupProof() throws Exception {
        for (boolean omitEof : new boolean[]{true, false}) {
            var transport = new Transport(wire(NONCE, Status.CAPTURED, Cleanup.CONFIRMED));
            if (omitEof) transport.eofAt = Long.MAX_VALUE;
            else transport.exitAt = Long.MAX_VALUE;
            var stream = transport.drain();
            assertTrue(transport.now >= AFTER.drainDeadline());
            assertEquals(omitEof ? LiveCropStream.Stage.WIRE_TAIL : LiveCropStream.Stage.PROCESS_EXIT,
                    stream.expiredAt);
            assertFalse(stream.released(true));
            assertNull(stream.helperCleanup);
            assertNull(stream.png);
        }
    }

    @Test public void noByteAndNoExitReportsWhatIsStillMissing() throws Exception {
        var stream = new LiveCropStream();
        var transport = new Transport(new byte[0]);
        transport.eofAt = transport.exitAt = Long.MAX_VALUE;
        stream.read(new byte[2048], request(NONCE), DISPATCH, AFTER, () -> transport.now,
                () -> true, transport, transport, millis -> transport.now += millis);
        assertEquals(LiveCropStream.Stage.FIRST_BYTE, stream.expiredAt);
        assertEquals(0, stream.count);
        assertFalse(stream.eof || stream.quiet || stream.exitObserved || stream.released(true));
        assertTrue(stream.diagnostic(true).contains("_EOF_0_QUIET_0_EXIT_0_CODE_NONE_FD_1_HELPER_NONE"));
    }

    @Test public void cleanExitCannotSubstituteForMissingTruncatedOrWrongNonceReceipt() throws Exception {
        byte[] valid = wire(NONCE, Status.CAPTURED, Cleanup.CONFIRMED);
        for (byte[] bytes : new byte[][] {new byte[0], Arrays.copyOf(valid, valid.length - 1),
                wire("ffffffffffffffffffffffffffffffff", Status.CAPTURED, Cleanup.CONFIRMED)}) {
            var stream = new Transport(bytes).drain();
            assertTrue(stream.eof && stream.quiet && stream.exitObserved);
            assertEquals(FixedStatus.PROTOCOL_REJECTED, stream.status);
            assertFalse(stream.released(true));
            assertNull(stream.png);
        }
    }

    @Test public void lateExplicitRefusalsAndUnconfirmedHelperCleanupRemainVisible() throws Exception {
        for (Status reason : new Status[]{Status.PERMISSION_DENIED, Status.SECURE_OUTPUT_REJECTED, Status.CLEANUP_FAILED}) {
            Cleanup cleanup = reason == Status.CLEANUP_FAILED ? Cleanup.UNKNOWN : Cleanup.CONFIRMED;
            var transport = new Transport(wire(NONCE, reason, cleanup));
            transport.tailAt = AFTER.captureDeadline() + 20;
            transport.eofAt = transport.tailAt + 20;
            var stream = transport.drain();
            assertEquals(reason, stream.helperStatus);
            assertEquals(cleanup, stream.helperCleanup);
            assertFalse(LivePerceptionBudget.retireCrop(LiveRewardsTypes.Phase.AFTER_1,
                    stream.status, stream.helperStatus, stream.released(true), true));
            assertEquals(cleanup == Cleanup.CONFIRMED, stream.released(true));
            assertNull(stream.png);
        }
    }

    @Test public void afterAdmissionReservesTimeForCleanupBeforeAllocatingACrop() {
        assertTrue(AFTER.captureDeadline() < AFTER.drainDeadline());
        assertTrue(AFTER.mayDispatch(DISPATCH));
        assertFalse(AFTER.mayDispatch(AFTER.captureDeadline() - 1));
        assertFalse(AFTER.mayDispatch(AFTER.captureDeadline()));
        var tooShort = LiveCropStream.window(2000, 2100, true);
        assertFalse(tooShort.mayDispatch(2000));
        var before = LiveCropStream.window(START, 5100, false);
        assertEquals(before.captureDeadline(), before.drainDeadline());
        assertTrue(before.mayDispatch(DISPATCH));
    }

    @Test public void onlyCleanAfterWorkMayRetireForInsufficientTime() {
        for (var phase : new LiveRewardsTypes.Phase[]{LiveRewardsTypes.Phase.AFTER_1, LiveRewardsTypes.Phase.AFTER_2}) {
            assertTrue(LivePerceptionBudget.retireCrop(phase, FixedStatus.INSUFFICIENT_BUDGET, null, true, false));
            assertTrue(LivePerceptionBudget.retireCrop(phase, FixedStatus.CAPTURE_REJECTED,
                    Status.DEADLINE_EXCEEDED, true, false));
            assertFalse(LivePerceptionBudget.retireCrop(phase, FixedStatus.READ_TIMEOUT, null, false, true));
            assertFalse(LivePerceptionBudget.retireCrop(phase, FixedStatus.CANCELLED, null, true, false));
            assertFalse(LivePerceptionBudget.retireCrop(phase, FixedStatus.CANCELLED, null, true, true));
        }
        assertFalse(LivePerceptionBudget.retireCrop(LiveRewardsTypes.Phase.BEFORE, FixedStatus.INSUFFICIENT_BUDGET,
                null, true, false));
        assertFalse(LivePerceptionBudget.retireCrop(LiveRewardsTypes.Phase.BEFORE, FixedStatus.READ_TIMEOUT,
                Status.CAPTURED, true, false));
    }

    @Test public void admissionDistinguishesAnElapsedWorkWindowFromLostAuthority() {
        assertNull(AFTER.preDispatch(DISPATCH, true, true, true, false, false));
        long low = AFTER.captureDeadline() - 1;
        assertEquals(FixedStatus.INSUFFICIENT_BUDGET, AFTER.preDispatch(low, true, true, true, false, false));
        long elapsed = AFTER.drainDeadline() + 1;
        assertEquals(FixedStatus.INSUFFICIENT_BUDGET, AFTER.preDispatch(elapsed, true, true, false, true, false));
        assertEquals(FixedStatus.AUTHORITY_REJECTED, AFTER.preDispatch(elapsed, false, true, false, true, false));
        assertEquals(FixedStatus.CANCELLED, AFTER.preDispatch(elapsed, true, false, false, true, false));
        assertEquals(FixedStatus.CANCELLED, AFTER.preDispatch(elapsed, true, true, false, true, true));
        assertEquals(FixedStatus.CANCELLED, AFTER.preDispatch(low, true, true, false, false, false));
        var before = LiveCropStream.window(START, 5100, false);
        assertEquals(FixedStatus.READ_TIMEOUT, before.preDispatch(elapsed, true, true, false, true, false));
    }

    @Test public void cancellationDuringCleanupTakesPriorityOverTheEarlierReadTimeout() throws Exception {
        var transport = new Transport(wire(NONCE, Status.CAPTURED, Cleanup.CONFIRMED));
        transport.tailAt = AFTER.captureDeadline() + 60;
        transport.eofAt = transport.exitAt = transport.tailAt + 30;
        var stream = new LiveCropStream();
        stream.read(new byte[2048], request(NONCE), DISPATCH, AFTER, () -> transport.now,
                () -> transport.now < transport.tailAt, transport, transport, millis -> transport.now += millis);
        assertTrue(stream.released(true));
        assertEquals(FixedStatus.CANCELLED, stream.status);
        assertNull(stream.png);
        assertFalse(LivePerceptionBudget.retireCrop(LiveRewardsTypes.Phase.AFTER_1,
                stream.status, stream.helperStatus, true, true));
    }

    @Test public void knownSourceRejectionWinsEvenIfTheGateAlsoRunsOutOfTime() throws Exception {
        var image = new LiveCoreFixture().source();
        int[] consumed = {0};
        var changed = LiveRewardsPerception.cropGate(() -> true, image, () -> null,
                () -> { consumed[0]++; return true; }, () -> true);
        assertThrows(SecurityException.class, () -> changed.consumeWithin(() -> false));
        assertEquals(0, consumed[0]);
        var failedRead = LiveRewardsPerception.cropGate(() -> true, image,
                () -> { throw new SecurityException("Source rejected"); }, () -> true, () -> true);
        assertThrows(SecurityException.class, failedRead::requireCurrent);
        var elapsed = LiveRewardsPerception.cropGate(() -> false, image,
                () -> { throw new AssertionError("A retired crop cannot read"); },
                () -> { consumed[0]++; return true; }, () -> true);
        assertThrows(AndroidCardRegionCapture.InsufficientBudget.class, elapsed::consume);
        assertEquals(0, consumed[0]);
    }

    @Test public void aDeadlineReceiptCannotClearAnObservedRevocation() throws Exception {
        var transport = new Transport(wire(NONCE, Status.DEADLINE_EXCEEDED, Cleanup.CONFIRMED));
        var stream = new LiveCropStream();
        stream.read(new byte[2048], request(NONCE), DISPATCH, AFTER, () -> transport.now,
                () -> transport.now < 350 || transport.now > 370, transport, transport, millis -> transport.now += millis);
        assertEquals(Status.DEADLINE_EXCEEDED, stream.helperStatus);
        assertTrue(stream.released(true));
        assertEquals(FixedStatus.CANCELLED, stream.status);
        assertFalse(LivePerceptionBudget.retireCrop(LiveRewardsTypes.Phase.AFTER_1,
                stream.status, stream.helperStatus, true, true));
    }
}
