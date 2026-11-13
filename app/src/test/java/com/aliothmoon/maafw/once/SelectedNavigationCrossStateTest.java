package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;import static com.aliothmoon.maafw.once.BingNavigationFixture.*;
/** Synthetic original documents only. No rewriting to fabricate Ready and no device observation. */
public class SelectedNavigationCrossStateTest {
 static final String T=BingNavigationSnapshot.TEMPLATE,B=BingNavigationSnapshot.BROWSER;
 static BingNavigationSnapshot.Ready anchor(){return RootReadOnlyAnimationGuardTest.r(RootReadOnlyAnimationGuardTest.w(),RootReadOnlyAnimationGuardTest.a()).ready();}
 static String w(boolean windowBrowser,boolean appBrowser){return "mTopFocusedDisplayId=0\nmCurrentFocus=Window{"+(windowBrowser?"bbb":"aaa")+" u0 "+(windowBrowser?B:T)+"}\nmFocusedApp=ActivityRecord{"+(appBrowser?"ccc":"ddd")+" u0 "+(appBrowser?B:T)+" t15}\n"
  +window(T,"aaa",true)+"    mActivityRecord=ActivityRecord{ddd u0 "+T+" t15}\n"+RootReadOnlyAnimationGuardTest.animator(T,false)
  +window(B,"bbb",true)+"    mActivityRecord=ActivityRecord{ccc u0 "+B+" t15}\n"+RootReadOnlyAnimationGuardTest.animator(B,false)+statusBar()+RootReadOnlyAnimationGuardTest.ownWindow();}
 static String a(boolean browserTop){return "Display #0 (activities from top to bottom):\n"
  +"  * Task{abc #15 type=standard A=10073:com.microsoft.bing U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=2}\n"
  +"    isSleeping=false\n    topResumedActivity=ActivityRecord{"+(browserTop?"ccc":"ddd")+" u0 "+(browserTop?B:T)+" t15}\n"
  +activityBlock(T,"ddd","15",!browserTop)+activityBlock(B,"ccc","15",browserTop)
  +"  * Task{abc #41 type=standard A=10087:"+RootReadOnlyAnimationGuardTest.P+" U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}\n"
  +"    isSleeping=false\n"+RootReadOnlyAnimationGuardTest.ownActivity();}
 static BingNavigationSnapshot.Result read(String w,String a){return BingNavigationSnapshot.parseSelectedNavigation(w,a,PROFILE,RootReadOnlyAnimationGuardTest.G,anchor(),1000,1010);}
 @Test public void windowBrowserFocusedTemplateAndAmBrowserIsTransitionOnly(){var r=read(w(true,false),a(true));assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r.status());assertEquals(BingNavigationSnapshot.FirstGate.SELECTED_BOUND_CROSS_ACTIVITY,r.firstGate());assertNull(r.ready());}
 @Test public void reverseCrossStateAlsoNeverReady(){var r=read(w(false,true),a(false));assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r.status());assertNull(r.ready());}
 @Test public void wmBrowserButAmTemplateRequiresBothBoundRecords(){var r=read(w(true,true),a(false));assertEquals(BingNavigationSnapshot.FirstGate.SELECTED_BOUND_CROSS_ACTIVITY,r.firstGate());assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r.status());}
 @Test public void oldParserStillRejectsComponentConflict(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parseRootReadOnly(w(true,false),a(true),PROFILE,RootReadOnlyAnimationGuardTest.G,1000,1010).status());}
 @Test public void noOriginalAnchorCannotClassifyCrossState(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parseSelectedNavigation(w(true,false),a(true),PROFILE,RootReadOnlyAnimationGuardTest.G,null,1000,1010).status());}
 @Test public void originalTemplateWindowOrRecordReplacementIsUnknown(){for(String s:new String[]{w(true,false).replace("Window{aaa u0","Window{aab u0"),w(true,false).replace("mActivityRecord=ActivityRecord{ddd","mActivityRecord=ActivityRecord{ddd1")})assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(s,a(true)).status());}
 @Test public void missingDrawAndForeignOverlayAreUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false).replace("mDrawState=HAS_DRAWN","missingDraw"),a(true)).status());assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false)+window("InputMethod","adad",true),a(true)).status());}
 @Test public void uidTaskAndDisplayChangesCannotBecomeRetryable(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false).replace("mOwnerUid=10073","mOwnerUid=10074"),a(true)).status());assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false),a(true).replace("#15 type=standard A=10073","#16 type=standard A=10073")).status());assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false).replace("mTopFocusedDisplayId=0","mTopFocusedDisplayId=1"),a(true)).status());}
 @Test public void missingOrContradictoryLifecycleFieldsRemainUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false),a(true).replace("inHistory=true","missingHistory")).status());assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false),a(true).replace("reportedDrawn=true","reportedDrawn=true reportedDrawn=false")).status());}
 @Test public void unknownComponentAndDuplicateTopRemainUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false).replace(B,"com.other/.Page"),a(true)).status());assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false),a(true)+"topResumedActivity=null\n").status());}
 @Test public void malformedTaskOrDepartedOwnChainDominatesCross(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false),a(true).replace("  * Task{abc #15","  NotTask{abc #15")).status());assertEquals(BingNavigationSnapshot.Status.UNKNOWN,read(w(true,false).replace("mDrawState=NO_SURFACE","missingOwnDraw"),a(true)).status());}
 @Test public void ordinaryFreshTemplateStillUsesExactReadyBranch(){String w=RootReadOnlyAnimationGuardTest.w(),a=RootReadOnlyAnimationGuardTest.a();var r=read(w,a);assertEquals(BingNavigationSnapshot.Status.READY,r.status());assertTrue(anchor().sameSource(r.ready()));}
}
