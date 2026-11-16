package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

/** Existing scalar protocol data only. Diagnostics never turn rejected evidence into a permit. */
public class LiveUiDiagnosticTest {
    private static AndroidLiveUiReader.Result read(LiveUiProtocol.Report report,AndroidLiveUiReader.FixedStatus status,boolean clean){
        return new AndroidLiveUiReader.Result(report,null,status,true,clean,123,true,true,0);
    }
    private static LiveUiProtocol.Report rejected(NativeUiStructureProtocol.Report source,OverviewUiRegions.Status overview,RemainingCardUi.Status cards){
        return new LiveUiProtocol.Report(new OverviewUiProtocol.Report(source,OverviewUiRegions.Result.rejected(overview),OverviewUiProtocol.ScrollHint.UNKNOWN,
                RemainingCardUi.Result.rejected(cards)),List.of());
    }
    private static NativeUiStructureProtocol.Report blocked(boolean signIn,boolean error){
        return new NativeUiStructureProtocol.Report(LiveUiProtocolTest.NONCE,100,120,140,NativeUiStructureProtocol.Reason.OK,
                NativeUiStructureProtocol.Cleanup.CONFIRMED,39,0,7,0,0,0,1440,2560,-1,-1,
                NativeUiStructureProtocol.Task.unknown(),NativeUiStructureProtocol.Task.unknown(),
                new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,signIn?1:0,error?1:0,(signIn?4:0)|(error?8:0),0,0,0));
    }
    @Test public void qualifiedReportRecordsCountsAndEnumsWithoutAnyTaskText(){
        var report=LiveUiProtocolTest.sample();var diagnostic=LiveUiDiagnostic.from(read(report,AndroidLiveUiReader.FixedStatus.READ_COMPLETE,true));
        assertTrue(report.qualified());assertEquals(true,diagnostic.value("sourceQualified"));assertEquals(true,diagnostic.value("reportQualified"));
        assertEquals("OBSERVED",diagnostic.value("overviewStatus"));assertEquals("OBSERVED",diagnostic.value("cardsStatus"));
        assertEquals(3,diagnostic.value("dailyCount"));assertEquals(7,diagnostic.value("moreCount"));assertEquals(10,diagnostic.value("identityCount"));
        assertEquals(6,diagnostic.value("visibleCardCount"));assertEquals(39,diagnostic.value("nodeCount"));
        assertEquals((1<<OverviewUiRegions.Role.DAILY_ACTIVITIES.ordinal())|(1<<OverviewUiRegions.Role.MORE_ACTIVITIES.ordinal()),diagnostic.value("roleMask"));
        String json=diagnostic.json();assertFalse(json.contains("Synthetic"));assertFalse(json.contains("积分"));assertFalse(json.contains("descriptorHash"));
        assertFalse(json.contains("nonce"));assertFalse(json.contains("windowId"));
        for(Object value:diagnostic.scalars().values())assertTrue(value instanceof Boolean||value instanceof Integer||value instanceof String&&((String)value).matches("[A-Z0-9_]+"));
        assertThrows(UnsupportedOperationException.class,()->diagnostic.scalars().put("foreign","ORIGINAL_TEXT"));
        assertTrue(diagnostic.safeState().matches("[A-Z0-9_]{1,128}"));
    }
    @Test public void helperOkAndConfirmedCleanupStillDoNotHideSignInOrErrorBlocking(){
        for(boolean signIn:new boolean[]{true,false}){
            var report=rejected(blocked(signIn,true),OverviewUiRegions.Status.WRONG_STRUCTURE,RemainingCardUi.Status.WRONG_STRUCTURE);
            var diagnostic=LiveUiDiagnostic.from(read(report,AndroidLiveUiReader.FixedStatus.READ_COMPLETE,true));
            assertFalse(report.qualified());assertEquals("OK",diagnostic.value("helperReason"));assertEquals("CONFIRMED",diagnostic.value("helperCleanup"));
            assertEquals(true,diagnostic.value("sourceQualified"));assertEquals(true,diagnostic.value("readinessBlocked"));
            assertEquals(false,diagnostic.value("reportQualified"));assertEquals(signIn?"UI_READINESS_SIGN_IN_ERROR":"UI_READINESS_ERROR",diagnostic.safeState());
        }
    }
    @Test public void globalOverviewFailureIsDistinguishedFromRemainingCardFailureWithoutChangingEither(){
        for(var status:List.of(OverviewUiRegions.Status.WRONG_STRUCTURE,OverviewUiRegions.Status.AMBIGUOUS_STRUCTURE)){
            var report=rejected(LiveUiProtocolTest.source(2560),status,RemainingCardUi.Status.WRONG_STRUCTURE);
            var diagnostic=LiveUiDiagnostic.from(read(report,AndroidLiveUiReader.FixedStatus.READ_COMPLETE,true));
            assertFalse(report.qualified());assertEquals("UI_OVERVIEW_"+status.name(),diagnostic.safeState());assertEquals(false,diagnostic.value("readinessBlocked"));
        }
        var original=LiveUiProtocolTest.sample();var base=new OverviewUiProtocol.Report(original.base().source(),original.base().overview(),OverviewUiProtocol.ScrollHint.UNKNOWN,
                RemainingCardUi.Result.rejected(RemainingCardUi.Status.AMBIGUOUS_STRUCTURE));
        var report=new LiveUiProtocol.Report(base,List.of());var diagnostic=LiveUiDiagnostic.from(read(report,AndroidLiveUiReader.FixedStatus.READ_COMPLETE,true));
        assertFalse(report.qualified());assertEquals("UI_CARDS_AMBIGUOUS_STRUCTURE",diagnostic.safeState());assertEquals("OBSERVED",diagnostic.value("overviewStatus"));
    }
    @Test public void anUnknownSingleRegionRosterIsNotMisreportedAsGlobalSourceOrGrammarFailure(){
        var original=LiveUiProtocolTest.sample();var base=new OverviewUiProtocol.Report(original.base().source(),original.base().overview(),OverviewUiProtocol.ScrollHint.UNKNOWN,
                new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED,-1,-1,List.of()));
        var report=new LiveUiProtocol.Report(base,List.of());var diagnostic=LiveUiDiagnostic.from(read(report,AndroidLiveUiReader.FixedStatus.READ_COMPLETE,true));
        assertTrue(report.qualified());assertEquals(-1,diagnostic.value("dailyCount"));assertEquals(-1,diagnostic.value("moreCount"));
        assertEquals("UI_READ_COMPLETE_OK_OV_OBSERVED_CARDS_OBSERVED",diagnostic.safeState());
    }
    @Test public void permissionOrCleanupRejectionIsRetainedAndNeverBecomesQualified(){
        var source=new NativeUiStructureProtocol.Report(LiveUiProtocolTest.NONCE,100,120,140,NativeUiStructureProtocol.Reason.REFLECTION_DENIED,
                NativeUiStructureProtocol.Cleanup.CONFIRMED,0,0,-1,-1,0,0,0,0,-1,-1,
                NativeUiStructureProtocol.Task.unknown(),NativeUiStructureProtocol.Task.unknown(),
                new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,0,0,0,0,0,0,0,0));
        var report=rejected(source,OverviewUiRegions.Status.WRONG_STRUCTURE,RemainingCardUi.Status.WRONG_STRUCTURE);
        var diagnostic=LiveUiDiagnostic.from(read(report,AndroidLiveUiReader.FixedStatus.READ_COMPLETE,true));
        assertFalse(report.qualified());assertEquals("UI_SOURCE_REFLECTION_DENIED_CONFIRMED",diagnostic.safeState());
        assertEquals(false,diagnostic.value("sourceQualified"));
        var unknown=LiveUiDiagnostic.from(read(null,AndroidLiveUiReader.FixedStatus.CLEANUP_UNKNOWN,false));
        assertEquals("UI_CLEANUP_UNCONFIRMED",unknown.safeState());assertEquals("NONE",unknown.value("helperReason"));assertEquals(-1,unknown.value("nodeCount"));
        assertEquals(false,unknown.value("reportQualified"));
    }
}
