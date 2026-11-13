package com.aliothmoon.maafw.once;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionGate.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** All rasters in this class are synthetic. Actual-image Java runs are separately recorded. */
public class RewardRegionLocatorTest {
    static final int W=523,H=688;
    Key key(){return new Key(Source.SYNTHETIC_FIXTURE,"synthetic-crop",1,"activity-region");}
    byte[] pixels(){byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);fill(p,25,149,509,340,255);fill(p,388,216,490,273,80);return p;}
    void fill(byte[] p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
    Geometry geometry(byte[] p){return RewardRegionLocator.observeGray8(key(),p,W,H,()->false);}
    Assessment gate(String value){return RewardRegionGate.assess(key(),List.of(
        new Reading(key(),value,.95,true,true,CounterRole.AGGREGATE_PROGRESS,TextBasis.CONTEXTUAL_OCR)));}
    Section section(){return new Section(key(),"每日活动",new Box(25,110,135,135),.95,Origin.SYNTHETIC);}
    List<Row> rows(){return List.of(new Row("Upcoming comedy",new Box(55,170,320,195),.95,Origin.SYNTHETIC),
        new Row("+10",new Box(414,231,465,257),.95,Origin.SYNTHETIC));}
    Result associate(Geometry g,Assessment a,Section section,List<Row> supplied){
        List<Row> full=supplied==null?null:new ArrayList<>(supplied);
        if(full!=null&&section!=null&&a!=null&&a.fraction()!=null){
            full.add(new Row(section.title(),section.titleBox(),.95,Origin.SYNTHETIC));
            full.add(new Row(a.fraction().numerator()+"/"+a.fraction().denominator(),
                new Box(388,110,480,135),.95,Origin.SYNTHETIC));
        }
        return RewardRegionLocator.associate(g,a,section==null?null:List.of(section),full);
    }
    Result result(){return associate(geometry(pixels()),gate("0/30"),section(),rows());}
    @Test public void actualAlgorithmDetectsSyntheticCardAndBadgeWithoutInputBoxes(){
        Geometry g=geometry(pixels());assertEquals(Status.OBSERVED,g.status());assertEquals(1,g.cards().size());
        assertEquals(new Box(25,149,509,340),g.cards().get(0).box());assertTrue(g.cards().get(0).fullyVisible());
        assertEquals(new Box(388,216,490,273),g.cards().get(0).badges().get(0).box());
        assertEquals(BadgeTone.DARK,g.cards().get(0).badges().get(0).tone());
    }
    @Test public void paleSurfaceIsNotDisabledOrClickableClaim(){
        byte[] p=pixels();fill(p,388,216,490,273,246);
        Result r=associate(geometry(p),gate("0/30"),section(),rows());
        assertEquals(Status.CANDIDATES,r.status());assertEquals(BadgeTone.PALE,r.candidates().get(0).tone());
        assertFalse(r.candidates().get(0).verifiedClickable());
    }
    @Test public void candidateCenterAndCoordinateSpaceRemainCropLocal(){
        Candidate c=result().candidates().get(0);assertEquals(439,c.cropX(),0);assertEquals(244.5,c.cropY(),0);
        assertEquals("CROP_LOCAL_ONLY",c.coordinateSpace());assertEquals(10,c.displayedPoints());
        assertArrayEquals(new double[]{25.0/W,149.0/H,509.0/W,340.0/H},c.cardBox().normalized(W,H),1e-12);
    }
    @Test public void completeAndUnknownGateReturnBeforeTargetOcr(){
        Geometry g=geometry(pixels());
        assertEquals(Status.SKIP_COMPLETE,associate(g,gate("30/30"),null,null).status());
        assertEquals(Status.UNKNOWN_PROGRESS,associate(g,gate("0/0"),null,null).status());
    }
    @Test public void wrongOrMissingSectionStops(){
        Section wrong=new Section(key(),"unknown navigation",section().titleBox(),.99,Origin.SYNTHETIC);
        assertEquals(Status.BAD_SECTION,associate(geometry(pixels()),gate("0/30"),wrong,rows()).status());
    }
    @Test public void noWhiteCardMeansNoBoundaryFallbackToNumericText(){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
        assertEquals(Status.NO_CARDS,geometry(p).status());
    }
    @Test public void croppedCardIsNeverComplete(){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);fill(p,0,149,484,340,255);fill(p,363,216,465,273,80);
        Geometry g=geometry(p);assertFalse(g.cards().get(0).fullyVisible());
        assertEquals(Status.INCOMPLETE_CARD,associate(g,gate("0/30"),section(),rows()).status());
    }
    @Test public void twoBadgesInOneCardStop(){
        byte[] p=pixels();fill(p,388,216,490,273,255);fill(p,388,170,490,215,80);fill(p,388,270,490,315,80);
        assertEquals(Status.AMBIGUOUS_BADGE,associate(geometry(p),gate("0/30"),section(),rows()).status());
    }
    @Test public void twoPointReadingsInOneCardStop(){
        List<Row> r=List.of(rows().get(0),new Row("+5",new Box(400,231,420,251),.95,Origin.SYNTHETIC),
            new Row("+5",new Box(440,231,460,251),.95,Origin.SYNTHETIC));
        assertEquals(Status.AMBIGUOUS_TOKEN,associate(geometry(pixels()),gate("0/30"),section(),r).status());
    }
    @Test public void plainBalanceNumberCannotSubstitutePlusBadge(){
        List<Row> r=List.of(rows().get(0),new Row("100",rows().get(1).box(),.95,Origin.SYNTHETIC));
        assertEquals(Status.AMBIGUOUS_TOKEN,associate(geometry(pixels()),gate("0/30"),section(),r).status());
    }
    @Test public void fullwidthPlusAndWhitespaceAreSupportedLiterally(){
        List<Row> r=List.of(rows().get(0),new Row("＋ １０",rows().get(1).box(),.95,Origin.SYNTHETIC));
        assertEquals(Status.CANDIDATES,associate(geometry(pixels()),gate("0/30"),section(),r).status());
    }
    @Test public void lowConfidenceDoesNotDisappearToCreateUniqueness(){
        List<Row> r=new ArrayList<>(rows());r.add(new Row("+5",new Box(50,280,90,305),.2,Origin.SYNTHETIC));
        assertEquals(Status.BAD_OCR,associate(geometry(pixels()),gate("0/30"),section(),r).status());
    }
    @Test public void ocrOverlapStops(){
        List<Row> r=new ArrayList<>(rows());r.add(new Row("+10",rows().get(1).box(),.95,Origin.SYNTHETIC));
        assertEquals(Status.BAD_OCR,associate(geometry(pixels()),gate("0/30"),section(),r).status());
    }
    @Test public void completedMarkerCannotBeAnActiveCandidate(){
        List<Row> r=List.of(new Row("Already completed",rows().get(0).box(),.95,Origin.SYNTHETIC),rows().get(1));
        assertEquals(Status.COMPLETION_MARKER,associate(geometry(pixels()),gate("0/30"),section(),r).status());
    }
    @Test public void noTitleAndTokenTouchingBadgeEdgeStop(){
        assertEquals(Status.MISSING_TITLE,associate(geometry(pixels()),gate("0/30"),section(),
            List.of(rows().get(1))).status());
        List<Row> r=List.of(rows().get(0),new Row("+10",new Box(388,231,465,257),.95,Origin.SYNTHETIC));
        assertEquals(Status.AMBIGUOUS_TOKEN,associate(geometry(pixels()),gate("0/30"),section(),r).status());
    }
    @Test public void distortedCardAspectStopsEvenWithObservedWhiteSurface(){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);fill(p,25,149,509,240,255);fill(p,388,170,490,220,80);
        assertEquals(Status.INCOMPLETE_CARD,associate(geometry(p),gate("0/30"),section(),rows()).status());
    }
    @Test public void invalidStrideDimensionsAndOverflowsStop(){
        assertEquals(Status.INVALID_INPUT,RewardRegionLocator.observeGray8(key(),new byte[10],W,H,()->false).status());
        assertEquals(Status.INVALID_INPUT,RewardRegionLocator.observeGray8(key(),new byte[10],Integer.MAX_VALUE,H,()->false).status());
        List<Row> r=List.of(rows().get(0),new Row("+10",new Box(Integer.MAX_VALUE,231,-2,257),.95,Origin.SYNTHETIC));
        assertEquals(Status.BAD_OCR,associate(geometry(pixels()),gate("0/30"),section(),r).status());
    }
    @Test public void cancelDiscardsAllPartialGeometry(){
        AtomicInteger polls=new AtomicInteger();
        Geometry g=RewardRegionLocator.observeGray8(key(),pixels(),W,H,()->polls.incrementAndGet()>8);
        assertEquals(Status.CANCELED,g.status());assertTrue(g.cards().isEmpty());
        assertEquals(Status.CANCELED,associate(g,gate("30/30"),section(),rows()).status());
    }
    @Test public void fakeTargetNeedsExplicitSelectionAndNeverExecutes(){
        Result r=result();RewardRegionLocator.Session s=new RewardRegionLocator.Session(r);
        assertEquals("EXPLICIT_UNIQUE_SELECTION_REQUIRED",s.previewExplicitly(r,null).status());
        RewardRegionLocator.Preview p=s.previewExplicitly(r,"visual-card-0");
        assertEquals("HYPOTHETICAL_CROP_TARGET_ONLY",p.status());assertEquals(0,p.executedActions());
        assertEquals("UNBOUND",p.actor());assertFalse(p.claimed());assertFalse(p.credited());
        assertTrue(p.requiresIndependentNewCounter());
        assertEquals("ALREADY_PREVIEWED",s.previewExplicitly(r,"visual-card-0").status());
    }
    @Test public void cancelAndStaleSelectionAreTerminal(){
        Result r=result();RewardRegionLocator.Session s=new RewardRegionLocator.Session(r);s.cancel();
        assertEquals("CANCELED",s.previewExplicitly(r,"visual-card-0").status());
        s=new RewardRegionLocator.Session(r);
        assertEquals("STALE",s.previewExplicitly(result(),"visual-card-0").status());
        assertEquals("BLOCKED",s.previewExplicitly(r,"visual-card-0").status());
    }
    @Test public void independentCardsNeedSeparateExplicitSelection(){
        byte[] p=pixels();fill(p,25,357,509,519,255);fill(p,388,410,490,466,80);
        List<Row> r=new ArrayList<>(rows());r.add(new Row("Another activity",new Box(55,380,320,400),.95,Origin.SYNTHETIC));
        r.add(new Row("+10",new Box(414,421,465,451),.95,Origin.SYNTHETIC));
        Result two=associate(geometry(p),gate("0/30"),section(),r);
        assertEquals(Status.CANDIDATES,two.status());assertEquals(2,two.candidates().size());
        RewardRegionLocator.Session s=new RewardRegionLocator.Session(two);
        assertEquals("EXPLICIT_UNIQUE_SELECTION_REQUIRED",s.previewExplicitly(two,null).status());
        assertEquals("visual-card-1",s.previewExplicitly(two,"visual-card-1").hypotheticalTarget().visualCardId());
    }
    @Test public void manualAnnotationOriginIsNotReportedAsOcr(){
        List<Row> r=List.of(rows().get(0),new Row("+10",rows().get(1).box(),.95,Origin.MANUAL_ANNOTATION));
        Result out=associate(geometry(pixels()),gate("0/30"),section(),r);
        assertEquals(Origin.MANUAL_ANNOTATION,out.candidates().get(0).ocrOrigin());
    }

    @Test public void sameNamedDailyStreakIsNotWhiteActivityCardLayout(){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
        // Small synthetic day discs and future-day labels do not form full-width white cards.
        for(int i=0;i<7;i++)fill(p,25+i*70,165,65+i*70,205,255);
        Geometry streak=geometry(p);
        assertEquals(Status.NO_CARDS,streak.status());
        assertEquals(Status.NO_CARDS,associate(streak,gate("0/30"),section(),rows()).status());
        assertEquals(Status.UNKNOWN_PROGRESS,associate(streak,gate("5 小时 32 分"),section(),rows()).status());
    }

    @Test public void multipleSectionsWithSameKeyCannotReuseFirstContext(){
        Section second=new Section(key(),"更多活动",new Box(25,345,135,355),.95,Origin.SYNTHETIC);
        assertEquals(Status.BAD_SECTION,RewardRegionLocator.associate(geometry(pixels()),gate("0/30"),
            List.of(section(),second),rows()).status());
    }
    @Test public void secondHeadingInOcrStopsEvenIfCallerSuppliesOneSection(){
        List<Row> extra=new ArrayList<>(rows());
        extra.add(new Row("更多活动",new Box(25,345,135,355),.95,Origin.SYNTHETIC));
        assertEquals(Status.BAD_SECTION,associate(geometry(pixels()),gate("0/30"),section(),extra).status());
    }
    @Test public void extraCounterOrMissingObservedContextStops(){
        List<Row> extra=new ArrayList<>(rows());
        extra.add(new Row("0/20",new Box(388,345,480,355),.95,Origin.SYNTHETIC));
        assertEquals(Status.UNKNOWN_PROGRESS,associate(geometry(pixels()),gate("0/30"),section(),extra).status());
        assertEquals(Status.BAD_SECTION,RewardRegionLocator.associate(geometry(pixels()),gate("0/30"),
            List.of(section()),rows()).status());
    }
    @Test public void parentCounterAboveDifferentHeadingIsNotChildCounter(){
        List<Row> supplied=new ArrayList<>(rows());
        supplied.add(new Row(section().title(),section().titleBox(),.95,Origin.SYNTHETIC));
        supplied.add(new Row("0/30",new Box(388,20,480,45),.95,Origin.SYNTHETIC));
        assertEquals(Status.UNKNOWN_PROGRESS,RewardRegionLocator.associate(geometry(pixels()),gate("0/30"),
            List.of(section()),supplied).status());
    }
    @Test public void fourMiPixelBudgetIncludesCurrentDeviceSizeButDoesNotValidateLayout(){
        byte[] p=new byte[1440*2560];Arrays.fill(p,(byte)247);
        assertEquals(Status.NO_CARDS,RewardRegionLocator.observeGray8(key(),p,1440,2560,()->false).status());
        assertEquals(Status.INVALID_INPUT,RewardRegionLocator.observeGray8(key(),new byte[4096*1025],4096,1025,()->false).status());
    }
    @Test public void inputPixelsUnchangedOnNormalCancelAndExceptionPaths(){
        byte[] p=pixels(),original=p.clone();geometry(p);assertArrayEquals(original,p);
        RewardRegionLocator.observeGray8(key(),p,W,H,()->true);assertArrayEquals(original,p);
        AtomicInteger polls=new AtomicInteger();
        try{
            RewardRegionLocator.observeGray8(key(),p,W,H,()->{if(polls.incrementAndGet()>3)throw new IllegalStateException("synthetic");return false;});
            fail("exception expected");
        }catch(IllegalStateException expected){assertArrayEquals(original,p);}
    }

    @Test public void tinySeparateUnitNearBadgeNumberCanBeIgnored(){
        List<Row> r=new ArrayList<>(rows());r.add(new Row("pts",new Box(466,240,485,252),.95,Origin.SYNTHETIC));
        Result out=associate(geometry(pixels()),gate("0/30"),section(),r);
        assertEquals(Status.CANDIDATES,out.status());assertEquals(1,out.ignoredSmallTextBoxes());
        assertEquals(10,out.candidates().get(0).displayedPoints());
    }
    @Test public void necessaryStateOrTimeTextNearNumberIsNotIgnored(){
        for(String text:List.of("天","分钟","明天","锁定","登录","tomorrow","locked")){
            List<Row> r=new ArrayList<>(rows());r.add(new Row(text,new Box(466,240,485,252),.95,Origin.SYNTHETIC));
            assertEquals(Status.UNRESOLVED_STATE,associate(geometry(pixels()),gate("0/30"),section(),r).status());
        }
    }
    @Test public void nearbyCompleteMarkerStillStops(){
        List<Row> r=new ArrayList<>(rows());r.add(new Row("已完成",new Box(466,240,485,252),.95,Origin.SYNTHETIC));
        assertEquals(Status.COMPLETION_MARKER,associate(geometry(pixels()),gate("0/30"),section(),r).status());
    }
    @Test public void mixedNumericTextBoxCannotBeStrippedToDigits(){
        List<Row> r=List.of(rows().get(0),new Row("+10pts",rows().get(1).box(),.95,Origin.SYNTHETIC));
        assertEquals(Status.AMBIGUOUS_TOKEN,associate(geometry(pixels()),gate("0/30"),section(),r).status());
    }
    @Test public void overlappingOrUncertainUnitCannotBeDiscarded(){
        List<Row> overlap=new ArrayList<>(rows());overlap.add(new Row("pts",new Box(450,240,480,252),.95,Origin.SYNTHETIC));
        assertEquals(Status.BAD_OCR,associate(geometry(pixels()),gate("0/30"),section(),overlap).status());
        List<Row> low=new ArrayList<>(rows());low.add(new Row("pts",new Box(466,240,485,252),.2,Origin.SYNTHETIC));
        assertEquals(Status.BAD_OCR,associate(geometry(pixels()),gate("0/30"),section(),low).status());
    }
    @Test public void titleOrLargeExternalLabelIsNotCountedAsDiscardedUnit(){
        List<Row> r=new ArrayList<>(rows());r.add(new Row("points",new Box(60,280,130,305),.95,Origin.SYNTHETIC));
        Result out=associate(geometry(pixels()),gate("0/30"),section(),r);
        assertEquals(Status.CANDIDATES,out.status());assertEquals(0,out.ignoredSmallTextBoxes());
    }

    byte[] tickPixels(boolean withGlyph,boolean mirrored){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
        fill(p,25,149,509,313,255);fill(p,388,203,490,260,80);
        fill(p,25,329,509,460,255);fill(p,388,366,490,423,80);
        fill(p,25,476,509,547,255);
        for(int y=499;y<524;y++)for(int x=465;x<490;x++)
            if(Math.pow(x+0.5-477.5,2)+Math.pow(y+0.5-511.5,2)<=156.25)p[y*W+x]=(byte)110;
        String[] mask={"00000000011","00000000111","00000001110","10000011100",
            "11000111000","11101110000","01111100000","00111000000"};
        if(withGlyph)for(int y=0;y<8;y++)for(int x=0;x<11;x++)
            if(mask[y].charAt(x)=='1')p[(507+y)*W+472+(mirrored?10-x:x)]=(byte)255;
        return p;
    }
    Row completedNumber(double confidence){return new Row("10",new Box(433,502,457,519),confidence,Origin.SYNTHETIC);}
    List<Row> tickRows(){
        return List.of(new Row("First activity",new Box(55,170,320,195),.95,Origin.SYNTHETIC),
            new Row("+10",new Box(414,218,465,245),.95,Origin.SYNTHETIC),
            new Row("Second activity",new Box(55,350,320,370),.95,Origin.SYNTHETIC),
            new Row("+10",new Box(414,380,465,405),.95,Origin.SYNTHETIC),
            new Row("Finished activity",new Box(55,495,320,520),.95,Origin.SYNTHETIC),
            completedNumber(.95));
    }
    @Test public void localWhiteTickShapeIsObservedButDiskAloneIsNot(){
        Geometry g=geometry(tickPixels(true,false));
        assertEquals(3,g.cards().size());assertEquals(1,g.cards().get(2).tickMarks().size());
        assertEquals(1.0,g.cards().get(2).tickMarks().get(0).referenceSimilarity(),0);
        assertTrue(geometry(tickPixels(false,false)).cards().get(2).tickMarks().isEmpty());
        assertTrue(geometry(tickPixels(true,true)).cards().get(2).tickMarks().isEmpty());
    }
    @Test public void sameCardPlainNumberThenTickSkipsOnlyCompletedItem(){
        Geometry g=geometry(tickPixels(true,false));
        List<SkippedItem> skipped=RewardRegionLocator.observeCompletedItems(g,List.of(completedNumber(.95)));
        assertEquals(1,skipped.size());assertEquals("visual-card-2",skipped.get(0).visualCardId());
        assertEquals(10,skipped.get(0).displayedNumber());assertFalse(skipped.get(0).credited());
        assertEquals("USER_PROVIDED_TASK_RULE_NUMBER_THEN_LOCAL_TICK",skipped.get(0).rule());
    }
    @Test public void completedShortCardDoesNotBlockTwoUnfinishedCandidates(){
        Result r=associate(geometry(tickPixels(true,false)),gate("0/30"),section(),tickRows());
        assertEquals(Status.CANDIDATES,r.status());assertEquals(2,r.candidates().size());assertEquals(1,r.skippedItems().size());
        assertEquals("visual-card-2",r.skippedItems().get(0).visualCardId());
    }
    @Test public void nativeSizedOcrPaddingMayEnterDiskBackgroundWithoutCoveringGlyph(){
        Geometry g=geometry(tickPixels(true,false));
        Row padded=new Row("10",new Box(426,494,472,524),.999950,Origin.SYNTHETIC);
        List<SkippedItem> skipped=RewardRegionLocator.observeCompletedItems(g,List.of(padded));
        assertEquals(1,skipped.size());assertEquals(padded.box(),skipped.get(0).numberBox());
        List<Row> rows=new ArrayList<>(tickRows());rows.set(rows.size()-1,padded);
        Result r=associate(g,gate("0/30"),section(),rows);
        assertEquals(Status.CANDIDATES,r.status());assertEquals(2,r.candidates().size());
        assertEquals(1,r.skippedItems().size());
    }
    @Test public void paddingCannotCoverGlyphGoTooDeepOrHideCompetingNumber(){
        Geometry g=geometry(tickPixels(true,false));
        for(Box box:List.of(new Box(426,494,473,524),new Box(464,494,474,524),
                new Box(426,476,472,496),new Box(426,340,472,370))){
            assertTrue(RewardRegionLocator.observeCompletedItems(g,List.of(
                new Row("10",box,.95,Origin.SYNTHETIC))).isEmpty());
        }
        assertTrue(RewardRegionLocator.observeCompletedItems(g,List.of(
            new Row("1",new Box(433,502,450,519),.95,Origin.SYNTHETIC),
            new Row("0",new Box(451,502,472,519),.95,Origin.SYNTHETIC))).isEmpty());
    }
    @Test public void missingLowConfidenceOrCrossCardNumberCannotComplete(){
        Geometry g=geometry(tickPixels(true,false));
        assertTrue(RewardRegionLocator.observeCompletedItems(g,List.of()).isEmpty());
        assertTrue(RewardRegionLocator.observeCompletedItems(g,List.of(completedNumber(.2))).isEmpty());
        assertTrue(RewardRegionLocator.observeCompletedItems(g,List.of(
            new Row("10",new Box(433,180,457,197),.95,Origin.SYNTHETIC))).isEmpty());
    }
    @Test public void mathematicalOrMixedGlyphTextIsNotAPartialNumberFallback(){
        Geometry g=geometry(tickPixels(true,false));
        for(String text:List.of("√10","10@","10√","question 10")){
            assertTrue(RewardRegionLocator.observeCompletedItems(g,List.of(
                new Row(text,completedNumber(.95).box(),.95,Origin.SYNTHETIC))).isEmpty());
        }
    }
    @Test public void twoNearbyNumbersCannotBeAssignedToSingleTick(){
        Geometry g=geometry(tickPixels(true,false));
        List<Row> r=List.of(new Row("1",new Box(433,503,440,519),.95,Origin.SYNTHETIC),
            new Row("0",new Box(446,502,457,519),.95,Origin.SYNTHETIC));
        // Individual OCR digits are not blindly concatenated.
        assertTrue(RewardRegionLocator.observeCompletedItems(g,r).isEmpty());
    }
    @Test public void plusTokenAndTickAreConflictingCuesNotCompletion(){
        Geometry g=geometry(tickPixels(true,false));
        List<Row> r=List.of(completedNumber(.95),new Row("+10",new Box(380,502,420,519),.95,Origin.SYNTHETIC));
        assertTrue(RewardRegionLocator.observeCompletedItems(g,r).isEmpty());
    }
    @Test public void plainNumberDoesNotCompleteWithoutRecognizedLocalGlyph(){
        assertTrue(RewardRegionLocator.observeCompletedItems(geometry(tickPixels(false,false)),
            List.of(completedNumber(.95))).isEmpty());
    }
    @Test public void unknownProgressRemainsHeldEvenWithLocalTickObservation(){
        Result r=associate(geometry(tickPixels(true,false)),gate("?/30"),section(),tickRows());
        assertEquals(Status.UNKNOWN_PROGRESS,r.status());assertTrue(r.candidates().isEmpty());
    }

    Key parentKey(){return new Key(Source.SYNTHETIC_FIXTURE,"synthetic-crop",1,"today-points-parent");}
    Assessment parentGate(double confidence){
        return RewardRegionGate.assess(parentKey(),List.of(new Reading(parentKey(),"55/120",confidence,true,true,
            CounterRole.AGGREGATE_PROGRESS,TextBasis.CONTEXTUAL_OCR)));
    }
    ProgressHeader parentHeader(){
        return new ProgressHeader(parentKey(),HeaderLevel.PAGE_AGGREGATE,"今日积分",
            new Box(6,16,131,49),new Box(385,20,479,45),.95,Origin.SYNTHETIC);
    }
    List<Row> withParentRows(){
        List<Row> r=new ArrayList<>(tickRows());
        r.add(new Row(section().title(),section().titleBox(),.95,Origin.SYNTHETIC));
        r.add(new Row("今日积分",parentHeader().titleBox(),.95,Origin.SYNTHETIC));
        r.add(new Row("55/120",parentHeader().counterBox(),.95,Origin.SYNTHETIC));
        return r;
    }
    @Test public void typedPageAggregateAboveChildAllowsInspectionNotChildCredit(){
        Result r=RewardRegionLocator.associate(geometry(tickPixels(true,false)),parentGate(.95),
            parentHeader(),List.of(section()),withParentRows());
        assertEquals(Status.CANDIDATES,r.status());assertEquals(2,r.candidates().size());assertEquals(1,r.skippedItems().size());
        assertEquals(CounterRole.AGGREGATE_PROGRESS,r.progress().role());assertEquals(parentKey(),r.progress().key());
        assertFalse(r.skippedItems().get(0).credited());
    }
    @Test public void parentCounterLowConfidenceStillHolds(){
        assertEquals(Status.UNKNOWN_PROGRESS,RewardRegionLocator.associate(geometry(tickPixels(true,false)),
            parentGate(.49622841),parentHeader(),List.of(section()),withParentRows()).status());
    }
    @Test public void secondParentScopeCannotBeSilentlyDiscarded(){
        List<Row> r=withParentRows();r.add(new Row("今日积分",new Box(6,60,131,85),.95,Origin.SYNTHETIC));
        assertEquals(Status.UNKNOWN_PROGRESS,RewardRegionLocator.associate(geometry(tickPixels(true,false)),
            parentGate(.95),parentHeader(),List.of(section()),r).status());
    }
    @Test public void typedParentCannotComeFromAnotherCropOrSequence(){
        Key stale=new Key(Source.SYNTHETIC_FIXTURE,"other-crop",1,"today-points-parent");
        ProgressHeader wrong=new ProgressHeader(stale,HeaderLevel.PAGE_AGGREGATE,"今日积分",
            parentHeader().titleBox(),parentHeader().counterBox(),.95,Origin.SYNTHETIC);
        assertEquals(Status.UNKNOWN_PROGRESS,RewardRegionLocator.associate(geometry(tickPixels(true,false)),
            parentGate(.95),wrong,List.of(section()),withParentRows()).status());
    }
    @Test public void parentTitleAndCounterMustBothBeObservedAtTheirTrueBoxes(){
        List<Row> r=withParentRows();r.remove(r.size()-2);
        assertEquals(Status.UNKNOWN_PROGRESS,RewardRegionLocator.associate(geometry(tickPixels(true,false)),
            parentGate(.95),parentHeader(),List.of(section()),r).status());
        ProgressHeader moved=new ProgressHeader(parentKey(),HeaderLevel.PAGE_AGGREGATE,"今日积分",
            new Box(6,115,131,140),parentHeader().counterBox(),.95,Origin.SYNTHETIC);
        assertEquals(Status.UNKNOWN_PROGRESS,RewardRegionLocator.associate(geometry(tickPixels(true,false)),
            parentGate(.95),moved,List.of(section()),withParentRows()).status());
    }
}
