package com.aliothmoon.maafw.once;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Static Android wiring contracts only. These are not a live lifecycle or capture acceptance. */
public class UiImageAlignmentRuntimeWiringTest {
    private String source(String name) throws Exception {
        return Files.readString(Path.of(System.getProperty("staging.root", ".."),
                "app/src/main/java/com/aliothmoon/maafw/once/" + name + ".java"));
    }
    private String block(String source, String begin, String end) {
        int start = source.indexOf(begin), finish = source.indexOf(end, start + begin.length());
        assertTrue(begin, start >= 0); assertTrue(end, finish > start);
        return source.substring(start, finish);
    }
    private void before(String source, String earlier, String later) {
        int first = source.indexOf(earlier), second = source.indexOf(later);
        assertTrue(earlier, first >= 0); assertTrue(later, second > first);
    }

    @Test public void visibleStartUsesSharedRootAndQueuesHandoffAfterActivityAssignment() throws Exception {
        String s = source("UiImageAlignmentRuntime");
        String start = block(s, "static UiImageAlignmentRuntime start(", "private void requestHandoffOnMain()");
        assertTrue(start.contains("VisibleCaptureAuthority.issueAlignment(activity, click, epoch, approvalOwner, now)"));
        assertTrue(start.contains("RealCaptureClaim.visible(proof, epoch, approvalOwner, now)"));
        assertTrue(start.contains("CaptureSessionRuntime.alignmentEpoch()"));
        assertTrue(start.contains("CaptureSessionRuntime.retainAlignment(run, run.sessionEnd"));
        assertTrue(start.contains("run::closeOnOwner, () -> run.closed, run::postOwner"));
        before(start, "run.owner = new Handler", "run.root.arm()");
        assertTrue(start.contains("new Handler(Looper.getMainLooper()).post(run::requestHandoffOnMain)"));
        assertFalse(start.contains("activity.requestHandoff("));
        assertTrue(start.contains("run.stage = Stage.CLEANUP_UNKNOWN; run.reportReady = true"));
        assertTrue(start.contains("run.cancel(); run.notifyFinished()"));
        String handoff = block(s, "private void requestHandoffOnMain()", "private boolean postOwner(");
        assertTrue(handoff.contains("activity.requestHandoff(this::requireSessionCurrent"));
        assertTrue(handoff.contains("if (!postOwner(this::observeOnOwner)) cancel()"));
        assertEquals(2, handoff.split("catch \\(RuntimeException rejected\\)", -1).length - 1);
    }

    @Test public void everyOwnerQueueFailureReportsUnknownWithoutClaimingRelease() throws Exception {
        String s = source("UiImageAlignmentRuntime");
        String post = block(s, "private boolean postOwner(", "private boolean sessionCurrent()");
        assertTrue(post.contains("exact != null && exact.post(task)"));
        assertTrue(post.contains("constructionUnknown = true; stage = Stage.CLEANUP_UNKNOWN; reportReady = true"));
        before(post, "latchCancel()", "notifyFinished()");
        assertTrue(post.contains("notifyFinished(); return false"));
        assertFalse(post.contains("closed = true")); assertFalse(post.contains("released = true"));
        assertFalse(post.contains("releaseIfClosed")); assertFalse(post.contains("quitSafely"));
        assertTrue(s.contains("private void queueClose() { if (!postOwner(this::closeOnOwner)) cancel(); }"));
    }

