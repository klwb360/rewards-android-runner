package com.aliothmoon.maafw.once;

/** Fixed diagnostics of one already examined native OCR row. Never recognition or source authority. */
final class UiTitleDiagnostic {
    private static final String SEARCH = "搜索以赚取", READING = "阅读以赚取";
    private static final int MAX_TEXT = 512;

    enum Kind { NOT_OBSERVED, NO_LITERAL, EXACT_EXPECTED, EXACT_OPPOSITE,
        EXPECTED_EMBEDDED, OPPOSITE_EMBEDDED, BOTH_ROLES }
    enum Anchor { NONE, EXPECTED, OPPOSITE }
    enum Surround { NOT_APPLICABLE, NONE, WHITESPACE, OTHER }
    enum SummaryOverlap { NOT_AVAILABLE, NO, YES }

    /** Counts describe this row, not all rows. No text, hash, coordinates or source reference is retained. */
    record Sample(Kind kind, int rowExpectedCount, int rowOppositeCount, Anchor anchor,
                  Surround prefix, Surround suffix, boolean insidePixelBoundary, boolean upperLeft,
                  SummaryOverlap overlapsUiSummary) {
        Sample {
            if (kind == null || anchor == null || prefix == null || suffix == null || overlapsUiSummary == null
                    || rowExpectedCount < 0 || rowExpectedCount > 2 || rowOppositeCount < 0 || rowOppositeCount > 2
                    || upperLeft && !insidePixelBoundary) {
                throw new IllegalArgumentException("Fixed title diagnostic fields required");
            }
        }
    }

    private static final Sample NOT_OBSERVED = new Sample(Kind.NOT_OBSERVED, 0, 0, Anchor.NONE,
            Surround.NOT_APPLICABLE, Surround.NOT_APPLICABLE, false, false, SummaryOverlap.NOT_AVAILABLE);

    static Sample notObserved() { return NOT_OBSERVED; }

    /** Does not trim, normalize, split, join, infer character boxes, or alter any recognition result. */
    static Sample classify(NativeUiRegions.Role role, String text, NativeUiRegions.Rect row,
                           NativeUiRegions.Rect pixelBoundary, NativeUiRegions.Rect uiSummary) {
        if (role == null || text == null || text.length() > MAX_TEXT || row == null || pixelBoundary == null) {
            return NOT_OBSERVED;
        }
        String expected = role == NativeUiRegions.Role.SEARCH ? SEARCH : READING;
        String opposite = role == NativeUiRegions.Role.SEARCH ? READING : SEARCH;
        int expectedCount = count(text, expected), oppositeCount = count(text, opposite);
        Kind kind = expectedCount > 0 && oppositeCount > 0 ? Kind.BOTH_ROLES
                : text.equals(expected) ? Kind.EXACT_EXPECTED : text.equals(opposite) ? Kind.EXACT_OPPOSITE
                : expectedCount > 0 ? Kind.EXPECTED_EMBEDDED : oppositeCount > 0 ? Kind.OPPOSITE_EMBEDDED
                : Kind.NO_LITERAL;
        Anchor anchor = expectedCount > 0 ? Anchor.EXPECTED : oppositeCount > 0 ? Anchor.OPPOSITE : Anchor.NONE;
        String literal = anchor == Anchor.EXPECTED ? expected : opposite;
        int at = anchor == Anchor.NONE ? -1 : text.indexOf(literal);
        Surround prefix = at < 0 ? Surround.NOT_APPLICABLE : surround(text, 0, at);
        Surround suffix = at < 0 ? Surround.NOT_APPLICABLE : surround(text, at + literal.length(), text.length());
        boolean inside = pixelBoundary.contains(row);
        int width = pixelBoundary.right() - pixelBoundary.left(), height = pixelBoundary.bottom() - pixelBoundary.top();
        // The same fixed upper-left geometry as the refiner, used only for explanation.
        boolean upperLeft = inside && (long) (row.left() - pixelBoundary.left()) * 3 <= width
                && (long) (row.top() - pixelBoundary.top()) * 3 <= height
                && (long) (row.bottom() - pixelBoundary.top()) * 2 <= height;
        // Geometric overlap only: this does not assert that a row contains a counter or merged text.
        SummaryOverlap overlap = uiSummary == null ? SummaryOverlap.NOT_AVAILABLE
                : overlaps(row, uiSummary) ? SummaryOverlap.YES : SummaryOverlap.NO;
        return new Sample(kind, expectedCount, oppositeCount, anchor, prefix, suffix, inside, upperLeft, overlap);
    }

    private static int count(String text, String literal) {
        int count = 0, from = 0;
        while (count < 2) {
            int at = text.indexOf(literal, from);
            if (at < 0) break;
            count++; from = at + literal.length();
        }
        return count;
    }

    private static Surround surround(String text, int from, int until) {
        if (from == until) return Surround.NONE;
        for (int at = from; at < until;) {
            int cp = text.codePointAt(at);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) return Surround.OTHER;
            at += Character.charCount(cp);
        }
        return Surround.WHITESPACE;
    }

    private static boolean overlaps(NativeUiRegions.Rect a, NativeUiRegions.Rect b) {
        return a.left() < b.right() && a.right() > b.left() && a.top() < b.bottom() && a.bottom() > b.top();
    }

    private UiTitleDiagnostic() { }
}
