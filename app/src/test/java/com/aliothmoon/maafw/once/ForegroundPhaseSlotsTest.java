package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.ForegroundPhaseSlots.Phase.*;

public final class ForegroundPhaseSlotsTest {
    private static ForegroundPhaseSlots.Lease<Object> dispatched(ForegroundPhaseSlots<Object> slots){
        var lease=slots.reserve(BEFORE,new Object());slots.beginDispatch(lease);slots.dispatchReturned(lease);return lease;
    }
    private static void close(ForegroundPhaseSlots<Object> slots,ForegroundPhaseSlots.Lease<Object> lease,Object service)throws Exception{
        assertSame(lease,slots.deliver(lease.phase,service));slots.requestClose(lease);
        assertFalse(slots.isClosed(lease));assertTrue(slots.destroyed(lease,service));assertTrue(slots.awaitClosed(lease,0));
    }
    @Test public void bothFixedPhasesCloseAndStaySpent()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var before=dispatched(slots);close(slots,before,new Object());
        var after=slots.reserve(AFTER,new Object());slots.beginDispatch(after);slots.dispatchReturned(after);close(slots,after,new Object());
        assertFalse(slots.retaining());assertTrue(slots.spent(BEFORE));assertTrue(slots.spent(AFTER));
        assertThrows(ForegroundPhaseSlots.KnownNotDispatched.class,()->slots.reserve(BEFORE,new Object()));
        assertThrows(ForegroundPhaseSlots.KnownNotDispatched.class,()->slots.reserve(AFTER,new Object()));
    }
    @Test public void afterCannotStartBeforePredecessorDestroyed(){
        var slots=new ForegroundPhaseSlots<Object>();assertThrows(ForegroundPhaseSlots.KnownNotDispatched.class,()->slots.reserve(AFTER,new Object()));
        var before=dispatched(slots);slots.deliver(BEFORE,new Object());slots.requestClose(before);
        assertThrows(ForegroundPhaseSlots.KnownNotDispatched.class,()->slots.reserve(AFTER,new Object()));assertTrue(slots.retaining());
    }
    @Test public void wrongComponentAndDuplicateDeliveryDoNotReplaceTuple(){
        var slots=new ForegroundPhaseSlots<Object>();var lease=dispatched(slots);Object service=new Object();
        assertNull(slots.deliver(AFTER,new Object()));assertSame(lease,slots.deliver(BEFORE,service));
        assertNull(slots.deliver(BEFORE,service));assertNull(slots.deliver(BEFORE,new Object()));assertTrue(slots.mayStart(lease,service));
    }
    @Test public void foreignAndStaleDestroyCannotReleaseAfter()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var before=dispatched(slots);Object first=new Object();
        slots.deliver(BEFORE,first);slots.requestClose(before);assertFalse(slots.destroyed(before,new Object()));assertTrue(slots.retaining());
        assertTrue(slots.destroyed(before,first));assertTrue(slots.awaitClosed(before,0));
        var after=slots.reserve(AFTER,new Object());slots.beginDispatch(after);slots.dispatchReturned(after);
        Object second=new Object();slots.deliver(AFTER,second);slots.requestClose(after);
        assertFalse(slots.destroyed(before,first));slots.unknown(before);assertTrue(slots.retaining());
        assertNull(slots.deliver(BEFORE,first));assertFalse(slots.destroyed(after,first));
        assertTrue(slots.destroyed(after,second));assertTrue(slots.awaitClosed(after,0));
    }
    @Test public void knownNotDispatchedCanCloseButCannotReplay()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var lease=slots.reserve(BEFORE,new Object());
        assertNull(slots.deliver(BEFORE,new Object()));slots.requestClose(lease);assertTrue(slots.awaitClosed(lease,0));
        assertNull(slots.payload(lease));assertFalse(slots.retaining());
        assertThrows(ForegroundPhaseSlots.KnownNotDispatched.class,()->slots.reserve(BEFORE,new Object()));
    }
    @Test public void dispatchExceptionNeverBecomesCleanEvenAfterLateDestroy()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var lease=slots.reserve(BEFORE,new Object());slots.beginDispatch(lease);slots.unknown(lease);
        Object service=new Object();assertSame(lease,slots.deliver(BEFORE,service));assertFalse(slots.mayStart(lease,service));
        slots.requestClose(lease);assertTrue(slots.destroyed(lease,service));assertFalse(slots.awaitClosed(lease,0));assertTrue(slots.retaining());
        assertThrows(ForegroundPhaseSlots.KnownNotDispatched.class,()->slots.reserve(AFTER,new Object()));
    }
    @Test public void stopReturnWithoutDestroyTimesOutAndLateAckCannotReopen()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var lease=dispatched(slots);Object service=new Object();slots.deliver(BEFORE,service);slots.requestClose(lease);
        assertFalse(slots.awaitClosed(lease,0));assertTrue(slots.destroyed(lease,service));assertFalse(slots.isClosed(lease));assertTrue(slots.retaining());
        assertThrows(ForegroundPhaseSlots.KnownNotDispatched.class,()->slots.reserve(AFTER,new Object()));
    }
    @Test public void pendingDeliveryTimeoutIsNotClean()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var lease=dispatched(slots);slots.requestClose(lease);
        assertFalse(slots.awaitClosed(lease,0));Object late=new Object();assertSame(lease,slots.deliver(BEFORE,late));
        assertFalse(slots.mayStart(lease,late));slots.destroyed(lease,late);assertTrue(slots.retaining());
    }
    @Test public void closeRejectsStartButLateDeliveryCanAcknowledgeBeforeTimeout()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var lease=dispatched(slots);slots.requestClose(lease);Object service=new Object();
        assertSame(lease,slots.deliver(BEFORE,service));assertFalse(slots.mayStart(lease,service));
        slots.destroyed(lease,service);assertTrue(slots.awaitClosed(lease,0));
    }
    @Test public void destroyBeforeStartCallReturnsDoesNotReleaseInFlightDispatch()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var lease=slots.reserve(BEFORE,new Object());slots.beginDispatch(lease);
        Object service=new Object();slots.deliver(BEFORE,service);slots.requestClose(lease);slots.destroyed(lease,service);
        assertFalse(slots.isClosed(lease));assertTrue(slots.retaining());
        slots.dispatchReturned(lease);assertTrue(slots.awaitClosed(lease,0));
    }
    @Test public void unexpectedDestroyStillRequiresOwnerClose()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var lease=dispatched(slots);Object service=new Object();slots.deliver(BEFORE,service);slots.destroyed(lease,service);
        assertFalse(slots.isClosed(lease));slots.requestClose(lease);assertTrue(slots.awaitClosed(lease,0));
    }
    @Test public void closeFailureCannotBeConvertedByActualDestroy()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var lease=dispatched(slots);Object service=new Object();slots.deliver(BEFORE,service);slots.requestClose(lease);
        slots.unknown(lease);slots.destroyed(lease,service);assertFalse(slots.awaitClosed(lease,0));assertTrue(slots.retaining());
    }
    @Test public void wrongBrokerCannotAcknowledgeTuple(){
        var a=new ForegroundPhaseSlots<Object>();var b=new ForegroundPhaseSlots<Object>();var lease=dispatched(a);
        assertThrows(SecurityException.class,()->b.destroyed(lease,new Object()));assertTrue(a.retaining());
    }
    @Test public void interruptedWaitQuarantinesAndRestoresTestThread()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var lease=dispatched(slots);slots.requestClose(lease);Thread.currentThread().interrupt();
        try{assertThrows(InterruptedException.class,()->slots.awaitClosed(lease,100));assertTrue(slots.retaining());assertFalse(slots.awaitClosed(lease,0));}
        finally{Thread.interrupted();}
    }
    @Test public void exactCallbackSignalsBlockedOwnerWithoutOwnerQueue()throws Exception{
        var slots=new ForegroundPhaseSlots<Object>();var lease=dispatched(slots);Object service=new Object();slots.deliver(BEFORE,service);slots.requestClose(lease);
        Thread callback=new Thread(()->slots.destroyed(lease,service));callback.start();assertTrue(slots.awaitClosed(lease,1000));callback.join();
    }
}
