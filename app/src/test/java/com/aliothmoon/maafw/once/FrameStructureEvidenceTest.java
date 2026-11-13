package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
public class FrameStructureEvidenceTest {
 private final FrameStructureEvidence.Binding b=new FrameStructureEvidence.Binding(new Object(),new Object(),new Object(),new Object());
 private FrameStructureEvidence.Line line(String text){return new FrameStructureEvidence.Line(text,10,10,100,20,0.95);}
 private FrameStructureEvidence.Result parse(String text){return FrameStructureEvidence.parse(b,b,List.of(line(text)),300,300);}
 @Test public void explicitRowAdmitsCounterNotActor(){var r=parse("Search to earn 1/3");assertTrue(r.supported());assertEquals(1,r.current());assertEquals(3,r.target());}
 @Test public void markerIsNotStructure(){assertFalse(parse("Rewards Daily Set").supported());}
 @Test public void unrelatedBalanceRejected(){assertFalse(parse("Rewards balance 1/3").supported());}
 @Test public void duplicateTaskRejected(){assertFalse(FrameStructureEvidence.parse(b,b,List.of(line("Search to earn 1/3"),line("Search to earn 2/3")),300,300).supported());}
 @Test public void identityMismatchRejected(){var other=new FrameStructureEvidence.Binding(b.source(),b.owner(),b.epoch(),new Object());assertFalse(FrameStructureEvidence.parse(b,other,List.of(line("Search to earn 1/3")),300,300).supported());}
 @Test public void invalidCountRejected(){assertFalse(parse("Search to earn 4/3").supported());assertFalse(parse("Search to earn 1/0").supported());}
 @Test public void signInRejected(){assertFalse(FrameStructureEvidence.parse(b,b,List.of(line("Search to earn 1/3"),line("Sign in")),300,300).supported());}
 @Test public void differentTaskUnitsNeverSummed(){assertFalse(FrameStructureEvidence.parse(b,b,List.of(line("Daily set 1/3"),line("Search points 10/90")),300,300).supported());}
 @Test public void taskAndUnitSurviveSingleRow(){var r=parse("Search points 10/90");assertTrue(r.supported());assertEquals(io.github.klwb360.rewards.core.RewardsCore.Task.SEARCH,r.task());assertEquals(FrameStructureEvidence.Unit.OCR_TEXT_POINTS,r.unit());assertEquals(FrameStructureEvidence.Unit.UNESTABLISHED,parse("Daily set 1/3").unit());}
 @Test public void syntheticReceiptOnlyContainsScopedScalars(){assertEquals("SYNTHETIC_NATIVE_OCR task=WEB unit=UNESTABLISHED fraction=1/3",FrameStructureEvidence.syntheticReceipt(parse("Daily set 1/3"),true));}
 @Test public void unavailableReceiptClearsAndUnsupportedIsExplicit(){assertEquals("NOT_OBSERVED",FrameStructureEvidence.syntheticReceipt(parse("Daily set 1/3"),false));assertEquals("SYNTHETIC_NATIVE_OCR: UNSUPPORTED",FrameStructureEvidence.syntheticReceipt(null,true));}
}


