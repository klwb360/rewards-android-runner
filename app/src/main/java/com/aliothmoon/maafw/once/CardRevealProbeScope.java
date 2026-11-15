package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Independent UI-only locate and one direct-region capture. Never reuses earlier records. */
final class CardRevealProbeScope {
    static final String DIRECTORY = "rewards-card-native-chain-v1";
    static final int MAX_SWIPES = 8, MAX_UI_OBSERVATIONS = 24, MAX_CROPS = 1;
    static final long SESSION_TTL = 120_000L, UI_TTL = 9_000L;

    static long sessionDeadline(long now) {
        if (now < 0 || now > Long.MAX_VALUE - SESSION_TTL)
            throw new SecurityException("Card reveal clock unavailable");
        return now + SESSION_TTL;
    }
    static long uiDeadline(long now, long sessionEnd) {
        if (now < 0 || now > Long.MAX_VALUE - UI_TTL || sessionEnd <= now)
            throw new SecurityException("Card reveal observation expired");
        return Math.min(sessionEnd, now + UI_TTL);
    }
    static boolean validUi(int index) { return index >= 1 && index <= MAX_UI_OBSERVATIONS; }
    static void burnRun(Path files) throws IOException { burn(files, "RUN"); }
    static void burnUi(Path files, int index) throws IOException {
        if (!validUi(index)) throw new SecurityException("Card reveal observation index");
        burn(files, "UI_" + index);
    }
    static void burnScroll(Path files, int index) throws IOException {
        if (index < 1 || index > MAX_SWIPES) throw new SecurityException("Card reveal scroll index");
        burn(files, "SCROLL_" + index);
    }
    static void burnCrop(Path files) throws IOException { burn(files, "CROP"); }

    static void burnOcr(Path files) throws IOException { burn(files, "OCR"); }

    private static Object directory(Path path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isDirectory() || a.isSymbolicLink() || a.fileKey() == null)
            throw new IOException("Card reveal directory identity unavailable");
        return a.fileKey();
    }
    private static void same(Path files, Object parent, Path dir, Object child) throws IOException {
        if (!parent.equals(directory(files)) || !child.equals(directory(dir)))
            throw new IOException("Card reveal directory changed");
    }
    private static void burn(Path files, String slot) throws IOException {
        if (files == null || !files.isAbsolute() || !files.equals(files.normalize()))
            throw new IOException("Exact private directory required");
        Object parent = directory(files); Path dir = files.resolve(DIRECTORY);
        try { Files.createDirectory(dir); } catch (FileAlreadyExistsException exists) { /* Verify identity below. */ }
        Object child = directory(dir); same(files, parent, dir, child);
        try (FileChannel c = FileChannel.open(dir.resolve(slot + ".burned"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.US_ASCII.encode("card-native-chain-v1 slot=" + slot
                    + " maxUi=24 maxSwipes=8 maxRegionCaptures=1 fullScreenCaptures=0 taskTaps=0 maxOcr=9 maxMock=1\n");
            while (bytes.hasRemaining()) if (c.write(bytes) <= 0) throw new IOException("Card reveal marker incomplete");
            c.force(true);
        }
        same(files, parent, dir, child);
        try (FileChannel c = FileChannel.open(dir, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { c.force(true); }
        try (FileChannel c = FileChannel.open(files, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { c.force(true); }
        same(files, parent, dir, child);
    }
    private CardRevealProbeScope() { }
}
