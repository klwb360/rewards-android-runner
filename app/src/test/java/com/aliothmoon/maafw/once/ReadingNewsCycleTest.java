package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public final class ReadingNewsCycleTest {
    private static final String DAY = "2026-11-19";
    private static final ReadingNewsCycle.Kind ARTICLE = ReadingNewsCycle.Kind.ARTICLE;
    private static final ReadingNewsCycle.Kind AD = ReadingNewsCycle.Kind.AD;
    private static final ReadingNewsCycle.Kind UNKNOWN = ReadingNewsCycle.Kind.UNKNOWN;
    private static String hash(String text) { return LiveRewardsTypes.hash(text); }
    private static ReadingNewsLedger ledger(LiveRewardsLedger.Store store) throws IOException {
        return new ReadingNewsLedger(store, DAY, UUID.randomUUID().toString());
    }
    private static ReadingNewsLedger progressLedger(LiveRewardsLedger.Store store)throws IOException{
        return new ReadingNewsLedger(store,DAY,UUID.randomUUID().toString(),true);
    }
    private static ReadingNewsCycle.CardProof card(ReadingNewsCycle.Kind kind, String id) {
        return new ReadingNewsCycle.CardProof(kind, kind == ARTICLE ? hash(id) : "", hash("layout-" + id), false);
    }
    private static List<ReadingNewsCycle.CardProof> articles(int count) {
        List<ReadingNewsCycle.CardProof> cards = new ArrayList<>();
        for (int i = 0; i < count; i++) cards.add(card(ARTICLE, "article-" + i));
        return cards;
    }
    private static final class Feed implements ReadingNewsCycle.Ports {
        final List<ReadingNewsCycle.CardProof> cards;
        final List<Long> dwell = new ArrayList<>();
        final List<String> tapped = new ArrayList<>();
        ReadingNewsCycle.CardProof fresh, clicked;
        ReadingNewsCycle.BurnGate storedGate;
        long clock = 1_000, success;
        long openingSampleMillis;
        boolean openingDenied;
        int anchor, moves, backCalls, inFlight, autoAt, failAt, reorderAfter, pageChecks;
        boolean revoked, revokeDuringWait, shortSleep, stalled, bypassTap, returnUncertain, clean = true;
        boolean changedBeforeTap, changedAfterReserve;
        boolean coverDuringTap, coverAtFinalGate;
        ReadingNewsCycle.Kind replacementWhileCovered;
        ReadingNewsCycle.Page destination = ReadingNewsCycle.Page.ARTICLE;
        Feed(List<ReadingNewsCycle.CardProof> cards) { this.cards = cards; }
        public long now() { return clock; }
        public boolean current() { return !revoked; }
        public boolean knownClean() { return clean; }
        public void assertFeed() { assertEquals("No list mutation on an article", 0, inFlight); }
        public ReadingNewsCycle.CardProof readCard() {
            assertFeed();
            if (anchor >= cards.size()) {
                fresh = new ReadingNewsCycle.CardProof(ReadingNewsCycle.Kind.END, "", "", true);
            } else {
                var c = cards.get(anchor);
                fresh = new ReadingNewsCycle.CardProof(c.kind(), c.key(), c.layoutKey(), anchor+1 == cards.size());
            }
            return fresh;
        }
        public long tapArticle(ReadingNewsCycle.CardProof card, ReadingNewsCycle.BurnGate gate) throws Exception {
            assertSame("Tap must use the latest read", fresh, card);
            assertEquals(ARTICLE, card.kind());
            storedGate = gate;
            if (bypassTap) return now();
            if (changedBeforeTap) {changedBeforeTap=false;throw new ReadingNewsCycle.CardChangedBeforeDispatch();}
            if(coverDuringTap){
                coverDuringTap=false;
                if(replacementWhileCovered!=null)cards.set(anchor,ReadingNewsCycleTest.card(replacementWhileCovered,"covered"));
                NewsDeviceEvidenceTest.rejectCoveredGate(coverAtFinalGate,gate);
            }
            clock += 75;
            gate.burn();
            if (changedAfterReserve) throw new ReadingNewsCycle.CardChangedBeforeDispatch();
            tapped.add(card.key());
            assertEquals(1, ++inFlight);
            clicked = card;
            fresh = null;
            if (tapped.size() == failAt) throw new IOException("Injected uncertain tap outcome");
            clock += 300;
            success = now();
            return success;
        }
        public void observeArticleOpen(ReadingNewsCycle.CardProof c){
            assertSame(clicked,c);clock+=openingSampleMillis;
            if(openingDenied)throw new SecurityException("NEWS_ROUTE_FOREIGN_FOCUS");
        }
        public ReadingNewsCycle.Page pageAfterWait(ReadingNewsCycle.CardProof c) {
            assertSame(clicked, c);
            assertEquals(1, inFlight);
            assertTrue(now()-success >= ReadingNewsCycle.WAIT_MILLIS);
            pageChecks++;
            dwell.add(now()-success);
            if (pageChecks == autoAt) {
                inFlight = 0;
                return ReadingNewsCycle.Page.FEED;
            }
            return destination;
        }
        public void back(ReadingNewsCycle.CardProof c, ReadingNewsCycle.BurnGate gate) throws Exception {
            assertSame(clicked, c);
            assertTrue(now()-success >= ReadingNewsCycle.WAIT_MILLIS);
            gate.burn();
            backCalls++;
            if (returnUncertain) throw new IOException("Injected unknown return");
            inFlight = 0;
            if (backCalls == reorderAfter) anchor = 0;
        }
        public void advance(ReadingNewsCycle.CardProof c) {
            assertFeed();
            assertSame("Swipe must be bound to the reacquired list", fresh, c);
            moves++;
            if (!stalled) anchor++;
            fresh = null;
        }
        public void pause(long ms) {
            assertTrue(ms > 0 && ms <= ReadingNewsCycle.WAIT_MILLIS);
            clock += shortSleep ? Math.min(4_000, ms) : ms;
            if (revokeDuringWait) revoked = true;
        }
    }

    @Test public void twoDistinctPilotVisitsExpandToThirteenAndStopWithoutFinalSwipe() throws Exception {
        var store = new LiveRewardsLedger.MemoryStore();
        var journal = ledger(store);
        var feed = new Feed(articles(15));
        feed.shortSleep = true;
        var cycle = new ReadingNewsCycle(journal);
        var result = cycle.run(feed);
        assertTrue(result.pilotPassed());
        assertEquals(ReadingNewsCycle.Status.DAILY_LIMIT, result.status());
        assertEquals(13, result.taps());
        assertEquals(13, result.openedAndReturned());
        assertEquals(13, result.backs());
        assertEquals(13, journal.used());
        assertEquals(12, result.swipes());
        assertEquals(13, new java.util.HashSet<>(feed.tapped).size());
        for (var visit : result.visits()) assertEquals(10_000, visit.waitMillis());
        assertThrows(IllegalStateException.class, () -> cycle.run(feed));
        assertThrows(SecurityException.class, feed.storedGate::burn);
    }
    @Test public void optionalOpeningObservationUsesTimeSinceClickAndKeepsConfirmedTapOnFailure()throws Exception{
        for(long sample:new long[]{0,3500,11000}){
            var feed=new Feed(articles(1));feed.openingSampleMillis=sample;
            var result=new ReadingNewsCycle(ledger(new LiveRewardsLedger.MemoryStore())).run(feed);
            assertEquals(1,result.backs());assertEquals(Math.max(10000,sample),result.visits().get(0).waitMillis());
        }
        var journal=ledger(new LiveRewardsLedger.MemoryStore());
        var denied=new Feed(articles(1));denied.openingDenied=true;
        var cycle=new ReadingNewsCycle(journal);
        assertThrows(SecurityException.class,()->cycle.run(denied));
        assertEquals(1,cycle.snapshot().taps());assertEquals(0,denied.backCalls);assertEquals(1,journal.used());
    }

    @Test public void adsUnreadableAndDuplicatesConsumeNoTapBudget() throws Exception {
        var journal = ledger(new LiveRewardsLedger.MemoryStore());
        var feed = new Feed(List.of(card(AD,"sponsor"),card(ARTICLE,"one"),card(UNKNOWN,"clipped"),
                card(ARTICLE,"one"),card(AD,"other"),card(ARTICLE,"two")));
        var result = new ReadingNewsCycle(journal).run(feed);
        assertEquals(ReadingNewsCycle.Status.FEED_END,result.status());
        assertEquals(List.of(hash("one"),hash("two")),feed.tapped);
        assertEquals(2,journal.used());
        assertEquals(2,result.ads());
        assertEquals(1,result.unreadable());
        assertEquals(1,result.duplicateArticles());
        assertEquals(5,result.swipes());
        assertTrue(result.pilotPassed());
    }

    @Test public void onRestartUnknownAttemptStaysSpentAndDailyCapRemainsGlobal() throws Exception {
        var store = new LiveRewardsLedger.MemoryStore();
        var first = ledger(store);
        var broken = new Feed(articles(15));
        broken.failAt = 1;
        var cycle = new ReadingNewsCycle(first);
        assertThrows(IOException.class, () -> cycle.run(broken));
        assertEquals(ReadingNewsCycle.Status.STOPPED,cycle.snapshot().status());
        assertEquals(1,cycle.snapshot().attempts());
        assertEquals(0,cycle.snapshot().taps());
        assertEquals(0,broken.backCalls);
        var next = ledger(store);
        var newFeed = new Feed(articles(15));
        var result = new ReadingNewsCycle(next).run(newFeed);
        assertEquals(1,result.duplicateArticles());
        assertEquals(12,result.taps());
        assertFalse(newFeed.tapped.contains(hash("article-0")));
        assertEquals(13,next.used());
        var full = ledger(store);
        var exhausted = new ReadingNewsCycle(full).run(new Feed(articles(20)));
        assertEquals(0,exhausted.attempts());
        assertEquals(0,exhausted.swipes());
    }

    @Test public void automaticListReturnHasNoBackAndDoesNotQualifyPilot() throws Exception {
        var feed = new Feed(articles(15));
        feed.autoAt = 2;
        var result = new ReadingNewsCycle(ledger(new LiveRewardsLedger.MemoryStore())).run(feed);
        assertEquals(ReadingNewsCycle.Status.PILOT_UNCONFIRMED,result.status());
        assertFalse(result.pilotPassed());
        assertEquals(2,result.taps());
        assertEquals(1,result.backs());
        assertEquals(1,result.autoReturns());
        assertEquals(1,result.openedAndReturned());
        assertTrue(result.visits().get(1).automatic());
    }

    @Test public void confirmedPilotCanContinueAfterAutomaticReturn() throws Exception {
        var feed = new Feed(articles(4));
        feed.autoAt = 3;
        var result = new ReadingNewsCycle(ledger(new LiveRewardsLedger.MemoryStore())).run(feed);
        assertEquals(ReadingNewsCycle.Status.FEED_END,result.status());
        assertTrue(result.pilotPassed());
        assertEquals(1,result.autoReturns());
        assertEquals(3,result.openedAndReturned());
        assertEquals(3,result.backs());
    }

    @Test public void frozenAnchorStopsAfterTwoMovesAndReorderingCannotUseOldCardProof() throws Exception {
        var stuck = new Feed(articles(4)); stuck.stalled = true;
        var stop = new ReadingNewsCycle(ledger(new LiveRewardsLedger.MemoryStore())).run(stuck);
        assertEquals(ReadingNewsCycle.Status.NO_MOVEMENT,stop.status());
        assertEquals(1,stuck.tapped.size());
        assertEquals(2,stuck.moves);
        var reordered = new Feed(articles(6)); reordered.reorderAfter = 2;
        var result = new ReadingNewsCycle(ledger(new LiveRewardsLedger.MemoryStore())).run(reordered);
        assertEquals(6,result.openedAndReturned());
        assertEquals(6,new java.util.HashSet<>(reordered.tapped).size());
        assertTrue(result.duplicateArticles() >= 1);
    }

    @Test public void unknownForeignOrUncleanPagesNeverTriggerASecondAction() throws Exception {
        for (var page : List.of(ReadingNewsCycle.Page.UNKNOWN, ReadingNewsCycle.Page.BLOCKED)) {
            var feed = new Feed(articles(3)); feed.destination = page;
            assertThrows(SecurityException.class, () -> new ReadingNewsCycle(ledger(new LiveRewardsLedger.MemoryStore())).run(feed));
            assertEquals(1,feed.tapped.size());
            assertEquals(0,feed.backCalls);
            assertEquals(0,feed.moves);
        }
        var noSource = new Feed(articles(3)); noSource.clean = false;
        assertThrows(SecurityException.class, () -> new ReadingNewsCycle(ledger(new LiveRewardsLedger.MemoryStore())).run(noSource));
        assertEquals(0,noSource.tapped.size());
        var canceled = new Feed(articles(3)); canceled.revokeDuringWait = true;
        assertThrows(SecurityException.class, () -> new ReadingNewsCycle(ledger(new LiveRewardsLedger.MemoryStore())).run(canceled));
        assertEquals(0,canceled.pageChecks);
        assertEquals(0,canceled.backCalls);
        var failedBack = new Feed(articles(3)); failedBack.returnUncertain = true;
        assertThrows(IOException.class, () -> new ReadingNewsCycle(ledger(new LiveRewardsLedger.MemoryStore())).run(failedBack));
        assertEquals(1,failedBack.backCalls);
        assertEquals(0,failedBack.moves);
    }

    @Test public void changedPictureBeforeDispatchSkipsOneSlotWithoutCountingAnAttempt() throws Exception{
        var journal=ledger(new LiveRewardsLedger.MemoryStore());
        var feed=new Feed(articles(3));feed.changedBeforeTap=true;
        var result=new ReadingNewsCycle(journal).run(feed);
        assertTrue(result.pilotPassed());assertEquals(2,result.openedAndReturned());assertEquals(2,result.attempts());
        assertEquals(1,result.unreadable());assertEquals(2,journal.used());assertEquals(2,feed.moves);
        assertFalse(journal.attempted(hash("article-0")));
        var reserved=ledger(new LiveRewardsLedger.MemoryStore());
        var bad=new Feed(articles(3));bad.changedAfterReserve=true;
        assertThrows(SecurityException.class,()->new ReadingNewsCycle(reserved).run(bad));
        assertEquals(1,reserved.used());assertEquals(0,bad.moves);
    }
    @Test public void initialAndLateCoverSkipStraightToNextStoryWithoutAReservationWaitOrBack()throws Exception{
        for(int phase=0;phase<3;phase++){
            var journal=ledger(new LiveRewardsLedger.MemoryStore());
            var cards=articles(2);
            if(phase==0)cards.set(0,NewsAndroidPort.scrollOnly(NewsDeviceEvidenceTest.overlaySlot(true)));
            var feed=new Feed(cards);
            feed.coverDuringTap=phase>0;
            feed.coverAtFinalGate=phase==2;
            var result=new ReadingNewsCycle(journal).run(feed);
            assertEquals(ReadingNewsCycle.Status.FEED_END,result.status());
            assertEquals(1,result.attempts());assertEquals(1,journal.used());assertEquals(1,result.unreadable());
            assertEquals(1,feed.moves);assertEquals(List.of(hash("article-1")),feed.tapped);
            assertEquals(1,result.openedAndReturned());assertEquals(1,feed.backCalls);assertEquals(List.of(10_000L),feed.dwell);
            assertFalse(journal.attempted(hash("article-0")));
        }
    }
    @Test public void persistentCoverOrVisibleAdAfterLateRejectionIsOneSkippedSlot()throws Exception{
        for(var reread:List.of(UNKNOWN,AD)){
            var journal=ledger(new LiveRewardsLedger.MemoryStore());
            var feed=new Feed(articles(2));
            feed.coverDuringTap=true;feed.coverAtFinalGate=true;feed.replacementWhileCovered=reread;
            var result=new ReadingNewsCycle(journal).run(feed);
            assertEquals(reread==AD?1:0,result.ads());assertEquals(reread==UNKNOWN?1:0,result.unreadable());
            assertEquals(1,journal.used());assertEquals(List.of(hash("article-1")),feed.tapped);
            assertEquals(1,feed.moves);assertEquals(1,feed.backCalls);
        }
    }

    @Test public void missingInputGateOrEarlyReturnCannotBeReportedAsSuccess() throws Exception {
        var journal = ledger(new LiveRewardsLedger.MemoryStore());
        var bypass = new Feed(articles(1)); bypass.bypassTap = true;
        assertThrows(SecurityException.class, () -> new ReadingNewsCycle(journal).run(bypass));
        assertThrows(SecurityException.class, bypass.storedGate::burn);
        assertEquals(0,journal.used());
        var attempt = journal.reserve(hash("one"));
        assertThrows(IOException.class, () -> attempt.reserveReturn(99_000));
        attempt.tapConfirmed(950);
        assertThrows(IOException.class, () -> attempt.reserveReturn(10_949));
        attempt.reserveReturn(10_950);
        assertThrows(IOException.class, () -> attempt.reserveReturn(11_000));
        assertThrows(IOException.class, () -> attempt.finish(true));
        attempt.finish(false);
        assertThrows(IOException.class, () -> attempt.finish(false));
    }

    @Test public void parallelReservationsCannotRepeatAnIdentityOrExceedDayLimit() throws Exception {
        var shared = new LiveRewardsLedger.MemoryStore();
        var pool = Executors.newFixedThreadPool(5);
        try {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                var journal = ledger(shared);
                String key = hash("candidate-" + (i % 19));
                requests.add(() -> {
                    try { journal.reserve(key); return true; }
                    catch (IOException occupied) { return false; }
                });
            }
            int issued = 0;
            for (var future : pool.invokeAll(requests)) if (future.get()) issued++;
            assertEquals(13,issued);
            assertEquals(13,ledger(shared).used());
            var nextDay = new ReadingNewsLedger(shared,"2026-11-20",UUID.randomUUID().toString());
            assertEquals(0,nextDay.used());
            assertFalse(nextDay.attempted(hash("candidate-1")));
        } finally { pool.shutdownNow(); }
    }

    @Test public void progressModeStopsAfterOneNewArticleAndSharesFiveAcrossFreshRuns()throws Exception{
        var store=new LiveRewardsLedger.MemoryStore();
        for(int batch=1;batch<=5;batch++){
            var journal=progressLedger(store);
            var feed=new Feed(articles(16));
            if(batch==2)feed.autoAt=1;
            var result=new ReadingNewsCycle(journal).run(feed);
            assertEquals(batch==5?ReadingNewsCycle.Status.FIVE_LIMIT:ReadingNewsCycle.Status.PROGRESS_CHECK_REQUIRED,result.status());
            assertEquals(1,result.attempts());assertEquals(1,result.taps());
            assertEquals(batch==2?0:1,result.backs());assertFalse(result.pilotPassed());
            assertEquals(List.of(hash("article-"+(batch-1))),feed.tapped);
            assertEquals(batch,journal.progressUsed());assertEquals(batch,journal.used());
        }
        var next=new ReadingNewsCycle(progressLedger(store)).run(new Feed(articles(16)));
        assertEquals(ReadingNewsCycle.Status.FIVE_LIMIT,next.status());assertEquals(0,next.taps());
        var legacy=new ReadingNewsCycle(ledger(store)).run(new Feed(articles(16)));
        assertEquals(ReadingNewsCycle.Status.DAILY_LIMIT,legacy.status());
        assertEquals(8,legacy.taps());assertEquals(5,progressLedger(store).progressUsed());
        var followingDay=new ReadingNewsLedger(store,"2026-11-20",UUID.randomUUID().toString(),true);
        assertEquals(0,followingDay.progressUsed());
    }

    @Test public void sevenNewArticlesRunContinuouslyAfterFiveAndBothBudgetsSurviveRestart()throws Exception{
        var store=new LiveRewardsLedger.MemoryStore();
        for(int i=0;i<5;i++)progressLedger(store).reserve(hash("article-"+i));
        var seven=new ReadingNewsLedger(store,DAY,UUID.randomUUID().toString(),ReadingNewsChoice.Mode.SEVEN);
        var feed=new Feed(articles(16));
        var result=new ReadingNewsCycle(seven).run(feed);
        assertEquals(ReadingNewsCycle.Status.SEVEN_LIMIT,result.status());
        assertEquals(7,result.taps());assertEquals(7,result.backs());assertEquals(7,result.openedAndReturned());
        for(int i=0;i<7;i++)assertEquals(hash("article-"+(i+5)),feed.tapped.get(i));
        assertTrue(feed.dwell.stream().allMatch(ms->ms>=10_000));
        assertEquals(12,seven.used());assertEquals(7,seven.progressUsed());assertEquals(5,progressLedger(store).progressUsed());
        var restarted=new ReadingNewsLedger(store,DAY,UUID.randomUUID().toString(),ReadingNewsChoice.Mode.SEVEN);
        var stopped=new ReadingNewsCycle(restarted).run(new Feed(articles(20)));
        assertEquals(ReadingNewsCycle.Status.SEVEN_LIMIT,stopped.status());assertEquals(0,stopped.taps());
        assertThrows(IOException.class,()->restarted.reserve(hash("overflow")));
    }
    @Test public void continuousSevenStopsAtSharedDailyLimitWhenFewerThanSevenSlotsRemain()throws Exception{
        var store=new LiveRewardsLedger.MemoryStore();
        for(int i=0;i<11;i++)ledger(store).reserve(hash("previous-"+i));
        var seven=new ReadingNewsLedger(store,DAY,UUID.randomUUID().toString(),ReadingNewsChoice.Mode.SEVEN);
        var result=new ReadingNewsCycle(seven).run(new Feed(articles(12)));
        assertEquals(ReadingNewsCycle.Status.DAILY_LIMIT,result.status());assertEquals(2,result.taps());
        assertEquals(2,seven.progressUsed());assertEquals(0,progressLedger(store).progressUsed());
    }
    @Test public void mixedRunsKeepTheirDailyLimitAndConcurrentProgressTapsCannotExceedFive()throws Exception{
        var usedDay=new LiveRewardsLedger.MemoryStore();
        for(int i=0;i<12;i++)ledger(usedDay).reserve(hash("prior-"+i));
        var last=new ReadingNewsCycle(progressLedger(usedDay)).run(new Feed(articles(2)));
        assertEquals(ReadingNewsCycle.Status.DAILY_LIMIT,last.status());assertEquals(1,last.taps());
        var shared=new LiveRewardsLedger.MemoryStore();
        var pool=Executors.newFixedThreadPool(6);
        try{
            List<Callable<Boolean>> work=new ArrayList<>();
            for(int i=0;i<20;i++){
                String key=hash("limited-"+i);var journal=progressLedger(shared);
                work.add(()->{try{journal.reserve(key);return true;}catch(IOException occupied){return false;}});
            }
            int granted=0;
            for(var future:pool.invokeAll(work))if(future.get())granted++;
            assertEquals(5,granted);assertEquals(5,ledger(shared).used());assertEquals(5,progressLedger(shared).progressUsed());
        }finally{pool.shutdownNow();}
    }
}
