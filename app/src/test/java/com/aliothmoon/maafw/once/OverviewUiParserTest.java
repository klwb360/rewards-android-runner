package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class OverviewUiParserTest {
    private static NativeUiBoundPointsParser.Node node(int id,int parent,String resource,String text,int l,int t,int r,int b) {
        return new NativeUiBoundPointsParser.Node(id,parent,true,true,resource,text,"",l,t,r,b);
    }
    static List<NativeUiBoundPointsParser.Node> sample() {
        var n=new ArrayList<NativeUiBoundPointsParser.Node>();
        n.add(node(0,-1,"","",0,0,1440,2400));n.add(node(1,0,"homePageWrapper","",0,0,1440,2400));
        n.add(node(2,1,"dailyTaskPanel","",64,100,1376,2380));
        n.add(node(3,2,"","连续",64,120,224,200));n.add(node(4,2,"","",268,120,628,200));
        n.add(node(5,2,"","11/12",1016,120,1376,200));
        n.add(node(6,2,"","",112,280,1328,360));n.add(node(7,6,"","签入",112,280,232,360));
        n.add(node(8,2,"","",112,400,1328,600));
        days(n,9,8,560);
        n.add(node(16,2,"","",112,700,1328,780));
        n.add(node(17,2,"","",112,820,1328,1020));
        n.add(node(18,2,"","",112,1100,1328,1180));n.add(node(19,18,"","每日活动",112,1100,348,1180));
        n.add(node(20,2,"","",112,1220,1328,1420));days(n,21,20,1380);
        n.add(node(28,2,"","今日积分",64,1500,380,1580));
        n.add(node(29,2,"","",1056,1500,1376,1580));n.add(node(30,29,"","30/60",1100,1500,1320,1580));
        n.add(node(31,2,"dailySet","",64,1650,1376,1850));n.add(node(32,31,"","每日活动",112,1680,348,1740));
        n.add(node(33,31,"","",112,1770,1328,1830));
        n.add(node(34,2,"dailyActivities","",64,1900,1376,2100));n.add(node(35,34,"","每日活动",112,1930,348,1990));
        n.add(node(36,2,"","更多活动",64,2160,1376,2320));
        n.add(node(37,36,"more-activities-panel-title","更多活动",64,2170,380,2230));
        n.add(node(38,36,"","0/20",1120,2170,1320,2230));return n;
    }
    private static void days(List<NativeUiBoundPointsParser.Node> n,int start,int parent,int y) {
        for(int i=0;i<7;i++)n.add(node(start+i,parent,"","Day "+(i+1),120+i*170,y,220+i*170,y+40));
    }
    private static OverviewUiRegions.Region get(OverviewUiRegions.Result r,OverviewUiRegions.Role role) {
        return r.regions().stream().filter(x->x.role()==role).findFirst().orElse(null);
    }
    private static void replace(List<NativeUiBoundPointsParser.Node> n,int id,java.util.function.UnaryOperator<NativeUiBoundPointsParser.Node> op) {
        for(int i=0;i<n.size();i++)if(n.get(i).index()==id){n.set(i,op.apply(n.get(i)));return;}
        throw new AssertionError();
    }
    @Test public void fixedHomeAncestryFindsAllSixRolesWithoutSearchOrReading() {
        var r=OverviewUiParser.parse(sample());assertEquals(OverviewUiRegions.Status.OBSERVED,r.status());
        assertEquals(6,r.regions().size());assertEquals(30,get(r,OverviewUiRegions.Role.DAILY_ACTIVITIES).current());
        assertEquals(60,get(r,OverviewUiRegions.Role.DAILY_ACTIVITIES).target());
        assertEquals(0,get(r,OverviewUiRegions.Role.MORE_ACTIVITIES).current());
        assertTrue(get(r,OverviewUiRegions.Role.CHECK_IN).completeVisible());
    }
    @Test public void dailyActivitiesContainerNeverBecomesDailySet() {
        var n=sample();replace(n,31,x->node(x.index(),x.parentIndex(),"unknown",x.text(),x.left(),x.top(),x.right(),x.bottom()));
        var r=OverviewUiParser.parse(n);assertNull(get(r,OverviewUiRegions.Role.DAILY_ACTIVITIES));
        assertNotNull(get(r,OverviewUiRegions.Role.DAILY_PROGRESS));
    }
    @Test public void foreignNodeRejectsWholeTree() {
        var n=sample();replace(n,33,x->new NativeUiBoundPointsParser.Node(x.index(),x.parentIndex(),true,false,x.resourceId(),x.text(),x.description(),x.left(),x.top(),x.right(),x.bottom()));
        assertEquals(OverviewUiRegions.Status.WRONG_STRUCTURE,OverviewUiParser.parse(n).status());
    }
    @Test public void movedTaskPanelParentRejected() {
        var n=sample();replace(n,2,x->node(x.index(),0,x.resourceId(),x.text(),x.left(),x.top(),x.right(),x.bottom()));
        assertEquals(OverviewUiRegions.Status.WRONG_STRUCTURE,OverviewUiParser.parse(n).status());
    }
    @Test public void duplicateResourceRejectedEvenWhenNotVisible() {
        var n=sample();n.add(new NativeUiBoundPointsParser.Node(39,2,false,true,"dailySet","","",0,0,0,0));
        assertEquals(OverviewUiRegions.Status.AMBIGUOUS_STRUCTURE,OverviewUiParser.parse(n).status());
    }
    @Test public void missingOrReorderedDayLabelsCannotFormCheckIn() {
        var n=sample();replace(n,10,x->node(x.index(),x.parentIndex(),"","Day 1",x.left(),x.top(),x.right(),x.bottom()));
        assertNull(get(OverviewUiParser.parse(n),OverviewUiRegions.Role.CHECK_IN));
    }
    @Test public void missingHeadingDoesNotInferFromDayRow() {
        var n=sample();replace(n,7,x->node(x.index(),x.parentIndex(),"","",x.left(),x.top(),x.right(),x.bottom()));
        assertNull(get(OverviewUiParser.parse(n),OverviewUiRegions.Role.CHECK_IN));
    }
    @Test public void fractionRequiresExactGrammarAndVisibleOwnership() {
        var n=sample();replace(n,30,x->node(x.index(),x.parentIndex(),"","30 / 60",x.left(),x.top(),x.right(),x.bottom()));
        var r=get(OverviewUiParser.parse(n),OverviewUiRegions.Role.DAILY_ACTIVITIES);
        assertEquals(-1,r.current());assertNull(r.counter());
    }
    @Test public void duplicateCounterRejectsWholeObservation() {
        var n=sample();n.add(node(39,36,"","20/20",900,2170,1100,2230));
        assertEquals(OverviewUiRegions.Status.AMBIGUOUS_STRUCTURE,OverviewUiParser.parse(n).status());
    }
    @Test public void counterOutsideContainerRejected() {
        var n=sample();replace(n,38,x->node(x.index(),x.parentIndex(),"","0/20",1120,2100,1320,2160));
        assertEquals(OverviewUiRegions.Status.AMBIGUOUS_STRUCTURE,OverviewUiParser.parse(n).status());
    }
    @Test public void unrelatedFractionBetweenHeadingAndCardsIsNotAttached() {
        var n=sample();n.add(31,node(39,2,"","",64,1600,1376,1620));
        var r=get(OverviewUiParser.parse(n),OverviewUiRegions.Role.DAILY_ACTIVITIES);
        assertEquals(-1,r.current());
    }
    @Test public void clippedOuterRegionDoesNotClaimCompleteVisibility() {
        var n=sample();replace(n,36,x->node(x.index(),x.parentIndex(),x.resourceId(),x.text(),x.left(),x.top(),x.right(),2400));
        assertFalse(get(OverviewUiParser.parse(n),OverviewUiRegions.Role.MORE_ACTIVITIES).completeVisible());
    }
    @Test public void malformedRootCannotProduceViewport() {
        var n=sample();replace(n,0,x->node(0,-1,"","",0,0,100,100));
        var r=OverviewUiParser.parse(n);assertEquals(OverviewUiRegions.Status.WRONG_STRUCTURE,r.status());assertNull(r.viewport());
    }
    @Test public void freshExactMoreAnchorCanBeOffscreenWithoutBorrowingOldCoordinates() {
        var n=sample();replace(n,37,x->new NativeUiBoundPointsParser.Node(x.index(),x.parentIndex(),false,true,x.resourceId(),x.text(),x.description(),0,0,0,0));
        var r=get(OverviewUiParser.parse(n),OverviewUiRegions.Role.MORE_ACTIVITIES);
        assertNotNull(r);assertNull(r.heading());assertNull(r.counter());
        assertEquals(-1,r.current());assertEquals(-1,r.target());
    }
    @Test public void cardFractionCannotBecomeMoreCounterWithoutHeaderCounter() {
        var n=sample();replace(n,38,x->new NativeUiBoundPointsParser.Node(x.index(),x.parentIndex(),false,true,x.resourceId(),x.text(),x.description(),0,0,0,0));
        addCardFraction(n);
        var parsed=OverviewUiParser.parse(n);assertEquals(OverviewUiRegions.Status.OBSERVED,parsed.status());
        var r=get(parsed,OverviewUiRegions.Role.MORE_ACTIVITIES);
        assertNotNull(r);assertNotNull(r.heading());assertNull(r.counter());
        assertEquals(-1,r.current());assertEquals(-1,r.target());
    }
    @Test public void actualMoreCounterIgnoresCardFraction() {
        var n=sample();addCardFraction(n);
        var parsed=OverviewUiParser.parse(n);assertEquals(OverviewUiRegions.Status.OBSERVED,parsed.status());
        var r=get(parsed,OverviewUiRegions.Role.MORE_ACTIVITIES);
        assertEquals(0,r.current());assertEquals(20,r.target());
        assertEquals(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(1120,2170,1320,2230),r.counter());
    }
    @Test public void offscreenMoreHeadingDoesNotBorrowVisibleCardFraction() {
        var n=sample();replace(n,37,x->new NativeUiBoundPointsParser.Node(x.index(),x.parentIndex(),false,true,x.resourceId(),x.text(),x.description(),0,0,0,0));
        addCardFraction(n);
        var parsed=OverviewUiParser.parse(n);assertEquals(OverviewUiRegions.Status.OBSERVED,parsed.status());
        var r=get(parsed,OverviewUiRegions.Role.MORE_ACTIVITIES);
        assertNotNull(r);assertNull(r.heading());assertNull(r.counter());
        assertEquals(-1,r.current());assertEquals(-1,r.target());
    }
    @Test public void moreCounterMustFitEntirelyInsideHeadingRightBand() {
        for(int[] bounds:new int[][]{{300,2170,500,2230},{1120,2169,1320,2230},{1120,2170,1320,2231}}) {
            var n=sample();replace(n,38,x->node(x.index(),x.parentIndex(),"","5/5",bounds[0],bounds[1],bounds[2],bounds[3]));
            var parsed=OverviewUiParser.parse(n);assertEquals(OverviewUiRegions.Status.OBSERVED,parsed.status());
            var r=get(parsed,OverviewUiRegions.Role.MORE_ACTIVITIES);
            assertNull(r.counter());assertEquals(-1,r.current());assertEquals(-1,r.target());
        }
    }
    private static void addCardFraction(List<NativeUiBoundPointsParser.Node> n) {
        n.add(node(39,36,"","",112,2240,1328,2310));
        n.add(node(40,39,"","5/5",1120,2250,1320,2290));
    }
    @Test public void absentMoreAnchorIsNotRecreatedFromContainerText() {
        var n=sample();replace(n,37,x->node(x.index(),x.parentIndex(),"","更多活动",x.left(),x.top(),x.right(),x.bottom()));
        assertNull(get(OverviewUiParser.parse(n),OverviewUiRegions.Role.MORE_ACTIVITIES));
    }
    @Test public void freshDailySetStillIdentifiedWhenItsTitleIsOffscreen() {
        var n=sample();replace(n,32,x->new NativeUiBoundPointsParser.Node(x.index(),x.parentIndex(),false,true,x.resourceId(),x.text(),x.description(),0,0,0,0));
        var r=get(OverviewUiParser.parse(n),OverviewUiRegions.Role.DAILY_ACTIVITIES);
        assertNotNull(r);assertNull(r.heading());assertEquals(30,r.current());
    }
    @Test public void browserChromeBelowHomeDoesNotMakeClippedCardComplete() {
        var n=sample();replace(n,1,x->node(x.index(),x.parentIndex(),x.resourceId(),x.text(),x.left(),x.top(),x.right(),2320));
        replace(n,2,x->node(x.index(),x.parentIndex(),x.resourceId(),x.text(),x.left(),x.top(),x.right(),2320));
        assertFalse(get(OverviewUiParser.parse(n),OverviewUiRegions.Role.MORE_ACTIVITIES).completeVisible());
    }
    @Test public void scrollHintsAreOnlyDeclarationsFromUniqueHomeAncestor() {
        var n=sample();assertEquals(OverviewUiProtocol.ScrollHint.DECLARED_BOTH,OverviewUiParser.scrollHint(n,List.of(new OverviewUiParser.ScrollNode(0,true,true))));
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(n,List.of(new OverviewUiParser.ScrollNode(8,true,true))));
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(n,List.of(new OverviewUiParser.ScrollNode(0,true,true),new OverviewUiParser.ScrollNode(0,true,false))));
    }
    @Test public void declaredNoneRemainsDistinctFromUnknownAndIsNotAnEdgeReceipt() {
        assertEquals(OverviewUiProtocol.ScrollHint.DECLARED_NONE,OverviewUiParser.scrollHint(sample(),List.of(new OverviewUiParser.ScrollNode(0,false,false))));
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(sample(),List.of()));
    }
    private static List<NativeUiBoundPointsParser.Node> selfScrollingHome() {
        // Synthetic structure only: non-scrollable wrappers, the scrolling home, and an inner carousel.
        return new ArrayList<>(List.of(
                node(0,-1,"","",0,0,1000,1800),
                node(1,0,"","",0,0,1000,1800),
                node(2,1,"homePageWrapper","",0,0,1000,1800),
                node(3,2,"","",0,100,1000,500),
                node(4,2,"dailyTaskPanel","",40,700,960,1800)));
    }
    @Test public void scrollingHomeItselfIsAcceptedAndNestedCarouselIsIgnored() {
        var n=selfScrollingHome();
        assertEquals(OverviewUiRegions.Status.OBSERVED,OverviewUiParser.parse(n).status());
        assertEquals(OverviewUiProtocol.ScrollHint.DECLARED_FORWARD,OverviewUiParser.scrollHint(n,List.of(
                new OverviewUiParser.ScrollNode(2,false,true),new OverviewUiParser.ScrollNode(3,true,true))));
    }
    @Test public void scrollingHomeStillRequiresExplicitDirectionDeclarations() {
        var n=selfScrollingHome();
        assertEquals(OverviewUiProtocol.ScrollHint.DECLARED_BACKWARD,OverviewUiParser.scrollHint(n,List.of(new OverviewUiParser.ScrollNode(2,true,false))));
        assertEquals(OverviewUiProtocol.ScrollHint.DECLARED_BOTH,OverviewUiParser.scrollHint(n,List.of(new OverviewUiParser.ScrollNode(2,true,true))));
        assertEquals(OverviewUiProtocol.ScrollHint.DECLARED_NONE,OverviewUiParser.scrollHint(n,List.of(new OverviewUiParser.ScrollNode(2,false,false))));
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(n,List.of()));
    }
    @Test public void selfAndAncestorOrDuplicateSelfRemainAmbiguous() {
        var n=selfScrollingHome();
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(n,List.of(
                new OverviewUiParser.ScrollNode(2,false,true),new OverviewUiParser.ScrollNode(1,false,true))));
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(n,List.of(
                new OverviewUiParser.ScrollNode(2,false,true),new OverviewUiParser.ScrollNode(2,false,true))));
    }
    @Test public void nestedAndUnrelatedScrollableNodesNeverSupplyHomeDirections() {
        var n=selfScrollingHome();
        // Even a nested node or sibling with the same bounds cannot become the home scroll owner.
        replace(n,3,x->node(3,2,"","",0,0,1000,1800));
        n.add(node(5,1,"","",0,0,1000,1800));
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(n,List.of(
                new OverviewUiParser.ScrollNode(3,true,true),new OverviewUiParser.ScrollNode(5,true,true))));
    }
    @Test public void selfCannotBypassForeignHiddenOrAmbiguousHomeChecks() {
        var foreign=selfScrollingHome();
        replace(foreign,2,x->new NativeUiBoundPointsParser.Node(2,1,true,false,"homePageWrapper","","",0,0,1000,1800));
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(foreign,List.of(new OverviewUiParser.ScrollNode(2,true,true))));
        var hidden=selfScrollingHome();
        replace(hidden,2,x->new NativeUiBoundPointsParser.Node(2,1,false,true,"homePageWrapper","","",0,0,1000,1800));
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(hidden,List.of(new OverviewUiParser.ScrollNode(2,true,true))));
        var duplicate=selfScrollingHome();duplicate.add(node(5,1,"homePageWrapper","",0,0,1000,1800));
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(duplicate,List.of(new OverviewUiParser.ScrollNode(2,true,true))));
    }
    @Test public void ancestorMustStillContainEntireHomeViewport() {
        var n=selfScrollingHome();replace(n,1,x->node(1,0,"","",0,0,1000,1700));
        assertEquals(OverviewUiProtocol.ScrollHint.UNKNOWN,OverviewUiParser.scrollHint(n,List.of(new OverviewUiParser.ScrollNode(1,true,true))));
    }
}
