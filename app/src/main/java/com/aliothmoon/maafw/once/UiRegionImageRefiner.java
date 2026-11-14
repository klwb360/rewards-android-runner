package com.aliothmoon.maafw.once;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Pure, bounded visual diagnostics inside UI-provided search/reading regions.
 * The caller must bind the UI observation, original pixels and OCR to its source receipt.
 * This class neither authenticates a frame nor establishes an atomic UI/image observation.
 * Its conservative thresholds are diagnostic heuristics, not validated real-page calibration.
 * No pixels, OCR rows, text, identifiers, counter values or action targets escape the result.
 */
final class UiRegionImageRefiner {
    private static final int MAX_DIMENSION = 4096, MAX_PIXELS = 4 * 1024 * 1024;
    private static final int MAX_ROWS = 128, MAX_TEXT = 512, MAX_COMPONENTS = 512;
    private static final int DIAGNOSTIC_MIN_CONTRAST = 6, DIAGNOSTIC_MAX_CONTRAST = 64;
    private static final int DIAGNOSTIC_MODE_PIXELS = 4, DIAGNOSTIC_MIN_SIDE = 8;
    private static final int DIAGNOSTIC_COARSE_DIVISOR = 8, DIAGNOSTIC_MODE_AREA_DIVISOR = 64;
    private static final int DIAGNOSTIC_MIN_FILL_PERCENT = 75, DIAGNOSTIC_MIN_EDGE_SUPPORT_PERCENT = 80;
    private static final int DIAGNOSTIC_TITLE_START_DIVISOR = 3, DIAGNOSTIC_TITLE_BOTTOM_DIVISOR = 2;
    private static final double DIAGNOSTIC_MIN_CONFIDENCE = .8;
    private static final String SEARCH_TITLE = "搜索以赚取", READING_TITLE = "阅读以赚取";

    enum Status {
        VISUAL_CANDIDATE, NO_UI_REGION, INVALID_INPUT, INVALID_IMAGE, INVALID_ROWS,
        UI_OUTSIDE_FRAME, UI_OVERLAP, CANCELED, DETECTOR_UNAVAILABLE,
        NO_SURFACE_CONTRAST, NO_BOUNDARY, TOO_MANY_COMPONENTS, AMBIGUOUS_BOUNDARY,
        SURFACE_CROSSES_COARSE, FRAME_CLIPPED, SURFACE_NOT_FILLED, EDGE_UNSUPPORTED,
        ROW_CROSSES_COARSE, ROW_CROSSES_BOUNDARY, LOW_CONFIDENCE_ROW,
        TITLE_MISSING, AMBIGUOUS_TITLE, WRONG_ROLE_TITLE, UNSUPPORTED_TITLE,
        TITLE_OUTSIDE_BOUNDARY, TITLE_NOT_UPPER_LEFT
    }

    record Item(NativeUiRegions.Role role, NativeUiRegions.Rect coarse,
                NativeUiRegions.Rect cardBoundary, NativeUiRegions.Rect textBounds, Status status) {
        Item {
            if (role == null || status == null) throw new IllegalArgumentException("fixed result fields");
            if (status == Status.VISUAL_CANDIDATE) {
                if (coarse == null || cardBoundary == null || textBounds == null
                        || !coarse.contains(cardBoundary) || !cardBoundary.contains(textBounds)) {
                    throw new IllegalArgumentException("candidate bounds");
                }
            } else if (cardBoundary != null || textBounds != null) {
                throw new IllegalArgumentException("held result contains fine bounds");
            }
        }
    }

    record Result(Item search, Item reading, UiTitleDiagnostic.Sample searchTitleDiagnostic,
                  UiTitleDiagnostic.Sample readingTitleDiagnostic) {
        Result(Item search, Item reading) {
            this(search, reading, UiTitleDiagnostic.notObserved(), UiTitleDiagnostic.notObserved());
        }
        Result {
            if (search == null || reading == null || search.role() != NativeUiRegions.Role.SEARCH
                    || reading.role() != NativeUiRegions.Role.READING
                    || searchTitleDiagnostic == null || readingTitleDiagnostic == null) {
                throw new IllegalArgumentException("fixed result roles");
            }
        }

        String summary(boolean qualified) {
            String limits = " nonAtomic=true counterAuthority=false actionAuthority=false";
            if (!qualified) return "uiImageAlignment=UNQUALIFIED regions=SUPPRESSED" + limits;
            return "uiImageAlignment=DIAGNOSTIC_ONLY" + limits + " search=" + search + " reading=" + reading
                    + " searchTitleDiagnostic=" + searchTitleDiagnostic + " readingTitleDiagnostic=" + readingTitleDiagnostic;
        }
    }

