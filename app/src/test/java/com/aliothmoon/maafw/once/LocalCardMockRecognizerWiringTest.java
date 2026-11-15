package com.aliothmoon.maafw.once;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

/** Source contracts only; no native library or Android process is started. */
public class LocalCardMockRecognizerWiringTest {
    private String source(String name)throws Exception{return Files.readString(Path.of(System.getProperty("staging.root",".."),"app/src/main/java/com/aliothmoon/maafw/once",name+".java"));}
    private String section(String text,String start,String end){int a=text.indexOf(start),b=text.indexOf(end,a+start.length());assertTrue(a>=0&&b>a);return text.substring(a,b);}
    @Test public void localModeReturnsBeforeTheLegacyFullImageQueryLoop()throws Exception{
        String text=source("MaaRecognizer"),start=section(text,"if(localCardMockPlan!=null){","boolean[] hits = new boolean[RewardsQuery.values().length]");
        assertTrue(start.contains("refineLocalCardMock(frame,stop)"));assertTrue(start.contains("return new BackendPorts.Recognition"));
        String local=section(text,"private void refineLocalCardMock(","/** Run only after readStructure");
        assertTrue(local.contains("for(var roi:localCardMockPlan.rois())"));assertEquals(1,local.split("MaaTaskerPostRecognition",-1).length-1);
        assertTrue(local.contains("\\\"roi\\\":["));assertTrue(local.contains("readingLabelRoi=true"));assertTrue(local.contains("detail(status,true)"));
        assertTrue(local.contains("localCardMockPlan.matchesGray8"));assertTrue(local.contains("batches.clear();roiRows=null"));
    }
    @Test public void resultIsWithheldUntilExactOwnerAndNativeCleanup()throws Exception{
        String text=source("MaaRecognizer"),getter=section(text,"synchronized LocalCardMockPlan.Outcome localCardMockOutcome(","private BackendPorts.FrameView materialFrame;");
        assertTrue(getter.contains("expected==owner&&returned&&observation()!=null"));
        String observation=section(text,"synchronized RewardsObservation observation()","private Pointer resource");assertTrue(observation.contains("!complete"));
        String plan=source("LocalCardMockPlan");assertTrue(plan.contains("RewardRegionGate.Source.USER_STATIC_MATERIAL"));
        for(String bad:new String[]{"ReviewShizukuTapActor","ReviewShizukuPngBinding","screencap","RealCaptureClaim","input tap"})assertFalse(bad,plan.contains(bad));
        assertTrue(plan.contains("session.previewOnce()"));assertTrue(plan.contains("recognitionClaimed.compareAndSet(false,true)"));
    }
    @Test public void perRoiDecodeMetadataIsRecordedBeforeRejectingTheDetail()throws Exception{
        String text=source("MaaRecognizer"),read=section(text,"private void readStructure(","private void observeLive(");
        assertTrue(read.indexOf("localCardRoiDiagnostics.decoded(activeLocalCardRoi,decoded)")<read.indexOf("need(decoded.transportAccepted())"));
        assertTrue(read.contains("localCardRoiDiagnostics.rows(activeLocalCardRoi,rows)"));
        String local=section(text,"private void refineLocalCardMock(","/** Run only after readStructure");
        assertTrue(local.contains("localCardRoiDiagnostics.attempted(index)"));assertTrue(local.contains("localCardRoiDiagnostics.completed(index,status)"));
        assertTrue(local.contains("batches.add(roiRows)"));assertFalse(local.contains("roiRows==null?java.util.List.of():roiRows"));
        assertTrue(local.indexOf("localCardRoiDiagnostics.attempted(index)")<local.indexOf("api.MaaTaskerPostRecognition"));
        String plan=source("LocalCardMockPlan");
        assertTrue(plan.contains("if(batch==null)return hold(\"ROI_ROWS_UNAVAILABLE\",count)"));
        assertTrue(plan.contains("if(batch.size()!=1)return hold(\"ROI_ROW_COUNT\",count)"));
    }
    @Test public void scalarDiagnosticGetterNeedsExactOwnerAndActualCleanReleaseButNotABusinessHit()throws Exception{
        String text=source("MaaRecognizer"),getter=section(text,"synchronized LocalCardRoiDiagnostic.Report localCardRoiDiagnostic(","private BackendPorts.FrameView materialFrame;");
        assertTrue(getter.contains("expected==owner&&returned&&complete&&!uncertain"));assertTrue(getter.contains("!observationStop.requested()"));
        assertFalse(getter.contains("observation()!=null"));assertTrue(getter.contains("localCardRoiDiagnostics.snapshot()"));
        String plan=source("LocalCardMockPlan");int diagnostic=plan.indexOf("if(diagnosticOnly)return new Outcome(\"DIAGNOSTIC_ONLY\"");
        assertTrue(diagnostic>0&&diagnostic<plan.indexOf("new DailyCheckInRegion.Session(associated)"));
    }
}
