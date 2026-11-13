package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;import java.nio.file.*;
public class RootReturnReadWiringTest {
 private String source(String name)throws Exception{return Files.readString(Path.of(System.getProperty("staging.root",".."),"app/src/main/java/com/aliothmoon/maafw/once",name)).replace("\r\n","\n");}
 private String between(String s,String a,String b){return s.substring(s.indexOf(a),s.indexOf(b,s.indexOf(a)));}
 @Test public void newVisibleApprovalCannotConstructTaskActorOrOldRun()throws Exception{
  String a=between(source("DormantCaptureConfirmationActivity.java"),"private void confirmReturnRead()","void returnReadFrameFinished");
  assertTrue(a.indexOf("returnReadSpent=true")<a.indexOf("approveReturnRead"));assertFalse(a.contains("NativeAutonomousCardRun"));assertFalse(a.contains("TapActor"));assertFalse(a.contains("ReturnPermit"));
  String c=source("RootReturnReadController.java");assertFalse(c.contains("actor.tap("));assertFalse(c.contains("NativeAutonomousCardRun"));assertTrue(c.contains("ports.metadataClean()"));assertTrue(c.contains("exactSource.templateMatches(ready)"));
 }
 @Test public void oldLiveIssuersAndOldRuntimeStartsAreExplicitlyExcluded()throws Exception{
  String v=source("VisibleCaptureAuthority.java"),r=source("CaptureSessionRuntime.java");
  for(String name:new String[]{"issueBoundedIntent(","issueBoundedFrame(","issueSeriesIntent(","issueSeriesFrame(","issueRootIntent(","issueRoot("}){int n=v.indexOf(name);assertTrue(name,n>=0);assertTrue(v.substring(n,v.indexOf("}",n)).contains("if(RootReturnReadScope.ACTIVE)"));}
  for(String name:new String[]{"startBounded(","startSeries(","startRoot("}){int n=r.indexOf(name);assertTrue(r.substring(n,r.indexOf("}",n)).contains("if(RootReturnReadScope.ACTIVE)"));}
 }
 @Test public void rootRetainedBeforeNavigationAndGrantOnlyAfterConfirmedReturn()throws Exception{
  String r=source("CaptureSessionRuntime.java");String start=between(r,"static CaptureSessionRuntime startReturnRead(","private void newReturnSourcePermit");
  assertTrue(start.indexOf("EPOCH.retain(run)")<start.indexOf("run::returnBeforeReadOnOwner"));
  String nav=between(r,"private void returnBeforeReadOnOwner()","static boolean returnReadUnpresented");assertTrue(nav.indexOf("returnController.execute()")<nav.indexOf("returnReadIntent.returned("));assertTrue(nav.indexOf("returnReadIntent.returned(")<nav.indexOf("issueReturnReadFrame("));
  assertTrue(nav.contains("RootReturnReadScope.burnReturn("));assertFalse(nav.contains("burnAction("));assertFalse(nav.contains("newProcess("));
 }
 @Test public void phaseDeadlineDoesNotExtendAndOldTimerIsLocal()throws Exception{
  String intent=source("RootReturnReadIntent.java");assertTrue(intent.contains("if(currentOnly||readEnd!=0"));assertTrue(intent.contains("now,readEnd,live"));
  String cancel=between(source("CaptureSessionRuntime.java"),"private void latchCancel()","void onActivityResult(");assertFalse(cancel.contains("returnReadIntent.cancel()"));
  assertTrue(source("CaptureSessionRuntime.java").contains("readPhaseDeadline="));
 }
 @Test public void healthyUnpresentedIsOnlyContinuation()throws Exception{
  String r=source("CaptureSessionRuntime.java");String un=between(r,"static boolean returnReadUnpresented(","private void closeReturnReadOnOwner()");
  for(String key:new String[]{"nativeNonemptyRows()<=0","headerCount()!=0","marker.candidates()!=0","Status.NO_CARDS","cv.cardTotal()==0","band.populationVariance()==0.0","band.flatCount()==band.count()"})assertTrue(key,un.contains(key));
  String close=between(r,"private void closeReturnReadOnOwner()","static final class ReturnReadCompletion");assertTrue(close.contains("rootResourcesAfterPostcheck"));assertTrue(close.contains("ReturnReadCompletion next=unpresented&&released"));assertTrue(close.contains("completeSeriesAudits("));assertTrue(close.contains("rootCandidate=false;rootPublished=false"));assertFalse(close.contains("Sha256"));assertTrue(close.contains("qualifiedCurrentReadSummary(readOnlyObservation,healthy,returnReadIntent.currentCardsOnly())"));String qualification=between(r,"static String qualifiedCurrentReadSummary(","private void closeReturnReadOnOwner()");assertTrue(qualification.contains("currentStatusEvidence=UNQUALIFIED diagnosticOnly=true"));assertTrue(qualification.indexOf("if(!healthy)")<qualification.indexOf("currentStatusEvidence=QUALIFIED"));
 }
 @Test public void nextDoesNotCreateNewClickOrReturnAgain()throws Exception{
  String a=between(source("DormantCaptureConfirmationActivity.java"),"void returnReadFrameFinished(","private volatile RootBoundedBusinessIntent");assertFalse(a.contains("new LiveClick"));assertFalse(a.contains("moveToFront"));assertFalse(a.contains("actor.back("));assertTrue(a.contains("exact.returnReadIndex()+1"));
  String r=source("CaptureSessionRuntime.java");String start=between(r,"static CaptureSessionRuntime startReturnRead(","private void newReturnSourcePermit");assertTrue(start.contains("if(index==1)a.requestHandoff"));assertTrue(start.contains("intent.claimNavigation(EPOCH,now)"));
 }
 @Test public void navigationBudgetAndReadBudgetAreIndependent()throws Exception{
  String r=source("CaptureSessionRuntime.java");String permit=between(r,"static final class RootRecoveryReadPermit","private void returnBeforeReadOnOwner()");assertTrue(permit.contains("reads>=7"));assertTrue(permit.contains("claimed||!current()"));assertTrue(r.contains("newReturnSourcePermit()"));assertTrue(r.contains("RootReturnReadScope.store(activity.getNoBackupFilesDir().toPath(),returnReadIndex)"));assertFalse(r.contains(".seriesDiagnostics("));
 }
 @Test public void numberedReportsAreLosslessAndBounded(){String text="safe=true ".repeat(1800);var reports=java.util.Collections.nCopies(4,text);var parts=RootCaptureUi.returnReadDisplayChunks(reports);assertEquals(12,parts.size());for(int i=0;i<4;i++){StringBuilder b=new StringBuilder();for(int j=0;j<3;j++){String part=parts.get(i*3+j);assertTrue(part.startsWith("returnReadReport="+(i+1)+"/4 chunk="+(j+1)+"/3\n"));assertTrue(part.length()<7000);b.append(part.substring(part.indexOf('\n')+1));}assertEquals(text,b.toString());}}
}
