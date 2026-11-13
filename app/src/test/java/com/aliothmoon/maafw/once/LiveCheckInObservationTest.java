package com.aliothmoon.maafw.once;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Pure synthetic test inputs only. This does not authenticate a live producer. */
public class LiveCheckInObservationTest {
 static List<FrameStructureEvidence.Line> rows(){var r=new ArrayList<FrameStructureEvidence.Line>();
  for(var x:DailyCheckInRegionTest.visualRows())r.add(new FrameStructureEvidence.Line(x.text(),x.box().left(),x.box().top(),x.box().width(),x.box().height(),x.confidence()));return r;}
 static String observe(byte[] p,List<FrameStructureEvidence.Line> r){return LiveCheckInObservation.observe(p,552,201,r,()->false);}
 @Test public void fullDisplayIdentityMappingAndScalarOnly(){
  var p=DailyCheckInRegionTest.pixels();var copy=p.clone();String s=observe(p,rows());
  assertTrue(s.contains("visual=CANDIDATE"));assertTrue(s.contains("target=272.0,115.5"));
  assertTrue(s.contains("cropOrigin=0,0 scale=1 coordinateSpace=FULL_DISPLAY"));
  assertTrue(s.contains("localTickCount=3"));assertTrue(s.contains("independentDays=7"));
  assertTrue(s.contains("nativeActions=0"));assertFalse(s.contains("签入"));assertFalse(s.contains("5小时59分"));assertArrayEquals(copy,p);
 }
 @Test public void missingNumberCannotCreateTarget(){var r=rows();r.remove(8);assertFalse(observe(DailyCheckInRegionTest.pixels(),r).contains("target="));}
 @Test public void lowRequiredNumberCannotCreateTarget(){var r=rows();var n=r.get(8);r.set(8,new FrameStructureEvidence.Line(n.text(),n.x(),n.y(),n.width(),n.height(),.2));assertTrue(observe(DailyCheckInRegionTest.pixels(),r).contains("visual=BAD_OCR"));}
 @Test public void cancelledAndMalformedInputsDoNotPublishTarget(){
  assertEquals("REAL_NATIVE_CHECKIN CANCELED",LiveCheckInObservation.observe(DailyCheckInRegionTest.pixels(),552,201,rows(),()->true));
  assertEquals("REAL_NATIVE_CHECKIN INVALID_INPUT",observe(new byte[1],rows()));
  var r=rows();r.add(null);assertEquals("REAL_NATIVE_CHECKIN BAD_OCR",observe(DailyCheckInRegionTest.pixels(),r));
 }
 @Test public void freshAfterStateShowsFourTicksWithoutAccountClaim(){var p=DailyCheckInRegionTest.pixels();DailyCheckInRegionTest.fill(p,245,88,300,143,250);DailyCheckInRegionTest.tick(p,272);var r=rows();r.remove(8);String s=observe(p,r);assertTrue(s.contains("localTickCount=4"));assertTrue(s.contains("visual=NO_CURRENT_TARGET"));assertTrue(s.contains("accountCredit=UNPROVEN"));}
 @Test public void fixedScopeHasTwoPurposesAndHardExpiry(){assertEquals(2,BusinessCaptureScope.Purpose.values().length);assertEquals(90000,BusinessCaptureScope.TTL);assertFalse(BusinessCaptureScope.current(0));assertTrue(BusinessCaptureScope.current(1794405000000L));assertFalse(BusinessCaptureScope.current(BusinessCaptureScope.EXPIRES_UTC-BusinessCaptureScope.TTL));assertFalse(BusinessCaptureScope.current(Long.MAX_VALUE));}
}
