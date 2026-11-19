package com.aliothmoon.maafw.once;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IRemoteProcess;
import rikka.shizuku.Shizuku;

/** Native Bing search chrome only. No screenshot, URL, article text or search results are used. */
final class AlphabetSearchDevice implements AlphabetSearchCycle.Ports {
    static final String BING = "com.microsoft.bing";
    static final String EDITOR = BING + "/com.microsoft.sapphire.feature.zeroquery.impl.ui.ZeroQueryActivity";
    static final String RESULTS = BING + "/com.microsoft.sapphire.app.browser.SearchBrowserActivity";
    private static final String IDS = BING + ":id/";
    private static final String ADDRESS = IDS + "sa_template_header_address_bar_container";
    private static final String ADDRESS_TEXT = IDS + "sa_template_header_address_bar_text_view";
    private static final int DOC_LIMIT = 262144;
    private static volatile boolean quarantined;

    private final Context context;
    private final LiveRunAuthority authority;
    private final LiveRewardsNavigation navigation;
    private final BingNavigationSnapshot.Ready initial;
    private final BingNavigationSnapshot.Shape shape;
    private final BooleanSupplier hidden;
    private final String apk;
    private final String imePackage;
    private final int imeUid;
    private BingForegroundSnapshot.Snapshot origin, editor;
    private boolean reusableEditor, uncertain;
    private char prepared, submitted;
    private long successAt;
    private int opens, openInvoked, opened, backs, backInvoked;

    AlphabetSearchDevice(Context context, LiveRunAuthority authority, LiveRewardsNavigation navigation,
                         BingNavigationSnapshot.Ready initial, BingNavigationSnapshot.Shape shape,
                         BooleanSupplier hidden) throws Exception {
        this.context = context.getApplicationContext();
        this.authority = authority;
        this.navigation = navigation;
        this.initial = initial;
        this.shape = shape;
        this.hidden = hidden;
        need(Build.VERSION.SDK_INT >= 34 && initial != null && shape != null && initial.profile().shape().equals(shape)
                && (initial.kind() == BingNavigationSnapshot.Kind.MAIN || initial.kind() == BingNavigationSnapshot.Kind.TEMPLATE)
                && current() && AndroidLiveUiReader.authority(this.context), "SEARCH_AUTHORITY");
        apk = AndroidLiveUiReader.verifiedApk(this.context);
        need(apk != null, "SEARCH_APK");
        // Android 14 filters third-party IME enumeration for ordinary apps. Resolve only
        // the active user-0 setting and its exact package through the existing local bridge.
        String configured = execute(new String[]{"/system/bin/settings","--user","0","get","secure",
                Settings.Secure.DEFAULT_INPUT_METHOD}, 512, null).trim();
        ComponentName ime = ComponentName.unflattenFromString(configured);
        need(ime != null && ime.getPackageName().matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"),
                "SEARCH_IME_SETTING");
        imePackage = ime.getPackageName();
        String deviceOwner = execute(new String[]{"/system/bin/cmd","package","list","packages","--user","0",
                "-U",imePackage}, 8192, null);
        imeUid = exactPackageUid(deviceOwner,imePackage);
    }

    static int exactPackageUid(String listing, String pkg) {
        var pattern = Pattern.compile("package:" + Pattern.quote(pkg) + " uid:([1-9][0-9]{0,7})");
        int id = -1;
        for (String row : listing.split("\\r?\\n")) {
            var match = pattern.matcher(row);
            if (match.matches()) {
                need(id == -1, "SEARCH_IME_OWNER_AMBIGUOUS");
                id = Integer.parseInt(match.group(1));
            }
        }
        need(id >= 0, "SEARCH_IME_OWNER_UNKNOWN");
        return id;
    }

    static boolean knownClean() { return !quarantined; }
    boolean inputOutcomeUnknown() { return uncertain; }
    int backCalls() { return backs; }
    int backDispatches() { return backInvoked; }
    int openCalls() { return opens; }
    int openDispatches() { return openInvoked; }
    int openSuccesses() { return opened; }
    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public boolean current() {
        return !Thread.currentThread().isInterrupted() && knownClean() && !uncertain && authority != null
                && authority.live() && authority.taskInputAllowed() && authority.current() && hidden.getAsBoolean()
                && navigation.current() && AndroidLiveUiReader.knownClean() && ReviewShizukuBackActor.newsClean()
                && ReviewShizukuTapActor.newsClean();
    }
    @Override public void pause(long millis) throws InterruptedException { Thread.sleep(millis); }

