package com.aliothmoon.maafw.once;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.NativeUiRegions.Role.*;
import static com.aliothmoon.maafw.once.UiTitleDiagnostic.Kind.*;
import static com.aliothmoon.maafw.once.UiTitleDiagnostic.Anchor.*;
import static com.aliothmoon.maafw.once.UiTitleDiagnostic.Surround.*;
import static com.aliothmoon.maafw.once.UiTitleDiagnostic.SummaryOverlap.*;
import static com.aliothmoon.maafw.once.UiRegionImageRefiner.Status.*;

/** Generated pixels and supplied rows only; no Android capture, native OCR, or task authority. */
public class UiTitleDiagnosticTest {
    private static final int W = 160, H = 180;
    private static final String SEARCH_TEXT = "搜索以赚取", READING_TEXT = "阅读以赚取";
    private static final NativeUiRegions.Rect COARSE = rect(10, 10, 150, 80);
    private static final NativeUiRegions.Rect PIXEL = rect(20, 20, 140, 70);
    private static final NativeUiRegions.Rect TITLE = rect(28, 28, 88, 38);
    private static final NativeUiRegions.Rect SUMMARY = rect(110, 22, 136, 38);

    private static NativeUiRegions.Rect rect(int l, int t, int r, int b) {
        return new NativeUiRegions.Rect(l, t, r, b);
    }
    private static FrameStructureEvidence.Line row(String text, int x, int y, int width, int height, double score) {
        return new FrameStructureEvidence.Line(text, x, y, width, height, score);
    }
    private static NativeUiRegions.Pair regions() {
        return new NativeUiRegions.Pair(new NativeUiRegions.Region(SEARCH, COARSE, SUMMARY),
                new NativeUiRegions.Region(READING, rect(10, 90, 150, 170), null));
    }
    private static byte[] pixels() {
        byte[] pixels = new byte[W * H];
        Arrays.fill(pixels, (byte) 240);
        for (var box : List.of(PIXEL, rect(20, 100, 140, 160))) {
            for (int y = box.top(); y < box.bottom(); y++) {
                Arrays.fill(pixels, y * W + box.left(), y * W + box.right(), (byte) 247);
            }
        }
        return pixels;
    }
    private static List<FrameStructureEvidence.Line> rows(String search) {
        return List.of(row(search, 28, 28, 100, 10, .98), row(READING_TEXT, 28, 108, 60, 10, .98));
    }
    private static UiTitleDiagnostic.Sample classify(String text) {
        return UiTitleDiagnostic.classify(SEARCH, text, TITLE, PIXEL, null);
    }
    private static UiRegionImageRefiner.Result observe(List<FrameStructureEvidence.Line> rows) {
        return UiRegionImageRefiner.observe(regions(), pixels(), W, H, rows, () -> false);
    }
    private static void held(UiRegionImageRefiner.Status status, UiRegionImageRefiner.Result result) {
        assertEquals(status, result.search().status());
        assertNull(result.search().cardBoundary());
        assertNull(result.search().textBounds());
    }

    @Test public void exactTitleCountsAreRelativeToTheExpectedRole() {
        for (var role : NativeUiRegions.Role.values()) {
            String expected = role == SEARCH ? SEARCH_TEXT : READING_TEXT;
            var sample = UiTitleDiagnostic.classify(role, expected, TITLE, PIXEL, null);
            assertEquals(EXACT_EXPECTED, sample.kind());
            assertEquals(1, sample.rowExpectedCount()); assertEquals(0, sample.rowOppositeCount());
            assertEquals(EXPECTED, sample.anchor());
            assertEquals(UiTitleDiagnostic.Surround.NONE, sample.prefix());
            assertEquals(UiTitleDiagnostic.Surround.NONE, sample.suffix());
            assertTrue(sample.insidePixelBoundary()); assertTrue(sample.upperLeft());
            assertEquals(NOT_AVAILABLE, sample.overlapsUiSummary());
        }
    }

