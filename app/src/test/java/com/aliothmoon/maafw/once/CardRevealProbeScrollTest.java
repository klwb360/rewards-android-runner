package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic UI geometry only; does not establish physical scrolling or image boundaries. */
public class CardRevealProbeScrollTest {
    private OverviewUiProtocol.Report report(int top,int bottom,int headingTop,boolean complete,
            OverviewUiProtocol.ScrollHint hint){
        var base=OverviewFrameCaptureTest.report();
        var region=new OverviewUiRegions.Region(OverviewUiRegions.Role.CHECK_IN,new Box(30,top,493,bottom),
                new Box(45,headingTop,100,headingTop+25),null,complete,-1,-1);
        return new OverviewUiProtocol.Report(base.source(),new OverviewUiRegions.Result(OverviewUiRegions.Status.OBSERVED,
                new Box(0,0,523,1000),List.of(region)),hint);
    }
    @Test public void alreadyCompleteCandidateCannotStartActualScrollProbe(){
        var report=report(300,550,320,true,OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        assertEquals(CardRevealProbeTarget.State.CANDIDATE,CardRevealProbeTarget.select(report,523,1000).state());
        assertFalse(CardRevealProbeTarget.initialClipped(report,523,1000));
    }
    @Test public void missingCheckInCannotBeClaimedAsInitiallyClipped(){
        var base=OverviewFrameCaptureTest.report();
        var report=new OverviewUiProtocol.Report(base.source(),new OverviewUiRegions.Result(OverviewUiRegions.Status.OBSERVED,
                new Box(0,0,523,1000),List.of()),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        assertEquals(CardRevealProbeTarget.State.SCROLL,CardRevealProbeTarget.select(report,523,1000).state());
        assertFalse(CardRevealProbeTarget.initialClipped(report,523,1000));
    }
    @Test public void clippingNeverSuppliesAnUndeclaredScrollDirection(){
        var report=report(100,350,135,false,OverviewUiProtocol.ScrollHint.DECLARED_FORWARD);
        assertEquals(CardRevealProbeTarget.State.NO_DIRECTION,CardRevealProbeTarget.select(report,523,1000).state());
        assertFalse(CardRevealProbeTarget.initialClipped(report,523,1000));
    }
    @Test public void captureSafetyMarginAloneIsNotClippingEvidence(){
        var report=report(170,420,195,true,OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        assertEquals(CardRevealProbeTarget.State.SCROLL,CardRevealProbeTarget.select(report,523,1000).state());
        assertFalse(CardRevealProbeTarget.initialClipped(report,523,1000));
    }
    @Test public void knownChromeOverlapNeedsAVisibleHeadingBelowChrome(){
        assertTrue(CardRevealProbeTarget.initialClipped(report(100,350,135,true,
                OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000));
        assertFalse(CardRevealProbeTarget.initialClipped(report(100,350,110,true,
                OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000));
        assertFalse(CardRevealProbeTarget.initialClipped(null,523,1000));
    }
    @Test public void uiIncompleteBottomRegionCanStartOnlyBoundedRevealScroll(){
        var report=report(650,930,670,false,OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        assertEquals(ReviewShizukuSwipeActor.Direction.UP,CardRevealProbeTarget.select(report,523,1000).direction());
        assertTrue(CardRevealProbeTarget.initialClipped(report,523,1000));
        assertFalse(CardRevealProbeTarget.initialClipped(report,0,1000));
    }
}
