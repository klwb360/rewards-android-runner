package com.aliothmoon.maafw.once;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Executor;
import com.aliothmoon.maafw.once.OnceBackend.Owner;

/** TEST ONLY. Entirely in-memory, non-durable and unrelated to any real claim path. */
final class SyntheticHarness {
    private SyntheticHarness() { }
    static final class FakeApproval { /* Identity label, NOT user consent or security. */ }
    static final class NonDurableLedger {
        private final Set<FakeApproval> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        private int calls;
        synchronized BackendPorts.ClaimReceipt consume(FakeApproval fake, Owner owner) {
            calls++;
            BackendPorts.ClaimDecision status = fake == null ? BackendPorts.ClaimDecision.DENIED
                    : consumed.add(fake) ? BackendPorts.ClaimDecision.CLAIMED : BackendPorts.ClaimDecision.DUPLICATE;
            return new BackendPorts.ClaimReceipt(owner, status);
        }
        synchronized int calls() { return calls; }
    }
    static class Resource implements BackendPorts.Resource {
        int releases;
        int queries;
        BackendPorts.Released released = BackendPorts.Released.CONFIRMED;
        BackendPorts.Quiet quiet = BackendPorts.Quiet.CONFIRMED;
        Owner evidenceOwner;
        boolean staleEvidence;
        boolean throwRelease;
        boolean throwEvidence;
        Runnable onRelease = () -> { };
        Runnable onEvidence = () -> { };
        @Override public void release(Owner owner) {
            releases++;
            onRelease.run();
            if (throwRelease) throw new IllegalStateException("SYNTHETIC_PRIVATE_SENTINEL");
        }
        @Override public BackendPorts.Completion completion(Owner owner) {
            queries++;
            onEvidence.run();
            if (throwEvidence) throw new IllegalStateException("SYNTHETIC_PRIVATE_SENTINEL");
            return new BackendPorts.Completion(staleEvidence ? evidenceOwner : owner, released, quiet);
        }
    }
    @FunctionalInterface
    interface Producer {
        void produce(Owner owner, BackendPorts.FrameSink sink, BackendPorts.StopSignal stop) throws Exception;
    }
    static final class Source extends Resource implements BackendPorts.FrameSource {
        int calls;
        Producer action = (owner, sink, stop) -> {
            sink.begin(2, 2);
            sink.append(new byte[] { 1, 2, 3, 4 }, 0, 4);
        };
        @Override public void produce(Owner owner, BackendPorts.FrameSink sink, BackendPorts.StopSignal stop) throws Exception {
            calls++;
            action.produce(owner, sink, stop);
        }
    }
    @FunctionalInterface
    interface RecognitionAction {
        BackendPorts.Recognition recognize(Owner owner, BackendPorts.FrameView frame, BackendPorts.StopSignal stop) throws Exception;
    }
    static final class Recognizer extends Resource implements BackendPorts.Recognizer {
        int calls;
        BackendPorts.FrameView retained;
        RecognitionAction action = (owner, frame, stop) -> new BackendPorts.Recognition(owner, frame.unsignedByte(0) == 1);
        @Override public BackendPorts.Recognition recognize(Owner owner, BackendPorts.FrameView frame,
                BackendPorts.StopSignal stop) throws Exception {
            calls++;
            retained = frame;
            return action.recognize(owner, frame, stop);
        }
    }
    static final class ManualExecutor implements Executor {
        Runnable queued;
        @Override public void execute(Runnable job) {
            if (queued != null) throw new IllegalStateException();
            queued = job;
        }
        void run() { queued.run(); }
    }
    static OnceBackend model(NonDurableLedger ledger, FakeApproval approval,
            Source source, Recognizer recognizer, Executor executor) {
        return new OnceBackend(owner -> ledger.consume(approval, owner), source, recognizer, executor);
    }
}