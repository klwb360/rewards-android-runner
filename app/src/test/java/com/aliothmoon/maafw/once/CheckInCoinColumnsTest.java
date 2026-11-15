package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic color geometry only; no OCR model, device operation or screenshot. */
public class CheckInCoinColumnsTest {
    private static final int W=552,H=201,GOLD=0xffffd778,PALE=0xfffafafa;
    private static final Box PANEL=new Box(10,17,536,193);
    private record Fixture(int[] argb,Box ring){}
    private static void fill(int[] argb,int left,int top,int right,int bottom,int color){
        for(int y=top;y<bottom;y++)Arrays.fill(argb,y*W+left,y*W+right,color);
    }
    private static void disk(int[] argb,int cx,int cy,int radius,int color){
        for(int y=cy-radius;y<=cy+radius;y++)for(int x=cx-radius;x<=cx+radius;x++)
            if((x-cx)*(x-cx)+(y-cy)*(y-cy)<=radius*radius)argb[y*W+x]=color;
    }
    private static Fixture fixture(int current){
        int[] pixels=new int[W*H];Arrays.fill(pixels,PALE);
        for(int i=0;i<7;i++)disk(pixels,62+70*i,115,20,GOLD);
        int cx=62+70*(current-1);
        disk(pixels,cx,115,27,0xffffaa14);disk(pixels,cx,115,25,PALE);disk(pixels,cx,115,21,GOLD);
        return new Fixture(pixels,new Box(cx-27,88,cx+28,143));
    }
    private static CheckInCoinColumns.Result locate(Fixture f){return CheckInCoinColumns.locate(f.argb(),W,H,PANEL,f.ring(),()->false);}
    private static void noGeometry(CheckInCoinColumns.Result result){
        assertFalse(result.ready());assertTrue(result.columns().isEmpty());assertTrue(result.boundaries().isEmpty());
        assertTrue(result.dayRois().isEmpty());assertEquals(-1,result.currentDay());
    }

