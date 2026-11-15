package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemainingCardUiParserTest {
    static NativeUiBoundPointsParser.Node node(int id,int parent,boolean visible,String resource,String text,String description,int l,int t,int r,int b){
        return new NativeUiBoundPointsParser.Node(id,parent,visible,true,resource,text,description,l,t,r,b);
    }
    static List<NativeUiBoundPointsParser.Node> sample(){
        var n=new ArrayList<NativeUiBoundPointsParser.Node>();
        n.add(node(0,-1,true,"","","",0,0,1440,2560));
        n.add(node(1,0,true,"homePageWrapper","","",0,0,1440,2334));
        n.add(node(2,1,true,"dailyTaskPanel","","",64,80,1376,2334));
        n.add(node(3,2,true,"dailySet","","",64,100,1376,1400));
        n.add(node(4,3,true,"","每日活动","",112,120,348,200));
        for(int i=0;i<3;i++){
            int id=5+2*i,top=300+i*300;String text="Synthetic task "+i+", + 10 积分";
            n.add(node(id,3,true,"","",text,112,top,1328,top+240));
            n.add(node(id+1,id,true,"","",text,160,top+48,1280,top+192));
        }
        n.add(node(11,2,true,"","更多活动","",64,1550,1376,2334));
        n.add(node(12,11,true,"more-activities-panel-title","更多活动","",64,1550,380,1630));
        n.add(node(13,11,true,"","","",1136,1550,1376,1630));
        n.add(node(14,13,true,"","0/35","",1136,1550,1248,1630));
        n.add(node(15,13,true,"","","",1280,1550,1376,1630));
        n.add(node(16,11,true,"moreActivities","","",64,1670,1376,2334));
        for(int i=0;i<7;i++){
            int id=17+2*i,top=1720+i*290;boolean visible=i<3;int left=visible?112:0,right=visible?1328:0;
            int y=visible?top:0,bottom=visible?Math.min(top+240,2334):0;
            String text="Synthetic more "+i+", "+(i<3?"+ 5":"5")+" 积分";
            n.add(node(id,16,visible,"",i<3?"":text,i<3?text:"",left,y,right,bottom));
            if(i<3)n.add(node(id+1,id,visible,"","",text,left+48,y+Math.min(48,Math.max(1,bottom-y-2)),right-48,bottom-1));
        }
        return n;
    }
    private static NativeUiBoundPointsParser.Node get(List<NativeUiBoundPointsParser.Node> n,int id){return n.stream().filter(x->x.index()==id).findFirst().orElseThrow();}
    private static void replace(List<NativeUiBoundPointsParser.Node> n,int id,NativeUiBoundPointsParser.Node replacement){for(int i=0;i<n.size();i++)if(n.get(i).index()==id){n.set(i,replacement);return;}throw new AssertionError();}
    private static void hide(List<NativeUiBoundPointsParser.Node> n,int id){var x=get(n,id);replace(n,id,node(id,x.parentIndex(),false,x.resourceId(),x.text(),x.description(),0,0,0,0));}
    @Test public void knownContainersProduceStableFullDomIndicesAndNoText(){
        var r=RemainingCardUiParser.parse(sample());assertEquals(RemainingCardUi.Status.OBSERVED,r.status());
        assertEquals(3,r.dailyCount());assertEquals(7,r.moreCount());assertEquals(6,r.items().size());
        assertEquals(1,r.items().get(0).index());assertEquals(3,r.items().get(5).index());
        for(var item:r.items()){assertNull(item.title());assertNull(item.reward());}
        assertFalse(r.items().get(5).completeVisible());assertTrue(r.items().get(4).completeVisible());
    }
    @Test public void offscreenEarlierCardsDoNotRenumberVisibleLaterCards(){
        var n=sample();hide(n,5);hide(n,6);var r=RemainingCardUiParser.parse(n);
        assertEquals(3,r.dailyCount());assertEquals(2,r.items().get(0).index());
    }
    @Test public void invisibleLazyContainerCannotClaimOneCardOrZero(){
        var n=sample();hide(n,16);for(int id:new int[]{17,18,19,20,21,22,23,25,27,29})hide(n,id);
        var r=RemainingCardUiParser.parse(n);assertEquals(3,r.dailyCount());assertEquals(-1,r.moreCount());
        assertTrue(r.items().stream().noneMatch(x->x.role()==OverviewUiRegions.Role.MORE_ACTIVITIES));
    }
    @Test public void unknownFooterDoesNotCountAsACompactTask(){
        var n=sample();n.add(node(31,16,false,"","Synthetic footer","",0,0,0,0));
        var r=RemainingCardUiParser.parse(n);assertEquals(3,r.dailyCount());assertEquals(-1,r.moreCount());
    }
    @Test public void mergedManyCardDescriptionDoesNotBecomeSingleCard(){
        var n=sample();var a=get(n,17);var b=get(n,18);String aggregate="Synthetic A, + 5 积分, Synthetic B, + 5 积分";
        replace(n,17,node(17,a.parentIndex(),true,"","",aggregate,a.left(),a.top(),a.right(),a.bottom()));
        replace(n,18,node(18,b.parentIndex(),true,"","",aggregate,b.left(),b.top(),b.right(),b.bottom()));
        var r=RemainingCardUiParser.parse(n);assertEquals(-1,r.moreCount());assertEquals(3,r.dailyCount());
    }
    @Test public void moreAnchorCannotBeBorrowedFromOtherAncestor(){
        var n=sample();var a=get(n,16);replace(n,16,node(a.index(),2,a.visible(),a.resourceId(),a.text(),a.description(),a.left(),a.top(),a.right(),a.bottom()));
        assertEquals(-1,RemainingCardUiParser.parse(n).moreCount());
    }
    @Test public void searchAndReadingContainerNeverContributeCards(){
        var n=sample();n.add(node(31,2,true,"dailyActivities","","",64,1400,1376,1500));
        n.add(node(32,31,true,"search","Synthetic search, + 5 积分","",112,1410,1328,1490));
        var r=RemainingCardUiParser.parse(n);assertEquals(3,r.dailyCount());assertEquals(7,r.moreCount());
    }
    @Test public void foreignOrWrongOverviewCannotProvideAnyRoster(){
        var n=sample();var a=get(n,6);replace(n,6,new NativeUiBoundPointsParser.Node(a.index(),a.parentIndex(),a.visible(),false,a.resourceId(),a.text(),a.description(),a.left(),a.top(),a.right(),a.bottom()));
        assertEquals(RemainingCardUi.Status.WRONG_STRUCTURE,RemainingCardUiParser.parse(n).status());
        assertEquals(RemainingCardUi.Status.WRONG_STRUCTURE,RemainingCardUiParser.parse(sample(),OverviewUiRegions.Result.rejected(OverviewUiRegions.Status.WRONG_STRUCTURE)).status());
    }
    @Test public void overlappingCardsCannotBecomeIndependentTargets(){
        var n=sample();var a=get(n,7);var b=get(n,8);
        replace(n,7,node(a.index(),a.parentIndex(),true,"",a.text(),a.description(),112,300,1328,540));
        replace(n,8,node(b.index(),b.parentIndex(),true,"",b.text(),b.description(),160,348,1280,492));
        assertEquals(RemainingCardUi.Status.AMBIGUOUS_STRUCTURE,RemainingCardUiParser.parse(n).status());
    }
    @Test public void resultRejectsUnknownRolesDuplicateIndicesAndForeignBoxes(){
        assertThrows(IllegalArgumentException.class,()->new RemainingCardUi.Item(OverviewUiRegions.Role.CHECK_IN,1,new Box(1,1,100,100),null,null,true));
        var item=new RemainingCardUi.Item(OverviewUiRegions.Role.DAILY_ACTIVITIES,1,new Box(1,1,100,100),null,null,true);
        assertThrows(IllegalArgumentException.class,()->new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED,2,-1,List.of(item,item)));
        assertThrows(IllegalArgumentException.class,()->new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED,-1,-1,List.of(item)));
        assertThrows(IllegalArgumentException.class,()->new RemainingCardUi.Item(OverviewUiRegions.Role.DAILY_ACTIVITIES,1,new Box(1,1,100,100),new Box(99,99,101,101),null,true));
    }
}
