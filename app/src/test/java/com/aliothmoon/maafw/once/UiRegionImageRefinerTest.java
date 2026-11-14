package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.NativeUiRegions.Role.*;
import static com.aliothmoon.maafw.once.UiRegionImageRefiner.Status.*;

public class UiRegionImageRefinerTest {
    private static final int W = 160, H = 180;
    private static final NativeUiRegions.Rect SEARCH_COARSE = rect(10, 10, 150, 80);
    private static final NativeUiRegions.Rect READING_COARSE = rect(10, 90, 150, 170);
    private static final NativeUiRegions.Rect SEARCH_PIXEL = rect(20, 20, 140, 70);
    private static final NativeUiRegions.Rect READING_PIXEL = rect(20, 100, 140, 160);

    private static NativeUiRegions.Rect rect(int l, int t, int r, int b) {
        return new NativeUiRegions.Rect(l, t, r, b);
    }

    private static NativeUiRegions.Region region(NativeUiRegions.Role role, NativeUiRegions.Rect box) {
        return new NativeUiRegions.Region(role, box, null);
    }

    private static NativeUiRegions.Pair pair() {
        return new NativeUiRegions.Pair(region(SEARCH, SEARCH_COARSE), region(READING, READING_COARSE));
    }

    private static NativeUiRegions.Pair searchOnly(NativeUiRegions.Rect coarse) {
        return new NativeUiRegions.Pair(region(SEARCH, coarse), null);
    }

    private static byte[] blank(int value) {
        byte[] pixels = new byte[W * H];
        Arrays.fill(pixels, (byte) value);
        return pixels;
    }

    private static void fill(byte[] pixels, NativeUiRegions.Rect box, int value) {
        for (int y = box.top(); y < box.bottom(); y++) {
            Arrays.fill(pixels, y * W + box.left(), y * W + box.right(), (byte) value);
        }
    }

    private static byte[] image() {
        byte[] pixels = blank(200);
        fill(pixels, SEARCH_PIXEL, 240);
        fill(pixels, READING_PIXEL, 240);
        return pixels;
    }

    private static FrameStructureEvidence.Line row(String text, int x, int y, int w, int h, double score) {
        return new FrameStructureEvidence.Line(text, x, y, w, h, score);
    }

    private static FrameStructureEvidence.Line searchTitle() { return row("搜索以赚取", 28, 28, 60, 10, .98); }
    private static FrameStructureEvidence.Line readingTitle() { return row("阅读以赚取", 28, 108, 60, 10, .98); }
    private static List<FrameStructureEvidence.Line> titles() { return List.of(searchTitle(), readingTitle()); }

    private static UiRegionImageRefiner.Result observe(NativeUiRegions.Pair regions, byte[] pixels,
                                                       List<FrameStructureEvidence.Line> rows) {
        var result = UiRegionImageRefiner.observe(regions, pixels, W, H, rows, () -> false);
        assertEquals(SEARCH, result.search().role());
        assertEquals(READING, result.reading().role());
        return result;
    }

    private static void held(UiRegionImageRefiner.Status expected, UiRegionImageRefiner.Item item) {
        assertEquals(expected, item.status());
        assertNull(item.cardBoundary());
        assertNull(item.textBounds());
    }

    @Test public void bothRolesUsePixelBoundariesAndOriginalGlobalOcrBoxes() {
        var result = observe(pair(), image(), titles());
        assertEquals(VISUAL_CANDIDATE, result.search().status());
        assertEquals(VISUAL_CANDIDATE, result.reading().status());
        assertEquals(SEARCH_COARSE, result.search().coarse());
        assertEquals(SEARCH_PIXEL, result.search().cardBoundary());
        assertEquals(READING_PIXEL, result.reading().cardBoundary());
        assertEquals(rect(28, 28, 88, 38), result.search().textBounds());
        assertEquals(rect(28, 108, 88, 118), result.reading().textBounds());
        assertNotEquals(result.search().coarse(), result.search().cardBoundary());
    }

