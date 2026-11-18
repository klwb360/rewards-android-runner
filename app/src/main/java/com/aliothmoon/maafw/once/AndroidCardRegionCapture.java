package com.aliothmoon.maafw.once;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** One permit-bound, direct display-root crop. No shell, whole-display acquisition or retry. */
final class AndroidCardRegionCapture {
    private static final String PACKAGE="com.aliothmoon.maafw.productrewardsworking.capturereview";
    private static final String HELPER="com.aliothmoon.maafw.once.NativeCardRegionCaptureHelper";
    private static volatile Object quarantine;
    interface Gate {
        boolean consume() throws Exception;
        default boolean consumeWithin(BooleanSupplier mayDispatch) throws Exception {
            if(!mayDispatch.getAsBoolean())throw new InsufficientBudget();
            return consume();
        }
        void requireCurrent() throws Exception;
    }
    static final class InsufficientBudget extends RuntimeException {
        private static final long serialVersionUID=1L;
    }
    enum FixedStatus { CAPTURED, AUTHORITY_REJECTED, INVALID_REQUEST, ALREADY_CLAIMED,
        GATE_REJECTED, PROCESS_NULL, STREAM_MISSING, BUFFER_LIMIT, READ_TIMEOUT, CANCELLED,
        EXIT_NONZERO, PROTOCOL_REJECTED, PERMISSION_DENIED, API_UNAVAILABLE,
        CAPTURE_REJECTED, IO_UNKNOWN, CLEANUP_UNKNOWN, INSUFFICIENT_BUDGET }
    record Result(FixedStatus status,boolean knownClean,byte[] png,boolean invoked,
                  long helperStartedMs,long helperFinishedMs,CardRegionCaptureProtocol.Status helperStatus,
                  int pngWidth,int pngHeight,String diagnostic) {
        Result(FixedStatus status,boolean knownClean,byte[] png,boolean invoked,long helperStartedMs,long helperFinishedMs,
               CardRegionCaptureProtocol.Status helperStatus,int pngWidth,int pngHeight) {
            this(status,knownClean,png,invoked,helperStartedMs,helperFinishedMs,helperStatus,pngWidth,pngHeight,"LEGACY");
        }
    }
    static boolean knownClean(){return quarantine==null;}

