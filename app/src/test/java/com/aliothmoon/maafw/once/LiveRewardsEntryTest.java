package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.nio.file.*;
import org.junit.Test;

/** Architectural boundaries for the new visible live entry; no Android or real input. */
public class LiveRewardsEntryTest {
    private String source(String name)throws Exception{
        return Files.readString(Path.of(System.getProperty("staging.root",".."),
                "app/src/main/java/com/aliothmoon/maafw/once",name+".java"));
    }
    private String body(String source,String begin,String end){
        int start=source.indexOf(begin);assertTrue(start>=0);int stop=source.indexOf(end,start);assertTrue(stop>start);
        return source.substring(start,stop);
    }
    @Test public void freshVisibleApprovalCannotBeMintedByLegacyOrExternalConfiguration()throws Exception{
        String issuer=body(source("VisibleCaptureAuthority"),"static LiveRewardsApproval issueLiveRewardsApproval(",
                "static LiveRewardsApproval fixtureLiveRewardsApproval(");
        for(String required:new String[]{"CaptureReleaseGate.requireConfigured()","epoch!=CaptureSessionRuntime.alignmentEpoch()",
                "!NativeCheckInSession.permissionReady()","!activity.liveRewardsApprovalMatches(owner,click)",
                "!click.consume(activity,activity.requestIdentity(),owner,epoch)","now+1_800_000L,true"})assertTrue(required,issuer.contains(required));
        for(String forbidden:new String[]{"getIntent(","getSharedPreferences","System.getenv","fixture","set(false)"})assertFalse(forbidden,issuer.contains(forbidden));
        assertTrue(ReadOnlyCheckpoint.blocksLiveInput());
    }
    @Test public void fixtureApprovalIsNotLiveAndCanBeConsumedOnlyOnce(){
        var approval=VisibleCaptureAuthority.fixtureLiveRewardsApproval(new RealCaptureClaim.ProcessEpoch(),new Object(),100);
        assertFalse(approval.live);assertTrue(approval.consume(100));assertFalse(approval.consume(101));
        var expired=VisibleCaptureAuthority.fixtureLiveRewardsApproval(new RealCaptureClaim.ProcessEpoch(),new Object(),100);
        assertFalse(expired.consume(expired.deadline));assertFalse(expired.consume(101));
    }
    @Test public void liveCropHasSeparateTypedAuthorityWhileLegacyCropRemainsOneShot()throws Exception{
        String transport=source("AndroidCardRegionCapture");
        String live=body(transport,"/** Independent live-run crop authority", "private static boolean live(CardRevealProbeIntent.UiPermit");
        for(String required:new String[]{"LiveRunAuthority.CropPermit permit","!permit.live()","permit.bindRequest(",
                "gate.consume()","permit.claimDispatch(","process=service.newProcess("})assertTrue(required,live.contains(required));
        for(String bad:new String[]{"fixture","PROCESS_SPENT","set(false)","screencap","captureDisplayBitmap"})assertFalse(bad,live.contains(bad));
    }
    @Test public void productionRuntimeRequiresHiddenOwnerAndConfirmedCleanupBeforeRelease()throws Exception{
        String runtime=source("LiveRewardsRuntime");
        assertTrue(runtime.contains("VisibleCaptureAuthority.issueLiveRewardsApproval("));
        assertTrue(runtime.contains("activity.rootHiddenScalar()&&activity.hiddenWithSoleTask()"));
        assertTrue(runtime.contains("if(!allResourcesClosed()){stage=\"CLEANUP_UNKNOWN\""));
        assertTrue(runtime.contains("root.releaseIfClosed()"));
        assertTrue(runtime.contains("finally{recordTap(permit,result);}"));
        assertTrue(runtime.contains("finally{recordReturn(permit,receipt,result);}"));
        for(String bad:new String[]{"synthetic(","forTest(","PROCESS_SPENT.set","TRUNCATE_EXISTING"})assertFalse(bad,runtime.contains(bad));
    }
    @Test public void aRejectedOwnTaskHandoffCancelsTheNewRunImmediately()throws Exception{
        String handoff=body(source("DormantCaptureConfirmationActivity"),"private void tryHandoff()", "boolean hiddenWithSoleTask()");
        int failure=handoff.indexOf("catch(RuntimeException failure)");assertTrue(failure>=0);
        assertTrue(handoff.substring(failure).contains("if(liveRewardsRuntime!=null)liveRewardsRuntime.cancel()"));
    }
}
