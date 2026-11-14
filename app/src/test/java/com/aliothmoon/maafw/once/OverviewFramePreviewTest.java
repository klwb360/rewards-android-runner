package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;

public class OverviewFramePreviewTest {
    private void rejected(Runnable body){try{body.run();fail("Expected unavailable preview");}catch(IllegalStateException expected){}}
    @Test public void samePngColorMustMatchEveryGrayByte()throws Exception{
        var p=new OverviewFramePreview(4,1);p.row(0,new int[]{0xffff0000,0xff00ff00,0xff0000ff,0xffffffff});p.complete();
        assertTrue(p.matchesGray(new byte[]{77,(byte)149,29,(byte)255}));
        assertFalse(p.matchesGray(new byte[]{77,(byte)150,29,(byte)255}));
        assertFalse(p.matchesGray(new byte[]{77}));
        assertEquals(Integer.valueOf(0xffff0000),p.withPixels((w,h,a)->{assertEquals(4,w);assertEquals(1,h);return a[0];}));
        p.close();assertTrue(p.knownReleased());assertFalse(p.matchesGray(new byte[4]));
    }
    @Test public void partialDuplicateAndOutOfOrderRowsCannotPublish()throws Exception{
        var p=new OverviewFramePreview(1,2);rejected(()->p.row(1,new int[]{0xff000000}));
        p.row(0,new int[]{0xff000000});rejected(()->p.row(0,new int[]{0xff000000}));
        rejected(p::complete);assertFalse(p.matchesGray(new byte[2]));p.close();assertTrue(p.knownReleased());
    }
    @Test public void borrowedArrayIsWipedAtFinalClose()throws Exception{
        var p=new OverviewFramePreview(1,1);p.row(0,new int[]{0xff000000});p.complete();
        final int[][] held={null};p.withPixels((w,h,a)->{held[0]=a;rejected(p::close);return null;});
        p.close();assertEquals(0,held[0][0]);
        try{p.withPixels((w,h,a)->null);fail();}catch(IllegalStateException expected){}
    }
    @Test public void transparentPixelsCannotBorrowOpaqueGrayAuthority(){
        var p=new OverviewFramePreview(1,1);p.row(0,new int[]{0x00000000});p.complete();
        assertFalse(p.matchesGray(new byte[]{0}));p.close();
    }
}
