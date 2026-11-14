package com.aliothmoon.maafw.once;

import android.app.Application;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One independent hidden-page UI read and one native frame. No navigation or task actor. */
final class UiImageAlignmentRuntime {
    enum MetadataStage { UI_BEFORE, UI_AFTER, IMAGE_BEFORE, IMAGE_AFTER }
    enum Stage { NEW, HANDOFF, UI_READ, IMAGE_READ, IMAGE_AFTER, SEALED, HOLD, CANCELED, CLEANUP_UNKNOWN }

    /** Exact one-reader, four-pair sequence. A rejected attempt is never returned to the budget. */
    static final class MetadataPermit {
        private final BingNavigationSnapshot.ReadOnlyGuard guard;
        private final BooleanSupplier allowed;
        private boolean claimed, failed;
        private int next;
        MetadataPermit(BingNavigationSnapshot.ReadOnlyGuard guard, BooleanSupplier allowed) {
            this.guard = guard; this.allowed = allowed;
        }
        synchronized BingNavigationSnapshot.ReadOnlyGuard claimReader() {
            if (claimed || guard == null || !guard.valid() || !current()) { failed = true; return null; }
            claimed = true; return guard;
        }
        synchronized boolean read(MetadataStage stage) {
            boolean accepted = claimed && current() && stage != null && next < MetadataStage.values().length
                    && stage.ordinal() == next;
            if (!accepted) { failed = true; return false; }
            next++; return true;
        }
        synchronized boolean current() {
            if (failed) return false;
            try { if (allowed != null && allowed.getAsBoolean()) return true; }
            catch (RuntimeException unavailable) { /* Fail closed without retaining the exception. */ }
            failed = true; return false;
        }
        synchronized int pairsReserved() { return next; }
    }

    private final DormantCaptureConfirmationActivity activity;
    private final Object approvalOwner;
    private final boolean diagnosticRun;
    private final RealCaptureClaim.ProcessEpoch epoch;
    private final RealCaptureClaim.Grant grant;
    private final long sessionEnd;
    private final BingNavigationSnapshot.Shape shape;
    private final HandlerThread loop = new HandlerThread("ui-image-alignment-owner");
    private final UiImageAlignmentScope.Budget budget = new UiImageAlignmentScope.Budget();
    private final BackendPorts.RevocationFence fence = new BackendPorts.RevocationFence();
    private final AtomicBoolean canceled = new AtomicBoolean(), started = new AtomicBoolean();
    private final AtomicBoolean appendAttempted = new AtomicBoolean(), notified = new AtomicBoolean();
    private volatile Handler owner;
    private CaptureSessionRuntime.AlignmentRoot root;
    private volatile OnceBackend backend;
    private ReviewShizukuPngBinding.AlignmentBinding binding;
    private ReviewBingNavigation metadata;
    private MetadataPermit metadataPermit;
    private AndroidUiStructureReader.Result uiRead;
    private UiImageAlignmentCapture capture;
    private UiImageAlignmentPublication pendingPublication;
    private volatile Stage stage = Stage.NEW;
    private volatile boolean workerReturned, reportReady, closed, released;
    private volatile boolean constructionUnknown;
    private boolean claimUncertain, bindingConstruction;
    private final long[] metadataMillis = {-1, -1, -1, -1};
    private long backendMillis = -1, notifyAt = -1;
    private Stage failureAt;
    private OnceBackend.Phase backendPhase;
    private OnceBackend.Failure backendFailure;

    private UiImageAlignmentRuntime(DormantCaptureConfirmationActivity activity, Object approvalOwner,
            RealCaptureClaim.ProcessEpoch epoch, RealCaptureClaim.Grant grant,
            BingNavigationSnapshot.Shape shape, boolean diagnosticRun) {
        this.activity = activity; this.approvalOwner = approvalOwner; this.epoch = epoch;
        this.grant = grant; this.sessionEnd = RealCaptureClaim.deadline(grant); this.shape = shape;
        this.diagnosticRun = diagnosticRun;
    }

