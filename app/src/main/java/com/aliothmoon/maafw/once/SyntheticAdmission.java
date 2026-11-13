package com.aliothmoon.maafw.once;

/** Synthetic identity fence ONLY. Never real capture consent or authorization. */
public final class SyntheticAdmission {
    final AttemptJournal journal;
    final long generation;
    private OnceBackend.Owner owner;
    private boolean consumed;
    private Object sourceSession;
    SyntheticAdmission(AttemptJournal journal, long generation) {
        this.journal = journal; this.generation = generation;
    }
    boolean isValidReservation() { return journal.valid(this); }
    synchronized BackendPorts.ClaimReceipt consume(OnceBackend.Owner candidate) {
        if (consumed) return new BackendPorts.ClaimReceipt(candidate, BackendPorts.ClaimDecision.DUPLICATE);
        consumed = true;
        if (candidate == null || !journal.valid(this))
            return new BackendPorts.ClaimReceipt(candidate, BackendPorts.ClaimDecision.DENIED);
        owner = candidate;
        return new BackendPorts.ClaimReceipt(candidate, BackendPorts.ClaimDecision.CLAIMED);
    }
    synchronized boolean bindSource(OnceBackend.Owner candidate, Object session) {
        if (owner == null || owner != candidate || session == null || sourceSession != null) return false;
        sourceSession = session;
        return true;
    }
    synchronized boolean owns(OnceBackend.Owner candidate) { return owner != null && owner == candidate; }
}
