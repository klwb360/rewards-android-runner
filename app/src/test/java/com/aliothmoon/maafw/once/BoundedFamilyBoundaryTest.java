package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;

/** New bounded first-A template only. Synthetic pixels/rows, never native replay. */
public class BoundedFamilyBoundaryTest {
    static LiveRewardCardObservation.Frame observe(byte[] p,List<FrameStructureEvidence.Line> rows){
        return LiveRewardCardObservation.observe("bounded-fixture",1,p,523,1000,rows,()->false,LiveRewardCardObservation.TargetSpec.moreTriviaChallengeBounded());}
    private static RewardRegionLocator.Card cv(Box b,boolean full){return new RewardRegionLocator.Card(b,full,List.of(),List.of());}
    @Test public void unknownPendingOrRiskBoundaryIsOnlyCutoffNotTask(){
        for(String text:List.of("Unknown boundary task","Subscribe for something","Another pending activity")){
            var rows=AutonomousCardFixture.rows(false,false);MoreVisibleCardObservationTest.replace(rows,"Complete this puzzle",text,.2);
            var f=observe(AutonomousCardFixture.pixels(false,false),rows);assertEquals(LiveRewardCardObservation.Status.OBSERVED,f.status());assertEquals(2,f.cards().size());
            assertTrue(LiveRewardCardObservation.pendingBoundedFirstFamily(f));for(var c:f.cards())assertEquals(AutonomousCardFixture.TITLE,c.title());
            assertEquals(LiveRewardCardObservation.Status.HOLD,AutonomousCardFixture.observe(AutonomousCardFixture.pixels(false,false),rows).status());
        }
    }
    @Test public void boundaryNeedNotHaveAnyOcrStateAtAll(){
        var rows=AutonomousCardFixture.rows(false,false);rows.removeIf(r->r.y()>=605);
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,observe(AutonomousCardFixture.pixels(false,false),rows).status());}
    @Test public void missingOrNearestPartialBoundaryCannotBeSkipped(){
        byte[] p=AutonomousCardFixture.pixels(false,false);RewardCardFixture.fill(p,0,605,523,1000,247);
        var rows=AutonomousCardFixture.rows(false,false);rows.removeIf(r->r.y()>=605);
        assertEquals(LiveRewardCardObservation.Reason.MORE_FAMILY_COVERAGE,observe(p,rows).reason());
        p=AutonomousCardFixture.pixels(false,false);RewardCardFixture.fill(p,0,605,509,695,255);
        assertEquals(LiveRewardCardObservation.Reason.MORE_FAMILY_COVERAGE,observe(p,AutonomousCardFixture.rows(false,false)).reason());}
    @Test public void pureGeometryRejectsTiesOverlapGapZeroAndNearerPartial(){
        Box a=new Box(25,249,509,410),b=new Box(25,427,509,588),h=new Box(25,210,135,235),cut=new Box(25,605,509,695);
        var family=List.of(a,b);var base=new ArrayList<>(List.of(cv(a,true),cv(b,true),cv(cut,true)));
        assertEquals(cut,LiveRewardCardObservation.boundedGeometryLower(base,h,family));
        base.add(cv(new Box(0,605,10,700),true));assertNull(LiveRewardCardObservation.boundedGeometryLower(base,h,family));
        for(var bad:List.of(cv(new Box(25,595,509,610),false),cv(new Box(25,588,509,650),true),cv(new Box(25,400,509,420),true))){
            assertNull(LiveRewardCardObservation.boundedGeometryLower(List.of(cv(a,true),cv(b,true),bad,cv(cut,true)),h,family));}
        assertNull(LiveRewardCardObservation.boundedGeometryLower(List.of(cv(a,true),cv(b,true),cv(cut,true),cv(new Box(20,690,510,760),true)),h,family));}
    @Test public void gapOrAcrossBoundaryOriginalRowsStillHold(){
        for(var row:List.of(MoreCardFixture.line("unexplained gap",200,590,100,10),MoreCardFixture.line("crosses boundary",200,600,100,20))){
            var rows=AutonomousCardFixture.rows(false,false);rows.add(row);var f=observe(AutonomousCardFixture.pixels(false,false),rows);
            assertEquals(LiveRewardCardObservation.Status.HOLD,f.status());assertEquals(LiveRewardCardObservation.AdapterGate.UNEXPLAINED_ROW,f.diagnostic().adapterFirstGate());}}
    @Test public void extraFullSplitOrKnownFragmentTitleAnywhereCannotBeCutAway(){
        for(int variant=0;variant<6;variant++){
            var rows=AutonomousCardFixture.rows(false,false);
            if(variant==0)rows.add(new FrameStructureEvidence.Line(AutonomousCardFixture.TITLE,123,850,240,20,.2));
            if(variant==1){rows.add(MoreCardFixture.line("Do you know the",123,850,240,20));rows.add(new FrameStructureEvidence.Line("answer?",123,875,120,20,.1));}
            if(variant==2)rows.add(new FrameStructureEvidence.Line("Do you know the",123,630,240,20,.1));
            if(variant==3)rows.add(new FrameStructureEvidence.Line("answer？",123,850,120,20,.1));
            if(variant==4)rows.add(new FrameStructureEvidence.Line("THE ANSWER?",123,850,120,20,.1));
            if(variant==5)rows.add(new FrameStructureEvidence.Line(AutonomousCardFixture.TITLE,123,150,240,20,.1));
            assertEquals("variant="+variant,LiveRewardCardObservation.Status.HOLD,observe(AutonomousCardFixture.pixels(false,false),rows).status());
        }
    }
    @Test public void onlyExactFragmentsAreVetoedNotArbitraryBoundaryText(){
        for(String text:List.of("Do you kn0w the","you answer","know","Other unknown text")){
            var rows=AutonomousCardFixture.rows(false,false);rows.add(new FrameStructureEvidence.Line(text,123,850,240,20,.1));
            assertEquals(LiveRewardCardObservation.Status.OBSERVED,observe(AutonomousCardFixture.pixels(false,false),rows).status());}}
    @Test public void firstAQualificationRejectsStaticEmptyOrCompletedSibling(){
        var pending=observe(AutonomousCardFixture.pixels(false,false),AutonomousCardFixture.rows(false,false));assertTrue(LiveRewardCardObservation.pendingBoundedFirstFamily(pending));
        var mixed=observe(AutonomousCardFixture.pixels(false,true),AutonomousCardFixture.rows(false,true));assertEquals(LiveRewardCardObservation.Status.OBSERVED,mixed.status());
        assertFalse(LiveRewardCardObservation.pendingBoundedFirstFamily(mixed));
        var after=observe(AutonomousCardFixture.pixels(true,false),AutonomousCardFixture.rows(true,false));assertEquals(LiveRewardCardObservation.Status.OBSERVED,after.status());assertFalse(LiveRewardCardObservation.pendingBoundedFirstFamily(after));}
    @Test public void wrongPointsAndDuplicateMissingLowBodiesStillStop(){
        for(int variant=0;variant<4;variant++){
            var rows=AutonomousCardFixture.rows(false,false);
            if(variant==0)MoreVisibleCardObservationTest.replace(rows,"+5","+10",.95);
            if(variant==1)MoreVisibleCardObservationTest.replace(rows,AutonomousCardFixture.B,AutonomousCardFixture.A,.95);
            if(variant==2)rows.removeIf(r->r.text().equals(AutonomousCardFixture.B));
            if(variant==3)MoreVisibleCardObservationTest.replace(rows,AutonomousCardFixture.B,AutonomousCardFixture.B,.2);
            assertEquals(LiveRewardCardObservation.Status.HOLD,observe(AutonomousCardFixture.pixels(false,false),rows).status());}}
}