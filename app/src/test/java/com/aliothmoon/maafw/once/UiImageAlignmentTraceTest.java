package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.UiImageAlignmentTrace.Phase.*;
import static com.aliothmoon.maafw.once.UiImageAlignmentTrace.Check.*;
import static com.aliothmoon.maafw.once.UiImageAlignmentTrace.State.*;

/** Fake monotonic clocks and in-memory fixtures; no real screenshot, source or native run. */
public class UiImageAlignmentTraceTest {
    private static LongSupplier sequence(long... values) {
        AtomicInteger next = new AtomicInteger();
        return () -> values[next.getAndIncrement()];
    }

    @Test public void phaseStartsAndFinishesWithoutInventingTimeForUnobservedWork() {
        var trace = new UiImageAlignmentTrace(sequence(10_000_000, 25_000_000));
        assertEquals(NOT_OBSERVED, trace.sample(FRAME_COMPARE).state());
        assertEquals(-1, trace.sample(FRAME_COMPARE).elapsedMillis());
        var span = trace.begin(FRAME_COMPARE);
        assertEquals(RUNNING, trace.sample(FRAME_COMPARE).state());
        assertEquals(1, trace.sample(FRAME_COMPARE).calls());
        assertEquals(0, trace.sample(FRAME_COMPARE).finished());
        trace.end(span);
        assertEquals(MEASURED, trace.sample(FRAME_COMPARE).state());
        assertEquals(1, trace.sample(FRAME_COMPARE).finished());
        assertEquals(15, trace.sample(FRAME_COMPARE).elapsedMillis());
        assertEquals(NOT_OBSERVED, trace.sample(FRAME_HASH).state());
    }

    @Test public void wrappersCallOriginalOnceEveryTimeAndNeverCacheItsBoolean() {
        AtomicInteger actual = new AtomicInteger(), clock = new AtomicInteger();
        var trace = new UiImageAlignmentTrace(() -> clock.getAndIncrement() * 1_000_000L);
        BooleanSupplier checked = trace.watch(SOURCE_CURRENT, () -> actual.incrementAndGet() % 2 == 0);
        assertFalse(checked.getAsBoolean()); assertTrue(checked.getAsBoolean()); assertFalse(checked.getAsBoolean());
        assertEquals(3, actual.get()); assertEquals(6, clock.get());
        assertEquals(3, trace.sample(SOURCE_CURRENT).calls());
        assertEquals(3, trace.sample(SOURCE_CURRENT).finished());
        assertEquals(3, trace.sample(SOURCE_CURRENT).elapsedMillis());
    }

    @Test public void originalShortCircuitAndInvocationOrderRemainUnchanged() {
        for (boolean cancel : new boolean[]{true, false}) {
            List<String> calls = new ArrayList<>();
            var trace = new UiImageAlignmentTrace(() -> 7);
            var stopping = trace.watch(CANCEL, () -> { calls.add("cancel"); return cancel; });
            var revoked = trace.watch(SOURCE_CURRENT, () -> { calls.add("source"); return true; });
            assertTrue(stopping.getAsBoolean() || revoked.getAsBoolean());
            assertEquals(cancel ? List.of("cancel") : List.of("cancel", "source"), calls);
            assertEquals(1, trace.sample(CANCEL).calls());
            assertEquals(cancel ? 0 : 1, trace.sample(SOURCE_CURRENT).calls());
        }
    }

    @Test public void originalExceptionEscapesUnchangedAndFinallyRecordsTheInvocation() {
        var trace = new UiImageAlignmentTrace(sequence(1_000_000, 4_000_000));
        SecurityException expected = new SecurityException("synthetic original failure");
        var checked = trace.watch(SOURCE_CURRENT, () -> { throw expected; });
        assertSame(expected, assertThrows(SecurityException.class, checked::getAsBoolean));
        assertEquals(1, trace.sample(SOURCE_CURRENT).calls());
        assertEquals(1, trace.sample(SOURCE_CURRENT).finished());
        assertEquals(3, trace.sample(SOURCE_CURRENT).elapsedMillis());
    }

