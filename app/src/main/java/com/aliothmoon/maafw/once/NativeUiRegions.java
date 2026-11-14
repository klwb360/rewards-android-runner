package com.aliothmoon.maafw.once;

/** Fixed task regions only. No source strings, node identity, action target or authority. */
final class NativeUiRegions {
    enum Role { SEARCH, READING }

    record Rect(int left, int top, int right, int bottom) {
        Rect {
            if (left < 0 || top < 0 || right <= left || bottom <= top
                    || right > 8192 || bottom > 8192) {
                throw new IllegalArgumentException("region bounds");
            }
        }

        boolean contains(Rect other) {
            return other != null && other.left >= left && other.top >= top
                    && other.right <= right && other.bottom <= bottom;
        }
    }

    record Region(Role role, Rect card, Rect summary) {
        Region {
            if (role == null || card == null || summary != null && !card.contains(summary)) {
                throw new IllegalArgumentException("task region");
            }
        }
    }

    record Pair(Region search, Region reading) {
        Pair {
            if (search != null && search.role() != Role.SEARCH
                    || reading != null && reading.role() != Role.READING) {
                throw new IllegalArgumentException("region role");
            }
        }

        static Pair empty() { return new Pair(null, null); }
    }

    private NativeUiRegions() { }
}
