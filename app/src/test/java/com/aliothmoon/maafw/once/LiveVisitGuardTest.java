package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class LiveVisitGuardTest {
    @Test public void absenceOfRewardsIsNeverEnoughToClaimABingChild(){
        var a=new LiveVisitGuard.Accumulator();
        assertEquals(LiveVisitGuard.Hint.UNKNOWN,LiveVisitGuard.classify(List.of(),a,false));
        a.node("android.webkit.WebView","","",true);
        assertEquals(LiveVisitGuard.Hint.WEB_CONTAINER,LiveVisitGuard.classify(List.of(),a,false));
    }
    @Test public void positiveSerpRequiresBothExactAncestryAndVisibleWebView(){
        var n=List.of(RemainingCardUiParserTest.node(0,-1,true,"b_content","","",0,0,1440,2560),
                RemainingCardUiParserTest.node(1,0,true,"b_results","","",10,100,1430,2450));
        var a=new LiveVisitGuard.Accumulator();assertEquals(LiveVisitGuard.Hint.UNKNOWN,LiveVisitGuard.classify(n,a,false));
        a.node("android.webkit.WebView","","",true);assertEquals(LiveVisitGuard.Hint.SERP,LiveVisitGuard.classify(n,a,false));
        var wrong=List.of(n.get(0),RemainingCardUiParserTest.node(1,-1,true,"b_results","","",10,100,1430,2450));
        assertEquals(LiveVisitGuard.Hint.WEB_CONTAINER,LiveVisitGuard.classify(wrong,a,false));
    }
    @Test public void challengeOrModalBlocksEvenWhenTheUnderlyingRewardsTreeExists(){
        for(String label:List.of("Verify you are human","安全验证","accept all")){
            var a=new LiveVisitGuard.Accumulator();a.node("android.widget.TextView",label,"",true);
            assertEquals(LiveVisitGuard.Hint.BLOCKED,LiveVisitGuard.classify(List.of(),a,true));
            assertFalse(new LiveVisitGuard.Report(LiveUiProtocolTest.source(2560),LiveVisitGuard.Hint.BLOCKED,a.webViews,a.challenges,a.dialogs).qualified());
        }
    }
    @Test public void passiveReportIsCanonicalHashFreeScalarOnlyAndDoesNotContainReadText(){
        var a=new LiveVisitGuard.Accumulator();a.node("android.webkit.WebView","Private page content","",true);
        var report=new LiveVisitGuard.Report(LiveUiProtocolTest.source(2560),LiveVisitGuard.Hint.WEB_CONTAINER,a.webViews,a.challenges,a.dialogs);
        String wire=LiveVisitGuard.encode(report);assertFalse(wire.contains("Private"));
        assertEquals(report,LiveVisitGuard.parse(wire,LiveUiProtocolTest.NONCE,99,1000,160));
        assertThrows(IllegalArgumentException.class,()->LiveVisitGuard.parse(wire+"|0",LiveUiProtocolTest.NONCE,99,1000,160));
    }
}