    @Test public void absentUiSlotStaysAbsentWithoutGuessingFromPixelsOrTitles() {
        var result = observe(searchOnly(SEARCH_COARSE), image(), titles());
        assertEquals(VISUAL_CANDIDATE, result.search().status());
        held(NO_UI_REGION, result.reading());
        assertNull(result.reading().coarse());
        result = observe(NativeUiRegions.Pair.empty(), image(), titles());
        held(NO_UI_REGION, result.search());
        held(NO_UI_REGION, result.reading());
    }

    @Test public void pixelBoundaryMayEqualCoarseWhenRealExteriorPixelsSupportAllSides() {
        var result = observe(searchOnly(SEARCH_PIXEL), image(), List.of(searchTitle()));
        assertEquals(VISUAL_CANDIDATE, result.search().status());
        assertEquals(SEARCH_PIXEL, result.search().cardBoundary());
        assertEquals(result.search().coarse(), result.search().cardBoundary());
        assertNotEquals(result.search().coarse(), result.search().textBounds());
    }

    @Test public void touchingOneCoarseEdgeDoesNotMeanImageClipping() {
        var coarse = rect(20, 10, 150, 80);
        var result = observe(searchOnly(coarse), image(), List.of(searchTitle()));
        assertEquals(VISUAL_CANDIDATE, result.search().status());
        assertEquals(SEARCH_PIXEL, result.search().cardBoundary());
    }

    @Test public void coarseCuttingThroughBrightSurfaceCannotInventAClosedBorder() {
        var result = observe(searchOnly(rect(30, 20, 130, 70)), image(), List.of(searchTitle()));
        held(SURFACE_CROSSES_COARSE, result.search());
    }

    @Test public void realFrameEdgeHasNoInventedExteriorSupport() {
        byte[] pixels = blank(200);
        fill(pixels, rect(0, 20, 140, 70), 240);
        var result = observe(searchOnly(rect(0, 10, 150, 80)), pixels,
                List.of(row("搜索以赚取", 8, 28, 60, 10, .98)));
        held(FRAME_CLIPPED, result.search());
    }

    @Test public void wholeFrameCoarseCanStillExposeAnInteriorPixelBoundary() {
        byte[] pixels = blank(200);
        fill(pixels, SEARCH_PIXEL, 240);
        var result = observe(searchOnly(rect(0, 0, W, H)), pixels, List.of(searchTitle()));
        assertEquals(VISUAL_CANDIDATE, result.search().status());
        assertEquals(SEARCH_PIXEL, result.search().cardBoundary());
    }

    @Test public void perfectTitleDoesNotReplaceMissingOrWeakPixelContrast() {
        for (byte[] pixels : List.of(blank(200), blank(240))) {
            held(NO_SURFACE_CONTRAST, observe(searchOnly(SEARCH_COARSE), pixels, List.of(searchTitle())).search());
        }
        byte[] weak = blank(234);
        fill(weak, SEARCH_PIXEL, 240);
        held(NO_SURFACE_CONTRAST, observe(searchOnly(SEARCH_COARSE), weak, List.of(searchTitle())).search());
    }

    @Test public void localThresholdAcceptsNonWhiteSurfacesWithoutMoreCardAbsoluteWhiteBias() {
        byte[] pixels = blank(160);
        fill(pixels, SEARCH_PIXEL, 190);
        var result = observe(searchOnly(SEARCH_COARSE), pixels, List.of(searchTitle()));
        assertEquals(VISUAL_CANDIDATE, result.search().status());
        assertEquals(SEARCH_PIXEL, result.search().cardBoundary());
    }

