package com.aliothmoon.maafw.once;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public class NativeProgressScalarsTest {
 private static FrameStructureEvidence.Line row(String text,int y,double score){return new FrameStructureEvidence.Line(text,0,y,250,20,score);}
 private static NativeProgressScalars observe(FrameStructureEvidence.Line... rows){Object o=new Object();return NativeProgressScalars.observe(new FrameStructureEvidence.Binding(o,o,o,o),List.of(rows),400,800);}
 @Test public void mixedTasksKeepTheirOwnFractionAndUnknownUnits(){String s=observe(row("search to earn 12/60",10,.99),row("read to earn 6/30",40,.99)).summary(true);assertTrue(s.contains("SEARCHFraction=12/60 SEARCHUnit=UNESTABLISHED SEARCHComplete=false SEARCHRemaining=48"));assertTrue(s.contains("READINGFraction=6/30"));assertFalse(s.contains("OCR_TEXT_POINTS"));}
 @Test public void unqualifiedNeverPublishesCounter(){String s=observe(row("search points 60/60",10,.99)).summary(false);assertTrue(s.contains("SEARCH=UNKNOWN READING=UNKNOWN"));assertFalse(s.contains("60/60"));}
 @Test public void completeIsReadOnlyScalar(){String s=observe(row("search points 60/60",10,.99)).summary(true);assertTrue(s.contains("SEARCHUnit=OCR_TEXT_POINTS SEARCHComplete=true SEARCHRemaining=0"));assertTrue(s.contains("READING=UNKNOWN"));assertTrue(s.contains("actorConstructed=false"));}
 @Test public void splitRowsStayUnknownWithSafeCounts(){String s=observe(row("search to earn",10,.99),row("12/60",40,.99),row("read to earn",70,.99),row("6/30",100,.99)).summary(true);assertTrue(s.contains("SEARCH=UNKNOWN READING=UNKNOWN"));assertTrue(s.contains("searchHeadingRows=1 readingHeadingRows=1 sameRowFractionRows=0 bareFractionRows=2"));assertFalse(s.contains("12/60"));}
 @Test public void unrelatedLowConfidenceStillBlocks(){String s=observe(row("search to earn 12/60",10,.99),row("private notification text",40,.4)).summary(true);assertTrue(s.contains("SEARCH=UNKNOWN"));assertTrue(s.contains("lowConfidenceRows=1"));assertFalse(s.contains("private notification"));}
 @Test public void unsupportedOtherRoleNeverBorrowed(){String s=observe(row("read to earn 30/30",10,.99)).summary(true);assertTrue(s.contains("SEARCH=UNKNOWN READING=KNOWN"));assertTrue(s.contains("READINGComplete=true"));}
 @Test public void excessiveRowsBoundDiagnostics(){var rows=new java.util.ArrayList<FrameStructureEvidence.Line>();for(int i=0;i<129;i++)rows.add(row("SECRET",10,.99));Object o=new Object();String s=NativeProgressScalars.observe(new FrameStructureEvidence.Binding(o,o,o,o),rows,400,800).summary(true);assertTrue(s.contains("rowQuality=ROW_LIMIT nativeRows=129"));assertFalse(s.contains("SECRET"));}
}
