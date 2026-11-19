package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs only inside a verified Bing news feed. News points are not an input or a result. */
final class ReadingNewsCycle {
    static final long WAIT_MILLIS = 10_000L;
    static final long RUN_MILLIS = 30 * 60_000L;
    enum Kind { ARTICLE, AD, UNKNOWN, END }
    enum Page { ARTICLE, FEED, BLOCKED, UNKNOWN }
    enum Status { READY, RUNNING, DAILY_LIMIT, FIVE_LIMIT, SEVEN_LIMIT, PROGRESS_CHECK_REQUIRED, FEED_END, NO_MOVEMENT, PILOT_UNCONFIRMED, STOPPED }
    static final class CardChangedBeforeDispatch extends Exception {
        private static final long serialVersionUID=1L;
        CardChangedBeforeDispatch(){super("NEWS_CARD_CHANGED_BEFORE_DISPATCH");}
    }
    private enum VisitResult { CONFIRMED, AUTO_RETURN, CHANGED_BEFORE_DISPATCH }

    record CardProof(Kind kind, String key, String layoutKey, boolean atEnd) {
        CardProof {
            if (kind == null || key == null || layoutKey == null
                    || (kind == Kind.ARTICLE ? !LiveRewardsTypes.hashValue(key) : !key.isEmpty())
                    || (kind == Kind.END ? !atEnd || !layoutKey.isEmpty() : !LiveRewardsTypes.hashValue(layoutKey))) {
                throw new IllegalArgumentException("Bounded news card proof required");
            }
        }
        boolean sameCard(CardProof other) {
            if (kind != other.kind) return false;
            return kind == Kind.ARTICLE ? key.equals(other.key) : layoutKey.equals(other.layoutKey);
        }
    }

    interface BurnGate { void burn() throws Exception; }
    interface Ports {
        void assertFeed() throws Exception;
        CardProof readCard() throws Exception;
        long tapArticle(CardProof card, BurnGate gate) throws Exception;
        default void observeArticleOpen(CardProof card) throws Exception {}
        Page pageAfterWait(CardProof card) throws Exception;
        void back(CardProof card, BurnGate gate) throws Exception;
        void advance(CardProof current) throws Exception;
        boolean current();
        boolean knownClean();
        long now();
        void pause(long millis) throws Exception;
    }

    record Visit(String articleKey, long tapElapsed, long waitMillis, boolean automatic) {}
    record Summary(Status status, boolean pilotPassed, int attempts, int taps, int backs, int openedAndReturned,
                   int autoReturns, int ads, int duplicateArticles, int unreadable, int swipes, List<Visit> visits) {
        Summary { visits = List.copyOf(visits); }
    }

    private static final class Once implements BurnGate {
        private final BurnGate action;
        private boolean available = true, entered, consumed;
        Once(BurnGate action) { this.action = action; }
        @Override public synchronized void burn() throws Exception {
            if (!available) throw new SecurityException("News input gate already spent");
            available = false;
            entered = true;
            action.burn();
            consumed = true;
        }
        synchronized void close() { available = false; }
        synchronized boolean closeUnused(){available=false;return !entered;}
        synchronized void checkConsumed() {
            if (!consumed) throw new SecurityException("News input has no durable reservation");
        }
    }

    private final ReadingNewsLedger ledger;
    private final AtomicBoolean started = new AtomicBoolean();
    private final List<Visit> visits = new ArrayList<>();
    private volatile Summary published;
    private long startedAt;
    private int attempts, taps, backs, completed, autoReturns, ads, duplicates, unreadable, swipes;
    private Status status = Status.READY;

    ReadingNewsCycle(ReadingNewsLedger ledger) {
        if (ledger == null) throw new IllegalArgumentException("News ledger required");
        this.ledger = ledger;
        publish();
    }

    Summary snapshot() { return published; }

    static Status reachedLimit(ReadingNewsLedger ledger)throws java.io.IOException{
        if(ledger.used()>=ReadingNewsLedger.DAILY_LIMIT)return Status.DAILY_LIMIT;
        if((ledger.progressBatch || ledger.sevenBatch) && ledger.progressUsed()>=ledger.batchLimit())
            return ledger.sevenBatch?Status.SEVEN_LIMIT:Status.FIVE_LIMIT;
        return null;
    }

