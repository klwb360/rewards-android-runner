package com.aliothmoon.maafw.readingtrust;

import com.aliothmoon.maafw.trial.ReadingReservationGuard.History;
import io.github.klwb360.rewards.core.RewardsCore;
import io.github.klwb360.rewards.core.RewardsCore.*;
import java.nio.file.*;
import java.util.Set;

/** SOURCE ONLY: NOT COMPILED OR EXECUTED. Synthetic grants use existing reflection fixtures.
 * No Android, live issuer, migration, or real directory-sync coverage is claimed. */
public final class NormalReadingDeadlineTest {
    private static int checks;
    private static final long BEFORE = 7300, DEADLINE = 7400;
    // Calls in settle: initial, commit, beforeWrite, beforeReplace, confirmedAt.
    private static final class AdvancingClock extends NormalReadingLedgerTest.Clock {
        final int boundary;
        final long after;
        int reads;
        AdvancingClock(int boundary, long after) { this.boundary = boundary; this.after = after; }
        @Override public long elapsed() { return reads++ >= boundary ? after : BEFORE; }
    }

    private static NormalReadingLedgerTest.Fixture fixture(boolean reading, int boundary,
            long after, boolean zeroReadingStart) throws Exception {
        Path dir = Files.createTempDirectory("SYNTHETIC-normal-deadline-");
        AdvancingClock clock = new AdvancingClock(boundary, after);
        // Each case isolates one deadline. Run starts at 100, Reading at 200.
        Limits limits = reading ? new Limits(10, 7200, 7_200_000)
                                : new Limits(10, 1_800_000, 7300);
        NormalReadingLedger ledger = new NormalReadingLedger(dir, clock,
                p -> { if (!Files.isDirectory(p)) throw new java.io.IOException("Synthetic sync path"); },
                limits, stage -> {});
        State state = new State("2026-11-12", 100, zeroReadingStart ? 0 : 200,
                1, 0, 0, 0, Set.of(), "READING", 0, 30, "");
        String article = RewardsCore.hashTerm("synthetic deadline article");
        History history = new History(state.day(), Set.of(article),
                com.aliothmoon.maafw.trial.ReadingCodecAccess.fingerprint(state), article);
        NormalReadingLedger.View old = new NormalReadingLedger.View(1, state, history, null);
        Files.write(dir.resolve("normal-reading-v2.bin"), NormalReadingLedgerTest.encode(old, 8),
                StandardOpenOption.CREATE_NEW);
        return new NormalReadingLedgerTest.Fixture(dir, clock, ledger, old);
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void expired(boolean reading, int boundary, long at) throws Exception {
        var f = fixture(reading, boundary, at, false);
        var grant = NormalReadingLedgerTest.grant(f, 3, 7000, 7200);
        String label = (reading ? "Reading" : "Run") + " boundary=" + boundary + " at=" + at;
        var expected = boundary <= 2 ? NormalReadingLedger.Outcome.HOLD
                                    : NormalReadingLedger.Outcome.STORAGE_UNCERTAIN;
        check(f.ledger().settle(grant) == expected, label + " outcome");
        check(((AdvancingClock) f.clock()).reads == boundary + 1, label + " checkpoint reached");
        var disk = NormalReadingLedgerTest.decode(f.dir().resolve("normal-reading-v2.bin"));
        if (boundary < 4) {
            check(disk.equals(f.old()), label + " exact old image preserved");
            check(disk.state().pendingTask().equals("READING"), label + " core pending retained");
            check(disk.history().pendingState().equals(f.old().history().pendingState())
                    && disk.history().pendingArticle().equals(f.old().history().pendingArticle()),
                    label + " article pending retained");
        } else {
            // Expiry after replacement cannot undo storage. Preserve one complete new image,
            // report uncertainty, and do not falsely assert that pending remains on disk.
            check(disk.state().pendingTask().isEmpty() && disk.history().pendingState().isEmpty()
                    && disk.history().pendingArticle().isEmpty(), label + " pending cleared together");
            check(disk.receipt() != null && disk.receipt().after() == 3,
                    label + " complete settlement receipt");
        }
        check(disk.history().hashes().equals(f.old().history().hashes()), label + " history retained");
        check(Files.exists(f.dir().resolve("normal-reading-v2.txn")) == (boundary == 3),
                label + " transaction residue");
        check(Files.exists(f.dir().resolve("normal-reading-v2.new")) == (boundary == 3),
                label + " staged residue");
        check(!Files.exists(f.dir().resolve("normal-reading-v2.bak")), label + " no backup created");
        check(f.ledger().settle(grant) == NormalReadingLedger.Outcome.HOLD,
                label + " grant remains consumed");
        check(NormalReadingLedgerTest.decode(f.dir().resolve("normal-reading-v2.bin")).equals(disk),
                label + " consumed grant leaves disk unchanged");
    }

    public static void main(String[] args) throws Exception {
        for (boolean reading : new boolean[] {false, true}) {
            for (int boundary = 0; boundary < 5; boundary++) {
                expired(reading, boundary, DEADLINE);     // Core uses >=, not >.
                expired(reading, boundary, DEADLINE + 1);
            }
            var f = fixture(reading, 0, DEADLINE - 1, false);
            var grant = NormalReadingLedgerTest.grant(f, 3, 7000, 7200);
            check(f.ledger().settle(grant) == NormalReadingLedger.Outcome.CONFIRMED,
                    "one millisecond before deadline remains valid");
            check(f.ledger().settle(grant) == NormalReadingLedger.Outcome.HOLD,
                    "successful grant remains consumed");
        }
        // Preserve RewardsCore's readingStartedAt == 0 sentinel semantics; this synthetic
        // malformed-as-a-live-attempt fixture is not permission for a future issuer to emit it.
        var zero = fixture(true, 0, DEADLINE, true);
        check(zero.ledger().settle(NormalReadingLedgerTest.grant(zero, 3, 7000, 7200))
                == NormalReadingLedger.Outcome.CONFIRMED, "zero reading-start sentinel unchanged");
        System.out.println("PASS " + checks + " synthetic deadline assertions; NO LIVE ISSUER");
    }
}
