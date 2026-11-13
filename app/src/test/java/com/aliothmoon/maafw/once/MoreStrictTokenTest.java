package com.aliothmoon.maafw.once;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.MoreCardFixture.*;

public class MoreStrictTokenTest {
    private static List<FrameStructureEvidence.Line> extra(String text,int x,int y,int w,int h){
        var rows=rows(false);rows.add(line(text,x,y,w,h));return rows;
    }
    @Test public void unknownTextInsideIndicatorOrOnItsRightCannotDisappear(){
        for(var row:List.of(line("Later",466,681,19,12),line("unknown",491,681,15,12))){
            var r=rows(false);r.add(row);assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),r).status());
        }
    }
    @Test public void anotherNumberProgressOrMergedNumberUnitIsNotBody(){
        for(String text:List.of("5","1/2","5pts","Earn 5 points","+5 points"))
            assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),extra(text,55,730,265,20)).status());
    }
    @Test public void onlySmallAttachedKnownPointUnitCanBeClassifiedAsUnit(){
        for(String text:List.of("pts","points","点","积分")){
            var f=observe(pixels(false),extra(text,466,681,19,12));
            assertEquals(LiveRewardCardObservation.Status.OBSERVED,f.status());
            assertTrue(f.cards().get(0).sameTask(observe(pixels(true),rows(true)).cards().get(0)));
        }
    }
    @Test public void detachedOrOversizedKnownUnitsStillHold(){
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),extra("pts",491,681,15,12)).status());
        assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false),extra("points",55,730,220,20)).status());
    }
    @Test public void actualPlainQuoteBodyRemainsAnIdentityAnchor(){
        var b=observe(pixels(false),rows(false));var a=observe(pixels(true),rows(true));
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,b.status());assertTrue(b.cards().get(0).sameTask(a.cards().get(0)));
        assertTrue(a.cards().get(0).complete());
    }
    @Test public void unclassifiedNativeLocalRowsCannotOpenActorOrConsumeDurableTap(){
        for(String token:List.of("Later","5pts","1/2")){
            AtomicInteger opens=new AtomicInteger(),burns=new AtomicInteger();
            var authority=new NativeRewardCardSession.Authority(){
                public long elapsedRealtime(){return 1000;}
                public boolean scopeCurrent(){return true;}
                public boolean sourceStillMatches(int w,int h,int rotation){return w==W&&h==H&&rotation==0;}
                public void consumeOneTap(ReviewShizukuTapActor.TapTarget target){burns.incrementAndGet();}
            };
            var actor=new ReviewShizukuTapActor(()->{opens.incrementAndGet();throw new AssertionError("Unclassified row reached actor");});
            var session=new NativeRewardCardSession("strict-fake-only",100000,authority,actor,LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay());
            var c=session.startCapture(NativeRewardCardSession.Phase.BEFORE,W,H,0);
            var lines=token.equals("Later")?extra(token,466,681,19,12):extra(token,55,730,265,20);
            var o=c.prepare(pixels(false),W,H,lines,()->false);
            assertEquals(NativeRewardCardSession.Outcome.HOLD,session.acceptAfterCleanup(c,o,true,()->true,null).outcome());
            assertEquals(0,opens.get());assertEquals(0,burns.get());assertFalse(session.needsAfter());
        }
    }
}
