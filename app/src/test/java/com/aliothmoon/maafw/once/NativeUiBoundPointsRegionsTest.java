package com.aliothmoon.maafw.once;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.NativeUiBoundPointsParserTest.*;
import static com.aliothmoon.maafw.once.NativeUiStructureParser.Status.*;

public class NativeUiBoundPointsRegionsTest {
    private static String summary(boolean search) {
        return text(search, search ? "搜索奖励" : "阅读奖励", "1", search ? "60" : "30");
    }

    private static NativeUiBoundPointsParser.BoundResult observe(List<NativeUiBoundPointsParser.Node> nodes) {
        var bound = NativeUiBoundPointsParser.parseRegions(nodes);
        assertEquals(NativeUiBoundPointsParser.parse(nodes), bound.counters());
        assertFalse(bound.counters().actionAuthority());
        return bound;
    }

    private static void cardsOnly(NativeUiBoundPointsParser.BoundResult result) {
        assertEquals(new NativeUiRegions.Rect(0,0,1000,900), result.regions().search().card());
        assertEquals(new NativeUiRegions.Rect(0,1000,1000,1900), result.regions().reading().card());
        assertEquals(NativeUiRegions.Role.SEARCH, result.regions().search().role());
        assertEquals(NativeUiRegions.Role.READING, result.regions().reading().role());
        assertNull(result.regions().search().summary());
        assertNull(result.regions().reading().summary());
    }

    private static void invalidEmpty(List<NativeUiBoundPointsParser.Node> nodes) {
        var result = observe(nodes);
        status(INVALID, result.counters().search());
        status(INVALID, result.counters().reading());
        assertEquals(NativeUiRegions.Pair.empty(), result.regions());
    }

    @Test public void knownCountersExposeTheirExactDirectChildBounds() {
        var nodes = tree();
        nodes.add(new NativeUiBoundPointsParser.Node(6,4,true,true,null,summary(true),null,25,100,950,300));
        nodes.add(new NativeUiBoundPointsParser.Node(7,5,true,true,null,summary(false),null,35,1100,900,1300));
        var result = observe(nodes);
        known(result.counters().search(),1,60);
        known(result.counters().reading(),1,30);
        assertEquals(new NativeUiRegions.Rect(0,0,1000,900), result.regions().search().card());
        assertEquals(new NativeUiRegions.Rect(25,100,950,300), result.regions().search().summary());
        assertEquals(new NativeUiRegions.Rect(0,1000,1000,1900), result.regions().reading().card());
        assertEquals(new NativeUiRegions.Rect(35,1100,900,1300), result.regions().reading().summary());
    }

    @Test public void missingSummariesKeepReliableCardsOnly() {
        var result = observe(tree());
        status(UNKNOWN,result.counters().search());
        status(UNKNOWN,result.counters().reading());
        cardsOnly(result);
    }

    @Test public void oneKnownSummaryDoesNotInventTheOther() {
        var nodes = tree();nodes.add(n(6,5,null,summary(false),1100,1200));
        var result = observe(nodes);
        status(UNKNOWN,result.counters().search());known(result.counters().reading(),1,30);
        assertNotNull(result.regions().search().card());assertNull(result.regions().search().summary());
        assertEquals(new NativeUiRegions.Rect(0,1100,1000,1200),result.regions().reading().summary());
    }

    @Test public void identicalFieldsShareOneSummaryAndDescriptionAloneIsEnough() {
        for (boolean same : new boolean[] {true,false}) {
            var nodes = tree();
            var row = n(6,5,null,same ? summary(false) : null,1100,1200);
            nodes.add(copy(row,true,summary(false)));
            var result = observe(nodes);known(result.counters().reading(),1,30);
            assertEquals(new NativeUiRegions.Rect(0,1100,1000,1200),result.regions().reading().summary());
        }
    }

    @Test public void separateSummaryNodesRemainAmbiguousWithoutSummaryBounds() {
        for (int top : new int[] {1100,1200}) {
            var nodes = tree();nodes.add(n(6,5,null,summary(false),1100,1200));
            nodes.add(n(7,5,null,summary(false),top,top+100));
            var result = observe(nodes);status(AMBIGUOUS,result.counters().reading());cardsOnly(result);
        }
    }