    @Test public void whitespaceIsExplainedWithoutNormalizingTheRecognitionInput() {
        for (String whitespace : List.of(" ", "\t\n", "\u00a0", "\u3000")) {
            String text = whitespace + SEARCH_TEXT + whitespace;
            var sample = classify(text);
            assertEquals(EXPECTED_EMBEDDED, sample.kind());
            assertEquals(WHITESPACE, sample.prefix()); assertEquals(WHITESPACE, sample.suffix());
            assertEquals(1, sample.rowExpectedCount());
            var result = observe(rows(text));
            held(UNSUPPORTED_TITLE, result);
            assertEquals(WHITESPACE, result.searchTitleDiagnostic().prefix());
            assertEquals(WHITESPACE, result.searchTitleDiagnostic().suffix());
        }
        assertEquals(OTHER, classify("\u200b" + SEARCH_TEXT).prefix());
        assertEquals(OTHER, classify(SEARCH_TEXT + "\u0000").suffix());
    }

    @Test public void prefixSuffixAndOppositeRoleContentStaySeparateFixedClassifications() {
        assertEquals(OTHER, classify("private-prefix " + SEARCH_TEXT).prefix());
        assertEquals(UiTitleDiagnostic.Surround.NONE, classify("private-prefix " + SEARCH_TEXT).suffix());
        var extra = classify(SEARCH_TEXT + " 17/60");
        assertEquals(EXPECTED_EMBEDDED, extra.kind());
        assertEquals(OTHER, extra.suffix());
        var opposite = classify(READING_TEXT + " private-suffix");
        assertEquals(OPPOSITE_EMBEDDED, opposite.kind());
        assertEquals(OPPOSITE, opposite.anchor());
        assertEquals(0, opposite.rowExpectedCount()); assertEquals(1, opposite.rowOppositeCount());
        assertEquals(UiTitleDiagnostic.Surround.NONE, opposite.prefix()); assertEquals(OTHER, opposite.suffix());
    }

    @Test public void repeatedLiteralsAreCappedAtTwoWithoutPublishingTheirText() {
        var sample = classify(READING_TEXT.repeat(3) + SEARCH_TEXT.repeat(50));
        assertEquals(BOTH_ROLES, sample.kind());
        assertEquals(2, sample.rowExpectedCount()); assertEquals(2, sample.rowOppositeCount());
        assertEquals(EXPECTED, sample.anchor());
        assertEquals(OTHER, sample.prefix()); assertEquals(OTHER, sample.suffix());
        for (String literal : List.of(SEARCH_TEXT, READING_TEXT)) assertFalse(sample.toString().contains(literal));
    }

    @Test public void splitOrUnknownTextDoesNotBecomeALiteralByJoiningOrGuessing() {
        for (String text : List.of("搜索 以赚取", "Search to earn", "unknown private value", "")) {
            var sample = classify(text);
            assertEquals(NO_LITERAL, sample.kind());
            assertEquals(0, sample.rowExpectedCount()); assertEquals(0, sample.rowOppositeCount());
            assertEquals(UiTitleDiagnostic.Anchor.NONE, sample.anchor());
            assertEquals(NOT_APPLICABLE, sample.prefix()); assertEquals(NOT_APPLICABLE, sample.suffix());
        }
    }

    @Test public void positionDiagnosticCannotInventAnInsideOrUpperLeftTitle() {
        var outside = UiTitleDiagnostic.classify(SEARCH, SEARCH_TEXT, rect(28, 12, 88, 18), PIXEL, null);
        assertFalse(outside.insidePixelBoundary()); assertFalse(outside.upperLeft());
        var lower = UiTitleDiagnostic.classify(SEARCH, SEARCH_TEXT, rect(28, 55, 88, 65), PIXEL, null);
        assertTrue(lower.insidePixelBoundary()); assertFalse(lower.upperLeft());
        var right = UiTitleDiagnostic.classify(SEARCH, SEARCH_TEXT, rect(80, 28, 120, 38), PIXEL, null);
        assertTrue(right.insidePixelBoundary()); assertFalse(right.upperLeft());
    }

    @Test public void summaryOverlapMeansOnlyGeometryAndAbsenceIsNotReportedAsNo() {
        assertEquals(NOT_AVAILABLE, classify(SEARCH_TEXT).overlapsUiSummary());
        assertEquals(NO, UiTitleDiagnostic.classify(SEARCH, SEARCH_TEXT, TITLE, PIXEL, SUMMARY).overlapsUiSummary());
        assertEquals(YES, UiTitleDiagnostic.classify(SEARCH, SEARCH_TEXT, rect(28, 28, 128, 38), PIXEL, SUMMARY).overlapsUiSummary());
        assertEquals(NO, UiTitleDiagnostic.classify(SEARCH, SEARCH_TEXT, rect(28, 28, 110, 38), PIXEL, SUMMARY).overlapsUiSummary());
    }

