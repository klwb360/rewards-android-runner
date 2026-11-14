package com.aliothmoon.maafw.once;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Namespace preservation and observation-only diagnostics; no real UI or capture is invoked. */
public class UiImageAlignmentDiagnosticEntryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private String source(String name) throws Exception {
        return Files.readString(Path.of(System.getProperty("staging.root", ".."),
                "app/src/main/java/com/aliothmoon/maafw/once/" + name + ".java"));
    }
    @Test public void diagnosticNamespaceCannotReuseOriginalMarkers() throws Exception {
        assertEquals("rewards-ui-image-alignment-v1", UiImageAlignmentScope.DIRECTORY);
        assertEquals("rewards-ui-image-alignment-diagnostic-v2", UiImageAlignmentScope.DIAGNOSTIC_DIRECTORY);
        assertNotEquals(UiImageAlignmentScope.DIRECTORY, UiImageAlignmentScope.DIAGNOSTIC_DIRECTORY);
        for (String testedSlot : new String[]{"RUN", "UI", "FRAME"}) verifySeparatedSlot(testedSlot);
    }
    private void verifySeparatedSlot(String testedSlot) throws Exception {
        Path root = temporary.newFolder(testedSlot).toPath().toAbsolutePath().normalize();
        Path old = Files.createDirectory(root.resolve(UiImageAlignmentScope.DIRECTORY));
        byte[] saved = "old incomplete record must remain\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (String slot : new String[]{"RUN", "UI", "FRAME"}) Files.write(old.resolve(slot + ".burned"), saved);
        boolean noIdentity = Files.readAttributes(root, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey() == null;
        try {
            burnDiagnostic(root, testedSlot);
            assertFalse("A missing directory identity must reject before mutation", noIdentity);
        } catch (java.io.IOException failure) {
            if (noIdentity) {
                assertEquals(java.io.IOException.class, failure.getClass());
                assertEquals("Alignment directory identity unconfirmed", failure.getMessage());
                assertEquals(UiImageAlignmentScope.class.getName(), failure.getStackTrace()[0].getClassName());
                assertEquals("directoryIdentity", failure.getStackTrace()[0].getMethodName());
                assertFalse(Files.exists(root.resolve(UiImageAlignmentScope.DIAGNOSTIC_DIRECTORY)));
            } else {
                assertTrue("Only actual directory force uncertainty is accepted", java.util.Arrays.stream(failure.getStackTrace())
                        .anyMatch(frame -> frame.getClassName().equals(UiImageAlignmentScope.class.getName()) && frame.getMethodName().equals("forceDirectory")));
                assertEquals("ui-image-alignment-diagnostic-v2 slot=" + testedSlot
                        + " maxUiReads=1 maxFrames=1 maxOcrPerFrame=1 maxNavigation=0 maxInput=0\n",
                        Files.readString(root.resolve(UiImageAlignmentScope.DIAGNOSTIC_DIRECTORY).resolve(testedSlot + ".burned")));
            }
        }
        for (String slot : new String[]{"RUN", "UI", "FRAME"}) assertArrayEquals(saved, Files.readAllBytes(old.resolve(slot + ".burned")));
        assertThrows(java.io.IOException.class, () -> burnDiagnostic(root, testedSlot));
    }
    private void burnDiagnostic(Path root, String slot) throws java.io.IOException {
        switch (slot) {
            case "RUN" -> UiImageAlignmentScope.burnDiagnosticRun(root);
            case "UI" -> UiImageAlignmentScope.burnDiagnosticUi(root);
            case "FRAME" -> UiImageAlignmentScope.diagnosticFrameStore(root).burn("11111111-1111-1111-1111-111111111111", "fixed");
            default -> throw new AssertionError(slot);
        }
    }
    @Test public void diagnosticStoreIsSingleUseEvenWhenFirstBurnCannotBeConfirmed() {
        var store = UiImageAlignmentScope.diagnosticFrameStore(temporary.getRoot().toPath().toAbsolutePath().normalize());
        assertThrows(SecurityException.class, () -> store.burn("invalid", ""));
        assertThrows(SecurityException.class, () -> store.burn("11111111-1111-1111-1111-111111111111", "fixed"));
    }
    @Test public void elapsedTimeIsBoundedAndUnknownIsNotZero() {
        assertEquals(-1, UiImageAlignmentTiming.elapsed(-1, 10));
        assertEquals(-1, UiImageAlignmentTiming.elapsed(10, 9));
        assertEquals(0, UiImageAlignmentTiming.elapsed(10, 10));
        assertEquals(600_000, UiImageAlignmentTiming.elapsed(0, Long.MAX_VALUE));
        assertEquals("NOT_OBSERVED", UiImageAlignmentTiming.value(-1));
        assertEquals("5001", UiImageAlignmentTiming.value(5001));
        assertEquals("600000", UiImageAlignmentTiming.value(Long.MAX_VALUE));
        assertEquals(600_000, UiImageAlignmentDiagnostics.elapsed(Long.MAX_VALUE, 0));
        assertEquals(-1, UiImageAlignmentDiagnostics.elapsed(9, 10));
    }
    @Test public void explicitSelectedEntryControlsAllThreeStores() throws Exception {
        String s=source("UiImageAlignmentRuntime"), a=source("DormantCaptureConfirmationActivity");
        assertTrue(s.contains("activity.alignmentDiagnosticSelected() != diagnosticRun"));
        assertTrue(s.contains("if (diagnosticRun) UiImageAlignmentScope.burnDiagnosticRun("));
        assertTrue(s.contains("if (diagnosticRun) UiImageAlignmentScope.burnDiagnosticUi("));
        assertTrue(s.contains("diagnosticRun ? UiImageAlignmentScope.diagnosticFrameStore("));
        assertTrue(a.contains("alignmentDiagnostic=true;"));
        assertTrue(a.contains("alignmentDiagnostic?UiImageAlignmentRuntime.startDiagnostic("));
        assertFalse(a.contains("getBooleanExtra(\"diagnostic"));
        String scope=source("UiImageAlignmentScope");
        assertTrue(scope.contains("burnDiagnosticRun(Path files) throws IOException { burn(files, Slot.RUN, Profile.DIAGNOSTIC); }"));
        assertTrue(scope.contains("burnDiagnosticUi(Path files) throws IOException { burn(files, Slot.UI, Profile.DIAGNOSTIC); }"));
        String frame=scope.substring(scope.indexOf("static RealCaptureClaim.BurnStore diagnosticFrameStore("),scope.indexOf("private static RealCaptureClaim.BurnStore frameStore("));
        assertTrue(frame.contains("return frameStore(files, Profile.DIAGNOSTIC)"));
    }
    @Test public void terminalDumpOnlyReadsCachedSafeHistory() throws Exception {
        String s=source("DormantCaptureConfirmationActivity");
        String dump=s.substring(s.indexOf("@Override public void dump("),s.indexOf("private SealedReadOnlyReceipt receipt"));
        assertTrue(dump.contains("writer.println(value.line(remaining))"));
        assertTrue(dump.contains("if(alignmentFinished&&history!=null)"));
        assertTrue(dump.contains("RewardsAlignmentDiagnostic version=2 "));
        for(String forbidden:new String[]{"super.dump", "finalizePublication", "readSource", "diagnostics()", "consume(", "getMessage", "SystemClock"})
            assertFalse(forbidden, dump.contains(forbidden));
    }
    @Test public void failureStageRecordedBeforeHoldAndAppendDoesNotReplaceIt() throws Exception {
        String s=source("UiImageAlignmentRuntime");
        int caught=s.indexOf("} catch (Exception failed)");
        int failure=s.indexOf("failureAt = stage;",caught),hold=s.indexOf("stage = canceled.get()",caught);
        assertTrue(caught>=0 && failure>caught && hold>failure);
        String append=s.substring(s.indexOf("String finalizePublication()"),s.indexOf("private static String unqualified()"));
        assertFalse(append.contains("failureAt ="));
        assertFalse(append.contains("backend.start()"));
        assertFalse(append.contains("readSource("));
        assertTrue(append.contains("appendRuntimeCurrent="));
        assertFalse(s.contains("getMessage()"));
    }
}
