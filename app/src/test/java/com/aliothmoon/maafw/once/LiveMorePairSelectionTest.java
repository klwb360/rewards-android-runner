package com.aliothmoon.maafw.once;

import java.util.List;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveMorePairSelectionTest {
    private static final String DAY = LiveCoreFixture.DAY;
    private static final LiveRewardsTypes.Kind MORE = LiveRewardsTypes.Kind.MORE;

    private static LiveRewardsTypes.Task card(int ordinal, String name) {
        return LiveCoreFixture.task(MORE, ordinal, name);
    }

    private static LiveRewardsTaskSelection pair(LiveRewardsTypes.Task first, LiveRewardsTypes.Task second) {
        return LiveRewardsTaskSelection.morePair(DAY + ":" + first.key() + ":" + second.key(), DAY);
    }

    private static LiveRewardsCoordinator.Summary run(LiveRewardsCoordinatorTest.Rig rig, LiveRewardsTaskSelection selection) {
        return new LiveRewardsCoordinator(rig.f.authority, rig.ledger, rig, false, selection).run();
    }

    @Test public void malformedStaleOrRepeatedKeysDoNotProduceASelection() {
        var a = card(1,"A");
        var b = card(2,"B");
        String valid = DAY + ":" + a.key() + ":" + b.key();
        for (String bad : List.of("", valid + " ", valid.replaceFirst(":", "-"), valid.toUpperCase(java.util.Locale.ROOT),
                valid.replace(b.key(),a.key()), valid.replace(DAY, "2026-11-15"))) {
            assertThrows(IllegalArgumentException.class, () -> LiveRewardsTaskSelection.morePair(bad,DAY));
        }
        assertThrows(IllegalArgumentException.class, () -> LiveRewardsTaskSelection.morePair(null,DAY));
        assertFalse(pair(a,b).current("2026-11-17"));
    }

    @Test public void confirmationBelongsOnlyToThatVisibleCallbackAndActivity() {
        Object owner = new Object();
        var choice = new LiveRewardsTaskSelection.VisibleChoice();
        var selected = pair(card(1,"A"),card(2,"B"));
        assertThrows(SecurityException.class, () -> choice.take(owner));
        choice.confirm(owner,selected,() -> {
            assertThrows(SecurityException.class, () -> choice.take(new Object()));
            assertSame(selected, choice.take(owner));
            assertThrows(SecurityException.class, () -> choice.take(owner));
            assertThrows(IllegalStateException.class, () -> choice.confirm(owner,LiveRewardsTaskSelection.ALL,() -> fail()));
        });
        assertThrows(SecurityException.class, () -> choice.take(owner));
        assertThrows(IllegalStateException.class, () -> choice.confirm(owner,selected,() -> {throw new IllegalStateException();}));
        assertThrows(SecurityException.class, () -> choice.take(owner));
        var restarted = new LiveRewardsTaskSelection.VisibleChoice();
        assertThrows(SecurityException.class, () -> restarted.take(owner));
    }

    @Test public void bothSelectedMoreCardsKeepNormalLedgerAndReturnRulesWithoutEnteringOtherRegions() throws Exception {
        var first = card(2,"first");
        var second = card(4,"second");
        var unrelated = card(1,"other");
        var daily = LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"first");
        var checkIn = LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN,0,"first");
        var rig = new LiveRewardsCoordinatorTest.Rig(List.of(checkIn, daily, second, unrelated, first));
        var result = run(rig,pair(first,second));
        assertFalse(result.reason(),result.stopped());
        assertEquals(1,result.regionsReady());
        assertTrue(result.preflightPassed());
        assertEquals(2,result.planned());
        assertEquals(2,result.taps());
        assertEquals(2,result.backs());
        assertEquals(List.of("SCAN_MORE","RESULT_MORE_OBSERVED_COMPLETE","RESULT_MORE_OBSERVED_COMPLETE"),rig.regionEvents);
        for (var target : List.of(first,second)) assertTrue(rig.ledger.attempted(target));
        for (var target : List.of(unrelated,daily,checkIn)) assertFalse(rig.ledger.attempted(target));
    }

    @Test public void duplicateAbsentAttemptedAndCompleteTargetsNeverSelectASubstitute() throws Exception {
        var a = card(1,"A");
        var b = card(2,"B");
        var unknown = card(3,"new task");
        var rig = new LiveRewardsCoordinatorTest.Rig(List.of(a,card(4,"A"),unknown));
        var result = run(rig,pair(a,b));
        assertEquals(0,result.taps());
        assertEquals(Integer.valueOf(2),result.counts().get(LiveRewardsTypes.Outcome.DUPLICATE_SKIP));
        assertFalse(rig.ledger.attempted(unknown));
        var skipped = new LiveRewardsCoordinatorTest.Rig(List.of(a,LiveCoreFixture.completed(b),unknown));
        skipped.ledger.begin(a);
        var summary = run(skipped,pair(a,b));
        assertFalse(summary.stopped());
        assertEquals(0,summary.taps());
        assertEquals(Integer.valueOf(1),summary.counts().get(LiveRewardsTypes.Outcome.ALREADY_ATTEMPTED_SKIP));
        assertEquals(Integer.valueOf(1),summary.counts().get(LiveRewardsTypes.Outcome.COMPLETE_SKIP));
        assertFalse(skipped.ledger.attempted(b));
        assertFalse(skipped.ledger.attempted(unknown));
    }

    @Test public void sourceFailureAndDateRolloverStopBeforeAnyAttempt() throws Exception {
        var a = card(1,"A");
        var b = card(2,"B");
        var lost = new LiveRewardsCoordinatorTest.Rig(List.of(a,b));
        lost.sourceFailureRegion = MORE;
        assertTrue(run(lost,pair(a,b)).stopped());
        assertFalse(lost.ledger.attempted(a));
        var expired = new LiveRewardsCoordinatorTest.Rig(List.of(a,b));
        expired.f.clock.day = "2026-11-17";
        assertTrue(run(expired,pair(a,b)).stopped());
        assertFalse(expired.ledger.attempted(a));
    }

    @Test public void scopedPreviewReadsOnlyThePairAndKeepsTheUnknownSameValuePeerInTheFullRoster() throws Exception {
        var ignored = LiveTaskMatcherTest.task("Old compact task, 5 积分","Old compact task",1);
        var a = LiveTaskMatcherTest.task("A, Description A, 5 积分","A",2);
        var b = LiveTaskMatcherTest.task("B, Description B, 5 积分","B",3);
        var seeds = List.of(ignored,a,b).stream().map(LivePreflightHistory::unreadable).toList();
        var checked = new ArrayList<String>();
        var previewed = new ArrayList<String>();
        var fixture = new LiveCoreFixture();
        fixture.attach();
        var scoped = LiveRewardsPerception.recognizeRoster(seeds, pair(a,b),
                seed -> { checked.add(seed.key()); return false; }, seed -> {
                    previewed.add(seed.key());
                    assertTrue(seed.sameKey(a) || seed.sameKey(b));
                    return fixture.seal(fixture.authority.preflightScope(seed), LiveRewardsTypes.Phase.BEFORE,
                            seed.sameKey(a) ? a : b, 0, 20);
                });
        assertTrue(scoped.known());
        assertEquals(List.of(a.key(),b.key()),checked);
        assertEquals(checked,previewed);
        assertEquals(List.of(LivePreflightHistory.unreadable(ignored),a,b),scoped.tasks());
        var page = LiveTaskMatcherTest.roster("Old compact task, 5 积分","A, Description A, 5 积分","B, Description B, 5 积分");
        assertTrue(LiveTaskMatcher.regionSnapshot(page,MORE).matchesPreflight(scoped.tasks()));
        assertTrue(LiveTaskMatcher.admits(page,a,scoped.tasks()));
        assertTrue(LiveTaskMatcher.admits(page,b,scoped.tasks()));
        var compact = LiveTaskMatcherTest.roster("Old compact task, 5 积分","A, 5 积分","B, Description B, 5 积分");
        assertEquals(LiveTaskMatcher.State.AMBIGUOUS,LiveTaskMatcher.find(compact,a,scoped.tasks()).state());
    }

    @Test public void ordinaryPreviewStillRequiresAnUnrelatedUnrecognizedCardAndSelectedFailureStillStops() throws Exception {
        var bad = card(1,"bad");
        var target = card(2,"target");
        for (var selection : List.of(LiveRewardsTaskSelection.ALL,pair(bad,target))) {
            var fixture = new LiveCoreFixture();
            fixture.attach();
            var result = LiveRewardsPerception.recognizeRoster(List.of(bad,target),selection,seed -> false, seed -> {
                assertEquals(bad.key(),seed.key());
                return fixture.authority.preflightScope(seed).unreadable(LiveRewardsTypes.Phase.BEFORE,seed,
                        fixture.source(),fixture.clock.now,"TEST_UNREADABLE");
            });
            assertFalse(result.known());
        }
    }
}