    static boolean allowedLetter(char letter) { return letter >= 'a' && letter <= 't'; }
    private static void need(boolean value, String reason) {
        if (!value) throw new SecurityException(reason);
    }
    private static boolean landing(String component) {
        return BingNavigationSnapshot.MAIN.equals(component) || BingNavigationSnapshot.TEMPLATE.equals(component);
    }

    static boolean exactStart(BingNavigationSnapshot.Ready initial, BingForegroundSnapshot.Snapshot active) {
        return initial != null && active != null && landing(active.component())
                && initial.diagnosticActivityMatches(active.activity(), active.component(), active.task())
                && initial.diagnosticWindowMatches(active.window());
    }

    @Override public void requireOrigin() throws Exception {
        need(submitted == 0, "SEARCH_RETURN_PENDING");
        long end = now() + 5000;
        while (true) {
            var active = origin == null ? source() : settledSource();
            if (origin == null) {
                need(exactStart(initial, active), "SEARCH_START_INSTANCE");
                probe(active, "BAR", 'a');
                origin = active;
                return;
            }
            if (origin.equals(active) || reusableEditor && editor != null && editor.equals(active)) return;
            if (now() >= end || !RESULTS.equals(active.component()) && !BingNavigationSnapshot.BROWSER.equals(active.component()))
                throw new SecurityException("SEARCH_RETURN_ORIGIN");
            pause(180);
        }
    }

    @Override public void prepare(char letter) throws Exception {
        need(allowedLetter(letter) && submitted == 0 && prepared == 0 && origin != null, "SEARCH_PREPARE");
        var active = source();
        if (origin.equals(active)) {
            opens++;
            var bar = probe(origin, "BAR", letter);
            action(Input.OPEN, origin, letter, bar.x, bar.y);
            opened++;
            long end = now() + 5000;
            pause(450);
            do {
                pause(150);
                active = settledSource();
                if (EDITOR.equals(active.component())) break;
                need(origin.equals(active) && now() < end, "SEARCH_EDITOR_NOT_OPEN");
            } while (true);
            editor = active;
        } else {
            need(reusableEditor && editor != null && editor.equals(active), "SEARCH_EDITOR_OWNER");
        }
        reusableEditor = false;
        var field = probe(editor, "EDITOR", letter);
        if (!"MATCH".equals(field.state)) {
            if ("OTHER".equals(field.state)) {
                action(Input.SELECT_ALL, editor, letter, 0, 0);
                need("SELECTED".equals(probe(editor, "SELECTION", letter).state), "SEARCH_SELECTION");
            }
            action(Input.TYPE, editor, letter, 0, 0);
        }
        // This is checked again immediately before the submit command.
        need("MATCH".equals(probe(editor, "EDITOR", letter).state), "SEARCH_TEXT_MISMATCH");
        prepared = letter;
    }

    @Override public void submit(char letter) throws Exception {
        need(allowedLetter(letter) && prepared == letter && submitted == 0, "SEARCH_SUBMIT_OWNER");
        var field = probe(editor, "EDITOR", letter);
        need("MATCH".equals(field.state), "SEARCH_TEXT_MISMATCH");
        prepared = 0;
        action(Input.SUBMIT, editor, letter, 0, 0);
        successAt = now();
        submitted = letter;
    }

    @Override public void back() throws Exception {
        backs++;
        char letter = submitted;
        need(allowedLetter(letter) && now() - successAt >= 5000, "SEARCH_BACK_EARLY");
        var result = source();
        need(!result.equals(origin) && !result.equals(editor), "SEARCH_RESULT_UNBOUND");
        if (BingNavigationSnapshot.BROWSER.equals(result.component())) {
            need("MATCH".equals(probe(result, "QUERY", letter).state), "SEARCH_RESULT_QUERY");
        } else {
            need(RESULTS.equals(result.component()), "SEARCH_RESULT_COMPONENT");
        }
        submitted = 0;
        action(Input.BACK, result, letter, 0, 0);
        reusableEditor = true;
        pause(500);
    }

    private enum Input { OPEN, SELECT_ALL, TYPE, SUBMIT, BACK }

