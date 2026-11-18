package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

public final class LiveMoreUiRecheckTest {
    private static final class Rig implements LiveMoreUiRecheck.Reader {
        final LiveCoreFixture f = new LiveCoreFixture();
        final LiveMoreUiRecheck probe = new LiveMoreUiRecheck();
        final LiveRunAuthority run = LiveRunAuthority.syntheticReadOnly(new Object(), new RealCaptureClaim.ProcessEpoch(), f.clock, () -> true);
        int calls, refuseAt, failAt, uncleanAt;
        long firstMillis = 400, secondMillis = 200;

        Rig() throws Exception { run.attachLedger(f.ledger); }

        public boolean read(LiveRunAuthority.CardScope scope, LiveRewardsTypes.Phase phase) throws Exception {
            calls++;
            assertTrue(scope.uiRecheckOnly());
            assertEquals(LiveRewardsTypes.Kind.MORE, scope.task().kind());
            assertEquals(calls == 1 ? LiveRewardsTypes.Phase.AFTER_1 : LiveRewardsTypes.Phase.AFTER_2, phase);
            assertTrue(scope.claimUiRecheckPhase(phase));
            if (calls == failAt) throw new IOException("Fixture source rejection");
            f.ui(scope);
            f.clock.now += calls == 1 ? firstMillis : secondMillis;
            return calls != refuseAt;
        }
        public boolean knownClean() { return uncleanAt == 0 || calls != uncleanAt; }
        public String diagnostic() { return "FIXTURE_" + calls; }
        void execute() throws Exception { probe.run(run, this, millis -> f.clock.now += millis); }
    }

    @Test public void twoQualifiedReadsUseOneWindowAndWaitFromFirstFinish() throws Exception {
        var r = new Rig();
        r.execute();
        var a = r.probe.attempts();
        assertEquals(2, a.size()); assertTrue(r.probe.verified());
        assertEquals(LiveMoreUiRecheck.Step.SHARED_FIRST, a.get(0).step());
        assertEquals(LiveMoreUiRecheck.Step.SHARED_SECOND, a.get(1).step());
        assertEquals(a.get(0).started() + 5000, a.get(0).deadline());
        assertEquals(a.get(0).deadline(), a.get(1).deadline());
        assertEquals(1000, a.get(1).started() - a.get(0).finished());
        assertEquals(LiveMoreUiRecheck.Result.QUALIFIED, a.get(1).result());
        assertEquals(0, r.run.taskScopeCount());
        assertThrows(SecurityException.class, r::execute);
    }

    @Test public void cleanPredispatchBudgetRefusalIsDistinctFromFreshIndependentRead() throws Exception {
        var r = new Rig();
        r.refuseAt = 2;
        r.execute();
        var a = r.probe.attempts();
        assertTrue(r.probe.verified()); assertEquals(3, a.size());
        assertEquals(LiveMoreUiRecheck.Result.INSUFFICIENT_BUDGET, a.get(1).result());
        assertEquals(LiveMoreUiRecheck.Step.INDEPENDENT_SECOND, a.get(2).step());
        assertEquals(a.get(2).started() + 5000, a.get(2).deadline());
        assertTrue(a.get(2).deadline() > a.get(1).deadline());
        assertEquals(LiveMoreUiRecheck.Result.QUALIFIED, a.get(2).result());
    }

    @Test public void insufficientRoomForSpacingIsReportedWithoutInventingASecondSharedRead() throws Exception {
        for (long duration : List.of(4000L, 4500L)) {
            var r = new Rig();
            r.firstMillis = duration;
            r.execute();
            var a = r.probe.attempts();
            assertEquals(2, r.calls);
            assertEquals(LiveMoreUiRecheck.Result.NO_TIME_TO_START, a.get(1).result());
            assertEquals("NONE", a.get(1).diagnostic());
            assertEquals(LiveMoreUiRecheck.Step.INDEPENDENT_SECOND, a.get(2).step());
        }
    }

    @Test public void sourceOrCleanupFailureAndEarlyWakeNeverFallThroughToAnotherRead() throws Exception {
        for (int at : List.of(1, 2)) {
            var reject = new Rig(); reject.failAt = at;
            assertThrows(IOException.class, reject::execute);
            assertEquals(at, reject.calls); assertFalse(reject.probe.verified());
            assertEquals(LiveMoreUiRecheck.Result.FAILED, reject.probe.attempts().get(at - 1).result());
            var unknown = new Rig(); unknown.uncleanAt = at;
            assertThrows(SecurityException.class, unknown::execute);
            assertEquals(at, unknown.calls); assertFalse(unknown.probe.verified());
        }
        var wake = new Rig();
        assertThrows(IllegalStateException.class, () -> wake.probe.run(wake.run, wake, wait -> wake.f.clock.now += wait - 1));
        assertEquals(1, wake.calls);
        var late = new Rig(); late.firstMillis = 5001;
        assertThrows(IllegalStateException.class, late::execute);
        assertEquals(1, late.calls);
        var firstBudget = new Rig(); firstBudget.refuseAt = 1;
        assertThrows(IllegalStateException.class, firstBudget::execute);
        assertEquals(1, firstBudget.calls);
    }