    Summary run(Ports ports) throws Exception {
        if (ports == null || !started.compareAndSet(false, true)) throw new IllegalStateException("One news run required");
        startedAt = ports.now();
        status = Status.RUNNING;
        publish();
        try {
            requireCurrent(ports);
            ports.assertFeed();
            requireCurrent(ports);
            Status limit=reachedLimit(ledger);
            if(limit!=null)return stop(limit);
            CardProof card = read(ports);
            while (true) {
                requireCurrent(ports);
                if (card.kind() == Kind.END) return stop(Status.FEED_END);
                limit=reachedLimit(ledger);
                if(limit!=null)return stop(limit);
                if (card.kind() == Kind.ARTICLE && !ledger.attempted(card.key())) {
                    VisitResult result = visit(ports, card);
                    if(ledger.progressBatch && result!=VisitResult.CHANGED_BEFORE_DISPATCH){
                        limit=reachedLimit(ledger);
                        return stop(limit==null?Status.PROGRESS_CHECK_REQUIRED:limit);
                    }
                    if (result == VisitResult.AUTO_RETURN && completed < 2) return stop(Status.PILOT_UNCONFIRMED);
                    limit=reachedLimit(ledger);
                    if(limit!=null)return stop(limit);
                    // Returning can reorder/repopulate the list. A pre-tap proof can never authorize a swipe.
                    CardProof returned = read(ports);
                    if (result != VisitResult.CHANGED_BEFORE_DISPATCH && !card.sameCard(returned) && returned.atEnd()) {
                        card = returned;
                        continue;
                    }
                    if (returned.kind() == Kind.AD) ads++;
                    else if (returned.kind() == Kind.UNKNOWN || result == VisitResult.CHANGED_BEFORE_DISPATCH) unreadable++;
                    card = returned;
                } else {
                    switch (card.kind()) {
                        case ARTICLE -> duplicates++;
                        case AD -> ads++;
                        case UNKNOWN -> unreadable++;
                        default -> throw new IllegalStateException("Unexpected news candidate");
                    }
                }
                if (card.atEnd()) return stop(Status.FEED_END);
                CardProof next = nextCard(ports, card);
                if (next == null) return stop(Status.NO_MOVEMENT);
                card = next;
            }
        } catch (Exception failure) {
            stop(Status.STOPPED);
            throw failure;
        }
    }

    private CardProof nextCard(Ports ports, CardProof current) throws Exception {
        for (int move = 0; move < 2; move++) {
            requireCurrent(ports);
            ports.advance(current);
            swipes++;
            CardProof next = read(ports);
            if (!current.sameCard(next)) return next;
            if (next.atEnd()) return new CardProof(Kind.END, "", "", true);
            current = next;
        }
        return null;
    }

    private VisitResult visit(Ports ports, CardProof card) throws Exception {
        ReadingNewsLedger.Attempt[] owner = {null};
        long[] dispatched = {-1};
        Once tap = new Once(() -> {
            requireCurrent(ports);
            owner[0] = ledger.reserve(card.key());
            attempts++;
            requireCurrent(ports);
            dispatched[0] = ports.now();
        });
        long success;
        try {
            success = ports.tapArticle(card, tap);
        } catch (CardChangedBeforeDispatch changed) {
            if(!tap.closeUnused())throw new SecurityException("News card changed after input reservation",changed);
            requireCurrent(ports);
            return VisitResult.CHANGED_BEFORE_DISPATCH;
        } finally {
            tap.close();
        }
        tap.checkConsumed();
        if (success < dispatched[0] || success > ports.now()) throw new SecurityException("Invalid successful tap clock");
        taps++;
        owner[0].tapConfirmed(success);
        requireCurrent(ports);
        publish();
        ports.observeArticleOpen(card);
        long waited = waitAfterTap(ports, success);
        Page destination = ports.pageAfterWait(card);
        requireCurrent(ports);
        boolean automatic = destination == Page.FEED;
        if (!automatic) {
            if (destination != Page.ARTICLE) throw new SecurityException("Unknown news article destination");
            Once back = new Once(() -> {
                requireCurrent(ports);
                owner[0].reserveReturn(ports.now());
                requireCurrent(ports);
            });
            try {
                ports.back(card, back);
            } finally {
                back.close();
            }
            back.checkConsumed();
            backs++;
        }
        ports.assertFeed();
        requireCurrent(ports);
        owner[0].finish(automatic);
        if (automatic) autoReturns++;
        else completed++;
        visits.add(new Visit(card.key(), success, waited, automatic));
        publish();
        return automatic?VisitResult.AUTO_RETURN:VisitResult.CONFIRMED;
    }

    private long waitAfterTap(Ports ports, long success) throws Exception {
        long previous = success;
        while (true) {
            requireCurrent(ports);
            long now = ports.now();
            if (now < previous) throw new IllegalStateException("News time moved backwards");
            long elapsed = now - success;
            if (elapsed >= WAIT_MILLIS) return elapsed;
            ports.pause(WAIT_MILLIS - elapsed);
            if (ports.now() <= now) throw new IllegalStateException("News wait did not advance");
            previous = now;
        }
    }

    private CardProof read(Ports ports) throws Exception {
        requireCurrent(ports);
        CardProof result = ports.readCard();
        requireCurrent(ports);
        if (result == null) throw new SecurityException("Missing news source or card evidence");
        return result;
    }

    private void requireCurrent(Ports ports) {
        long now = ports.now();
        if (startedAt < 0 || now < startedAt || now - startedAt >= RUN_MILLIS
                || !ports.current() || !ports.knownClean() || Thread.currentThread().isInterrupted()) {
            throw new SecurityException("News source, time or cleanup unavailable");
        }
    }

    private Summary stop(Status reason) {
        status = reason;
        publish();
        return published;
    }

    private void publish() {
        published = new Summary(status, completed >= 2, attempts, taps, backs, completed, autoReturns,
                ads, duplicates, unreadable, swipes, visits);
    }
}
