package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
/** Synthetic final-append fence tests. No native or device evidence is asserted. */
public class NativeProgressPublicationTest {
 private NativeProgressPublication pending(boolean healthy,long phase,long session){Object o=new Object();var binding=new FrameStructureEvidence.Binding(o,o,o,o);var scalars=NativeProgressScalars.observe(binding,List.of(new FrameStructureEvidence.Line("search points 60/60",0,10,250,20,.99),new FrameStructureEvidence.Line("read to earn 12/30",0,40,250,20,.99)),400,800);return new NativeProgressPublication(scalars,healthy,1000,phase,session);}
 private void unknown(String s){assertTrue(s,s.contains("SEARCH=UNKNOWN READING=UNKNOWN"));assertTrue(s.contains("progressEvidence=UNQUALIFIED"));assertFalse(s.contains("60/60"));assertFalse(s.contains("12/30"));assertTrue(s.contains("REJECTED_AT_APPEND"));}
 @Test public void healthyAndCurrentAtAppendCanPublish(){String s=pending(true,30000,60000).publish(2000,true,true,true);assertTrue(s.contains("SEARCH=KNOWN"));assertTrue(s.contains("SEARCHComplete=true"));assertTrue(s.contains("CURRENT_AT_APPEND"));}
 @Test public void cancelledWhileCallbackQueuedPublishesUnknown(){unknown(pending(true,30000,60000).publish(2000,false,true,true));}
 @Test public void phaseExpiredWhileCallbackQueuedPublishesUnknown(){unknown(pending(true,2000,60000).publish(2000,true,true,true));}
 @Test public void sessionExpiredWhileCallbackQueuedPublishesUnknown(){unknown(pending(true,30000,2000).publish(2000,true,true,true));}
 @Test public void frameStaleBeforeAppendPublishesUnknown(){unknown(pending(true,30000,60000).publish(16001,true,true,true));}
 @Test public void sourceVisibilityRevokedBeforeAppendPublishesUnknown(){unknown(pending(true,30000,60000).publish(2000,true,false,true));}
 @Test public void sourceAuthorityLostBeforeAppendPublishesUnknown(){unknown(pending(true,30000,60000).publish(2000,true,true,false));}
 @Test public void unhealthyOwnerResultCannotBePromoted(){unknown(pending(false,30000,60000).publish(2000,true,true,true));}
 @Test public void duplicatePublicationIsRejectedAndHistoricalReceiptStaysFixed(){var p=pending(true,30000,60000);String historical=p.publish(2000,true,true,true);unknown(p.publish(17000,false,false,false));assertTrue(historical.contains("SEARCH=KNOWN"));assertTrue(historical.contains("CURRENT_AT_APPEND"));}
 @Test public void futureAcquisitionTimestampIsRejected(){unknown(pending(true,30000,60000).publish(999,true,true,true));}
 @Test public void sharedNativeFrameAgeFenceAcceptsExactBoundaryOnly(){assertTrue(NativeProgressPublication.frameFresh(16000,1000));assertFalse(NativeProgressPublication.frameFresh(16001,1000));assertFalse(NativeProgressPublication.frameFresh(999,1000));assertFalse(NativeProgressPublication.frameFresh(1000,-1));}
 @Test public void exactFrameAgeBoundaryCanPublishButOneMillisecondOlderCannot(){assertTrue(pending(true,30000,60000).publish(16000,true,true,true).contains("CURRENT_AT_APPEND"));unknown(pending(true,30000,60000).publish(16001,true,true,true));}
}
