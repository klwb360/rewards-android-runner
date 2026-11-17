package com.aliothmoon.maafw.once;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Waits for completely fresh, strictly authenticated metadata for a read-only UI observation. */
final class LiveDisplayRecovery {
    static final class MissingDisplay extends SecurityException {
        private static final long serialVersionUID = 1L;
        MissingDisplay() { super("Display ID missing on the exact original Rewards source"); }
    }

    @FunctionalInterface
    interface Pause { void sleep(long millis) throws InterruptedException; }

    private static final long[] OFFSETS_MS = {750L, 2000L};

    static BingNavigationSnapshot.Ready afterMissingDisplay(BingNavigationSnapshot.Ready anchor,
            BooleanSupplier current, LongSupplier elapsed, Pause pause, Supplier<BingNavigationSnapshot.Ready> read) {
        long start = elapsed.getAsLong();
        if (anchor == null || start < 0 || start > Long.MAX_VALUE - OFFSETS_MS[1]) {
            throw new SecurityException("A current run anchor and clock are required");
        }
        long previous = start;
        for (long offset : OFFSETS_MS) {
            long due = start + offset;
            checkCurrent(current);
            long now = elapsed.getAsLong();
            if (now < previous) throw new SecurityException("Monotonic clock moved backwards");
            if (now < due) {
                try {
                    pause.sleep(due - now);
                } catch (InterruptedException canceled) {
                    Thread.currentThread().interrupt();
                    throw new SecurityException("Focus recheck interrupted", canceled);
                }
            }
            checkCurrent(current);
            long readStart = elapsed.getAsLong();
            if (readStart < due || readStart < now) throw new SecurityException("Focus recheck is not due");
            try {
                var fresh = read.get();
                checkCurrent(current);
                long readEnd = elapsed.getAsLong();
                if (!anchor.sameSource(fresh) || fresh.lower() < readStart || !fresh.fresh(readEnd)) {
                    throw new SecurityException("Resampled source is stale or differs from the original");
                }
                return fresh;
            } catch (MissingDisplay stillMissing) {
                // Only this same, fully classified gap allows the second metadata pair.
                previous = elapsed.getAsLong();
                if (previous < readStart) throw new SecurityException("Monotonic clock moved backwards");
            }
        }
        throw new SecurityException("Display ID still missing after two metadata pairs");
    }

    private static void checkCurrent(BooleanSupplier current) {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) {
            throw new SecurityException("Scope expired or changed while waiting for display metadata");
        }
    }

    private LiveDisplayRecovery() {}
}
