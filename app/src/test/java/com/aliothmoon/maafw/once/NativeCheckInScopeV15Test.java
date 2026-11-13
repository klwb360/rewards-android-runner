package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
public final class NativeCheckInScopeV15Test {
 @Test public void exactNewUtcWindow(){
  assertEquals(java.time.Instant.parse("2026-11-11T15:10:00Z").toEpochMilli(),NativeCheckInScope.STARTS_UTC);
  assertEquals(java.time.Instant.parse("2026-11-11T16:00:00Z").toEpochMilli(),NativeCheckInScope.EXPIRES_UTC);
 }
 @Test public void cannotStartBeforeNewGrant(){assertFalse(NativeCheckInScope.current(NativeCheckInScope.STARTS_UTC-1));}
 @Test public void opensAtNewGrant(){assertTrue(NativeCheckInScope.admissionCurrent(NativeCheckInScope.STARTS_UTC));}
 @Test public void closesAtAbsoluteExpiry(){assertFalse(NativeCheckInScope.current(NativeCheckInScope.EXPIRES_UTC));}
 @Test public void currentButNoNewCaptureInFinalNinetySeconds(){
  long utc=NativeCheckInScope.EXPIRES_UTC-NativeCheckInScope.CAPTURE_TTL;
  assertTrue(NativeCheckInScope.current(utc));assertFalse(NativeCheckInScope.admissionCurrent(utc));
 }
 @Test public void fixedCaptureBudget(){assertEquals(91300,NativeCheckInScope.captureDeadline(1300,NativeCheckInScope.STARTS_UTC));}
 @Test public void latestAdmissibleDeadlineStillPrecedesAbsoluteExpiry(){
  long utc=NativeCheckInScope.EXPIRES_UTC-NativeCheckInScope.CAPTURE_TTL-1;
  long deadline=NativeCheckInScope.captureDeadline(1300,utc);
  assertEquals(NativeCheckInScope.EXPIRES_UTC-1,utc+deadline-1300);
 }
 @Test(expected=SecurityException.class) public void rejectsMissingMargin(){
  NativeCheckInScope.captureDeadline(0,NativeCheckInScope.EXPIRES_UTC-NativeCheckInScope.CAPTURE_TTL);
 }
 @Test(expected=SecurityException.class) public void rejectsElapsedOverflow(){
  NativeCheckInScope.captureDeadline(Long.MAX_VALUE-89999,NativeCheckInScope.STARTS_UTC);
 }
 @Test(expected=SecurityException.class) public void rejectsNegativeElapsed(){
  NativeCheckInScope.captureDeadline(-1,NativeCheckInScope.STARTS_UTC);
 }
 @Test public void keepsAllBudgets(){assertEquals(180000,NativeCheckInScope.SESSION_TTL);assertEquals(90000,NativeCheckInScope.CAPTURE_TTL);assertEquals(15000,NativeCheckInScope.MAX_FRAME_AGE);}
 @Test public void newDurableNamespace(){assertEquals("rewards-native-checkin-20261111-v15",NativeCheckInScope.DIRECTORY);}
 @Test public void oldBusinessRemainsExpired(){
  assertEquals(java.time.Instant.parse("2026-11-11T15:00:00Z").toEpochMilli(),BusinessCaptureScope.EXPIRES_UTC);
  assertFalse(BusinessCaptureScope.current(NativeCheckInScope.STARTS_UTC));
 }
}
