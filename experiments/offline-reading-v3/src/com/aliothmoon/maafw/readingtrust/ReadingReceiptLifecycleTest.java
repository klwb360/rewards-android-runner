package com.aliothmoon.maafw.readingtrust;

import com.aliothmoon.maafw.trial.ReadingReservationGuard.History;
import io.github.klwb360.rewards.core.RewardsCore;
import io.github.klwb360.rewards.core.RewardsCore.*;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.*;
import java.util.Set;
import static com.aliothmoon.maafw.readingtrust.NormalReadingLedger.*;

/** Synthetic-only sequence tests. Each reserved projection is written in a NEW test temp
 * directory. This does NOT implement or validate atomic persistence of the envelope. */
public final class ReadingReceiptLifecycleTest {
    static int checks;
    static final long EPOCH = NormalReadingLedgerTest.EPOCH, BOOT = 8;
    static final Limits LIMITS = Limits.defaults();
    static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    interface Failing { void run() throws Exception; }
    static void rejected(Failing f, String label) throws Exception {
        boolean rejected = false;
        try { f.run(); } catch (IOException expected) { rejected = true; }
        check(rejected, label);
    }
    static Observation observed(int n, int target) {
        return new Observation(ParseStatus.OK, new Counter(Task.READING, n, target, true), "synthetic");
    }
    static View seed() {
        State state = RewardsCore.fresh(EPOCH, 100);
        return new View(0, state, new History(state.day(), Set.of(), "", ""), null);
    }
    static ReadingReceiptLifecycleModel.Proposal plan(View settled, int before, String article, long now)
            throws Exception {
        return ReadingReceiptLifecycleModel.reserve(settled, BOOT, observed(before, 30),
                RewardsCore.hashTerm(article), EPOCH, now, false, LIMITS);
    }
    static TrustedReadingSettlementGrant grant(NormalReadingLedger ledger, View reserved,
            int after, long start, String dispatch) throws Exception {
        Constructor<?> c = TrustedReadingSettlementGrant.class.getDeclaredConstructors()[0];
        c.setAccessible(true);
        Observation o = observed(after, 30);
        return (TrustedReadingSettlementGrant)c.newInstance(ledger, reserved, BOOT, dispatch,
                start + 10, start + 20, start + 5020, start + 5100,
                start + 5200, o, start + 5300, o);
    }
    record Run(ReadingReceiptLifecycleModel.Pending pending, View settled, long now) {}
    static Run run(View old, int before, int after, String article, long start, String dispatch)
            throws Exception {
        var proposal = plan(old, before, article, start);
        check(proposal.decision() == Decision.ALLOW && proposal.pending() != null, "reservation allowed");
        var pending = proposal.pending();
        check(pending.settled() == old, "previous entire settled snapshot retained unchanged");
        check(pending.reserved().receipt() == null, "new intent cannot masquerade as a receipt");
        check(pending.reserved().generation() == old.generation() + 1, "reservation generation increments");
        check(pending.reserved().state().readingAttempts() == old.state().readingAttempts() + 1,
                "one attempt reserved");
        check(pending.reserved().state().readingNoCredit() == old.state().readingNoCredit(),
                "reservation cannot reset no-credit streak");
        check(pending.reserved().history().hashes().containsAll(old.history().hashes()), "all hashes retained");
        Path dir = Files.createTempDirectory("SYNTHETIC-lifecycle-projection-");
        var clock = new NormalReadingLedgerTest.Clock(); clock.now = start + 5400;
        var ledger = new NormalReadingLedger(dir, clock,
                p -> { if (!Files.isDirectory(p)) throw new IOException("simulated sync"); }, LIMITS);
        Files.write(dir.resolve("normal-reading-v2.bin"), NormalReadingLedgerTest.encode(pending.reserved(), BOOT),
                StandardOpenOption.CREATE_NEW);
        var grant = grant(ledger, pending.reserved(), after, start, dispatch);
        check(ledger.settle(grant) == Outcome.CONFIRMED, "synthetic settlement confirms");
        View settled = NormalReadingLedgerTest.decode(dir.resolve("normal-reading-v2.bin"));
        check(ReadingReceiptLifecycleModel.checkSettlement(pending, settled, EPOCH, clock.now, false, LIMITS)
                .equals(settled), "exact reservation-to-settlement correspondence");
        check(settled.state().pendingTask().isEmpty() && settled.history().pendingState().isEmpty()
                && settled.history().pendingArticle().isEmpty(), "pending fields cleared together");
        check(settled.generation() == old.generation() + 2, "two transitions, two generations");
        check(settled.history().hashes().equals(pending.reserved().history().hashes()), "settlement retains all articles");
        check(ledger.settle(grant) == Outcome.HOLD, "settlement grant remains one-use");
        check(pending.settled() == old, "settlement cannot rewrite retained prior snapshot");
        return new Run(pending, settled, clock.now);
    }
    static String dispatch(int n) { return String.format("%032x", n); }
    static Receipt generation(Receipt r, long g) {
        return new Receipt(r.type(), r.day(), r.boot(), g, r.fingerprint(), r.article(), r.attempt(),
                r.dispatch(), r.before(), r.after(), r.target(), r.noCreditBefore(), r.noCreditAfter(),
                r.decision(), r.receivedTrue(), r.articleReady(), r.returned(), r.verificationStart(),
                r.firstAt(), r.secondAt(), r.committedAt());
    }
    public static void main(String[] args) throws Exception {
        View current = seed();
        for (int i = 1; i <= 4; i++) {
            current = run(current, 0, 0, "no-credit-" + i, i * 10000, dispatch(i)).settled();
            check(current.state().readingNoCredit() == i, "consecutive no-credit count " + i);
            check(current.receipt().decision() == (i == 4 ? Decision.READING_NO_CREDIT : Decision.ALLOW),
                    "fourth no-credit stops");
        }
        var blocked = plan(current, 0, "fifth", 60000);
        check(blocked.decision() == Decision.READING_NO_CREDIT && blocked.pending() == null,
                "fifth attempt never becomes pending");
        current = seed(); int before = 0;
        int[] afters = {0, 0, 3, 3}; int[] streaks = {1, 2, 0, 1};
        for (int i = 0; i < afters.length; i++) {
            current = run(current, before, afters[i], "reset-" + i, (i + 1) * 10000,
                    dispatch(20 + i)).settled();
            check(current.state().readingNoCredit() == streaks[i], "credit resets only streak");
            before = afters[i];
        }
        current = seed();
        for (int i = 1; i <= 10; i++) {
            current = run(current, i - 1, i, "ten-" + i, i * 10000, dispatch(40 + i)).settled();
            check(current.state().readingAttempts() == i && current.history().hashes().size() == i,
                    "history and attempts retained at " + i);
        }
        blocked = plan(current, 10, "eleventh", 120000);
        check(blocked.decision() == Decision.READING_LIMIT && blocked.pending() == null, "eleventh blocked");

        Run one = run(seed(), 0, 3, "one", 10000, dispatch(70));
        final View settled = one.settled(); long next = 20000;
        check(plan(settled, 3, "one", next).decision() == Decision.DUPLICATE, "duplicate article blocked");
        check(plan(settled, 2, "other", next).decision() == Decision.UNREADABLE, "counter regression blocked");
        check(plan(settled, 3, "other", one.now() - 1).decision() == Decision.UNREADABLE, "clock rollback blocked");
        check(ReadingReceiptLifecycleModel.reserve(settled, BOOT, observed(3, 31),
                RewardsCore.hashTerm("other"), EPOCH, next, false, LIMITS).decision() == Decision.UNREADABLE,
                "changed target blocked");
        check(ReadingReceiptLifecycleModel.reserve(settled, BOOT, observed(3, 30),
                RewardsCore.hashTerm("other"), EPOCH, next, true, LIMITS).decision() == Decision.CANCELLED,
                "cancellation blocks reservation");
        check(ReadingReceiptLifecycleModel.reserve(settled, BOOT, observed(3, 30),
                RewardsCore.hashTerm("other"), EPOCH + 86400000L, next, false, LIMITS).decision() == Decision.DAY_CHANGED,
                "day rollover not reset");
        check(plan(settled, 3, "other", 10000 + LIMITS.maxReadingMillis()).decision() == Decision.READING_TIMEOUT,
                "persistent reading deadline blocks next reservation");
        check(plan(settled, 3, "other", 100 + LIMITS.maxRunMillis()).decision() == Decision.RUN_TIMEOUT,
                "persistent run deadline blocks next reservation");
        rejected(() -> ReadingReceiptLifecycleModel.reserve(settled, 9, observed(3, 30),
                RewardsCore.hashTerm("other"), EPOCH, next, false, LIMITS), "boot mismatch rejected");
        rejected(() -> plan(new View(settled.generation(), settled.state(), settled.history(), null),
                3, "other", next), "history without normal receipt cannot silently migrate");
        for (long badStart : new long[] {0, 99, settled.receipt().receivedTrue() + 1}) {
            State oldState = settled.state();
            State impossible = new State(oldState.day(), oldState.runStartedAt(), badStart,
                    oldState.readingAttempts(), oldState.readingNoCredit(), oldState.searchAttempts(),
                    oldState.searchNoCredit(), oldState.searchHashes(), "", 0, 0, "");
            View unreachable = new View(settled.generation(), impossible, settled.history(), settled.receipt());
            rejected(() -> plan(unreachable, 3, "other", next), "malformed prior Reading clock cannot reset deadline");
        }
        State base = seed().state();
        View prestartedSeed = new View(0, new State(base.day(), base.runStartedAt(), 101,
                0, 0, 0, 0, Set.of(), "", 0, 0, ""), seed().history(), null);
        rejected(() -> plan(prestartedSeed, 0, "first", next), "zero-attempt seed must have no Reading start");
        View exhausted = new View(Long.MAX_VALUE - 1, settled.state(), settled.history(),
                generation(settled.receipt(), Long.MAX_VALUE - 1));
        rejected(() -> plan(exhausted, 3, "other", next), "two-generation overflow blocked");
        var pending = plan(settled, 3, "other", next).pending();
        check(plan(pending.reserved(), 3, "third", next + 1).decision() == Decision.PENDING,
                "unresolved pending blocks another reservation");
        // Existing v2 validation remains strict: old receipt cannot be attached to a new intent.
        boolean invalidV2 = false;
        try { NormalReadingLedgerTest.encode(new View(pending.reserved().generation(),
                pending.reserved().state(), pending.reserved().history(), settled.receipt()), BOOT); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            invalidV2 = expected.getCause() instanceof IOException;
        }
        check(invalidV2, "prior receipt cannot validate new pending v2 image");
        Run two = run(settled, 3, 6, "other", next, dispatch(71));
        rejected(() -> ReadingReceiptLifecycleModel.checkSettlement(one.pending(), two.settled(),
                EPOCH, two.now(), false, LIMITS), "wrong reservation settlement rejected");
        rejected(() -> ReadingReceiptLifecycleModel.checkSettlement(two.pending(), two.settled(),
                EPOCH, two.now(), true, LIMITS), "cancelled settlement check rejected");
        State s = two.settled().state();
        State tampered = new State(s.day(), s.runStartedAt() + 1, s.readingStartedAt(), s.readingAttempts(),
                s.readingNoCredit(), s.searchAttempts(), s.searchNoCredit(), s.searchHashes(), "", 0, 0, "");
        View changed = new View(two.settled().generation(), tampered, two.settled().history(), two.settled().receipt());
        rejected(() -> ReadingReceiptLifecycleModel.checkSettlement(two.pending(), changed,
                EPOCH, two.now(), false, LIMITS), "unrelated core fields cannot change");
        Run complete = run(seed(), 0, 30, "complete", 10000, dispatch(80));
        check(plan(complete.settled(), 30, "after-complete", 20000).decision() == Decision.COMPLETE,
                "full target is terminal");
        System.out.println("PASS " + checks + " synthetic receipt lifecycle assertions; NO ENVELOPE STORAGE OR LIVE ISSUER");
    }
}
