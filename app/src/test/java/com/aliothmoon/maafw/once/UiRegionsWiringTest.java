package com.aliothmoon.maafw.once;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Wiring checks complement parser/protocol/source tests; no Android UI is exercised here. */
public class UiRegionsWiringTest {
    private String source(String name) throws Exception {
        return Files.readString(Path.of(System.getProperty("staging.root", ".."),
                "app/src/main/java/com/aliothmoon/maafw/once/" + name + ".java"));
    }
    @Test public void regionHelperModeIsExactAndRetainsLegacyMode() {
        for (String mode : new String[]{"BOUND_REGIONS", "BOUND_POINTS_V53"}) {
            assertTrue(NativeUiStructureHelper.validReadyArguments(new String[]{"n", "d", mode}));
            assertFalse(NativeUiStructureHelper.validReadyArguments(new String[]{"n", "d", mode + " "}));
            assertFalse(NativeUiStructureHelper.validReadyArguments(new String[]{"n", "d", mode, "extra"}));
        }
    }
    @Test public void regionsComeFromTheSameSingleTreeAndHaveNoInput() throws Exception {
        String s = source("NativeUiStructureHelper");
        assertTrue(s.contains("NativeUiBoundPointsParser.parseRegions(boundRecords)"));
        assertTrue(s.contains("result=bound.counters();regions=bound.regions()"));
        assertTrue(s.contains("if(regionMode)regions=NativeUiRegions.Pair.empty()"));
        assertTrue(s.indexOf("automation.getRootInActiveWindow()") >= 0);
        assertEquals(s.indexOf("automation.getRootInActiveWindow()"), s.lastIndexOf("automation.getRootInActiveWindow()"));
        for (String forbidden : new String[]{"performAction(", "injectInputEvent(", "takeScreenshot(", "getWindows(", "dumpWindowHierarchy"})
            assertFalse(forbidden, s.contains(forbidden));
    }
    @Test public void readerRequiresBoundCheckpointAndStrictNewVersion() throws Exception {
        String s = source("AndroidUiStructureReader");
        assertTrue(s.contains("regionMode && !boundMode"));
        assertTrue(s.contains("regionMode ? \"BOUND_REGIONS\" : \"BOUND_POINTS_V53\""));
        assertTrue(s.contains("nonce,dispatched,deadline,SystemClock.elapsedRealtime(),true,false,true,true"));
        assertTrue(s.indexOf("service.newProcess(command, null, null)") >= 0);
        assertEquals(s.indexOf("service.newProcess(command, null, null)"), s.lastIndexOf("service.newProcess(command, null, null)"));
        assertTrue(s.indexOf("finalGate.allowDispatch()") >= 0);
        assertTrue(s.indexOf("finalGate.allowDispatch()") < s.indexOf("service.newProcess(command, null, null)"));
    }
    @Test public void runtimeCarriesOwnedBeforeAndAfterEvidenceThroughCleanup() throws Exception {
        String s = source("CaptureSessionRuntime");
        int before = s.indexOf("if(!entryCounterSource(V46EntryMetadataReader.Stage.COUNTER_BEFORE))return false");
        int begin = s.indexOf("UiRegionSourceEvidence.begin(this,returnReadIntent,EPOCH,nonce,entryProof,SystemClock.elapsedRealtime())");
        int read = s.indexOf("?AndroidUiStructureReader.readRegions(");
        int after = s.indexOf("entryCounterSource(V46EntryMetadataReader.Stage.COUNTER_AFTER)", read);
        int seal = s.indexOf("uiRegionPending.complete(report,entryProof,SystemClock.elapsedRealtime(),qualified&&rootEpochHeld)");
        assertTrue(before >= 0 && before < begin && begin < read && read < after && after < seal);
        assertTrue(s.contains("if(!rootEpochHeld)return false"));
        assertTrue(s.indexOf("EPOCH.release(this)", seal) > seal);
        assertTrue(s.contains("regionSource,this,returnReadIntent,EPOCH)"));
        assertTrue(s.contains("if(returnReadIntent.uiBoundPoints())current=current&&!EPOCH.retaining()"));
    }
}
