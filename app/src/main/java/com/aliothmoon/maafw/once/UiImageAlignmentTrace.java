package com.aliothmoon.maafw.once;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Bounded observation only. No source result, source/cancel supplier, frame, text or authority is retained. */
final class UiImageAlignmentTrace {
    enum Phase {
        NATIVE_SETUP, IMAGE_ENCODING_AND_AUDIT, NATIVE_OCR, OCR_DETAIL_AND_IMAGE_AUDIT,
        FRAME_COPY_AND_GEOMETRY_AUDIT, FRAME_COMPARE, FRAME_HASH, REFINER,
        SEARCH_THRESHOLD, SEARCH_GEOMETRY, SEARCH_TITLE,
        READING_THRESHOLD, READING_GEOMETRY, READING_TITLE,
        CANDIDATE_TO_BACKEND_RETURN, IMAGE_AFTER
    }
    /** Only suppliers invoked by Capture are counted, not all runtime source checks. */
    enum Check { CANCEL, SOURCE_CURRENT }
    enum State { NOT_OBSERVED, RUNNING, MEASURED, INVALID_CLOCK, INVALID_USE, SATURATED }
    static final long MAX_CALLS = 1_000_000L;
    static final long MAX_NANOS = 600_000_000_000L;

    record Sample(long calls, long finished, long totalNanos, State state,
                  boolean countSaturated, boolean timeSaturated) {
        long elapsedMillis() {
            return state == State.MEASURED || state == State.SATURATED ? totalNanos / 1_000_000L : -1;
        }
    }
    static final class Span {
        private final UiImageAlignmentTrace owner;
        private final int index;
        private final long start;
        private final boolean clockValid;
        private boolean ended;
        private Span(UiImageAlignmentTrace owner, int index, long start, boolean clockValid) {
            this.owner = owner; this.index = index; this.start = start; this.clockValid = clockValid;
        }
    }

    private final LongSupplier clock;
    private final long[] calls = new long[Phase.values().length + Check.values().length];
    private final long[] finished = new long[calls.length], nanos = new long[calls.length];
    private final boolean[] invalidClock = new boolean[calls.length], invalidUse = new boolean[calls.length];
    private final boolean[] countSaturated = new boolean[calls.length], timeSaturated = new boolean[calls.length];
    private boolean invalidInvocation;

    /** The supplied monotonic clock is not read during construction. */
    UiImageAlignmentTrace(LongSupplier nanoClock) { clock = Objects.requireNonNull(nanoClock); }

    Span begin(Phase phase) {
        if (phase == null) { badInvocation(); return null; }
        int index = phase.ordinal(); started(index);
        long start = 0; boolean valid = false;
        try { start = clock.getAsLong(); valid = true; }
        catch (RuntimeException | Error unavailable) { /* Diagnostics never change the task outcome. */ }
        return new Span(this, index, start, valid);
    }

    /** Null permits optional instrumentation; a duplicate/foreign span invalidates diagnostics only. */
    void end(Span span) {
        if (span == null) return;
        if (span.owner != this) { badInvocation(); return; }
        synchronized (span) {
            if (span.ended) { badUse(span.index); return; }
            span.ended = true;
        }
        long end = 0; boolean valid = false;
        try { end = clock.getAsLong(); valid = true; }
        catch (RuntimeException | Error unavailable) { /* Keep the original outcome or exception. */ }
        ended(span.index, span.clockValid && valid, end - span.start);
    }

    /**
     * One original invocation, in its original caller position, including its exact exception.
     * Clock exceptions are swallowed separately. No timing object is allocated per check.
     * A null supplier remains null so the existing stopped(null) rule is unchanged.
     */
    BooleanSupplier watch(Check check, BooleanSupplier original) {
        if (original == null) return null;
        if (check == null) { badInvocation(); return original; }
        int index = Phase.values().length + check.ordinal();
        return () -> {
            started(index);
            long start = 0; boolean startValid = false;
            try { start = clock.getAsLong(); startValid = true; }
            catch (RuntimeException | Error unavailable) { /* Still invoke the original exactly once. */ }
            try {
                return original.getAsBoolean();
            } finally {
                long end = 0; boolean endValid = false;
                try { end = clock.getAsLong(); endValid = true; }
                catch (RuntimeException | Error unavailable) { /* Never mask the original exception. */ }
                ended(index, startValid && endValid, end - start);
            }
        };
    }

    synchronized Sample sample(Phase phase) { return sampleAt(phase.ordinal()); }
    synchronized Sample sample(Check check) { return sampleAt(Phase.values().length + check.ordinal()); }

    /** Fixed enum names, bounded counts/times and validity only. Reading it never reads the clock. */
    synchronized String summary() {
        StringBuilder out = new StringBuilder("alignmentTiming=OBSERVATION_ONLY supplierScope=CAPTURE_SUPPLIERS_ONLY timingNesting=INCLUSIVE");
        boolean usesValid = !invalidInvocation;
        for (boolean invalid : invalidUse) usesValid &= !invalid;
        out.append(" timingUseValid=").append(usesValid);
        for (Phase phase : Phase.values()) append(out, phase.name(), sampleAt(phase.ordinal()));
        for (Check check : Check.values()) append(out, "CHECK_" + check.name(), sampleAt(Phase.values().length + check.ordinal()));
        return out.toString();
    }

    private static void append(StringBuilder out, String name, Sample sample) {
        String prefix = "t" + pascalCase(name);
        out.append(' ').append(prefix).append("Calls=").append(sample.calls())
                .append(' ').append(prefix).append("Finished=").append(sample.finished())
                .append(' ').append(prefix).append("Ms=");
        if (sample.elapsedMillis() < 0) out.append("NOT_OBSERVED"); else out.append(sample.elapsedMillis());
        out.append(' ').append(prefix).append("State=").append(sample.state())
                .append(' ').append(prefix).append("CountCapped=").append(sample.countSaturated())
                .append(' ').append(prefix).append("TimeCapped=").append(sample.timeSaturated());
    }

    /** Input is a fixed internal enum name, never observed text or a caller-provided field key. */
    private static String pascalCase(String name) {
        StringBuilder result = new StringBuilder(name.length());
        boolean initial = true;
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (value == '_') initial = true;
            else { result.append(initial ? value : Character.toLowerCase(value)); initial = false; }
        }
        return result.toString();
    }

    private synchronized void started(int index) {
        if (calls[index] < MAX_CALLS) calls[index]++; else countSaturated[index] = true;
    }
    private synchronized void ended(int index, boolean clockValid, long elapsed) {
        if (finished[index] < MAX_CALLS) finished[index]++; else countSaturated[index] = true;
        // nanoTime's arbitrary signed origin (and ordinary wraparound) is valid; its difference matters.
        if (!clockValid || elapsed < 0) { invalidClock[index] = true; return; }
        if (elapsed > MAX_NANOS - nanos[index]) { nanos[index] = MAX_NANOS; timeSaturated[index] = true; }
        else nanos[index] += elapsed;
    }
    private Sample sampleAt(int index) {
        State state = invalidInvocation || invalidUse[index] ? State.INVALID_USE
                : invalidClock[index] ? State.INVALID_CLOCK
                : countSaturated[index] || timeSaturated[index] ? State.SATURATED
                : calls[index] == 0 ? State.NOT_OBSERVED
                : calls[index] != finished[index] ? State.RUNNING : State.MEASURED;
        return new Sample(calls[index], finished[index], nanos[index], state, countSaturated[index], timeSaturated[index]);
    }
    private synchronized void badUse(int index) { invalidUse[index] = true; }
    private synchronized void badInvocation() { invalidInvocation = true; }
}