    @Test public void uiAndImageUseFourFreshOrderedMetadataPairsWithExactOwners() throws Exception {
        String s = block(source("UiImageAlignmentRuntime"), "private void observeOnOwner()", "private BackendPorts.ClaimReceipt consumeFrameClaim(");
        before(s, "MetadataStage.UI_BEFORE", "MetadataStage.UI_AFTER");
        before(s, "MetadataStage.UI_AFTER", "MetadataStage.IMAGE_BEFORE");
        before(s, "MetadataStage.IMAGE_BEFORE", "MetadataStage.IMAGE_AFTER");
        for (var stage : UiImageAlignmentRuntime.MetadataStage.values())
            assertEquals(stage.name(), 1, s.split("MetadataStage\\." + stage.name() + "\\)", -1).length - 1);
        assertTrue(s.contains("AndroidUiStructureReader.readAlignmentRegions("));
        assertFalse(s.contains("AndroidUiStructureReader.readRegions("));
        assertFalse(s.contains("AndroidUiStructureReader.read("));
        assertTrue(s.contains("UiRegionSourceEvidence.begin(this, approvalOwner, epoch, nonce, before"));
        assertTrue(s.contains("source.consumeGeometry(this, approvalOwner, epoch, report"));
        assertTrue(s.contains("geometry.beginFrame(imageBefore, lower"));
        assertTrue(s.contains("this, approvalOwner, epoch, sessionEnd"));
        assertFalse(s.contains("Ready.fixture")); assertFalse(s.contains("RealCaptureClaim.fixture"));
    }

    @Test public void reservationsAndBurnsPrecedeEachDispatchAndNeverLeaveAnIdleBoundBackend() throws Exception {
        String s = block(source("UiImageAlignmentRuntime"), "private void observeOnOwner()", "private BackendPorts.ClaimReceipt consumeFrameClaim(");
        before(s, "budget.claimRun()", "UiImageAlignmentScope.burnRun(");
        before(s, "UiImageAlignmentScope.burnRun(", "AndroidUiStructureReader.readAlignmentRegions(");
        before(s, "budget.claimUi()", "UiImageAlignmentScope.burnUi(");
        before(s, "UiImageAlignmentScope.burnUi(", "UiRegionSourceEvidence.begin(");
        before(s, "budget.claimFrame()", "budget.claimOcr()");
        before(s, "root.boundDeadline(capture.deadline())", "ReviewShizukuPngBinding.bindReadOnlyAlignment(");
        before(s, "budget.claimOcr()", "ReviewShizukuPngBinding.bindReadOnlyAlignment(");
        int bind = s.indexOf("ReviewShizukuPngBinding.bindReadOnlyAlignment(");
        int run = s.indexOf("backend.start()", bind);
        assertTrue(run > bind);
        String boundToStart = s.substring(bind, run);
        assertFalse(boundToStart.contains("budget.claim")); assertFalse(boundToStart.contains("sourceGate()"));
        assertFalse(boundToStart.contains("readSource("));
        String claim = block(source("UiImageAlignmentRuntime"), "private BackendPorts.ClaimReceipt consumeFrameClaim(", "/** Pure cleanup predicate");
        assertTrue(claim.contains("RealCaptureClaim.consume(grant, epoch, approvalOwner"));
        assertTrue(claim.contains("UiImageAlignmentScope.frameStore(activity.getNoBackupFilesDir().toPath())"));
        assertTrue(claim.contains("outcome == RealCaptureClaim.Outcome.UNCERTAIN"));
    }

    @Test public void postImageSourceReadRequiresActualCleanNativeCompletion() throws Exception {
        String s = block(source("UiImageAlignmentRuntime"), "OnceBackend.Result result;", "} catch (Exception failed)");
        assertTrue(s.contains("result.phase() == OnceBackend.Phase.COMPLETED"));
        assertTrue(s.contains("result.failure() == OnceBackend.Failure.NONE"));
        assertTrue(s.contains("result.cancellation() == OnceBackend.Cancellation.NOT_REQUESTED && actualResourcesClosed()"));
        before(s, "readSource(MetadataStage.IMAGE_AFTER)", "binding.publication(imageAfter");
        assertFalse(s.contains("capture.captureCurrent"));
        assertTrue(s.contains("capture.publicationCurrent(SystemClock.elapsedRealtime())"));
    }