    @Test public void originalErrorIsNotMaskedEvenWhenBothClockReadsFail() {
        var trace = new UiImageAlignmentTrace(() -> { throw new AssertionError("synthetic clock failure"); });
        AssertionError expected = new AssertionError("synthetic original error");
        AtomicInteger calls = new AtomicInteger();
        var checked = trace.watch(CANCEL, () -> { calls.incrementAndGet(); throw expected; });
        assertSame(expected, assertThrows(AssertionError.class, checked::getAsBoolean));
        assertEquals(1, calls.get()); assertEquals(1, trace.sample(CANCEL).finished());
        assertEquals(INVALID_CLOCK, trace.sample(CANCEL).state());
    }

    @Test public void eitherClockReadMayThrowWithoutChangingTrueOrFalse() {
        for (int failingRead : new int[]{1, 2}) for (boolean outcome : new boolean[]{false, true}) {
            AtomicInteger clockCalls = new AtomicInteger(), actualCalls = new AtomicInteger();
            var trace = new UiImageAlignmentTrace(() -> {
                int call = clockCalls.incrementAndGet();
                if (call == failingRead) throw new IllegalStateException("synthetic clock failure");
                return call;
            });
            var checked = trace.watch(SOURCE_CURRENT, () -> { actualCalls.incrementAndGet(); return outcome; });
            assertEquals(outcome, checked.getAsBoolean()); assertEquals(1, actualCalls.get());
            assertEquals(2, clockCalls.get()); assertEquals(1, trace.sample(SOURCE_CURRENT).finished());
            assertEquals(INVALID_CLOCK, trace.sample(SOURCE_CURRENT).state());
            assertEquals(-1, trace.sample(SOURCE_CURRENT).elapsedMillis());
        }
    }

    @Test public void phaseClockFailureInvalidatesOnlyTimingAndDoesNotEscape() {
        for (boolean error : new boolean[]{false, true}) {
            var trace = new UiImageAlignmentTrace(() -> {
                if (error) throw new AssertionError("synthetic clock error");
                throw new IllegalStateException("synthetic clock exception");
            });
            var span = trace.begin(NATIVE_SETUP); trace.end(span);
            assertEquals(1, trace.sample(NATIVE_SETUP).calls());
            assertEquals(1, trace.sample(NATIVE_SETUP).finished());
            assertEquals(INVALID_CLOCK, trace.sample(NATIVE_SETUP).state());
        }
    }

    @Test public void backwardClockIsInvalidButSignedOriginAndNormalWrapAreAllowed() {
        var backwards = new UiImageAlignmentTrace(sequence(30, 20));
        backwards.end(backwards.begin(FRAME_HASH));
        assertEquals(INVALID_CLOCK, backwards.sample(FRAME_HASH).state());
        var negativeOrigin = new UiImageAlignmentTrace(sequence(-20, -5));
        negativeOrigin.end(negativeOrigin.begin(FRAME_HASH));
        assertEquals(MEASURED, negativeOrigin.sample(FRAME_HASH).state());
        assertEquals(15, negativeOrigin.sample(FRAME_HASH).totalNanos());
        var wrapped = new UiImageAlignmentTrace(sequence(Long.MAX_VALUE - 4, Long.MIN_VALUE + 5));
        wrapped.end(wrapped.begin(FRAME_HASH));
        assertEquals(MEASURED, wrapped.sample(FRAME_HASH).state());
        assertEquals(10, wrapped.sample(FRAME_HASH).totalNanos());
    }

    @Test public void accumulatedTimeIsCappedWithoutOverflowOrPretendingItIsExact() {
        var trace = new UiImageAlignmentTrace(sequence(0, UiImageAlignmentTrace.MAX_NANOS - 1,
                0, Long.MAX_VALUE));
        var checked = trace.watch(CANCEL, () -> false);
        assertFalse(checked.getAsBoolean()); assertFalse(checked.getAsBoolean());
        assertEquals(UiImageAlignmentTrace.MAX_NANOS, trace.sample(CANCEL).totalNanos());
        assertEquals(600_000, trace.sample(CANCEL).elapsedMillis());
        assertEquals(SATURATED, trace.sample(CANCEL).state());
        assertTrue(trace.sample(CANCEL).timeSaturated());
        assertEquals(2, trace.sample(CANCEL).calls());
    }

