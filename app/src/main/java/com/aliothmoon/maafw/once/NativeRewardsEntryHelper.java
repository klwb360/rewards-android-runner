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
import java.util.ArrayList;
import java.util.List;
/** Fixed one-entry navigation. No screenshot, counter parser, task action or generic command. */
public final class NativeRewardsEntryHelper {
 private static final String BING="com.microsoft.bing",RESOURCE="com.microsoft.bing:id/glance_card_container";
 private static final long MAX_MS=30000;
 private static final class Stop extends Exception {final NativeRewardsEntryProtocol.Reason reason;Stop(NativeRewardsEntryProtocol.Reason r){reason=r;}}
 private record Pending(AccessibilityNodeInfo node,int index,int depth){}
 private record Tree(AccessibilityNodeInfo candidate,int candidateIndex,int nodes,int windowId,Rect rootBounds,long started,AccessibilityNodeInfo semantic,Rect clickBounds,AccessibilityNodeInfo[] region){
  Tree(AccessibilityNodeInfo candidate,int candidateIndex,int nodes,int windowId,Rect rootBounds,long started,AccessibilityNodeInfo semantic,Rect clickBounds){this(candidate,candidateIndex,nodes,windowId,rootBounds,started,semantic,clickBounds,null);}
  Tree(AccessibilityNodeInfo candidate,int candidateIndex,int nodes,int windowId,Rect rootBounds,long started){this(candidate,candidateIndex,nodes,windowId,rootBounds,started,null,null);}
 }
 private static final class Cleanup {boolean clean=true;void recycle(AccessibilityNodeInfo n){if(n!=null)try{n.recycle();}catch(Throwable ignored){clean=false;}}}
 private static final class Input {volatile boolean done;volatile int length;final byte[] bytes=new byte[80];volatile boolean failed;}
 public static void main(String[] args){
  long start=SystemClock.elapsedRealtime();NativeRewardsEntryProtocol.Binding b;boolean parentMode,showMode;
  try{if(args==null||!validModeArguments(args))return;parentMode=args.length==11&&"PARENT_REWARDS_V48".equals(args[10]);showMode=args.length==11&&"SHOW_REGION_V52".equals(args[10]);b=new NativeRewardsEntryProtocol.Binding(args[0],Long.parseLong(args[1]),Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]),Integer.parseInt(args[5]),Integer.parseInt(args[6]),Integer.parseInt(args[7]),Integer.parseInt(args[8]),Integer.parseInt(args[9]));if(b.deadline()<=start||b.deadline()-start>MAX_MS)return;}catch(Throwable ignored){return;}
  NativeRewardsEntryProtocol.Reason reason=NativeRewardsEntryProtocol.Reason.TREE_FAILED;Cleanup clean=new Cleanup();HandlerThread thread=null;UiAutomation ui=null;boolean connected=false,attempted=false,returned=false;long prepared=-1,actionFinished=-1;Tree first=null,second=null;Thread inputThread=null;V46EntryMetadataReader metadata=null;
  try{
   if(Process.myUid()!=0)throw new Stop(NativeRewardsEntryProtocol.Reason.NOT_ROOT);check(b.deadline());
   thread=new HandlerThread("RewardsEntry");thread.start();Class<?> type=Class.forName("android.app.IUiAutomationConnection");Object connection=Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();ui=UiAutomation.class.getConstructor(Looper.class,type).newInstance(thread.getLooper(),connection);
   connected=true;UiAutomation.class.getMethod("connect",int.class).invoke(ui,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);check(b.deadline());
   AccessibilityServiceInfo info=ui.getServiceInfo();if(info==null)throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
   int flags=info.flags|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;info.flags=flags;ui.setServiceInfo(info);AccessibilityServiceInfo confirmed=ui.getServiceInfo();if(confirmed==null||confirmed.flags!=flags)throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
   metadata=V46EntryMetadataReader.rootHelper(new BingNavigationSnapshot.Profile(b.bingUid(),b.systemUiUid(),new BingNavigationSnapshot.Shape(b.width(),b.height(),b.rotation())),new BingNavigationSnapshot.ReadOnlyGuard(b.ownUid(),b.ownTask(),NativeRewardsEntryProtocol.OWN));
   first=walkMode(ui,b,clean,parentMode,showMode);var before=metadata.read(V46EntryMetadataReader.Stage.HELPER_PREPARE,b.deadline(),()->SystemClock.elapsedRealtime()<b.deadline());requireSource(before,b,metadata,showMode);check(b.deadline());
   if(!clean.clean||!freshTree(first))throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_FAILED);
   prepared=SystemClock.elapsedRealtime();var preparedRecord=new NativeRewardsEntryProtocol.Prepared(b.nonce(),b.task(),0,first.windowId(),prepared,first.nodes(),first.candidateIndex());System.out.println(showMode?NativeRewardsEntryProtocol.encodeTemplatePrepared(preparedRecord):NativeRewardsEntryProtocol.encode(preparedRecord));System.out.flush();if(System.out.checkError())throw new Stop(NativeRewardsEntryProtocol.Reason.COMMIT_REJECTED);
   Input input=new Input();inputThread=new Thread(()->{try{int n;while((n=System.in.read())!=-1){if(input.length>=input.bytes.length){input.failed=true;break;}input.bytes[input.length++]=(byte)n;}}catch(Throwable ignored){input.failed=true;}finally{input.done=true;}},"entry-commit");inputThread.setDaemon(true);inputThread.start();
   while(!input.done){check(b.deadline());inputThread.join(Math.min(20,Math.max(1,b.deadline()-SystemClock.elapsedRealtime())));}check(b.deadline());
   boolean valid=!input.failed&&NativeRewardsEntryProtocol.validCommit(input.bytes,input.length,b.nonce());java.util.Arrays.fill(input.bytes,(byte)0);if(!valid)throw new Stop(NativeRewardsEntryProtocol.Reason.COMMIT_REJECTED);
   // A fresh full traversal after complete COMMIT+EOF, never selecting a replacement node.
   second=walkMode(ui,b,clean,parentMode,showMode);if(first.windowId()!=second.windowId()||!first.rootBounds().equals(second.rootBounds())||!first.candidate().equals(second.candidate())||showMode&&!sameRegion(first,second)||parentMode&&(!first.semantic().equals(second.semantic())||!first.clickBounds().equals(second.clickBounds())))throw new Stop(NativeRewardsEntryProtocol.Reason.ENTRY_NOT_UNIQUE);
   var after=metadata.read(V46EntryMetadataReader.Stage.HELPER_COMMIT,b.deadline(),()->SystemClock.elapsedRealtime()<b.deadline());requireSource(after,b,metadata,showMode);if(!before.ready().sameEntrySource(after.ready()))throw new Stop(NativeRewardsEntryProtocol.Reason.SOURCE_REJECTED);
   if(!second.candidate().refresh()||!(showMode?refreshRegion(second,clean):parentMode?refreshParentPair(second,clean):selector(second.candidate()))||second.candidate().getWindowId()!=second.windowId()||!showMode&&!validBounds(second.candidate(),second.rootBounds())||!freshTree(second)||!clean.clean)throw new Stop(NativeRewardsEntryProtocol.Reason.ENTRY_NOT_UNIQUE);
   verifyActiveWindow(second.candidate(),second.windowId(),clean);
   if(!clean.clean)throw new Stop(NativeRewardsEntryProtocol.Reason.CLEANUP_UNKNOWN);
   if(!after.ready().fresh(SystemClock.elapsedRealtime()))throw new Stop(NativeRewardsEntryProtocol.Reason.SOURCE_REJECTED);
   if(!freshTree(second))throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_FAILED);
   check(b.deadline());attempted=true;try{if(showMode)returned=second.candidate().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());else returned=second.candidate().performAction(AccessibilityNodeInfo.ACTION_CLICK);}finally{actionFinished=SystemClock.elapsedRealtime();}
   check(b.deadline());reason=returned?NativeRewardsEntryProtocol.Reason.OK:NativeRewardsEntryProtocol.Reason.ACTION_FALSE;
  }catch(Stop stop){reason=stop.reason;}catch(ClassNotFoundException|NoSuchMethodException e){reason=NativeRewardsEntryProtocol.Reason.REFLECTION_UNAVAILABLE;}catch(Throwable ignored){reason=NativeRewardsEntryProtocol.Reason.TREE_FAILED;}
  finally{
   if(first!=null){recycleTree(first,clean);}if(second!=null){recycleTree(second,clean);}
   try{System.in.close();}catch(Throwable ignored){clean.clean=false;}
   if(inputThread!=null)try{inputThread.join(100);if(inputThread.isAlive())clean.clean=false;}catch(Throwable ignored){clean.clean=false;}
   if(metadata!=null&&!metadata.knownClean())clean.clean=false;
   if(connected)try{UiAutomation.class.getMethod("disconnect").invoke(ui);}catch(Throwable ignored){clean.clean=false;}
   if(thread!=null)try{thread.quitSafely();thread.join(250);if(thread.isAlive())clean.clean=false;}catch(Throwable ignored){clean.clean=false;}
  }
  long finished=SystemClock.elapsedRealtime();if(finished>=b.deadline())return;
  var cleanup=clean.clean?(connected?NativeRewardsEntryProtocol.Cleanup.CONFIRMED:NativeRewardsEntryProtocol.Cleanup.NOT_STARTED):NativeRewardsEntryProtocol.Cleanup.UNKNOWN;
  if(!clean.clean)reason=NativeRewardsEntryProtocol.Reason.CLEANUP_UNKNOWN;
  try{System.out.println(NativeRewardsEntryProtocol.encode(new NativeRewardsEntryProtocol.Result(b.nonce(),b.task(),reason,cleanup,attempted,returned,prepared,actionFinished,finished)));System.out.flush();}catch(Throwable ignored){}
 }
 private static void requireSource(BingNavigationSnapshot.Result r,NativeRewardsEntryProtocol.Binding b,V46EntryMetadataReader m,boolean showMode)throws Stop{
  if(!m.knownClean()||r==null||r.status()!=BingNavigationSnapshot.Status.READY||r.ready()==null||r.ready().kind()!=(showMode?BingNavigationSnapshot.Kind.TEMPLATE:BingNavigationSnapshot.Kind.MAIN)||!r.ready().task().equals(Integer.toString(b.task()))||!r.ready().fresh(SystemClock.elapsedRealtime()))throw new Stop(NativeRewardsEntryProtocol.Reason.SOURCE_REJECTED);
 }
 private static boolean freshTree(Tree t){long now=SystemClock.elapsedRealtime();return now>=t.started()&&now-t.started()<=5000;}
 private static boolean eq(CharSequence value,String exact){return value!=null&&value.length()==exact.length()&&exact.contentEquals(value);}
 private static boolean selector(AccessibilityNodeInfo n){return NativeRewardsEntryProtocol.eligible(n.getPackageName(),n.getViewIdResourceName(),n.getContentDescription(),n.isVisibleToUser(),n.isEnabled(),n.isClickable());}
 private static boolean validBounds(AccessibilityNodeInfo n,Rect root){Rect r=new Rect();n.getBoundsInScreen(r);return !r.isEmpty()&&root.contains(r);}
 static boolean validModeArguments(String[] args){return args!=null&&(args.length==10||args.length==11&&("PARENT_REWARDS_V48".equals(args[10])||"SHOW_REGION_V52".equals(args[10])));}
 private static Tree walkMode(UiAutomation ui,NativeRewardsEntryProtocol.Binding b,Cleanup clean,boolean parentMode,boolean showMode)throws Exception{return showMode?walkRegion(ui,b,clean):walk(ui,b,clean,parentMode);}
 static int regionResource(String id){if(id==null)return -1;return switch(id){case "homePageWrapper"->0;case "dailyTaskPanel"->1;case "dailyActivities"->2;case "search"->3;case "read"->4;default->-1;};}
 static boolean uniqueRegionCounts(int[] counts){if(counts==null||counts.length!=5)return false;for(int count:counts)if(count!=1)return false;return true;}
 private static void recycleTree(Tree t,Cleanup clean){if(t.region()!=null){for(var n:t.region())clean.recycle(n);}else{clean.recycle(t.candidate());clean.recycle(t.semantic());}}
 private static boolean sameRegion(Tree a,Tree b){if(a.region()==null||b.region()==null)return false;for(int i=0;i<5;i++)if(!a.region()[i].equals(b.region()[i]))return false;return true;}
 private static boolean advertisedShow(AccessibilityNodeInfo n){return n.getActionList()!=null&&n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN);}
 private static boolean regionRelations(AccessibilityNodeInfo[] chain,int window,Cleanup clean,boolean refresh){
  for(int i=0;i<5;i++){
   AccessibilityNodeInfo n=chain[i];if(n==null||refresh&&!n.refresh()||!eq(n.getPackageName(),BING)||n.getWindowId()!=window||regionResource(n.getViewIdResourceName())!=i)return false;
   if(i>0){AccessibilityNodeInfo parent=null;try{parent=n.getParent();if(parent==null||!parent.equals(chain[i<3?i-1:2]))return false;}finally{clean.recycle(parent);}}
  }
  return chain[4].isEnabled()&&advertisedShow(chain[4]);
 }
 private static boolean refreshRegion(Tree t,Cleanup clean){return regionRelations(t.region(),t.windowId(),clean,true);}
 private static Tree walkRegion(UiAutomation ui,NativeRewardsEntryProtocol.Binding b,Cleanup clean)throws Exception{
  long started=SystemClock.elapsedRealtime();check(b.deadline());AccessibilityNodeInfo root=ui.getRootInActiveWindow();if(root==null)throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
  ArrayDeque<Pending> queue=new ArrayDeque<>();queue.add(new Pending(root,0,0));AccessibilityNodeInfo[] chain=new AccessibilityNodeInfo[5];int[] counts=new int[5];int nodes=0,index=-1,scheduled=1,chars=0,window=-1;Rect bounds=new Rect();boolean success=false;
  try{
   if(!eq(root.getPackageName(),BING)||!root.isVisibleToUser())throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);root.getBoundsInScreen(bounds);window=root.getWindowId();if(window<0||bounds.isEmpty()||bounds.left<0||bounds.top<0||bounds.right>b.width()||bounds.bottom>b.height())throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);verifyActiveWindow(root,window,clean);
   while(!queue.isEmpty()){
    check(b.deadline());Pending item=queue.removeFirst();AccessibilityNodeInfo n=item.node();boolean keep=false;
    try{
     if(!eq(n.getPackageName(),BING)||n.getWindowId()!=window)throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
     int resource=regionResource(n.getViewIdResourceName());
     // Hidden or zero-height READ containers are admissible only for SHOW_ON_SCREEN, never click eligibility.
     if(n.isVisibleToUser()&&resource!=4&&!validBounds(n,bounds))throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
     CharSequence text=n.getText(),description=n.getContentDescription();int length=(text==null?0:text.length())+(description==null?0:description.length());if((text!=null&&text.length()>2048)||(description!=null&&description.length()>2048)||chars+length>32768)throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_OVERFLOW);chars+=length;
     if(resource>=0){counts[resource]++;if(counts[resource]==1){chain[resource]=n;keep=true;if(resource==4)index=item.index();}}
     nodes++;int count=n.getChildCount();if(count<0||scheduled+count>512||count>0&&item.depth()>=32)throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_OVERFLOW);
     for(int i=0;i<count;i++){check(b.deadline());AccessibilityNodeInfo child=n.getChild(i);if(child==null)throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_FAILED);queue.add(new Pending(child,scheduled++,item.depth()+1));}
    }finally{if(!keep)clean.recycle(n);}
   }
   if(!uniqueRegionCounts(counts)||!regionRelations(chain,window,clean,false))throw new Stop(NativeRewardsEntryProtocol.Reason.ENTRY_NOT_UNIQUE);
   check(b.deadline());success=true;return new Tree(chain[4],index,nodes,window,new Rect(bounds),started,null,null,chain);
  }finally{while(!queue.isEmpty())clean.recycle(queue.removeFirst().node());if(!success)for(var n:chain)clean.recycle(n);}
 }
 private static Tree walk(UiAutomation ui,NativeRewardsEntryProtocol.Binding b,Cleanup clean,boolean parentMode)throws Exception{return parentMode?walkParent(ui,b,clean):walk(ui,b,clean);}
 static boolean semanticLabel(CharSequence pkg,CharSequence description){return eq(pkg,BING)&&eq(description,"Rewards");}
 static boolean emptyField(CharSequence value){return value==null||value.length()==0;}
 static boolean semanticMarker(CharSequence pkg,CharSequence text,CharSequence description){return semanticLabel(pkg,text)||semanticLabel(pkg,description);}
 static boolean pairFlags(boolean childVisible,boolean childEnabled,boolean childClickable,boolean parentVisible,boolean parentEnabled,boolean parentClickable,boolean sameBounds){return childVisible&&childEnabled&&!childClickable&&parentVisible&&parentEnabled&&parentClickable&&sameBounds;}
 private static boolean parentPair(AccessibilityNodeInfo child,AccessibilityNodeInfo parent,int window,Rect root,Rect expected){
  if(child==null||parent==null||!semanticLabel(child.getPackageName(),child.getContentDescription())||!emptyField(child.getText())||!emptyField(parent.getText())||!emptyField(parent.getContentDescription())||!eq(parent.getPackageName(),BING)||child.getWindowId()!=window||parent.getWindowId()!=window)return false;
  Rect c=new Rect(),p=new Rect();child.getBoundsInScreen(c);parent.getBoundsInScreen(p);
  return !c.isEmpty()&&root.contains(c)&&(expected==null||expected.equals(c))&&pairFlags(child.isVisibleToUser(),child.isEnabled(),child.isClickable(),parent.isVisibleToUser(),parent.isEnabled(),parent.isClickable(),c.equals(p));
 }
 private static boolean refreshParentPair(Tree tree,Cleanup clean){
  AccessibilityNodeInfo actual=null;try{
   if(!tree.semantic().refresh())return false;actual=tree.semantic().getParent();
   return actual!=null&&actual.equals(tree.candidate())&&parentPair(tree.semantic(),tree.candidate(),tree.windowId(),tree.rootBounds(),tree.clickBounds());
  }finally{clean.recycle(actual);}
 }
 private static Tree walkParent(UiAutomation ui,NativeRewardsEntryProtocol.Binding b,Cleanup clean)throws Exception{
  long started=SystemClock.elapsedRealtime();check(b.deadline());AccessibilityNodeInfo root=ui.getRootInActiveWindow();if(root==null)throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
  ArrayDeque<Pending> queue=new ArrayDeque<>();queue.add(new Pending(root,0,0));AccessibilityNodeInfo semantic=null,parent=null;int semanticCount=0,nodes=0,index=-1,scheduled=1,chars=0,window=-1;Rect bounds=new Rect(),clickBounds=null;boolean success=false;
  try{
   if(!eq(root.getPackageName(),BING)||!root.isVisibleToUser())throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);root.getBoundsInScreen(bounds);window=root.getWindowId();if(window<0||bounds.isEmpty()||bounds.left<0||bounds.top<0||bounds.right>b.width()||bounds.bottom>b.height())throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);verifyActiveWindow(root,window,clean);
   while(!queue.isEmpty()){
    check(b.deadline());Pending item=queue.removeFirst();AccessibilityNodeInfo node=item.node();boolean keep=false;
    try{
     if(!eq(node.getPackageName(),BING)||node.getWindowId()!=window||node.isVisibleToUser()&&!validBounds(node,bounds))throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
     CharSequence text=node.getText(),description=node.getContentDescription();int length=(text==null?0:text.length())+(description==null?0:description.length());if((text!=null&&text.length()>2048)||(description!=null&&description.length()>2048)||chars+length>32768)throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_OVERFLOW);chars+=length;
     // Count every exact semantic label before parent compatibility, never truncate ambiguity away.
     if(semanticMarker(node.getPackageName(),text,description)){
      semanticCount++;
      if(semanticCount==1){semantic=node;keep=true;index=item.index();parent=node.getParent();if(parentPair(node,parent,window,bounds,null)){clickBounds=new Rect();node.getBoundsInScreen(clickBounds);}}
     }
     nodes++;int count=node.getChildCount();if(count<0||scheduled+count>512||count>0&&item.depth()>=32)throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_OVERFLOW);
     for(int i=0;i<count;i++){check(b.deadline());AccessibilityNodeInfo child=node.getChild(i);if(child==null)throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_FAILED);queue.add(new Pending(child,scheduled++,item.depth()+1));}
    }finally{if(!keep)clean.recycle(node);}
   }
   if(semanticCount!=1||clickBounds==null||!parentPair(semantic,parent,window,bounds,clickBounds))throw new Stop(NativeRewardsEntryProtocol.Reason.ENTRY_NOT_UNIQUE);
   check(b.deadline());success=true;return new Tree(parent,index,nodes,window,new Rect(bounds),started,semantic,clickBounds);
  }finally{while(!queue.isEmpty())clean.recycle(queue.removeFirst().node());if(!success){clean.recycle(semantic);clean.recycle(parent);}}
 }
 private static Tree walk(UiAutomation ui,NativeRewardsEntryProtocol.Binding b,Cleanup clean)throws Exception{
  long started=SystemClock.elapsedRealtime();check(b.deadline());AccessibilityNodeInfo root=ui.getRootInActiveWindow();if(root==null)throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
  ArrayDeque<Pending> queue=new ArrayDeque<>();queue.add(new Pending(root,0,0));List<AccessibilityNodeInfo> candidates=new ArrayList<>();int nodes=0,index=-1,window=-1,scheduled=1,chars=0;Rect bounds=new Rect();boolean success=false;
  try{
   if(!eq(root.getPackageName(),BING)||!root.isVisibleToUser())throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);root.getBoundsInScreen(bounds);window=root.getWindowId();if(window<0||bounds.isEmpty()||bounds.left<0||bounds.top<0||bounds.right>b.width()||bounds.bottom>b.height())throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
   AccessibilityWindowInfo w=root.getWindow();if(w==null)throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);try{if(android.os.Build.VERSION.SDK_INT<30||w.getId()!=window||!w.isActive()||w.getDisplayId()!=0)throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);}finally{try{w.recycle();}catch(Throwable ignored){clean.clean=false;}}
   while(!queue.isEmpty()){
    check(b.deadline());Pending item=queue.removeFirst();AccessibilityNodeInfo n=item.node();boolean keep=false;
    try{
     if(!eq(n.getPackageName(),BING)||n.getWindowId()!=window||n.isVisibleToUser()&&!validBounds(n,bounds))throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
     CharSequence text=n.getText(),description=n.getContentDescription();int length=(text==null?0:text.length())+(description==null?0:description.length());if((text!=null&&text.length()>2048)||(description!=null&&description.length()>2048)||chars+length>32768)throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_OVERFLOW);chars+=length;
     if(selector(n)){candidates.add(n);keep=true;index=item.index();}
     nodes++;int count=n.getChildCount();if(count<0||scheduled+count>512||count>0&&item.depth()>=32)throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_OVERFLOW);
     for(int i=0;i<count;i++){check(b.deadline());AccessibilityNodeInfo child=n.getChild(i);if(child==null)throw new Stop(NativeRewardsEntryProtocol.Reason.TREE_FAILED);queue.add(new Pending(child,scheduled++,item.depth()+1));}
    }finally{if(!keep)clean.recycle(n);}
   }
   if(candidates.size()!=1)throw new Stop(NativeRewardsEntryProtocol.Reason.ENTRY_NOT_UNIQUE);check(b.deadline());success=true;return new Tree(candidates.get(0),index,nodes,window,new Rect(bounds),started);
  }finally{while(!queue.isEmpty())clean.recycle(queue.removeFirst().node());if(!success)for(var n:candidates)clean.recycle(n);}
 }
 private static void verifyActiveWindow(AccessibilityNodeInfo node,int id,Cleanup clean)throws Stop{
  AccessibilityWindowInfo w=node.getWindow();if(w==null)throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);
  try{if(android.os.Build.VERSION.SDK_INT<30||w.getId()!=id||!w.isActive()||w.getDisplayId()!=0)throw new Stop(NativeRewardsEntryProtocol.Reason.WINDOW_UNAVAILABLE);}finally{try{w.recycle();}catch(Throwable ignored){clean.clean=false;}}
 }
 private static void check(long deadline)throws Stop{if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>=deadline)throw new Stop(NativeRewardsEntryProtocol.Reason.TIMEOUT);}
 private NativeRewardsEntryHelper(){}
}
