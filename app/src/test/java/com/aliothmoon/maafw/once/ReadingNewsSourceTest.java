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
    private static String viewerWindows(String component){
        return childWindows(TASK).replace("Window{aaa u0 "+TEMPLATE,"Window{baba u0 "+MAIN)
                .replace("ActivityRecord{ddd u0 "+TEMPLATE,"ActivityRecord{"+MAIN_RECORD+" u0 "+MAIN)
                .replace("WindowStateAnimator{abc "+TEMPLATE,"WindowStateAnimator{abc "+MAIN).replace(BROWSER,component);
    }
    private static String viewerActivities(String component){
        return childActivities(TASK).replace(activityBlock(TEMPLATE,"ddd",NUM,false),mainActivity(false)).replace(BROWSER,component);
    }
    private static String passiveExit(String component,boolean tagged){
        return leavingRewards(tagged).replace("ActivityRecord{ddd u0 "+TEMPLATE,"ActivityRecord{eee u0 "+component);
    }
    private static String oldViewerWindow(String component,String token){
        return window(component,token,false).replace("rootTaskId=15","rootTaskId="+TASK)
                +"    mActivityRecord=ActivityRecord{eee u0 "+component+" t"+TASK+"}\n"
                +RootReadOnlyAnimationGuardTest.animator(component,true);
    }
    private static ReadingNewsSource enteredList(){
        var route=anchored();route.entrySucceeded();route.confirmList(read(route,mainWindows(),mainActivities()).source());
        route.articleSucceeded();return route;
    }
    @Test public void oneSuccessfulReturnOnlyWaitsForTheBoundArticleToLeaveBeforeAcceptingStableList(){
        for(String component:new String[]{TEMPLATE,BROWSER}){
            var route=enteredList();String vw=viewerWindows(component),va=viewerActivities(component);
            Ready viewer=read(route,vw,va).source();assertNotNull(viewer);
            assertThrows(SecurityException.class,()->route.backSucceeded(viewer,3000));
            route.confirmArticle(viewer);
            rejectWithoutWaiting(route,mainWindows(),passiveExit(component,true),"NEWS_ROUTE_MAIN_HISTORY_COUNT");
            var beforeBack=read(route,vw,va).source();route.confirmArticle(beforeBack);
            route.backSucceeded(beforeBack,3000);
            var stillViewer=read(route,vw,va);
            assertNull(stillViewer.source());assertTrue(stillViewer.waitable());
            for(boolean tagged:new boolean[]{true,false}){
                var waiting=read(route,mainWindows()+oldViewerWindow(component,"bbb"),passiveExit(component,tagged));
                assertEquals(tagged?FirstGate.ACTIVITY_HEADERS:FirstGate.READY,waiting.parsed().firstGate());
                assertEquals("NEWS_ROUTE_WAIT_ARTICLE_EXIT",waiting.gate());assertNull(waiting.source());assertTrue(waiting.waitable());
                assertThrows(SecurityException.class,()->route.confirmArticle(viewer));
                assertThrows(SecurityException.class,()->route.backSucceeded(viewer,3100));
            }
            var expired=route.inspect(mainWindows(),passiveExit(component,true),PROFILE,RootReadOnlyAnimationGuardTest.G,
                    3000+ReadingNewsSource.RETURN_WAIT_MS,3050+ReadingNewsSource.RETURN_WAIT_MS);
            assertNull(expired.source());assertFalse(expired.waitable());
            var stable=read(route,mainWindows(),mainActivities());
            assertNotNull(stable.source());assertFalse(stable.waitable());route.confirmList(stable.source());
            rejectWithoutWaiting(route,mainWindows(),passiveExit(component,true),"NEWS_ROUTE_MAIN_HISTORY_COUNT");
        }
    }
    @Test public void returnCannotBorrowAnotherViewerMainTaskOrWindow(){
        var route=enteredList();String vw=viewerWindows(TEMPLATE),va=viewerActivities(TEMPLATE);
        var viewer=read(route,vw,va).source();route.confirmArticle(viewer);
        rejectWithoutWaiting(route,vw.replace("Window{bbb","Window{bcb"),va,"NEWS_ROUTE_ARTICLE_SUBSTITUTED");
        assertThrows(SecurityException.class,()->route.backSucceeded(viewer,3000));
        rejectWithoutWaiting(route,viewerWindows(BROWSER),viewerActivities(BROWSER),"NEWS_ROUTE_ARTICLE_SUBSTITUTED");
        var again=read(route,vw,va).source();route.confirmArticle(again);route.backSucceeded(again,3000);
        String w=mainWindows(),a=passiveExit(TEMPLATE,true);
        rejectWithoutWaiting(route,w,a.replace("ActivityRecord{eee","ActivityRecord{fefe"),"NEWS_ROUTE_ARTICLE_SUBSTITUTED");
        rejectWithoutWaiting(route,w,passiveExit(BROWSER,true),"NEWS_ROUTE_ARTICLE_SUBSTITUTED");
        rejectWithoutWaiting(route,w.replace("Window{baba","Window{fade"),a,"NEWS_ROUTE_MAIN_WINDOW_CHANGED");
        rejectWithoutWaiting(route,w+oldViewerWindow(TEMPLATE,"cafe"),a,"NEWS_ROUTE_EXIT_WINDOWS");
        rejectWithoutWaiting(route,w,a.replace("Task{abc #"+TASK,"Task{fab #"+TASK),"NEWS_ROUTE_TASK_OBJECT");
        rejectWithoutWaiting(route,w,a.replace(" t41}"," t41 f}}"),"NEWS_ROUTE_RETURN_OTHER_HEADER");
        rejectWithoutWaiting(route,w,a.replace("finishing=true","finishing=false"),"NEWS_ROUTE_EXIT_LIFECYCLE");
        rejectWithoutWaiting(route,w+window("other.app/.Overlay","decaf",true),a,null);
        rejectWithoutWaiting(route,w.replace("mOwnerUid=10073","mOwnerUid=10099"),a,null);
        rejectWithoutWaiting(route,w,a.replace(mainActivity(true),mainActivity(true)+activityBlock(BROWSER,"cc",NUM,false)),
                "NEWS_ROUTE_MAIN_HISTORY_COUNT");
    }
    @Test public void theSameMainByItselfNeverProvesAnArticleOrAuthorizesBack(){
        var route=enteredList();var main=read(route,mainWindows(),mainActivities()).source();
        assertThrows(SecurityException.class,()->route.confirmArticle(main));
        assertThrows(SecurityException.class,()->route.backSucceeded(main,3000));
        route.confirmList(read(route,mainWindows(),mainActivities()).source());
    }
    @Test public void anUnfocusedCoverOnlyWaitsThenTheUnaffectedStrictParserMustPass(){
        for(boolean early:new boolean[]{false,true}){
            var route=enteredList();String w=viewerWindows(TEMPLATE),a=viewerActivities(TEMPLATE);
            if(early)route.confirmArticle(read(route,w,a).source());
            String cover=window("FloatingSurface","f00d",true);
            var pending=read(route,w+cover,a);
            assertEquals(FirstGate.VISIBLE_UNKNOWN_WINDOW,pending.parsed().firstGate());
            assertEquals(ReadingNewsSource.WAIT_ARTICLE_OVERLAY,pending.gate());
            assertNull(pending.source());assertTrue(pending.waitable());
            assertThrows(SecurityException.class,()->route.confirmArticle(pending.source()));
            rejectWithoutWaiting(route,w+cover.replace("FloatingSurface","other.app/.Dialog"),a,null);
            rejectWithoutWaiting(route,w+cover.replace("ty=BASE_APPLICATION","ty=SYSTEM_DIALOG"),a,null);
            rejectWithoutWaiting(route,w+cover.replace("package=com.microsoft.bing","package=other.app"),a,null);
            var clean=read(route,w,a);
            assertEquals(FirstGate.READY,clean.parsed().firstGate());assertFalse(clean.waitable());
            route.confirmArticle(clean.source());route.backSucceeded(clean.source(),3050);
            assertThrows(SecurityException.class,()->route.backSucceeded(clean.source(),3050));
        }
    }
    private static String attachedPanel(String component){
        return window("Panel:"+component,"f00d",true).replace("rootTaskId=15","rootTaskId="+TASK)
                .replace("ty=BASE_APPLICATION","ty=APPLICATION_PANEL")
                .replace("    Requested","      fl=NOT_FOCUSABLE HARDWARE_ACCELERATED\n    Requested")
                +"    mParentWindow=Window{bbb u0 "+component+"} mLayoutAttached=true\n"
                +"    mActivityRecord=ActivityRecord{eee u0 "+component+" t"+TASK+"}\n";
    }
    @Test public void attachedBingPanelCanOnlyWaitForTheOriginalUncoveredArticle(){
        for(String component:new String[]{TEMPLATE,BROWSER}){
            var route=enteredList();
            String w=viewerWindows(component),a=viewerActivities(component);
            var original=read(route,w,a).source();
            route.confirmArticle(original);
            var pending=read(route,w+attachedPanel(component),a);
            assertEquals(FirstGate.VISIBLE_UNKNOWN_WINDOW,pending.parsed().firstGate());
            assertEquals(ReadingNewsSource.WAIT_ARTICLE_OVERLAY,pending.gate());
            assertTrue(pending.waitable());assertNull(pending.source());
            assertThrows(SecurityException.class,()->route.backSucceeded(original,3050));
            var clear=read(route,w,a);
            assertEquals(FirstGate.READY,clear.parsed().firstGate());
            assertTrue(original.sameLiveWindow(clear.source()));route.confirmArticle(clear.source());
            route.backSucceeded(clear.source(),3050);
            assertThrows(SecurityException.class,()->route.backSucceeded(clear.source(),3100));
        }
    }
    @Test public void foreignDetachedFocusableOrAmbiguousArticlePanelsEndTheVisit(){
        var route=enteredList();
        String w=viewerWindows(TEMPLATE),a=viewerActivities(TEMPLATE),panel=attachedPanel(TEMPLATE);
        route.confirmArticle(read(route,w,a).source());
        for(String invalid:new String[]{
                panel.replace("Panel:"+TEMPLATE,"Panel:"+MAIN),panel.replace("mOwnerUid=10073","mOwnerUid=10074"),
                panel.replace("package=com.microsoft.bing","package=other.app"),panel.replace("appop=NONE","appop=OTHER"),
                panel.replace("mDisplayId=0","mDisplayId=1"),panel.replace("rootTaskId="+TASK,"rootTaskId=23"),
                panel.replace("mParentWindow=Window{bbb","mParentWindow=Window{bbc"),panel.replace("mLayoutAttached=true","mLayoutAttached=false"),
                panel.replace("u0 "+TEMPLATE+"} mLayoutAttached","u0 "+MAIN+"} mLayoutAttached"),
                panel.replace("ActivityRecord{eee","ActivityRecord{eed"),panel.replace(" t"+TASK+"}"," t23}"),
                panel.replace("u0 "+TEMPLATE+" t"+TASK,"u0 "+MAIN+" t"+TASK),
                panel.replace("ty=APPLICATION_PANEL","ty=SYSTEM_DIALOG"),panel.replace("NOT_FOCUSABLE","NOT_TOUCH_MODAL"),
                panel+"    mParentWindow=Window{bbb u0 "+TEMPLATE+"} mLayoutAttached=true\n"}){
            assertNotEquals(panel,invalid);
            rejectWithoutWaiting(route,w+invalid,a,"NEWS_ROUTE_ARTICLE_OVERLAY_UNTRUSTED");
        }
        rejectWithoutWaiting(route,w+panel+window("other.app/.Dialog","cade",true),a,null);
    }
    @Test public void aLateVisibleCoverCannotHideChangedFocusActivityTaskOrMissingList(){
        var route=enteredList();String w=viewerWindows(TEMPLATE),a=viewerActivities(TEMPLATE);
        route.confirmArticle(read(route,w,a).source());
        String cover=window("FloatingSurface","f00d",true);
        rejectWithoutWaiting(route,w.replace("Window{bbb","Window{bbc")+cover,a,"NEWS_ROUTE_ARTICLE_SUBSTITUTED");
        rejectWithoutWaiting(route,w+cover,a.replace("ActivityRecord{eee","ActivityRecord{def"),null);
        rejectWithoutWaiting(route,w+cover,a.replace("Task{abc #"+TASK,"Task{cab #"+TASK),"NEWS_ROUTE_TASK_OBJECT");
        rejectWithoutWaiting(route,w.replace("Window{baba","Window{abab")+cover,a,"NEWS_ROUTE_ARTICLE_STACK");
    }
}
