package com.aliothmoon.maafw.once;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** Independent per-frame overview helper; original reader and its spent marker remain unchanged. */
final class AndroidLiveUiReader {
    private static final String PACKAGE = "com.aliothmoon.maafw.productrewardsworking.capturereview";
    private static final String HELPER = "com.aliothmoon.maafw.once.NativeUiStructureHelper";
    private static volatile Object quarantine;
    interface DispatchGate { boolean allowDispatch() throws Exception; }
    enum FixedStatus { AUTHORITY_REJECTED, INVALID_REQUEST, ALREADY_CLAIMED, GATE_REJECTED,
        PROCESS_NULL, STREAM_MISSING, BUFFER_LIMIT, READ_TIMEOUT, CANCELLED, EXIT_NONZERO,
        PROTOCOL_REJECTED, IO_UNKNOWN, READ_COMPLETE, CLEANUP_UNKNOWN, INSUFFICIENT_BUDGET, BUDGET_RECEIPT, HELPER_REJECTED }
    record Result(LiveUiProtocol.Report protocolReport,LiveVisitGuard.Report visitReport, FixedStatus fixedStatus,
                  boolean invoked, boolean knownClean, int acquiredBytes, boolean processQuiet,
                  boolean exitObserved, int exitCode,LiveReadPreparation.Snapshot preparation,String diagnostic) {
        Result(LiveUiProtocol.Report protocolReport,LiveVisitGuard.Report visitReport,FixedStatus fixedStatus,
                boolean invoked,boolean knownClean,int acquiredBytes,boolean processQuiet,boolean exitObserved,int exitCode){
            this(protocolReport,visitReport,fixedStatus,invoked,knownClean,acquiredBytes,processQuiet,exitObserved,exitCode,null,"NONE");
        }
        Result(LiveUiProtocol.Report protocolReport,LiveVisitGuard.Report visitReport,FixedStatus fixedStatus,
                boolean invoked,boolean knownClean,int acquiredBytes,boolean processQuiet,boolean exitObserved,int exitCode,
                LiveReadPreparation.Snapshot preparation){
            this(protocolReport,visitReport,fixedStatus,invoked,knownClean,acquiredBytes,processQuiet,exitObserved,exitCode,preparation,"NONE");
        }
    }
    static boolean knownClean() { return quarantine == null; }
    static boolean processKnownClean() { return knownClean(); }

