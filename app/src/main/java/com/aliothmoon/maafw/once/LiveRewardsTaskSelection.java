package com.aliothmoon.maafw.once;

import java.time.LocalDate;
import java.util.List;

/** The visible selection can only narrow the normal source, task and ledger gates. */
final class LiveRewardsTaskSelection {
    static final LiveRewardsTaskSelection ALL = new LiveRewardsTaskSelection("", List.of());
    private final String day;
    private final List<String> keys;

    private LiveRewardsTaskSelection(String day, List<String> keys) {
        this.day = day;
        this.keys = keys;
    }

    static LiveRewardsTaskSelection morePair(String text, String today) {
        if (text == null || text.length() != 140) throw new IllegalArgumentException("Expected a date and two task keys");
        String[] fields = text.split(":", -1);
        if (fields.length != 3 || !fields[0].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                || !fields[0].equals(today) || !LocalDate.parse(today).toString().equals(today)
                || !LiveRewardsTypes.hashValue(fields[1]) || !LiveRewardsTypes.hashValue(fields[2])
                || fields[1].equals(fields[2])) throw new IllegalArgumentException("Invalid dated task selection");
        return new LiveRewardsTaskSelection(today, List.of(fields[1], fields[2]));
    }

    boolean limited() { return this != ALL; }
    boolean current(String today) { return !limited() || day.equals(today); }
    boolean includes(LiveRewardsTypes.Kind kind) { return !limited() || kind == LiveRewardsTypes.Kind.MORE; }
    boolean includes(LiveRewardsTypes.Task task) {
        return task != null && includes(task.kind()) && (!limited() || keys.contains(task.key()));
    }
    int regionCount() { return limited() ? 1 : LiveRewardsTypes.Kind.values().length; }

    /** Only exists during the exact button callback. It carries a selection, never input authority. */
    static final class VisibleChoice {
        private record Bound(Object activity, LiveRewardsTaskSelection selection) {}
        private final ThreadLocal<Bound> click = new ThreadLocal<>();

        void confirm(Object activity, LiveRewardsTaskSelection selection, Runnable start) {
            if (activity == null || selection == null || start == null || click.get() != null)
                throw new IllegalStateException("Fresh visible selection required");
            click.set(new Bound(activity, selection));
            try {
                start.run();
            } finally {
                click.remove();
            }
        }

        LiveRewardsTaskSelection take(Object activity) {
            var bound = click.get();
            if (bound == null || bound.activity() != activity || bound.selection() == null)
                throw new SecurityException("Missing visible task selection");
            // Keep a consumed slot until the callback returns, including through nested UI callbacks.
            click.set(new Bound(activity, null));
            return bound.selection();
        }
    }
}
