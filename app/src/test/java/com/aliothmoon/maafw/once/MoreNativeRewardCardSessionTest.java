package com.aliothmoon.maafw.once;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.MoreCardFixture.*;
import static com.aliothmoon.maafw.once.NativeRewardCardSession.*;

/** Fake typed actor/authority only. Does not execute any native app input. */
public class MoreNativeRewardCardSessionTest {
    private static final class Rig implements Authority,ReviewShizukuTapActor.Backend,
            ReviewShizukuTapActor.Session,ReviewShizukuTapActor.PendingTap {
        long now=1000;int burns,starts,sources,expectedY=685;boolean source=true,burnThrows,dispatchThrows;
        @Override public long elapsedRealtime(){return now;}
        @Override public boolean scopeCurrent(){return true;}
        @Override public boolean sourceStillMatches(int w,int h,int r){sources++;return source&&w==W&&h==H&&r==0;}
        @Override public void consumeOneTap(ReviewShizukuTapActor.TapTarget target)throws Exception{
            burns++;assertEquals(439,target.x());assertEquals(expectedY,target.y());if(burnThrows)throw new Exception("synthetic uncertain burn");
        }
        @Override public ReviewShizukuTapActor.Session open(){return this;}
        @Override public boolean stillReady(){return true;}
        @Override public ReviewShizukuTapActor.DisplayShape display(){return new ReviewShizukuTapActor.DisplayShape(W,H,0);}
        @Override public ReviewShizukuTapActor.PendingTap dispatch(ReviewShizukuTapActor.TapTarget target,ReviewShizukuTapActor.FinalGate gate)throws Exception{
            gate.requireValid();starts++;if(dispatchThrows)throw new Exception("synthetic unknown dispatch");return this;
        }
        @Override public boolean waitForExit(long millis){return true;}
        @Override public int exitCode(){return 0;}
        @Override public void destroy(){}
        @Override public void close(){}
        NativeRewardCardSession session(){return new NativeRewardCardSession("fresh-more-only-scope",100000,this,
            new ReviewShizukuTapActor(this),LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay());}
    }
    private static Observation observe(Capture capture,boolean complete,List<FrameStructureEvidence.Line> lines){
        assertNotNull(capture);return capture.prepare(pixels(complete),W,H,lines,()->false);
    }
    private static Receipt before(NativeRewardCardSession s){
        var c=s.startCapture(Phase.BEFORE,W,H,0);var o=observe(c,false,rows(false));
        assertEquals(LiveRewardCardObservation.Route.MORE_VISIBLE_ITEM,o.route());
        return s.acceptAfterCleanup(c,o,true,()->true,o.incompleteCards().get(0));
    }
    private static Receipt after(NativeRewardCardSession s,boolean complete,List<FrameStructureEvidence.Line> lines){
        var c=s.startCapture(Phase.AFTER,W,H,0);var o=observe(c,complete,lines);
        return s.acceptAfterCleanup(c,o,true,()->true,null);
    }
    @Test public void exactQuotePlusFiveThenFreshSameTaskPlainFiveTickCompletes(){
        Rig r=new Rig();var s=r.session();var first=before(s);
        assertEquals(Outcome.AWAITING_NEW_OBSERVATION,first.outcome());assertEquals(1,r.starts);assertEquals(1,r.burns);
        r.now=2000;var result=after(s,true,rows(true));
        assertEquals(Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED,result.outcome());assertFalse(result.accountCreditKnown());assertFalse(result.causedByTap());
        assertEquals(1,r.starts);assertEquals(1,r.burns);assertEquals(6,r.sources);assertFalse(s.needsAfter());
    }
    @Test public void repeatedQuestionTitlesAndChangedUnrelatedSiblingDoNotAnchorOrBlockQuote(){
        Rig r=new Rig();var s=r.session();before(s);r.now=2000;
        var lines=rows(true);MoreVisibleCardObservationTest.replace(lines,"Do you know the answer?","Changed unrelated sibling",.2);
        assertEquals(Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED,after(s,true,lines).outcome());
    }
    @Test public void headerFiveToTenDoesNotCompleteUnchangedQuote(){
        Rig r=new Rig();var s=r.session();before(s);r.now=2000;
        var lines=rows(false);MoreVisibleCardObservationTest.replace(lines,"5/20","10/20",.95);
        assertEquals(Outcome.HOLD,after(s,false,lines).outcome());assertEquals(1,r.starts);assertFalse(s.needsAfter());
    }
    @Test public void changedQuoteBodyOrMissingNativeMoreContextHolds(){
        Rig r=new Rig();var s=r.session();before(s);r.now=2000;
        var lines=rows(true);MoreVisibleCardObservationTest.replace(lines,"Start your day with a quote","Other item body",.95);
        assertEquals(Outcome.HOLD,after(s,true,lines).outcome());assertEquals(1,r.starts);
        r=new Rig();s=r.session();before(s);r.now=2000;lines=rows(true);lines.removeIf(line->line.text().equals("更多活动"));
        assertEquals(Outcome.HOLD,after(s,true,lines).outcome());assertEquals(1,r.starts);
    }
    @Test public void afterCounterMustRemainObservedValidAndSameDenominator(){
        for(String counter:List.of("MISSING","10/?","10/30","4/20")){
            Rig r=new Rig();var s=r.session();before(s);r.now=2000;var lines=rows(true);
            if(counter.equals("MISSING"))lines.removeIf(line->line.text().equals("10/20"));
            else MoreVisibleCardObservationTest.replace(lines,"10/20",counter,.95);
            assertEquals(Outcome.HOLD,after(s,true,lines).outcome());assertEquals(1,r.starts);assertFalse(s.needsAfter());
        }
    }
    @Test public void completeBeforeSkipsWithZeroTapsEvenWhenNoIncompleteSelection(){
        Rig r=new Rig();var s=r.session();var c=s.startCapture(Phase.BEFORE,W,H,0);var o=observe(c,true,rows(true));
        assertTrue(o.incompleteCards().isEmpty());
        assertEquals(Outcome.TASK_ALREADY_COMPLETE_SKIP,s.acceptAfterCleanup(c,o,true,()->true,null).outcome());
        assertEquals(0,r.starts);assertEquals(0,r.burns);
    }
    @Test public void realCompleteRegionFractionBeforeSkipsWithoutInput(){
        Rig r=new Rig();var s=r.session();var c=s.startCapture(Phase.BEFORE,W,H,0);var lines=rows(false);
        MoreVisibleCardObservationTest.replace(lines,"5/20","20/20",.95);var o=observe(c,false,lines);
        assertEquals(Outcome.SECTION_ALREADY_COMPLETE_SKIP,s.acceptAfterCleanup(c,o,true,()->true,null).outcome());assertEquals(0,r.starts);
    }
    @Test public void regionCompleteAfterAloneStillCannotProveSelectedQuote(){
        Rig r=new Rig();var s=r.session();before(s);r.now=2000;var lines=rows(false);
        MoreVisibleCardObservationTest.replace(lines,"5/20","20/20",.95);
        assertEquals(Outcome.HOLD,after(s,false,lines).outcome());assertEquals(1,r.starts);
    }
    @Test public void failedCleanupAndStaleNativeFrameNeverReachTypedActor(){
        Rig r=new Rig();var s=r.session();var c=s.startCapture(Phase.BEFORE,W,H,0);var o=observe(c,false,rows(false));
        assertEquals(Outcome.HOLD,s.acceptAfterCleanup(c,o,false,()->true,o.cards().get(0)).outcome());assertEquals(0,r.starts);
        r=new Rig();s=r.session();c=s.startCapture(Phase.BEFORE,W,H,0);o=observe(c,false,rows(false));r.now+=MAX_FRAME_AGE+1;
        assertEquals(Outcome.HOLD,s.acceptAfterCleanup(c,o,true,()->true,o.cards().get(0)).outcome());assertEquals(0,r.burns);
    }
    @Test public void unknownBurnOrDispatchSpendsMoreScopeAndNeverRetries(){
        for(boolean burn:new boolean[]{true,false}){
            Rig r=new Rig();r.burnThrows=burn;r.dispatchThrows=!burn;var s=r.session();var result=before(s);
            assertEquals(Outcome.HOLD,result.outcome());assertEquals(1,r.burns);assertEquals(burn?0:1,r.starts);
            assertFalse(s.needsAfter());assertNull(s.startCapture(Phase.BEFORE,W,H,0));assertNull(s.startCapture(Phase.AFTER,W,H,0));
        }
    }
    @Test public void nativeCounterMissingBeforeNeverConsumesTap(){
        Rig r=new Rig();var s=r.session();var c=s.startCapture(Phase.BEFORE,W,H,0);var lines=rows(false);lines.removeIf(line->line.text().equals("5/20"));
        var o=observe(c,false,lines);assertEquals(Outcome.HOLD,s.acceptAfterCleanup(c,o,true,()->true,null).outcome());assertEquals(0,r.burns);assertEquals(0,r.starts);
    }
    @Test public void cancelBeforeAfterNeverCreatesASecondAction(){
        Rig r=new Rig();var s=r.session();before(s);s.cancel();r.now=2000;
        assertFalse(s.needsAfter());assertNull(s.startCapture(Phase.AFTER,W,H,0));assertNull(s.startCapture(Phase.BEFORE,W,H,0));assertEquals(1,r.starts);
    }
    private static Receipt afterCustom(NativeRewardCardSession session,byte[] p,List<FrameStructureEvidence.Line> lines){
        var capture=session.startCapture(Phase.AFTER,W,H,0);assertNotNull(capture);
        var observation=capture.prepare(p,W,H,lines,()->false);assertNotNull(observation);
        return session.acceptAfterCleanup(capture,observation,true,()->true,null);
    }
    @Test public void controlledCompactNativeQuoteTickAllowsMissingBodyOnlyInAfter(){
        var original=MoreCardFixture.observe(pixels(false),rows(false)).cards().get(0);
        var current=MoreCardFixture.observe(compactPixels(),compactRows()).cards().get(0);
        assertTrue(current.complete());assertFalse(original.sameTask(current));
        assertTrue(original.matchesControlledCompactCompletion(current,LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay()));
        Rig r=new Rig();var session=r.session();before(session);r.now=2000;
        var result=afterCustom(session,compactPixels(),compactRows());
        assertEquals(Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED,result.outcome());
        assertEquals(Reason.SAME_TASK_COMPACT_NATIVE_TICK,result.reason());assertEquals(1,r.starts);assertEquals(1,r.burns);
        assertFalse(result.causedByTap());assertFalse(result.accountCreditKnown());
    }
    @Test public void missingBodyWithoutPhysicalCardShrinkIsStillHold(){
        var lines=rows(true);lines.removeIf(row->row.text().equals("Start your day with a quote"));
        Rig r=new Rig();var session=r.session();before(session);r.now=2000;
        assertEquals(Outcome.HOLD,afterCustom(session,pixels(true),lines).outcome());assertEquals(1,r.starts);
    }
    @Test public void compactMissingBodyWithoutTickIsNotCompletion(){
        byte[] p=compactPixels();RewardCardFixture.fill(p,465,638,490,663,255);RewardCardFixture.fill(p,388,632,490,668,80);
        var lines=compactRows();for(int i=0;i<lines.size();i++)if(lines.get(i).text().equals("5")&&lines.get(i).y()>600)
            lines.set(i,line("+5",414,637,51,26));
        var frame=MoreCardFixture.observe(p,lines);assertEquals(LiveRewardCardObservation.Status.HOLD,frame.status()); // Compact incomplete shape also fails the independent item gate.
        Rig r=new Rig();var session=r.session();before(session);r.now=2000;
        assertEquals(Outcome.HOLD,afterCustom(session,p,lines).outcome());assertEquals(1,r.starts);
    }
    @Test public void compactNewOrConflictingBodyNeverUsesTheMissingBodyException(){
        var lines=compactRows();lines.add(line("New conflicting body",55,666,265,20));
        var frame=MoreCardFixture.observe(compactPixels(),lines);assertEquals(LiveRewardCardObservation.Status.OBSERVED,frame.status());assertTrue(frame.cards().get(0).complete());
        Rig r=new Rig();var session=r.session();before(session);r.now=2000;
        assertEquals(Outcome.HOLD,afterCustom(session,compactPixels(),lines).outcome());assertEquals(1,r.starts);
    }
    @Test public void compactLowOrUnknownLocalBodyHoldsBeforeIdentityMatching(){
        for(boolean low:new boolean[]{false,true}){
            var lines=compactRows();lines.add(new FrameStructureEvidence.Line(low?"Start your day with a quote":"",55,666,265,20,low?.3:.95));
            Rig r=new Rig();var session=r.session();before(session);r.now=2000;
            assertEquals(Outcome.HOLD,afterCustom(session,compactPixels(),lines).outcome());assertEquals(1,r.starts);
        }
    }
    @Test public void independentlyCompleteButDifferentCardWidthCannotUseCompactRelation(){
        byte[] p=compactPixels();RewardCardFixture.fill(p,25,605,509,695,247);RewardCardFixture.fill(p,25,605,485,695,255);RewardCardFixture.tick(p,441,638);
        var lines=compactRows();for(int i=0;i<lines.size();i++)if(lines.get(i).text().equals("5")&&lines.get(i).y()>600)
            lines.set(i,line("5",409,641,24,17));
        var frame=MoreCardFixture.observe(p,lines);assertEquals(LiveRewardCardObservation.Status.OBSERVED,frame.status());assertTrue(frame.cards().get(0).complete());
        Rig r=new Rig();var session=r.session();before(session);r.now=2000;
        assertEquals(Outcome.HOLD,afterCustom(session,p,lines).outcome());
    }
    @Test public void independentCompactTickWithChangedTitleLayoutOrScaleHolds(){
        for(boolean scale:new boolean[]{false,true}){
            var lines=compactRows();for(int i=0;i<lines.size();i++)if(lines.get(i).text().equals("Quote of the day"))
                lines.set(i,line("Quote of the day",scale?55:105,626,scale?150:265,25));
            var frame=MoreCardFixture.observe(compactPixels(),lines);assertEquals(LiveRewardCardObservation.Status.OBSERVED,frame.status());
            Rig r=new Rig();var session=r.session();before(session);r.now=2000;
            assertEquals(Outcome.HOLD,afterCustom(session,compactPixels(),lines).outcome());
        }
    }
    @Test public void compactRelationAllowsPageMovementInsteadOfCoordinateTaskIds(){
        byte[] source=compactPixels(),p=source.clone();RewardCardFixture.fill(p,25,605,509,695,247);
        for(int y=605;y<695;y++)for(int x=25;x<509;x++)p[(y+100)*W+x+2]=source[y*W+x];
        var lines=compactRows();for(int i=0;i<lines.size();i++){var row=lines.get(i);if(row.y()>=605&&row.y()<695)
            lines.set(i,new FrameStructureEvidence.Line(row.text(),row.x()+2,row.y()+100,row.width(),row.height(),row.score()));}
        Rig r=new Rig();var session=r.session();before(session);r.now=2000;
        assertEquals(Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED,afterCustom(session,p,lines).outcome());assertEquals(1,r.starts);
    }
    @Test public void compactCardCannotBypassMissingOrChangedHeaderCounter(){
        for(boolean missing:new boolean[]{false,true}){
            var lines=compactRows();if(missing)lines.removeIf(row->row.text().equals("10/20"));
            else MoreVisibleCardObservationTest.replace(lines,"10/20","10/30",.95);
            Rig r=new Rig();var session=r.session();before(session);r.now=2000;
            assertEquals(Outcome.HOLD,afterCustom(session,compactPixels(),lines).outcome());assertEquals(1,r.starts);
        }
    }
    @Test public void compactCompletionCannotBypassFreshnessSourceOrCleanup(){
        Rig r=new Rig();var session=r.session();before(session);r.now=2000;
        var c=session.startCapture(Phase.AFTER,W,H,0);var o=c.prepare(compactPixels(),W,H,compactRows(),()->false);r.now+=MAX_FRAME_AGE+1;
        assertEquals(Outcome.HOLD,session.acceptAfterCleanup(c,o,true,()->true,null).outcome());
        r=new Rig();session=r.session();before(session);r.now=2000;c=session.startCapture(Phase.AFTER,W,H,0);o=c.prepare(compactPixels(),W,H,compactRows(),()->false);r.source=false;
        assertEquals(Outcome.HOLD,session.acceptAfterCleanup(c,o,true,()->true,null).outcome());
        r=new Rig();session=r.session();before(session);r.now=2000;c=session.startCapture(Phase.AFTER,W,H,0);o=c.prepare(compactPixels(),W,H,compactRows(),()->false);
        assertEquals(Outcome.HOLD,session.acceptAfterCleanup(c,o,false,()->true,null).outcome());assertEquals(1,r.starts);
    }
    @Test public void sameQuoteAndFiveInDailyDoesNotGetCompactBodyException(){
        var b=RewardCardFixture.rows(false,false);MoreVisibleCardObservationTest.replace(b,"First activity","Quote of the day",.95);
        MoreVisibleCardObservationTest.replace(b,"+10","+5",.95);b.add(line("Start your day with a quote",55,390,265,20));
        Rig r=new Rig();r.expectedY=344;var session=new NativeRewardCardSession("strict-daily",100000,r,new ReviewShizukuTapActor(r));
        var c=session.startCapture(Phase.BEFORE,W,H,0);var o=c.prepare(RewardCardFixture.pixels(false,false),W,H,b,()->false);
        assertEquals(Outcome.AWAITING_NEW_OBSERVATION,session.acceptAfterCleanup(c,o,true,()->true,o.cards().get(0)).outcome());
        byte[] p=RewardCardFixture.pixels(true,false);RewardCardFixture.fill(p,25,249,509,440,247);RewardCardFixture.fill(p,25,249,509,339,255);RewardCardFixture.tick(p,465,282);
        var a=RewardCardFixture.rows(true,false);MoreVisibleCardObservationTest.replace(a,"First activity","Quote of the day",.95);
        for(int i=0;i<a.size();i++)if(a.get(i).text().equals("10"))a.set(i,line("5",433,285,24,17));
        r.now=2000;c=session.startCapture(Phase.AFTER,W,H,0);o=c.prepare(p,W,H,a,()->false);
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,o.status());assertTrue(o.cards().get(0).complete());
        assertEquals(Outcome.HOLD,session.acceptAfterCleanup(c,o,true,()->true,null).outcome());assertEquals(1,r.starts);
    }}


