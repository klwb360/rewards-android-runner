package com.aliothmoon.maafw.readonly;

import com.aliothmoon.maafw.readonly.ReadOnlyRecognition.Error;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static com.aliothmoon.maafw.readonly.ReadOnlyRecognition.*;

/** Host tests of real product reducer/controller; no Android or backend calls. */
public final class ReadOnlyRecognitionTest {
    private static int assertions;
    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private static Admission good(Authorization auth) {
        return new Admission(true, true, true, Backend.REVIEWED_READ_ONLY, auth);
    }
    private static ReadOnlyRecognition fresh() { return new ReadOnlyRecognition(good(Authorization.APPROVED_ONCE)); }
    private static CleanupEvidence full() { return new CleanupEvidence(true, true, true); }
    private static Owner begin(ReadOnlyRecognition r) {
        State state = r.onDurableClaimCommitted(true);
        check(state.phase == Phase.RUNNING && state.authorization == Authorization.CONSUMED, "claim consumes before running");
        return state.owner;
    }
    private static void terminal(State s, Phase expected) {
        check(s.phase == expected && s.terminal() && s.cleanupConfirmed, "terminal only with cleanup evidence");
        check(s.authorization == Authorization.CONSUMED, "terminal remains consumed");
    }
    private static void admission() {
        check(matchesLegacyDevice(35, "ranchu", "x/sdk_gphone64/x"), "legacy match");
        for (int sdk : new int[]{0, 28, 34, 36, 37}) check(!matchesLegacyDevice(sdk, "ranchu", "sdk_gphone"), "SDK rejected");
        check(!matchesLegacyDevice(35, "other", "sdk_gphone"), "hardware rejected");
        check(!matchesLegacyDevice(35, null, "sdk_gphone"), "null hardware rejected");
        check(!matchesLegacyDevice(35, "ranchu", null), "null fingerprint rejected");
        check(!matchesLegacyDevice(35, "ranchu", "other"), "fingerprint rejected");
        check(good(Authorization.NOT_AUTHORIZED).readiness() == Readiness.READY_FOR_FUTURE_APPROVAL, "ready is not authorized");
        check(good(Authorization.CONSUMED).readiness() == Readiness.CONSUMED, "consumed precedence");
        check(new Admission(false, true, true, Backend.REVIEWED_READ_ONLY, Authorization.APPROVED_ONCE).readiness() == Readiness.UNSUPPORTED, "unsupported");
        check(new Admission(true, true, true, Backend.UNBOUND, Authorization.APPROVED_ONCE).readiness() == Readiness.BACKEND_UNBOUND, "unbound");
        check(new Admission(true, false, true, Backend.REVIEWED_READ_ONLY, Authorization.APPROVED_ONCE).readiness() == Readiness.RESOURCE_UNVERIFIED, "resource missing");
        check(new Admission(true, true, false, Backend.REVIEWED_READ_ONLY, Authorization.APPROVED_ONCE).readiness() == Readiness.IDENTITY_UNVERIFIED, "remote identity missing");
        check(new Admission(true, true, true, null, null).backend == Backend.UNBOUND, "null backend closed");
        for (Authorization auth : Authorization.values()) {
            for (Backend backend : Backend.values()) {
                for (int flags = 0; flags < 8; flags++) {
                    Admission a = new Admission((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, backend, auth);
                    State state = new ReadOnlyRecognition(a).onDurableClaimCommitted(true);
                    boolean allowed = flags == 7 && backend == Backend.REVIEWED_READ_ONLY && auth == Authorization.APPROVED_ONCE;
                    check((state.phase == Phase.RUNNING) == allowed, "all admission combinations");
                }
            }
        }
        ReadOnlyRecognition r = fresh();
        State before = r.snapshot();
        check(r.onDurableClaimCommitted(false) == before, "no confirmed durable claim");
        check(r.requestStop() == before, "idle cancel does not consume");
        ReadOnlyRecognition ready = new ReadOnlyRecognition(good(Authorization.NOT_AUTHORIZED));
        check(ready.onDurableClaimCommitted(true).phase == Phase.IDLE, "readiness does not mint approval");
    }
    private static void outcomes() {
        for (Result result : new Result[]{Result.TEXT_PRESENT, Result.NO_TEXT}) {
            ReadOnlyRecognition r = fresh(); Owner owner = begin(r);
            State resultState = r.onResult(owner, result);
            check(resultState.phase == Phase.RUNNING && !resultState.cleanupConfirmed, "result not completion");
            check(r.onResult(owner, Result.TEXT_PRESENT) == resultState, "duplicate result ignored");
            terminal(r.onCleanupEvidence(owner, full()), Phase.COMPLETED);
            State done = r.snapshot();
            check(r.onDurableClaimCommitted(true) == done, "cannot rearm terminal");
            check(r.requestStop() == done, "late stop ignored");
            check(r.onFailure(owner, Error.BACKEND_FAILURE) == done, "late failure ignored");
            check(r.onCleanupUnconfirmed(owner) == done, "late timer ignored");
            check(r.onCleanupEvidence(owner, full()) == done, "duplicate cleanup ignored");
        }
        ReadOnlyRecognition fail = fresh(); Owner f = begin(fail);
        State failedPending = fail.onFailure(f, Error.BACKEND_FAILURE);
        check(failedPending.phase == Phase.STOPPING && !failedPending.terminal(), "failure waits for cleanup");
        check(fail.onResult(f, Result.TEXT_PRESENT) == failedPending, "result after error ignored");
        terminal(fail.onCleanupEvidence(f, full()), Phase.FAILED);
        for (Result invalid : new Result[]{null, Result.NONE}) {
            ReadOnlyRecognition r = fresh(); Owner o = begin(r);
            check(r.onResult(o, invalid).error == Error.INVALID_RESULT, "invalid result is typed error");
            terminal(r.onCleanupEvidence(o, full()), Phase.FAILED);
        }
        ReadOnlyRecognition missing = fresh(); Owner o = begin(missing);
        State s = missing.onCleanupEvidence(o, full());
        terminal(s, Phase.FAILED);
        check(s.error == Error.MISSING_RESULT, "cleanup without result fails");
    }
    private static void ownershipAndStop() {
        ReadOnlyRecognition a = fresh(), b = fresh(); Owner x = begin(a), y = begin(b);
        check(x != y, "owners unique across instances");
        State initial = a.snapshot();
        check(a.onResult(y, Result.TEXT_PRESENT) == initial, "foreign result ignored");
        check(a.onFailure(y, Error.BACKEND_FAILURE) == initial, "foreign error ignored");
        check(a.onCleanupEvidence(y, full()) == initial, "foreign cleanup ignored");
        check(a.onCleanupUnconfirmed(y) == initial, "foreign deadline ignored");
        check(a.onCleanupEvidence(null, full()) == initial, "null owner ignored");
        check(a.onDurableClaimCommitted(true) == initial, "repeat begin cannot replace owner");
        a.onResult(x, Result.TEXT_PRESENT);
        State stopping = a.requestStop();
        check(stopping.phase == Phase.STOPPING && stopping.result == Result.NONE, "cancel clears provisional result");
        check(a.requestStop() == stopping, "repeat stop idempotent");
        check(a.onResult(x, Result.TEXT_PRESENT) == stopping, "late result cannot override cancel");
        check(a.onFailure(x, Error.BACKEND_FAILURE) == stopping, "late error cannot override cancel");
        for (int flags = 0; flags < 7; flags++) {
            State s = a.onCleanupEvidence(x, new CleanupEvidence((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0));
            check(s.phase == Phase.CLEANUP_UNCONFIRMED && !s.terminal() && !s.cleanupConfirmed, "partial cleanup never stopped");
            check(a.onDurableClaimCommitted(true) == s, "partial cleanup blocks new begin");
        }
        terminal(a.onCleanupEvidence(x, full()), Phase.CANCELLED);
        check(b.snapshot().phase == Phase.RUNNING, "old cleanup cannot affect new owner");
        ReadOnlyRecognition uncertain = fresh(); Owner u = begin(uncertain);
        uncertain.onCleanupUnconfirmed(u);
        check(uncertain.requestStop().phase == Phase.CLEANUP_UNCONFIRMED, "cancel never downgrades uncertainty");
        terminal(uncertain.onCleanupEvidence(u, full()), Phase.CANCELLED);
        ReadOnlyRecognition lateFailure = fresh(); Owner l = begin(lateFailure);
        lateFailure.onCleanupUnconfirmed(l);
        State failedUncertain = lateFailure.onFailure(l, Error.BACKEND_FAILURE);
        check(failedUncertain.phase == Phase.CLEANUP_UNCONFIRMED, "failure cannot downgrade cleanup uncertainty");
        check(!failedUncertain.stopRequested && !failedUncertain.terminal(), "failure does not invent a stop request or completion");
        terminal(lateFailure.onCleanupEvidence(l, full()), Phase.FAILED);
        State recovered = recoverInterruptedClaim();
        check(recovered.phase == Phase.CLEANUP_UNCONFIRMED && recovered.authorization == Authorization.CONSUMED, "recovery fail closed");
        check(recovered.owner == null && !recovered.cleanupConfirmed && !recovered.terminal(), "recovery not fabricated completion");
    }
    private static void runtimeAlwaysUnbound() {
        for (int sdk : new int[]{34, 35, 36}) {
            UnboundReadOnlyController c = new UnboundReadOnlyController(sdk, "ranchu", "sdk_gphone");
            State state = c.snapshot();
            for (int i = 0; i < 50; i++) check(c.requestRecognition() == state, "runtime accidental/repeated start inert");
            check(c.admission().backend == Backend.UNBOUND && c.admission().authorization == Authorization.NOT_AUTHORIZED, "binding cannot authorize");
            check(state.owner == null && state.phase == Phase.IDLE, "no runtime owner");
        }
    }
    private static void races() throws Exception {
        // 100 repetitions of ONE two-thread schedule, not exhaustive/native concurrency proof.
        for (int i = 0; i < 100; i++) {
            ReadOnlyRecognition r = fresh(); Owner owner = begin(r);
            CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(2);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread stop = new Thread(() -> { try { go.await(); r.requestStop(); } catch (Throwable e) { error.set(e); } finally { done.countDown(); } });
            Thread result = new Thread(() -> { try { go.await(); r.onResult(owner, Result.TEXT_PRESENT); } catch (Throwable e) { error.set(e); } finally { done.countDown(); } });
            stop.start(); result.start(); go.countDown();
            check(done.await(2, TimeUnit.SECONDS), "race harness finished");
            stop.join(); result.join();
            check(error.get() == null, "race no thread failure");
            State s = r.snapshot();
            check(s.phase == Phase.STOPPING && s.stopRequested && s.result == Result.NONE, "stop wins before cleanup");
            terminal(r.onCleanupEvidence(owner, full()), Phase.CANCELLED);
        }
    }
    public static int runAll() throws Exception {
        assertions = 0;
        admission(); outcomes(); ownershipAndStop(); runtimeAlwaysUnbound(); races();
        return assertions;
    }
    public static void main(String[] args) throws Exception { System.out.println("PASS " + runAll() + " assertions (includes repeated schedules)"); }
}


