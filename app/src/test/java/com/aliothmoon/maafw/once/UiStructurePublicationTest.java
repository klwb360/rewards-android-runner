package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;
public class UiStructurePublicationTest {
 private NativeUiStructureProtocol.Report report(){return new NativeUiStructureProtocol.Report("0123456789abcdef0123456789abcdef",1000,2000,2100,NativeUiStructureProtocol.Reason.OK,NativeUiStructureProtocol.Cleanup.CONFIRMED,3,120,7,0,0,0,400,800,-1,-1,new NativeUiStructureProtocol.Task(NativeUiStructureProtocol.Status.KNOWN,0,60,1,NativeUiStructureProtocol.Unit.EXPLICIT_TEXT_POINTS),NativeUiStructureProtocol.Task.unknown());}
 private UiStructurePublication token(){return new UiStructurePublication(report(),true,30000);}
 private void unknown(String s){assertTrue(s,s.contains("SEARCH=UNKNOWN READING=UNKNOWN"));assertFalse(s.contains("n=0 d=60"));assertFalse(s.contains("acquiredStart="));assertTrue(s.contains("counterSource=UI_STRUCTURE"));}
 @Test public void knownIsUiOnlyAndNotAnImageClaim(){String s=token().publish(2500,true,true,true);assertTrue(s.contains("SEARCH=KNOWN n=0 d=60"));assertTrue(s.contains("READING=UNKNOWN"));assertTrue(s.contains("imageAudit=NOT_REQUESTED screenshots=0 ocr=0 atomicWithImage=false actionAuthority=false ledgerChanged=false"));}
 @Test public void cancellationRejects(){unknown(token().publish(2500,false,true,true));}
 @Test public void ownWindowVisibleRejects(){unknown(token().publish(2500,true,false,true));}
 @Test public void sourceOrPermissionLostRejects(){unknown(token().publish(2500,true,true,false));}
 @Test public void cleanupUnprovenRejects(){unknown(new UiStructurePublication(report(),false,30000).publish(2500,true,true,true));}
 @Test public void exactFreshnessBoundaryAcceptedThenStaleRejected(){assertTrue(token().publish(16000,true,true,true).contains("SEARCH=KNOWN"));unknown(token().publish(16001,true,true,true));}
 @Test public void deadlineRejectsAndPreFinishRejects(){unknown(new UiStructurePublication(report(),true,2500).publish(2500,true,true,true));unknown(token().publish(2099,true,true,true));}
 @Test public void oneShotDoesNotRewriteHistory(){var t=token();String history=t.publish(2500,true,true,true);unknown(t.publish(2501,true,true,true));assertTrue(history.contains("SEARCH=KNOWN"));}
 @Test public void absentReportNeverInventsCounters(){unknown(new UiStructurePublication(null,true,30000).publish(2500,true,true,true));}
 private UiStructurePublication readyToken(){var r=report();return new UiStructurePublication(new NativeUiStructureProtocol.Report(r.nonce(),r.started(),r.acquired(),r.finished(),r.reason(),r.cleanup(),r.nodeCount(),r.charCount(),r.windowId(),r.displayId(),r.left(),r.top(),r.right(),r.bottom(),r.rotationBefore(),r.rotationAfter(),r.search(),r.reading(),new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,0,0,0,1,1,2)),true,30000);}
 @Test public void readinessOnlyAtSameFinalGate(){assertTrue(readyToken().publish(2500,true,true,true).contains("readinessDiagnosticOnly=true pageState=UNESTABLISHED idle=IDLE_OBSERVED"));for(String s:new String[]{readyToken().publish(2500,false,true,true),readyToken().publish(2500,true,false,true),readyToken().publish(2500,true,true,false),readyToken().publish(16001,true,true,true)}){unknown(s);assertFalse(s.contains("webViewCount"));assertFalse(s.contains("TitleNodes"));assertFalse(s.contains("fixedLabelMask"));}}
 @Test public void regionReportCannotBorrowTheLegacyBooleanGate(){
  var f=new UiRegionSourceEvidenceTest.Fixture();String s=new UiStructurePublication(f.report,true,10000).publish(2300,true,true,true);unknown(s);assertTrue(s.contains("SEARCH_REGION=UNKNOWN READING_REGION=UNKNOWN"));assertFalse(s.contains("card="));assertFalse(s.contains("sourceBinding="));
 }
 @Test public void regionPublicationCarriesOnlyFixedRolesAndExplicitImageLimit(){
  var f=new UiRegionSourceEvidenceTest.Fixture();String s=f.publication(f.evidence()).publish(2300,true,true,true);
  assertTrue(s.contains("SEARCH=KNOWN n=0 d=60"));assertTrue(s.contains("READING=UNKNOWN"));
  assertTrue(s.contains("sourceBinding=TEMPLATE_BEFORE_AFTER coordinateSpace=ACCESSIBILITY_SCREEN imageBinding=NOT_ESTABLISHED helperRotation=UNOBSERVED"));
  assertTrue(s.contains("SEARCH_REGION=KNOWN card=[20,40,380,200] summary=[200,60,370,90]"));assertTrue(s.contains("READING_REGION=KNOWN card=[20,220,380,400] summary=UNKNOWN"));
  assertTrue(s.contains("atomicWithImage=false actionAuthority=false ledgerChanged=false"));assertFalse(s.contains(UiRegionSourceEvidenceTest.NONCE));assertFalse(s.contains("aaa"));assertFalse(s.contains("ddd"));assertFalse(s.contains(f.owner.toString()));
 }
 @Test public void observedHelperRotationStillDoesNotClaimImageBinding(){
  var f=new UiRegionSourceEvidenceTest.Fixture(UiRegionSourceEvidenceTest.report(UiRegionSourceEvidenceTest.NONCE,1000,2100,400,800,0,0));String s=f.publication(f.evidence()).publish(2300,true,true,true);assertTrue(s.contains("helperRotation=OBSERVED_MATCHING"));assertTrue(s.contains("imageBinding=NOT_ESTABLISHED"));assertTrue(s.contains("atomicWithImage=false"));
 }
 @Test public void regionAppendChecksEveryExistingGateAndConsumesRejectedEvidence(){
  for(int gate=0;gate<6;gate++){
   var f=new UiRegionSourceEvidenceTest.Fixture();var source=f.evidence();
   var publication=new UiStructurePublication(f.report,gate!=3,gate==4?2300:10000,source,f.owner,f.session,f.epoch);
   String s=publication.publish(gate==5?7201:2300,gate!=0,gate!=1,gate!=2);unknown(s);assertFalse(s.contains("card="));assertFalse(s.contains("sourceBinding="));assertFalse(s.contains("fixedLabelMask="));
   unknown(f.publication(source).publish(2300,true,true,true));
  }
 }
 @Test public void regionEvidenceCannotMoveAcrossOwnersSessionsEpochsOrEqualReports(){
  for(int changed=0;changed<4;changed++){
   var f=new UiRegionSourceEvidenceTest.Fixture();
   var p=new UiStructurePublication(changed==3?UiRegionSourceEvidenceTest.report():f.report,true,10000,f.evidence(),changed==0?new Object():f.owner,changed==1?new Object():f.session,changed==2?new Object():f.epoch);
   unknown(p.publish(2300,true,true,true));
  }
 }
 @Test public void newPublicationCannotReplayConsumedRegionEvidence(){
  var f=new UiRegionSourceEvidenceTest.Fixture();var source=f.evidence();var p=f.publication(source);String history=p.publish(2300,true,true,true);assertTrue(history.contains("SEARCH_REGION=KNOWN"));unknown(p.publish(2301,true,true,true));unknown(f.publication(source).publish(2301,true,true,true));assertTrue(history.contains("SEARCH_REGION=KNOWN"));
 }
}
