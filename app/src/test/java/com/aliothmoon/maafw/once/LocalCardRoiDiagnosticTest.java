package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Invented native-shaped detail only. No image, Android process, model or device is used. */
public class LocalCardRoiDiagnosticTest {
    private static final int W=400,H=800;
    private List<Box> rois(){List<Box> values=new ArrayList<>();for(int i=0;i<9;i++)values.add(new Box(10+40*i,20,45+40*i,100));return values;}
    private LocalCardRoiDiagnostic diagnostic(){return new LocalCardRoiDiagnostic(rois(),W,H);}
    private String expected(int index){return index==0?"签入":index==1?"5":"Day "+(index-1);}
    private String quoted(String text){return '"'+text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+'"';}
    private String row(int index,String text,double score){Box b=rois().get(index);return row(text,score,b.left()+2,b.top()+2,10,20);}
    private String row(String text,double score,int x,int y,int w,int h){return "{\"text\":"+quoted(text)+",\"score\":"+score+",\"box\":["+x+","+y+","+w+","+h+"]}";}
    private OcrDetailDecoder.Result complete(LocalCardRoiDiagnostic d,int index,String json){
        d.attempted(index);d.posted(index);d.completed(index,3000);d.detailStarted(index);
        Object token=new Object();var binding=new FrameStructureEvidence.Binding(token,token,token,token);
        var result=OcrDetailDecoder.decode(json.getBytes(StandardCharsets.UTF_8),binding,W,H,rows->d.rows(index,rows));
        d.decoded(index,result);return result;
    }
    private void valid(LocalCardRoiDiagnostic d,int index){complete(d,index,"{\"all\":["+row(index,expected(index),.95)+"]}");}

