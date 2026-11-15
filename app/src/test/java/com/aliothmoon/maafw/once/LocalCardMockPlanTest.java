package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic pixels and synthetic native-shaped OCR batches only; no Android or device operation. */
public class LocalCardMockPlanTest {
    private static final int W=DailyCheckInRegionTest.W,H=DailyCheckInRegionTest.H;
    private record Fixture(byte[] gray,int[] argb){}
    private Fixture fixture(){
        byte[] gray=DailyCheckInRegionTest.pixels();int[] argb=new int[gray.length];
        for(int i=0;i<gray.length;i++){
            int g=gray[i]&255;
            argb[i]=g==190?0xffffaa14:g==200?0xffffc33c:0xff000000|(g<<16)|(g<<8)|g;
        }
        // Seven visible gold coins are independent geometric evidence, including future days.
        disk(argb,272,115,21,0xffffd778);
        for(int day=4;day<7;day++)disk(argb,62+70*day,115,20,0xffffd778);
        fill(argb,264,109,268,122,0xff5a5a5a);fill(argb,274,109,280,122,0xff5a5a5a);
        for(int i=0;i<gray.length;i++){int p=argb[i];gray[i]=(byte)((77*((p>>>16)&255)+150*((p>>>8)&255)+29*(p&255)+128)>>>8);}
        return new Fixture(gray,argb);
    }
    private static void disk(int[] argb,int cx,int cy,int radius,int color){
        for(int y=cy-radius;y<=cy+radius;y++)for(int x=cx-radius;x<=cx+radius;x++)
            if((x-cx)*(x-cx)+(y-cy)*(y-cy)<=radius*radius)argb[y*W+x]=color;
    }
    private static void fill(int[] argb,int left,int top,int right,int bottom,int color){
        for(int y=top;y<bottom;y++)Arrays.fill(argb,y*W+left,y*W+right,color);
    }
    private LocalCardMockPlan.Plan plan(){var f=fixture();return LocalCardMockPlan.prepare(f.gray(),f.argb(),W,H,()->false);}
    private List<List<FrameStructureEvidence.Line>> batches(){
        var original=DailyCheckInRegionTest.rows();List<List<FrameStructureEvidence.Line>> result=new ArrayList<>();
        var title=original.get(0);var number=original.get(8);
        result.add(List.of(line(title.text(),title.box(),.97)));result.add(List.of(line(number.text(),number.box(),.96)));
        for(int day=0;day<7;day++){var row=original.get(day+1);result.add(List.of(line(row.text(),row.box(),.95)));}
        return result;
    }
    private FrameStructureEvidence.Line line(String text,Box box,double score){
        return new FrameStructureEvidence.Line(text,box.left(),box.top(),box.width(),box.height(),score);
    }
    @Test public void prepareProducesOnlyNinePanelContainedQueries(){
        var p=plan();assertTrue(p.reason(),p.admitted());assertEquals(9,p.rois().size());assertEquals(W,p.width());assertEquals(H,p.height());
        Box panel=new Box(10,17,536,193);
        for(var roi:p.rois()){assertTrue(panel.contains(roi));assertTrue(roi.width()>0&&roi.height()>0);assertNotEquals(new Box(0,0,W,H),roi);}
        assertEquals(new Box(256,99,289,132),p.rois().get(1));
        for(int i=2;i<8;i++){
            assertEquals(p.rois().get(i).right(),p.rois().get(i+1).left());
            assertFalse(p.rois().get(i).overlaps(p.rois().get(i+1)));
        }
    }
    @Test public void aSingleYellowCurrentRingCannotInventTheOtherSixCoins(){
        byte[] gray=DailyCheckInRegionTest.pixels();int[] argb=new int[gray.length];
        for(int i=0;i<gray.length;i++){
            int g=gray[i]&255,p=g==190?0xffffaa14:0xff000000|(g<<16)|(g<<8)|g;
            argb[i]=p;gray[i]=(byte)((77*((p>>>16)&255)+150*((p>>>8)&255)+29*(p&255)+128)>>>8);
        }
        var p=LocalCardMockPlan.prepare(gray,argb,W,H,()->false);
        assertFalse(p.admitted());assertEquals("COIN_COLUMN_COUNT",p.reason());
        assertTrue(p.rois().isEmpty());assertFalse(p.claimRecognition());
        assertEquals(0,p.finish(batches(),()->false).hypotheticalClicks());
    }
    @Test public void exactlyOneFakeReceiptUsesTheNativeNumberWithoutHardcodingTen(){
        for(String points:List.of("5","10","15","30")){
            var rows=batches();var nativeNumber=rows.get(1).get(0);rows.set(1,List.of(new FrameStructureEvidence.Line(points,nativeNumber.x(),nativeNumber.y(),nativeNumber.width(),nativeNumber.height(),nativeNumber.score())));
            var result=plan().finish(rows,()->false);assertEquals("PREVIEW_ONLY",result.status());assertEquals(1,result.hypotheticalClicks());
            assertEquals(0,result.realExecutions());assertEquals(4,result.day());assertEquals(Integer.parseInt(points),result.displayedPoints());
            assertEquals(272,result.x());assertEquals(115,result.y());assertEquals(9,result.ocrQueries());assertEquals(.95,result.confidence(),0);
        }
    }
    @Test public void repeatedFinishCannotMintAnotherFakeReceipt(){
        var p=plan();assertEquals(1,p.finish(batches(),()->false).hypotheticalClicks());
        var again=p.finish(batches(),()->false);assertEquals("ALREADY_CONSUMED",again.reason());assertEquals(0,again.hypotheticalClicks());assertEquals(-1,again.x());
    }
    @Test public void diagnosticOnlyModeCannotIssueAFakeClickOrPointEvenWhenAllRowsPass(){
        var f=fixture();var p=LocalCardMockPlan.prepare(f.gray(),f.argb(),W,H,true,()->false);
        var result=p.finish(batches(),()->false);assertEquals("DIAGNOSTIC_ONLY",result.status());
        assertEquals("ALL_ROIS_VALID",result.reason());assertEquals(0,result.hypotheticalClicks());assertEquals(0,result.realExecutions());
        assertEquals(-1,result.x());assertEquals(-1,result.y());assertEquals(-1,result.day());assertEquals(-1,result.displayedPoints());
        assertEquals(9,result.ocrQueries());assertEquals("ALREADY_CONSUMED",p.finish(batches(),()->false).reason());
    }
    @Test public void diagnosticOnlyModeStillRejectsZeroMultipleAndUnavailableBatches(){
        for(int mode=0;mode<3;mode++){
            var f=fixture();var p=LocalCardMockPlan.prepare(f.gray(),f.argb(),W,H,true,()->false);var rows=batches();
            rows.set(1,mode==0?List.of():mode==1?List.of(rows.get(1).get(0),rows.get(1).get(0)):null);
            var result=p.finish(rows,()->false);assertEquals("HOLD",result.status());assertEquals(mode==2?"ROI_ROWS_UNAVAILABLE":"ROI_ROW_COUNT",result.reason());
            assertEquals(0,result.hypotheticalClicks());assertEquals(-1,result.x());
        }
    }
    @Test public void unavailableValidatedRowsAndTrueNativeEmptyHaveDifferentReasons(){
        var unavailable=batches();unavailable.set(0,null);
        var absent=plan().finish(unavailable,()->false);assertEquals("ROI_ROWS_UNAVAILABLE",absent.reason());
        assertEquals(0,absent.hypotheticalClicks());assertEquals(-1,absent.x());
        var empty=batches();empty.set(0,List.of());
        var zero=plan().finish(empty,()->false);assertEquals("ROI_ROW_COUNT",zero.reason());
        assertEquals(0,zero.hypotheticalClicks());assertEquals(-1,zero.x());
    }
    @Test public void failedBatchConsumesThePlanAndDoesNotRetry(){
        var p=plan();var rows=batches();rows.remove(8);assertEquals("MISSING_BATCHES",p.finish(rows,()->false).reason());
        assertEquals("ALREADY_CONSUMED",p.finish(batches(),()->false).reason());
    }
    @Test public void batchEvidenceCannotMoveAcrossRoiOrBeOffsetAgain(){
        var rows=batches();var nativeNumber=rows.get(1).get(0);
        rows.set(1,List.of(new FrameStructureEvidence.Line("10",nativeNumber.x()+256,nativeNumber.y(),nativeNumber.width(),nativeNumber.height(),.99)));
        assertEquals("ROW_OUTSIDE_OWN_ROI",plan().finish(rows,()->false).reason());
        rows=batches();rows.set(1,List.of(line("10",new Box(2,2,18,15),.99)));
        assertEquals("ROW_OUTSIDE_OWN_ROI",plan().finish(rows,()->false).reason());
    }
    @Test public void lowNonfiniteAndMissingConfidenceAreNotPromoted(){
        for(double score:new double[]{.849,Double.NaN,Double.POSITIVE_INFINITY,-1,1.01}){
            var rows=batches();var row=rows.get(1).get(0);rows.set(1,List.of(new FrameStructureEvidence.Line(row.text(),row.x(),row.y(),row.width(),row.height(),score)));
            var outcome=plan().finish(rows,()->false);assertEquals("ROI_ROW_SHAPE_OR_CONFIDENCE",outcome.reason());assertEquals(0,outcome.hypotheticalClicks());
        }
    }
    @Test public void wholeSearchFallbackDoesNotBecomeLocalizedDigitEvidence(){
        var p=plan();var rows=batches();rows.set(1,List.of(line("10",p.rois().get(1),.99)));
        assertEquals("ROW_TOUCHES_ROI",p.finish(rows,()->false).reason());
    }
    @Test public void extraRowsOrMergedNumbersDoNotGetDiscardedOrSplit(){
        var rows=batches();var row=rows.get(1).get(0);rows.set(1,List.of(row,row));
        assertEquals("ROI_ROW_COUNT",plan().finish(rows,()->false).reason());
        for(String text:List.of("5 10","510","10积分","+10","0","１０")){
            rows=batches();rows.set(1,List.of(new FrameStructureEvidence.Line(text,row.x(),row.y(),row.width(),row.height(),.99)));
            var result=plan().finish(rows,()->false);
            // 510 is one valid positive displayed number; neither it nor other values are rewritten.
            if(text.equals("510"))assertEquals(510,result.displayedPoints());else assertEquals("NUMBER_NOT_PLAIN",result.reason());
        }
    }
    @Test public void narrowerDayRoisStillRejectEveryAdditionalNativeRowWithoutFiltering(){
        for(int day=0;day<7;day++){
            var p=plan();var rows=batches();var nativeRow=rows.get(day+2).get(0);
            rows.set(day+2,List.of(nativeRow,new FrameStructureEvidence.Line("",nativeRow.x(),nativeRow.y(),1,1,0)));
            var result=p.finish(rows,()->false);
            assertEquals("ROI_ROW_COUNT",result.reason());assertEquals(0,result.hypotheticalClicks());
        }
    }
    @Test public void adjacentDayBoxCannotBeAcceptedInTheWrongDisjointRoi(){
        var rows=batches();var neighbor=rows.get(3).get(0);
        rows.set(2,List.of(new FrameStructureEvidence.Line("Day 1",neighbor.x(),neighbor.y(),neighbor.width(),neighbor.height(),.99)));
        assertEquals("ROW_OUTSIDE_OWN_ROI",plan().finish(rows,()->false).reason());
    }
    @Test public void exactTitleAndEachOrderedDayMustBeIndependentlyRead(){
        var rows=batches();var title=rows.get(0).get(0);rows.set(0,List.of(new FrameStructureEvidence.Line("每日活动",title.x(),title.y(),title.width(),title.height(),.99)));
        assertEquals("TITLE_NOT_EXACT",plan().finish(rows,()->false).reason());
        rows=batches();var first=rows.get(2).get(0);rows.set(2,List.of(new FrameStructureEvidence.Line("Day 2",first.x(),first.y(),first.width(),first.height(),.99)));
        assertEquals("WRONG_DAY",plan().finish(rows,()->false).reason());
    }
    @Test public void colorsMustDescribeTheSameGrayBytesAndObservedRingMustBeYellow(){
        var f=fixture();f.argb()[0]=0xff000000;assertEquals("COLOR_GRAY_MISMATCH",LocalCardMockPlan.prepare(f.gray(),f.argb(),W,H,()->false).reason());
        byte[] gray=DailyCheckInRegionTest.pixels();int[] argb=new int[gray.length];
        for(int i=0;i<gray.length;i++){int g=gray[i]&255;argb[i]=0xff000000|(g<<16)|(g<<8)|g;}
        assertEquals("RING_NOT_YELLOW",LocalCardMockPlan.prepare(gray,argb,W,H,()->false).reason());
    }
    @Test public void planBindsTheExactHistoricalGrayImageAndCanReserveRecognitionOnce(){
        var f=fixture();var p=LocalCardMockPlan.prepare(f.gray(),f.argb(),W,H,()->false);
        assertTrue(p.matchesGray8(f.gray(),W,H,()->false));f.gray()[0]^=1;
        assertFalse(p.matchesGray8(f.gray(),W,H,()->false));assertFalse(p.matchesGray8(f.gray(),W-1,H,()->false));
        assertTrue(p.claimRecognition());assertFalse(p.claimRecognition());
    }
    @Test public void visualFallbackCannotInventADayOrFakeClick(){
        var rows=batches();var first=rows.get(2).get(0);rows.set(2,List.of(new FrameStructureEvidence.Line(first.text(),15,first.y(),40,first.height(),.95)));
        var result=plan().finish(rows,()->false);assertEquals("VISUAL_PREVIEW_ONLY",result.status());assertEquals(0,result.hypotheticalClicks());
        assertEquals(0,result.realExecutions());assertEquals(-1,result.day());assertEquals(272,result.x());
    }
    @Test public void cancelOrMalformedImageCannotProduceAFakeReceipt(){
        var f=fixture();assertEquals("CANCELED",LocalCardMockPlan.prepare(f.gray(),f.argb(),W,H,()->true).reason());
        assertEquals("INVALID_LOCAL_CROP",LocalCardMockPlan.prepare(new byte[1],new int[1],W,H,()->false).reason());
        var p=plan();assertEquals("CANCELED",p.finish(batches(),()->true).reason());assertEquals("ALREADY_CONSUMED",p.finish(batches(),()->false).reason());
    }
    @Test public void capturedPlanUsesNativeProvenanceWithoutChangingTheNineRegionsOrPreview(){
        var f=fixture();Object owner=new Object();byte[] png=new byte[128];Arrays.fill(png,(byte)31);
        var receipt=CardNativeMockReceipt.seal(owner,CardNativeMockReceipt.digest(png),W,H);
        assertTrue(receipt.claim(owner,png,W,H));
        var captured=LocalCardMockPlan.prepareCaptured(f.gray(),f.argb(),W,H,receipt,owner,()->false);
        var historical=LocalCardMockPlan.prepare(f.gray(),f.argb(),W,H,()->false);
        assertTrue(captured.reason(),captured.admitted());
        assertEquals(com.aliothmoon.maafw.rewards.RewardRegionGate.Source.REAL_DISPLAY_NATIVE,captured.source());
        assertEquals(com.aliothmoon.maafw.rewards.RewardRegionGate.Source.USER_STATIC_MATERIAL,historical.source());
        assertEquals(historical.rois(),captured.rois());assertEquals(historical.finish(batches(),()->false),captured.finish(batches(),()->false));
    }
    @Test public void capturedPlanCannotPromoteUnclaimedForeignOrCanceledReceipts(){
        var f=fixture();Object owner=new Object();byte[] png=new byte[128];
        var receipt=CardNativeMockReceipt.seal(owner,CardNativeMockReceipt.digest(png),W,H);
        assertEquals("CAPTURE_RECEIPT_UNAVAILABLE",LocalCardMockPlan.prepareCaptured(f.gray(),f.argb(),W,H,receipt,owner,()->false).reason());
        assertTrue(receipt.claim(owner,png,W,H));
        assertEquals("CAPTURE_RECEIPT_UNAVAILABLE",LocalCardMockPlan.prepareCaptured(f.gray(),f.argb(),W,H,receipt,new Object(),()->false).reason());
        receipt.cancel();
        assertEquals("CAPTURE_RECEIPT_UNAVAILABLE",LocalCardMockPlan.prepareCaptured(f.gray(),f.argb(),W,H,receipt,owner,()->false).reason());
        assertEquals("CANCELED",LocalCardMockPlan.prepareCaptured(f.gray(),f.argb(),W,H,receipt,owner,()->true).reason());
    }
    @Test public void capturedReceiptCannotMintAnotherPlanAndCancellationRevokesTheFirstPlan(){
        var f=fixture();Object owner=new Object();byte[] png=new byte[128];
        var receipt=CardNativeMockReceipt.seal(owner,CardNativeMockReceipt.digest(png),W,H);assertTrue(receipt.claim(owner,png,W,H));
        var first=LocalCardMockPlan.prepareCaptured(f.gray(),f.argb(),W,H,receipt,owner,()->false);assertTrue(first.admitted());
        assertEquals("CAPTURE_RECEIPT_UNAVAILABLE",LocalCardMockPlan.prepareCaptured(f.gray(),f.argb(),W,H,receipt,owner,()->false).reason());
        receipt.cancel();assertFalse(first.admitted());assertFalse(first.claimRecognition());assertFalse(first.matchesGray8(f.gray(),W,H,()->false));
        assertEquals("CAPTURE_RECEIPT_UNAVAILABLE",first.finish(batches(),()->false).reason());
    }
    @Test public void duplicatePayloadClaimRevokesAnAlreadyPreparedCapturePlan(){
        var f=fixture();Object owner=new Object();byte[] png=new byte[128];
        var receipt=CardNativeMockReceipt.seal(owner,CardNativeMockReceipt.digest(png),W,H);assertTrue(receipt.claim(owner,png,W,H));
        var first=LocalCardMockPlan.prepareCaptured(f.gray(),f.argb(),W,H,receipt,owner,()->false);assertTrue(first.admitted());
        assertFalse(receipt.claim(owner,png,W,H));assertFalse(first.admitted());assertFalse(first.claimRecognition());
        assertEquals(0,first.finish(batches(),()->false).hypotheticalClicks());
    }
    @Test public void captureRevocationWhileReadingTheLastRegionCannotIssueAPreview(){
        var f=fixture();Object owner=new Object();byte[] png=new byte[128];
        var receipt=CardNativeMockReceipt.seal(owner,CardNativeMockReceipt.digest(png),W,H);assertTrue(receipt.claim(owner,png,W,H));
        var p=LocalCardMockPlan.prepareCaptured(f.gray(),f.argb(),W,H,receipt,owner,()->false);var original=batches();
        List<List<FrameStructureEvidence.Line>> revokeOnLastRegion=new java.util.AbstractList<>(){
            public int size(){return original.size();}
            public List<FrameStructureEvidence.Line> get(int index){if(index==8)receipt.cancel();return original.get(index);}
        };
        var result=p.finish(revokeOnLastRegion,()->false);assertEquals("CANCELED",result.reason());
        assertEquals(0,result.hypotheticalClicks());assertEquals(0,result.realExecutions());assertEquals(-1,result.x());
    }
    @Test public void capturedPlanStillRejectsZeroMultipleLowScoreAndBoundaryTouchingRows(){
        for(int mode=0;mode<4;mode++){
            var f=fixture();Object owner=new Object();byte[] png=new byte[128];
            var receipt=CardNativeMockReceipt.seal(owner,CardNativeMockReceipt.digest(png),W,H);assertTrue(receipt.claim(owner,png,W,H));
            var p=LocalCardMockPlan.prepareCaptured(f.gray(),f.argb(),W,H,receipt,owner,()->false);var rows=batches();var nativeNumber=rows.get(1).get(0);
            rows.set(1,mode==0?List.of():mode==1?List.of(nativeNumber,nativeNumber):mode==2?
                    List.of(new FrameStructureEvidence.Line(nativeNumber.text(),nativeNumber.x(),nativeNumber.y(),nativeNumber.width(),nativeNumber.height(),.849)):
                    List.of(new FrameStructureEvidence.Line(nativeNumber.text(),p.rois().get(1).left(),nativeNumber.y(),nativeNumber.width(),nativeNumber.height(),.99)));
            var result=p.finish(rows,()->false);assertEquals(0,result.hypotheticalClicks());assertEquals(0,result.realExecutions());assertEquals(-1,result.x());
            assertEquals(mode<2?"ROI_ROW_COUNT":mode==2?"ROI_ROW_SHAPE_OR_CONFIDENCE":"ROW_TOUCHES_ROI",result.reason());
        }
    }
    @Test public void originalRowsAndRasterRemainUnchanged(){
        var f=fixture();var original=f.gray().clone();var colors=f.argb().clone();var p=LocalCardMockPlan.prepare(f.gray(),f.argb(),W,H,()->false);
        assertArrayEquals(original,f.gray());assertArrayEquals(colors,f.argb());
        var rows=batches();var snapshot=new ArrayList<>(rows);p.finish(rows,()->false);assertEquals(snapshot,rows);
        for(int i=0;i<rows.size();i++)assertSame(snapshot.get(i).get(0),rows.get(i).get(0));Arrays.fill(original,(byte)0);Arrays.fill(colors,0);
    }
}