    static UiImageAlignmentRuntime start(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click, Object approvalOwner) {
        return startSelected(activity, click, approvalOwner, false);
    }
    static UiImageAlignmentRuntime startDiagnostic(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click, Object approvalOwner) {
        return startSelected(activity, click, approvalOwner, true);
    }
    private static UiImageAlignmentRuntime startSelected(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click, Object approvalOwner, boolean diagnosticRun) {
        CaptureReleaseGate.requireConfigured();
        if (activity == null || click == null || approvalOwner == null || Looper.myLooper() != Looper.getMainLooper()
                || android.os.Build.VERSION.SDK_INT < 34 || android.os.Process.myUid() < 10000
                || !"com.aliothmoon.maafw.productrewardsworking.capturereview".equals(activity.getPackageName())
                || !(activity.getPackageName() + ":capture_once").equals(Application.getProcessName())) {
            throw new SecurityException("Independent visible alignment confirmation required");
        }
        if (activity.alignmentDiagnosticSelected() != diagnosticRun) throw new SecurityException("Exact selected alignment entry required");
        var epoch = CaptureSessionRuntime.alignmentEpoch();
        long now = SystemClock.elapsedRealtime();
        var proof = VisibleCaptureAuthority.issueAlignment(activity, click, epoch, approvalOwner, now);
        var grant = RealCaptureClaim.visible(proof, epoch, approvalOwner, now);
        var manager = activity.getSystemService(DisplayManager.class);
        var display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null || display.getState() != Display.STATE_ON) throw new SecurityException("Display unavailable");
        var windows = activity.createDisplayContext(display).getSystemService(WindowManager.class);
        if (windows == null) throw new SecurityException("Display geometry unavailable");
        var bounds = windows.getMaximumWindowMetrics().getBounds();
        if (bounds.left != 0 || bounds.top != 0 || bounds.width() <= 0 || bounds.height() <= 0
                || bounds.width() > OwnedFrame.MAX_DIMENSION || bounds.height() > OwnedFrame.MAX_DIMENSION
                || (long) bounds.width() * bounds.height() > OwnedFrame.MAX_BYTES) {
            throw new SecurityException("Full original frame required");
        }
        var shape = new BingNavigationSnapshot.Shape(bounds.width(), bounds.height(), display.getRotation());
        var run = new UiImageAlignmentRuntime(activity, approvalOwner, epoch, grant, shape, diagnosticRun);
        run.root = CaptureSessionRuntime.retainAlignment(run, run.sessionEnd, run::latchCancel,
                run::closeOnOwner, () -> run.closed, run::postOwner);
        try {
            run.loop.start(); run.owner = new Handler(run.loop.getLooper());
            run.root.arm(); // The cleanup dispatcher exists before any deadline is armed.
            run.stage = Stage.HANDOFF;
            // Install the Activity's runtime field before requestHandoff can complete or reject.
            if (!new Handler(Looper.getMainLooper()).post(run::requestHandoffOnMain)) run.cancel();
        } catch (RuntimeException failed) {
            run.constructionUnknown = true; run.stage = Stage.CLEANUP_UNKNOWN; run.reportReady = true;
            run.cancel(); run.notifyFinished(); // Report even when an owner could not be installed; retain ROOT.
        }
        return run;
    }

    private void requestHandoffOnMain() {
        try {
            requireSessionCurrent();
            activity.requestHandoff(this::requireSessionCurrent, () -> {
                // OwnTaskHandoff can deliver from onStop, outside requestHandoff's stack.
                try {
                    requireSessionCurrent();
                    if (!postOwner(this::observeOnOwner)) cancel();
                } catch (RuntimeException rejected) { cancel(); }
            }, this::cancel);
        } catch (RuntimeException rejected) { cancel(); }
    }

    private boolean postOwner(Runnable task) {
        try {
            Handler exact = owner;
            if (exact != null && exact.post(task)) return true;
        } catch (RuntimeException unavailable) { /* Queue failure is not resource release. */ }
        constructionUnknown = true; stage = Stage.CLEANUP_UNKNOWN; reportReady = true;
        latchCancel(); // A dispatcher that queued then threw must not let that job begin work.
        notifyFinished(); return false;
    }

    private boolean sessionCurrent() {
        long now = SystemClock.elapsedRealtime();
        return !canceled.get() && root != null && root.current() && RealCaptureClaim.live(grant)
                && now >= 0 && now < sessionEnd && RealCaptureClaim.current(grant, now);
    }
    private void requireSessionCurrent() { if (!sessionCurrent()) throw new SecurityException("Alignment expired or revoked"); }
    private boolean hiddenCurrent() { return sessionCurrent() && activity.rootHiddenScalar() && activity.hiddenWithSoleTask(); }
    private void sourceGate() {
        if (!hiddenCurrent() || metadata == null || !ReviewBingNavigation.knownClean()
                || !metadata.authorityMatches(shape)) throw new SecurityException("Alignment source unavailable");
    }

    private BingNavigationSnapshot.Ready readSource(MetadataStage expected) {
        long startedAt = SystemClock.elapsedRealtime();
        try {
        sourceGate();
        var result = metadata.readAlignment(shape, expected);
        long now = SystemClock.elapsedRealtime();
        if (result == null || result.status() != BingNavigationSnapshot.Status.READY || result.ready() == null
                || result.ready().kind() != BingNavigationSnapshot.Kind.TEMPLATE || !result.ready().fresh(now)) return null;
        sourceGate(); return result.ready();
        } finally {
            metadataMillis[expected.ordinal()] = UiImageAlignmentTiming.elapsed(startedAt, SystemClock.elapsedRealtime());
        }
    }

    private void observeOnOwner() {
        if (!started.compareAndSet(false, true) || closed) return;
        try {
            requireSessionCurrent();
            if (!hiddenCurrent() || !budget.claimRun()) throw new SecurityException("Alignment run unavailable");
            if (diagnosticRun) UiImageAlignmentScope.burnDiagnosticRun(activity.getNoBackupFilesDir().toPath());
            else UiImageAlignmentScope.burnRun(activity.getNoBackupFilesDir().toPath());
            var installed = BingPackageMetadata.read(activity.getPackageManager());
            if (installed.state() != BingIdentityEvidence.PackageState.METADATA_OBSERVED) throw new SecurityException("Package unavailable");
            int bing = activity.getPackageManager().getApplicationInfo("com.microsoft.bing", 0).uid;
            int system = activity.getPackageManager().getApplicationInfo("com.android.systemui", 0).uid;
            metadataPermit = new MetadataPermit(new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),
                    activity.getTaskId(), activity.getComponentName().flattenToString()), this::hiddenCurrent);
            metadata = ReviewBingNavigation.forUiImageAlignment(activity.getApplicationContext(), installed, bing, system, metadataPermit);
            String nonce = UUID.randomUUID().toString().replace("-", "");
            UiRegionSourceEvidence.Pending[] pending = new UiRegionSourceEvidence.Pending[1];
            stage = Stage.UI_READ;
            uiRead = AndroidUiStructureReader.readAlignmentRegions(activity.getApplicationContext(), nonce,
                    Math.min(sessionEnd, SystemClock.elapsedRealtime() + 9000), this::hiddenCurrent, () -> {
                        var before = readSource(MetadataStage.UI_BEFORE);
                        if (before == null || !budget.claimUi()) return false;
                        if (diagnosticRun) UiImageAlignmentScope.burnDiagnosticUi(activity.getNoBackupFilesDir().toPath());
                        else UiImageAlignmentScope.burnUi(activity.getNoBackupFilesDir().toPath());
                        pending[0] = UiRegionSourceEvidence.begin(this, approvalOwner, epoch, nonce, before, SystemClock.elapsedRealtime());
                        sourceGate(); return pending[0] != null;
                    });
            if (uiRead == null || !uiRead.knownClean() || uiRead.protocolReport() == null
                    || !uiRead.protocolReport().qualified() || uiRead.protocolReport().regions() == null || pending[0] == null) {
                throw new SecurityException("UI observation unqualified");
            }
            var report = uiRead.protocolReport();
            var afterUi = readSource(MetadataStage.UI_AFTER);
            var source = pending[0].complete(report, afterUi, SystemClock.elapsedRealtime(),
                    AndroidUiStructureReader.processKnownClean() && ReviewBingNavigation.knownClean() && hiddenCurrent());
            if (source == null) throw new SecurityException("UI source changed");
            var geometry = source.consumeGeometry(this, approvalOwner, epoch, report, SystemClock.elapsedRealtime());
            if (geometry == null || report.regions().search() == null && report.regions().reading() == null) {
                throw new SecurityException("No source-bound UI regions");
            }
            var imageBefore = readSource(MetadataStage.IMAGE_BEFORE);
            long lower = SystemClock.elapsedRealtime();
            capture = geometry.beginFrame(imageBefore, lower, UiImageAlignmentScope.captureDeadline(lower, sessionEnd),
                    () -> !hiddenCurrent());
            if (capture == null || !budget.claimFrame()) throw new SecurityException("Image observation unavailable");
            root.boundDeadline(capture.deadline());
            stage = Stage.IMAGE_READ;
            // Finish fallible preconditions before binding. An idle backend cannot prove cleanup.
            if (!budget.claimOcr()) throw new SecurityException("One OCR reservation required");
            sourceGate();
            bindingConstruction = true;
            binding = ReviewShizukuPngBinding.bindReadOnlyAlignment(activity.getApplicationContext(),
                    this::consumeFrameClaim, this::sourceGate, fence, Runnable::run, capture);
            bindingConstruction = false; backend = binding.backend();
            long backendStartedAt = SystemClock.elapsedRealtime();
            var result = backend.start();
            backendMillis = UiImageAlignmentTiming.elapsed(backendStartedAt, SystemClock.elapsedRealtime());
            backendPhase = result.phase(); backendFailure = result.failure();
            BingNavigationSnapshot.Ready imageAfter = null;
            if (result.phase() == OnceBackend.Phase.COMPLETED && result.failure() == OnceBackend.Failure.NONE
                    && result.cancellation() == OnceBackend.Cancellation.NOT_REQUESTED && actualResourcesClosed()) {
                stage = Stage.IMAGE_AFTER; imageAfter = readSource(MetadataStage.IMAGE_AFTER);
            }
            pendingPublication = binding.publication(imageAfter, SystemClock.elapsedRealtime(),
                    this, approvalOwner, epoch, sessionEnd);
            stage = capture.publicationCurrent(SystemClock.elapsedRealtime()) ? Stage.SEALED : Stage.HOLD;
        } catch (Exception failed) {
            failureAt = stage;
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            if (bindingConstruction) constructionUnknown = true;
            stage = canceled.get() ? Stage.CANCELED : Stage.HOLD;
            // A failed source/after-read still spends the candidate seal; no retry or stale replay.
            if (binding != null && pendingPublication == null) {
                try { pendingPublication = binding.publication(null, SystemClock.elapsedRealtime(), this, approvalOwner, epoch, sessionEnd); }
                catch (RuntimeException unknown) { constructionUnknown = true; }
            }
        } finally {
            workerReturned = true; reportReady = true;
            if (!actualResourcesClosed()) stage = Stage.CLEANUP_UNKNOWN;
            if (stage != Stage.SEALED || canceled.get()) closeOnOwner();
            notifyFinished();
        }
    }

    private BackendPorts.ClaimReceipt consumeFrameClaim(OnceBackend.Owner backendOwner) {
        if (backendOwner == null || capture == null || !hiddenCurrent()
                || !capture.captureCurrent(SystemClock.elapsedRealtime())) {
            return new BackendPorts.ClaimReceipt(backendOwner, BackendPorts.ClaimDecision.DENIED);
        }
        var outcome = RealCaptureClaim.consume(grant, epoch, approvalOwner, SystemClock.elapsedRealtime(),
                diagnosticRun ? UiImageAlignmentScope.diagnosticFrameStore(activity.getNoBackupFilesDir().toPath())
                        : UiImageAlignmentScope.frameStore(activity.getNoBackupFilesDir().toPath()));
        claimUncertain = outcome == RealCaptureClaim.Outcome.UNCERTAIN;
        return new BackendPorts.ClaimReceipt(backendOwner, switch (outcome) {
            case CONSUMED -> BackendPorts.ClaimDecision.CLAIMED;
            case REJECTED -> BackendPorts.ClaimDecision.DUPLICATE;
            case UNCERTAIN -> BackendPorts.ClaimDecision.UNCERTAIN;
        });
    }

    /** Pure cleanup predicate, not a provenance receipt or publication authorization. */
    static boolean resourcesClosed(boolean uiClean, boolean metadataClean, boolean constructionUnknown,
            boolean claimUncertain, boolean nativeBound, OnceBackend.Result result) {
        if (!uiClean || !metadataClean || constructionUnknown || claimUncertain) return false;
        if (!nativeBound) return result == null;
        if (result == null || !result.terminal() || result.failure() == OnceBackend.Failure.CLAIM_UNCERTAIN || result.memory() == null
                || result.memory() == OnceBackend.Memory.RETAINED) return false;
        return result.cleanup() == OnceBackend.Cleanup.CONFIRMED && result.quiescence() == OnceBackend.Quiescence.CONFIRMED
                || result.cleanup() == OnceBackend.Cleanup.NOT_NEEDED && result.quiescence() == OnceBackend.Quiescence.NOT_INVOKED
                && result.memory() == OnceBackend.Memory.NOT_ALLOCATED;
    }

    private boolean actualResourcesClosed() {
        return resourcesClosed(AndroidUiStructureReader.processKnownClean() && (uiRead == null || uiRead.knownClean()),
                ReviewBingNavigation.knownClean(), constructionUnknown || bindingConstruction, claimUncertain,
                backend != null, backend == null ? null : backend.snapshot());
    }

    private void notifyFinished() {
        if (!notified.compareAndSet(false, true)) return;
        notifyAt = SystemClock.elapsedRealtime();
        String details = "profile=UI_IMAGE_ALIGNMENT stage=" + stage + " cleanup=" + (actualResourcesClosed() ? "CONFIRMED" : "UNKNOWN")
                + " metadataPairsReserved=" + (metadataPermit == null ? 0 : metadataPermit.pairsReserved())
                + " maxUiReads=1 maxFrames=1 maxOcr=1 taskTaps=0 swipes=0 back=0 textEntry=0 businessLedgerChanged=false"
                + "\n" + diagnostics();
        activity.runOnUiThread(() -> activity.alignmentFinished(this, details));
    }

    private String diagnostics() {
        var read = metadata == null ? null : metadata.lastReadDiagnostic();
        return "alignmentEntry=" + (diagnosticRun ? "DIAGNOSTIC_V2" : "ORIGINAL_V1")
                + " failureAt=" + (failureAt == null ? "NONE" : failureAt)
                + " backendPhase=" + (backendPhase == null ? "NOT_OBSERVED" : backendPhase)
                + " backendFailure=" + (backendFailure == null ? "NOT_OBSERVED" : backendFailure)
                + " backendMs=" + UiImageAlignmentTiming.value(backendMillis)
                + " uiBeforeReadMs=" + UiImageAlignmentTiming.value(metadataMillis[0])
                + " uiAfterReadMs=" + UiImageAlignmentTiming.value(metadataMillis[1])
                + " imageBeforeReadMs=" + UiImageAlignmentTiming.value(metadataMillis[2])
                + " imageAfterReadMs=" + UiImageAlignmentTiming.value(metadataMillis[3])
                + " lastReadGate=" + (read == null ? "NOT_OBSERVED" : read.gate())
                + " lastWindowsStatus=" + (read == null ? "NOT_OBSERVED" : read.windows().status())
                + " lastActivitiesStatus=" + (read == null ? "NOT_OBSERVED" : read.activities().status())
                + " lastParserGate=" + (read == null ? "NOT_OBSERVED" : read.parserGate());
    }
    /** Fixed diagnostic fields only; safe even when the visibility publication is rejected. */
    String captureDiagnostics() {
        return binding != null ? binding.diagnostics() : capture != null ? capture.diagnostics() : "captureDiagnostic=NOT_OBSERVED";
    }

    /** The Activity calls this once inside its existing visibility publication fence. */
    String finalizePublication() {
        boolean first = appendAttempted.compareAndSet(false, true);
        var publication = pendingPublication; pendingPublication = null;
        try {
            long now = SystemClock.elapsedRealtime();
            boolean current = false, hidden = false, source = false;
            try {
                current = first && reportReady && workerReturned && actualResourcesClosed()
                        && sessionCurrent() && Looper.myLooper() == Looper.getMainLooper();
                hidden = activity.rootHiddenScalar() && activity.hiddenWithSoleTask();
                source = metadata != null && metadata.alignmentPublicationAuthorityMatches(shape);
            } catch (RuntimeException unavailable) { current = false; hidden = false; source = false; }
            // Even a failed scalar check consumes the sealed receipt, suppressing every region.
            String result = publication == null ? unqualified() : publication.publish(now, current, hidden, source);
            return result + "\nappendPublicationPresent=" + (publication != null) + " appendRuntimeCurrent=" + current
                    + " appendHidden=" + hidden + " appendSourceReady=" + source
                    + " appendQueueMs=" + UiImageAlignmentTiming.value(UiImageAlignmentTiming.elapsed(notifyAt, now))
                    + "\n" + captureDiagnostics();
        } catch (RuntimeException unavailable) { return unqualified(); }
        finally { queueClose(); }
    }

    private static String unqualified() {
        return "alignmentEvidence=UNQUALIFIED imageBinding=NOT_ESTABLISHED regions=SUPPRESSED nonAtomic=true"
                + " counterAuthority=false actionAuthority=false taskTaps=0 publicationFence=REJECTED_AT_APPEND";
    }

    private void latchCancel() {
        canceled.set(true); fence.revoke(); activity.clearPendingHandoff();
        OnceBackend exact = backend;
        if (exact != null) exact.cancel(exact.snapshot().owner());
    }
    void cancel() { var exact = root; if (exact != null) exact.request(); else latchCancel(); }
    private void queueClose() { if (!postOwner(this::closeOnOwner)) cancel(); }
    private void closeOnOwner() {
        if (closed) return;
        boolean early = canceled.get() || workerReturned && stage != Stage.SEALED;
        if (!appendAttempted.get() && !early) return;
        if (!actualResourcesClosed()) { stage = Stage.CLEANUP_UNKNOWN; return; }
        closed = true;
        if (root != null) root.releaseIfClosed();
        released = true; loop.quitSafely();
        if (!reportReady) { stage = Stage.CANCELED; reportReady = true; notifyFinished(); }
    }
    boolean finishedAndReleased() { return reportReady && closed && released; }
}
