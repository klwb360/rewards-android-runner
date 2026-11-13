package com.aliothmoon.maafw.once;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.os.Build;
import android.view.Surface;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Internal one-shot media adapter. No display/capture API, listener, preview, GPU,
 * restart, permission, or consent implementation exists here. A trusted producer
 * must target only this owner's private Surface. The normal product is UNBOUND.
 *
 * One calling worker owns every platform call, late result and cleanup. Stop is
 * cooperative: an admitted call may enter after stop and remains pinned until it
 * actually returns. Neither a deadline nor a thread interrupt proves native exit.
 */
final class PollingFrameSource implements BackendPorts.FrameSource, CleanupEvidence.Provider {
    interface ProducerFactory {
        default void validateOwner(OnceBackend.Owner owner) { }
        default BackendPorts.RevocationFence revocationFence(OnceBackend.Owner owner) { return null; }
        default Producer open(OnceBackend.Owner owner, Surface target, BackendPorts.StopSignal stop) throws Exception {
            return open(target, stop);
        }
        /**
         * Return a producer owning all resulting producer resources. An exception
         * is uncertain: this adapter retains the reader rather than infer detach.
         * No autonomous work may outlive Producer.close's successful return.
         */
        Producer open(Surface target, BackendPorts.StopSignal stop) throws Exception;
    }
    interface Producer {
        void produce(BackendPorts.StopSignal stop) throws Exception;
        /** Scalar geometry admission, under the original source budget. */
        default boolean ready() { return true; }
        /** Scalar full-resource state, separate from permission to close reader. */
        default boolean fullyClosed() { return true; }
        /** Return only after producer calls/children ended and target refs dropped.
         * The shared reader Surface must NOT be released by the producer. */
        void close() throws Exception;
    }
    private final CleanupEvidence.Ledger receipts = new CleanupEvidence.Ledger();
    @Override public CleanupEvidence.Snapshot evidenceSnapshot(OnceBackend.Owner expected) { CleanupEvidence.Snapshot local = receipts.snapshot(expected);
        return factory instanceof CleanupEvidence.Provider evidence ? local.plus(evidence.evidenceSnapshot(expected)) : local; }
    private void waiting(CleanupEvidence.Item item) { receipts.set(item, CleanupEvidence.State.WAITING); }
    private void confirmed(CleanupEvidence.Item item) { receipts.set(item, CleanupEvidence.State.CONFIRMED); }
    private static final long BUDGET_NANOS = 5_000_000_000L;
    private static final Set<PollingFrameSource> LIVE = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final int MAX_RETAINED = 4;
    private final Object lock = new Object();
    private final int width;
    private final int height;
    private final ProducerFactory factory;
    private OnceBackend.Owner owner;
    private boolean workerActive;
    private boolean bodyReturned;
    private boolean releaseClaimed;
    private boolean clean;
    private boolean anchored;
    private boolean creationUncertain;
    // Set only by the owner worker, never accessed while workerActive by release.
    private ImageReader reader;
    private Surface surface;
    private Producer producer;
    private Image image;
    private BackendPorts.RevocationFence fence;
    private long readyMs=-1,acquiredMs=-1,copiedMs=-1;
    private static long elapsedMs(long start){return Math.max(0,Math.min(60000,(SystemClock.elapsedRealtimeNanos()-start)/1000000));}
    @Override public BackendPorts.SourceTiming sourceTiming(OnceBackend.Owner expected){
        synchronized(lock){return expected!=null&&expected==owner&&clean&&bodyReturned&&!workerActive&&releaseClaimed
            ?new BackendPorts.SourceTiming(width,height,readyMs,acquiredMs,copiedMs):null;}
    }
    @Override public BackendPorts.RevocationFence revocationFence(OnceBackend.Owner expected) {
        synchronized (lock) {
            if (expected == null || expected != owner) throw new SecurityException("Wrong source owner");
            return fence;
        }
    }