    @Test public void callCountIsBoundedWhileEveryOriginalInvocationStillOccurs() {
        var trace = new UiImageAlignmentTrace(() -> 1);
        AtomicInteger actual = new AtomicInteger();
        var checked = trace.watch(CANCEL, () -> { actual.incrementAndGet(); return false; });
        for (long i = 0; i <= UiImageAlignmentTrace.MAX_CALLS; i++) checked.getAsBoolean();
        assertEquals(UiImageAlignmentTrace.MAX_CALLS + 1, actual.get());
        assertEquals(UiImageAlignmentTrace.MAX_CALLS, trace.sample(CANCEL).calls());
        assertEquals(UiImageAlignmentTrace.MAX_CALLS, trace.sample(CANCEL).finished());
        assertEquals(SATURATED, trace.sample(CANCEL).state());
        assertTrue(trace.sample(CANCEL).countSaturated());
    }

    @Test public void readingDiagnosticsDoesNotReadClockOrInvokeOriginalAgain() {
        AtomicInteger clockCalls = new AtomicInteger(), actualCalls = new AtomicInteger();
        var trace = new UiImageAlignmentTrace(() -> clockCalls.incrementAndGet());
        trace.watch(SOURCE_CURRENT, () -> { actualCalls.incrementAndGet(); return false; }).getAsBoolean();
        var sample = trace.sample(SOURCE_CURRENT); String summary = trace.summary();
        assertEquals(sample, trace.sample(SOURCE_CURRENT)); assertEquals(summary, trace.summary());
        assertEquals(2, clockCalls.get()); assertEquals(1, actualCalls.get());
        assertTrue(summary.contains("supplierScope=CAPTURE_SUPPLIERS_ONLY"));
        assertTrue(summary.matches("[A-Za-z0-9_= .,:;()\\[\\]/+\\-]+"));
        assertTrue(summary.length() < 4000);
    }

    @Test public void summaryUsesUniqueFlatScalarFieldsCompatibleWithBindingEvidence() {
        var trace = new UiImageAlignmentTrace(sequence(1_000_000, 3_000_000, 4_000_000, 7_000_000));
        trace.end(trace.begin(NATIVE_SETUP));
        assertFalse(trace.watch(SOURCE_CURRENT, () -> false).getAsBoolean());
        String summary = trace.summary();
        var keys = new java.util.HashSet<String>();
        for (String field : summary.split(" ")) {
            assertTrue(field, field.matches("[A-Za-z][A-Za-z0-9]*=(?:[A-Z_]+|true|false|-?[0-9]+)"));
            assertTrue("Duplicate scalar key", keys.add(field.substring(0, field.indexOf('='))));
        }
        assertTrue(summary.contains("tNativeSetupCalls=1 tNativeSetupFinished=1 tNativeSetupMs=2 tNativeSetupState=MEASURED"));
        assertTrue(summary.contains("tCheckSourceCurrentCalls=1 tCheckSourceCurrentFinished=1 tCheckSourceCurrentMs=3"));
        assertEquals(4 + 6 * (UiImageAlignmentTrace.Phase.values().length + UiImageAlignmentTrace.Check.values().length), keys.size());
    }

    @Test public void longestBoundedScalarValuesKeepTheTraceWithinItsReceiptBudget() {
        String summary = new UiImageAlignmentTrace(() -> 0).summary();
        StringBuilder worst = new StringBuilder();
        int longestState = 0;
        for (var state : UiImageAlignmentTrace.State.values()) longestState = Math.max(longestState, state.name().length());
        for (String field : summary.split(" ")) {
            int equals = field.indexOf('='); String key = field.substring(0, equals);
            String value = field.substring(equals + 1);
            if (key.endsWith("Calls") || key.endsWith("Finished")) value = Long.toString(UiImageAlignmentTrace.MAX_CALLS);
            else if (key.endsWith("Ms")) value = "X".repeat(Math.max("NOT_OBSERVED".length(),
                    Long.toString(UiImageAlignmentTrace.MAX_NANOS / 1_000_000L).length()));
            else if (key.endsWith("State")) value = "X".repeat(longestState);
            else if (value.equals("true") || value.equals("false")) value = "false";
            if (worst.length() > 0) worst.append(' ');
            worst.append(key).append('=').append(value);
        }
        // Reserve at least half of the 8000-character history for existing source/title/runtime evidence.
        assertTrue("Fixed trace must not exhaust the shared receipt budget", worst.length() < 4000);
    }

