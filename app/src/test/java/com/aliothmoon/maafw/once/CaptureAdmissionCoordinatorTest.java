package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Pure inputs and fake Android boundaries. No device capture or account access. */
public final class CaptureAdmissionCoordinatorTest {
    static final class Fake implements CaptureAdmissionCoordinator.Ports<Object> {
        final List<String> calls=new ArrayList<>();
        CaptureAdmissionCoordinator.Request request;
        CaptureAdmissionCoordinator.Ticket ticket;
        Runnable acquiring=()->{};
        boolean failStart, failAcquire, failStop, nullAcquire;
        final Object projection=new Object();
        public void launchConsent(CaptureAdmissionCoordinator.Ticket t,CaptureAdmissionCoordinator.Request r) { calls.add("consent");ticket=t;request=r; }
        public void startForeground(CaptureAdmissionCoordinator.Ticket t) throws IOException { assertSame(ticket,t);calls.add("fgs-request");if(failStart)throw new IOException(); }
        public Object acquire(CaptureAdmissionCoordinator.Ticket t,Object result) throws IOException { assertSame(ticket,t);assertNotNull(result);calls.add("acquire");acquiring.run();if(failAcquire)throw new IOException();return nullAcquire?null:projection; }
        public void stop(CaptureAdmissionCoordinator.Ticket t,Object p) throws IOException { assertSame(projection,p);calls.add("stop");if(failStop)throw new IOException(); }
    }
    static final class Run {
        final Object owner=new Object(); final RealCaptureClaim.ProcessEpoch epoch=new RealCaptureClaim.ProcessEpoch();
        final AtomicLong clock=new AtomicLong(100); final Fake fake=new Fake();
        final RealCaptureClaim.Grant grant=RealCaptureClaim.fixture(epoch,owner,100,1000,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
        final CaptureAdmissionCoordinator<Object> core;
        Run() {this((id,metadata)->{});}
        Run(RealCaptureClaim.BurnStore store) {core=CaptureAdmissionCoordinator.fixture(epoch,owner,grant,store,clock::get,fake);}
        void consent() {assertNotNull(core.begin());assertTrue(core.consentResult(fake.request,true,new Object()));}
    }
    @Test public void orderOneUseAndNoReaderBeforeFgs() {
        Run r=new Run();r.consent();assertEquals(List.of("consent","fgs-request"),r.fake.calls);
        r.core.foregroundReady(new Object());assertEquals(List.of("consent","fgs-request","acquire"),r.fake.calls);
        assertEquals(CaptureAdmissionCoordinator.State.ACQUIRED,r.core.state());
        assertFalse(r.core.consentResult(r.fake.request,true,new Object()));
        assertThrows(IllegalStateException.class,()->r.core.foregroundReady(new Object()));
        r.core.close();assertFalse(r.core.retainingProjection());assertFalse(r.epoch.retaining());
        assertThrows(IllegalStateException.class,r.core::begin);r.core.close();
    }
    @Test public void foreignRequestAndProcessCannotReplay() {
        Run a=new Run(),b=new Run();a.core.begin();b.core.begin();
        assertFalse(a.core.consentResult(b.fake.request,true,new Object()));assertEquals(List.of("consent"),a.fake.calls);
        assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(a.grant,b.epoch,a.owner,100,(i,m)->fail()));
        a.core.close();b.core.close();
    }
    @Test public void denialNullAndExpiryNeverAcquire() {
        for(int mode=0;mode<3;mode++) {Run r=new Run();r.core.begin();if(mode==2)r.clock.set(1100);
            assertFalse(r.core.consentResult(r.fake.request,mode!=0,mode==1?null:new Object()));
            assertFalse(r.fake.calls.contains("acquire"));assertTrue(r.core.fence().revoked());r.core.close();}
    }
    @Test public void failedOrDestroyedFgsPreventsAcquisition() {
        Run a=new Run();a.fake.failStart=true;a.core.begin();assertFalse(a.core.consentResult(a.fake.request,true,new Object()));a.core.close();
        Run b=new Run();b.consent();b.core.serviceDestroyed(new Object());b.core.foregroundReady(new Object());
        assertFalse(b.fake.calls.contains("acquire"));b.core.close();
    }
    @Test public void lateAcquisitionAdoptedBeforeCancel() {
        Run r=new Run();r.fake.acquiring=r.core::cancel;r.consent();r.core.foregroundReady(new Object());
        assertTrue(r.core.retainingProjection());assertTrue(r.core.fence().revoked());r.core.close();
        assertEquals(List.of("consent","fgs-request","acquire","stop"),r.fake.calls);
    }
    @Test public void thrownOrNullAcquisitionRetainsUnknownEpoch() {
        for(boolean nullResult:new boolean[]{false,true}) {Run r=new Run();r.fake.failAcquire=!nullResult;r.fake.nullAcquire=nullResult;
            r.consent();r.core.foregroundReady(new Object());r.core.close();
            assertEquals(CaptureAdmissionCoordinator.State.UNKNOWN,r.core.state());assertTrue(r.epoch.retaining());
            assertFalse(r.fake.calls.contains("stop"));}
    }
    @Test public void stopFailureRetainsKnownHandleAndCannotRetry() {
        Run r=new Run();r.fake.failStop=true;r.consent();r.core.foregroundReady(new Object());r.core.close();
        assertTrue(r.core.retainingProjection());assertTrue(r.epoch.retaining());assertEquals(CaptureAdmissionCoordinator.State.UNKNOWN,r.core.state());
        r.core.close();
    }
    @Test public void unknownBurnNeverReplayedOrLaunchesOs() {
        Run r=new Run((id,metadata)->{throw new IOException("uncertain force");});assertNull(r.core.begin());
        assertEquals(CaptureAdmissionCoordinator.State.UNKNOWN,r.core.state());assertTrue(r.fake.calls.isEmpty());
        assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(r.grant,r.epoch,r.owner,100,(i,m)->fail()));r.core.close();
    }
    @Test public void wrongOwnerClockAndUnprovedScopeBurnGrant() {
        var e=new RealCaptureClaim.ProcessEpoch();Object o=new Object();
        var g=RealCaptureClaim.fixture(e,o,100,1000,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
        assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(g,e,new Object(),100,(i,m)->fail()));
        assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(g,e,o,100,(i,m)->fail()));
        g=RealCaptureClaim.fixture(e,o,100,1000,RealCaptureClaim.Scope.UNVERIFIED_APP_SELECTION);
        assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(g,e,o,100,(i,m)->fail()));
        g=RealCaptureClaim.fixture(e,o,100,1000,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
        assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(g,e,o,99,(i,m)->fail()));
    }
    @Test public void fileTombstonePartialAndDuplicateCannotOverwrite() throws Exception {
        Path root=Files.createTempDirectory("real-claim-fixture");var store=new RealCaptureClaim.FileBurnStore(root);
        String id="12345678-1234-1234-1234-123456789abc";
        // Windows directory force may be unsupported: that must fail closed, not be
        // relabelled durable. In either case the exclusive tombstone is retained.
        try {store.burn(id,"metadata only\n");}catch(IOException expected) { }
        Path file=root.resolve("controlled-whole-frame-20261111-fifth-once").resolve("attempt.burned");assertTrue(Files.exists(file));
        byte[] before=Files.readAllBytes(file);assertThrows(IOException.class,()->store.burn(id,"replacement"));assertArrayEquals(before,Files.readAllBytes(file));
        String partial="22345678-1234-1234-1234-123456789abc";
        // A new UUID or new FileBurnStore instance must not create a second attempt.
        assertThrows(IOException.class,()->new RealCaptureClaim.FileBurnStore(root).burn(partial,"fresh grant"));
        assertThrows(IOException.class,()->store.burn(partial,"repair"));assertThrows(IOException.class,()->store.burn("../escape","x"));
    }
    @Test public void enabledBusinessBuildStillRejectsFixtureLiveEntry() {
        Run r=new Run();r.core.begin();
        assertTrue(CaptureReleaseGate.configured());
        CaptureReleaseGate.requireConfigured();
        assertThrows(SecurityException.class,r.fake.ticket::requireLive);
        Runnable wrapper=r.fake.ticket::requireLive;assertThrows(SecurityException.class,wrapper::run);r.core.close();
    }
    @Test public void ownerThreadRequiredButCrossThreadCancelSafe() throws Exception {
        Run r=new Run();r.core.begin();Thread t=new Thread(()->{assertThrows(IllegalStateException.class,r.core::state);r.core.cancel();});t.start();t.join();
        assertFalse(r.core.consentResult(r.fake.request,true,new Object()));r.core.close();
    }
    @Test public void reentrantCloseDuringAcquireCleansLateHandleExactlyOnce() {
        Run r=new Run();r.fake.acquiring=r.core::close;r.consent();r.core.foregroundReady(new Object());
        assertFalse(r.core.retainingProjection());assertFalse(r.epoch.retaining());
        assertEquals(List.of("consent","fgs-request","acquire","stop"),r.fake.calls);
        r.core.close();assertEquals(4,r.fake.calls.size());
    }
    @Test public void pendingOwnerPreventsAnotherAdmission() {
        Run r=new Run();r.core.begin();var g=RealCaptureClaim.fixture(r.epoch,r.owner,100,1000,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
        var second=CaptureAdmissionCoordinator.fixture(r.epoch,r.owner,g,(id,m)->fail(),r.clock::get,new Fake());
        assertNull(second.begin());assertEquals(CaptureAdmissionCoordinator.State.DENIED,second.state());r.core.close();
    }
    @Test public void fixtureCannotEnterAdmittedBackendOrHandover() {
        Run r=new Run();r.consent();r.core.foregroundReady(new Object());
        assertThrows(SecurityException.class,()->r.core.admittedBackend(null,Runnable::run,null,16,16,160));
        assertThrows(SecurityException.class,()->r.core.handover(null,null,16,16,160));
        assertTrue(r.core.retainingProjection());r.core.close();
        assertEquals(List.of("consent","fgs-request","acquire","stop"),r.fake.calls);
    }
    @Test public void scalarFreshnessRejectsDeadlineAndDoesNotCallPorts() {
        Run r=new Run();r.core.begin();r.fake.ticket.requireActiveScalar();r.clock.set(1100);
        assertThrows(SecurityException.class,r.fake.ticket::requireActiveScalar);
        assertEquals(List.of("consent"),r.fake.calls);r.core.close();
    }
    @Test public void sharedFenceCancelsBeforeFinalCommit() {
        BackendPorts.RevocationFence shared=new BackendPorts.RevocationFence();
        shared.revoke();assertFalse(shared.tryCommit());
    }}