    @Test public void distinctSummaryFieldsOnOneNodeRemainAmbiguous() {
        for (String second : new String[] {text(false,"阅读奖励","2","30"),text(false,"其他奖励","1","30")}) {
            var nodes = tree();
            nodes.add(copy(n(6,5,null,summary(false),1100,1200),true,second));
            var result = observe(nodes);status(AMBIGUOUS,result.counters().reading());cardsOnly(result);
        }
    }

    @Test public void hiddenSummaryIsNotAVisibleRegionAndStillPreventsFalseUniqueness() {
        var nodes = tree();nodes.add(copy(n(6,5,null,summary(false),1100,1200),false,null));
        var result = observe(nodes);status(UNKNOWN,result.counters().reading());cardsOnly(result);
        nodes.add(n(7,5,null,summary(false),1200,1300));
        result = observe(nodes);status(AMBIGUOUS,result.counters().reading());cardsOnly(result);
    }

    @Test public void unavailableHiddenBoundsNeverBecomeARectangle() {
        var nodes=tree();
        nodes.add(new NativeUiBoundPointsParser.Node(6,5,false,true,null,summary(false),null,-1,-1,-1,-1));
        var result=observe(nodes);status(UNKNOWN,result.counters().reading());cardsOnly(result);
    }

    @Test public void malformedHiddenSummaryInvalidatesCounterWithoutReusingCleanBounds() {
        var nodes=tree();nodes.add(n(6,5,null,summary(false),1100,1200));
        nodes.add(copy(n(7,5,null,"\u200b"+summary(false),1200,1300),false,null));
        var result=observe(nodes);status(INVALID,result.counters().reading());cardsOnly(result);
    }

    @Test public void nestedSummaryDoesNotBecomeADirectChildRegion() {
        var nodes = tree();nodes.add(n(6,5,null,null,1100,1500));
        nodes.add(n(7,6,null,summary(false),1200,1300));
        var result = observe(nodes);status(UNKNOWN,result.counters().reading());cardsOnly(result);
        nodes.add(n(8,5,null,summary(false),1400,1500));
        result = observe(nodes);status(AMBIGUOUS,result.counters().reading());cardsOnly(result);
    }

    @Test public void cardTextIsNotItsOwnSummaryRegion() {
        var nodes = tree();nodes.set(5,n(5,3,"read",summary(false),1000,1900));
        var result = observe(nodes);status(UNKNOWN,result.counters().reading());cardsOnly(result);
        nodes.add(n(6,5,null,summary(false),1100,1200));
        result = observe(nodes);status(AMBIGUOUS,result.counters().reading());cardsOnly(result);
    }

    @Test public void nearbySummaryOutsideCardAncestryCannotBind() {
        var nodes = tree();nodes.add(n(6,3,null,summary(false),1100,1200));
        var result = observe(nodes);status(UNKNOWN,result.counters().reading());cardsOnly(result);
    }

    @Test public void wrongRoleSummaryRejectsBothRegionsRegardlessOfVisibilityOrDepth() {
        for (boolean visible : new boolean[] {true,false}) for (boolean nested : new boolean[] {true,false}) {
            var nodes = tree();
            if (nested) nodes.add(n(6,5,null,null,1100,1500));
            nodes.add(copy(n(nested ? 7 : 6,nested ? 6 : 5,null,summary(true),1200,1300),visible,null));
            invalidEmpty(nodes);
        }
        var nodes = tree();nodes.set(4,copy(nodes.get(4),true,"\u200b"+summary(false)));invalidEmpty(nodes);
    }

