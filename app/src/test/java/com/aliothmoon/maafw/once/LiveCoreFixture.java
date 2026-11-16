package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.io.IOException;
import static org.junit.Assert.*;

/** Synthetic clock, journal and native-evidence boundaries; no Android input is invoked. */
final class LiveCoreFixture {
    static final String DAY="2026-11-16",RUN="00000000-0000-4000-8000-000000000001";
    static final class Clock implements LiveRunAuthority.Clock {
        long now=1000;String day=DAY;
        public long elapsedRealtime(){return now;}public String day(){return day;}
    }
    final Clock clock=new Clock();
    final LiveRewardsLedger ledger;
    final LiveRunAuthority authority;
    LiveCoreFixture()throws IOException{
        ledger=LiveRewardsLedger.synthetic(DAY,RUN);
        authority=LiveRunAuthority.synthetic(new Object(),new RealCaptureClaim.ProcessEpoch(),clock,()->true);
    }
    void attach(){authority.attachLedger(ledger);}
    static LiveRewardsTypes.Task task(LiveRewardsTypes.Kind kind,int ordinal,String name){
        return new LiveRewardsTypes.Task(kind,LiveRewardsTypes.hash(name),ordinal,LiveRewardsTypes.hash("TITLE_"+name),
                10,true,false,kind==LiveRewardsTypes.Kind.CHECK_IN?2:-1);
    }
    static LiveRewardsTypes.Task completed(LiveRewardsTypes.Task t){
        return new LiveRewardsTypes.Task(t.kind(),t.descriptorHash(),t.ordinal(),t.titleHash(),t.points(),true,true,t.day());
    }
    BingNavigationSnapshot.Ready source(){return LiveNavigationSnapshotTest.original(79,clock.now);}
    LiveRunAuthority.UiPermit ui(LiveRunAuthority.CardScope scope)throws IOException{
        var ui=scope.claimUi(new Object(),clock.now);assertTrue(ui.claimReader(clock.now));assertTrue(ui.consume(clock.now));
        scope.finishUi(ui,true,true,clock.now);return ui;
    }
    LiveRewardsTypes.Observation seal(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,
            LiveRewardsTypes.Task actual,int current,int target)throws IOException{
        return seal(scope,phase,actual,current,target,true);
    }
    LiveRewardsTypes.Observation seal(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,
            LiveRewardsTypes.Task actual,int current,int target,boolean unique)throws IOException{
        var ui=ui(scope);var crop=scope.claimCrop(ui,phase,clock.now);
        assertTrue(crop.bindRequest(new Box(20,100,420,500),523,1000,0,clock.now));
        assertTrue(crop.consume(clock.now));assertTrue(crop.claimDispatch(clock.now));scope.finishCrop(crop,true,true,clock.now);
        var data=new LiveRewardsTypes.ObservationData(actual,source(),clock.now,new Box(20,100,420,500),400,400,
                actual.complete()?null:new Box(280,100,360,150),.95,LiveRewardsTypes.hash("CAPTURE_"+phase+actual.key()),true,true,unique,current,target);
        return scope.sealObservation(crop,data);
    }
    LiveActionPermit permit(LiveRewardsTypes.Task t)throws IOException{
        attach();var scope=authority.cardScope(t);var before=seal(scope,LiveRewardsTypes.Phase.BEFORE,t,0,100);
        ledger.begin(t);return scope.actionPermit(before,ledger);
    }
}
