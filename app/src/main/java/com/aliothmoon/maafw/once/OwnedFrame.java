package com.aliothmoon.maafw.once;

import java.util.Arrays;

/** A single bounded internal allocation. No array, buffer or string getter exists. */
final class OwnedFrame {
    static final int MAX_DIMENSION = 4096;
    static final int MAX_BYTES = 4 * 1024 * 1024;

    static final class InvalidFrame extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final OnceBackend.Failure failure;
        InvalidFrame(OnceBackend.Failure failure) {
            super(null, null, false, false);
            this.failure = failure;
        }
        OnceBackend.Failure failure() { return failure; }
    }

    private int width;
    private int height;
    private int written;
    private byte[] bytes;
    private boolean sealed;
    private boolean wiped;
    private boolean rejected;
    private final BackendPorts.FrameSink sink = new BackendPorts.FrameSink() {
        @Override public void begin(int w, int h) { OwnedFrame.this.begin(w, h); }
        @Override public void append(byte[] b, int offset, int length) {
            OwnedFrame.this.append(b, offset, length);
        }
    };
    private final BackendPorts.FrameView view = new BackendPorts.FrameView() {
        @Override public int width() { synchronized (OwnedFrame.this) { checkReadable(); return width; } }
        @Override public int height() { synchronized (OwnedFrame.this) { checkReadable(); return height; } }
        @Override public int size() { synchronized (OwnedFrame.this) { checkReadable(); return bytes.length; } }
        @Override public int unsignedByte(int index) {
            synchronized (OwnedFrame.this) {
                checkReadable();
                if (index < 0 || index >= bytes.length) throw new IndexOutOfBoundsException();
                return bytes[index] & 255;
            }
        }
    };

    BackendPorts.FrameSink sink() { return sink; }
    BackendPorts.FrameView view() { return view; }
    private InvalidFrame reject(OnceBackend.Failure failure) {
        rejected = true;
        return new InvalidFrame(failure);
    }
    private synchronized void begin(int w, int h) {
        if (sealed || wiped) throw new InvalidFrame(OnceBackend.Failure.INVALID_FRAME);
        if (bytes != null || rejected) throw reject(OnceBackend.Failure.INVALID_FRAME);
        long size = (long) w * h;
        if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION || size > MAX_BYTES) {
            throw reject(OnceBackend.Failure.FRAME_BOUNDS);
        }
        width = w;
        height = h;
        bytes = new byte[(int) size];
    }
    private synchronized void append(byte[] value, int offset, int length) {
        if (sealed || wiped) throw new InvalidFrame(OnceBackend.Failure.INVALID_FRAME);
        if (rejected || bytes == null) throw reject(OnceBackend.Failure.INVALID_FRAME);
        if (value == null || offset < 0 || length < 0 || offset > value.length
                || length > value.length - offset || length > bytes.length - written) {
            throw reject(OnceBackend.Failure.FRAME_BOUNDS);
        }
        System.arraycopy(value, offset, bytes, written, length);
        written += length;
    }
    synchronized boolean seal() {
        sealed = true;
        return !rejected && !wiped && bytes != null && written == bytes.length;
    }
    private void checkReadable() {
        if (!sealed || rejected || wiped || bytes == null || written != bytes.length) {
            throw new IllegalStateException();
        }
    }
    synchronized boolean rejected() { return rejected; }
    synchronized boolean allocated() { return bytes != null; }
    synchronized boolean wiped() { return wiped; }
    // Called ONLY after all invoked ports explicitly report owner-matching quiet.
    synchronized void wipeAfterQuiescence() {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
        wiped = true;
        sealed = true;
    }
}