package com.aliothmoon.maafw.once;
import org.junit.Test;
/** Runs the staged pure main-based assertion suites in the actual module test aggregate. */
public class NativeLocationIntegrationTest {
 @Test public void ringRoiAssertions(){com.aliothmoon.maafw.rewards.CheckInRingRoiPlanTest.main(new String[0]);}
 @Test public void sameLocationVerificationAssertions(){com.aliothmoon.maafw.rewards.LiveCheckInVerificationTest.main(new String[0]);}
 @Test public void scalarDiagnosticAssertions(){LiveCheckInDiagnosticTest.main(new String[0]);}
}
