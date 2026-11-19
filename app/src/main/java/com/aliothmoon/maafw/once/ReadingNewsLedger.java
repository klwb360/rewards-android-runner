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
    static final int PROGRESS_LIMIT = 5;
    private final LiveRewardsLedger.Store store;
    private final String dayRoot;
    private final String runId;
    final boolean progressBatch;

    ReadingNewsLedger(Path noBackupRoot, String day, String runId) throws IOException {
        this(new LiveRewardsLedger.FileStore(noBackupRoot), day, runId);
    }

    ReadingNewsLedger(LiveRewardsLedger.Store store, String day, String runId) throws IOException {
        this(store,day,runId,false);
    }

    ReadingNewsLedger(LiveRewardsLedger.Store store,String day,String runId,boolean progressBatch) throws IOException {
        if (store == null || day == null || !day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                || !LocalDate.parse(day).toString().equals(day) || runId == null
                || !UUID.fromString(runId).toString().equals(runId)) {
            throw new IllegalArgumentException("Exact news run identity required");
        }
        this.store = store;
        this.dayRoot = DIRECTORY + "/" + day;
        this.runId = runId;
        this.progressBatch=progressBatch;
        store.create(dayRoot + "/runs/" + runId + "/RUN.burned", "profile=READING_NEWS_V1 day=" + day + "\n");
    }

    int used() throws IOException {
        return used(false);
    }

    int progressUsed() throws IOException {
        return used(true);
    }

    private int used(boolean progress) throws IOException {
        int count = 0;
        for (int slot = 1; slot <= (progress?PROGRESS_LIMIT:DAILY_LIMIT); slot++) {
            if (store.exists(slotPath(slot,progress))) count++;
        }
        return count;
    }

    boolean attempted(String key) throws IOException {
        return store.exists(articleRoot(key) + "/PENDING.burned");
    }

    private String slotPath(int slot,boolean progress) {
        return dayRoot + (progress?"/progress-five-v1/slots/":"/slots/") + slot + ".burned";
    }

    private String articleRoot(String key) {
        if (!LiveRewardsTypes.hashValue(key)) throw new IllegalArgumentException("Hashed news identity required");
        return dayRoot + "/articles/" + key;
    }

    /** Called at the final tap gate. Only this owner may confirm or return from this article. */
    Attempt reserve(String key) throws IOException {
        String root = articleRoot(key);
        if (used() == DAILY_LIMIT) throw new IOException("NEWS_DAILY_LIMIT");
        if(progressBatch && progressUsed()==PROGRESS_LIMIT)throw new IOException("NEWS_FIVE_LIMIT");
        store.create(root + "/PENDING.burned", "run=" + runId + " state=PENDING\n");
        // A crash between these creates leaves the identity spent and no issued tap.
        // Exclusive slots keep the global cap even if two fresh runs compete for the last slot.
        // The progress cohort survives process restarts. Both exclusive reservations must succeed
        // before a physical tap; an interrupted reservation can only reduce the remaining budget.
        if(progressBatch)claimSlot(key,true);
        claimSlot(key,false);
        return new Attempt(root);
    }

    private void claimSlot(String key,boolean progress)throws IOException{
        for (int slot = 1; slot <= (progress?PROGRESS_LIMIT:DAILY_LIMIT); slot++) {
            String path = slotPath(slot,progress);
            if (store.exists(path)) continue;
            try {
                store.create(path, "run=" + runId + " article=" + key + "\n");
                return;
            } catch (FileAlreadyExistsException occupied) {
                if (!store.exists(path)) throw occupied;
            }
        }
        throw new IOException(progress?"NEWS_FIVE_LIMIT":"NEWS_DAILY_LIMIT");
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
