package com.aliothmoon.maafw.once;

/** Pure diagram text, never a publication receipt, clickability finding or input capability.
 * The live caller emits it only inside the existing qualified publication branch. */
final class UiMockOverlayPlan {
    static String summary(UiRegionImageRefiner.Result result) {
        String point = "NONE";
        var reading = result == null ? null : result.reading();
        if (reading != null && reading.role() == NativeUiRegions.Role.READING
                && reading.status() == UiRegionImageRefiner.Status.VISUAL_CANDIDATE) {
            var card = reading.cardBoundary();
            if (card != null && reading.coarse() != null && reading.coarse().contains(card)
                    && reading.textBounds() != null && card.contains(reading.textBounds())) {
                // Bounds are half-open. Floor the geometric center; even a one-pixel card stays inside.
                int x = card.left() + (card.right() - card.left()) / 2;
                int y = card.top() + (card.bottom() - card.top()) / 2;
                point = "(" + x + "," + y + ")";
            }
        }
        return "mockOverlay=REVIEW_ONLY mockReadingPoint=" + point
                + " mockSearchPoint=NONE mockDispatched=0 counterAuthority=false actionAuthority=false"
                + " taskCompletion=NOT_EVALUATED";
    }

    private UiMockOverlayPlan() { }
}
