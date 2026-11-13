package com.aliothmoon.maafw.once;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Only A's six original boxes/scores come from v26 safe scalars. Known words are
 * manual literals; UNKNOWN_TEXT is not recovered OCR. B/aux pixels, rows and scores
 * are explicitly synthetic passing context, never reconstructed live evidence. */
public class V26MeasuredLayoutAdapterTest {
    static final int W=1440,H=2560;
    static final String UNKNOWN_TEXT="SYNTHETIC_UNMODELED_TOKEN";
    static void fill(byte[] p,int l,int t,int r,int b,int value){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)value);}
    static void tick(byte[] p,int left,int top){
        for(int y=0;y<50;y++)for(int x=0;x<50;x++)if(Math.pow(x+.5-25,2)+Math.pow(y+.5-25,2)<=625)p[(top+y)*W+left+x]=(byte)110;
        String[] mask={"00000000011","00000000111","00000001110","10000011100","11000111000","11101110000","01111100000","00111000000"};
        for(int y=0;y<8;y++)for(int x=0;x<11;x++)if(mask[y].charAt(x)=='1')fill(p,left+14+2*x,top+16+2*y,left+16+2*x,top+18+2*y,255);
    }
    static byte[] pixels(){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);
        fill(p,114,661,1326,1069,255);fill(p,1022,793,1278,937,80);
        fill(p,114,1111,1326,1519,255);fill(p,1022,1243,1278,1387,80);
        fill(p,114,1559,1326,1737,255);tick(p,1210,1620);
        fill(p,114,1773,1326,1949,255);tick(p,1210,1834);return p;
    }
    static FrameStructureEvidence.Line line(String s,double q,int l,int t,int r,int b){return new FrameStructureEvidence.Line(s,l,t,r-l,b-t,q);}
    static List<FrameStructureEvidence.Line> rows(){return new ArrayList<>(List.of(
        line("更多活动",.999907,64,450,380,516),line("10/20",.999988,1096,456,1248,512),
        line(UNKNOWN_TEXT,.398219,177,786,309,920),
        line("Do you know the",.999976,360,712,804,784),line("answer?",.999897,360,794,592,858),
        line("Challenge yourself with",.99988,365,880,900,936),line("these trivia questions",.999857,365,957,860,1010),line("+5",.997889,1090,826,1207,898),
        line("Do you know the",.999976,362,1162,802,1226),line("answer?",.999897,360,1242,592,1306),
        line("Test your knowledge on",.95,365,1330,900,1386),line("these topics",.95,365,1407,860,1460),line("+5",.95,1090,1276,1207,1348),
        line("Complete this puzzle",.95,360,1600,880,1654),line("5",.95,1120,1627,1177,1675),
        line("Quote of the day",.95,360,1814,880,1868),line("5",.95,1120,1841,1177,1889)));}
    static LiveRewardCardObservation.Frame observe(byte[] p,List<FrameStructureEvidence.Line> rows){
        return LiveRewardCardObservation.observe("v26-measured-A-synthetic-context",1,p,W,H,rows,()->false,LiveRewardCardObservation.TargetSpec.moreTriviaChallenge());
    }
    @Test public void measuredAReachesTheRealAdapterWithOnlySyntheticPassingSiblingAndAuxContext(){
        var p=pixels();var input=rows();var originals=List.copyOf(input);var frame=observe(p,input);
        System.out.println("V26_FIXTURE status="+frame.status()+" reason="+frame.reason()+" "+frame.diagnostic().summary());
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,frame.status());assertEquals(2,frame.cards().size());
        assertEquals(LiveRewardCardObservation.AdapterGate.NONE,frame.diagnostic().adapterFirstGate());
        assertEquals(1,frame.diagnostic().familyItemDiagnostics().get(0).leftRows());assertEquals(4,frame.diagnostic().familyItemDiagnostics().get(0).identityRows());
        assertEquals(1,frame.diagnostic().familyItemDiagnostics().get(0).stateRows());
        assertTrue(frame.cards().get(0).configuredTarget(LiveRewardCardObservation.TargetSpec.moreTriviaChallenge()));
        assertEquals(originals,input);assertFalse(frame.diagnostic().summary().contains(UNKNOWN_TEXT));
    }
    @Test public void syntheticHeaderCounterOverhangRemainsAnExplicitCoverageHold(){
        var input=rows();var r=input.get(0);input.set(0,new FrameStructureEvidence.Line(r.text(),r.x(),r.y(),r.width(),60,r.score()));
        var frame=observe(pixels(),input);assertEquals(LiveRewardCardObservation.Status.HOLD,frame.status());
        assertEquals(LiveRewardCardObservation.AdapterGate.UNEXPLAINED_ROW,frame.diagnostic().adapterFirstGate());
        assertEquals(1,frame.diagnostic().unexplainedRowsSeen());
    }    static byte[] completedPixels(int height,boolean tickPresent,boolean siblingChanged){
        byte[] p=pixels();fill(p,114,661,1326,1069,247);fill(p,114,661,1326,661+height,255);
        if(tickPresent)tick(p,1210,661+height/2-25);
        if(siblingChanged){fill(p,1022,1243,1278,1387,255);tick(p,1210,1290);}return p;
    }
    static List<FrameStructureEvidence.Line> completedRows(int height,boolean bodyConflict,boolean siblingChanged){
        var input=rows();input.removeIf(r->r.text().equals(UNKNOWN_TEXT)||r.y()<1100&&(r.text().equals("Challenge yourself with")||r.text().equals("these trivia questions")||r.text().equals("+5")));
        input.set(1,line("15/20",.999988,1096,456,1248,512));
        int center=661+height/2;input.add(line("5",.997889,1090,center-36,1207,center+36));
        if(bodyConflict)input.add(line("Unexpected body",.99,365,880,900,900));
        if(siblingChanged)for(int i=0;i<input.size();i++){var r=input.get(i);if(r.text().equals("+5")&&r.y()>1100)input.set(i,new FrameStructureEvidence.Line("5",r.x(),r.y(),r.width(),r.height(),r.score()));}
        return input;
    }
    private static final class GeometryRun implements NativeAutonomousCardRun.FixturePorts {
        final java.util.concurrent.atomic.AtomicLong elapsed=new java.util.concurrent.atomic.AtomicLong(1000);
        final java.util.concurrent.atomic.AtomicInteger taps=new java.util.concurrent.atomic.AtomicInteger();
        final BingNavigationSnapshot.Shape shape=new BingNavigationSnapshot.Shape(W,H,0);
        final NativeAutonomousCardRun run=NativeAutonomousCardRun.fixture(elapsed::get,()->AutonomousRewardCardScope.STARTS_UTC+1000,this);
        public BingNavigationSnapshot.Shape shape(){return shape;}
        public boolean authorityMatches(BingNavigationSnapshot.Shape actual){return shape.equals(actual);}
        public boolean knownClean(){return true;}
        public BingNavigationSnapshot.Result observe(BingNavigationSnapshot.Shape actual){
            String component=BingNavigationSnapshot.TEMPLATE;
            String windows=BingNavigationFixture.windows(component,"aaa","ddd","15").replace("w=523 h=1000","w=1440 h=2560").replace("w=523 h=96","w=1440 h=96");
            return BingNavigationSnapshot.parse(windows,BingNavigationFixture.activities(component,"ddd","15",true),new BingNavigationSnapshot.Profile(10073,10049,shape),elapsed.get(),elapsed.get());
        }
        public void consumeTap(ReviewShizukuTapActor.TapTarget t){}public void consumeReturn(ReviewShizukuBackActor.BackTarget t){throw new AssertionError("Source already ready: no fixture Back needed");}
        public void tapped(ReviewShizukuTapActor.TapTarget t){taps.incrementAndGet();}public void backed(ReviewShizukuBackActor.BackTarget t){throw new AssertionError("Unexpected fixture Back");}
        String accept(NativeRewardCardSession.Phase phase,byte[] p,List<FrameStructureEvidence.Line> input){
            run.reserve(phase);long end=run.deadline(elapsed.get());var capture=run.startCapture(phase,W,H);assertNotNull(capture);
            var observation=capture.prepare(p,W,H,input,()->false);assertNotNull(observation);
            return run.acceptAfterCleanup(capture,observation,true,()->true,end);
        }
        void before(){String first=accept(NativeRewardCardSession.Phase.BEFORE,pixels(),rows());assertNotNull(first);assertTrue(first.contains("outcome=AWAITING_NEW_OBSERVATION"));assertTrue(run.returnReady());assertEquals(1,taps.get());elapsed.addAndGet(10);}
    }
    @Test public void hypothetical256By1212CompletedCardTraversesTheActualRunAndSessionRelation(){
        var rig=new GeometryRun();rig.before();String after=rig.accept(NativeRewardCardSession.Phase.AFTER,completedPixels(256,true,false),completedRows(256,false,false));
        assertNotNull(after);assertTrue(after,after.contains("outcome=OBSERVED_TASK_COMPLETE_UNATTRIBUTED"));assertEquals(1,rig.taps.get());assertFalse(rig.run.needsAfter());
    }
    @Test public void familyAspectAbovePoint22MissingTickBodyConflictOrSiblingChangeStillHold(){
        for(int kind=0;kind<4;kind++){
            int height=kind==0?267:256;var rig=new GeometryRun();rig.before();
            String after=rig.accept(NativeRewardCardSession.Phase.AFTER,completedPixels(height,kind!=1,kind==3),completedRows(height,kind==2,kind==3));
            assertNotNull(after);assertTrue("case="+kind+" "+after,after.contains("outcome=HOLD"));assertEquals(1,rig.taps.get());assertFalse(rig.run.needsAfter());
        }
    }
    private static LiveRewardCardObservation.Card quote(boolean complete,int height){
        byte[] p=new byte[W*H];Arrays.fill(p,(byte)247);fill(p,114,661,1326,complete?661+height:1069,255);
        if(complete)tick(p,1210,661+height/2-25);else fill(p,1022,793,1278,937,80);
        var input=new ArrayList<FrameStructureEvidence.Line>();input.add(line("更多活动",.99,64,450,380,516));input.add(line("10/20",.99,1096,456,1248,512));
        input.add(line("Quote of the day",.99,360,712,804,784));
        if(complete){int center=661+height/2;input.add(line("5",.99,1090,center-36,1207,center+36));}
        else{input.add(line("Start your day with a quote",.99,365,880,900,936));input.add(line("+5",.99,1090,826,1207,898));}
        var frame=LiveRewardCardObservation.observe("synthetic-quote-regression",1,p,W,H,input,()->false,LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay());
        assertEquals(LiveRewardCardObservation.Status.OBSERVED,frame.status());assertEquals(1,frame.cards().size());return frame.cards().get(0);
    }
    @Test public void quoteKeepsPoint20AspectWhileOnlyTheFixedFamilyUsesPoint22(){
        var before=quote(false,408);assertTrue(before.matchesControlledCompactCompletion(quote(true,240),LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay()));
        assertFalse(before.matchesControlledCompactCompletion(quote(true,256),LiveRewardCardObservation.TargetSpec.moreQuoteOfTheDay()));
    }
    @Test public void firstFailedAuxiliaryOnlyHasBoundedOriginalGeometryScoresAndNoText(){
        var input=rows();for(int i=0;i<7;i++)input.add(line("SYNTHETIC_AUX_TOKEN_"+i,.4,500,1660+i*8,550,1664+i*8));
        var frame=observe(pixels(),input);assertEquals(LiveRewardCardObservation.Status.HOLD,frame.status());
        var aux=frame.diagnostic().auxiliaryDiagnostics();assertEquals(2,aux.size());var failed=aux.get(0);
        assertEquals(1,failed.failureCardMatches());assertNotNull(failed.failureCardBox());assertEquals(9,failed.failureLocalRows());
        assertEquals(8,failed.failureRows().size());assertTrue(failed.failureRowsTruncated());assertFalse(failed.failureRowsPartitioned());
        for(var scalar:failed.failureRows()){
            var original=input.stream().filter(r->r.x()==scalar.box().left()&&r.y()==scalar.box().top()&&r.x()+r.width()==scalar.box().right()&&r.y()+r.height()==scalar.box().bottom()).findFirst().orElseThrow();
            assertEquals(original.score(),scalar.confidence(),0);assertEquals("UNCLASSIFIED",scalar.region().name());assertTrue(frame.diagnostic().summary().contains(scalar.toString()));
        }
        assertEquals(-1,aux.get(1).failureCardMatches());assertThrows(UnsupportedOperationException.class,()->failed.failureRows().clear());
        assertFalse(frame.diagnostic().summary().contains("SYNTHETIC_AUX_TOKEN"));assertTrue(frame.diagnostic().summary().length()+2048<8000);
    }}