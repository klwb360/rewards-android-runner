package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Drains one bounded metadata document before checking its producer's exit. */
final class MetadataPipeReader {
    private static final int POLL_SLICE_MS = 25;

    interface Input {
        /** Positive bytes, zero on temporary unavailability, or -1 on actual EOF. */
        int read(byte[] target, int offset, int length, int waitMillis) throws Exception;
    }

    interface Exit {
        boolean await(int waitMillis) throws Exception;
    }

    private int count;
    private boolean eof;
    private boolean exited;

    int count() { return count; }
    boolean eof() { return eof; }
    boolean exited() { return exited; }

    ReviewBingNavigation.FixedStatus drain(byte[] target, Input input, Exit exit,
            LongSupplier clock, BooleanSupplier current, long deadline) throws Exception {
        byte[] overflow = new byte[1];
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) {
                    return ReviewBingNavigation.FixedStatus.READ_TIMEOUT;
                }
                long remaining = deadline - clock.getAsLong();
                if (remaining <= 0) return ReviewBingNavigation.FixedStatus.READ_TIMEOUT;
                if (exited) return ReviewBingNavigation.FixedStatus.READ_COMPLETE;
                int waitMillis = (int) Math.min(POLL_SLICE_MS, remaining);
                if (eof) {
                    exited = exit.await(waitMillis);
                    continue;
                }

                // A full buffer can still be a valid document. Probe for EOF with a positive length.
                int room = target.length - count;
                int length = room == 0 ? 1 : room;
                int n = room == 0 ? input.read(overflow, 0, length, waitMillis)
                        : input.read(target, count, length, waitMillis);
                if (n < -1 || n > length) throw new IOException("Invalid metadata pipe read size");
                if (n < 0) {
                    eof = true;
                } else if (n > room) {
                    return ReviewBingNavigation.FixedStatus.BUFFER_LIMIT;
                } else {
                    count += n;
                }
            }
        } finally {
            overflow[0] = 0;
        }
    }
}
