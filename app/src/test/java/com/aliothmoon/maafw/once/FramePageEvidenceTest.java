package com.aliothmoon.maafw.once;
import org.junit.Test;import java.util.*;import static org.junit.Assert.*;
import io.github.klwb360.rewards.core.BingDailySetPage;
import io.github.klwb360.rewards.core.RewardsCore;
public class FramePageEvidenceTest {
 private final FrameStructureEvidence.Binding b=new FrameStructureEvidence.Binding(new Object(),new Object(),new Object(),new Object());
 private List<FrameStructureEvidence.Line> rows(String...labels){List<FrameStructureEvidence.Line> out=new ArrayList<>();for(int i=0;i<labels.length;i++)out.add(new FrameStructureEvidence.Line(labels[i],1,1+i*25,250,20,0.95));return out;}
 private FramePageEvidence.Result parse(String...labels){return FramePageEvidence.parse(b,b,rows(labels),400,800);}
 @Test public void sameFrameTextIsNotBingIdentity(){var r=parse("Bing","Daily set","Alpha, learn more, earn 10 points");assertEquals(FramePageEvidence.Brand.SAME_FRAME_BING_TEXT_CANDIDATE,r.brand());assertEquals(FramePageEvidence.Scope.DAILY_SET_TEXT_IN_FRAME_UNBOUND,r.scope());assertEquals(1,r.offers().size());assertEquals(FramePageEvidence.Completeness.TEXT_ONLY_INCOMPLETE,r.completeness());assertNotEquals(RewardsCore.ParseStatus.OK,r.strictCards());}
 @Test public void explicitEarnedTextOnly(){var r=parse("Alpha, 10 points earned");assertEquals(BingDailySetPage.TextStatus.EXPLICIT_POINTS_EARNED_TEXT,r.offers().get(0).status());assertEquals(5,r.offers().get(0).titleCodePoints());assertEquals(10,r.offers().get(0).points());assertFalse(FramePageEvidence.summary(r).contains("Alpha"));}
 @Test public void tenPointsAndNearbyRowsCannotInventCard(){assertTrue(parse("Alpha","10 points","Completed").offers().isEmpty());assertTrue(parse("Alpha, learn more, earn 20 points").offers().isEmpty());}
 @Test public void noCrossFrameBinding(){var other=new FrameStructureEvidence.Binding(b.source(),b.owner(),b.epoch(),new Object());assertEquals(FramePageEvidence.Completeness.REJECTED,FramePageEvidence.parse(b,other,rows("Bing"),400,800).completeness());}
 @Test public void duplicateOffersAndSignInReject(){assertEquals(FramePageEvidence.Completeness.REJECTED,parse("A, 10 points earned","A, 10 points earned").completeness());assertEquals(FramePageEvidence.Completeness.REJECTED,parse("Sign in","A, 10 points earned").completeness());}
 @Test public void ambiguousHeadingsCannotScope(){assertEquals(FramePageEvidence.Scope.UNKNOWN,parse("Daily set","Daily set","A, 10 points earned").scope());}
 @Test public void boundsAndConfidenceReject(){assertEquals(FramePageEvidence.Completeness.REJECTED,FramePageEvidence.parse(b,b,List.of(new FrameStructureEvidence.Line("Bing",0,0,20,20,0.79)),400,800).completeness());assertEquals(FramePageEvidence.Completeness.REJECTED,FramePageEvidence.parse(b,b,rows("Bing"),4096,4096).completeness());}
 @Test public void threeRowsStillNotThreeLogicalCards(){var r=parse("Daily activities","Daily set","A, 10 points earned","B, description, earn 10 points","C, description, earn 10 points");assertEquals(3,r.offers().size());assertNotEquals(RewardsCore.ParseStatus.OK,r.strictCards());}
 @Test public void rawControlAndAmbiguousTitleRejected(){assertTrue(BingDailySetPage.observeOfferText("A, B, C, earn 10 points").isEmpty());assertTrue(BingDailySetPage.observeOfferText("A\n, 10 points earned").isEmpty());}
}
