package com.aliothmoon.maafw.once;

import android.app.Application;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** V46 fixed metadata only. No caller command, shell, file, UI text or metadata output. */
final class V46EntryMetadataReader {
    enum Stage { ANCHOR, PREPARE_BEFORE, PREPARE_AFTER, COMMIT_BEFORE, AFTER_WAIT, COUNTER_BEFORE, COUNTER_AFTER, HELPER_PREPARE, HELPER_COMMIT }
    enum Status { NOT_INVOKED, STAGE_REJECTED, AUTHORITY_REJECTED, DEADLINE, PROCESS_UNKNOWN, STREAM_UNKNOWN, BUFFER_LIMIT, EXIT_NONZERO, PARSED, IO_UNKNOWN, CLEANUP_UNKNOWN }
    private enum Backend { APP_SHIZUKU, ROOT_HELPER }
    private static final String APP="com.aliothmoon.maafw.productrewardsworking.capturereview:capture_once";
    private static volatile boolean quarantine;
    private final Backend backend;
    private final BingNavigationSnapshot.Profile profile;
    private final BingNavigationSnapshot.ReadOnlyGuard guard;
    private final EnumSet<Stage> consumed=EnumSet.noneOf(Stage.class);
    private Status status=Status.NOT_INVOKED;
    private int invocations,bytes;
    private boolean clean=true;
    private V46EntryMetadataReader(Backend backend,BingNavigationSnapshot.Profile profile,BingNavigationSnapshot.ReadOnlyGuard guard){
        if(profile==null||profile.shape()==null||profile.bingUid()<10000||profile.systemUiUid()<10000||guard==null||!guard.valid())throw new IllegalArgumentException("Exact metadata profile required");
        this.backend=backend;this.profile=profile;this.guard=guard;
    }
    static V46EntryMetadataReader app(BingNavigationSnapshot.Profile profile,BingNavigationSnapshot.ReadOnlyGuard guard){return new V46EntryMetadataReader(Backend.APP_SHIZUKU,profile,guard);}
    static V46EntryMetadataReader rootHelper(BingNavigationSnapshot.Profile profile,BingNavigationSnapshot.ReadOnlyGuard guard){return new V46EntryMetadataReader(Backend.ROOT_HELPER,profile,guard);}
    static boolean stageAllowed(boolean root,Stage stage){return stage!=null&&(root?(stage==Stage.HELPER_PREPARE||stage==Stage.HELPER_COMMIT):stage!=Stage.HELPER_PREPARE&&stage!=Stage.HELPER_COMMIT);}
    static boolean processKnownClean(){return !quarantine;}
    synchronized int pairsUsed(){return consumed.size();}
    synchronized int commandsInvoked(){return invocations;}
    synchronized Status status(){return status;}
    synchronized boolean knownClean(){return clean&&!quarantine;}
    synchronized String summary(){return "entryMetadataPairs="+consumed.size()+" commandsInvoked="+invocations+" metadataBytes="+bytes+" status="+status+" metadataKnownClean="+knownClean();}
    synchronized BingNavigationSnapshot.Result read(Stage stage,long absoluteDeadline,BooleanSupplier current){
        if(!stageAllowed(backend==Backend.ROOT_HELPER,stage)||consumed.contains(stage)){status=Status.STAGE_REJECTED;return BingNavigationSnapshot.Result.unknown();}
        consumed.add(stage); // Never returned after failure, cancellation or partial command.
        byte[] windows=null,activities=null;
        try {
            long lower=SystemClock.elapsedRealtime();
            if(!local(current,absoluteDeadline)){status=Status.DEADLINE;return BingNavigationSnapshot.Result.unknown();}
            windows=readFixed(true,absoluteDeadline,current);if(windows==null)return BingNavigationSnapshot.Result.unknown();
            activities=readFixed(false,absoluteDeadline,current);if(activities==null)return BingNavigationSnapshot.Result.unknown();
            if(!authority()||!local(current,absoluteDeadline)){status=Status.AUTHORITY_REJECTED;return BingNavigationSnapshot.Result.unknown();}
            long observed=SystemClock.elapsedRealtime();
            BingNavigationSnapshot.Result result=BingNavigationSnapshot.parseEntrySource(new String(windows,StandardCharsets.UTF_8),new String(activities,StandardCharsets.UTF_8),profile,guard,lower,observed);
            status=Status.PARSED;return result;
        } finally {if(windows!=null)Arrays.fill(windows,(byte)0);if(activities!=null)Arrays.fill(activities,(byte)0);}
    }
    private boolean local(BooleanSupplier current,long end){
        if(quarantine||!clean||Thread.currentThread().isInterrupted()||current==null||SystemClock.elapsedRealtime()>=end)return false;
        try{return current.getAsBoolean();}catch(RuntimeException unknown){return false;}
    }
    private boolean authority(){
        if(quarantine||!clean)return false;
        if(backend==Backend.ROOT_HELPER)return android.os.Process.myUid()==0;
        return Looper.myLooper()!=Looper.getMainLooper()&&android.os.Process.myUid()==guard.ownUid()&&APP.equals(Application.getProcessName())&&Shizuku.pingBinder()&&!Shizuku.isPreV11()
            &&Shizuku.getUid()==0&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;
    }
    private byte[] readFixed(boolean windows,long absoluteDeadline,BooleanSupplier current){
        final String[] command=windows?new String[]{"/system/bin/dumpsys","window","-a"}:new String[]{"/system/bin/dumpsys","activity","activities"};
        byte[] buffer=new byte[262144];int count=0;Handle handle=null;boolean invoked=false,quiet=false,closed=true;byte[] result=null;
        long end=Math.min(absoluteDeadline,SystemClock.elapsedRealtime()+2000);
        try {
            status=Status.AUTHORITY_REJECTED;if(!authority()||!local(current,end))return null;
            if(backend==Backend.ROOT_HELPER){
                if(!local(current,end))return null;
                invoked=true;invocations++;closed=false;handle=new LocalHandle(Runtime.getRuntime().exec(command,null,null));
            }else{
                IBinder binder=Shizuku.getBinder();if(binder==null||!binder.isBinderAlive())return null;
                IShizukuService service=IShizukuService.Stub.asInterface(binder);if(service==null)return null;
                if(!authority()||Shizuku.getBinder()!=binder||!binder.isBinderAlive()||!local(current,end))return null;
                invoked=true;invocations++;closed=false;IRemoteProcess process=service.newProcess(command,null,null);
                if(process==null)throw new IllegalStateException();handle=new RemoteHandle(process);
            }
            status=Status.STREAM_UNKNOWN;handle.open();InputStream input=handle.input();if(input==null)throw new IllegalStateException();status=Status.IO_UNKNOWN;
            for(;;){
                if(!local(current,end)){status=Status.DEADLINE;throw new IllegalStateException();}
                int available=input.available();if(available>0){if(count==buffer.length){status=Status.BUFFER_LIMIT;throw new IllegalStateException();}int n=input.read(buffer,count,Math.min(available,buffer.length-count));if(n<0)throw new IllegalStateException();count+=n;}
                quiet=handle.quiet();if(quiet&&input.available()==0)break;
                if(available==0)Thread.sleep(5);
            }
            if(handle.exit()!=0){status=Status.EXIT_NONZERO;return null;}
            if(!local(current,end)){status=Status.DEADLINE;return null;}
            result=Arrays.copyOf(buffer,count);return result;
        }catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();return null;}
        finally{
            bytes+=count;Arrays.fill(buffer,(byte)0);
            if(invoked){
                if(handle!=null){
                    if(!quiet){try{handle.destroy();quiet=handle.awaitQuiet();}catch(Exception ignored){quiet=false;}}
                    closed=handle.closeAll();
                }
                if(!quiet||!closed){clean=false;quarantine=true;status=Status.CLEANUP_UNKNOWN;if(result!=null)Arrays.fill(result,(byte)0);}
            }
        }
    }
    private interface Handle {
        void open()throws Exception;InputStream input();boolean quiet()throws Exception;boolean awaitQuiet()throws Exception;int exit()throws Exception;void destroy()throws Exception;boolean closeAll();
    }
    private static final class LocalHandle implements Handle {
        final Process p;InputStream in,err;OutputStream out;
        LocalHandle(Process p){this.p=p;}
        public void open(){in=p.getInputStream();out=p.getOutputStream();err=p.getErrorStream();}
        public InputStream input(){return in;}public boolean quiet(){return !p.isAlive();}public boolean awaitQuiet()throws Exception{return p.waitFor(500,TimeUnit.MILLISECONDS);}public int exit(){return p.exitValue();}public void destroy(){p.destroy();}
        public boolean closeAll(){boolean ok=true;try{if(in!=null)in.close();else p.getInputStream().close();}catch(Exception e){ok=false;}try{if(out!=null)out.close();else p.getOutputStream().close();}catch(Exception e){ok=false;}try{if(err!=null)err.close();else p.getErrorStream().close();}catch(Exception e){ok=false;}return ok;}
    }
    private static final class RemoteHandle implements Handle {
        final IRemoteProcess p;ParcelFileDescriptor inFd,outFd,errFd;InputStream in;
        RemoteHandle(IRemoteProcess p){this.p=p;}
        public void open()throws Exception{inFd=p.getInputStream();outFd=p.getOutputStream();errFd=p.getErrorStream();if(inFd==null||outFd==null||errFd==null)throw new IllegalStateException();in=new ParcelFileDescriptor.AutoCloseInputStream(inFd);inFd=null;}
        public InputStream input(){return in;}public boolean quiet()throws Exception{return p.waitForTimeout(0,"MILLISECONDS");}public boolean awaitQuiet()throws Exception{return p.waitForTimeout(500,"MILLISECONDS");}public int exit()throws Exception{return p.exitValue();}public void destroy()throws Exception{p.destroy();}
        public boolean closeAll(){boolean ok=true;try{if(in!=null)in.close();else if(inFd!=null)inFd.close();else p.getInputStream().close();}catch(Exception e){ok=false;}try{if(outFd!=null)outFd.close();else p.getOutputStream().close();}catch(Exception e){ok=false;}try{if(errFd!=null)errFd.close();else p.getErrorStream().close();}catch(Exception e){ok=false;}return ok;}
    }
}
