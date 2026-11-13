package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Checked RGBA8888 -> tightly packed GRAY8 copy; no borrowed buffer escapes. */
final class Gray8Copy {
    private Gray8Copy() { }
    static void validate(int width, int height, int rowStride, int pixelStride, long remaining)
            throws IOException {
        long pixels = (long) width * height;
        long row = (long) width * 4;
        long extent = (long) (height - 1) * rowStride + row;
        if (width <= 0 || height <= 0 || width > OwnedFrame.MAX_DIMENSION
                || height > OwnedFrame.MAX_DIMENSION || pixels > OwnedFrame.MAX_BYTES
                || pixelStride != 4 || rowStride < row || remaining < 0
                || extent < row || extent > remaining) throw new IOException();
    }
    static void copy(ByteBuffer source, int width, int height, int rowStride, int pixelStride,
            BackendPorts.FrameSink sink, BackendPorts.StopSignal stop) throws IOException {
        if (source == null || sink == null || stop == null) throw new IOException();
        ByteBuffer bytes = source.duplicate();
        validate(width, height, rowStride, pixelStride, bytes.remaining());
        int origin = bytes.position();
        byte[] row = new byte[width];
        try {
            if (stop.requested()) throw new BackendPorts.Cancelled();
            sink.begin(width, height);
            for (int y = 0; y < height; y++) {
                if (stop.requested()) throw new BackendPorts.Cancelled();
                long start = (long) origin + (long) y * rowStride;
                for (int x = 0; x < width; x++) {
                    int offset = (int) (start + (long) x * 4);
                    int r = bytes.get(offset) & 255;
                    int g = bytes.get(offset + 1) & 255;
                    int b = bytes.get(offset + 2) & 255;
                    // This first adapter accepts opaque RGBA only, never reinterprets RGBX.
                    if ((bytes.get(offset + 3) & 255) != 255) throw new IOException();
                    row[x] = (byte) ((77 * r + 150 * g + 29 * b + 128) >>> 8);
                }
                sink.append(row, 0, row.length);
            }
            if (stop.requested()) throw new BackendPorts.Cancelled();
        } finally {
            Arrays.fill(row, (byte) 0);
        }
    }
}
