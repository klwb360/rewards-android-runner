package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Generated local files only; Android durable storage remains a separate real-device check. */
public class UiTitleTimingEntryTest {
 @Rule public TemporaryFolder temporary=new TemporaryFolder();
 private String source(String name)throws Exception{return Files.readString(Path.of(System.getProperty("staging.root",".."),"app/src/main/java/com/aliothmoon/maafw/once/"+name+".java"));}
 @Test public void independentSlotNeverChangesAnyPredecessorDirectoryOrMarker()throws Exception{
  for(String slot:new String[]{"RUN","UI","FRAME"}){
   Path root=temporary.newFolder(slot).toPath().toAbsolutePath().normalize();
   byte[] old="preserve incomplete and completed history\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
   var predecessors=new ArrayList<Path>();
   for(String name:new String[]{UiImageAlignmentScope.DIRECTORY,UiImageAlignmentScope.DIAGNOSTIC_DIRECTORY,UiImageAlignmentScope.CALIBRATION_DIRECTORY,UiImageAlignmentScope.CONFIRMATION_DIRECTORY}){
    Path dir=Files.createDirectory(root.resolve(name));predecessors.add(dir);
    for(String prior:new String[]{"RUN","UI","FRAME"})Files.write(dir.resolve(prior+".burned"),old);
   }
   boolean missingKey=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()==null;
   boolean marker=false;
   try{burn(root,slot);assertFalse(missingKey);marker=true;}
   catch(IOException failure){
    if(missingKey){assertEquals(IOException.class,failure.getClass());assertEquals("Alignment directory identity unconfirmed",failure.getMessage());assertEquals(UiImageAlignmentScope.class.getName(),failure.getStackTrace()[0].getClassName());assertEquals("directoryIdentity",failure.getStackTrace()[0].getMethodName());assertFalse(Files.exists(root.resolve(UiImageAlignmentScope.TITLE_TIMING_DIRECTORY),LinkOption.NOFOLLOW_LINKS));}
    else{assertTrue(java.util.Arrays.stream(failure.getStackTrace()).anyMatch(x->x.getClassName().equals(UiImageAlignmentScope.class.getName())&&x.getMethodName().equals("forceDirectory")));marker=true;}
   }
   if(marker)assertEquals("ui-image-title-timing-v4 slot="+slot+" maxUiReads=1 maxFrames=1 maxOcrPerFrame=1 maxNavigation=0 maxInput=0\n",Files.readString(root.resolve(UiImageAlignmentScope.TITLE_TIMING_DIRECTORY).resolve(slot+".burned")));
   unchanged(predecessors,old);assertThrows(IOException.class,()->burn(root,slot));unchanged(predecessors,old);
  }
 }
 private void unchanged(java.util.List<Path> paths,byte[] old)throws Exception{assertEquals(4,paths.size());for(Path dir:paths){assertTrue(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS));for(String slot:new String[]{"RUN","UI","FRAME"})assertArrayEquals(old,Files.readAllBytes(dir.resolve(slot+".burned")));}}
 private void burn(Path root,String slot)throws IOException{var attempt=UiImageAlignmentScope.Calibration.TITLE_TIMING;switch(slot){case "RUN"->UiImageAlignmentScope.burnCalibrationRun(root,attempt);case "UI"->UiImageAlignmentScope.burnCalibrationUi(root,attempt);case "FRAME"->UiImageAlignmentScope.calibrationFrameStore(root,attempt).burn("11111111-1111-1111-1111-111111111111","fixed");default->throw new AssertionError();}}
 @Test public void titleTimingSelectionUsesExistingExactApprovalAndAllThreeFixedSlots()throws Exception{
  String activity=source("DormantCaptureConfirmationActivity"),runtime=source("UiImageAlignmentRuntime"),ui=source("OwnStateSummaryUi");
  assertTrue(activity.contains("openAlignmentCalibrationChoice(UiImageAlignmentScope.Calibration.TITLE_TIMING)"));
  assertTrue(runtime.contains("activity.alignmentCalibrationSelected() != calibration"));
  assertTrue(runtime.contains("burnCalibrationRun(activity.getNoBackupFilesDir().toPath(), calibration)"));
  assertTrue(runtime.contains("burnCalibrationUi(activity.getNoBackupFilesDir().toPath(), calibration)"));
  assertTrue(runtime.contains("calibrationFrameStore(activity.getNoBackupFilesDir().toPath(), calibration)"));
  assertTrue(runtime.contains("\"TITLE_TIMING_V4\""));
  assertTrue(ui.contains("TITLE_TIMING_RUN_BUTTON"));
  assertTrue(runtime.contains("finally { capture.backendReturned(); }"));
  assertTrue(runtime.contains("finally { capture.diagnosticsTrace().end(afterTiming); }"));
 }
 @Test public void mockPlanExistsOnlyInsideQualifiedPublicationBranch()throws Exception{
  String pub=source("UiImageAlignmentPublication");
  int reject=pub.indexOf("if (!qualified)"),earlyReturn=pub.indexOf("return head",reject),mock=pub.indexOf("UiMockOverlayPlan.summary(completion.result())");
  assertTrue(reject>=0&&earlyReturn>reject&&mock>earlyReturn);
  assertFalse(source("UiImageAlignmentRuntime").contains("UiMockOverlayPlan"));
  assertFalse(source("UiImageAlignmentCapture").contains("UiMockOverlayPlan"));
  assertFalse(source("DormantCaptureConfirmationActivity").contains("UiMockOverlayPlan"));
 }
}
