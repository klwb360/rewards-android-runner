package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;

/** Fixed structure observations only; no source text or input authority. */
final class OverviewUiRegions {
    enum Role { CHECK_IN, DAILY_PROGRESS, DAILY_ACTIVITIES, MORE_ACTIVITIES,
        POINTS_SUMMARY, STREAK_SUMMARY }
    enum Status { OBSERVED, WRONG_STRUCTURE, AMBIGUOUS_STRUCTURE }

    record Region(Role role, Box coarse, Box heading, Box counter, boolean completeVisible,
                  int current, int target) {
        Region {
            if (role == null || !valid(coarse)
                    || (heading == null ? role != Role.DAILY_ACTIVITIES && role != Role.MORE_ACTIVITIES
                    : !valid(heading) || !coarse.contains(heading))
                    || counter != null && (!valid(counter) || !coarse.contains(counter))
                    || (current == -1 ? target != -1 : current < 0 || target <= 0
                    || target > 10000 || current > target || counter == null)) {
                throw new IllegalArgumentException("Invalid fixed overview region");
            }
        }
    }

    record Result(Status status, Box viewport, List<Region> regions) {
        Result {
            if (status == null || regions == null || regions.size() > Role.values().length
                    || status == Status.OBSERVED && !valid(viewport)
                    || status != Status.OBSERVED && (viewport != null || !regions.isEmpty())) {
                throw new IllegalArgumentException("Invalid overview structure result");
            }
            regions = List.copyOf(regions);
            boolean[] roles = new boolean[Role.values().length];
            for (Region region : regions) {
                if (region == null || roles[region.role().ordinal()] || !viewport.contains(region.coarse())) {
                    throw new IllegalArgumentException("Conflicting overview region");
                }
                roles[region.role().ordinal()] = true;
            }
        }
        static Result rejected(Status status) { return new Result(status, null, List.of()); }
    }

    private static boolean valid(Box box) {
        return box != null && box.left() >= 0 && box.top() >= 0 && box.right() > box.left()
                && box.bottom() > box.top() && box.right() <= 8192 && box.bottom() <= 8192;
    }
    private OverviewUiRegions() { }
}
