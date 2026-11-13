package com.aliothmoon.maafw.once;
import java.util.concurrent.atomic.AtomicBoolean;
/** Independent fixed Back only. No tap, business session or old return capability. */
final class RootReturnReadController {
 interface Ports {
  long now();boolean current();BingNavigationSnapshot.RecoveryResult initial();BingNavigationSnapshot.Result observe();
  default BingNavigationSnapshot.StatusRecoveryInitial statusInitial(){return null;}
  void burnReturn(ReviewShizukuBackActor.BackTarget target)throws Exception;void pause()throws InterruptedException;String diagnostic();boolean metadataClean();
 }
 static final class Result {
  private final RootReturnReadIntent intent;private final BingNavigationSnapshot.Ready template;private final String reason,diagnostic;
  private final ReviewShizukuBackActor.Result back;private final int observations;private final boolean clean;
  private final BingNavigationSnapshot.CurrentTemplateSource direct;
  private final AtomicBoolean used=new AtomicBoolean();
  private Result(RootReturnReadIntent i,BingNavigationSnapshot.Ready t,String r,ReviewShizukuBackActor.Result b,int n,boolean c,String d){this(i,t,r,b,n,c,d,null);}
  private Result(RootReturnReadIntent i,BingNavigationSnapshot.Ready t,String r,ReviewShizukuBackActor.Result b,int n,boolean c,String d,BingNavigationSnapshot.CurrentTemplateSource direct){intent=i;template=t;reason=r;back=b;observations=n;clean=c;diagnostic=d;this.direct=direct;}
  boolean consume(RootReturnReadIntent exact,long now){return used.compareAndSet(false,true)&&exact==intent&&clean&&template!=null&&template.fresh(now)&&((back!=null&&back.commandExitZero())||(intent.statusRecovery()&&back==null&&direct!=null&&direct.fresh(now)&&direct.matches(template)))&&intent.current(now);}
  BingNavigationSnapshot.Ready template(){return template;}boolean returned(){return template!=null&&clean&&back!=null&&back.commandExitZero();}boolean readyForRead(){return template!=null&&clean&&(returned()||intent.statusRecovery()&&direct!=null);}boolean clean(){return clean;}
  String summary(){if(intent.statusRecovery())return "independentStatusRecovery=true oldBusinessContinued=false taskTaps=0 returnInvoked="+(back!=null&&back.invocationAttempted())+" templateAnchor="+(direct!=null?"FRESH_STRICT_CURRENT":returned()?"EXACT_AFTER_BACK":"UNOBSERVED")+" returnReason="+reason+" templateReturned="+returned()+" navigationObservations="+observations+" backKind="+(back==null?"NONE":back.kind())+" backReason="+(back==null?"NONE":back.reason())+" backExit="+(back==null?ReviewShizukuBackActor.NO_EXIT:back.exitCode())+" navigationCleanup="+(clean?"CONFIRMED":"UNKNOWN")+" "+diagnostic;return "independentRecovery=true oldBusinessContinued=false taskTaps=0 returnReason="+reason+" templateReturned="+returned()+" navigationObservations="+observations+" backKind="+(back==null?"NONE":back.kind())+" backReason="+(back==null?"NONE":back.reason())+" backInvocation="+(back!=null&&back.invocationAttempted())+" backExit="+(back==null?ReviewShizukuBackActor.NO_EXIT:back.exitCode())+" navigationCleanup="+(clean?"CONFIRMED":"UNKNOWN")+" "+diagnostic;}
 }
 private final RootReturnReadIntent intent;private final Ports ports;private final ReviewShizukuBackActor actor;private final AtomicBoolean attempted=new AtomicBoolean();
 private int observations;private boolean actionEntered,actionUnknown;private ReviewShizukuBackActor.Result back;private volatile boolean clean=true;
 RootReturnReadController(RootReturnReadIntent i,Ports p,ReviewShizukuBackActor a){if(i==null||p==null||a==null)throw new IllegalArgumentException();if(i.currentOnly())throw new SecurityException("Current-only intent cannot authorize Back");intent=i;ports=p;actor=a;}
 boolean resourcesKnown(){return clean&&actor.knownClean()&&ports.metadataClean();}
 private boolean current(){return intent.current(ports.now())&&ports.current()&&ports.metadataClean();}
 private Result result(String why,BingNavigationSnapshot.Ready t){clean=!actionUnknown&&actor.knownClean()&&ports.metadataClean();return new Result(intent,t,why,back,observations,clean,ports.diagnostic());}
 private boolean pause(){try{ports.pause();return true;}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
 Result execute(){
  if(!attempted.compareAndSet(false,true))return result("ALREADY_ATTEMPTED",null);
  try{
   BingNavigationSnapshot.RecoverySource source=null;
   for(int n=0;n<3;n++){
    if(!current())return result("AUTHORITY_LOST",null);observations++;
    if(intent.statusRecovery()){
     var r=ports.statusInitial();if(r==null||r.status()==BingNavigationSnapshot.Status.UNKNOWN)return result("SOURCE_UNKNOWN",null);
     if(r.status()==BingNavigationSnapshot.Status.READY){
      if(r.template()!=null){var proof=r.template();var ready=proof.ready();
       if(r.browser()!=null||ready==null||!proof.fresh(ports.now())||ready.kind()!=BingNavigationSnapshot.Kind.TEMPLATE||!ready.task().equals(Integer.toString(RootStatusRecoveryScope.SOURCE_TASK_ID))||!current())return result("TEMPLATE_CHANGED",null);
       clean=!actionUnknown&&actor.knownClean()&&ports.metadataClean();return new Result(intent,ready,"EXACT_TEMPLATE_ALREADY_READY",null,observations,clean,ports.diagnostic(),proof);
      }
      source=r.browser();break;
     }
    }else{
     var r=ports.initial();if(r==null||r.status()==BingNavigationSnapshot.Status.UNKNOWN)return result("SOURCE_UNKNOWN",null);
     if(r.status()==BingNavigationSnapshot.Status.READY){source=r.source();break;}
    }
    if(n<2&&!pause())return result("CANCELLED",null);
   }
   if(source==null||source.browser()==null||!source.browser().fresh(ports.now())||!source.browser().task().equals(Integer.toString(RootReturnReadScope.SOURCE_TASK_ID)))return result("BROWSER_NOT_READY",null);
   final var exactSource=source;final var browser=source.browser();var shape=browser.profile().shape();
   var target=new ReviewShizukuBackActor.BackTarget(shape.width(),shape.height(),shape.rotation());
   actionEntered=true;clean=false;
   back=actor.back(target,new ReviewShizukuBackActor.DispatchGate(){private boolean spent;
    public boolean consume(ReviewShizukuBackActor.BackTarget exact)throws Exception{if(spent)return false;spent=true;if(exact!=target||!current()||!browser.fresh(ports.now()))return false;ports.burnReturn(exact);return true;}
    public boolean stillValid(ReviewShizukuBackActor.BackTarget exact){if(exact!=target||!spent||!current())return false;observations++;if(intent.statusRecovery()){var r=ports.statusInitial();return r!=null&&r.status()==BingNavigationSnapshot.Status.READY&&r.template()==null&&r.browser()!=null&&exactSource.sameSource(r.browser())&&r.browser().browser().fresh(ports.now())&&current();}var r=ports.initial();return r!=null&&r.status()==BingNavigationSnapshot.Status.READY&&r.source()!=null&&exactSource.sameSource(r.source())&&r.source().browser().fresh(ports.now())&&current();}
   });
   if(!back.commandExitZero())return result("BACK_NOT_CONFIRMED",null);
   for(int n=0;n<3;n++){
    if(!current())return result("AUTHORITY_LOST",null);observations++;var r=ports.observe();
    if(r==null||r.status()==BingNavigationSnapshot.Status.UNKNOWN)return result("RETURN_SOURCE_UNKNOWN",null);
    if(r.status()==BingNavigationSnapshot.Status.READY){var ready=r.ready();
     if(!ready.fresh(ports.now())||!browser.sameTask(ready))return result("SOURCE_CHANGED",null);
     if(ready.kind()==BingNavigationSnapshot.Kind.TEMPLATE)return exactSource.templateMatches(ready)&&current()?result("EXACT_TEMPLATE_RETURNED",ready):result("TEMPLATE_CHANGED",null);
     if(!browser.sameBrowser(ready))return result("BROWSER_CHANGED",null);
    }
    if(n<2&&!pause())return result("CANCELLED",null);
   }
   return result("RETURN_NOT_READY",null);
  }catch(RuntimeException|Error failure){if(actionEntered&&back==null)actionUnknown=true;clean=!actionUnknown&&actor.knownClean()&&ports.metadataClean();return new Result(intent,null,"INTERNAL_UNKNOWN",back,observations,clean,ports.diagnostic());}
 }
}