    private void action(Input input, BingForegroundSnapshot.Snapshot owner, char letter, int x, int y) throws Exception {
        need(allowedLetter(letter) && owner.equals(source()), "SEARCH_DISPATCH_SOURCE");
        String[] command = switch (input) {
            case OPEN -> {
                need(x > 0 && y > 0 && x < shape.width() && y < shape.height()/2, "SEARCH_BAR_BOUNDS");
                yield new String[]{"/system/bin/input","-d","0","tap",String.valueOf(x),String.valueOf(y)};
            }
            case SELECT_ALL -> new String[]{"/system/bin/input","-d","0","keycombination","113","29"};
            case TYPE -> new String[]{"/system/bin/input","-d","0","text",String.valueOf(letter)};
            case SUBMIT -> new String[]{"/system/bin/input","-d","0","keyevent","66"};
            case BACK -> new String[]{"/system/bin/input","-d","0","keyevent","4"};
        };
        execute(command, 256, input);
    }

    private record Projection(int x, int y, String state) {}

    /** Process exit alone cannot witness disconnection of its accessibility client. */
    static final class ProbeAttempt {
        boolean invoked;
        private boolean acknowledged;
        boolean clean() { return !invoked || acknowledged; }

        Projection accept(String line, String nonce, String operation, int width, int height) {
            need(invoked && !acknowledged && NativeUiStructureProtocol.validNonce(nonce)
                    && width > 0 && width <= 8192 && height > 0 && height <= 8192
                    && ("BAR".equals(operation) || "QUERY".equals(operation)
                    || "EDITOR".equals(operation) || "SELECTION".equals(operation))
                    && line != null && line.length() <= 300 && line.endsWith("\n"), "SEARCH_PROBE_PROTOCOL");
            String[] f = line.substring(0,line.length()-1).split("\\|",-1);
            need(f.length == 6 && f[0].equals("S1") && f[1].equals(nonce), "SEARCH_PROBE_PROTOCOL");
            Projection projection = null;
            if ("MISS".equals(f[2])) {
                need(f[3].equals("0") && f[4].equals("0") && f[5].equals("NONE"), "SEARCH_PROBE_PROTOCOL");
            } else {
                need(f[2].equals("OK") && f[3].matches("[1-9][0-9]{0,3}")
                        && f[4].matches("[1-9][0-9]{0,3}"), "SEARCH_PROBE_PROTOCOL");
                int x = Integer.parseInt(f[3]), y = Integer.parseInt(f[4]);
                boolean valid = switch (operation) {
                    case "BAR" -> f[5].equals("BAR");
                    case "QUERY" -> f[5].equals("MATCH");
                    case "EDITOR", "SELECTION" -> f[5].equals("EMPTY") || f[5].equals("OTHER") || f[5].equals("MATCH")
                            || operation.equals("SELECTION") && f[5].equals("SELECTED");
                    default -> false;
                };
                need(valid && x < width && y < height/2, "SEARCH_PROBE_PROTOCOL");
                projection = new Projection(x,y,f[5]);
            }
            acknowledged = true;
            return projection;
        }
    }

    private Projection probe(BingForegroundSnapshot.Snapshot owner, String operation, char letter) throws Exception {
        need(owner != null && owner.equals(source()), "SEARCH_PROBE_OWNER");
        String nonce = UUID.randomUUID().toString().replace("-", "");
        long end = Math.min(now()+5000, authority.deadline());
        String[] command = {"/system/bin/app_process","-cp",apk,"/system/bin",Probe.class.getName(),nonce,
                Long.toString(end),operation,owner.component(),String.valueOf(letter),String.valueOf(shape.width()),
                String.valueOf(shape.height())};
        ProbeAttempt attempt = new ProbeAttempt();
        try {
            String result = execute(command, 300, null, attempt);
            Projection point = attempt.accept(result, nonce, operation, shape.width(), shape.height());
            need(point != null, "SEARCH_PROBE_MISS");
            need(now() < end && owner.equals(source()), "SEARCH_PROBE_STALE");
            return point;
        } finally {
            if (!attempt.clean()) quarantined = true;
        }
    }

    // A transition sometimes displays two Bing windows together. Neither authorizes input.
    private BingForegroundSnapshot.Snapshot settledSource() throws Exception {
        long end = Math.min(authority.deadline(), now()+2000);
        while (true) {
            try {
                return source();
            } catch (SecurityException covered) {
                if (!"SEARCH_BING_WINDOW_OVERLAP".equals(covered.getMessage()) || now() >= end) throw covered;
                pause(150);
            }
        }
    }

