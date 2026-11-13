package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.once.OnceBackend.Owner;

/**
 * Trusted INTERNAL adapter boundary, not an authorization mechanism. This artifact
 * supplies internal adapters, never a durable consent or public enabling route.
 */
final class BackendPorts {
    private BackendPorts() { }

    /** Cooperative cancellation only; never native completion evidence. */
    static final class Cancelled extends java.io.IOException {
        private static final long serialVersionUID = 1L;
    }

    enum ClaimDecision { CLAIMED, DENIED, DUPLICATE, UNCERTAIN }
    record ClaimReceipt(Owner owner, ClaimDecision decision) { }

    @FunctionalInterface
    interface ClaimBoundary {
        // Must atomically consume the fresh scoped approval before any source call.
        // A receipt is only a model assertion; the real durable boundary is absent.
        ClaimReceipt consume(Owner owner) throws Exception;
    }

    enum Released { CONFIRMED, UNKNOWN }
    enum Quiet { CONFIRMED, UNKNOWN }
    record Completion(Owner owner, Released released, Quiet quiet) { }
    record Recognition(Owner owner, boolean hit) { }

    @FunctionalInterface
    interface StopSignal { boolean requested(); }

    interface Resource {
        // Exactly one release request per invoked port, after its method returns.
        // Return/throw is NOT evidence of release or of native/external exit.
        void release(Owner owner) throws Exception;
        // Read-only evidence query; may be polled. Facts must cover this owner and
        // every retained sink/view, producer, consumer and external/native child.
        Completion completion(Owner owner) throws Exception;
    }

    /** Scalar-only, fresh per source. CAS orders observed revocation vs commit.
     * Events delivered after commit are recorded, but cannot alter sealed output.
     * An OS event whose callback has not arrived cannot be detected here. */
    static final class RevocationFence {
        private static final int REVOKED = 1, COMMITTED = 2;
        private final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger();
        void revoke() { state.getAndUpdate(old -> old | REVOKED); }
        boolean revoked() { return state.get() == REVOKED; }
        boolean observed() { return (state.get() & REVOKED) != 0; }
        boolean tryCommit() {
            for (;;) {
                int old = state.get();
                if ((old & COMMITTED) != 0) return true;
                if ((old & REVOKED) != 0) return false;
                if (state.compareAndSet(old, COMMITTED)) return true;
            }
        }
    }
    record SourceTiming(int width,int height,long readyMs,long acquiredMs,long copiedMs) {
        SourceTiming {
            if(width<=0||height<=0||width>4096||height>4096||(long)width*height>4L*1024*1024
                    ||readyMs< -1||readyMs>60000||acquiredMs< -1||acquiredMs>60000||copiedMs< -1||copiedMs>60000)
                throw new IllegalArgumentException("Unbounded source scalars");
        }
        String summary(){return "Source relative millis (capped 60000; -1 unobserved): producerReady="+readyMs+" firstAcquire="+acquiredMs+" copyFinished="+copiedMs
            +" frame="+width+"x"+height+"; compositorPixelAck=NOT_OBSERVED; targetIdentity=UNKNOWN";}
    }
    interface FrameSource extends Resource {
        default SourceTiming sourceTiming(Owner owner) { return null; }
        /** Cached scalar fence only. No platform calls or cleanup. */
        default RevocationFence revocationFence(Owner owner) { return null; }
        void produce(Owner owner, FrameSink sink, StopSignal stop) throws Exception;
    }
    interface Recognizer extends Resource {
        Recognition recognize(Owner owner, FrameView frame, StopSignal stop) throws Exception;
    }

    interface FrameSink {
        // One tightly packed GRAY8 frame only. No encoded image decoding here.
        void begin(int width, int height);
        void append(byte[] bytes, int offset, int length);
    }
    interface FrameView {
        int width();
        int height();
        int size();
        int unsignedByte(int index);
    }
}