package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
/** Source wiring contract only; does not exercise Binder, Looper, quarantine or device command execution. */
public class RootReadOnlyNavigationWiringTest {
    private static String source() throws Exception {
        String root=System.getProperty("staging.root");if(root==null)root=System.getProperty("user.dir");
        Path p=Path.of(root,"app/src/main/java/com/aliothmoon/maafw/once/ReviewBingNavigation.java");
        if(!Files.exists(p))p=Path.of(root,"../app/src/main/java/com/aliothmoon/maafw/once/ReviewBingNavigation.java");
        return Files.readString(p).replace("\r\n","\n");
    }
    @Test public void isolatedFactoryAndBudgetDoNotResetLegacySlots() throws Exception {String s=source();assertTrue(s.contains("forRootReadOnly("));assertTrue(s.contains("READ_ONLY_PAIRS.incrementAndGet()>4"));assertTrue(s.contains("PREFLIGHT_PAIRS.incrementAndGet()>2"));assertTrue(s.contains("PAIRS.incrementAndGet()>12"));assertFalse(s.contains(".set(0)"));}
    @Test public void onlyNewFactorySelectsWindowAllAndStrictParser() throws Exception {String s=source();assertTrue(s.contains("readOnlyGuard==null?new String[]{\"/system/bin/dumpsys\",\"window\"}"));assertTrue(s.contains("new String[]{\"/system/bin/dumpsys\",\"window\",\"-a\"}"));assertTrue(s.contains("new String[]{\"/system/bin/dumpsys\",\"activity\",\"activities\"}"));assertTrue(s.contains("readOnlyGuard==null?BingNavigationSnapshot.parse"));assertTrue(s.contains("BingNavigationSnapshot.parseRootReadOnly"));}
    @Test public void getterHasNoIpcOrLock() throws Exception {String s=source();assertTrue(s.contains("volatile BingNavigationSnapshot.ReadOnlyGate lastReadOnlyGate"));assertTrue(s.contains("lastReadOnlyGate(){return lastReadOnlyGate;}"));}
    @Test public void quarantineAndAuthorityRemainShared() throws Exception {String s=source();assertEquals(1,s.split("volatile Object quarantine",-1).length-1);assertTrue(s.contains("installed.equals(BingPackageMetadata.read(context.getPackageManager()))"));assertTrue(s.contains("shape.equals(display())"));assertTrue(s.contains("Shizuku.getUid()!=0"));assertTrue(s.contains("quarantine=new Object[]{process,input,stdout,stdin,stderr}"));assertTrue(s.contains("Arrays.fill(windows,(byte)0)"));}
}