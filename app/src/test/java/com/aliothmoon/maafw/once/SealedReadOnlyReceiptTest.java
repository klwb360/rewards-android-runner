package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;
public class SealedReadOnlyReceiptTest {
 private AppVisibilityFence fence(){AppVisibilityFence f=new AppVisibilityFence();f.enter("a");f.stopped("a");return f;}
 @Test public void precommitReentryRejects(){var f=fence();var lease=f.lease(()->{});var r=new SealedReadOnlyReceipt();f.enter("b");assertFalse(r.commit(f,lease,true,"safe scalars"));assertNull(r.history());}
 @Test public void postcommitReentryKeepsHistoryButNotAuthority(){var f=fence();var lease=f.lease(()->{});var r=new SealedReadOnlyReceipt();assertTrue(r.commit(f,lease,true,"safe scalars"));f.enter("b");assertFalse(f.valid(lease));assertEquals("safe scalars",r.history());assertFalse(r.commit(f,lease,true,"replay"));}
 @Test public void unknownHiddenAndSizeReject(){var f=fence();var lease=f.lease(()->{});var r=new SealedReadOnlyReceipt();assertFalse(r.commit(f,lease,false,"x"));assertFalse(r.commit(f,lease,true,"x".repeat(8193)));f.unknown();assertFalse(r.commit(f,lease,true,"x"));assertNull(r.history());}
 @Test public void historyNeverReplacedOrRestored(){var f=fence();var lease=f.lease(()->{});var r=new SealedReadOnlyReceipt();assertTrue(r.commit(f,lease,true,"first"));r.commit(f,lease,true,"second");assertEquals("first",r.history());assertNull(new SealedReadOnlyReceipt().history());}
 @Test public void diagnosticsOnlyReceiptUsesSameFinalFence(){
  var f=fence();var lease=f.lease(()->{});var d=new PerceptionDiagnostics();d.frame(400,800);
  String scalar=d.published(true,true,false);assertNotNull(scalar);var r=new SealedReadOnlyReceipt();
  assertFalse(r.commit(f,lease,false,scalar));f.enter("reentered");assertFalse(r.commit(f,lease,true,scalar));assertNull(r.history());
 }
}
