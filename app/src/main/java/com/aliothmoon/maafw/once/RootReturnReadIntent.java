package com.aliothmoon.maafw.once;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
/** New visible return-and-read authority. Never an old tap receipt, Run or ReturnPermit. */
final class RootReturnReadIntent {
 private final RealCaptureClaim.ProcessEpoch epoch;private final Object root,visible,request;
 private final ReadOnlyCheckpoint checkpoint;private final long issued,end;private final boolean live,currentOnly,statusRecovery,progressRead,progressScan,progressLocate;private final LongSupplier utc;
 private final AtomicBoolean cancelled=new AtomicBoolean();private final Object[] owners=new Object[3];
 private boolean navigationClaimed;private String returnSummary="returnNavigation=NOT_INVOKED";
 private long readEnd;private int next=1;private BingNavigationSnapshot.Ready original;
 private RootReturnReadIntent(VisibleCaptureAuthority.ReturnReadApproval a){checkpoint=a.checkpoint;epoch=a.epoch;root=a.root;visible=a.visible;request=a.request;issued=a.issued;end=a.end;live=a.live;currentOnly=a.currentOnly;statusRecovery=a.statusRecovery;progressRead=a.progressRead;progressScan=a.progressScan;progressLocate=a.progressLocate;utc=a.utc;}
 static RootReturnReadIntent fromVisible(VisibleCaptureAuthority.ReturnReadApproval a,long now){if(a==null||a.currentOnly||a.statusRecovery||!a.consume(now))throw new SecurityException("Fresh recovery approval required");return new RootReturnReadIntent(a);}
 static RootReturnReadIntent fromCurrentVisible(VisibleCaptureAuthority.ReturnReadApproval a,long now){if(a==null||!a.currentOnly||a.statusRecovery||!a.consume(now))throw new SecurityException("Fresh current-only approval required");return new RootReturnReadIntent(a);}
 static RootReturnReadIntent fromStatusVisible(VisibleCaptureAuthority.ReturnReadApproval a,long now){if(a==null||a.currentOnly||!a.statusRecovery||!a.consume(now))throw new SecurityException("Fresh status recovery approval required");return new RootReturnReadIntent(a);}
 boolean entryDiagnostic(){return checkpoint==ReadOnlyCheckpoint.ENTRY_DIAGNOSTIC_V47;}
 boolean uiBoundPoints(){return checkpoint==ReadOnlyCheckpoint.UI_BOUND_POINTS_V53;}
 boolean uiLocate(){return checkpoint==ReadOnlyCheckpoint.UI_LOCATE_V52;}
 boolean uiField(){return checkpoint==ReadOnlyCheckpoint.UI_FIELD_V51;}
 boolean uiFormat(){return checkpoint==ReadOnlyCheckpoint.UI_FORMAT_V50;}
 boolean uiReady(){return uiBoundPoints()||uiLocate()||uiField()||uiFormat()||checkpoint==ReadOnlyCheckpoint.UI_READY_V49;}
 boolean entryRecovery(){return uiReady()|| checkpoint==ReadOnlyCheckpoint.REWARDS_ENTRY_V46||checkpoint==ReadOnlyCheckpoint.REWARDS_ENTRY_V48;}
 boolean uiStructure(){return entryRecovery()||entryDiagnostic()|| checkpoint!=null&&checkpoint.projection()==ReadOnlyCheckpoint.Projection.UI_STRUCTURE;}
 boolean progressLocate(){return progressLocate;} boolean scanNavigation(){return progressScan||progressLocate;} boolean layoutDiagnostic(){return checkpoint!=null||progressLocate;}
 ReadOnlyCheckpoint checkpoint(){return checkpoint;} boolean progressScan(){return progressScan;} boolean progressRead(){return progressRead;} boolean statusRecovery(){return statusRecovery;}boolean currentCardsOnly(){return currentOnly||statusRecovery;}
 boolean currentOnly(){return currentOnly;}
 boolean live(){return live;}long sessionEnd(){return end;}synchronized long readDeadline(){return readEnd;}
 boolean matchesOwner(Object r,Object v,Object q){return r==root&&v==visible&&q==request;}
 boolean current(long now){try{return !cancelled.get()&&now>=issued&&now<end&&(progressLocate?RootProgressLocateScope.current(utc.getAsLong()):checkpoint!=null?checkpoint.current(utc.getAsLong()):progressScan?RootProgressScanScope.current(utc.getAsLong()):progressRead?RootProgressReadScope.current(utc.getAsLong()):statusRecovery?RootStatusRecoveryScope.current(utc.getAsLong()):currentOnly?RootCurrentCardScope.current(utc.getAsLong()):RootReturnReadScope.current(utc.getAsLong()));}catch(RuntimeException e){return false;}}
 void cancel(){cancelled.set(true);}
 synchronized void claimNavigation(RealCaptureClaim.ProcessEpoch e,long now){if(navigationClaimed||e!=epoch||!current(now))throw new SecurityException("Navigation already claimed");navigationClaimed=true;}
 synchronized String navigationSummary(){return returnSummary;}
 synchronized void returned(RootReturnReadController.Result result,long now){
  if(currentOnly||readEnd!=0||!current(now)||result==null||!result.consume(this,now))throw new SecurityException("Exact fresh returned template required");
  original=result.template();returnSummary=result.summary();readEnd=Math.min((statusRecovery?RootStatusRecoveryScope.deadline(now,utc.getAsLong(),end):RootReturnReadScope.deadline(now,utc.getAsLong(),end)),live?Long.MAX_VALUE:issued+50_000);
 }
 synchronized void anchorCurrent(BingNavigationSnapshot.CurrentTemplateSource proof,long now){
  if(!currentOnly||readEnd!=0||proof==null||!current(now)||!proof.fresh(now)||proof.ready()==null
    ||proof.ready().kind()!=BingNavigationSnapshot.Kind.TEMPLATE||!proof.ready().task().equals(Integer.toString(progressLocate?RootProgressLocateScope.SOURCE_TASK_ID:checkpoint!=null?checkpoint.sourceTask():progressScan?RootProgressScanScope.SOURCE_TASK_ID:progressRead?RootProgressReadScope.SOURCE_TASK_ID:RootCurrentCardScope.SOURCE_TASK_ID)))throw new SecurityException("Fresh strict current Template proof required");
  original=proof.ready();returnSummary="independentCurrentRead=true returnInvoked=false taskTaps=0 oldBusinessContinued=false templateAnchor=FRESH_STRICT_CURRENT";
  if(progressLocate)readEnd=Math.min(RootProgressLocateScope.deadline(now,utc.getAsLong(),end),live?Long.MAX_VALUE:issued+50_000);
  else if(checkpoint!=null)readEnd=Math.min(checkpoint.deadline(now,utc.getAsLong(),end),live?Long.MAX_VALUE:issued+50_000);
  else if(progressScan)readEnd=Math.min(RootProgressScanScope.deadline(now,utc.getAsLong(),end),live?Long.MAX_VALUE:issued+50_000);
  else if(progressRead)readEnd=Math.min(RootProgressReadScope.deadline(now,utc.getAsLong(),end),live?Long.MAX_VALUE:issued+50_000);
  else readEnd=Math.min(RootCurrentCardScope.deadline(now,utc.getAsLong(),end),live?Long.MAX_VALUE:issued+50_000);
 }
 synchronized void beginEntryDiagnostic(long now){if(!entryDiagnostic()||!currentOnly||readEnd!=0||!current(now))throw new SecurityException("Fresh diagnostic checkpoint required");readEnd=checkpoint.deadline(now,utc.getAsLong(),end);}
 synchronized void beginEntry(long now){if(!entryRecovery()||!currentOnly||readEnd!=0||!current(now))throw new SecurityException("Fresh entry checkpoint required");readEnd=checkpoint.deadline(now,utc.getAsLong(),end);}
 synchronized void anchorEntryTemplate(BingNavigationSnapshot.Ready ready,long now){if(!entryRecovery()||original!=null||readEnd<=now||!current(now)||ready==null||ready.kind()!=BingNavigationSnapshot.Kind.TEMPLATE||!ready.fresh(now))throw new SecurityException("Fresh entry Template required");original=ready;returnSummary="entryRecovery=true templateAnchor=FRESH_SAME_TASK taskTaps=0 back=0";}
 synchronized boolean sourceMatches(BingNavigationSnapshot.Ready r){return original!=null&&original.sameSource(r);}
 synchronized FramePermit claimFrame(int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous,boolean forLive){
  if(live!=forLive||e!=epoch||owner==null||!current(now)||readEnd<=now||index!=next||!(progressLocate?RootProgressLocateScope.validIndex(index):checkpoint!=null?checkpoint.validIndex(index):progressScan?RootProgressScanScope.validIndex(index):progressRead?RootProgressReadScope.validIndex(index):statusRecovery?RootStatusRecoveryScope.validIndex(index):currentOnly?RootCurrentCardScope.validIndex(index):RootReturnReadScope.validIndex(index)))throw new SecurityException("Exact return-read frame required");
  for(Object used:owners)if(used==owner)throw new SecurityException("Distinct owner required");
  if(index==1){if(previous!=null||original==null)throw new SecurityException("Confirmed return required");}
  else if(previous==null||!previous.consume(this,index-1,e,now))throw new SecurityException("Healthy unpresented completion required");
  owners[index-1]=owner;next++;return new FramePermit(this,e,owner,now,readEnd,live);
 }
 private final boolean[] scrollReserved=new boolean[2];private int scrollSlots,scrollAttempts,scrollConfirmed;private boolean scrollActor;private long scanAfter=-1;
 synchronized long scanBoundary(){return scanAfter;}
 synchronized int scrollAttempts(){return scrollAttempts;}synchronized int scrollConfirmed(){return scrollConfirmed;}synchronized boolean scrollActor(){return scrollActor;}
 synchronized String scanSummary(){return "scrollSlotsConsumed="+scrollSlots+" swipeInvocations="+scrollAttempts+" swipeConfirmedExits="+scrollConfirmed+" scrollActorConstructed="+scrollActor+" movement=UNPROVEN";}
 synchronized ScrollPermit claimScroll(int afterFrame,RealCaptureClaim.ProcessEpoch e,Object owner,long now){
  if(!scanNavigation()||!live||e!=epoch||owner==null||afterFrame<1||afterFrame>2||owners[afterFrame-1]!=owner||next!=afterFrame+1||scrollSlots>=2||scrollReserved[afterFrame-1]||!current(now)||now>=readEnd)throw new SecurityException("Exact fresh scan frame required");
  scrollReserved[afterFrame-1]=true;scrollSlots++;return new ScrollPermit(this,owner,afterFrame);
 }
 synchronized void scrollActorCreated(){scrollActor=true;}
 synchronized void scrollResult(ScrollPermit permit,ReviewShizukuSwipeActor.Result result,long now){
  if(permit==null||permit.intent!=this||permit.finished||result==null)throw new SecurityException("Exact scroll result required");permit.finished=true;
  if(result.invocationAttempted())scrollAttempts++;if(result.commandExitZero()){scrollConfirmed++;scanAfter=now;}
 }
 static final class ScrollPermit {
  final RootReturnReadIntent intent;final Object owner;final int afterFrame;private boolean burned,finished;
  private ScrollPermit(RootReturnReadIntent intent,Object owner,int afterFrame){this.intent=intent;this.owner=owner;this.afterFrame=afterFrame;}
  synchronized boolean burn(Object exact,long now){if(burned||finished||owner!=exact||!intent.current(now)||now>=intent.readDeadline())return false;burned=true;return true;}
  synchronized boolean current(Object exact,long now){return burned&&!finished&&owner==exact&&intent.current(now)&&now<intent.readDeadline();}
 }
 static final class FramePermit {
  final RootReturnReadIntent intent;final RealCaptureClaim.ProcessEpoch epoch;final Object owner;final long issued,deadline;final boolean live;
  private FramePermit(RootReturnReadIntent i,RealCaptureClaim.ProcessEpoch e,Object o,long n,long d,boolean l){intent=i;epoch=e;owner=o;issued=n;deadline=d;live=l;}
  boolean current(RealCaptureClaim.ProcessEpoch e,Object o,long now){return e==epoch&&o==owner&&now>=issued&&now<deadline&&intent.current(now);}
 }
}
