package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;
import java.util.HashSet;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemainingCardTargetTest {
    private static final OverviewUiRegions.Role DAILY=OverviewUiRegions.Role.DAILY_ACTIVITIES;
    private static final OverviewUiRegions.Role MORE=OverviewUiRegions.Role.MORE_ACTIVITIES;
    private OverviewUiProtocol.Report report(int daily,int more,List<RemainingCardUi.Item> cards,List<OverviewUiRegions.Region> regions,OverviewUiProtocol.ScrollHint hint){
        var old=OverviewFrameCaptureTest.report();var owners=new java.util.ArrayList<>(regions);
        for(var item:cards)if(owners.stream().noneMatch(r->r.role()==item.role()))
            owners.add(new OverviewUiRegions.Region(item.role(),item.coarse(),null,null,item.completeVisible(),-1,-1));
        return new OverviewUiProtocol.Report(old.source(),new OverviewUiRegions.Result(OverviewUiRegions.Status.OBSERVED,new Box(0,0,523,1000),owners),hint,
                new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED,daily,more,cards));
    }
    private RemainingCardUi.Item item(int index,int top,int bottom,boolean complete){return new RemainingCardUi.Item(DAILY,index,new Box(30,top,493,bottom),null,null,complete);}
    @Test public void fixedChoicesExcludeSearchReadingAndCheckInAndNeverShareDirectories(){
        var dirs=new HashSet<String>();
        assertTrue(dirs.add(new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0).directory()));
        for(var role:new OverviewUiRegions.Role[]{DAILY,MORE})for(int i=1;i<=8;i++)assertTrue(dirs.add(new RemainingCardChoice(role,i).directory()));
        assertEquals(17,dirs.size());assertFalse(dirs.contains(CardRevealProbeScope.DIRECTORY));
        assertThrows(IllegalArgumentException.class,()->new RemainingCardChoice(OverviewUiRegions.Role.CHECK_IN,1));
        assertThrows(IllegalArgumentException.class,()->new RemainingCardChoice(DAILY,0));
        assertThrows(IllegalArgumentException.class,()->new RemainingCardChoice(MORE,9));
        assertThrows(IllegalArgumentException.class,()->new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,1));
    }
    @Test public void exactSlotUsesOnlyItsCompleteCardAndSmallMargin(){
        var r=RemainingCardTarget.select(report(3,-1,List.of(item(2,300,550,true)),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000,new RemainingCardChoice(DAILY,2));
        assertEquals("READY",r.reason());assertEquals(new Box(18,288,505,562),r.selection().crop());assertNull(r.selection().region().heading());
        assertTrue(r.selection().sameCandidate(r.selection()));
    }
    @Test public void visibleSlotsAreNotRenumbered(){
        var p=report(3,-1,List.of(item(2,300,550,true)),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        assertEquals(ReviewShizukuSwipeActor.Direction.DOWN,RemainingCardTarget.select(p,523,1000,new RemainingCardChoice(DAILY,1)).selection().direction());
        assertEquals(ReviewShizukuSwipeActor.Direction.UP,RemainingCardTarget.select(p,523,1000,new RemainingCardChoice(DAILY,3)).selection().direction());
    }
    @Test public void unknownRosterDoesNotTurnIntoNoCards(){
        var p=report(-1,-1,List.of(),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_FORWARD);
        var r=RemainingCardTarget.select(p,523,1000,new RemainingCardChoice(MORE,1));
        assertEquals(-1,r.moreCount());assertEquals(CardRevealProbeTarget.State.SCROLL,r.selection().state());
        var absent=RemainingCardTarget.select(report(-1,0,List.of(),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_FORWARD),523,1000,new RemainingCardChoice(MORE,1));
        assertEquals("NO_SUCH_CARD",absent.reason());assertNull(absent.selection().crop());
    }
    @Test public void fullRegionCounterSkipsEvenWithAnUnfinishedLookingCard(){
        var parent=new OverviewUiRegions.Region(DAILY,new Box(20,250,503,600),null,new Box(400,260,480,290),true,30,30);
        var r=RemainingCardTarget.select(report(1,-1,List.of(item(1,300,550,true)),List.of(parent),OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000,new RemainingCardChoice(DAILY,1));
        assertEquals("REGION_COMPLETE_SKIP",r.reason());assertNull(r.selection().crop());
    }
    @Test public void clippedAndOversizedCardsDoNotExpandToScreenCapture(){
        var partial=RemainingCardTarget.select(report(1,-1,List.of(item(1,300,550,false)),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000,new RemainingCardChoice(DAILY,1));
        assertEquals("CARD_PARTIAL",partial.reason());assertNull(partial.selection().crop());
        var large=RemainingCardTarget.select(report(1,-1,List.of(item(1,100,950,false)),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_BOTH),523,1000,new RemainingCardChoice(DAILY,1));
        assertEquals(CardRevealProbeTarget.State.TOO_LARGE,large.selection().state());assertNull(large.selection().crop());
    }
    @Test public void undeclaredScrollDirectionIsNeverInvented(){
        var r=RemainingCardTarget.select(report(1,-1,List.of(item(1,100,350,true)),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_FORWARD),523,1000,new RemainingCardChoice(DAILY,1));
        assertEquals(CardRevealProbeTarget.State.NO_DIRECTION,r.selection().state());assertNull(r.selection().direction());
    }
}
