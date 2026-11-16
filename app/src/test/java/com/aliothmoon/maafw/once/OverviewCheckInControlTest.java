package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.NativeUiBoundPointsParser.ClassRole.*;
import com.aliothmoon.maafw.once.NativeUiBoundPointsParser.ClassRole;
import com.aliothmoon.maafw.once.NativeUiBoundPointsParser.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.Test;

/** Generated UI nodes only; no XML, screenshot, device OCR or real input is used. */
public class OverviewCheckInControlTest {
    private static Node copy(Node n,ClassRole role,boolean clickable,String text,String desc){
        return new Node(n.index(),n.parentIndex(),n.visible(),n.packageMatches(),n.resourceId(),text,desc,n.left(),n.top(),n.right(),n.bottom(),role,clickable);
    }
    private static Node geometry(Node n,int l,int t,int r,int b){return new Node(n.index(),n.parentIndex(),n.visible(),n.packageMatches(),n.resourceId(),n.text(),n.description(),l,t,r,b,n.classRole(),n.clickable());}
    private static void replace(List<Node> nodes,int id,UnaryOperator<Node> change){for(int i=0;i<nodes.size();i++)if(nodes.get(i).index()==id){nodes.set(i,change.apply(nodes.get(i)));return;}throw new AssertionError();}
    private static List<Node> pair(){
        var nodes=OverviewUiParserTest.sample();replace(nodes,7,n->copy(n,TEXT,false,n.text(),n.description()));
        nodes.add(new Node(39,6,true,true,"","","签入",1260,288,1328,352,BUTTON,true));return nodes;
    }
    private static OverviewUiRegions.Region checkIn(OverviewUiRegions.Result result){return result.regions().stream().filter(r->r.role()==OverviewUiRegions.Role.CHECK_IN).findFirst().orElse(null);}
    private static void ambiguous(List<Node> nodes){assertEquals(OverviewUiRegions.Status.AMBIGUOUS_STRUCTURE,OverviewUiParser.parse(nodes).status());}
    private static void hide(List<Node> nodes,int id){replace(nodes,id,n->new Node(n.index(),n.parentIndex(),false,n.packageMatches(),n.resourceId(),n.text(),n.description(),0,0,0,0,n.classRole(),n.clickable()));}

