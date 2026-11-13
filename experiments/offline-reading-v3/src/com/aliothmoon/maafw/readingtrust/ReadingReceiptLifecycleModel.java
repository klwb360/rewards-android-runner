package com.aliothmoon.maafw.readingtrust;

import com.aliothmoon.maafw.trial.ReadingCodecAccess;
import com.aliothmoon.maafw.trial.ReadingReservationGuard.History;
import io.github.klwb360.rewards.core.RewardsCore;
import io.github.klwb360.rewards.core.RewardsCore.*;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import static com.aliothmoon.maafw.readingtrust.NormalReadingLedger.*;

/** OFFLINE TRANSITION MODEL ONLY. No store, issuer, actor, recovery or public integration API.
 * A Pending pair must eventually be stored as ONE new schema image. Projecting only reserved
 * into v2 would lose the last receipt and is prohibited outside synthetic test directories.
 * Snapshots and receipts are inert data; none of this class grants permission to act. */
final class ReadingReceiptLifecycleModel {
    private ReadingReceiptLifecycleModel() {}
    static final class Pending {
        private final View settled, reserved;
        private final long reservedAt, boot;
        private Pending(View settled, View reserved, long reservedAt, long boot) {
            this.settled = settled; this.reserved = reserved;
            this.reservedAt = reservedAt; this.boot = boot;
        }
        View settled() { return settled; }
        View reserved() { return reserved; }
        long reservedAt() { return reservedAt; }
        @Override public String toString() { return "SyntheticReadingTransition[redacted]"; }
    }
    record Proposal(Decision decision, Pending pending) {}

    static Proposal reserve(View settled, long boot, Observation observed, String articleHash,
            long epoch, long now, boolean cancelled, Limits limits) throws IOException {
        NormalReadingLedger.validate(settled, boot);
        State s = settled.state();
        if (!s.pendingTask().isEmpty() || !settled.history().pendingState().isEmpty())
            return new Proposal(Decision.PENDING, null);
        // The model accepts a zero-attempt seed or a fully settled normal receipt only.
        // It deliberately has no migration from an unexplained receipt-free old history.
        if (s.readingAttempts() == 0 ? settled.receipt() != null || settled.generation() != 0
                                    : settled.receipt() == null)
            throw new IOException("Unexplained settled history");
        if (s.readingAttempts() == 0 ? s.readingStartedAt() != 0
                : s.readingStartedAt() <= 0 || s.readingStartedAt() < s.runStartedAt()
                  || settled.receipt().receivedTrue() < s.readingStartedAt())
            throw new IOException("Unreachable normal Reading timeline");
        if (settled.generation() > Long.MAX_VALUE - 2 || now <= 0)
            throw new IOException("Generation or monotonic range exhausted");
        if (!readable(observed)) return new Proposal(Decision.UNREADABLE, null);
        Receipt previous = settled.receipt();
        if (previous != null && (now < previous.committedAt()
                || observed.counter().target() != previous.target()
                || observed.counter().current() < previous.after()))
            return new Proposal(Decision.UNREADABLE, null);
        if (articleHash == null || !articleHash.matches("[a-f0-9]{64}"))
            return new Proposal(Decision.UNREADABLE, null);
        Step prepared = RewardsCore.prepare(s, Task.READING, observed, "", epoch, now,
                cancelled, limits);
        if (prepared.decision() != Decision.ALLOW) return new Proposal(prepared.decision(), null);
        if (settled.history().hashes().contains(articleHash))
            return new Proposal(Decision.DUPLICATE, null);
        Set<String> hashes = new HashSet<>(settled.history().hashes()); hashes.add(articleHash);
        State pendingState = prepared.state();
        History history = new History(s.day(), hashes, ReadingCodecAccess.fingerprint(pendingState), articleHash);
        // Keep the old receipt unchanged with its original nonpending after-image. Do not
        // relabel it as the receipt for a new attempt or weaken v2 receipt validation.
        View reserved = new View(settled.generation() + 1, pendingState, history, null);
        NormalReadingLedger.validate(reserved, boot);
        return new Proposal(Decision.ALLOW, new Pending(settled, reserved, now, boot));
    }

    /** Checks synthetic settlement state correspondence only. Not a commit or recovery API. */
    static View checkSettlement(Pending pending, View completed, long epoch, long now,
            boolean cancelled, Limits limits) throws IOException {
        if (pending == null) throw new IOException("No planned reservation");
        NormalReadingLedger.validate(pending.settled, pending.boot);
        NormalReadingLedger.validate(pending.reserved, pending.boot);
        NormalReadingLedger.validate(completed, pending.boot);
        View before = pending.reserved; State s = before.state(); Receipt r = completed.receipt();
        if (r == null || completed.generation() != before.generation() + 1
                || !r.fingerprint().equals(before.history().pendingState())
                || !r.article().equals(before.history().pendingArticle())
                || r.attempt() != s.readingAttempts() || r.before() != s.pendingBefore()
                || r.target() != s.pendingTarget() || r.noCreditBefore() != s.readingNoCredit()
                || r.receivedTrue() < pending.reservedAt || now < r.committedAt()
                || pending.settled.receipt() != null
                    && r.dispatch().equals(pending.settled.receipt().dispatch()))
            throw new IOException("Settlement does not belong to this reservation");
        Observation after = new Observation(ParseStatus.OK,
                new Counter(Task.READING, r.after(), r.target(), true), "synthetic receipt");
        Step checked = RewardsCore.verify(s, Task.READING, after, epoch, now, cancelled, limits);
        if (checked.decision() != Decision.ALLOW && checked.decision() != Decision.COMPLETE
                && checked.decision() != Decision.READING_NO_CREDIT)
            throw new IOException("Settlement blocked by core");
        History expectedHistory = new History(before.history().day(), before.history().hashes(), "", "");
        if (!checked.state().equals(completed.state()) || !expectedHistory.equals(completed.history())
                || r.decision() != checked.decision())
            throw new IOException("Settlement state changed unrelated data");
        return completed;
    }
    private static boolean readable(Observation o) {
        return o != null && o.status() == ParseStatus.OK && o.counter() != null
                && o.counter().task() == Task.READING && o.counter().today()
                && o.counter().current() >= 0 && o.counter().target() > 0
                && o.counter().current() <= o.counter().target();
    }
}