    @Test public void invalidOwnSummaryKeepsCardButNoSummaryAndDoesNotPoisonOtherRole() {
        for (String invalid : new String[] {text(false,"阅读奖励","31","30"),text(false,"另一奖励","1","30")}) {
            var nodes = tree();nodes.add(n(6,4,null,summary(true),100,200));
            nodes.add(n(7,5,null,invalid,1100,1200));
            var result = observe(nodes);known(result.counters().search(),1,60);status(INVALID,result.counters().reading());
            assertNotNull(result.regions().search().summary());
            assertNotNull(result.regions().reading().card());assertNull(result.regions().reading().summary());
        }
    }

    @Test public void summaryOutsideItsCardCannotExposeBounds() {
        var nodes = tree();nodes.add(n(6,5,null,summary(false),800,1200));
        var result = observe(nodes);status(INVALID,result.counters().reading());cardsOnly(result);
    }

    @Test public void wrongOrNestedChainParentAndDuplicateResourcesRejectRegions() {
        var nodes = tree();nodes.set(5,n(5,2,"read",null,1000,1900));invalidEmpty(nodes);
        nodes=tree();nodes.set(5,n(5,4,"read",null,100,800));invalidEmpty(nodes);
        nodes=tree();nodes.add(copy(n(6,3,"read",null,1000,1900),false,null));invalidEmpty(nodes);
        nodes=tree();nodes.remove(5);invalidEmpty(nodes);
    }

    @Test public void hiddenInvalidOrOutOfRootCardCannotExposeBounds() {
        var nodes=tree();nodes.set(5,copy(nodes.get(5),false,null));invalidEmpty(nodes);
        nodes=tree();nodes.set(5,n(5,3,"read",null,1000,1000));invalidEmpty(nodes);
        nodes=tree();nodes.set(5,n(5,3,"read",null,1000,2100));invalidEmpty(nodes);
        nodes=tree();nodes.set(5,new NativeUiBoundPointsParser.Node(5,3,true,true,"read",null,null,-1,1000,1000,1900));invalidEmpty(nodes);
    }

    @Test public void invalidWholeShapeHasNoPartialRegions() {
        invalidEmpty(null);invalidEmpty(List.of());
        var nodes=tree();nodes.add(n(5,3,null,null,100,200));invalidEmpty(nodes);
        nodes=tree();nodes.add(n(6,99,null,null,100,200));invalidEmpty(nodes);
        nodes=tree();nodes.add(new NativeUiBoundPointsParser.Node(6,5,false,false,null,null,null,0,0,0,0));invalidEmpty(nodes);
    }

    @Test public void edgeAlignedSummaryAndMaximumCoordinatesArePreserved() {
        var nodes=new ArrayList<>(List.of(n(0,-1,"root",null,0,8192),n(1,0,"homePageWrapper",null,0,8192),
                n(2,1,"dailyTaskPanel",null,0,8192),n(3,2,"dailyActivities",null,0,8192),
                n(4,3,"search",null,0,4000),n(5,3,"read",null,4000,8192),
                n(6,4,null,summary(true),0,4000),n(7,5,null,summary(false),4000,8192)));
        var result=observe(nodes);known(result.counters().search(),1,60);known(result.counters().reading(),1,30);
        assertEquals(result.regions().search().card(),result.regions().search().summary());
        assertEquals(result.regions().reading().card(),result.regions().reading().summary());
        nodes.set(5,n(5,3,"read",null,4000,8193));invalidEmpty(nodes);
    }

    @Test public void overlappingOrIdenticalCardsRetainReadOnlyStructureWithoutInventingAuthority() {
        for (int[] reading : new int[][] {{500,1400},{0,900}}) {
            var nodes=tree();nodes.set(5,n(5,3,"read",null,reading[0],reading[1]));
            nodes.add(n(6,4,null,summary(true),100,200));nodes.add(n(7,5,null,summary(false),600,700));
            var result=observe(nodes);known(result.counters().search(),1,60);known(result.counters().reading(),1,30);
            assertEquals(new NativeUiRegions.Rect(0,reading[0],1000,reading[1]),result.regions().reading().card());
            assertNotNull(result.regions().reading().summary());assertFalse(result.counters().actionAuthority());
        }
    }

