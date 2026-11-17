package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Synthetic capability and journal tests. No Android service, metadata process or input is invoked. */
public final class LiveRewardsReadOnlyTest {
    private static final class Rig {
        final LiveCoreFixture f;final LiveRunAuthority run;
        Rig()throws Exception{
            f=new LiveCoreFixture();run=LiveRunAuthority.syntheticReadOnly(new Object(),new RealCaptureClaim.ProcessEpoch(),f.clock,()->true);
            run.attachLedger(f.ledger);
        }
        LiveRewardsTypes.Task task(){return LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"READ_ONLY_TASK");}
    }
    private static Map<String,String> snapshot(LiveRewardsLedger ledger)throws Exception{
        var store=LiveRewardsLedger.class.getDeclaredField("store");store.setAccessible(true);Object memory=store.get(ledger);
        var entries=memory.getClass().getDeclaredField("entries");entries.setAccessible(true);
        Object value=entries.get(memory);assertTrue(value instanceof Map<?,?>);Map<String,String> snapshot=new TreeMap<>();
        for(var entry:((Map<?,?>)value).entrySet()){
            assertTrue(entry.getKey() instanceof String);assertTrue(entry.getValue() instanceof String);
            snapshot.put((String)entry.getKey(),(String)entry.getValue());
        }
        return snapshot;
    }
    @Test public void theVisiblePurposeIsImmutableAndOldFixturesRemainTaskCapable()throws Exception{
        Object owner=new Object();var epoch=new RealCaptureClaim.ProcessEpoch();
        var live=VisibleCaptureAuthority.fixtureLiveRewardsApproval(epoch,owner,100);
        var read=VisibleCaptureAuthority.fixtureReadOnlyRewardsApproval(epoch,owner,100);
        assertTrue(live.taskInputAllowed);assertFalse(read.taskInputAllowed);assertFalse(read.live);
        assertTrue(read.consume(100));assertFalse(read.consume(101));
        var expired=VisibleCaptureAuthority.fixtureReadOnlyRewardsApproval(epoch,owner,100);
        assertFalse(expired.consume(expired.deadline));assertFalse(expired.consume(100));
        assertTrue(Modifier.isFinal(LiveRunAuthority.class.getDeclaredField("taskInputAllowed").getModifiers()));
        assertTrue(Modifier.isFinal(VisibleCaptureAuthority.LiveRewardsApproval.class.getDeclaredField("taskInputAllowed").getModifiers()));
        assertTrue(new LiveCoreFixture().authority.taskInputAllowed());assertFalse(new Rig().run.taskInputAllowed());
    }
    @Test public void readOnlyRunsCannotAllocateAnyBusinessTaskScopeOrPendingRecord()throws Exception{
        var r=new Rig();var before=snapshot(r.f.ledger);
        for(var kind:LiveRewardsTypes.Kind.values()){
            var task=LiveCoreFixture.task(kind,kind==LiveRewardsTypes.Kind.CHECK_IN?0:1,"TASK_"+kind);
            assertThrows(SecurityException.class,()->r.run.cardScope(task));assertFalse(r.f.ledger.attempted(task));
        }
        assertEquals(0,r.run.taskScopeCount());assertEquals(before,snapshot(r.f.ledger));
    }
    @Test public void existingNativePreflightAndBoundedScrollCapabilitiesRemainAvailable()throws Exception{
        var r=new Rig();var task=r.task();var scope=r.run.preflightScope(task);
        var observation=r.f.seal(scope,LiveRewardsTypes.Phase.BEFORE,task,0,100);
        assertTrue(observation.qualified());assertTrue(scope.preflight());assertEquals(0,r.run.taskScopeCount());
        assertThrows(SecurityException.class,()->scope.actionPermit(observation,r.f.ledger));assertFalse(r.f.ledger.attempted(task));
        var scan=r.run.scanScope(LiveRewardsTypes.Kind.MORE);var ui=r.f.ui(scan);
        var scroll=scan.claimScroll(ui,ReviewShizukuSwipeActor.Direction.UP,r.f.clock.now);
        assertTrue(scroll.claimActor(r.f.clock.now));assertTrue(scroll.consume(r.f.clock.now));assertTrue(scroll.dispatchValid(r.f.clock.now));
        assertFalse(scroll.consume(r.f.clock.now));assertFalse(r.run.taskInputAllowed());
    }
    @Test public void directPermitCreationCannotBypassTheReadOnlyRunEvenWithASealedTaskFrame()throws Exception{
        var r=new Rig();var task=r.task();
        var constructor=LiveRunAuthority.CardScope.class.getDeclaredConstructor(LiveRunAuthority.class,LiveRewardsTypes.Task.class,
                boolean.class,boolean.class,int.class,long.class);constructor.setAccessible(true);
        var forgedPath=constructor.newInstance(r.run,task,false,false,1,r.f.clock.now);
        var frame=r.f.seal(forgedPath,LiveRewardsTypes.Phase.BEFORE,task,0,100);
        assertTrue(frame.qualified());
        assertThrows(SecurityException.class,()->LiveActionPermit.create(forgedPath,frame,r.f.ledger));
        assertFalse(r.f.ledger.attempted(task));assertEquals(0,r.run.taskScopeCount());
    }
    @Test public void inputFactoryRejectsReadOnlyBeforeTouchingAnyInputBridge()throws Exception{
        var r=new Rig();var calls=new AtomicInteger();
        var ports=(LiveRewardsInput.Ports)Proxy.newProxyInstance(LiveRewardsInput.Ports.class.getClassLoader(),
                new Class<?>[]{LiveRewardsInput.Ports.class},(proxy,method,args)->{calls.incrementAndGet();throw new AssertionError("No input bridge may be queried");});
        assertThrows(SecurityException.class,()->LiveRewardsInput.synthetic(r.run,ports));assertEquals(0,calls.get());
    }
    @Test public void sealingDiagnosticsRevokesOrdinaryScopesWithoutExtendingTheRunLifetime()throws Exception{
        var r=new Rig();var scope=r.run.preflightScope(r.task());var ui=r.f.ui(scope);long original=r.run.deadline();
        var diagnostic=r.run.claimFocusDiagnostics(r.f.clock.now);
        assertTrue(r.run.current());assertTrue(r.run.diagnosticOnly());assertEquals(original,diagnostic.deadline());
        assertFalse(scope.current(r.f.clock.now));assertFalse(ui.current(r.f.clock.now));
        assertThrows(SecurityException.class,()->scope.claimUi(new Object(),r.f.clock.now));
        assertThrows(SecurityException.class,()->r.run.preflightScope(r.task()));
        assertThrows(SecurityException.class,()->r.run.scanScope(LiveRewardsTypes.Kind.MORE));
        assertThrows(SecurityException.class,()->r.run.bindTemplate(r.f.source(),r.f.clock.now));
        assertThrows(SecurityException.class,()->r.run.claimFocusDiagnostics(r.f.clock.now));
    }
    @Test public void diagnosticPairsHaveIndependentOneShotSlotsAndOriginalMinimumDueTimes()throws Exception{
        var r=new Rig();long start=r.f.clock.now;var diagnostic=r.run.claimFocusDiagnostics(start);
        for(int i=0;i<3;i++){
            long due=start+new long[]{0,750,2000}[i];assertEquals(due,diagnostic.nextDueElapsed());
            if(i>0){r.f.clock.now=due-1;assertFalse(diagnostic.claimPair(r.f.clock.now));}
            r.f.clock.now=due;assertTrue(diagnostic.claimPair(r.f.clock.now));assertTrue(diagnostic.dispatchValid(r.f.clock.now));
            assertFalse(diagnostic.claimPair(r.f.clock.now));assertEquals(i+1,diagnostic.pairCount());diagnostic.finishPair(true,true);
        }
        assertFalse(diagnostic.current());assertFalse(diagnostic.claimPair(r.f.clock.now));assertTrue(r.run.current());
        var entries=snapshot(r.f.ledger);long slots=entries.keySet().stream().filter(path->path.contains("/reads/FOCUS_DIAGNOSTIC_")).count();
        assertEquals(3,slots);assertFalse(r.f.ledger.attempted(r.task()));
    }
    @Test public void aDiagnosticBurnFailureCannotBeReplayedOrBecomeDispatchable()throws Exception{
        var r=new Rig();r.f.ledger.recordRead("FOCUS_DIAGNOSTIC_1");var diagnostic=r.run.claimFocusDiagnostics(r.f.clock.now);
        assertThrows(IOException.class,()->diagnostic.claimPair(r.f.clock.now));assertFalse(diagnostic.dispatchValid(r.f.clock.now));
        assertFalse(diagnostic.claimPair(r.f.clock.now));assertFalse(r.run.current());assertEquals(1,diagnostic.pairCount());
    }
    @Test public void unknownCleanupCancellationAndDayChangeStopFurtherDiagnosticReads()throws Exception{
        var unknown=new Rig();var a=unknown.run.claimFocusDiagnostics(unknown.f.clock.now);assertTrue(a.claimPair(unknown.f.clock.now));
        a.finishPair(false,false);assertFalse(a.current());assertFalse(unknown.run.current());
        var cleanFailure=new Rig();var stopped=cleanFailure.run.claimFocusDiagnostics(cleanFailure.f.clock.now);assertTrue(stopped.claimPair(cleanFailure.f.clock.now));
        stopped.finishPair(false,true);cleanFailure.f.clock.now+=750;assertFalse(stopped.claimPair(cleanFailure.f.clock.now));
        assertTrue(cleanFailure.run.current());assertTrue(cleanFailure.run.diagnosticOnly());
        var midnight=new Rig();var b=midnight.run.claimFocusDiagnostics(midnight.f.clock.now);midnight.f.clock.day="2026-11-17";
        assertFalse(b.current());assertFalse(b.claimPair(midnight.f.clock.now));assertEquals(0,b.pairCount());
        var cancelled=new Rig();var c=cancelled.run.claimFocusDiagnostics(cancelled.f.clock.now);cancelled.run.cancel();
        assertFalse(c.current());assertFalse(c.claimPair(cancelled.f.clock.now));
    }
    @Test public void taskCapableRunsCannotObtainTheReadOnlyDiagnosticCapability()throws Exception{
        var f=new LiveCoreFixture();f.attach();assertTrue(f.authority.taskInputAllowed());
        assertThrows(SecurityException.class,()->f.authority.claimFocusDiagnostics(f.clock.now));assertFalse(f.authority.diagnosticOnly());
        assertNotNull(f.authority.cardScope(LiveCoreFixture.task(LiveRewardsTypes.Kind.DAILY,1,"STILL_LIVE")));
    }
    @Test public void diagnosticNamespaceAcceptsOnlyTheThreeExplicitReadMarkers()throws Exception{
        var r=new Rig();for(String invalid:new String[]{"FOCUS_DIAGNOSTIC_0","FOCUS_DIAGNOSTIC_4","FOCUS_DIAGNOSTIC_01","FOCUS_DIAGNOSTIC_1_EXTRA"})
            assertThrows(IOException.class,()->r.f.ledger.recordRead(invalid));
        assertEquals(1,snapshot(r.f.ledger).size());
        for(int i=1;i<=3;i++)r.f.ledger.recordRead("FOCUS_DIAGNOSTIC_"+i);
        assertEquals(4,snapshot(r.f.ledger).size());
    }
    @Test public void newReadOnlyMarkersDoNotRewritePriorTaskAttempts()throws Exception{
        var f=new LiveCoreFixture();var task=LiveCoreFixture.task(LiveRewardsTypes.Kind.CHECK_IN,0,"HISTORY");
        f.ledger.begin(task);f.ledger.burnTap(task);f.ledger.finish(task,LiveRewardsTypes.Outcome.UNCONFIRMED);var old=snapshot(f.ledger);
        var next=f.ledger.syntheticNextRun("00000000-0000-4000-8000-000000000002");
        var read=LiveRunAuthority.syntheticReadOnly(new Object(),new RealCaptureClaim.ProcessEpoch(),f.clock,()->true);read.attachLedger(next);
        var diagnostic=read.claimFocusDiagnostics(f.clock.now);assertTrue(diagnostic.claimPair(f.clock.now));diagnostic.finishPair(true,true);
        assertTrue(next.attempted(task));var after=snapshot(next);
        old.forEach((path,bytes)->assertEquals("Historical scalar bytes remain unchanged",bytes,after.get(path)));
    }
    @Test public void foregroundCleanupRetainsCompletedOrStoppedWorkWithoutACoordinatorSummary(){
        for(String completed:new String[]{"COMPLETE","STOPPED","CANCELED"})
            assertEquals(completed,LiveRewardsRuntime.workStageAfterForeground("FOREGROUND_STOPPING",completed));
        assertEquals("CLEANUP_UNKNOWN",LiveRewardsRuntime.workStageAfterForeground("CLEANUP_UNKNOWN","COMPLETE"));
        assertThrows(IllegalStateException.class,()->LiveRewardsRuntime.workStageAfterForeground("FOREGROUND_STOPPING",null));
        assertThrows(IllegalStateException.class,()->LiveRewardsRuntime.workStageAfterForeground("FOREGROUND_STOPPING","PREFLIGHT"));
    }
}
