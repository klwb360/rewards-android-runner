package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;

/** Fixed local diagram facts. No OCR text, source grant, task receipt or input capability. */
final class OverviewFrameResult {
    enum Status { OBSERVED, CANDIDATE, ITEM_COMPLETE_SKIP, REGION_COMPLETE_SKIP, HOLD }
    enum Reason {
        READ_ONLY_SUMMARY, READ_ONLY_WEEK_PROGRESS, CURRENT_RING_NUMBER, PLUS_NUMBER,
        NUMBER_AND_TICK, EQUAL_REGION_COUNTER, INVALID_INPUT, INVALID_IMAGE, INVALID_ROWS,
        REGION_OUTSIDE_FRAME, REGION_PARTIAL, REGION_OVERLAP, ROW_CROSSES_REGION,
        NO_BOUNDARY, AMBIGUOUS_BOUNDARY, HEADER_MISSING, HEADER_WITH_EXTRA_TEXT, HEADER_CONFLICT,
        COUNTER_MISSING, COUNTER_AMBIGUOUS, COUNTER_CONFLICT, COUNTER_INVALID,
        LOW_CONFIDENCE, CARD_PARTIAL, CARD_OVERLAP, ROW_CROSSES_CARD, ROW_OVERLAP,
        TITLE_MISSING, BADGE_AMBIGUOUS, TOKEN_AMBIGUOUS, TICK_CONFLICT,
        STATE_CONFLICT, NO_CURRENT_RING, AMBIGUOUS_RING, NUMBER_INVALID,
        NUMBER_MISSING, NUMBER_NON_NUMERIC, NUMBER_MULTIPLE, NUMBER_OUTSIDE_SAFE_RING,
        COLOR_UNAVAILABLE, COLOR_NOT_YELLOW, CANCELED, DETECTOR_UNAVAILABLE
    }
    record Item(int regionIndex, int itemIndex, OverviewUiRegions.Role role,
                Box coarse, Box boundary, Box title, Box target,
                Status status, Reason reason, int displayedPoints, int current, int total) {
        Item {
            if (regionIndex < 0 || itemIndex < 0 || role == null || coarse == null
                    || status == null || reason == null || displayedPoints < -1
                    || current < -1 || total < -1) throw new IllegalArgumentException("fixed diagram item");
            if (target != null && (status != Status.CANDIDATE || boundary == null
                    || !coarse.contains(boundary) || !boundary.contains(target)))
                throw new IllegalArgumentException("unqualified diagram target");
            if (status == Status.CANDIDATE && (target == null || title == null
                    || displayedPoints <= 0 || !boundary.contains(title)))
                throw new IllegalArgumentException("candidate evidence missing");
            if (role != OverviewUiRegions.Role.CHECK_IN
                    && role != OverviewUiRegions.Role.DAILY_ACTIVITIES
                    && role != OverviewUiRegions.Role.MORE_ACTIVITIES && target != null)
                throw new IllegalArgumentException("summary cannot have a target");
        }
        boolean mockPointAvailable() { return status == Status.CANDIDATE && target != null; }
    }
    private final long frameId;
    private final List<Item> items;
    private final Reason frameFailure;
    OverviewFrameResult(long frameId, List<Item> items, Reason frameFailure) {
        if (frameId < 1 || items == null || items.size() > 96) throw new IllegalArgumentException("bounded frame");
        this.frameId = frameId; this.items = List.copyOf(items); this.frameFailure = frameFailure;
    }
    long frameId() { return frameId; }
    List<Item> items() { return items; }
    Reason frameFailure() { return frameFailure; }
    boolean failed() { return frameFailure != null; }
    boolean actionAuthority() { return false; }
    boolean counterAuthority() { return false; }
}
