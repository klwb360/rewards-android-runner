package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Generated UI nodes and scalar envelopes only; these tests grant no live source authority. */
public class LiveUiProtocolTest {
    static final String NONCE="0123456789abcdef0123456789abcdef";
    static NativeUiStructureProtocol.Report source(int height){
        return new NativeUiStructureProtocol.Report(NONCE,100,120,140,NativeUiStructureProtocol.Reason.OK,
                NativeUiStructureProtocol.Cleanup.CONFIRMED,39,0,7,0,0,0,1440,height,-1,-1,
                NativeUiStructureProtocol.Task.unknown(),NativeUiStructureProtocol.Task.unknown(),
                new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,0,0,0,0,0,0));
    }
    static LiveUiProtocol.Report sample(){
        var nodes=RemainingCardUiParserTest.sample();var overview=OverviewUiParser.parse(nodes);var cards=RemainingCardUiParser.parse(nodes,overview);
        var base=new OverviewUiProtocol.Report(source(2560),overview,OverviewUiProtocol.ScrollHint.DECLARED_BOTH,cards);
        return new LiveUiProtocol.Report(base,LiveUiParser.identities(nodes,base));
    }
    @Test public void roundTripHashesTheWholeRosterIncludingFourOffscreenMoreCards(){
        var r=sample();assertTrue(r.qualified());assertEquals(10,r.items().size());
        assertEquals(7,r.items().stream().filter(x->x.role()==OverviewUiRegions.Role.MORE_ACTIVITIES).count());
        assertEquals(4,r.items().stream().filter(x->x.coarse()==null).count());
        String line=LiveUiProtocol.encode(r);assertFalse(line.contains("Synthetic"));assertFalse(line.contains("积分"));
        assertTrue(line.matches("[A-Z0-9a-f_|-]+"));assertEquals(r,LiveUiProtocol.parse(line,NONCE,99,1000,160));
    }
    @Test public void oldEnvelopeAndExtraNoncanonicalFieldsCannotBecomeLiveIdentityEvidence(){
        var r=sample();String line=LiveUiProtocol.encode(r);
        for(String mutation:List.of(OverviewUiProtocol.encode(r.base()),line+"|0",line.replace("|IDENTITIES|10","|IDENTITIES|010"),line+"\n"))
            assertThrows(IllegalArgumentException.class,()->LiveUiProtocol.parse(mutation,NONCE,99,1000,160));
        assertThrows(IllegalArgumentException.class,()->LiveUiProtocol.parse(line,"ffffffffffffffffffffffffffffffff",99,1000,160));
    }
    @Test public void foreignAndUnknownAncestryNeverAcquireTaskHashes(){
        var nodes=RemainingCardUiParserTest.sample();var n=nodes.get(6);
        nodes.set(6,new NativeUiBoundPointsParser.Node(n.index(),n.parentIndex(),n.visible(),false,n.resourceId(),n.text(),n.description(),n.left(),n.top(),n.right(),n.bottom()));
        var ov=OverviewUiParser.parse(nodes);var cards=RemainingCardUiParser.parse(nodes,ov);
        assertTrue(LiveUiParser.identities(nodes,ov,cards).isEmpty());
    }
    @Test public void hashesIgnoreSpacingWidthAndPendingPlusButKeepDescriptionAndValueSeparate(){
        assertEquals(LiveCardIdentity.descriptor("  Ｔｉｔｌｅ ,  + 10 积分"),LiveCardIdentity.descriptor("Title , 10 积分"));
        assertEquals(LiveCardIdentity.hashText("title"),LiveCardIdentity.descriptor("Title, + 10 积分"));
        assertNotEquals(LiveCardIdentity.descriptor("Title, Alpha, 10 积分"),LiveCardIdentity.descriptor("Title, Beta, 10 积分"));
        assertEquals(LiveCardIdentity.descriptor("Title, Alpha, + 10 积分"),LiveCardIdentity.descriptor("Title, Alpha, 5 积分"));
        var a=new LiveRewardsTypes.Task(LiveRewardsTypes.Kind.MORE,LiveCardIdentity.descriptor("Title, Alpha, 10 积分"),1,"",10,true,false,-1);
        var b=new LiveRewardsTypes.Task(a.kind(),a.descriptorHash(),1,"",5,true,false,-1);assertFalse(a.sameKey(b));
    }
    @Test public void checkInAliasIsLimitedToNewModeAndCannotTurnTheWeeklyCardIntoCheckIn(){
        var old=OverviewUiParserTest.sample();var nodes=new ArrayList<NativeUiBoundPointsParser.Node>();
        for(var n:old)nodes.add(new NativeUiBoundPointsParser.Node(n.index(),n.parentIndex(),n.visible(),n.packageMatches(),n.resourceId(),
                n.index()==7?"签到":n.text(),n.description(),n.left(),n.top(),n.right(),n.bottom()));
        assertTrue(OverviewUiParser.parse(nodes).regions().stream().noneMatch(x->x.role()==OverviewUiRegions.Role.CHECK_IN));
        var fixed=OverviewUiParser.parse(LiveUiParser.labels(nodes));
        assertEquals(1,fixed.regions().stream().filter(x->x.role()==OverviewUiRegions.Role.CHECK_IN).count());
        assertEquals(1,fixed.regions().stream().filter(x->x.role()==OverviewUiRegions.Role.DAILY_PROGRESS).count());
        assertEquals("签到",nodes.stream().filter(x->x.index()==7).findFirst().orElseThrow().text());
    }
    @Test public void identityGeometryMustBeTheSameCurrentCardGeometry(){
        var r=sample();var items=new ArrayList<>(r.items());var a=items.get(0);
        items.set(0,new LiveUiProtocol.Item(a.role(),a.index(),a.descriptorHash(),a.points(),null,false));
        assertThrows(IllegalArgumentException.class,()->new LiveUiProtocol.Report(r.base(),items));
    }
}
