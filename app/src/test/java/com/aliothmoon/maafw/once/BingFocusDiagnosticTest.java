package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class BingFocusDiagnosticTest {
    private static final String TEMPLATE="com.microsoft.bing/com.microsoft.sapphire.runtime.templates.TemplateActivity";
    private static final String BROWSER="com.microsoft.bing/com.microsoft.sapphire.app.browser.BrowserActivity";
    private static String document(){return document("aabbccdd","11223344",TEMPLATE,TEMPLATE,"77");}
    private static String document(String window,String activity,String focused,String app,String task){
        return "mTopFocusedDisplayId=0\n  mCurrentFocus=Window{"+window+" u0 "+focused+"}\n"
                +"  mFocusedApp=ActivityRecord{"+activity+" u0 "+app+" t"+task+"}\n";
    }
    private static Map<String,Object> inspect(String text){return BingFocusDiagnostic.inspect(text).scalars();}
    private static String without(String source,String key){return source.replaceAll("(?m)^.*"+key+"[^\\r\\n]*\\r?\\n?","");}
    private static List<Map<?,?>> samples(BingFocusDiagnostic.History history){
        return ((List<?>)history.scalars().get("samples")).stream().<Map<?,?>>map(sample->(Map<?,?>)sample).toList();
    }
    private static void ready(BingFocusDiagnostic.History history,int pair,String text){
        history.record("LIVE_TEMPLATE",pair,10,BingFocusDiagnostic.inspect(text),true,"READY","CURRENT_TEMPLATE_BOUND",true,true,true);
    }
    private static void rejected(BingFocusDiagnostic.History history,int pair,String text){
        history.record("LIVE_TEMPLATE",pair,10,BingFocusDiagnostic.inspect(text),true,"UNKNOWN","FOCUS_FORMAT",false,false,true);
    }
    @Test public void acceptedFocusPreservesStrictAcceptanceWithoutExposingIdentity(){
        var diagnostic=BingFocusDiagnostic.inspect(document());var values=diagnostic.scalars();
        assertNotNull(BingForegroundSnapshot.parse(document()));assertEquals(true,values.get("strictAccepted"));
        assertEquals("PRESENT",values.get("windowState"));assertEquals("PRESENT",values.get("appState"));
        assertEquals("TRUE",values.get("componentRelation"));assertEquals("ACCEPTED",values.get("failureClass"));
        assertEquals("TEMPLATE",values.get("windowRole"));
        assertFalse(diagnostic.json().contains("aabbccdd"));assertFalse(diagnostic.json().contains("11223344"));
        assertFalse(diagnostic.json().contains("com.microsoft"));
        assertThrows(UnsupportedOperationException.class,()->values.put("raw",document()));
    }
    @Test public void eachMissingFieldHasItsOwnCategory(){
        assertEquals("DISPLAY_MISSING",inspect(without(document(),"mTopFocusedDisplayId=")).get("failureClass"));
        assertEquals("WINDOW_MISSING",inspect(without(document(),"mCurrentFocus=")).get("failureClass"));
        assertEquals("APP_MISSING",inspect(without(document(),"mFocusedApp=")).get("failureClass"));
    }
    @Test public void repeatedSameIdentityAndExtraNullCannotDisappearThroughStrictRegex(){
        String current="  mCurrentFocus=Window{aabbccdd u0 "+TEMPLATE+"}\n";
        var repeated=inspect(document()+current);assertEquals(2,repeated.get("windowTokenCount"));
        assertEquals(2,repeated.get("windowStrictCount"));assertEquals("WINDOW_DUPLICATE",repeated.get("failureClass"));
        assertEquals(false,repeated.get("strictAccepted"));
        var nil=inspect(document()+"  mCurrentFocus=null\n");
        assertEquals(1,nil.get("windowStrictCount"));assertEquals(1,nil.get("windowNullCount"));
        assertEquals("WINDOW_DUPLICATE",nil.get("failureClass"));assertEquals(false,nil.get("strictAccepted"));
    }
    @Test public void nullWindowAndAppAreDifferentFromMissingOrUnrecognized(){
        String nilWindow=document().replaceAll("mCurrentFocus=Window\\{[^}]+\\}","mCurrentFocus=null");
        String nilApp=document().replaceAll("mFocusedApp=ActivityRecord\\{[^}]+\\}","mFocusedApp=null");
        assertEquals("WINDOW_NULL_VALUE",inspect(nilWindow).get("failureClass"));
        assertEquals("APP_NULL_VALUE",inspect(nilApp).get("failureClass"));
        assertEquals(0,inspect(nilWindow).get("windowUnrecognizedCount"));
    }
    @Test public void displayWrongDuplicateAndMalformedAreNotCoalesced(){
        assertEquals("DISPLAY_WRONG_DISPLAY",inspect(document().replace("DisplayId=0","DisplayId=2")).get("failureClass"));
        assertEquals("DISPLAY_DUPLICATE",inspect(document()+"mTopFocusedDisplayId=0\n").get("failureClass"));
        assertEquals("DISPLAY_UNRECOGNIZED",inspect(document().replace("DisplayId=0","DisplayId=unknown")).get("failureClass"));
        assertEquals("DISPLAY_NULL_VALUE",inspect(document().replace("DisplayId=0","DisplayId=null")).get("failureClass"));
        assertEquals(false,inspect(document().replace("DisplayId=0","DisplayId=00")).get("strictAccepted"));
    }
    @Test public void nonBingWrongUserAndComponentConflictRemainStrictlyRejected(){
        String other="org.example.sensitive/org.example.sensitive.AccountActivity";
        var nonBing=inspect(document("aabbccdd","11223344",other,other,"77"));
        assertEquals("WINDOW_NON_BING",nonBing.get("failureClass"));assertEquals(1,nonBing.get("windowNonBingCount"));
        assertEquals("NON_BING",nonBing.get("windowRole"));assertEquals(false,nonBing.get("strictAccepted"));
        String user=document().replace(" u0 "," u10 ");
        assertEquals("WINDOW_WRONG_USER",inspect(user).get("failureClass"));assertEquals(false,inspect(user).get("strictAccepted"));
        var conflict=inspect(document("aabbccdd","11223344",TEMPLATE,BROWSER,"77"));
        assertEquals("COMPONENT_MISMATCH",conflict.get("failureClass"));assertEquals("FALSE",conflict.get("componentRelation"));
        assertEquals(false,conflict.get("strictAccepted"));
        assertFalse(BingFocusDiagnostic.inspect(document("aabbccdd","11223344",other,other,"77")).json().contains("sensitive"));
    }
    @Test public void malformedAndInlineRecordsAreCountedWithoutNormalizingThem(){
        String malformed=document().replace("Window{aabbccdd","Window{not_hex");
        assertEquals("WINDOW_UNRECOGNIZED",inspect(malformed).get("failureClass"));
        assertEquals(1,inspect(malformed).get("windowUnrecognizedCount"));
        String inline=document().replace("  mCurrentFocus=","  prefix mCurrentFocus=");
        assertEquals(1,inspect(inline).get("windowTokenCount"));assertEquals(0,inspect(inline).get("windowLineCount"));
        assertEquals("WINDOW_UNRECOGNIZED",inspect(inline).get("failureClass"));assertNull(BingForegroundSnapshot.parse(inline));
    }
    @Test public void diagnosticDisplayCountsDoNotInventARejectionTheStrictParserDoesNotMake(){
        String extra=document()+"  inline mTopFocusedDisplayId=unknown\n";
        assertNotNull(BingForegroundSnapshot.parse(extra));assertEquals(2,inspect(extra).get("displayTokenCount"));
        assertEquals(1,inspect(extra).get("displayStrictCount"));assertEquals("PRESENT",inspect(extra).get("displayState"));
        assertEquals("ACCEPTED",inspect(extra).get("failureClass"));
    }
    @Test public void documentBoundsAndNullAreClassifiedWithoutInspectingRejectedPayload(){
        assertEquals("DOCUMENT_NOT_OBSERVED",inspect(null).get("failureClass"));assertEquals(-1,inspect(null).get("documentChars"));
        assertEquals("DOCUMENT_NUL_PRESENT",inspect(document()+"\0").get("failureClass"));
        String large="x".repeat(BingFocusDiagnostic.MAX_DOCUMENT_CHARS+10);
        assertEquals("DOCUMENT_TOO_LARGE",inspect(large).get("failureClass"));assertEquals(262145,inspect(large).get("documentChars"));
        assertEquals(0,inspect(large).get("windowTokenCount"));
    }
    @Test public void supplementalClassificationNeverChangesTheExistingParserDecision(){
        for(String text:new String[]{document(),document().replace("\n","\r\n"),document().replace(" u0 "," u1 "),
                document().replace(TEMPLATE,BROWSER),document()+"mFocusedApp=null\n",document().replace("DisplayId=0","DisplayId=1"),
                document().replace("aabbccdd","not_hex"),without(document(),"mFocusedApp="),null}){
            assertEquals(BingForegroundSnapshot.parse(text)!=null,inspect(text).get("strictAccepted"));
        }
    }
    @Test public void fixedProjectionHasOnlyScalarValuesAndThePublishedKeys(){
        var values=inspect(document());
        assertEquals(32,values.size());
        assertEquals(Set.of("schemaVersion","documentState","documentChars","displayTokenCount","displayLineCount","displayStrictCount",
                "displayNullCount","displayUnrecognizedCount","displayState","windowTokenCount","windowLineCount","windowStrictCount",
                "windowNullCount","windowGenericCount","windowWrongUserCount","windowNonBingCount","windowUnrecognizedCount","windowState","windowRole",
                "appTokenCount","appLineCount","appStrictCount","appNullCount","appGenericCount","appWrongUserCount","appNonBingCount",
                "appUnrecognizedCount","appState","appRole","componentRelation","strictAccepted","failureClass"),values.keySet());
        for(var value:values.values())assertTrue(value instanceof Integer||value instanceof Boolean||value instanceof String);
    }
    @Test public void historyComparesOnlyOpaquePreviouslyAcceptedMetadata(){
        var history=BingFocusDiagnostic.history();ready(history,1,document());ready(history,2,document());
        rejected(history,3,document().replace("mCurrentFocus=Window{aabbccdd u0 "+TEMPLATE+"}","mCurrentFocus=null"));
        String changed=document("deadbeef","11223344",TEMPLATE,TEMPLATE,"77");rejected(history,4,changed);ready(history,5,document());
        var records=samples(history);assertEquals("NOT_OBSERVED",records.get(0).get("samePreviousAccepted"));
        assertEquals("TRUE",records.get(1).get("samePreviousAccepted"));assertEquals("NOT_OBSERVED",records.get(2).get("samePreviousAccepted"));
        assertEquals("FALSE",records.get(3).get("samePreviousAccepted"));assertEquals("TRUE",records.get(4).get("samePreviousAccepted"));
        assertFalse(history.json().contains("deadbeef"));assertFalse(history.json().contains("com.microsoft"));
    }
    @Test public void parserReadyDoesNotBecomeAReaderReturnAfterAuthorityRejection(){
        var history=BingFocusDiagnostic.history();
        history.record("LIVE_TEMPLATE",1,10,BingFocusDiagnostic.inspect(document()),true,"READY","CURRENT_TEMPLATE_BOUND",true,false,true);
        ready(history,2,document());var record=samples(history).get(0);
        assertEquals(true,record.get("parserSourceReady"));assertEquals(false,record.get("returnedSourceReady"));
        assertEquals(false,samples(history).get(1).get("previousAcceptedObserved"));
    }
    @Test public void historyRetainsFirstFailureAndTheLatestSamplesWithinTheHardLimit(){
        var history=BingFocusDiagnostic.history();ready(history,1,document());rejected(history,2,without(document(),"mCurrentFocus="));
        for(int pair=3;pair<=200;pair++)ready(history,pair,document());
        var snapshot=history.scalars();var records=samples(history);
        assertEquals(64,records.size());assertEquals(200L,snapshot.get("totalSamples"));assertEquals(136L,snapshot.get("droppedSamples"));
        assertEquals(true,snapshot.get("firstRejectedRetained"));assertEquals(2,records.get(0).get("pair"));
        assertEquals(138,records.get(1).get("pair"));assertEquals(200,records.get(63).get("pair"));
        assertThrows(UnsupportedOperationException.class,()->((List<?>)snapshot.get("samples")).clear());
    }
    @Test public void independentDiagnosticSamplesCannotReplaceThePreviousAcceptedSource(){
        var history=BingFocusDiagnostic.history();ready(history,1,document());
        String changed=document("deadbeef","11223344",TEMPLATE,TEMPLATE,"77");
        history.record("AFTER_REJECTION_DIAGNOSTIC","DIAGNOSTIC",2,10,BingFocusDiagnostic.inspect(changed),true,"READY","READY",true,false,true);
        ready(history,3,document());assertEquals("FALSE",samples(history).get(1).get("samePreviousAccepted"));
        assertEquals("TRUE",samples(history).get(2).get("samePreviousAccepted"));
        assertEquals(false,history.scalars().get("firstRejectedRetained"));
    }
    @Test public void invalidDiagnosticProjectionFailsClosedWithoutThrowingIntoItsOwner(){
        var history=BingFocusDiagnostic.history();
        history.record("LIVE_TEMPLATE",1,-2,BingFocusDiagnostic.inspect(document()),true,"READY","READY",true,true,true);
        assertNull(history.scalars());assertNull(history.json());
        ready(history,2,document());assertNull(history.json());
    }
    @Test public void followUpIsTriggeredOnlyByAnObservedOriginalFocusParserRejection(){
        var history=BingFocusDiagnostic.history();var absent=BingFocusDiagnostic.inspect(without(document(),"mCurrentFocus="));
        history.record("LIVE_TEMPLATE",1,10,absent,false,"NOT_OBSERVED","NOT_OBSERVED",false,false,true);
        assertFalse(history.hasFocusRejection());
        history.record("AFTER_REJECTION_DIAGNOSTIC","DIAGNOSTIC",2,10,absent,true,"UNKNOWN","FOCUS_FORMAT",false,false,true);
        assertFalse(history.hasFocusRejection());
        history.record("LIVE_TEMPLATE",3,10,absent,true,"UNKNOWN","WINDOW_HEADERS",false,false,true);
        assertFalse(history.hasFocusRejection());
        history.record("LIVE_TEMPLATE",4,10,BingFocusDiagnostic.inspect(document()),true,"UNKNOWN","FOCUS_FORMAT",false,false,true);
        assertFalse(history.hasFocusRejection());
        history.record("LIVE_TEMPLATE",5,10,absent,true,"UNKNOWN","FOCUS_FORMAT",false,false,true);
        assertTrue(history.hasFocusRejection());
    }
    @Test public void theOwnersMonotonicTimestampIsKeptWithoutInventingAnAndroidClock(){
        var history=BingFocusDiagnostic.history();
        history.record("AFTER_REJECTION_DIAGNOSTIC","DIAGNOSTIC",1,4250,250,BingFocusDiagnostic.inspect(document()),
                true,"READY","CURRENT_TEMPLATE_BOUND",true,false,true,"READ_COMPLETE","READ_COMPLETE");
        var sample=samples(history).get(0);assertEquals(17,sample.size());assertEquals(4250L,sample.get("observedElapsed"));
        assertEquals(250L,sample.get("elapsedMs"));assertEquals(4000L,(long)sample.get("observedElapsed")-(long)sample.get("elapsedMs"));
        var unknown=BingFocusDiagnostic.history();ready(unknown,1,document());
        assertEquals(-1L,samples(unknown).get(0).get("observedElapsed"));
    }
}
