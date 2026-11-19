package com.aliothmoon.maafw.once;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NewsDeviceEvidenceTest {
    private static final String NONCE="0123456789abcdef0123456789abcdef";

    @Test public void carouselHasOnlyAScrollProofAndCannotOwnAStoryTap(){
        var rotating=NewsFeedModel.inspect(NewsFeedModelTest.list(true),1000,2000,-1,null);
        var ordinary=NewsFeedModel.inspect(NewsFeedModelTest.list(false),1000,2000,-1,null);
        var proof=NewsAndroidPort.scrollOnly(rotating);
        assertEquals(ReadingNewsCycle.Kind.UNKNOWN,proof.kind());
        assertEquals("",proof.key());
        assertEquals(rotating.card().layoutKey(),proof.layoutKey());
        assertNull(NewsAndroidPort.scrollOnly(ordinary));
        assertThrows(SecurityException.class,()->new NewsAndroidPort.StoryTapCheck(rotating,LiveRewardsTypes.hash("story")));
    }

    static NewsFeedModel.Frame overlaySlot(boolean covered){
        var nodes=NewsFeedModelTest.list(false);
        if(covered)nodes.add(NewsFeedModelTest.n(90,1,NewsFeedModelTest.b(390,635,610,740),null,"",true));
        return NewsFeedModel.inspect(nodes,1000,2000,1180,null);
    }
    private static NewsAndroidPort.Snapshot snapshot(NewsFeedModel.Frame frame,int window){
        return new NewsAndroidPort.Snapshot(frame,
                BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,1000).ready(),window,1000);
    }
    @Test public void coveredOwnerCanOnlyAdvanceUnderSameWindowGeometryAndMeasuredStride(){
        var clear=snapshot(overlaySlot(false),1);var covered=snapshot(overlaySlot(true),1);
        assertEquals("FEED_OCCLUDED",covered.frame().reason());
        var proof=NewsAndroidPort.scrollOnly(covered.frame());
        assertEquals(ReadingNewsCycle.Kind.UNKNOWN,proof.kind());assertEquals("",proof.key());
        assertThrows(SecurityException.class,()->new NewsAndroidPort.StoryTapCheck(covered.frame(),LiveRewardsTypes.hash("story")));
        assertEquals(NewsAndroidPort.FrameDecision.ACCEPT,NewsAndroidPort.compare(clear,covered,NewsAndroidPort.RefreshUse.IDENTIFY));
        assertEquals(NewsAndroidPort.FrameDecision.SKIP_BEFORE_TAP,NewsAndroidPort.compare(clear,covered,NewsAndroidPort.RefreshUse.STORY_TAP));
        assertEquals(NewsAndroidPort.FrameDecision.REJECT,NewsAndroidPort.compare(covered,covered,NewsAndroidPort.RefreshUse.STORY_TAP));
        var swipe=NewsAndroidPort.RefreshUse.FEED_SWIPE;
        assertEquals(NewsAndroidPort.FrameDecision.ACCEPT,NewsAndroidPort.compare(covered,clear,swipe));
        assertEquals(NewsAndroidPort.FrameDecision.ACCEPT,NewsAndroidPort.compare(clear,covered,swipe));
        assertEquals(NewsAndroidPort.FrameDecision.REJECT,NewsAndroidPort.compare(covered,snapshot(clear.frame(),2),swipe));
        var foreign=new NewsAndroidPort.Snapshot(clear.frame(),BingNavigationFixture.result(BingNavigationSnapshot.Kind.BROWSER,1000).ready(),1,1000);
        assertEquals(NewsAndroidPort.FrameDecision.REJECT,NewsAndroidPort.compare(covered,foreign,swipe));
        var f=clear.frame();
        var changedStride=new NewsFeedModel.Frame(f.page(),f.viewport(),f.entry(),f.entryComplete(),f.card(),f.stride()+1,f.atEnd(),f.reason());
        assertEquals(NewsAndroidPort.FrameDecision.REJECT,NewsAndroidPort.compare(covered,snapshot(changedStride,1),swipe));
    }
    static void rejectCoveredGate(boolean finalGate,ReadingNewsCycle.BurnGate budget)throws Exception{
        var clear=snapshot(overlaySlot(false),1);var covered=snapshot(overlaySlot(true),1);
        var target=new ReviewShizukuTapActor.TapTarget(500,690,1000,2000,0);
        String key=LiveRewardsTypes.hash("story");
        var story=new NewsAndroidPort.StoryTapCheck(clear.frame(),key);
        var actor=new ReviewShizukuTapActor(()->new ReviewShizukuTapActor.Session(){
            public boolean stillReady(){return true;}
            public ReviewShizukuTapActor.DisplayShape display(){return new ReviewShizukuTapActor.DisplayShape(1000,2000,0);}
            public ReviewShizukuTapActor.PendingTap dispatch(ReviewShizukuTapActor.TapTarget t,
                    ReviewShizukuTapActor.FinalGate gate)throws Exception{
                assertTrue(finalGate);
                gate.requireValid();
                throw new AssertionError("A covered point reached native input");
            }
        });
        var result=actor.tap(target,new ReviewShizukuTapActor.DispatchGate(){
            public boolean consume(ReviewShizukuTapActor.TapTarget exact){
                var observed=finalGate?clear:covered;
                if(NewsAndroidPort.compare(clear,observed,NewsAndroidPort.RefreshUse.STORY_TAP)
                        ==NewsAndroidPort.FrameDecision.SKIP_BEFORE_TAP)return story.markChanged();
                return true;
            }
            public boolean stillValid(ReviewShizukuTapActor.TapTarget exact)throws Exception{
                if(NewsAndroidPort.compare(clear,covered,NewsAndroidPort.RefreshUse.STORY_TAP)
                        ==NewsAndroidPort.FrameDecision.SKIP_BEFORE_TAP)return story.markChanged();
                return story.checkAndBurn(new NewsLocalOcr.Result(NewsFeedModel.Kind.ARTICLE,key,500,690,"BADGE_ABSENT",true,""),exact,budget);
            }
        });
        assertEquals(finalGate?ReviewShizukuTapActor.Reason.AUTHORITY_STALE:ReviewShizukuTapActor.Reason.AUTHORITY_REJECTED,
                result.reason());
        assertFalse(result.invocationAttempted());assertTrue(actor.knownClean());
        assertTrue(story.confirmedSkip(result,false));assertFalse(story.confirmedSkip(result,true));
        throw new ReadingNewsCycle.CardChangedBeforeDispatch();
    }

    @Test public void actorRejectsChangedImageAndAdWithoutEnteringStoryBudgetOrDispatch(){
        var key=LiveRewardsTypes.hash("first");
        var changed=LiveRewardsTypes.hash("replacement");
        var slot=NewsFeedModel.inspect(NewsFeedModelTest.list(false),1000,2000,-1,null);
        var point=new ReviewShizukuTapActor.TapTarget(200,500,1000,2000,0);
        var rows=List.of(
                new NewsLocalOcr.Result(NewsFeedModel.Kind.AD,key,-1,-1,"AD_BADGE",true,""),
                new NewsLocalOcr.Result(NewsFeedModel.Kind.ARTICLE,changed,200,500,"BADGE_ABSENT",true,""),
                new NewsLocalOcr.Result(NewsFeedModel.Kind.UNKNOWN,"",-1,-1,"BADGE_ROW_UNCERTAIN",true,""));
        for(var row:rows){
            var story=new NewsAndroidPort.StoryTapCheck(slot,key);
            var actor=new ReviewShizukuTapActor(()->new ReviewShizukuTapActor.Session(){
                public boolean stillReady(){return true;}
                public ReviewShizukuTapActor.DisplayShape display(){return new ReviewShizukuTapActor.DisplayShape(1000,2000,0);}
                public ReviewShizukuTapActor.PendingTap dispatch(ReviewShizukuTapActor.TapTarget t,
                        ReviewShizukuTapActor.FinalGate finalGate){throw new AssertionError("Unexpected dispatch");}
            });
            var result=actor.tap(point,new ReviewShizukuTapActor.DispatchGate(){
                public boolean consume(ReviewShizukuTapActor.TapTarget target)throws Exception{
                    return story.checkAndBurn(row,target,()->{throw new AssertionError("Unexpected daily budget burn");});
                }
                public boolean stillValid(ReviewShizukuTapActor.TapTarget target){throw new AssertionError("After rejected gate");}
            });
            assertFalse(result.invocationAttempted());
            assertTrue(actor.knownClean());
            assertEquals(row.kind()!=NewsFeedModel.Kind.UNKNOWN,story.confirmedSkip(result,false));
            assertFalse(story.confirmedSkip(result,true));
            assertFalse(story.confirmedSkip(new ReviewShizukuTapActor.Result(ReviewShizukuTapActor.Kind.REJECTED,
                    ReviewShizukuTapActor.NO_EXIT,false,true,ReviewShizukuTapActor.Reason.DISPLAY_CHANGED),false));
        }
    }

    @Test public void storyBudgetRequiresSameImageClearBadgeAndExactFinalPoint()throws Exception{
        var key=LiveRewardsTypes.hash("first");
        var slot=NewsFeedModel.inspect(NewsFeedModelTest.list(false),1000,2000,-1,null);
        var point=new ReviewShizukuTapActor.TapTarget(200,500,1000,2000,0);
        var story=new NewsAndroidPort.StoryTapCheck(slot,key);
        var burns=new java.util.concurrent.atomic.AtomicInteger();
        var movedPoint=new NewsLocalOcr.Result(NewsFeedModel.Kind.ARTICLE,key,230,500,"BADGE_ABSENT",true,"");
        assertThrows(SecurityException.class,()->story.checkAndBurn(movedPoint,point,burns::incrementAndGet));
        assertEquals(0,burns.get());
        var exact=new NewsLocalOcr.Result(NewsFeedModel.Kind.ARTICLE,key,200,500,"BADGE_ABSENT",true,"");
        assertTrue(story.checkAndBurn(exact,point,burns::incrementAndGet));
        assertEquals(1,burns.get());
    }

    private static NativeUiStructureProtocol.Report source(int chars) {
        var idle=new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,0,0,0,0,0,0);
        var unknown=NativeUiStructureProtocol.Task.unknown();
        return new NativeUiStructureProtocol.Report(NONCE,1100,1150,1200,
                NativeUiStructureProtocol.Reason.OK,NativeUiStructureProtocol.Cleanup.CONFIRMED,
                2,chars,3,0,0,0,500,900,-1,-1,unknown,unknown,idle,null,true);
    }
    @Test public void articleWindowReceiptNeedsNoRenderedDocumentAndCannotAuthorizeAListRead()throws Exception{
        var unknown=NativeUiStructureProtocol.Task.unknown();
        var guard=new NativeUiStructureProtocol.Report(NONCE,1100,1150,1200,
                NativeUiStructureProtocol.Reason.OK,NativeUiStructureProtocol.Cleanup.CONFIRMED,
                1,0,3,0,0,0,500,900,-1,-1,unknown,unknown);
        var out=new ByteArrayOutputStream();NewsTreeWire.writeArticle(out,guard);
        byte[] bytes=out.toByteArray();
        var parsed=NewsTreeWire.parseArticle(bytes,bytes.length,NONCE,1000,9000,1450);
        assertEquals(guard,parsed.source());assertTrue(parsed.nodes().isEmpty());
        assertThrows(IOException.class,()->parse(bytes));
        byte[] ordinary=wire(List.of(node(0,-1,true,200),node(1,0,true,120)),6);
        assertThrows(IOException.class,()->NewsTreeWire.parseArticle(ordinary,ordinary.length,NONCE,1000,9000,1450));
        var before=BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,1000).ready();
        var after=BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,1250).ready();
        var other=BingNavigationFixture.result(BingNavigationSnapshot.Kind.BROWSER,1250).ready();
        assertTrue(NewsAndroidPort.articleBound(guard,before,after,before.profile().shape(),1300));
        assertFalse(NewsAndroidPort.bound(guard,before,after,before.profile().shape(),1300));
        assertFalse(NewsAndroidPort.articleBound(source(6),before,after,before.profile().shape(),1300));
        assertFalse(NewsAndroidPort.articleBound(guard,before,other,before.profile().shape(),1300));
        assertFalse(NewsAndroidPort.articleBound(guard,before,after,before.profile().shape(),9000));
        var blocker=new NativeUiStructureProtocol.Report(NONCE,1100,1150,1200,
                NativeUiStructureProtocol.Reason.UI_BLOCKER,NativeUiStructureProtocol.Cleanup.CONFIRMED,
                1,0,-1,-1,0,0,0,0,-1,-1,unknown,unknown);
        assertFalse(NewsAndroidPort.articleBound(blocker,before,after,before.profile().shape(),1300));
        assertTrue(NativeUiStructureHelper.validReadyArguments(new String[]{NONCE,"9000",NewsTreeWire.ARTICLE_ARGUMENT}));
    }
    @Test public void longDocumentFieldsNeverDelayTheViewerOrConcealSeparateChallengeAndLoginLabels(){
        var guard=new LiveVisitGuard.Accumulator();
        var ready=new NativeUiReadyDiagnostics.Accumulator();
        for(CharSequence value:List.of("normal story text ".repeat(4000)," ".repeat(3500)+"验证身份","Sign in")){
            String label=NativeUiStructureHelper.articleGuardLabel(value);
            assertTrue(label.length()<=128);
            guard.node("android.webkit.WebView",label,"",true);
            ready.node("android.webkit.WebView",label,"");
        }
        assertEquals("",NativeUiStructureHelper.articleGuardLabel("a".repeat(40000)));
        assertEquals(1,guard.challenges);assertTrue(ready.blocked());
    }
    @Test public void sampledArticleMatchesOnlyTheSameOsAndAccessibilityWindow(){
        var article=new NewsFeedModel.Frame(NewsFeedModel.Page.ARTICLE,null,null,false,null,0,false,"BOUND_ARTICLE_VIEWER");
        var early=snapshot(article,3);
        var bound=NewsAndroidPort.ArticleWindow.from(early);
        assertTrue(bound.matches(snapshot(article,3)));assertFalse(bound.matches(snapshot(article,4)));
        var other=new NewsAndroidPort.Snapshot(article,BingNavigationFixture.result(BingNavigationSnapshot.Kind.BROWSER,1000).ready(),3,1000);
        assertFalse(bound.matches(other));
        var returned=alignmentFrame(235,765,"FEED_CANDIDATE",550);
        assertFalse(bound.matches(returned));assertThrows(SecurityException.class,()->NewsAndroidPort.ArticleWindow.from(returned));
    }
    @Test public void transientCoverOnlyRereadsAndNeverRetriesOtherFailures()throws Exception{
        int[] reads={0};long[] now={0};
        int value=NewsAndroidPort.awaitClearArticle(()->{if(++reads[0]==1)throw new NewsAndroidPort.ArticleCovered();return 7;},
                millis->now[0]+=millis,()->now[0]);
        assertEquals(7,value);assertEquals(2,reads[0]);assertEquals(500,now[0]);
        reads[0]=0;
        assertThrows(SecurityException.class,()->NewsAndroidPort.awaitClearArticle(()->{
            reads[0]++;throw new NewsAndroidPort.ArticleCovered();},millis->now[0]+=millis,()->now[0]));
        assertEquals(6,reads[0]);reads[0]=0;
        assertThrows(SecurityException.class,()->NewsAndroidPort.awaitClearArticle(()->{
            reads[0]++;throw new SecurityException("NEWS_TREE_UI_BLOCKER");},millis->fail("Unexpected wait"),()->now[0]));
        assertEquals(1,reads[0]);
    }
    private static NativeUiBoundPointsParser.Node node(int index,int parent,boolean own,int right) {
        return new NativeUiBoundPointsParser.Node(index,parent,true,own,"card","abc","",6,9,right,41,
                NativeUiBoundPointsParser.ClassRole.TEXT,index!=0);
    }
    private static byte[] wire(List<NativeUiBoundPointsParser.Node> nodes,int chars)throws IOException{
        var out=new ByteArrayOutputStream();NewsTreeWire.write(out,source(chars),nodes);return out.toByteArray();
    }
    private static NewsTreeWire.Tree parse(byte[] data)throws IOException{
        return NewsTreeWire.parse(data,data.length,NONCE,1000,9000,1450);
    }
    @Test public void wireRequiresExactlyOneOwnedTreeWithOriginalCoordinates()throws Exception{
        var nodes=List.of(node(0,-1,true,200),node(1,0,true,120));
        var data=wire(nodes,6);
        assertEquals(nodes,parse(data).nodes());
        assertEquals(source(6),parse(data).source());
        assertThrows(IOException.class,()->parse(Arrays.copyOf(data,data.length-1)));
        assertThrows(IOException.class,()->parse(Arrays.copyOf(data,data.length+1)));
        assertThrows(IOException.class,()->parse(wire(nodes,5)));
        assertThrows(IllegalArgumentException.class,()->NewsTreeWire.parse(data,data.length,
                "ffffffffffffffffffffffffffffffff",1000,9000,1450));
    }
    @Test public void rejectsForeignNodesBadAncestryAndOutOfWindowGeometry(){
        assertThrows(IOException.class,()->parse(wire(List.of(node(0,-1,true,200),node(1,0,false,120)),6)));
        assertThrows(IOException.class,()->parse(wire(List.of(node(0,-1,true,200),node(1,1,true,120)),6)));
        assertThrows(IOException.class,()->parse(wire(List.of(node(0,-1,true,200),node(1,0,true,1000)),6)));
    }
    @Test public void sourceMustSurroundHelperAndStayInTheSameFocusedWindow(){
        var before=BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,1000).ready();
        var after=BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,1250).ready();
        var child=BingNavigationFixture.result(BingNavigationSnapshot.Kind.BROWSER,1250).ready();
        var shape=before.profile().shape();
        assertTrue(NewsAndroidPort.bound(source(6),before,after,shape,1300));
        assertFalse(NewsAndroidPort.bound(source(6),before,child,shape,1300));
        assertFalse(NewsAndroidPort.bound(source(6),after,after,shape,1300));
        assertFalse(NewsAndroidPort.bound(source(6),before,before,shape,1300));
        assertFalse(NewsAndroidPort.bound(source(6),before,after,shape,9000));
    }
    @Test public void measuredSwipeCarriesOneCardPitchAndRequiresDedicatedOwner(){
        var up=ReviewShizukuSwipeActor.Direction.UP;
        var measured=new ReviewShizukuSwipeActor.SwipeTarget(1440,2560,0,up,1330,true);
        assertTrue(ReviewShizukuSwipeActor.valid(measured));
        assertEquals(1330,measured.fromY()-measured.toY());
        assertFalse(ReviewShizukuSwipeActor.valid(new ReviewShizukuSwipeActor.SwipeTarget(1440,2560,0,up,1330)));
        assertFalse(ReviewShizukuSwipeActor.valid(new ReviewShizukuSwipeActor.SwipeTarget(1440,2560,0,up,2000,true)));
        var actor=new ReviewShizukuSwipeActor(()->{throw new AssertionError("Unexpected command");});
        var rejected=actor.swipe(measured,new ReviewShizukuSwipeActor.DispatchGate(){
            public boolean consume(ReviewShizukuSwipeActor.SwipeTarget ignored){throw new AssertionError("Unexpected burn");}
            public boolean stillValid(ReviewShizukuSwipeActor.SwipeTarget ignored){return false;}
        });
        assertFalse(rejected.invocationAttempted());
    }
    private static NewsAndroidPort.Snapshot alignmentFrame(int top,int bottom,String reason,int distance){
        var main=BingNavigationSnapshot.MAIN;
        var ready=V46EntryMetadataTest.r(V46EntryMetadataTest.w(main),V46EntryMetadataTest.a(main)).ready();
        boolean partial=reason.startsWith("FEED_ALIGN_");
        var box=NewsFeedModelTest.b(40,top,483,bottom);
        var image=NewsFeedModelTest.b(50,top+12,473,top+172);
        var source=partial?null:NewsFeedModelTest.b(50,top+180,105,top+203);
        var card=new NewsFeedModel.Card(partial?NewsFeedModel.Kind.UNKNOWN:NewsFeedModel.Kind.ARTICLE,
                box,source,null,"","","",LiveRewardsTypes.hash("synthetic-slot"),image);
        var frame=new NewsFeedModel.Frame(NewsFeedModel.Page.FEED,NewsFeedModelTest.b(0,20,523,900),null,false,
                card,distance,false,reason);
        return new NewsAndroidPort.Snapshot(frame,ready,9,1000);
    }
    @Test public void smallCorrectionsOnlyReturnFreshCompleteCardsInTheSameList()throws Exception{
        var display=BingNavigationFixture.SHAPE;
        var complete=alignmentFrame(235,765,"FEED_CANDIDATE",550);
        for(var direction:ReviewShizukuSwipeActor.Direction.values()){
            var partial=direction==ReviewShizukuSwipeActor.Direction.UP
                    ?alignmentFrame(400,930,"FEED_ALIGN_UP",200):alignmentFrame(0,530,"FEED_ALIGN_DOWN",200);
            int[] moves={0};
            var result=NewsAndroidPort.alignCard(partial,500,display,(before,target)->{
                assertSame(partial,before);assertEquals(direction,target.direction());
                assertEquals(200,target.distance());assertTrue(target.measuredNewsStride());
                assertTrue(ReviewShizukuSwipeActor.valid(target));moves[0]++;
                return complete;
            });
            assertSame(complete,result);assertEquals(1,moves[0]);
        }
    }
    @Test public void correctionStopsOnNoMovementWindowChangeOrAnIncompleteReread(){
        var display=BingNavigationFixture.SHAPE;
        var first=alignmentFrame(400,930,"FEED_ALIGN_UP",200);
        var visible=alignmentFrame(235,765,"FEED_CANDIDATE",550);
        var otherWindow=new NewsAndroidPort.Snapshot(visible.frame(),first.source(),10,1000);
        var otherAppWindow=new NewsAndroidPort.Snapshot(visible.frame(),
                BingNavigationFixture.result(BingNavigationSnapshot.Kind.BROWSER,1000).ready(),first.window(),1000);
        for(var after:List.of(first,otherWindow,otherAppWindow,alignmentFrame(395,925,"FEED_CANDIDATE",550))){
            int[] moves={0};
            assertThrows(SecurityException.class,()->NewsAndroidPort.alignCard(first,500,display,(s,t)->{moves[0]++;return after;}));
            assertEquals(1,moves[0]);
        }
        var wrongSource=new NewsAndroidPort.Snapshot(first.frame(),otherAppWindow.source(),first.window(),1000);
        for(var before:List.of(wrongSource,alignmentFrame(400,930,"FEED_ALIGN_UP",199))){
            assertThrows(SecurityException.class,()->NewsAndroidPort.alignCard(before,500,display,(s,t)->{
                throw new AssertionError("Invalid correction dispatched");
            }));
        }
        assertThrows(SecurityException.class,()->NewsAndroidPort.alignCard(first,-1,display,(s,t)->{
            throw new AssertionError("Unanchored correction dispatched");
        }));
    }
    @Test public void partialCardConsumesAtMostTwoSmallCorrectionsBeforeAnotherCapture()throws Exception{
        var display=BingNavigationFixture.SHAPE;
        var first=alignmentFrame(400,930,"FEED_ALIGN_UP",200);
        var second=alignmentFrame(390,920,"FEED_ALIGN_UP",200);
        for(boolean complete:List.of(false,true)){
            int[] moves={0};
            var last=complete?alignmentFrame(235,765,"FEED_CANDIDATE",550):alignmentFrame(380,910,"FEED_ALIGN_UP",200);
            NewsAndroidPort.AlignmentStep advance=(s,t)->{moves[0]++;return moves[0]==1?second:last;};
            if(complete)assertSame(last,NewsAndroidPort.alignCard(first,500,display,advance));
            else assertThrows(SecurityException.class,()->NewsAndroidPort.alignCard(first,500,display,advance));
            assertEquals(2,moves[0]);
        }
    }
    @Test public void feedLoadingSamplesOnlyKnownTransitionsAndNeverGrantsInput(){
        var placeholder=new NewsFeedModel.Frame(NewsFeedModel.Page.UNKNOWN,null,null,false,null,0,false,"NO_FEED_CARDS");
        var ambiguous=new NewsFeedModel.Frame(NewsFeedModel.Page.UNKNOWN,null,null,false,null,0,false,"AMBIGUOUS_FEED");
        var blocked=new NewsFeedModel.Frame(NewsFeedModel.Page.BLOCKED,null,null,false,null,0,false,"BLOCKER");
        var rewards=new NewsFeedModel.Frame(NewsFeedModel.Page.REWARDS,null,null,false,null,0,false,"READING_ENTRY");
        assertTrue(NewsAndroidPort.feedMayStillLoad(placeholder,true));
        assertTrue(NewsAndroidPort.entryMayStillLoad(placeholder));
        assertFalse(NewsAndroidPort.entryMayStillLoad(rewards));
        assertFalse(NewsAndroidPort.entryMayStillLoad(ambiguous));
        assertFalse(NewsAndroidPort.entryMayStillLoad(blocked));
        assertTrue(NewsAndroidPort.feedMayStillLoad(rewards,true));
        assertFalse(NewsAndroidPort.feedMayStillLoad(rewards,false));
        assertFalse(NewsAndroidPort.feedMayStillLoad(ambiguous,true));
        assertFalse(NewsAndroidPort.feedMayStillLoad(blocked,true));
    }
}
