package com.aliothmoon.maafw.once;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Looper;
import java.util.List;

/** Own-task navigation evidence only. Never a live proof, target identity or pixel ACK.
 * One helper per exact Activity instance; no serialization or cross-process state. */
public final class OwnTaskHandoff {
    private final Activity activity;
    private final State state=new State();
    private boolean resumed;
    private Runnable ready=()->{}, revoked=()->{};
    public OwnTaskHandoff(Activity activity){this.activity=java.util.Objects.requireNonNull(activity);}
    static boolean shape(int count,int ownId,int taskId,int activities,boolean root,boolean base,boolean top){
        return count==1 && ownId>=0 && ownId==taskId && activities==1 && root && base && top;
    }
    public static boolean soleRoot(Activity activity){
        if(android.os.Build.VERSION.SDK_INT<34)return false; // Same platform floor as capture handoff.
        try {
            ActivityManager manager=activity.getSystemService(ActivityManager.class);
            List<ActivityManager.AppTask> tasks=manager.getAppTasks();
            if(tasks.size()!=1)return false;
            ActivityManager.RecentTaskInfo t=tasks.get(0).getTaskInfo();
            android.content.ComponentName exact=activity.getComponentName();
            return shape(tasks.size(),activity.getTaskId(),t.taskId,t.numActivities,activity.isTaskRoot(),
                exact.equals(t.baseActivity),exact.equals(t.topActivity));
        } catch(RuntimeException unavailable){return false;}
    }
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main lifecycle only");}
    public void onResume(){main();resumed=true;}
    public void onPause(){main();resumed=false;}
    public void onStart(){main();if(state.start())revoked.run();}
    public void onStop(){main();if(state.stop())deliver();}
    public void onDestroy(){main();resumed=false;if(state.revoke())revoked.run();}
    public void request(Runnable onReady,Runnable onRevoked){
        main();ready=java.util.Objects.requireNonNull(onReady);revoked=java.util.Objects.requireNonNull(onRevoked);
        if(!resumed || !activity.hasWindowFocus() || activity.isFinishing() || activity.isDestroyed()
                || !soleRoot(activity) || !state.arm())throw new IllegalStateException("Visible sole own-task root required");
        boolean moved;
        try {moved=activity.moveTaskToBack(true);}catch(RuntimeException failure){state.revoke();revoked.run();throw failure;}
        // Return alone does not open the gate. Both receipts belong to this instance.
        if(!moved){state.revoke();revoked.run();throw new IllegalStateException("Task move rejected");}
        if(state.moved())deliver();
    }
    private void deliver(){if(!soleRoot(activity)){state.revoke();revoked.run();return;}ready.run();}
    public boolean hidden(){return state.hidden();}
    public boolean hiddenWithSoleTask(){return hidden() && soleRoot(activity);}
    /** Pure scalar event model, also used by the actual helper. Pause is insufficient. */
    static final class State {
        private boolean started, armed, moved, stopped, delivered, invalid;
        synchronized boolean start(){started=true;return (armed||delivered)&&revoke();}
        synchronized boolean arm(){if(invalid||armed||!started)return false;armed=true;return true;}
        synchronized boolean moved(){moved=true;return deliver();}
        synchronized boolean stop(){started=false;if(armed)stopped=true;return deliver();}
        private boolean deliver(){if(armed&&moved&&stopped&&!started&&!invalid&&!delivered){delivered=true;return true;}return false;}
        synchronized boolean revoke(){boolean notify=!invalid;invalid=true;return notify;}
        synchronized boolean hidden(){return delivered&&!started&&!invalid;}
    }
}
