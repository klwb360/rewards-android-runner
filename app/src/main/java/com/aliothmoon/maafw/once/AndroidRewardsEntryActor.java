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
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** Single fixed home-entry navigation only. No task card actor, shell, caller command or raw output. */
final class AndroidRewardsEntryActor {
    private static final String PACKAGE="com.aliothmoon.maafw.productrewardsworking.capturereview";
    private static final String HELPER="com.aliothmoon.maafw.once.NativeRewardsEntryHelper";
    static final long PHASE_MILLIS=30_000;
    private static final AtomicBoolean CLAIMED=new AtomicBoolean();
    private static volatile Object quarantine;
    record Request(String nonce,int task,int width,int height,int rotation,int bingUid,int systemUiUid,int ownUid,int ownTask) {
        boolean valid(){return nonce!=null&&nonce.matches("[a-f0-9]{32}")&&task>0&&task<=1_000_000
            &&width>0&&width<=8192&&height>0&&height<=8192&&rotation>=0&&rotation<=3
            &&bingUid>=10000&&systemUiUid>=10000&&ownUid>=10000&&ownTask>0&&ownTask<=1_000_000&&ownTask!=task;}
    }
    interface DispatchGate { boolean allowPrepare() throws Exception; }
    interface CommitGate { boolean allowAndBurnNavigation(NativeRewardsEntryProtocol.Prepared prepared) throws Exception; }
    enum FixedStatus { INVALID_REQUEST,AUTHORITY_REJECTED,ALREADY_CLAIMED,PREPARE_REJECTED,COMMIT_REJECTED,
        PROCESS_NULL,STREAM_MISSING,BUFFER_LIMIT,TIMEOUT,CANCELLED,EXIT_NONZERO,PROTOCOL_REJECTED,
        IO_UNKNOWN,COMPLETE,CLEANUP_UNKNOWN }
    record Result(NativeRewardsEntryProtocol.Result receipt,FixedStatus status,boolean invocationAttempted,
        boolean preparedObserved,boolean commitAttempted,boolean commitFlushed,boolean knownClean,
        boolean processQuiet,boolean descriptorsClosed,boolean exitObserved,int exitCode,int stdoutBytes,long receivedAt) {
        boolean actionAttempted(){return receipt!=null&&receipt.actionAttempted();}
        boolean actionReturnedTrue(){return receipt!=null&&receipt.actionReturnedTrue();}
        boolean succeeded(){return status==FixedStatus.COMPLETE&&knownClean&&receipt!=null&&receipt.reason()==NativeRewardsEntryProtocol.Reason.OK
            &&receipt.cleanup()==NativeRewardsEntryProtocol.Cleanup.CONFIRMED&&receipt.actionReturnedTrue()&&commitAttempted&&commitFlushed&&receivedAt>=0;}
    }
    /** Failure spends the capability too. No caller can send a second or replacement command. */
    static final class OneCommit {
        private boolean attempted,flushed;
        synchronized void send(OutputStream output,byte[] command)throws IOException {
            if(attempted)throw new IOException("Commit already consumed");
            attempted=true;
            output.write(command);output.flush();flushed=true;output.close();
        }
        boolean attempted(){return attempted;}boolean flushed(){return flushed;}
    }
    static boolean knownClean(){return quarantine==null;}
    static boolean processKnownClean(){return knownClean();}
    static boolean dispatchAllowed(long now,long deadline,boolean current,boolean interrupted,boolean sameBinder,boolean alive){
        return now>=0&&now<deadline&&current&&!interrupted&&sameBinder&&alive;
    }
    static boolean validLine(byte[] bytes,int count){
        if(bytes==null||count<2||count>bytes.length||count>NativeRewardsEntryProtocol.MAX_LINE||bytes[count-1]!='\n')return false;
        for(int i=0;i<count-1;i++){int c=bytes[i]&255;if(!(c>='A'&&c<='Z'||c>='a'&&c<='f'||c>='0'&&c<='9'||c=='_'||c=='|'||c=='-'))return false;}
        return true;
    }
    static Result execute(Context context,Request request,long absoluteDeadline,BooleanSupplier current,
                          DispatchGate prepareGate,CommitGate commitGate){
        if(ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_READY_V49||ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_FORMAT_V50||ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_FIELD_V51||ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_BOUND_POINTS_V53)throw new SecurityException("Read-only checkpoint blocks navigation");
        byte[] line=new byte[NativeRewardsEntryProtocol.MAX_LINE];byte[] commitBytes=null;OneCommit once=new OneCommit();
        IRemoteProcess process=null;InputStream input=null;OutputStream output=null;
        ParcelFileDescriptor stdout=null,stdin=null,stderr=null;
        boolean invoked=false,preparedObserved=false,commitAttempted=false,commitFlushed=false;
        boolean quiet=false,closed=true,exitObserved=false;int exit=-1,lineCount=0,lineBytes=0,total=0;
        long started=SystemClock.elapsedRealtime(),deadline=Math.min(absoluteDeadline,started+PHASE_MILLIS),dispatched=-1,receivedAt=-1;
        NativeRewardsEntryProtocol.Prepared prepared=null;NativeRewardsEntryProtocol.Result receipt=null;
        FixedStatus status=FixedStatus.AUTHORITY_REJECTED;
        try{
            if(context==null||request==null||!request.valid()||current==null||prepareGate==null||commitGate==null||deadline<=started
                ||request.ownUid()!=android.os.Process.myUid())throw new Stop(FixedStatus.INVALID_REQUEST);
            if(!authority(context)||!live(current,deadline))throw new Stop(status);
            String apk=verifiedApk(context);if(apk==null)throw new Stop(status);
            var binder=Shizuku.getBinder();if(binder==null||!binder.isBinderAlive())throw new Stop(status);
            var service=IShizukuService.Stub.asInterface(binder);if(service==null)throw new Stop(status);
            String[] command={"/system/bin/app_process","-cp",apk,"/system/bin",HELPER,request.nonce(),Long.toString(deadline),
                Integer.toString(request.task()),Integer.toString(request.width()),Integer.toString(request.height()),Integer.toString(request.rotation()),
                Integer.toString(request.bingUid()),Integer.toString(request.systemUiUid()),Integer.toString(request.ownUid()),Integer.toString(request.ownTask())};
            if(ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.REWARDS_ENTRY_V48){
                command=Arrays.copyOf(command,command.length+1);
                command[command.length-1]="PARENT_REWARDS_V48";
            }
            if(ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_LOCATE_V52){
                command=Arrays.copyOf(command,command.length+1);
                command[command.length-1]="SHOW_REGION_V52";
            }
            if(!authority(context)||!live(current,deadline))throw new Stop(status);
            if(!CLAIMED.compareAndSet(false,true))throw new Stop(FixedStatus.ALREADY_CLAIMED);
            if(!prepareGate.allowPrepare())throw new Stop(FixedStatus.PREPARE_REJECTED);
            boolean same=binder==Shizuku.getBinder(),alive=binder.isBinderAlive(),active=current.getAsBoolean();
            dispatched=SystemClock.elapsedRealtime();
            if(!dispatchAllowed(dispatched,deadline,active,Thread.currentThread().isInterrupted(),same,alive))throw new Stop(FixedStatus.CANCELLED);
            invoked=true;status=FixedStatus.PROCESS_NULL;process=service.newProcess(command,null,null);
            if(process==null)throw new Stop(status);
            closed=false;status=FixedStatus.STREAM_MISSING;
            stdout=process.getInputStream();stdin=process.getOutputStream();stderr=process.getErrorStream();
            if(stderr!=null){stderr.close();stderr=null;} // Never read or expose stderr.
            if(stdout==null||stdin==null)throw new Stop(status);
            input=new ParcelFileDescriptor.AutoCloseInputStream(stdout);stdout=null;
            output=new ParcelFileDescriptor.AutoCloseOutputStream(stdin);stdin=null;
            for(;;){
                if(!live(current,deadline))throw new Stop(SystemClock.elapsedRealtime()>=deadline?FixedStatus.TIMEOUT:FixedStatus.CANCELLED);
                int available=input.available();
                if(available>0){
                    if(lineCount>=2||lineBytes==line.length||total>=2*line.length)throw new Stop(FixedStatus.BUFFER_LIMIT);
                    int c=input.read();if(c<0)throw new Stop(FixedStatus.IO_UNKNOWN);line[lineBytes++]=(byte)c;total++;
                    if(c=='\n'){
                        if(!validLine(line,lineBytes))throw new Stop(FixedStatus.PROTOCOL_REJECTED);
                        String frame=new String(line,0,lineBytes-1,StandardCharsets.US_ASCII);lineCount++;
                        if(lineCount==1&&frame.startsWith(ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_LOCATE_V52?"ERP2|":"ERP1|")){
                            prepared=ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_LOCATE_V52
                                ?NativeRewardsEntryProtocol.parseTemplatePrepared(frame,request.nonce(),request.task(),dispatched,deadline,SystemClock.elapsedRealtime())
                                :NativeRewardsEntryProtocol.parsePrepared(frame,request.nonce(),request.task(),dispatched,deadline,SystemClock.elapsedRealtime());
                            preparedObserved=true;
                            // No pre-sent second frame may race the one authorized commit.
                            if(input.available()!=0||!authority(context)||!live(current,deadline))throw new Stop(FixedStatus.COMMIT_REJECTED);
                            if(!commitGate.allowAndBurnNavigation(prepared))throw new Stop(FixedStatus.COMMIT_REJECTED);
                            if(!authority(context))throw new Stop(FixedStatus.COMMIT_REJECTED);
                            // The callback's durable NAV burn precedes even the first write attempt.
                            same=binder==Shizuku.getBinder();alive=binder.isBinderAlive();active=current.getAsBoolean();
                            if(!dispatchAllowed(SystemClock.elapsedRealtime(),deadline,active,Thread.currentThread().isInterrupted(),same,alive))throw new Stop(FixedStatus.COMMIT_REJECTED);
                            commitBytes=NativeRewardsEntryProtocol.commit(request.nonce()).getBytes(StandardCharsets.US_ASCII);
                            commitAttempted=true;
                            // EOF is part of the fixed command. Any write/flush/close failure spends it.
                            once.send(output,commitBytes);commitFlushed=once.flushed();
                            output=null;Arrays.fill(commitBytes,(byte)0);commitBytes=null;
                        }else{
                            if(receipt!=null||lineCount==2&&prepared==null||!frame.startsWith("ERR1|"))throw new Stop(FixedStatus.PROTOCOL_REJECTED);
                            receipt=NativeRewardsEntryProtocol.parseResult(frame,request.nonce(),request.task(),prepared==null?-1:prepared.prepared(),deadline,SystemClock.elapsedRealtime());
                            if(receipt.actionAttempted()&&!commitAttempted||receipt.actionReturnedTrue()&&!commitFlushed)throw new Stop(FixedStatus.PROTOCOL_REJECTED);
                            receivedAt=SystemClock.elapsedRealtime();
                        }
                        Arrays.fill(line,(byte)0);lineBytes=0;
                    }
                }
                quiet=process.waitForTimeout(0,"MILLISECONDS");
                if(quiet&&input.available()==0)break;
                if(available==0)Thread.sleep(5);
            }
            exit=process.exitValue();exitObserved=true;
            if(exit!=0)throw new Stop(FixedStatus.EXIT_NONZERO);
            if(lineBytes!=0||receipt==null||!live(current,deadline)||!authority(context))throw new Stop(FixedStatus.PROTOCOL_REJECTED);
            status=FixedStatus.COMPLETE;
        }catch(Stop stopped){status=stopped.status;}
        catch(Exception failed){if(failed instanceof InterruptedException)Thread.currentThread().interrupt();status=FixedStatus.IO_UNKNOWN;}
        finally{
            commitAttempted=commitAttempted||once.attempted();commitFlushed=once.flushed();
            Arrays.fill(line,(byte)0);if(commitBytes!=null)Arrays.fill(commitBytes,(byte)0);
            boolean allClosed=true;
            try{if(input!=null)input.close();}catch(Exception ignored){allClosed=false;}
            try{if(output!=null)output.close();}catch(Exception ignored){allClosed=false;}
            try{if(stdout!=null)stdout.close();}catch(Exception ignored){allClosed=false;}
            try{if(stdin!=null)stdin.close();}catch(Exception ignored){allClosed=false;}
            try{if(stderr!=null)stderr.close();}catch(Exception ignored){allClosed=false;}
            if(invoked){
                try{if(process!=null&&!quiet){process.destroy();quiet=process.waitForTimeout(500,"MILLISECONDS");}}catch(Exception ignored){quiet=false;}
                closed=allClosed&&process!=null;
                if(!quiet||!closed||status!=FixedStatus.COMPLETE||receipt==null||receipt.cleanup()==NativeRewardsEntryProtocol.Cleanup.UNKNOWN)
                    quarantine=new Object[]{process,input,output,stdout,stdin,stderr};
            }
        }
        boolean clean=knownClean()&&(!invoked||quiet&&closed);
        if(!clean)status=FixedStatus.CLEANUP_UNKNOWN;
        return new Result(receipt,status,invoked,preparedObserved,commitAttempted,commitFlushed,clean,quiet||!invoked,closed,exitObserved,exit,total,receivedAt);
    }
    private static boolean live(BooleanSupplier current,long deadline){return !Thread.currentThread().isInterrupted()&&SystemClock.elapsedRealtime()<deadline&&current.getAsBoolean();}
    private static boolean authority(Context context){return knownClean()&&AndroidUiStructureReader.processKnownClean()
        &&Looper.myLooper()!=Looper.getMainLooper()&&android.os.Process.myUid()>=10000&&PACKAGE.equals(context.getPackageName())
        &&(PACKAGE+":capture_once").equals(Application.getProcessName())&&Shizuku.pingBinder()&&!Shizuku.isPreV11()&&Shizuku.getUid()==0
        &&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
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
        private static final long serialVersionUID=1L;final FixedStatus status;Stop(FixedStatus status){this.status=status;}
    }
    private AndroidRewardsEntryActor(){}
}
