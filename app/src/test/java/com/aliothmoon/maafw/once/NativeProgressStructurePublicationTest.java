package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;import java.util.List;
/** Synthetic pixel/metadata fixtures do not authenticate a live frame. */
public class NativeProgressStructurePublicationTest {
 private NativeProgressPublication token(){Object o=new Object();var b=new FrameStructureEvidence.Binding(o,o,o,o);byte[] gray=new byte[400*800];for(int y=200;y<440;y++)for(int x=20;x<380;x++)gray[y*400+x]=(byte)255;
  var d=NativeProgressRegionDiagnostics.observe(b,b,gray,List.of(new FrameStructureEvidence.Line("阅读以赚取",40,240,140,30,.99),new FrameStructureEvidence.Line("0/30",280,280,70,30,.98)),400,800,()->false);java.util.Arrays.fill(gray,(byte)0);return new NativeProgressPublication(d,true,1000,30000,60000);}
 private void suppressed(String s){assertTrue(s,s.contains("structureEvidence=UNQUALIFIED"));assertTrue(s,s.contains("regions=SUPPRESSED"));assertTrue(s.contains("counterAuthority=false actionAuthority=false"));for(String bad:new String[]{"box=","bboxBrightRatio=","insideRows=[","SEARCH=KNOWN","READING=KNOWN","0/30","阅读以赚取"})assertFalse(bad,s.contains(bad));}
 @Test public void qualifiedStructureIsDiagnosticNotBusinessCounter(){String s=token().publish(2000,true,true,true);assertTrue(s,s.contains("structureEvidence=QUALIFIED"));assertTrue(s.contains("counterAuthority=false actionAuthority=false"));assertTrue(s.contains("publicationFence=CURRENT_AT_APPEND"));assertFalse(s.contains("READING=KNOWN"));assertFalse(s.contains("阅读以赚取"));assertFalse(s.contains("0/30"));}
 @Test public void cancelledBeforeAppendSuppressesAllRegionMetadata(){suppressed(token().publish(2000,false,true,true));}
 @Test public void sourceChangeSuppressesAllRegionMetadata(){suppressed(token().publish(2000,true,true,false));}
 @Test public void visibleOwnUiSuppressesAllRegionMetadata(){suppressed(token().publish(2000,true,false,true));}
 @Test public void staleFrameSuppressesAllRegionMetadata(){suppressed(token().publish(16001,true,true,true));}
 @Test public void exactFreshnessBoundaryStillQualifies(){assertTrue(token().publish(16000,true,true,true).contains("publicationFence=CURRENT_AT_APPEND"));}
 @Test public void phaseDeadlineSuppressesMetadata(){suppressed(token().publish(30000,true,true,true));}
 @Test public void historyRemainsFixedAfterRevocation(){var t=token();String history=t.publish(2000,true,true,true);suppressed(t.publish(2001,false,false,false));assertTrue(history.contains("structureEvidence=QUALIFIED"));}
 @Test public void missingRegionProjectionNeverFallsThroughToBusinessCounter(){suppressed(new NativeProgressPublication((NativeProgressRegionDiagnostics)null,true,1000,30000,60000).publish(2000,true,true,true));}
}