    PollingFrameSource(int width, int height, ProducerFactory factory) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0 || width > OwnedFrame.MAX_DIMENSION
                || height > OwnedFrame.MAX_DIMENSION || pixels > OwnedFrame.MAX_BYTES)
            throw new IllegalArgumentException();
        this.width = width;
        this.height = height;
        this.factory = Objects.requireNonNull(factory);
    }
    private static void check(BackendPorts.StopSignal stop, long started) throws IOException {
        if (stop.requested()) throw new BackendPorts.Cancelled();
        if (SystemClock.elapsedRealtimeNanos() - started >= BUDGET_NANOS)
            throw new IOException();
    }
    @Override public void produce(OnceBackend.Owner expected, BackendPorts.FrameSink sink,
            BackendPorts.StopSignal stop) throws Exception {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(sink);
        Objects.requireNonNull(stop);
        synchronized (lock) {
            if (owner != null) throw new IllegalStateException();
            owner = expected; receipts.bind(expected);
            workerActive = true;
        }
        final long started = SystemClock.elapsedRealtimeNanos();
        final BackendPorts.StopSignal combined = () -> stop.requested() ||
                (fence != null && fence.revoked());
        try {
            factory.validateOwner(expected);
            synchronized (lock) { fence = factory.revocationFence(expected); }
            // Fail before any media creation or uncertain-creation receipt on API 28.
            // The CPU usage overload is required; do not silently weaken it via a fallback.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw new IOException("API 29 required");
            synchronized (LIVE) {
                if (LIVE.size() >= MAX_RETAINED) throw new IOException();
                LIVE.add(this);
                anchored = true;
            }
            check(combined, started);
            // Entire call is retained by workerActive; adopt late returned handle
            // BEFORE checking cancellation. No constructor/finalizer guessing.
            creationUncertain = true;
            waiting(CleanupEvidence.Item.READER_CLOSE);
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
                    HardwareBuffer.USAGE_CPU_READ_OFTEN | HardwareBuffer.USAGE_CPU_WRITE_OFTEN);
            creationUncertain = false;
            check(combined, started);
            surface = reader.getSurface();
            check(combined, started);
            creationUncertain = true;
            waiting(CleanupEvidence.Item.PRODUCER_CLOSE);
            producer = Objects.requireNonNull(factory.open(expected, surface, combined));
            synchronized (lock) { fence = factory.revocationFence(expected); }
            creationUncertain = false;
            check(combined, started);
            producer.produce(combined);
            while (!producer.ready()) {
                check(combined, started);
                try { Thread.sleep(8); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IOException(interrupted);
                }
            }
            check(combined, started);
            readyMs=elapsedMs(started);
            while (image == null) {
                // Each check admits at most ONE polling call. It does not pretend
                // admission and actual native entry are atomic with cancellation.
                check(combined, started);
                image = reader.acquireNextImage();
                if(image!=null) {acquiredMs=elapsedMs(started);waiting(CleanupEvidence.Item.MEDIA_IMAGE_CLOSE);}
                if (image == null) {
                    try { Thread.sleep(8); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException();
                    }
                }
            }
            check(combined, started);
            Rect crop = image.getCropRect();
            if (image.getFormat() != PixelFormat.RGBA_8888 || image.getWidth() != width
                    || image.getHeight() != height || crop.left != 0 || crop.top != 0
                    || crop.right != width || crop.bottom != height) throw new IOException();
            Image.Plane[] planes = image.getPlanes();
            if (planes.length != 1) throw new IOException();
            Gray8Copy.copy(planes[0].getBuffer(), width, height, planes[0].getRowStride(),
                    planes[0].getPixelStride(), sink,
                    () -> combined.requested() || SystemClock.elapsedRealtimeNanos() - started >= BUDGET_NANOS);
            copiedMs=elapsedMs(started);
            // No ByteBuffer/sink/view retained; media cleanup precedes OCR admission.
        } finally {
            try { cleanupMedia(); }
            catch(Exception | Error failed) { receipts.unknownOutstanding(); throw failed; }
            finally { synchronized (lock) { workerActive = false; bodyReturned = true; } }
        }
    }
    @Override public void release(OnceBackend.Owner expected) throws Exception {
        synchronized (lock) {
            if (expected == null || owner != expected || workerActive || !bodyReturned
                    || releaseClaimed) throw new IllegalStateException();
            releaseClaimed = true;
        }
        // Media cleanup already ran on the source worker BEFORE OCR admission.
        // Never retry any uncertain native operation from this acknowledgement.
        if (clean && anchored) synchronized (LIVE) { LIVE.remove(this); anchored = false; }
    }
    private void cleanupMedia() throws Exception {
        // No platform call under lock. Called once, in produce finally, while
        // workerActive still pins all native call/creator/adoption ownership.
        if (creationUncertain) throw new IOException();
        boolean allProducerResourcesClosed = true;
        if (producer != null) {
            producer.close(); // throws unless target-specific reader permission exists
            allProducerResourcesClosed = producer.fullyClosed();
            if (allProducerResourcesClosed) { confirmed(CleanupEvidence.Item.PRODUCER_CLOSE); producer = null; }
            else receipts.set(CleanupEvidence.Item.PRODUCER_CLOSE, CleanupEvidence.State.UNKNOWN);
        }
        if (image != null) { image.close(); confirmed(CleanupEvidence.Item.MEDIA_IMAGE_CLOSE); image = null; }
        if (reader != null) { reader.close(); confirmed(CleanupEvidence.Item.READER_CLOSE); reader = null; surface = null; }
        // ImageReader.close releases its Java Surface; no duplicate release here.
        synchronized (lock) { clean = allProducerResourcesClosed; }
    }
    @Override public BackendPorts.Completion completion(OnceBackend.Owner expected) {
        synchronized (lock) {
            if (expected == null || owner != expected) throw new IllegalArgumentException();
            boolean confirmed = clean && bodyReturned && !workerActive && releaseClaimed;
            return new BackendPorts.Completion(owner,
                    confirmed ? BackendPorts.Released.CONFIRMED : BackendPorts.Released.UNKNOWN,
                    confirmed ? BackendPorts.Quiet.CONFIRMED : BackendPorts.Quiet.UNKNOWN);
        }
    }
}