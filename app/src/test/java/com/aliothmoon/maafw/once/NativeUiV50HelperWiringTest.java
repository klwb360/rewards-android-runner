package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;import java.nio.file.*;
public class NativeUiV50HelperWiringTest {
 String source()throws Exception{return Files.readString(Path.of(System.getProperty("staging.root",".."),"app/src/main/java/com/aliothmoon/maafw/once/NativeUiStructureHelper.java"));}
 @Test public void fixedV50ModeAndLegacyStillAccepted(){assertTrue(NativeUiStructureHelper.validReadyArguments(new String[]{"n","d","FORMAT_V50"}));assertTrue(NativeUiStructureHelper.validReadyArguments(new String[]{"n","d","READY_V49"}));assertTrue(NativeUiStructureHelper.validReadyArguments(new String[]{"n","d"}));assertFalse(NativeUiStructureHelper.validReadyArguments(new String[]{"n","d","FORMAT_V50 "}));assertFalse(NativeUiStructureHelper.validReadyArguments(new String[]{"n","d","FORMAT_V50","extra"}));}
 @Test public void exactNewModeSelectsWrapperOnly()throws Exception{String s=source();assertTrue(s.contains("boolean formatMode=readyMode&&\"FORMAT_V50\".equals(args[2])"));assertTrue(s.contains("formatMode?NativeUiStructureV50Parser.parse(records):NativeUiStructureParser.parse(records)"));assertTrue(s.contains("boolean fieldMode=readyMode&&\"DIAGNOSTIC_V51\".equals(args[2])"));}
 @Test public void sameIdleBoundsAndBlockerGate()throws Exception{String s=source();assertTrue(s.contains("automation.waitForIdle(500,3000)"));assertTrue(s.contains("start + (readyMode?8000:5000)"));assertTrue(s.contains("if(!readyMode||!diagnostics.blocked())"));assertTrue(s.contains("readyMode?diagnostics.result(idle):null"));}
}
