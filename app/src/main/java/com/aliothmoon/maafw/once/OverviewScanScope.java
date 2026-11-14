package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Independent bounded overview attempt. Existing once records are never reused or reset. */
final class OverviewScanScope {
    static final String DIRECTORY = "rewards-overview-review-v5";
    static final int MAX_FRAMES = 12, MAX_SWIPES = 11;
    static final long SESSION_TTL = 180_000L, CAPTURE_TTL = 90_000L;
    static long sessionDeadline(long now) {
        if (now < 0 || now > Long.MAX_VALUE - SESSION_TTL) throw new SecurityException("Overview clock unavailable");
        return now + SESSION_TTL;
    }
    static long frameDeadline(long now, long sessionEnd) {
        if (now < 0 || now > Long.MAX_VALUE - CAPTURE_TTL || sessionEnd <= now)
            throw new SecurityException("Overview frame expired");
        return Math.min(sessionEnd, now + CAPTURE_TTL);
    }
    static boolean validFrame(int index) { return index >= 1 && index <= MAX_FRAMES; }
    static void burnRun(Path files) throws IOException { burn(files, "RUN"); }
    static void burnUi(Path files, int index) throws IOException { burn(files, frame(index) + "_UI"); }
    static RealCaptureClaim.BurnStore frameStore(Path files, int index) {
        final String slot = frame(index) + "_PNG";
        return new RealCaptureClaim.BurnStore() {
            private boolean used;
            @Override public synchronized void burn(String id, String ignored) throws IOException {
                boolean first = !used; used = true;
                if (!first || id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
                    throw new IOException("Exact unspent overview frame required");
                OverviewScanScope.burn(files, slot);
            }
        };
    }
    static void burnScroll(Path files, int index) throws IOException {
        if (index < 1 || index > MAX_SWIPES) throw new SecurityException("Overview scroll index");
        burn(files, "SCROLL_" + index);
    }
    private static String frame(int index) {
        if (!validFrame(index)) throw new SecurityException("Overview frame index");
        return "FRAME_" + index;
    }
    private static Object directory(Path path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isDirectory() || a.isSymbolicLink() || a.fileKey() == null)
            throw new IOException("Overview directory identity unavailable");
        return a.fileKey();
    }
    private static void same(Path files, Object parent, Path dir, Object child) throws IOException {
        if (!parent.equals(directory(files)) || !child.equals(directory(dir)))
            throw new IOException("Overview directory changed");
    }
    private static void burn(Path files, String slot) throws IOException {
        if (files == null || !files.isAbsolute() || !files.equals(files.normalize()))
            throw new IOException("Exact private directory required");
        Object parent = directory(files); Path dir = files.resolve(DIRECTORY);
        try { Files.createDirectory(dir); } catch (FileAlreadyExistsException exists) { /* Validate exact identity below. */ }
        Object child = directory(dir); same(files, parent, dir, child);
        try (FileChannel c = FileChannel.open(dir.resolve(slot + ".burned"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.US_ASCII.encode("overview-v5 slot=" + slot
                    + " maxFrames=12 maxSwipes=11 maxTaskTaps=0 maxBack=0 maxText=0 maxOcrPerFrame=1\n");
            while (bytes.hasRemaining()) if (c.write(bytes) <= 0) throw new IOException("Overview marker incomplete");
            c.force(true);
        }
        same(files, parent, dir, child);
        try (FileChannel c = FileChannel.open(dir, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { c.force(true); }
        try (FileChannel c = FileChannel.open(files, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { c.force(true); }
        same(files, parent, dir, child);
    }
    private OverviewScanScope() { }
}
