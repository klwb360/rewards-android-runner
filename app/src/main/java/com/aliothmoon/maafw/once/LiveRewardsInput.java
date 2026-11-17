package com.aliothmoon.maafw.once;

import android.content.Context;
import android.os.SystemClock;

/** Exact live-card inputs. A successful command never proves task completion or account credit. */
final class LiveRewardsInput {
    static final long RETURN_DELAY_MS=LiveActionPermit.RETURN_DELAY_MILLIS;
    enum VisitPage { ORIGINAL_REWARDS, BOUND_CHILD, BLOCKED, UNKNOWN }
    @FunctionalInterface interface BeforeTapGuard { boolean inspect(LiveActionPermit permit) throws Exception; }
    interface Ports {
        long elapsedRealtime();
        void pause(long millis) throws InterruptedException;
        boolean current();
        boolean knownClean();
        BingNavigationSnapshot.Ready template();
        boolean beforeTap(LiveActionPermit permit) throws Exception;
        ReviewShizukuTapActor tapActor(LiveActionPermit permit);
        ReviewShizukuBackActor backActor(LiveActionPermit permit);
    }
    static final class TapReceipt {
        private final LiveActionPermit permit;
        private final boolean success,clean;
        private final long successful;
        private final String reason;
        private final ReviewShizukuTapActor.Result actorResult;
        private TapReceipt(LiveActionPermit permit,boolean success,boolean clean,long successful,String reason,ReviewShizukuTapActor.Result actorResult){
            this.permit=permit;this.success=success;this.clean=clean;this.successful=successful;this.reason=reason;this.actorResult=actorResult;
        }
        boolean success(){return success;}boolean knownClean(){return clean;}long successfulElapsed(){return successful;}
        String reason(){return reason;}
        ReviewShizukuTapActor.Result actorResult(){return actorResult;}
        boolean invocationAttempted(){return actorResult!=null&&actorResult.invocationAttempted();}
        static TapReceipt synthetic(LiveActionPermit permit,boolean success,boolean clean,long successful){
            if(permit==null||permit.live())throw new SecurityException("Synthetic receipt only");
            if(success)permit.tapSucceeded(successful);
            return new TapReceipt(permit,success,clean,success?successful:-1,"SYNTHETIC",null);
        }
    }
    static final class ReturnReceipt {
        private final LiveActionPermit permit;
        private final boolean ready,clean,backDispatched;
        private final BingNavigationSnapshot.Ready source;
        private final long backGateElapsed;
        private final String reason;
        private final ReviewShizukuBackActor.Result actorResult;
        private ReturnReceipt(LiveActionPermit permit,boolean ready,boolean clean,BingNavigationSnapshot.Ready source,
                boolean backDispatched,long backGateElapsed,String reason,ReviewShizukuBackActor.Result actorResult){
            this.permit=permit;this.ready=ready;this.clean=clean;this.source=source;this.backDispatched=backDispatched;
            this.backGateElapsed=backGateElapsed;this.reason=reason;this.actorResult=actorResult;
        }
        /** Compatibility alias: confirms only the Back command, never a Rewards page. */
        boolean ready(){return commandConfirmed();}
        boolean commandConfirmed(){return ready;}
        boolean matchesPermit(LiveActionPermit exact){return permit==exact;}
        boolean knownClean(){return clean;}BingNavigationSnapshot.Ready source(){return source;}
        boolean backDispatched(){return backDispatched;}long backGateElapsed(){return backGateElapsed;}String reason(){return reason;}
        boolean backAttempted(){return backDispatched;}
        ReviewShizukuBackActor.Result actorResult(){return actorResult;}
        static ReturnReceipt synthetic(LiveActionPermit permit,boolean ready,boolean clean,BingNavigationSnapshot.Ready source){
            if(permit==null||permit.live())throw new SecurityException("Synthetic return receipt only");
            if(ready&&!permit.backValid(permit.scope().run().now()))throw new SecurityException("Consumed due synthetic Back required");
            return new ReturnReceipt(permit,ready,clean,source,false,-1,"SYNTHETIC",null);
        }
    }
    private final LiveRunAuthority authority;
    private final Ports ports;
    private ReviewShizukuBackActor.Result lastBackResult;
    private boolean inputKnownClean=true;
    boolean knownClean(){return inputKnownClean&&ports.knownClean();}
    LiveRewardsInput(Context context,LiveRunAuthority authority,LiveRewardsNavigation navigation,BeforeTapGuard beforeTap){
        if(context==null||authority==null||!authority.live()||navigation==null||beforeTap==null)
            throw new IllegalArgumentException("Visible live input owner and pre-tap guard required");
        this.authority=authority;
        ports=new Ports(){
            public long elapsedRealtime(){return SystemClock.elapsedRealtime();}
            public void pause(long millis)throws InterruptedException{Thread.sleep(millis);}
            public boolean current(){return authority.current();}
            public boolean knownClean(){return navigation.knownClean();}
            public BingNavigationSnapshot.Ready template(){return navigation.template();}
            public boolean beforeTap(LiveActionPermit permit)throws Exception{return beforeTap.inspect(permit);}
            public ReviewShizukuTapActor tapActor(LiveActionPermit permit){return ReviewShizukuTapActor.forLiveRun(context,permit);}
            public ReviewShizukuBackActor backActor(LiveActionPermit permit){return ReviewShizukuBackActor.forLiveRun(context,permit);}
        };
    }
    private LiveRewardsInput(LiveRunAuthority authority,Ports ports){this.authority=authority;this.ports=ports;}
    static LiveRewardsInput synthetic(LiveRunAuthority authority,Ports ports){
        if(authority==null||authority.live()||ports==null)throw new SecurityException("Synthetic input fixture only");
        return new LiveRewardsInput(authority,ports);
    }
    private boolean current(LiveActionPermit permit){
        return permit!=null&&permit.scope().run()==authority&&permit.live()==authority.live()
                &&authority.current()&&permit.current(ports.elapsedRealtime())&&ports.current()&&knownClean()
                &&!Thread.currentThread().isInterrupted();
    }
    TapReceipt tap(LiveActionPermit permit){
        if(!current(permit))return tapStopped(permit,"AUTHORITY_LOST");
        ReviewShizukuTapActor.Result savedResult=null;
        try{
            if(!permit.matchesBeforeSource(ports.template())||!current(permit))return tapStopped(permit,"SOURCE_CHANGED");
            var actor=ports.tapActor(permit);var target=permit.target();
            var result=actor.tap(target,new ReviewShizukuTapActor.DispatchGate(){
                public boolean consume(ReviewShizukuTapActor.TapTarget exact)throws Exception{
                    if(exact!=target||!current(permit)||!ports.beforeTap(permit)||!permit.matchesBeforeSource(ports.template()))return false;
                    return permit.consumeTap(ports.elapsedRealtime())&&current(permit);
                }
                public boolean stillValid(ReviewShizukuTapActor.TapTarget exact)throws Exception{
                    return exact==target&&current(permit)&&ports.beforeTap(permit)&&permit.matchesBeforeSource(ports.template())
                            &&permit.tapValid(ports.elapsedRealtime())&&current(permit);
                }
            });
            savedResult=result;
            boolean clean=actor.knownClean()&&ports.knownClean();
            inputKnownClean&=clean;
            boolean success=result!=null&&result.commandExitZero()&&clean&&current(permit);
            long elapsed=success?ports.elapsedRealtime():-1;
            if(success)permit.tapSucceeded(elapsed);
            return new TapReceipt(permit,success,clean,elapsed,result==null?"TAP_NO_RESULT":result.reason().name(),result);
        }catch(RuntimeException failed){return new TapReceipt(permit,false,knownClean(),-1,"TAP_REJECTED",savedResult);}
    }
    private TapReceipt tapStopped(LiveActionPermit permit,String reason){return new TapReceipt(permit,false,knownClean(),-1,reason,null);}
    ReturnReceipt returnAfterTap(LiveActionPermit permit,TapReceipt tapped){
        lastBackResult=null;
        if(permit==null||permit.kind()==LiveRewardsTypes.Kind.CHECK_IN)return stopped(permit,false,-1,"CHECK_IN_RETURN_FORBIDDEN");
        if(tapped==null||tapped.permit!=permit||!tapped.success||!tapped.clean
                ||tapped.successful!=permit.successfulTapElapsed()||!current(permit))return stopped(permit,false,-1,"TAP_RECEIPT_REJECTED");
        long successful=tapped.successful;
        if(successful<0||successful>Long.MAX_VALUE-RETURN_DELAY_MS)return stopped(permit,false,-1,"INVALID_TAP_CLOCK");
        long due=successful+RETURN_DELAY_MS;
        boolean dispatched=false;long[] finalGate={-1};
        try{
            int waits=0;long previous=ports.elapsedRealtime();
            while(ports.elapsedRealtime()<due){
                long now=ports.elapsedRealtime();
                if(now<previous||now<successful||!current(permit)||waits++>=256)return stopped(permit,false,-1,"WAIT_AUTHORITY_LOST");
                previous=now;ports.pause(Math.min(50,due-now));
            }
            if(!current(permit)||ports.elapsedRealtime()<due)return stopped(permit,false,-1,"WAIT_AUTHORITY_LOST");
            // The approved return policy has no page, navigation, screenshot, UI or OCR reads.
            var actor=ports.backActor(permit);var shape=permit.shape();
            var target=new ReviewShizukuBackActor.BackTarget(shape.width(),shape.height(),shape.rotation());
            var result=actor.back(target,new ReviewShizukuBackActor.DispatchGate(){
                private boolean consumed;
                public boolean consume(ReviewShizukuBackActor.BackTarget candidate)throws Exception{
                    if(candidate!=target||consumed||!current(permit)||ports.elapsedRealtime()<due)return false;consumed=true;
                    return permit.consumeBack(ports.elapsedRealtime())&&current(permit);
                }
                public boolean stillValid(ReviewShizukuBackActor.BackTarget candidate){
                    if(candidate!=target||!consumed||!current(permit)||!permit.backValid(ports.elapsedRealtime()))return false;
                    finalGate[0]=ports.elapsedRealtime();return finalGate[0]>=due&&current(permit);
                }
            });
            lastBackResult=result;
            inputKnownClean&=actor.knownClean()&&ports.knownClean();
            dispatched=result!=null&&result.invocationAttempted();
            if(result==null||!result.commandExitZero()||!actor.knownClean()||!ports.knownClean())
                return stopped(permit,dispatched,finalGate[0],"BACK_NOT_CONFIRMED");
            if(!current(permit))return stopped(permit,dispatched,finalGate[0],"RETURN_AUTHORITY_LOST");
            // A known command exit is not a page or completion claim. The coordinator observes next.
            return new ReturnReceipt(permit,true,knownClean(),null,dispatched,finalGate[0],"BACK_COMMAND_CONFIRMED",lastBackResult);
        }catch(InterruptedException canceled){Thread.currentThread().interrupt();return stopped(permit,dispatched,finalGate[0],"CANCELED");}
        catch(Exception unknown){return stopped(permit,dispatched,finalGate[0],"RETURN_REJECTED");}
    }
    private ReturnReceipt stopped(LiveActionPermit permit,boolean dispatched,long gate,String reason){
        return new ReturnReceipt(permit,false,knownClean(),null,dispatched,gate,reason,lastBackResult);
    }
    /** Legacy passive diagnostics only; the five-second return path never calls this classifier. */
    static VisitPage classifyVisit(LiveActionPermit permit,BingNavigationSnapshot.LiveVisitSource before,
            LiveVisitGuard.Report guard,BingNavigationSnapshot.LiveVisitResult after,long now){
        if(permit==null||!permit.current(now)||before==null||guard==null||after==null||after.source()==null||!guard.qualified()
                ||!before.matchesOriginal(permit.source())||!before.sameSource(after.source())||!after.source().fresh(now))return VisitPage.UNKNOWN;
        var ui=guard.source();var shape=permit.shape();
        if(before.ready().observed()>ui.started()||!before.ready().fresh(ui.started())||ui.finished()>after.source().ready().lower()
                ||ui.displayId()!=0||ui.left()<0||ui.top()<0||ui.right()>shape.width()||ui.bottom()>shape.height()
                ||ui.right()<=ui.left()||ui.bottom()<=ui.top()
                ||ui.rotationBefore()!=-1&&ui.rotationBefore()!=shape.rotation()
                ||ui.rotationAfter()!=-1&&ui.rotationAfter()!=shape.rotation())return VisitPage.UNKNOWN;
        return switch(guard.hint().name()){
            case "REWARDS"->after.kind()==BingNavigationSnapshot.LiveVisitKind.ORIGINAL_REWARDS?VisitPage.ORIGINAL_REWARDS:VisitPage.UNKNOWN;
            case "SERP"->VisitPage.BOUND_CHILD;
            case "WEB_CONTAINER"->after.source().ready().kind()==BingNavigationSnapshot.Kind.BROWSER?VisitPage.BOUND_CHILD:VisitPage.UNKNOWN;
            case "BLOCKED"->VisitPage.BLOCKED;
            default->VisitPage.UNKNOWN;
        };
    }
}
