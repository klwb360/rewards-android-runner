package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Generated color rasters only; no screenshot or OCR model is used. */
public class WeeklyCoinColumnsV3Test {
    private static final int W=552,H=201,GOLD=0xffffd778,PALE=0xfffafafa;
    private static final Box PANEL=new Box(10,17,536,193);
    private record Fixture(int[] argb,Box ring){}
    private static void fill(int[] p,int w,int l,int t,int r,int b,int color){
        for(int y=t;y<b;y++)Arrays.fill(p,y*w+l,y*w+r,color);
    }
    private static void disk(int[] p,int w,int cx,int cy,int radius,int color){
        for(int y=cy-radius;y<=cy+radius;y++)for(int x=cx-radius;x<=cx+radius;x++)
            if((x-cx)*(x-cx)+(y-cy)*(y-cy)<=radius*radius)p[y*w+x]=color;
    }
    private static Fixture fixture(int lineHeight){
        int[] p=new int[W*H];Arrays.fill(p,PALE);
        for(int i=0;i<7;i++)disk(p,W,62+70*i,115,20,GOLD);
        disk(p,W,132,115,27,0xffffaa14);disk(p,W,132,115,25,PALE);disk(p,W,132,115,21,GOLD);
        if(lineHeight>0)fill(p,W,80,115-lineHeight/2,107,115-lineHeight/2+lineHeight,GOLD);
        return new Fixture(p,new Box(105,88,160,143));
    }
    private static CheckInCoinColumns.Result fixed(Fixture f){
        return CheckInCoinColumns.locateSeparatedCoins(f.argb(),W,H,PANEL,f.ring(),()->false);
    }
    private static void noPartial(CheckInCoinColumns.Result r){
        assertFalse(r.ready());assertTrue(r.columns().isEmpty());assertTrue(r.dayRois().isEmpty());
        assertTrue(r.boundaries().isEmpty());assertEquals(-1,r.currentDay());
    }
    @Test public void thinConnectionToDayTwoDoesNotSupplyCoinWidth(){
        for(int thickness:new int[]{0,1,2,3,4,5}){
            var f=fixture(thickness);var r=fixed(f);assertTrue("thickness="+thickness+" "+r.status(),r.ready());
            assertEquals(7,r.columns().size());assertEquals(2,r.currentDay());assertEquals(8,r.boundaries().size());
            for(int i=1;i<7;i++)assertTrue(r.columns().get(i).left()>r.columns().get(i-1).right());
        }
    }
    @Test public void legacyProjectionDoesNotSilentlyAdoptTheV3Rule(){
        var f=fixture(3);var legacy=CheckInCoinColumns.locate(f.argb(),W,H,PANEL,f.ring(),()->false);
        assertEquals("COIN_COLUMN_COUNT",legacy.status());noPartial(legacy);assertTrue(fixed(f).ready());
    }
    @Test public void aThickBridgeStillCannotBeSplitIntoInventedCoins(){
        for(int thickness:new int[]{6,8,12})noPartial(fixed(fixture(thickness)));
    }
    @Test public void aMissingCoinCannotBeReplacedByItsRemainingThinConnection(){
        var f=fixture(3);fill(f.argb(),W,38,88,85,144,PALE);
        noPartial(fixed(f));assertEquals("COIN_COLUMN_COUNT",fixed(f).status());
    }
    @Test public void extraIndependentYellowColumnIsNotDroppedToSelectSeven(){
        var f=fixture(0);fill(f.argb(),W,90,102,98,128,GOLD);
        noPartial(fixed(f));assertEquals("COIN_COLUMN_COUNT",fixed(f).status());
    }
    @Test public void scaleAndTranslationPreserveTheObservedDayTwoColumns(){
        var f=fixture(3);var before=fixed(f);assertTrue(before.ready());
        for(int scale:new int[]{1,2,3}){
            int tx=13,ty=7,w=W*scale+2*tx,h=H*scale+2*ty;int[] p=new int[w*h];Arrays.fill(p,PALE);
            for(int y=0;y<H;y++)for(int x=0;x<W;x++)for(int dy=0;dy<scale;dy++)for(int dx=0;dx<scale;dx++)
                p[(ty+y*scale+dy)*w+tx+x*scale+dx]=f.argb()[y*W+x];
            var r=CheckInCoinColumns.locateSeparatedCoins(p,w,h,transform(PANEL,scale,tx,ty),
                    transform(f.ring(),scale,tx,ty),()->false);
            assertTrue(r.status(),r.ready());assertEquals(2,r.currentDay());
            for(int i=0;i<8;i++)assertEquals(tx+scale*before.boundaries().get(i),r.boundaries().get(i),2);
        }
    }
    private static Box transform(Box b,int scale,int x,int y){
        return new Box(x+scale*b.left(),y+scale*b.top(),x+scale*b.right(),y+scale*b.bottom());
    }
    @Test public void eightPixelBridgeInAFiftyFourPixelBandRemainsBelowCoinSupport(){
        int w=1336,h=672;Box panel=new Box(12,99,1324,535),ring=new Box(249,273,386,410);
        int[] p=new int[w*h];Arrays.fill(p,PALE);
        int[] centers={142,317,492,667,842,1018,1207},radii={64,68,50,50,50,50,62};
        for(int i=0;i<7;i++)disk(p,w,centers[i],341,radii[i],GOLD);
        disk(p,w,317,341,63,PALE);disk(p,w,317,341,60,GOLD);fill(p,w,206,337,251,345,GOLD);
        assertEquals("COIN_COLUMN_COUNT",CheckInCoinColumns.locate(p,w,h,panel,ring,()->false).status());
        var r=CheckInCoinColumns.locateSeparatedCoins(p,w,h,panel,ring,()->false);
        assertTrue(r.status(),r.ready());assertEquals(7,r.columns().size());assertEquals(2,r.currentDay());
        assertTrue(r.columns().get(0).right()<r.columns().get(1).left());
    }
    @Test public void v3DoesNotReturnGeometryAfterCancellationOrMutateCallerPixels(){
        var f=fixture(3);int[] before=f.argb().clone();fixed(f);assertArrayEquals(before,f.argb());
        noPartial(CheckInCoinColumns.locateSeparatedCoins(f.argb(),W,H,PANEL,f.ring(),()->true));
        AtomicInteger polls=new AtomicInteger();
        var r=CheckInCoinColumns.locateSeparatedCoins(f.argb(),W,H,PANEL,f.ring(),()->polls.incrementAndGet()>3);
        assertEquals("CANCELED",r.status());noPartial(r);assertArrayEquals(before,f.argb());
    }
}
