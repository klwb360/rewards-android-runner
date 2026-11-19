package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.once.NativeUiBoundPointsParser.Node;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NewsFeedModelTest {
    static Node n(int id,int parent,Box b,String rid,String label,boolean click) {
        return new Node(id,parent,true,true,rid,label,"",b.left(),b.top(),b.right(),b.bottom(),
                label==null?NativeUiBoundPointsParser.ClassRole.UNKNOWN:NativeUiBoundPointsParser.ClassRole.TEXT,click);
    }
    static Box b(int l,int t,int r,int d){return new Box(l,t,r,d);}
    static List<Node> list(String first,String second,boolean secondAd) {
        List<Node> nodes=new ArrayList<>();
        nodes.add(n(0,-1,b(0,0,1080,1900),null,null,false));
        nodes.add(n(1,0,b(20,100,1060,1875),null,null,false));
        item(nodes,2,130,"城市日报 · 4 天",first);
        item(nodes,5,905,secondAd?"广告":"城镇时报 · 1 小时",second);
        return nodes;
    }
    static void item(List<Node> rows,int id,int top,String source,String title){
        rows.add(n(id,1,b(55,top,1025,top+750),"recycled-card",null,true));
        rows.add(n(id+1,id,b(115,top+485,440,top+535),null,source,false));
        rows.add(n(id+2,id,b(115,top+555,925,top+691),null,title,false));
    }
    @Test public void readsEqualPitchAndDistinguishesBadgeFromTopic(){
        var rows=list("有关广告的新闻","普通商品名称",true);
        var one=NewsFeedModel.inspect(rows,1080,1900,-1,null);
        assertEquals(NewsFeedModel.Page.FEED,one.page());
        assertEquals(775,one.stride());
        assertEquals(NewsFeedModel.Kind.ARTICLE,one.card().kind());
        assertTrue(one.card().key().matches("[a-f0-9]{64}"));
        var ad=NewsFeedModel.inspect(rows,1080,1900,1279,null);
        assertEquals(NewsFeedModel.Kind.AD,ad.card().kind());
        assertTrue(ad.card().key().isEmpty());
        assertNotEquals(one.card().layoutKey(),ad.card().layoutKey());
    }
    @Test public void articlesIdentifyByPublisherAndFullTitleNotSlotOrTime() {
        var before=NewsFeedModel.inspect(list("甲文章标题","乙文章标题",false),1080,1900,-1,null).card();
        var reordered=list("乙文章标题","甲文章标题",false);
        reordered.set(6,n(6,5,b(115,1390,440,1440),null,"城市日报 · 5 天",false));
        var after=NewsFeedModel.inspect(reordered,1080,1900,1280,null).card();
        assertEquals(before.key(),after.key());
        assertNotEquals(before.layoutKey(),after.layoutKey());
        assertNull(NewsFeedModel.inspect(reordered,1080,1900,920,null).card());
    }
    @Test public void duplicateFallbackIdsAndTruncatedTitlesNeverAuthorize(){
        var rows=list("相同新闻标题","相同新闻标题",false);
        rows.set(6,n(6,5,b(115,1390,440,1440),null,"城市日报 · 1 小时",false));
        var dup=NewsFeedModel.inspect(rows,1080,1900,-1,null).card();
        assertEquals(NewsFeedModel.Kind.UNKNOWN,dup.kind());assertTrue(dup.key().isEmpty());
        var clipped=list("可见标题被截断……","完整新闻标题",false);
        clipped.add(n(9,0,b(55,20,305,80),null,"新闻",false));
        assertEquals(NewsFeedModel.Kind.UNKNOWN,NewsFeedModel.inspect(clipped,1080,1900,-1,null).card().kind());
    }
    @Test public void blocksChallengeAndRejectsForeignAndUnboundArticle(){
        var list=list("长篇文章标题","第二个文章标题",false);
        var opened=NewsFeedModel.inspect(list,1080,1900,-1,null).card();
        list.add(n(10,0,b(10,10,300,85),null,"安全验证",false));
        assertEquals(NewsFeedModel.Page.BLOCKED,NewsFeedModel.inspect(list,1080,1900,-1,opened).page());
        list.remove(list.size()-1);
        list.add(new Node(9,0,true,false,null,"extra","",3,3,103,45));
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(list,1080,1900,-1,opened).page());
        List<Node> detail=new ArrayList<>();
        detail.add(n(0,-1,b(0,0,1080,1900),null,null,false));
        detail.add(n(10,0,b(15,50,1065,1750),null,null,false));
        detail.add(n(1,10,b(40,180,1000,330),null,"长篇文章标题",false));
        detail.add(n(2,10,b(40,350,500,430),null,"城市日报",false));
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(detail,1080,1900,-1,opened).page());
        String paragraph="一段足够长的虚构文章用来测试本地正文窗口的结构。第二段描述重复阅读卡片之后的页面形状和文章出版信息。";
        detail.add(n(3,0,b(40,450,1000,1250),null,paragraph,false));
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(detail,1080,1900,-1,opened).page());
        detail.set(4,n(3,10,b(40,450,1000,1250),null,paragraph,false));
        assertEquals(NewsFeedModel.Page.ARTICLE,NewsFeedModel.inspect(detail,1080,1900,-1,opened).page());
        detail.set(1,n(10,0,b(15,50,1065,1750),null,null,true));
        assertEquals(NewsFeedModel.Page.UNKNOWN,NewsFeedModel.inspect(detail,1080,1900,-1,opened).page());
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
    @Test public void missingSourceGeometryDoesNotBecomeArticleFromTitleAlone(){
        var nodes=list("普通新闻","广告标题",true);
        nodes.remove(3);
        var frame=NewsFeedModel.inspect(nodes,1080,1900,-1,null);
        assertEquals(NewsFeedModel.Page.UNKNOWN,frame.page());
    }
    @Test public void equalUnknownSiblingRetainsItsSlotWithoutGainingIdentity(){
        var rows=list("普通新闻","无法核对的标题",false);
        rows.remove(6);
        rows.add(n(9,0,b(55,15,310,75),null,"新闻",false));
        var result=NewsFeedModel.inspect(rows,1080,1900,1280,null);
        assertEquals(NewsFeedModel.Page.FEED,result.page());
        assertEquals(775,result.stride());
        assertEquals(NewsFeedModel.Kind.UNKNOWN,result.card().kind());
        assertTrue(result.card().key().isEmpty());
    }
    @Test public void splitPublisherAndTimeBelongToSameMetadataLine(){
        var rows=list("可靠的新闻标题","第二篇标题",false);
        rows.set(3,n(3,2,b(125,615,325,665),null,"城市日报",false));
        rows.add(n(12,2,b(335,615,450,665),null,"· 4 天",false));
        var card=NewsFeedModel.inspect(rows,1080,1900,-1,null).card();
        assertEquals(NewsFeedModel.Kind.ARTICLE,card.kind());
        assertEquals("城市日报",card.publisher());assertEquals(b(125,615,450,665),card.source());
    }
    @Test public void combinedAdvertisingLabelAndPublisherNeverIdentifyANewsArticle(){
        var rows=list("普通商品名称","第二篇标题",false);
        rows.set(3,n(3,2,b(115,615,440,665),null,"广告商家 · 4 天",false));
        var result=NewsFeedModel.inspect(rows,1080,1900,-1,null);
        assertEquals(NewsFeedModel.Kind.AD,result.card().kind());
        assertTrue(result.card().key().isEmpty());
    }
    @Test public void footerEndsOnlyTheLastAnchoredCard(){
        var rows=list("第一篇标题","第二篇标题",false);
        rows.add(n(9,1,b(100,1670,980,1770),null,"没有更多新闻",false));
        assertFalse(NewsFeedModel.inspect(rows,1080,1900,-1,null).atEnd());
        assertTrue(NewsFeedModel.inspect(rows,1080,1900,1280,null).atEnd());
    }
    @Test public void anArticleWithRecommendationCardsStillRequiresOneReturn(){
        var opened=NewsFeedModel.inspect(list("已打开的新闻","其他文章标题",false),1080,1900,-1,null).card();
        List<Node> rows=new ArrayList<>();
        rows.add(n(0,-1,b(0,0,1080,2700),null,null,false));
        rows.add(n(20,0,b(15,30,1065,755),null,null,false));
        rows.add(n(21,20,b(40,80,980,160),null,"已打开的新闻",false));
        rows.add(n(22,20,b(40,180,300,250),null,"城市日报",false));
        rows.add(n(23,20,b(40,300,980,650),null,"一段足够长的虚构文章用来测试本地正文窗口的结构。第二段描述重复阅读卡片之后的页面形状和文章出版信息。",false));
        rows.add(n(1,0,b(20,800,1060,2650),null,null,false));
        item(rows,2,875,"新闻甲 · 2 天","推荐的第一篇");
        item(rows,5,1650,"新闻乙 · 1 天","推荐的第二篇");
        assertEquals(NewsFeedModel.Page.FEED,NewsFeedModel.inspect(rows,1080,2700,-1,null).page());
        assertEquals(NewsFeedModel.Page.ARTICLE,NewsFeedModel.inspect(rows,1080,2700,-1,opened).page());
    }
}
