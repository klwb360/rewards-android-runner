package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
/** Fake pixels/known literals. Current state does not claim old-frame attribution. */
public class CurrentFamilyStatusTest {
 static ReadOnlyTwoStageRun.Observation observe(boolean ac,boolean bc){return observe(AutonomousCardFixture.pixels(ac,bc),AutonomousCardFixture.rows(ac,bc));}
 static ReadOnlyTwoStageRun.Observation observe(byte[] pixels,List<FrameStructureEvidence.Line> rows){var c=ReadOnlyTwoStageRun.Capture.moreCurrentStatus(new Object(),"current-fixture",523,1000,0,1000,()->false);return c.prepare(pixels,523,1000,rows,()->false);}
 @Test public void twoPendingCardsKeepTheirExactIndependentIdentities(){var o=observe(false,false);assertEquals(ReadOnlyTwoStageRun.CurrentFamilyState.TARGET_EXACT_PENDING,o.currentFamilyState());assertEquals(2,o.currentItems().size());
  assertEquals(ReadOnlyTwoStageRun.CurrentIdentity.EXACT_A,o.currentItems().get(0).identity());assertEquals(ReadOnlyTwoStageRun.CurrentIdentity.EXACT_B,o.currentItems().get(1).identity());assertTrue(o.currentItems().stream().allMatch(i->!i.complete()&&i.points()==5));}
 @Test public void bodylessCompletedAIsNotNamedByEliminatingB(){var o=observe(true,false);assertEquals(ReadOnlyTwoStageRun.CurrentFamilyState.UNNAMED_COMPLETE_PRESENT,o.currentFamilyState());
  assertEquals(2,o.currentItems().size());assertEquals(ReadOnlyTwoStageRun.CurrentIdentity.UNNAMED,o.currentItems().get(0).identity());assertTrue(o.currentItems().get(0).complete());
  assertEquals(ReadOnlyTwoStageRun.CurrentIdentity.EXACT_B,o.currentItems().get(1).identity());assertFalse(o.currentItems().get(1).complete());}
 @Test public void twoBodylessCompletedCardsRemainTwoUnnamedResults(){var o=observe(true,true);assertEquals(2,o.currentItems().size());assertTrue(o.currentItems().stream().allMatch(i->i.identity()==ReadOnlyTwoStageRun.CurrentIdentity.UNNAMED&&i.complete()));}
 @Test public void exactRetainedABodyCanDirectlyNameItsCompletion(){byte[] p=AutonomousCardFixture.pixels(true,false);var rows=AutonomousCardFixture.rows(true,false);
  // Known A body is inserted on a synthetic taller completed card, not inferred from B.
  RewardCardFixture.fill(p,25,249,509,410,255);RewardCardFixture.tick(p,465,282);rows.add(MoreCardFixture.line(AutonomousCardFixture.A,123,340,240,20));
  var o=observe(p,rows);assertEquals(LiveRewardCardObservation.Status.OBSERVED,o.frame.status());assertEquals(ReadOnlyTwoStageRun.CurrentFamilyState.TARGET_EXACT_COMPLETE,o.currentFamilyState());assertTrue(o.currentItems().get(0).complete());}
 @Test public void badNativeOrSemanticHoldCannotPublishZeroCardsAsFact(){var rows=AutonomousCardFixture.rows(false,false);MoreVisibleCardObservationTest.replace(rows,AutonomousCardFixture.A,AutonomousCardFixture.A,.1);var o=observe(AutonomousCardFixture.pixels(false,false),rows);
  assertEquals(ReadOnlyTwoStageRun.CurrentFamilyState.HOLD,o.currentFamilyState());assertTrue(o.currentItems().isEmpty());assertTrue(o.currentStatusSummary().contains("familyItems=UNOBSERVED"));
  rows=AutonomousCardFixture.rows(false,false);rows.set(0,new FrameStructureEvidence.Line("More",-1,10,20,20,.9));o=observe(AutonomousCardFixture.pixels(false,false),rows);assertFalse(o.validNativeShape());assertTrue(o.currentItems().isEmpty());}
 @Test public void summaryIsTextFreeAndNativeBatchCountIsSameHolder(){var o=observe(false,false);assertTrue(o.nativeNonemptyRows()>0);assertEquals(AutonomousCardFixture.rows(false,false).size(),o.nativeRowCount());String s=o.currentStatusSummary();assertFalse(s.contains(AutonomousCardFixture.A));assertFalse(s.contains(AutonomousCardFixture.B));assertFalse(s.contains("actions=0"));assertTrue(s.contains("taskTaps=0"));assertTrue(s.length()<1600);}
}

