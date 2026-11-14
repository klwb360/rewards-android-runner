package com.aliothmoon.maafw.once;

/** Diagnostic durations only. Never used for admission, freshness or deadline decisions. */
final class UiImageAlignmentTiming {
    static long elapsed(long start, long end) {
        return start < 0 || end < start ? -1 : Math.min(end - start, 600_000L);
    }
    static String value(long duration) {
        return duration < 0 ? "NOT_OBSERVED" : Long.toString(Math.min(duration, 600_000L));
    }
    private UiImageAlignmentTiming() { }
}