    @Test public void malformedDiagnosticInputProducesNoInferredObservations() {
        assertEquals(NOT_OBSERVED, classify(null).kind());
        assertEquals(NOT_OBSERVED, classify("x".repeat(513)).kind());
        assertEquals(NOT_OBSERVED, UiTitleDiagnostic.classify(null, SEARCH_TEXT, TITLE, PIXEL, null).kind());
        assertEquals(NOT_OBSERVED, UiTitleDiagnostic.classify(SEARCH, SEARCH_TEXT, null, PIXEL, null).kind());
        assertEquals(NOT_OBSERVED, UiTitleDiagnostic.classify(SEARCH, SEARCH_TEXT, TITLE, null, null).kind());
    }

    @Test public void realRefinerBranchPublishesClassificationWithoutChangingEitherRoleOutcome() {
        var result = observe(rows(SEARCH_TEXT + " private-account-value"));
        held(UNSUPPORTED_TITLE, result);
        assertEquals(VISUAL_CANDIDATE, result.reading().status());
        var search = result.searchTitleDiagnostic();
        assertEquals(EXPECTED_EMBEDDED, search.kind());
        assertEquals(1, search.rowExpectedCount()); assertEquals(0, search.rowOppositeCount());
        assertEquals(OTHER, search.suffix()); assertTrue(search.insidePixelBoundary()); assertTrue(search.upperLeft());
        assertEquals(YES, search.overlapsUiSummary());
        assertEquals(EXACT_EXPECTED, result.readingTitleDiagnostic().kind());
        String summary = result.summary(true);
        assertTrue(summary.contains("searchTitleDiagnostic="));
        assertTrue(summary.contains("readingTitleDiagnostic="));
        assertTrue(summary.contains("actionAuthority=false"));
        for (String raw : List.of(SEARCH_TEXT, READING_TEXT, "private-account-value")) assertFalse(summary.contains(raw));
        assertFalse(result.summary(false).contains("TitleDiagnostic="));
        assertTrue(result.summary(false).contains("regions=SUPPRESSED"));
    }

    @Test public void coarseMarginTextIsExplainedButNeverPromotedIntoPixelTitleBounds() {
        var result = observe(List.of(row(SEARCH_TEXT + " suffix", 28, 12, 60, 6, .98)));
        held(UNSUPPORTED_TITLE, result);
        assertEquals(EXPECTED_EMBEDDED, result.searchTitleDiagnostic().kind());
        assertFalse(result.searchTitleDiagnostic().insidePixelBoundary());
        assertFalse(result.searchTitleDiagnostic().upperLeft());
        var exact = observe(List.of(row(SEARCH_TEXT, 28, 12, 60, 6, .98)));
        held(TITLE_OUTSIDE_BOUNDARY, exact);
        assertEquals(EXACT_EXPECTED, exact.searchTitleDiagnostic().kind());
        assertFalse(exact.searchTitleDiagnostic().insidePixelBoundary());
    }

    @Test public void earlierRejectionBranchesDoNotAcquireTitleDiagnosticsOrChangePrecedence() {
        var low = observe(List.of(row(SEARCH_TEXT + " suffix", 28, 28, 60, 10, .79)));
        held(LOW_CONFIDENCE_ROW, low); assertEquals(NOT_OBSERVED, low.searchTitleDiagnostic().kind());
        var coarseCross = observe(List.of(row(SEARCH_TEXT + " suffix", 5, 28, 60, 10, .98)));
        held(ROW_CROSSES_COARSE, coarseCross); assertEquals(NOT_OBSERVED, coarseCross.searchTitleDiagnostic().kind());
        var pixelCross = observe(List.of(row(SEARCH_TEXT + " suffix", 15, 28, 60, 10, .98)));
        held(ROW_CROSSES_BOUNDARY, pixelCross); assertEquals(NOT_OBSERVED, pixelCross.searchTitleDiagnostic().kind());
        var absent = observe(List.of());
        held(TITLE_MISSING, absent); assertEquals(NOT_OBSERVED, absent.searchTitleDiagnostic().kind());
        var duplicate = row(SEARCH_TEXT, 28, 28, 60, 10, .98);
        var ambiguous = observe(List.of(duplicate, duplicate));
        held(AMBIGUOUS_TITLE, ambiguous); assertEquals(NOT_OBSERVED, ambiguous.searchTitleDiagnostic().kind());
    }