    @Test public void separateSurfacesOfDifferentBrightnessRemainAmbiguousBeforeOcr() {
        byte[] pixels = blank(180);
        fill(pixels, rect(20, 20, 65, 70), 210);
        fill(pixels, rect(90, 20, 140, 70), 235);
        var result = observe(searchOnly(SEARCH_COARSE), pixels,
                List.of(row("搜索以赚取", 25, 28, 30, 10, .99)));
        held(AMBIGUOUS_BOUNDARY, result.search());
    }

    @Test public void aThinBrightLineIsNotACompleteCard() {
        byte[] pixels = blank(200);
        fill(pixels, rect(20, 20, 140, 23), 240);
        held(NO_BOUNDARY, observe(searchOnly(SEARCH_COARSE), pixels, List.of(searchTitle())).search());
    }

    @Test public void hollowBrightOutlineDoesNotPassFilledSurfaceEvidence() {
        byte[] pixels = image();
        fill(pixels, rect(30, 30, 130, 60), 200);
        held(SURFACE_NOT_FILLED, observe(searchOnly(SEARCH_COARSE), pixels, List.of(searchTitle())).search());
    }

    @Test public void aLargeMissingTopEdgeDoesNotPassOtherThreeEdges() {
        byte[] pixels = image();
        fill(pixels, rect(20, 20, 60, 23), 200);
        held(EDGE_UNSUPPORTED, observe(searchOnly(SEARCH_COARSE), pixels, List.of(searchTitle())).search());
    }

    @Test public void modestRoundedCornersRetainFourSidedCandidateEvidence() {
        byte[] pixels = image();
        for (var corner : List.of(rect(20, 20, 24, 24), rect(136, 20, 140, 24),
                rect(20, 66, 24, 70), rect(136, 66, 140, 70))) fill(pixels, corner, 200);
        var result = observe(searchOnly(SEARCH_COARSE), pixels, List.of(searchTitle()));
        assertEquals(VISUAL_CANDIDATE, result.search().status());
        assertEquals(SEARCH_PIXEL, result.search().cardBoundary());
    }

    @Test public void excessiveSmallComponentsAreBoundedWithoutPickingOne() {
        byte[] pixels = blank(200);
        for (int y = 4; y < H - 4; y += 3) for (int x = 4; x < W - 4; x += 3) pixels[y * W + x] = (byte) 240;
        held(TOO_MANY_COMPONENTS,
                observe(searchOnly(rect(1, 1, W - 1, H - 1)), pixels, List.of(searchTitle())).search());
    }

    @Test public void overlappingOrIdenticalUiSlotsAreRejectedWithoutChangingRoles() {
        for (var reading : List.of(SEARCH_COARSE, rect(30, 30, 100, 60), rect(20, 60, 150, 170))) {
            var result = observe(new NativeUiRegions.Pair(region(SEARCH, SEARCH_COARSE), region(READING, reading)), image(), titles());
            held(UI_OVERLAP, result.search());
            held(UI_OVERLAP, result.reading());
        }
    }

    @Test public void merelyAdjacentUiSlotsDoNotOverlapInHalfOpenCoordinates() {
        var regions = new NativeUiRegions.Pair(region(SEARCH, SEARCH_COARSE), region(READING, rect(10, 80, 150, 170)));
        var result = observe(regions, image(), titles());
        assertEquals(VISUAL_CANDIDATE, result.search().status());
        assertEquals(VISUAL_CANDIDATE, result.reading().status());
    }

    @Test public void invalidImageShapeAndOutOfFrameUiAreNotClippedOrRescaled() {
        for (int[] shape : new int[][] {{0, H}, {-1, H}, {W, 0}, {4097, 1}, {4096, 4096}, {W + 1, H}}) {
            var result = UiRegionImageRefiner.observe(pair(), image(), shape[0], shape[1], titles(), () -> false);
            held(INVALID_IMAGE, result.search());
            held(INVALID_IMAGE, result.reading());
        }
        held(INVALID_IMAGE, UiRegionImageRefiner.observe(pair(), null, W, H, titles(), () -> false).search());
        held(UI_OUTSIDE_FRAME, observe(searchOnly(rect(10, 10, W + 1, 80)), image(), titles()).search());
    }

