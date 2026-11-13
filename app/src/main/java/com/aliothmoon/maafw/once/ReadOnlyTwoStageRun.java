package com.aliothmoon.maafw.once;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
/** Two current-state observations only. No input actor, business session or TapTarget exists here. */
final class ReadOnlyTwoStageRun {
 enum Phase { FIRST, SECOND;
  String hardwareLabel(){return this==FIRST?"BEFORE":"AFTER";}
  ForegroundPhaseSlots.Phase foreground(){return this==FIRST?ForegroundPhaseSlots.Phase.BEFORE:ForegroundPhaseSlots.Phase.AFTER;}
 }
 static final class Capture {
  final Object owner;final Phase phase;final String runId;final int width,height,rotation;final long lower,sequence;
  private final AtomicBoolean prepared=new AtomicBoolean();private final BooleanSupplier revoked;
  private final boolean familyDiagnostic;private final boolean currentStatus;private boolean currentCards,progressRead,layoutDiagnostic,structureDiagnostic;
  Capture(Object owner,Phase phase,String runId,int w,int h,int rotation,long lower,BooleanSupplier revoked){
   this(owner,phase,runId,w,h,rotation,lower,revoked,false,false);
  }
  private Capture(Object owner,Phase phase,String runId,int w,int h,int rotation,long lower,BooleanSupplier revoked,boolean familyDiagnostic,boolean currentStatus){
   this.owner=owner;this.phase=phase;this.runId=runId;width=w;height=h;this.rotation=rotation;this.lower=lower;sequence=phase.ordinal()+1;this.revoked=revoked;this.familyDiagnostic=familyDiagnostic;this.currentStatus=currentStatus;
  }
  static Capture moreFamilyDiagnostic(Object owner,String runId,int w,int h,int rotation,long lower,BooleanSupplier revoked){
   return new Capture(owner,Phase.FIRST,runId,w,h,rotation,lower,revoked,true,false);
  }
  /** Fixed current-state observation only, no business Run/Session/actor construction. */
  static Capture moreCurrentStatus(Object owner,String runId,int w,int h,int rotation,long lower,BooleanSupplier revoked){
   if(owner==null||runId==null||revoked==null||w<=0||h<=0||rotation<0||rotation>3||lower<0)throw new IllegalArgumentException("current-state holder");
   return new Capture(owner,Phase.FIRST,runId,w,h,rotation,lower,revoked,false,true);
  }
  static Capture moreCurrentCards(Object owner,String runId,int w,int h,int rotation,long lower,BooleanSupplier revoked){
   Capture capture=moreCurrentStatus(owner,runId,w,h,rotation,lower,revoked);capture.currentCards=true;return capture;
  }
  static Capture structureDiagnostic(Object owner,String runId,int w,int h,int rotation,long lower,BooleanSupplier revoked){
   Capture capture=moreCurrentStatus(owner,runId,w,h,rotation,lower,revoked);capture.structureDiagnostic=true;return capture;
  }
  static Capture layoutDiagnostic(Object owner,String runId,int w,int h,int rotation,long lower,BooleanSupplier revoked){
   Capture capture=moreCurrentStatus(owner,runId,w,h,rotation,lower,revoked);capture.layoutDiagnostic=true;return capture;
  }
  static Capture progressRead(Object owner,String runId,int w,int h,int rotation,long lower,BooleanSupplier revoked){
   Capture capture=moreCurrentStatus(owner,runId,w,h,rotation,lower,revoked);capture.progressRead=true;return capture;
  }
  boolean currentStatusOnly(){return currentStatus;}
  Observation prepare(byte[] gray,int w,int h,java.util.List<FrameStructureEvidence.Line> rows,BooleanSupplier cancel){
   if(w!=width||h!=height||cancel==null||!prepared.compareAndSet(false,true)||cancel.getAsBoolean()||revoked.getAsBoolean())return null;
   Object token=new Object();var binding=new FrameStructureEvidence.Binding(this,owner,token,token);
   var assessment=OcrRowsAssessment.assess(binding,binding,rows,w,h);
   var frame=LiveRewardCardObservation.observe(runId,sequence,gray,w,h,rows,()->cancel.getAsBoolean()||revoked.getAsBoolean(),currentStatus?LiveRewardCardObservation.TargetSpec.moreTriviaChallengeBounded():familyDiagnostic?LiveRewardCardObservation.TargetSpec.moreTriviaChallenge():LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay());
   com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentReadBatch independent=null;
   if(currentCards){
    java.util.List<com.aliothmoon.maafw.rewards.RewardRegionLocator.Row> originals=new java.util.ArrayList<>();
    if(assessment.shapeValid())for(var row:rows)originals.add(new com.aliothmoon.maafw.rewards.RewardRegionLocator.Row(row.text(),new com.aliothmoon.maafw.rewards.RewardRegionLocator.Box(row.x(),row.y(),row.x()+row.width(),row.y()+row.height()),row.score(),com.aliothmoon.maafw.rewards.RewardRegionLocator.Origin.STATIC_OCR));
    try{independent=com.aliothmoon.maafw.rewards.RewardRegionLocator.observeCurrentCardsGray8(
     new com.aliothmoon.maafw.rewards.RewardRegionGate.Key(com.aliothmoon.maafw.rewards.RewardRegionGate.Source.REAL_DISPLAY_NATIVE,runId,sequence,"READ_ONLY_CURRENT_CARDS"),gray,w,h,originals,()->cancel.getAsBoolean()||revoked.getAsBoolean());}
    finally{originals.clear();}
   }
   return cancel.getAsBoolean()||revoked.getAsBoolean()?null:new Observation(this,frame,assessment,independent,progressRead?NativeProgressScalars.observe(binding,rows,w,h):null,layoutDiagnostic?NativeProgressLayoutDiagnostics.observe(binding,binding,rows,w,h):null,structureDiagnostic?NativeProgressRegionDiagnostics.observe(binding,binding,gray,rows,w,h,()->cancel.getAsBoolean()||revoked.getAsBoolean()):null);
  }
 }
 enum CurrentFamilyState { NOT_APPLICABLE, TARGET_EXACT_PENDING, TARGET_EXACT_COMPLETE, UNNAMED_COMPLETE_PRESENT, HOLD }
 enum CurrentIdentity { EXACT_A, EXACT_B, UNNAMED }
 enum CurrentItemState { PENDING_5, COMPLETE_5_TICK }
 record CurrentItem(CurrentIdentity identity,LiveRewardCardObservation.BodyClass canonicalBody,CurrentItemState state,boolean complete,int points,com.aliothmoon.maafw.rewards.RewardRegionLocator.Box cardBox) {}
 static final class Observation {
  final Capture capture;final LiveRewardCardObservation.Frame frame;
  private final OcrRowsAssessment assessment;
  private final NativeProgressScalars progress;private final NativeProgressLayoutDiagnostics layout;private final NativeProgressRegionDiagnostics regions;
  NativeProgressRegionDiagnostics regions(){return regions;}
  NativeProgressLayoutDiagnostics layout(){return layout;}
  NativeProgressScalars progress(){return progress;}
  private final com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentReadBatch currentCards;
  private Observation(Capture c,LiveRewardCardObservation.Frame f,OcrRowsAssessment assessment,com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentReadBatch currentCards,NativeProgressScalars progress,NativeProgressLayoutDiagnostics layout,NativeProgressRegionDiagnostics regions){capture=c;frame=f;this.assessment=assessment;this.currentCards=currentCards;this.progress=progress;this.layout=layout;this.regions=regions;}
  com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentReadBatch currentCards(){return currentCards;}
  int nativeRowCount(){return assessment.rows();}int nativeNonemptyRows(){return assessment.nonempty();}
  boolean validNativeShape(){return assessment.shapeValid()&&frame.route()==LiveRewardCardObservation.Route.MORE_VISIBLE_ITEM&&frame.reason()!=LiveRewardCardObservation.Reason.INVALID_INPUT&&frame.reason()!=LiveRewardCardObservation.Reason.BAD_NATIVE_BATCH&&frame.reason()!=LiveRewardCardObservation.Reason.CANCELED;}
  java.util.List<CurrentItem> currentItems(){
   if(!capture.currentStatusOnly()||!validNativeShape()||frame.status()!=LiveRewardCardObservation.Status.OBSERVED||frame.diagnostic()==null)return java.util.List.of();
   var details=frame.diagnostic().familyItemDiagnostics();var cards=frame.cards();
   if(details.size()!=cards.size())return java.util.List.of();
   boolean[] seen=new boolean[cards.size()];java.util.List<CurrentItem> result=new java.util.ArrayList<>();
   for(var detail:details){
    int i=detail.frameItemIndex();if(i<0||i>=cards.size()||seen[i])return java.util.List.of();seen[i]=true;
    var card=cards.get(i);if(detail.points()!=5||card.displayedPoints()!=5)return java.util.List.of();
    CurrentIdentity identity=switch(detail.canonicalBody()){
     case TARGET->CurrentIdentity.EXACT_A;case SIBLING->CurrentIdentity.EXACT_B;case EMPTY_COMPLETE->CurrentIdentity.UNNAMED;default->null;};
    if(identity==null||identity==CurrentIdentity.UNNAMED&&!card.complete()
       ||identity==CurrentIdentity.EXACT_A&&!card.configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallengeBounded()))return java.util.List.of();
    result.add(new CurrentItem(identity,detail.canonicalBody(),card.complete()?CurrentItemState.COMPLETE_5_TICK:CurrentItemState.PENDING_5,card.complete(),5,card.cardBox()));
   }
   return java.util.List.copyOf(result);
  }
  CurrentFamilyState currentFamilyState(){
   if(!capture.currentStatusOnly())return CurrentFamilyState.NOT_APPLICABLE;
   var items=currentItems();if(items.size()!=2)return CurrentFamilyState.HOLD;
   int pending=0,complete=0,empty=0;
   for(var item:items){if(item.identity()==CurrentIdentity.EXACT_A){if(item.state()==CurrentItemState.PENDING_5)pending++;else complete++;}
    if(item.identity()==CurrentIdentity.UNNAMED)empty++;}
   if(pending==1&&complete==0)return CurrentFamilyState.TARGET_EXACT_PENDING;
   if(complete==1&&pending==0)return CurrentFamilyState.TARGET_EXACT_COMPLETE;
   if(pending==0&&complete==0&&empty>0)return CurrentFamilyState.UNNAMED_COMPLETE_PRESENT;
   return CurrentFamilyState.HOLD;
  }
  String currentStatusSummary(){return "profile=READ_ONLY_CURRENT_FAMILY currentState="+currentFamilyState()+" observation="+frame.status()
   +" reason="+frame.reason()+" familyItems="+(frame.status()==LiveRewardCardObservation.Status.OBSERVED?Integer.toString(frame.cards().size()):"UNOBSERVED")+" nativeRows="+nativeRowCount()+" nativeNonempty="+nativeNonemptyRows()
   +" currentItems="+currentItems()+" adapterFirstGate="+(frame.diagnostic()==null?"NOT_EVALUATED":frame.diagnostic().adapterFirstGate())
   +" taskTaps=0 taskActorConstructed=false causalAttribution=NOT_APPLICABLE";}
  boolean quoteComplete(){return frame.status()==LiveRewardCardObservation.Status.OBSERVED&&frame.cards().size()==1&&frame.cards().get(0).complete();}
 }
 private static final AtomicBoolean CREATED=new AtomicBoolean();
 private final Context context;private final Path files;private final long expires;private final int processId;
 private final String runId=UUID.randomUUID().toString();private final AtomicBoolean revoked=new AtomicBoolean();
 private final boolean[] reserved=new boolean[2];private final BingIdentityEvidence.Installed installed;
 private BingForegroundSnapshot.Snapshot foreground;private Capture active;private int width,height,rotation;
 private volatile boolean firstAccepted,secondStarted,finished;private long firstFinished;
 private ReadOnlyTwoStageRun(DormantCaptureConfirmationActivity activity){
  context=activity.getApplicationContext();files=activity.getNoBackupFilesDir().toPath();processId=android.os.Process.myPid();
  long now=SystemClock.elapsedRealtime();expires=now+Math.min(ReadOnlyTwoStageScope.SESSION_TTL,ReadOnlyTwoStageScope.EXPIRES_UTC-System.currentTimeMillis());
  installed=BingPackageMetadata.read(context.getPackageManager());
  if(installed.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Bing metadata unavailable");
 }
 static ReadOnlyTwoStageRun confirmed(DormantCaptureConfirmationActivity activity){
  if(activity==null||!activity.isResumedForApproval()||!ReadOnlyTwoStageScope.admissionCurrent(System.currentTimeMillis())
    ||!NativeCheckInSession.permissionReady()||!CREATED.compareAndSet(false,true))throw new SecurityException("Fresh read-only approval required");
  return new ReadOnlyTwoStageRun(activity);
 }
 boolean current(){return !revoked.get()&&!finished&&ReadOnlyTwoStageScope.current(System.currentTimeMillis())&&SystemClock.elapsedRealtime()<expires&&android.os.Process.myPid()==processId;}
 long deadline(long now){return ReadOnlyTwoStageScope.deadline(now,System.currentTimeMillis(),expires);}
 synchronized void reserve(Phase phase){
  if(phase==null||reserved[phase.ordinal()]||!current()||phase==Phase.SECOND&&!needsAfter())throw new SecurityException("Read-only phase unavailable");
  reserved[phase.ordinal()]=true;if(phase==Phase.SECOND)secondStarted=true;
 }
 RealCaptureClaim.BurnStore store(Phase phase){return (id,metadata)->ReadOnlyTwoStageScope.burn(files,phase.name(),metadata);}
 Capture startCapture(Phase phase,int w,int h){
  if(phase==null||!reserved[phase.ordinal()]||active!=null||!current())return null;
  var manager=context.getSystemService(DisplayManager.class);var d=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
  if(d==null||d.getState()!=Display.STATE_ON)return null;int r=d.getRotation();
  if(phase==Phase.SECOND&&(w!=width||h!=height||r!=rotation||!firstAccepted))return null;
  if(!sourceMatches(w,h,r)||!current())return null;
  long lower=SystemClock.elapsedRealtime();if(phase==Phase.SECOND&&lower<=firstFinished)return null;
  if(phase==Phase.FIRST){width=w;height=h;rotation=r;}
  return active=new Capture(this,phase,runId,w,h,r,lower,revoked::get);
 }
 private boolean sourceMatches(int w,int h,int r){
  if(!current()||!NativeCheckInSession.permissionReady()||!installed.equals(BingPackageMetadata.read(context.getPackageManager()))||android.os.Build.VERSION.SDK_INT<30)return false;
  var manager=context.getSystemService(DisplayManager.class);var d=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
  if(d==null||d.getState()!=Display.STATE_ON||d.getRotation()!=r)return false;
  var wm=context.createDisplayContext(d).getSystemService(android.view.WindowManager.class);if(wm==null)return false;
  var b=wm.getMaximumWindowMetrics().getBounds();if(b.left!=0||b.top!=0||b.width()!=w||b.height()!=h)return false;
  var snap=ReviewBingForeground.read(context);if(snap==null||!ReviewBingForeground.knownClean()||!current())return false;
  if(foreground==null)foreground=snap;return foreground.equals(snap);
 }
 static boolean freshPublication(long now,long lower,long phaseDeadline,long sessionEnd,long utc,boolean revoked){
  return !revoked&&lower>=0&&now>=lower&&now-lower<=15000&&now<phaseDeadline&&now<sessionEnd&&ReadOnlyTwoStageScope.current(utc);
 }
 boolean publicationCurrent(long lower,long phaseDeadline){return android.os.Process.myPid()==processId&&freshPublication(SystemClock.elapsedRealtime(),lower,phaseDeadline,expires,System.currentTimeMillis(),revoked.get());}
 String accept(Capture capture,Observation observation,boolean candidateEligible,BooleanSupplier hidden,ForegroundPhaseSlots.Snapshot life,boolean projectionReturned,int requestCode,long phaseDeadline){
  long now=SystemClock.elapsedRealtime();
  boolean valid=candidateEligible&&capture!=null&&capture==active&&capture.owner==this&&observation!=null&&observation.capture==capture&&observation.validNativeShape()
    &&life!=null&&life.phase()==capture.phase.foreground()&&life.confirmedDestruction()&&projectionReturned&&requestCode>0
    &&hidden!=null&&hidden.getAsBoolean()&&current()&&publicationCurrent(capture.lower,phaseDeadline);
  if(valid)valid=sourceMatches(capture.width,capture.height,capture.rotation)&&hidden.getAsBoolean()&&current()&&publicationCurrent(capture.lower,phaseDeadline);
  active=null;
  if(!valid){finished=true;return null;}
  boolean complete=observation.quoteComplete();var diagnostic=observation.frame.diagnostic();
  String result="profile=READ_ONLY_TWO_STAGE source=REAL_NATIVE_CURRENT_QUOTE scope="+runId+" stage="+capture.phase+" processId="+processId
   +" frameId="+capture.sequence+" sourceStartElapsed="+capture.lower+" fullDisplay="+capture.width+"x"+capture.height+" rotation="+capture.rotation
   +" taskState="+(complete?"CURRENT_QUOTE_COMPLETE_NATIVE_TICK":"HOLD")+" observation="+observation.frame.status()+" observationReason="+observation.frame.reason()
   +" "+life.summary()+" projectionReturned="+projectionReturned+" consentRequestCode="+requestCode
   +" maxFrames=2 actions=0 actorConstructed=false identicalPixelsAllowed=true causalAttribution=NOT_APPLICABLE points=NOT_EVALUATED";
  if(diagnostic!=null)result+=" "+diagnostic.summary();
  if(capture.phase==Phase.FIRST){firstFinished=SystemClock.elapsedRealtime();firstAccepted=true;}else finished=true;
  return result+"\n"+(complete?"此新鲜原生帧中，Quote 任务自身显示可靠完成勾。":"当前任务证据不足，任务状态保持HOLD。")+" 本阶段只有读取；两帧允许相同画面，不代表新业务动作或因果。";
 }
 boolean needsAfter(){return firstAccepted&&!secondStarted&&!finished&&!revoked.get()&&ReadOnlyTwoStageScope.admissionCurrent(System.currentTimeMillis())&&SystemClock.elapsedRealtime()<expires;}
 long remainingForAfterMillis(){return needsAfter()?Math.max(0,expires-SystemClock.elapsedRealtime()):0;}
 void cancel(){revoked.set(true);}
}
