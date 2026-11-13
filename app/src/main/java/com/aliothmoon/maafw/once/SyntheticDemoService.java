package com.aliothmoon.maafw.once;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Unexported, normal-UID process boundary. Accepts compiled fixture IDs only.
 * Binding/REGISTER never starts source/OCR. Every explicit START is journal-spent
 * before producer construction. No capture APIs, permission Intents or real claims.
 * Only fixed enums/booleans/numbers leave this service; no image/OCR text IPC. */
public final class SyntheticDemoService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "synthetic-demo-owner"));
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), this::message));
    private Messenger client;
    private IBinder.DeathRecipient clientDeath;
    private volatile boolean destroyed;
    private AttemptJournal journal; // Only the single worker performs storage I/O.
    private boolean ready, blocked, canArchive, archiving, terminating, endAcknowledged;
    private long archivedCount, lastArchived;
    private String ocrCounter = "NOT_OBSERVED";
    private String evidence = "NOT_OBSERVED", workerBoundary = "LOCK_NOT_ACQUIRED";
    private String phase = "INITIALIZING", recovery = "NO_AUTOMATIC_REPLAY", page = "UNKNOWN", flow = "NO_RESULT", cleanup = "NOT_STARTED";
    private int attempt, lastRequestId;
    private Run active;
    private MaterialReplayObservation materialReady;
    private String materialSummary = "NOT_OBSERVED";
    private int[] materialTargets = new int[0];
    private static final class Run {
        final SyntheticRewardsProvider.Fixture fixture;
        final int requestId;
        final MaterialReplayObservation before;
        final int selectedAfter;
        volatile boolean cancelled;
        volatile OnceBackend backend;
        volatile int attempt;
        Run(SyntheticRewardsProvider.Fixture fixture, int requestId, MaterialReplayObservation before, int selectedAfter) {
            this.fixture = fixture; this.requestId = requestId; this.before = before; this.selectedAfter = selectedAfter;
        }
        void stop() {
            cancelled = true;
            OnceBackend b = backend;
            if (b != null) b.cancel(b.snapshot().owner());
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        if (android.os.Process.myUid() < 10000 || !Application.getProcessName().equals(getPackageName()+":readonly_ocr")) {
            blocked = true; phase = "WRONG_PROCESS_BLOCKED"; return;
        }
        worker.execute(() -> {
            AttemptJournal.Snapshot saved = null;
            try { journal = new AttemptJournal(getNoBackupFilesDir()); saved = journal.snapshot(); }
            catch (Exception ignored) { }
            final AttemptJournal.Snapshot state = saved;
            main.post(() -> {
                if (destroyed) return;
                ready = true;
                if (state == null) { blocked = true; phase = "JOURNAL_UNAVAILABLE"; recovery = "BLOCKED_UNCERTAIN"; }
                else {
                    applySaved(state);
                    workerBoundary = canArchive ? "EXCLUSIVE_LOCK_FRESH_INCARNATION_OLD_CLEANUP_UNKNOWN" : "EXCLUSIVE_JOURNAL_LOCK";
                    blocked = !admissible(state);
                    phase = state.generation() >= 20000 && state.state() == AttemptJournal.State.COMPLETED ? "SYNTHETIC_LIMIT_REACHED" : blocked ? "RECOVERY_BLOCKED" : "IDLE";
                    cleanup = blocked ? "UNCONFIRMED" : "NOT_STARTED";
                }
                publish();
            });
        });
    }
    @Override public IBinder onBind(Intent ignored) { return endpoint.getBinder(); }
    @Override public boolean onUnbind(Intent ignored) { if (!terminating) cancelActive(); return false; }
    private boolean sameClient(Messenger candidate) {
        return client != null && candidate != null && client.getBinder().equals(candidate.getBinder());
    }
    private boolean message(Message message) {
        if (destroyed || message.sendingUid != android.os.Process.myUid() || message.replyTo == null) return true;
        if (message.what == 1) {
            if (!sameClient(message.replyTo)) {
                clearMaterial();
                if (active != null) active.stop();
                unlink(); client = message.replyTo; lastRequestId = 0;
                final IBinder binder = client.getBinder();
                clientDeath = () -> main.post(() -> { if (client != null && client.getBinder().equals(binder)) { cancelActive(); unlink(); } });
                try { binder.linkToDeath(clientDeath, 0); } catch (RemoteException dead) { client = null; clientDeath = null; cancelActive(); }
            }
            publish(); return true;
        }
        if (!sameClient(message.replyTo)) return true;
        switch (message.what) {
            case 2 -> start(message.arg1, message.arg2);
            case 3 -> {
                if (active != null && message.arg2 == active.requestId && (message.arg1 == active.attempt || message.arg1 == 0)) cancelActive();
                else if (active == null && message.arg2 == lastRequestId && (message.arg1 == attempt || message.arg1 == 0)) {
                    clearMaterial();
                    ocrCounter = "NOT_OBSERVED"; page = "UNKNOWN"; flow = "NO_RESULT"; phase = "RESULT_DISCARDED"; publish();
                }
            }
            case 4 -> { cancelActive(); unlink(); }
            case 5 -> { if (message.arg1 == attempt && message.arg2 == lastRequestId) archiveInterrupted(); }
            case 6 -> { if (message.arg2 == lastRequestId && (message.arg1 == attempt || message.arg1 == 0 && active != null)) terminateSyntheticWorker(); }
            case 7 -> fakeMaterial(message.arg1, message.arg2);
            default -> { }
        }
        return true;
    }
    private void unlink() {
        if (client != null && clientDeath != null) client.getBinder().unlinkToDeath(clientDeath, 0);
        client = null; clientDeath = null;
    }
    private void cancelActive() {
        clearMaterial();
        if (active != null) { active.stop(); phase = "STOP_REQUESTED"; ocrCounter = "NOT_OBSERVED"; page = "UNKNOWN"; flow = "NO_RESULT"; publish(); }
    }
    private void start(int fixture, int requestId) {
        begin(fixture, requestId, null, 0);
    }
    private void clearMaterial() { materialReady = null; materialSummary = "NOT_OBSERVED"; materialTargets = new int[0]; }
    private boolean canBegin(int requestId) {
        return ready && !blocked && !archiving && !terminating && attempt < 20000 && active == null && requestId > lastRequestId;
    }
    private void fakeMaterial(int selected, int requestId) {
        MaterialReplayObservation before = materialReady;
        if (!canBegin(requestId) || before == null || !before.candidates().contains(selected)) return;
        if (before.kind() == MaterialReplayObservation.Kind.CHECKIN) {
            var preview = new com.aliothmoon.maafw.rewards.DailyCheckInRegion.Session(before.checkin()).previewOnce();
            if (preview.outcome() != com.aliothmoon.maafw.rewards.DailyCheckInRegion.Outcome.PREVIEW_ONLY) return;
        } else {
            var preview = new com.aliothmoon.maafw.rewards.RewardRegionLocator.Session(before.cards())
                    .previewExplicitly(before.cards(), "visual-card-" + (selected - 1));
            if (!"HYPOTHETICAL_CROP_TARGET_ONLY".equals(preview.status())) return;
        }
        // Take the only pending proposal before queueing. Repeated/late messages cannot reuse it.
        int fixture = 6 + before.kind().ordinal();
        begin(fixture, requestId, before, selected);
    }
    private void begin(int fixture, int requestId, MaterialReplayObservation before, int selected) {
        if (!canBegin(requestId) || fixture < 1 || fixture > 8) return;
        Run run = new Run(SyntheticRewardsProvider.Fixture.values()[fixture - 1], requestId, before, selected);
        clearMaterial();
        lastRequestId = requestId;
        active = run; canArchive = false; evidence = "NOT_OBSERVED"; phase = "RESERVING"; recovery = "SPENDING_SYNTHETIC_ATTEMPT";
        ocrCounter = "NOT_OBSERVED"; page = "UNKNOWN"; flow = "NO_RESULT"; cleanup = "PENDING"; publish();
        worker.execute(() -> execute(run));
        main.postDelayed(() -> poll(run), 100);
    }
    private void poll(Run run) {
        if (destroyed || terminating || active != run) return;
        if (run.attempt > 0) attempt = run.attempt;
        OnceBackend backend = run.backend;
        if (backend != null) {
            OnceBackend.Result value = backend.snapshot();
            phase = run.cancelled ? "WAITING_OWNER_RETURN" : value.phase().name(); cleanup = value.cleanup().name();
            evidence = renderEvidence(backend, value.owner());
        }
        publish(); main.postDelayed(() -> poll(run), 200);
    }
    private void execute(Run run) {
        OnceBackend.Result result = null;
        RewardsObservation observation = null;
        FrameStructureEvidence.Result structure = null;
        MaterialReplayObservation material = null;
        String materialRoi = "DAY_ROI_NOT_OBSERVED";
        boolean durableFinish = false;
        try {
            SyntheticAdmission reservation = journal.reserveSynthetic();
            if (reservation == null) throw new IllegalStateException();
            run.attempt = (int) journal.snapshot().generation();
            // The forced reservation precedes these adapter/provider allocations.
            PollingFrameSource source = ScreenProducerProvider.bindSynthetic(new SyntheticRewardsProvider(run.fixture, run.selectedAfter), reservation);
            MaterialReplayObservation.Kind kind = run.fixture.ordinal() >= 5 ?
                    MaterialReplayObservation.Kind.values()[run.fixture.ordinal() - 5] : null;
            MaaRecognizer recognizer = new MaaRecognizer(getApplicationContext(), kind, run.attempt);
            OnceBackend backend = new OnceBackend(reservation::consume, source, recognizer, job -> {
                if (run.cancelled || destroyed) run.stop();
                job.run();
            });
            run.backend = backend;
            result = backend.start();
            durableFinish = journal.completeSynthetic(reservation, result);
            if (durableFinish && !run.cancelled && !destroyed && result.phase() == OnceBackend.Phase.COMPLETED
                    && result.cancellation() == OnceBackend.Cancellation.NOT_REQUESTED
                    && result.cleanup() == OnceBackend.Cleanup.CONFIRMED
                    && result.quiescence() == OnceBackend.Quiescence.CONFIRMED
                    && result.memory() == OnceBackend.Memory.WIPED) {
                observation = recognizer.observation();
                structure=recognizer.structure(result.owner());
                material=recognizer.materialObservation(result.owner());
                materialRoi=recognizer.materialRoiDiagnostic(result.owner());
            }
        } catch (Throwable ignored) { /* Never expose exception strings, paths or raw native output. */ }
        final OnceBackend.Result end = result;
        final RewardsObservation accepted = observation;
        final FrameStructureEvidence.Result acceptedStructure = structure;
        final MaterialReplayObservation acceptedMaterial = material;
        final String acceptedMaterialRoi = materialRoi;
        final boolean committed = durableFinish;
        final AttemptJournal.Snapshot saved = journal == null ? null : journal.snapshot();
        final String receipt = run.backend == null ? "UNKNOWN_BEFORE_BACKEND" : renderEvidence(run.backend, run.backend.snapshot().owner());
        main.post(() -> finish(run, end, accepted, acceptedStructure, acceptedMaterial, acceptedMaterialRoi, committed, saved, receipt));
        if (destroyed) closeJournal();
    }
    private void finish(Run run, OnceBackend.Result end, RewardsObservation observation, FrameStructureEvidence.Result structure,
                        MaterialReplayObservation material, String materialRoi, boolean committed, AttemptJournal.Snapshot saved, String receipt) {
        if (destroyed || active != run) return;
        if (saved != null) applySaved(saved);
        attempt = Math.max(attempt, run.attempt);
        evidence = receipt;
        canArchive = false; // Current-incarnation unknown owner cannot be archived.
        ocrCounter = "NOT_OBSERVED"; page = "UNKNOWN"; flow = "NO_RESULT";
        if (!committed || end == null) {
            blocked = true; phase = end == null ? "JOURNAL_OR_PIPELINE_FAILURE" : end.phase().name();
            cleanup = end == null ? "UNCONFIRMED" : end.cleanup().name(); recovery = "BLOCKED_UNCERTAIN";
        } else {
            recovery = "COMPLETED_SPENT_EXPLICIT_RETRY_ONLY";
            phase = run.cancelled && end.phase() == OnceBackend.Phase.COMPLETED ? "CANCELLED" : end.phase().name(); cleanup = end.cleanup().name();
            if (!run.cancelled && end.phase() == OnceBackend.Phase.COMPLETED && observation != null) {
                page = observation.page().name(); flow = ReadOnlyRewardsFlow.plan(observation).name();
                ocrCounter=FrameStructureEvidence.syntheticReceipt(structure,observation.page()==RewardsObservation.Page.REWARDS_HOME);
                if (material != null) {
                    if (run.before == null) {
                        materialReady = material;
                        materialTargets = material.candidates().stream().mapToInt(Integer::intValue).toArray();
                        materialSummary = material.summary();
                    } else {
                        materialSummary = "FAKE_SELECTED=" + run.selectedAfter + " REAL_ACTIONS=0 VERIFY=" +
                                run.before.compare(material, run.selectedAfter) + "\n" + material.summary();
                    }
                    if (material.kind()==MaterialReplayObservation.Kind.CHECKIN) materialSummary+="\n"+materialRoi;
                } else if (run.fixture.ordinal() >= 5) materialSummary = "HOLD: NO_RELEASED_NATIVE_MATERIAL_EVIDENCE";
            }
        }
        active = null; if (terminating) phase = "PROCESS_TERMINATION_REQUESTED"; publish();
    }
    private static boolean admissible(AttemptJournal.Snapshot saved) {
        return saved.generation() < 20000 && (saved.state() == AttemptJournal.State.READY || saved.state() == AttemptJournal.State.COMPLETED
                || saved.state() == AttemptJournal.State.ARCHIVED_UNPROVEN);
    }
    private void applySaved(AttemptJournal.Snapshot saved) {
        attempt = (int)saved.generation(); recovery = saved.state().name();
        archivedCount = saved.archivedCount(); lastArchived = saved.lastArchivedGeneration();
        canArchive = saved.canArchiveInterruptedSynthetic();
    }
    private static String renderEvidence(OnceBackend backend, OnceBackend.Owner owner) {
        CleanupEvidence.Snapshot facts = backend.evidenceSnapshot(owner);
        if (!facts.ownerMatched()) return "FOREIGN_OR_UNKNOWN_OWNER";
        StringBuilder out = new StringBuilder();
        for (CleanupEvidence.Item item : CleanupEvidence.Item.values()) {
            if (out.length() > 0) out.append("\n");
            out.append(item.name()).append('=').append(facts.state(item).name());
        }
        return out.toString();
    }
    private void archiveInterrupted() {
        if (!ready || !blocked || !canArchive || active != null || archiving || terminating) return;
        archiving = true; canArchive = false; phase = "ARCHIVING_SYNTHETIC";
        clearMaterial();
        ocrCounter = "NOT_OBSERVED"; page = "UNKNOWN"; flow = "NO_RESULT"; publish();
        worker.execute(() -> {
            boolean archived = journal != null && journal.archiveInterruptedSynthetic();
            AttemptJournal.Snapshot saved = journal == null ? null : journal.snapshot();
            main.post(() -> {
                if (destroyed) return;
                archiving = false;
                if (saved != null) applySaved(saved);
                blocked = !archived || saved == null || !admissible(saved);
                phase = terminating ? "PROCESS_TERMINATION_REQUESTED" : archived ? "ARCHIVED_SYNTHETIC" : "RECOVERY_BLOCKED";
                cleanup = "UNCONFIRMED"; evidence = "ARCHIVED_UNPROVEN_NO_OLD_OWNER_RECEIPT";
                workerBoundary = "EXCLUSIVE_LOCK_NEW_SYNTHETIC_ONLY_OLD_CLEANUP_UNKNOWN";
                publish(); // No producer, recognizer, reservation or automatic replay here.
            });
        });
    }
    private void terminateSyntheticWorker() {
        if (!ready || terminating || !isOwnSyntheticProcess()) return;
        terminating = true; canArchive = false;
        clearMaterial();
        // Force-end is deliberately different from cooperative Cancel. Suppress
        // results but do not call cleanup or wait for a possibly blocked native owner.
        if (active != null) active.cancelled = true;
        phase = "PROCESS_TERMINATION_REQUESTED"; ocrCounter = "NOT_OBSERVED"; page = "UNKNOWN"; flow = "NO_RESULT";
        cleanup = "UNCONFIRMED"; publish();
        final int requestedAttempt = attempt;
        // Separate safe-metadata sidecar, NOT the admission journal or a cleanup receipt.
        // This lane may run while the owner is stuck inside native code. It never opens
        // the journal, releases resources, accepts a PID, or affects another package.
        new Thread(() -> {
            try {
                java.nio.file.Path base = getNoBackupFilesDir().toPath();
                java.nio.file.Path file = base.resolve("synthetic-end-request-" + java.util.UUID.randomUUID() + ".record");
                java.nio.ByteBuffer record = java.nio.ByteBuffer.allocate(24);
                record.putLong(0x53594e36454e4452L).putLong(requestedAttempt).putLong(android.os.SystemClock.elapsedRealtime());
                record.flip();
                try (java.nio.channels.FileChannel out = java.nio.channels.FileChannel.open(file,
                        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                    while (record.hasRemaining()) out.write(record);
                    out.force(true);
                }
                try (java.nio.channels.FileChannel directory = java.nio.channels.FileChannel.open(base, java.nio.file.StandardOpenOption.READ)) { directory.force(true); }
                main.post(() -> {
                    endAcknowledged = true;
                    publish(); // Client explicitly unbinds; BIND_AUTO_CREATE is not recovery.
                    main.postDelayed(() -> {
                        if (isOwnSyntheticProcess()) android.os.Process.killProcess(android.os.Process.myPid());
                    }, 250); // Delivery grace only; never cleanup or process-death evidence.
                });
            } catch (Exception failed) {
                main.post(() -> { if (!destroyed) { terminating = false; blocked = true; phase = "TERMINATION_RECORD_FAILED"; cleanup = "UNCONFIRMED"; publish(); Run pending = active; if (pending != null) main.post(() -> poll(pending)); } });
            }
        }, "synthetic-explicit-worker-end").start();
    }
    private boolean isOwnSyntheticProcess() {
        return (getPackageName().equals("com.aliothmoon.maafw.productrewardsworking")
                || getPackageName().equals("com.aliothmoon.maafw.productrewardsworking.capturereview"))
                && android.os.Process.myUid() >= 10000
                && Application.getProcessName().equals(getPackageName() + ":readonly_ocr");
    }
    private void publish() {
        if (client == null || destroyed) return;
        Bundle b = new Bundle();
        ProductCapabilities.Snapshot capability = ProductCapabilities.current(active != null, canArchive, ready && !blocked && !archiving && !terminating && attempt < 20000 && active == null);
        b.putString("realBinding", ProductCapabilities.realSource().binding().name());
        b.putString("realMissing", ProductCapabilities.realSource().missing().toString());
        b.putBoolean("endAcknowledged", endAcknowledged);
        b.putString("phase", terminating ? "PROCESS_TERMINATION_REQUESTED" : phase); b.putString("permission", capability.permission().name());
        b.putString("recovery", recovery); b.putString("page", page); b.putString("flow", flow);
        b.putString("cleanup", cleanup); b.putString("origin", "SYNTHETIC");
        b.putString("ocrCounter", ocrCounter);
        b.putString("materialSummary", materialSummary);
        b.putIntArray("materialTargets", materialTargets);
        b.putString("evidence", evidence); b.putString("workerBoundary", workerBoundary);
        b.putInt("archivedCount", (int)archivedCount); b.putInt("lastArchived", (int)lastArchived);
        b.putBoolean("canArchive", ready && blocked && canArchive && active == null && !archiving && !terminating);
        b.putBoolean("canTerminate", ready && !terminating && isOwnSyntheticProcess());
        b.putInt("requestId", lastRequestId); b.putInt("attempt", attempt); b.putInt("workerUid", android.os.Process.myUid());
        b.putBoolean("canStart", ready && !blocked && !archiving && !terminating && attempt < 20000 && active == null);
        b.putBoolean("canCancel", !terminating && active != null && !active.cancelled);
        Message m = Message.obtain(null, 10); m.setData(b);
        try { client.send(m); } catch (RemoteException dead) { cancelActiveNoPublish(); unlink(); }
    }
    private void cancelActiveNoPublish() { clearMaterial(); if (active != null) active.stop(); ocrCounter = "NOT_OBSERVED"; page = "UNKNOWN"; flow = "NO_RESULT"; }
    private void closeJournal() { if (journal != null) try { journal.close(); } catch (Exception ignored) { } }
    @Override public void onDestroy() {
        destroyed = true; if (!terminating) cancelActiveNoPublish(); unlink();
        // Queue closure behind owner work; never interrupt native calls or infer quiet.
        // Journal itself retains a static anchor and exclusive lock for in-process
        // uncertain owners. A fresh incarnation can archive but never call it clean.
        worker.execute(this::closeJournal); worker.shutdown();
        super.onDestroy();
    }
}




