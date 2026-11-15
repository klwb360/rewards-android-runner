package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic permit lifecycle only. No Android navigation, capture or device authority. */
public class CardRevealProbeIntentTest {
    private CardRevealProbeIntent intent() {
        return VisibleCaptureAuthority.fixtureCardRevealIntent(new RealCaptureClaim.ProcessEpoch(), new Object(), 100);
    }
    private void denied(Runnable action) {
        try { action.run(); fail("Expected rejection"); } catch (SecurityException expected) { }
    }
    private CardRevealProbeIntent.UiPermit accepted(CardRevealProbeIntent intent, long now) {
        var ui = intent.claimUi(new Object(), now);
        assertTrue(ui.claimReader(now + 1));
        intent.finishUi(ui, true, true, now + 2);
        return ui;
    }
    private ReviewShizukuSwipeActor.Result exited() {
        return new ReviewShizukuSwipeActor.Result(ReviewShizukuSwipeActor.Kind.COMMAND_EXITED, 0,
                true, true, ReviewShizukuSwipeActor.Reason.COMMAND_EXIT_ZERO);
    }
    @Test public void stableUiReadsNeedNeitherAnImageNorAScrollAndCannotReuseTheirReader() {
        var intent = intent(); var first = accepted(intent, 101);
        assertTrue(first.current(105)); assertFalse(first.claimReader(105));
        var second = intent.claimUi(new Object(), 110);
        assertFalse(first.current(110)); assertTrue(second.current(110));
        assertTrue(second.claimReader(111)); intent.finishUi(second, true, true, 112);
        assertTrue(second.current(113)); assertEquals(2, intent.uiCount()); assertEquals(0, intent.swipeCount());
        assertFalse(intent.cropSpent());
    }
    @Test public void unfinishedUnclaimedOrUnqualifiedUiCannotAuthorizeFollowingWork() {
        for (int mode = 0; mode < 3; mode++) {
            var intent = intent(); var ui = intent.claimUi(new Object(), 101);
            if (mode != 0) assertTrue(ui.claimReader(102));
            final int value = mode;
            denied(() -> intent.finishUi(ui, value != 1, value != 2, 103));
            assertFalse(intent.current(104));
            denied(() -> intent.claimScroll(ui, ReviewShizukuSwipeActor.Direction.UP, 104));
            assertFalse(intent.claimCrop(ui, 104));
        }
    }
    @Test public void identityAndObservationExpiryRemainBoundAfterUiCompletion() {
        var intent = intent(); var ui = accepted(intent, 101);
        assertTrue(ui.current(ui.epoch, ui.owner, 104));
        assertFalse(ui.current(new RealCaptureClaim.ProcessEpoch(), ui.owner, 104));
        assertFalse(ui.current(ui.epoch, new Object(), 104));
        assertTrue(ui.current(9100)); assertFalse(ui.current(9101));
        assertFalse(intent.claimCrop(ui, 9101));
        assertTrue(intent.cropSpent());
    }
    @Test public void exactlyTwentyFourUiObservationsAreAvailableWithoutImages() {
        var intent = intent();
        for (int index = 1; index <= 24; index++) {
            var ui = accepted(intent, 100 + 4L * index); assertEquals(index, ui.index);
        }
        assertEquals(24, intent.uiCount());
        denied(() -> intent.claimUi(new Object(), 200));
        assertEquals(24, intent.uiCount());
    }
    @Test public void latestQualifiedUiIsRequiredAndScrollInvalidatesItsSourceLease() {
        var intent = intent(); var old = accepted(intent, 101); var latest = accepted(intent, 110);
        denied(() -> intent.claimScroll(old, ReviewShizukuSwipeActor.Direction.UP, 114));
        var scroll = intent.claimScroll(latest, ReviewShizukuSwipeActor.Direction.UP, 115);
        assertFalse(latest.current(115)); assertSame(latest, scroll.observation);
        denied(() -> intent.claimUi(new Object(), 116));
        assertTrue(scroll.consume(116)); assertFalse(scroll.consume(117));
        intent.finishScroll(scroll, exited(), true, 118);
        assertFalse(scroll.current(119));
        denied(() -> intent.claimScroll(latest, ReviewShizukuSwipeActor.Direction.UP, 119));
        accepted(intent, 120);
    }
    @Test public void eightScrollsAreHardLimitButAFinalFreshUiMayStillProduceOneCrop() {
        var intent = intent();
        for (int index = 1; index <= 8; index++) {
            long now = 100 + index * 10L; var ui = accepted(intent, now);
            var scroll = intent.claimScroll(ui, ReviewShizukuSwipeActor.Direction.DOWN, now + 3);
            assertEquals(index, scroll.index); assertTrue(scroll.consume(now + 4));
            intent.finishScroll(scroll, exited(), true, now + 5);
        }
        var finalUi = accepted(intent, 200);
        denied(() -> intent.claimScroll(finalUi, ReviewShizukuSwipeActor.Direction.DOWN, 203));
        assertTrue(intent.claimCrop(finalUi, 204)); assertEquals(8, intent.swipeCount());
    }
    @Test public void unknownNonzeroOrUncleanScrollStopsInsteadOfGrantingAnotherObservation() {
        var outcomes = new ReviewShizukuSwipeActor.Result[]{
                new ReviewShizukuSwipeActor.Result(ReviewShizukuSwipeActor.Kind.DISPATCH_UNKNOWN, Integer.MIN_VALUE,
                        true, true, ReviewShizukuSwipeActor.Reason.WAIT_TIMEOUT),
                new ReviewShizukuSwipeActor.Result(ReviewShizukuSwipeActor.Kind.COMMAND_EXITED, 1,
                        true, true, ReviewShizukuSwipeActor.Reason.COMMAND_EXIT_NONZERO),
                exited()};
        for (int index = 0; index < outcomes.length; index++) {
            var intent = intent(); var ui = accepted(intent, 101);
            var scroll = intent.claimScroll(ui, ReviewShizukuSwipeActor.Direction.UP, 104);
            assertTrue(scroll.consume(105)); final int value = index;
            denied(() -> intent.finishScroll(scroll, outcomes[value], value != 2, 106));
            assertFalse(intent.current(107)); denied(() -> intent.claimUi(new Object(), 107));
        }
    }
    @Test public void forgedZeroExitWithoutDispatchOrAuthorityIsNotScrollSuccess() {
        for (int mode = 0; mode < 2; mode++) {
            var intent = intent(); var ui = accepted(intent, 101);
            var scroll = intent.claimScroll(ui, ReviewShizukuSwipeActor.Direction.UP, 104);
            assertTrue(scroll.consume(105));
            var result = new ReviewShizukuSwipeActor.Result(ReviewShizukuSwipeActor.Kind.COMMAND_EXITED, 0,
                    mode != 0, mode != 1, ReviewShizukuSwipeActor.Reason.COMMAND_EXIT_ZERO);
            denied(() -> intent.finishScroll(scroll, result, true, 106)); assertFalse(intent.current(107));
        }
    }
    @Test public void cropIsOnceOnlyAndKeepsSourceCurrentThroughPublication() {
        var intent = intent(); var ui = accepted(intent, 101);
        assertTrue(intent.claimCrop(ui, 104)); assertTrue(ui.current(105));
        assertFalse(intent.claimCrop(ui, 106)); assertTrue(ui.current(107));
        denied(() -> intent.claimUi(new Object(), 107));
        denied(() -> intent.claimScroll(ui, ReviewShizukuSwipeActor.Direction.UP, 107));
        intent.cancel(); assertFalse(ui.current(108));
    }
    @Test public void anInvalidCropClaimStillSpendsItsOnlySlot() {
        var intent = intent(); assertFalse(intent.claimCrop(null, 101)); assertTrue(intent.cropSpent());
        denied(() -> intent.claimUi(new Object(), 102));
        assertFalse(intent.claimCropDispatch(null, 103));
    }
    @Test public void fixtureCropClaimCannotBecomeALiveDispatch() {
        var intent = intent(); var ui = accepted(intent, 101);
        assertTrue(intent.claimCrop(ui, 104)); assertFalse(intent.live());
        assertFalse(intent.claimCropDispatch(ui, 105));
        assertFalse(intent.claimCropDispatch(ui, 106));
        assertTrue(ui.current(107));
    }
    @Test public void failedCropClaimCannotBeReplacedByTheCurrentUi() {
        var intent = intent(); var ui = accepted(intent, 101);
        var foreign = accepted(intent(), 101);
        assertFalse(intent.claimCrop(foreign, 104));
        assertFalse(intent.claimCrop(ui, 105));
        assertFalse(intent.claimCropDispatch(ui, 106));
    }
    @Test public void foreignUiCannotFinishAnotherIntentAndFixtureCannotCreateLiveActor() {
        var one = intent(); var two = intent(); var ui = accepted(one, 101);
        denied(() -> two.finishUi(ui, true, true, 104)); assertFalse(two.current(105));
        var scroll = one.claimScroll(ui, ReviewShizukuSwipeActor.Direction.UP, 106);
        assertFalse(one.live()); assertFalse(scroll.claimActor(107));
        denied(() -> ReviewShizukuSwipeActor.forCardReveal(null, scroll));
        denied(() -> ReviewShizukuSwipeActor.forCardReveal(null, null));
    }
    @Test public void sessionDeadlineAndCancelAreNotResettable() {
        var intent = intent(); assertEquals(120100, intent.deadline());
        assertFalse(intent.current(99)); assertTrue(intent.current(120099)); assertFalse(intent.current(120100));
        denied(() -> intent.claimUi(new Object(), 120100));
        var canceled = intent(); canceled.cancel(); denied(() -> canceled.claimUi(new Object(), 101));
        denied(() -> CardRevealProbeScope.sessionDeadline(Long.MAX_VALUE));
        denied(() -> CardRevealProbeScope.sessionDeadline(-1));
    }
}
