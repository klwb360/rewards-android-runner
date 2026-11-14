package com.aliothmoon.maafw.once;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Entry/lifecycle wiring checks only; no Activity, permission request, UI read or capture is run. */
public class UiImageAlignmentEntryTest {
    private static String source(String name)throws Exception{
        String relative="app/src/main/java/com/aliothmoon/maafw/once/"+name+".java";
        Path path=Path.of(System.getProperty("staging.root","."),relative);
        if(!Files.exists(path))path=Path.of("..",relative);
        return Files.readString(path);
    }
    private static String block(String source,String start,String end){
        int from=source.indexOf(start);assertTrue("Missing start: "+start,from>=0);
        int to=source.indexOf(end,from+start.length());assertTrue("Missing end: "+end,to>from);
        return source.substring(from,to);
    }
    @Test public void openingExplanationDoesNotCreateAuthorityOrStartRuntime()throws Exception{
        String a=source("DormantCaptureConfirmationActivity");
        String open=block(a,"private void openAlignmentChoice()","private void showAlignmentChoice()");
        assertTrue(open.contains("if(consumed||alignmentSpent||!isResumedForApproval())return"));
        assertTrue(open.contains("alignmentSelected=true;showAlignmentChoice()"));
        assertFalse(open.contains("new LiveClick"));assertFalse(open.contains("UiImageAlignmentRuntime.start"));
        assertFalse(open.contains("burn"));
    }
    @Test public void explicitConfirmationIsSpentBeforeFreshClickAndStart()throws Exception{
        String confirm=block(source("DormantCaptureConfirmationActivity"),"private void confirmAlignment()","void alignmentFinished(");
        int guard=confirm.indexOf("if(consumed||alignmentSpent||!alignmentSelected||!isResumedForApproval())return");
        int spent=confirm.indexOf("alignmentSpent=true;consumed=true");
        int click=confirm.indexOf("click=new LiveClick(this)");
        int start=confirm.indexOf("UiImageAlignmentRuntime.start(this,click,alignmentOwner)");
        assertTrue(guard>=0&&spent>guard&&click>spent&&start>click);
        assertTrue(confirm.contains("if(!NativeCheckInSession.permissionReady())throw"));
        assertTrue(confirm.contains("if(click!=null)click.invalidate()"));
        assertFalse(confirm.contains("RootReturnReadIntent"));assertFalse(confirm.contains("requestPermission"));
    }
    @Test public void authorityBindsExactSharedEpochVisibleOwnerAndFreshClick()throws Exception{
        String authority=block(source("VisibleCaptureAuthority"),"static Proof issueAlignment(","static final class ReturnReadApproval");
        assertTrue(authority.contains("CaptureReleaseGate.requireConfigured()"));
        assertTrue(authority.contains("UiImageAlignmentScope.sessionDeadline(now)"));
        assertTrue(authority.contains("epoch!=CaptureSessionRuntime.alignmentEpoch()"));
        assertTrue(authority.contains("!NativeCheckInSession.permissionReady()"));
        assertTrue(authority.contains("!activity.alignmentApprovalMatches(owner,click)"));
        assertTrue(authority.contains("!click.consume(activity,activity.requestIdentity(),owner,epoch)"));
        assertTrue(authority.contains("new Proof(epoch,owner,activity.requestIdentity(),now,deadline)"));
        assertFalse(authority.contains("ReadOnlyCheckpoint"));assertFalse(authority.contains("fixture"));
        assertFalse(authority.contains("System.currentTimeMillis"));
    }
    @Test public void alignmentApprovalCannotBorrowAnOldModeOwner()throws Exception{
        String matching=block(source("DormantCaptureConfirmationActivity"),"boolean alignmentApprovalMatches(","private void openAlignmentChoice()");
        assertTrue(matching.contains("alignmentSelected&&alignmentSpent&&!alignmentFinished"));
        assertTrue(matching.contains("owner==alignmentOwner"));assertTrue(matching.contains("incoming==click&&isResumedForApproval()"));
        assertFalse(matching.contains("owner==rootOwner"));
    }
    @Test public void v54StaysDefaultAndNewButtonOnlyOpensItsOwnConfirmation()throws Exception{
        String ui=source("OwnStateSummaryUi"),activity=source("DormantCaptureConfirmationActivity");
        assertTrue(ui.contains("static final boolean ACTIVE=true"));
        assertTrue(ui.contains("V54：只读本应用状态，不访问Bing"));
        assertTrue(ui.contains("打开截图对齐测试（尚不采集）"));assertTrue(ui.contains("确认只读：1次结构 + 1张截图"));
        assertTrue(ui.indexOf("button(a,box,BUTTON,run)")<ui.indexOf("button(a,box,ALIGNMENT_OPEN_BUTTON,openAlignment)"));
        String create=block(activity,"@Override protected void onCreate(Bundle state)","private volatile boolean ownStateCancelled");
        assertTrue(create.indexOf("if(OwnStateSummaryUi.ACTIVE)")<create.indexOf("installed=BingPackageMetadata.read"));
        assertTrue(create.contains("this::inspectOwnState,this::openAlignmentChoice,this::cancelAndFinish"));
        assertFalse(create.contains("UiImageAlignmentRuntime.start"));
        assertTrue(ui.contains("不保存或发送原图"));assertTrue(ui.contains("不代表任务完成或赚分"));
    }
    @Test public void newRouteDoesNotRequestPermissionOrNavigateBing()throws Exception{
        String a=source("DormantCaptureConfirmationActivity");
        String route=block(a,"private volatile UiImageAlignmentRuntime alignmentRuntime","private boolean returnReadSpent");
        for(String forbidden:new String[]{"requestPermission(","startActivity(","moveToFront(","ReviewShizukuBackActor","ReviewShizukuSwipeActor","NativeAutonomousCardRun"})
            assertFalse(forbidden,route.contains(forbidden));
        assertTrue(route.contains("本次不申请权限，也不采集"));
    }
    @Test public void normalPauseDoesNotRevokeButReentryAndDestroyDo()throws Exception{
        String a=source("DormantCaptureConfirmationActivity");
        String pause=block(a,"@Override protected void onPause()","@Override protected void onActivityResult(");
        assertFalse(pause.contains("alignmentRuntime.cancel"));
        String start=block(a,"@Override protected void onStart()","if(returnReadIntent!=null){");
        assertTrue(start.contains("if(alignmentRuntime!=null&&!alignmentFinished)alignmentRuntime.cancel()"));
        assertTrue(start.contains("if(alignmentFinished)OwnStateSummaryUi.alignmentStatus"));
        String destroy=a.substring(a.indexOf("@Override protected void onDestroy()"));
        assertTrue(destroy.contains("if(alignmentRuntime!=null)alignmentRuntime.cancel()"));
        String cancel=block(a,"private void cancelAndFinish()","/** Only ordinary superclass UI state");
        assertTrue(cancel.contains("if(alignmentRuntime!=null)alignmentRuntime.cancel()"));
        String handoff=block(a,"private void tryHandoff()","boolean hiddenWithSoleTask()");
        assertTrue(handoff.contains("if(alignmentRuntime!=null)alignmentRuntime.cancel()"));
    }
    @Test public void publicationRunsOnceInsideVisibilityFenceAndAlwaysCancelsAfterAppend()throws Exception{
        String finish=block(source("DormantCaptureConfirmationActivity"),"void alignmentFinished(","private boolean returnReadSpent");
        assertTrue(finish.contains("stageDeadlines.post(()->"));
        assertTrue(finish.contains("}))exact.cancel()"));
        assertTrue(finish.contains("alignmentRuntime!=exact||alignmentFinished"));
        int fence=finish.indexOf("ReviewVisibilityProvider.FENCE.publish(visibilityLease,()->");
        int publish=finish.indexOf("exact.finalizePublication()");
        assertTrue(fence>=0&&publish>fence);
        assertEquals(publish,finish.lastIndexOf("exact.finalizePublication()"));
        assertTrue(finish.contains("if(rootHiddenScalar()&&hiddenWithSoleTask())"));
        assertTrue(finish.contains("stage=APPEND_REJECTED"));
        assertTrue(finish.contains("exact.cancel();alignmentFinished=true;terminalShown=true"));
        assertTrue(finish.contains("new TerminalSnapshot(true,false,\"CURRENT_STATUS\",false)"));
        assertFalse(finish.contains("moveToFront"));assertFalse(finish.contains("startActivity"));
    }
    @Test public void legacyCheckpointStillRejectsImageClaimsAndHasNoNewNamespace()throws Exception{
        String policy=source("ReadOnlyCheckpoint"),authority=source("VisibleCaptureAuthority");
        assertTrue(policy.contains("static final ReadOnlyCheckpoint CURRENT=UI_BOUND_POINTS_V53"));
        assertTrue(policy.contains("UI checkpoint cannot create an image claim"));
        assertFalse(policy.contains("UiImageAlignment"));
        String frame=block(authority,"static Proof issueCheckpointFrame(","static RootReturnReadIntent issueProgressScanIntent(");
        assertTrue(frame.contains("i==null||i.uiStructure()||i.checkpoint()!=ReadOnlyCheckpoint.CURRENT"));
    }
    @Test public void rejectedStartupAndAppendKeepCleanupUncertaintyExplicit()throws Exception{
        String a=source("DormantCaptureConfirmationActivity");
        String start=block(a,"private void confirmAlignment()","void alignmentFinished(");
        String append=block(a,"void alignmentFinished(","private boolean returnReadSpent");
        assertTrue(start.contains("stage=START_REJECTED cleanup="));
        assertTrue(start.contains("alignmentRuntime!=null&&alignmentRuntime.finishedAndReleased()?\"CONFIRMED\":\"NOT_CONFIRMED\""));
        assertTrue(append.contains("stage=APPEND_REJECTED cleanup="));
        assertTrue(append.contains("exact.finishedAndReleased()?\"CONFIRMED\":\"NOT_CONFIRMED\""));
        for(String raw:new String[]{"getMessage()","getLocalizedMessage()","printStackTrace(","rejected.toString()"}){
            assertFalse(raw,start.contains(raw));assertFalse(raw,append.contains(raw));
        }
    }
}
