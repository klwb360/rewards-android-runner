package com.aliothmoon.maafw.once;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;
public class NativeAutonomousRunContractTest {
    private static final long UTC=AutonomousRewardCardScope.STARTS_UTC+60_000;
    @Test public void actualRunAuthorityAllowsConstructionButNoUnreservedOperation(){
        AtomicLong now=new AtomicLong(1000);var run=new NativeAutonomousCardRun(now::get,()->UTC);
        assertTrue(run.current());assertTrue(run.authorityForContractTest().scopeCurrent());
        assertFalse(run.authorityForContractTest().sourceStillMatches(523,1000,0));
        assertNull(run.sessionForContractTest().startCapture(NativeRewardCardSession.Phase.BEFORE,523,1000,0));
        try{run.deadline(now.get());fail("No phase deadline before reserve");}catch(SecurityException expected){}
    }
    @Test public void actualRunDelegateChangesToHardReservedPhaseDeadline(){
        AtomicLong now=new AtomicLong(1000);var run=new NativeAutonomousCardRun(now::get,()->UTC);
        run.reserve(NativeRewardCardSession.Phase.BEFORE);long end=run.deadline(now.get());assertEquals(91000,end);
        assertTrue(run.authorityForContractTest().scopeCurrent());now.set(end);
        assertTrue("Parent grant still alive",run.current());assertFalse("Reserved operation expired",run.authorityForContractTest().scopeCurrent());
        try{run.reserve(NativeRewardCardSession.Phase.BEFORE);fail("No phase renewal");}catch(SecurityException expected){}
    }
    @Test public void constructorGrantAndCancelAreNotFakeAlwaysTrueAuthority(){
        try{new NativeAutonomousCardRun(()->1000L,()->AutonomousRewardCardScope.EXPIRES_UTC);fail("Expired real Scope contract");}catch(SecurityException expected){}
        var run=new NativeAutonomousCardRun(()->1000L,()->UTC);run.cancel();assertFalse(run.current());assertFalse(run.authorityForContractTest().scopeCurrent());
    }
    private static NativeRewardCardSession.Observation observation(boolean more,byte[] pixels,List<FrameStructureEvidence.Line> rows){
        var a=new NativeRewardCardSession.Authority(){public long elapsedRealtime(){return 1000;}public boolean scopeCurrent(){return true;}
            public boolean sourceStillMatches(int w,int h,int r){return true;}public void consumeOneTap(ReviewShizukuTapActor.TapTarget t){throw new AssertionError();}};
        var s=new NativeRewardCardSession("structural-contract",10000,a,new ReviewShizukuTapActor(()->{throw new AssertionError();}),more?LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay():LiveRewardCardObservation.TargetSpec.dailyRegionCounter());
        var c=s.startCapture(NativeRewardCardSession.Phase.BEFORE,523,1000,0);return c.prepare(pixels,523,1000,rows,()->false);
    }
    @Test public void realRunEligibilityRejectsNullWrongRouteAndInvalidNativeStructure(){
        assertFalse(NativeAutonomousCardRun.nativeObservationEligible(null));
        assertFalse(NativeAutonomousCardRun.nativeObservationEligible(observation(false,RewardCardFixture.pixels(false,false),RewardCardFixture.rows(false,false))));
        assertFalse(NativeAutonomousCardRun.nativeObservationEligible(observation(true,new byte[1],MoreCardFixture.rows(false))));
        assertFalse(NativeAutonomousCardRun.nativeObservationEligible(observation(true,MoreCardFixture.pixels(false),null)));
        assertTrue(NativeAutonomousCardRun.nativeObservationEligible(observation(true,MoreCardFixture.pixels(false),List.of(MoreCardFixture.line("Unrelated",20,20,200,20)))));
    }
}
