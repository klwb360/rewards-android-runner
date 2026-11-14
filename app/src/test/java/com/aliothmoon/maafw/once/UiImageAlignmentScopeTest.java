package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class UiImageAlignmentScopeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String CLAIM = "11111111-1111-1111-1111-111111111111";

    private Path root() { return temporary.getRoot().toPath().toAbsolutePath().normalize(); }
    private Path marker(String slot) { return root().resolve(UiImageAlignmentScope.DIRECTORY).resolve(slot + ".burned"); }

    @Test public void fixedScopeHasOnlyOneReadOneFrameOneOcrAndNoActions() {
        assertEquals("rewards-ui-image-alignment-v1", UiImageAlignmentScope.DIRECTORY);
        assertEquals(180_000L, UiImageAlignmentScope.SESSION_TTL);
        assertEquals(90_000L, UiImageAlignmentScope.CAPTURE_TTL);
        assertEquals(1, UiImageAlignmentScope.MAX_UI_READS);
        assertEquals(1, UiImageAlignmentScope.MAX_FRAMES);
        assertEquals(1, UiImageAlignmentScope.MAX_OCR_PER_FRAME);
        assertEquals(0, UiImageAlignmentScope.MAX_NAVIGATION);
        assertEquals(0, UiImageAlignmentScope.MAX_INPUT);
    }

    @Test public void namespaceIsSeparateFromEveryOldReadAndCaptureSlot() {
        for (String old : List.of(RootReturnReadScope.DIRECTORY, RootCurrentReadScope.DIRECTORY,
                RootCurrentCardScope.DIRECTORY, RootProgressLocateScope.DIRECTORY,
                RootProgressReadScope.DIRECTORY, RootProgressScanScope.DIRECTORY,
                RootImageSeriesScope.DIRECTORY, ReadOnlyCheckpoint.UI_BOUND_POINTS_V53.directory(),
                "controlled-whole-frame-20261111-fifth-once")) {
            assertNotEquals(old, UiImageAlignmentScope.DIRECTORY);
        }
    }

    @Test public void deadlinesAreMonotonicAndDoNotNeedAnOldUtcWindow() {
        assertEquals(180_000L, UiImageAlignmentScope.sessionDeadline(0));
        assertEquals(181_000L, UiImageAlignmentScope.sessionDeadline(1000));
        assertEquals(91_000L, UiImageAlignmentScope.captureDeadline(1000, 181_000));
        assertEquals(1050L, UiImageAlignmentScope.captureDeadline(1000, 1050));
        assertEquals(1001L, UiImageAlignmentScope.captureDeadline(1000, 1001));
    }

    @Test public void sessionDeadlineRejectsInvalidAndOverflowingClock() {
        for (long now : new long[]{-1, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 179_999}) {
            assertThrows(SecurityException.class, () -> UiImageAlignmentScope.sessionDeadline(now));
        }
        assertEquals(Long.MAX_VALUE, UiImageAlignmentScope.sessionDeadline(Long.MAX_VALUE - 180_000));
    }

    @Test public void captureDeadlineRejectsExpiryNegativeAndOverflowingClock() {
        for (long[] pair : new long[][]{{-1, 5}, {0, 0}, {1, 1}, {5, 4},
                {Long.MAX_VALUE, Long.MAX_VALUE}, {Long.MAX_VALUE - 89_999, Long.MAX_VALUE}}) {
            assertThrows(SecurityException.class, () -> UiImageAlignmentScope.captureDeadline(pair[0], pair[1]));
        }
        assertEquals(Long.MAX_VALUE,
                UiImageAlignmentScope.captureDeadline(Long.MAX_VALUE - 90_000, Long.MAX_VALUE));
    }

    @Test public void reservationsSucceedOnceInOnlyTheRequiredOrder() {
        var budget = new UiImageAlignmentScope.Budget();
        assertTrue(budget.claimRun()); assertTrue(budget.claimUi());
        assertTrue(budget.claimFrame()); assertTrue(budget.claimOcr());
        assertTrue(budget.summary().contains("ocrReserved=true"));
        assertFalse(budget.claimOcr()); assertFalse(budget.claimRun());
        assertTrue(budget.summary().contains("alignmentBudgetRejected=true"));
    }

    private boolean claim(UiImageAlignmentScope.Budget budget, int slot) {
        return switch (slot) {
            case 0 -> budget.claimRun(); case 1 -> budget.claimUi();
            case 2 -> budget.claimFrame(); case 3 -> budget.claimOcr();
            default -> throw new AssertionError();
        };
    }

    @Test public void everyOutOfOrderReservationPoisonsTheWholeBudget() {
        for (int next = 0; next < 4; next++) {
            for (int wrong = 0; wrong < 4; wrong++) {
                if (wrong == next) continue;
                var budget = new UiImageAlignmentScope.Budget();
                for (int i = 0; i < next; i++) assertTrue(claim(budget, i));
                assertFalse(claim(budget, wrong));
                for (int i = 0; i < 4; i++) assertFalse(claim(budget, i));
            }
        }
    }

    @Test public void repeatedReservationCannotLeaveLaterStagesAvailable() {
        for (int duplicate = 0; duplicate < 4; duplicate++) {
            var budget = new UiImageAlignmentScope.Budget();
            for (int i = 0; i <= duplicate; i++) assertTrue(claim(budget, i));
            assertFalse(claim(budget, duplicate));
            for (int i = duplicate + 1; i < 4; i++) assertFalse(claim(budget, i));
        }
    }

    @Test public void concurrentRunReservationHasOnlyOneWinnerAndCannotContinueAfterReplay() throws Exception {
        var budget = new UiImageAlignmentScope.Budget();
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var winners = new AtomicInteger(); var failures = new AtomicInteger();
        Runnable compete = () -> {
            ready.countDown();
            try { if (!start.await(5, TimeUnit.SECONDS)) { failures.incrementAndGet(); return; }
                if (budget.claimRun()) winners.incrementAndGet(); }
            catch (InterruptedException interrupted) { failures.incrementAndGet(); Thread.currentThread().interrupt(); }
        };
        var first = new Thread(compete); var second = new Thread(compete);
        first.start(); second.start(); assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
        first.join(5000); second.join(5000); assertFalse(first.isAlive()); assertFalse(second.isAlive());
        assertEquals(0, failures.get()); assertEquals(1, winners.get()); assertFalse(budget.claimUi());
    }

    @Test public void reservationSummaryContainsNoAuthorityOrSuccessClaim() {
        var budget = new UiImageAlignmentScope.Budget(); budget.claimRun(); budget.claimFrame();
        String summary = budget.summary();
        assertTrue(summary.contains("frameAttempted=true frameReserved=false"));
        assertTrue(summary.contains("alignmentBudgetRejected=true"));
        for (String falseEvidence : List.of("LIVE", "VERIFIED", "cleanup", "durable", "earned", "taskComplete")) {
            assertFalse(summary.contains(falseEvidence));
        }
    }

    @Test public void storageRejectsRelativeUnnormalizedOrMissingRoots() throws Exception {
        assertThrows(SecurityException.class, () -> UiImageAlignmentScope.burnRun(null));
        assertThrows(SecurityException.class, () -> UiImageAlignmentScope.burnUi(Path.of("relative")));
        assertThrows(SecurityException.class, () -> UiImageAlignmentScope.frameStore(root().resolve("x").resolve("..")));
        Path missing = root().resolve("missing");
        assertThrows(IOException.class, () -> UiImageAlignmentScope.burnRun(missing));
        assertFalse(Files.exists(missing, LinkOption.NOFOLLOW_LINKS));
    }

    @Test public void privateFilesRootMustBeADirectory() throws Exception {
        Path file = root().resolve("file"); Files.writeString(file, "unchanged");
        assertThrows(IOException.class, () -> UiImageAlignmentScope.burnRun(file));
        assertEquals("unchanged", Files.readString(file));
    }

    @Test public void scopeDirectoryMustNotBeAnExistingRegularFile() throws Exception {
        Path scope = root().resolve(UiImageAlignmentScope.DIRECTORY); Files.writeString(scope, "unchanged");
        assertThrows(IOException.class, () -> UiImageAlignmentScope.burnRun(root()));
        assertEquals("unchanged", Files.readString(scope));
    }

    @Test public void preexistingPartialMarkerIsNeverTruncatedOrReplaced() throws Exception {
        Files.createDirectory(root().resolve(UiImageAlignmentScope.DIRECTORY));
        Files.writeString(marker("RUN"), "partial old attempt");
        assertThrows(IOException.class, () -> UiImageAlignmentScope.burnRun(root()));
        assertEquals("partial old attempt", Files.readString(marker("RUN")));
    }

    @Test public void directoryAtMarkerNameIsNeverRemovedOrReused() throws Exception {
        Files.createDirectories(marker("UI"));
        assertThrows(IOException.class, () -> UiImageAlignmentScope.burnUi(root()));
        assertTrue(Files.isDirectory(marker("UI"), LinkOption.NOFOLLOW_LINKS));
    }

    private enum BurnObservation { IDENTITY_REJECTED, MARKER_RETAINED_UNCONFIRMED, BURN_RETURNED }

    private boolean exactIdentityRejection(IOException failure) {
        StackTraceElement[] stack = failure.getStackTrace();
        return failure.getClass() == IOException.class
                && "Alignment directory identity unconfirmed".equals(failure.getMessage())
                && stack.length > 0
                && stack[0].getClassName().equals(UiImageAlignmentScope.class.getName())
                && stack[0].getMethodName().equals("directoryIdentity");
    }

    private boolean directoryForceWasEntered(IOException failure) {
        for (var frame : failure.getStackTrace()) {
            if (frame.getClassName().equals(UiImageAlignmentScope.class.getName())
                    && frame.getMethodName().equals("forceDirectory")) return true;
        }
        return false;
    }

    /** Tests rejection or retention as observed; an unavailable positive path is not a durability pass. */
    private BurnObservation observeAttempt(String slot, RealCaptureClaim.BurnStore store) throws Exception {
        BasicFileAttributes before = Files.readAttributes(root(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertTrue(before.isDirectory()); assertFalse(before.isSymbolicLink());
        Path directory = root().resolve(UiImageAlignmentScope.DIRECTORY);
        assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS));
        BurnObservation observation = BurnObservation.BURN_RETURNED;
        try {
            if (slot.equals("RUN")) UiImageAlignmentScope.burnRun(root());
            else if (slot.equals("UI")) UiImageAlignmentScope.burnUi(root());
            else store.burn(CLAIM, "private text must not be persisted");
        } catch (IOException failure) {
            if (before.fileKey() == null) {
                if (!exactIdentityRejection(failure)) throw failure;
                // Observed on this Windows JDK: production rejects before creating even its directory.
                assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS));
                assertFalse(Files.exists(marker(slot), LinkOption.NOFOLLOW_LINKS));
                return BurnObservation.IDENTITY_REJECTED;
            }
            // Only an exception from the actual directory-force stage can mean a retained marker.
            // Earlier/unrecognized I/O failures retain their original exception and fail this test.
            if (!directoryForceWasEntered(failure)
                    || !Files.isRegularFile(marker(slot), LinkOption.NOFOLLOW_LINKS)) throw failure;
            observation = BurnObservation.MARKER_RETAINED_UNCONFIRMED;
        }
        assertNotNull("Missing directory identity must reject before any write", before.fileKey());
        assertTrue(Files.isRegularFile(marker(slot), LinkOption.NOFOLLOW_LINKS));
        assertEquals("ui-image-alignment-v1 slot=" + slot
                + " maxUiReads=1 maxFrames=1 maxOcrPerFrame=1 maxNavigation=0 maxInput=0\n",
                Files.readString(marker(slot), StandardCharsets.UTF_8));
        return observation;
    }

    @Test public void runAttemptRetainsMarkerOrRejectsMissingIdentityWithoutCreatingFiles() throws Exception {
        BurnObservation observation = observeAttempt("RUN", null);
        if (observation == BurnObservation.IDENTITY_REJECTED) {
            assertFalse(Files.exists(marker("RUN"), LinkOption.NOFOLLOW_LINKS));
        } else {
            byte[] before = Files.readAllBytes(marker("RUN"));
            assertThrows(IOException.class, () -> UiImageAlignmentScope.burnRun(root()));
            assertArrayEquals(before, Files.readAllBytes(marker("RUN")));
        }
    }

    @Test public void uiAttemptUsesOnlyItsFixedSlotOrRejectsMissingIdentity() throws Exception {
        BurnObservation observation = observeAttempt("UI", null);
        if (observation == BurnObservation.IDENTITY_REJECTED) {
            assertFalse(Files.exists(marker("UI"), LinkOption.NOFOLLOW_LINKS));
        } else {
            assertThrows(IOException.class, () -> UiImageAlignmentScope.burnUi(root()));
        }
        assertFalse(Files.exists(marker("RUN"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(marker("FRAME"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test public void frameAttemptSpendsStoreAndRetainsSafeMarkerOrRejectsMissingIdentity() throws Exception {
        var store = UiImageAlignmentScope.frameStore(root());
        BurnObservation observation = observeAttempt("FRAME", store);
        // In-memory one-use consumption must hold even when the directory could not be identified.
        assertThrows(SecurityException.class, () -> store.burn(CLAIM, "safe"));
        if (observation == BurnObservation.IDENTITY_REJECTED) {
            assertFalse(Files.exists(marker("FRAME"), LinkOption.NOFOLLOW_LINKS));
        } else {
            String text = Files.readString(marker("FRAME"));
            assertFalse(text.contains(CLAIM)); assertFalse(text.contains("private text"));
            assertThrows(IOException.class, () -> UiImageAlignmentScope.frameStore(root()).burn(CLAIM, "safe"));
        }
    }

    @Test public void rejectedFrameClaimSpendsTheStoreWithoutWriting() {
        for (String invalid : new String[]{null, "", "--------------------1111111111111111", "../RUN", CLAIM.toUpperCase() + "x"}) {
            var store = UiImageAlignmentScope.frameStore(root());
            assertThrows(SecurityException.class, () -> store.burn(invalid, "safe"));
            assertThrows(SecurityException.class, () -> store.burn(CLAIM, "safe"));
            assertFalse(Files.exists(marker("FRAME"), LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Test public void rejectedMetadataCannotBeRetriedWithNewMetadata() {
        for (String invalid : new String[]{null, "x".repeat(2049)}) {
            var store = UiImageAlignmentScope.frameStore(root());
            assertThrows(SecurityException.class, () -> store.burn(CLAIM, invalid));
            assertThrows(SecurityException.class, () -> store.burn(CLAIM, "safe"));
            assertFalse(Files.exists(marker("FRAME"), LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Test public void newAttemptKeepsOldEvidenceUnchangedEvenWhenDirectoryIdentityIsRejected() throws Exception {
        Path old = root().resolve(ReadOnlyCheckpoint.UI_BOUND_POINTS_V53.directory());
        Files.createDirectory(old); Path record = old.resolve("RUN_1.burned"); Files.writeString(record, "old evidence");
        byte[] before = Files.readAllBytes(record);
        observeAttempt("RUN", null);
        // Always checked, including the unsupported-identity branch; never skipped by an assumption.
        assertArrayEquals(before, Files.readAllBytes(record));
        assertTrue(Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS));
    }
}
