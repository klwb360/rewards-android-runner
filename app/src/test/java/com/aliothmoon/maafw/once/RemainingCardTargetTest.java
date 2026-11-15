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
    private OverviewUiRegions.Region anchor(OverviewUiRegions.Role role,int top,int bottom){
        return new OverviewUiRegions.Region(role,new Box(20,top,503,bottom),new Box(30,top+5,250,top+45),null,true,-1,-1);
    }
    private void direction(OverviewUiProtocol.Report page,RemainingCardChoice choice,ReviewShizukuSwipeActor.Direction previous,
            ReviewShizukuSwipeActor.Direction wanted){
        var r=RemainingCardTarget.select(page,523,1000,choice,previous);
        assertEquals(CardRevealProbeTarget.State.SCROLL,r.selection().state());assertEquals(wanted,r.selection().direction());assertNull(r.selection().crop());
    }
    @Test public void dailyMissingAboveVisibleMoreHeadingSearchesBackward(){
        var page=report(-1,-1,List.of(),List.of(anchor(MORE,300,600)),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        direction(page,new RemainingCardChoice(DAILY,1),null,ReviewShizukuSwipeActor.Direction.DOWN);
    }
    @Test public void dailyAndWeeklyMissingAboveVisibleMoreCardSearchBackward(){
        var more=new RemainingCardUi.Item(MORE,3,new Box(30,300,493,550),null,null,true);
        var page=report(-1,7,List.of(more),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        direction(page,new RemainingCardChoice(DAILY,1),ReviewShizukuSwipeActor.Direction.UP,ReviewShizukuSwipeActor.Direction.DOWN);
        direction(page,new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0),ReviewShizukuSwipeActor.Direction.UP,ReviewShizukuSwipeActor.Direction.DOWN);
    }
    @Test public void weeklyMissingAboveMoreHeadingAlsoSearchesBackward(){
        var page=report(-1,-1,List.of(),List.of(anchor(MORE,300,600)),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        direction(page,new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0),null,ReviewShizukuSwipeActor.Direction.DOWN);
    }
    @Test public void bottomBackwardOnlyDoesNotKeepAnImpossibleForwardSearch(){
        var page=report(-1,-1,List.of(),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_BACKWARD);
        direction(page,new RemainingCardChoice(DAILY,1),ReviewShizukuSwipeActor.Direction.UP,ReviewShizukuSwipeActor.Direction.DOWN);
        direction(page,new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0),ReviewShizukuSwipeActor.Direction.UP,ReviewShizukuSwipeActor.Direction.DOWN);
    }
    @Test public void emptyGapWithBothDeclarationsContinuesItsLastSearch(){
        var page=report(-1,-1,List.of(),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        direction(page,new RemainingCardChoice(DAILY,1),ReviewShizukuSwipeActor.Direction.DOWN,ReviewShizukuSwipeActor.Direction.DOWN);
        direction(page,new RemainingCardChoice(DAILY,1),ReviewShizukuSwipeActor.Direction.UP,ReviewShizukuSwipeActor.Direction.UP);
        direction(page,new RemainingCardChoice(DAILY,1),null,ReviewShizukuSwipeActor.Direction.UP);
    }
    @Test public void earlierKnownRegionsLeadTowardLaterTarget(){
        var page=report(-1,-1,List.of(),List.of(anchor(OverviewUiRegions.Role.CHECK_IN,250,400),anchor(OverviewUiRegions.Role.DAILY_PROGRESS,450,650)),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        direction(page,new RemainingCardChoice(DAILY,1),ReviewShizukuSwipeActor.Direction.DOWN,ReviewShizukuSwipeActor.Direction.UP);
        var top=report(-1,-1,List.of(),List.of(anchor(OverviewUiRegions.Role.STREAK_SUMMARY,250,400)),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        direction(top,new RemainingCardChoice(OverviewUiRegions.Role.DAILY_PROGRESS,0),null,ReviewShizukuSwipeActor.Direction.UP);
    }
    @Test public void contradictoryKnownAnchorsDoNotGuessOrCapture(){
        var page=report(-1,-1,List.of(),List.of(anchor(OverviewUiRegions.Role.CHECK_IN,250,400),anchor(MORE,600,800)),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        var r=RemainingCardTarget.select(page,523,1000,new RemainingCardChoice(DAILY,1),ReviewShizukuSwipeActor.Direction.DOWN);
        assertEquals("CONFLICTING_SEARCH_ANCHORS",r.reason());assertEquals(CardRevealProbeTarget.State.UNQUALIFIED,r.selection().state());
        assertNull(r.selection().direction());assertNull(r.selection().crop());
    }
    @Test public void ownVisibleHeadingEntersItsNotYetExpandedCardGroup(){
        var page=report(3,-1,List.of(),List.of(anchor(DAILY,250,600)),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        direction(page,new RemainingCardChoice(DAILY,2),ReviewShizukuSwipeActor.Direction.DOWN,ReviewShizukuSwipeActor.Direction.UP);
    }
    @Test public void knownCorrectDirectionStillRequiresItsOwnDeclaration(){
        var page=report(-1,-1,List.of(),List.of(anchor(MORE,300,600)),OverviewUiProtocol.ScrollHint.DECLARED_FORWARD);
        var r=RemainingCardTarget.select(page,523,1000,new RemainingCardChoice(DAILY,1),null);
        assertEquals(CardRevealProbeTarget.State.NO_DIRECTION,r.selection().state());assertNull(r.selection().direction());
        var noDeclaration=report(-1,-1,List.of(),List.of(),OverviewUiProtocol.ScrollHint.DECLARED_NONE);
        assertEquals(CardRevealProbeTarget.State.NO_DIRECTION,RemainingCardTarget.select(noDeclaration,523,1000,new RemainingCardChoice(DAILY,1),ReviewShizukuSwipeActor.Direction.DOWN).selection().state());
    }
    @Test public void aMissingSlotBetweenVisibleSameGroupCardsIsNotGuessed(){
        var owner=new OverviewUiRegions.Region(DAILY,new Box(20,250,503,750),null,null,true,-1,-1);
        var page=report(3,-1,List.of(item(1,300,450,true),item(3,550,700,true)),List.of(owner),OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
        var r=RemainingCardTarget.select(page,523,1000,new RemainingCardChoice(DAILY,2));
        assertEquals("AMBIGUOUS_CARD_GAP",r.reason());assertNull(r.selection().direction());assertNull(r.selection().crop());
    }
}
