package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
public class LocalCardMockCleanupTest {
 private OnceBackend.Result result(OnceBackend.Phase phase,OnceBackend.Cleanup cleanup,OnceBackend.Quiescence quiet,OnceBackend.Memory memory){
  return new OnceBackend.Result(null,1,phase,OnceBackend.Admission.CONSUMED,OnceBackend.Cancellation.NOT_REQUESTED,false,OnceBackend.Failure.NONE,cleanup,quiet,memory);
 }
 @Test public void completedNativeGraphNeedsAllThreeReceipts(){
  assertTrue(LocalCardMockRuntime.cleanupConfirmed(result(OnceBackend.Phase.COMPLETED,OnceBackend.Cleanup.CONFIRMED,OnceBackend.Quiescence.CONFIRMED,OnceBackend.Memory.WIPED)));
  assertFalse(LocalCardMockRuntime.cleanupConfirmed(result(OnceBackend.Phase.COMPLETED,OnceBackend.Cleanup.CONFIRMED,OnceBackend.Quiescence.UNCONFIRMED,OnceBackend.Memory.WIPED)));
  assertFalse(LocalCardMockRuntime.cleanupConfirmed(result(OnceBackend.Phase.COMPLETED,OnceBackend.Cleanup.CONFIRMED,OnceBackend.Quiescence.CONFIRMED,OnceBackend.Memory.RETAINED)));
 }
 @Test public void terminalBeforeAnyInvocationNeedsExplicitNeverStartedReceipts(){
  assertTrue(LocalCardMockRuntime.cleanupConfirmed(result(OnceBackend.Phase.CANCELLED,OnceBackend.Cleanup.NOT_NEEDED,OnceBackend.Quiescence.NOT_INVOKED,OnceBackend.Memory.NOT_ALLOCATED)));
  assertFalse(LocalCardMockRuntime.cleanupConfirmed(result(OnceBackend.Phase.CANCELLED,OnceBackend.Cleanup.NOT_NEEDED,OnceBackend.Quiescence.UNCONFIRMED,OnceBackend.Memory.NOT_ALLOCATED)));
 }
 @Test public void queuedOrIdleCannotBeReleasedFromNonInvocationAlone(){
  for(var phase:new OnceBackend.Phase[]{OnceBackend.Phase.IDLE,OnceBackend.Phase.QUEUED,OnceBackend.Phase.CLEANUP_UNCONFIRMED})
   assertFalse(LocalCardMockRuntime.cleanupConfirmed(result(phase,OnceBackend.Cleanup.NOT_NEEDED,OnceBackend.Quiescence.NOT_INVOKED,OnceBackend.Memory.NOT_ALLOCATED)));
 }
 @Test public void nullOrUnknownCleanupCannotProveRelease(){
  assertFalse(LocalCardMockRuntime.cleanupConfirmed(null));
  assertFalse(LocalCardMockRuntime.cleanupConfirmed(result(OnceBackend.Phase.FAILED,OnceBackend.Cleanup.UNCONFIRMED,OnceBackend.Quiescence.UNCONFIRMED,OnceBackend.Memory.RETAINED)));
 }
}
