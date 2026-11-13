package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
/** Pure synthetic integration/guard checks. No Android capture, permission or tap is executed. */
public final class NativeCompletionIntegrationTest {
 @Test public void afterRequiresDispatchedActionAndBefore(){
  assertTrue(NativeCheckInSession.afterTimingEligible(true,true,true,100,101,200,20000));
  assertFalse(NativeCheckInSession.afterTimingEligible(false,true,true,100,101,200,20000));
  assertFalse(NativeCheckInSession.afterTimingEligible(true,false,true,100,101,200,20000));
  assertFalse(NativeCheckInSession.afterTimingEligible(true,true,false,100,101,200,20000));
 }
 @Test public void beforeActionAndEqualTimestampRejected(){
  assertFalse(NativeCheckInSession.afterTimingEligible(true,true,true,100,99,200,20000));
  assertFalse(NativeCheckInSession.afterTimingEligible(true,true,true,100,100,200,20000));
  assertFalse(NativeCheckInSession.afterTimingEligible(true,true,true,-1,101,200,20000));
 }
 @Test public void staleAfterRejectedEvenWithModal(){assertFalse(NativeCheckInSession.afterTimingEligible(true,true,true,100,101,15102,20000));}
 @Test public void freshBoundaryAccepted(){assertTrue(NativeCheckInSession.afterTimingEligible(true,true,true,100,101,15101,20000));}
 @Test public void expiryOrReversedClockRejected(){
  assertFalse(NativeCheckInSession.afterTimingEligible(true,true,true,100,101,200,200));
  assertFalse(NativeCheckInSession.afterTimingEligible(true,true,true,100,101,100,20000));
 }
 private static NativeCheckInSession.Capture capture()throws Exception{
  var ctor=NativeCheckInSession.Capture.class.getDeclaredConstructor(NativeCheckInSession.class,NativeCheckInScope.Phase.class,int.class,int.class,int.class,long.class);
  ctor.setAccessible(true);return ctor.newInstance(null,NativeCheckInScope.Phase.AFTER,1440,2560,0,101L);
 }
 private static NativeCheckInSession.Observation observation(NativeCheckInSession.Capture c)throws Exception{
  var ctor=NativeCheckInSession.Observation.class.getDeclaredConstructor(NativeCheckInSession.Capture.class,
   com.aliothmoon.maafw.rewards.LiveCheckInVerification.Target.class,
   com.aliothmoon.maafw.rewards.LiveCheckInVerification.Outcome.class,CheckInSuccessModal.Result.class,String.class,String.class);
  ctor.setAccessible(true);return ctor.newInstance(c,null,null,null,null,"synthetic");
 }
 @Test public void cleanupObservationIdentityMustMatchExactCapture()throws Exception{
  var a=capture();var b=capture();var result=observation(a);
  assertTrue(NativeCheckInSession.ownsObservation(a,a,result));
  assertFalse(NativeCheckInSession.ownsObservation(a,b,result));
  assertFalse(NativeCheckInSession.ownsObservation(b,b,result));
  assertFalse(NativeCheckInSession.ownsObservation(null,null,result));
  assertFalse(NativeCheckInSession.ownsObservation(a,a,null));
 }
 @Test public void completedModalPreparationContainsNoTargetFrameOrRoi()throws Exception{checkModalPreparation(CheckInSuccessModal.State.COMPLETE);}
 @Test public void uncertainModalPreparationAlsoCannotCreateInputTarget()throws Exception{checkModalPreparation(CheckInSuccessModal.State.HOLD);}
 private static void checkModalPreparation(CheckInSuccessModal.State state)throws Exception{
  var ctor=NativeCheckInSession.Prepared.class.getDeclaredConstructor(NativeCheckInSession.Capture.class,CheckInSuccessModal.Result.class,String.class);
  ctor.setAccessible(true);var prepared=ctor.newInstance(null,new CheckInSuccessModal.Result(state,"synthetic scalar"),"a".repeat(64));
  assertNull(prepared.roi());
  for(String name:new String[]{"direct","plan","frame"}){var field=NativeCheckInSession.Prepared.class.getDeclaredField(name);field.setAccessible(true);assertNull(field.get(prepared));}
 }
 private static DormantCaptureConfirmationActivity.TerminalSnapshot status(boolean terminal,boolean published,String phase,boolean after){
  return new DormantCaptureConfirmationActivity.TerminalSnapshot(terminal,published,phase,after);
 }
 @Test public void dumpAfterRequiresPublishedBeforeAndRemainingBudget(){
  String line=status(true,true,"BEFORE",true).line(120000);
  assertTrue(line.contains("needsAfter=true nextAllowed=AFTER_READ_ONLY_APPROVAL sessionRemainingMs=120000"));
 }
 @Test public void dumpUnpublishedCannotOfferAfter(){assertTrue(status(true,false,"BEFORE",true).line(120000).contains("needsAfter=false nextAllowed=NONE sessionRemainingMs=0"));}
 @Test public void dumpExpiredCannotOfferAfter(){assertTrue(status(true,true,"BEFORE",true).line(0).contains("needsAfter=false nextAllowed=NONE sessionRemainingMs=0"));}
 @Test public void dumpNegativeRemainingCannotOfferAfter(){assertTrue(status(true,true,"BEFORE",true).line(-1).contains("nextAllowed=NONE sessionRemainingMs=0"));}
 @Test public void dumpWrongOrUnknownPhaseCannotOfferAfter(){
  assertTrue(status(true,true,"AFTER",true).line(120000).contains("nextAllowed=NONE"));
  assertTrue(status(true,true,"unexpected raw text",true).line(120000).contains("phase=NONE"));
  assertTrue(status(true,true,null,true).line(120000).contains("phase=NONE"));
 }
 @Test public void dumpNewAfterStartsPendingWithoutOldReceipt(){assertEquals("RewardsNativeTerminal terminal=false published=false phase=AFTER needsAfter=false nextAllowed=WAIT sessionRemainingMs=0",status(false,false,"AFTER",false).line(120000));}
 @Test public void dumpCurrentReadOnlyNeverAllowsInput(){assertTrue(status(true,true,"CURRENT_STATUS",false).line(120000).contains("needsAfter=false nextAllowed=NONE sessionRemainingMs=0"));}
 @Test public void dumpRemainingIsBoundedByExistingSessionTtl(){assertTrue(status(true,true,"BEFORE",true).line(Long.MAX_VALUE).endsWith("sessionRemainingMs=180000"));}
 @Test public void repeatedOrMissingPixelHashCannotConfirmAfter(){
  String a="a".repeat(64),b="b".repeat(64);
  assertTrue(NativeCheckInSession.distinctFrameHashes(a,b));
  assertFalse(NativeCheckInSession.distinctFrameHashes(a,a));
  assertFalse(NativeCheckInSession.distinctFrameHashes(null,b));
  assertFalse(NativeCheckInSession.distinctFrameHashes(a,"short"));
 }
}