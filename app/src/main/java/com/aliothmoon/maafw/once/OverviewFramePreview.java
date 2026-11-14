package com.aliothmoon.maafw.once;

import java.util.Arrays;

/** Owned local-only ARGB from the same PNG audit. A borrowed buffer never grants input authority. */
final class OverviewFramePreview implements AutoCloseable {
    @FunctionalInterface interface PixelConsumer<T> {
        T accept(int width, int height, int[] argb) throws Exception;
    }
    private final int width, height;
    private int[] pixels;
    private int nextRow;
    private boolean completed, closed, borrowed;
    OverviewFramePreview(int width, int height) {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || (long)width * height > 4L * 1024 * 1024) throw new IllegalArgumentException("Bounded preview required");
        this.width = width; this.height = height; pixels = new int[width * height];
    }
    synchronized void row(int y, int[] row) {
        if (closed || completed || borrowed || y != nextRow || row == null || row.length != width || y >= height)
            throw new IllegalStateException("Exact single ordered preview row required");
        System.arraycopy(row, 0, pixels, y * width, width); nextRow++;
    }
    synchronized void complete() {
        if (closed || completed || nextRow != height) throw new IllegalStateException("Preview incomplete");
        completed = true;
    }
    synchronized boolean matchesGray(byte[] gray) {
        if (closed || !completed || gray == null || gray.length != pixels.length) return false;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            if ((p >>> 24) != 255 || (((77*((p>>>16)&255)+150*((p>>>8)&255)+29*(p&255)+128)>>>8)&255) != (gray[i]&255)) return false;
        }
        return true;
    }
    synchronized <T> T withPixels(PixelConsumer<T> consumer) throws Exception {
        if (closed || !completed || borrowed || consumer == null) throw new IllegalStateException("Preview unavailable");
        borrowed = true;
        try { return consumer.accept(width, height, pixels); }
        finally { borrowed = false; }
    }
    @Override public synchronized void close() {
        if (borrowed) throw new IllegalStateException("Preview callback active");
        if (!closed) { Arrays.fill(pixels, 0); pixels = null; closed = true; }
    }
    synchronized boolean knownReleased() { return closed && pixels == null && !borrowed; }
}
