package com.aliothmoon.maafw.once;

import org.junit.Test;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Pure fake-OS tests of actual production lifecycle. No Android or screen data. */
public final class ProjectionLifecycleTest {
    static final AtomicInteger checks = new AtomicInteger();
    static void check(boolean condition) { checks.incrementAndGet(); if (!condition) throw new AssertionError(); }
    interface Throwing { void run() throws Exception; }
    static void expect(Class<? extends Throwable> type, Throwing action) throws Exception {
        boolean hit = false;
        try { action.run(); } catch (Throwable t) { if (!type.isInstance(t)) throw t; hit = true; }
        check(hit);
    }
    static final class Fake implements ProjectionLifecycle.Ports<Object, Object, Object> {
        final Object projection = new Object(), display = new Object(), target = new Object();
        final List<String> calls = new ArrayList<>();
        ProjectionLifecycle.Lease lease;
        ProjectionLifecycle.Anchor anchor;
        Runnable takeHook = () -> {}, registerHook = () -> {}, createHook = () -> {},
                releaseHook = () -> {}, stopHook = () -> {}, unregisterHook = () -> {};
        String failure = "";
        boolean nullTake, nullCreate;
        void call(String name, ProjectionLifecycle.Lease incoming) throws IOException {
            check(incoming == lease); incoming.requireThread();
            check(incoming.source() == ProjectionLifecycle.Source.SYNTHETIC_OS_FACADE);
            calls.add(name);
            if (failure.equals(name)) throw new IOException("fake " + name);
        }
        public Object takeProjection(ProjectionLifecycle.Lease l) throws IOException {
            check(lease == null); lease = l; call("take", l); takeHook.run(); return nullTake ? null : projection;
        }
        public void register(Object p, ProjectionLifecycle.Anchor a, ProjectionLifecycle.Lease l) throws IOException {
            check(p == projection); anchor = a; call("register", l); registerHook.run();
        }
        public Object createDisplay(Object p, Object t, int w, int h, int dpi, ProjectionLifecycle.Lease l) throws IOException {
            check(p == projection && t == target && w == 8 && h == 4 && dpi == 160);
            call("create", l); createHook.run(); return nullCreate ? null : display;
        }
        public void releaseDisplay(Object d, ProjectionLifecycle.Lease l) throws IOException {
            check(d == display); call("release", l); releaseHook.run();
        }
        public void stopProjection(Object p, ProjectionLifecycle.Lease l) throws IOException {
            check(p == projection); call("stop", l); stopHook.run();
        }
        public void unregister(Object p, ProjectionLifecycle.Anchor a, ProjectionLifecycle.Lease l) throws IOException {
            check(p == projection && a == anchor); call("unregister", l); unregisterHook.run();
        }
    }
    static ProjectionLifecycle<Object, Object, Object> core(Object owner, Fake fake) {
        return new ProjectionLifecycle<>(owner, fake, fake.target, 8, 4, 160);
    }
    static void unproven(ProjectionLifecycle.Evidence e) {
        check(!e.callbackJoinProven()); check(!e.driverQuietProven()); check(e.readerReleasePermitted() == (e.appReferencesDropped() || e.projectionStop() == ProjectionLifecycle.Fact.THREW && !e.creationUncertain() && e.displayRelease() == ProjectionLifecycle.Fact.RETURNED && e.unregister() == ProjectionLifecycle.Fact.RETURNED));
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    @Test public void normalReturnsAreNotQuiescence() throws Exception {
        Object owner = new Object(); Fake f = new Fake(); var c = core(owner, f);
        c.start(owner); check(c.candidate(owner)); c.close(owner);
        var e = c.evidence(owner); check(e.appReferencesDropped()); check(!e.creationUncertain());
        check(e.take() == ProjectionLifecycle.Fact.RETURNED && e.register() == ProjectionLifecycle.Fact.RETURNED
                && e.create() == ProjectionLifecycle.Fact.RETURNED && e.displayRelease() == ProjectionLifecycle.Fact.RETURNED
                && e.projectionStop() == ProjectionLifecycle.Fact.RETURNED && e.unregister() == ProjectionLifecycle.Fact.RETURNED);
        check(f.calls.equals(List.of("take", "register", "create", "unregister", "release", "stop")));
        check(!c.candidate(owner)); unproven(e);
    }
    @Test public void preStopDisposesPreownedProjectionWithoutCreating() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f); c.requestStop();
        expect(BackendPorts.Cancelled.class, () -> c.start(o)); c.close(o);
        check(f.calls.equals(List.of("take", "stop"))); check(c.evidence(o).appReferencesDropped()); unproven(c.evidence(o));
    }
    @Test public void closeBeforeStartDisposesPreownedProjection() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f); c.close(o);
        check(f.calls.equals(List.of("take", "stop"))); check(c.evidence(o).appReferencesDropped());
        expect(IllegalStateException.class, () -> c.start(o)); unproven(c.evidence(o));
    }
    @Test public void lateProjectionAdoptedBeforeStop() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f); f.takeHook = c::requestStop;
        expect(BackendPorts.Cancelled.class, () -> c.start(o)); c.close(o);
        check(f.calls.equals(List.of("take", "stop"))); check(c.evidence(o).appReferencesDropped());
    }
    @Test public void lateRegistrationDoesNotCreate() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f); f.registerHook = c::requestStop;
        expect(BackendPorts.Cancelled.class, () -> c.start(o)); c.close(o);
        check(f.calls.equals(List.of("take", "register", "unregister", "stop")));
    }
    @Test public void lateDisplayAdoptedBeforeStop() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f); f.createHook = c::requestStop;
        expect(BackendPorts.Cancelled.class, () -> c.start(o)); c.close(o);
        check(f.calls.contains("release")); check(!c.evidence(o).creationUncertain()); check(!c.candidate(o));
    }
    @Test public void revocationDuringRegistrationCancels() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f); f.registerHook = () -> f.anchor.onStop();
        expect(BackendPorts.Cancelled.class, () -> c.start(o)); c.close(o);
        check(!f.calls.contains("create")); check(c.evidence(o).stopBeforeClose());
    }
    @Test public void revocationDuringCreateCancelsAndReleases() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f); f.createHook = () -> f.anchor.onStop();
        expect(BackendPorts.Cancelled.class, () -> c.start(o)); c.close(o);
        check(f.calls.contains("release")); check(c.evidence(o).stopBeforeClose()); check(!c.candidate(o));
    }
    @Test public void revocationInvalidatesCandidate() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f); c.start(o); check(c.candidate(o));
        f.anchor.onStop(); check(c.cancellationRequested()); check(!c.candidate(o)); c.close(o);
    }
    @Test public void ownStopCallbackIsAmbiguousNotProof() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f); f.stopHook = () -> f.anchor.onStop();
        c.start(o); c.close(o); var e = c.evidence(o);
        check(!e.stopBeforeClose()); check(e.stopAfterCloseAmbiguous()); check(!c.candidate(o)); unproven(e);
    }
    @Test public void queuedOldCallbackCannotReachNewOwner() throws Exception {
        Object a = new Object(), b = new Object(); Fake f = new Fake(), g = new Fake(); var c = core(a, f);
        c.start(a); ProjectionLifecycle.Anchor old = f.anchor; c.close(a); var next = core(b, g); next.start(b);
        old.onStop(); old.onResize(); check(c.evidence(a).stopAfterCloseAmbiguous());
        check(next.candidate(b)); check(!next.cancellationRequested()); next.close(b);
    }
    @Test public void geometryEventCancelsWithoutRearm() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f); c.start(o); f.anchor.onResize();
        check(!c.candidate(o)); check(c.evidence(o).resized()); expect(IllegalStateException.class, () -> c.start(o)); c.close(o);
        check(f.calls.stream().filter("create"::equals).count() == 1);
    }
    @Test public void creationThrowsKeepUnknownAndCleanKnownHandles() throws Exception {
        for (String fail : List.of("take", "register", "create")) {
            Object o = new Object(); Fake f = new Fake(); f.failure = fail; var c = core(o, f);
            expect(IOException.class, () -> c.start(o)); c.close(o); var e = c.evidence(o);
            check(e.creationUncertain()); check(!e.appReferencesDropped()); unproven(e);
            check(f.calls.contains("stop") == !fail.equals("take"));
            check(f.calls.contains("unregister") == !fail.equals("take"));
            expect(IllegalStateException.class, () -> c.start(o)); expect(IllegalStateException.class, () -> c.close(o));
        }
    }
    @Test public void nullHandlesNeverMeanNoResources() throws Exception {
        for (boolean take : new boolean[]{true, false}) {
            Object o = new Object(); Fake f = new Fake(); f.nullTake = take; f.nullCreate = !take; var c = core(o, f);
            expect(IllegalStateException.class, () -> c.start(o)); c.close(o);
            check(c.evidence(o).creationUncertain()); check(!c.evidence(o).appReferencesDropped()); unproven(c.evidence(o));
        }
    }
    @Test public void cleanupFailuresAreSeparateAndNotRetried() throws Exception {
        for (String fail : List.of("release", "stop", "unregister")) {
            Object o = new Object(); Fake f = new Fake(); var c = core(o, f); c.start(o); f.failure = fail; c.close(o);
            var e = c.evidence(o); check(!e.appReferencesDropped()); unproven(e);
            check(e.displayRelease() == (fail.equals("release") ? ProjectionLifecycle.Fact.THREW : ProjectionLifecycle.Fact.RETURNED));
            check(e.projectionStop() == (fail.equals("stop") ? ProjectionLifecycle.Fact.THREW : ProjectionLifecycle.Fact.RETURNED));
            check(e.unregister() == (fail.equals("unregister") ? ProjectionLifecycle.Fact.THREW : ProjectionLifecycle.Fact.RETURNED));
            check(f.calls.size() == 6); expect(IllegalStateException.class, () -> c.close(o)); check(f.calls.size() == 6);
        }
    }
    @Test public void exactOwnerThreadAndSingleUse() throws Exception {
        Object o = new Object(); Fake f = new Fake(); var c = core(o, f);
        expect(SecurityException.class, () -> c.start(new Object())); check(f.calls.isEmpty());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread wrong = new Thread(() -> { try { c.start(o); } catch (Throwable e) { failure.set(e); } }); wrong.start(); wrong.join(5000);
        check(!wrong.isAlive()); check(failure.get() instanceof IllegalStateException); check(f.calls.isEmpty());
        c.start(o); expect(IllegalStateException.class, () -> c.start(o));
        expect(SecurityException.class, () -> c.evidence(new Object()));
        expect(SecurityException.class, () -> c.close(new Object())); c.close(o);
        expect(IllegalStateException.class, () -> c.close(o)); expect(IllegalStateException.class, () -> c.start(o)); check(f.calls.size() == 6);
    }
    @Test public void blockedCreateRetainsOwnerUntilLateReturn() throws Exception {
        Object o = new Object(); Fake f = new Fake(); CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<ProjectionLifecycle<Object, Object, Object>> current = new AtomicReference<>();
        AtomicReference<ProjectionLifecycle.Evidence> evidence = new AtomicReference<>(); AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicInteger releases = new AtomicInteger(); f.releaseHook = releases::incrementAndGet;
        f.createHook = () -> { entered.countDown(); await(finish); };
        Thread worker = new Thread(() -> { try {
            var c = core(o, f); current.set(c); expect(BackendPorts.Cancelled.class, () -> c.start(o)); c.close(o); evidence.set(c.evidence(o));
        } catch (Throwable e) { failed.set(e); } });
        worker.start(); await(entered); current.get().requestStop(); check(releases.get() == 0); check(worker.isAlive());
        finish.countDown(); worker.join(5000); check(!worker.isAlive()); check(failed.get() == null);
        check(releases.get() == 1); check(evidence.get().appReferencesDropped()); unproven(evidence.get());
    }
    @Test public void blockedReleaseCannotBeFreedByCancel() throws Exception {
        Object o = new Object(); Fake f = new Fake(); CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<ProjectionLifecycle<Object, Object, Object>> current = new AtomicReference<>(); AtomicReference<Throwable> failed = new AtomicReference<>();
        AtomicInteger stops = new AtomicInteger(); f.stopHook = stops::incrementAndGet;
        f.releaseHook = () -> { entered.countDown(); await(finish); };
        Thread worker = new Thread(() -> { try { var c = core(o, f); current.set(c); c.start(o); c.close(o); unproven(c.evidence(o)); }
            catch (Throwable e) { failed.set(e); } });
        worker.start(); await(entered); current.get().requestStop(); check(stops.get() == 0); check(worker.isAlive());
        finish.countDown(); worker.join(5000); check(!worker.isAlive()); check(failed.get() == null); check(stops.get() == 1);
    }
    @Test public void anchorHasNoResourceGraphAndGeometryIsBounded() throws Exception {
        for (var field : ProjectionLifecycle.Anchor.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) check(field.getType() == AtomicInteger.class || field.getType() == java.util.concurrent.atomic.AtomicLong.class || field.getType() == BackendPorts.RevocationFence.class);
        }
        check(Modifier.isStatic(ProjectionLifecycle.Anchor.class.getModifiers()));
        Object o = new Object(); Fake f = new Fake();
        expect(IllegalArgumentException.class, () -> new ProjectionLifecycle<>(o, f, f.target, 4096, 4096, 160));
        expect(IllegalArgumentException.class, () -> new ProjectionLifecycle<>(o, f, f.target, 8, 4, 0));
    }
    @Test public void initialNaturalGeometryAndDuplicateAreNotOutputGeometry() throws Exception {
        Object o=new Object();Fake f=new Fake();var c=new ProjectionLifecycle<>(o,f,f.target,8,4,160,true,ProjectionLifecycle.Source.SYNTHETIC_OS_FACADE);
        c.start(o);check(!c.geometryConfirmed());check(!c.candidate(o));f.anchor.onResize(1200,2400);check(c.geometryConfirmed());check(c.candidate(o));f.anchor.onResize(1200,2400);check(c.candidate(o));f.anchor.onResize(2400,1200);check(!c.candidate(o));c.close(o);
    }
    @Test public void invalidGeometryNeverBecomesAccepted() throws Exception {
        for(int[] size:new int[][]{{0,10},{10,-1},{16385,1},{16384,16384}}){Object o=new Object();Fake f=new Fake();var c=core(o,f);c.start(o);f.anchor.onResize(size[0],size[1]);check(c.cancellationRequested());f.anchor.onResize(100,200);check(c.cancellationRequested());c.close(o);}
    }
    @Test public void receiptCannotCrossSessionOwnerTargetOrDomain() throws Exception {
        Object o=new Object();Fake f=new Fake();var c=core(o,f);var key=c.targetKey(o,f.target);check(c.readerReleaseReceipt(o)==null);c.start(o);c.close(o);var r=c.readerReleaseReceipt(o);
        check(c.permitsReaderRelease(r,o,key,ProjectionLifecycle.Source.SYNTHETIC_OS_FACADE));check(!c.permitsReaderRelease(r,o,key,ProjectionLifecycle.Source.DORMANT_MEDIA_PROJECTION));expect(SecurityException.class,()->c.permitsReaderRelease(r,new Object(),key,ProjectionLifecycle.Source.SYNTHETIC_OS_FACADE));
        Fake other=new Fake();var d=core(o,other);var otherKey=d.targetKey(o,other.target);d.start(o);d.close(o);check(!d.permitsReaderRelease(r,o,otherKey,ProjectionLifecycle.Source.SYNTHETIC_OS_FACADE));check(!c.permitsReaderRelease(r,o,otherKey,ProjectionLifecycle.Source.SYNTHETIC_OS_FACADE));
        expect(SecurityException.class,()->new ProjectionLifecycle<>(o,new Fake(),new Object(),8,4,160,true,ProjectionLifecycle.Source.DORMANT_MEDIA_PROJECTION));
    }
    public static void main(String[] args) throws Exception {
        ProjectionLifecycleTest test = new ProjectionLifecycleTest(); int cases = 0;
        for (var method : ProjectionLifecycleTest.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Test.class)) { method.invoke(test); cases++; }
        }
        System.out.println("PASS ProjectionLifecycleTest: " + cases + " cases / " + checks.get() + " assertions; fake OS only");
    }
}
