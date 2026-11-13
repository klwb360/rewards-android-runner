package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;import java.util.List;
/** Synthetic rows only. Clues stop navigation; they never establish a task counter. */
public class NativeProgressScanClueTest {
 private NativeProgressScalars rows(String text,int y,double score){Object o=new Object();return NativeProgressScalars.observe(new FrameStructureEvidence.Binding(o,o,o,o),List.of(new FrameStructureEvidence.Line(text,10,y,300,20,score)),400,1000);}
 @Test public void toolbarOnlyBroadSearchDoesNotStop(){var s=rows("Search",40,.99);assertFalse(s.scanClue());assertTrue(s.summary(false).contains("broadSearchRows=1 broadReadRows=0 contentSearchClues=0"));}
 @Test public void contentBroadSearchStopsButDoesNotBecomeCounter(){var s=rows("Search something new",300,.99);assertTrue(s.scanClue());assertTrue(s.summary(true).contains("SEARCH=UNKNOWN READING=UNKNOWN"));}
 @Test public void contentBroadReadStops(){assertTrue(rows("Read an article",300,.99).scanClue());}
 @Test public void chineseContentWordsStop(){assertTrue(rows("开始搜索",300,.99).scanClue());assertTrue(rows("通过阅读了解",300,.99).scanClue());}
 @Test public void englishWholeWordsDoNotMatchResearchOrAlready(){assertFalse(rows("Research already done",300,.99).scanClue());}
 @Test public void broadLowConfidenceDoesNotEstablishContentClue(){var s=rows("Read an article",300,.4);assertFalse(s.scanClue());assertTrue(s.summary(true).contains("READING=UNKNOWN"));assertTrue(s.summary(true).contains("lowConfidenceRows=1"));}
 @Test public void exactHeadingStopsAnywhereEvenLowConfidence(){assertTrue(rows("read to earn",20,.4).scanClue());assertTrue(rows("search to earn 0/60",950,.4).scanClue());}
 @Test public void straddlingBandDoesNotCountWhollyInside(){assertFalse(rows("Search",190,.99).scanClue());assertFalse(rows("Read",890,.99).scanClue());assertTrue(rows("Read",880,.99).scanClue());}
 @Test public void validCounterStopsAndKeepsObservedUnit(){var s=rows("search points 60/60",300,.99);assertTrue(s.scanClue());assertTrue(s.summary(true).contains("SEARCHComplete=true"));}
 @Test public void badShapeCannotAuthorizeScan(){var s=rows("hello",-1,.99);assertFalse(s.scanRowsUsable());}
 @Test public void scanSummaryReportsRealSuppliedActionCountsAndV39StaysZero(){var s=rows("hello",300,.99);assertTrue(s.summary(false,2,1,true).contains("swipes=2 swipeConfirmedExits=1 textEntry=0 actorConstructed=true"));assertTrue(s.summary(false).contains("swipes=0 swipeConfirmedExits=0 textEntry=0 actorConstructed=false"));}
 @Test public void publicationPreservesScanCountsWhenUnknown(){var p=new NativeProgressPublication(rows("hello",300,.99),true,1000,30000,60000,2,2,true);String s=p.publish(2000,true,true,true);assertTrue(s.contains("SEARCH=UNKNOWN READING=UNKNOWN"));assertTrue(s.contains("swipes=2 swipeConfirmedExits=2"));}
}