    private BingForegroundSnapshot.Snapshot source() throws Exception {
        var profile = initial.profile();
        long start = now();
        String windows = execute(new String[]{"/system/bin/dumpsys","window","-a"}, DOC_LIMIT, null);
        String activities = execute(new String[]{"/system/bin/dumpsys","activity","activities"}, DOC_LIMIT, null);
        var focus = parseSource(windows, activities, profile, initial.task(), imePackage, imeUid);
        if (origin != null && !origin.equals(focus)) {
            need(retainsOrigin(activities, origin), "SEARCH_ORIGINAL_TASK");
        }
        need(now()-start < 5000 && current(), "SEARCH_SOURCE_STALE");
        return focus;
    }

    private static final Pattern WINDOW_HEADER = Pattern.compile("Window #\\d+ Window\\{([a-fA-F0-9]+) u0 ([^}\\r\\n]+)\\}:");
    private static final String TOP = "(?m)^\\s*topResumedActivity=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^\\s}]+) t(\\d+)(?: [^}\\r\\n]*)?\\}\\s*$";

    static BingForegroundSnapshot.Snapshot parseSource(String w, String a, BingNavigationSnapshot.Profile p,
                                                        String task, String imePkg, int keyboardUid) {
        var f = BingForegroundSnapshot.parse(w);
        need(f != null && f.task().equals(task) && (landing(f.component()) || EDITOR.equals(f.component())
                || RESULTS.equals(f.component()) || BingNavigationSnapshot.BROWSER.equals(f.component())), "SEARCH_FOCUS");
        need(a != null && a.length() <= DOC_LIMIT && a.indexOf('\0') < 0
                && a.indexOf("topResumedActivity=") == a.lastIndexOf("topResumedActivity="), "SEARCH_RESUMED");
        String[] top = BingNavigationSnapshot.one(a, TOP, 3);
        need(top != null && f.activity().equals(top[0]) && f.component().equals(top[1]) && task.equals(top[2]),
                "SEARCH_ACTIVITY");
        var history = BingNavigationSnapshot.sections(a,"\\s*\\* Hist +#\\d+: ActivityRecord\\{.*\\}");
        var item = BingNavigationSnapshot.uniqueSection(history,"\\* Hist +#\\d+: ActivityRecord\\{"
                +Pattern.quote(f.activity()+" u0 "+f.component()+" t"+task)+"(?: [^}]*)?\\}");
        need(item != null && Pattern.compile("\\bstate=RESUMED\\b").matcher(item.body()).find()
                && item.body().contains("finishing=false") && item.body().contains("inHistory=true"), "SEARCH_ACTIVITY_STATE");
        var windows = BingNavigationSnapshot.sections(w,"\\s*Window #\\d+ .*");
        int focused = 0;
        for (var row : windows) {
            var label = WINDOW_HEADER.matcher(row.header());
            need(label.matches(), "SEARCH_WINDOW_HEADER");
            String token = label.group(1), name = label.group(2), body = row.body();
            if (!f.window().equals(token) && BingNavigationSnapshot.safelyInvisible(
                    new BingNavigationSnapshot.Block(token,name,null,body))) continue;
            String[] owner = BingNavigationSnapshot.one(body,"^\\s*mOwnerUid=(\\d+)\\b[^\\r\\n]*\\bpackage=(\\S+)\\s+appop=(\\S+)[^\\r\\n]*$",3);
            String[] show = BingNavigationSnapshot.one(body,"^\\s*mDisplayId=(\\d+)\\s+rootTaskId=(-?\\d+)\\b.*$",2);
            need(owner != null && show != null && "0".equals(show[0]), "SEARCH_VISIBLE_WINDOW");
            boolean isApp = f.window().equals(token);
            if (isApp) {
                String[] dim = BingNavigationSnapshot.one(body,"^\\s*Requested w=(\\d+) h=(\\d+)(?:\\s.*)?$",2);
                String[] link = BingNavigationSnapshot.one(body, "^\\s*mActivityRecord=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^\\s}]+) t(\\d+)(?: [^}]*)?\\}\\s*$",3);
                need(name.equals(f.component()) && "NONE".equals(owner[2]) && BING.equals(owner[1])
                        && String.valueOf(p.bingUid()).equals(owner[0]) && task.equals(show[1])
                        && dim != null && String.valueOf(p.shape().width()).equals(dim[0])
                        && String.valueOf(p.shape().height()).equals(dim[1]) && type(body,"BASE_APPLICATION")
                        && body.contains("mHasSurface=true") && body.contains("isVisible=true")
                        && body.contains("isOnScreen=true") && body.contains("isReadyForDisplay()=true")
                        && link != null && f.activity().equals(link[0]) && name.equals(link[1]) && task.equals(link[2]),
                        "SEARCH_FOCUS_OWNER");
                focused++;
            } else {
                boolean bar = ("StatusBar".equals(name) && type(body,"STATUS_BAR")
                        || ("NavigationBar0".equals(name) || "NavigationBar".equals(name)) && type(body,"NAVIGATION_BAR"))
                        && "com.android.systemui".equals(owner[1]) && String.valueOf(p.systemUiUid()).equals(owner[0]);
                boolean ime = EDITOR.equals(f.component()) && keyboardUid >= 0 && imePkg.equals(owner[1])
                        && String.valueOf(keyboardUid).equals(owner[0]) && "InputMethod".equals(name) && type(body,"INPUT_METHOD");
                if (!bar && !ime) {
                    if ("InputMethod".equals(name)) throw new SecurityException("SEARCH_IME_WINDOW_UNBOUND");
                    boolean bingPage = (landing(name) || EDITOR.equals(name) || RESULTS.equals(name)
                            || BingNavigationSnapshot.BROWSER.equals(name)) && BING.equals(owner[1])
                            && String.valueOf(p.bingUid()).equals(owner[0]) && type(body,"BASE_APPLICATION");
                    throw new SecurityException(bingPage ? "SEARCH_BING_WINDOW_OVERLAP" : "SEARCH_COVERED");
                }
            }
        }
        need(focused == 1, "SEARCH_FOCUS_COUNT");
        return f;
    }

