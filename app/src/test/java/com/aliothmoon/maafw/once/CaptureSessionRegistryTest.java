package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
public final class CaptureSessionRegistryTest {
 @Test public void deadlineLatchesBeforeQueuedOwnerCleanupAndHoldsUnknown() {
  var registry=new CaptureSessionRegistry();var epoch=new RealCaptureClaim.ProcessEpoch();
  var cancelled=new AtomicBoolean();var closed=new AtomicBoolean();var queue=new ArrayList<Runnable>();var timer=new AtomicReference<Runnable>();
  var entry=registry.retain(new Object(),epoch,100,()->cancelled.set(true),()->{},closed::get,r->{queue.add(r);return true;});
  registry.arm(entry,(d,r)->{assertEquals(100,d);timer.set(r);},()->100);
  timer.get().run();assertTrue(cancelled.get());assertTrue(registry.retaining());assertEquals(1,queue.size());
  queue.remove(0).run();assertTrue(registry.retaining());
  assertThrows(IllegalStateException.class,()->registry.retain(new Object(),epoch,200,()->{},()->{},()->true,r->true));
  closed.set(true);registry.releaseIfClosed(entry);assertFalse(registry.retaining());
 }
 @Test public void staleTimerCannotCancelNewEpoch() {
  var r=new CaptureSessionRegistry();var events=new ArrayList<String>();
  var old=r.retain(new Object(),new RealCaptureClaim.ProcessEpoch(),1,()->events.add("old"),()->{},()->true,RunnableDispatcher.INSTANCE);
  r.releaseIfClosed(old);
  r.retain(new Object(),new RealCaptureClaim.ProcessEpoch(),2,()->events.add("new"),()->{},()->false,RunnableDispatcher.INSTANCE);
  r.request(old);assertTrue(events.isEmpty());assertTrue(r.retaining());
 }
 @Test public void rejectedDispatchRetainsAndCancellationIsImmediate() {
  var r=new CaptureSessionRegistry();var flag=new AtomicBoolean();
  var e=r.retain(new Object(),new RealCaptureClaim.ProcessEpoch(),1,()->flag.set(true),()->fail(),()->false,t->false);
  r.request(e);assertTrue(flag.get());assertTrue(r.retaining());assertTrue(r.unknownQueue());r.request(e);
 }
 @Test public void duplicateTimerQueuesCleanupOnlyOnce() {
  var r=new CaptureSessionRegistry();List<Runnable> queue=new ArrayList<>();
  var e=r.retain(new Object(),new RealCaptureClaim.ProcessEpoch(),1,()->{},()->{},()->false,t->{queue.add(t);return true;});
  r.request(e);r.request(e);assertEquals(1,queue.size());
 }
 enum RunnableDispatcher implements CaptureSessionRegistry.Dispatcher { INSTANCE; public boolean post(Runnable r){r.run();return true;} }
}
