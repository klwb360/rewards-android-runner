package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.ForegroundPhaseSlots.Phase.*;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Real coordinator + shared process epoch + production fixed-slot gate.
 * OS lifecycle events are fake; finishForeground is explicitly implemented and
 * must receive the matching destruction event, unlike the old no-op fake. */
public final class TwoPhaseForegroundAdmissionTest {
    static final class Process {
        final RealCaptureClaim.ProcessEpoch epoch=new RealCaptureClaim.ProcessEpoch();
        final ForegroundPhaseSlots<Object> slots=new ForegroundPhaseSlots<>();
        final AtomicLong now=new AtomicLong(100);
        final Set<ForegroundPhaseSlots.Phase> burned=new HashSet<>();
        int starts,acquires,stops;
        Run run(ForegroundPhaseSlots.Phase phase){return new Run(this,phase);}
    }
    static final class Run implements CaptureAdmissionCoordinator.Ports<Object> {
        final Process process;final ForegroundPhaseSlots.Phase phase;final Object service=new Object(),projection=new Object();
        final CaptureAdmissionCoordinator<Object> core;
        ForegroundPhaseSlots.Lease<Object> lease;CaptureAdmissionCoordinator.Request request;
        boolean noAck,wrongAck,failDispatch;
        Run(Process process,ForegroundPhaseSlots.Phase phase){
            this.process=process;this.phase=phase;Object owner=new Object();
            var grant=RealCaptureClaim.fixture(process.epoch,owner,process.now.get(),1000,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
            core=CaptureAdmissionCoordinator.fixture(process.epoch,owner,grant,(id,metadata)->{
                if(!process.burned.add(phase))throw new IOException("per-phase tombstone already burned");
            },process.now::get,this);
        }
        public void launchConsent(CaptureAdmissionCoordinator.Ticket ticket,CaptureAdmissionCoordinator.Request request){this.request=request;}
        public void startForeground(CaptureAdmissionCoordinator.Ticket ticket){
            lease=process.slots.reserve(phase,ticket);process.slots.beginDispatch(lease);process.starts++;
            if(failDispatch){process.slots.unknown(lease);throw new IllegalStateException("fake may-have-dispatched error");}
            process.slots.dispatchReturned(lease);
        }
        public Object acquire(CaptureAdmissionCoordinator.Ticket ticket,Object consent){process.acquires++;return projection;}
        public void stop(CaptureAdmissionCoordinator.Ticket ticket,Object projection){assertSame(this.projection,projection);process.stops++;}
        public void finishForeground(CaptureAdmissionCoordinator.Ticket ticket)throws Exception{
            if(lease==null)return;process.slots.requestClose(lease);
            if(!noAck){Thread callback=new Thread(()->process.slots.destroyed(lease,wrongAck?new Object():service));callback.start();callback.join();}
            if(!process.slots.awaitClosed(lease,0))throw new IOException("actual destroy ACK missing");
        }
        void begin(){assertNotNull(core.begin());assertTrue(core.consentResult(request,true,new Object()));}
        void deliver(){assertSame(lease,process.slots.deliver(phase,service));core.foregroundReady(service);}
    }
    @Test public void sharedProcessBeforeDestroyAfterUsesTwoNewClaimsAndOneStartPerPhase(){
        Process p=new Process();Run before=p.run(BEFORE);before.begin();before.deliver();before.core.close();
        assertEquals(CaptureAdmissionCoordinator.State.CLOSED,before.core.state());assertFalse(p.epoch.retaining());assertFalse(p.slots.retaining());
        p.now.incrementAndGet();Run after=p.run(AFTER);after.begin();after.deliver();after.core.close();
        assertEquals(CaptureAdmissionCoordinator.State.CLOSED,after.core.state());assertFalse(p.epoch.retaining());assertFalse(p.slots.retaining());
        assertEquals(2,p.starts);assertEquals(2,p.acquires);assertEquals(2,p.stops);assertEquals(Set.of(BEFORE,AFTER),p.burned);
        Run replay=p.run(BEFORE);assertNull(replay.core.begin());assertEquals(CaptureAdmissionCoordinator.State.UNKNOWN,replay.core.state());
        assertEquals(2,p.starts);assertTrue(p.slots.spent(BEFORE));assertTrue(p.slots.spent(AFTER));
    }
    @Test public void stopRequestWithoutExactDestroyKeepsEpochAndPreventsAfter(){
        for(boolean wrong:new boolean[]{false,true}){
            Process p=new Process();Run before=p.run(BEFORE);before.begin();before.deliver();before.noAck=!wrong;before.wrongAck=wrong;before.core.close();
            assertEquals(CaptureAdmissionCoordinator.State.UNKNOWN,before.core.state());assertTrue(p.epoch.retaining());assertTrue(p.slots.retaining());
            Run after=p.run(AFTER);assertNull(after.core.begin());assertEquals(CaptureAdmissionCoordinator.State.DENIED,after.core.state());assertEquals(1,p.starts);
            p.slots.destroyed(before.lease,before.service);assertTrue(p.slots.retaining());assertTrue(p.epoch.retaining());
        }
    }
    @Test public void mayHaveDispatchedFailureIsNotRetriedOrReclassified(){
        Process p=new Process();Run before=p.run(BEFORE);before.failDispatch=true;assertNotNull(before.core.begin());
        assertFalse(before.core.consentResult(before.request,true,new Object()));
        assertSame(before.lease,p.slots.deliver(BEFORE,before.service));before.core.close();
        assertEquals(CaptureAdmissionCoordinator.State.UNKNOWN,before.core.state());assertTrue(p.epoch.retaining());assertTrue(p.slots.retaining());
        assertFalse(before.core.consentResult(before.request,true,new Object()));assertEquals(1,p.starts);assertEquals(0,p.acquires);
    }
    @Test public void cancellationBeforeFgsReadyNeverAcquiresAndStillNeedsDestroy(){
        Process p=new Process();Run before=p.run(BEFORE);before.begin();before.core.cancel();before.deliver();before.core.close();
        assertEquals(0,p.acquires);assertEquals(0,p.stops);assertEquals(CaptureAdmissionCoordinator.State.CLOSED,before.core.state());
        assertFalse(p.slots.retaining());assertFalse(p.epoch.retaining());
    }
    @Test public void expiredConsentNeverDispatchesOrUsesAServiceSlot(){
        Process p=new Process();Run before=p.run(BEFORE);assertNotNull(before.core.begin());p.now.set(1100);
        assertFalse(before.core.consentResult(before.request,true,new Object()));before.core.close();
        assertEquals(0,p.starts);assertEquals(0,p.acquires);assertFalse(p.slots.spent(BEFORE));assertFalse(p.epoch.retaining());assertTrue(p.burned.contains(BEFORE));
    }
    @Test public void lateBeforeEventsCannotAffectAfterEpochOrTuple(){
        Process p=new Process();Run before=p.run(BEFORE);before.begin();before.deliver();before.core.close();
        Run after=p.run(AFTER);after.begin();after.deliver();
        assertNull(p.slots.deliver(BEFORE,new Object()));assertFalse(p.slots.destroyed(before.lease,before.service));
        before.core.serviceDestroyed(before.service);before.core.close();assertTrue(p.epoch.retaining());assertTrue(p.slots.retaining());
        assertEquals(CaptureAdmissionCoordinator.State.ACQUIRED,after.core.state());after.core.close();assertFalse(p.epoch.retaining());
    }
    @Test public void missingDeliveryCannotUseStopReturnAsDestruction(){
        Process p=new Process();Run before=p.run(BEFORE);before.begin();before.core.close();
        assertEquals(CaptureAdmissionCoordinator.State.UNKNOWN,before.core.state());assertTrue(p.epoch.retaining());assertTrue(p.slots.retaining());assertEquals(0,p.acquires);
    }
}