    static Result observe(NativeUiRegions.Pair ui, byte[] gray, int width, int height,
                          List<FrameStructureEvidence.Line> rows, BooleanSupplier cancel) {
        return observe(ui, gray, width, height, rows, cancel, null);
    }

    static Result observe(NativeUiRegions.Pair ui, byte[] gray, int width, int height,
                          List<FrameStructureEvidence.Line> rows, BooleanSupplier cancel, UiImageAlignmentTrace trace) {
        if (ui == null || cancel == null) return held(ui, Status.INVALID_INPUT);
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS || gray == null
                || gray.length != (long) width * height) return held(ui, Status.INVALID_IMAGE);
        if (!inside(ui.search(), width, height) || !inside(ui.reading(), width, height)) {
            return held(ui, Status.UI_OUTSIDE_FRAME);
        }
        if (ui.search() != null && ui.reading() != null
                && overlaps(ui.search().card(), ui.reading().card())) return held(ui, Status.UI_OVERLAP);
        try {
            check(cancel);
            // Shape validation only. A new artificial Binding here would not prove OCR provenance.
            if (!validRows(rows, width, height, cancel)) return held(ui, Status.INVALID_ROWS);
            UiTitleDiagnostic.Sample[] diagnostics = {UiTitleDiagnostic.notObserved(), UiTitleDiagnostic.notObserved()};
            Item search = refine(NativeUiRegions.Role.SEARCH, ui.search(), gray, width, height, rows, cancel, diagnostics, trace);
            Item reading = refine(NativeUiRegions.Role.READING, ui.reading(), gray, width, height, rows, cancel, diagnostics, trace);
            check(cancel);
            return new Result(search, reading, diagnostics[0], diagnostics[1]);
        } catch (Canceled ignored) {
            return held(ui, Status.CANCELED);
        } catch (RuntimeException unavailable) {
            // A mutable/broken caller or cancellation supplier must not expose a partial candidate.
            return held(ui, Status.DETECTOR_UNAVAILABLE);
        }
    }

    private static Result held(NativeUiRegions.Pair ui, Status status) {
        return new Result(item(NativeUiRegions.Role.SEARCH, ui == null ? null : ui.search(), status),
                item(NativeUiRegions.Role.READING, ui == null ? null : ui.reading(), status));
    }

    private static Item item(NativeUiRegions.Role role, NativeUiRegions.Region region, Status status) {
        return new Item(role, region == null ? null : region.card(), null, null, status);
    }

    private static boolean inside(NativeUiRegions.Region region, int width, int height) {
        return region == null || region.card().right() <= width && region.card().bottom() <= height;
    }

    private static boolean overlaps(NativeUiRegions.Rect a, NativeUiRegions.Rect b) {
        return a.left() < b.right() && a.right() > b.left() && a.top() < b.bottom() && a.bottom() > b.top();
    }

    private static boolean validRows(List<FrameStructureEvidence.Line> rows, int width, int height,
                                     BooleanSupplier cancel) {
        if (rows == null || rows.size() > MAX_ROWS) return false;
        for (var row : rows) {
            check(cancel);
            if (row == null || row.text() == null || row.text().length() > MAX_TEXT
                    || !Double.isFinite(row.score()) || row.score() < 0 || row.score() > 1
                    || row.x() < 0 || row.y() < 0 || row.width() <= 0 || row.height() <= 0
                    || (long) row.x() + row.width() > width || (long) row.y() + row.height() > height) {
                return false;
            }
        }
        return true;
    }

    private static Item refine(NativeUiRegions.Role role, NativeUiRegions.Region region,
                               byte[] gray, int width, int height,
                               List<FrameStructureEvidence.Line> rows, BooleanSupplier cancel,
                               UiTitleDiagnostic.Sample[] diagnostics, UiImageAlignmentTrace trace) {
        if (region == null) return item(role, null, Status.NO_UI_REGION);
        NativeUiRegions.Rect coarse = region.card();
        int cw = coarse.right() - coarse.left(), ch = coarse.bottom() - coarse.top();
        if (cw < DIAGNOSTIC_MIN_SIDE || ch < DIAGNOSTIC_MIN_SIDE) return item(role, region, Status.NO_BOUNDARY);
        int threshold;
        var thresholdSpan = trace == null ? null : trace.begin(role == NativeUiRegions.Role.SEARCH
                ? UiImageAlignmentTrace.Phase.SEARCH_THRESHOLD : UiImageAlignmentTrace.Phase.READING_THRESHOLD);
        try { threshold = threshold(gray, width, height, coarse, cancel); }
        finally { if (trace != null) trace.end(thresholdSpan); }
        if (threshold < 0) return item(role, region, Status.NO_SURFACE_CONTRAST);
        Geometry geometry;
        var geometrySpan = trace == null ? null : trace.begin(role == NativeUiRegions.Role.SEARCH
                ? UiImageAlignmentTrace.Phase.SEARCH_GEOMETRY : UiImageAlignmentTrace.Phase.READING_GEOMETRY);
        try { geometry = geometry(gray, width, height, coarse, threshold, cancel); }
        finally { if (trace != null) trace.end(geometrySpan); }
        if (geometry.status() != Status.VISUAL_CANDIDATE) return item(role, region, geometry.status());
        // OCR cannot select among competing pixel boundaries or repair a missing boundary.
        var titleSpan = trace == null ? null : trace.begin(role == NativeUiRegions.Role.SEARCH
                ? UiImageAlignmentTrace.Phase.SEARCH_TITLE : UiImageAlignmentTrace.Phase.READING_TITLE);
        try {
        NativeUiRegions.Rect boundary = geometry.boundary();
        FrameStructureEvidence.Line title = null;
        String expected = role == NativeUiRegions.Role.SEARCH ? SEARCH_TITLE : READING_TITLE;
        String opposite = role == NativeUiRegions.Role.SEARCH ? READING_TITLE : SEARCH_TITLE;
        int titles = 0;
        for (var row : rows) {
            check(cancel);
            NativeUiRegions.Rect box = box(row);
            if (!overlaps(coarse, box)) continue;
            if (!coarse.contains(box)) return item(role, region, Status.ROW_CROSSES_COARSE);
            if (row.score() < DIAGNOSTIC_MIN_CONFIDENCE) return item(role, region, Status.LOW_CONFIDENCE_ROW);
            if (overlaps(boundary, box) && !boundary.contains(box)) {
                return item(role, region, Status.ROW_CROSSES_BOUNDARY);
            }
            if (row.text().equals(opposite)) {
                diagnostics[role == NativeUiRegions.Role.SEARCH ? 0 : 1] =
                        UiTitleDiagnostic.classify(role, row.text(), box, boundary, region.summary());
                return item(role, region, Status.WRONG_ROLE_TITLE);
            }
            if (!row.text().equals(expected)
                    && (row.text().contains(SEARCH_TITLE) || row.text().contains(READING_TITLE))) {
                diagnostics[role == NativeUiRegions.Role.SEARCH ? 0 : 1] =
                        UiTitleDiagnostic.classify(role, row.text(), box, boundary, region.summary());
                return item(role, region, Status.UNSUPPORTED_TITLE);
            }
            if (row.text().equals(expected)) { titles++; title = row; }
        }
        if (titles == 0) return item(role, region, Status.TITLE_MISSING);
        if (titles != 1) return item(role, region, Status.AMBIGUOUS_TITLE);
        NativeUiRegions.Rect text = box(title);
        diagnostics[role == NativeUiRegions.Role.SEARCH ? 0 : 1] =
                UiTitleDiagnostic.classify(role, title.text(), text, boundary, region.summary());
        if (!boundary.contains(text)) return item(role, region, Status.TITLE_OUTSIDE_BOUNDARY);
        int bw = boundary.right() - boundary.left(), bh = boundary.bottom() - boundary.top();
        if ((long) (text.left() - boundary.left()) * DIAGNOSTIC_TITLE_START_DIVISOR > bw
                || (long) (text.top() - boundary.top()) * DIAGNOSTIC_TITLE_START_DIVISOR > bh
                || (long) (text.bottom() - boundary.top()) * DIAGNOSTIC_TITLE_BOTTOM_DIVISOR > bh) {
            return item(role, region, Status.TITLE_NOT_UPPER_LEFT);
        }
        return new Item(role, coarse, boundary, text, Status.VISUAL_CANDIDATE);
        } finally { if (trace != null) trace.end(titleSpan); }
    }

    /** Local modes only; no More-card full-screen ratio or absolute white threshold is reused. */
    private static int threshold(byte[] gray, int width, int height, NativeUiRegions.Rect coarse,
                                 BooleanSupplier cancel) {
        int[] interior = new int[256], context = new int[256];
        int area = (coarse.right() - coarse.left()) * (coarse.bottom() - coarse.top());
        int visited = 0;
        for (int y = coarse.top(); y < coarse.bottom(); y++) {
            for (int x = coarse.left(); x < coarse.right(); x++) {
                if ((visited++ & 1023) == 0) check(cancel);
                int value = gray[y * width + x] & 255;
                interior[value]++;
                if (x == coarse.left() || x == coarse.right() - 1
                        || y == coarse.top() || y == coarse.bottom() - 1) context[value]++;
            }
        }
        // Real full-frame samples outside the coarse ROI, with no invented/clamped pixel values.
        for (int x = Math.max(0, coarse.left() - 1); x <= Math.min(width - 1, coarse.right()); x++) {
            check(cancel);
            if (coarse.top() > 0) context[gray[(coarse.top() - 1) * width + x] & 255]++;
            if (coarse.bottom() < height) context[gray[coarse.bottom() * width + x] & 255]++;
        }
        for (int y = coarse.top(); y < coarse.bottom(); y++) {
            check(cancel);
            if (coarse.left() > 0) context[gray[y * width + coarse.left() - 1] & 255]++;
            if (coarse.right() < width) context[gray[y * width + coarse.right()] & 255]++;
        }
        int bright = -1, minimum = Math.max(DIAGNOSTIC_MODE_PIXELS, area / DIAGNOSTIC_MODE_AREA_DIVISOR);
        for (int level = 255; level >= 0; level--) {
            if (interior[level] >= minimum) { bright = level; break; }
        }
        if (bright < DIAGNOSTIC_MIN_CONTRAST) return -1;
        int background = -1, support = DIAGNOSTIC_MODE_PIXELS - 1;
        for (int level = Math.max(0, bright - DIAGNOSTIC_MAX_CONTRAST);
             level <= bright - DIAGNOSTIC_MIN_CONTRAST; level++) {
            if (context[level] > support) { support = context[level]; background = level; }
        }
        // The minimum supported contrast includes distinct brighter tones instead of selecting
        // only the brightest object; multiple sizable surfaces must remain ambiguous.
        return background < 0 ? -1 : background + DIAGNOSTIC_MIN_CONTRAST;
    }

    private record Geometry(NativeUiRegions.Rect boundary, Status status) { }

    private static Geometry geometry(byte[] gray, int width, int height, NativeUiRegions.Rect coarse,
                                     int threshold, BooleanSupplier cancel) {
        int cw = coarse.right() - coarse.left(), ch = coarse.bottom() - coarse.top(), area = cw * ch;
        boolean[] seen = new boolean[area];
        int[] queue = new int[area];
        int minimumWidth = Math.max(DIAGNOSTIC_MIN_SIDE, cw / DIAGNOSTIC_COARSE_DIVISOR);
        int minimumHeight = Math.max(DIAGNOSTIC_MIN_SIDE, ch / DIAGNOSTIC_COARSE_DIVISOR);
        int components = 0, candidates = 0, candidateArea = 0;
        NativeUiRegions.Rect candidate = null;
        boolean candidateCrosses = false;
        for (int seed = 0; seed < area; seed++) {
            if ((seed & 1023) == 0) check(cancel);
            int sx = seed % cw, sy = seed / cw;
            if (seen[seed] || !bright(gray, width, coarse.left() + sx, coarse.top() + sy, threshold)) continue;
            if (++components > MAX_COMPONENTS) return new Geometry(null, Status.TOO_MANY_COMPONENTS);
            int head = 0, tail = 0, minX = sx, maxX = sx, minY = sy, maxY = sy;
            boolean crosses = false;
            seen[seed] = true;
            queue[tail++] = seed;
            while (head < tail) {
                if ((head & 1023) == 0) check(cancel);
                int at = queue[head++], x = at % cw, y = at / cw;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                for (int direction = 0; direction < 4; direction++) {
                    int nx = x + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
                    int ny = y + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
                    int globalX = coarse.left() + nx, globalY = coarse.top() + ny;
                    if (nx < 0 || nx >= cw || ny < 0 || ny >= ch) {
                        if (globalX >= 0 && globalX < width && globalY >= 0 && globalY < height
                                && bright(gray, width, globalX, globalY, threshold)) crosses = true;
                    } else {
                        int next = ny * cw + nx;
                        if (!seen[next] && bright(gray, width, globalX, globalY, threshold)) {
                            seen[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
            }
            if (maxX - minX + 1 < minimumWidth || maxY - minY + 1 < minimumHeight) continue;
            if (++candidates > 1) return new Geometry(null, Status.AMBIGUOUS_BOUNDARY);
            candidate = new NativeUiRegions.Rect(coarse.left() + minX, coarse.top() + minY,
                    coarse.left() + maxX + 1, coarse.top() + maxY + 1);
            candidateArea = tail;
            candidateCrosses = crosses;
        }
        if (candidate == null) return new Geometry(null, Status.NO_BOUNDARY);
        if (candidateCrosses) return new Geometry(null, Status.SURFACE_CROSSES_COARSE);
        if (candidate.left() == 0 || candidate.top() == 0 || candidate.right() == width || candidate.bottom() == height) {
            return new Geometry(null, Status.FRAME_CLIPPED);
        }
        long boxArea = (long) (candidate.right() - candidate.left()) * (candidate.bottom() - candidate.top());
        if ((long) candidateArea * 100 < boxArea * DIAGNOSTIC_MIN_FILL_PERCENT) return new Geometry(null, Status.SURFACE_NOT_FILLED);
        for (int side = 0; side < 4; side++) {
            if (!supportedEdge(gray, width, candidate, side, threshold, cancel)) return new Geometry(null, Status.EDGE_UNSUPPORTED);
        }
        return new Geometry(candidate, Status.VISUAL_CANDIDATE);
    }

    private static boolean supportedEdge(byte[] gray, int width, NativeUiRegions.Rect box, int side,
                                         int threshold, BooleanSupplier cancel) {
        int length = side % 2 == 0 ? box.bottom() - box.top() : box.right() - box.left();
        int supported = 0;
        for (int offset = 0; offset < length; offset++) {
            if ((offset & 255) == 0) check(cancel);
            int ix, iy, ox, oy;
            if (side == 0) { ix = box.left(); ox = ix - 1; iy = oy = box.top() + offset; }
            else if (side == 2) { ix = box.right() - 1; ox = ix + 1; iy = oy = box.top() + offset; }
            else if (side == 1) { iy = box.top(); oy = iy - 1; ix = ox = box.left() + offset; }
            else { iy = box.bottom() - 1; oy = iy + 1; ix = ox = box.left() + offset; }
            int inner = gray[iy * width + ix] & 255, outer = gray[oy * width + ox] & 255;
            if (inner >= threshold && inner - outer >= DIAGNOSTIC_MIN_CONTRAST) supported++;
        }
        return (long) supported * 100 >= (long) length * DIAGNOSTIC_MIN_EDGE_SUPPORT_PERCENT;
    }

    private static boolean bright(byte[] gray, int width, int x, int y, int threshold) {
        return (gray[y * width + x] & 255) >= threshold;
    }

    private static NativeUiRegions.Rect box(FrameStructureEvidence.Line row) {
        return new NativeUiRegions.Rect(row.x(), row.y(), row.x() + row.width(), row.y() + row.height());
    }

    private static void check(BooleanSupplier cancel) { if (cancel.getAsBoolean()) throw new Canceled(); }
    private static final class Canceled extends RuntimeException { private static final long serialVersionUID = 1L; }
    private UiRegionImageRefiner() { }
}
