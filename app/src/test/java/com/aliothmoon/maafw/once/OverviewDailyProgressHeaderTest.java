package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.once.NativeUiBoundPointsParser.ClassRole;
import com.aliothmoon.maafw.once.NativeUiBoundPointsParser.Node;
import com.aliothmoon.maafw.once.OverviewUiRegions.Role;
import com.aliothmoon.maafw.once.OverviewUiRegions.Status;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic Rewards panels with separate text headings and expand/collapse buttons. */
public class OverviewDailyProgressHeaderTest {
    private static final int WEEKLY_HEADER = 18;
    private static final int HEADING = 19;
    private static final int BUTTON = 39;
    private static final String DAILY = "每日活动";
    private static final Box LEFT = new Box(112,1100,348,1180);
    private static final Box RIGHT = new Box(1260,1108,1328,1172);

    private static Node leaf(int index, int parent, Box box, String id, String text,
                             String description, ClassRole role, boolean clickable) {
        if (box == null) return new Node(index,parent,false,true,id,text,description,0,0,0,0,role,clickable);
        return new Node(index,parent,true,true,id,text,description,
                box.left(),box.top(),box.right(),box.bottom(),role,clickable);
    }

    private static Node heading(int index, int parent, String label, Box box) {
        return leaf(index,parent,box,"",label,"",ClassRole.TEXT,false);
    }

    private static Node button(int index, int parent, String label, Box box) {
        return leaf(index,parent,box,"","",label,ClassRole.BUTTON,true);
    }

    private static List<Node> visiblePair() {
        var nodes = OverviewUiParserTest.sample();
        nodes.set(HEADING,heading(HEADING,WEEKLY_HEADER,DAILY,LEFT));
        nodes.add(button(BUTTON,WEEKLY_HEADER,DAILY,RIGHT));
        return nodes;
    }

    private static OverviewUiRegions.Region region(OverviewUiRegions.Result result, Role role) {
        return result.regions().stream().filter(r -> r.role() == role).findFirst().orElse(null);
    }

    private static void assertBadButton(Node replacement) {
        var nodes = visiblePair();
        nodes.set(BUTTON,replacement);
        assertEquals(Status.AMBIGUOUS_STRUCTURE,OverviewUiParser.parse(nodes).status());
    }

    @Test public void hiddenCollapsedWeeklyPairKeepsDailyAndMoreRostersReadable() {
        var nodes = RemainingCardUiParserTest.sample();
        var expectedOverview = OverviewUiParser.parse(nodes);
        var expectedCards = RemainingCardUiParser.parse(nodes,expectedOverview);
        // Insert a collapsed weekly header directly under the panel, before dailySet.
        nodes.addAll(3,List.of(leaf(50,2,null,"","","",ClassRole.UNKNOWN,false),
                heading(51,50,DAILY,null),button(52,50,DAILY,null)));

        var overview = OverviewUiParser.parse(nodes);
        assertEquals(expectedOverview,overview);
        assertEquals(Status.OBSERVED,overview.status());
        assertNull(region(overview,Role.DAILY_PROGRESS));
        assertNotNull(region(overview,Role.DAILY_ACTIVITIES));
        assertNotNull(region(overview,Role.MORE_ACTIVITIES));
        var cards = RemainingCardUiParser.parse(nodes,overview);
        assertEquals(expectedCards,cards);
        assertEquals(3,cards.dailyCount());
        assertEquals(7,cards.moreCount());
        assertEquals(10,LiveUiParser.identities(nodes,overview,cards).size());
    }

    @Test public void visiblePairsUseTheLeftHeadingAndStillRequireSevenDays() {
        var nodes = visiblePair();
        nodes.set(7,heading(7,6,"签入",new Box(112,280,232,360)));
        nodes.add(button(40,6,"签入",new Box(1260,288,1328,352)));
        var overview = OverviewUiParser.parse(nodes);
        assertEquals(Status.OBSERVED,overview.status());
        assertEquals(LEFT,region(overview,Role.DAILY_PROGRESS).heading());
        assertEquals(new Box(112,280,232,360),region(overview,Role.CHECK_IN).heading());

        nodes.removeIf(n -> n.index() == 27); // Remove Day 7 from the weekly activity.
        var incomplete = OverviewUiParser.parse(nodes);
        assertEquals(Status.OBSERVED,incomplete.status());
        assertNull(region(incomplete,Role.DAILY_PROGRESS));
        assertNotNull(region(incomplete,Role.CHECK_IN));
    }

