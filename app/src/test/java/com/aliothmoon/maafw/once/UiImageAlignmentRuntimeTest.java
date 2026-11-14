package com.aliothmoon.maafw.once;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure metadata reservations and cleanup decisions. No Android, image, OCR or durable burn runs. */
public class UiImageAlignmentRuntimeTest {
    private static final String OWN = "com.aliothmoon.maafw.productrewardsworking.capturereview"
            + "/com.aliothmoon.maafw.once.DormantCaptureConfirmationActivity";
    private BingNavigationSnapshot.ReadOnlyGuard guard() {
        return new BingNavigationSnapshot.ReadOnlyGuard(10001, 17, OWN);
    }
    private UiImageAlignmentRuntime.MetadataPermit permit() {
        return new UiImageAlignmentRuntime.MetadataPermit(guard(), () -> true);
    }

    @Test public void oneExactReaderCanReserveOnlyFourOrderedSourcePairs() {
        var permit = permit();
        assertEquals(guard(), permit.claimReader());
        for (var stage : UiImageAlignmentRuntime.MetadataStage.values()) {
            assertTrue(permit.read(stage)); assertTrue(permit.current());
        }
        assertEquals(4, permit.pairsReserved());
        assertFalse(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE));
        assertFalse(permit.current()); assertEquals(4, permit.pairsReserved());
    }

    @Test public void eachOutOfOrderPairPermanentlyRejectsRemainingMetadata() {
        var stages = UiImageAlignmentRuntime.MetadataStage.values();
        for (int next = 0; next < stages.length; next++) {
            for (int wrong = 0; wrong < stages.length; wrong++) {
                if (wrong == next) continue;
                var permit = permit(); assertNotNull(permit.claimReader());
                for (int i = 0; i < next; i++) assertTrue(permit.read(stages[i]));
                assertFalse(permit.read(stages[wrong]));
                for (var stage : stages) assertFalse(permit.read(stage));
                assertNull(permit.claimReader()); assertEquals(next, permit.pairsReserved());
            }
        }
    }

    @Test public void readingBeforeClaimDoesNotLeaveAReusableReader() {
        var permit = permit();
        assertFalse(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE));
        assertNull(permit.claimReader()); assertFalse(permit.current());
        assertEquals(0, permit.pairsReserved());
    }

    @Test public void aSecondReaderRevokesEvenTheFirstReadersUnspentPairs() {
        var permit = permit(); assertNotNull(permit.claimReader());
        assertTrue(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE));
        assertNull(permit.claimReader()); assertFalse(permit.current());
        assertFalse(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_AFTER));
        assertEquals(1, permit.pairsReserved());
    }

    @Test public void nullStageConsumesThePermitWithoutReservingACommandPair() {
        var permit = permit(); assertNotNull(permit.claimReader());
        assertFalse(permit.read(null)); assertFalse(permit.current());
        assertFalse(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE));
        assertEquals(0, permit.pairsReserved());
    }

    @Test public void exactOwnUidTaskAndComponentAreRequiredBeforeAReaderExists() {
        BingNavigationSnapshot.ReadOnlyGuard[] invalid = {null,
                new BingNavigationSnapshot.ReadOnlyGuard(9999, 17, OWN),
                new BingNavigationSnapshot.ReadOnlyGuard(10001, 0, OWN),
                new BingNavigationSnapshot.ReadOnlyGuard(10001, 17, "com.microsoft.bing/.MainActivity"),
                new BingNavigationSnapshot.ReadOnlyGuard(10001, 17, null)};
        for (var guard : invalid) {
            var permit = new UiImageAlignmentRuntime.MetadataPermit(guard, () -> true);
            assertNull(permit.claimReader()); assertFalse(permit.current());
            assertFalse(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE));
        }
    }

    @Test public void revokedVisibilityOrDeadlineCannotBeRestoredByAProvider() {
        var live = new AtomicBoolean(true);
        var permit = new UiImageAlignmentRuntime.MetadataPermit(guard(), live::get);
        assertNotNull(permit.claimReader()); assertTrue(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE));
        live.set(false); assertFalse(permit.current());
        live.set(true); assertFalse(permit.current());
        assertFalse(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_AFTER));
        assertEquals(1, permit.pairsReserved());
    }

    @Test public void failedCurrentReadDoesNotReserveAnotherPair() {
        var live = new AtomicBoolean(true);
        var permit = new UiImageAlignmentRuntime.MetadataPermit(guard(), live::get);
        assertNotNull(permit.claimReader()); live.set(false);
        assertFalse(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE));
        live.set(true); assertFalse(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE));
        assertEquals(0, permit.pairsReserved());
    }

    @Test public void unavailableCurrentProviderFailsClosedWithoutReturningExceptionText() {
        var throwing = new UiImageAlignmentRuntime.MetadataPermit(guard(), () -> {
            throw new IllegalStateException("private provider detail");
        });
        assertNull(throwing.claimReader()); assertFalse(throwing.current());
        var missing = new UiImageAlignmentRuntime.MetadataPermit(guard(), null);
        assertNull(missing.claimReader()); assertFalse(missing.current());
    }

    @Test public void racingReaderClaimsHaveOneWinnerAndPoisonFurtherReads() throws Exception {
        var permit = permit(); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var won = new AtomicInteger(); var failed = new AtomicInteger();
        Runnable race = () -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) { failed.incrementAndGet(); return; }
                if (permit.claimReader() != null) won.incrementAndGet();
            } catch (InterruptedException interrupted) { failed.incrementAndGet(); Thread.currentThread().interrupt(); }
        };
        var first = new Thread(race); var second = new Thread(race);
        first.start(); second.start(); assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
        first.join(5000); second.join(5000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        assertEquals(0, failed.get()); assertEquals(1, won.get());
        assertFalse(permit.read(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE));
        assertEquals(0, permit.pairsReserved());
    }

    private OnceBackend.Result result(OnceBackend.Phase phase, OnceBackend.Cleanup cleanup,
            OnceBackend.Quiescence quiet, OnceBackend.Memory memory) {
        return new OnceBackend.Result(null, 0, phase, OnceBackend.Admission.NOT_ADMITTED,
                OnceBackend.Cancellation.NOT_REQUESTED, false, OnceBackend.Failure.NONE, cleanup, quiet, memory);
    }
    private boolean closed(OnceBackend.Result result) {
        return UiImageAlignmentRuntime.resourcesClosed(true, true, false, false, true, result);
    }

    @Test public void noNativeBindingIsClosedOnlyWhenEveryEarlierResourceIsKnownClean() {
        assertTrue(UiImageAlignmentRuntime.resourcesClosed(true, true, false, false, false, null));
        assertFalse(UiImageAlignmentRuntime.resourcesClosed(false, true, false, false, false, null));
        assertFalse(UiImageAlignmentRuntime.resourcesClosed(true, false, false, false, false, null));
        assertFalse(UiImageAlignmentRuntime.resourcesClosed(true, true, true, false, false, null));
        assertFalse(UiImageAlignmentRuntime.resourcesClosed(true, true, false, true, false, null));
        assertFalse(UiImageAlignmentRuntime.resourcesClosed(true, true, false, false, false,
                result(OnceBackend.Phase.COMPLETED, OnceBackend.Cleanup.CONFIRMED,
                        OnceBackend.Quiescence.CONFIRMED, OnceBackend.Memory.WIPED)));
    }

    @Test public void idleAndEveryNonterminalBackendRemainRetained() {
        assertFalse(closed(null));
        for (var phase : OnceBackend.Phase.values()) {
            if (phase == OnceBackend.Phase.COMPLETED || phase == OnceBackend.Phase.FAILED
                    || phase == OnceBackend.Phase.CANCELLED) continue;
            assertFalse(phase.name(), closed(result(phase, OnceBackend.Cleanup.CONFIRMED,
                    OnceBackend.Quiescence.CONFIRMED, OnceBackend.Memory.WIPED)));
        }
    }

    @Test public void actualCleanTerminalCompletionCanReleaseRegardlessOfBusinessOutcome() {
        for (var phase : new OnceBackend.Phase[]{OnceBackend.Phase.COMPLETED, OnceBackend.Phase.FAILED, OnceBackend.Phase.CANCELLED}) {
            assertTrue(closed(result(phase, OnceBackend.Cleanup.CONFIRMED, OnceBackend.Quiescence.CONFIRMED, OnceBackend.Memory.WIPED)));
            assertTrue(closed(result(phase, OnceBackend.Cleanup.CONFIRMED, OnceBackend.Quiescence.CONFIRMED, OnceBackend.Memory.NOT_ALLOCATED)));
            assertTrue(closed(result(phase, OnceBackend.Cleanup.NOT_NEEDED, OnceBackend.Quiescence.NOT_INVOKED, OnceBackend.Memory.NOT_ALLOCATED)));
        }
    }

    @Test public void completionOrWipeWithoutCleanupAndQuiescenceIsInsufficient() {
        for (var cleanup : OnceBackend.Cleanup.values()) {
            for (var quiet : OnceBackend.Quiescence.values()) {
                if (cleanup == OnceBackend.Cleanup.CONFIRMED && quiet == OnceBackend.Quiescence.CONFIRMED) continue;
                assertFalse(cleanup + "/" + quiet,
                        closed(result(OnceBackend.Phase.COMPLETED, cleanup, quiet, OnceBackend.Memory.WIPED)));
            }
        }
        assertFalse(closed(result(OnceBackend.Phase.COMPLETED, null, OnceBackend.Quiescence.CONFIRMED, OnceBackend.Memory.WIPED)));
        assertFalse(closed(result(OnceBackend.Phase.COMPLETED, OnceBackend.Cleanup.CONFIRMED, null, OnceBackend.Memory.WIPED)));
    }

    @Test public void retainedOrUnknownMemoryCanNeverRelease() {
        for (var memory : new OnceBackend.Memory[]{OnceBackend.Memory.RETAINED, null}) {
            assertFalse(closed(result(OnceBackend.Phase.COMPLETED, OnceBackend.Cleanup.CONFIRMED, OnceBackend.Quiescence.CONFIRMED, memory)));
            assertFalse(closed(result(OnceBackend.Phase.FAILED, OnceBackend.Cleanup.NOT_NEEDED, OnceBackend.Quiescence.NOT_INVOKED, memory)));
        }
    }

    @Test public void cleanNativeResultCannotOverrideUiMetadataConstructionOrClaimUnknown() {
        var result = result(OnceBackend.Phase.COMPLETED, OnceBackend.Cleanup.CONFIRMED, OnceBackend.Quiescence.CONFIRMED, OnceBackend.Memory.WIPED);
        assertFalse(UiImageAlignmentRuntime.resourcesClosed(false, true, false, false, true, result));
        assertFalse(UiImageAlignmentRuntime.resourcesClosed(true, false, false, false, true, result));
        assertFalse(UiImageAlignmentRuntime.resourcesClosed(true, true, true, false, true, result));
        assertFalse(UiImageAlignmentRuntime.resourcesClosed(true, true, false, true, true, result));
    }

    @Test public void claimBoundaryExceptionRemainsUnknownEvenWithoutAPreviousScalarFlag() {
        var result = new OnceBackend.Result(null, 0, OnceBackend.Phase.FAILED, OnceBackend.Admission.NOT_ADMITTED,
                OnceBackend.Cancellation.NOT_REQUESTED, false, OnceBackend.Failure.CLAIM_UNCERTAIN,
                OnceBackend.Cleanup.NOT_NEEDED, OnceBackend.Quiescence.NOT_INVOKED, OnceBackend.Memory.NOT_ALLOCATED);
        assertFalse(closed(result));
    }
}
