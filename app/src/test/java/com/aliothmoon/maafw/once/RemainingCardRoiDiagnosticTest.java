package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthesized native JSON and pixels only. No source screenshots or native process. */
public class RemainingCardRoiDiagnosticTest {
    private OcrDetailDecoder.Result complete(RemainingCardRoiDiagnostic diagnostic,int index,String value,int width,int height){
        diagnostic.attempted(index);diagnostic.posted(index);diagnostic.completed(index,3000);diagnostic.detailStarted(index);
        Object token=new Object();var binding=new FrameStructureEvidence.Binding(token,token,token,token);
        var result=OcrDetailDecoder.decode(value.getBytes(StandardCharsets.UTF_8),binding,width,height,rows->diagnostic.rows(index,rows));
        diagnostic.decoded(index,result);return result;
    }
    private String row(String text,Box box){return "{\"text\":\""+text+"\",\"score\":0.95,\"box\":["+(box.left()+3)+","+(box.top()+3)+","+(box.width()-6)+","+(box.height()-6)+"]}";}
    @Test public void plannedVariableQueriesAreUnavailableNotInventedEmptyRows(){
        var f=RemainingCardPlanTest.activity(false);var diagnostic=new RemainingCardRoiDiagnostic(f.plan());
        var report=diagnostic.snapshot();assertEquals(2,report.rois().size());assertEquals("ACTIVITY_TITLE",report.firstProblemRole());
        assertEquals(-1,report.rois().get(1).rawCount());assertEquals(-1,report.rois().get(1).deliveredCount());
        assertEquals(0,RemainingCardRoiDiagnostic.empty(f.width(),f.height()).completedQueries());
        assertEquals("PLAN",RemainingCardRoiDiagnostic.empty(f.width(),f.height()).firstProblemRole());
    }
    @Test public void zeroAndMultiplePointRowsRemainDistinctFromUnknown(){
        var f=RemainingCardPlanTest.activity(false);var d=new RemainingCardRoiDiagnostic(f.plan());
        complete(d,0,"{\"all\":[]}",f.width(),f.height());var box=f.plan().rois().get(1);
        String r=row("+5",box);complete(d,1,"{\"all\":["+r+","+r+"]}",f.width(),f.height());
        assertEquals(0,d.snapshot().rois().get(0).rawCount());assertEquals(RemainingCardRoiDiagnostic.CountClass.ZERO,d.snapshot().rois().get(0).countClass());
        assertEquals(2,d.snapshot().rois().get(1).rawCount());assertEquals(2,d.snapshot().rois().get(1).deliveredCount());
        assertTrue(d.snapshot().rois().get(1).deliveryObserved());
    }
    @Test public void rejectedNativeCoordinatesRetainRawCountWithoutInventingDelivery(){
        var f=RemainingCardPlanTest.activity(false);var d=new RemainingCardRoiDiagnostic(f.plan());
        complete(d,0,"{\"all\":[{\"text\":\"private\",\"score\":0.95,\"box\":[508,20,20,10]}]}",f.width(),f.height());
        var roi=d.snapshot().rois().get(0);assertEquals(1,roi.rawCount());assertEquals(-1,roi.deliveredCount());assertFalse(roi.deliveryObserved());
        assertEquals(OcrRowsAssessment.Reason.BOX,roi.quality());
    }
    @Test public void originalTitleAndPointsAreClassifiedWithoutRetainingEitherTextOrHash()throws Exception{
        var f=RemainingCardPlanTest.activity(false);var d=new RemainingCardRoiDiagnostic(f.plan());String secret="SECRET_ACTIVITY_TITLE_CANARY_8142";
        complete(d,0,"{\"all\":["+row(secret,f.plan().rois().get(0))+"]}",f.width(),f.height());
        complete(d,1,"{\"all\":["+row("+10",f.plan().rois().get(1))+"]}",f.width(),f.height());
        var report=d.snapshot();assertEquals("NONE",report.firstProblemRole());assertEquals(2,report.completedQueries());
        assertEquals(RemainingCardRoiDiagnostic.TokenClass.ACTIVITY_TITLE,report.rois().get(0).samples().get(0).tokenClass());
        String hash=CardNativeMockReceipt.digest(secret.getBytes(StandardCharsets.UTF_8));
        for(String forbidden:List.of(secret,hash,"textHash","textLength","\"text\":")){
            assertFalse(report.json().contains(forbidden));assertFalse(report.summary().contains(forbidden));
        }
        assertTrue(report.json().contains("\"rawCountClass\":\"ONE\""));
    }
    @Test public void twoOriginalTitleLinesAreAllowedButNothingIsMerged(){
        var f=RemainingCardPlanTest.activity(false);var d=new RemainingCardRoiDiagnostic(f.plan());var b=f.plan().rois().get(0);int y=(int)b.centerY();
        String first="{\"text\":\"First line\",\"score\":0.95,\"box\":["+(b.left()+3)+","+(b.top()+2)+","+(b.width()-6)+","+(y-b.top()-3)+"]}";
        String second="{\"text\":\"Second line\",\"score\":0.95,\"box\":["+(b.left()+3)+","+(y+1)+","+(b.width()-6)+","+(b.bottom()-y-3)+"]}";
        complete(d,0,"{\"all\":["+first+","+second+"]}",f.width(),f.height());
        complete(d,1,"{\"all\":["+row("+5",f.plan().rois().get(1))+"]}",f.width(),f.height());
        assertEquals("NONE",d.snapshot().firstProblemRole());assertEquals(2,d.snapshot().rois().get(0).rawCount());
        assertEquals(2,d.snapshot().rois().get(0).deliveredCount());assertEquals(2,d.snapshot().rois().get(0).samples().size());
    }
    @Test public void weeklyHasNineSeparateExpectedRolesAndStillNoRawText(){
        var f=RemainingCardPlanTest.weekly();var d=new RemainingCardRoiDiagnostic(f.plan());
        for(int i=0;i<9;i++)complete(d,i,"{\"all\":["+row(i==0?"每日活动":i==1?"30":"Day "+(i-1),f.plan().rois().get(i))+"]}",f.width(),f.height());
        assertEquals("NONE",d.snapshot().firstProblemRole());assertEquals(9,d.snapshot().completedQueries());assertFalse(d.snapshot().json().contains("每日活动"));
        assertEquals(RemainingCardPlan.Role.WEEKLY_TITLE,d.snapshot().rois().get(0).role());
    }
}
