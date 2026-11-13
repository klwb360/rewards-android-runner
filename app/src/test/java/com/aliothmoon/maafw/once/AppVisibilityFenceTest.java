package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
public class AppVisibilityFenceTest {
 @Test public void visibleRefusedAndHiddenAccepted(){AppVisibilityFence f=new AppVisibilityFence();Object a=new Object();f.enter(a);assertNull(f.lease(()->{}));f.stopped(a);assertNotNull(f.lease(()->{}));}
 @Test public void shortOtherUiCannotReviveOldLease(){AppVisibilityFence f=new AppVisibilityFence();Object a=new Object(),b=new Object();f.enter(a);f.stopped(a);int[] revoked={0};var l=f.lease(()->revoked[0]++);f.enter(b);f.stopped(b);assertFalse(f.valid(l));assertEquals(1,revoked[0]);assertFalse(f.publish(l,()->fail()));}
 @Test public void unknownAndLateStopRemainRejected(){AppVisibilityFence f=new AppVisibilityFence();f.enter("a");f.stopped("a");var l=f.lease(()->{});f.stopped("unknown");assertFalse(f.valid(l));assertNull(f.lease(()->{}));}
 @Test public void processFailureNeverMeansQuiet(){AppVisibilityFence f=new AppVisibilityFence();f.enter("a");f.stopped("a");var l=f.lease(()->{});f.unknown();f.stopped("a");assertFalse(f.valid(l));assertNull(f.lease(()->{}));}
 @Test public void publicationSerializedWithReentry(){AppVisibilityFence f=new AppVisibilityFence();f.enter("a");f.stopped("a");var l=f.lease(()->{});int[] n={0};assertTrue(f.publish(l,()->n[0]++));f.enter("a");assertFalse(f.publish(l,()->n[0]++));assertEquals(1,n[0]);}
 @Test public void uiEnterDirectlyRevokesBackendCommit(){AppVisibilityFence f=new AppVisibilityFence();var backend=new BackendPorts.RevocationFence();f.enter("a");f.stopped("a");var l=f.lease(backend::revoke);f.enter("b");f.stopped("b");assertTrue(backend.observed());assertFalse(backend.tryCommit());assertFalse(f.valid(l));}
 @Test public void enterAfterBackendCommitStillSuppressesUi(){AppVisibilityFence f=new AppVisibilityFence();var backend=new BackendPorts.RevocationFence();f.enter("a");f.stopped("a");var l=f.lease(backend::revoke);assertTrue(backend.tryCommit());f.enter("b");assertTrue(backend.observed());assertFalse(f.publish(l,()->fail()));}
}

