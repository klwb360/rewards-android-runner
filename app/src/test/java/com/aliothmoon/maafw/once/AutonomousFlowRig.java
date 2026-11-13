package com.aliothmoon.maafw.once;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Pure synthetic transport. No Android or Shizuku call is made by this rig. */
final class AutonomousFlowRig implements NativeRewardCardSession.Authority,NativeBingReturnController.Ports {
    enum Step { TEMPLATE, BROWSER, TRANSITION, UNKNOWN, DIFFERENT_BROWSER, DIFFERENT_TASK }
    long now=1000;int sourceReads,taps,backs,tapBurns,returnBurns,observations;
    boolean current=true,source=true,returnBurnThrows,backThrows,backTimeout;
    Runnable onReturnBurn=()->{};
    final ArrayDeque<Step> steps=new ArrayDeque<>();Step last=Step.UNKNOWN;
    final NativeRewardCardSession session;
    final ReviewShizukuBackActor backActor;
    final NativeBingReturnController controller;
    AutonomousFlowRig(Step... steps){
        this.steps.addAll(Arrays.asList(steps));
        backActor=new ReviewShizukuBackActor(()->new ReviewShizukuBackActor.Session(){
            public ReviewShizukuBackActor.DisplayShape display(){return new ReviewShizukuBackActor.DisplayShape(523,1000,0);}
            public boolean stillReady(){return true;}
            public ReviewShizukuBackActor.PendingBack dispatch(ReviewShizukuBackActor.BackTarget t,ReviewShizukuBackActor.FinalGate gate)throws Exception{
                gate.requireValid();backs++;if(backThrows)throw new Exception("fake uncertain Back");
                return new ReviewShizukuBackActor.PendingBack(){public boolean waitForExit(long millis){return !backTimeout;}public int exitCode(){return 0;}public void destroy(){}public void close(){}};
            }
        });
        controller=new NativeBingReturnController("synthetic-autonomous",this,backActor);
        var tap=new ReviewShizukuTapActor(()->new ReviewShizukuTapActor.Session(){
            public ReviewShizukuTapActor.DisplayShape display(){return new ReviewShizukuTapActor.DisplayShape(523,1000,0);}
            public boolean stillReady(){return true;}
            public ReviewShizukuTapActor.PendingTap dispatch(ReviewShizukuTapActor.TapTarget t,ReviewShizukuTapActor.FinalGate gate)throws Exception{
                gate.requireValid();if(t.x()!=439||t.y()!=329)throw new AssertionError("Not native first-body target");taps++;
                return new ReviewShizukuTapActor.PendingTap(){public boolean waitForExit(long millis){return true;}public int exitCode(){return 0;}public void destroy(){}public void close(){}};
            }
        });
        session=new NativeRewardCardSession("synthetic-autonomous",100000,this,tap,LiveRewardCardObservation.TargetSpec.moreTriviaChallenge(),controller);
    }
    public long elapsedRealtime(){return now;}
    public boolean scopeCurrent(){return current;}
    public boolean sourceStillMatches(int w,int h,int r){sourceReads++;return source&&current&&w==523&&h==1000&&r==0;}
    public void consumeOneTap(ReviewShizukuTapActor.TapTarget t){tapBurns++;}
    public boolean authorityCurrent(BingNavigationSnapshot.Shape shape){return current&&shape.equals(BingNavigationFixture.SHAPE);}
    public BingNavigationSnapshot.Ready originalSource(){return BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,1000).ready();}
    public BingNavigationSnapshot.Result observe(BingNavigationSnapshot.Shape shape){
        observations++;now+=50;if(!steps.isEmpty())last=steps.removeFirst();
        return switch(last){
            case TEMPLATE->BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,now);
            case BROWSER->BingNavigationFixture.result(BingNavigationSnapshot.Kind.BROWSER,now);
            case DIFFERENT_BROWSER->BingNavigationFixture.result(BingNavigationSnapshot.Kind.BROWSER,now,"bbbb","eeee","15");
            case DIFFERENT_TASK->BingNavigationFixture.result(BingNavigationSnapshot.Kind.BROWSER,now,"bbb","eee","16");
            case TRANSITION->new BingNavigationSnapshot.Result(BingNavigationSnapshot.Status.BING_TRANSITION,null);
            case UNKNOWN->BingNavigationSnapshot.Result.unknown();
        };
    }
    public void consumeReturn(ReviewShizukuBackActor.BackTarget t)throws Exception{returnBurns++;onReturnBurn.run();if(returnBurnThrows)throw new Exception("fake uncertain durable return");}
    public void pollPause(){now+=100;}
    NativeRewardCardSession.Receipt before(){return before(100000);}
    NativeRewardCardSession.Receipt before(long deadline){
        var c=session.startCapture(NativeRewardCardSession.Phase.BEFORE,523,1000,0);
        if(c==null)throw new AssertionError("Missing BEFORE capture");
        var o=c.prepare(AutonomousCardFixture.pixels(false,false),523,1000,AutonomousCardFixture.rows(false,false),()->false);
        if(o.configuredTarget()==null)throw new AssertionError("Missing exact native body target: "+o.reason());
        return session.acceptAfterCleanup(c,o,true,()->true,o.configuredTarget(),deadline);
    }
    NativeRewardCardSession.Receipt after(boolean aComplete,boolean bComplete){return after(AutonomousCardFixture.pixels(aComplete,bComplete),AutonomousCardFixture.rows(aComplete,bComplete));}
    NativeRewardCardSession.Receipt after(byte[] pixels,java.util.List<FrameStructureEvidence.Line> rows){
        now+=1000;var c=session.startCapture(NativeRewardCardSession.Phase.AFTER,523,1000,0);
        if(c==null)throw new AssertionError("Missing AFTER capture");var o=c.prepare(pixels,523,1000,rows,()->false);
        return session.acceptAfterCleanup(c,o,true,()->true,null,100000);
    }
}
