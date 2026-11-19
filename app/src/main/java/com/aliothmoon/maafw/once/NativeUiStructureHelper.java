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
        boolean articleMode=args.length==3&&NewsTreeWire.ARTICLE_ARGUMENT.equals(args[2]);
        boolean readyMode=args.length==3&&!articleMode;
        boolean newsMode=readyMode&&"READING_NEWS_TREE_V1".equals(args[2]);
        boolean afterMode=readyMode&&"LIVE_REWARDS_AFTER_V1".equals(args[2]);
        boolean liveMode=readyMode&&"LIVE_REWARDS_UI_V1".equals(args[2])||afterMode;
        boolean visitMode=readyMode&&"LIVE_VISIT_V1".equals(args[2]);
        boolean cardsMode=readyMode&&"OVERVIEW_CARDS_V1".equals(args[2])||liveMode||visitMode;
        boolean overviewMode=readyMode&&"OVERVIEW_V5".equals(args[2])||cardsMode;
        boolean regionMode=readyMode&&"BOUND_REGIONS".equals(args[2]);
        boolean boundMode=newsMode||overviewMode||regionMode||readyMode&&"BOUND_POINTS_V53".equals(args[2]);
        boolean formatMode=readyMode&&"FORMAT_V50".equals(args[2]);
        boolean locatedMode=readyMode&&"DIAGNOSTIC_LOCATED_V52".equals(args[2]);
        boolean fieldMode=readyMode&&"DIAGNOSTIC_V51".equals(args[2]);
        if(locatedMode)fieldMode=true;
        try {
            deadline = NativeUiStructureProtocol.number(args[1]);
            if (deadline <= start || deadline - start > 10000) return;
        } catch (RuntimeException ignored) { return; }
        long readEnd = Math.min(deadline, start + (readyMode?8000:5000)), acquired = start;
        boolean liveReceipt=liveMode||visitMode;
        if(afterMode)readEnd=Math.min(readEnd,LiveUiReadStream.workDeadline(deadline));
        NativeUiReadyDiagnostics.Idle idle=NativeUiReadyDiagnostics.Idle.NOT_REQUESTED;
        NativeUiReadyDiagnostics.Accumulator diagnostics=readyMode||articleMode?new NativeUiReadyDiagnostics.Accumulator():null;
        NativeUiFieldDiagnostics.Accumulator fieldDiagnostics=fieldMode?new NativeUiFieldDiagnostics.Accumulator(locatedMode):null;
        NativeUiStructureProtocol.Reason reason = NativeUiStructureProtocol.Reason.TREE_FAILED;
        NativeUiStructureProtocol.Cleanup cleanup = NativeUiStructureProtocol.Cleanup.NOT_STARTED;
        NativeUiStructureProtocol.Task search = NativeUiStructureProtocol.Task.unknown();
        NativeUiStructureProtocol.Task reading = NativeUiStructureProtocol.Task.unknown();
        NativeUiRegions.Pair regions = regionMode ? NativeUiRegions.Pair.empty() : null;
        OverviewUiRegions.Result overview=OverviewUiRegions.Result.rejected(OverviewUiRegions.Status.WRONG_STRUCTURE);
        OverviewUiProtocol.ScrollHint scrollHint=OverviewUiProtocol.ScrollHint.UNKNOWN;
        java.util.List<LiveUiProtocol.Item> liveItems=java.util.List.of();
        LiveVisitGuard.Accumulator liveGuard=new LiveVisitGuard.Accumulator();
        LiveVisitGuard.Hint visitHint=LiveVisitGuard.Hint.UNKNOWN;
        RemainingCardUi.Result cards=cardsMode?RemainingCardUi.Result.rejected(RemainingCardUi.Status.WRONG_STRUCTURE):null;
        HandlerThread thread = null;
        UiAutomation automation = null;
        boolean attemptedConnect = false, clean = true;
        boolean structureRejected=false;
        var treeRelease=LiveUiReadStream.Resource.NOT_OPENED;
        var connectionRelease=LiveUiReadStream.Resource.NOT_OPENED;
        var threadRelease=LiveUiReadStream.Resource.NOT_OPENED;
        ArrayDeque<Pending> pending = new ArrayDeque<>();
        List<NativeUiStructureParser.Node> records = new ArrayList<>();
        List<NativeUiBoundPointsParser.Node> boundRecords = new ArrayList<>();
        List<NativeUiBoundPointsParser.Node> newsRecords=List.of();
        List<OverviewUiParser.ScrollNode> overviewScroll = new ArrayList<>();
        int chars = 0, nodeCount = 0, windowId = -1, displayId = -1;
        Rect bounds = new Rect();
        try {
            if (Process.myUid() != 0) throw new Stop(NativeUiStructureProtocol.Reason.NOT_ROOT);
            if(afterMode && readEnd-SystemClock.elapsedRealtime()<LiveUiReadStream.IDLE_MILLIS)
                throw new Stop(NativeUiStructureProtocol.Reason.TIMEOUT);
            checkTime(readEnd);
            thread = new HandlerThread("NativeUiStructure");
            threadRelease=LiveUiReadStream.Resource.UNKNOWN;
            thread.start();
            Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation = UiAutomation.class.getConstructor(Looper.class, connectionType)
                    .newInstance(thread.getLooper(), connection);
            checkTime(readEnd);
            attemptedConnect = true;
            connectionRelease=LiveUiReadStream.Resource.UNKNOWN;
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
                if(readEnd-SystemClock.elapsedRealtime()<3000){idle=NativeUiReadyDiagnostics.Idle.TIMEOUT;
                    throw new Stop(afterMode?NativeUiStructureProtocol.Reason.TIMEOUT:NativeUiStructureProtocol.Reason.IDLE_TIMEOUT);}
                try{automation.waitForIdle(500,3000);idle=NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED;}
                catch(java.util.concurrent.TimeoutException timedOut){idle=NativeUiReadyDiagnostics.Idle.TIMEOUT;throw new Stop(NativeUiStructureProtocol.Reason.IDLE_TIMEOUT);}
                catch(Throwable ignored){idle=NativeUiReadyDiagnostics.Idle.FAILED;throw new Stop(NativeUiStructureProtocol.Reason.IDLE_FAILED);}
            }
            checkTime(readEnd);
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) throw new Stop(NativeUiStructureProtocol.Reason.NO_ROOT);
            treeRelease=LiveUiReadStream.Resource.UNKNOWN;
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
                    if(articleMode){
                        String text=articleGuardLabel(node.getText()), description=articleGuardLabel(node.getContentDescription());
                        diagnostics.node(node.getClassName(),text,description);
                        liveGuard.node(node.getClassName(),text,description,visible);
                        if(liveGuard.challenges>0 || liveGuard.dialogs>0 || diagnostics.blocked())
                            throw new Stop(NativeUiStructureProtocol.Reason.UI_BLOCKER);
                        nodeCount++;
                    }
                    if (visible) {
                        Rect nodeBounds = new Rect();
                        node.getBoundsInScreen(nodeBounds);
                        if (nodeBounds.isEmpty() || !bounds.contains(nodeBounds))
                            throw new Stop(articleMode ? NativeUiStructureProtocol.Reason.ARTICLE_NODE_BOUNDS
                                    : NativeUiStructureProtocol.Reason.WINDOW_UNAVAILABLE);
                    }
                    if(!articleMode){
                        String text = field(node.getText()), description = field(node.getContentDescription());
                        if (chars + text.length() + description.length() > 32768)
                            throw new Stop(NativeUiStructureProtocol.Reason.TREE_OVERFLOW);
                        chars += text.length() + description.length();
                        if(readyMode)diagnostics.node(node.getClassName(),text,description);
                        if(liveMode||visitMode||newsMode)liveGuard.node(node.getClassName(),text,description,visible);
                        if(fieldMode){Rect originalBounds=new Rect();node.getBoundsInScreen(originalBounds);
                            fieldDiagnostics.node(current.index(),current.parent(),current.depth(),originalBounds.left,originalBounds.top,originalBounds.right,originalBounds.bottom,visible,node.isEnabled(),node.isClickable(),node.getClassName(),node.getViewIdResourceName(),text,description);
                        }
                        if(boundMode){Rect originalBounds=new Rect();node.getBoundsInScreen(originalBounds);
                            var classRole=liveMode||visitMode||newsMode?liveClassRole(node.getClassName()):NativeUiBoundPointsParser.ClassRole.UNKNOWN;
                            boolean clickable=(liveMode||visitMode||newsMode)&&node.isClickable()&&(!newsMode||node.isEnabled());
                            boundRecords.add(new NativeUiBoundPointsParser.Node(current.index(),current.parent(),visible,true,
                                    node.getViewIdResourceName(),text,description,originalBounds.left,originalBounds.top,originalBounds.right,originalBounds.bottom,classRole,clickable));
                        }
                        if(overviewMode&&visible&&node.isScrollable()) {
                            boolean backward=false,forward=false;
                            for(var action:node.getActionList()) {
                                backward|=action.getId()==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId();
                                forward|=action.getId()==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId();
                            }
                            overviewScroll.add(new OverviewUiParser.ScrollNode(current.index(),backward,forward));
                        }
                        records.add(new NativeUiStructureParser.Node(current.index(), current.parent(),
                                visible, true, text, description));
                    }
                    int count = node.getChildCount();
                    if (count < 0 || scheduled + count > 512 || count > 0 && current.depth() >= 32)
                        throw new Stop(NativeUiStructureProtocol.Reason.TREE_OVERFLOW);
                    for (int i = 0; i < count; i++) {
                        checkTime(readEnd);
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child == null) throw new Stop(articleMode ? NativeUiStructureProtocol.Reason.ARTICLE_CHILD_MISSING
                                : NativeUiStructureProtocol.Reason.TREE_FAILED);
                        pending.addLast(new Pending(child, scheduled++, current.index(), current.depth()+1));
                    }
                } finally {
                    try { node.recycle(); } catch (Throwable ignored) { clean = false; }
                }
            }
            checkTime(readEnd);
            if(articleMode){
                if(nodeCount!=scheduled)throw new Stop(NativeUiStructureProtocol.Reason.TREE_FAILED);
            }else if(!fieldMode){if(!readyMode||!diagnostics.blocked()){
                NativeUiStructureParser.Result result;
                if(overviewMode){
                    if(liveMode||visitMode){var fixed=LiveUiParser.labels(boundRecords);boundRecords.clear();boundRecords.addAll(fixed);}
                    overview=OverviewUiParser.parse(boundRecords);
                    if(liveMode)structureRejected=overview.status()!=OverviewUiRegions.Status.OBSERVED;
                    if(cardsMode)cards=RemainingCardUiParser.parse(boundRecords,overview);
                    if(liveMode)structureRejected|=cards.status()!=RemainingCardUi.Status.OBSERVED;
                    if(visitMode)visitHint=LiveVisitGuard.classify(boundRecords,liveGuard,overview.status()==OverviewUiRegions.Status.OBSERVED&&cards.status()==RemainingCardUi.Status.OBSERVED);
                    if(visitMode)structureRejected=visitHint==LiveVisitGuard.Hint.UNKNOWN;
                    if(overview.status()==OverviewUiRegions.Status.OBSERVED)scrollHint=OverviewUiParser.scrollHint(boundRecords,overviewScroll);
                    result=new NativeUiStructureParser.Result(
                        new NativeUiStructureParser.TaskResult(NativeUiStructureParser.Status.UNKNOWN,0,0,0,NativeUiStructureParser.Unit.UNESTABLISHED),
                        new NativeUiStructureParser.TaskResult(NativeUiStructureParser.Status.UNKNOWN,0,0,0,NativeUiStructureParser.Unit.UNESTABLISHED));
                }else if(regionMode){
                    var bound=NativeUiBoundPointsParser.parseRegions(boundRecords);
                    result=bound.counters();regions=bound.regions();
                }else result = boundMode?NativeUiBoundPointsParser.parse(boundRecords):formatMode?NativeUiStructureV50Parser.parse(records):NativeUiStructureParser.parse(records);
                search = task(result.search()); reading = task(result.reading());
            }} // A valid tree with a fixed sign-in/error label retains UNKNOWN tasks; not page-ready proof.
            if((liveMode||newsMode)&&(liveGuard.challenges>0||liveGuard.dialogs>0)||newsMode&&diagnostics.blocked())
                throw new Stop(NativeUiStructureProtocol.Reason.UI_BLOCKER);
            if(newsMode)newsRecords=List.copyOf(boundRecords);
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
            if(liveMode&&reason==NativeUiStructureProtocol.Reason.OK&&overview.status()==OverviewUiRegions.Status.OBSERVED&&cards.status()==RemainingCardUi.Status.OBSERVED){
                try{
                    liveItems=LiveUiParser.identities(boundRecords,overview,cards);
                }catch(RuntimeException invalid){reason=NativeUiStructureProtocol.Reason.TREE_FAILED;liveItems=java.util.List.of();}
            }
            if(!articleMode)nodeCount = records.size();
            records.clear();
            boundRecords.clear();
            overviewScroll.clear();
            if(treeRelease==LiveUiReadStream.Resource.UNKNOWN && clean)treeRelease=LiveUiReadStream.Resource.RELEASED;
            if (attemptedConnect) {
                try { UiAutomation.class.getMethod("disconnect").invoke(automation);connectionRelease=LiveUiReadStream.Resource.RELEASED; }
                catch (Throwable ignored) { clean = false; }
            }
            if (thread != null) {
                try {
                    thread.quitSafely();
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining > 0) thread.join(Math.min(remaining, 250));
                    if (thread.isAlive()) clean = false;
                    else threadRelease=LiveUiReadStream.Resource.RELEASED;
                } catch (Throwable ignored) { clean = false; }
            }
            cleanup = clean ? (attemptedConnect ? NativeUiStructureProtocol.Cleanup.CONFIRMED
                    : NativeUiStructureProtocol.Cleanup.NOT_STARTED) : NativeUiStructureProtocol.Cleanup.UNKNOWN;
        }
        long finished = SystemClock.elapsedRealtime();
        // Cooperative deadline: synchronous Binder can block. Owner must fail closed/quarantine
        // uncertain process cleanup; this check never emits late success.
        if (finished >= deadline || finished - start > 10000) return;
        if(afterMode && (finished>=readEnd||reason==NativeUiStructureProtocol.Reason.TIMEOUT)
                ||liveReceipt&&cleanup==NativeUiStructureProtocol.Cleanup.UNKNOWN){
            var reasonOnly=LiveUiReadStream.cleanupReason(reason,clean,
                    diagnostics.blocked()||liveGuard.challenges>0||liveGuard.dialogs>0||visitHint==LiveVisitGuard.Hint.BLOCKED,
                    structureRejected);
            var mode=afterMode?LiveUiReadStream.Mode.AFTER:visitMode?LiveUiReadStream.Mode.VISIT:LiveUiReadStream.Mode.UI;
            var receipt=new LiveUiReadStream.CleanupReceipt(args[0],mode,deadline,start,finished,
                    reasonOnly,treeRelease,connectionRelease,threadRelease);
            System.out.println(receipt.encode());
            System.out.flush();
            return;
        }
        if (cleanup == NativeUiStructureProtocol.Cleanup.UNKNOWN) reason = NativeUiStructureProtocol.Reason.CLEANUP_UNKNOWN;
        if (reason != NativeUiStructureProtocol.Reason.OK) {
            search = NativeUiStructureProtocol.Task.unknown(); reading = NativeUiStructureProtocol.Task.unknown();
            if(regionMode)regions=NativeUiRegions.Pair.empty();
            overview=OverviewUiRegions.Result.rejected(OverviewUiRegions.Status.WRONG_STRUCTURE);
            if(cardsMode)cards=RemainingCardUi.Result.rejected(RemainingCardUi.Status.WRONG_STRUCTURE);
            scrollHint=OverviewUiProtocol.ScrollHint.UNKNOWN;
            acquired = Math.min(finished, Math.max(start, acquired));
            // Invalid geometry must not prevent a bounded failure receipt.
            windowId = -1; displayId = -1; bounds.setEmpty();
        }
        try {
            NativeUiStructureProtocol.Report report = new NativeUiStructureProtocol.Report(args[0], start,
                    acquired, finished, reason, cleanup, nodeCount, chars, windowId, displayId,
                    bounds.left, bounds.top, bounds.right, bounds.bottom, -1, -1, search, reading,readyMode?diagnostics.result(idle):null,fieldMode?fieldDiagnostics.result():null,boundMode&&!overviewMode,regions);
            if(articleMode)NewsTreeWire.writeArticle(System.out,report);
            else if(newsMode)NewsTreeWire.write(System.out, report, report.qualified()?newsRecords:List.of());
            else if(visitMode)System.out.println(LiveVisitGuard.encode(new LiveVisitGuard.Report(report,visitHint,liveGuard.webViews,liveGuard.challenges,liveGuard.dialogs)));
            else if(liveMode)System.out.println(LiveUiProtocol.encode(new LiveUiProtocol.Report(new OverviewUiProtocol.Report(report,overview,scrollHint,cards),reason==NativeUiStructureProtocol.Reason.OK?liveItems:java.util.List.of())));
            else if(cardsMode)System.out.println(OverviewUiProtocol.encode(new OverviewUiProtocol.Report(report,overview,scrollHint,cards)));
            else if(overviewMode)System.out.println(OverviewUiProtocol.encode(new OverviewUiProtocol.Report(report,overview,scrollHint)));
            else System.out.println(NativeUiStructureProtocol.encode(report));
            System.out.flush();
        } catch (Throwable ignored) { /* No raw exception or partial report is published. */ }
    }
    static boolean validReadyArguments(String[] args){return args!=null&&(args.length==2||args.length==3&&("READY_V49".equals(args[2])||"FORMAT_V50".equals(args[2])||"DIAGNOSTIC_V51".equals(args[2])||"DIAGNOSTIC_LOCATED_V52".equals(args[2])||"BOUND_POINTS_V53".equals(args[2])||"BOUND_REGIONS".equals(args[2])||"OVERVIEW_V5".equals(args[2])||"OVERVIEW_CARDS_V1".equals(args[2])||"LIVE_REWARDS_UI_V1".equals(args[2])||"LIVE_REWARDS_AFTER_V1".equals(args[2])||"LIVE_VISIT_V1".equals(args[2])||"READING_NEWS_TREE_V1".equals(args[2])||NewsTreeWire.ARTICLE_ARGUMENT.equals(args[2])));}
    private static boolean packageMatches(CharSequence value) {
        return value != null && value.length() == BING.length() && BING.contentEquals(value);
    }
    static NativeUiBoundPointsParser.ClassRole liveClassRole(CharSequence value){
        if(value!=null&&value.length()=="android.widget.TextView".length()&&"android.widget.TextView".contentEquals(value))return NativeUiBoundPointsParser.ClassRole.TEXT;
        if(value!=null&&value.length()=="android.widget.Button".length()&&"android.widget.Button".contentEquals(value))return NativeUiBoundPointsParser.ClassRole.BUTTON;
        return NativeUiBoundPointsParser.ClassRole.UNKNOWN;
    }
    /** Only whole, short blocker labels matter in the article viewer. Never copy a paragraph as evidence. */
    static String articleGuardLabel(CharSequence value){
        if(value==null)return "";
        StringBuilder label=new StringBuilder();
        boolean blank=false;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c=='\u200B'||c=='\u200C'||c=='\u200D'||c=='\uFEFF')continue;
            if(Character.isWhitespace(c)||Character.isSpaceChar(c)){blank=label.length()>0;continue;}
            if(blank){label.append(' ');blank=false;}
            if(label.length()>=128)return "";
            label.append(c);
        }
        return label.toString();
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
