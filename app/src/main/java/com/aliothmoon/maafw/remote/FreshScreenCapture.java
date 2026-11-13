package com.aliothmoon.maafw.remote;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Captures through one new, synchronous system screenshot request. The caller
 * must first verify the shell identity, supported Android build and display.
 * No screenshot is written to a file or emitted to a log.
 */
public final class FreshScreenCapture {
    public interface Clock { long nowMs(); }
    public interface Starter { Process start() throws Exception; }
    public static final class Frame {
        public final byte[] png;
        public final int width, height;
        public final long startMs, endMs;
        private Frame(byte[] png, int width, int height, long startMs, long endMs) {
            this.png = png; this.width = width; this.height = height;
            this.startMs = startMs; this.endMs = endMs;
        }
        public void erase() { Arrays.fill(png, (byte) 0); }
    }
    private static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10};
    private static final int MAX_PNG = 16 * 1024 * 1024;
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);
    private FreshScreenCapture() {}

    private static byte[] readBounded(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (count > limit - out.size()) throw new IllegalStateException("capture output too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    private static int integer(byte[] b, int i) {
        return ((b[i] & 255) << 24) | ((b[i+1] & 255) << 16) | ((b[i+2] & 255) << 8) | (b[i+3] & 255);
    }
    private static int[] dimensions(byte[] png) {
        if (png == null || png.length < 33 || !Arrays.equals(PNG, Arrays.copyOf(png, 8))
                || integer(png, 8) != 13 || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return null;
        int width=integer(png,16), height=integer(png,20);
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192 || (long)width*height > 20_000_000) return null;
        return new int[] {width,height};
    }

    /** Returns null on any interruption, warning, malformed PNG or timeout. */
    public static Frame take(Clock clock, BooleanSupplier stopped, Starter starter) {
        if (clock == null || stopped == null || starter == null || stopped.getAsBoolean()) return null;
        long start = clock.nowMs(), deadline = System.nanoTime() + TIMEOUT_NANOS;
        Process process = null;
        byte[] png = null;
        ExecutorService readers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "diagnostic-screencap-reader");
            thread.setDaemon(true); return thread;
        });
        try {
            process = starter.start();
            if (process == null || start < 0) return null;
            process.getOutputStream().close();
            final Process p = process;
            Future<byte[]> stdout = readers.submit(() -> readBounded(p.getInputStream(), MAX_PNG));
            Future<byte[]> stderr = readers.submit(() -> readBounded(p.getErrorStream(), 4096));
            while (!p.waitFor(40, TimeUnit.MILLISECONDS)) {
                if (stopped.getAsBoolean() || System.nanoTime() >= deadline) return null;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0 || stopped.getAsBoolean() || p.exitValue() != 0) return null;
            png = stdout.get(left, TimeUnit.NANOSECONDS);
            left = deadline - System.nanoTime();
            if (left <= 0 || stderr.get(left, TimeUnit.NANOSECONDS).length != 0 || stopped.getAsBoolean()) return null;
            int[] size=dimensions(png);
            long end = clock.nowMs();
            if (size == null || end < start) return null;
            Frame frame = new Frame(png,size[0],size[1],start,end);
            png = null;
            return frame;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return null;
        } catch (Exception failure) {
            return null;
        } finally {
            if (png != null) Arrays.fill(png, (byte) 0);
            if (process != null) {
                process.destroyForcibly();
                try { process.getInputStream().close(); } catch (Exception ignored) { }
                try { process.getErrorStream().close(); } catch (Exception ignored) { }
            }
            readers.shutdownNow();
        }
    }
}
