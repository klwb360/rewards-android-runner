package com.aliothmoon.maafw.once;

import org.junit.Test;
import static com.aliothmoon.maafw.once.BingNavigationFixture.*;
import static com.aliothmoon.maafw.once.BingNavigationSnapshot.*;
import static com.aliothmoon.maafw.once.LiveNavigationSnapshotTest.*;
import static org.junit.Assert.*;

/** Synthetic owner, full-screen browser and neighboring activity histories. */
public class ReadingNewsSourceTest {
    private static final int TASK=79;
    private static final String NUM=Integer.toString(TASK);
    private static final String MAIN_RECORD="bead";
    private static String mainActivity(boolean ready){
        return activityBlock(MAIN,MAIN_RECORD,NUM,ready).replace("Hist  #1:","Hist  #0:").replace("rootOfTask=false","rootOfTask=true");
    }
    private static String beforeActivities(){
        return originalActivities(TASK).replace("  * Task{abc #41",mainActivity(false)+"  * Task{abc #41");
    }
    private static String mainWindows(){
        return originalWindows(TASK).replace(TEMPLATE,MAIN).replace("Window{aaa","Window{baba").replace("ActivityRecord{ddd","ActivityRecord{"+MAIN_RECORD);
    }
    private static String mainActivities(){
        return originalActivities(TASK).replace(activityBlock(TEMPLATE,"ddd",NUM,true),mainActivity(true))
                .replace("topResumedActivity=ActivityRecord{ddd u0 "+TEMPLATE,"topResumedActivity=ActivityRecord{"+MAIN_RECORD+" u0 "+MAIN);
    }
    private static ReadingNewsSource.Observation read(ReadingNewsSource route,String w,String a){
        return route.inspect(w,a,PROFILE,RootReadOnlyAnimationGuardTest.G,3000,3050);
    }
    private static ReadingNewsSource anchored(){
        var route=new ReadingNewsSource(original(TASK,2000));
        assertNotNull(read(route,originalWindows(TASK),beforeActivities()).source());
        return route;
    }
    @Test public void onlyExactPreexistingMainBecomesListAndOrdinaryVisitRulesStayUnchanged(){
        var route=anchored();
        assertNull(read(route,mainWindows(),mainActivities()).source());
        route.entrySucceeded();
        var main=read(route,mainWindows(),mainActivities());
        assertEquals("NEWS_ROUTE_READY",main.gate());
        assertNotNull(main.source());route.confirmList(main.source());
        assertNull(read(route,mainWindows().replace("Window{baba","Window{babb"),mainActivities()).source());
        assertNull(read(route,originalWindows(TASK),beforeActivities()).source());
        assertNull(parseLiveVisit(mainWindows(),mainActivities(),PROFILE,RootReadOnlyAnimationGuardTest.G,
                original(TASK,2000),3000,3050).source());
    }
    @Test public void rejectsDifferentInstanceTaskObjectUidMissingOwnAndGuessedEntry(){
        var empty=new ReadingNewsSource(original(TASK,2000));
        assertNull(read(empty,originalWindows(TASK),originalActivities(TASK)).source());
        assertThrows(SecurityException.class,empty::entrySucceeded);
        assertNull(read(empty,originalWindows(TASK),beforeActivities().replace("rootOfTask=true","rootOfTask=false")).source());
        var route=anchored();route.entrySucceeded();
        assertNull(read(route,mainWindows().replace(MAIN_RECORD,"cafe"),mainActivities().replace(MAIN_RECORD,"cafe")).source());
        assertNull(read(route,mainWindows(),mainActivities().replace("Task{abc #"+TASK,"Task{bad #"+TASK)).source());
        assertNull(read(route,mainWindows().replace("mOwnerUid=10073","mOwnerUid=10074"),mainActivities()).source());
        assertNull(read(route,mainWindows().replace(RootReadOnlyAnimationGuardTest.ownWindow(),""),mainActivities()).source());
        assertNull(read(route,mainWindows(),mainActivities().replace("mode=fullscreen","mode=freeform")).source());
    }
    @Test public void waitOnlyForEstablishedTransitionWithTheCorrectIdentity(){
        var route=anchored();route.entrySucceeded();
        var turning=read(route,mainWindows().replace("HAS_DRAWN","DRAW_PENDING"),mainActivities());
        assertNull(turning.source());assertTrue(turning.waitable());
        var unknown=read(route,mainWindows().replace("mDrawState=HAS_DRAWN","missing=1"),mainActivities());
        assertNull(unknown.source());assertFalse(unknown.waitable());
        var foreign=read(route,mainWindows()+window("other.app/.Dialog","bad",true),mainActivities());
        assertNull(foreign.source());assertFalse(foreign.waitable());
    }
    private static String leavingRewards(boolean specialHeader){
        String old=activityBlock(TEMPLATE,"ddd",NUM,false).replace("state=STOPPED","state=STOPPING")
                .replace("finishing=false","finishing=true");
        if(specialHeader)old=old.replace(" t"+NUM+"}"," t"+NUM+" f}}");
        return mainActivities().replace(mainActivity(true),old+mainActivity(true));
    }
    private static void rejectWithoutWaiting(ReadingNewsSource route,String windows,String activities,String gate){
        var seen=read(route,windows,activities);
        assertNull(seen.gate(),seen.source());
        assertFalse(seen.gate(),seen.waitable());
        if(gate!=null)assertEquals(gate,seen.gate());
    }
    @Test public void entryPopIsWaitOnlyThenStableMainMustPassTheWholeUnchangedParser(){
        for(boolean tagged:new boolean[]{true,false}){
            var route=anchored();
            String w=tagged?mainWindows():mainWindows().replace("HAS_DRAWN","DRAW_PENDING");
            var activities=leavingRewards(tagged);
            rejectWithoutWaiting(route,w,activities,null);
            route.entrySucceeded();
            var pending=read(route,w,activities);
            assertEquals(tagged?FirstGate.ACTIVITY_HEADERS:FirstGate.READ_ONLY_STABILITY,pending.parsed().firstGate());
            assertTrue(pending.gate(),pending.waitable());
            assertNull(pending.source());assertFalse(route.isCheckedViewer(pending.source()));
            assertThrows(SecurityException.class,()->route.confirmList(pending.source()));
            assertThrows(SecurityException.class,route::articleSucceeded);
            var next=read(route,mainWindows(),mainActivities());
            assertEquals(Status.READY,next.parsed().status());assertFalse(next.waitable());
            route.confirmList(next.source());
            rejectWithoutWaiting(route,w,activities,"NEWS_ROUTE_MAIN_HISTORY_COUNT");
        }
    }
    @Test public void entryPopRejectsChangedWindowActivityOwnerTaskUnknownHeadersAndExtraHistory(){
        var route=anchored();route.entrySucceeded();
        String w=mainWindows(), a=leavingRewards(true);
        rejectWithoutWaiting(route,w,a.replace("ActivityRecord{ddd","ActivityRecord{def"),"NEWS_ROUTE_TEMPLATE_SUBSTITUTED");
        rejectWithoutWaiting(route,w.replace(MAIN_RECORD,"ace"),a.replace(MAIN_RECORD,"ace"),"NEWS_ROUTE_MAIN_SUBSTITUTED");
        rejectWithoutWaiting(route,w,a.replace("rootOfTask=true","rootOfTask=false"),"NEWS_ROUTE_MAIN_MEMBER");
        rejectWithoutWaiting(route,w,a.replace("finishing=true","finishing=false"),"NEWS_ROUTE_EXIT_LIFECYCLE");
        rejectWithoutWaiting(route,w,a.replace(" t41}"," t41 f}}"),"NEWS_ROUTE_ENTRY_OTHER_HEADER");
        rejectWithoutWaiting(route,w,a.replace("Task{abc #"+TASK,"Task{cab #"+TASK),"NEWS_ROUTE_TASK_OBJECT");
        rejectWithoutWaiting(route,w,a.replace(mainActivity(true),mainActivity(true)+activityBlock(BROWSER,"cc",NUM,false)),
                "NEWS_ROUTE_MAIN_HISTORY_COUNT");
        rejectWithoutWaiting(route,w+window("other.app/.Popup","abcdef",true),a,null);
        rejectWithoutWaiting(route,w.replace("mOwnerUid=10073","mOwnerUid=10074"),a,null);
        rejectWithoutWaiting(route,w.replace("mOwnerUid=10087","mOwnerUid=10088"),a,"NEWS_ROUTE_EXIT_WINDOWS");
        rejectWithoutWaiting(route,w.replace("mActivityRecord=ActivityRecord{"+MAIN_RECORD,"mActivityRecord=ActivityRecord{cafe"),
                a,"NEWS_ROUTE_EXIT_WINDOWS");
        rejectWithoutWaiting(route,w,a.replace(" f}}"," unknown}}"),"NEWS_ROUTE_TEMPLATE_SUBSTITUTED");
        rejectWithoutWaiting(route,w,mainActivities().replace(" t41}"," t41 f}}"),null);
    }
    @Test public void childArticleRequiresSuccessfulTapFullStrictChecksAndRetainedList(){
        String childW=childWindows(TASK).replace("Window{aaa u0 "+TEMPLATE,"Window{baba u0 "+MAIN)
                .replace("ActivityRecord{ddd u0 "+TEMPLATE,"ActivityRecord{"+MAIN_RECORD+" u0 "+MAIN)
                .replace("WindowStateAnimator{abc "+TEMPLATE,"WindowStateAnimator{abc "+MAIN);
        String childA=childActivities(TASK).replace(activityBlock(TEMPLATE,"ddd",NUM,false),mainActivity(false));
        var route=anchored();route.entrySucceeded();
        route.confirmList(read(route,mainWindows(),mainActivities()).source());
        assertNull(read(route,childW,childA).source());
        route.articleSucceeded();
        var viewer=read(route,childW,childA).source();
        assertEquals(Kind.BROWSER,viewer.kind());assertTrue(route.isCheckedViewer(viewer));
        assertNull(read(route,childW.replace("Window{baba","Window{babb"),childA).source());
        assertFalse(route.isCheckedViewer(viewer));
        assertNull(read(route,childW,childA.replace(MAIN_RECORD,"cafe")).source());
        route.confirmList(read(route,mainWindows(),mainActivities()).source());
        assertNull(read(route,childW,childA).source());
    }
    @Test public void viewerNeedsAQualifiedTreeOutsideRewardsAndFeedWithNoBlocker(){
        var noList=new NewsFeedModel.Frame(NewsFeedModel.Page.UNKNOWN,null,null,false,null,0,false,"NO_FEED_CARDS");
        var invalid=new NewsFeedModel.Frame(NewsFeedModel.Page.UNKNOWN,null,null,false,null,0,false,"TREE");
        var blocked=new NewsFeedModel.Frame(NewsFeedModel.Page.BLOCKED,null,null,false,null,0,false,"BLOCKER");
        var body=java.util.List.of(new NativeUiBoundPointsParser.Node(0,-1,true,true,"viewer","","",0,0,500,700));
        assertTrue(NewsAndroidPort.viewerContent(noList,body));
        assertFalse(NewsAndroidPort.viewerContent(noList,java.util.List.of()));
        assertFalse(NewsAndroidPort.viewerContent(invalid,body));assertFalse(NewsAndroidPort.viewerContent(blocked,body));
        for(String root:new String[]{"homePageWrapper","compose_home_root"})
            assertFalse(NewsAndroidPort.viewerContent(noList,java.util.List.of(new NativeUiBoundPointsParser.Node(0,-1,true,true,root,"","",0,0,500,700))));
    }
}
