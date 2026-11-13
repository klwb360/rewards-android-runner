package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;
public class EntryDiagnosticPublicationTest {
 private NativeEntryDiagnosticProtocol.Report report(){var a=new NativeEntryDiagnosticProtocol.Accumulator();a.add(0,-1,0,0,0,100,100,true,true,true,"com.microsoft.bing:id/glance_card_container",null,"Rewards");return new NativeEntryDiagnosticProtocol.Report("0".repeat(32),1000,1100,1200,NativeEntryDiagnosticProtocol.Reason.OK,NativeEntryDiagnosticProtocol.Cleanup.CONFIRMED,8,4,0,a.finish());}
 private EntryDiagnosticPublication token(){return new EntryDiagnosticPublication(report(),true,20000);}
 @Test public void currentAppendPublishesOnlySafeProjection(){String s=token().publish(1300,true,true,true);assertTrue(s.contains("CURRENT_AT_APPEND"));assertTrue(s.contains("strictMatches=1"));assertTrue(s.contains("actionAuthority=false"));assertFalse(s.contains("glance_card_container"));}
 @Test public void cancellationSuppressesAllNodeData(){String s=token().publish(1300,false,true,true);assertTrue(s.contains("nodes=SUPPRESSED"));assertFalse(s.contains("strictMatches"));assertFalse(s.contains("Candidate["));}
 @Test public void hiddenAndSourceLossSuppress(){assertTrue(token().publish(1300,true,false,true).contains("SUPPRESSED"));assertTrue(token().publish(1300,true,true,false).contains("SUPPRESSED"));}
 @Test public void earliestReadBoundaryIsEnforced(){assertTrue(token().publish(16000,true,true,true).contains("CURRENT_AT_APPEND"));assertTrue(token().publish(16001,true,true,true).contains("SUPPRESSED"));}
 @Test public void expiryAndBeforeFinishSuppress(){assertTrue(new EntryDiagnosticPublication(report(),true,1300).publish(1300,true,true,true).contains("SUPPRESSED"));assertTrue(token().publish(1199,true,true,true).contains("SUPPRESSED"));}
 @Test public void cleanupOrMissingEvidenceSuppress(){assertTrue(new EntryDiagnosticPublication(report(),false,20000).publish(1300,true,true,true).contains("SUPPRESSED"));assertTrue(new EntryDiagnosticPublication(null,true,20000).publish(1300,true,true,true).contains("SUPPRESSED"));}
 @Test public void onceOnlyDoesNotInvalidateHistoricalString(){var t=token();String first=t.publish(1300,true,true,true);assertTrue(t.publish(1400,true,true,true).contains("SUPPRESSED"));assertTrue(first.contains("CURRENT_AT_APPEND"));}
}
