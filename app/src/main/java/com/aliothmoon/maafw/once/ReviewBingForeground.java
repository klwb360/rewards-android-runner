package com.aliothmoon.maafw.once;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;
/** Exactly one fixed read-only command, bounded metadata only. No arbitrary shell,
 * screenshot, UI tree, raw OCR, host PID, root fallback or permission grant path. */
final class ReviewBingForeground {
    private static final AtomicInteger READS=new AtomicInteger();
    private static volatile Object quarantine;
    static BingForegroundSnapshot.Snapshot read(Context context) {
        if(context==null || quarantine!=null || Looper.myLooper()==Looper.getMainLooper()
            || !"com.aliothmoon.maafw.productrewardsworking.capturereview".equals(context.getPackageName())
            || !(context.getPackageName()+":capture_once").equals(Application.getProcessName())
            || android.os.Process.myUid()<10000 || READS.incrementAndGet()>8) return null;
        byte[] bytes=new byte[262144];
        IRemoteProcess process=null;
        InputStream input=null;
        ParcelFileDescriptor stdout=null,stdin=null,stderr=null;
        boolean invoked=false,quiet=false,clean=false;
        try {
            var binder=Shizuku.getBinder();
            if(binder==null || !binder.isBinderAlive() || !Shizuku.pingBinder() || Shizuku.isPreV11()
                || Shizuku.getUid()!=0 || Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) return null;
            var service=IShizukuService.Stub.asInterface(binder);
            if(service==null)return null;
            invoked=true;
            process=service.newProcess(new String[]{"/system/bin/dumpsys","window"},null,null);
            if(process==null)throw new IllegalStateException();
            stdout=process.getInputStream();stdin=process.getOutputStream();stderr=process.getErrorStream();
            if(stdout==null)throw new IllegalStateException();
            input=new ParcelFileDescriptor.AutoCloseInputStream(stdout);stdout=null;
            long end=SystemClock.elapsedRealtime()+2000;
            int count=0;
            for(;;) {
                int available=input.available();
                if(available>0){
                    if(count==bytes.length)throw new IllegalStateException("Metadata bound");
                    int n=input.read(bytes,count,Math.min(available,bytes.length-count));
                    if(n<0)throw new IllegalStateException();
                    count+=n;
                }
                quiet=process.waitForTimeout(0,"MILLISECONDS");
                if(quiet && input.available()==0)break;
                if(SystemClock.elapsedRealtime()>=end)throw new IllegalStateException("Snapshot timeout");
                if(available==0)Thread.sleep(5);
            }
            if(process.exitValue()!=0)return null;
            // Close owned descriptors before publishing any fact.
            input.close();input=null;
            if(stdin!=null){stdin.close();stdin=null;}
            if(stderr!=null){stderr.close();stderr=null;}
            clean=true;
            return BingForegroundSnapshot.parse(new String(bytes,0,count,java.nio.charset.StandardCharsets.UTF_8));
        } catch(Exception failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            return null;
        } finally {
            Arrays.fill(bytes,(byte)0);
            if(invoked && !clean) {
                try {
                    if(process!=null && !quiet){process.destroy();quiet=process.waitForTimeout(500,"MILLISECONDS");}
                    if(input!=null)input.close();
                    if(stdout!=null)stdout.close();
                    if(stdin!=null)stdin.close();
                    if(stderr!=null)stderr.close();
                    clean=quiet;
                }catch(Exception unknown){clean=false;}
                if(!clean)quarantine=new Object[]{process,input,stdout,stdin,stderr};
            }
        }
    }
    static boolean knownClean(){return quarantine==null;}
    private ReviewBingForeground(){}
}
