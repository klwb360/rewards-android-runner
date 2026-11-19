package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.once.NativeUiBoundPointsParser.Node;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NewsFeedModelTest {
    static Box b(int l,int t,int r,int d){return new Box(l,t,r,d);}
    static Node n(int id,int parent,Box b,String rid,String label,boolean click){
        return new Node(id,parent,true,true,rid,label,"",b.left(),b.top(),b.right(),b.bottom(),
                label==null?NativeUiBoundPointsParser.ClassRole.UNKNOWN:NativeUiBoundPointsParser.ClassRole.TEXT,click);
    }
    static List<Node> list(boolean rotating){
        List<Node> nodes=new ArrayList<>();
        nodes.add(n(0,-1,b(0,0,1000,2000),null,null,false));
        nodes.add(n(1,0,b(0,0,1000,2000),"compose_home_root",null,false));
        nodes.add(n(2,1,b(50,40,950,200),"search_bar",null,true));
        nodes.add(n(3,0,b(0,1840,1000,2000),"com.microsoft.bing:id/sa_main_footer",null,false));
        nodes.add(n(4,1,b(0,0,1000,2000),null,null,false));
        nodes.add(n(5,4,b(50,430,950,1310),null,null,false));
        if(rotating)nodes.add(n(6,5,b(50,430,950,1250),null,null,false));
        item(nodes,10,rotating?6:5,430,1250,true);
        nodes.add(n(17,4,b(50,1360,950,2000),null,null,false));
        item(nodes,21,17,1360,2000,false);
        return nodes;
    }
    static void item(List<Node> nodes,int id,int parent,int y,int bottom,boolean full){
        Box card=b(50,y,950,bottom);
        nodes.add(n(id,parent,card,null,null,true));
        nodes.add(n(id+1,id,card,null,null,false));
        nodes.add(n(id+2,id,b(80,y+30,920,Math.min(bottom,y+490)),null,null,false));
        if(full)nodes.add(n(id+3,id,b(160,y+530,890,y+590),null,"",false));
    }
    @Test public void verifiedComposeSlotsUseAncestryImageAndMetadataGeometryAlone(){
        for(boolean rotating:List.of(false,true)){
            var f=NewsFeedModel.inspect(list(rotating),1000,2000,-1,null);
            assertEquals(NewsFeedModel.Page.FEED,f.page());assertEquals(930,f.stride());
            assertEquals(rotating?"FEED_CAROUSEL_CANDIDATE":"FEED_CANDIDATE",f.reason());
            assertEquals(b(50,430,950,1250),f.card().box());
            assertTrue(f.card().key().isEmpty());assertTrue(f.card().publisher().isEmpty());assertTrue(f.card().headline().isEmpty());
            assertTrue(f.card().source().right()<=f.card().box().left()+f.card().box().width()/5);
            assertTrue(f.card().source().top()>=f.card().image().bottom());
        }
    }
    @Test public void searchBarHeaderOrOccludedSecondCardCannotBecomeInput(){
        var nodes=list(false);
        assertNull(NewsFeedModel.inspect(nodes,1000,2000,1600,null).card());
        var onlyOne=new ArrayList<>(nodes);onlyOne.removeIf(v->v.index()>=17);
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(onlyOne,1000,2000,-1,null).page());
        nodes.removeIf(v->v.index()==2);
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(nodes,1000,2000,-1,null).page());
    }
    @Test public void repeatedForeignAndChallengeNodesRejectTheWholeFrame(){
        var nodes=list(false);nodes.add(n(29,0,b(20,30,300,100),null,"安全验证",false));
        assertEquals(NewsFeedModel.Page.BLOCKED,NewsFeedModel.inspect(nodes,1000,2000,-1,null).page());
        nodes.remove(nodes.size()-1);
        nodes.add(new Node(29,0,true,false,null,"","",0,0,20,22));
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(nodes,1000,2000,-1,null).page());
        nodes.remove(nodes.size()-1);nodes.add(nodes.get(0));
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(nodes,1000,2000,-1,null).page());
    }
    @Test public void articleUsesOwnedArticleContainerAndNeverRequiresBodyWords(){
        var opened=NewsFeedModel.inspect(list(false),1000,2000,-1,null).card();
        var nodes=new ArrayList<Node>();
        nodes.add(n(0,-1,b(0,0,1000,2000),null,null,false));
        nodes.add(n(1,0,b(10,20,990,1800),"com.microsoft.bing:id/article_body",null,false));
        nodes.add(n(2,1,b(40,80,940,160),null,"",false));
        nodes.add(n(3,1,b(40,200,940,940),null,"",false));
        assertEquals(NewsFeedModel.Page.ARTICLE,NewsFeedModel.inspect(nodes,1000,2000,-1,opened).page());
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(nodes,1000,2000,-1,null).page());
        nodes.set(1,n(1,0,b(10,20,990,1800),"unknown",null,false));
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(nodes,1000,2000,-1,opened).page());
    }
    @Test public void entryRequiresExactPageAncestryAndDefersClippedRegion() {
        List<Node> nodes=new ArrayList<>();
        nodes.add(n(0,-1,b(0,0,1080,1900),null,null,false));
        nodes.add(n(1,0,b(15,140,1065,1830),"homePageWrapper",null,false));
        nodes.add(n(2,1,b(20,160,1060,1820),"dailyTaskPanel",null,false));
        nodes.add(n(3,2,b(30,300,1050,1700),"dailyActivities",null,false));
        nodes.add(n(4,3,b(40,320,1040,550),"search",null,false));
        nodes.add(n(5,3,b(40,600,1040,1000),"read",null,false));
        nodes.add(n(6,5,b(70,610,1010,950),null,"阅读以赚取, 阅读奖励, 已赚取 10 积分(需要 30 积分)",true));
        var frame=NewsFeedModel.inspect(nodes,1080,1900,-1,null);
        assertEquals(NewsFeedModel.Page.REWARDS,frame.page());
        assertEquals(b(40,600,1040,1000),frame.entry());assertFalse(frame.entryComplete());
        nodes.set(6,n(6,5,b(70,610,1010,950),null,"阅读以赚取, 阅读奖励, 已赚取 30 积分(需要 30 积分)",true));
        assertTrue(NewsFeedModel.inspect(nodes,1080,1900,-1,null).entryComplete());
        nodes.set(5,n(5,3,b(40,1830,1040,1830),"read",null,false));
        frame=NewsFeedModel.inspect(nodes,1080,1900,-1,null);
        assertEquals(NewsFeedModel.Page.REWARDS,frame.page());assertNull(frame.entry());
        assertEquals("ENTRY_BELOW",frame.reason());
        nodes.set(5,n(5,3,b(40,140,1040,140),"read",null,false));
        assertEquals("ENTRY_ABOVE",NewsFeedModel.inspect(nodes,1080,1900,-1,null).reason());
        nodes.set(5,n(5,3,b(40,140,1040,246),"read",null,false));
        assertEquals("ENTRY_ABOVE",NewsFeedModel.inspect(nodes,1080,1900,-1,null).reason());
        nodes.set(5,n(5,3,b(0,0,0,0),"read",null,false));
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(nodes,1080,1900,-1,null).page());
        nodes.set(5,n(5,0,b(40,600,1040,1000),"read",null,false));
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(nodes,1080,1900,-1,null).page());
    }
}