    static Result read(Context context,String nonce,long absoluteDeadline,BooleanSupplier current,
            DispatchGate finalGate,LiveRunAuthority.UiPermit permit){return readBound(context,nonce,absoluteDeadline,current,finalGate,permit,false,null);}
    static Result read(Context context,String nonce,long absoluteDeadline,BooleanSupplier current,
            DispatchGate finalGate,LiveRunAuthority.UiPermit permit,LiveReadPreparation preparation){
        return readBound(context,nonce,absoluteDeadline,current,finalGate,permit,false,preparation);
    }
    static Result readVisit(Context context,String nonce,long absoluteDeadline,BooleanSupplier current,
            DispatchGate finalGate,LiveRunAuthority.UiPermit permit){return readBound(context,nonce,absoluteDeadline,current,finalGate,permit,true,null);}
    static Result readVisit(Context context,String nonce,long absoluteDeadline,BooleanSupplier current,
            DispatchGate finalGate,LiveRunAuthority.UiPermit permit,LiveReadPreparation preparation){
        return readBound(context,nonce,absoluteDeadline,current,finalGate,permit,true,preparation);
    }
    private static BooleanSupplier bounded(BooleanSupplier current,LiveRunAuthority.UiPermit permit){
        return current==null||permit==null?null:()->permit.current(SystemClock.elapsedRealtime())&&current.getAsBoolean();
    }
    private static Result readBound(Context context,String nonce,long absoluteDeadline,BooleanSupplier ownerCurrent,
            DispatchGate finalGate,LiveRunAuthority.UiPermit permit,boolean visitMode,LiveReadPreparation preparation){
        BooleanSupplier current=bounded(ownerCurrent,permit);
        // One sentinel byte permits a final EOF read at the largest valid frame.
        byte[] bytes = new byte[OverviewUiProtocol.MAX_LINE + 2];
        IRemoteProcess process = null;
        ParcelFileDescriptor stdout = null, stdin = null, stderr = null;
        boolean invoked = false, descriptorsClosed = true;
        boolean outAdopted=false, inAdopted=false, errAdopted=false;
        boolean outClosed=true, inClosed=true, errClosed=true;
        var stream = new LiveUiReadStream();
        LiveUiProtocol.Report report = null;LiveVisitGuard.Report visitReport=null;
        FixedStatus status = FixedStatus.AUTHORITY_REJECTED;
        long started = SystemClock.elapsedRealtime();
        long deadline = Math.min(absoluteDeadline, started + 9000);
        if(permit!=null)deadline=Math.min(deadline,permit.deadline());
        boolean afterMode=!visitMode&&permit!=null&&permit.scope().afterDeadline()>=0;
        var mode=afterMode?LiveUiReadStream.Mode.AFTER:visitMode?LiveUiReadStream.Mode.VISIT:LiveUiReadStream.Mode.UI;
        var helperMode=afterMode?LiveReadPreparation.Mode.LIVE_REWARDS_AFTER_V1:
                visitMode?LiveReadPreparation.Mode.LIVE_VISIT_V1:LiveReadPreparation.Mode.LIVE_REWARDS_UI_V1;
        LiveReadPreparation trace=preparation;
        try{
            if(trace==null)trace=new LiveReadPreparation();
            trace.begin(started,deadline,helperMode,SystemClock::elapsedRealtime);
        }catch(Throwable diagnosticUnavailable){trace=null;}
        try {
            if (context == null || current == null || finalGate == null
                    || !NativeUiStructureProtocol.validNonce(nonce) || deadline <= started || permit == null || !permit.live())
                throw new Stop(FixedStatus.INVALID_REQUEST);
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.AUTHORITY_INITIAL);
            if (!authority(context) || !live(current, deadline)) throw new Stop(status);
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.APK_VERIFY);
            String apk = verifiedApk(context);
            if (apk == null) throw new Stop(status);
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.BINDER);
            var binder = Shizuku.getBinder();
            if (binder == null || !binder.isBinderAlive()) throw new Stop(status);
            var service = IShizukuService.Stub.asInterface(binder);
            if (service == null) throw new Stop(status);
            String[] command = {"/system/bin/app_process", "-cp", apk, "/system/bin", HELPER, nonce, Long.toString(deadline), helperMode.name()};
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.AUTHORITY_RECHECK);
            if (!authority(context) || !live(current, deadline)) throw new Stop(status);
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.CLAIM_READER);
            if (!permit.claimReader(SystemClock.elapsedRealtime())) throw new Stop(FixedStatus.ALREADY_CLAIMED);
            status = FixedStatus.GATE_REJECTED;
            // The root gate atomically persists the durable UI_READ burn and rechecks its epoch.
            // Only local freshness and cached binder checks follow this gate; no new authority read.
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.SOURCE_GATE);
            if (!finalGate.allowDispatch()) throw new Stop(status);
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.POST_GATE);
            boolean sameBinder = binder == Shizuku.getBinder();
            boolean binderAlive = binder.isBinderAlive();
            boolean ownerStillCurrent = ownerCurrent.getAsBoolean();
            long dispatched = SystemClock.elapsedRealtime();
            // Verify source, ownership and revocation first, including the durable read burn.
            // A short post-return lease cannot run the helper's existing three-second idle check.
            var stop=postGate(dispatched,deadline,ownerStillCurrent,permit.dispatchValid(dispatched),
                    Thread.currentThread().isInterrupted(),sameBinder,binderAlive,afterMode);
            if(stop!=null)throw new Stop(stop);
            stream.stage=LiveUiReadStream.Stage.NEW_PROCESS;
            invoked = true;
            status = FixedStatus.PROCESS_NULL;
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.NEW_PROCESS,dispatched);
            process = service.newProcess(command, null, null);
            if (process == null) throw new Stop(status);
            stream.stage=LiveUiReadStream.Stage.OPEN_STREAMS;
            descriptorsClosed = false;
            status = FixedStatus.STREAM_MISSING;
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.STREAMS);
            stdout = process.getInputStream(); outAdopted=stdout!=null;
            stdin = process.getOutputStream(); inAdopted=stdin!=null;
            stderr = process.getErrorStream(); errAdopted=stderr!=null;
            if (!outAdopted || !inAdopted || !errAdopted) throw new Stop(status);
            stdin.close(); stdin=null;
            stderr.close(); stderr=null; // Never inspect stderr.
            if(android.os.Build.VERSION.SDK_INT>=30){
                int flags=Os.fcntlInt(stdout.getFileDescriptor(),OsConstants.F_GETFL,0);
                Os.fcntlInt(stdout.getFileDescriptor(),OsConstants.F_SETFL,flags|OsConstants.O_NONBLOCK);
            }
            status = FixedStatus.IO_UNKNOWN;
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.READ);
            ParcelFileDescriptor pipe=stdout;
            IRemoteProcess child=process;
            var drainStatus=stream.drain(bytes,deadline,SystemClock::elapsedRealtime,current,
                    (target,offset,length)->AndroidCardRegionCapture.readPipe(pipe,target,offset,length),
                    new LiveCropStream.ProcessExit(){
                        public boolean quiet()throws Exception{return child.waitForTimeout(0,"MILLISECONDS");}
                        public int exitValue()throws Exception{return child.exitValue();}
                    },Thread::sleep);
            if(drainStatus!=null)throw new Stop(drainStatus);
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.EXIT);
            // Require one complete ASCII frame and reject diagnostics, trailing bytes and extra lines.
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.PROTOCOL);
            if (!validFrame(bytes, stream.count)) throw new Stop(FixedStatus.PROTOCOL_REJECTED);
            try {
                String frame=new String(bytes,0,stream.count-1,StandardCharsets.US_ASCII);
                long received=SystemClock.elapsedRealtime();
                if(frame.startsWith("LUC1|")){
                    stream.cleanup=LiveUiReadStream.CleanupReceipt.parse(frame,nonce,mode,dispatched,deadline,received);
                    stream.helperReason=stream.cleanup.reason();
                }else{
                    if(visitMode)visitReport=LiveVisitGuard.parse(frame,nonce,dispatched,deadline,received);
                    else report=LiveUiProtocol.parse(frame,nonce,dispatched,deadline,received);
                    var source=visitMode?visitReport.source():report.base().source();
                    stream.frameCleanup=source.cleanup();stream.helperReason=source.reason();stream.completeReceipt=true;
                }
            } catch (RuntimeException invalid) { if(trace!=null)trace.exception(invalid);throw new Stop(FixedStatus.PROTOCOL_REJECTED); }
            if(trace!=null)trace.enter(LiveReadPreparation.Stage.AUTHORITY_FINAL);
            if (!authority(context)) throw new Stop(FixedStatus.AUTHORITY_REJECTED);
            if (stream.currentLost || !live(current,deadline)) throw new Stop(FixedStatus.CANCELLED);
            if(stream.cleanup!=null)throw new Stop(afterMode&&stream.helperReason==NativeUiStructureProtocol.Reason.TIMEOUT
                    ?FixedStatus.BUDGET_RECEIPT:FixedStatus.HELPER_REJECTED);
            var timeStatus=receivedStatus(mode,SystemClock.elapsedRealtime(),deadline,stream.helperReason,
                    visitMode?visitReport.qualified():report.qualified());
            if(timeStatus!=FixedStatus.READ_COMPLETE)throw new Stop(timeStatus);
            stream.stage=LiveUiReadStream.Stage.DONE;
            status = FixedStatus.READ_COMPLETE;
        } catch (Stop stopped) {
            if(trace!=null)trace.controlledStop();status = stopped.status;
        } catch (Exception failure) {
            if(trace!=null){
                if(failure instanceof PackageManager.NameNotFoundException)trace.exception(LiveReadPreparation.ExceptionKind.PACKAGE_NOT_FOUND);else trace.exception(failure);
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            status = failure instanceof SecurityException
                    ? (status==FixedStatus.GATE_REJECTED ? FixedStatus.GATE_REJECTED : FixedStatus.AUTHORITY_REJECTED)
                    : FixedStatus.IO_UNKNOWN;
        } finally {
            if(trace!=null)trace.beginCleanup();
            Arrays.fill(bytes, (byte) 0);
            // Each close is independent: one failure must not skip another descriptor.
            try { if (stdout != null) stdout.close(); } catch (Exception ignored) { if(trace!=null)trace.cleanupException(ignored);outClosed=false; }
            try { if (stdin != null) stdin.close(); } catch (Exception ignored) { if(trace!=null)trace.cleanupException(ignored);inClosed=false; }
            try { if (stderr != null) stderr.close(); } catch (Exception ignored) { if(trace!=null)trace.cleanupException(ignored);errClosed=false; }
            if (invoked) {
                try {
                    if (process != null && !stream.quiet) {
                        process.destroy();
                        stream.quiet = process.waitForTimeout(500, "MILLISECONDS");
                    }
                } catch (Exception ignored) { if(trace!=null)trace.cleanupException(ignored);stream.quiet=false; }
                descriptorsClosed = outAdopted && inAdopted && errAdopted && outClosed && inClosed && errClosed;
                if (!stream.released(descriptorsClosed)) quarantine = new Object[]{process,stdout,stdin,stderr};
            }
        }
        boolean clean = knownClean() && (!invoked || stream.released(descriptorsClosed));
        String diagnostic=stream.diagnostic(status,descriptor(outAdopted,outClosed),descriptor(inAdopted,inClosed),
                descriptor(errAdopted,errClosed));
        if (!clean) status=FixedStatus.CLEANUP_UNKNOWN;
        if (status!=FixedStatus.READ_COMPLETE) {report=null;visitReport=null;}
        var completed=trace==null?null:trace.finish(status.name(),invoked,clean,stream.count,stream.quiet||!invoked,stream.exitObserved,stream.exitCode);
        return new Result(report,visitReport,status,invoked,clean,stream.count,stream.quiet||!invoked,
                stream.exitObserved,stream.exitCode,completed,diagnostic);
    }
    private static String descriptor(boolean adopted,boolean closed){return !adopted?"NO_HANDLE":closed?"CLOSED":"UNCONFIRMED";}
    static FixedStatus receivedStatus(LiveUiReadStream.Mode mode,long received,long deadline,
            NativeUiStructureProtocol.Reason reason,boolean qualified){
        if(received>=deadline)return FixedStatus.CANCELLED;
        if(mode!=LiveUiReadStream.Mode.AFTER||received<LiveUiReadStream.workDeadline(deadline))
            return FixedStatus.READ_COMPLETE;
        return qualified||reason==NativeUiStructureProtocol.Reason.TIMEOUT?FixedStatus.BUDGET_RECEIPT:FixedStatus.HELPER_REJECTED;
    }
    static FixedStatus postGate(long now,long deadline,boolean owned,boolean permitValid,boolean interrupted,
            boolean sameBinder,boolean binderAlive,boolean after){
        if(!sameBinder||!binderAlive)return FixedStatus.AUTHORITY_REJECTED;
        if(!owned||interrupted)return FixedStatus.CANCELLED;
        if(!dispatchAllowed(now,deadline,permitValid,false,sameBinder,binderAlive))
            return now>=deadline?FixedStatus.READ_TIMEOUT:FixedStatus.CANCELLED;
        return after&&!LiveUiReadStream.enoughTime(now,deadline)?FixedStatus.INSUFFICIENT_BUDGET:null;
    }
    /** Pure post-gate decision, evaluated after the gate and any local epoch predicate. */
    static boolean dispatchAllowed(long now, long deadline, boolean current, boolean interrupted,
                                   boolean binderMatches, boolean binderAlive) {
        return now >= 0 && now < deadline && current && !interrupted && binderMatches && binderAlive;
    }
    static boolean validFrame(byte[] bytes, int count) {
        if(bytes==null||count<2||count>bytes.length||count>OverviewUiProtocol.MAX_LINE+1||bytes[count-1]!='\n')return false;
        for(int i=0;i<count-1;i++){int v=bytes[i]&255;if(!(v>='A'&&v<='Z'||v>='0'&&v<='9'||v>='a'&&v<='f'||v=='_'||v=='|'||v=='-'))return false;}
        return true;
    }
    private static boolean live(BooleanSupplier current, long deadline) {
        return !Thread.currentThread().isInterrupted() && SystemClock.elapsedRealtime() < deadline
                && current.getAsBoolean();
    }
    private static boolean authority(Context context) {
        return knownClean() && AndroidUiStructureReader.processKnownClean() && Looper.myLooper()!=Looper.getMainLooper() && android.os.Process.myUid()>=10000
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
    private AndroidLiveUiReader() { }
}
