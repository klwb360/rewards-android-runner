package com.aliothmoon.maafw.once;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;
/** One bounded memory-only selector diagnostic. No navigation or business authority. */
public final class NativeEntryDiagnosticHelper {
 private static final String BING="com.microsoft.bing";
 private static final long MAX_MS=15000;
 private static final class Stop extends Exception {final NativeEntryDiagnosticProtocol.Reason reason;Stop(NativeEntryDiagnosticProtocol.Reason r){reason=r;}}
 private record Pending(AccessibilityNodeInfo node,int index,int parent,int depth){}
 private record Tree(NativeEntryDiagnosticProtocol.Projection projection,int windowId,Rect rootBounds,long started){}
 private static final class Cleanup {boolean clean=true;void recycle(AccessibilityNodeInfo n){if(n!=null)try{n.recycle();}catch(Throwable ignored){clean=false;}}}
 public static void main(String[] args){
  long started=SystemClock.elapsedRealtime(),acquired=started;NativeRewardsEntryProtocol.Binding b;
  try{if(args==null||args.length!=10)return;b=new NativeRewardsEntryProtocol.Binding(args[0],Long.parseLong(args[1]),Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),Integer.parseInt(args[5]),Integer.parseInt(args[6]),Integer.parseInt(args[7]),Integer.parseInt(args[8]),Integer.parseInt(args[9]));if(b.deadline()<=started||b.deadline()-started>MAX_MS)return;}catch(Throwable ignored){return;}
  NativeEntryDiagnosticProtocol.Reason reason=NativeEntryDiagnosticProtocol.Reason.TREE_FAILED;Cleanup clean=new Cleanup();HandlerThread thread=null;UiAutomation ui=null;boolean connected=false;Tree tree=null;V46EntryMetadataReader metadata=null;
  try{
   if(Process.myUid()!=0)throw new Stop(NativeEntryDiagnosticProtocol.Reason.NOT_ROOT);check(b.deadline());
   thread=new HandlerThread("EntryDiagnostic");thread.start();Class<?> type=Class.forName("android.app.IUiAutomationConnection");Object connection=Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();ui=UiAutomation.class.getConstructor(Looper.class,type).newInstance(thread.getLooper(),connection);
   connected=true;UiAutomation.class.getMethod("connect",int.class).invoke(ui,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);check(b.deadline());
   AccessibilityServiceInfo info=ui.getServiceInfo();if(info==null)throw new Stop(NativeEntryDiagnosticProtocol.Reason.WINDOW_UNAVAILABLE);
   int flags=info.flags|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;info.flags=flags;ui.setServiceInfo(info);AccessibilityServiceInfo confirmed=ui.getServiceInfo();if(confirmed==null||confirmed.flags!=flags)throw new Stop(NativeEntryDiagnosticProtocol.Reason.WINDOW_UNAVAILABLE);
   metadata=V46EntryMetadataReader.rootHelper(new BingNavigationSnapshot.Profile(b.bingUid(),b.systemUiUid(),new BingNavigationSnapshot.Shape(b.width(),b.height(),b.rotation())),new BingNavigationSnapshot.ReadOnlyGuard(b.ownUid(),b.ownTask(),NativeRewardsEntryProtocol.OWN));
   tree=walk(ui,b,clean);var source=metadata.read(V46EntryMetadataReader.Stage.HELPER_PREPARE,b.deadline(),()->SystemClock.elapsedRealtime()<b.deadline());
   if(!metadata.knownClean()||source==null||source.status()!=BingNavigationSnapshot.Status.READY||source.ready()==null||source.ready().kind()!=BingNavigationSnapshot.Kind.MAIN||!source.ready().task().equals(Integer.toString(b.task()))||!source.ready().fresh(SystemClock.elapsedRealtime()))throw new Stop(NativeEntryDiagnosticProtocol.Reason.SOURCE_REJECTED);
   long now=SystemClock.elapsedRealtime();if(now<tree.started()||now-tree.started()>5000||!clean.clean)throw new Stop(NativeEntryDiagnosticProtocol.Reason.TREE_FAILED);
   check(b.deadline());acquired=now;reason=NativeEntryDiagnosticProtocol.Reason.OK;
  }catch(Stop stop){reason=stop.reason;}catch(ClassNotFoundException|NoSuchMethodException e){reason=NativeEntryDiagnosticProtocol.Reason.REFLECTION_UNAVAILABLE;}catch(Throwable ignored){reason=NativeEntryDiagnosticProtocol.Reason.TREE_FAILED;}
  finally{
   if(metadata!=null&&!metadata.knownClean())clean.clean=false;
   if(connected)try{UiAutomation.class.getMethod("disconnect").invoke(ui);}catch(Throwable ignored){clean.clean=false;}
   if(thread!=null)try{thread.quitSafely();thread.join(250);if(thread.isAlive())clean.clean=false;}catch(Throwable ignored){clean.clean=false;}
  }
  long finished=SystemClock.elapsedRealtime();if(finished>=b.deadline())return;
  var cleanup=clean.clean?(connected?NativeEntryDiagnosticProtocol.Cleanup.CONFIRMED:NativeEntryDiagnosticProtocol.Cleanup.NOT_STARTED):NativeEntryDiagnosticProtocol.Cleanup.UNKNOWN;
  if(!clean.clean)reason=NativeEntryDiagnosticProtocol.Reason.CLEANUP_UNKNOWN;
  NativeEntryDiagnosticProtocol.Projection projection=reason==NativeEntryDiagnosticProtocol.Reason.OK?tree.projection():null;
  try{var report=new NativeEntryDiagnosticProtocol.Report(b.nonce(),started,acquired,finished,reason,cleanup,b.task(),projection==null?-1:tree.windowId(),projection==null?-1:0,projection);System.out.println(NativeEntryDiagnosticProtocol.encode(report));System.out.flush();}catch(Throwable ignored){}
 }
 private static boolean eq(CharSequence value,String exact){return value!=null&&value.length()==exact.length()&&exact.contentEquals(value);}
 private static boolean validBounds(AccessibilityNodeInfo n,Rect root){Rect r=new Rect();n.getBoundsInScreen(r);return !r.isEmpty()&&root.contains(r);}
 private static Tree walk(UiAutomation ui,NativeRewardsEntryProtocol.Binding b,Cleanup clean)throws Exception{
  long started=SystemClock.elapsedRealtime();check(b.deadline());AccessibilityNodeInfo root=ui.getRootInActiveWindow();if(root==null)throw new Stop(NativeEntryDiagnosticProtocol.Reason.WINDOW_UNAVAILABLE);
  ArrayDeque<Pending> queue=new ArrayDeque<>();queue.add(new Pending(root,0,-1,0));NativeEntryDiagnosticProtocol.Accumulator accumulator=new NativeEntryDiagnosticProtocol.Accumulator();int window=-1,scheduled=1,chars=0;Rect bounds=new Rect();
  try{
   if(!eq(root.getPackageName(),BING)||!root.isVisibleToUser())throw new Stop(NativeEntryDiagnosticProtocol.Reason.WINDOW_UNAVAILABLE);root.getBoundsInScreen(bounds);window=root.getWindowId();if(window<0||bounds.isEmpty()||bounds.left<0||bounds.top<0||bounds.right>b.width()||bounds.bottom>b.height())throw new Stop(NativeEntryDiagnosticProtocol.Reason.WINDOW_UNAVAILABLE);
   AccessibilityWindowInfo w=root.getWindow();if(w==null)throw new Stop(NativeEntryDiagnosticProtocol.Reason.WINDOW_UNAVAILABLE);try{if(android.os.Build.VERSION.SDK_INT<30||w.getId()!=window||!w.isActive()||w.getDisplayId()!=0)throw new Stop(NativeEntryDiagnosticProtocol.Reason.WINDOW_UNAVAILABLE);}finally{try{w.recycle();}catch(Throwable ignored){clean.clean=false;}}
   while(!queue.isEmpty()){
    check(b.deadline());Pending item=queue.removeFirst();AccessibilityNodeInfo n=item.node();
    try{
     if(!eq(n.getPackageName(),BING)||n.getWindowId()!=window||n.isVisibleToUser()&&!validBounds(n,bounds))throw new Stop(NativeEntryDiagnosticProtocol.Reason.WINDOW_UNAVAILABLE);
     CharSequence text=n.getText(),description=n.getContentDescription();int length=(text==null?0:text.length())+(description==null?0:description.length());if((text!=null&&text.length()>2048)||(description!=null&&description.length()>2048)||chars+length>32768)throw new Stop(NativeEntryDiagnosticProtocol.Reason.TREE_OVERFLOW);chars+=length;
     Rect box=new Rect();n.getBoundsInScreen(box);accumulator.add(item.index(),item.parent(),item.depth(),box.left,box.top,box.right,box.bottom,n.isVisibleToUser(),n.isEnabled(),n.isClickable(),n.getViewIdResourceName(),text,description);
     int count=n.getChildCount();if(count<0||scheduled+count>512||count>0&&item.depth()>=32)throw new Stop(NativeEntryDiagnosticProtocol.Reason.TREE_OVERFLOW);
     for(int i=0;i<count;i++){check(b.deadline());AccessibilityNodeInfo child=n.getChild(i);if(child==null)throw new Stop(NativeEntryDiagnosticProtocol.Reason.TREE_FAILED);queue.add(new Pending(child,scheduled++,item.index(),item.depth()+1));}
    }finally{clean.recycle(n);}
   }
   check(b.deadline());return new Tree(accumulator.finish(),window,new Rect(bounds),started);
  }finally{while(!queue.isEmpty())clean.recycle(queue.removeFirst().node());}
 }
 private static void check(long deadline)throws Stop{if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>=deadline)throw new Stop(NativeEntryDiagnosticProtocol.Reason.TIMEOUT);}
 private NativeEntryDiagnosticHelper(){}
}
