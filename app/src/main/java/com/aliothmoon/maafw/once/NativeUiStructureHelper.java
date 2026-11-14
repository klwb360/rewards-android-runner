package com.aliothmoon.maafw.once;

import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Fixed app_process entry. No XML, files, input actions, exception text or node logging. */
public final class NativeUiStructureHelper {
    private static final String BING = "com.microsoft.bing";
    private record Pending(AccessibilityNodeInfo node, int index, int parent, int depth) { }
    private static final class Stop extends Exception {
        private static final long serialVersionUID = 1L;
        final NativeUiStructureProtocol.Reason reason;
        Stop(NativeUiStructureProtocol.Reason reason) { this.reason = reason; }
    }
    @SuppressWarnings("deprecation") // Required on older Android; a no-op on newer node implementations.
    public static void main(String[] args) {
        // Invalid invocation produces no frame, and does not initialize any automation objects.
        long start = SystemClock.elapsedRealtime(), deadline;
        if (args == null || !validReadyArguments(args) || !NativeUiStructureProtocol.validNonce(args[0])) return;
        boolean readyMode=args.length==3;
        boolean regionMode=readyMode&&"BOUND_REGIONS".equals(args[2]);
        boolean boundMode=regionMode||readyMode&&"BOUND_POINTS_V53".equals(args[2]);
        boolean formatMode=readyMode&&"FORMAT_V50".equals(args[2]);
        boolean locatedMode=readyMode&&"DIAGNOSTIC_LOCATED_V52".equals(args[2]);
        boolean fieldMode=readyMode&&"DIAGNOSTIC_V51".equals(args[2]);
        if(locatedMode)fieldMode=true;
        try {
            deadline = NativeUiStructureProtocol.number(args[1]);
            if (deadline <= start || deadline - start > 10000) return;
        } catch (RuntimeException ignored) { return; }
        long readEnd = Math.min(deadline, start + (readyMode?8000:5000)), acquired = start;
        NativeUiReadyDiagnostics.Idle idle=NativeUiReadyDiagnostics.Idle.NOT_REQUESTED;
        NativeUiReadyDiagnostics.Accumulator diagnostics=readyMode?new NativeUiReadyDiagnostics.Accumulator():null;
        NativeUiFieldDiagnostics.Accumulator fieldDiagnostics=fieldMode?new NativeUiFieldDiagnostics.Accumulator(locatedMode):null;
        NativeUiStructureProtocol.Reason reason = NativeUiStructureProtocol.Reason.TREE_FAILED;
        NativeUiStructureProtocol.Cleanup cleanup = NativeUiStructureProtocol.Cleanup.NOT_STARTED;
        NativeUiStructureProtocol.Task search = NativeUiStructureProtocol.Task.unknown();
        NativeUiStructureProtocol.Task reading = NativeUiStructureProtocol.Task.unknown();
        NativeUiRegions.Pair regions = regionMode ? NativeUiRegions.Pair.empty() : null;
        HandlerThread thread = null;
        UiAutomation automation = null;
        boolean attemptedConnect = false, clean = true;
        ArrayDeque<Pending> pending = new ArrayDeque<>();
        List<NativeUiStructureParser.Node> records = new ArrayList<>();
        List<NativeUiBoundPointsParser.Node> boundRecords = new ArrayList<>();
        int chars = 0, nodeCount = 0, windowId = -1, displayId = -1;
        Rect bounds = new Rect();
        try {
            if (Process.myUid() != 0) throw new Stop(NativeUiStructureProtocol.Reason.NOT_ROOT);
            checkTime(readEnd);
            thread = new HandlerThread("NativeUiStructure");
            thread.start();
            Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation = UiAutomation.class.getConstructor(Looper.class, connectionType)
                    .newInstance(thread.getLooper(), connection);
            checkTime(readEnd);
            attemptedConnect = true;
            UiAutomation.class.getMethod("connect", int.class).invoke(automation,
                    UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            checkTime(readEnd);
            // Approved temporary own-service capability; never enumerate other windows.
            try {
                AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
                if (serviceInfo == null) throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_CONFIG_UNAVAILABLE);
                int requiredFlags = serviceInfo.flags | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | (fieldMode ? AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS : 0)
                        | (boundMode ? AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS : 0);
                serviceInfo.flags = requiredFlags;
                automation.setServiceInfo(serviceInfo);
                AccessibilityServiceInfo readback = automation.getServiceInfo();
                if (readback == null || readback.flags != requiredFlags)
                    throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_CONFIG_UNAVAILABLE);
            } catch (Stop stopped) { throw stopped;
            } catch (Throwable ignored) {
                throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_CONFIG_UNAVAILABLE);
            }
            if(readyMode){
                if(readEnd-SystemClock.elapsedRealtime()<3000){idle=NativeUiReadyDiagnostics.Idle.TIMEOUT;throw new Stop(NativeUiStructureProtocol.Reason.IDLE_TIMEOUT);}
                try{automation.waitForIdle(500,3000);idle=NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED;}
                catch(java.util.concurrent.TimeoutException timedOut){idle=NativeUiReadyDiagnostics.Idle.TIMEOUT;throw new Stop(NativeUiStructureProtocol.Reason.IDLE_TIMEOUT);}
                catch(Throwable ignored){idle=NativeUiReadyDiagnostics.Idle.FAILED;throw new Stop(NativeUiStructureProtocol.Reason.IDLE_FAILED);}
            }
            checkTime(readEnd);
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) throw new Stop(NativeUiStructureProtocol.Reason.NO_ROOT);
            pending.add(new Pending(root, 0, -1, 0));
            if (!packageMatches(root.getPackageName())) throw new Stop(NativeUiStructureProtocol.Reason.FOREIGN_ROOT);
            if (!root.isVisibleToUser()) throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_UNAVAILABLE);
            root.getBoundsInScreen(bounds);
            windowId = root.getWindowId();
            AccessibilityWindowInfo window = root.getWindow();
            if (window == null) throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_UNAVAILABLE);
            try {
                if (window.getId() != windowId || !window.isActive())
                    throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_UNAVAILABLE);
                if (android.os.Build.VERSION.SDK_INT < 30)
                    throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_UNAVAILABLE);
                displayId = window.getDisplayId();
            } finally {
                try { window.recycle(); } catch (Throwable ignored) { clean = false; }
            }
            if (displayId != 0 || windowId < 0 || bounds.isEmpty() || bounds.left < -4096
                    || bounds.top < -4096 || bounds.right > 8192 || bounds.bottom > 8192)
                throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_UNAVAILABLE);
            int scheduled = 1;
            while (!pending.isEmpty()) {
                checkTime(readEnd);
                Pending current = pending.removeFirst();
                AccessibilityNodeInfo node = current.node();
                try {
                    if (!packageMatches(node.getPackageName())) throw new Stop(NativeUiStructureProtocol.Reason.FOREIGN_NODE);
                    if (node.getWindowId() != windowId) throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_UNAVAILABLE);
                    boolean visible = node.isVisibleToUser();
                    if (visible) {
                        Rect nodeBounds = new Rect();
                        node.getBoundsInScreen(nodeBounds);
                        if (nodeBounds.isEmpty() || !bounds.contains(nodeBounds))
                            throw new Stop(NativeUiStructureProtocol.Reason.WINDOW_UNAVAILABLE);
                    }
                    String text = field(node.getText()), description = field(node.getContentDescription());
                    if (chars + text.length() + description.length() > 32768)
                        throw new Stop(NativeUiStructureProtocol.Reason.TREE_OVERFLOW);
                    chars += text.length() + description.length();
                    if(readyMode)diagnostics.node(node.getClassName(),text,description);
                    if(fieldMode){Rect originalBounds=new Rect();node.getBoundsInScreen(originalBounds);
                        fieldDiagnostics.node(current.index(),current.parent(),current.depth(),originalBounds.left,originalBounds.top,originalBounds.right,originalBounds.bottom,visible,node.isEnabled(),node.isClickable(),node.getClassName(),node.getViewIdResourceName(),text,description);
                    }
                    if(boundMode){Rect originalBounds=new Rect();node.getBoundsInScreen(originalBounds);
                        boundRecords.add(new NativeUiBoundPointsParser.Node(current.index(),current.parent(),visible,true,
                                node.getViewIdResourceName(),text,description,originalBounds.left,originalBounds.top,originalBounds.right,originalBounds.bottom));
                    }
                    records.add(new NativeUiStructureParser.Node(current.index(), current.parent(),
                            visible, true, text, description));
                    int count = node.getChildCount();
                    if (count < 0 || scheduled + count > 512 || count > 0 && current.depth() >= 32)
                        throw new Stop(NativeUiStructureProtocol.Reason.TREE_OVERFLOW);
                    for (int i = 0; i < count; i++) {
                        checkTime(readEnd);
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child == null) throw new Stop(NativeUiStructureProtocol.Reason.TREE_FAILED);
                        pending.addLast(new Pending(child, scheduled++, current.index(), current.depth()+1));
                    }
                } finally {
                    try { node.recycle(); } catch (Throwable ignored) { clean = false; }
                }
            }
            checkTime(readEnd);
            if(!fieldMode){if(!readyMode||!diagnostics.blocked()){
                NativeUiStructureParser.Result result;
                if(regionMode){
                    var bound=NativeUiBoundPointsParser.parseRegions(boundRecords);
                    result=bound.counters();regions=bound.regions();
                }else result = boundMode?NativeUiBoundPointsParser.parse(boundRecords):formatMode?NativeUiStructureV50Parser.parse(records):NativeUiStructureParser.parse(records);
                search = task(result.search()); reading = task(result.reading());
            }} // A valid tree with a fixed sign-in/error label retains UNKNOWN tasks; not page-ready proof.
            acquired = SystemClock.elapsedRealtime();
            checkTime(readEnd);
            reason = NativeUiStructureProtocol.Reason.OK;
        } catch (Stop stopped) { reason = stopped.reason;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            reason = NativeUiStructureProtocol.Reason.REFLECTION_UNAVAILABLE;
        } catch (IllegalAccessException | SecurityException e) {
            reason = NativeUiStructureProtocol.Reason.REFLECTION_DENIED;
        } catch (InvocationTargetException e) {
            reason = e.getCause() instanceof SecurityException
                    ? NativeUiStructureProtocol.Reason.REFLECTION_DENIED : NativeUiStructureProtocol.Reason.CONNECT_FAILED;
        } catch (Throwable ignored) { reason = NativeUiStructureProtocol.Reason.TREE_FAILED;
        } finally {
            while (!pending.isEmpty()) {
                try { pending.removeFirst().node().recycle(); } catch (Throwable ignored) { clean = false; }
            }
            nodeCount = records.size();
            records.clear();
            boundRecords.clear();
            if (attemptedConnect) {
                try { UiAutomation.class.getMethod("disconnect").invoke(automation); }
                catch (Throwable ignored) { clean = false; }
            }
            if (thread != null) {
                try {
                    thread.quitSafely();
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining > 0) thread.join(Math.min(remaining, 250));
                    if (thread.isAlive()) clean = false;
                } catch (Throwable ignored) { clean = false; }
            }
            cleanup = clean ? (attemptedConnect ? NativeUiStructureProtocol.Cleanup.CONFIRMED
                    : NativeUiStructureProtocol.Cleanup.NOT_STARTED) : NativeUiStructureProtocol.Cleanup.UNKNOWN;
        }
        long finished = SystemClock.elapsedRealtime();
        // Cooperative deadline: synchronous Binder can block. Owner must fail closed/quarantine
        // uncertain process cleanup; this check never emits late success.
        if (finished >= deadline || finished - start > 10000) return;
        if (cleanup == NativeUiStructureProtocol.Cleanup.UNKNOWN) reason = NativeUiStructureProtocol.Reason.CLEANUP_UNKNOWN;
        if (reason != NativeUiStructureProtocol.Reason.OK) {
            search = NativeUiStructureProtocol.Task.unknown(); reading = NativeUiStructureProtocol.Task.unknown();
            if(regionMode)regions=NativeUiRegions.Pair.empty();
            acquired = Math.min(finished, Math.max(start, acquired));
            // Invalid geometry must not prevent a bounded failure receipt.
            windowId = -1; displayId = -1; bounds.setEmpty();
        }
        try {
            NativeUiStructureProtocol.Report report = new NativeUiStructureProtocol.Report(args[0], start,
                    acquired, finished, reason, cleanup, nodeCount, chars, windowId, displayId,
                    bounds.left, bounds.top, bounds.right, bounds.bottom, -1, -1, search, reading,readyMode?diagnostics.result(idle):null,fieldMode?fieldDiagnostics.result():null,boundMode,regions);
            System.out.println(NativeUiStructureProtocol.encode(report));
            System.out.flush();
        } catch (Throwable ignored) { /* No raw exception or partial report is published. */ }
    }
    static boolean validReadyArguments(String[] args){return args!=null&&(args.length==2||args.length==3&&("READY_V49".equals(args[2])||"FORMAT_V50".equals(args[2])||"DIAGNOSTIC_V51".equals(args[2])||"DIAGNOSTIC_LOCATED_V52".equals(args[2])||"BOUND_POINTS_V53".equals(args[2])||"BOUND_REGIONS".equals(args[2])));}
    private static boolean packageMatches(CharSequence value) {
        return value != null && value.length() == BING.length() && BING.contentEquals(value);
    }
    private static String field(CharSequence value) throws Stop {
        if (value == null) return "";
        if (value.length() > 2048) throw new Stop(NativeUiStructureProtocol.Reason.TREE_OVERFLOW);
        // Copy only the bounded characters, never a node or arbitrary object's toString().
        StringBuilder b = new StringBuilder(value.length());
        for (int i=0;i<value.length();i++) b.append(value.charAt(i));
        return b.toString();
    }
    private static void checkTime(long deadline) throws Stop {
        if (SystemClock.elapsedRealtime() >= deadline) throw new Stop(NativeUiStructureProtocol.Reason.TIMEOUT);
    }
    private static NativeUiStructureProtocol.Task task(NativeUiStructureParser.TaskResult t) {
        return new NativeUiStructureProtocol.Task(NativeUiStructureProtocol.Status.valueOf(t.status().name()),
                t.status() == NativeUiStructureParser.Status.KNOWN ? t.current() : -1,
                t.status() == NativeUiStructureParser.Status.KNOWN ? t.target() : -1,
                t.matchCount(), NativeUiStructureProtocol.Unit.valueOf(t.unit().name()));
    }
    private NativeUiStructureHelper() { }
}
