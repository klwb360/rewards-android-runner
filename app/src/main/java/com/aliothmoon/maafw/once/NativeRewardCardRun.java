package com.aliothmoon.maafw.once;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
/** Android admission/source wrapper around the pure one-card session. No arbitrary input or navigation. */
final class NativeRewardCardRun {
 private static final AtomicBoolean CREATED=new AtomicBoolean();
 private final Context context;
 private final Path files;
 private final long expires;
 private final String runId=UUID.randomUUID().toString();
 private final AtomicBoolean revoked=new AtomicBoolean();
 private final boolean[] reserved=new boolean[2];
 private final BingIdentityEvidence.Installed installed;
 private final NativeRewardCardSession session;
 private BingForegroundSnapshot.Snapshot foreground;
 private NativeRewardCardRun(DormantCaptureConfirmationActivity activity){
  context=activity.getApplicationContext();files=activity.getNoBackupFilesDir().toPath();
  long now=SystemClock.elapsedRealtime(),remaining=NativeRewardCardScope.EXPIRES_UTC-System.currentTimeMillis();
  if(remaining<=0)throw new SecurityException("Expired card scope");
  expires=now+Math.min(NativeRewardCardScope.SESSION_TTL,remaining);
  installed=BingPackageMetadata.read(context.getPackageManager());
  if(installed.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Bing metadata unavailable");
  session=new NativeRewardCardSession(runId,expires,new NativeRewardCardSession.Authority(){
   public long elapsedRealtime(){return SystemClock.elapsedRealtime();}
   public boolean scopeCurrent(){return current();}
   public boolean sourceStillMatches(int w,int h,int rotation){return sourceMatches(w,h,rotation);}
   public void consumeOneTap(ReviewShizukuTapActor.TapTarget target)throws Exception{
    if(!current())throw new SecurityException("Card authority lost");
    NativeRewardCardScope.burn(files,"TAP","fullDisplay="+target.fullDisplayWidth()+"x"+target.fullDisplayHeight()+"\n");
   }
  },ReviewShizukuTapActor.forApprovedBusiness20261111(context),LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay());
 }
 static NativeRewardCardRun confirmed(DormantCaptureConfirmationActivity activity){
  if(activity==null||!activity.isResumedForApproval()||!NativeRewardCardScope.admissionCurrent(System.currentTimeMillis())
    ||!NativeCheckInSession.permissionReady()||!CREATED.compareAndSet(false,true))throw new SecurityException("Fresh visible card approval required");
  return new NativeRewardCardRun(activity);
 }
 synchronized void reserve(NativeRewardCardSession.Phase phase){
  if(phase==null||reserved[phase.ordinal()]||!current()||phase==NativeRewardCardSession.Phase.AFTER&&!needsAfter())throw new SecurityException("Card phase unavailable");
  reserved[phase.ordinal()]=true;
 }
 RealCaptureClaim.BurnStore store(NativeRewardCardSession.Phase phase){return NativeRewardCardScope.store(files,phase);}
 NativeRewardCardSession.Capture startCapture(NativeRewardCardSession.Phase phase,int w,int h){
  if(phase==null||!reserved[phase.ordinal()]||!current())return null;
  var manager=context.getSystemService(DisplayManager.class);var d=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
  if(d==null||d.getState()!=Display.STATE_ON)return null;
  return session.startCapture(phase,w,h,d.getRotation());
 }
 private boolean current(){return !revoked.get()&&NativeRewardCardScope.current(System.currentTimeMillis())&&SystemClock.elapsedRealtime()<expires;}
 private boolean sourceMatches(int width,int height,int rotation){
  if(!current()||!NativeCheckInSession.permissionReady()||!installed.equals(BingPackageMetadata.read(context.getPackageManager())))return false;
  if(android.os.Build.VERSION.SDK_INT<30)return false;
  var manager=context.getSystemService(DisplayManager.class);var d=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
  if(d==null||d.getState()!=Display.STATE_ON||d.getRotation()!=rotation)return false;
  var wm=context.createDisplayContext(d).getSystemService(android.view.WindowManager.class);
  if(wm==null)return false;var bounds=wm.getMaximumWindowMetrics().getBounds();
  if(bounds.left!=0||bounds.top!=0||bounds.width()!=width||bounds.height()!=height)return false;
  var snap=ReviewBingForeground.read(context);
  if(snap==null||!ReviewBingForeground.knownClean()||!current())return false;
  if(foreground==null)foreground=snap;
  return foreground.equals(snap);
 }
 static LiveRewardCardObservation.Card select(NativeRewardCardSession.Observation observation){
  if(observation==null)return null;
  // Bind the exact configured item, including a completed item so the session can skip it.
  // Selection is not action authority; completed evidence never reaches the actor.
  LiveRewardCardObservation.Card selected=null;int matches=0;
  for(var card:observation.cards())if(card.displayedPoints()==5&&card.title().equals("Quote of the day")){selected=card;matches++;}
  return matches==1?selected:null;
 }
 String acceptAfterCleanup(NativeRewardCardSession.Capture capture,NativeRewardCardSession.Observation observation,
                           boolean released,BooleanSupplier hidden){
  var selected=capture!=null&&capture.phase==NativeRewardCardSession.Phase.BEFORE?select(observation):null;
  var receipt=session.acceptAfterCleanup(capture,observation,released,hidden,selected);
  String out="source=REAL_NATIVE_MORE_VISIBLE_ITEM scope="+runId+" phase="+(capture==null?"NONE":capture.phase)
   +" observation="+(observation==null?"NONE":observation.status())+" observationReason="+(observation==null?"NONE":observation.reason())
   +" candidates="+(observation==null?0:observation.incompleteCards().size())+" selection=EXACT_CONFIGURED_MORE_QUOTE counterMode=HEADER_FRACTION"
   +" outcome="+receipt.outcome()+" reason="+receipt.reason()+" points=NOT_EVALUATED causalAttribution=UNPROVEN";
  if(observation!=null&&observation.diagnostic()!=null)out+=" "+observation.diagnostic().summary();
  if(capture!=null)out+=" fullDisplay="+capture.width+"x"+capture.height+" rotation="+capture.rotation+" frameId="+capture.sequence+" sourceStartElapsed="+capture.acquiredLowerBound+" expiresElapsed="+expires;
  if(selected!=null)out+=" selectedCard="+box(selected.cardBox())+" selectedIndicator="+box(selected.indicatorBox())+" nativeNumber="+box(selected.numberBox())+" displayedTaskPoints="+selected.displayedPoints();
  if(receipt.actorResult()!=null){var r=receipt.actorResult();out+=" actorKind="+r.kind()+" actorReason="+r.reason()+" exit="+r.exitCode()+" invocationAttempted="+r.invocationAttempted()+" authorityConsumed="+r.authorityConsumed();}
  return out+"\n"+switch(receipt.outcome()){
   case AWAITING_NEW_OBSERVATION->"已发出一次任务卡触摸，跳转或退出码不代表完成。需要全新只读帧核验同一任务；不自动返回、不再点击。";
   case OBSERVED_VISIBLE_MORE_SET_COMPLETE->"当前可见卡集合各自有完成勾；不命名匿名项或推断因果。";
   case OBSERVED_VISIBLE_KNOWN_SET_COMPLETE->"当前可见同标题小项各自有完成勾；不为匿名小项命名或推断因果。";
   case OBSERVED_TASK_COMPLETE_UNATTRIBUTED->"新鲜原生帧中，同一任务身份显示可靠勾选。未核总积分，不证明本次点击是唯一原因。";
   case SECTION_ALREADY_COMPLETE_SKIP,TASK_ALREADY_COMPLETE_SKIP->"当前区域或任务已完成，已跳过，没有点击。";
   case HOLD->"当前证据不足，保持停止。没有自动重试、返回或第二次点击。";
  };
 }
 private static String box(com.aliothmoon.maafw.rewards.RewardRegionLocator.Box b){return b==null?"NONE":b.left()+","+b.top()+","+b.right()+","+b.bottom();}
 boolean needsAfter(){return current()&&NativeRewardCardScope.admissionCurrent(System.currentTimeMillis())&&session.needsAfter();}
 long remainingForAfterMillis(){return needsAfter()?Math.max(0,expires-SystemClock.elapsedRealtime()):0;}
 void cancel(){revoked.set(true);session.cancel();}
}
