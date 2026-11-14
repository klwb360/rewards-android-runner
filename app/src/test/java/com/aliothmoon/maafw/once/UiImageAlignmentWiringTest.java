package com.aliothmoon.maafw.once;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native wiring contracts only; these checks do not exercise Android, capture or OCR. */
public class UiImageAlignmentWiringTest {
    private String source(String name) throws Exception {
        return Files.readString(Path.of(System.getProperty("staging.root", ".."),
                "app/src/main/java/com/aliothmoon/maafw/once/" + name + ".java"));
    }

    @Test public void nativeFactoryUsesActualMaaAndExistingCaptureBoundary() throws Exception {
        String s = source("ReviewShizukuPngBinding");
        assertTrue(s.contains("MaaRecognizer.rootUiRegionAlignmentAudit(context,capture)"));
        assertTrue(s.contains("capture.captureCurrent(SystemClock.elapsedRealtime())"));
        assertTrue(s.contains("new AndroidReviewShizukuPngTransport(context,geometry,capture.rotation(),gate)"));
        assertTrue(s.contains("bindAlignmentInternal(existingClaim,transport,transport,existingFence,recognizer,existingOwnerExecutor"));
        assertTrue(s.contains("SystemClock::elapsedRealtimeNanos"));
        assertTrue(s.contains("new OnceBackend(claim,source,recognizer,executor)"));
        assertTrue(s.contains("new UiImageAlignmentPublication(takeReceipt(after,now)"));
    }

    @Test public void candidateUsesTheSameFrameAndExistingSingleOcrBatch() throws Exception {
        String s = source("MaaRecognizer");
        assertTrue(s.contains("if(alignmentCapture!=null)refineUiImageAlignment(stop)"));
        assertTrue(s.contains("alignmentCapture.prepare(this,owner,materialFrame,gray,materialRows"));
        assertTrue(s.contains("if(liveCheckin)break;"));
        int start = s.indexOf("private void refineUiImageAlignment(");
        int end = s.indexOf("private void refinePreparedRewardCard(", start);
        assertTrue(start >= 0 && end > start);
        String method = s.substring(start, end);
        assertTrue(method.contains("for(int i=0;i<gray.length;i+=4096){checkStop(stop);materialFrame.copyGrayRange(i,gray,i,Math.min(4096,gray.length-i));}"));
        assertTrue(method.contains("Arrays.fill(gray,(byte)0)"));
        assertTrue(method.contains("materialRows=null"));
        assertFalse(method.contains("MaaTaskerPostRecognition"));
        assertFalse(method.contains("takeScreenshot"));
    }

    @Test public void nativeReceiptRequiresActualBackendAndMatchingImageAudits() throws Exception {
        String s = source("ReviewShizukuPngBinding");
        assertTrue(s.contains("var terminal=binding.backend.snapshot()"));
        assertTrue(s.contains("var clean=binding.cleanReceipt()"));
        assertTrue(s.contains("binding.source.sourceWindow(owner)"));
        assertTrue(s.contains("candidate.matches(capture,recognizer,owner)"));
        assertTrue(s.contains("MaaImageBufferAudit.matchesGraySha256("));
        assertTrue(s.contains("capture.sealSource("));
        for (var constructor : ReviewShizukuPngBinding.AlignmentCompletion.class.getDeclaredConstructors())
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }

    @Test public void newGeometryCannotAuthorizeInputOrReopenOldAdmission() throws Exception {
        for (String name : new String[]{"UiRegionImageRefiner", "UiImageGeometryBinding", "UiImageAlignmentCapture", "UiImageAlignmentPublication"}) {
            String s = source(name);
            for (String forbidden : new String[]{"TapTarget", "dispatchGesture(", "performAction(", "injectInputEvent(", "MediaProjectionManager", "ReviewShizukuTapActor"})
                assertFalse(name + ": " + forbidden, s.contains(forbidden));
        }
        String checkpoint = source("ReadOnlyCheckpoint");
        assertTrue(checkpoint.contains("CURRENT=UI_BOUND_POINTS_V53"));
        assertTrue(checkpoint.contains("UI checkpoint cannot create an image claim"));
        assertFalse(source("CaptureSessionRuntime").contains("bindReadOnlyAlignment("));
        assertTrue(source("OwnStateSummaryUi").contains("ACTIVE=true"));
    }

    @Test public void missingSealedReceiptNeverPublishesGeometry() {
        var publication = new UiImageAlignmentPublication(null, new Object(), new Object(), new Object(), 10000);
        for (long now : new long[]{2500, 2501}) {
            String text = publication.publish(now, true, true, true);
            assertTrue(text.contains("alignmentEvidence=UNQUALIFIED"));
            assertTrue(text.contains("imageBinding=NOT_ESTABLISHED regions=SUPPRESSED"));
            assertTrue(text.contains("counterAuthority=false actionAuthority=false taskTaps=0"));
            assertFalse(text.contains("VISUAL_CANDIDATE"));
            assertFalse(text.contains("cardBoundary="));
        }
    }
}