    @Test public void nullRequiredArgumentsAreExplicitlyHeld() {
        held(INVALID_INPUT, UiRegionImageRefiner.observe(null, image(), W, H, titles(), () -> false).search());
        held(INVALID_INPUT, UiRegionImageRefiner.observe(pair(), image(), W, H, titles(), null).reading());
    }

    @Test public void malformedOcrAnywhereRejectsTheWholeImageProjection() {
        var invalid = new ArrayList<FrameStructureEvidence.Line>();
        invalid.add(null);
        invalid.add(row(null, 28, 28, 60, 10, .99));
        invalid.add(row("x".repeat(513), 28, 28, 60, 10, .99));
        invalid.add(row("private", 28, 28, 60, 10, Double.NaN));
        invalid.add(row("private", 28, 28, 60, 10, Double.POSITIVE_INFINITY));
        invalid.add(row("private", 28, 28, 60, 10, -1));
        invalid.add(row("private", 28, 28, 60, 10, 1.01));
        invalid.add(row("private", -1, 0, 10, 10, .99));
        invalid.add(row("private", 0, -1, 10, 10, .99));
        invalid.add(row("private", 0, 0, 0, 10, .99));
        invalid.add(row("private", 0, 0, 10, 0, .99));
        invalid.add(row("private", 150, 0, Integer.MAX_VALUE, 10, .99));
        invalid.add(row("private", 0, 175, 10, 10, .99));
        for (var bad : invalid) {
            var rows = new ArrayList<>(titles());
            rows.add(bad);
            var result = observe(pair(), image(), rows);
            held(INVALID_ROWS, result.search());
            held(INVALID_ROWS, result.reading());
        }
        held(INVALID_ROWS, observe(pair(), image(), null).search());
        var tooMany = new ArrayList<FrameStructureEvidence.Line>();
        for (int i = 0; i < 129; i++) tooMany.add(searchTitle());
        held(INVALID_ROWS, observe(pair(), image(), tooMany).search());
    }

    @Test public void validEmptyNativeRowsDoNotInventATitle() {
        held(TITLE_MISSING, observe(searchOnly(SEARCH_COARSE), image(), List.of()).search());
    }

    @Test public void lowConfidenceInsideCoarseHoldsEvenWhenExactTitleIsGood() {
        for (var low : List.of(row("搜索以赚取", 28, 28, 60, 10, .799), row("private", 35, 45, 40, 10, .2))) {
            held(LOW_CONFIDENCE_ROW,
                    observe(searchOnly(SEARCH_COARSE), image(), List.of(searchTitle(), low)).search());
        }
        assertEquals(VISUAL_CANDIDATE, observe(searchOnly(SEARCH_COARSE), image(),
                List.of(row("搜索以赚取", 28, 28, 60, 10, .8))).search().status());
    }

    @Test public void lowConfidenceOutsideCoarseCannotBeAssignedToItsCard() {
        var result = observe(searchOnly(SEARCH_COARSE), image(), List.of(searchTitle(), row("private", 0, 0, 5, 5, .1)));
        assertEquals(VISUAL_CANDIDATE, result.search().status());
    }

    @Test public void exactChineseTitleIsRequiredWithoutTrimmingJoiningOrAliasGuessing() {
        for (String title : List.of(" 搜索以赚取", "搜索以赚取 ", "搜索以赚取, private", "prefix 搜索以赚取")) {
            held(UNSUPPORTED_TITLE, observe(searchOnly(SEARCH_COARSE), image(),
                    List.of(row(title, 28, 28, 60, 10, .99))).search());
        }
        for (String title : List.of("Search to earn", "搜索赚取", "搜索", "以赚取")) {
            held(TITLE_MISSING, observe(searchOnly(SEARCH_COARSE), image(),
                    List.of(row(title, 28, 28, 60, 10, .99))).search());
        }
        held(TITLE_MISSING, observe(searchOnly(SEARCH_COARSE), image(),
                List.of(row("搜索", 28, 28, 25, 10, .99), row("以赚取", 55, 28, 30, 10, .99))).search());
    }

