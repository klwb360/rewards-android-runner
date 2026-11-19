package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NewsCardOcrPlanTest {
    private static final class Fixture {
        final NewsFeedModel.Frame frame=NewsFeedModel.inspect(NewsFeedModelTest.list(false),1000,2000,-1,null);
        final Box crop=frame.card().box();
        final int[] pixels=new int[crop.width()*crop.height()];
        final byte[] gray=new byte[pixels.length];
        boolean valid=true;
        Fixture(){
            Arrays.fill(pixels,0xffffffff);Arrays.fill(gray,(byte)255);
            for(int i:new int[]{0,crop.width()-1,pixels.length-crop.width(),pixels.length-1}){
                pixels[i]=0xffe2e2e2;gray[i]=(byte)226;
            }
            picture(false);
        }
        void picture(boolean invert){
            Box image=frame.card().image();
            for(int y=image.top()-crop.top();y<image.bottom()-crop.top();y++)
                for(int x=image.left()-crop.left();x<image.right()-crop.left();x++){
                    int ink=((x/17+y/23)%2==0)^invert?40:212;
                    pixels[y*crop.width()+x]=0xff000000+ink*0x010101;gray[y*crop.width()+x]=(byte)ink;
                }
        }
        NewsCardOcrPlan plan(boolean expand){
            var p=NewsCardOcrPlan.prepare(frame,crop,pixels,gray,expand,()->valid);
            assertNotNull(p);assertTrue(p.claim());assertFalse(p.claim());return p;
        }
    }
    private static FrameStructureEvidence.Line mark(String text,Box r){
        return new FrameStructureEvidence.Line(text,r.left()+4,r.top()+4,32,16,.94);
    }
    @Test public void onlyOneBadgeWindowIsRecognizedAndConfirmedEmptyProducesAPoint(){
        var f=new Fixture();var p=f.plan(false);
        assertEquals(1,p.rois().size());assertTrue(p.sameFrame(f.gray,f.crop.width(),f.crop.height()));
        var roi=p.rois().get(0);
        assertTrue(roi.right()<f.crop.width()/4);assertTrue(roi.height()<f.crop.height()/5);
        var result=p.finish(List.of(List.of()));
        assertEquals(NewsFeedModel.Kind.ARTICLE,result.kind());assertTrue(result.key().matches("[a-f0-9]{64}"));
        assertTrue(f.frame.card().image().contains(new Box(result.x(),result.y(),result.x()+1,result.y()+1)));
        var expanded=f.plan(true);assertTrue(expanded.rois().get(0).contains(roi));
        assertEquals(result.key(),expanded.finish(List.of(List.of(mark("1.",expanded.rois().get(0))))).key());
    }
    @Test public void ordinaryAndSplitAdMarksHaveNoClickPoint(){
        var f=new Fixture();
        for(boolean split:List.of(false,true)){
            var p=f.plan(false);Box r=p.rois().get(0);
            var rows=split?List.of(mark("广",r),new FrameStructureEvidence.Line("告",r.left()+40,r.top()+4,20,16,.96))
                    :List.of(mark("广告",r));
            var result=p.finish(List.of(rows));
            assertEquals(NewsFeedModel.Kind.AD,result.kind());assertEquals(-1,result.x());
        }
    }
    @Test public void badgeEvidenceWinsEvenWhenAnIconOrExtraRowFlanksIt(){
        var f=new Fixture();
        for(boolean split:List.of(false,true)){
            var p=f.plan(false);var roi=p.rois().get(0);int x=roi.left(),y=roi.top()+5;
            var icon=new FrameStructureEvidence.Line("1.",x+4,y,13,16,.52);
            var badge=new FrameStructureEvidence.Line(split?"广":"广告",x+24,y,18,16,.97);
            var tail=new FrameStructureEvidence.Line("告",x+45,y+1,17,16,.98);
            var extra=new FrameStructureEvidence.Line("图",x+70,y,15,16,.96);
            var rows=split?List.of(extra,icon,tail,badge):List.of(extra,icon,badge);
            var result=p.finish(List.of(rows));
            assertEquals(NewsFeedModel.Kind.AD,result.kind());assertEquals(-1,result.x());
        }
    }
    @Test public void isolatedOrSeparatedHalfCharactersNeverAuthorizeANewsTap(){
        var f=new Fixture();
        for(String half:List.of("广","告")){
            var p=f.plan(false);var roi=p.rois().get(0);int x=roi.left(),y=roi.top()+5;
            var fragment=new FrameStructureEvidence.Line(half,x+36,y,13,16,.99);
            var extra=new FrameStructureEvidence.Line("1.",x+5,y,12,16,.90);
            var result=p.finish(List.of(List.of(extra,fragment)));
            assertEquals("BADGE_PARTIAL",result.reason());assertEquals(-1,result.x());
        }
        var p=f.plan(false);var roi=p.rois().get(0);int x=roi.left(),y=roi.top()+5;
        var result=p.finish(List.of(List.of(new FrameStructureEvidence.Line("广",x+4,y,13,16,.94),
                new FrameStructureEvidence.Line("告",x+86,y,13,16,.96))));
        assertEquals("BADGE_PARTIAL",result.reason());
    }
    @Test public void missingOcrOrClippedOrUncertainBadgeIsNeverAnEmptyRead(){
        var f=new Fixture();
        assertEquals(NewsFeedModel.Kind.UNKNOWN,f.plan(false).finish(null).kind());
        assertEquals(NewsFeedModel.Kind.UNKNOWN,f.plan(false).finish(List.of()).kind());
        assertEquals(NewsFeedModel.Kind.UNKNOWN,f.plan(false).finish(java.util.Collections.singletonList(null)).kind());
        for(boolean edge:List.of(true,false)){
            var p=f.plan(false);var r=p.rois().get(0);
            var row=new FrameStructureEvidence.Line("广",r.left()+(edge?0:3),r.top()+3,18,14,edge?.98:.84);
            assertEquals(NewsFeedModel.Kind.UNKNOWN,p.finish(List.of(List.of(row))).kind());
        }
    }
    @Test public void fingerprintsChangeWithPhotographAndNeverWithUnrecognizedBodyText(){
        var f=new Fixture();var original=f.plan(false).finish(List.of(List.of()));
        for(int y=f.crop.height()-60;y<f.crop.height()-50;y++)for(int x=100;x<550;x++){
            f.pixels[y*f.crop.width()+x]=0xff444444;f.gray[y*f.crop.width()+x]=68;
        }
        assertEquals(original.key(),f.plan(false).finish(List.of(List.of())).key());
        f.picture(true);
        assertNotEquals(original.key(),f.plan(false).finish(List.of(List.of())).key());
        var stale=f.plan(false);f.gray[10]^=1;assertFalse(stale.sameFrame(f.gray,f.crop.width(),f.crop.height()));
        f.valid=false;assertEquals(NewsFeedModel.Kind.UNKNOWN,stale.finish(List.of(List.of())).kind());
    }
    @Test public void incompleteBorderOrFeaturelessImageCannotSupplyStoryTap(){
        var f=new Fixture();Arrays.fill(f.pixels,0xffffffff);
        assertNull(NewsCardOcrPlan.prepare(f.frame,f.crop,f.pixels,f.gray,false,()->true));
        for(int i:new int[]{0,f.crop.width()-1,f.pixels.length-f.crop.width(),f.pixels.length-1})f.pixels[i]=0xffe2e2e2;
        assertEquals("THUMBNAIL_NOT_DISTINCT",f.plan(false).finish(List.of(List.of())).reason());
    }
}