    @Test public void oneVisibleLeafCannotExposeTheWeeklyCard() {
        for (boolean visibleHeading : new boolean[]{true,false}) {
            var nodes = visiblePair();
            nodes.set(HEADING,heading(HEADING,WEEKLY_HEADER,DAILY,visibleHeading ? LEFT : null));
            nodes.set(BUTTON,button(BUTTON,WEEKLY_HEADER,DAILY,visibleHeading ? null : RIGHT));
            var overview=OverviewUiParser.parse(nodes);
            assertEquals(Status.OBSERVED,overview.status());
            assertNull(region(overview,Role.DAILY_PROGRESS));
        }
    }

    @Test public void distinctNativeRolesAndFieldsAreRequiredEvenForAHiddenPair() {
        assertBadButton(leaf(BUTTON,WEEKLY_HEADER,RIGHT,"","",DAILY,ClassRole.UNKNOWN,true));
        assertBadButton(leaf(BUTTON,WEEKLY_HEADER,RIGHT,"","",DAILY,ClassRole.BUTTON,false));
        assertBadButton(leaf(BUTTON,WEEKLY_HEADER,RIGHT,"",DAILY,DAILY,ClassRole.BUTTON,true));
        var hidden = visiblePair();
        hidden.set(HEADING,leaf(HEADING,WEEKLY_HEADER,null,"",DAILY,"",ClassRole.UNKNOWN,false));
        hidden.set(BUTTON,button(BUTTON,WEEKLY_HEADER,DAILY,null));
        assertEquals(Status.AMBIGUOUS_STRUCTURE,OverviewUiParser.parse(hidden).status());
    }

    @Test public void duplicateHeadingsThirdLabelsOrNonLeafControlsAreAmbiguous() {
        assertBadButton(heading(BUTTON,WEEKLY_HEADER,DAILY,RIGHT));
        var third = visiblePair();
        third.add(heading(40,WEEKLY_HEADER,DAILY,null));
        assertEquals(Status.AMBIGUOUS_STRUCTURE,OverviewUiParser.parse(third).status());

        assertBadButton(leaf(BUTTON,WEEKLY_HEADER,RIGHT,"unrelated-control","",DAILY,ClassRole.BUTTON,true));
        var nested = visiblePair();
        nested.add(leaf(40,BUTTON,null,"","","",ClassRole.UNKNOWN,false));
        assertEquals(Status.AMBIGUOUS_STRUCTURE,OverviewUiParser.parse(nested).status());
    }

    @Test public void misplacedOrOverlappingButtonsAreAmbiguous() {
        assertBadButton(button(BUTTON,WEEKLY_HEADER,DAILY,new Box(700,1108,768,1172)));
        assertBadButton(button(BUTTON,WEEKLY_HEADER,DAILY,new Box(200,1108,268,1172)));
        assertBadButton(button(BUTTON,WEEKLY_HEADER,DAILY,new Box(1260,1164,1328,1228)));
    }

    @Test public void weeklyPairRuleCannotDisambiguateADailySetTitleOrForeignNode() {
        var nodes = visiblePair();
        // dailySet has its own named container and list. Give it the same plausible pair.
        nodes.set(32,heading(32,31,DAILY,new Box(112,1680,348,1740)));
        nodes.add(button(40,31,DAILY,new Box(1270,1672,1338,1740)));
        assertEquals(Status.AMBIGUOUS_STRUCTURE,OverviewUiParser.parse(nodes).status());

        var foreign = visiblePair();
        foreign.set(BUTTON,new Node(BUTTON,WEEKLY_HEADER,true,false,"","",DAILY,
                RIGHT.left(),RIGHT.top(),RIGHT.right(),RIGHT.bottom(),ClassRole.BUTTON,true));
        assertEquals(Status.WRONG_STRUCTURE,OverviewUiParser.parse(foreign).status());
    }
}
