package com.aliothmoon.maafw.readingtrust;

import io.github.klwb360.rewards.core.RewardsCore.Limits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import static com.aliothmoon.maafw.readingtrust.OfflineReadingV3Envelope.*;
import static com.aliothmoon.maafw.readingtrust.OfflineReadingV3Inspection.*;

/** Synthetic fixtures only. No Android, process launching, network, repair or live actions. */
public final class OfflineReadingV3InspectionTest {
    private static int checks;
    private static final Limits LIMITS = Limits.defaults();
    private static final long BOOT = 8, EPOCH = NormalReadingLedgerTest.EPOCH;
    private static final Image ABSENT = Image.of(null);
    private static byte[] seed, pending, completed, alteredSeed, alteredCompleted;
    private static void check(boolean result, String label) {
        checks++; if (!result) throw new AssertionError(label);
    }
    private static Snapshot snapshot(byte[] data, byte[] marker, byte[] next, byte[] backup) {
        return new Snapshot(Inventory.COMPLETE_AS_REPORTED, Image.of(data), Image.of(marker),
                Image.of(next), Image.of(backup));
    }
    private static Status classify(byte[] data, byte[] marker, byte[] next, byte[] backup) {
        return inspect(snapshot(data, marker, next, backup), BOOT, LIMITS);
    }
    private static void expect(Status status, byte[] data, byte[] marker, byte[] next, String label) {
        check(classify(data, marker, next, null) == status, label);
    }
    private static byte[] marker(byte[] old, byte[] next) throws Exception {
        return OfflineReadingV3Store.transactionMarker(old, next);
    }
    private static byte[] reseal(byte[] image) throws Exception {
        byte[] result = image.clone();
        byte[] sum = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(result, result.length - 32));
        System.arraycopy(sum, 0, result, result.length - 32, 32);
        return result;
    }
    private static void fixtures() throws Exception {
        var original = OfflineReadingV3StoreTest.seed();
        var reserved = OfflineReadingV3StoreTest.planned(original, 0, "inspection-one", 10000, LIMITS);
        var done = OfflineReadingV3StoreTest.completed(reserved, 3, 10000, LIMITS);
        seed = encode(settled(BOOT, original), LIMITS);
        pending = encode(pending(BOOT, reserved, EPOCH), LIMITS);
        completed = encode(settled(BOOT, done), LIMITS);
        alteredSeed = encode(settled(BOOT, new NormalReadingLedger.View(0,
                OfflineReadingV3EnvelopeTest.runStart(original.state(), 101), original.history(), null)), LIMITS);
        alteredCompleted = encode(settled(BOOT, new NormalReadingLedger.View(done.generation(),
                OfflineReadingV3EnvelopeTest.runStart(done.state(), 101), done.history(), done.receipt())), LIMITS);
    }
    private static void contexts() {
        Snapshot valid = snapshot(seed, null, null, null);
        check(inspect(null, BOOT, LIMITS) == Status.INVALID_CONTEXT, "null snapshot");
        check(inspect(valid, -1, LIMITS) == Status.INVALID_CONTEXT, "negative boot");
        check(inspect(valid, BOOT, null) == Status.INVALID_CONTEXT, "missing limits");
        check(inspect(new Snapshot(null, ABSENT, ABSENT, ABSENT, ABSENT), BOOT, LIMITS)
                == Status.INVALID_CONTEXT, "missing inventory");
        for (int i = 0; i < 4; i++) {
            Image[] items = {Image.of(seed), ABSENT, ABSENT, ABSENT}; items[i] = null;
            check(inspect(new Snapshot(Inventory.COMPLETE_AS_REPORTED, items[0], items[1], items[2], items[3]),
                    BOOT, LIMITS) == Status.INVALID_CONTEXT, "null image container " + i);
        }
        for (Inventory inventory : Inventory.values()) {
            Status expected = switch (inventory) {
                case COMPLETE_AS_REPORTED -> Status.CLEAN_SETTLED_IMAGE;
                case UNKNOWN -> Status.INVENTORY_UNKNOWN;
                case LEGACY_PRESENT -> Status.LEGACY_COLLISION;
                case UNEXPECTED_ENTRY -> Status.UNEXPECTED_ENTRY;
            };
            check(inspect(new Snapshot(inventory, Image.of(seed), ABSENT, ABSENT, ABSENT), BOOT, LIMITS)
                    == expected, "inventory precedence " + inventory);
        }
        for (int i = 0; i < 4; i++) {
            Image[] items = {Image.of(seed), ABSENT, ABSENT, ABSENT}; items[i] = Image.unavailable();
            check(inspect(new Snapshot(Inventory.COMPLETE_AS_REPORTED, items[0], items[1], items[2], items[3]),
                    BOOT, LIMITS) == Status.UNREADABLE_ARTIFACT, "unreadable artifact " + i);
            items[i] = Image.of(new byte[300001]);
            check(inspect(new Snapshot(Inventory.COMPLETE_AS_REPORTED, items[0], items[1], items[2], items[3]),
                    BOOT, LIMITS) == Status.UNREADABLE_ARTIFACT, "oversized artifact " + i);
        }
        check(inspect(valid, BOOT + 1, LIMITS) == Status.DATA_MALFORMED, "other boot not interpreted");
        for (int size : new int[]{0, 1, 32, 300000})
            expect(Status.DATA_MALFORMED, new byte[size], null, null, "data size " + size);
        check(classify(seed, null, null, new byte[0]) == Status.BACKUP_PRESENT, "empty backup is present");
        expect(Status.ORPHAN_NEXT, seed, null, new byte[0], "empty next is present");
        expect(Status.MARKER_MALFORMED, seed, new byte[0], null, "empty marker is present");
    }
    private static void presenceMatrix() throws Exception {
        for (boolean settlement : new boolean[]{false, true}) {
            byte[] old = settlement ? pending : seed, next = settlement ? completed : pending;
            byte[] txn = marker(old, next);
            Status clean = settlement ? Status.CLEAN_PENDING_IMAGE : Status.CLEAN_SETTLED_IMAGE;
            for (int mask = 0; mask < 16; mask++) {
                boolean d = (mask & 1) != 0, m = (mask & 2) != 0,
                        n = (mask & 4) != 0, b = (mask & 8) != 0;
                Status expected = b ? Status.BACKUP_PRESENT : !d ? Status.DATA_MISSING
                        : !m ? (n ? Status.ORPHAN_NEXT : clean)
                        : n ? Status.OLD_IMAGE_WITH_VALID_NEXT : Status.OLD_IMAGE_ONLY_WITH_MARKER;
                check(classify(d ? old : null, m ? txn : null, n ? next : null, b ? old : null)
                        == expected, "presence matrix " + settlement + " " + mask);
            }
        }
    }
    private static void markers() throws Exception {
        byte[] valid = marker(seed, pending);
        check(valid.length == 104, "specified marker length");
        for (int length = 0; length < valid.length; length++)
            expect(Status.MARKER_MALFORMED, seed, Arrays.copyOf(valid, length), pending,
                    "every marker truncation " + length);
        for (int extra : new int[]{1, 32, 1000})
            expect(Status.MARKER_MALFORMED, seed, Arrays.copyOf(valid, valid.length + extra), pending,
                    "marker trailing bytes " + extra);
        for (int index = 0; index < valid.length; index++) {
            byte[] corrupt = valid.clone(); corrupt[index] ^= 1;
            expect(Status.MARKER_MALFORMED, seed, corrupt, pending, "marker bit corruption " + index);
        }
        for (int index = 0; index < 8; index++) {
            byte[] corrupt = valid.clone(); corrupt[index] ^= 1;
            expect(Status.MARKER_MALFORMED, seed, reseal(corrupt), pending,
                    "authenticated-looking unknown marker schema " + index);
        }
        expect(Status.MARKER_DEGENERATE, seed, marker(seed, seed), null, "identical image marker rejected");
        expect(Status.MARKER_DEGENERATE, seed, marker(pending, pending), pending,
                "equal-hash marker wins over unrelated data");
    }
    private static void relations() throws Exception {
        for (boolean settlement : new boolean[]{false, true}) {
            byte[] old = settlement ? pending : seed, next = settlement ? completed : pending;
            byte[] txn = marker(old, next);
            expect(Status.OLD_IMAGE_ONLY_WITH_MARKER, old, txn, null, "old only " + settlement);
            expect(Status.OLD_IMAGE_WITH_INVALID_NEXT, old, txn, new byte[0], "empty staged " + settlement);
            byte[] corrupt = next.clone(); corrupt[corrupt.length / 2] ^= 1;
            expect(Status.OLD_IMAGE_WITH_INVALID_NEXT, old, txn, corrupt, "corrupt staged " + settlement);
            expect(Status.OLD_IMAGE_WITH_WRONG_NEXT, old, txn, alteredSeed, "unrelated valid next " + settlement);
            expect(Status.OLD_IMAGE_WITH_VALID_NEXT, old, txn, next, "exact valid pair " + settlement);
            expect(Status.NEW_IMAGE_WITH_MARKER, next, txn, null, "new hash match only " + settlement);
            for (byte[] extra : new byte[][]{old, next, alteredSeed, new byte[0], corrupt})
                expect(Status.NEW_IMAGE_WITH_EXTRA_NEXT, next, txn, extra, "any extra next is suspicious " + settlement);
            for (byte[] extra : new byte[][]{null, old, next, new byte[0]})
                expect(Status.DATA_NOT_BOUND_TO_MARKER, alteredSeed, txn, extra,
                        "unrelated current image " + settlement);
            expect(Status.DATA_MISSING, null, txn, next, "never recover missing data from next " + settlement);
            expect(Status.DATA_MALFORMED, corrupt, txn, next, "never recover corrupt data from next " + settlement);
        }
        for (byte[][] pair : new byte[][][]{
                {seed, completed}, {pending, seed}, {completed, pending},
                {seed, alteredSeed}, {alteredSeed, pending}, {pending, alteredCompleted}}) {
            expect(Status.OLD_IMAGE_WITH_INVALID_TRANSITION, pair[0], marker(pair[0], pair[1]), pair[1],
                    "individually valid images do not imply a valid transition");
        }
        // The new hash alone cannot validate an unavailable old image or its transition.
        expect(Status.NEW_IMAGE_WITH_MARKER, completed, marker(seed, completed), null,
                "new hash match deliberately makes no transition claim");
    }
    private static void defensiveCopies() throws Exception {
        byte[] old = seed.clone(), txn = marker(seed, pending), next = pending.clone();
        byte[] beforeOld = old.clone(), beforeTxn = txn.clone(), beforeNext = next.clone();
        Snapshot value = snapshot(old, txn, next, null);
        check(inspect(value, BOOT, LIMITS) == Status.OLD_IMAGE_WITH_VALID_NEXT, "initial defensive snapshot");
        check(Arrays.equals(old, beforeOld) && Arrays.equals(txn, beforeTxn) && Arrays.equals(next, beforeNext),
                "inspection does not mutate caller bytes");
        Arrays.fill(old, (byte) 0); Arrays.fill(txn, (byte) 0); Arrays.fill(next, (byte) 0);
        check(inspect(value, BOOT, LIMITS) == Status.OLD_IMAGE_WITH_VALID_NEXT, "caller mutation cannot change snapshot");
        check(value.toString().equals("OfflineInspectionSnapshot[redacted]"), "snapshot string redacted");
        check(value.data().toString().equals("OfflineImage[redacted]"), "image string redacted");
        for (var method : Image.class.getDeclaredMethods())
            check(method.getReturnType() != byte[].class, "no image byte accessor " + method.getName());
    }
    private static byte[] fixtureFile(Path dir, String suffix) throws Exception {
        Path file = dir.resolve("synthetic-reading-v3." + suffix);
        return Files.exists(file) ? Files.readAllBytes(file) : null;
    }
    private static Map<String, String> fixtureHashes(Path dir) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) result.put(file.getFileName().toString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        return result;
    }
    private static void syntheticStorageFaults() throws Exception {
        for (boolean settlement : new boolean[]{false, true}) for (var stage : OfflineReadingV3Store.Stage.values()) {
            var fixture = OfflineReadingV3StoreTest.fixture(settlement, LIMITS,
                    reached -> { if (reached == stage) throw new IOException("synthetic inspection fault"); });
            check(fixture.store().compareAndReplace(fixture.old(), fixture.next())
                    == OfflineReadingV3Store.Outcome.STORAGE_UNCERTAIN, "uncertain fixture " + stage);
            Map<String, String> before = fixtureHashes(fixture.dir());
            Snapshot observed = snapshot(fixtureFile(fixture.dir(), "bin"), fixtureFile(fixture.dir(), "txn"),
                    fixtureFile(fixture.dir(), "new"), fixtureFile(fixture.dir(), "bak"));
            Status expected = switch (stage) {
                case MARKER_PARTIAL -> Status.MARKER_MALFORMED;
                case MARKER_FORCED, MARKER_DIRECTORY_SYNCED -> Status.OLD_IMAGE_ONLY_WITH_MARKER;
                case NEXT_PARTIAL -> Status.OLD_IMAGE_WITH_INVALID_NEXT;
                case NEXT_FORCED, NEXT_DIRECTORY_SYNCED, BEFORE_REPLACE -> Status.OLD_IMAGE_WITH_VALID_NEXT;
                case AFTER_REPLACE, REPLACEMENT_DIRECTORY_SYNCED -> Status.NEW_IMAGE_WITH_MARKER;
                case AFTER_MARKER_DELETE, FINAL_DIRECTORY_SYNCED, BEFORE_FINAL_READ ->
                        settlement ? Status.CLEAN_SETTLED_IMAGE : Status.CLEAN_PENDING_IMAGE;
            };
            check(inspect(observed, BOOT, LIMITS) == expected, "actual synthetic residue " + settlement + " " + stage);
            check(fixtureHashes(fixture.dir()).equals(before), "classifier leaves all synthetic files unchanged " + stage);
            if (stage.ordinal() >= OfflineReadingV3Store.Stage.AFTER_MARKER_DELETE.ordinal()) {
                Status identicalBytes = classify(encode(fixture.next(), LIMITS), null, null, null);
                check(identicalBytes == expected, "clean bytes cannot establish successful outcome " + stage);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        fixtures(); contexts(); presenceMatrix(); markers(); relations(); defensiveCopies(); syntheticStorageFaults();
        System.out.println("PASS " + checks + " synthetic inspection assertions; OBSERVATION ONLY, NO RECOVERY OR ACTION AUTHORITY");
    }
}
