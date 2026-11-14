package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import org.junit.Test;

public class OverviewUiProtocolTest {
    private static final String NONCE="0123456789abcdef0123456789abcdef";
    private static NativeUiStructureProtocol.Report source() {
        return new NativeUiStructureProtocol.Report(NONCE,100,120,140,NativeUiStructureProtocol.Reason.OK,
                NativeUiStructureProtocol.Cleanup.CONFIRMED,39,0,7,0,0,0,1440,2400,-1,-1,
                NativeUiStructureProtocol.Task.unknown(),NativeUiStructureProtocol.Task.unknown(),
                new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,0,0,0,0,0,0));
    }
    private static OverviewUiProtocol.Report report() {
        return new OverviewUiProtocol.Report(source(),OverviewUiParser.parse(OverviewUiParserTest.sample()),
                OverviewUiProtocol.ScrollHint.DECLARED_BOTH);
    }
    private static OverviewUiProtocol.Report parse(String line) {return OverviewUiProtocol.parse(line,NONCE,99,1000,160);}
    private static void rejected(Runnable task) {try{task.run();fail("Expected fixed protocol rejection");}catch(IllegalArgumentException expected){}}

    @Test public void roundTripContainsOnlyFixedScalars() {
        var r=report();String wire=OverviewUiProtocol.encode(r);
        assertTrue(wire.startsWith("OVR1|UIS2|"));assertTrue(wire.matches("[A-Z0-9a-f_|-]+"));
        assertEquals(r,parse(wire));assertTrue(parse(wire).qualified());assertTrue(wire.length()<OverviewUiProtocol.MAX_LINE);
    }
    @Test public void legacyProtocolDoesNotAcceptOverviewEnvelope() {
        String wire=OverviewUiProtocol.encode(report());
        rejected(()->NativeUiStructureProtocol.parse(wire,NONCE,99,1000,160,true));
        assertTrue(NativeUiStructureProtocol.encode(source()).startsWith("UIS2|"));
    }
    @Test public void wrongNonceAndLateSourceCannotBeImported() {
        String wire=OverviewUiProtocol.encode(report());
        rejected(()->OverviewUiProtocol.parse(wire,"ffffffffffffffffffffffffffffffff",99,1000,160));
        rejected(()->OverviewUiProtocol.parse(wire,NONCE,99,150,160));
        rejected(()->OverviewUiProtocol.parse(wire,NONCE,130,1000,160));
    }
    @Test public void extraFieldsWhitespaceAndNonAsciiRejected() {
        String wire=OverviewUiProtocol.encode(report());
        for(String mutation:new String[]{wire+"|0",wire+"\n"," "+wire,wire+"签入",wire+"|OV|0"})rejected(()->parse(mutation));
    }
    @Test public void incorrectRegionCountAndUnknownRoleRejected() {
        String wire=OverviewUiProtocol.encode(report());
        rejected(()->parse(wire.replace("|6|CHECK_IN|","|5|CHECK_IN|")));
        rejected(()->parse(wire.replace("|CHECK_IN|","|SEARCH|")));
    }
    @Test public void duplicateRoleAndNoncanonicalNumberRejected() {
        String wire=OverviewUiProtocol.encode(report());
        rejected(()->parse(wire.replace("|DAILY_PROGRESS|","|CHECK_IN|")));
        rejected(()->parse(wire.replace("|1440|","|01440|")));
    }
    @Test public void outOfViewportAndInvalidCounterRejected() {
        String wire=OverviewUiProtocol.encode(report());
        rejected(()->parse(wire.replace("|1376|","|8193|")));
        rejected(()->parse(wire.replace("|30|60|","|61|60|")));
    }
    @Test public void noStructureMeansNoScrollHintOrGeometry() {
        var empty=OverviewUiRegions.Result.rejected(OverviewUiRegions.Status.WRONG_STRUCTURE);
        var r=new OverviewUiProtocol.Report(source(),empty,OverviewUiProtocol.ScrollHint.UNKNOWN);
        assertFalse(parse(OverviewUiProtocol.encode(r)).qualified());
        rejected(()->new OverviewUiProtocol.Report(source(),empty,OverviewUiProtocol.ScrollHint.DECLARED_BOTH));
    }
    @Test public void declaredNoneDoesNotBecomeCoverageOrCompletion() {
        var r=new OverviewUiProtocol.Report(source(),report().overview(),OverviewUiProtocol.ScrollHint.DECLARED_NONE);
        String wire=OverviewUiProtocol.encode(r);assertEquals(OverviewUiProtocol.ScrollHint.DECLARED_NONE,parse(wire).scrollHint());
        assertFalse(wire.contains("COMPLETE"));assertFalse(wire.contains("CLICK"));
    }
    @Test public void invisibleActivityHeadingRoundTripsButCheckInRequiresVisibleHeading() {
        var original=report();var regions=new java.util.ArrayList<>(original.overview().regions());
        int i=OverviewUiRegions.Role.MORE_ACTIVITIES.ordinal();var more=regions.get(i);
        regions.set(i,new OverviewUiRegions.Region(more.role(),more.coarse(),null,more.counter(),false,more.current(),more.target()));
        var r=new OverviewUiProtocol.Report(source(),new OverviewUiRegions.Result(OverviewUiRegions.Status.OBSERVED,original.overview().viewport(),regions),OverviewUiProtocol.ScrollHint.UNKNOWN);
        assertNull(parse(OverviewUiProtocol.encode(r)).overview().regions().get(i).heading());
        var checkIn=regions.get(0);rejected(()->new OverviewUiRegions.Region(checkIn.role(),checkIn.coarse(),null,null,false,-1,-1));
    }
}
