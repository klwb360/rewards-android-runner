package com.aliothmoon.maafw.once;
import android.app.*;
import android.os.*;
import android.net.Uri;
import java.util.*;
/** Review-only lifecycle interception in every manifest process. No native graph. */
public final class ReviewVisibilityApplication extends Application implements Application.ActivityLifecycleCallbacks {
 private final IBinder client=new Binder();
 private final Map<Activity,String> activities=new IdentityHashMap<>();
 private long sequence;
 private void event(String method,Activity activity){
  Bundle b=new Bundle();b.putBinder("client",client);
  if(activity!=null){b.putLong("sequence",++sequence);b.putString("activity",activities.get(activity));}
  // A missing/failed acknowledgement throws before create/start completes. Never continue UI on unknown IPC.
  Bundle reply=getContentResolver().call(Uri.parse("content://"+getPackageName()+".visibility"),method,null,b);
  if(reply==null||!reply.getBoolean("ack"))throw new IllegalStateException("Visibility coordination unavailable");
 }
 @Override public void onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=29 && (getProcessName().equals(getPackageName()) || getProcessName().equals(getPackageName()+":capture_once"))){event("register",null);registerActivityLifecycleCallbacks(this);}}
 @Override public void onActivityPreCreated(Activity a,Bundle state){activities.put(a,UUID.randomUUID().toString());event("create",a);}
 @Override public void onActivityPreStarted(Activity a){event("start",a);}
 @Override public void onActivityPostStopped(Activity a){event("stop",a);if(a instanceof DormantCaptureConfirmationActivity c)c.visibilityStopped();}
 @Override public void onActivityPostDestroyed(Activity a){event("destroy",a);activities.remove(a);}
 @Override public void onActivityCreated(Activity a,Bundle b){}
 @Override public void onActivityStarted(Activity a){}
 @Override public void onActivityResumed(Activity a){}
 @Override public void onActivityPaused(Activity a){}
 @Override public void onActivityStopped(Activity a){}
 @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
 @Override public void onActivityDestroyed(Activity a){}
}
