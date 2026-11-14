package com.aliothmoon.maafw.once;

import java.lang.reflect.Modifier;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic geometry only. Neither these points nor this serializer authorize any input. */
public class UiMockOverlayPlanTest {
    private static final String TAIL = " mockSearchPoint=NONE mockDispatched=0 counterAuthority=false"
            + " actionAuthority=false taskCompletion=NOT_EVALUATED";

    private static NativeUiRegions.Rect box(int left, int top, int right, int bottom) {
        return new NativeUiRegions.Rect(left, top, right, bottom);
    }
    private static UiRegionImageRefiner.Item held(NativeUiRegions.Role role, UiRegionImageRefiner.Status status) {
        return new UiRegionImageRefiner.Item(role, null, null, null, status);
    }
    private static UiRegionImageRefiner.Item candidate(NativeUiRegions.Role role, NativeUiRegions.Rect coarse,
            NativeUiRegions.Rect card, NativeUiRegions.Rect title) {
        return new UiRegionImageRefiner.Item(role, coarse, card, title, UiRegionImageRefiner.Status.VISUAL_CANDIDATE);
    }
    private static UiRegionImageRefiner.Result reading(NativeUiRegions.Rect card) {
        return new UiRegionImageRefiner.Result(held(NativeUiRegions.Role.SEARCH, UiRegionImageRefiner.Status.NO_UI_REGION),
                candidate(NativeUiRegions.Role.READING, card, card, card));
    }
    private static void noPoint(String summary) {
        assertEquals("mockOverlay=REVIEW_ONLY mockReadingPoint=NONE" + TAIL, summary);
        assertFalse(summary.contains("(")); assertFalse(summary.contains(")"));
    }

    @Test public void absentResultHasNoPointAndNoAuthority() {
        noPoint(UiMockOverlayPlan.summary(null));
    }

    @Test public void visualSearchNeverProducesAPointOrSubstitutesForMissingReading() {
        var card = box(10, 20, 110, 80);
        var result = new UiRegionImageRefiner.Result(candidate(NativeUiRegions.Role.SEARCH, card, card, card),
                held(NativeUiRegions.Role.READING, UiRegionImageRefiner.Status.NO_UI_REGION));
        noPoint(UiMockOverlayPlan.summary(result));
    }

    @Test public void everyUnsupportedReadingStatusSuppressesThePoint() {
        for (var status : UiRegionImageRefiner.Status.values()) {
            if (status == UiRegionImageRefiner.Status.VISUAL_CANDIDATE) continue;
            var result = new UiRegionImageRefiner.Result(held(NativeUiRegions.Role.SEARCH, status),
                    held(NativeUiRegions.Role.READING, status));
            noPoint(UiMockOverlayPlan.summary(result));
        }
    }

    @Test public void readingUsesThePixelCardCenterInsteadOfTheCoarseOrTitleCenter() {
        var search = box(10, 10, 60, 60);
        var result = new UiRegionImageRefiner.Result(candidate(NativeUiRegions.Role.SEARCH, search, search, search),
                candidate(NativeUiRegions.Role.READING, box(0, 80, 500, 600), box(80, 120, 280, 320), box(85, 125, 155, 145)));
        assertEquals("mockOverlay=REVIEW_ONLY mockReadingPoint=(180,220)" + TAIL, UiMockOverlayPlan.summary(result));
    }

    @Test public void oddEvenSinglePixelAndUpperLimitBoundsKeepThePointInsideExclusiveEdges() {
        int[][] cases = {{0, 0, 1, 1, 0, 0}, {10, 20, 12, 22, 11, 21}, {10, 20, 15, 27, 12, 23},
                {4095, 4095, 4096, 4096, 4095, 4095}, {8191, 8191, 8192, 8192, 8191, 8191},
                {0, 0, 8192, 8192, 4096, 4096}};
        for (int[] value : cases) {
            var card = box(value[0], value[1], value[2], value[3]);
            assertEquals("mockOverlay=REVIEW_ONLY mockReadingPoint=(" + value[4] + "," + value[5] + ")" + TAIL,
                    UiMockOverlayPlan.summary(reading(card)));
            assertTrue(value[4] >= card.left() && value[4] < card.right());
            assertTrue(value[5] >= card.top() && value[5] < card.bottom());
        }
    }

    @Test public void serializationIsOneFixedAsciiLineAndDoesNotConsumeTheResult() {
        var result = reading(box(30, 230, 370, 390));
        String summary = UiMockOverlayPlan.summary(result);
        assertEquals("mockOverlay=REVIEW_ONLY mockReadingPoint=(200,310)" + TAIL, summary);
        assertTrue(summary.chars().allMatch(value -> value >= 32 && value <= 126));
        assertEquals(7, summary.split(" ").length); assertTrue(summary.length() < 200);
        for (int i = 0; i < 4; i++) assertEquals(summary, UiMockOverlayPlan.summary(result));
        assertEquals(box(30, 230, 370, 390), result.reading().cardBoundary());
    }

    @Test public void apiHasOnlyAStaticScalarSerializerAndNoConstructibleInputCapability() {
        assertTrue(Modifier.isFinal(UiMockOverlayPlan.class.getModifiers()));
        for (var constructor : UiMockOverlayPlan.class.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        var methods = UiMockOverlayPlan.class.getDeclaredMethods(); assertEquals(1, methods.length);
        assertEquals("summary", methods[0].getName()); assertTrue(Modifier.isStatic(methods[0].getModifiers()));
        assertEquals(String.class, methods[0].getReturnType());
        assertArrayEquals(new Class<?>[]{UiRegionImageRefiner.Result.class}, methods[0].getParameterTypes());
        assertEquals(0, UiMockOverlayPlan.class.getDeclaredFields().length);
    }

    @Test public void missingCompletionCannotPublishMockPointsEvenWhenCallerFlagsAreTrue() {
        var publication = new UiImageAlignmentPublication(null, new Object(), new Object(), new Object(), 1000);
        for (int attempt = 0; attempt < 2; attempt++) {
            String summary = publication.publish(300, true, true, true);
            assertTrue(summary.contains("alignmentEvidence=UNQUALIFIED"));
            assertTrue(summary.contains("publicationFence=REJECTED_AT_APPEND"));
            assertFalse(summary.contains("mockOverlay=")); assertFalse(summary.contains("mockReadingPoint="));
            assertFalse(summary.contains("mockSearchPoint="));
        }
    }
}
