package com.aliothmoon.maafw.once;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Source contract checks only: never initializes Android automation in host tests. */
public class NativeUiStructureHelperWiringTest {
    private static String source() throws Exception {
        String staging = System.getProperty("staging.root");
        if (staging != null) return Files.readString(Path.of(staging).resolve(
                "app/src/main/java/com/aliothmoon/maafw/once/NativeUiStructureHelper.java"));
        Path p=Path.of("src/main/java/com/aliothmoon/maafw/once/NativeUiStructureHelper.java");
        if(!Files.isRegularFile(p))p=Path.of("app").resolve(p);
        if(!Files.isRegularFile(p))p=Path.of("../app/src/main/java/com/aliothmoon/maafw/once/NativeUiStructureHelper.java");
        return Files.readString(p);
    }
    @Test public void onlyNonSuppressingConnectAndNoExemptions() throws Exception {
        String s=source();
        assertTrue(s.contains("getMethod(\"connect\", int.class)"));
        assertTrue(s.contains("UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES"));
        assertFalse(s.contains("setHiddenApiExemptions"));assertFalse(s.contains("setAccessible("));
    }
    @Test public void noInputScreenshotDumpOrRawExceptionOutput() throws Exception {
        String s=source();
        for(String forbidden:new String[]{"injectInputEvent(","performAction(","takeScreenshot(","dumpWindow", "printStackTrace(","getMessage(","Log.","setRotation(","Settings.","FileOutputStream","FileWriter"})assertFalse(forbidden,s.contains(forbidden));
        assertTrue(s.contains("System.out.println(NativeUiStructureProtocol.encode(report))"));
    }
    @Test public void fixedInvocationRootAndResourceBounds() throws Exception {
        String s=source();
        for(String required:new String[]{"validReadyArguments(args)","validNonce(args[0])","deadline - start > 10000",
                "Process.myUid() != 0","start + (readyMode?8000:5000)","scheduled + count > 512","current.depth() >= 32",
                "value.length() > 2048","> 32768","FOREIGN_ROOT","FOREIGN_NODE"})assertTrue(required,s.contains(required));
    }
    @Test public void finalOutputFollowsCleanupAndNoLateSuccess() throws Exception {
        String s=source();
        int cleanup=s.indexOf("getMethod(\"disconnect\")");int output=s.indexOf("System.out.println");
        assertTrue(cleanup>0 && cleanup<output);assertTrue(s.indexOf("thread.quitSafely()")<output);
        assertTrue(s.contains("if (finished >= deadline || finished - start > 10000) return;"));
        assertTrue(s.contains("Reason.CLEANUP_UNKNOWN"));
    }
    @Test public void onlyActualBoundedNodeFieldsReachParser() throws Exception {
        String s=source();
        assertTrue(s.contains("field(node.getText()), description = field(node.getContentDescription())"));
        assertTrue(s.contains("NativeUiStructureParser.parse(records)"));
        assertTrue(s.contains("nodeCount = records.size();"));
        assertTrue(s.contains("records.clear();"));
        assertFalse(s.contains("node.toString()"));
    }
    @Test public void rootMustBeVisibleAndEveryVisibleNodeContained() throws Exception {
        String s=source();
        assertTrue(s.contains("if (!root.isVisibleToUser())"));
        assertTrue(s.contains("boolean visible = node.isVisibleToUser();"));
        assertTrue(s.contains("nodeBounds.isEmpty() || !bounds.contains(nodeBounds)"));
        assertTrue(s.contains("visible, true, text, description"));
    }
    @Test public void temporaryWindowFlagHasImmediateExactReadbackAndNoEnumeration() throws Exception {
        String s=source();
        assertTrue(s.contains("serviceInfo.flags | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS"));
        assertTrue(s.contains("automation.setServiceInfo(serviceInfo);"));
        assertTrue(s.contains("AccessibilityServiceInfo readback = automation.getServiceInfo();"));
        assertTrue(s.contains("readback == null || readback.flags != requiredFlags"));
        assertTrue(s.contains("Reason.WINDOW_CONFIG_UNAVAILABLE"));
        assertFalse(s.contains(".getWindows("));assertFalse(s.contains(".getWindowsOnAllDisplays("));
        assertTrue(s.indexOf("readback.flags != requiredFlags") < s.indexOf("automation.getRootInActiveWindow()"));
    }
    @Test public void exactCurrentWindowMustBeActiveWithoutGuessingFocus() throws Exception {
        String s=source();
        assertTrue(s.contains("window.getId() != windowId || !window.isActive()"));
        assertFalse(s.contains("window.isFocused()"));
        assertTrue(s.contains("displayId = window.getDisplayId();"));
    }
    @Test public void windowRecycleFailureDowngradesCleanup() throws Exception {
        String s=source();
        assertTrue(s.contains("try { window.recycle(); } catch (Throwable ignored) { clean = false; }"));
        assertTrue(s.contains("NativeUiStructureProtocol.Cleanup.UNKNOWN"));
    }
    @Test public void preApi30CannotGuessWindowDisplay() throws Exception {
        String s=source();int gate=s.indexOf("if (android.os.Build.VERSION.SDK_INT < 30)");
        int read=s.indexOf("displayId = window.getDisplayId();");assertTrue(gate>=0&&gate<read);
        assertTrue(s.substring(gate,read).contains("throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_UNAVAILABLE)"));
        assertFalse(s.contains("SuppressLint"));
    }
}
