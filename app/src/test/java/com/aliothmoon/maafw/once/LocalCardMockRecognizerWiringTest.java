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
}
