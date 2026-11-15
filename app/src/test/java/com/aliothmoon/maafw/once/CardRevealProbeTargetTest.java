package com.aliothmoon.maafw.once;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CardRevealProbeTargetTest {
    private OverviewUiProtocol.Report report(int top,int bottom,boolean complete,OverviewUiProtocol.ScrollHint hint){
        var base=OverviewFrameCaptureTest.report();
        var r=new OverviewUiRegions.Region(OverviewUiRegions.Role.CHECK_IN,new Box(30,top,493,bottom),
                new Box(45,top+10,100,top+35),null,complete,-1,-1);
        return new OverviewUiProtocol.Report(base.source(),new OverviewUiRegions.Result(OverviewUiRegions.Status.OBSERVED,
                new Box(0,0,523,1000),List.of(r)),hint);
    }
    @Test public void completePanelUsesOnlySmallBorderMargin(){
        var result=CardRevealProbeTarget.select(report(300,550,true,OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000);
        assertEquals(CardRevealProbeTarget.State.CANDIDATE,result.state());assertEquals(new Box(18,288,505,562),result.crop());
        assertTrue(result.sameCandidate(result));assertFalse(result.sameCandidate(CardRevealProbeTarget.select(report(301,551,true,OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000)));
    }
    @Test public void clippedOrChromeCoveredPanelCannotTriggerCapture(){
        assertEquals(ReviewShizukuSwipeActor.Direction.DOWN,CardRevealProbeTarget.select(report(120,370,true,OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000).direction());
        assertEquals(ReviewShizukuSwipeActor.Direction.UP,CardRevealProbeTarget.select(report(650,930,true,OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000).direction());
        assertEquals(CardRevealProbeTarget.State.UNQUALIFIED,CardRevealProbeTarget.select(report(300,550,false,OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000).state());
    }
    @Test public void noNativeDirectionIsNotReplacedByGuessedInput(){
        var r=CardRevealProbeTarget.select(report(120,370,true,OverviewUiProtocol.ScrollHint.DECLARED_FORWARD),523,1000);
        assertEquals(CardRevealProbeTarget.State.NO_DIRECTION,r.state());assertNull(r.crop());assertNull(r.direction());
    }
    @Test public void targetTooLargeDoesNotExpandCropToViewport(){
        var r=CardRevealProbeTarget.select(report(1,999,true,OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000);
        assertEquals(CardRevealProbeTarget.State.TOO_LARGE,r.state());assertNull(r.crop());
    }
}
