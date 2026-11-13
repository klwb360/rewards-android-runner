package com.aliothmoon.maafw.once;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.aliothmoon.maafw.once.OnceBackend.*;
import com.aliothmoon.maafw.once.SyntheticHarness.*;

/** Deterministic host checks. Latch deadlines detect hung tests, never prove native exit. */
public final class ContractTests {
    private static int checks;
    private static int scenarios;
    private ContractTests() { }
    @FunctionalInterface private interface Test { void run() throws Exception; }
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("check " + checks);
    }
    private static void scenario(String name, Test test) throws Exception {
        test.run(); scenarios++; System.out.println("PASS " + name);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("harness wait"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError("harness interrupted"); }
    }
    private static Thread worker(Runnable body, AtomicReference<Throwable> error) {
        Thread thread = new Thread(() -> { try { body.run(); } catch (Throwable e) { error.set(e); } });
        thread.setDaemon(true); thread.start(); return thread;
    }
    private static void join(Thread thread, AtomicReference<Throwable> error) throws InterruptedException {
        thread.join(3000);
        check(!thread.isAlive());
        if (error.get() != null) throw new AssertionError("worker failed", error.get());
    }
    private static OnceBackend direct(Source source, Recognizer recognizer) {
        return SyntheticHarness.model(new NonDurableLedger(), new FakeApproval(), source, recognizer, Runnable::run);
    }
    private static Owner otherOwner() { return direct(new Source(), new Recognizer()).start().owner(); }
    private static void consumed(OnceBackend backend, Owner expected) {
        Result before = backend.snapshot();
        check(before.admission() == Admission.CONSUMED);
        check(backend.start().equals(before));
        check(before.owner() == expected);
    }
    private static void inaccessible(BackendPorts.FrameView view) {
        boolean denied = false;
        try { view.unsignedByte(0); } catch (IllegalStateException expected) { denied = true; }
        check(denied);
    }
    private static void unbound() {
        System.setProperty("once.live.enabled", "true");
        try {
            OnceBackend backend = new OnceBackend(); Result initial = backend.snapshot();
            check(initial.phase() == Phase.UNBOUND);
            for (int i = 0; i < 5; i++) {
                check(backend.start().equals(initial)); check(backend.cancel(null).equals(initial));
                check(backend.observeTimeout(otherOwner()).equals(initial)); check(backend.refreshEvidence(null).equals(initial));
            }
            check(initial.owner() == null && initial.runId() == 0 && !initial.hit());
            Constructor<?>[] constructors = OnceBackend.class.getConstructors();
            check(constructors.length == 1 && constructors[0].getParameterCount() == 0);
            check(!Modifier.isPublic(BackendPorts.class.getModifiers()));
        } finally { System.clearProperty("once.live.enabled"); }
    }
    private static void successAndRepeatedStart() {
        Source source = new Source(); Recognizer recognizer = new Recognizer();
        NonDurableLedger ledger = new NonDurableLedger(); ManualExecutor queue = new ManualExecutor();
        OnceBackend backend = SyntheticHarness.model(ledger, new FakeApproval(), source, recognizer, queue);
        check(backend.snapshot().phase() == Phase.IDLE);
        Result queued = backend.start();
        check(queued.phase() == Phase.QUEUED && ledger.calls() == 1 && source.calls == 0);
        consumed(backend, queued.owner()); queue.run(); queue.run();
        Result result = backend.snapshot();
        check(result.phase() == Phase.COMPLETED && result.hit());
        check(result.cleanup() == Cleanup.CONFIRMED && result.quiescence() == Quiescence.CONFIRMED);
        check(result.memory() == Memory.WIPED && source.calls == 1 && recognizer.calls == 1);
        check(source.releases == 1 && recognizer.releases == 1); inaccessible(recognizer.retained);
        check(backend.cancel(result.owner()).equals(result)); check(backend.observeTimeout(result.owner()).equals(result));
        consumed(backend, result.owner());
    }
    private static void noHit() {
        Recognizer recognizer = new Recognizer();
        recognizer.action = (owner, view, stop) -> new BackendPorts.Recognition(owner, false);
        Result result = direct(new Source(), recognizer).start();
        check(result.phase() == Phase.COMPLETED && !result.hit() && result.failure() == Failure.NONE);
    }
    private static void duplicateAndDeniedClaim() {
        NonDurableLedger ledger = new NonDurableLedger(); FakeApproval fake = new FakeApproval();
        OnceBackend first = SyntheticHarness.model(ledger, fake, new Source(), new Recognizer(), Runnable::run);
        check(first.start().phase() == Phase.COMPLETED); Source secondSource = new Source();
        OnceBackend second = SyntheticHarness.model(ledger, fake, secondSource, new Recognizer(), Runnable::run);
        Result duplicate = second.start();
        check(duplicate.failure() == Failure.DUPLICATE_CLAIM && secondSource.calls == 0); consumed(second, duplicate.owner());
        Result denied = SyntheticHarness.model(ledger, null, secondSource, new Recognizer(), Runnable::run).start();
        check(denied.failure() == Failure.APPROVAL_DENIED && secondSource.calls == 0);
        // Honest limitation: a brand-new fake ledger forgets every consumed fake.
        check(SyntheticHarness.model(new NonDurableLedger(), fake, new Source(), new Recognizer(), Runnable::run)
                .start().phase() == Phase.COMPLETED);
    }
    private static void claimFailureAndStale() {
        Source source = new Source();
        List<BackendPorts.ClaimBoundary> boundaries = List.of(
                owner -> { throw new Exception("SYNTHETIC_PRIVATE_SENTINEL"); }, owner -> null,
                owner -> new BackendPorts.ClaimReceipt(owner, null),
                owner -> new BackendPorts.ClaimReceipt(owner, BackendPorts.ClaimDecision.UNCERTAIN),
                owner -> new BackendPorts.ClaimReceipt(otherOwner(), BackendPorts.ClaimDecision.CLAIMED));
        for (int i = 0; i < boundaries.size(); i++) {
            OnceBackend backend = new OnceBackend(boundaries.get(i), source, new Recognizer(), Runnable::run);
            Result result = backend.start(); check(result.phase() == Phase.FAILED && !result.hit());
            check(result.failure() == (i == 4 ? Failure.STALE_CLAIM : Failure.CLAIM_UNCERTAIN));
            check(source.calls == 0 && result.quiescence() == Quiescence.NOT_INVOKED); consumed(backend, result.owner());
        }
    }    private static void concurrentStartAndClaimCancel() throws Exception {
        CountDownLatch inClaim = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicReference<Throwable> errors = new AtomicReference<>(); Source source = new Source();
        NonDurableLedger ledger = new NonDurableLedger(); FakeApproval fake = new FakeApproval();
        OnceBackend backend = new OnceBackend(owner -> {
            BackendPorts.ClaimReceipt receipt = ledger.consume(fake, owner);
            inClaim.countDown(); await(proceed); return receipt;
        }, source, new Recognizer(), Runnable::run);
        Thread thread = worker(backend::start, errors); await(inClaim); Result claiming = backend.snapshot();
        check(claiming.phase() == Phase.CLAIMING && claiming.admission() == Admission.CONSUMED);
        check(backend.start().owner() == claiming.owner() && ledger.calls() == 1);
        backend.cancel(claiming.owner()); check(backend.snapshot().phase() == Phase.CLAIMING);
        proceed.countDown(); join(thread, errors);
        check(backend.snapshot().phase() == Phase.CANCELLED && source.calls == 0);
        check(ledger.consume(fake, otherOwner()).decision() == BackendPorts.ClaimDecision.DUPLICATE);
    }
    private static void queuedCancellation() {
        ManualExecutor queue = new ManualExecutor(); Source source = new Source(); Recognizer recognizer = new Recognizer();
        NonDurableLedger ledger = new NonDurableLedger(); FakeApproval fake = new FakeApproval();
        OnceBackend backend = SyntheticHarness.model(ledger, fake, source, recognizer, queue);
        Owner owner = backend.start().owner(); backend.cancel(owner); check(backend.snapshot().phase() == Phase.QUEUED);
        queue.run(); queue.run(); Result result = backend.snapshot();
        check(result.phase() == Phase.CANCELLED && !result.hit());
        check(source.calls == 0 && recognizer.calls == 0 && source.releases == 0);
        check(result.memory() == Memory.NOT_ALLOCATED && result.quiescence() == Quiescence.NOT_INVOKED);
        check(ledger.consume(fake, otherOwner()).decision() == BackendPorts.ClaimDecision.DUPLICATE); consumed(backend, owner);
    }
    private static void blockedSourceCancel() throws Exception {
        CountDownLatch active = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicReference<Throwable> errors = new AtomicReference<>(); Source source = new Source(); Recognizer recognizer = new Recognizer();
        source.action = (owner, sink, stop) -> {
            sink.begin(2, 2); active.countDown(); await(proceed);
            if (!stop.requested()) throw new AssertionError("cancel absent");
            sink.append(new byte[] { 1, 2, 3, 4 }, 0, 4);
        };
        OnceBackend backend = direct(source, recognizer); Thread thread = worker(backend::start, errors); await(active);
        Owner owner = backend.snapshot().owner(); backend.cancel(owner); backend.refreshEvidence(owner);
        check(backend.snapshot().phase() == Phase.SOURCE && source.releases == 0 && source.queries == 0);
        check(backend.snapshot().quiescence() == Quiescence.UNCONFIRMED); consumed(backend, owner);
        proceed.countDown(); join(thread, errors);
        check(backend.snapshot().phase() == Phase.CANCELLED && recognizer.calls == 0);
        check(backend.snapshot().memory() == Memory.WIPED);
    }
    private static void activeRecognizerTimeout() throws Exception {
        CountDownLatch active = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicReference<Throwable> errors = new AtomicReference<>(); Source source = new Source(); Recognizer recognizer = new Recognizer();
        recognizer.action = (owner, frame, stop) -> {
            active.countDown(); await(proceed);
            if (frame.unsignedByte(0) != 1 || !stop.requested()) throw new AssertionError("premature wipe");
            return new BackendPorts.Recognition(owner, true);
        };
        OnceBackend backend = direct(source, recognizer); Thread thread = worker(backend::start, errors); await(active);
        Owner owner = backend.snapshot().owner(); backend.cancel(owner); backend.observeTimeout(owner); backend.refreshEvidence(owner);
        Result activeResult = backend.snapshot();
        check(activeResult.phase() == Phase.RECOGNIZER && !activeResult.terminal());
        check(activeResult.failure() == Failure.OBSERVATION_TIMEOUT && activeResult.memory() == Memory.RETAINED);
        check(recognizer.retained.unsignedByte(0) == 1 && recognizer.releases == 0 && source.releases == 0);
        consumed(backend, owner); proceed.countDown(); join(thread, errors);
        Result result = backend.snapshot();
        check(result.phase() == Phase.FAILED && !result.hit() && result.memory() == Memory.WIPED); inaccessible(recognizer.retained);
    }
    private static void invalidFrames() {
        List<Producer> invalid = List.of(
                (o, s, c) -> s.begin(0, 1), (o, s, c) -> s.begin(-1, 2),
                (o, s, c) -> s.begin(Integer.MAX_VALUE, Integer.MAX_VALUE),
                (o, s, c) -> s.begin(4097, 1), (o, s, c) -> s.begin(4096, 1025),
                (o, s, c) -> { s.begin(1, 1); s.append(new byte[2], 0, 2); },
                (o, s, c) -> { s.begin(1, 1); s.append(new byte[1], Integer.MAX_VALUE, 1); },
                (o, s, c) -> { s.begin(1, 1); s.append(null, 0, 1); },
                (o, s, c) -> { s.begin(1, 1); s.append(new byte[1], 0, -1); }, (o, s, c) -> { },
                (o, s, c) -> { s.begin(2, 2); s.append(new byte[3], 0, 3); },
                (o, s, c) -> { s.begin(1, 1); s.begin(1, 1); },
                (o, s, c) -> { s.begin(1, 1); try { s.append(new byte[2], 0, 2); }
                    catch (OwnedFrame.InvalidFrame ignored) { } });
        for (Producer producer : invalid) {
            Source source = new Source(); source.action = producer; Recognizer recognizer = new Recognizer();
            OnceBackend backend = direct(source, recognizer); Result result = backend.start();
            check(result.phase() == Phase.FAILED && !result.hit() && recognizer.calls == 0);
            check(result.failure() == Failure.FRAME_BOUNDS || result.failure() == Failure.INVALID_FRAME);
            check(result.cleanup() == Cleanup.CONFIRMED && source.releases == 1); consumed(backend, result.owner());
        }
    }
    private static void maximumFrameAndCopy() {
        Source source = new Source(); Recognizer recognizer = new Recognizer();
        source.action = (owner, sink, stop) -> {
            sink.begin(4096, 1024); byte[] block = new byte[4096]; block[0] = 7; block[4095] = 9;
            for (int i = 0; i < 1024; i++) sink.append(block, 0, block.length);
            block[0] = 99; block[4095] = 99;
        };
        recognizer.action = (owner, frame, stop) -> {
            check(frame.size() == OwnedFrame.MAX_BYTES && frame.width() == 4096 && frame.height() == 1024);
            check(frame.unsignedByte(0) == 7 && frame.unsignedByte(frame.size() - 1) == 9);
            return new BackendPorts.Recognition(owner, true);
        };
        check(direct(source, recognizer).start().phase() == Phase.COMPLETED);
    }
    private static void sourceAndRecognizerThrow() {
        Source source = new Source(); Recognizer recognizer = new Recognizer();
        source.action = (o, s, c) -> { s.begin(1, 1); s.append(new byte[] { 1 }, 0, 1);
            throw new Exception("SYNTHETIC_PRIVATE_SENTINEL"); };
        Result sourceResult = direct(source, recognizer).start();
        check(sourceResult.failure() == Failure.SOURCE_FAILURE && sourceResult.memory() == Memory.WIPED);
        check(recognizer.calls == 0 && source.releases == 1);
        Source goodSource = new Source(); Recognizer badRecognizer = new Recognizer();
        badRecognizer.action = (o, f, c) -> { throw new AssertionError("SYNTHETIC_PRIVATE_SENTINEL"); };
        Result recognizeResult = direct(goodSource, badRecognizer).start();
        check(recognizeResult.failure() == Failure.RECOGNIZER_FAILURE && recognizeResult.memory() == Memory.WIPED);
        check(goodSource.releases == 1 && badRecognizer.releases == 1);
        check(!sourceResult.toString().contains("SYNTHETIC_PRIVATE_SENTINEL"));
        check(!recognizeResult.toString().contains("SYNTHETIC_PRIVATE_SENTINEL"));
    }
    private static void staleOwnersAndResults() {
        Owner old = otherOwner(); ManualExecutor queue = new ManualExecutor();
        OnceBackend backend = SyntheticHarness.model(new NonDurableLedger(), new FakeApproval(), new Source(), new Recognizer(), queue);
        Result queued = backend.start(); check(queued.runId() == 1 && old != queued.owner());
        check(backend.cancel(old).equals(queued)); check(backend.observeTimeout(old).equals(queued));
        check(backend.refreshEvidence(old).equals(queued)); queue.run(); check(backend.snapshot().phase() == Phase.COMPLETED);
        Recognizer stale = new Recognizer(); stale.action = (o, f, c) -> new BackendPorts.Recognition(old, true);
        Result staleResult = direct(new Source(), stale).start(); check(staleResult.failure() == Failure.STALE_RESULT && !staleResult.hit());
        Recognizer missing = new Recognizer(); missing.action = (o, f, c) -> null;
        check(direct(new Source(), missing).start().failure() == Failure.INVALID_RESULT);
    }    private static void incompleteEvidenceMatrix() {
        for (int mask = 0; mask < 16; mask++) {
            Source source = new Source(); Recognizer recognizer = new Recognizer();
            source.released = (mask & 1) != 0 ? BackendPorts.Released.CONFIRMED : BackendPorts.Released.UNKNOWN;
            source.quiet = (mask & 2) != 0 ? BackendPorts.Quiet.CONFIRMED : BackendPorts.Quiet.UNKNOWN;
            recognizer.released = (mask & 4) != 0 ? BackendPorts.Released.CONFIRMED : BackendPorts.Released.UNKNOWN;
            recognizer.quiet = (mask & 8) != 0 ? BackendPorts.Quiet.CONFIRMED : BackendPorts.Quiet.UNKNOWN;
            OnceBackend backend = direct(source, recognizer); Result result = backend.start();
            boolean released = (mask & 5) == 5, quiet = (mask & 10) == 10;
            check(result.cleanup() == (released ? Cleanup.CONFIRMED : Cleanup.UNCONFIRMED));
            check(result.quiescence() == (quiet ? Quiescence.CONFIRMED : Quiescence.UNCONFIRMED));
            check(result.memory() == (quiet ? Memory.WIPED : Memory.RETAINED));
            check(result.phase() == (mask == 15 ? Phase.COMPLETED : Phase.CLEANUP_UNCONFIRMED));
            check(result.hit() == (mask == 15));
            check(source.releases == 1 && recognizer.releases == 1);
            consumed(backend, result.owner());
            if (!quiet) check(recognizer.retained.unsignedByte(0) == 1);
            else inaccessible(recognizer.retained);
            source.released = BackendPorts.Released.CONFIRMED; source.quiet = BackendPorts.Quiet.CONFIRMED;
            recognizer.released = BackendPorts.Released.CONFIRMED; recognizer.quiet = BackendPorts.Quiet.CONFIRMED;
            Result completed = backend.refreshEvidence(result.owner());
            check(completed.phase() == Phase.COMPLETED && completed.hit() && completed.memory() == Memory.WIPED);
            check(source.releases == 1 && recognizer.releases == 1); inaccessible(recognizer.retained);
        }
    }
    private static void releaseFailureAndStaleEvidence() {
        Source source = new Source(); Recognizer recognizer = new Recognizer(); source.throwRelease = true;
        OnceBackend backend = direct(source, recognizer); Result result = backend.start();
        check(result.phase() == Phase.FAILED && result.failure() == Failure.CLEANUP_FAILURE);
        check(result.cleanup() == Cleanup.CONFIRMED && result.memory() == Memory.WIPED && !result.hit());
        check(source.releases == 1 && recognizer.releases == 1);
        backend.refreshEvidence(result.owner()); check(source.releases == 1);
        Source stale = new Source(); stale.staleEvidence = true; stale.evidenceOwner = otherOwner();
        OnceBackend staleBackend = direct(stale, new Recognizer()); Result unknown = staleBackend.start();
        check(unknown.phase() == Phase.CLEANUP_UNCONFIRMED && unknown.failure() == Failure.STALE_COMPLETION);
        check(unknown.memory() == Memory.RETAINED && !unknown.hit());
        stale.staleEvidence = false; Result finalResult = staleBackend.refreshEvidence(unknown.owner());
        check(finalResult.phase() == Phase.FAILED && finalResult.failure() == Failure.STALE_COMPLETION);
        check(finalResult.memory() == Memory.WIPED && stale.releases == 1);
    }
    private static void evidenceThrowsAndCancellation() {
        Source source = new Source(); Recognizer recognizer = new Recognizer(); source.throwEvidence = true;
        OnceBackend backend = direct(source, recognizer); Result pending = backend.start();
        check(pending.phase() == Phase.CLEANUP_UNCONFIRMED && pending.failure() == Failure.EVIDENCE_UNCERTAIN);
        check(pending.memory() == Memory.RETAINED && !pending.hit());
        backend.cancel(pending.owner()); source.throwEvidence = false;
        Result failed = backend.refreshEvidence(pending.owner());
        check(failed.phase() == Phase.FAILED && failed.cancellation() == Cancellation.REQUESTED);
        check(failed.memory() == Memory.WIPED && !failed.hit());
        Source waiting = new Source(); waiting.quiet = BackendPorts.Quiet.UNKNOWN;
        OnceBackend cancelBackend = direct(waiting, new Recognizer()); Result before = cancelBackend.start();
        check(before.phase() == Phase.CLEANUP_UNCONFIRMED); cancelBackend.cancel(before.owner());
        waiting.quiet = BackendPorts.Quiet.CONFIRMED; Result cancelled = cancelBackend.refreshEvidence(before.owner());
        check(cancelled.phase() == Phase.CANCELLED && !cancelled.hit());
    }
    private static void blockedReleaseDoesNotProveExit() throws Exception {
        CountDownLatch active = new CountDownLatch(1), proceed = new CountDownLatch(1);
        Source source = new Source(); Recognizer recognizer = new Recognizer();
        recognizer.onRelease = () -> { active.countDown(); await(proceed); };
        AtomicReference<Throwable> errors = new AtomicReference<>(); OnceBackend backend = direct(source, recognizer);
        Thread thread = worker(backend::start, errors); await(active); Owner owner = backend.snapshot().owner();
        backend.cancel(owner); backend.observeTimeout(owner); backend.refreshEvidence(owner);
        Result retained = backend.snapshot();
        check(retained.phase() == Phase.CLEANING && !retained.terminal());
        check(retained.memory() == Memory.RETAINED && recognizer.retained.unsignedByte(0) == 1);
        check(source.releases == 0 && source.queries == 0 && recognizer.queries == 0);
        proceed.countDown(); join(thread, errors);
        check(backend.snapshot().phase() == Phase.FAILED && backend.snapshot().memory() == Memory.WIPED);
    }
    private static void blockedEvidenceAndRepeatedRefresh() throws Exception {
        Source source = new Source(); source.quiet = BackendPorts.Quiet.UNKNOWN;
        Recognizer recognizer = new Recognizer(); OnceBackend backend = direct(source, recognizer);
        Owner owner = backend.start().owner();
        CountDownLatch active = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicReference<Throwable> errors = new AtomicReference<>();
        source.onEvidence = () -> { active.countDown(); await(proceed); };
        Thread thread = worker(() -> backend.refreshEvidence(owner), errors); await(active);
        int queries = source.queries; backend.refreshEvidence(owner); backend.cancel(owner); backend.observeTimeout(owner);
        check(source.queries == queries && backend.snapshot().memory() == Memory.RETAINED);
        check(!backend.snapshot().terminal() && !backend.snapshot().hit());
        proceed.countDown(); join(thread, errors);
        source.onEvidence = () -> { }; source.quiet = BackendPorts.Quiet.CONFIRMED;
        Result result = backend.refreshEvidence(owner);
        check(result.phase() == Phase.FAILED && result.memory() == Memory.WIPED);
        check(source.releases == 1 && recognizer.releases == 1);
    }
    private static void dispatchRejectionAndUncertainQueue() {
        List<Runnable> jobs = new ArrayList<>(); Source source = new Source();
        OnceBackend backend = SyntheticHarness.model(new NonDurableLedger(), new FakeApproval(), source, new Recognizer(),
            job -> { jobs.add(job); throw new IllegalStateException("SYNTHETIC_PRIVATE_SENTINEL"); });
        Result failed = backend.start();
        check(failed.phase() == Phase.FAILED && failed.failure() == Failure.DISPATCH_UNCERTAIN);
        jobs.get(0).run(); jobs.get(0).run(); check(source.calls == 0 && source.releases == 0);
        check(backend.snapshot().equals(failed)); consumed(backend, failed.owner());
        OnceBackend rejected = SyntheticHarness.model(new NonDurableLedger(), new FakeApproval(), new Source(), new Recognizer(),
            job -> { throw new java.util.concurrent.RejectedExecutionException(); });
        check(rejected.start().failure() == Failure.DISPATCH_UNCERTAIN);
        OnceBackend completedThenThrow = SyntheticHarness.model(new NonDurableLedger(), new FakeApproval(), new Source(),
            new Recognizer(), job -> { job.run(); throw new IllegalStateException(); });
        check(completedThenThrow.start().phase() == Phase.COMPLETED);
    }
    private static void duplicateConcurrentJobs() throws Exception {
        Source source = new Source(); Recognizer recognizer = new Recognizer(); ManualExecutor queue = new ManualExecutor();
        CountDownLatch active = new CountDownLatch(1), proceed = new CountDownLatch(1);
        source.action = (owner, sink, stop) -> { active.countDown(); await(proceed); sink.begin(1, 1); sink.append(new byte[]{1},0,1); };
        OnceBackend backend = SyntheticHarness.model(new NonDurableLedger(), new FakeApproval(), source, recognizer, queue);
        Owner owner = backend.start().owner(); AtomicReference<Throwable> errors = new AtomicReference<>();
        Thread thread = worker(queue::run, errors); await(active);
        queue.run(); backend.start(); check(source.calls == 1 && !backend.snapshot().terminal());
        backend.cancel(owner); proceed.countDown(); join(thread, errors); queue.run();
        check(source.calls == 1 && recognizer.calls == 0 && source.releases == 1);
        check(backend.snapshot().phase() == Phase.CANCELLED);
    }
    private static void publicResultSchema() {
        for (RecordComponent field : Result.class.getRecordComponents()) {
            Class<?> type = field.getType();
            check(type.isEnum() || type == Owner.class || type == long.class || type == boolean.class);
            check(type != String.class && !type.isArray());
        }
        for (java.lang.reflect.Method method : OnceBackend.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                check(method.getReturnType() == Result.class);
            }
        }
    }
    private static void sealedViewIsImmutable() {
        for (boolean begin : new boolean[]{false, true}) {
            Source source = new Source(); Recognizer recognizer = new Recognizer();
            AtomicReference<BackendPorts.FrameSink> retained = new AtomicReference<>();
            source.action = (owner, sink, stop) -> {
                retained.set(sink); sink.begin(2, 2); sink.append(new byte[]{1, 2, 3, 4}, 0, 4);
            };
            recognizer.action = (owner, frame, stop) -> {
                check(frame.unsignedByte(0) == 1); boolean rejected = false;
                try {
                    if (begin) retained.get().begin(9, 9);
                    else retained.get().append(new byte[]{99}, 0, 1);
                } catch (OwnedFrame.InvalidFrame expected) { rejected = true; }
                check(rejected); check(frame.width() == 2 && frame.height() == 2 && frame.size() == 4);
                check(frame.unsignedByte(0) == 1 && frame.unsignedByte(3) == 4);
                return new BackendPorts.Recognition(owner, true);
            };
            Result result = direct(source, recognizer).start();
            check(result.phase() == Phase.COMPLETED && result.hit() && result.memory() == Memory.WIPED);
        }
    }
    private static void wipedMemoryCannotRegress() {
        for (boolean throwsEvidence : new boolean[]{false, true}) {
            Source source = new Source(); Recognizer recognizer = new Recognizer();
            source.released = BackendPorts.Released.UNKNOWN;
            OnceBackend backend = direct(source, recognizer); Result first = backend.start();
            check(first.phase() == Phase.CLEANUP_UNCONFIRMED && first.memory() == Memory.WIPED);
            inaccessible(recognizer.retained);
            source.quiet = BackendPorts.Quiet.UNKNOWN; source.throwEvidence = throwsEvidence;
            Result uncertain = backend.refreshEvidence(first.owner());
            check(uncertain.phase() == Phase.CLEANUP_UNCONFIRMED && uncertain.memory() == Memory.WIPED);
            check(uncertain.quiescence() == Quiescence.UNCONFIRMED && !uncertain.hit());
            inaccessible(recognizer.retained);
            source.throwEvidence = false; source.quiet = BackendPorts.Quiet.CONFIRMED;
            source.released = BackendPorts.Released.CONFIRMED;
            Result result = backend.refreshEvidence(first.owner());
            check(result.phase() == (throwsEvidence ? Phase.FAILED : Phase.COMPLETED));
            check(result.memory() == Memory.WIPED && source.releases == 1 && recognizer.releases == 1);
        }
    }
    private static void dispatchThrowsAfterEntry() throws Exception {
        CountDownLatch active = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicReference<Throwable> errors = new AtomicReference<>(); AtomicReference<Thread> thread = new AtomicReference<>();
        Source source = new Source(); Recognizer recognizer = new Recognizer();
        source.action = (owner, sink, stop) -> {
            active.countDown(); await(proceed); sink.begin(1,1); sink.append(new byte[]{1},0,1);
        };
        OnceBackend backend = SyntheticHarness.model(new NonDurableLedger(),new FakeApproval(),source,recognizer, job -> {
            thread.set(worker(job, errors)); await(active); throw new IllegalStateException();
        });
        Result running = backend.start();
        check(running.phase() == Phase.SOURCE && !running.terminal());
        check(running.failure() == Failure.DISPATCH_UNCERTAIN && running.cancellation() == Cancellation.REQUESTED);
        check(source.releases == 0 && !running.hit());
        proceed.countDown(); join(thread.get(), errors);
        check(backend.snapshot().phase() == Phase.FAILED && recognizer.calls == 0 && source.releases == 1);
    }
    private static void blockedSourceBeforeAllocation() throws Exception {
        CountDownLatch active = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicReference<Throwable> errors = new AtomicReference<>(); Source source = new Source();
        source.action = (owner, sink, stop) -> {
            active.countDown(); await(proceed); sink.begin(1,1); sink.append(new byte[]{1},0,1);
        };
        OnceBackend backend=direct(source,new Recognizer()); Thread thread=worker(backend::start, errors); await(active);
        Result before=backend.snapshot();
        check(before.phase()==Phase.SOURCE && before.memory()==Memory.NOT_ALLOCATED);
        check(before.cleanup()==Cleanup.PENDING && before.quiescence()==Quiescence.UNCONFIRMED);
        backend.cancel(before.owner());
        check(backend.snapshot().memory()==Memory.NOT_ALLOCATED && !backend.snapshot().terminal());
        proceed.countDown(); join(thread,errors);
        check(backend.snapshot().phase()==Phase.CANCELLED && backend.snapshot().memory()==Memory.WIPED);
    }
    public static void main(String[] args) throws Exception {
        scenario("public default is permanently unbound", ContractTests::unbound);
        scenario("one frame, one dispatch and no repeated start", ContractTests::successAndRepeatedStart);
        scenario("no-text result", ContractTests::noHit);
        scenario("synthetic duplicate and denied claim", ContractTests::duplicateAndDeniedClaim);
        scenario("uncertain and foreign claim", ContractTests::claimFailureAndStale);
        scenario("concurrent start and cancellation during claim", ContractTests::concurrentStartAndClaimCancel);
        scenario("queued cancellation", ContractTests::queuedCancellation);
        scenario("blocked source retains ownership", ContractTests::blockedSourceCancel);
        scenario("active recognizer timeout retains ownership", ContractTests::activeRecognizerTimeout);
        scenario("invalid and incomplete frames fail closed", ContractTests::invalidFrames);
        scenario("maximum frame and source-copy isolation", ContractTests::maximumFrameAndCopy);
        scenario("source and recognizer failure privacy", ContractTests::sourceAndRecognizerThrow);
        scenario("foreign owners and late results", ContractTests::staleOwnersAndResults);
        scenario("all sixteen cleanup and quiescence combinations", ContractTests::incompleteEvidenceMatrix);
        scenario("release failures and foreign completion", ContractTests::releaseFailureAndStaleEvidence);
        scenario("uncertain completion and pending cancellation", ContractTests::evidenceThrowsAndCancellation);
        scenario("blocked release is not exit proof", ContractTests::blockedReleaseDoesNotProveExit);
        scenario("blocked evidence and concurrent refresh", ContractTests::blockedEvidenceAndRepeatedRefresh);
        scenario("dispatch rejection and queue-then-throw", ContractTests::dispatchRejectionAndUncertainQueue);
        scenario("duplicate concurrent executor callbacks", ContractTests::duplicateConcurrentJobs);
        scenario("public metadata-only result schema", ContractTests::publicResultSchema);
        scenario("sealed view survives rejected late source writes", ContractTests::sealedViewIsImmutable);
        scenario("irreversible memory wipe never regresses", ContractTests::wipedMemoryCannotRegress);
        scenario("dispatch throws after worker entry", ContractTests::dispatchThrowsAfterEntry);
        scenario("source blocked before allocation reports no pixels", ContractTests::blockedSourceBeforeAllocation);
        System.out.println("PASS " + scenarios + " named synthetic scenarios; " + checks + " assertions.");
        System.out.println("No durable claim, Android, real capture, OCR engine or native lifetime proof.");
    }
}
