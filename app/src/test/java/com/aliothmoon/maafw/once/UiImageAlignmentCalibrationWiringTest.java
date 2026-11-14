package com.aliothmoon.maafw.once;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Wiring/namespace contracts; the independent Android acceptance remains a separate check. */
public class UiImageAlignmentCalibrationWiringTest {
 @Rule public TemporaryFolder temporary=new TemporaryFolder();
 private String source(String name)throws Exception{return Files.readString(Path.of(System.getProperty("staging.root",".."),"app/src/main/java/com/aliothmoon/maafw/once/"+name+".java"));}
 private void before(String s,String a,String b){int x=s.indexOf(a),y=s.indexOf(b);assertTrue(a+" before "+b,x>=0&&y>x);}
 @Test public void preparationIsOwnedBeforeIoAndPrecedesEveryUiSourceRead()throws Exception{
  String s=source("UiImageAlignmentRuntime");
  before(s,"preparedModels = PreparedAlignmentModels.create(this, MaaOnceModelStore::open)","preparedModels.prepare(this");
  before(s,"preparedModels.prepare(this","AndroidUiStructureReader.readAlignmentRegions(");
  before(s,"preparedModels.prepare(this","readSource(MetadataStage.UI_BEFORE)");
  assertTrue(s.contains("() -> !hiddenCurrent()"));
  assertTrue(s.contains("if (!ready) throw new SecurityException"));
  assertTrue(s.contains("if (preparedModels != null) preparedModels.closeBeforeAdoption(this)"));
  assertTrue(s.contains("preparedModels == null || preparedModels.knownReleased()"));
  assertTrue(s.contains("catch (Error unknownPreparation)"));
  assertTrue(s.contains("catch (RuntimeException | Error unknownModelClose)"));
  before(s,"catch (RuntimeException | Error unknownModelClose)","if (!actualResourcesClosed()) stage = Stage.CLEANUP_UNKNOWN");
 }
 @Test public void modelAdoptionDoesNotForgeBackendOwnerOrReplaceCleanup()throws Exception{
  String s=source("MaaRecognizer");
  before(s,"owner = own; receipts.bind(own)","models=preparedAlignmentModels.claim(alignmentCapture.owner(),own)");
  assertTrue(s.contains("models.close();if(preparedAlignmentModels!=null)preparedAlignmentModels.confirmClosed(models);confirmed(CleanupEvidence.Item.MODELS_CLOSE)"));
  assertTrue(s.contains("try { models.prepare(); } catch (Exception e) { uncertain=true; throw e; }"));
  assertFalse(source("PreparedAlignmentModels").contains("OnceBackend.Owner"));
 }
 @Test public void calibratedModesCannotBorrowAnotherSelectedEntry()throws Exception{
  String s=source("UiImageAlignmentRuntime");
  assertTrue(s.contains("activity.alignmentCalibrationSelected() != calibration"));
  assertTrue(s.contains("burnCalibrationRun(activity.getNoBackupFilesDir().toPath(), calibration)"));
  assertTrue(s.contains("burnCalibrationUi(activity.getNoBackupFilesDir().toPath(), calibration)"));
  assertTrue(s.contains("calibrationFrameStore(activity.getNoBackupFilesDir().toPath(), calibration)"));
  assertFalse(s.contains("setCurrent"));assertFalse(s.contains("Thread.interrupt("));
 }
 @Test public void everyCalibrationSlotKeepsAllPredecessorBytes()throws Exception{
  for(var attempt:new UiImageAlignmentScope.Calibration[]{UiImageAlignmentScope.Calibration.FIRST,UiImageAlignmentScope.Calibration.CONFIRMATION})for(String slot:new String[]{"RUN","UI","FRAME"}){
   Path root=temporary.newFolder(attempt+slot).toPath().toAbsolutePath().normalize();
   String selected=attempt==UiImageAlignmentScope.Calibration.FIRST?UiImageAlignmentScope.CALIBRATION_DIRECTORY:UiImageAlignmentScope.CONFIRMATION_DIRECTORY;
   byte[] old="incomplete previous attempt must stay\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
   var predecessors=new java.util.ArrayList<Path>();
   for(String dir:new String[]{UiImageAlignmentScope.DIRECTORY,UiImageAlignmentScope.DIAGNOSTIC_DIRECTORY,
     attempt==UiImageAlignmentScope.Calibration.FIRST?UiImageAlignmentScope.CONFIRMATION_DIRECTORY:UiImageAlignmentScope.CALIBRATION_DIRECTORY}){
    Path predecessor=Files.createDirectory(root.resolve(dir));predecessors.add(predecessor);
    for(String prior:new String[]{"RUN","UI","FRAME"})Files.write(predecessor.resolve(prior+".burned"),old);
   }
   boolean missingKey=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()==null;
   boolean markerRetained=false;
   try{burn(root,attempt,slot);assertFalse(missingKey);markerRetained=true;}
   catch(IOException failure){
    if(missingKey){
     assertEquals(IOException.class,failure.getClass());assertEquals("Alignment directory identity unconfirmed",failure.getMessage());
     assertTrue(failure.getStackTrace().length>0);
     assertEquals(UiImageAlignmentScope.class.getName(),failure.getStackTrace()[0].getClassName());
     assertEquals("directoryIdentity",failure.getStackTrace()[0].getMethodName());
     assertFalse(Files.exists(root.resolve(selected),LinkOption.NOFOLLOW_LINKS));
    }else{
     assertTrue(java.util.Arrays.stream(failure.getStackTrace()).anyMatch(x->x.getClassName().equals(UiImageAlignmentScope.class.getName())&&x.getMethodName().equals("forceDirectory")));
     markerRetained=true;
    }
   }
   if(markerRetained){
    Path marker=root.resolve(selected).resolve(slot+".burned");assertTrue(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS));
    String header=attempt==UiImageAlignmentScope.Calibration.FIRST?"ui-image-alignment-calibration-v3":"ui-image-alignment-calibration-confirm-v3";
    assertEquals(header+" slot="+slot+" maxUiReads=1 maxFrames=1 maxOcrPerFrame=1 maxNavigation=0 maxInput=0\n",Files.readString(marker));
   }
   assertPredecessors(predecessors,old);
   assertThrows(IOException.class,()->burn(root,attempt,slot));
   assertPredecessors(predecessors,old);
  }
 }
 private void assertPredecessors(java.util.List<Path> predecessors,byte[] old)throws IOException{
  assertEquals(3,predecessors.size());
  for(Path dir:predecessors){
   assertTrue(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS));
   for(String prior:new String[]{"RUN","UI","FRAME"}){
    Path marker=dir.resolve(prior+".burned");assertTrue(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS));
    assertArrayEquals(old,Files.readAllBytes(marker));
   }
  }
 }
 private void burn(Path root,UiImageAlignmentScope.Calibration attempt,String slot)throws IOException{
  switch(slot){case "RUN"->UiImageAlignmentScope.burnCalibrationRun(root,attempt);case "UI"->UiImageAlignmentScope.burnCalibrationUi(root,attempt);case "FRAME"->UiImageAlignmentScope.calibrationFrameStore(root,attempt).burn("11111111-1111-1111-1111-111111111111","fixed");default->throw new AssertionError();}
 }
}