    @Test public void wrongRoleAndEmbeddedRoleStillPreemptASeparateCorrectTitle() {
        var exact = row(SEARCH_TEXT, 28, 28, 60, 10, .98);
        var wrong = row(READING_TEXT, 28, 44, 60, 10, .98);
        var result = observe(List.of(exact, wrong));
        held(WRONG_ROLE_TITLE, result);
        assertEquals(EXACT_OPPOSITE, result.searchTitleDiagnostic().kind());
        result = observe(List.of(exact, row(READING_TEXT + " suffix", 28, 44, 60, 10, .98)));
        held(UNSUPPORTED_TITLE, result);
        assertEquals(OPPOSITE_EMBEDDED, result.searchTitleDiagnostic().kind());
    }

    @Test public void traceClockFailureLeavesResultsAndCancellationChecksUnchanged() {
        for (boolean error : new boolean[] {false, true}) {
            AtomicInteger withoutTrace = new AtomicInteger(), withTrace = new AtomicInteger();
            var expected = UiRegionImageRefiner.observe(regions(), pixels(), W, H, rows(" " + SEARCH_TEXT),
                    () -> { withoutTrace.incrementAndGet(); return false; });
            var trace = new UiImageAlignmentTrace(() -> {
                if (error) throw new AssertionError("private clock error");
                throw new IllegalStateException("private clock failure");
            });
            var actual = UiRegionImageRefiner.observe(regions(), pixels(), W, H, rows(" " + SEARCH_TEXT),
                    () -> { withTrace.incrementAndGet(); return false; }, trace);
            assertEquals(expected, actual);
            assertEquals(withoutTrace.get(), withTrace.get());
            assertFalse(actual.summary(true).contains("private clock"));
        }
    }

    @Test public void roleTimingEndsOnEarlyReturnAndDoesNotInventUnreachedPhases() {
        AtomicLong clock = new AtomicLong();
        var trace = new UiImageAlignmentTrace(() -> clock.getAndAdd(10));
        byte[] blank = new byte[W * H]; Arrays.fill(blank, (byte) 240);
        var result = UiRegionImageRefiner.observe(regions(), blank, W, H, rows(SEARCH_TEXT), () -> false, trace);
        held(NO_SURFACE_CONTRAST, result);
        assertEquals(1, trace.sample(UiImageAlignmentTrace.Phase.SEARCH_THRESHOLD).calls());
        assertEquals(1, trace.sample(UiImageAlignmentTrace.Phase.SEARCH_THRESHOLD).finished());
        assertEquals(0, trace.sample(UiImageAlignmentTrace.Phase.SEARCH_GEOMETRY).calls());
        assertEquals(0, trace.sample(UiImageAlignmentTrace.Phase.SEARCH_TITLE).calls());
        assertEquals(1, trace.sample(UiImageAlignmentTrace.Phase.READING_THRESHOLD).finished());
        trace = new UiImageAlignmentTrace(() -> clock.getAndAdd(10));
        result = UiRegionImageRefiner.observe(regions(), pixels(), W, H, rows(SEARCH_TEXT + " suffix"), () -> false, trace);
        held(UNSUPPORTED_TITLE, result);
        assertEquals(1, trace.sample(UiImageAlignmentTrace.Phase.SEARCH_TITLE).finished());
        assertEquals(1, trace.sample(UiImageAlignmentTrace.Phase.READING_TITLE).finished());
    }

    @Test public void safeSampleShapeAndLegacyResultConstructorRetainNoRawData() {
        for (var component : UiTitleDiagnostic.Sample.class.getRecordComponents()) {
            assertTrue(component.getType().isEnum() || component.getType() == int.class || component.getType() == boolean.class);
        }
        for (var component : UiRegionImageRefiner.Item.class.getRecordComponents()) {
            assertTrue(component.getType() == NativeUiRegions.Role.class || component.getType() == NativeUiRegions.Rect.class
                    || component.getType() == UiRegionImageRefiner.Status.class);
        }
        var result = observe(rows(SEARCH_TEXT));
        var legacy = new UiRegionImageRefiner.Result(result.search(), result.reading());
        assertSame(result.search(), legacy.search()); assertSame(result.reading(), legacy.reading());
        assertEquals(NOT_OBSERVED, legacy.searchTitleDiagnostic().kind());
        assertEquals(NOT_OBSERVED, legacy.readingTitleDiagnostic().kind());
    }
}
