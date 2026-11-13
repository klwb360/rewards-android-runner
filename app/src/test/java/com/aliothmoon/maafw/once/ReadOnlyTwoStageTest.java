package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.Instant;
import java.nio.file.*;
public class ReadOnlyTwoStageTest {
 @Test public void independentWindowAndNoTapSlot(){
  assertEquals(Instant.parse("2026-11-11T18:00:00Z").toEpochMilli(),ReadOnlyTwoStageScope.STARTS_UTC);
  assertEquals(Instant.parse("2026-11-11T19:30:00Z").toEpochMilli(),ReadOnlyTwoStageScope.EXPIRES_UTC);
  assertTrue(ReadOnlyTwoStageScope.slotAllowed("FIRST"));assertTrue(ReadOnlyTwoStageScope.slotAllowed("SECOND"));
  for(String s:new String[]{null,"TAP","BEFORE","AFTER","FIRST2","../FIRST"})assertFalse(ReadOnlyTwoStageScope.slotAllowed(s));
  assertNotEquals(NativeRewardCardScope.DIRECTORY,ReadOnlyTwoStageScope.DIRECTORY);
 }
 @Test public void admissionHalfOpenAndSafetyMargin(){long start=ReadOnlyTwoStageScope.STARTS_UTC,end=ReadOnlyTwoStageScope.EXPIRES_UTC;
  assertFalse(ReadOnlyTwoStageScope.current(start-1));assertTrue(ReadOnlyTwoStageScope.current(start));assertFalse(ReadOnlyTwoStageScope.current(end));
  assertTrue(ReadOnlyTwoStageScope.admissionCurrent(end-90001));assertFalse(ReadOnlyTwoStageScope.admissionCurrent(end-90000));
 }
 @Test public void eachGrantClampsToSessionRemaining(){long utc=ReadOnlyTwoStageScope.STARTS_UTC;
  assertEquals(91000,ReadOnlyTwoStageScope.deadline(1000,utc,200000));assertEquals(45000,ReadOnlyTwoStageScope.deadline(1000,utc,45000));
 }
 @Test public void invalidDeadlineRejects(){for(long n:new long[]{-1,Long.MAX_VALUE})try{ReadOnlyTwoStageScope.deadline(n,ReadOnlyTwoStageScope.STARTS_UTC,Long.MAX_VALUE);fail();}catch(SecurityException expected){}
  try{ReadOnlyTwoStageScope.deadline(100,ReadOnlyTwoStageScope.STARTS_UTC,100);fail();}catch(SecurityException expected){}
 }
 private ReadOnlyTwoStageRun.Capture capture(ReadOnlyTwoStageRun.Phase phase){return new ReadOnlyTwoStageRun.Capture(this,phase,"synthetic-readonly",MoreCardFixture.W,MoreCardFixture.H,0,phase==ReadOnlyTwoStageRun.Phase.FIRST?1000:2000,()->false);}
 @Test public void samePixelsAllowedInTwoIndependentCaptures(){var a=capture(ReadOnlyTwoStageRun.Phase.FIRST);var b=capture(ReadOnlyTwoStageRun.Phase.SECOND);byte[] image=MoreCardFixture.compactPixels();var rows=MoreCardFixture.compactRows();
  var first=a.prepare(image,MoreCardFixture.W,MoreCardFixture.H,rows,()->false);var second=b.prepare(image,MoreCardFixture.W,MoreCardFixture.H,rows,()->false);
  assertNotNull(first);assertNotNull(second);assertTrue(first.quoteComplete());assertTrue(second.quoteComplete());assertTrue(first.validNativeShape());assertTrue(second.validNativeShape());assertNotSame(first.capture,second.capture);assertTrue(b.lower>a.lower);assertEquals(2,b.sequence);
 }
 @Test public void oneCaptureCannotReplay(){var c=capture(ReadOnlyTwoStageRun.Phase.FIRST);assertNotNull(c.prepare(MoreCardFixture.pixels(false),MoreCardFixture.W,MoreCardFixture.H,MoreCardFixture.rows(false),()->false));assertNull(c.prepare(MoreCardFixture.pixels(false),MoreCardFixture.W,MoreCardFixture.H,MoreCardFixture.rows(false),()->false));}
 @Test public void canceledCaptureCannotPublish(){var c=capture(ReadOnlyTwoStageRun.Phase.FIRST);assertNull(c.prepare(MoreCardFixture.compactPixels(),MoreCardFixture.W,MoreCardFixture.H,MoreCardFixture.compactRows(),()->true));}
 @Test public void incompleteTaskIsObservedWithoutAction(){var o=capture(ReadOnlyTwoStageRun.Phase.FIRST).prepare(MoreCardFixture.pixels(false),MoreCardFixture.W,MoreCardFixture.H,MoreCardFixture.rows(false),()->false);assertNotNull(o);assertTrue(o.validNativeShape());assertFalse(o.quoteComplete());}
 @Test public void semanticHoldDoesNotInventCompletion(){var rows=MoreCardFixture.rows(false);rows.removeIf(r->r.text().equals("Quote of the day"));var o=capture(ReadOnlyTwoStageRun.Phase.FIRST).prepare(MoreCardFixture.pixels(false),MoreCardFixture.W,MoreCardFixture.H,rows,()->false);assertNotNull(o);assertTrue(o.validNativeShape());assertFalse(o.quoteComplete());}
 @Test public void badNativeShapeCannotBeAccepted(){var o=capture(ReadOnlyTwoStageRun.Phase.FIRST).prepare(MoreCardFixture.pixels(false),MoreCardFixture.W,MoreCardFixture.H,null,()->false);assertNotNull(o);assertFalse(o.validNativeShape());}
 @Test public void undispatchedCloseIsNotLifecycleSuccess(){var slots=new ForegroundPhaseSlots<Object>();var l=slots.reserve(ForegroundPhaseSlots.Phase.BEFORE,new Object());slots.requestClose(l);var s=slots.snapshot(l);assertTrue(s.closed());assertFalse(s.confirmedDestruction());assertTrue(s.summary().contains("observedFgs=NONE"));}
 @Test public void exactDestroyProofHasAllFields(){var slots=new ForegroundPhaseSlots<Object>();var l=slots.reserve(ForegroundPhaseSlots.Phase.BEFORE,new Object());var service=new Object();slots.beginDispatch(l);slots.dispatchReturned(l);assertSame(l,slots.deliver(ForegroundPhaseSlots.Phase.BEFORE,service));slots.requestClose(l);assertFalse(slots.snapshot(l).confirmedDestruction());assertTrue(slots.destroyed(l,service));assertTrue(slots.snapshot(l).confirmedDestruction());}
 @Test public void unknownNeverBecomesProofAfterLateDestroy(){var slots=new ForegroundPhaseSlots<Object>();var l=slots.reserve(ForegroundPhaseSlots.Phase.BEFORE,new Object());var service=new Object();slots.beginDispatch(l);slots.dispatchReturned(l);slots.deliver(ForegroundPhaseSlots.Phase.BEFORE,service);slots.unknown(l);slots.requestClose(l);slots.destroyed(l,service);assertFalse(slots.snapshot(l).confirmedDestruction());assertTrue(slots.snapshot(l).unknown());}
 @Test public void hardwareLabelsAreExplicitlyMapped(){assertEquals("BEFORE",ReadOnlyTwoStageRun.Phase.FIRST.hardwareLabel());assertEquals("AFTER",ReadOnlyTwoStageRun.Phase.SECOND.hardwareLabel());}
 private Path root(){Path p=Path.of(".").toAbsolutePath();for(int i=0;i<4;i++,p=p.getParent())if(Files.exists(p.resolve("capture-review/src/main/AndroidManifest.xml")))return p;throw new AssertionError();}
 @Test public void productionReadOnlyRunHasNoActorOrBusinessSessionConstruction()throws Exception{
  String s=Files.readString(root().resolve("app/src/main/java/com/aliothmoon/maafw/once/ReadOnlyTwoStageRun.java"));assertFalse(s.contains("ReviewShizukuTapActor"));assertFalse(s.contains("NativeRewardCardSession"));assertFalse(s.contains("NativeRewardCardRun"));
 }
 @Test public void runtimeKeepsLegacyInputDisabledAndIndependentReadonlyAuthority()throws Exception{
  String s=Files.readString(root().resolve("app/src/main/java/com/aliothmoon/maafw/once/CaptureSessionRuntime.java"));
  assertTrue(s.contains("v20 read-only profile: business input route disabled"));assertTrue(s.contains("readOnlyRun==null&&cardRun==null&&nativeSession==null&&!START_SPENT"));assertTrue(s.contains("VisibleCaptureAuthority.issueReadOnly"));assertTrue(s.contains("readOnlyRun.store(readOnlyPhase)"));assertTrue(s.contains("readOnlyPhase==ReadOnlyTwoStageRun.Phase.SECOND"));
 }
 @Test public void emptyNativeBatchIsNotSemanticHold(){var o=capture(ReadOnlyTwoStageRun.Phase.FIRST).prepare(MoreCardFixture.pixels(false),MoreCardFixture.W,MoreCardFixture.H,java.util.List.of(),()->false);assertNotNull(o);assertFalse(o.validNativeShape());}
 @Test public void invalidPixelBufferCannotBePublished(){var o=capture(ReadOnlyTwoStageRun.Phase.FIRST).prepare(new byte[1],MoreCardFixture.W,MoreCardFixture.H,MoreCardFixture.rows(false),()->false);assertNotNull(o);assertFalse(o.validNativeShape());}
 @Test public void publicationRespectsPhaseDeadlineEvenIfSessionFresh(){long utc=ReadOnlyTwoStageScope.STARTS_UTC;assertTrue(ReadOnlyTwoStageRun.freshPublication(900,800,1000,10000,utc,false));assertFalse(ReadOnlyTwoStageRun.freshPublication(1000,800,1000,10000,utc,false));assertFalse(ReadOnlyTwoStageRun.freshPublication(1100,800,1000,10000,utc,false));}
 @Test public void publicationRejectsStaleRevokedAndExpiredFrame(){long utc=ReadOnlyTwoStageScope.STARTS_UTC;assertFalse(ReadOnlyTwoStageRun.freshPublication(16001,1000,20000,30000,utc,false));assertFalse(ReadOnlyTwoStageRun.freshPublication(900,800,1000,10000,utc,true));assertFalse(ReadOnlyTwoStageRun.freshPublication(900,800,1000,10000,ReadOnlyTwoStageScope.EXPIRES_UTC,false));assertFalse(ReadOnlyTwoStageRun.freshPublication(700,800,1000,10000,utc,false));}
}