    @Test public void notAttemptedIsUnknownInsteadOfZero(){
        var report=diagnostic().snapshot();assertEquals(9,report.rois().size());assertEquals(0,report.attemptedQueries());
        assertEquals(0,report.completedQueries());assertEquals("TITLE",report.firstProblemRole());
        for(var roi:report.rois()){
            assertEquals(LocalCardRoiDiagnostic.State.PLANNED,roi.state());assertEquals(-1,roi.rawCount());assertEquals(-1,roi.deliveredCount());
            assertFalse(roi.deliveryObserved());assertEquals(LocalCardRoiDiagnostic.CountClass.UNAVAILABLE,roi.countClass());
        }
    }
    @Test public void firstEmptyAndLastMultipleAreBothRetainedAfterNineReads(){
        var d=diagnostic();complete(d,0,"{\"all\":[]}");
        for(int i=1;i<8;i++)valid(d,i);
        complete(d,8,"{\"all\":["+row(8,"Day 7",.95)+","+row(8,"private-extra",.93)+"]}");
        var report=d.snapshot();assertEquals(9,report.attemptedQueries());assertEquals(9,report.postedQueries());
        assertEquals(9,report.completedQueries());assertEquals(9,report.decodedQueries());assertEquals("TITLE",report.firstProblemRole());
        var title=report.rois().get(0);assertEquals(0,title.rawCount());assertEquals(0,title.deliveredCount());
        assertTrue(title.deliveryObserved());assertEquals(LocalCardRoiDiagnostic.CountClass.ZERO,title.countClass());
        var last=report.rois().get(8);assertEquals(LocalCardRoiDiagnostic.Role.DAY7,last.role());
        assertEquals(2,last.rawCount());assertEquals(2,last.deliveredCount());assertEquals(LocalCardRoiDiagnostic.CountClass.MULTIPLE,last.countClass());
        assertFalse(report.json().contains("private-extra"));
    }
    @Test public void rejectedShapeIsNotMislabeledAsNativeEmpty(){
        var d=diagnostic();var result=complete(d,0,"{\"all\":["+row("private-canary",.95,399,20,2,20)+"]}");
        assertEquals(OcrDetailDecoder.Parse.OK,result.getParse());assertEquals(OcrRowsAssessment.Reason.BOX,result.getQuality().reason());
        var roi=d.snapshot().rois().get(0);assertEquals(1,roi.rawCount());assertEquals(-1,roi.deliveredCount());
        assertFalse(roi.deliveryObserved());assertEquals(LocalCardRoiDiagnostic.CountClass.UNAVAILABLE,roi.countClass());
        assertEquals(OcrRowsAssessment.Reason.BOX,roi.quality());assertFalse(d.snapshot().summary().contains("roi1Delivered=0"));
    }
    @Test public void malformedDetailAndOversizedRowArrayRemainExplicit(){
        var d=diagnostic();complete(d,0,"{\"all\":[");var rejected=d.snapshot().rois().get(0);
        assertEquals(LocalCardRoiDiagnostic.State.DETAIL_REJECTED,rejected.state());assertEquals(-1,rejected.rawCount());
        assertEquals(-1,rejected.deliveredCount());assertFalse(rejected.deliveryObserved());
        StringBuilder many=new StringBuilder("{\"all\":[");
        for(int i=0;i<129;i++){if(i>0)many.append(',');many.append(row(1,"5",.95));}many.append("]}");
        complete(d,1,many.toString());var limited=d.snapshot().rois().get(1);
        assertEquals(OcrDetailDecoder.Parse.ROW_LIMIT,limited.parse());assertEquals(129,limited.rawCount());assertTrue(limited.rawCountCapped());
        assertEquals(-1,limited.deliveredCount());assertEquals(0,limited.samples().size());
    }
    @Test public void invalidScoreMetadataDoesNotInventAConfidenceOrDeliveredRow(){
        var d=diagnostic();complete(d,0,"{\"all\":["+row(0,"private-score",1.01)+"]}");var roi=d.snapshot().rois().get(0);
        assertEquals(OcrRowsAssessment.Reason.SCORE,roi.quality());assertEquals(1,roi.rawCount());assertEquals(-1,roi.deliveredCount());
        assertEquals(-1,roi.minScoreBucket());assertEquals(-1,roi.maxScoreBucket());assertTrue(roi.samples().isEmpty());
    }
    @Test public void scoreBucketsPreserveTheThresholdBoundaryWithoutChangingRows(){
        var d=diagnostic();complete(d,0,"{\"all\":["+row(0,"签入",.849)+"]}");
        complete(d,1,"{\"all\":["+row(1,"5",.85)+"]}");
        var low=d.snapshot().rois().get(0);var high=d.snapshot().rois().get(1);
        assertEquals(1,low.lowScoreRows());assertEquals(16,low.minScoreBucket());
        assertEquals(LocalCardRoiDiagnostic.ScoreClass.BELOW_085,low.samples().get(0).scoreClass());
        assertEquals(0,high.lowScoreRows());assertEquals(17,high.minScoreBucket());
        assertEquals(LocalCardRoiDiagnostic.ScoreClass.AT_LEAST_085,high.samples().get(0).scoreClass());
    }
    @Test public void allRowsCountButOnlyFourSafeSamplesAreRetained(){
        var d=diagnostic();
        for(int index=0;index<9;index++){
            StringBuilder json=new StringBuilder("{\"all\":[");
            for(int i=0;i<128;i++){if(i>0)json.append(',');json.append(row(index,"PRIVATE_NATIVE_STRING",i%2==0?.95:.79));}json.append("]}");
            complete(d,index,json.toString());
        }
        var report=d.snapshot();
        for(var roi:report.rois()){
            assertEquals(128,roi.rawCount());assertEquals(128,roi.deliveredCount());assertEquals(4,roi.samples().size());
            assertTrue(roi.samplesTruncated());assertEquals(64,roi.lowScoreRows());assertEquals(128,roi.tokenMismatchRows());
        }
        String json=report.json();assertTrue(json.length()<16384);assertTrue(report.summary().length()<2500);
        assertTrue(json.chars().allMatch(c->c=='\n'||c>=32&&c<=126));assertFalse(json.contains("PRIVATE_NATIVE_STRING"));
    }
    @Test public void privateTextAndItsHashNeverEnterEitherRepresentation()throws Exception{
        String canary="SECRET_NATIVE_ALPHA_4839\"\nprivate";var d=diagnostic();complete(d,0,"{\"all\":["+row(0,canary,.95)+"]}");
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(canary.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash=new StringBuilder();for(byte b:digest)hash.append(String.format("%02x",b&255));
        String json=d.snapshot().json(),summary=d.snapshot().summary();
        for(String forbidden:List.of("SECRET_NATIVE_ALPHA","private",hash.toString(),"textHash","textLength","\"text\":")){
            assertFalse(forbidden,json.contains(forbidden));assertFalse(forbidden,summary.contains(forbidden));
        }
        assertEquals(LocalCardRoiDiagnostic.TokenClass.OTHER_TEXT,d.snapshot().rois().get(0).samples().get(0).tokenClass());
    }
    @Test public void snapshotsAreImmutableAndDoNotRetainInputRows(){
        var d=diagnostic();var before=d.snapshot();d.attempted(0);d.posted(0);d.completed(0,3000);d.detailStarted(0);
        var lines=new ArrayList<FrameStructureEvidence.Line>();lines.add(new FrameStructureEvidence.Line("签入",12,22,10,20,.95));
        d.rows(0,lines);lines.clear();
        assertEquals(1,d.snapshot().rois().get(0).samples().size());assertEquals(-1,before.rois().get(0).deliveredCount());
        assertThrows(UnsupportedOperationException.class,()->before.rois().clear());
        assertThrows(UnsupportedOperationException.class,()->d.snapshot().rois().get(0).samples().clear());
        assertThrows(UnsupportedOperationException.class,()->d.snapshot().rawCounts().clear());
    }
    @Test public void nativeTaskReceiptsCannotBeInventedOrReused(){
        var d=diagnostic();assertThrows(IllegalStateException.class,()->d.completed(0,3000));
        d.attempted(0);assertThrows(IllegalStateException.class,()->d.attempted(0));d.posted(0);
        assertThrows(IllegalStateException.class,()->d.completed(0,5000));assertEquals(0,d.snapshot().completedQueries());
        d.completed(0,4000);d.detailStarted(0);d.sizeRejected(0);var r=d.snapshot().rois().get(0);
        assertEquals(OcrDetailDecoder.Parse.SIZE,r.parse());assertEquals(-1,r.rawCount());assertEquals(-1,r.deliveredCount());
        assertFalse(r.decoded());assertEquals(1,d.snapshot().completedQueries());
    }
    @Test public void rowBoxesStayInNativeCropCoordinatesAndBoundaryRelationsAreSeparate(){
        var d=diagnostic();Box b=rois().get(0);
        complete(d,0,"{\"all\":["+row("签入",.95,b.left(),b.top(),10,20)+"]}");
        complete(d,1,"{\"all\":["+row("5",.95,2,2,10,20)+"]}");
        var touch=d.snapshot().rois().get(0);assertEquals(1,touch.touchingRows());assertEquals(b.left(),touch.samples().get(0).x());
        var outside=d.snapshot().rois().get(1);assertEquals(1,outside.outsideRows());assertEquals(2,outside.samples().get(0).x());
        assertEquals(LocalCardRoiDiagnostic.Relation.OUTSIDE,outside.samples().get(0).relation());
    }
    @Test public void validFixedRoisHaveNoDiagnosticProblemAndKeepTheirRoles(){
        var d=diagnostic();for(int i=0;i<9;i++)valid(d,i);var r=d.snapshot();
        assertEquals("NONE",r.firstProblemRole());assertEquals(List.of(1,1,1,1,1,1,1,1,1),r.rawCounts());
        assertEquals(r.rawCounts(),r.deliveredCounts());assertEquals(LocalCardRoiDiagnostic.Role.DAY1,r.rois().get(2).role());
        assertEquals(LocalCardRoiDiagnostic.Role.DAY7,r.rois().get(8).role());
    }
}
