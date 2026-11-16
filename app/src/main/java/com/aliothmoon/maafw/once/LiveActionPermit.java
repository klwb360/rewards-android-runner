package com.aliothmoon.maafw.once;

import java.io.IOException;

/** A current native BEFORE observation rebound to one run/task and its durable pending record. */
final class LiveActionPermit {
    static final long RETURN_DELAY_MILLIS=3_000L;
    private final LiveRunAuthority.CardScope scope;
    private final LiveRewardsTypes.Observation before;
    private final LiveRewardsLedger ledger;
    private final ReviewShizukuTapActor.TapTarget target;
    private boolean tapActorAttempted,tapActorClaimed,tapConsumed,tapBurned,backActorAttempted,backActorClaimed,backConsumed,backBurned;
    private long tapSuccess=-1;

    private LiveActionPermit(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Observation before,LiveRewardsLedger ledger){
        this.scope=scope;this.before=before;this.ledger=ledger;
        var data=before.data();var local=data.localTarget();var roi=data.screenRoi();var s=before.source().profile().shape();
        target=new ReviewShizukuTapActor.TapTarget(roi.left()+(int)local.centerX(),roi.top()+(int)local.centerY(),s.width(),s.height(),s.rotation());
    }
    static LiveActionPermit create(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Observation before,LiveRewardsLedger ledger){
        long now=scope==null?-1:scope.run().now();
        if(scope==null||scope.preflight()||scope.scan()||before==null||ledger==null
                ||!scope.isSealed(before)||before.owner()!=scope.owner()||before.phase()!=LiveRewardsTypes.Phase.BEFORE
                ||!before.qualified()||!before.fresh(now)||!before.data().uniqueMatch()||before.task().complete()
                ||!before.task().sameKey(scope.task())||before.source().kind()!=BingNavigationSnapshot.Kind.TEMPLATE
                ||!before.source().fresh(now)||before.data().localTarget()==null||before.task().points()<1
                ||before.task().kind()==LiveRewardsTypes.Kind.CHECK_IN&&before.task().day()<1||!validGeometry(before))
            throw new SecurityException("Fresh unique native task and durable pending required");
        return new LiveActionPermit(scope,before,ledger);
    }
    private static boolean validGeometry(LiveRewardsTypes.Observation observation){
        var d=observation.data();var roi=d.screenRoi();var local=d.localTarget();var shape=d.source().profile().shape();
        if(roi==null||local==null||shape.width()<1||shape.height()<1||shape.width()>4096||shape.height()>4096
                ||(long)shape.width()*shape.height()>4L*1024*1024||shape.rotation()<0||shape.rotation()>3
                ||roi.left()<0||roi.top()<0||roi.right()>shape.width()||roi.bottom()>shape.height()
                ||roi.width()!=d.cropWidth()||roi.height()!=d.cropHeight()||d.cropWidth()<1||d.cropHeight()<1
                ||local.left()<=0||local.top()<=0||local.right()>=d.cropWidth()||local.bottom()>=d.cropHeight()
                ||local.width()<1||local.height()<1)return false;
        long x=(long)roi.left()+(local.left()+(long)local.right())/2;
        long y=(long)roi.top()+(local.top()+(long)local.bottom())/2;
        return x>=0&&x<shape.width()&&y>=0&&y<shape.height();
    }
    boolean live(){return scope.run().live();}
    boolean current(long now){return scope.current(now);}
    LiveRewardsTypes.Kind kind(){return scope.task().kind();}
    Object owner(){return scope.owner();}
    LiveRewardsTypes.Task task(){return scope.task();}
    LiveRunAuthority.CardScope scope(){return scope;}
    LiveRewardsTypes.Observation before(){return before;}
    LiveRewardsTypes.Observation beforeObservation(){return before;}
    BingNavigationSnapshot.Ready source(){return before.source();}
    BingNavigationSnapshot.Shape shape(){return source().profile().shape();}
    ReviewShizukuTapActor.TapTarget target(){return target;}
    boolean matchesBeforeSource(BingNavigationSnapshot.Ready fresh){
        long now=scope.run().now();return current(now)&&fresh!=null&&fresh.kind()==BingNavigationSnapshot.Kind.TEMPLATE
                &&fresh.fresh(now)&&source().sameSource(fresh);
    }
    synchronized boolean claimTapActor(long now){
        boolean first=!tapActorAttempted;tapActorAttempted=true;
        boolean accepted=first&&current(now)&&before.fresh(now)&&tapSuccess<0;if(accepted)tapActorClaimed=true;return accepted;
    }
    synchronized boolean consumeTap(long now)throws IOException{
        boolean first=!tapConsumed;tapConsumed=true;
        if(!first||!tapActorClaimed||!current(now)||!before.fresh(now)||tapSuccess>=0)return false;
        try{ledger.burnTap(task());tapBurned=true;return true;}
        catch(IOException|RuntimeException failed){scope.run().cancel();throw failed;}
    }
    synchronized boolean tapValid(long now){return tapActorClaimed&&tapBurned&&tapSuccess<0&&current(now)&&before.fresh(now);}
    synchronized void tapSucceeded(long elapsed){
        long now=scope.run().now();
        if(tapSuccess>=0||!tapActorClaimed||!tapBurned||elapsed<before.data().acquiredElapsed()
                ||elapsed>now||!current(now))throw new SecurityException("Exact clean successful command timestamp required");
        tapSuccess=elapsed;
    }
    synchronized long successfulTapElapsed(){return tapSuccess;}
    synchronized boolean claimBackActor(long now){
        boolean first=!backActorAttempted;backActorAttempted=true;
        boolean accepted=first&&kind()!=LiveRewardsTypes.Kind.CHECK_IN&&due(now)&&current(now);
        if(accepted)backActorClaimed=true;return accepted;
    }
    private boolean due(long now){return tapSuccess>=0&&now>=tapSuccess&&now-tapSuccess>=RETURN_DELAY_MILLIS;}
    synchronized boolean consumeBack(long now)throws IOException{
        boolean first=!backConsumed;backConsumed=true;
        if(!first||!backActorClaimed||kind()==LiveRewardsTypes.Kind.CHECK_IN||!due(now)||!current(now))return false;
        try{ledger.burnBack(task());backBurned=true;return true;}
        catch(IOException|RuntimeException failed){scope.run().cancel();throw failed;}
    }
    synchronized boolean backValid(long now){return backActorClaimed&&backBurned&&kind()!=LiveRewardsTypes.Kind.CHECK_IN&&due(now)&&current(now);}
    synchronized boolean tapAttempted(){return tapConsumed;}
    synchronized boolean backAttempted(){return backConsumed;}
}