    static Result capture(Context context,String nonce,long absoluteDeadline,Box roi,
            int fullWidth,int fullHeight,int rotation,CardRevealProbeIntent.UiPermit permit,
            BooleanSupplier current,Gate gate){
        byte[] bytes=null;IRemoteProcess process=null;
        ParcelFileDescriptor stdout=null,stdin=null,stderr=null;
        boolean invoked=false,quiet=false,exitObserved=false,allDescriptorsAdopted=false,descriptorsClosed=true;
        int count=0;CardRegionCaptureProtocol.Report report=null;
        FixedStatus status=FixedStatus.AUTHORITY_REJECTED;
        long started=SystemClock.elapsedRealtime();
        long deadline=Math.min(absoluteDeadline,started+CardRegionCaptureProtocol.MAX_HELPER_MS);
        if(permit!=null)deadline=Math.min(deadline,permit.deadline);
        try{
            if(context==null||current==null||gate==null||permit==null||!permit.intent.live()
                    ||!permit.current(started)||permit.intent.cropSpent()
                    ||!NativeUiStructureProtocol.validNonce(nonce)||deadline<=started
                    ||!CardRegionCaptureProtocol.validCrop(roi,fullWidth,fullHeight,rotation))
                throw new Stop(FixedStatus.INVALID_REQUEST);
            if(android.os.Build.VERSION.SDK_INT<34)throw new Stop(FixedStatus.API_UNAVAILABLE);
            var request=new CardRegionCaptureProtocol.Request(nonce,deadline,roi,fullWidth,fullHeight,rotation);
            if(!authority(context)||!live(permit,current,deadline))throw new Stop(status);
            requireGate(gate);
            String apk=verifiedApk(context);if(apk==null)throw new Stop(status);
            var binder=Shizuku.getBinder();if(binder==null||!binder.isBinderAlive())throw new Stop(status);
            var service=IShizukuService.Stub.asInterface(binder);if(service==null)throw new Stop(status);
            String[] args=request.arguments();String[] command=new String[5+args.length];
            command[0]="/system/bin/app_process";command[1]="-cp";command[2]=apk;
            command[3]="/system/bin";command[4]=HELPER;System.arraycopy(args,0,command,5,args.length);
            bytes=new byte[CardRegionCaptureProtocol.MAX_WIRE+1];
            if(!authority(context)||!live(permit,current,deadline))throw new Stop(status);
            status=FixedStatus.GATE_REJECTED;
            // The root consumes the exact observation and durably burns the separate crop record.
            try{if(!gate.consume())throw new Stop(status);}
            catch(Stop stopped){throw stopped;}
            catch(Exception denied){if(denied instanceof InterruptedException)Thread.currentThread().interrupt();throw new Stop(FixedStatus.GATE_REJECTED);}
            if(!permit.intent.claimCropDispatch(permit,SystemClock.elapsedRealtime()))
                throw new Stop(FixedStatus.ALREADY_CLAIMED);
            requireGate(gate);
            // No further authority IPC follows the final source gate: only local epoch/binder checks.
            boolean sameBinder=binder==Shizuku.getBinder(),binderAlive=binder.isBinderAlive();
            boolean stillCurrent=permit.current(SystemClock.elapsedRealtime())&&current.getAsBoolean();
            long dispatched=SystemClock.elapsedRealtime();
            if(!AndroidOverviewUiReader.dispatchAllowed(dispatched,deadline,stillCurrent,
                    Thread.currentThread().isInterrupted(),sameBinder,binderAlive))
                throw new Stop(dispatched>=deadline?FixedStatus.READ_TIMEOUT:FixedStatus.CANCELLED);
            invoked=true;status=FixedStatus.PROCESS_NULL;process=service.newProcess(command,null,null);
            if(process==null)throw new Stop(status);
            descriptorsClosed=false;status=FixedStatus.STREAM_MISSING;
            stdout=process.getInputStream();stdin=process.getOutputStream();stderr=process.getErrorStream();
            if(stdout==null||stdin==null||stderr==null)throw new Stop(status);
            allDescriptorsAdopted=true;
            stdin.close();stdin=null;stderr.close();stderr=null; // No stderr text is inspected.
            int flags=Os.fcntlInt(stdout.getFileDescriptor(),OsConstants.F_GETFL,0);
            Os.fcntlInt(stdout.getFileDescriptor(),OsConstants.F_SETFL,flags|OsConstants.O_NONBLOCK);
            status=FixedStatus.IO_UNKNOWN;
            boolean eof=false;
            while(!eof||!quiet){
                if(!live(permit,current,deadline))throw new Stop(SystemClock.elapsedRealtime()>=deadline
                        ?FixedStatus.READ_TIMEOUT:FixedStatus.CANCELLED);
                if(!eof){
                    if(count==bytes.length)throw new Stop(FixedStatus.BUFFER_LIMIT);
                    int n=readPipe(stdout,bytes,count,Math.min(65536,bytes.length-count));
                    if(n<0)eof=true;else count+=n;
                }
                quiet=process.waitForTimeout(0,"MILLISECONDS");
                if(eof&&!quiet)Thread.sleep(5);
            }
            int exit=process.exitValue();exitObserved=true;
            if(exit!=0)throw new Stop(FixedStatus.EXIT_NONZERO);
            // A receipt is evidence about this crop, never a substitute for runtime source-after checks.
            try{report=CardRegionCaptureProtocol.parse(bytes,count,request,dispatched,SystemClock.elapsedRealtime());}
            catch(RuntimeException invalid){throw new Stop(FixedStatus.PROTOCOL_REJECTED);}
            status=switch(report.status()){
                case CAPTURED -> FixedStatus.CAPTURED;
                case PERMISSION_DENIED -> FixedStatus.PERMISSION_DENIED;
                case API_UNAVAILABLE -> FixedStatus.API_UNAVAILABLE;
                default -> FixedStatus.CAPTURE_REJECTED;
            };
            if(!live(permit,current,deadline)||!authority(context))throw new Stop(FixedStatus.CANCELLED);
        }catch(Stop stopped){status=stopped.status;}
        catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            status=failure instanceof SecurityException?FixedStatus.PERMISSION_DENIED:FixedStatus.IO_UNKNOWN;
        }finally{
            if(bytes!=null)Arrays.fill(bytes,(byte)0);
            boolean closed=true;
            try{if(stdout!=null)stdout.close();}catch(Exception ignored){closed=false;}
            try{if(stdin!=null)stdin.close();}catch(Exception ignored){closed=false;}
            try{if(stderr!=null)stderr.close();}catch(Exception ignored){closed=false;}
            if(invoked){
                try{if(process!=null&&!quiet){process.destroy();quiet=process.waitForTimeout(500,"MILLISECONDS");}}
                catch(Exception ignored){quiet=false;}
                descriptorsClosed=closed&&allDescriptorsAdopted;
                // A quiet process does not prove that a timed-out native callback released its buffer.
                if(!quiet||!exitObserved||!descriptorsClosed||report==null
                        ||report.cleanup()!=CardRegionCaptureProtocol.Cleanup.CONFIRMED)
                    quarantine=new Object[]{process,stdout,stdin,stderr};
            }
        }
        boolean clean=knownClean()&&(!invoked||quiet&&descriptorsClosed&&exitObserved);
        if(!clean&&status==FixedStatus.CAPTURED)status=FixedStatus.CLEANUP_UNKNOWN;
        byte[] png=report==null?null:report.png();
        if(png!=null&&(status!=FixedStatus.CAPTURED||!clean)){Arrays.fill(png,(byte)0);png=null;}
        return new Result(status,clean,png,invoked,report==null?-1:report.started(),
                report==null?-1:report.finished(),report==null?null:report.status(),
                png==null?0:report.pngWidth(),png==null?0:report.pngHeight());
    }
    /** Independent live-run crop authority; legacy reveal scope is not borrowed. */
    static Result capture(Context context,String nonce,long absoluteDeadline,Box roi,
            int fullWidth,int fullHeight,int rotation,LiveRunAuthority.CropPermit permit,
            BooleanSupplier current,Gate gate){
        byte[] bytes=null;IRemoteProcess process=null;
        ParcelFileDescriptor stdout=null,stdin=null,stderr=null;
        boolean invoked=false,allDescriptorsAdopted=false,descriptorsClosed=true;
        boolean stdoutAdopted=false,stdinAdopted=false,stderrAdopted=false;
        boolean stdoutClosed=true,stdinClosed=true,stderrClosed=true;
        var stream=new LiveCropStream();
        FixedStatus status=FixedStatus.AUTHORITY_REJECTED;
        long started=SystemClock.elapsedRealtime();
        var window=LiveCropStream.window(started,permit==null?absoluteDeadline:Math.min(absoluteDeadline,permit.deadline()),
                permit!=null && permit.phase()!=LiveRewardsTypes.Phase.BEFORE);
        long deadline=window.captureDeadline();
        try{
            if(context==null||current==null||gate==null||permit==null||!permit.live()
                    ||!NativeUiStructureProtocol.validNonce(nonce)||absoluteDeadline<0
                    ||!CardRegionCaptureProtocol.validCrop(roi,fullWidth,fullHeight,rotation))
                throw new Stop(FixedStatus.INVALID_REQUEST);
            if(android.os.Build.VERSION.SDK_INT<34)throw new Stop(FixedStatus.API_UNAVAILABLE);
            checkDispatch(window,permit,current,authority(context));
            var request=new CardRegionCaptureProtocol.Request(nonce,deadline,roi,fullWidth,fullHeight,rotation);
            if(!permit.bindRequest(roi,fullWidth,fullHeight,rotation,started))
                throw new Stop(FixedStatus.INVALID_REQUEST);
            checkDispatch(window,permit,current,authority(context));
            requireLiveGate(gate,window,permit,current,()->authority(context));
            String apk=verifiedApk(context);if(apk==null)throw new Stop(status);
            var binder=Shizuku.getBinder();if(binder==null||!binder.isBinderAlive())throw new Stop(status);
            var service=IShizukuService.Stub.asInterface(binder);if(service==null)throw new Stop(status);
            BooleanSupplier exactAuthority=()->authority(context)&&binder==Shizuku.getBinder()&&binder.isBinderAlive();
            String[] args=request.arguments();String[] command=new String[5+args.length];
            command[0]="/system/bin/app_process";command[1]="-cp";command[2]=apk;
            command[3]="/system/bin";command[4]=HELPER;System.arraycopy(args,0,command,5,args.length);
            bytes=new byte[CardRegionCaptureProtocol.MAX_WIRE+1];
            checkDispatch(window,permit,current,exactAuthority.getAsBoolean());
            status=FixedStatus.GATE_REJECTED;
            // The root consumes the exact observation and durably burns the separate crop record.
            try{
                boolean consumed=window.after()?gate.consumeWithin(()->window.mayDispatch(SystemClock.elapsedRealtime())):gate.consume();
                if(!consumed)throw new Stop(status);
            }
            catch(Stop stopped){throw stopped;}
            catch(InsufficientBudget depleted){checkDispatch(window,permit,current,exactAuthority.getAsBoolean());throw new Stop(status);}
            catch(Exception denied){if(denied instanceof InterruptedException)Thread.currentThread().interrupt();throw new Stop(FixedStatus.GATE_REJECTED);}
            long claimAt=checkDispatch(window,permit,current,exactAuthority.getAsBoolean());
            if(!permit.claimDispatch(claimAt))
                throw new Stop(FixedStatus.ALREADY_CLAIMED);
            requireLiveGate(gate,window,permit,current,exactAuthority);
            // No further authority IPC follows the final source gate: only local epoch/binder checks.
            long dispatched=checkDispatch(window,permit,current,binder==Shizuku.getBinder()&&binder.isBinderAlive());
            stream.stage=LiveCropStream.Stage.OPEN_STREAMS;
            invoked=true;status=FixedStatus.PROCESS_NULL;process=service.newProcess(command,null,null);
            if(process==null)throw new Stop(status);
            descriptorsClosed=false;status=FixedStatus.STREAM_MISSING;
            stdout=process.getInputStream();stdoutAdopted=stdout!=null;
            stdin=process.getOutputStream();stdinAdopted=stdin!=null;
            stderr=process.getErrorStream();stderrAdopted=stderr!=null;
            if(stdout==null||stdin==null||stderr==null)throw new Stop(status);
            allDescriptorsAdopted=true;
            stdin.close();stdin=null;stderr.close();stderr=null; // No stderr text is inspected.
            int flags=Os.fcntlInt(stdout.getFileDescriptor(),OsConstants.F_GETFL,0);
            Os.fcntlInt(stdout.getFileDescriptor(),OsConstants.F_SETFL,flags|OsConstants.O_NONBLOCK);
            status=FixedStatus.IO_UNKNOWN;
            ParcelFileDescriptor pipe=stdout;
            IRemoteProcess child=process;
            BooleanSupplier ownsStream=()->{
                long at=SystemClock.elapsedRealtime();
                return !Thread.currentThread().isInterrupted()&&current.getAsBoolean()
                        &&(at<deadline?permit.current(at):permit.activeForRetirement(at));
            };
            stream.read(bytes,request,dispatched,window,SystemClock::elapsedRealtime,
                    ownsStream,
                    (target,offset,length)->readPipe(pipe,target,offset,length),
                    new LiveCropStream.ProcessExit(){
                        public boolean quiet()throws Exception{return child.waitForTimeout(0,"MILLISECONDS");}
                        public int exitValue()throws Exception{return child.exitValue();}
                    },Thread::sleep);
            status=stream.status;
            if(status==FixedStatus.CAPTURED||status==FixedStatus.READ_TIMEOUT
                    ||status==FixedStatus.CAPTURE_REJECTED&&stream.helperStatus==CardRegionCaptureProtocol.Status.DEADLINE_EXCEEDED){
                if(!exactAuthority.getAsBoolean())throw new Stop(FixedStatus.AUTHORITY_REJECTED);
                if(!ownsStream.getAsBoolean())throw new Stop(FixedStatus.CANCELLED);
            }
            if(status==FixedStatus.CAPTURED){
                if(SystemClock.elapsedRealtime()>=deadline)throw new Stop(FixedStatus.READ_TIMEOUT);
                if(!live(permit,current,deadline))throw new Stop(FixedStatus.CANCELLED);
            }
        }catch(Stop stopped){status=stopped.status;}
        catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            status=failure instanceof SecurityException?FixedStatus.PERMISSION_DENIED:FixedStatus.IO_UNKNOWN;
        }finally{
            if(bytes!=null)Arrays.fill(bytes,(byte)0);
            try{if(stdout!=null)stdout.close();}catch(Exception ignored){stdoutClosed=false;}
            try{if(stdin!=null)stdin.close();}catch(Exception ignored){stdinClosed=false;}
            try{if(stderr!=null)stderr.close();}catch(Exception ignored){stderrClosed=false;}
            if(invoked){
                try{if(process!=null&&!stream.quiet){process.destroy();stream.quiet=process.waitForTimeout(500,"MILLISECONDS");}}
                catch(Exception ignored){stream.quiet=false;}
                descriptorsClosed=stdoutClosed&&stdinClosed&&stderrClosed&&allDescriptorsAdopted;
                if(!stream.released(descriptorsClosed))
                    quarantine=new Object[]{process,stdout,stdin,stderr};
            }
        }
        boolean clean=knownClean()&&(!invoked||stream.released(descriptorsClosed));
        if(!clean&&status==FixedStatus.CAPTURED)status=FixedStatus.CLEANUP_UNKNOWN;
        byte[] png=stream.png;
        if(png!=null&&(status!=FixedStatus.CAPTURED||!clean)){Arrays.fill(png,(byte)0);png=null;}
        String diagnostic=stream.diagnostic(descriptorsClosed)
                +"_OUT_"+descriptorState(stdoutAdopted,stdoutClosed)+"_IN_"+descriptorState(stdinAdopted,stdinClosed)
                +"_ERR_"+descriptorState(stderrAdopted,stderrClosed);
        return new Result(status,clean,png,invoked,stream.helperStarted,stream.helperFinished,stream.helperStatus,
                png==null?0:stream.pngWidth,png==null?0:stream.pngHeight,diagnostic);
    }
    private static String descriptorState(boolean adopted,boolean closed){
        return !adopted?"NO_HANDLE":closed?"CLOSED":"UNCONFIRMED";
    }
    private static long checkDispatch(LiveCropStream.Window window,LiveRunAuthority.CropPermit permit,
            BooleanSupplier current,boolean authorityReady)throws Stop {
        boolean ownerCurrent=current.getAsBoolean();
        long now=SystemClock.elapsedRealtime();
        var stop=window.preDispatch(now,authorityReady,ownerCurrent&&permit.activeForRetirement(now),
                permit.current(now),now>=permit.deadline(),Thread.currentThread().isInterrupted());
        if(stop!=null)throw new Stop(stop);
        return now;
    }
    private static void requireLiveGate(Gate gate,LiveCropStream.Window window,LiveRunAuthority.CropPermit permit,
            BooleanSupplier current,BooleanSupplier authorityReady)throws Stop {
        try{requireGate(gate);}
        catch(InsufficientBudget expired){
            checkDispatch(window,permit,current,authorityReady.getAsBoolean());
            throw new Stop(FixedStatus.GATE_REJECTED);
        }
    }
    static int readPipe(ParcelFileDescriptor fd,byte[] bytes,int offset,int length)throws Exception{
        StructPollfd poll=new StructPollfd();poll.fd=fd.getFileDescriptor();
        poll.events=(short)(OsConstants.POLLIN|OsConstants.POLLHUP|OsConstants.POLLERR);
        try{
            if(Os.poll(new StructPollfd[]{poll},25)==0)return 0;
            if((poll.revents&OsConstants.POLLNVAL)!=0)throw new IOException("Invalid crop pipe");
            // The child stdout has a single consumer. On API 28/29, read only when
            // poll proves pipe data or writer closure; an error event alone cannot authorize a blocking read.
            if((poll.revents&(OsConstants.POLLIN|OsConstants.POLLHUP))==0){
                if((poll.revents&OsConstants.POLLERR)!=0)throw new IOException("Child stdout pipe error");
                return 0;
            }
            int n=Os.read(poll.fd,bytes,offset,length);return n==0?-1:n;
        }catch(ErrnoException failure){
            if(failure.errno==OsConstants.EAGAIN||failure.errno==OsConstants.EINTR)return 0;
            throw failure;
        }
    }
    private static boolean live(CardRevealProbeIntent.UiPermit permit,BooleanSupplier current,long deadline){
        long now=SystemClock.elapsedRealtime();return !Thread.currentThread().isInterrupted()
                &&now<deadline&&permit.current(now)&&current.getAsBoolean();
    }
    private static boolean live(LiveRunAuthority.CropPermit permit,BooleanSupplier current,long deadline){
        long now=SystemClock.elapsedRealtime();return permit!=null&&!Thread.currentThread().isInterrupted()
                &&now<deadline&&permit.live()&&permit.current(now)&&current.getAsBoolean();
    }
    private static void requireGate(Gate gate)throws Stop{
        try{gate.requireCurrent();}catch(InsufficientBudget expired){throw expired;}catch(Exception rejected){
            if(rejected instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new Stop(FixedStatus.GATE_REJECTED);
        }
    }
    private static boolean authority(Context context){
        return knownClean()&&AndroidUiStructureReader.processKnownClean()&&AndroidOverviewUiReader.knownClean()
                &&Looper.myLooper()!=Looper.getMainLooper()&&android.os.Process.myUid()>=10000
                &&PACKAGE.equals(context.getPackageName())&&(PACKAGE+":capture_once").equals(Application.getProcessName())
                &&Shizuku.pingBinder()&&!Shizuku.isPreV11()&&Shizuku.getUid()==0
                &&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;
    }
    @SuppressWarnings("deprecation")
    private static String verifiedApk(Context context)throws Exception{
        PackageManager pm=context.getPackageManager();
        PackageInfo own=pm.getPackageInfo(PACKAGE,PackageManager.GET_SIGNATURES);
        if(own==null||!PACKAGE.equals(own.packageName)||own.applicationInfo==null
                ||own.applicationInfo.uid!=android.os.Process.myUid()||context.getApplicationInfo()==null
                ||!own.applicationInfo.sourceDir.equals(context.getApplicationInfo().sourceDir))return null;
        File file=new File(own.applicationInfo.sourceDir);String canonical=file.getCanonicalPath();
        if(!file.isFile()||!file.canRead()||!file.isAbsolute()||!canonical.equals(file.getAbsolutePath())
                ||!canonical.startsWith("/data/app/")||!canonical.endsWith("/base.apk")
                ||canonical.indexOf('\n')>=0||canonical.indexOf('\r')>=0||canonical.indexOf(':')>=0)return null;
        PackageInfo archive=pm.getPackageArchiveInfo(canonical,PackageManager.GET_SIGNATURES);
        return archive!=null&&PACKAGE.equals(archive.packageName)&&own.getLongVersionCode()==archive.getLongVersionCode()
                &&AndroidOverviewUiReader.sameSignatures(own.signatures,archive.signatures)?canonical:null;
    }
    private static final class Stop extends Exception{
        private static final long serialVersionUID=1L;final FixedStatus status;Stop(FixedStatus status){this.status=status;}
    }
    private AndroidCardRegionCapture(){}
}
