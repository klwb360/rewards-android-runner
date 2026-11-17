package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

/** The coordinator, BEFORE crop permit, source gate and metadata parser are the production ones. */
public class LiveBeforeCropMetadataTest {
    private static final String DISPLAY = "mTopFocusedDisplayId=0\n";
    private static final String WINDOW = LiveNavigationSnapshotTest.originalWindows(79).replace(DISPLAY, "");
    private static final String ACTIVITY = LiveNavigationSnapshotTest.originalActivities(79);
    private static final Box ROI = new Box(20, 100, 420, 500);

    private enum FinalRead { LATE_DISPLAY, MISSING_DISPLAY, CHANGED_WINDOW }

    private static final class Rig extends LiveRewardsCoordinatorTest.Rig {
        private final FinalRead finalRead;
        int sourceReads;
        int completedCrops;
        BingNavigationSnapshot.FirstGate lastParserGate;

        Rig(FinalRead finalRead) throws Exception {
            super(List.of(LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN, 0, "SIGN_IN")));
            this.finalRead = finalRead;
        }

        private BingNavigationSnapshot.Ready readAtCrop() {
            sourceReads++;
            // Change only the final metadata read, after the first two checks already accepted.
            FinalRead sample = sourceReads == 3 ? finalRead : FinalRead.LATE_DISPLAY;
            String prefix = sample == FinalRead.CHANGED_WINDOW ? WINDOW.replace("Window{aaa", "Window{aba") : WINDOW;
            var pipe = sample == FinalRead.MISSING_DISPLAY
                    ? new MetadataPipeReaderTest.ScriptedPipe(prefix, "")
                    : new MetadataPipeReaderTest.ScriptedPipe(prefix, "", DISPLAY);
            var reader = new MetadataPipeReader();
            byte[] output = new byte[8192];
            try {
                assertEquals(ReviewBingNavigation.FixedStatus.READ_COMPLETE, reader.drain(output, pipe,
                        wait -> true, new AtomicLong()::getAndIncrement, () -> true, 100));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            assertTrue(pipe.sentEof);
            String document = new String(output, 0, reader.count(), StandardCharsets.UTF_8);
            var parsed = BingNavigationSnapshot.parseCurrentTemplate(document, ACTIVITY, BingNavigationFixture.PROFILE,
                    RootReadOnlyAnimationGuardTest.G, f.clock.now, f.clock.now);
            lastParserGate = parsed.firstGate();
            return parsed.source() == null ? null : parsed.source().ready();
        }

        @Override public LiveRewardsTypes.Observation observe(LiveRunAuthority.CardScope scope,
                LiveRewardsTypes.Phase phase, LiveRewardsTypes.Task expected) throws Exception {
            if (phase != LiveRewardsTypes.Phase.BEFORE) return super.observe(scope, phase, expected);
            var imageBefore = f.source();
            var crop = scope.claimCrop(f.ui(scope), phase, f.clock.now);
            assertTrue(crop.bindRequest(ROI, imageBefore.profile().shape(), f.clock.now));
            var gate = LiveRewardsPerception.cropGate(() -> crop.current(f.clock.now), imageBefore, this::readAtCrop,
                    () -> crop.consume(f.clock.now));
            // The native capture entry performs these three source checks before starting its helper.
            gate.requireCurrent();
            assertTrue(gate.consume());
            assertTrue(crop.claimDispatch(f.clock.now));
            gate.requireCurrent();
            completedCrops++;
            scope.finishCrop(crop, true, true, f.clock.now);
            var data = new LiveRewardsTypes.ObservationData(expected, imageBefore, f.clock.now, ROI, 400, 400,
                    new Box(280, 100, 360, 150), .95, LiveRewardsTypes.hash("BEFORE_CROP"),
                    true, true, true, 0, 100);
            return scope.sealObservation(crop, data);
        }
    }

    @Test public void lateDisplayTailPermitsTheBeforeRecaptureAndOneSyntheticTap() throws Exception {
        var rig = new Rig(FinalRead.LATE_DISPLAY);
        var summary = rig.runSingleTask();
        assertFalse(summary.reason(), summary.stopped());
        assertEquals(3, rig.sourceReads);
        assertEquals(1, rig.completedCrops);
        assertEquals(List.of(LiveRewardsTypes.Kind.CHECK_IN), rig.tappedKinds);
        assertEquals(0, rig.returnCalls);
        assertTrue(rig.ledger.attempted(rig.tasks.get(0)));
    }

    @Test public void completeStreamWithoutDisplayStillStopsTheBeforeCropAndTap() throws Exception {
        var rig = new Rig(FinalRead.MISSING_DISPLAY);
        assertTrue(rig.runSingleTask().stopped());
        assertEquals(BingNavigationSnapshot.FirstGate.FOCUS_FORMAT, rig.lastParserGate);
        assertNoCropOrTap(rig);
    }

    @Test public void validButDifferentWindowAtFinalCheckCannotUsePreviousApproval() throws Exception {
        var rig = new Rig(FinalRead.CHANGED_WINDOW);
        assertTrue(rig.runSingleTask().stopped());
        assertEquals(BingNavigationSnapshot.FirstGate.CURRENT_TEMPLATE_BOUND, rig.lastParserGate);
        assertNoCropOrTap(rig);
    }

    private static void assertNoCropOrTap(Rig rig) throws java.io.IOException {
        assertEquals(3, rig.sourceReads);
        assertEquals(0, rig.completedCrops);
        assertEquals(0, rig.tapCalls);
        assertEquals(0, rig.returnCalls);
        assertFalse(rig.ledger.attempted(rig.tasks.get(0)));
    }
}
