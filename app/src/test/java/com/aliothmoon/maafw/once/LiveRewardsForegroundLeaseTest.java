package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Deterministic service receipts only; no Android service, permission or task input is created. */
public class LiveRewardsForegroundLeaseTest {
    private static final String RUN="11111111-1111-1111-1111-111111111111",TOKEN="22222222-2222-2222-2222-222222222222";
    private static final class Clock implements LiveRewardsForegroundLease.Clock {
        long now=100;String day="2026-11-17";
        public long elapsedRealtime(){return now;}public String day(){return day;}
    }
    private static final class Rig {
        final Clock clock=new Clock();final Object owner=new Object(),service=new Object();
        final AtomicInteger ready=new AtomicInteger(),revoked=new AtomicInteger(),destroyed=new AtomicInteger();
        final LiveRewardsForegroundLease lease=new LiveRewardsForegroundLease(owner,RUN,TOKEN,100,1_800_100,"2026-11-17",clock,
                ready::incrementAndGet,revoked::incrementAndGet,destroyed::incrementAndGet);
        void dispatch(){assertTrue(lease.beginDispatch(owner));lease.dispatchReturned(owner);}
        void start(){dispatch();assertTrue(lease.deliver(service));assertTrue(lease.foregroundReturned(service));}
    }
    @Test public void startReturnAndServiceDeliveryNeverImpersonateForegroundReady(){
        var r=new Rig();r.dispatch();assertTrue(r.lease.startReturned());assertFalse(r.lease.ready());assertEquals(0,r.ready.get());
        assertTrue(r.lease.deliver(r.service));assertFalse(r.lease.ready());assertEquals(0,r.ready.get());
        r.clock.now=200;assertTrue(r.lease.foregroundReturned(r.service));assertTrue(r.lease.ready());assertEquals(200,r.lease.readyElapsed());assertEquals(1,r.ready.get());
    }
    @Test public void readyAcknowledgementUsesOneTimestampAndDoesNotRenewOriginalBudget(){
        var calls=new AtomicInteger();Object owner=new Object(),service=new Object();
        LiveRewardsForegroundLease.Clock clock=new LiveRewardsForegroundLease.Clock(){
            public long elapsedRealtime(){return calls.getAndIncrement()<2?100:5099;}public String day(){return "2026-11-17";}
        };
        var lease=new LiveRewardsForegroundLease(owner,RUN,TOKEN,100,1_800_100,"2026-11-17",clock,()->{},()->{},()->{});
        assertTrue(lease.beginDispatch(owner));assertTrue(lease.deliver(service));assertTrue(lease.foregroundReturned(service));
        assertEquals(3,calls.get());assertEquals(5099,lease.readyElapsed());assertEquals(5100,lease.startupDeadline);assertEquals(1_800_100,lease.deadline);
    }
    @Test public void startupDeadlineRejectsLateDeliveryAndLateReadyWithoutOpeningAuthority(){
        var r=new Rig();r.dispatch();r.clock.now=5100;r.lease.checkDeadline();
        assertEquals(LiveRewardsForegroundLease.Reason.START_TIMEOUT,r.lease.reason());assertEquals(1,r.revoked.get());
        assertFalse(r.lease.deliver(r.service));assertFalse(r.lease.foregroundReturned(r.service));assertEquals(0,r.ready.get());
        r.lease.requestClose(r.owner);assertFalse(r.lease.destroyed());r.lease.serviceDestroyed(r.service);assertTrue(r.lease.destroyed());
        var other=new Rig();other.dispatch();assertTrue(other.lease.deliver(other.service));other.clock.now=5100;
        assertFalse(other.lease.foregroundReturned(other.service));assertEquals(0,other.ready.get());
    }
    @Test public void changingDateAtStartupOrDuringRunRevokesTheSameTuple(){
        var pending=new Rig();pending.clock.day="2026-11-18";assertFalse(pending.lease.beginDispatch(pending.owner));
        assertEquals(LiveRewardsForegroundLease.Reason.DATE_CHANGED,pending.lease.reason());assertEquals(1,pending.revoked.get());
        pending.lease.requestClose(pending.owner);assertTrue(pending.lease.destroyed());assertFalse(pending.lease.destroyObserved());assertEquals(-1,pending.lease.destroyedElapsed());
        var r=new Rig();r.start();r.clock.day="2026-11-18";assertFalse(r.lease.ready());assertEquals(1,r.revoked.get());
        assertEquals(LiveRewardsForegroundLease.Reason.DATE_CHANGED,r.lease.reason());assertEquals("2026-11-17",r.lease.day);
    }
    @Test public void roundDeadlineRemainsOriginalAfterReadyAndStopDoesNotExtendIt(){
        var r=new Rig();r.start();r.clock.now=1_800_099;assertTrue(r.lease.ready());r.clock.now=1_800_100;assertFalse(r.lease.ready());
        assertEquals(LiveRewardsForegroundLease.Reason.DEADLINE,r.lease.reason());assertEquals(1,r.revoked.get());
        r.lease.requestClose(r.owner);assertEquals(1_800_100,r.lease.deadline);assertFalse(r.lease.destroyed());
    }
    @Test public void cancelStopsEligibilityButKeepsServiceUntilItsExactDestroyReceipt(){
        var r=new Rig();r.start();assertFalse(r.lease.matchesCancel("33333333-3333-3333-3333-333333333333"));assertTrue(r.lease.ready());
        r.lease.cancel(LiveRewardsForegroundLease.Reason.CANCELLED);r.lease.cancel(LiveRewardsForegroundLease.Reason.CANCELLED);
        assertFalse(r.lease.ready());assertEquals(1,r.revoked.get());assertFalse(r.lease.destroyed());
        r.lease.requestClose(r.owner);assertFalse(r.lease.destroyed());r.lease.serviceDestroyed(new Object());assertFalse(r.lease.destroyed());
        r.lease.serviceDestroyed(r.service);assertTrue(r.lease.destroyed());assertEquals(1,r.destroyed.get());
        r.lease.serviceDestroyed(r.service);assertEquals(1,r.destroyed.get());
    }
    @Test public void unexpectedServiceLossRevokesImmediatelyAndNeverRestarts(){
        var r=new Rig();r.start();r.lease.serviceDestroyed(r.service);assertTrue(r.lease.destroyed());assertFalse(r.lease.ready());
        assertEquals(LiveRewardsForegroundLease.Reason.SERVICE_LOST,r.lease.reason());assertEquals(1,r.revoked.get());
        assertFalse(r.lease.deliver(new Object()));assertFalse(r.lease.beginDispatch(r.owner));assertEquals(1,r.ready.get());
    }
    @Test public void normalCloseNeedsOnDestroyAndDoesNotReportServiceLoss(){
        var r=new Rig();r.start();r.lease.requestClose(r.owner);assertFalse(r.lease.ready());assertFalse(r.lease.destroyed());
        assertEquals(0,r.destroyed.get());r.clock.now=300;r.lease.serviceDestroyed(r.service);
        assertTrue(r.lease.destroyed());assertEquals(300,r.lease.destroyedElapsed());assertEquals(1,r.destroyed.get());assertEquals(0,r.revoked.get());
        assertEquals(LiveRewardsForegroundLease.Reason.NONE,r.lease.reason());
    }
    @Test public void failedStopCannotBecomeAFalseDestroyAcknowledgement(){
        var r=new Rig();r.start();r.lease.requestClose(r.owner);r.lease.stopUnknown();
        assertEquals(LiveRewardsForegroundLease.State.UNKNOWN,r.lease.state());assertFalse(r.lease.destroyed());assertFalse(r.lease.ready());
        assertEquals(1,r.revoked.get());r.lease.serviceDestroyed(r.service);assertTrue(r.lease.destroyed());
        assertEquals(LiveRewardsForegroundLease.Reason.STOP_UNKNOWN,r.lease.reason());
    }
    @Test public void duplicatesAndWrongIdentityCannotAdoptOrReplaceATuple(){
        var r=new Rig();assertFalse(r.lease.beginDispatch(new Object()));r.dispatch();assertFalse(r.lease.beginDispatch(r.owner));
        assertTrue(r.lease.deliver(r.service));assertFalse(r.lease.deliver(new Object()));assertFalse(r.lease.foregroundReturned(new Object()));
        assertTrue(r.lease.foregroundReturned(r.service));assertFalse(r.lease.foregroundReturned(r.service));assertEquals(1,r.ready.get());
        assertThrows(SecurityException.class,()->r.lease.requestClose(new Object()));assertTrue(r.lease.ready());
        r.clock.now=6000;assertFalse(r.lease.beginDispatch(r.owner));assertFalse(r.lease.foregroundReturned(r.service));
        assertFalse(r.lease.foregroundReturned(new Object()));assertTrue(r.lease.ready());assertEquals(0,r.revoked.get());
    }
    @Test public void staleCancelAndUnknownStartsCannotAdoptANewPendingOwner(){
        for(boolean pending:new boolean[]{false,true}){
            assertEquals(LiveRewardsForegroundLease.Delivery.REJECT_UNOWNED,LiveRewardsForegroundLease.delivery(false,pending,false,true,false));
            assertEquals(LiveRewardsForegroundLease.Delivery.REJECT_UNOWNED,LiveRewardsForegroundLease.delivery(false,pending,false,false,false));
        }
        assertEquals(LiveRewardsForegroundLease.Delivery.START_PENDING,LiveRewardsForegroundLease.delivery(false,true,true,false,false));
        assertEquals(LiveRewardsForegroundLease.Delivery.REJECT_UNOWNED,LiveRewardsForegroundLease.delivery(false,false,true,false,false));
        assertEquals(LiveRewardsForegroundLease.Delivery.IGNORE,LiveRewardsForegroundLease.delivery(true,true,false,true,false));
        assertEquals(LiveRewardsForegroundLease.Delivery.CANCEL_OWNED,LiveRewardsForegroundLease.delivery(true,true,false,true,true));
        var old=new Rig();old.start();old.lease.requestClose(old.owner);old.lease.serviceDestroyed(old.service);
        var fresh=new LiveRewardsForegroundLease(new Object(),RUN,"33333333-3333-3333-3333-333333333333",100,1_800_100,"2026-11-17",old.clock,()->{},()->{},()->{});
        assertFalse(fresh.matchesCancel(TOKEN));assertFalse(old.lease.matchesCancel(TOKEN));
    }
    @Test public void lifecycleCallbacksExecuteOutsideLeaseMonitor(){
        var clock=new Clock();Object owner=new Object(),service=new Object();LiveRewardsForegroundLease[] exact={null};AtomicInteger callbacks=new AtomicInteger();
        Runnable callback=()->{assertFalse(Thread.holdsLock(exact[0]));callbacks.incrementAndGet();};
        exact[0]=new LiveRewardsForegroundLease(owner,RUN,TOKEN,100,1_800_100,"2026-11-17",clock,callback,callback,callback);
        assertTrue(exact[0].beginDispatch(owner));assertTrue(exact[0].deliver(service));assertTrue(exact[0].foregroundReturned(service));
        exact[0].cancel(LiveRewardsForegroundLease.Reason.CANCELLED);exact[0].requestClose(owner);exact[0].serviceDestroyed(service);assertEquals(3,callbacks.get());
    }
}
