package com.aliothmoon.maafw.once;

import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.util.Arrays;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/** Reads one signed helper response into memory. A complete release receipt is required. */
final class AndroidNewsTreeReader {
    private static final String HELPER = "com.aliothmoon.maafw.once.NativeUiStructureHelper";
    private static volatile Object quarantine;
    static boolean knownClean() { return quarantine == null; }

    static NewsTreeWire.Tree read(Context context, NewsAndroidPort.TreeGrant grant, boolean article) throws Exception {
        if (grant == null || !grant.current() || !knownClean() || !AndroidLiveUiReader.knownClean()
                || Build.VERSION.SDK_INT < 34 || !AndroidLiveUiReader.authority(context))
            throw new SecurityException("NEWS_TREE_AUTHORITY");
        String apk = AndroidLiveUiReader.verifiedApk(context);
        var binder = Shizuku.getBinder();
        if (apk == null || binder == null || !binder.isBinderAlive()) throw new SecurityException("NEWS_TREE_BINDER");
        var service = IShizukuService.Stub.asInterface(binder);
        if (service == null) throw new SecurityException("NEWS_TREE_SERVICE");
        String[] command = {"/system/bin/app_process","-cp",apk,"/system/bin",HELPER,
                grant.nonce,Long.toString(grant.deadline),article?NewsTreeWire.ARTICLE_ARGUMENT:"READING_NEWS_TREE_V1"};
        var stream = new LiveUiReadStream();
        byte[] bytes = new byte[NewsTreeWire.MAX_WIRE + 1];
        IRemoteProcess child = null;
        ParcelFileDescriptor out = null, in = null, err = null;
        boolean invoked = false, adopted = false;
        try {
            if (!AndroidLiveUiReader.authority(context)) throw new SecurityException("NEWS_TREE_AUTHORITY");
            grant.consume();
            long dispatch = SystemClock.elapsedRealtime();
            if (!grant.current() || !binder.isBinderAlive() || binder != Shizuku.getBinder())
                throw new SecurityException("NEWS_TREE_STALE");
            invoked = true;
            child = service.newProcess(command,null,null);
            if (child == null) throw new IOException("NEWS_TREE_CHILD");
            out = child.getInputStream(); in = child.getOutputStream(); err = child.getErrorStream();
            adopted = out != null && in != null && err != null;
            if (!adopted) throw new IOException("NEWS_TREE_STREAMS");
            in.close(); in = null;
            err.close(); err = null;
            int flags = Os.fcntlInt(out.getFileDescriptor(),OsConstants.F_GETFL,0);
            Os.fcntlInt(out.getFileDescriptor(),OsConstants.F_SETFL,flags | OsConstants.O_NONBLOCK);
            var pipe = out;
            var process = child;
            var status = stream.drain(bytes,grant.deadline,SystemClock::elapsedRealtime,grant::current,
                    (buffer,offset,length)->AndroidCardRegionCapture.readPipe(pipe,buffer,offset,length),
                    new LiveCropStream.ProcessExit(){
                        public boolean quiet() throws Exception { return process.waitForTimeout(0,"MILLISECONDS"); }
                        public int exitValue() throws Exception { return process.exitValue(); }
                    },Thread::sleep);
            if (status != null) throw new IOException("NEWS_TREE_" + status.name());
            long now=SystemClock.elapsedRealtime();
            var tree = article ? NewsTreeWire.parseArticle(bytes,stream.count,grant.nonce,dispatch,grant.deadline,now)
                    : NewsTreeWire.parse(bytes,stream.count,grant.nonce,dispatch,grant.deadline,now);
            stream.frameCleanup = tree.source().cleanup();
            stream.helperReason = tree.source().reason();
            stream.completeReceipt = true;
            if (!tree.source().qualified()) throw new SecurityException("NEWS_TREE_" + stream.helperReason.name());
            if (stream.currentLost || !grant.current() || !AndroidLiveUiReader.authority(context))
                throw new SecurityException("NEWS_TREE_STALE");
            return tree;
        } finally {
            Arrays.fill(bytes,(byte)0);
            boolean closed = close(out) & close(in) & close(err);
            if (invoked && !stream.released(adopted && closed)) {
                try { if (child != null && !stream.quiet) { child.destroy(); child.waitForTimeout(500,"MILLISECONDS"); } }
                catch (Exception ignored) { /* Retain ownership as uncertain. */ }
                quarantine = new Object[]{child,out,in,err};
            }
        }
    }

    private static boolean close(ParcelFileDescriptor fd) {
        try { if (fd != null) fd.close(); return true; }
        catch (IOException failure) { return false; }
    }

    private AndroidNewsTreeReader() {}
}
