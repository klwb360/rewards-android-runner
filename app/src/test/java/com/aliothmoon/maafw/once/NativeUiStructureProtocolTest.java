package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeUiStructureProtocolTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static NativeUiStructureProtocol.Task unknown() { return NativeUiStructureProtocol.Task.unknown(); }
    private static NativeUiStructureProtocol.Report good() {
        return new NativeUiStructureProtocol.Report(NONCE,110,120,130,
                NativeUiStructureProtocol.Reason.OK,NativeUiStructureProtocol.Cleanup.CONFIRMED,
                20,100,3,0,0,50,1080,2200,-1,-1,
                new NativeUiStructureProtocol.Task(NativeUiStructureProtocol.Status.KNOWN,3,90,1,
                        NativeUiStructureProtocol.Unit.EXPLICIT_TEXT_POINTS),unknown());
    }
    private static String line() { return NativeUiStructureProtocol.encode(good()); }
    private static void bad(String line) {
        assertThrows(IllegalArgumentException.class,()->NativeUiStructureProtocol.parse(line,NONCE,100,1000,140));
    }
    @Test public void roundTripHasScalarOnlyEvidence() {
        assertEquals(good(),NativeUiStructureProtocol.parse(line(),NONCE,100,1000,140));
        assertTrue(good().qualified()); assertEquals(-1,good().reading().current());
    }
    @Test public void rejectsWrongNonce() { bad(line().replace(NONCE,"ffffffffffffffffffffffffffffffff")); }
    @Test public void rejectsTrailingLineAndExtraFields() { bad(line()+"\n");bad(line()+"|0"); }
    @Test public void rejectsRawTextAndUnicode() {bad(line()+"<node text='secret'/>");bad(line()+"搜索");}
    @Test public void rejectsOverlongLine() { bad("A".repeat(4097)); }
    @Test public void rejectsNoncanonicalNumbers() { bad(line().replace("|110|","|0110|"));bad(line().replace("|110|","|+110|")); }
    @Test public void rejectsUnknownEnums() {bad(line().replace("|OK|","|GREAT|"));}
    @Test public void rejectsOverflowWithoutExceptionTextLeak() {bad(line().replace("|20|","|9999999999999999999|"));}
    @Test public void rejectsReversedAcquisition() {bad(line().replace("|120|","|109|"));}
    @Test public void rejectsBeforeDispatch() {
        assertThrows(IllegalArgumentException.class,()->NativeUiStructureProtocol.parse(line(),NONCE,111,1000,140));
    }
    @Test public void rejectsFutureAndExpiredPublication() {
        assertThrows(IllegalArgumentException.class,()->NativeUiStructureProtocol.parse(line(),NONCE,100,1000,129));
        assertThrows(IllegalArgumentException.class,()->NativeUiStructureProtocol.parse(line(),NONCE,100,1000,1000));
    }
    @Test public void rejectsExpandedDeadline() {
        assertThrows(IllegalArgumentException.class,()->NativeUiStructureProtocol.parse(line(),NONCE,100,10101,140));
    }
    @Test public void cannotPublishKnownOnFailureOrUnknownCleanup() {
        bad(line().replace("|OK|","|TREE_FAILED|"));bad(line().replace("|CONFIRMED|","|UNKNOWN|"));
    }
    @Test public void rejectsZeroNodesAndWrongDisplay() {bad(line().replace("|20|100|3|0|","|0|100|3|0|"));bad(line().replace("|20|100|3|0|","|20|100|3|1|"));}
    @Test public void rejectsImpossibleCountersAndUnknownNumericClaims() {
        bad(line().replace("|KNOWN|3|90|1|","|KNOWN|91|90|1|"));
        bad(line().replace("|UNKNOWN|-1|-1|0|","|UNKNOWN|0|0|0|"));
        bad(line().replace("|KNOWN|3|90|1|","|KNOWN|3|90|2|"));
    }
    @Test public void rejectsKnownWithoutExplicitPointsOrBeyondParserBound() {
        bad(line().replace("|KNOWN|3|90|1|EXPLICIT_TEXT_POINTS", "|KNOWN|3|90|1|UNESTABLISHED"));
        bad(line().replace("|KNOWN|3|90|1|", "|KNOWN|3|10001|1|"));
    }
    @Test public void failureRoundTripCannotQualify() {
        var r = new NativeUiStructureProtocol.Report(NONCE,110,110,130,
                NativeUiStructureProtocol.Reason.REFLECTION_DENIED,NativeUiStructureProtocol.Cleanup.NOT_STARTED,
                0,0,-1,-1,0,0,0,0,-1,-1,unknown(),unknown());
        assertFalse(NativeUiStructureProtocol.parse(NativeUiStructureProtocol.encode(r),NONCE,100,1000,140).qualified());
    }
    @Test public void rejectsUnsupportedAcquisitionDuration() {
        assertThrows(IllegalArgumentException.class,()->new NativeUiStructureProtocol.Report(NONCE,0,5001,5100,
                NativeUiStructureProtocol.Reason.OK,NativeUiStructureProtocol.Cleanup.CONFIRMED,
                1,0,0,0,0,0,1,1,-1,-1,unknown(),unknown()));
    }
    private NativeUiStructureProtocol.Report ready() {
        var r=good();
        return new NativeUiStructureProtocol.Report(r.nonce(),r.started(),r.acquired(),r.finished(),r.reason(),r.cleanup(),r.nodeCount(),r.charCount(),r.windowId(),r.displayId(),r.left(),r.top(),r.right(),r.bottom(),r.rotationBefore(),r.rotationAfter(),r.search(),r.reading(),new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,0,0,0,1,1,2));
    }
    @Test public void readyVersionRoundTripAndStrictModeBinding() {
        String wire=NativeUiStructureProtocol.encode(ready());
        assertTrue(wire.startsWith("UIS2|"));
        assertEquals(ready(),NativeUiStructureProtocol.parse(wire,NONCE,100,1000,140,true));
        bad(wire);
        assertThrows(IllegalArgumentException.class,()->NativeUiStructureProtocol.parse(line(),NONCE,100,1000,140,true));
    }
    @Test public void readyTailIsBoundedAndExact() {
        String wire=NativeUiStructureProtocol.encode(ready());
        for(String invalid:new String[]{wire+"|0",wire.replace("IDLE_OBSERVED","LOADED"),wire.replace("|1|0|0|0|0|1|1|2","|513|0|0|0|0|1|1|2")})
            assertThrows(IllegalArgumentException.class,()->NativeUiStructureProtocol.parse(invalid,NONCE,100,1000,140,true));
    }
    @Test public void blockerAllowsDiagnosticButNeverCounterAuthority() {
        var r=ready();var d=new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,1,0,4,0,0,0);
        assertThrows(IllegalArgumentException.class,()->new NativeUiStructureProtocol.Report(r.nonce(),r.started(),r.acquired(),r.finished(),r.reason(),r.cleanup(),r.nodeCount(),r.charCount(),r.windowId(),r.displayId(),r.left(),r.top(),r.right(),r.bottom(),r.rotationBefore(),r.rotationAfter(),r.search(),r.reading(),d));
        var blocked=new NativeUiStructureProtocol.Report(r.nonce(),r.started(),r.acquired(),r.finished(),r.reason(),r.cleanup(),r.nodeCount(),r.charCount(),r.windowId(),r.displayId(),r.left(),r.top(),r.right(),r.bottom(),r.rotationBefore(),r.rotationAfter(),unknown(),unknown(),d);
        assertTrue(blocked.qualified());assertEquals(blocked,NativeUiStructureProtocol.parse(NativeUiStructureProtocol.encode(blocked),NONCE,100,1000,140,true));
        String output=new UiStructurePublication(blocked,true,1000).publish(140,true,true,true);assertTrue(output.contains("pageState=HOLD"));assertTrue(output.contains("SEARCH=UNKNOWN"));assertTrue(output.contains("signInCount=1"));
    }
    @Test public void readyAcquisitionBoundDoesNotChangeLegacy() {
        var r=ready();
        assertTrue(new NativeUiStructureProtocol.Report(NONCE,0,8000,8100,r.reason(),r.cleanup(),r.nodeCount(),r.charCount(),r.windowId(),r.displayId(),r.left(),r.top(),r.right(),r.bottom(),-1,-1,unknown(),unknown(),r.readiness()).qualified());
        assertThrows(IllegalArgumentException.class,()->new NativeUiStructureProtocol.Report(NONCE,0,8001,8100,r.reason(),r.cleanup(),r.nodeCount(),r.charCount(),r.windowId(),r.displayId(),r.left(),r.top(),r.right(),r.bottom(),-1,-1,unknown(),unknown(),r.readiness()));
    }
}