    @Test public void outputDoesNotRetainPrivateSourceStringsOrNodeIdentity() {
        String privateMiddle="Private secret headline",privateId="private-resource-id",privateText="private unrelated text";
        var nodes=tree();nodes.add(new NativeUiBoundPointsParser.Node(307,5,true,true,privateId,
                text(false,privateMiddle,"1","30"),privateText,20,1100,900,1200));
        var result=observe(nodes);known(result.counters().reading(),1,30);
        for(String raw:new String[]{privateMiddle,privateId,privateText,"homePageWrapper","dailyActivities"})assertFalse(raw,result.toString().contains(raw));
        assertFalse(result.toString().contains("index="));assertFalse(result.toString().contains("parentIndex="));
        assertFalse(result.toString().contains("resourceId="));assertFalse(result.toString().contains("description="));
    }

    @Test public void repeatedReadDoesNotMutateCallerNodes() {
        var nodes=tree();nodes.add(n(6,5,null,summary(false),1100,1200));
        var snapshot=List.copyOf(nodes);var first=observe(snapshot);
        assertEquals(first,observe(snapshot));assertEquals(snapshot,nodes);
    }

    private static NativeUiStructureProtocol.Task wireTask(NativeUiStructureParser.TaskResult task) {
        return new NativeUiStructureProtocol.Task(NativeUiStructureProtocol.Status.valueOf(task.status().name()),
                task.status()==KNOWN ? task.current() : -1,
                task.status()==KNOWN ? task.target() : -1,
                task.matchCount(),NativeUiStructureProtocol.Unit.valueOf(task.unit().name()));
    }

    @Test public void parserResultsRoundTripThroughUIS5AcrossCounterAndRegionStates() {
        var knownNodes=tree();knownNodes.add(n(6,4,null,summary(true),100,200));
        knownNodes.add(n(7,5,null,summary(false),1100,1200));
        var unknownNodes=tree();
        var ambiguousNodes=tree();ambiguousNodes.add(n(6,5,null,summary(false),1100,1200));
        ambiguousNodes.add(n(7,5,null,summary(false),1200,1300));
        var invalidNodes=tree();invalidNodes.add(n(6,5,null,"阅读以赚取, wrong",1100,1200));
        var wrongParentNodes=tree();wrongParentNodes.set(5,n(5,2,"read",null,1000,1900));
        var wrongRoleNodes=tree();wrongRoleNodes.add(n(6,5,null,summary(true),1100,1200));
        var cases=List.of(knownNodes,unknownNodes,ambiguousNodes,invalidNodes,wrongParentNodes,wrongRoleNodes);
        var expected=List.of(KNOWN,UNKNOWN,AMBIGUOUS,INVALID,INVALID,INVALID);
        String nonce="0123456789abcdef0123456789abcdef";
        for(int i=0;i<cases.size();i++) {
            var nodes=cases.get(i);var bound=observe(nodes);
            status(expected.get(i),bound.counters().reading());
            if(i>=4)assertEquals(NativeUiRegions.Pair.empty(),bound.regions());
            else if(i==0)assertNotNull(bound.regions().reading().summary());
            else {assertNotNull(bound.regions().reading().card());assertNull(bound.regions().reading().summary());}
            int chars=0;
            for(var node:nodes) {
                if(node.text()!=null)chars+=node.text().length();
                if(node.description()!=null)chars+=node.description().length();
            }
            var report=new NativeUiStructureProtocol.Report(nonce,110,120,130,
                    NativeUiStructureProtocol.Reason.OK,NativeUiStructureProtocol.Cleanup.CONFIRMED,
                    nodes.size(),chars,3,0,0,0,1000,2000,-1,-1,
                    wireTask(bound.counters().search()),wireTask(bound.counters().reading()),
                    new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,0,0,0,0,0,0,0,0),
                    null,true,bound.regions());
            String wire=NativeUiStructureProtocol.encode(report);
            assertTrue(wire.startsWith("UIS5|"));
            assertEquals(report,NativeUiStructureProtocol.parse(wire,nonce,100,1000,140,true,false,true,true));
        }
    }
}
