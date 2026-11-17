package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic Android metadata, UI observations and task ports. No real input or device documents. */
public class LiveDisplayRecoveryTest {
    private static final int TASK = 79;
    private static final String WINDOW = LiveNavigationSnapshotTest.originalWindows(TASK);
    private static final String ACTIVITY = LiveNavigationSnapshotTest.originalActivities(TASK);
    private static final String MISSING = WINDOW.replace("mTopFocusedDisplayId=0\n", "");
    private static final BingNavigationSnapshot.Profile PROFILE = BingNavigationFixture.PROFILE;
    private static final BingNavigationSnapshot.ReadOnlyGuard GUARD = RootReadOnlyAnimationGuardTest.G;

    private static BingNavigationSnapshot.Ready anchor(long now) {
        return LiveNavigationSnapshotTest.original(TASK, now);
    }

    private static boolean permitsWait(String window, String activity, BingNavigationSnapshot.Ready anchor) {
        return BingNavigationSnapshot.onlyDisplayMissing(window, activity, PROFILE, GUARD, anchor, 1000, 1010);
    }

    @Test public void knownTemplateWithExactlyOneMissingFieldMayWaitButCannotReturnReady() {
        var result = BingNavigationSnapshot.parseCurrentTemplate(MISSING, ACTIVITY, PROFILE, GUARD, 1000, 1010);
        assertNull(result.source());
        assertEquals(BingNavigationSnapshot.FirstGate.FOCUS_FORMAT, result.firstGate());
        assertTrue(permitsWait(MISSING, ACTIVITY, anchor(1000)));
        assertFalse(permitsWait(MISSING, ACTIVITY, null));
        assertFalse(permitsWait(MISSING, ACTIVITY, LiveNavigationSnapshotTest.original(TASK + 1, 1000)));
    }

    @Test public void malformedDuplicateNullWrongDisplayAndOtherFocusCannotTriggerAWait() {
        var base = anchor(1000);
        for (String display : List.of("mTopFocusedDisplayId=0", "mTopFocusedDisplayId=1",
                "mTopFocusedDisplayId=null", "mTopFocusedDisplayId =0", "mTopFocusedDisplayId=0\nmTopFocusedDisplayId=0")) {
            assertFalse(permitsWait(display + "\n" + MISSING, ACTIVITY, base));
        }
        for (String window : List.of(
                MISSING + "mCurrentFocus=null\n", MISSING + "mFocusedApp = null\n",
                MISSING.replace("mCurrentFocus=Window{aaa", "mCurrentFocus=Window{bbb"),
                MISSING.replace("mFocusedApp=ActivityRecord{ddd", "mFocusedApp=ActivityRecord{aaa"),
                MISSING.replace("u0 " + BingNavigationSnapshot.TEMPLATE, "u1 " + BingNavigationSnapshot.TEMPLATE),
                MISSING.replaceFirst("com\\.microsoft\\.bing/", "com.example.other/"))) {
            assertFalse(permitsWait(window, ACTIVITY, base));
        }
    }

    @Test public void aSecondRejectionBehindTheMissingDisplayStillStopsImmediately() {
        var base = anchor(1000);
        assertFalse(permitsWait(MISSING + BingNavigationFixture.window("InputMethod", "abab", true), ACTIVITY, base));
        assertFalse(permitsWait(MISSING.replace("mDrawState=HAS_DRAWN", "mDrawState=DRAW_PENDING"), ACTIVITY, base));
        assertFalse(permitsWait(MISSING.replace("mOwnerUid=10073", "mOwnerUid=10035"), ACTIVITY, base));
        assertFalse(permitsWait(MISSING, ACTIVITY + "topResumedActivity=null\n", base));
        assertFalse(permitsWait(MISSING, ACTIVITY.replace("state=RESUMED", "state=PAUSING"), base));
        assertFalse(permitsWait(MISSING, ACTIVITY.replace("Display #0", "Display #1"), base));
        assertFalse(BingNavigationSnapshot.onlyDisplayMissing(MISSING, ACTIVITY, PROFILE, GUARD, base, 1000, 7000));
    }

    @Test public void aStaleOrDifferentReadyCannotAuthorizeAFreshObservation() {
        var old = anchor(1000);
        for (boolean stale : List.of(false, true)) {
            long[] now = {1000};
            AtomicInteger reads = new AtomicInteger();
            assertThrows(SecurityException.class, () -> LiveDisplayRecovery.afterMissingDisplay(old, () -> true,
                    () -> now[0], delay -> now[0] += delay, () -> {
                        reads.incrementAndGet();
                        return stale ? old : LiveNavigationSnapshotTest.original(TASK + 1, now[0]);
                    }));
            assertEquals(1, reads.get());
        }
    }

