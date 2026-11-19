package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NewsCardOcrPlanTest {
    private static final class Fixture {
        final NewsFeedModel.Frame frame;
        final Box crop;
        final int[] pixels;
        final byte[] gray;
        boolean valid=true;
        Fixture(boolean ad){
            frame=NewsFeedModel.inspect(NewsFeedModelTest.list("古城今日有一场特别演出","供应商品",ad),1080,1900,ad?1280:-1,null);
            crop=frame.card().box();
            pixels=new int[crop.width()*crop.height()];gray=new byte[pixels.length];
            Arrays.fill(pixels,0xffffffff);Arrays.fill(gray,(byte)255);
            for(int i:new int[]{0,crop.width()-1,pixels.length-crop.width(),pixels.length-1}){
                pixels[i]=0xffeeeff0;gray[i]=(byte)239;
            }
        }
        NewsCardOcrPlan plan(boolean expand){
            return NewsCardOcrPlan.prepare(frame,crop,pixels,gray,expand,()->valid);
        }
    }
    static FrameStructureEvidence.Line row(String text,Box r){
        return new FrameStructureEvidence.Line(text,r.left()+12,r.top()+5,Math.min(r.width()-15,160),Math.min(r.height()-9,25),.94);
    }
    @Test public void anchoredArticleUsesCurrentAbsoluteCoordinateAndTwoBoundLocalRois(){
        Fixture f=new Fixture(false);var plan=f.plan(false);assertNotNull(plan);
        assertEquals(2,plan.rois().size());assertTrue(plan.claim());assertFalse(plan.claim());
        assertTrue(plan.sameFrame(f.gray,f.crop.width(),f.crop.height()));
        var r=plan.finish(List.of(List.of(row("城市日报 · 4 天",plan.rois().get(0))),
                List.of(row("古城今日有一场特别演出",plan.rois().get(1)))));
        assertEquals(NewsFeedModel.Kind.ARTICLE,r.kind());assertEquals(f.frame.card().key(),r.key());
        assertEquals((int)f.frame.card().title().centerX(),r.x());assertEquals((int)f.frame.card().title().centerY(),r.y());
        assertEquals(NewsFeedModel.Kind.UNKNOWN,plan.finish(List.of()).kind());
    }
    @Test public void adBadgeInOwnSourceLineIsSkippedWithoutAnyPoint(){
        Fixture f=new Fixture(true);var p=f.plan(false);assertTrue(p.claim());assertEquals(1,p.rois().size());
        var r=p.finish(List.of(List.of(row("广告",p.rois().get(0)))));
        assertEquals(NewsFeedModel.Kind.AD,r.kind());assertEquals(-1,r.x());assertTrue(r.key().isEmpty());
        p=f.plan(true);assertTrue(p.claim());
        r=p.finish(List.of(List.of(row("其他供应商",p.rois().get(0)))));
        assertEquals(NewsFeedModel.Kind.UNKNOWN,r.kind());assertEquals(-1,r.x());
    }
    @Test public void strictOcrRetainsOriginalRoiConfidenceAndPublisherOwnership(){
        Fixture f=new Fixture(false);
        for(int i=0;i<3;i++) {
            var p=f.plan(i==2);assertTrue(p.claim());
            var source=p.rois().get(0);var title=p.rois().get(1);
            var s=i==0?new FrameStructureEvidence.Line("城市日报 · 4 天",source.left(),source.top()+5,100,22,.99)
                    :i==1?new FrameStructureEvidence.Line("城市日报 · 4 天",source.left()+6,source.top()+5,100,22,.84)
                    :row("另一家报纸 · 4 天",source);
            var r=p.finish(List.of(List.of(s),List.of(row("古城今日有一场特别演出",title))));
            assertEquals(NewsFeedModel.Kind.UNKNOWN,r.kind());assertEquals(-1,r.x());
        }
    }
    @Test public void widenedWindowStaysOnOneCardAndChangedGrayCannotBeRead(){
        Fixture f=new Fixture(false);
        var tight=f.plan(false);var broader=f.plan(true);
        assertTrue(broader.rois().get(0).contains(tight.rois().get(0)));
        Box viewport=new Box(0,0,1080,1900);
        assertNull(NewsCardOcrPlan.prepare(f.frame,viewport,new int[0],new byte[0],false,()->true));
        assertTrue(tight.claim());f.gray[10]^=1;
        assertFalse(tight.sameFrame(f.gray,f.crop.width(),f.crop.height()));
        f.valid=false;assertFalse(broader.claim());
    }
    @Test public void absentSameCardBorderAndOnlyATitleAreNotClickEvidence(){
        Fixture f=new Fixture(false);
        Arrays.fill(f.pixels,0xffffffff);assertNull(f.plan(false));
        Fixture good=new Fixture(false);var plan=good.plan(false);assertTrue(plan.claim());
        var r=plan.finish(List.of(List.of(row("城市日报 · 4 天",plan.rois().get(0))),
                List.of(row("另一篇标题",plan.rois().get(1)))));
        assertEquals(NewsFeedModel.Kind.UNKNOWN,r.kind());assertTrue(r.key().isEmpty());
    }
    @Test public void mergedOrSplitAdBadgeOverridesAnOtherwiseMatchingArticle(){
        Fixture f=new Fixture(false);
        for(boolean split:List.of(true,false)){
            var p=f.plan(false);assertTrue(p.claim());
            var src=p.rois().get(0);var title=p.rois().get(1);
            var lines=split?List.of(new FrameStructureEvidence.Line("广",src.left()+8,src.top()+5,16,20,.96),
                    new FrameStructureEvidence.Line("告城市日报 · 4 天",src.left()+29,src.top()+5,120,20,.95))
                    :List.of(row("广告城市日报 · 4 天",src));
            var result=p.finish(List.of(lines,List.of(row("古城今日有一场特别演出",title))));
            assertEquals(NewsFeedModel.Kind.AD,result.kind());assertEquals(-1,result.x());
        }
    }
    @Test public void publisherNameMustMatchInFull(){
        Fixture f=new Fixture(false);var p=f.plan(false);assertTrue(p.claim());
        var result=p.finish(List.of(List.of(row("新城市日报 · 4 天",p.rois().get(0))),
                List.of(row("古城今日有一场特别演出",p.rois().get(1)))));
        assertEquals(NewsFeedModel.Kind.UNKNOWN,result.kind());
    }
}