    static boolean retainsOrigin(String activities, BingForegroundSnapshot.Snapshot origin) {
        var history = BingNavigationSnapshot.sections(activities,"\\s*\\* Hist +#\\d+: ActivityRecord\\{.*\\}");
        var entry = BingNavigationSnapshot.uniqueSection(history,"\\* Hist +#\\d+: ActivityRecord\\{"
                +Pattern.quote(origin.activity()+" u0 "+origin.component()+" t"+origin.task())+"(?: [^}]*)?\\}");
        return entry != null && entry.body().contains("inHistory=true") && entry.body().contains("finishing=false");
    }

    private static boolean type(String body,String name) {
        return Pattern.compile("(?m)^\\s*mAttrs=\\{[^\\r\\n]*\\bty="+name+"\\b").matcher(body).find();
    }

    /** A fixed command with a byte limit, true pipe EOF, exit check and cleanup of its own process. */
    private String execute(String[] command, int limit, Input input) throws Exception {
        return execute(command, limit, input, null);
    }

    private String execute(String[] command, int limit, Input input, ProbeAttempt probe) throws Exception {
        need(current() && AndroidLiveUiReader.authority(context), "SEARCH_AUTHORITY");
        var binder = Shizuku.getBinder();
        need(binder != null && binder.isBinderAlive(), "SEARCH_BINDER");
        var service = IShizukuService.Stub.asInterface(binder);
        need(service != null && binder == Shizuku.getBinder() && current(), "SEARCH_DISPATCH_STALE");
        IRemoteProcess child = null;
        ParcelFileDescriptor in = null, out = null, err = null;
        boolean quiet = false, issued = false;
        byte[] bytes = new byte[limit];
        try {
            if (input == Input.OPEN) openInvoked++;
            if (input == Input.BACK) backInvoked++;
            issued = true;
            if (probe != null) probe.invoked = true;
            child = service.newProcess(command,null,null);
            need(child != null, "SEARCH_PROCESS");
            in = child.getInputStream(); out = child.getOutputStream(); err = child.getErrorStream();
            need(in != null && out != null && err != null, "SEARCH_STREAMS");
            out.close();
            out = null;
            var pipe = in;
            var process = child;
            var reader = new MetadataPipeReader();
            long deadline = Math.min(authority.deadline(), now()+6500);
            var status = reader.drain(bytes,(b,o,n,wait)->AndroidCardRegionCapture.readPipe(pipe,b,o,n),
                    wait->process.waitForTimeout(wait,"MILLISECONDS"),this::now,
                    ()->authority.current() && hidden.getAsBoolean(),deadline);
            quiet = reader.exited();
            need(status == ReviewBingNavigation.FixedStatus.READ_COMPLETE && reader.eof() && quiet
                    && child.exitValue() == 0, "SEARCH_COMMAND_FAILED");
            return new String(bytes,0,reader.count(),StandardCharsets.UTF_8);
        } catch (Exception failure) {
            if (input != null && issued) uncertain = true;
            throw failure;
        } finally {
            Arrays.fill(bytes,(byte)0);
            boolean closed = close(in) & close(out) & close(err);
            if (child != null && !quiet) {
                try { child.destroy(); quiet = child.waitForTimeout(500,"MILLISECONDS"); }
                catch (Exception ignored) { quiet = false; }
            }
            if (issued && (!closed || !quiet)) quarantined = true;
            need(!quarantined,"SEARCH_CLEANUP_UNKNOWN");
        }
    }

