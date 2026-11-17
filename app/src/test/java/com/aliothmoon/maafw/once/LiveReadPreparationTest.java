package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;

/** Pure diagnostic fault injection. These tests construct no Android reader or action permit. */
public class LiveReadPreparationTest {
    private static LiveReadPreparation trace(int attempt){
        var trace=new LiveReadPreparation(attempt,"DAILY","BEFORE",2,true,true);
        trace.begin(100,9100,LiveReadPreparation.Mode.LIVE_REWARDS_UI_V1);return trace;
    }
    private static LiveReadPreparation.Snapshot finish(LiveReadPreparation trace,String status,boolean invoked,long now){
        return trace.finish(status,invoked,true,0,true,false,-1,now);
    }
    @FunctionalInterface private interface FailurePoint { void run()throws Exception; }
    private static LiveReadPreparation.Snapshot inject(LiveReadPreparation.Stage stage,FailurePoint operation){
        var trace=trace(3);trace.enter(stage,120);
        try{operation.run();}catch(Exception failure){trace.exception(failure);}
        trace.beginCleanup(150);return finish(trace,"IO_UNKNOWN",stage==LiveReadPreparation.Stage.NEW_PROCESS,160);
    }
    @Test public void preparationFailuresRetainTheirExactPhaseAndNeverExposeExceptionText(){
        for(var stage:new LiveReadPreparation.Stage[]{LiveReadPreparation.Stage.AUTHORITY_INITIAL,
                LiveReadPreparation.Stage.APK_VERIFY,LiveReadPreparation.Stage.BINDER,LiveReadPreparation.Stage.AUTHORITY_RECHECK,
                LiveReadPreparation.Stage.CLAIM_READER,LiveReadPreparation.Stage.SOURCE_GATE,LiveReadPreparation.Stage.UI_READ_BURN,
                LiveReadPreparation.Stage.SOURCE_CURRENT,LiveReadPreparation.Stage.POST_GATE,LiveReadPreparation.Stage.NEW_PROCESS}){
            var result=inject(stage,()->{throw new SecurityException("SENSITIVE_URL_ACCOUNT_AND_PAGE_TEXT");});
            assertEquals(stage.name(),result.value("stage"));assertEquals("SECURITY_EXCEPTION",result.value("exceptionKind"));
            assertEquals("EXCEPTION",result.value("termination"));assertEquals("IO_UNKNOWN",result.value("fixedStatus"));
            assertEquals(stage==LiveReadPreparation.Stage.NEW_PROCESS,result.value("invoked"));
            assertFalse(result.json().contains("SENSITIVE"));assertFalse(result.json().contains("SecurityException"));
        }
    }
    @Test public void exceptionClassificationDoesNotInvokeMessageOrToString(){
        var hostile=new SecurityException(){
            public String getMessage(){throw new AssertionError("Exception text must stay unread");}
            public String toString(){throw new AssertionError("Exception object must stay unread");}
        };
        assertEquals(LiveReadPreparation.ExceptionKind.SECURITY_EXCEPTION,LiveReadPreparation.classify(hostile));
        assertEquals(LiveReadPreparation.ExceptionKind.IO_EXCEPTION,LiveReadPreparation.classify(new IOException("SENSITIVE")));
        assertEquals(LiveReadPreparation.ExceptionKind.INTERRUPTED,LiveReadPreparation.classify(new InterruptedException("SENSITIVE")));
        assertEquals(LiveReadPreparation.ExceptionKind.ILLEGAL_ARGUMENT,LiveReadPreparation.classify(new IllegalArgumentException("SENSITIVE")));
        assertEquals(LiveReadPreparation.ExceptionKind.ILLEGAL_STATE,LiveReadPreparation.classify(new IllegalStateException("SENSITIVE")));
        assertEquals(LiveReadPreparation.ExceptionKind.UNSUPPORTED_OPERATION,LiveReadPreparation.classify(new UnsupportedOperationException("SENSITIVE")));
        assertEquals(LiveReadPreparation.ExceptionKind.RUNTIME_EXCEPTION,LiveReadPreparation.classify(new ArithmeticException("SENSITIVE")));
        assertEquals(LiveReadPreparation.ExceptionKind.CHECKED_EXCEPTION,LiveReadPreparation.classify(new Exception("SENSITIVE")));
        assertEquals(LiveReadPreparation.ExceptionKind.NONE,LiveReadPreparation.classify(null));
        var trace=trace(1);trace.enter(LiveReadPreparation.Stage.SOURCE_GATE,120);trace.exception(hostile);trace.beginCleanup(140);
        assertFalse(finish(trace,"IO_UNKNOWN",false,150).json().contains("SENSITIVE"));
    }
    @Test public void durationsUseInjectedBoundariesAndSumToThisAttemptOnly(){
        var trace=trace(2);trace.enter(LiveReadPreparation.Stage.AUTHORITY_INITIAL,110);trace.enter(LiveReadPreparation.Stage.APK_VERIFY,130);
        trace.enter(LiveReadPreparation.Stage.SOURCE_GATE,160);trace.enter(LiveReadPreparation.Stage.UI_READ_BURN,200);trace.uiReadBurnReturned(true);
        trace.enter(LiveReadPreparation.Stage.SOURCE_CURRENT,210);trace.sourceGateReturned();trace.enter(LiveReadPreparation.Stage.POST_GATE,220);
        trace.enter(LiveReadPreparation.Stage.NEW_PROCESS,225);trace.enter(LiveReadPreparation.Stage.READ,250);
        trace.enter(LiveReadPreparation.Stage.PROTOCOL,300);trace.beginCleanup(320);
        var result=trace.finish("READ_COMPLETE",true,true,128,true,true,0,330);
        assertEquals(230L,result.value("elapsedMs"));assertEquals(9000L,result.value("deadlineBudgetMs"));assertEquals(8770L,result.value("remainingMsAtFinish"));
        assertEquals(true,result.value("timingValid"));assertEquals(true,result.value("uiReadBurnReturned"));assertEquals(true,result.value("sourceGateReturned"));
        assertEquals("PROTOCOL",result.value("stage"));assertEquals("COMPLETED",result.value("termination"));
        var stages=(Map<?,?>)result.value("stageMillis");long sum=0;for(Object value:stages.values())sum+=(Long)value;
        assertEquals(230L,sum);assertEquals(40L,stages.get("SOURCE_GATE"));assertEquals(10L,stages.get("UI_READ_BURN"));assertEquals(10L,stages.get("CLEANUP"));
    }
    @Test public void rejectedGateAndPackageFailureRemainDistinctWithoutManufacturingInvocation(){
        var rejected=trace(1);rejected.enter(LiveReadPreparation.Stage.SOURCE_GATE,120);rejected.controlledStop();rejected.beginCleanup(130);
        var stop=finish(rejected,"GATE_REJECTED",false,140);
        assertEquals("CONTROLLED_STOP",stop.value("termination"));assertEquals("NONE",stop.value("exceptionKind"));
        assertEquals(false,stop.value("invoked"));assertEquals(false,stop.value("sourceGateReturned"));
        var unavailable=trace(2);unavailable.enter(LiveReadPreparation.Stage.APK_VERIFY,120);
        unavailable.exception(LiveReadPreparation.ExceptionKind.PACKAGE_NOT_FOUND);unavailable.beginCleanup(130);
        var failure=finish(unavailable,"IO_UNKNOWN",false,140);
        assertEquals("EXCEPTION",failure.value("termination"));assertEquals("PACKAGE_NOT_FOUND",failure.value("exceptionKind"));
        assertEquals("APK_VERIFY",failure.value("stage"));assertEquals(false,failure.value("invoked"));
    }
    @Test public void cleanupFailureDoesNotOverwriteAnEarlierPreparationException(){
        var trace=trace(4);trace.enter(LiveReadPreparation.Stage.UI_READ_BURN,120);trace.exception(new IOException("SENSITIVE_PRIMARY"));
        trace.beginCleanup(130);trace.cleanupException(new SecurityException("SENSITIVE_CLEANUP"));
        var result=trace.finish("CLEANUP_UNKNOWN",false,false,0,true,false,-1,140);
        assertEquals("UI_READ_BURN",result.value("stage"));assertEquals("IO_EXCEPTION",result.value("exceptionKind"));
        assertEquals("SECURITY_EXCEPTION",result.value("cleanupExceptionKind"));assertEquals(false,result.value("knownClean"));
        assertFalse(result.json().contains("SENSITIVE"));
        var stopped=trace(5);stopped.enter(LiveReadPreparation.Stage.SOURCE_GATE,120);stopped.controlledStop();stopped.beginCleanup(130);
        stopped.cleanupException(new IOException("SENSITIVE"));var retained=finish(stopped,"CLEANUP_UNKNOWN",false,140);
        assertEquals("SOURCE_GATE",retained.value("stage"));assertEquals("CONTROLLED_STOP",retained.value("termination"));
        assertEquals("IO_EXCEPTION",retained.value("cleanupExceptionKind"));
    }
    @Test public void aSourceFailureAndALaterBurnFailureHaveDifferentObservedBoundaries(){
        var source=inject(LiveReadPreparation.Stage.SOURCE_GATE,()->{throw new SecurityException("SENSITIVE");});
        assertEquals(false,source.value("sourceGateReturned"));assertEquals(false,source.value("uiReadBurnReturned"));
        var burn=trace(4);burn.enter(LiveReadPreparation.Stage.SOURCE_GATE,120);burn.sourceGateReturned();
        burn.enter(LiveReadPreparation.Stage.UI_READ_BURN,130);burn.exception(new IOException("SENSITIVE"));burn.beginCleanup(140);
        var failed=finish(burn,"IO_UNKNOWN",false,150);
        assertEquals(true,failed.value("sourceGateReturned"));assertEquals(false,failed.value("uiReadBurnReturned"));
        assertEquals("UI_READ_BURN",failed.value("stage"));assertEquals("IO_EXCEPTION",failed.value("exceptionKind"));
        var rejected=trace(5);rejected.enter(LiveReadPreparation.Stage.SOURCE_GATE,120);rejected.sourceGateReturned();
        rejected.enter(LiveReadPreparation.Stage.UI_READ_BURN,130);rejected.uiReadBurnReturned(true);
        rejected.enter(LiveReadPreparation.Stage.SOURCE_CURRENT,140);rejected.controlledStop();rejected.beginCleanup(150);
        var stop=finish(rejected,"GATE_REJECTED",false,160);
        assertEquals(true,stop.value("sourceGateReturned"));assertEquals(true,stop.value("uiReadBurnReturned"));assertEquals(false,stop.value("invoked"));
        assertEquals("SOURCE_CURRENT",stop.value("stage"));assertEquals("CONTROLLED_STOP",stop.value("termination"));
        var denied=trace(6);denied.enter(LiveReadPreparation.Stage.SOURCE_GATE,120);denied.sourceGateReturned();
        denied.enter(LiveReadPreparation.Stage.UI_READ_BURN,130);denied.uiReadBurnReturned(false);denied.controlledStop();denied.beginCleanup(140);
        var shortCircuit=finish(denied,"GATE_REJECTED",false,150);
        assertEquals(true,shortCircuit.value("sourceGateReturned"));assertEquals(false,shortCircuit.value("uiReadBurnReturned"));
        assertEquals("UI_READ_BURN",shortCircuit.value("stage"));assertEquals("GATE_REJECTED",shortCircuit.value("fixedStatus"));
        assertEquals(false,shortCircuit.value("invoked"));assertEquals("CONTROLLED_STOP",shortCircuit.value("termination"));
    }
    @Test public void latestAttemptDoesNotEraseOrDescribeTheEarlierAttempts(){
        var first=trace(1);first.enter(LiveReadPreparation.Stage.PROTOCOL,120);first.beginCleanup(130);
        var completed=finish(first,"READ_COMPLETE",true,140);
        var second=inject(LiveReadPreparation.Stage.SOURCE_GATE,()->{throw new IOException("SENSITIVE");});
        assertEquals(1,completed.value("attemptIndex"));assertEquals(true,completed.value("invoked"));
        assertEquals(3,second.value("attemptIndex"));assertEquals(false,second.value("invoked"));
        assertEquals(2,second.value("scopeUiIndex"));assertEquals("DAILY",second.value("scopeKind"));
        assertEquals("BEFORE",second.value("observationPhase"));assertEquals(true,second.value("scan"));
    }
    @Test public void aClockRegressionIsVisibleAndCannotProduceNegativeTiming(){
        var trace=trace(1);trace.enter(LiveReadPreparation.Stage.SOURCE_GATE,120);trace.enter(LiveReadPreparation.Stage.POST_GATE,110);trace.beginCleanup(130);
        var result=finish(trace,"IO_UNKNOWN",false,140);assertEquals(false,result.value("timingValid"));
        for(Object duration:((Map<?,?>)result.value("stageMillis")).values())assertTrue((Long)duration>=0);
        assertTrue((Long)result.value("elapsedMs")>=0);assertTrue((Long)result.value("remainingMsAtFinish")>=0);
    }
    @Test public void snapshotsAreImmutableAndUnknownLabelsNeverBecomeTextDiagnostics(){
        var trace=new LiveReadPreparation(1,"SENSITIVE","SENSITIVE",1,false,false);trace.begin(100,9100,LiveReadPreparation.Mode.LIVE_VISIT_V1);
        trace.enter(LiveReadPreparation.Stage.SOURCE_GATE,120);trace.beginCleanup(130);var result=finish(trace,"SENSITIVE",false,140);
        assertEquals("UNKNOWN",result.value("scopeKind"));assertEquals("UNKNOWN",result.value("observationPhase"));
        assertEquals("UNKNOWN",result.value("fixedStatus"));assertEquals("LIVE_VISIT_V1",result.value("requestMode"));assertFalse(result.json().contains("SENSITIVE"));
        assertThrows(UnsupportedOperationException.class,()->result.scalars().put("injected","SENSITIVE"));
        @SuppressWarnings("unchecked") var durations=(Map<String,Object>)result.value("stageMillis");
        assertThrows(UnsupportedOperationException.class,()->durations.put("SENSITIVE",1L));
        String frozen=result.json();trace.enter(LiveReadPreparation.Stage.READ,170);assertEquals(frozen,result.json());
    }
    @Test public void diagnosticClockFaultsCannotMaskTheOriginalFailureOrSkipCleanup(){
        for(Throwable diagnosticFailure:new Throwable[]{new IllegalStateException("SENSITIVE"),new AssertionError("SENSITIVE"),new OutOfMemoryError("SENSITIVE")}){
            var trace=new LiveReadPreparation(1,"DAILY","BEFORE",1,true,true);
            trace.begin(100,9100,LiveReadPreparation.Mode.LIVE_REWARDS_UI_V1,()->{
                if(diagnosticFailure instanceof Error error)throw error;
                throw (RuntimeException)diagnosticFailure;
            });
            var original=new IOException("ORIGINAL_SOURCE_FAILURE");boolean[] cleaned={false};
            var propagated=assertThrows(IOException.class,()->{
                try{trace.enter(LiveReadPreparation.Stage.SOURCE_GATE);trace.exception(original);throw original;}
                finally{trace.beginCleanup();cleaned[0]=true;}
            });
            assertSame(original,propagated);assertTrue(cleaned[0]);
            assertNull(trace.finish("IO_UNKNOWN",false,true,0,true,false,-1));
        }
        boolean[] fail={false};var trace=new LiveReadPreparation();
        trace.begin(100,9100,LiveReadPreparation.Mode.LIVE_REWARDS_UI_V1,()->{
            if(fail[0])throw new AssertionError("SENSITIVE");return 120;
        });
        trace.enter(LiveReadPreparation.Stage.PROTOCOL);trace.beginCleanup();fail[0]=true;
        assertNull(trace.finish("READ_COMPLETE",true,true,10,true,true,0));
    }
}
