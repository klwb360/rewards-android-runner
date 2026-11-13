package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;import java.nio.file.*;
public class UiLocateWiringTest {
 private String s(String n)throws Exception{return Files.readString(Path.of(System.getProperty("staging.root",".."),"app/src/main/java/com/aliothmoon/maafw/once",n+".java"));}
 @Test public void freshIndependentSingleShowWindow(){var p=ReadOnlyCheckpoint.UI_LOCATE_V52;assertSame(ReadOnlyCheckpoint.UI_BOUND_POINTS_V53,ReadOnlyCheckpoint.CURRENT);assertEquals(1794483000000L,p.starts());assertEquals(1794493800000L,p.expires());assertFalse(p.admissionCurrent(1794493620000L));assertEquals(1794492000000L,ReadOnlyCheckpoint.UI_FIELD_V51.expires());assertNotEquals(p.directory(),ReadOnlyCheckpoint.UI_FIELD_V51.directory());assertTrue(ReadOnlyCheckpoint.blocksLiveInput());assertThrows(SecurityException.class,()->p.store(Path.of("fixture"),1));}
 @Test public void templateFencesAndDurableBurnPrecedeCommit()throws Exception{String c=s("CaptureSessionRuntime");String b=c.substring(c.indexOf("if(returnReadIntent.uiLocate()){",c.indexOf("private void entryRecoveryOnOwner")),c.indexOf("if(entryAnchor.kind()==BingNavigationSnapshot.Kind.MAIN){",c.indexOf("private void entryRecoveryOnOwner")));assertTrue(b.contains("entryLocateSource(V46EntryMetadataReader.Stage.PREPARE_BEFORE)"));assertTrue(b.contains("entryLocateSource(V46EntryMetadataReader.Stage.COMMIT_BEFORE)"));assertTrue(b.contains("entryBudget.burn(RewardsEntryBudget.Slot.NAV_1)"));assertTrue(b.indexOf("!entryNavigation.succeeded()")<b.indexOf("entryNavigation.receivedAt()"));assertTrue(b.contains("RewardsEntryBudget.waitSatisfied"));assertTrue(b.contains("Stage.AFTER_WAIT"));assertFalse(b.contains("Stage.ANCHOR"));assertTrue(c.contains("entryAnchor.kind()==BingNavigationSnapshot.Kind.TEMPLATE&&entryAnchor.sameEntrySource(now)"));}
 @Test public void accurateShowReceiptAndVisibleCopy()throws Exception{String b=s("RewardsEntryBudget");assertTrue(b.contains("maxShowOnScreen="));assertTrue(b.contains("policy==ReadOnlyCheckpoint.UI_LOCATE_V52?1:0"));String c=s("RootCaptureUi");c=c.substring(c.indexOf("static void uiLocateChoice("),c.indexOf("static void uiFieldChoice("));for(String v:new String[]{"v52","11:30–14:30 UTC","14:27 UTC停止接纳","最多一次ACTION_SHOW_ON_SCREEN","无坐标或滚动fallback","批准V52：最多一次区域定位，零任务点击"})assertTrue(v,c.contains(v));assertTrue(s("CaptureSessionRuntime").contains("\"ACTION_SHOW_ON_SCREEN\":\"HOME_ENTRY_CLICK\""));}
 @Test public void sameTaskReplacementTemplateCannotReanchor()throws Exception{
  String component=BingNavigationSnapshot.TEMPLATE;
  String windows=V46EntryMetadataTest.w(component),activities=V46EntryMetadataTest.a(component);
  var original=V46EntryMetadataTest.r(windows,activities).ready();
  var same=V46EntryMetadataTest.r(windows,activities).ready();
  var replacement=V46EntryMetadataTest.r(windows.replace("Window{aaa u0","Window{abb u0"),activities).ready();
  assertNotNull(original);assertNotNull(same);assertNotNull(replacement);
  assertTrue(RewardsEntryBudget.sameTaskTemplate(original,replacement,1010));
  assertTrue(original.sameEntrySource(same));assertFalse(original.sameEntrySource(replacement));
  String c=s("CaptureSessionRuntime");String guard="if(returnReadIntent.uiLocate()&&!entryAnchor.sameEntrySource(template))";
  int start=c.indexOf("private void entryRecoveryOnOwner()");
  assertTrue(c.indexOf(guard,start)>c.indexOf("RewardsEntryBudget.sameTaskTemplate(entryAnchor,template",start));
  assertTrue(c.indexOf(guard,start)<c.indexOf("returnReadIntent.anchorEntryTemplate(template",start));
 }
}
