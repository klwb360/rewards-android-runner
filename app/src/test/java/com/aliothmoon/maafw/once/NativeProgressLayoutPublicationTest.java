package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;import java.util.List;
/** Synthetic metadata publication fence; no native authenticity is inferred from fixtures. */
public class NativeProgressLayoutPublicationTest {
 private NativeProgressPublication token(){Object o=new Object();var b=new FrameStructureEvidence.Binding(o,o,o,o);var d=NativeProgressLayoutDiagnostics.observe(b,b,List.of(new FrameStructureEvidence.Line("search points 60/60",10,200,200,20,.99),new FrameStructureEvidence.Line("12/30",10,240,100,20,.91)),400,800);return new NativeProgressPublication(d,true,1000,30000,60000);}
 private void suppressed(String s){assertTrue(s,s.contains("layoutEvidence=UNQUALIFIED"));assertTrue(s.contains("counterAuthority=false actionAuthority=false"));assertTrue(s.contains("candidates=SUPPRESSED"));assertFalse(s.contains("row=0"));assertFalse(s.contains("box="));assertFalse(s.contains(" n=12"));assertFalse(s.contains("SEARCH=KNOWN"));}
 @Test public void qualifiedLayoutNeverPublishesBusinessCounter(){String s=token().publish(2000,true,true,true);assertTrue(s.contains("STRICT_SAME_ROW_COUNTER"));assertTrue(s.contains("fractionRole=UNATTRIBUTED"));assertTrue(s.contains("counterAuthority=false actionAuthority=false"));assertFalse(s.contains("SEARCH=KNOWN"));assertFalse(s.contains("60/60"));}
 @Test public void cancelledBeforeAppendSuppressesRows(){suppressed(token().publish(2000,false,true,true));}
 @Test public void hiddenStateLostSuppressesRows(){suppressed(token().publish(2000,true,false,true));}
 @Test public void sourceLostSuppressesRows(){suppressed(token().publish(2000,true,true,false));}
 @Test public void staleFrameSuppressesRows(){suppressed(token().publish(16001,true,true,true));}
 @Test public void duplicatePublicationSuppressesRowsButHistoryStaysFixed(){var t=token();String history=t.publish(2000,true,true,true);suppressed(t.publish(2001,true,true,true));assertTrue(history.contains("row=0"));}
 @Test public void missingDiagnosticNeverFallsThroughToCounterMode(){var t=new NativeProgressPublication((NativeProgressLayoutDiagnostics)null,true,1000,30000,60000);suppressed(t.publish(2000,true,true,true));}
}
