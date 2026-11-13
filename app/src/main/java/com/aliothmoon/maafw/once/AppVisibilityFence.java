package com.aliothmoon.maafw.once;
import java.util.HashMap;
import java.util.Map;
/** Monotonic lifecycle fence, not permission, target provenance, or pixel visibility. */
final class AppVisibilityFence {
 private long epoch;
 private boolean unknown;
 private final Map<Object,Boolean> visible=new HashMap<>();
 private Lease current;
 static final class Lease {
  private final AppVisibilityFence origin; private final long epoch; private final Runnable revoke;
  private Lease(AppVisibilityFence o,long e,Runnable r){origin=o;epoch=e;revoke=r;}
 }
 synchronized void invalidate(){epoch++;Lease old=current;current=null;if(old!=null)old.revoke.run();}
 synchronized void unknown(){unknown=true;invalidate();}
 synchronized void enter(Object activity){invalidate();visible.put(activity,true);}
 synchronized void stopped(Object activity){if(!visible.containsKey(activity)){unknown();return;}visible.put(activity,false);}
 synchronized void destroyed(Object activity){if(!visible.containsKey(activity)){unknown();return;}invalidate();visible.remove(activity);}
 synchronized Lease lease(Runnable revoke){if(unknown||current!=null||visible.isEmpty()||visible.containsValue(true))return null;current=new Lease(this,epoch,revoke);return current;}
 synchronized boolean valid(Lease lease){return lease!=null&&lease==current&&lease.origin==this&&lease.epoch==epoch&&!unknown&&!visible.containsValue(true);}
 synchronized boolean publish(Lease lease,Runnable publish){if(!valid(lease))return false;publish.run();return true;}
}
