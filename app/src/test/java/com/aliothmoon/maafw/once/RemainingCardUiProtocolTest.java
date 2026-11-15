package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemainingCardUiProtocolTest {
    private static final String NONCE="0123456789abcdef0123456789abcdef";
    private static NativeUiStructureProtocol.Report source(){return new NativeUiStructureProtocol.Report(NONCE,100,120,140,NativeUiStructureProtocol.Reason.OK,
            NativeUiStructureProtocol.Cleanup.CONFIRMED,40,0,7,0,0,0,1440,2560,-1,-1,NativeUiStructureProtocol.Task.unknown(),NativeUiStructureProtocol.Task.unknown(),
            new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,0,0,0,0,0,0));}
    private static OverviewUiProtocol.Report report(){var nodes=RemainingCardUiParserTest.sample();return new OverviewUiProtocol.Report(source(),OverviewUiParser.parse(nodes),OverviewUiProtocol.ScrollHint.DECLARED_BOTH,RemainingCardUiParser.parse(nodes));}
    private static OverviewUiProtocol.Report parse(String wire){return OverviewUiProtocol.parseRemaining(wire,NONCE,99,1000,160);}
    private static void rejected(Runnable action){assertThrows(IllegalArgumentException.class,action::run);}
    @Test public void separateCanonicalVersionRoundTripsScalarsOnly(){
        var r=report();String wire=OverviewUiProtocol.encode(r);assertTrue(wire.startsWith("OVR2|UIS2|"));assertTrue(wire.matches("[A-Z0-9a-f_|-]+"));
        assertEquals(r,parse(wire));assertTrue(parse(wire).qualified());assertTrue(wire.length()<OverviewUiProtocol.MAX_LINE);
    }
    @Test public void neitherModeCanImportTheOtherMode(){
        var r=report();String wire=OverviewUiProtocol.encode(r);rejected(()->OverviewUiProtocol.parse(wire,NONCE,99,1000,160));
        String old=OverviewUiProtocol.encode(new OverviewUiProtocol.Report(r.source(),r.overview(),r.scrollHint()));
        assertTrue(old.startsWith("OVR1|"));assertNull(OverviewUiProtocol.parse(old,NONCE,99,1000,160).cards());rejected(()->parse(old));
    }
    @Test public void sourceNonceAndTimeWindowsRemainMandatory(){
        String wire=OverviewUiProtocol.encode(report());rejected(()->OverviewUiProtocol.parseRemaining(wire,"ffffffffffffffffffffffffffffffff",99,1000,160));
        rejected(()->OverviewUiProtocol.parseRemaining(wire,NONCE,99,150,160));rejected(()->OverviewUiProtocol.parseRemaining(wire,NONCE,130,1000,160));
    }
    @Test public void unknownRosterRemainsUnknownWithoutBlockingWeeklyOverview(){
        var r=report();var cards=new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED,-1,-1,List.of());
        var parsed=parse(OverviewUiProtocol.encode(new OverviewUiProtocol.Report(r.source(),r.overview(),r.scrollHint(),cards)));
        assertTrue(parsed.qualified());assertEquals(-1,parsed.cards().dailyCount());assertEquals(-1,parsed.cards().moreCount());
    }
    @Test public void foreignTextTrailingPayloadDuplicateMarkerAndWrongCountsRejected(){
        String wire=OverviewUiProtocol.encode(report());for(String bad:new String[]{wire+"|0",wire+"\n",wire+"原文",wire+"|CARDS|OBSERVED|0|0|0",wire.replace("|CARDS|OBSERVED|3|7|6","|CARDS|OBSERVED|3|7|5")})rejected(()->parse(bad));
    }
    @Test public void noncanonicalCountInvalidRoleAndOutOfBoundsRejected(){
        String wire=OverviewUiProtocol.encode(report());
        rejected(()->parse(wire.replace("|CARDS|OBSERVED|3|7|6","|CARDS|OBSERVED|03|7|6")));
        int marker=wire.indexOf("|CARDS|");String head=wire.substring(0,marker),tail=wire.substring(marker);
        rejected(()->parse(head+tail.replace("|DAILY_ACTIVITIES|1|","|CHECK_IN|1|")));
        rejected(()->parse(head+tail.replace("|1328|","|8193|")));
    }
    @Test public void protocolRejectsCardOutsideItsKnownParent(){
        var r=report();var card=new RemainingCardUi.Item(OverviewUiRegions.Role.DAILY_ACTIVITIES,1,new Box(1,1,100,100),null,null,false);
        rejected(()->new OverviewUiProtocol.Report(r.source(),r.overview(),r.scrollHint(),new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED,1,-1,List.of(card))));
    }
    @Test public void helperAcceptsOnlyExactNewModeAndKeepsOldMode(){
        assertTrue(NativeUiStructureHelper.validReadyArguments(new String[]{NONCE,"1000","OVERVIEW_CARDS_V1"}));
        assertTrue(NativeUiStructureHelper.validReadyArguments(new String[]{NONCE,"1000","OVERVIEW_V5"}));
        assertFalse(NativeUiStructureHelper.validReadyArguments(new String[]{NONCE,"1000","OVERVIEW_CARDS_V1 "}));
        assertFalse(NativeUiStructureHelper.validReadyArguments(new String[]{NONCE,"1000","OVERVIEW_CARDS_V1","extra"}));
    }
}
