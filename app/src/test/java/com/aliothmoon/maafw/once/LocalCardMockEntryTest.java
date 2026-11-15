package com.aliothmoon.maafw.once;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Visible static-material admission contracts only; no Android or OCR is executed here. */
public class LocalCardMockEntryTest {
    private String source(String name)throws Exception {
        return Files.readString(Path.of(System.getProperty("staging.root",".."),
                "app/src/main/java/com/aliothmoon/maafw/once",name+".java"));
    }
    private String between(String text,String start,String end) {
        int from=text.indexOf(start),to=from<0?-1:text.indexOf(end,from+start.length());
        assertTrue("Missing source boundary: "+start,from>=0&&to>from);
        return text.substring(from,to);
    }
    private void ordered(String text,String... terms) {
        int previous=-1;
        for(String term:terms){int next=text.indexOf(term,previous+1);
            assertTrue("Missing or unordered: "+term,next>previous);previous=next;}
    }
    private void excludes(String text,String... terms) {
        for(String term:terms)assertFalse(term,text.contains(term));
    }

    @Test public void explanationCannotRunAndFreshConfirmationSpendsBeforeRuntimeStarts()throws Exception {
        String activity=source("DormantCaptureConfirmationActivity");
        String open=between(activity,"private void openLocalCardMockChoice()","private void showLocalCardMockChoice()");
        excludes(open,"new LiveClick","LocalCardMockRuntime.start","requestPermission(","burn(");
        String confirm=between(activity,"private void confirmLocalCardMock()","boolean localCardMockPublish(");
        ordered(confirm,"!localMockSelected||!isResumedForApproval()","localMockSpent=true;consumed=true",
                "click=new LiveClick(this)","LocalCardMockRuntime.start(this,click,localMockOwner)");
        excludes(confirm,"consumed=false","localMockSpent=false","requestPermission(","CardRevealProbeRuntime.start",
                "CaptureSessionRuntime.start(","requestHandoff(");
    }
    @Test public void staticApprovalUsesTheExactOwnerClickAndCurrentVisibleActivity()throws Exception {
        String activity=source("DormantCaptureConfirmationActivity");
        String proof=between(activity,"boolean localCardMockApprovalMatches(","boolean localCardMockCurrent(");
        ordered(proof,"localMockSelected&&localMockSpent&&!localMockFinished","owner==localMockOwner",
                "incoming==click&&isResumedForApproval()");
        excludes(proof,"owner==cardRevealOwner","owner==rootOwner","owner==alignmentOwner","getIntent(",
                "getSharedPreferences(");
        String current=between(activity,"boolean localCardMockCurrent(","private void openLocalCardMockChoice()");
        assertTrue(current.contains("localMockVisible&&exact!=null&&exact==localMockRun"));
        assertTrue(current.contains("!localMockFinished&&!destroyed"));
        excludes(current,"getPackageManager(","getSystemService(","getContentResolver(","isResumedForApproval()");
    }
    @Test public void publicationRequiresTheSameRuntimeAndFreshVisibleChecksAroundAppend()throws Exception {
        String publish=between(source("DormantCaptureConfirmationActivity"),"boolean localCardMockPublish(","void localCardMockFinished(");
        ordered(publish,"Looper.myLooper()!=android.os.Looper.getMainLooper()",
                "!localCardMockCurrent(exact)||!isResumedForApproval()","append.run()",
                "return localCardMockCurrent(exact)&&isResumedForApproval()");
        excludes(publish,"FENCE.publish","visibilityLease","rootHiddenScalar()","issueOverviewFrame(",
                "RealCaptureClaim.visible(");
    }
    @Test public void pausingOrLosingFocusCancelsWithoutReopeningTheConsumedEntry()throws Exception {
        String activity=source("DormantCaptureConfirmationActivity");
        String start=between(activity,"@Override protected void onStart()","@Override protected void onStop()");
        String local=between(start,"if(localMockSpent)","if(cardRevealSpent)");
        assertTrue(local.contains("if(localMockFinished)LocalCardMockUi.status"));
        assertTrue(local.contains("else LocalCardMockUi.running"));
        excludes(local,"localMockRun.cancel()","LocalCardMockRuntime.start(","localMockSpent=false");
        String focus=between(activity,"@Override public void onWindowFocusChanged(","@Override protected void onResume()");
        ordered(focus,"localMockVisible=focus&&resumed&&!destroyed",
                "if(!focus&&localMockRun!=null&&!localMockFinished)localMockRun.cancel()");
        String pause=between(activity,"@Override protected void onPause()","@Override protected void onActivityResult(");
        ordered(pause,"localMockVisible=false","localMockRun.cancel()","resumed=false","super.onPause()");
    }
    @Test public void repairedMockKeepsDiagnosticsAndOnlyDrawsAfterNativeCleanup()throws Exception {
        String run=source("LocalCardMockRuntime");
        assertTrue(run.contains("LocalCardMockPlan.prepare(gray,argb,w,h,false,()->!current())"));
        ordered(run,"if(!cleanupConfirmed(result)","recognizer.localCardRoiDiagnostic(result.owner())","publishResult()");
        assertTrue(run.contains("hypothetical<0||hypothetical>1||outcome.realExecutions()!=0"));
        ordered(run,"LocalCardMockFiles.write(output,\"DIAGNOSTICS.json\",bytes)","canvas.drawCircle(","LocalCardMockFiles.write(output,\"MARKED.png\",bytes)","LocalCardMockFiles.write(output,\"RESULT.json\"");
        excludes(run,"ReviewShizukuTapActor","input tap","screencap","takeScreenshot(");
    }
    @Test public void timedOutPublicationRevokesQueuedAndPartiallyEncodedWork()throws Exception {
        String run=source("LocalCardMockRuntime");
        assertTrue(run.contains("private boolean current(){return !unknown&&!workerReturned&&!canceled.get()"));
        assertTrue(run.contains("done.await(left,TimeUnit.MILLISECONDS)){unknown=true;latchCancel();"));
        assertTrue(run.contains("image.compress(Bitmap.CompressFormat.PNG,100,encoded)||!current()||!nativeClosed()"));
        ordered(run,"LocalCardMockFiles.write(output,\"MARKED.png\",bytes);","if(!current()||!nativeClosed())return;","LocalCardMockFiles.write(output,\"RESULT.json\"");
    }
    @Test public void newUiIsHistoricalNativeOcrAndAddsNoCaptureOrTaskInputRoute()throws Exception {
        String ui=source("LocalCardMockUi");
        assertTrue(ui.contains("APK内的MaaFramework模型在安卓中识别"));
        assertTrue(ui.contains("历史图片的局部回放"));
        assertTrue(ui.contains("核对通过后仅标出一个拟点击点，同时保存不含原文的逐区域行数诊断"));
        excludes(ui,"requestPermission(","startActivity(","newProcess(","screencap","performAction(",
                "dispatchGesture(","ReviewShizukuTapActor","ReviewShizukuBackActor");
        String choices=source("OwnStateSummaryUi");
        assertTrue(choices.contains("overview,cardReveal,null)"));
        assertTrue(choices.contains("if(localCardMock!=null)button(a,box,LocalCardMockUi.OPEN,localCardMock)"));
        String activity=source("DormantCaptureConfirmationActivity");
        assertTrue(activity.contains("RewardsLocalCardMockRelease present="));
        assertTrue(activity.contains("RewardsLocalCardMockDiagnostic version=1 "));
    }
}
