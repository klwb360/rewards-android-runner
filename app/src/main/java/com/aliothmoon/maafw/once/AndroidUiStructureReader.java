package com.aliothmoon.maafw.once;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** One fixed, memory-only root helper invocation. No caller command, APK path, or output text escapes. */
final class AndroidUiStructureReader {
    private static final String PACKAGE = "com.aliothmoon.maafw.productrewardsworking.capturereview";
    private static final String HELPER = "com.aliothmoon.maafw.once.NativeUiStructureHelper";
    private static final AtomicBoolean CLAIMED = new AtomicBoolean();
    private static volatile Object quarantine;
    interface DispatchGate { boolean allowDispatch() throws Exception; }
    enum FixedStatus { AUTHORITY_REJECTED, INVALID_REQUEST, ALREADY_CLAIMED, GATE_REJECTED,
        PROCESS_NULL, STREAM_MISSING, BUFFER_LIMIT, READ_TIMEOUT, CANCELLED, EXIT_NONZERO,
        PROTOCOL_REJECTED, IO_UNKNOWN, READ_COMPLETE, CLEANUP_UNKNOWN }
    record Result(NativeUiStructureProtocol.Report protocolReport, FixedStatus fixedStatus,
                  boolean invoked, boolean knownClean, int acquiredBytes, boolean processQuiet,
                  boolean exitObserved, int exitCode) { }
    static boolean knownClean() { return quarantine == null; }
    static boolean processKnownClean() { return knownClean(); }

