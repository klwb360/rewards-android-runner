package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.BingNavigationFixture.*;
/** Synthetic exact metadata only. No device read, TOP authority, BrowserReady or page fact. */
public class TimedTaskGuardTest {
    static String windows(){return RootReadOnlyAnimationGuardTest.w();}
    static String activities(){return RootReadOnlyAnimationGuardTest.a();}
    static BingNavigationSnapshot.Ready original(){return RootReadOnlyAnimationGuardTest.r(windows(),activities()).ready();}
    static BingNavigationSnapshot.TimedTaskGuard guard(BingNavigationSnapshot.Ready source,long now){
        var shape=source.profile().shape();
        String w=windows().replace("w=523 h=1000","w="+shape.width()+" h="+shape.height());
        return BingNavigationSnapshot.parseTimedTaskGuard(w,activities(),source.profile(),RootReadOnlyAnimationGuardTest.G,source,now,now);
    }
    static BingNavigationSnapshot.TimedTaskGuard parse(String w,String a){return BingNavigationSnapshot.parseTimedTaskGuard(w,a,PROFILE,RootReadOnlyAnimationGuardTest.G,original(),6000,6010);}
    static String browserWindows(){return "mTopFocusedDisplayId=0\n"
        +"mCurrentFocus=Window{bbb u0 "+BingNavigationSnapshot.BROWSER+"}\n"
        +"mFocusedApp=ActivityRecord{eee u0 "+BingNavigationSnapshot.BROWSER+" t15}\n"
        +window(BingNavigationSnapshot.TEMPLATE,"aaa",false)+"    mActivityRecord=ActivityRecord{ddd u0 "+BingNavigationSnapshot.TEMPLATE+" t15}\n"+RootReadOnlyAnimationGuardTest.animator(BingNavigationSnapshot.TEMPLATE,true)
        +window(BingNavigationSnapshot.BROWSER,"bbb",true)+"    mActivityRecord=ActivityRecord{eee u0 "+BingNavigationSnapshot.BROWSER+" t15}\n"+RootReadOnlyAnimationGuardTest.animator(BingNavigationSnapshot.BROWSER,false)
        +statusBar()+RootReadOnlyAnimationGuardTest.ownWindow();}
    static String browserActivities(){return activities().replace(activityBlock(BingNavigationSnapshot.TEMPLATE,"ddd","15",true),
        activityBlock(BingNavigationSnapshot.TEMPLATE,"ddd","15",false)+activityBlock(BingNavigationSnapshot.BROWSER,"eee","15",true));}
    @Test public void staleOriginalIsIdentityAnchorButOnlyFreshGuardMatches(){var p=parse(windows(),activities());assertTrue(p.matches(original(),6010));assertFalse(original().fresh(6010));assertFalse(p.matches(original(),11001));assertFalse(p.matches(original(),6009));}
    @Test public void noTopNullTopOrMultipleTopDoNotAuthorizeOrBlockThisNarrowTaskFact(){for(String a:new String[]{activities().replaceAll("(?m)^.*topResumedActivity=.*\\n", ""),activities().replace("topResumedActivity=ActivityRecord{ddd u0 "+BingNavigationSnapshot.TEMPLATE+" t15}","topResumedActivity=null"),activities()+"topResumedActivity=null\n"})assertTrue(parse(windows(),a).matches(original(),6010));}
    @Test public void browserOrProvenCrossLifecyclePresenceDoesNotRequireBrowserReady(){assertTrue(parse(browserWindows(),browserActivities()).matches(original(),6010));String w=browserWindows().replace("mFocusedApp=ActivityRecord{eee u0 "+BingNavigationSnapshot.BROWSER,"mFocusedApp=ActivityRecord{ddd u0 "+BingNavigationSnapshot.TEMPLATE);assertTrue(parse(w,browserActivities().replace("state=RESUMED","state=PAUSING")).matches(original(),6010));}
    @Test public void foreignFocusTaskProfileUidOrShapeReject(){for(String w:new String[]{windows().replace("mCurrentFocus=Window{aaa u0 "+BingNavigationSnapshot.TEMPLATE,"mCurrentFocus=Window{aaa u0 other/.Activity"),windows().replace("mFocusedApp=ActivityRecord{ddd u0 "+BingNavigationSnapshot.TEMPLATE+" t15}","mFocusedApp=ActivityRecord{ddd u0 "+BingNavigationSnapshot.TEMPLATE+" t16}"),windows().replace("mOwnerUid=10073","mOwnerUid=10074"),windows().replace("w=523 h=1000","w=523 h=999")})assertFalse(parse(w,activities()).matches(original(),6010));}
    @Test public void originalTemplateRecordWindowAndTaskCannotBeReplaced(){for(String w:new String[]{windows().replace("Window{aaa","Window{aab"),windows().replace("ActivityRecord{ddd","ActivityRecord{ddc"),windows().replace("rootTaskId=15","rootTaskId=16")})assertFalse(parse(w,activities()).matches(original(),6010));assertFalse(parse(windows(),activities().replace("  * Task{abc #15","  * Task{bbb #15")).matches(original(),6010));}
    @Test public void overlayVisibleOwnerUnknownHeadersAndMissingBindingReject(){for(String w:new String[]{windows()+window("other/.Dialog","1234",true),windows().replace(RootReadOnlyAnimationGuardTest.ownWindow(),RootReadOnlyAnimationGuardTest.ownWindow().replace("isVisible=false","isVisible=true")),windows()+"  Window #9 Window{not hex malformed}:\n",windows().replace("mActivityRecord=ActivityRecord{ddd","missing=ActivityRecord{ddd")})assertFalse(parse(w,activities()).matches(original(),6010));}
    @Test public void missingLifecycleTaskOrHiddenOwnerIsUnknownNotPermission(){for(String a:new String[]{activities().replace("state=RESUMED","state=DESTROYED"),activities().replace("inHistory=true","inHistory=false"),activities().replace("mode=fullscreen","mode=freeform"),activities().replace("A=10073:com.microsoft.bing U=0","A=10074:com.microsoft.bing U=0"),activities().replace(RootReadOnlyAnimationGuardTest.ownActivity(),"")})assertFalse(parse(windows(),a).matches(original(),6010));}
    @Test public void proofHasNoReadyPublicConstructorOrTokenSummary(){var p=parse(browserWindows(),browserActivities());assertTrue(p.matches(original(),6010));for(var c:BingNavigationSnapshot.TimedTaskGuard.class.getDeclaredConstructors())assertTrue(java.lang.reflect.Modifier.isPrivate(c.getModifiers()));assertFalse(p.summary().contains("aaa"));assertFalse(p.summary().contains("ddd"));assertTrue(p.summary().contains("globalTop=NOT_USED"));assertFalse(BingNavigationSnapshot.TimedTaskGuard.notObserved().matches(original(),6010));}
}
