package com.aliothmoon.maafw.once;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.NativeUiStructureProtocol.*;

/** Wire and cross-field contracts only; no Android UI, image, or action evidence. */
public class NativeUiRegionsProtocolTest {
    private static final String NONCE="0123456789abcdef0123456789abcdef";
    private static Task known(){return new Task(Status.KNOWN,3,90,1,Unit.EXPLICIT_TEXT_POINTS);}
    private static Task unknown(){return Task.unknown();}
    private static NativeUiReadyDiagnostics.Result ready(){
        return new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,0,0,0,1,1,0);
    }
    private static NativeUiRegions.Rect rect(int l,int t,int r,int b){return new NativeUiRegions.Rect(l,t,r,b);}
    private static NativeUiRegions.Region search(){
        return new NativeUiRegions.Region(NativeUiRegions.Role.SEARCH,rect(10,100,1070,300),rect(20,110,1000,150));
    }
    private static NativeUiRegions.Region reading(){
        return new NativeUiRegions.Region(NativeUiRegions.Role.READING,rect(10,400,1070,650),null);
    }
    private static Report report(Task search,Task reading,NativeUiRegions.Pair regions){
        return report(search,reading,regions,Reason.OK,Cleanup.CONFIRMED,ready(),true);
    }
    private static Report report(Task search,Task reading,NativeUiRegions.Pair regions,Reason reason,
            Cleanup cleanup,NativeUiReadyDiagnostics.Result readiness,boolean structureBound){
        return new Report(NONCE,110,120,130,reason,cleanup,7,100,3,0,0,50,1080,2200,
                -1,-1,search,reading,readiness,null,structureBound,regions);
    }
    private static Report good(){return report(known(),unknown(),new NativeUiRegions.Pair(search(),reading()));}
    private static String wire(){return encode(good());}
    private static Report read(String wire){return parse(wire,NONCE,100,1000,140,true,false,true,true);}
    private static String field(String wire,int at,String value){
        String[] fields=wire.split("\\|",-1);fields[at]=value;return String.join("|",fields);
    }
    private static String fields(String wire,int at,String... values){
        String[] fields=wire.split("\\|",-1);System.arraycopy(values,0,fields,at,values.length);return String.join("|",fields);
    }
    private static void reject(String wire){assertThrows(IllegalArgumentException.class,()->read(wire));}

    @Test public void roundTripPreservesBothTypedRegionsWithoutRawFields(){
        String wire=wire();assertTrue(wire.startsWith("UIS5|"));assertEquals(56,wire.split("\\|",-1).length);
        assertEquals(good(),read(wire));assertTrue(read(wire).structureBound());
        assertTrue(wire.contains("|SEARCH|SUMMARY|10|100|1070|300|20|110|1000|150"));
        assertTrue(wire.endsWith("|READING|CARD|10|400|1070|650|-1|-1|-1|-1"));
        assertTrue(wire.matches("[A-Z0-9a-f_|-]+"));
    }
    @Test public void emptyPairIsStillAnExplicitNewProtocol(){
        Report empty=report(unknown(),unknown(),NativeUiRegions.Pair.empty());
        assertEquals(empty,read(encode(empty)));assertNotNull(read(encode(empty)).regions());
        assertTrue(encode(empty).endsWith("|SEARCH|UNKNOWN|-1|-1|-1|-1|-1|-1|-1|-1|READING|UNKNOWN|-1|-1|-1|-1|-1|-1|-1|-1"));
    }
    @Test public void oldBoundConstructorKeepsUIS4AndNullRegions(){
        Report r=good();Report old=new Report(r.nonce(),r.started(),r.acquired(),r.finished(),r.reason(),r.cleanup(),
                r.nodeCount(),r.charCount(),r.windowId(),r.displayId(),r.left(),r.top(),r.right(),r.bottom(),
                r.rotationBefore(),r.rotationAfter(),r.search(),r.reading(),r.readiness(),null,true);
        assertNull(old.regions());assertEquals(36,encode(old).split("\\|",-1).length);
        assertEquals(old,parse(encode(old),NONCE,100,1000,140,true,false,true));reject(encode(old));
    }
    @Test public void everyOldParseOverloadRejectsUIS5(){
        String s=wire();
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,100,1000,140));
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,100,1000,140,true));
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,100,1000,140,true,false));
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,100,1000,140,true,false,true));
    }
    @Test public void newModeRequiresBoundReadyAndNoFieldDiagnostics(){
        String s=wire();
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,100,1000,140,true,false,false,true));
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,100,1000,140,false,false,true,true));
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,100,1000,140,true,true,true,true));
        assertThrows(IllegalArgumentException.class,()->report(unknown(),unknown(),NativeUiRegions.Pair.empty(),Reason.OK,Cleanup.CONFIRMED,ready(),false));
        assertThrows(IllegalArgumentException.class,()->report(unknown(),unknown(),NativeUiRegions.Pair.empty(),Reason.OK,Cleanup.CONFIRMED,null,true));
    }
    @Test public void rejectsVersionDowngradeAndWrongNonce(){
        for(String version:new String[]{"UIS1","UIS2","UIS3","UIS4","UIS6"})reject(field(wire(),0,version));
        reject(field(wire(),1,"ffffffffffffffffffffffffffffffff"));
    }
    @Test public void rejectsMissingExtraAndSplitTransportFields(){
        String s=wire();String[] f=s.split("\\|",-1);
        reject(String.join("|",Arrays.copyOf(f,55)));reject(s+"|0");reject(s+"\n");reject(s+"\n"+s);
    }
    @Test public void rejectsRawTextUnicodeAndOverlongLines(){
        reject(field(wire(),36,"<node text='private'/>") );reject(field(wire(),36,"搜索"));
        reject(field(wire(),36,"search"));reject("A".repeat(MAX_LINE+1));
    }
    @Test public void rolesAreFixedOrderedAndNotInterchangeable(){
        reject(field(wire(),36,"READING"));reject(field(wire(),46,"SEARCH"));
        reject(field(wire(),36,"MORE"));reject(field(wire(),47,"READY"));
    }
    @Test public void unknownRegionHasExactlyEightMinusOneCoordinates(){
        String s=encode(report(unknown(),unknown(),NativeUiRegions.Pair.empty()));
        for(int i=38;i<=45;i++)reject(field(s,i,"0"));
        for(int i=48;i<=55;i++)reject(field(s,i,"0"));
        reject(field(s,38,"-01"));reject(field(s,55,"-2"));
    }
    @Test public void cardOnlyCannotCarryAnySummaryCoordinate(){
        for(int i=52;i<=55;i++)reject(field(wire(),i,"0"));
        reject(field(wire(),52,"-01"));
    }
    @Test public void coordinatesRejectNoncanonicalNegativeEmptyAndOverflow(){
        for(String value:new String[]{"-1","-2","+10","010","","8193","2147483648","9999999999999999999"})reject(field(wire(),38,value));
        reject(field(wire(),40,"10"));reject(field(wire(),40,"9"));reject(field(wire(),41,"100"));
    }
    @Test public void cardMustBeWithinReportedWindowOnEverySide(){
        reject(field(wire(),11,"11"));reject(field(wire(),12,"101"));
        reject(field(wire(),13,"1069"));reject(field(wire(),14,"649"));
    }
    @Test public void summaryMustBeInsideItsOwnCard(){
        reject(field(wire(),42,"9"));reject(field(wire(),43,"99"));
        reject(field(wire(),44,"1071"));reject(field(wire(),45,"301"));
        reject(field(wire(),44,"20"));reject(field(wire(),45,"110"));
    }
    @Test public void knownCounterRequiresRegionAndSummaryInNewProtocol(){
        reject(fields(wire(),37,"UNKNOWN","-1","-1","-1","-1","-1","-1","-1","-1"));
        reject(fields(wire(),37,"CARD","10","100","1070","300","-1","-1","-1","-1"));
        assertThrows(IllegalArgumentException.class,()->report(known(),unknown(),NativeUiRegions.Pair.empty()));
    }
    @Test public void summaryCannotClaimUnknownAmbiguousOrInvalidCounter(){
        for(Status status:new Status[]{Status.UNKNOWN,Status.AMBIGUOUS,Status.INVALID})
            reject(fields(wire(),17,status.name(),"-1","-1","1","UNESTABLISHED"));
    }
    @Test public void cardOnlyMayKeepUnknownAmbiguousOrInvalidCounter(){
        var region=new NativeUiRegions.Region(NativeUiRegions.Role.SEARCH,search().card(),null);
        for(Status status:new Status[]{Status.UNKNOWN,Status.AMBIGUOUS,Status.INVALID}){
            Report r=report(new Task(status,-1,-1,1,Unit.UNESTABLISHED),unknown(),new NativeUiRegions.Pair(region,null));
            assertEquals(r,read(encode(r)));assertNull(read(encode(r)).regions().search().summary());
        }
    }
    @Test public void eitherTaskCanCarryTheOnlyKnownSummary(){
        var readRegion=new NativeUiRegions.Region(NativeUiRegions.Role.READING,reading().card(),rect(20,410,1000,450));
        Report r=report(unknown(),known(),new NativeUiRegions.Pair(null,readRegion));assertEquals(r,read(encode(r)));
        reject(field(encode(r),47,"CARD"));
    }
    @Test public void failureAndUnknownCleanupCannotRetainAnyRegion(){
        var pair=new NativeUiRegions.Pair(null,reading());
        assertThrows(IllegalArgumentException.class,()->report(unknown(),unknown(),pair,Reason.TREE_FAILED,Cleanup.CONFIRMED,ready(),true));
        assertThrows(IllegalArgumentException.class,()->report(unknown(),unknown(),pair,Reason.CLEANUP_UNKNOWN,Cleanup.UNKNOWN,ready(),true));
        assertThrows(IllegalArgumentException.class,()->report(unknown(),unknown(),pair,Reason.NOT_ROOT,Cleanup.NOT_STARTED,ready(),true));
    }
    @Test public void boundedFailureMayReportAnEmptyPair(){
        for(Cleanup cleanup:Cleanup.values()){
            Report r=report(unknown(),unknown(),NativeUiRegions.Pair.empty(),Reason.TREE_FAILED,cleanup,ready(),true);
            assertEquals(r,read(encode(r)));assertFalse(read(encode(r)).qualified());
        }
    }
    @Test public void signinAndErrorBlockersMustEraseRegionsEvenWithUnknownCounters(){
        for(var blocker:new NativeUiReadyDiagnostics.Result[]{
                new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,1,0,4,0,0,0),
                new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,0,1,8,0,0,0)}){
            assertThrows(IllegalArgumentException.class,()->report(unknown(),unknown(),new NativeUiRegions.Pair(null,reading()),Reason.OK,Cleanup.CONFIRMED,blocker,true));
            Report r=report(unknown(),unknown(),NativeUiRegions.Pair.empty(),Reason.OK,Cleanup.CONFIRMED,blocker,true);
            assertEquals(r,read(encode(r)));assertNull(read(encode(r)).regions().reading());
        }
    }
    @Test public void wireCannotSmuggleRegionIntoFailureOrBlocker(){
        String s=encode(report(unknown(),unknown(),new NativeUiRegions.Pair(null,reading())));
        reject(field(s,5,"TREE_FAILED"));reject(fields(s,5,"CLEANUP_UNKNOWN","UNKNOWN"));
        reject(fields(s,30,"1","0","4"));reject(fields(s,30,"0","1","8"));
    }
    @Test public void newProtocolRetainsStrictExistingTimeAndCounterChecks(){
        String s=wire();reject(field(s,3,"109"));reject(field(s,4,"119"));reject(field(s,18,"91"));
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,111,1000,140,true,false,true,true));
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,100,1000,129,true,false,true,true));
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,100,140,140,true,false,true,true));
        assertThrows(IllegalArgumentException.class,()->parse(s,NONCE,100,10101,140,true,false,true,true));
    }
    @Test public void oldConstructorsRetainOriginalProtocolVersions(){
        Report r=good();
        Report plain=new Report(NONCE,110,120,130,Reason.OK,Cleanup.CONFIRMED,7,100,3,0,0,50,1080,2200,-1,-1,known(),unknown());
        Report ready=new Report(NONCE,110,120,130,Reason.OK,Cleanup.CONFIRMED,7,100,3,0,0,50,1080,2200,-1,-1,known(),unknown(),r.readiness());
        Report fieldConstructor=new Report(NONCE,110,120,130,Reason.OK,Cleanup.CONFIRMED,7,100,3,0,0,50,1080,2200,-1,-1,known(),unknown(),r.readiness(),null);
        assertNull(plain.regions());assertNull(ready.regions());assertNull(fieldConstructor.regions());
        assertTrue(encode(plain).startsWith("UIS1|"));assertTrue(encode(ready).startsWith("UIS2|"));
        assertEquals(ready,fieldConstructor);
    }
}