    private static boolean close(ParcelFileDescriptor fd) {
        if (fd == null) return true;
        try { fd.close(); return true; } catch (IOException e) { return false; }
    }

    /** Signed, read-only helper. Only fixed native control geometry and single-letter equality leave this process. */
    public static final class Probe {
        private static final class Nodes implements AutoCloseable {
            final java.util.ArrayList<AccessibilityNodeInfo> held = new java.util.ArrayList<>();
            boolean clean = true;
            AccessibilityNodeInfo own(AccessibilityNodeInfo n) {
                if (n != null) held.add(n);
                return n;
            }
            @SuppressWarnings("deprecation")
            public void close() {
                for (var n : held) {
                    try { n.recycle(); } catch (RuntimeException failure) { clean = false; }
                }
            }
        }

        @SuppressWarnings("deprecation")
        public static void main(String[] args) {
            if (Build.VERSION.SDK_INT < 34 || android.os.Process.myUid() != 0 || args == null || args.length != 7
                    || !NativeUiStructureProtocol.validNonce(args[0]) || args[4].length()!=1 || !allowedLetter(args[4].charAt(0))) return;
            String status = "MISS";
            Projection point = new Projection(0,0,"NONE");
            HandlerThread worker = null;
            UiAutomation ui = null;
            boolean connectAttempt = false;
            Nodes nodes = new Nodes();
            try {
                long end = Long.parseLong(args[1]);
                int width = Integer.parseInt(args[5]), height = Integer.parseInt(args[6]);
                need(end > SystemClock.elapsedRealtime() && end-SystemClock.elapsedRealtime()<=5000,"DEADLINE");
                need(width>0 && width<=8192 && height>0 && height<=8192,"SIZE");
                worker = new HandlerThread("BingSearchChrome");
                worker.start();
                var type = Class.forName("android.app.IUiAutomationConnection");
                var conn = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
                ui = UiAutomation.class.getConstructor(Looper.class,type).newInstance(worker.getLooper(),conn);
                connectAttempt = true;
                UiAutomation.class.getMethod("connect",int.class).invoke(ui,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
                var config = ui.getServiceInfo();
                need(config!=null,"CONFIG");
                config.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                ui.setServiceInfo(config);
                need(ui.getServiceInfo()!=null && ui.getServiceInfo().flags==config.flags,"CONFIG");
                var root = nodes.own(ui.getRootInActiveWindow());
                need(valid(root) && root.isVisibleToUser(),"ROOT");
                var win = root.getWindow();
                try {
                    need(win!=null && win.getId()==root.getWindowId() && win.isActive() && win.isFocused()
                            && win.getDisplayId()==0 && win.getType()==AccessibilityWindowInfo.TYPE_APPLICATION,"WINDOW");
                    point = select(ui,root,nodes,args[2],args[3],args[4].charAt(0),width,height,end);
                    need(SystemClock.elapsedRealtime()<end,"DEADLINE");
                    status = "OK";
                } finally {
                    if (win != null) try { win.recycle(); } catch (RuntimeException failure) { nodes.clean = false; }
                }
            } catch (Exception miss) { status = "MISS"; }
            finally {
                nodes.close();
                if (!nodes.clean) status="UNCLEAN";
                if (connectAttempt) try { UiAutomation.class.getMethod("disconnect").invoke(ui); }
                catch (Exception ignored) { status="UNCLEAN"; }
                if (worker!=null) {
                    if (!worker.quitSafely()) status="UNCLEAN";
                    try { worker.join(500); if (worker.isAlive()) status="UNCLEAN"; }
                    catch (InterruptedException interrupted) { status="UNCLEAN"; Thread.currentThread().interrupt(); }
                }
            }
            if (!"OK".equals(status)) point = new Projection(0,0,"NONE");
            System.out.printf(Locale.ROOT,"S1|%s|%s|%d|%d|%s%n",args[0],status,point.x,point.y,point.state);
        }

        private static boolean valid(AccessibilityNodeInfo n) {
            return n!=null && BING.contentEquals(n.getPackageName()==null?"":n.getPackageName());
        }

        private static Projection select(UiAutomation ui, AccessibilityNodeInfo root, Nodes nodes, String operation,
                                         String component, char letter, int width, int height, long end) {
            if ("EDITOR".equals(operation) || "SELECTION".equals(operation)) {
                need(EDITOR.equals(component),"EDITOR_ACTIVITY");
                var field=nodes.own(ui.findFocus(AccessibilityNodeInfo.FOCUS_INPUT));
                need(valid(field) && field.getWindowId()==root.getWindowId() && field.isVisibleToUser()
                        && field.isEnabled() && field.isFocused() && field.isEditable() && !field.isPassword()
                        && !field.isMultiLine() && "android.widget.EditText".contentEquals(field.getClassName()),"EDITOR");
                var rect=chromeBounds(field,width,height);
                CharSequence value=field.getText();
                String state=field.isShowingHintText() || value==null || value.length()==0 ? "EMPTY"
                        : value.length()==1 && value.charAt(0)==letter ? "MATCH" : "OTHER";
                if ("SELECTION".equals(operation) && value!=null && value.length()>0
                        && Math.min(field.getTextSelectionStart(),field.getTextSelectionEnd())==0
                        && Math.max(field.getTextSelectionStart(),field.getTextSelectionEnd())==value.length()) state="SELECTED";
                return new Projection(rect.centerX(),rect.centerY(),state);
            }
            boolean bar = "BAR".equals(operation) && landing(component);
            boolean query = "QUERY".equals(operation) && BingNavigationSnapshot.BROWSER.equals(component);
            need(bar || query,"MODE");
            var visit = new ArrayDeque<AccessibilityNodeInfo>();
            visit.add(root);
            AccessibilityNodeInfo match=null;
            boolean homeAnchor=false, title=false;
            while (!visit.isEmpty()) {
                need(nodes.held.size()<=512 && SystemClock.elapsedRealtime()<end,"TREE_LIMIT");
                var n=visit.removeFirst();
                need(valid(n) && n.getWindowId()==root.getWindowId(),"FOREIGN_NODE");
                String id=n.getViewIdResourceName();
                if(n.isVisibleToUser()) {
                    if("compose_home_root".equals(id))homeAnchor=true;
                    if(ADDRESS_TEXT.equals(id)){
                        if(!query || n.getText()!=null && n.getText().length()==1 && n.getText().charAt(0)==letter)title=true;
                    }
                    boolean candidate = query ? ADDRESS_TEXT.equals(id) :
                            BingNavigationSnapshot.MAIN.equals(component) ? "search_bar".equals(id) : ADDRESS.equals(id);
                    if(candidate) {
                        need(match==null && n.isEnabled() && (query || n.isClickable()),"DUPLICATE_OR_DISABLED");
                        match=n;
                    }
                }
                // All selectors are native chrome. Do not traverse any article, advertisement or search-result WebView.
                if("android.webkit.WebView".contentEquals(n.getClassName()))continue;
                int count=n.getChildCount();
                need(count>=0 && count<=512-nodes.held.size(),"TREE_LIMIT");
                for(int i=0;i<count;i++){
                    var c=nodes.own(n.getChild(i));
                    need(c!=null,"CHILD");
                    visit.addLast(c);
                }
            }
            need(match!=null && (BingNavigationSnapshot.MAIN.equals(component) ? homeAnchor : title),"CHROME");
            var rect=chromeBounds(match,width,height);
            return new Projection(rect.centerX(),rect.centerY(),query?"MATCH":"BAR");
        }

        private static Rect chromeBounds(AccessibilityNodeInfo n,int width,int height) {
            Rect r=new Rect();
            n.getBoundsInScreen(r);
            need(!r.isEmpty() && r.left>=0 && r.top>=0 && r.right<=width && r.bottom<=height
                    && r.centerY()<height/2,"CHROME_BOUNDS");
            return r;
        }
    }
}