    static Result read(Context context, String nonce, long absoluteDeadline,
                       BooleanSupplier current, DispatchGate finalGate) {
        return readInternal(context,nonce,absoluteDeadline,current,finalGate,false,false);
    }
    /** Same bounded tree read and admission gates, with typed read-only card geometry. */
    static Result readRegions(Context context, String nonce, long absoluteDeadline,
                              BooleanSupplier current, DispatchGate finalGate) {
        return readInternal(context,nonce,absoluteDeadline,current,finalGate,true,false);
    }
    /** Independent alignment entry: fixed region protocol, with all existing transport gates. */
    static Result readAlignmentRegions(Context context,String nonce,long absoluteDeadline,
                                       BooleanSupplier current,DispatchGate finalGate){
        return readInternal(context,nonce,absoluteDeadline,current,finalGate,true,true);
    }
    private static Result readInternal(Context context, String nonce, long absoluteDeadline,
                                      BooleanSupplier current, DispatchGate finalGate, boolean regionMode,boolean alignmentMode) {
        boolean boundMode = alignmentMode || ReadOnlyCheckpoint.CURRENT == ReadOnlyCheckpoint.UI_BOUND_POINTS_V53;
        boolean locatedMode = !alignmentMode && ReadOnlyCheckpoint.CURRENT == ReadOnlyCheckpoint.UI_LOCATE_V52;
        boolean fieldMode = !alignmentMode && (ReadOnlyCheckpoint.CURRENT == ReadOnlyCheckpoint.UI_FIELD_V51 || locatedMode);
        byte[] bytes = new byte[fieldMode ? NativeUiStructureProtocol.MAX_LINE_FIELD : NativeUiStructureProtocol.MAX_LINE];
        IRemoteProcess process = null;
        InputStream input = null;
        ParcelFileDescriptor stdout = null, stdin = null, stderr = null;
        boolean invoked = false, quiet = false, exitObserved = false, descriptorsClosed = true;
        int count = 0, exit = -1;
        NativeUiStructureProtocol.Report report = null;
        FixedStatus status = FixedStatus.AUTHORITY_REJECTED;
        long started = SystemClock.elapsedRealtime();
        long deadline = Math.min(absoluteDeadline, started + 9000);
        try {
            if (context == null || current == null || finalGate == null
                    || !NativeUiStructureProtocol.validNonce(nonce) || deadline <= started || regionMode && !boundMode)
                throw new Stop(FixedStatus.INVALID_REQUEST);
            if (!authority(context) || !live(current, deadline)) throw new Stop(status);
            String apk = verifiedApk(context);
            if (apk == null) throw new Stop(status);
            var binder = Shizuku.getBinder();
            if (binder == null || !binder.isBinderAlive()) throw new Stop(status);
            var service = IShizukuService.Stub.asInterface(binder);
            if (service == null) throw new Stop(status);
            boolean readyMode = !alignmentMode && ReadOnlyCheckpoint.CURRENT == ReadOnlyCheckpoint.UI_READY_V49;
            boolean formatMode = !alignmentMode && ReadOnlyCheckpoint.CURRENT == ReadOnlyCheckpoint.UI_FORMAT_V50;
            String[] command = {"/system/bin/app_process", "-cp", apk, "/system/bin", HELPER, nonce, Long.toString(deadline)};
            if (readyMode) {
                command = Arrays.copyOf(command, command.length + 1);
                command[command.length - 1] = "READY_V49";
            }
            if (formatMode) {
                command = Arrays.copyOf(command, command.length + 1);
                command[command.length - 1] = "FORMAT_V50";
            }
            if (fieldMode) {
                command = Arrays.copyOf(command, command.length + 1);
                command[command.length - 1] = locatedMode ? "DIAGNOSTIC_LOCATED_V52" : "DIAGNOSTIC_V51";
            }
            if (boundMode) {
                command = Arrays.copyOf(command, command.length + 1);
                command[command.length - 1] = regionMode ? "BOUND_REGIONS" : "BOUND_POINTS_V53";
            }
            if (!authority(context) || !live(current, deadline)) throw new Stop(status);
            if (!CLAIMED.compareAndSet(false, true)) throw new Stop(FixedStatus.ALREADY_CLAIMED);
            status = FixedStatus.GATE_REJECTED;
            // The root gate atomically persists the durable UI_READ burn and rechecks its epoch.
            // Only local freshness and cached binder checks follow this gate; no new authority read.
            if (!finalGate.allowDispatch()) throw new Stop(status);
            boolean sameBinder = binder == Shizuku.getBinder();
            boolean binderAlive = binder.isBinderAlive();
            boolean stillCurrent = current.getAsBoolean();
            long dispatched = SystemClock.elapsedRealtime();
            if (!dispatchAllowed(dispatched, deadline, stillCurrent, Thread.currentThread().isInterrupted(),
                    sameBinder, binderAlive)) throw new Stop(dispatched >= deadline
                    ? FixedStatus.READ_TIMEOUT : FixedStatus.CANCELLED);
            invoked = true;
            status = FixedStatus.PROCESS_NULL;
            process = service.newProcess(command, null, null);
            if (process == null) throw new Stop(status);
            descriptorsClosed = false;
            status = FixedStatus.STREAM_MISSING;
            stdout = process.getInputStream();
            stdin = process.getOutputStream();
            stderr = process.getErrorStream();
            if (stdin != null) { stdin.close(); stdin = null; }
            if (stderr != null) { stderr.close(); stderr = null; } // Never inspect stderr.
            if (stdout == null) throw new Stop(status);
            input = new ParcelFileDescriptor.AutoCloseInputStream(stdout); stdout = null;
            status = FixedStatus.IO_UNKNOWN;
            for (;;) {
                if (!live(current, deadline)) throw new Stop(SystemClock.elapsedRealtime() >= deadline
                        ? FixedStatus.READ_TIMEOUT : FixedStatus.CANCELLED);
                int available = input.available();
                if (available > 0) {
                    if (count == bytes.length) throw new Stop(FixedStatus.BUFFER_LIMIT);
                    int n = input.read(bytes, count, Math.min(available, bytes.length-count));
                    if (n <= 0) throw new Stop(FixedStatus.IO_UNKNOWN);
                    count += n;
                }
                quiet = process.waitForTimeout(0, "MILLISECONDS");
                if (quiet && input.available() == 0) break;
                if (available == 0) Thread.sleep(5);
            }
            exit = process.exitValue(); exitObserved = true;
            if (exit != 0) throw new Stop(FixedStatus.EXIT_NONZERO);
            if (!live(current, deadline) || !authority(context)) throw new Stop(FixedStatus.CANCELLED);
            // Require one complete ASCII frame and reject diagnostics, trailing bytes and extra lines.
            if (!validFrame(bytes, count, fieldMode)) throw new Stop(FixedStatus.PROTOCOL_REJECTED);
            try {
                report = regionMode ? NativeUiStructureProtocol.parse(new String(bytes,0,count-1,
                        StandardCharsets.US_ASCII),nonce,dispatched,deadline,SystemClock.elapsedRealtime(),true,false,true,true)
                        : NativeUiStructureProtocol.parse(new String(bytes, 0, count-1,
                        StandardCharsets.US_ASCII), nonce, dispatched, deadline, SystemClock.elapsedRealtime(), readyMode || formatMode || fieldMode || boundMode, fieldMode, boundMode);
            } catch (RuntimeException invalid) { throw new Stop(FixedStatus.PROTOCOL_REJECTED); }
            status = FixedStatus.READ_COMPLETE;
        } catch (Stop stopped) {
            status = stopped.status;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            status = FixedStatus.IO_UNKNOWN;
        } finally {
            Arrays.fill(bytes, (byte) 0);
            boolean closed = true;
            // Each close is independent: one failure must not skip another descriptor.
            try { if (input != null) input.close(); } catch (Exception ignored) { closed = false; }
            try { if (stdout != null) stdout.close(); } catch (Exception ignored) { closed = false; }
            try { if (stdin != null) stdin.close(); } catch (Exception ignored) { closed = false; }
            try { if (stderr != null) stderr.close(); } catch (Exception ignored) { closed = false; }
            if (invoked) {
                try {
                    if (process != null && !quiet) {
                        process.destroy();
                        quiet = process.waitForTimeout(500, "MILLISECONDS");
                    }
                } catch (Exception ignored) { quiet = false; }
                descriptorsClosed = closed && process != null;
                // Process exit alone cannot attest UiAutomation disconnection. Missing/unknown
                // cleanup is permanently quarantined even when destroy reports a quiet process.
                if (!quiet || !descriptorsClosed || report == null
                        || report.cleanup() == NativeUiStructureProtocol.Cleanup.UNKNOWN)
                    quarantine = new Object[]{process, input, stdout, stdin, stderr};
            }
        }
        boolean clean = knownClean() && (!invoked || quiet && descriptorsClosed);
        if (!clean) { status = FixedStatus.CLEANUP_UNKNOWN; report = null; }
        return new Result(report, status, invoked, clean, count, quiet || !invoked, exitObserved, exit);
    }
    /** Pure post-gate decision, evaluated after the gate and any local epoch predicate. */
    static boolean dispatchAllowed(long now, long deadline, boolean current, boolean interrupted,
                                   boolean binderMatches, boolean binderAlive) {
        return now >= 0 && now < deadline && current && !interrupted && binderMatches && binderAlive;
    }
    static boolean validFrame(byte[] bytes, int count) {
        return validFrame(bytes,count,false);
    }
    static boolean validFrame(byte[] bytes,int count,boolean fieldMode) {
        if (bytes == null || count < 2 || count > bytes.length || count > (fieldMode ? NativeUiStructureProtocol.MAX_LINE_FIELD : NativeUiStructureProtocol.MAX_LINE)
                || bytes[count-1] != '\n') return false;
        for (int i=0;i<count-1;i++) {
            int b=bytes[i]&255;
            if (!(b>='A'&&b<='Z'||b>='0'&&b<='9'||b>='a'&&b<='f'||b=='_'||b=='|'||b=='-'||fieldMode&&(b==','||b==';'))) return false;
        }
        return true;
    }
    private static boolean live(BooleanSupplier current, long deadline) {
        return !Thread.currentThread().isInterrupted() && SystemClock.elapsedRealtime() < deadline
                && current.getAsBoolean();
    }
    private static boolean authority(Context context) {
        return knownClean() && Looper.myLooper()!=Looper.getMainLooper() && android.os.Process.myUid()>=10000
                && PACKAGE.equals(context.getPackageName()) && (PACKAGE+":capture_once").equals(Application.getProcessName())
                && Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.getUid()==0
                && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;
    }
    @SuppressWarnings("deprecation")
    private static String verifiedApk(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo own = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES);
        if (own == null || !PACKAGE.equals(own.packageName) || own.applicationInfo == null
                || own.applicationInfo.uid != android.os.Process.myUid() || context.getApplicationInfo() == null
                || !own.applicationInfo.sourceDir.equals(context.getApplicationInfo().sourceDir)) return null;
        File file = new File(own.applicationInfo.sourceDir);
        String canonical = file.getCanonicalPath();
        if (!file.isFile() || !file.canRead() || !file.isAbsolute() || !canonical.equals(file.getAbsolutePath())
                || !canonical.startsWith("/data/app/") || !canonical.endsWith("/base.apk")
                || canonical.indexOf('\n')>=0 || canonical.indexOf('\r')>=0 || canonical.indexOf(':')>=0) return null;
        PackageInfo archive = pm.getPackageArchiveInfo(canonical, PackageManager.GET_SIGNATURES);
        if (archive == null || !PACKAGE.equals(archive.packageName) || own.getLongVersionCode()!=archive.getLongVersionCode()
                || !sameSignatures(own.signatures, archive.signatures)) return null;
        return canonical;
    }
    static boolean sameSignatures(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return false;
        boolean[] matched = new boolean[b.length];
        for (Signature signature : a) {
            if (signature == null) return false;
            boolean found=false;
            for (int i=0;i<b.length;i++) if (!matched[i] && signature.equals(b[i])) { matched[i]=true; found=true; break; }
            if (!found) return false;
        }
        return true;
    }
    private static final class Stop extends Exception {
        private static final long serialVersionUID = 1L;
        final FixedStatus status;
        Stop(FixedStatus status) { this.status=status; }
    }
    private AndroidUiStructureReader() { }
}
