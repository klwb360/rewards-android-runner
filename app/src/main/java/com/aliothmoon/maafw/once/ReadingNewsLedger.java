package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

/** Append-only exposure budget shared by all native news runs on the same Shanghai day. */
final class ReadingNewsLedger {
    static final String DIRECTORY = "rewards-news-reading-v1";
    static final int DAILY_LIMIT = 13;
    private final LiveRewardsLedger.Store store;
    private final String dayRoot;
    private final String runId;

    ReadingNewsLedger(Path noBackupRoot, String day, String runId) throws IOException {
        this(new LiveRewardsLedger.FileStore(noBackupRoot), day, runId);
    }

    ReadingNewsLedger(LiveRewardsLedger.Store store, String day, String runId) throws IOException {
        if (store == null || day == null || !day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                || !LocalDate.parse(day).toString().equals(day) || runId == null
                || !UUID.fromString(runId).toString().equals(runId)) {
            throw new IllegalArgumentException("Exact news run identity required");
        }
        this.store = store;
        this.dayRoot = DIRECTORY + "/" + day;
        this.runId = runId;
        store.create(dayRoot + "/runs/" + runId + "/RUN.burned", "profile=READING_NEWS_V1 day=" + day + "\n");
    }

    int used() throws IOException {
        int count = 0;
        for (int slot = 1; slot <= DAILY_LIMIT; slot++) {
            if (store.exists(slotPath(slot))) count++;
        }
        return count;
    }

    boolean attempted(String key) throws IOException {
        return store.exists(articleRoot(key) + "/PENDING.burned");
    }

    private String slotPath(int slot) {
        return dayRoot + "/slots/" + slot + ".burned";
    }

    private String articleRoot(String key) {
        if (!LiveRewardsTypes.hashValue(key)) throw new IllegalArgumentException("Hashed news identity required");
        return dayRoot + "/articles/" + key;
    }

    /** Called at the final tap gate. Only this owner may confirm or return from this article. */
    Attempt reserve(String key) throws IOException {
        String root = articleRoot(key);
        if (used() == DAILY_LIMIT) throw new IOException("NEWS_DAILY_LIMIT");
        store.create(root + "/PENDING.burned", "run=" + runId + " state=PENDING\n");
        // A crash between these creates leaves the identity spent and no issued tap.
        // Exclusive slots keep the global cap even if two fresh runs compete for the last slot.
        for (int slot = 1; slot <= DAILY_LIMIT; slot++) {
            String path = slotPath(slot);
            if (store.exists(path)) continue;
            try {
                store.create(path, "run=" + runId + " article=" + key + "\n");
                return new Attempt(root);
            } catch (FileAlreadyExistsException occupied) {
                if (!store.exists(path)) throw occupied;
            }
        }
        throw new IOException("NEWS_DAILY_LIMIT");
    }

    final class Attempt {
        private final String root;
        private long tapElapsed = -1;
        private boolean returnReserved, finished;

        private Attempt(String root) { this.root = root; }

        synchronized void tapConfirmed(long elapsed) throws IOException {
            if (tapElapsed >= 0 || elapsed < 0) throw new IOException("Duplicate or invalid news tap receipt");
            tapElapsed = elapsed;
            store.create(root + "/TAP_OK.burned", "run=" + runId + " elapsed=" + elapsed + "\n");
        }

        synchronized void reserveReturn(long elapsed) throws IOException {
            if (finished || returnReserved || tapElapsed < 0 || elapsed < tapElapsed
                    || elapsed - tapElapsed < ReadingNewsCycle.WAIT_MILLIS) {
                throw new IOException("News return is not due");
            }
            returnReserved = true;
            store.create(root + "/BACK.burned", "run=" + runId + " elapsed=" + elapsed + "\n");
        }

        synchronized void finish(boolean automaticallyReturned) throws IOException {
            if (finished || tapElapsed < 0 || returnReserved == automaticallyReturned) {
                throw new IOException("News return evidence mismatch");
            }
            finished = true;
            store.create(root + "/RESULT.burned", "run=" + runId + " outcome="
                    + (automaticallyReturned ? "AUTO_RETURN" : "OPENED_AND_RETURNED") + "\n");
        }
    }
}
