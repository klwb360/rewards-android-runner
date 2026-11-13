package com.aliothmoon.maafw.once;
import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Source contract only, not a successful Android metadata query. */
public class RootMetadataBudgetContractTest {
 @Test public void separateTwoAndTwelveBudgetsNoReset()throws Exception{
  Path p=Path.of("../app/src/main/java/com/aliothmoon/maafw/once/ReviewBingNavigation.java");
  if(!Files.exists(p))p=Path.of("app/src/main/java/com/aliothmoon/maafw/once/ReviewBingNavigation.java");
  String s=Files.readString(p);
  assertTrue(s.contains("private static final AtomicInteger PREFLIGHT_PAIRS=new AtomicInteger()"));
  assertTrue(s.contains("private static final AtomicInteger PAIRS=new AtomicInteger()"));
  assertTrue(s.contains("rootPreflight?PREFLIGHT_PAIRS.incrementAndGet()>2:PAIRS.incrementAndGet()>12"));
  assertTrue(s.contains("static ReviewBingNavigation forRootPreflight("));
  assertTrue(s.contains("private ReviewBingNavigation("));
  assertFalse(s.contains("PAIRS.set("));assertFalse(s.contains("PAIRS.getAndSet("));
  assertTrue(s.contains("quarantine=new Object[]{process,input,stdout,stdin,stderr}"));
 }
}