    @Test public void aKnownTextHeadingAndSeparateKnownButtonRemainTwoPreservedNodes(){
        var nodes=pair();var saved=List.copyOf(nodes);var result=OverviewUiParser.parse(nodes);
        assertEquals(OverviewUiRegions.Status.OBSERVED,result.status());assertEquals(6,result.regions().size());
        var region=checkIn(result);assertNotNull(region);assertTrue(region.completeVisible());
        assertEquals(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(112,280,232,360),region.heading());
        assertEquals(saved,nodes);assertEquals(40,nodes.size());assertTrue(nodes.get(39).clickable());assertEquals("签入",nodes.get(39).description());
    }
    @Test public void realDuplicateTextOrAnyThirdMatchingNodeIsStillAmbiguous(){
        var twice=pair();replace(twice,39,n->copy(n,TEXT,false,"签入",""));ambiguous(twice);
        var third=pair();third.add(new Node(40,6,true,true,"","签入","",300,280,420,360,TEXT,false));ambiguous(third);
        var thirdButton=pair();thirdButton.add(new Node(40,6,false,true,"","","签入",0,0,0,0,BUTTON,true));ambiguous(thirdButton);
    }
    @Test public void unknownOrFalseClassOrClickabilityNeverSubstitutesForNativeRoleEvidence(){
        for(var role:ClassRole.values())if(role!=BUTTON){var nodes=pair();replace(nodes,39,n->copy(n,role,true,n.text(),n.description()));ambiguous(nodes);}
        for(var role:ClassRole.values())if(role!=TEXT){var nodes=pair();replace(nodes,7,n->copy(n,role,false,n.text(),n.description()));ambiguous(nodes);}
        var nonclick=pair();replace(nonclick,39,n->copy(n,BUTTON,false,n.text(),n.description()));ambiguous(nonclick);
        var clickTitle=pair();replace(clickTitle,7,n->copy(n,TEXT,true,n.text(),n.description()));ambiguous(clickTitle);
        var legacy=OverviewUiParserTest.sample();legacy.add(new Node(39,6,true,true,"","","签入",1260,288,1328,352));ambiguous(legacy);
        var old=legacy.get(7);assertEquals(UNKNOWN,old.classRole());assertFalse(old.clickable());
        assertNotNull(checkIn(OverviewUiParser.parse(OverviewUiParserTest.sample())));
    }
    @Test public void captionShapeAndLeafRequirementsCannotBeBypassed(){
        var bothFields=pair();replace(bothFields,7,n->copy(n,TEXT,false,"签入","签入"));ambiguous(bothFields);
        var buttonText=pair();replace(buttonText,39,n->copy(n,BUTTON,true,"签入","签入"));ambiguous(buttonText);
        for(int parent:new int[]{7,39}){
            var nodes=pair();nodes.add(new Node(40,parent,true,true,"","synthetic nested content","",1264,300,1300,320));ambiguous(nodes);
        }
    }
    @Test public void visibleGeometryMustFitTheObservedLeftTextAndSmallRightControl(){
        for(int[] b:new int[][]{{500,288,568,352},{1260,288,1350,352},{1120,288,1328,352},{1288,280,1328,320},{1260,288,1328,300}}){
            var nodes=pair();replace(nodes,39,n->geometry(n,b[0],b[1],b[2],b[3]));ambiguous(nodes);
        }
        for(int[] b:new int[][]{{500,280,620,360},{112,280,1200,360},{100,280,232,360}}){
            var nodes=pair();replace(nodes,7,n->geometry(n,b[0],b[1],b[2],b[3]));ambiguous(nodes);
        }
    }
    @Test public void sourceAndAncestryAndRootBoundsRemainStrict(){
        var foreign=pair();replace(foreign,39,n->new Node(n.index(),n.parentIndex(),n.visible(),false,n.resourceId(),n.text(),n.description(),n.left(),n.top(),n.right(),n.bottom(),n.classRole(),n.clickable()));
        assertEquals(OverviewUiRegions.Status.WRONG_STRUCTURE,OverviewUiParser.parse(foreign).status());
        var panel=pair();replace(panel,2,n->new Node(n.index(),0,n.visible(),n.packageMatches(),n.resourceId(),n.text(),n.description(),n.left(),n.top(),n.right(),n.bottom(),n.classRole(),n.clickable()));
        assertEquals(OverviewUiRegions.Status.WRONG_STRUCTURE,OverviewUiParser.parse(panel).status());
        var wrongParent=pair();replace(wrongParent,39,n->new Node(n.index(),18,n.visible(),n.packageMatches(),n.resourceId(),n.text(),n.description(),n.left(),n.top(),n.right(),n.bottom(),n.classRole(),n.clickable()));ambiguous(wrongParent);
        var outside=pair();replace(outside,39,n->geometry(n,1400,288,1450,352));assertEquals(OverviewUiRegions.Status.WRONG_STRUCTURE,OverviewUiParser.parse(outside).status());
    }
    @Test public void hiddenOrMixedVisibilityNeverGrantsACheckInRegionAndOtherRolesRemainReadable(){
        for(int mask=1;mask<=3;mask++){
            var nodes=pair();if((mask&1)!=0)hide(nodes,7);if((mask&2)!=0)hide(nodes,39);
            var result=OverviewUiParser.parse(nodes);assertEquals(OverviewUiRegions.Status.OBSERVED,result.status());assertNull(checkIn(result));
            assertTrue(result.regions().stream().anyMatch(r->r.role()==OverviewUiRegions.Role.DAILY_ACTIVITIES));
            assertTrue(result.regions().stream().anyMatch(r->r.role()==OverviewUiRegions.Role.MORE_ACTIVITIES));
        }
        var invalidVisible=pair();hide(invalidVisible,7);replace(invalidVisible,39,n->geometry(n,500,288,568,352));ambiguous(invalidVisible);
    }
    @Test public void liveLabelsCopyMetadataWithoutRemovingNodesOrMutatingTheOriginalFields(){
        var nodes=pair();replace(nodes,7,n->copy(n,TEXT,false,"签到",""));replace(nodes,39,n->copy(n,BUTTON,true,"","签到"));
        var fixed=LiveUiParser.labels(nodes);assertEquals(nodes.size(),fixed.size());assertEquals(TEXT,fixed.get(7).classRole());
        assertEquals(BUTTON,fixed.get(39).classRole());assertTrue(fixed.get(39).clickable());assertFalse(fixed.get(7).clickable());
        assertEquals("签到",nodes.get(7).text());assertEquals("签到",nodes.get(39).description());assertNotNull(checkIn(OverviewUiParser.parse(fixed)));
    }
    @Test public void classRoleClassifierAcceptsOnlyTheTwoExactObservedNativeClasses(){
        assertEquals(TEXT,NativeUiStructureHelper.liveClassRole("android.widget.TextView"));
        assertEquals(BUTTON,NativeUiStructureHelper.liveClassRole("android.widget.Button"));
        for(CharSequence c:new CharSequence[]{null,"android.widget.ImageButton","android.view.View","android.widget.Button ","Button","android.widget.textview"})
            assertEquals(UNKNOWN,NativeUiStructureHelper.liveClassRole(c));
    }
    private static int add(List<Node> n,int parent,String id,String text,String desc,int l,int t,int r,int b,ClassRole role,boolean clickable){
        int index=n.size();n.add(new Node(index,parent,true,true,id,text,desc,l,t,r,b,role,clickable));return index;
    }
    private static List<Node> activitiesWithPair(){
        List<Node> n=new ArrayList<>();add(n,-1,"","","",0,0,1440,2560,UNKNOWN,false);add(n,0,"homePageWrapper","","",0,0,1440,2400,UNKNOWN,false);
        add(n,1,"dailyTaskPanel","","",64,80,1376,2380,UNKNOWN,false);
        int header=add(n,2,"","","",112,100,1328,180,UNKNOWN,false);
        add(n,header,"","签入","",112,100,232,180,TEXT,false);add(n,header,"","","签入",1260,108,1328,172,BUTTON,true);
        int days=add(n,2,"","","",112,200,1328,400,UNKNOWN,false);
        for(int day=0;day<7;day++)add(n,days,"","Day "+(day+1),"",120+170*day,340,220+170*day,380,UNKNOWN,false);
        int daily=add(n,2,"dailySet","","",64,500,1376,1400,UNKNOWN,false);add(n,daily,"","每日活动","",112,520,348,580,UNKNOWN,false);
        for(int i=0;i<3;i++){
            int top=650+250*i;String desc="Synthetic task "+i+", + 10 积分";int card=add(n,daily,"","",desc,112,top,1328,top+200,UNKNOWN,false);
            add(n,card,"","",desc,160,top+40,1280,top+160,UNKNOWN,false);
        }
        int more=add(n,2,"","更多活动","",64,1500,1376,2330,UNKNOWN,false);
        add(n,more,"more-activities-panel-title","更多活动","",64,1510,380,1570,UNKNOWN,false);
        int counter=add(n,more,"","","",1136,1510,1376,1570,UNKNOWN,false);add(n,counter,"","0/20","",1136,1510,1248,1570,UNKNOWN,false);add(n,counter,"","","",1280,1510,1376,1570,UNKNOWN,false);
        int list=add(n,more,"moreActivities","","",64,1600,1376,2330,UNKNOWN,false);
        String desc="Synthetic more, + 5 积分";int card=add(n,list,"","",desc,112,1650,1328,1850,UNKNOWN,false);add(n,card,"","",desc,160,1690,1280,1810,UNKNOWN,false);
        add(n,list,"","Synthetic complete, 5 积分","",112,1900,1328,2050,UNKNOWN,false);return n;
    }
    @Test public void theHeaderFixPreservesDailyAndMoreFullRosterParsing(){
        var nodes=activitiesWithPair();var overview=OverviewUiParser.parse(nodes);assertEquals(OverviewUiRegions.Status.OBSERVED,overview.status());assertNotNull(checkIn(overview));
        var cards=RemainingCardUiParser.parse(nodes,overview);assertEquals(RemainingCardUi.Status.OBSERVED,cards.status());assertEquals(3,cards.dailyCount());assertEquals(2,cards.moreCount());assertEquals(5,cards.items().size());
        var identities=LiveUiParser.identities(nodes,overview,cards);assertEquals(6,identities.size());
    }
}