    @Test public void appendAlwaysConsumesReceiptThenQueuesOriginalOwnerCleanup() throws Exception {
        String s = source("UiImageAlignmentRuntime");
        String append = block(s, "String finalizePublication()", "private static String unqualified()");
        assertTrue(append.contains("appendAttempted.compareAndSet(false, true)"));
        assertTrue(append.contains("pendingPublication; pendingPublication = null"));
        assertTrue(append.contains("workerReturned && actualResourcesClosed()"));
        assertTrue(append.contains("sessionCurrent() && Looper.myLooper() == Looper.getMainLooper()"));
        assertTrue(append.contains("metadata.alignmentPublicationAuthorityMatches(shape)"));
        assertTrue(append.contains("current = false; hidden = false; source = false"));
        assertTrue(append.contains("publication.publish(now, current, hidden, source)"));
        assertTrue(append.contains("finally { queueClose(); }"));
        assertFalse(append.contains("readSource(")); assertFalse(append.contains("backend.start("));
        assertFalse(append.contains("releaseIfClosed("));
        String close = block(s, "private void closeOnOwner()", "boolean finishedAndReleased()");
        before(close, "if (!actualResourcesClosed())", "closed = true");
        before(close, "closed = true", "root.releaseIfClosed()");
    }

    @Test public void independentMetadataReadsCannotEnterLegacyCountersOrNavigationPaths() throws Exception {
        String s = source("ReviewBingNavigation");
        String read = block(s, "BingNavigationSnapshot.Result readAlignment(", "private BingNavigationSnapshot.Result readPair(");
        assertTrue(read.contains("alignmentPermit.read(stage)"));
        before(read, "alignmentPermit.read(stage)", "windows=readFixed(true)");
        before(read, "windows=readFixed(true)", "activities=readFixed(false)");
        assertTrue(read.contains("BingNavigationSnapshot.parseCurrentTemplate("));
        assertTrue(read.contains("Arrays.fill(windows,(byte)0)"));
        assertTrue(read.contains("Arrays.fill(activities,(byte)0)"));
        for (String old : new String[]{"PAIRS.incrementAndGet", "PREFLIGHT_PAIRS", "READ_ONLY_PAIRS", "RootSelectedPendingScope", "RootUniqueRefinedScope"})
            assertFalse(old, read.contains(old));
        assertTrue(s.contains("if(alignmentPermit!=null)return result;"));
        assertFalse(s.contains("PAIRS.set(")); assertFalse(s.contains("READ_ONLY_PAIRS.set("));
        String fixed = s.substring(s.indexOf("private byte[] readFixedCommand("));
        assertTrue(fixed.split("alignmentPermit!=null&&!alignmentPermit.current\\(\\)", -1).length - 1 >= 4);
        String append = block(s, "boolean alignmentPublicationAuthorityMatches(", "boolean authorityMatches(");
        assertFalse(append.contains("readFixed")); assertFalse(append.contains("readAlignment("));
        assertFalse(append.contains("alignmentPermit.read("));
    }

    @Test public void runtimeHasNoTaskActorOldScopeRecoveryOrRawOutput() throws Exception {
        String s = source("UiImageAlignmentRuntime");
        for (String forbidden : new String[]{"ReviewShizukuTapActor", "injectInputEvent", "dispatchGesture", "performAction(",
                "TaskRunCoordinator", "RealCaptureClaim.fixture", "FileBurnStore", "ReadOnlyCheckpoint", "System.currentTimeMillis()",
                "startActivity(", "Runtime.getRuntime", "ProcessBuilder", "requestPermission(", "Thread.interrupt(", "frame.pixels"})
            assertFalse(forbidden, s.contains(forbidden));
        assertTrue(s.contains("taskTaps=0 swipes=0 back=0 textEntry=0 businessLedgerChanged=false"));
        assertTrue(s.contains("nonAtomic=true"));
        assertTrue(s.contains("counterAuthority=false actionAuthority=false"));
    }
}
