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
    private static List<Node> collapsedList(){
        var nodes=new ArrayList<Node>();
        for(Node n:list(false)){
            if(n.index()==0){nodes.add(n(0,-1,b(0,0,1000,2000),"android:id/content",null,false));continue;}
            if(n.index()==1){
                nodes.add(n(40,0,b(0,0,1000,2000),"com.microsoft.bing:id/container",null,false));
                nodes.add(n(1,40,b(0,0,1000,2000),"compose_home_root",null,false));
                nodes.add(n(41,1,b(0,0,1000,12),null,null,false));
                nodes.add(n(44,1,b(480,0,520,31),null,null,false));
                continue;
            }
            if(n.index()!=2)nodes.add(n);
        }
        return nodes;
    }
    @Test public void collapsedHomeBarAllowsOnlyTheFullCardCoveringTheOriginalAnchor(){
        var nodes=collapsedList();
        for(int anchor:List.of(-1,840,1180)){
            var f=NewsFeedModel.inspect(nodes,1000,2000,anchor,null);
            assertEquals(NewsFeedModel.Page.FEED,f.page());assertEquals("FEED_CANDIDATE",f.reason());
            assertEquals(b(0,31,1000,1840),f.viewport());assertEquals(b(50,430,950,1250),f.card().box());
            assertEquals(930,f.stride());
        }
        assertNull(NewsFeedModel.inspect(nodes,1000,2000,1500,null).card());
    }
    @Test public void missingHeaderNeedsOneCollapsedEdgeAndTheNativeBingContainerBranches(){
        var missing=collapsedList();missing.removeIf(n->n.index()==41);
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(missing,1000,2000,1180,null).page());
        var duplicate=collapsedList();duplicate.add(n(42,1,b(0,0,1000,18),null,null,false));
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(duplicate,1000,2000,1180,null).page());
        var wrongFooter=collapsedList();
        wrongFooter.replaceAll(node->node.index()==3?n(3,40,b(0,1840,1000,2000),"com.microsoft.bing:id/sa_main_footer",null,false):node);
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(wrongFooter,1000,2000,1180,null).page());
    }
    private static Node hiddenChrome(int id,Box box){
        return new Node(id,1,false,true,"","","",box.left(),box.top(),box.right(),box.bottom(),
                NativeUiBoundPointsParser.ClassRole.UNKNOWN,false);
    }
    private static List<Node> nativeCollapsedList(){
        var rows=collapsedList();
        rows.removeIf(n->n.index()==41||n.index()==44);
        rows.add(hiddenChrome(41,b(0,0,1000,12)));
        rows.add(hiddenChrome(44,b(480,0,520,31)));
        rows.add(hiddenChrome(47,b(0,0,1000,230)));
        return rows;
    }
    @Test public void hiddenNativeToolbarBoundsOnlyTheChromeAndNeverAuthorizesAnInvisibleCard(){
        var rows=nativeCollapsedList();
        for(int anchor:List.of(-1,1180)){
            var frame=NewsFeedModel.inspect(rows,1000,2000,anchor,null);
            assertEquals("FEED_CANDIDATE",frame.reason());assertEquals(31,frame.viewport().top());
            assertEquals(b(50,430,950,1250),frame.card().box());assertEquals(930,frame.stride());
        }
        for(int missing:List.of(41,44,47)){
            var incomplete=new ArrayList<>(rows);incomplete.removeIf(n->n.index()==missing);
            assertEquals("NO_FEED_CARDS",NewsFeedModel.inspect(incomplete,1000,2000,1180,null).reason());
        }
        var noSurface=new ArrayList<>(rows);
        noSurface.replaceAll(v->v.index()==4?n(4,1,b(0,180,1000,1840),null,null,false):v);
        assertEquals("NO_FEED_CARDS",NewsFeedModel.inspect(noSurface,1000,2000,1180,null).reason());
        var ambiguous=new ArrayList<>(rows);ambiguous.add(hiddenChrome(48,b(0,0,1000,14)));
        assertEquals("NO_FEED_CARDS",NewsFeedModel.inspect(ambiguous,1000,2000,1180,null).reason());
        rows.replaceAll(v->v.index()==10?new Node(10,v.parentIndex(),false,true,"","","",50,430,950,1250,
                NativeUiBoundPointsParser.ClassRole.UNKNOWN,true):v);
        assertNull(NewsFeedModel.inspect(rows,1000,2000,1180,null).card());
    }
    @Test public void hiddenChromePreservesEachCompleteSlotAndSkipsOnlyTheCoveredPhoto(){
        var rows=nativeCollapsedList();rows.removeIf(n->n.index()>=5&&n.index()<40);
        rows.add(n(5,4,b(50,260,950,990),null,null,false));item(rows,10,5,260,990,true);
        rows.add(n(17,4,b(50,1040,950,1770),null,null,false));item(rows,21,17,1040,1770,true);
        for(boolean covered:List.of(false,true)){
            if(covered)rows.add(n(65,5,b(420,480,580,570),null,null,true));
            var first=NewsFeedModel.inspect(rows,1000,2000,600,null);
            assertEquals(covered?"FEED_OCCLUDED":"FEED_CANDIDATE",first.reason());
            var next=NewsFeedModel.inspect(rows,1000,2000,600+first.stride(),null);
            assertEquals("FEED_CANDIDATE",next.reason());assertEquals(b(50,1040,950,1770),next.card().box());
        }
    }
    @Test public void nestedAdvertisingControlsDoNotReplaceTheCardsImageOrBadgeSlot(){
        var nodes=collapsedList();
        nodes.add(n(45,10,b(100,930,222,1060),null,null,true));
        nodes.add(n(46,45,b(120,970,198,1020),null,"",false));
        nodes.add(n(47,10,b(820,930,930,1060),null,null,true));
        var f=NewsFeedModel.inspect(nodes,1000,2000,1180,null);
        assertEquals(NewsFeedModel.Page.FEED,f.page());
        assertEquals(b(50,430,950,1250),f.card().box());assertEquals(b(80,460,920,920),f.card().image());
        assertTrue(f.card().source().top()>=f.card().image().bottom());
        assertTrue(f.card().source().right()<=f.card().box().left()+f.card().box().width()/5);
        nodes.add(n(49,10,b(390,635,610,740),null,null,true));
        assertCovered(f,NewsFeedModel.inspect(nodes,1000,2000,1180,null));
    }
    private static void assertCovered(NewsFeedModel.Frame before,NewsFeedModel.Frame after){
        assertEquals(NewsFeedModel.Page.FEED,after.page());assertEquals("FEED_OCCLUDED",after.reason());
        assertEquals(NewsFeedModel.Kind.UNKNOWN,after.card().kind());assertEquals(before.stride(),after.stride());
        assertEquals(before.viewport(),after.viewport());assertEquals(before.card().layoutKey(),after.card().layoutKey());
        assertEquals(before.card().box(),after.card().box());assertEquals(before.card().image(),after.card().image());
        assertEquals(before.card().source(),after.card().source());assertEquals(before.atEnd(),after.atEnd());
    }
    @Test public void siblingOrExternalSurfaceCoveringTheFreshPhotoPointIsASingleCardSkip(){
        var rows=list(false);rows.removeIf(n->n.index()>=5);
        rows.add(n(5,4,b(50,260,950,990),null,null,false));item(rows,10,5,260,990,true);
        rows.add(n(17,4,b(50,1040,950,1770),null,null,false));item(rows,21,17,1040,1770,true);
        var first=NewsFeedModel.inspect(rows,1000,2000,600,null);
        assertEquals(NewsFeedModel.Kind.ARTICLE,first.card().kind());
        for(int parent:List.of(0,1,4,5)){
            for(boolean clickable:List.of(false,true)){
                rows.add(n(75,parent,b(440,470,560,570),null,null,clickable));
                assertCovered(first,NewsFeedModel.inspect(rows,1000,2000,600,null));
                var next=NewsFeedModel.inspect(rows,1000,2000,1400,null);
                assertEquals("FEED_CANDIDATE",next.reason());assertEquals(NewsFeedModel.Kind.ARTICLE,next.card().kind());
                rows.remove(rows.size()-1);
            }
        }
    }
    @Test public void hiddenSurfaceAndAdjacentControlDoNotCoverTheExactPhotoCenter(){
        var rows=list(false);
        rows.add(new Node(64,5,false,true,null,"","",390,635,610,740,NativeUiBoundPointsParser.ClassRole.BUTTON,true));
        // The right bound is exclusive; this active sibling is beside the center at x=500.
        rows.add(n(65,5,b(390,635,500,740),null,null,true));
        var clear=NewsFeedModel.inspect(rows,1000,2000,840,null);
        assertEquals("FEED_CANDIDATE",clear.reason());assertEquals(NewsFeedModel.Kind.ARTICLE,clear.card().kind());
        rows.add(n(66,5,b(390,635,501,740),null,null,true));
        assertCovered(clear,NewsFeedModel.inspect(rows,1000,2000,840,null));
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
