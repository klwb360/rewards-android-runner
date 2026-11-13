package com.aliothmoon.maafw.once;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.Display;
import android.view.WindowManager;
import java.io.IOException;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** Offline staged implementation. Never instantiated by any current entry point.
 * Reuses existing IShizukuService/IRemoteProcess bridge, NOT a new UserService.
 * Fixed default-display screencap stdout only. No path, shell, flags, display/PID input,
 * permission request, root fallback, file output, logging or retry interface. */
final class AndroidReviewShizukuPngTransport implements PngCaptureSession.Transport,PngCaptureSession.Gate {
    private static final String REVIEW_PACKAGE="com.aliothmoon.maafw.productrewardsworking.capturereview";
    private final Context context;
    private final BoundedPngGray.Geometry geometry;
    private final int rotation;
    private final PngCaptureSession.Gate existingGate;
    private final IBinder binder;
    private final IShizukuService service;
    AndroidReviewShizukuPngTransport(Context context,BoundedPngGray.Geometry geometry,int rotation,
            PngCaptureSession.Gate existingGate) {
        if(context==null||geometry==null||existingGate==null||rotation<0||rotation>3)
            throw new IllegalArgumentException();
        this.context=context.getApplicationContext();this.geometry=geometry;this.rotation=rotation;this.existingGate=existingGate;
        requireReviewProcess();binder=Shizuku.getBinder();
        if(binder==null) throw new SecurityException("Shizuku unavailable");
        service=IShizukuService.Stub.asInterface(binder);
        if(service==null) throw new SecurityException("Shizuku unavailable");
    }
    private void requireReviewProcess() {
        if(android.os.Build.VERSION.SDK_INT<34 || android.os.Process.myUid()<10000
                || !REVIEW_PACKAGE.equals(context.getPackageName())
                || !(REVIEW_PACKAGE+":capture_once").equals(Application.getProcessName())
                || Looper.myLooper()==Looper.getMainLooper())
            throw new SecurityException("dedicated normal-UID review worker required");
    }
    @Override public void requireCurrent() throws Exception {
        if(android.os.Build.VERSION.SDK_INT<34)throw new SecurityException("Android14 root profile required");
        requireReviewProcess();existingGate.requireCurrent();
        if(Shizuku.getBinder()!=binder||!binder.isBinderAlive()||!Shizuku.pingBinder()||Shizuku.isPreV11()
                || Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED||Shizuku.getUid()!=0)
            throw new SecurityException("root Shizuku state lost");
        DisplayManager dm=context.getSystemService(DisplayManager.class);
        Display d=dm==null?null:dm.getDisplay(Display.DEFAULT_DISPLAY);
        if(d==null||d.getState()!=Display.STATE_ON||d.getRotation()!=rotation)
            throw new SecurityException("display changed");
        WindowManager wm=context.createDisplayContext(d).getSystemService(WindowManager.class);
        if(wm==null) throw new SecurityException("display unavailable");
        Rect b=wm.getMaximumWindowMetrics().getBounds();
        if(b.left!=0||b.top!=0||b.width()!=geometry.width()||b.height()!=geometry.height())
            throw new SecurityException("display geometry changed");
        existingGate.requireCurrent();
    }
    @Override public PngCaptureSession.Process open() throws Exception {
        requireCurrent();
        // Final local admission is inside requireCurrent. No generic command builder.
        IRemoteProcess remote=service.newProcess(new String[]{"/system/bin/screencap","-p"},null,null);
        return remote==null?null:new OwnProcess(remote);
    }
    private static final class OwnProcess implements PngCaptureSession.Process {
        private final IRemoteProcess remote;
        private ParcelFileDescriptor stdout,stdin,stderr;
        private boolean adoptionAttempted,allGettersReturned,closeAttempted,destroyAttempted;
        OwnProcess(IRemoteProcess remote){this.remote=remote;}
        @Override public BoundedPngGray.Input adoptStdout() throws Exception {
            if(android.os.Build.VERSION.SDK_INT<34)throw new SecurityException("Android14 root pipe required");
            if(adoptionAttempted) throw new IllegalStateException("no re-adoption");
            adoptionAttempted=true;
            // Store each returned PFD before any further IPC/check. Getter exception
            // means ownership is uncertain, even if all known FDs later close.
            stdout=remote.getInputStream();stdin=remote.getOutputStream();stderr=remote.getErrorStream();
            if(stdout==null||stdin==null||stderr==null) throw new IOException("missing stdio PFD");
            allGettersReturned=true;
            int flags=Os.fcntlInt(stdout.getFileDescriptor(),OsConstants.F_GETFL,0);
            Os.fcntlInt(stdout.getFileDescriptor(),OsConstants.F_SETFL,flags|OsConstants.O_NONBLOCK);
            return new PfdInput(stdout);
        }
        @Override public boolean waitForExit(int millis) throws Exception {
            if(millis<1||millis>1000) throw new IllegalArgumentException();
            return remote.waitForTimeout(millis,"MILLISECONDS");
        }
        @Override public int exitCode() throws Exception {return remote.exitValue();}
        @Override public void destroy() throws Exception {
            if(destroyAttempted) throw new IllegalStateException("no second destroy");
            destroyAttempted=true;remote.destroy();
        }
        @Override public boolean closeDescriptors() {
            if(closeAttempted) throw new IllegalStateException("no second close");
            closeAttempted=true;
            boolean clean=allGettersReturned;
            // No stderr text is read or logged. A verbose failed child can block on
            // stderr; local read deadline then destroys it and requires observed exit.
            for(ParcelFileDescriptor fd:new ParcelFileDescriptor[]{stdout,stdin,stderr}) {
                if(fd!=null) try{fd.close();}catch(Exception failed){clean=false;}
            }
            return clean;
        }
    }
    private static final class PfdInput implements BoundedPngGray.Input {
        private final ParcelFileDescriptor fd;
        PfdInput(ParcelFileDescriptor fd){this.fd=fd;}
        @Override public int read(byte[] bytes,int off,int len,int waitMillis) throws Exception {
            if(len<=0||off<0||len>bytes.length-off||waitMillis<1||waitMillis>100) throw new IllegalArgumentException();
            StructPollfd p=new StructPollfd();p.fd=fd.getFileDescriptor();
            p.events=(short)(OsConstants.POLLIN|OsConstants.POLLHUP|OsConstants.POLLERR);
            try {
                if(Os.poll(new StructPollfd[]{p},waitMillis)==0) return 0;
                if((p.revents&OsConstants.POLLNVAL)!=0) throw new IOException("invalid pipe FD");
                // O_NONBLOCK closes poll/read readiness race; HUP is drained to EOF.
                int n=Os.read(p.fd,bytes,off,len);return n==0?-1:n;
            } catch(ErrnoException e) {
                if(e.errno==OsConstants.EAGAIN||e.errno==OsConstants.EINTR) return 0;
                throw e;
            }
        }
    }
}
