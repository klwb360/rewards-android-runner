package com.aliothmoon.maafw.once;

import java.util.concurrent.atomic.AtomicBoolean;

/** One typed Back at most, only after this session's sealed tap receipt. No webpage actions.
 * Legacy mode has 3 initial, 1 final Back gate and 2 return observations.
 * Timed mode has at most 2 task guards, both after the five-second delay, and 2 post-Back return observations.
 * Its monotonic five-second delay starts at the sealed successful tap return, not pre-dispatch. It is scheduling only. A pause or command exit never proves return. */
final class NativeBingReturnController {
    enum Status { RETURN_READY, HOLD }
    enum Reason { ORIGINAL_SOURCE_ALREADY_READY, ORIGINAL_SOURCE_READY_AFTER_BACK, ALREADY_ATTEMPTED,
        WAIT_LIMIT, TASK_GUARD_REJECTED, AUTHORITY_LOST, SOURCE_UNKNOWN, SOURCE_CHANGED, BROWSER_NOT_READY, BACK_UNKNOWN, RETURN_NOT_READY }
    interface Ports {
        long elapsedRealtime();
        boolean authorityCurrent(BingNavigationSnapshot.Shape shape);
        BingNavigationSnapshot.Ready originalSource();
        BingNavigationSnapshot.Result observe(BingNavigationSnapshot.Shape shape);
        void consumeReturn(ReviewShizukuBackActor.BackTarget exact) throws Exception;
        void pollPause() throws InterruptedException;
        default BingNavigationSnapshot.Result observeTimedReturn(BingNavigationSnapshot.Ready original){return BingNavigationSnapshot.Result.unknown();}
        default BingNavigationSnapshot.TimedTaskGuard timedTaskGuard(BingNavigationSnapshot.Ready original){return null;}
        default boolean metadataClean(){return true;}
        default String diagnostic(){return "navigationRead=NOT_OBSERVED";}
    }
    static final class Result {
        private final NativeRewardCardSession.ReturnPermit permit;
        private final BingNavigationSnapshot.Ready ready;
        private final Status status;
        private final Reason reason;
        private final ReviewShizukuBackActor.Result actor;
        private final int observations;
        private final String diagnostic;
        private final boolean browserObserved;
        private Result(NativeRewardCardSession.ReturnPermit p,BingNavigationSnapshot.Ready r,Status s,Reason why,ReviewShizukuBackActor.Result a,int n,String diagnostic){
            this(p,r,s,why,a,n,diagnostic,a!=null);
        }
        private Result(NativeRewardCardSession.ReturnPermit p,BingNavigationSnapshot.Ready r,Status s,Reason why,ReviewShizukuBackActor.Result a,int n,String diagnostic,boolean observedBrowser){
            permit=p;ready=r;status=s;reason=why;actor=a;observations=n;this.diagnostic=diagnostic;browserObserved=observedBrowser;
        }
        boolean readyFor(NativeRewardCardSession.ReturnPermit exact,long now){return permit==exact&&status==Status.RETURN_READY
            &&ready!=null&&ready.fresh(now)&&exact.current();}
        String summary(){return "returnStatus="+status+" returnReason="+reason+" returnReady="+(status==Status.RETURN_READY)
            +" browserObserved="+browserObserved+" navigationObservations="+observations+" backKind="+(actor==null?"NONE":actor.kind())
            +" backReason="+(actor==null?"NONE":actor.reason())+" backExit="+(actor==null?ReviewShizukuBackActor.NO_EXIT:actor.exitCode())
            +" backInvocationAttempted="+(actor!=null&&actor.invocationAttempted())+" returnAuthorityConsumed="+(actor!=null&&actor.authorityConsumed())
            +" htmlModalAbsence=NOT_ESTABLISHED "+diagnostic;}
    }
    private final String runId;
    private final Ports ports;
    private final ReviewShizukuBackActor actor;
    private final AtomicBoolean attempted=new AtomicBoolean();
    private int observations;
    private final boolean selectedPending,timed;
    private long waitStart=-1,tapDispatchElapsed=-1,tapSuccessElapsed=-1,backFinalGateElapsed=-1,backGateSinceTap=-1,backGateSinceSuccess=-1,backActorReturnedElapsed=-1;
    private int taskGuardObservations;
    private String taskGuardDiagnostic="timedTaskGuard=NOT_OBSERVED";
    private BingNavigationSnapshot.FirstGate lastFirstGate=BingNavigationSnapshot.FirstGate.NOT_APPLIED;
    NativeBingReturnController(String runId,Ports ports,ReviewShizukuBackActor actor){this(runId,ports,actor,false);}
    NativeBingReturnController(String runId,Ports ports,ReviewShizukuBackActor actor,boolean selectedPending){this(runId,ports,actor,selectedPending,false);}
    private NativeBingReturnController(String runId,Ports ports,ReviewShizukuBackActor actor,boolean selectedPending,boolean timed){
        if(runId==null||ports==null||actor==null)throw new IllegalArgumentException("explicit return profile");this.runId=runId;this.ports=ports;this.actor=actor;this.selectedPending=selectedPending;this.timed=timed;
    }
    static NativeBingReturnController timedAfterTap(String runId,Ports ports,ReviewShizukuBackActor actor){return new NativeBingReturnController(runId,ports,actor,true,true);}
    private String diagnostic(){return (timed?"returnPolicy=AFTER_SUCCESSFUL_TAP_5000MS waitStartElapsed="+waitStart+" tapDispatchElapsed="+tapDispatchElapsed+" successfulTapElapsed="+tapSuccessElapsed+" backFinalGateElapsed="+backFinalGateElapsed+" elapsedSinceTapDispatchAtBackGate="+backGateSinceTap+" elapsedSinceSuccessfulTapAtBackGate="+backGateSinceSuccess+" backActorReturnedElapsed="+backActorReturnedElapsed+" minimumDelayMs=5000 taskGuardObservations="+taskGuardObservations+" "+taskGuardDiagnostic+" ":"")+"navigationFirstGate="+lastFirstGate+" "+ports.diagnostic();}
    private boolean current(NativeRewardCardSession.ReturnPermit p){
        long now=ports.elapsedRealtime();return p!=null&&p.runId().equals(runId)&&p.current()&&now>=p.actionElapsed()
            &&now-p.actionElapsed()<=30_000&&(!selectedPending||ports.metadataClean())&&ports.authorityCurrent(p.shape())&&p.current();
    }
    private Result hold(NativeRewardCardSession.ReturnPermit p,Reason why,ReviewShizukuBackActor.Result result){return new Result(p,null,Status.HOLD,why,result,observations,diagnostic(),!timed&&result!=null);}
    private BingNavigationSnapshot.Result observe(NativeRewardCardSession.ReturnPermit p){observations++;var result=ports.observe(p.shape());lastFirstGate=result==null?BingNavigationSnapshot.FirstGate.INPUT:result.firstGate();return result;}
    private BingNavigationSnapshot.Result observeTimedReturn(BingNavigationSnapshot.Ready original){observations++;var result=ports.observeTimedReturn(original);lastFirstGate=result==null?BingNavigationSnapshot.FirstGate.INPUT:result.firstGate();return result;}
    Result returnAfterTap(NativeRewardCardSession.ReturnPermit permit){
        if(!attempted.compareAndSet(false,true))return hold(permit,Reason.ALREADY_ATTEMPTED,null);
        if(!current(permit))return hold(permit,Reason.AUTHORITY_LOST,null);
        var original=ports.originalSource();
        if(original==null||original.kind()!=BingNavigationSnapshot.Kind.TEMPLATE||!original.profile().shape().equals(permit.shape())||selectedPending&&!original.task().equals(Integer.toString(RootSelectedPendingScope.SOURCE_TASK_ID)))return hold(permit,Reason.SOURCE_UNKNOWN,null);
        if(timed)return timedReturn(permit,original);
        BingNavigationSnapshot.Ready browser=null;
        for(int i=0;i<3;i++){
            if(!current(permit))return hold(permit,Reason.AUTHORITY_LOST,null);
            var read=observe(permit);
            if(read==null||read.status()==BingNavigationSnapshot.Status.UNKNOWN||selectedPending&&!ports.metadataClean())return hold(permit,Reason.SOURCE_UNKNOWN,null);
            if(read.status()==BingNavigationSnapshot.Status.READY){
                var ready=read.ready();if(!ready.fresh(ports.elapsedRealtime())||!ready.sameTask(original))return hold(permit,Reason.SOURCE_CHANGED,null);
                if(ready.kind()==BingNavigationSnapshot.Kind.TEMPLATE){
                    if(!original.sameSource(ready)||!current(permit))return hold(permit,Reason.SOURCE_CHANGED,null);
                    return new Result(permit,ready,Status.RETURN_READY,Reason.ORIGINAL_SOURCE_ALREADY_READY,null,observations,diagnostic());
                }
                browser=ready;break;
            }
            if(i<2&&!pause())return hold(permit,Reason.AUTHORITY_LOST,null);
        }
        if(browser==null)return hold(permit,Reason.BROWSER_NOT_READY,null);
        final BingNavigationSnapshot.Ready exactBrowser=browser;
        var shape=permit.shape();var target=new ReviewShizukuBackActor.BackTarget(shape.width(),shape.height(),shape.rotation());
        var back=actor.back(target,new ReviewShizukuBackActor.DispatchGate(){
            private boolean spent;
            public boolean consume(ReviewShizukuBackActor.BackTarget exact)throws Exception{
                if(spent)return false;spent=true;
                if(exact!=target||!current(permit)||!exactBrowser.fresh(ports.elapsedRealtime()))return false;
                ports.consumeReturn(exact);return true;
            }
            public boolean stillValid(ReviewShizukuBackActor.BackTarget exact){
                if(exact!=target||!spent||!current(permit))return false;
                var fresh=observe(permit);
                return fresh!=null&&fresh.status()==BingNavigationSnapshot.Status.READY&&exactBrowser.sameBrowser(fresh.ready())
                    &&fresh.ready().fresh(ports.elapsedRealtime())&&(!selectedPending||ports.metadataClean())&&current(permit);
            }
        });
        if(!back.commandExitZero())return hold(permit,Reason.BACK_UNKNOWN,back);
        for(int i=0;i<2;i++){
            if(!current(permit))return hold(permit,Reason.AUTHORITY_LOST,back);
            var read=observe(permit);
            if(read==null||read.status()==BingNavigationSnapshot.Status.UNKNOWN||selectedPending&&!ports.metadataClean())return hold(permit,Reason.SOURCE_UNKNOWN,back);
            if(read.status()==BingNavigationSnapshot.Status.READY){
                var ready=read.ready();if(!ready.fresh(ports.elapsedRealtime())||!ready.sameTask(original))return hold(permit,Reason.SOURCE_CHANGED,back);
                if(ready.kind()==BingNavigationSnapshot.Kind.TEMPLATE){
                    if(!original.sameSource(ready)||!current(permit))return hold(permit,Reason.SOURCE_CHANGED,back);
                    return new Result(permit,ready,Status.RETURN_READY,Reason.ORIGINAL_SOURCE_READY_AFTER_BACK,back,observations,diagnostic());
                }
                if(!exactBrowser.sameBrowser(ready))return hold(permit,Reason.SOURCE_CHANGED,back);
            }
            if(i<1&&!pause())return hold(permit,Reason.AUTHORITY_LOST,back);
        }
        return hold(permit,Reason.RETURN_NOT_READY,back);
    }
    /** Time is a scheduling policy only; neither the delay nor exit zero proves return or completion. */
    private boolean taskGuard(NativeRewardCardSession.ReturnPermit permit,BingNavigationSnapshot.Ready original){
        if(!current(permit)||taskGuardObservations>=2)return false;
        taskGuardObservations++;observations++;
        var guard=ports.timedTaskGuard(original);taskGuardDiagnostic=guard==null?"timedTaskGuard=NOT_OBSERVED":guard.summary();
        return guard!=null&&guard.matches(original,ports.elapsedRealtime())&&ports.metadataClean()&&current(permit);
    }
    private Result timedReturn(NativeRewardCardSession.ReturnPermit permit,BingNavigationSnapshot.Ready original){
        waitStart=ports.elapsedRealtime();tapDispatchElapsed=permit.actionElapsed();long action=permit.successfulTapElapsed();tapSuccessElapsed=action;
        if(action<tapDispatchElapsed||action<0||waitStart<action||action>Long.MAX_VALUE-5000)return hold(permit,Reason.AUTHORITY_LOST,null);
        long due=action+5000;
        // A transient focus/window gap during the delay is not sampled or classified.
        int pauses=0;
        while(ports.elapsedRealtime()<due){
            if(!current(permit))return hold(permit,Reason.AUTHORITY_LOST,null);
            if(pauses++>=200)return hold(permit,Reason.WAIT_LIMIT,null);
            if(!pause())return hold(permit,Reason.AUTHORITY_LOST,null);
        }
        if(!current(permit)||ports.elapsedRealtime()<due)return hold(permit,Reason.AUTHORITY_LOST,null);
        if(!taskGuard(permit,original))return hold(permit,Reason.TASK_GUARD_REJECTED,null);
        var shape=permit.shape();var target=new ReviewShizukuBackActor.BackTarget(shape.width(),shape.height(),shape.rotation());
        var back=actor.back(target,new ReviewShizukuBackActor.DispatchGate(){
            private boolean spent;
            public boolean consume(ReviewShizukuBackActor.BackTarget exact)throws Exception{
                if(spent)return false;spent=true;
                if(exact!=target||ports.elapsedRealtime()<due||!current(permit))return false;
                ports.consumeReturn(exact);return true;
            }
            public boolean stillValid(ReviewShizukuBackActor.BackTarget exact){
                if(exact!=target||!spent||ports.elapsedRealtime()<due||!current(permit)||!taskGuard(permit,original))return false;
                long now=ports.elapsedRealtime();if(now<due||!current(permit))return false;
                backFinalGateElapsed=now;backGateSinceTap=now-tapDispatchElapsed;backGateSinceSuccess=now-action;return true;
            }
        });
        backActorReturnedElapsed=ports.elapsedRealtime();
        if(!back.commandExitZero()||!actor.knownClean()||!ports.metadataClean())return hold(permit,Reason.BACK_UNKNOWN,back);
        for(int i=0;i<2;i++){
            if(!current(permit))return hold(permit,Reason.AUTHORITY_LOST,back);
            var read=observeTimedReturn(original);
            if(read==null||read.status()==BingNavigationSnapshot.Status.UNKNOWN||!ports.metadataClean())return hold(permit,Reason.SOURCE_UNKNOWN,back);
            if(read.status()==BingNavigationSnapshot.Status.READY){
                var ready=read.ready();if(!ready.fresh(ports.elapsedRealtime())||!ready.sameTask(original))return hold(permit,Reason.SOURCE_CHANGED,back);
                if(ready.kind()==BingNavigationSnapshot.Kind.TEMPLATE){
                    if(!original.sameSource(ready)||!current(permit))return hold(permit,Reason.SOURCE_CHANGED,back);
                    return new Result(permit,ready,Status.RETURN_READY,Reason.ORIGINAL_SOURCE_READY_AFTER_BACK,back,observations,diagnostic(),false);
                }
            }
            if(i<1&&!pause())return hold(permit,Reason.AUTHORITY_LOST,back);
        }
        return hold(permit,Reason.RETURN_NOT_READY,back);
    }
    private boolean pause(){try{ports.pollPause();return true;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}}
}

