package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.ReviewShizukuTapActor.*;

/** Synthetic backend only. No Android, Shizuku, shell or account action runs. */
public class ReviewShizukuTapActorTest {
    private static final TapTarget TARGET = new TapTarget(542, 229, 1080, 1920, 0);
    @FunctionalInterface private interface Answer { boolean answer(TapTarget target) throws Exception; }
    private static DispatchGate gate(Answer consume, Answer stillValid) {
        return new DispatchGate() {
            @Override public boolean consume(TapTarget t) throws Exception { return consume.answer(t); }
            @Override public boolean stillValid(TapTarget t) throws Exception { return stillValid.answer(t); }
        };
    }
    private static DispatchGate permit(Answer consume) { return gate(consume, t -> true); }
    private static final class Rig implements Backend, Session, PendingTap {
        final List<String> order = new ArrayList<>();
        int opens, readiness, displays, gates, starts, waits, destroys, closes, exit;
        int failReadyAt, changeDisplayAt;
        Reason internalBlock;
        ServerMode serverMode = ServerMode.SHELL_ONLY;
        int observedUid = 2000;
        long waitMillis;
        boolean openThrows, nullSession, nullProcess, dispatchThrows, waitThrows,
                exitThrows, destroyThrows, closeThrows, timeout;
        DisplayShape shape = new DisplayShape(1080, 1920, 0);
        @Override public Session open() throws Exception {
            opens++; order.add("open");
            if (openThrows) throw new Exception("synthetic");
            return nullSession ? null : this;
        }
        @Override public boolean stillReady() { readiness++; order.add("ready"); return readiness != failReadyAt && AndroidReviewShizukuTapBackend.allowedServerUid(serverMode, observedUid); }
        @Override public DisplayShape display() {
            displays++; order.add("display");
            return displays == changeDisplayAt ? new DisplayShape(1920, 1080, 1) : shape;
        }
        DispatchGate gate(boolean answer) { return ReviewShizukuTapActorTest.gate(
            exact -> { assertSame(TARGET, exact); gates++; order.add("gate"); return answer; },
            exact -> { assertSame(TARGET, exact); order.add("stillValid"); return true; }); }
        @Override public PendingTap dispatch(TapTarget target, FinalGate finalGate) throws Exception {
            assertSame(TARGET,target);
            if(internalBlock != null) throw new DispatchBlocked(internalBlock);
            if(!AndroidReviewShizukuTapBackend.allowedServerUid(serverMode, observedUid)) throw new DispatchBlocked(Reason.READY_STATE_LOST);
            finalGate.requireValid();
            assertEquals(542, target.x()); assertEquals(229, target.y()); starts++; order.add("dispatch");
            if (dispatchThrows) throw new Exception("synthetic");
            return nullProcess ? null : this;
        }
        @Override public boolean waitForExit(long millis) throws Exception {
            waits++; waitMillis = millis; order.add("wait");
            if (waitThrows) throw new Exception("synthetic");
            return !timeout;
        }
        @Override public int exitCode() throws Exception { if (exitThrows) throw new Exception("synthetic"); return exit; }
        @Override public void destroy() throws Exception { destroys++; if (destroyThrows) throw new Exception("synthetic"); }
        @Override public void close() throws Exception { closes++; if (closeThrows) throw new Exception("synthetic"); }
        Result run() { return new ReviewShizukuTapActor(this).tap(TARGET, gate(true)); }
    }
    private static void notInvoked(Result r, Reason reason) {
        assertEquals(Kind.REJECTED, r.kind()); assertEquals(reason, r.reason());
        assertFalse(r.invocationAttempted()); assertEquals(NO_EXIT, r.exitCode()); assertFalse(r.commandExitZero());
    }
    private static void unknown(Result r, Reason reason) {
        assertEquals(Kind.DISPATCH_UNKNOWN, r.kind()); assertEquals(reason, r.reason());
        assertTrue(r.invocationAttempted()); assertTrue(r.authorityConsumed());
        assertEquals(NO_EXIT, r.exitCode()); assertFalse(r.commandExitZero());
    }
    @Test public void onlyShellServerUidIsAllowed() {
        assertTrue(AndroidReviewShizukuTapBackend.allowedServerUid(2000));
        for(int uid:new int[]{0,1000,10087,-1,Integer.MAX_VALUE}) assertFalse(AndroidReviewShizukuTapBackend.allowedServerUid(uid));
    }
    @Test public void approvedRootProfileAcceptsOnlyItsExactUid() {
        assertEquals(0,ServerMode.REWARDS_BUSINESS_ROOT_20261111.expectedServerUid());
        assertEquals(2000,ServerMode.SHELL_ONLY.expectedServerUid());
        assertTrue(AndroidReviewShizukuTapBackend.allowedServerUid(ServerMode.REWARDS_BUSINESS_ROOT_20261111,0));
        for(int uid:new int[]{2000,1000,10087,-1,Integer.MAX_VALUE})
            assertFalse(AndroidReviewShizukuTapBackend.allowedServerUid(ServerMode.REWARDS_BUSINESS_ROOT_20261111,uid));
        assertFalse(AndroidReviewShizukuTapBackend.allowedServerUid(null,0));
        assertFalse(AndroidReviewShizukuTapBackend.allowedServerUid(null,2000));
    }
    @Test public void explicitlyConsentedRootCanDispatchOneFixedTap() {
        Rig r=new Rig();r.serverMode=ServerMode.REWARDS_BUSINESS_ROOT_20261111;r.observedUid=0;
        Result result=r.run();assertTrue(result.commandExitZero());assertEquals(1,r.gates);assertEquals(1,r.starts);
    }
    @Test public void defaultShellProfileCannotFallBackToRoot() {
        Rig r=new Rig();r.observedUid=0;notInvoked(r.run(),Reason.PREFLIGHT_UNAVAILABLE);
        assertEquals(0,r.gates);assertEquals(0,r.starts);
    }
    @Test public void consentedRootProfileCannotFallBackToShell() {
        Rig r=new Rig();r.serverMode=ServerMode.REWARDS_BUSINESS_ROOT_20261111;r.observedUid=2000;
        notInvoked(r.run(),Reason.PREFLIGHT_UNAVAILABLE);assertEquals(0,r.gates);assertEquals(0,r.starts);
    }
    @Test public void consentedRootStillRejectsUnknownUid() {
        Rig r=new Rig();r.serverMode=ServerMode.REWARDS_BUSINESS_ROOT_20261111;r.observedUid=-1;
        notInvoked(r.run(),Reason.PREFLIGHT_UNAVAILABLE);assertEquals(0,r.starts);
    }
    @Test public void consentedRootIdentityChangeAfterBurnCannotDispatch() {
        Rig r=new Rig();r.serverMode=ServerMode.REWARDS_BUSINESS_ROOT_20261111;r.observedUid=0;
        Result result=new ReviewShizukuTapActor(r).tap(TARGET,permit(t->{r.observedUid=2000;return true;}));
        notInvoked(result,Reason.READY_STATE_LOST);assertTrue(result.authorityConsumed());assertEquals(0,r.starts);
    }
    @Test public void rootConsentDoesNotRemoveOneShotOrTargetGate() {
        Rig r=new Rig();r.serverMode=ServerMode.REWARDS_BUSINESS_ROOT_20261111;r.observedUid=0;
        var actor=new ReviewShizukuTapActor(r);notInvoked(actor.tap(TARGET,r.gate(false)),Reason.AUTHORITY_REJECTED);
        notInvoked(actor.tap(TARGET,r.gate(true)),Reason.ALREADY_SPENT);assertEquals(0,r.starts);
    }
    @Test public void zeroExitMeansCommandOnlyAndHasNoTaskCompletionField() {
        Rig r = new Rig(); Result result = r.run();
        assertEquals(Kind.COMMAND_EXITED, result.kind()); assertEquals(0, result.exitCode());
        assertEquals(Reason.COMMAND_EXIT_ZERO, result.reason()); assertTrue(result.commandExitZero());
        assertTrue(result.invocationAttempted()); assertTrue(result.authorityConsumed());
        assertEquals(List.of("open","ready","display","gate","ready","display","stillValid","dispatch","wait"), r.order);
        assertEquals(1, r.starts); assertEquals(1, r.closes); assertEquals(0, r.destroys);
        assertEquals(2_000L, r.waitMillis);
    }
    @Test public void nonzeroExitIsNeverReportedZero() {
        Rig r = new Rig(); r.exit = 1; Result result = r.run();
        assertEquals(Kind.COMMAND_EXITED, result.kind()); assertEquals(1, result.exitCode());
        assertEquals(Reason.COMMAND_EXIT_NONZERO, result.reason()); assertFalse(result.commandExitZero());
    }
    @Test public void secondAttemptCannotDispatch() {
        Rig r = new Rig(); var actor = new ReviewShizukuTapActor(r);
        actor.tap(TARGET,r.gate(true)); notInvoked(actor.tap(TARGET,r.gate(true)),Reason.ALREADY_SPENT);
        assertEquals(1,r.gates); assertEquals(1,r.starts); assertEquals(1,r.opens);
    }
    @Test public void invalidTargetDoesNotEnterBackend() {
        Rig r = new Rig(); var actor = new ReviewShizukuTapActor(r);
        TapTarget[] bad = {null,new TapTarget(-1,0,1080,1920,0),new TapTarget(1080,0,1080,1920,0),
            new TapTarget(0,-1,1080,1920,0),new TapTarget(0,1920,1080,1920,0),new TapTarget(0,0,0,1920,0),
            new TapTarget(0,0,1080,0,0),new TapTarget(0,0,-1,1920,0),new TapTarget(0,0,4097,1,0),
            new TapTarget(0,0,1,4097,0),new TapTarget(0,0,4096,4096,0),new TapTarget(0,0,1080,1920,-1),
            new TapTarget(0,0,1080,1920,4),new TapTarget(0,0,Integer.MAX_VALUE,Integer.MAX_VALUE,0)};
        for(TapTarget t:bad) notInvoked(actor.tap(t, permit(exact -> true)),Reason.INVALID_INPUT);
        assertEquals(0,r.opens); assertEquals(0,r.starts);
        assertTrue(actor.tap(TARGET,r.gate(true)).commandExitZero());
    }
    @Test public void nullGateCannotOpenBackend() { Rig r=new Rig();notInvoked(new ReviewShizukuTapActor(r).tap(TARGET,null),Reason.INVALID_INPUT);assertEquals(0,r.opens); }
    @Test public void unavailableOpenNeverConsumesGate() { Rig r=new Rig();r.openThrows=true;notInvoked(r.run(),Reason.PREFLIGHT_UNAVAILABLE);assertEquals(0,r.gates);assertEquals(0,r.starts); }
    @Test public void nullSessionNeverConsumesGate() { Rig r=new Rig();r.nullSession=true;notInvoked(r.run(),Reason.PREFLIGHT_UNAVAILABLE);assertEquals(0,r.gates); }
    @Test public void lostPermissionBeforeGateNeverConsumesGate() { Rig r=new Rig();r.failReadyAt=1;notInvoked(r.run(),Reason.PREFLIGHT_UNAVAILABLE);assertEquals(0,r.gates); }
    @Test public void wrongActualDimensionsCannotBeCallerCertified() { Rig r=new Rig();r.shape=new DisplayShape(720,1280,0);notInvoked(r.run(),Reason.DISPLAY_CHANGED);assertEquals(0,r.gates);assertEquals(0,r.starts); }
    @Test public void wrongActualRotationCannotBeCallerCertified() { Rig r=new Rig();r.shape=new DisplayShape(1080,1920,2);notInvoked(r.run(),Reason.DISPLAY_CHANGED);assertEquals(0,r.gates); }
    @Test public void unknownActualDisplayRejects() { Rig r=new Rig();r.shape=null;notInvoked(r.run(),Reason.DISPLAY_CHANGED);assertEquals(0,r.gates); }
    @Test public void rejectedAuthorityNeverDispatchesAndStaysSpent() {
        Rig r=new Rig();var actor=new ReviewShizukuTapActor(r);Result result=actor.tap(TARGET,r.gate(false));
        notInvoked(result,Reason.AUTHORITY_REJECTED);assertTrue(result.authorityConsumed());assertEquals(0,r.starts);
        notInvoked(actor.tap(TARGET,r.gate(true)),Reason.ALREADY_SPENT);assertEquals(1,r.gates);
    }
    @Test public void unknownAuthorityNeverDispatchesAndStaysSpent() {
        Rig r=new Rig();var actor=new ReviewShizukuTapActor(r);Result result=actor.tap(TARGET,permit(t->{throw new Exception("unknown burn");}));
        notInvoked(result,Reason.AUTHORITY_UNKNOWN);assertTrue(result.authorityConsumed());assertEquals(0,r.starts);
        notInvoked(actor.tap(TARGET,r.gate(true)),Reason.ALREADY_SPENT);
    }
    @Test public void lostBinderAfterAuthorityBurnRejectsWithoutDispatch() { Rig r=new Rig();r.failReadyAt=2;Result result=r.run();notInvoked(result,Reason.READY_STATE_LOST);assertTrue(result.authorityConsumed());assertEquals(1,r.gates);assertEquals(0,r.starts); }
    @Test public void rotationBetweenGateAndDispatchRejects() { Rig r=new Rig();r.changeDisplayAt=2;Result result=r.run();notInvoked(result,Reason.DISPLAY_CHANGED);assertTrue(result.authorityConsumed());assertEquals(0,r.starts); }
    @Test public void dispatchExceptionHasUnknownOutcomeAndCannotRetry() {
        Rig r=new Rig();r.dispatchThrows=true;var actor=new ReviewShizukuTapActor(r);unknown(actor.tap(TARGET,r.gate(true)),Reason.DISPATCH_EXCEPTION);
        notInvoked(actor.tap(TARGET,r.gate(true)),Reason.ALREADY_SPENT);assertEquals(1,r.starts);assertEquals(0,r.destroys);
    }
    @Test public void missingProcessHandleIsUnknownNotFailureProof() { Rig r=new Rig();r.nullProcess=true;unknown(r.run(),Reason.NO_PROCESS_HANDLE);assertEquals(1,r.starts); }
    @Test public void timeoutDestroysOnlyOwnProcessAndCloses() { Rig r=new Rig();r.timeout=true;unknown(r.run(),Reason.WAIT_TIMEOUT);assertEquals(1,r.destroys);assertEquals(1,r.closes); }
    @Test public void waitExceptionDestroysOnlyOwnProcessAndCloses() { Rig r=new Rig();r.waitThrows=true;unknown(r.run(),Reason.WAIT_EXCEPTION);assertEquals(1,r.destroys);assertEquals(1,r.closes); }
    @Test public void exitExceptionIsUnknownAndStillCleansUp() { Rig r=new Rig();r.exitThrows=true;unknown(r.run(),Reason.WAIT_EXCEPTION);assertEquals(1,r.destroys);assertEquals(1,r.closes); }
    @Test public void destroyExceptionDoesNotSkipClosingDescriptors() { Rig r=new Rig();r.timeout=true;r.destroyThrows=true;unknown(r.run(),Reason.CLEANUP_UNKNOWN);assertEquals(1,r.destroys);assertEquals(1,r.closes); }
    @Test public void descriptorExceptionNeverClaimsKnownSuccess() { Rig r=new Rig();r.closeThrows=true;unknown(r.run(),Reason.CLEANUP_UNKNOWN);assertEquals(1,r.closes); }
    @Test public void preflightFailureBudgetDoesNotReset() {
        Rig r=new Rig();r.openThrows=true;var actor=new ReviewShizukuTapActor(r);notInvoked(actor.tap(TARGET,r.gate(true)),Reason.PREFLIGHT_UNAVAILABLE);
        r.openThrows=false;notInvoked(actor.tap(TARGET,r.gate(true)),Reason.ALREADY_SPENT);assertEquals(0,r.starts);
    }
    @Test public void finalInternalBinderRejectionIsKnownNotInvoked() {
        Rig r=new Rig();r.internalBlock=Reason.READY_STATE_LOST;Result result=r.run();
        notInvoked(result,Reason.READY_STATE_LOST);assertTrue(result.authorityConsumed());assertEquals(0,r.starts);
    }
    @Test public void finalInternalDisplayRejectionIsKnownNotInvoked() {
        Rig r=new Rig();r.internalBlock=Reason.DISPLAY_CHANGED;Result result=r.run();
        notInvoked(result,Reason.DISPLAY_CHANGED);assertTrue(result.authorityConsumed());assertEquals(0,r.starts);
    }
    @Test public void expiryOrCancellationAtFinalGateCannotDispatch() {
        Rig r=new Rig();Result result=new ReviewShizukuTapActor(r).tap(TARGET,gate(t->true,t->false));
        notInvoked(result,Reason.AUTHORITY_STALE);assertTrue(result.authorityConsumed());assertEquals(0,r.starts);
    }
    @Test public void unknownFinalForegroundCheckCannotDispatch() {
        Rig r=new Rig();Result result=new ReviewShizukuTapActor(r).tap(TARGET,gate(t->true,t->{throw new Exception("foreground unknown");}));
        notInvoked(result,Reason.AUTHORITY_RECHECK_UNKNOWN);assertTrue(result.authorityConsumed());assertEquals(0,r.starts);
    }
    @Test public void finalGateRunsOnlyAfterSecondDisplayCheck() {
        Rig r=new Rig();r.changeDisplayAt=2;Result result=new ReviewShizukuTapActor(r).tap(TARGET,gate(t->true,t->{fail("must not enter final gate");return true;}));
        notInvoked(result,Reason.DISPLAY_CHANGED);assertEquals(0,r.starts);
    }
    @Test public void concurrentSecondTapCannotEnterGateOrDispatch() throws Exception {
        Rig r=new Rig();var actor=new ReviewShizukuTapActor(r);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var result=new AtomicReference<Result>();Thread first=new Thread(()->result.set(actor.tap(TARGET,permit(t->{entered.countDown();return release.await(2,TimeUnit.SECONDS);}))));first.start();
        assertTrue(entered.await(1,TimeUnit.SECONDS));notInvoked(actor.tap(TARGET,r.gate(true)),Reason.ALREADY_SPENT);
        release.countDown();first.join(2_000);assertFalse(first.isAlive());assertTrue(result.get().commandExitZero());assertEquals(1,r.starts);
    }
}