    @Test public void oppositeRoleTitleCannotBeRescuedByACorrectTitle() {
        var wrong = row("阅读以赚取", 28, 43, 60, 10, .99);
        for (var rows : List.of(List.of(wrong), List.of(searchTitle(), wrong), List.of(wrong, searchTitle()))) {
            held(WRONG_ROLE_TITLE, observe(searchOnly(SEARCH_COARSE), image(), rows).search());
        }
    }

    @Test public void duplicateTitleRowsStayAmbiguousEvenAtIdenticalCoordinates() {
        for (var duplicate : List.of(searchTitle(), row("搜索以赚取", 28, 43, 60, 10, .99))) {
            held(AMBIGUOUS_TITLE, observe(searchOnly(SEARCH_COARSE), image(), List.of(searchTitle(), duplicate)).search());
        }
    }

    @Test public void titleOutsidePixelBoundaryDoesNotChooseUiBoundsInstead() {
        held(TITLE_OUTSIDE_BOUNDARY, observe(searchOnly(SEARCH_COARSE), image(),
                List.of(row("搜索以赚取", 28, 12, 60, 6, .99))).search());
    }

    @Test public void crossingOcrRowsAreRejectedAtCoarseAndFineBoundaries() {
        held(ROW_CROSSES_COARSE, observe(searchOnly(SEARCH_COARSE), image(),
                List.of(row("搜索以赚取", 5, 28, 60, 10, .99))).search());
        held(ROW_CROSSES_BOUNDARY, observe(searchOnly(SEARCH_COARSE), image(),
                List.of(row("搜索以赚取", 15, 28, 60, 10, .99))).search());
        held(ROW_CROSSES_BOUNDARY, observe(searchOnly(SEARCH_COARSE), image(),
                List.of(searchTitle(), row("private", 100, 45, 45, 10, .99))).search());
    }

    @Test public void titleMustAssistFromUpperLeftInsteadOfAnyPointInTheCard() {
        for (var misplaced : List.of(row("搜索以赚取", 80, 28, 40, 10, .99),
                row("搜索以赚取", 28, 55, 60, 10, .99), row("搜索以赚取", 28, 24, 60, 30, .99))) {
            held(TITLE_NOT_UPPER_LEFT, observe(searchOnly(SEARCH_COARSE), image(), List.of(misplaced)).search());
        }
    }

    @Test public void uiSummaryIsNotUsedAsPixelBoundaryOrAsFineTextEvidence() {
        var summary = rect(30, 50, 120, 65);
        var ui = new NativeUiRegions.Pair(new NativeUiRegions.Region(SEARCH, SEARCH_COARSE, summary), null);
        var result = observe(ui, image(), List.of(searchTitle()));
        assertEquals(VISUAL_CANDIDATE, result.search().status());
        assertEquals(SEARCH_PIXEL, result.search().cardBoundary());
        assertEquals(rect(28, 28, 88, 38), result.search().textBounds());
        held(NO_SURFACE_CONTRAST, observe(ui, blank(200), List.of(searchTitle())).search());
    }

    @Test public void localFailureDoesNotEraseIndependentOtherRoleCandidate() {
        var result = observe(pair(), image(), List.of(searchTitle(), row("unknown title", 28, 108, 60, 10, .99)));
        assertEquals(VISUAL_CANDIDATE, result.search().status());
        held(TITLE_MISSING, result.reading());
    }