    @Test public void scopeExpiringDuringWaitDoesNotStartAnotherPair() {
        long[] now = {1000};
        var result = assertThrows(SecurityException.class, () -> LiveDisplayRecovery.afterMissingDisplay(anchor(1000),
                () -> now[0] < 1500, () -> now[0], delay -> now[0] += delay,
                () -> {throw new AssertionError("Expired scope read metadata");}));
        assertFalse(result instanceof LiveDisplayRecovery.MissingDisplay);
    }

    @Test public void unknownFrameCleanupNeverStartsTheStrictReplacement() throws Exception {
        var f = new LiveCoreFixture();
        f.attach();
        var scope = f.authority.scanScope(LiveRewardsTypes.Kind.DAILY);
        var ui = scope.claimUi(new Object(), f.clock.now);
        assertTrue(ui.claimReader(f.clock.now));
        assertTrue(ui.consume(f.clock.now));
        AtomicInteger frames = new AtomicInteger();
        assertThrows(SecurityException.class, () -> LiveRewardsPerception.readFreshUi(true, canRecover -> {
            frames.incrementAndGet();
            throw LiveRewardsPerception.discardRecoveredUi(scope, ui, false);
        }));
        assertEquals(1, frames.get());
        assertFalse(f.authority.current());
        assertFalse(ui.current(f.clock.now));
    }

    /** Real coordinator, journal and scope state machine with a synthetic UI/source port. */
    private static final class RecoveryRig extends LiveRewardsCoordinatorTest.Rig {
        final List<Long> pairOffsets = new ArrayList<>();
        final int missingRechecks;
        long gapAt;
        LiveRunAuthority.UiPermit discarded;

        RecoveryRig(int missingRechecks) throws Exception {
            super(List.of(LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN, 0, "TEST_SIGN_IN")));
            this.missingRechecks = missingRechecks;
        }

        @Override public LiveRewardsTypes.Observation observe(LiveRunAuthority.CardScope scope,
                LiveRewardsTypes.Phase phase, LiveRewardsTypes.Task expected) throws Exception {
            if (phase != LiveRewardsTypes.Phase.BEFORE) return super.observe(scope, phase, expected);
            var original = anchor(f.clock.now);
            return LiveRewardsPerception.readFreshUi(true, mayRecover -> {
                if (!mayRecover) {
                    assertNotNull(discarded);
                    assertFalse(discarded.current(f.clock.now));
                    assertThrows(SecurityException.class, () -> scope.claimCrop(discarded, phase, f.clock.now));
                    var fresh = super.observe(scope, phase, expected);
                    assertTrue(original.sameSource(fresh.source()));
                    assertTrue(fresh.data().acquiredElapsed() > gapAt);
                    return fresh;
                }
                var firstFrame = scope.claimUi(new Object(), f.clock.now);
                assertTrue(firstFrame.claimReader(f.clock.now));
                assertTrue(firstFrame.consume(f.clock.now));
                gapAt = f.clock.now;
                assertTrue(permitsWait(MISSING, ACTIVITY, original));
                LiveDisplayRecovery.afterMissingDisplay(original, () -> firstFrame.current(f.clock.now),
                        () -> f.clock.now, delay -> f.clock.now += delay, () -> {
                            pairOffsets.add(f.clock.now - gapAt);
                            if (pairOffsets.size() <= missingRechecks) throw new LiveDisplayRecovery.MissingDisplay();
                            return anchor(f.clock.now);
                        });
                discarded = firstFrame;
                throw LiveRewardsPerception.discardRecoveredUi(scope, firstFrame, true);
            });
        }
    }

    @Test public void recoveredDisplayRequiresNewCardEvidenceAndDispatchesExactlyOnce() throws Exception {
        for (int missingRechecks : List.of(0, 1)) {
            var rig = new RecoveryRig(missingRechecks);
            var summary = rig.run();
            assertFalse(summary.reason(), summary.stopped());
            assertEquals(missingRechecks == 0 ? List.of(750L) : List.of(750L, 2000L), rig.pairOffsets);
            assertEquals(List.of(LiveRewardsTypes.Kind.CHECK_IN), rig.tappedKinds);
            assertEquals(1, rig.tapCalls);
            assertEquals(0, rig.returnCalls);
        }
    }

    @Test public void persistentDisplayGapEndsTheRunBeforeTheTaskLedgerOrAnyInput() throws Exception {
        var rig = new RecoveryRig(2);
        assertTrue(rig.run().stopped());
        assertEquals(List.of(750L, 2000L), rig.pairOffsets);
        assertEquals(0, rig.tapCalls);
        assertEquals(0, rig.returnCalls);
        assertFalse(rig.ledger.attempted(rig.tasks.get(0)));
    }
}
