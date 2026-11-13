package com.aliothmoon.maafw.once;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Display;
import android.view.WindowManager;
import java.io.IOException;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** Fixed /system/bin/input -d 0 swipe only. Coordinates derive from validated portrait display geometry; no tap, text, Back, caller command or permission request. */
final class AndroidReviewShizukuSwipeBackend implements ReviewShizukuSwipeActor.Backend {
    private static final String REVIEW_PACKAGE = "com.aliothmoon.maafw.productrewardsworking.capturereview";
    private final Context context;
    AndroidReviewShizukuSwipeBackend(Context context) {
        if (context == null) throw new IllegalArgumentException("context/mode");
        this.context = context.getApplicationContext();
        requireReviewProcess();
    }
    private void requireReviewProcess() {
        if (android.os.Build.VERSION.SDK_INT < 34 || android.os.Process.myUid() < 10000
                || !REVIEW_PACKAGE.equals(context.getPackageName())
                || !(REVIEW_PACKAGE + ":capture_once").equals(Application.getProcessName())) {
            throw new SecurityException("Dedicated review capture process required");
        }
    }
    @Override public ReviewShizukuSwipeActor.Session open() throws Exception {
        requireReviewProcess();
        if (Looper.myLooper() == Looper.getMainLooper()) throw new SecurityException("Worker required");
        IBinder binder = Shizuku.getBinder();
        if (binder == null) throw new SecurityException("Shizuku unavailable");
        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        if (service == null) throw new SecurityException("Shizuku service unavailable");
        return new ReviewShizukuSwipeActor.Session() {
            @Override public boolean stillReady() {
                requireReviewProcess();
                if (Shizuku.getBinder() != binder || !binder.isBinderAlive() || !Shizuku.pingBinder()
                        || Shizuku.isPreV11()
                        || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false;
                int uid = Shizuku.getUid();
                return uid==0;
            }
            @Override public ReviewShizukuSwipeActor.DisplayShape display() {
                if (android.os.Build.VERSION.SDK_INT < 30) throw new SecurityException("Window metrics require API30");
                DisplayManager manager = context.getSystemService(DisplayManager.class);
                Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
                if (display == null || display.getState() != Display.STATE_ON)
                    throw new SecurityException("Default display not active");
                WindowManager windows = context.createDisplayContext(display).getSystemService(WindowManager.class);
                if (windows == null) throw new SecurityException("Window metrics unavailable");
                Rect bounds = windows.getMaximumWindowMetrics().getBounds();
                if (bounds.left != 0 || bounds.top != 0) throw new SecurityException("Non-identity display origin");
                return new ReviewShizukuSwipeActor.DisplayShape(bounds.width(), bounds.height(), display.getRotation());
            }
            @Override public ReviewShizukuSwipeActor.PendingSwipe dispatch(ReviewShizukuSwipeActor.SwipeTarget target,
                    ReviewShizukuSwipeActor.FinalGate finalGate) throws Exception {
                // Distinguish a known pre-dispatch rejection from uncertain remote invocation.
                try {
                    if (!stillReady()) throw new ReviewShizukuSwipeActor.DispatchBlocked(ReviewShizukuSwipeActor.Reason.READY_STATE_LOST);
                    ReviewShizukuSwipeActor.DisplayShape actual = display();
                    if (!ReviewShizukuSwipeActor.valid(target) || target.fullDisplayWidth() != actual.width()
                            || target.fullDisplayHeight() != actual.height() || target.rotation() != actual.rotation())
                        throw new ReviewShizukuSwipeActor.DispatchBlocked(ReviewShizukuSwipeActor.Reason.DISPLAY_CHANGED);
                } catch (ReviewShizukuSwipeActor.DispatchBlocked blocked) { throw blocked; }
                catch (Exception unavailable) {
                    throw new ReviewShizukuSwipeActor.DispatchBlocked(ReviewShizukuSwipeActor.Reason.READY_STATE_LOST);
                }
                // Gate ends with fresh local expiry/cancel checks after its source checks.
                // There is no further blocking preflight between it and newProcess.
                finalGate.requireValid();
                // No sh -c, user text, package input, environment or working directory.
                IRemoteProcess process = service.newProcess(new String[]{
                        "/system/bin/input", "-d", "0", "swipe",
                        Integer.toString(target.x()),Integer.toString(target.fromY()),
                        Integer.toString(target.x()),Integer.toString(target.toY()),
                        Integer.toString(ReviewShizukuSwipeActor.DURATION_MILLIS)
                }, null, null);
                return process == null ? null : new OwnSwipeProcess(process);
            }
        };
    }
    private static final class OwnSwipeProcess implements ReviewShizukuSwipeActor.PendingSwipe {
        private final IRemoteProcess process;
        OwnSwipeProcess(IRemoteProcess process) { this.process = process; }
        @Override public boolean waitForExit(long millis) throws Exception {
            return process.waitForTimeout(millis, "MILLISECONDS");
        }
        @Override public int exitCode() throws Exception { return process.exitValue(); }
        @Override public void destroy() throws Exception { process.destroy(); }
        @Override public void close() throws Exception {
            // Close only our duplicated pipe descriptors. Never read command output.
            Exception failure = null;
            try { closeDescriptor(process.getOutputStream()); } catch (Exception e) { failure = e; }
            try { closeDescriptor(process.getInputStream()); } catch (Exception e) { if (failure == null) failure = e; }
            try { closeDescriptor(process.getErrorStream()); } catch (Exception e) { if (failure == null) failure = e; }
            if (failure != null) throw failure;
        }
        private static void closeDescriptor(ParcelFileDescriptor fd) throws IOException {
            if (fd != null) fd.close();
        }
    }
}



