package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;

public class OverviewScanIntentTest {
    private OverviewScanIntent intent(){return VisibleCaptureAuthority.fixtureOverviewIntent(new RealCaptureClaim.ProcessEpoch(),new Object(),100);}
    private void denied(Runnable body){try{body.run();fail("Expected rejection");}catch(SecurityException expected){}}
    private ReviewShizukuSwipeActor.Result exited(){return new ReviewShizukuSwipeActor.Result(ReviewShizukuSwipeActor.Kind.COMMAND_EXITED,0,true,true,ReviewShizukuSwipeActor.Reason.COMMAND_EXIT_ZERO);}
    @Test public void frameAndUiProofAreConsumedOnce(){
        var s=intent();assertFalse(s.live());var p=s.claimFrame(new Object(),101);
        assertTrue(p.claimUi(102));assertFalse(p.claimUi(103));
        assertTrue(p.claimProof(102));assertFalse(p.claimProof(103));
        denied(()->s.claimFrame(new Object(),104));
        denied(()->s.claimScroll(p,104));
    }
    @Test public void onlyCleanQualifiedFrameCanPrecedeScroll(){
        for(int bad=0;bad<2;bad++){
            var s=intent();var p=s.claimFrame(new Object(),101);final int mode=bad;
            denied(()->s.frameFinished(p,mode!=0,mode!=1,102));assertFalse(s.current(103));
        }
    }
    @Test public void twelveFramesAndElevenScrollsAreHardLimits(){
        var s=intent();
        for(int n=1;n<=12;n++){
            var f=s.claimFrame(new Object(),100+n);assertEquals(n,f.index);
            s.frameFinished(f,true,true,120+n);
            if(n==1){assertEquals(ReviewShizukuSwipeActor.Direction.DOWN,s.direction());s.beginDownwardScan(f,121+n);}
            assertEquals(ReviewShizukuSwipeActor.Direction.UP,s.direction());
            if(n==12){assertFalse(s.budgetRemaining());denied(()->s.claimScroll(f,150));break;}
            var scroll=s.claimScroll(f,140+n);final int repeatedAt=141+n;denied(()->s.claimScroll(f,repeatedAt));
            assertTrue(scroll.consume(142+n));assertFalse(scroll.consume(143+n));
            s.scrollFinished(scroll,exited(),144+n);
        }
        assertEquals(12,s.frames());assertEquals(11,s.swipes());
    }
    @Test public void uncertainScrollStopsWithoutNewFrame(){
        var s=intent();var f=s.claimFrame(new Object(),101);s.frameFinished(f,true,true,102);
        var scroll=s.claimScroll(f,103);assertTrue(scroll.consume(104));
        var unknown=new ReviewShizukuSwipeActor.Result(ReviewShizukuSwipeActor.Kind.DISPATCH_UNKNOWN,Integer.MIN_VALUE,true,true,ReviewShizukuSwipeActor.Reason.WAIT_TIMEOUT);
        denied(()->s.scrollFinished(scroll,unknown,105));assertFalse(s.current(106));
        denied(()->s.claimFrame(new Object(),106));
    }
    @Test public void expiryAndCancelAreNotResettable(){
        var s=intent();assertEquals(180100,s.deadline());assertFalse(s.current(99));assertTrue(s.current(180099));assertFalse(s.current(180100));
        denied(()->s.claimFrame(new Object(),180100));
        var c=intent();c.cancel();denied(()->c.claimFrame(new Object(),101));
    }
    @Test public void expiredFrameCannotCompleteWhileSessionStillHasBudget(){
        var s=intent();var f=s.claimFrame(new Object(),101);
        assertEquals(90101,f.deadline);assertTrue(s.current(f.deadline));
        assertTrue(f.current(f.epoch,f.owner,f.deadline-1));assertFalse(f.current(f.epoch,f.owner,f.deadline));
        denied(()->s.frameFinished(f,true,true,f.deadline));assertFalse(s.current(f.deadline));
        denied(()->s.claimScroll(f,f.deadline));
    }
    @Test public void originalDirectoriesAreNotOverviewNamespace(){
        assertEquals("rewards-overview-review-v6",OverviewScanScope.PREVIOUS_DIRECTORY);
        assertEquals("rewards-overview-review-v7",OverviewScanScope.DIRECTORY);
        assertNotEquals(OverviewScanScope.PREVIOUS_DIRECTORY,OverviewScanScope.DIRECTORY);
        assertNotEquals(UiImageAlignmentScope.DIRECTORY,OverviewScanScope.DIRECTORY);
        assertNotEquals(UiImageAlignmentScope.TITLE_TIMING_DIRECTORY,OverviewScanScope.DIRECTORY);
        assertEquals(12,OverviewScanScope.MAX_FRAMES);assertEquals(11,OverviewScanScope.MAX_SWIPES);
    }
}
