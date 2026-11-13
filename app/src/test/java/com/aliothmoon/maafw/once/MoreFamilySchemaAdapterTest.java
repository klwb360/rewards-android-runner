package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.AutonomousCardFixture.*;

/** Fixed-layout schema assumptions and synthetic regressions, not v22 raw OCR replay. */
public class MoreFamilySchemaAdapterTest {
    private static Box targetBox(){return observe(pixels(false,false),rows(false,false)).diagnostic().targetCard();}
    private static FrameStructureEvidence.Line left(String text,double score,int right){
        Box card=targetBox();int x=card.left()+12;
        return new FrameStructureEvidence.Line(text,x,card.top()+105,right-x,12,score);
    }
    private static int leftEnd(){Box card=targetBox();return (int)Math.floor(card.left()+.161*card.width());}
    @Test public void leftUnmodeledBareGlyphsDoNotBecomeBodyOrCompeteWithRightNumber(){
        for(String text:List.of("1","一","?"))for(double score:new double[]{.3,.95}){
            var lines=rows(false,false);var added=left(text,score,leftEnd()-1);lines.add(added);
            var f=observe(pixels(false,false),lines);assertEquals(LiveRewardCardObservation.Status.OBSERVED,f.status());
            assertTrue(f.cards().get(0).configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()));
            assertEquals(1,f.diagnostic().familyItemDiagnostics().get(0).leftRows());
            assertEquals(LiveRewardCardObservation.BodyClass.TARGET,f.diagnostic().familyItemDiagnostics().get(0).canonicalBody());
            assertEquals(added,lines.get(lines.size()-1));assertFalse(f.diagnostic().summary().contains(A));
        }
    }
    @Test public void leftBoundaryPlusMinusOneAndGutterRemainFailClosed(){
        for(int delta:new int[]{-1,0,1}){
            var lines=rows(false,false);lines.add(left("?",.3,leftEnd()+delta));
            assertEquals(delta<=0?LiveRewardCardObservation.Status.OBSERVED:LiveRewardCardObservation.Status.HOLD,
                observe(pixels(false,false),lines).status());
        }
    }
    @Test public void movingSameLowUnknownRowIntoIdentityOrStateNeverAdoptsIt(){
        Box card=targetBox();int center=(int)Math.ceil(card.left()+.18*card.width());
        for(int x:new int[]{center,415}){
            var lines=rows(false,false);lines.add(new FrameStructureEvidence.Line("?",x,card.top()+105,15,12,.3));
            assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        }
    }
    @Test public void wholeCardKnownRiskOrUnexplainedStateConflictsStillVetoLeftLane(){
        for(String text:List.of("buy","订阅","已完成","+5","1/2","√","Do you know the answer?",A)){
            var lines=rows(false,false);lines.add(left(text,.3,leftEnd()-1));
            assertEquals(text,LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        }
    }
    @Test public void extraCentralBodyRightUnknownAndDuplicateOriginalRowsAreRejected(){
        for(int lane:new int[]{0,1,2}){
            var lines=rows(false,false);
            if(lane==0)lines.add(MoreCardFixture.line("Unknown",123,360,150,12));
            else if(lane==1)lines.add(MoreCardFixture.line("Later",466,354,19,12));
            else lines.add(lines.stream().filter(r->r.text().equals(A)).findFirst().orElseThrow());
            assertEquals(LiveRewardCardObservation.Status.HOLD,observe(pixels(false,false),lines).status());
        }
    }
    @Test public void oldSyntheticX55LayoutIsUnsupportedRatherThanFallbackToStrictRoute(){
        var lines=rows(false,false);
        for(int i=0;i<lines.size();i++){var r=lines.get(i);if(r.text().equals(TITLE)||r.text().equals(A)||r.text().equals(B))
            lines.set(i,new FrameStructureEvidence.Line(r.text(),55,r.y(),265,r.height(),r.score()));}
        var f=observe(pixels(false,false),lines);assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());
        assertEquals(LiveRewardCardObservation.AdapterGate.FAMILY_RESULT,f.diagnostic().adapterFirstGate());
    }
    @Test public void readyDiagnosticsDescribeBodyClassesAuxiliariesAndCompleteCoverageWithoutText(){
        var d=observe(pixels(false,false),rows(false,false)).diagnostic();
        assertEquals(LiveRewardCardObservation.AdapterGate.NONE,d.adapterFirstGate());
        assertEquals(2,d.familyItemDiagnostics().size());assertEquals(2,d.auxiliaryDiagnostics().size());
        assertEquals(LiveRewardCardObservation.BodyClass.TARGET,d.familyItemDiagnostics().get(0).canonicalBody());
        assertEquals(LiveRewardCardObservation.BodyClass.SIBLING,d.familyItemDiagnostics().get(1).canonicalBody());
        for(var item:d.familyItemDiagnostics()){assertEquals(5,item.points());assertEquals(RewardRegionLocator.DiagnosticCheck.YES,item.partitionValid());}
        for(var aux:d.auxiliaryDiagnostics()){assertEquals(1,aux.titleCount());assertEquals(RewardRegionLocator.DiagnosticCheck.YES,aux.geometryValid());}
        assertEquals(0,d.unexplainedCardsSeen());assertEquals(0,d.unexplainedRowsSeen());
        assertTrue(d.summary().length()<8192);
        for(String raw:List.of(TITLE,A,B,"Complete this puzzle","Quote of the day"))assertFalse(d.summary().contains(raw));
    }
    @Test public void missingAuxiliaryRetainsCountsAndFirstAdapterGateWithoutInventingGeometry(){
        var lines=rows(false,false);lines.removeIf(r->r.text().equals("Complete this puzzle")||r.text().equals("Quote of the day"));
        var f=observe(pixels(false,false),lines);var d=f.diagnostic();
        assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());assertEquals(2,d.familyItemCount());
        assertEquals(LiveRewardCardObservation.AdapterGate.AUXILIARY_BOUNDARY,d.adapterFirstGate());
        for(var aux:d.auxiliaryDiagnostics()){assertEquals(0,aux.titleCount());assertNull(aux.association());assertEquals(RewardRegionLocator.DiagnosticCheck.NOT_EVALUATED,aux.geometryValid());}
        assertEquals(-1,d.unexplainedCardsSeen());assertEquals(-1,d.unexplainedRowsSeen());
    }
    @Test public void unexplainedRowsAndFailedAuxiliaryCardsExposeExactFirstStop(){
        var lines=rows(false,false);lines.add(MoreCardFixture.line("Unknown",55,414,265,10));
        var f=observe(pixels(false,false),lines);assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());
        assertEquals(LiveRewardCardObservation.AdapterGate.UNEXPLAINED_ROW,f.diagnostic().adapterFirstGate());
        assertEquals(1,f.diagnostic().unexplainedRowsSeen());
        lines=rows(false,false);lines.add(new FrameStructureEvidence.Line("Unclear",55,666,150,12,.3));
        f=observe(pixels(false,false),lines);assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());
        assertEquals(LiveRewardCardObservation.AdapterGate.UNEXPLAINED_CARD,f.diagnostic().adapterFirstGate());
        assertEquals(1,f.diagnostic().unexplainedCardsSeen());
        assertEquals(RewardRegionLocator.VisibleItemStatus.HOLD,f.diagnostic().auxiliaryDiagnostics().get(0).association());
    }
}