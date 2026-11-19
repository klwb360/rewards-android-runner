package com.aliothmoon.maafw.once;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One ordered batch in Bing, with a distinct, append-only reservation for each Shanghai-day letter. */
final class AlphabetSearchCycle {
    static final long WAIT_MILLIS = 5_000L;
    static final String DIRECTORY = "rewards-alphabet-search-v1";
    static final char FIRST = 'a', LAST = 't';
    private static final int LETTERS = LAST - FIRST + 1;
    private static final AtomicBoolean IN_USE = new AtomicBoolean();

    interface Ports {
        void requireOrigin() throws Exception;
        void prepare(char letter) throws Exception;
        void submit(char letter) throws Exception;
        void back() throws Exception;
        boolean current();
        long now();
        void pause(long millis) throws Exception;
    }

    enum Status { READY, RUNNING, COMPLETE, EXHAUSTED_WITH_UNCONFIRMED, STOPPED }

    record Summary(Status status, int claimed, int submitted, int backs, int returned,
                   int previousAttempts, int previousReturns) {}

    private final LiveRewardsLedger.Store store;
    private final String day;
    private final String runId;
    private final Supplier<String> shanghaiDay;
    private final AtomicBoolean started = new AtomicBoolean();
    private long firstTime = -1, lastTime = -1;
    private int claimed, submitted, backs, returned, previousAttempts, previousReturns;
    private volatile Summary latest = new Summary(Status.READY, 0, 0, 0, 0, 0, 0);

    AlphabetSearchCycle(LiveRewardsLedger.Store store, String day, String runId, Supplier<String> shanghaiDay) {
        this.store = Objects.requireNonNull(store);
        this.shanghaiDay = Objects.requireNonNull(shanghaiDay);
        if (day == null || !day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !LocalDate.parse(day).toString().equals(day)
                || runId == null || !UUID.fromString(runId).toString().equals(runId))
            throw new IllegalArgumentException("A date and canonical run ID are required");
        this.day = day;
        this.runId = runId;
    }

    Summary snapshot() { return latest; }

    Summary run(Ports ports) throws Exception {
        Objects.requireNonNull(ports);
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("SEARCH_RUN_ALREADY_USED");
        if (!IN_USE.compareAndSet(false, true)) {
            publish(Status.STOPPED);
            throw new IllegalStateException("SEARCH_ALREADY_RUNNING");
        }
        try {
            activeTime(ports);
            String dayRoot = DIRECTORY + "/" + day;
            store.create(dayRoot + "/runs/" + runId + "/RUN.burned", "day=" + day + "\n");
            publish(Status.RUNNING);
            boolean originChecked = false;
            for (char letter = FIRST; letter <= LAST; letter++) {
                activeTime(ports);
                String path = dayRoot + "/letters/" + letter + "/";
                if (store.exists(path + "PENDING.burned")) {
                    previousAttempts++;
                    if (store.exists(path + "RETURNED.burned")) previousReturns++;
                    publish(Status.RUNNING);
                    continue;
                }
                if (!originChecked) {
                    ports.requireOrigin();
                    activeTime(ports);
                    originChecked = true;
                }
                search(ports, letter, path);
            }
            activeTime(ports);
            return publish(previousReturns + returned == LETTERS ? Status.COMPLETE : Status.EXHAUSTED_WITH_UNCONFIRMED);
        } catch (Exception stopped) {
            publish(Status.STOPPED);
            throw stopped;
        } finally {
            IN_USE.set(false);
        }
    }

    private void search(Ports ports, char letter, String path) throws Exception {
        ports.prepare(letter);
        activeTime(ports);
        String owner = "run=" + runId;
        store.create(path + "PENDING.burned", owner + "\n");
        claimed++;
        publish(Status.RUNNING);
        activeTime(ports);
        ports.submit(letter);
        long success = ports.now();
        submitted++;
        publish(Status.RUNNING);
        observeTime(success);
        store.create(path + "SUBMITTED.burned", owner + " elapsed=" + success + "\n");

        long now = activeTime(ports);
        while (now - success < WAIT_MILLIS) {
            ports.pause(WAIT_MILLIS - (now - success));
            long after = activeTime(ports);
            if (after <= now) throw new IllegalStateException("SEARCH_WAIT_STALLED");
            now = after;
        }
        store.create(path + "BACK.burned", owner + " elapsed=" + now + "\n");
        activeTime(ports);
        ports.back();
        backs++;
        publish(Status.RUNNING);
        activeTime(ports);
        ports.requireOrigin();
        activeTime(ports);
        store.create(path + "RETURNED.burned", owner + "\n");
        returned++;
        publish(Status.RUNNING);
    }

    private long activeTime(Ports ports) {
        if (Thread.currentThread().isInterrupted() || !ports.current())
            throw new IllegalStateException("SEARCH_RUN_INACTIVE");
        if (!day.equals(shanghaiDay.get())) throw new IllegalStateException("SEARCH_DATE_CHANGED");
        long now = ports.now();
        observeTime(now);
        return now;
    }

    private void observeTime(long now) {
        if (now < 0 || now < lastTime) throw new IllegalStateException("SEARCH_CLOCK_INVALID");
        if (firstTime < 0) firstTime = now;
        if (now - firstTime >= LiveRunAuthority.MAX_RUN_MILLIS)
            throw new IllegalStateException("SEARCH_RUN_EXPIRED");
        lastTime = now;
    }

    private Summary publish(Status status) {
        latest = new Summary(status, claimed, submitted, backs, returned, previousAttempts, previousReturns);
        return latest;
    }
}
