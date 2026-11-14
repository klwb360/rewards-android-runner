package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.NativeUiRegions.*;

public class NativeUiRegionsTest {
    private static final Rect CARD = new Rect(10, 20, 110, 220);

    private static void rejected(Runnable construction) {
        try { construction.run(); fail("invalid region accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void boundsAcceptOriginAndExactMaximum() {
        Rect full = new Rect(0, 0, 8192, 8192);
        assertTrue(full.contains(new Rect(8191, 8191, 8192, 8192)));
        assertTrue(full.contains(full));
    }

    @Test public void negativeEmptyReversedAndOversizedBoundsRejected() {
        for (int[] bounds : new int[][] {
                {-1,0,1,1}, {0,-1,1,1}, {0,0,-1,1}, {0,0,1,-1},
                {0,0,0,1}, {0,0,1,0}, {2,0,1,1}, {0,2,1,1},
                {0,0,8193,1}, {0,0,1,8193},
                {0,0,Integer.MAX_VALUE,1}, {0,Integer.MIN_VALUE,1,1}}) {
            rejected(() -> new Rect(bounds[0], bounds[1], bounds[2], bounds[3]));
        }
    }

    @Test public void containmentIncludesEdgesButNeverPartialOverlap() {
        assertTrue(CARD.contains(new Rect(10, 20, 110, 220)));
        assertTrue(CARD.contains(new Rect(11, 21, 109, 219)));
        assertFalse(CARD.contains(null));
        for (Rect outside : new Rect[] {
                new Rect(9,20,110,220), new Rect(10,19,110,220),
                new Rect(10,20,111,220), new Rect(10,20,110,221),
                new Rect(110,20,120,220)}) {
            assertFalse(CARD.contains(outside));
        }
    }

    @Test public void regionRequiresRoleAndCardButSummaryMayBeAbsent() {
        Region region = new Region(Role.SEARCH, CARD, null);
        assertEquals(Role.SEARCH, region.role());
        assertEquals(CARD, region.card());
        assertNull(region.summary());
        rejected(() -> new Region(null, CARD, null));
        rejected(() -> new Region(Role.READING, null, null));
    }

    @Test public void summaryMustFitCardIncludingEveryEdge() {
        assertEquals(CARD, new Region(Role.READING, CARD, CARD).summary());
        for (Rect outside : new Rect[] {
                new Rect(9,20,110,220), new Rect(10,19,110,220),
                new Rect(10,20,111,220), new Rect(10,20,110,221)}) {
            rejected(() -> new Region(Role.READING, CARD, outside));
        }
    }

    @Test public void pairAllowsEachMissingRoleAndBothMissing() {
        Region search = new Region(Role.SEARCH, CARD, null);
        Region reading = new Region(Role.READING, CARD, null);
        assertEquals(new Pair(null, null), Pair.empty());
        assertEquals(search, new Pair(search, null).search());
        assertEquals(reading, new Pair(null, reading).reading());
        assertEquals(reading, new Pair(search, reading).reading());
    }

    @Test public void pairRejectsWrongRoleInEitherSlot() {
        Region search = new Region(Role.SEARCH, CARD, null);
        Region reading = new Region(Role.READING, CARD, null);
        rejected(() -> new Pair(reading, null));
        rejected(() -> new Pair(null, search));
        rejected(() -> new Pair(reading, search));
    }

    @Test public void regionValueSchemaContainsOnlyFixedRolesAndRectangles() {
        assertArrayEquals(new Role[] {Role.SEARCH, Role.READING}, Role.values());
        for (var component : Rect.class.getRecordComponents()) assertEquals(int.class, component.getType());
        var fields = Region.class.getRecordComponents();
        assertEquals(3, fields.length);
        assertEquals(Role.class, fields[0].getType());
        assertEquals(Rect.class, fields[1].getType());
        assertEquals(Rect.class, fields[2].getType());
        for (var component : Pair.class.getRecordComponents()) assertEquals(Region.class, component.getType());
    }
}