    @Test public void readOnlyScopesHaveOnlyBoundedUiReadsAndCannotMixWithExistingAuthorities() throws Exception {
        var r = new Rig();
        var task = LiveCoreFixture.task(LiveRewardsTypes.Kind.MORE, 2, "UNATTEMPTED");
        r.f.attach();
        assertThrows(SecurityException.class, () -> r.f.authority.moreUiRecheckScope(false));
        assertThrows(SecurityException.class, () -> r.run.moreUiRecheckScope(true));
        var shared = r.run.moreUiRecheckScope(false);
        shared.startAfterWindow(r.f.clock.now);
        assertFalse(shared.claimUiRecheckPhase(LiveRewardsTypes.Phase.AFTER_2));
        assertThrows(SecurityException.class, () -> shared.claimUi(new Object(), r.f.clock.now));
        assertTrue(shared.claimUiRecheckPhase(LiveRewardsTypes.Phase.AFTER_1));
        assertFalse(shared.claimUiRecheckPhase(LiveRewardsTypes.Phase.AFTER_1));
        var ui = r.f.ui(shared);
        assertThrows(SecurityException.class, () -> shared.claimScroll(ui, ReviewShizukuSwipeActor.Direction.UP, r.f.clock.now));
        assertThrows(SecurityException.class, () -> shared.claimCrop(ui, LiveRewardsTypes.Phase.AFTER_2, r.f.clock.now));
        assertThrows(SecurityException.class, () -> shared.actionPermit(null, r.f.ledger));
        assertThrows(SecurityException.class, () -> r.run.scanScope(LiveRewardsTypes.Kind.MORE));
        assertThrows(SecurityException.class, () -> r.run.preflightScope(task));
        assertThrows(SecurityException.class, () -> r.run.cardScope(task));
        assertThrows(SecurityException.class, () -> r.run.claimFocusDiagnostics(r.f.clock.now));
        assertFalse(shared.claimUiRecheckPhase(LiveRewardsTypes.Phase.AFTER_1));
        assertTrue(shared.claimUiRecheckPhase(LiveRewardsTypes.Phase.AFTER_2));
        r.f.ui(shared);
        assertThrows(SecurityException.class, () -> shared.claimUi(new Object(), r.f.clock.now));
        var independent = r.run.moreUiRecheckScope(true);
        independent.startAfterWindow(r.f.clock.now);
        assertFalse(shared.current(r.f.clock.now));
        assertFalse(independent.claimUiRecheckPhase(LiveRewardsTypes.Phase.AFTER_1));
        assertTrue(independent.claimUiRecheckPhase(LiveRewardsTypes.Phase.AFTER_2));
        r.f.ui(independent);
        assertThrows(SecurityException.class, () -> independent.claimUi(new Object(), r.f.clock.now));
        assertThrows(SecurityException.class, () -> r.run.moreUiRecheckScope(true));
        assertFalse(r.f.ledger.attempted(task));
        var mixed = new Rig(); mixed.run.scanScope(LiveRewardsTypes.Kind.MORE);
        assertThrows(SecurityException.class, () -> mixed.run.moreUiRecheckScope(false));
    }

    @Test public void explicitChoiceIsScopedToTheSameVisibleButtonAndOwner() {
        var choice = new LiveMoreUiRecheck.Choice(); var owner = new Object();
        assertThrows(SecurityException.class, () -> choice.take(owner));
        choice.confirm(owner, true, () -> {
            assertTrue(choice.pending(owner));
            assertThrows(SecurityException.class, () -> choice.take(new Object()));
            assertTrue(choice.take(owner));
            assertFalse(choice.pending(owner));
            assertThrows(SecurityException.class, () -> choice.take(owner));
            assertThrows(IllegalStateException.class, () -> choice.confirm(owner, false, () -> {}));
        });
        assertThrows(SecurityException.class, () -> choice.take(owner));
        choice.confirm(owner, false, () -> {
            assertFalse(choice.pending(owner));
            assertFalse(choice.take(owner));
        });
        assertThrows(IllegalStateException.class, () -> choice.confirm(owner, true, () -> { throw new IllegalStateException(); }));
        assertThrows(SecurityException.class, () -> choice.take(owner));
    }
}
