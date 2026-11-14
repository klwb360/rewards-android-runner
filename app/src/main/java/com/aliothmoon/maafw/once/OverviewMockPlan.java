package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.List;

/** Coordinates for local numbered diagrams only; has no dispatch API. */
final class OverviewMockPlan {
    record Point(int number, int regionIndex, int itemIndex, OverviewUiRegions.Role role, int x, int y) { }
    static List<Point> points(OverviewFrameResult result, boolean sourceQualified) {
        if (!sourceQualified || result == null || result.failed()) return List.of();
        List<Point> points = new ArrayList<>();
        for (var item : result.items()) if (item.mockPointAvailable()) {
            var target = item.target();
            points.add(new Point(points.size() + 1, item.regionIndex(), item.itemIndex(), item.role(),
                    target.left() + target.width()/2, target.top() + target.height()/2));
        }
        return List.copyOf(points);
    }
    private OverviewMockPlan() { }
}
