package com.aliothmoon.maafw.once;

/** Additional purpose bound to the existing synchronous, visible owner confirmation. */
final class ReadingNewsChoice {
    enum Mode { PREFLIGHT, LIVE }
    private record Selection(Object owner, Mode mode) {}
    private final ThreadLocal<Selection> active = new ThreadLocal<>();

    void confirm(Object owner, Mode mode, Runnable start) {
        if (owner == null || mode == null || start == null || active.get() != null)
            throw new IllegalStateException("Fresh news confirmation required");
        active.set(new Selection(owner, mode));
        try { start.run(); } finally { active.remove(); }
    }

    Mode take(Object owner, boolean live, boolean singleOrLimited) {
        Selection selection = active.get();
        if (selection == null) return null;
        if (selection.owner() != owner || selection.mode() == null || singleOrLimited
                || live != (selection.mode() == Mode.LIVE))
            throw new SecurityException("Reading mode differs from visible purpose");
        active.set(new Selection(owner, null));
        return selection.mode();
    }
}
