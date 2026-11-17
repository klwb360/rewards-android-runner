package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Synthetic projection and callback behavior only: no Android reader or source command is run. */
public class LiveNavigationDiagnosticTest {
    private static LiveNavigationDiagnostic snapshot(LiveNavigationDiagnostic.Trace trace){return LiveNavigationDiagnostic.finish(trace,7);}
    @Test public void unobservedChecksCannotLookLikeFailedOrSuccessfulChecks(){
        var trace=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE);
        var first=snapshot(trace);assertEquals(48,first.scalars().size());
        assertEquals("NOT_OBSERVED",first.scalars().get("checkCURRENT_BEFORE"));
        assertEquals("NOT_OBSERVED",first.scalars().get("activitiesInvoked"));
        assertEquals(-1,first.scalars().get("activitiesBytes"));assertEquals(-1L,first.scalars().get("activitiesElapsedMs"));
        LiveNavigationDiagnostic.check(trace,LiveNavigationDiagnostic.Check.EXTRA_BEFORE,false);
        var next=snapshot(trace);assertEquals("FALSE",next.scalars().get("checkEXTRA_BEFORE"));
        assertEquals("NOT_OBSERVED",next.scalars().get("checkCURRENT_BEFORE"));
        assertEquals("NOT_OBSERVED",first.scalars().get("checkEXTRA_BEFORE"));
        assertThrows(UnsupportedOperationException.class,()->first.scalars().put("foreign","RAW_TEXT"));
    }
    @Test public void observingShortCircuitChecksNeverAddsOrRepeatsAGuardCall(){
        for(int bits=0;bits<8;bits++){
            boolean extra=(bits&1)!=0,current=(bits&2)!=0,clean=(bits&4)!=0;
            var calls=new AtomicInteger();var trace=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE);
            boolean accepted=LiveNavigationDiagnostic.observe(trace,LiveNavigationDiagnostic.Stage.EXTRA_BEFORE,LiveNavigationDiagnostic.Check.EXTRA_BEFORE,()->{calls.incrementAndGet();return extra;})
                    &&LiveNavigationDiagnostic.observe(trace,LiveNavigationDiagnostic.Stage.CURRENT_BEFORE,LiveNavigationDiagnostic.Check.CURRENT_BEFORE,()->{calls.incrementAndGet();return current;})
                    &&LiveNavigationDiagnostic.observe(trace,LiveNavigationDiagnostic.Stage.READ_CLEAN,LiveNavigationDiagnostic.Check.READ_CLEAN,()->{calls.incrementAndGet();return clean;});
            assertEquals(extra&&current&&clean,accepted);assertEquals(extra?(current?3:2):1,calls.get());
            var value=snapshot(trace);
            assertEquals(extra?Boolean.toString(current).toUpperCase():"NOT_OBSERVED",value.scalars().get("checkCURRENT_BEFORE"));
            assertEquals(extra&&current?Boolean.toString(clean).toUpperCase():"NOT_OBSERVED",value.scalars().get("checkREAD_CLEAN"));
        }
    }
    @Test public void sourceExceptionIdentityAndItsFailedStageArePreservedWithoutMessage(){
        var trace=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE);
        var original=new SecurityException("PRIVATE_ACCOUNT_URL_AND_SOURCE_TEXT");
        var caught=assertThrows(SecurityException.class,()->LiveNavigationDiagnostic.observe(trace,
                LiveNavigationDiagnostic.Stage.CURRENT_BEFORE,LiveNavigationDiagnostic.Check.CURRENT_BEFORE,()->{throw original;}));
        assertSame(original,caught);LiveNavigationDiagnostic.exception(trace,caught);
        var value=snapshot(trace);assertEquals("CURRENT_BEFORE",value.scalars().get("stage"));
        assertEquals("SECURITY",value.scalars().get("exceptionKind"));assertEquals("NOT_OBSERVED",value.scalars().get("checkCURRENT_BEFORE"));
        assertFalse(value.json().contains("PRIVATE"));assertFalse(value.json().contains("SecurityException"));
    }
    @Test public void windowsFailureLeavesActivitiesAndParserUnobserved(){
        var trace=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE);
        LiveNavigationDiagnostic.metadataStarted(trace,4);LiveNavigationDiagnostic.pairGate(trace,ReviewBingNavigation.PairGate.WINDOWS_READ);
        var read=new ReviewBingNavigation.FixedRead(ReviewBingNavigation.FixedStatus.READ_TIMEOUT,0,true,false,-1,true,true);
        LiveNavigationDiagnostic.fixed(trace,true,read,LiveNavigationDiagnostic.ExceptionKind.ILLEGAL_STATE,2001);
        var value=snapshot(trace);assertEquals("WINDOWS_READ",value.scalars().get("metadataPairGate"));
        assertEquals("READ_TIMEOUT",value.scalars().get("windowsStatus"));assertEquals("TRUE",value.scalars().get("windowsInvoked"));
        assertEquals("FALSE",value.scalars().get("windowsExitObserved"));assertEquals("TRUE",value.scalars().get("windowsClean"));
        assertEquals("NOT_OBSERVED",value.scalars().get("activitiesStatus"));assertEquals("NOT_OBSERVED",value.scalars().get("metadataParserStatus"));
    }
    @Test public void metadataSnapshotCopiesIntoTheCurrentOperationWithoutReplacingItsStage(){
        var metadata=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE);
        LiveNavigationDiagnostic.metadataStarted(metadata,9);LiveNavigationDiagnostic.pairGate(metadata,ReviewBingNavigation.PairGate.PARSED);
        var read=new ReviewBingNavigation.FixedRead(ReviewBingNavigation.FixedStatus.READ_COMPLETE,100,true,true,0,true,true);
        LiveNavigationDiagnostic.fixed(metadata,true,read,LiveNavigationDiagnostic.ExceptionKind.NONE,20);
        LiveNavigationDiagnostic.fixed(metadata,false,read,LiveNavigationDiagnostic.ExceptionKind.NONE,30);
        LiveNavigationDiagnostic.parser(metadata,BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,1000));
        LiveNavigationDiagnostic.metadataElapsed(metadata,60);
        var operation=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE);
        LiveNavigationDiagnostic.stage(operation,LiveNavigationDiagnostic.Stage.EXTRA_AFTER);
        LiveNavigationDiagnostic.check(operation,LiveNavigationDiagnostic.Check.EXTRA_AFTER,false);
        LiveNavigationDiagnostic.metadata(operation,snapshot(metadata));
        var value=snapshot(operation);assertEquals("EXTRA_AFTER",value.scalars().get("stage"));
        assertEquals("FALSE",value.scalars().get("checkEXTRA_AFTER"));assertEquals(9,value.scalars().get("metadataPair"));
        assertEquals("READY",value.scalars().get("metadataParserStatus"));assertEquals(60L,value.scalars().get("metadataElapsedMs"));
        assertEquals("TRUE",value.scalars().get("activitiesClean"));assertEquals(value.json(),value.json());
    }
    private static void rejected(Map<String,Object> input)throws Exception{
        var constructor=LiveNavigationDiagnostic.class.getDeclaredConstructor(Map.class);constructor.setAccessible(true);
        var error=assertThrows(InvocationTargetException.class,()->constructor.newInstance(input));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
    }
    @Test public void rawEnumsBooleansWrongIntegersAndOutOfBoundsValuesAreRejected()throws Exception{
        var good=snapshot(LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE)).scalars();
        for(var entry:java.util.List.of(
                Map.entry("call","RAW_ACCOUNT_URL"),Map.entry("stage","SOURCE TEXT"),Map.entry("exceptionKind","SecurityException"),
                Map.entry("checkEXTRA_AFTER",true),Map.entry("windowsInvoked",false),Map.entry("version",1L),
                Map.entry("version",2),Map.entry("metadataPair",4098),Map.entry("windowsBytes",-2),Map.entry("activitiesBytes",262145),
                Map.entry("windowsExitCode",-2),Map.entry("activitiesExitCode",65536),Map.entry("elapsedMs",-2L),
                Map.entry("windowsElapsedMs",2147483648L),Map.entry("elapsedMs",7))){
            var bad=new LinkedHashMap<>(good);bad.put(entry.getKey(),entry.getValue());rejected(bad);
        }
        var extra=new LinkedHashMap<>(good);extra.put("rawText","PRIVATE");rejected(extra);
        var missing=new LinkedHashMap<>(good);missing.remove("activitiesStatus");rejected(missing);
    }
    @Test public void unavailableDiagnosticsCannotChangeTheUnderlyingBooleanOrException(){
        assertTrue(LiveNavigationDiagnostic.observe(null,LiveNavigationDiagnostic.Stage.CURRENT_BEFORE,LiveNavigationDiagnostic.Check.CURRENT_BEFORE,()->true));
        assertFalse(LiveNavigationDiagnostic.observe(null,LiveNavigationDiagnostic.Stage.CURRENT_BEFORE,LiveNavigationDiagnostic.Check.CURRENT_BEFORE,()->false));
        var error=new IllegalStateException("PRIVATE");assertSame(error,assertThrows(IllegalStateException.class,()->
                LiveNavigationDiagnostic.observe(null,LiveNavigationDiagnostic.Stage.CURRENT_BEFORE,LiveNavigationDiagnostic.Check.CURRENT_BEFORE,()->{throw error;})));
        assertNull(LiveNavigationDiagnostic.finish(null,10));
        assertNull(LiveNavigationDiagnostic.finish(LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE),2147483648L));
        var unavailable=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE);
        LiveNavigationDiagnostic.stage(unavailable,null);
        assertTrue(LiveNavigationDiagnostic.observe(unavailable,LiveNavigationDiagnostic.Stage.CURRENT_BEFORE,LiveNavigationDiagnostic.Check.CURRENT_BEFORE,()->true));
        assertNull(snapshot(unavailable));
        unavailable=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE);
        LiveNavigationDiagnostic.pairGate(unavailable,null);assertNull(snapshot(unavailable));
    }
    @Test public void diagnosticClockFailuresStayUnobservedWithoutReplacingTheSourceFailure(){
        assertEquals(-1,LiveNavigationDiagnostic.clock(()->{throw new AssertionError("PRIVATE");}));
        assertEquals(123,LiveNavigationDiagnostic.clock(()->123));
        assertEquals(-1,LiveNavigationDiagnostic.elapsed(-1,100));assertEquals(-1,LiveNavigationDiagnostic.elapsed(100,99));
        assertEquals(10,LiveNavigationDiagnostic.elapsed(100,110));
        var trace=LiveNavigationDiagnostic.begin(LiveNavigationDiagnostic.Call.TEMPLATE);
        var value=LiveNavigationDiagnostic.finish(trace,-1);assertNotNull(value);assertEquals(-1L,value.scalars().get("elapsedMs"));
    }
}
