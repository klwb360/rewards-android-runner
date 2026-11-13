package com.aliothmoon.maafw.once;
import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import com.sun.jna.*;
import java.io.*;
import java.util.concurrent.atomic.*;

/** Process-global sinks for an isolated normal-UID worker only. No drain thread,
 * frame/result read, owner callback or graph reference. Does not suppress OS
 * tombstones, dumps or promise forensic/system-wide zero retention. Kept installed
 * for the process lifetime; not part of any individual recognition owner graph. */
final class MaaOnceNativePrivacy {
    public interface Logger extends Callback { void invoke(Pointer message); }
    public interface LibLog extends Library {
        void __android_log_set_logger(Logger callback);
        int __android_log_write(int priority,String tag,String message);
    }
    private static final AtomicInteger testTid=new AtomicInteger(-1);
    private static final AtomicBoolean seen=new AtomicBoolean();
    private static final Logger DROP=pointer->{if(testTid.get()==android.os.Process.myTid())seen.set(true);};
    private static boolean attempted, installed;
    private static LibLog log;
    static synchronized void install(Context context)throws Exception {
        if(attempted){check();return;}
        attempted=true;
        // Write-only discard, not a pipe or collector. No asynchronous owner child.
        FileDescriptor sink=Os.open("/dev/null",OsConstants.O_WRONLY,0);
        try { Os.dup2(sink,1); Os.dup2(sink,2); } finally { Os.close(sink); }
        System.setProperty("jna.nounpack","true");
        System.setProperty("jna.nosys","true");
        System.setProperty("jna.boot.library.path",context.getApplicationInfo().nativeLibraryDir);
        log=Native.load("/system/lib64/liblog.so",LibLog.class);
        log.__android_log_set_logger(DROP);
        installed=true;
        check();
    }
    static synchronized void check()throws IOException {
        if(!installed || log==null)throw new IOException();
        seen.set(false);testTid.set(android.os.Process.myTid());
        try {
            int result=log.__android_log_write(6,"MaaOnceCanary","SYNTHETIC_LOG_SINK_CANARY_V1");
            if(result<0 || !seen.get())throw new IOException();
        } finally {testTid.set(-1);}
    }
}
