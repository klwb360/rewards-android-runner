package com.aliothmoon.maafw.once;
import java.nio.file.*;import org.junit.Test;import static org.junit.Assert.*;
public class UiReadyReaderWiringTest {
 private String source()throws Exception{String rel="app/src/main/java/com/aliothmoon/maafw/once/AndroidUiStructureReader.java";for(Path p:new Path[]{Path.of(System.getProperty("staging.root","."),rel),Path.of("..",rel),Path.of(rel)})if(Files.isRegularFile(p))return Files.readString(p);throw new IllegalStateException("source missing");}
 @Test public void fixedCurrentSelectsExactOptionalArgument()throws Exception{
  String s=source();
  assertTrue(s.contains("boolean readyMode = !alignmentMode && ReadOnlyCheckpoint.CURRENT == ReadOnlyCheckpoint.UI_READY_V49;"));
  assertTrue(s.contains("command[command.length - 1] = \"READY_V49\";"));assertTrue(s.contains("HELPER, nonce, Long.toString(deadline)};"));
  int read=s.indexOf("static Result read("),regions=s.indexOf("static Result readRegions("),alignment=s.indexOf("static Result readAlignmentRegions("),internal=s.indexOf("private static Result readInternal(");
  assertTrue(read>=0&&regions>read&&alignment>regions&&internal>alignment);
  assertTrue(s.substring(read,regions).contains("readInternal(context,nonce,absoluteDeadline,current,finalGate,false,false)"));
  assertTrue(s.substring(regions,alignment).contains("readInternal(context,nonce,absoluteDeadline,current,finalGate,true,false)"));
  assertTrue(s.substring(alignment,internal).contains("readInternal(context,nonce,absoluteDeadline,current,finalGate,true,true)"));
  assertTrue(s.contains("boolean boundMode = alignmentMode || ReadOnlyCheckpoint.CURRENT == ReadOnlyCheckpoint.UI_BOUND_POINTS_V53;"));
 }
 @Test public void replyBoundToExpectedModeAndOriginalDeadline()throws Exception{String s=source();assertTrue(s.contains("SystemClock.elapsedRealtime(), readyMode || formatMode || fieldMode || boundMode, fieldMode, boundMode)"));assertTrue(s.contains("service.newProcess(command, null, null)"));assertTrue(s.contains("started + 9000"));assertFalse(s.contains(".write("));assertTrue(s.contains("stdin.close(); stdin = null;"));}
 @Test public void formatAddsOnlyFixedArgumentAndKeepsSameProtocol()throws Exception{String s=source();assertTrue(s.contains("ReadOnlyCheckpoint.CURRENT == ReadOnlyCheckpoint.UI_FORMAT_V50"));assertTrue(s.contains("command[command.length - 1] = \"FORMAT_V50\";"));assertTrue(s.contains("fieldMode ? NativeUiStructureProtocol.MAX_LINE_FIELD : NativeUiStructureProtocol.MAX_LINE"));assertFalse(s.contains("MAX_LINE_FORMAT"));}
}
