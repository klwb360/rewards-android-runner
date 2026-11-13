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
final class AndroidEntryDiagnosticReader {
    private static final String PACKAGE = "com.aliothmoon.maafw.productrewardsworking.capturereview";
    private static final String HELPER = "com.aliothmoon.maafw.once.NativeEntryDiagnosticHelper";
    private static final AtomicBoolean CLAIMED = new AtomicBoolean();
    private static volatile Object quarantine;
    static final long PHASE_MILLIS=15_000;
    record Request(String nonce,int task,int width,int height,int rotation,int bingUid,int systemUiUid,int ownUid,int ownTask) {
        boolean valid(){return nonce!=null&&nonce.matches("[a-f0-9]{32}")&&task>0&&task<=1_000_000
            &&width>0&&width<=8192&&height>0&&height<=8192&&rotation>=0&&rotation<=3
            &&bingUid>=10000&&systemUiUid>=10000&&ownUid>=10000&&ownTask>0&&ownTask<=1_000_000&&ownTask!=task;}
    }
    interface DispatchGate { boolean allowDispatch() throws Exception; }
    enum FixedStatus { AUTHORITY_REJECTED, INVALID_REQUEST, ALREADY_CLAIMED, GATE_REJECTED,
        PROCESS_NULL, STREAM_MISSING, BUFFER_LIMIT, READ_TIMEOUT, CANCELLED, EXIT_NONZERO,
        PROTOCOL_REJECTED, IO_UNKNOWN, READ_COMPLETE, CLEANUP_UNKNOWN }
    record Result(NativeEntryDiagnosticProtocol.Report protocolReport, FixedStatus fixedStatus,
                  boolean invoked, boolean knownClean, int acquiredBytes, boolean processQuiet,
                  boolean descriptorsClosed, boolean exitObserved, int exitCode) { }
    static boolean knownClean() { return quarantine == null; }
    static boolean processKnownClean() { return knownClean(); }

    static Result read(Context context, Request request, long absoluteDeadline,
                       BooleanSupplier current, DispatchGate finalGate) {
        byte[] bytes = new byte[NativeEntryDiagnosticProtocol.MAX_LINE];
        IRemoteProcess process = null;
        InputStream input = null;
        ParcelFileDescriptor stdout = null, stdin = null, stderr = null;
        boolean invoked = false, quiet = false, exitObserved = false, descriptorsClosed = true;
        int count = 0, exit = -1;
        NativeEntryDiagnosticProtocol.Report report = null;
        FixedStatus status = FixedStatus.AUTHORITY_REJECTED;
        long started = SystemClock.elapsedRealtime();
        long deadline = Math.min(absoluteDeadline, started + PHASE_MILLIS);
        try {
            if (context == null || current == null || finalGate == null
                    || request == null || !request.valid() || deadline <= started)
                throw new Stop(FixedStatus.INVALID_REQUEST);
            if (!authority(context) || !live(current, deadline)) throw new Stop(status);
            String apk = verifiedApk(context);
            if (apk == null) throw new Stop(status);
            var binder = Shizuku.getBinder();
            if (binder == null || !binder.isBinderAlive()) throw new Stop(status);
            var service = IShizukuService.Stub.asInterface(binder);
            if (service == null) throw new Stop(status);
            String[] command = command(request,apk,deadline);
            if (!authority(context) || !live(current, deadline)) throw new Stop(status);
            if (!CLAIMED.compareAndSet(false, true)) throw new Stop(FixedStatus.ALREADY_CLAIMED);
            status = FixedStatus.GATE_REJECTED;
            // The parent gate persists the diagnostic slot and checks fresh exact Main ownership.
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
            if (!validFrame(bytes, count)) throw new Stop(FixedStatus.PROTOCOL_REJECTED);
            try {
                report = NativeEntryDiagnosticProtocol.parse(new String(bytes, 0, count-1,
                        StandardCharsets.US_ASCII), request.nonce(), request.task(), dispatched, deadline, SystemClock.elapsedRealtime());
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
                        || report.cleanup() == NativeEntryDiagnosticProtocol.Cleanup.UNKNOWN)
                    quarantine = new Object[]{process, input, stdout, stdin, stderr};
            }
        }
        boolean clean = knownClean() && (!invoked || quiet && descriptorsClosed);
        if (!clean) { status = FixedStatus.CLEANUP_UNKNOWN; report = null; }
        return new Result(report, status, invoked, clean, count, quiet || !invoked, descriptorsClosed, exitObserved, exit);
    }
    /** Exact argv only; the APK value is supplied solely by verifiedApk above. */
    static String[] command(Request r,String apk,long deadline){
        return new String[]{"/system/bin/app_process","-cp",apk,"/system/bin",HELPER,r.nonce(),Long.toString(deadline),
            Integer.toString(r.task()),Integer.toString(r.width()),Integer.toString(r.height()),Integer.toString(r.rotation()),
            Integer.toString(r.bingUid()),Integer.toString(r.systemUiUid()),Integer.toString(r.ownUid()),Integer.toString(r.ownTask())};
    }
    /** Pure post-gate decision, evaluated after the gate and any local epoch predicate. */
    static boolean dispatchAllowed(long now, long deadline, boolean current, boolean interrupted,
                                   boolean binderMatches, boolean binderAlive) {
        return now >= 0 && now < deadline && current && !interrupted && binderMatches && binderAlive;
    }
    static boolean validFrame(byte[] bytes, int count) {
        if (bytes == null || count < 2 || count > bytes.length || count > NativeEntryDiagnosticProtocol.MAX_LINE
                || bytes[count-1] != '\n') return false;
        for (int i=0;i<count-1;i++) {
            int b=bytes[i]&255;
            if (!(b>='A'&&b<='Z'||b>='0'&&b<='9'||b>='a'&&b<='f'||b=='_'||b=='|'||b=='-'||b==',')) return false;
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
    private AndroidEntryDiagnosticReader() { }
}