    @Test public void duplicateAndForeignSpanCannotDoubleCountOrInventCompletion() {
        var first = new UiImageAlignmentTrace(() -> 1);
        var other = new UiImageAlignmentTrace(() -> 2);
        var span = first.begin(FRAME_HASH);
        other.end(span);
        assertEquals(RUNNING, first.sample(FRAME_HASH).state());
        assertEquals(INVALID_USE, other.sample(FRAME_HASH).state());
        first.end(span); first.end(span);
        assertEquals(1, first.sample(FRAME_HASH).calls());
        assertEquals(1, first.sample(FRAME_HASH).finished());
        assertEquals(INVALID_USE, first.sample(FRAME_HASH).state());
    }

    @Test public void optionalNullSupplierAndNullSpanRemainUnobserved() {
        AtomicInteger clocks = new AtomicInteger();
        var trace = new UiImageAlignmentTrace(() -> clocks.incrementAndGet());
        assertNull(trace.watch(CANCEL, null)); trace.end(null);
        assertEquals(0, clocks.get()); assertEquals(NOT_OBSERVED, trace.sample(CANCEL).state());
    }

    @Test public void captureSeparatesComparisonHashRefinementAndPostPrepareTail() {
        try (var f = new UiImageAlignmentCaptureTest.Fixture()) {
            var trace = f.capture.diagnosticsTrace();
            assertNotNull(f.prepare());
            for (var phase : List.of(FRAME_COMPARE, FRAME_HASH, REFINER)) {
                assertEquals(1, trace.sample(phase).calls());
                assertEquals(1, trace.sample(phase).finished());
            }
            assertEquals(RUNNING, trace.sample(CANDIDATE_TO_BACKEND_RETURN).state());
            var cancel = trace.sample(CANCEL); var source = trace.sample(SOURCE_CURRENT);
            f.capture.backendReturned();
            assertEquals(1, trace.sample(CANDIDATE_TO_BACKEND_RETURN).finished());
            assertEquals(cancel, trace.sample(CANCEL)); assertEquals(source, trace.sample(SOURCE_CURRENT));
            f.capture.diagnostics(); f.capture.diagnostics();
            assertEquals(cancel, trace.sample(CANCEL)); assertEquals(source, trace.sample(SOURCE_CURRENT));
            assertNull(f.prepare());
            assertEquals(1, trace.sample(CANDIDATE_TO_BACKEND_RETURN).calls());
        }
    }

    @Test public void failedByteComparisonStillHasATailButNoHashOrRefinerTiming() {
        try (var f = new UiImageAlignmentCaptureTest.Fixture()) {
            var trace = f.capture.diagnosticsTrace(); f.gray[17] = 1;
            assertNull(f.prepare());
            assertEquals(1, trace.sample(FRAME_COMPARE).finished());
            assertEquals(NOT_OBSERVED, trace.sample(FRAME_HASH).state());
            assertEquals(NOT_OBSERVED, trace.sample(REFINER).state());
            assertEquals(RUNNING, trace.sample(CANDIDATE_TO_BACKEND_RETURN).state());
            assertEquals(2, trace.sample(CANCEL).calls());
            assertEquals(2, trace.sample(SOURCE_CURRENT).calls());
            f.capture.backendReturned();
            assertEquals(1, trace.sample(CANDIDATE_TO_BACKEND_RETURN).finished());
            assertTrue(f.capture.diagnostics().contains("prepareGate=PIXEL_MISMATCH"));
        }
    }

    @Test public void earlyIdentityRejectionIsTimedWithoutCallingAnySupplier() {
        try (var f = new UiImageAlignmentCaptureTest.Fixture()) {
            var trace = f.capture.diagnosticsTrace();
            assertNull(f.capture.prepare(f.recognizer, f.owner, null, f.gray, List.of(), f.cancelled::get, 2600));
            assertEquals(0, trace.sample(CANCEL).calls()); assertEquals(0, trace.sample(SOURCE_CURRENT).calls());
            assertEquals(NOT_OBSERVED, trace.sample(FRAME_COMPARE).state());
            assertEquals(RUNNING, trace.sample(CANDIDATE_TO_BACKEND_RETURN).state());
            f.capture.backendReturned();
            assertEquals(1, trace.sample(CANDIDATE_TO_BACKEND_RETURN).finished());
        }
    }
}
