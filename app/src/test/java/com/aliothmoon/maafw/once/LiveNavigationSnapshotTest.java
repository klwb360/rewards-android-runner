package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.BingNavigationFixture.*;

/** Synthetic source grammar only. No device metadata or captured document is reused. */
public class LiveNavigationSnapshotTest {
    static final String C=BingNavigationSnapshot.TEMPLATE;
    static final String B=BingNavigationSnapshot.BROWSER;
    static String originalWindows(int task){return RootReadOnlyAnimationGuardTest.w().replace("t15}","t"+task+"}").replace("rootTaskId=15","rootTaskId="+task);}
    static String originalActivities(int task){return RootReadOnlyAnimationGuardTest.a().replace("#15","#"+task).replace("t15}","t"+task+"}");}
    static BingNavigationSnapshot.Ready original(int task,long now){
        var result=BingNavigationSnapshot.parseRootReadOnly(originalWindows(task),originalActivities(task),PROFILE,RootReadOnlyAnimationGuardTest.G,now,now);
        assertEquals(result.firstGate()+"/"+result.readOnlyGate(),BingNavigationSnapshot.Status.READY,result.status());return result.ready();
    }
    static String childWindows(int task){
        return originalWindows(task).replace(C,B).replace("Window{aaa","Window{bbb").replace("ActivityRecord{ddd","ActivityRecord{eee")
                +window(C,"aaa",false).replace("rootTaskId=15","rootTaskId="+task)
                +"    mActivityRecord=ActivityRecord{ddd u0 "+C+" t"+task+"}\n"+RootReadOnlyAnimationGuardTest.animator(C,true);
    }
    static String childActivities(int task){
        String base=originalActivities(task).replace(C,B).replace("ActivityRecord{ddd","ActivityRecord{eee").replace("translucent=false sz=1","translucent=false sz=2");
        int own=base.indexOf("  * Task{abc #41");
        return base.substring(0,own)+activityBlock(C,"ddd",Integer.toString(task),false)+base.substring(own);
    }
    static BingNavigationSnapshot.LiveVisitResult visit(int task,long now,boolean child,BingNavigationSnapshot.Ready original){
        return BingNavigationSnapshot.parseLiveVisit(child?childWindows(task):originalWindows(task),
                child?childActivities(task):originalActivities(task),PROFILE,RootReadOnlyAnimationGuardTest.G,original,now,now);
    }
    @Test public void dynamicTaskOriginalAndChildAreBothBound(){
        for(int task:new int[]{15,79,205}){
            var original=original(task,1_000);var same=visit(task,5_000,false,original);var child=visit(task,5_000,true,original);
            assertEquals(BingNavigationSnapshot.LiveVisitKind.ORIGINAL_REWARDS,same.kind());
            assertEquals(child.gate().name(),BingNavigationSnapshot.LiveVisitKind.BOUND_CHILD,child.kind());
            assertTrue(child.source().matchesOriginal(original));assertEquals(Integer.toString(task),child.source().ready().task());
            assertFalse(child.source().sameSource(same.source()));
        }
    }
    @Test public void oldFixedTaskReturnProfileStillRejectsDynamicTask(){
        var original=original(79,1_000);
        assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parseTimedReturn(originalWindows(79),originalActivities(79),
                PROFILE,RootReadOnlyAnimationGuardTest.G,original,5_000,5_000).status());
    }
    @Test public void anotherBingTaskCannotBorrowTheOriginal(){
        assertNull(visit(80,5_000,true,original(79,1_000)).source());
    }
    @Test public void changedOriginalWindowAndMissingAnchorReject(){
        var original=original(79,1_000);
        assertNull(BingNavigationSnapshot.parseLiveVisit(originalWindows(79).replace("Window{aaa","Window{abc"),originalActivities(79),PROFILE,
                RootReadOnlyAnimationGuardTest.G,original,5_000,5_000).source());
        assertNull(BingNavigationSnapshot.parseLiveVisit(childWindows(79),childActivities(79).replace("state=STOPPED delayedResume=false finishing=false","state=PAUSED delayedResume=false finishing=false"),
                PROFILE,RootReadOnlyAnimationGuardTest.G,original,5_000,5_000).source());
    }
    @Test public void foreignOverlayAndTransitionCannotMintChild(){
        var original=original(79,1_000);
        assertNull(BingNavigationSnapshot.parseLiveVisit(childWindows(79)+window("other.app/.Popup","111",true),childActivities(79),PROFILE,
                RootReadOnlyAnimationGuardTest.G,original,5_000,5_000).source());
        assertNull(BingNavigationSnapshot.parseLiveVisit(childWindows(79).replace("HAS_DRAWN","DRAW_PENDING"),childActivities(79),PROFILE,
                RootReadOnlyAnimationGuardTest.G,original,5_000,5_000).source());
    }
    @Test public void childWindowRecreationCannotReusePreviousSource(){
        var original=original(79,1_000);var before=visit(79,5_000,true,original);
        var after=BingNavigationSnapshot.parseLiveVisit(childWindows(79).replace("Window{bbb","Window{bab"),childActivities(79),PROFILE,
                RootReadOnlyAnimationGuardTest.G,original,5_100,5_100);
        assertNotNull(after.source());assertFalse(before.source().sameSource(after.source()));
    }
}