    @Test public void sevenObservedCoinsPartitionTheOriginalDayBandWithoutOverlap(){
        var result=locate(fixture(4));assertTrue(result.status(),result.ready());
        assertEquals(7,result.columns().size());assertEquals(8,result.boundaries().size());assertEquals(7,result.dayRois().size());
        assertEquals(4,result.currentDay());assertEquals(Integer.valueOf(PANEL.left()),result.boundaries().get(0));
        assertEquals(Integer.valueOf(PANEL.right()),result.boundaries().get(7));
        for(int i=0;i<7;i++){
            Box roi=result.dayRois().get(i);assertTrue(PANEL.contains(roi));assertEquals(139,roi.top());assertEquals(193,roi.bottom());
            assertTrue(roi.left()<62+70*i&&roi.right()>62+70*i);
            if(i>0){
                Box previous=result.columns().get(i-1),next=result.columns().get(i);
                assertEquals(previous.right()+(next.left()-previous.right()+1)/2,roi.left());
                assertEquals(result.dayRois().get(i-1).right(),roi.left());
                assertFalse(result.dayRois().get(i-1).overlaps(roi));
            }
        }
    }
    @Test public void uniqueCurrentRingCanMapToEachOfTheSevenObservedColumns(){
        for(int day=1;day<=7;day++){
            var result=locate(fixture(day));assertTrue(result.status(),result.ready());assertEquals(day,result.currentDay());
        }
    }
    @Test public void aConnectedWiderFinalPuzzleCoinKeepsItsOwnValleyAndSlot(){
        var f=fixture(4);fill(f.argb(),451,108,475,122,GOLD);
        var result=locate(f);assertTrue(result.status(),result.ready());assertEquals(7,result.columns().size());
        assertTrue(result.columns().get(6).width()>result.columns().get(5).width());
        assertTrue(result.dayRois().get(6).left()>result.columns().get(5).right());
        assertEquals(4,result.currentDay());
    }
    @Test public void withinCoinPaleGapIsClosedButActualCoinValleysAreNot(){
        var f=fixture(4);var result=locate(f);assertTrue(result.status(),result.ready());
        Box active=result.columns().get(3);
        assertTrue(active.width()>.85*f.ring().width());
        assertTrue(active.left()<=f.ring().left()+2);assertTrue(active.right()>=f.ring().right()-2);
        assertEquals(7,result.columns().size());
    }
    @Test public void zeroSixEightAndExtraIndependentColumnsCannotSelectAConvenientSeven(){
        int[] empty=new int[W*H];Arrays.fill(empty,PALE);
        assertEquals("COIN_COLUMN_COUNT",CheckInCoinColumns.locate(empty,W,H,PANEL,fixture(4).ring(),()->false).status());
        var missing=fixture(4);fill(missing.argb(),38,88,87,144,PALE);
        var absent=locate(missing);noGeometry(absent);assertEquals("COIN_COLUMN_COUNT",absent.status());
        var extra=fixture(4);fill(extra.argb(),90,108,105,122,GOLD);
        var eight=locate(extra);noGeometry(eight);assertEquals("COIN_COLUMN_COUNT",eight.status());
    }
    @Test public void crossCoinYellowBridgeCannotBecomeAValidSevenColumnObservation(){
        var f=fixture(4);fill(f.argb(),80,110,114,120,GOLD);noGeometry(locate(f));
    }
    @Test public void irregularSpacingAndNarrowValleysRejectTheWholeGeometry(){
        var uneven=fixture(4);fill(uneven.argb(),110,88,155,144,PALE);disk(uneven.argb(),150,115,20,GOLD);
        var spacing=locate(uneven);noGeometry(spacing);assertEquals("COIN_PITCH",spacing.status());
        var close=fixture(4);fill(close.argb(),110,88,155,144,PALE);disk(close.argb(),109,115,20,GOLD);
        var valley=locate(close);noGeometry(valley);assertEquals("NO_COIN_VALLEY",valley.status());
    }
    @Test public void thinOrSparseYellowNoiseCannotStandInForAFullCoinColumn(){
        var thin=fixture(4);fill(thin.argb(),38,88,87,144,PALE);fill(thin.argb(),61,104,63,126,GOLD);
        var narrow=locate(thin);noGeometry(narrow);assertEquals("COIN_COLUMN_SHAPE",narrow.status());
        var sparse=fixture(4);fill(sparse.argb(),38,88,87,144,PALE);fill(sparse.argb(),43,114,82,116,GOLD);
        var light=locate(sparse);noGeometry(light);assertEquals("COIN_COLUMN_SPARSE",light.status());
    }
    @Test public void ringBetweenCoinsOrTooNarrowlyIntersectingOneCoinIsNotMapped(){
        var f=fixture(4);
        var between=CheckInCoinColumns.locate(f.argb(),W,H,PANEL,new Box(70,88,125,143),()->false);
        noGeometry(between);assertEquals("RING_COLUMN_MISMATCH",between.status());
        var offCenter=CheckInCoinColumns.locate(f.argb(),W,H,PANEL,new Box(238,88,293,143),()->false);
        noGeometry(offCenter);assertEquals("RING_COLUMN_MISMATCH",offCenter.status());
    }
    @Test public void translatedAndScaledInputsUseObservedGeometryRatherThanFixedPixelLocations(){
        var original=fixture(4);var before=locate(original);
        for(int scale:new int[]{1,2,3}){
            int tx=13,ty=7,width=W*scale+2*tx,height=H*scale+2*ty;
            int[] pixels=new int[width*height];Arrays.fill(pixels,PALE);
            for(int y=0;y<H;y++)for(int x=0;x<W;x++)for(int dy=0;dy<scale;dy++)for(int dx=0;dx<scale;dx++)
                pixels[(ty+y*scale+dy)*width+tx+x*scale+dx]=original.argb()[y*W+x];
            Box panel=transform(PANEL,scale,tx,ty),ring=transform(original.ring(),scale,tx,ty);
            var result=CheckInCoinColumns.locate(pixels,width,height,panel,ring,()->false);
            assertTrue(result.status(),result.ready());assertEquals(4,result.currentDay());
            for(int i=0;i<8;i++)assertEquals(tx+scale*before.boundaries().get(i),result.boundaries().get(i),1);
        }
    }
    private static Box transform(Box b,int s,int x,int y){return new Box(x+s*b.left(),y+s*b.top(),x+s*b.right(),y+s*b.bottom());}
    @Test public void cancellationAndMalformedInputsDoNotReturnPartialGeometryOrChangePixels(){
        var f=fixture(4);int[] original=f.argb().clone();
        noGeometry(CheckInCoinColumns.locate(f.argb(),W,H,PANEL,f.ring(),()->true));
        AtomicInteger polls=new AtomicInteger();
        var canceled=CheckInCoinColumns.locate(f.argb(),W,H,PANEL,f.ring(),()->polls.incrementAndGet()>3);
        assertEquals("CANCELED",canceled.status());noGeometry(canceled);assertArrayEquals(original,f.argb());
        noGeometry(CheckInCoinColumns.locate(new int[1],W,H,PANEL,f.ring(),()->false));
        noGeometry(CheckInCoinColumns.locate(f.argb(),W,H,new Box(-1,17,536,193),f.ring(),()->false));
        noGeometry(CheckInCoinColumns.locate(f.argb(),W,H,PANEL,null,()->false));
        var result=locate(f);assertArrayEquals(original,f.argb());
        try{result.dayRois().clear();fail("ROIs must be immutable");}catch(UnsupportedOperationException expected){}
        try{result.columns().clear();fail("Columns must be immutable");}catch(UnsupportedOperationException expected){}
        try{result.boundaries().clear();fail("Boundaries must be immutable");}catch(UnsupportedOperationException expected){}
    }
}
