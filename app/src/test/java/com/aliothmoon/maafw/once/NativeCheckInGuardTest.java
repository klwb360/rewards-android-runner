package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
public final class NativeCheckInGuardTest {
 private static final String GOOD="  mCurrentFocus=Window{b608f89 u0 com.microsoft.bing/com.microsoft.sapphire.runtime.templates.TemplateActivity}\n"
  +"  mFocusedApp=ActivityRecord{9f2e466 u0 com.microsoft.bing/com.microsoft.sapphire.runtime.templates.TemplateActivity t29}\n"
  +"  mTopFocusedDisplayId=0\n";
 @Test public void actualWindowFormat(){assertNotNull(BingForegroundSnapshot.parse(GOOD));}
 @Test public void rejectsOtherPackage(){assertNull(BingForegroundSnapshot.parse(GOOD.replace("com.microsoft.bing","com.example")));}
 @Test public void rejectsComponentChange(){assertNull(BingForegroundSnapshot.parse(GOOD.replace("Activity t29","OtherActivity t29")));}
 @Test public void rejectsDisplayChange(){assertNull(BingForegroundSnapshot.parse(GOOD.replace("DisplayId=0","DisplayId=1")));}
 @Test public void rejectsNullFocus(){assertNull(BingForegroundSnapshot.parse(GOOD+"mCurrentFocus=null\n"));}
 @Test public void rejectsMissingMetadata(){assertNull(BingForegroundSnapshot.parse("mTopFocusedDisplayId=0\n"));}
 @Test public void rejectsDuplicateMetadata(){assertNull(BingForegroundSnapshot.parse(GOOD+GOOD));}
 @Test public void rejectsDifferentUser(){assertNull(BingForegroundSnapshot.parse(GOOD.replace(" u0 "," u10 ")));}
 @Test public void rejectsLargeAndNull(){assertNull(BingForegroundSnapshot.parse(null));assertNull(BingForegroundSnapshot.parse("x".repeat(262145)));}
 @Test public void rejectsWindowSwitch(){assertNotEquals(BingForegroundSnapshot.parse(GOOD),BingForegroundSnapshot.parse(GOOD.replace("b608f89","ff0077")));}
 @Test public void rejectsActivitySwitch(){assertNotEquals(BingForegroundSnapshot.parse(GOOD),BingForegroundSnapshot.parse(GOOD.replace("9f2e466","ff0077")));}
 @Test public void rejectsTaskSwitch(){assertNotEquals(BingForegroundSnapshot.parse(GOOD),BingForegroundSnapshot.parse(GOOD.replace(" t29"," t30")));}
 @Test public void acceptsFreshBoundaries(){assertTrue(NativeCheckInScope.fresh(100,100,20000));assertTrue(NativeCheckInScope.fresh(100,15100,20000));}
 @Test public void rejectsStale(){assertFalse(NativeCheckInScope.fresh(100,15101,20000));}
 @Test public void rejectsExpiry(){assertFalse(NativeCheckInScope.fresh(100,101,101));}
 @Test public void rejectsClockReversal(){assertFalse(NativeCheckInScope.fresh(100,99,20000));}
 @Test public void rejectsUnknownCaptureTime(){assertFalse(NativeCheckInScope.fresh(-1,0,20000));}
 @Test public void absoluteExpiryNeverRenews(){assertFalse(NativeCheckInScope.current(NativeCheckInScope.EXPIRES_UTC));assertFalse(NativeCheckInScope.current(0));}

 @Test public void capturePhaseReceiptsAreIndependent(){
  var f=new AppVisibilityFence();f.enter("activity");f.stopped("activity");
  var beforeLease=f.lease(()->{});var before=new SealedReadOnlyReceipt();
  assertTrue(before.commit(f,beforeLease,true,"BEFORE: command returned, not completed"));
  f.enter("activity");f.stopped("activity");var afterLease=f.lease(()->{});
  var after=new SealedReadOnlyReceipt();
  assertNull(after.history());
  assertTrue(after.commit(f,afterLease,true,"AFTER: same location tick observed"));
  assertEquals("BEFORE: command returned, not completed",before.history());
  assertEquals("AFTER: same location tick observed",after.history());
 }
 @Test public void afterCannotPublishThroughBeforeLease(){
  var f=new AppVisibilityFence();f.enter("activity");f.stopped("activity");
  var beforeLease=f.lease(()->{});var before=new SealedReadOnlyReceipt();
  assertTrue(before.commit(f,beforeLease,true,"BEFORE"));
  f.enter("activity");f.stopped("activity");
  var after=new SealedReadOnlyReceipt();
  assertFalse(after.commit(f,beforeLease,true,"AFTER"));
  assertNull(after.history());assertEquals("BEFORE",before.history());
 }
}
