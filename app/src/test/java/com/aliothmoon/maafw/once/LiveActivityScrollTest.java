package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.OverviewUiRegions.Role.DAILY_ACTIVITIES;
import static com.aliothmoon.maafw.once.OverviewUiRegions.Role.MORE_ACTIVITIES;
import static com.aliothmoon.maafw.once.ReviewShizukuSwipeActor.Direction.DOWN;
import static com.aliothmoon.maafw.once.ReviewShizukuSwipeActor.Direction.UP;

public class LiveActivityScrollTest {
    private static final int W = 1440, H = 2560;
    private static final BingNavigationSnapshot.Shape SHAPE = new BingNavigationSnapshot.Shape(W,H,0);
    private static final OverviewUiProtocol.ScrollHint BOTH = OverviewUiProtocol.ScrollHint.DECLARED_BOTH;

    private static RemainingCardTarget.Result select(OverviewUiRegions.Role role, int visibleIndex, int top,
            int bottom, boolean complete, OverviewUiProtocol.ScrollHint hint) {
        var old = OverviewFrameCaptureTest.report().source();
        var source = new NativeUiStructureProtocol.Report(old.nonce(),old.started(),old.acquired(),old.finished(),
                old.reason(),old.cleanup(),old.nodeCount(),old.charCount(),old.windowId(),old.displayId(),
                0,320,W,2334,old.rotationBefore(),old.rotationAfter(),old.search(),old.reading(),old.readiness());
        var viewport = new Box(0,320,W,2334);
        var parent = new OverviewUiRegions.Region(role,viewport,null,null,false,-1,-1);
        var item = new RemainingCardUi.Item(role,visibleIndex,new Box(110,top,1330,bottom),null,null,complete);
        var page = new OverviewUiProtocol.Report(source,
                new OverviewUiRegions.Result(OverviewUiRegions.Status.OBSERVED,viewport,List.of(parent)),hint,
                new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED,role==DAILY_ACTIVITIES?3:-1,
                        role==MORE_ACTIVITIES?3:-1,List.of(item)));
        return RemainingCardTarget.select(page,W,H,new RemainingCardChoice(role,2));
    }

    @Test public void nearEitherEdgeUsesAShortDirectionalGestureForBothActivityRegions() {
        for (var role : List.of(DAILY_ACTIVITIES,MORE_ACTIVITIES)) {
            var top = select(role,2,520,950,true,BOTH);
            var bottom = select(role,2,1882,2307,false,BOTH);
            assertEquals(4,top.edgeGap());
            assertEquals(15,bottom.edgeGap());
            for (var observation : List.of(top,bottom)) {
                var gesture = observation.swipeTarget(SHAPE,null);
                assertTrue(ReviewShizukuSwipeActor.valid(gesture));
                assertNull(observation.selection().crop());
                assertEquals(observation==top?DOWN:UP,gesture.direction());
                assertTrue(gesture.distance()<1152);
                assertEquals(gesture.distance(),Math.abs(gesture.fromY()-gesture.toY()));
                assertTrue(Math.min(gesture.fromY(),gesture.toY())>=H*7/20);
                assertTrue(Math.max(gesture.fromY(),gesture.toY())<=H*4/5);
            }
        }
    }

    @Test public void syntheticOvershootBecomesAStableFullyVisibleSecondCard() {
        // Synthetic motion at the emulator's display size: content travels 1.3 times the finger stroke.
        int top=1882, bottom=2307;
        int oldMotion = 13*new ReviewShizukuSwipeActor.SwipeTarget(W,H,0).distance()/10;
        assertEquals(DOWN,select(DAILY_ACTIVITIES,2,top-oldMotion,bottom-oldMotion,true,BOTH).selection().direction());
        var first = select(DAILY_ACTIVITIES,2,top,bottom,false,BOTH);
        assertEquals(UP,first.selection().direction());
        int motion = first.swipeTarget(SHAPE,null).distance()*13/10;
        var next = select(DAILY_ACTIVITIES,2,top-motion,bottom-motion,true,BOTH);
        var repeated = select(DAILY_ACTIVITIES,2,top-motion,bottom-motion,true,BOTH);
        assertEquals(CardRevealProbeTarget.State.CANDIDATE,next.selection().state());
        assertTrue(next.selection().sameCandidate(repeated.selection()));
        assertEquals(new Box(98,top-motion-12,1342,bottom-motion+12),next.selection().crop());
    }

    @Test public void unseenCardUsesStandardSearchUntilReversalThenReducesTheStroke() {
        var earlier = select(DAILY_ACTIVITIES,1,650,1090,true,BOTH);
        var later = select(DAILY_ACTIVITIES,3,650,1090,true,BOTH);
        var forward = earlier.swipeTarget(SHAPE,null);
        assertEquals(UP,forward.direction());
        assertEquals(1152,forward.distance());
        var reverse = later.swipeTarget(SHAPE,forward);
        assertEquals(DOWN,reverse.direction());
        assertTrue(reverse.distance()<forward.distance());
        assertEquals(reverse.distance(),later.swipeTarget(SHAPE,reverse).distance());
        assertTrue(earlier.swipeTarget(SHAPE,reverse).distance()<reverse.distance());
        assertEquals(1152,later.swipeTarget(SHAPE,null).distance());
    }

    @Test public void partialUndeclaredAndOversizedCardsCannotBecomeCropCandidates() {
        var partial = select(MORE_ACTIVITIES,2,750,1180,false,BOTH);
        var denied = select(MORE_ACTIVITIES,2,520,950,false,OverviewUiProtocol.ScrollHint.DECLARED_FORWARD);
        var tooTall = select(MORE_ACTIVITIES,2,350,2300,true,BOTH);
        assertEquals("CARD_PARTIAL",partial.reason());
        assertEquals(CardRevealProbeTarget.State.NO_DIRECTION,denied.selection().state());
        assertEquals(CardRevealProbeTarget.State.TOO_LARGE,tooTall.selection().state());
        for (var held : List.of(partial,denied,tooTall)) {
            assertNull(held.selection().crop());
            assertThrows(IllegalStateException.class,()->held.swipeTarget(SHAPE,null));
        }
    }

    @Test public void legacyCoordinatesAndDistanceLimitsArePreserved() {
        var up = new ReviewShizukuSwipeActor.SwipeTarget(W,H,0);
        var down = new ReviewShizukuSwipeActor.SwipeTarget(W,H,0,DOWN);
        assertEquals(2048,up.fromY()); assertEquals(896,up.toY());
        assertEquals(896,down.fromY()); assertEquals(2048,down.toY());
        assertTrue(up.standard() && down.standard());
        for (int invalid : new int[]{-1,0,ReviewShizukuSwipeActor.MIN_DISTANCE-1,1153,Integer.MAX_VALUE})
            assertFalse(ReviewShizukuSwipeActor.valid(new ReviewShizukuSwipeActor.SwipeTarget(W,H,0,UP,invalid)));
    }

    @Test public void adaptivePermitRequiresTheSameTargetAndAnotherFreshFrameForEachOfEightSwipes() throws Exception {
        var fixture = new LiveCoreFixture();
        fixture.attach();
        var scan = fixture.authority.scanScope(LiveRewardsTypes.Kind.DAILY);
        var target = select(DAILY_ACTIVITIES,2,1882,2307,false,BOTH).swipeTarget(SHAPE,null);
        for (int i=0;i<8;i++) {
            var frame = fixture.ui(scan);
            var permit = scan.claimScroll(frame,target,fixture.clock.now);
            assertTrue(permit.matchesTarget(target));
            assertFalse(permit.matchesTarget(new ReviewShizukuSwipeActor.SwipeTarget(W,H,0,UP)));
            assertFalse(permit.matchesTarget(new ReviewShizukuSwipeActor.SwipeTarget(W,H,0,DOWN,target.distance())));
            assertTrue(permit.claimActor(fixture.clock.now));
            assertTrue(permit.consume(fixture.clock.now));
            assertTrue(permit.dispatchValid(fixture.clock.now));
            scan.finishScroll(permit,new ReviewShizukuSwipeActor.Result(ReviewShizukuSwipeActor.Kind.COMMAND_EXITED,
                    0,true,true,ReviewShizukuSwipeActor.Reason.COMMAND_EXIT_ZERO),true,fixture.clock.now);
            assertFalse(permit.dispatchValid(fixture.clock.now));
            assertThrows(SecurityException.class,()->scan.claimScroll(frame,target,fixture.clock.now));
        }
        var ninthFrame=fixture.ui(scan);
        assertThrows(SecurityException.class,()->scan.claimScroll(ninthFrame,target,fixture.clock.now));
    }

    @Test public void directionOnlyPermitAndCheckInKeepStandardGeometry() throws Exception {
        var fixture = new LiveCoreFixture();
        fixture.attach();
        var scan = fixture.authority.scanScope(LiveRewardsTypes.Kind.CHECK_IN);
        var ui = fixture.ui(scan);
        var fine = select(DAILY_ACTIVITIES,2,1882,2307,false,BOTH).swipeTarget(SHAPE,null);
        assertThrows(SecurityException.class,()->scan.claimScroll(ui,fine,fixture.clock.now));
        var legacy = scan.claimScroll(ui,UP,fixture.clock.now);
        assertTrue(legacy.matchesTarget(new ReviewShizukuSwipeActor.SwipeTarget(W,H,0,UP)));
        assertFalse(legacy.matchesTarget(fine));
    }
}
