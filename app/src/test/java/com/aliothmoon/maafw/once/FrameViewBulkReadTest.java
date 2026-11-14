package com.aliothmoon.maafw.once;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** In-memory frame contract only; no source, native recognizer or platform gate is invoked. */
public class FrameViewBulkReadTest {
    private static final class ScalarView implements BackendPorts.FrameView {
        final int declaredSize;
        int reads, invalidAt = -1, invalidValue;
        RuntimeException failure;
        ScalarView(int size) { declaredSize = size; }
        public int width() { return declaredSize; }
        public int height() { return 1; }
        public int size() { return declaredSize; }
        public int unsignedByte(int index) {
            reads++;
            if (failure != null) throw failure;
            return index == invalidAt ? invalidValue : index & 255;
        }
    }

    private static OwnedFrame sealed(byte[] bytes, int width, int height) {
        var frame = new OwnedFrame();
        frame.sink().begin(width, height); frame.sink().append(bytes, 0, bytes.length);
        assertTrue(frame.seal()); return frame;
    }

    @Test public void defaultCopiesExactUnsignedBytesAndOnlyTheRequestedDestination() {
        var view = new ScalarView(5000); byte[] target = new byte[4100]; Arrays.fill(target, (byte) 77);
        view.copyGrayRange(123, target, 2, 4096);
        assertEquals(4096, view.reads);
        assertEquals(77, target[0]); assertEquals(77, target[1]);
        assertEquals(77, target[4098]); assertEquals(77, target[4099]);
        for (int i = 0; i < 4096; i++) assertEquals((123 + i) & 255, target[i + 2] & 255);
        byte[] last = new byte[1]; view.copyGrayRange(4999, last, 0, 1);
        assertEquals(4999 & 255, last[0] & 255);
    }

    @Test public void defaultRejectsTheCompleteRangeBeforeAnyReadOrWrite() {
        int[][] invalid = {{0, 0, 0}, {0, 0, -1}, {0, 0, 4097}, {-1, 0, 1},
                {32, 0, 1}, {31, 0, 2}, {0, -1, 1}, {0, 32, 1}, {0, 31, 2},
                {Integer.MAX_VALUE, 0, 1}, {0, Integer.MAX_VALUE, 1}, {1, 0, Integer.MAX_VALUE}};
        for (int[] range : invalid) {
            var view = new ScalarView(32); byte[] target = new byte[32]; Arrays.fill(target, (byte) 91);
            byte[] original = target.clone();
            assertThrows(IndexOutOfBoundsException.class,
                    () -> view.copyGrayRange(range[0], target, range[1], range[2]));
            assertEquals(0, view.reads); assertArrayEquals(original, target);
        }
    }

    @Test public void defaultRejectsNullAndInvalidSourceSizeWithoutReading() {
        var view = new ScalarView(32);
        assertThrows(NullPointerException.class, () -> view.copyGrayRange(0, null, 0, 1));
        assertEquals(0, view.reads);
        var invalid = new ScalarView(-1); byte[] target = {(byte) 90};
        assertThrows(IndexOutOfBoundsException.class, () -> invalid.copyGrayRange(0, target, 0, 1));
        assertEquals(0, invalid.reads); assertEquals(90, target[0]);
    }

