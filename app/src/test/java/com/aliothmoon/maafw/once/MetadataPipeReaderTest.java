package com.aliothmoon.maafw.once;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetadataPipeReaderTest {
    /** An empty chunk is one poll with no data. Only exhaustion of all chunks sends EOF. */
    static final class ScriptedPipe implements MetadataPipeReader.Input {
        private final byte[][] chunks;
        private int next;
        private int offset;
        int emptyPolls;
        boolean sentEof;

        ScriptedPipe(String... chunks) {
            this.chunks = new byte[chunks.length][];
            for (int i = 0; i < chunks.length; i++) {
                this.chunks[i] = chunks[i].getBytes(StandardCharsets.UTF_8);
            }
        }

        public int read(byte[] target, int start, int limit, int waitMillis) {
            assertFalse("Read after EOF", sentEof);
            assertTrue(limit > 0 && start >= 0 && start + limit <= target.length);
            if (next == chunks.length) {
                sentEof = true;
                return -1;
            }
            byte[] chunk = chunks[next];
            int count = Math.min(limit, chunk.length - offset);
            System.arraycopy(chunk, offset, target, start, count);
            offset += count;
            if (offset == chunk.length) {
                next++;
                offset = 0;
            }
            if (count == 0) emptyPolls++;
            return count;
        }
    }

    @Test public void commandAlreadyExitedButPipeDeliversTailAfterTwoEmptyPolls() throws Exception {
        var pipe = new ScriptedPipe("head\n", "", "", "mTopFocusedDisplayId=0\n");
        var reader = new MetadataPipeReader();
        byte[] dest = new byte[100];
        var result = reader.drain(dest, pipe, wait -> true, new AtomicLong()::getAndIncrement, () -> true, 100);
        assertEquals(ReviewBingNavigation.FixedStatus.READ_COMPLETE, result);
        assertEquals("head\nmTopFocusedDisplayId=0\n", new String(dest, 0, reader.count(), StandardCharsets.UTF_8));
        assertEquals(2, pipe.emptyPolls);
        assertTrue(pipe.sentEof && reader.eof() && reader.exited());
    }

    @Test public void eofAloneDoesNotCompleteWhileProcessStillRunning() throws Exception {
        var pipe = new ScriptedPipe("x");
        var reader = new MetadataPipeReader();
        var exitChecks = new AtomicInteger();
        var result = reader.drain(new byte[8], pipe, wait -> pipe.sentEof && exitChecks.incrementAndGet() == 3,
                new AtomicLong()::getAndIncrement, () -> true, 100);
        assertEquals(ReviewBingNavigation.FixedStatus.READ_COMPLETE, result);
        assertEquals(3, exitChecks.get());
        assertEquals(1, reader.count());
        assertTrue(reader.eof() && reader.exited());
    }

    @Test public void exactCapacityWaitsForEofAndOneExtraLateByteExceedsLimit() throws Exception {
        for (boolean hasExtra : new boolean[]{false, true}) {
            var pipe = hasExtra ? new ScriptedPipe("1234", "", "5") : new ScriptedPipe("1234", "");
            var reader = new MetadataPipeReader();
            byte[] dest = new byte[4];
            var result = reader.drain(dest, pipe, wait -> true, new AtomicLong()::getAndIncrement, () -> true, 100);
            assertEquals(hasExtra ? ReviewBingNavigation.FixedStatus.BUFFER_LIMIT : ReviewBingNavigation.FixedStatus.READ_COMPLETE, result);
            assertArrayEquals("1234".getBytes(StandardCharsets.UTF_8), dest);
            assertEquals(4, reader.count());
            assertEquals(!hasExtra, reader.eof());
        }
    }

    @Test public void missingEofOrMissingExitRemainsBoundedByDeadline() throws Exception {
        for (boolean sentEof : new boolean[]{false, true}) {
            var reader = new MetadataPipeReader();
            var result = reader.drain(new byte[8], (b, start, limit, wait) -> sentEof ? -1 : 0,
                    wait -> !sentEof, new AtomicLong()::getAndIncrement, () -> true, 15);
            assertEquals(ReviewBingNavigation.FixedStatus.READ_TIMEOUT, result);
            assertEquals(sentEof, reader.eof());
        }
    }

    @Test public void revocationDuringADataGapDiscardsThePartialDocument() throws Exception {
        var pipe = new ScriptedPipe("head", "", "tail");
        var reader = new MetadataPipeReader();
        var result = reader.drain(new byte[32], pipe, wait -> true, new AtomicLong()::getAndIncrement,
                () -> pipe.emptyPolls == 0, 100);
        assertEquals(ReviewBingNavigation.FixedStatus.READ_TIMEOUT, result);
        assertEquals(4, reader.count());
        assertFalse(reader.eof());
    }
}
