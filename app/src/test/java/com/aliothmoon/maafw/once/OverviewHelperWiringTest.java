package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class OverviewHelperWiringTest {
    private static String helper() throws Exception {
        Path path=Path.of("../app/src/main/java/com/aliothmoon/maafw/once/NativeUiStructureHelper.java");
        if(!Files.exists(path))path=Path.of("app/src/main/java/com/aliothmoon/maafw/once/NativeUiStructureHelper.java");
        return Files.readString(path);
    }
    @Test public void independentModeKeepsRootWindowAndCleanupChecks() throws Exception {
        String s=helper();assertTrue(s.contains("\"OVERVIEW_V5\".equals(args[2])"));
        assertTrue(s.contains("Process.myUid() != 0"));assertTrue(s.contains("node.getWindowId() != windowId"));
        assertTrue(s.contains("displayId != 0"));assertTrue(s.contains("OverviewUiParser.parse(boundRecords)"));
        assertTrue(s.contains("OverviewUiParser.scrollHint(boundRecords,overviewScroll)"));
        assertTrue(s.contains("overviewScroll.clear()"));assertTrue(s.contains("boundMode&&!overviewMode"));
    }
    @Test public void helperDoesNotDispatchAccessibilityInputOrExportNodeText() throws Exception {
        String s=helper();assertFalse(s.contains("performAction("));assertFalse(s.contains("dispatchGesture("));
        assertFalse(s.contains("System.out.println(text"));assertFalse(s.contains("System.out.println(description"));
        assertTrue(s.contains("OverviewUiProtocol.encode(new OverviewUiProtocol.Report(report,overview,scrollHint))"));
    }
}