    @Test public void cancellationAndSupplierFailureNeverExposePartialFineBounds() {
        var result = UiRegionImageRefiner.observe(pair(), image(), W, H, titles(), () -> true);
        held(CANCELED, result.search());
        held(CANCELED, result.reading());
        AtomicInteger checks = new AtomicInteger();
        result = UiRegionImageRefiner.observe(pair(), image(), W, H, titles(), () -> checks.incrementAndGet() > 20);
        held(CANCELED, result.search());
        held(CANCELED, result.reading());
        assertTrue(checks.get() > 20);
        result = UiRegionImageRefiner.observe(pair(), image(), W, H, titles(), () -> { throw new IllegalStateException("private"); });
        held(DETECTOR_UNAVAILABLE, result.search());
        held(DETECTOR_UNAVAILABLE, result.reading());
    }

    @Test public void pixelsAndRowsAreNotMutatedOrRetainedAndRawTextNeverEscapes() {
        byte[] pixels = image(), before = pixels.clone();
        var rows = new ArrayList<>(titles());
        rows.add(row("private account headline", 30, 45, 80, 10, .99));
        var snapshot = List.copyOf(rows);
        var result = observe(pair(), pixels, rows);
        assertArrayEquals(before, pixels);
        assertEquals(snapshot, rows);
        String output = result.toString(), published = result.summary(true);
        Arrays.fill(pixels, (byte) 0);
        rows.clear();
        assertEquals(output, result.toString());
        assertEquals(published, result.summary(true));
        for (String raw : List.of("private account headline", "搜索以赚取", "阅读以赚取")) {
            assertFalse(output.contains(raw));
            assertFalse(published.contains(raw));
        }
        for (var component : UiRegionImageRefiner.Item.class.getRecordComponents()) {
            assertTrue(component.getType() == NativeUiRegions.Role.class
                    || component.getType() == NativeUiRegions.Rect.class
                    || component.getType() == UiRegionImageRefiner.Status.class);
        }
    }

    @Test public void failedPublicationSuppressesAllRegionsAndCandidates() {
        var result = observe(pair(), image(), titles());
        String hidden = result.summary(false), visible = result.summary(true);
        assertTrue(hidden.contains("regions=SUPPRESSED"));
        for (String disallowed : List.of("Rect", "VISUAL_CANDIDATE", "coarse=", "cardBoundary=", "textBounds=")) {
            assertFalse(hidden, hidden.contains(disallowed));
        }
        for (String summary : List.of(hidden, visible)) {
            assertTrue(summary.contains("nonAtomic=true"));
            assertTrue(summary.contains("counterAuthority=false"));
            assertTrue(summary.contains("actionAuthority=false"));
        }
        assertTrue(visible.contains("DIAGNOSTIC_ONLY"));
        assertFalse(visible.contains("VERIFIED"));
    }

    @Test public void recordConstructionCannotPublishFineBoundsForAHoldOrSwapRoles() {
        var valid = observe(pair(), image(), titles());
        assertThrows(IllegalArgumentException.class, () -> new UiRegionImageRefiner.Result(valid.reading(), valid.search()));
        assertThrows(IllegalArgumentException.class, () -> new UiRegionImageRefiner.Result(null, valid.reading()));
        assertThrows(IllegalArgumentException.class, () -> new UiRegionImageRefiner.Item(null, SEARCH_COARSE, null, null, NO_BOUNDARY));
        assertThrows(IllegalArgumentException.class, () -> new UiRegionImageRefiner.Item(SEARCH, SEARCH_COARSE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new UiRegionImageRefiner.Item(SEARCH, SEARCH_COARSE, SEARCH_PIXEL, null, NO_BOUNDARY));
        assertThrows(IllegalArgumentException.class, () -> new UiRegionImageRefiner.Item(SEARCH, SEARCH_COARSE, null, null, VISUAL_CANDIDATE));
        assertThrows(IllegalArgumentException.class, () -> new UiRegionImageRefiner.Item(SEARCH, SEARCH_COARSE, SEARCH_PIXEL, READING_PIXEL, VISUAL_CANDIDATE));
    }
}