    @Test public void defaultDoesNotTruncateAnInvalidUnsignedValueIntoAnAcceptedByte() {
        for (int invalid : new int[]{-1, 256, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            var view = new ScalarView(8); view.invalidAt = 2; view.invalidValue = invalid;
            byte[] target = new byte[8]; Arrays.fill(target, (byte) 99);
            assertThrows(IllegalStateException.class, () -> view.copyGrayRange(0, target, 0, 8));
            assertEquals(3, view.reads); assertEquals(0, target[0]); assertEquals(1, target[1]);
            for (int i = 2; i < target.length; i++) assertEquals(99, target[i]);
        }
    }

    @Test public void defaultPropagatesTheOriginalReaderException() {
        var view = new ScalarView(4); view.failure = new SecurityException("fixture failure");
        byte[] target = {(byte) 33};
        assertSame(view.failure, assertThrows(SecurityException.class, () -> view.copyGrayRange(0, target, 0, 1)));
        assertEquals(1, view.reads); assertEquals(33, target[0]);
    }

    @Test public void ownedCopyIsDetachedAndMatchesTheScalarView() {
        byte[] pixels = new byte[8192];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (i * 37);
        var frame = sealed(pixels, 128, 64);
        try {
            byte[] target = new byte[4100]; Arrays.fill(target, (byte) 11);
            frame.view().copyGrayRange(3000, target, 2, 4096);
            for (int i = 0; i < 4096; i++) assertEquals(frame.view().unsignedByte(3000 + i), target[i + 2] & 255);
            assertEquals(11, target[0]); assertEquals(11, target[4099]);
            target[2] ^= 1; assertEquals(pixels[3000] & 255, frame.view().unsignedByte(3000));
            byte[] copied = target.clone(); frame.wipeAfterQuiescence(); assertArrayEquals(copied, target);
        } finally { frame.wipeAfterQuiescence(); Arrays.fill(pixels, (byte) 0); }
    }

    @Test public void ownedCopyRejectsEveryInvalidRangeBeforeDestinationMutation() {
        byte[] pixels = new byte[32]; Arrays.fill(pixels, (byte) 7); var frame = sealed(pixels, 8, 4);
        try {
            for (int[] range : new int[][]{{0, 0, 0}, {0, 0, 4097}, {-1, 0, 1}, {32, 0, 1},
                    {31, 0, 2}, {0, -1, 1}, {0, 31, 2}, {Integer.MAX_VALUE, 0, 1}, {0, Integer.MAX_VALUE, 1}}) {
                byte[] target = new byte[32]; Arrays.fill(target, (byte) 99); byte[] original = target.clone();
                assertThrows(IndexOutOfBoundsException.class,
                        () -> frame.view().copyGrayRange(range[0], target, range[1], range[2]));
                assertArrayEquals(original, target);
            }
            assertThrows(NullPointerException.class, () -> frame.view().copyGrayRange(0, null, 0, 1));
        } finally { frame.wipeAfterQuiescence(); }
    }

    @Test public void ownedCopyRequiresACompleteSealedFrameAndRejectsAfterWipe() {
        var frame = new OwnedFrame(); byte[] target = {(byte) 17};
        assertThrows(IllegalStateException.class, () -> frame.view().copyGrayRange(0, target, 0, 1));
        frame.sink().begin(2, 2); frame.sink().append(new byte[]{1, 2, 3, 4}, 0, 4);
        assertThrows(IllegalStateException.class, () -> frame.view().copyGrayRange(0, target, 0, 1));
        assertTrue(frame.seal()); frame.view().copyGrayRange(0, target, 0, 1); assertEquals(1, target[0]);
        frame.wipeAfterQuiescence(); target[0] = 17;
        assertThrows(IllegalStateException.class, () -> frame.view().copyGrayRange(0, target, 0, 1));
        assertEquals(17, target[0]);
    }

    @Test public void incompleteOrRejectedFrameCannotBeBulkRead() {
        var incomplete = new OwnedFrame(); incomplete.sink().begin(2, 2);
        incomplete.sink().append(new byte[]{1}, 0, 1); assertFalse(incomplete.seal());
        var rejected = new OwnedFrame(); rejected.sink().begin(2, 2);
        assertThrows(OwnedFrame.InvalidFrame.class, () -> rejected.sink().append(new byte[5], 0, 5));
        assertFalse(rejected.seal());
        for (var frame : new OwnedFrame[]{incomplete, rejected}) {
            byte[] target = {(byte) 21};
            assertThrows(IllegalStateException.class, () -> frame.view().copyGrayRange(0, target, 0, 1));
            assertEquals(21, target[0]); frame.wipeAfterQuiescence();
        }
    }

    @Test public void bulkReadWaitsForFrameMonitorAndCannotReadAfterWipe() throws Exception {
        byte[] pixels = new byte[4096]; Arrays.fill(pixels, (byte) 7); var frame = sealed(pixels, 64, 64);
        byte[] target = new byte[4096]; Arrays.fill(target, (byte) 19); byte[] original = target.clone();
        var attempted = new CountDownLatch(1); var failure = new AtomicReference<Throwable>();
        Thread reader = new Thread(() -> {
            attempted.countDown();
            try { frame.view().copyGrayRange(0, target, 0, target.length); }
            catch (Throwable caught) { failure.set(caught); }
        });
        try {
            synchronized (frame) {
                reader.start(); assertTrue(attempted.await(5, TimeUnit.SECONDS));
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (reader.getState() != Thread.State.BLOCKED && reader.isAlive() && System.nanoTime() < end) Thread.yield();
                assertEquals(Thread.State.BLOCKED, reader.getState()); assertArrayEquals(original, target);
                frame.wipeAfterQuiescence();
            }
        } finally { reader.join(5000); frame.wipeAfterQuiescence(); }
        assertFalse(reader.isAlive()); assertTrue(failure.get() instanceof IllegalStateException);
        assertArrayEquals(original, target);
    }
}
