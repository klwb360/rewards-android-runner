package com.aliothmoon.maafw.remote;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fail closed unless this diagnostic receives only its own unchanged resources and tasks. */
public final class ReadOnlyResourceGuard {
    private static final Map<String, String> PINS = new HashMap<>();
    private static final Set<String> ENTRIES = new HashSet<>(Arrays.asList("SearchCheck", "ReadingCheck"));
    static {
        PINS.put("pipeline/diagnostic.json", "c9a16ddf24cc7666d642740ffc0a848096edbfa6f1450436a6e76f3d04bcdeca");
        PINS.put("model/ocr/det.onnx", "66c0f34caaf432553710fd9973a7134d8cf924db7109310c3d2562dcc39b209d");
        PINS.put("model/ocr/keys.txt", "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d");
        PINS.put("model/ocr/rec.onnx", "7dcf6298d77d2a6eb44c1ebeed990826ca895a9c68d955a3eace00763d052949");
    }

    private ReadOnlyResourceGuard() {}

    public static boolean validFiles(String piRoot) {
        return validFilesForTest(piRoot, PINS);
    }

    public static boolean allow(String piRoot, List<String> paths, List<String> entries,
                                boolean noOverrides, boolean noAgents, int displayMode) {
        if (!noOverrides || !noAgents || displayMode != 1 || entries == null || entries.isEmpty()
                || entries.size() != 1 || !ENTRIES.containsAll(entries)
                || new HashSet<>(entries).size() != entries.size()
                || paths == null || paths.size() != 1 || piRoot == null) return false;
        try {
            File expected = new File(new File(piRoot).getCanonicalFile(), "resource");
            if (!expected.getAbsolutePath().equals(expected.getCanonicalPath())) return false;
            if (!expected.getCanonicalFile().equals(new File(paths.get(0)).getCanonicalFile())) return false;
            return validFiles(piRoot);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static boolean validFilesForTest(String piRoot, Map<String, String> pins) {
        if (piRoot == null || pins == null || pins.isEmpty() || pins.size() > 16) return false;
        try {
            File root = new File(new File(piRoot).getCanonicalFile(), "resource");
            if (!root.isDirectory() || !root.getAbsolutePath().equals(root.getCanonicalPath())) return false;
            Set<String> found = new HashSet<>();
            if (!walk(root, root, pins, found, 0)) return false;
            return found.equals(pins.keySet());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean walk(File root, File current, Map<String, String> pins,
                                Set<String> found, int depth) throws IOException {
        if (depth > 6 || !current.getAbsolutePath().equals(current.getCanonicalPath())) return false;
        File[] children = current.listFiles();
        if (children == null || children.length > 32) return false;
        for (File child : children) {
            if (!child.getAbsolutePath().equals(child.getCanonicalPath())) return false;
            if (child.isDirectory()) {
                if (!walk(root, child, pins, found, depth + 1)) return false;
            } else if (child.isFile()) {
                String relative = root.toURI().relativize(child.toURI()).getPath();
                String expected = pins.get(relative);
                if (expected == null || !found.add(relative) || !expected.equals(hash(child))) return false;
            } else return false;
        }
        return true;
    }

    private static String hash(File file) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }
}
