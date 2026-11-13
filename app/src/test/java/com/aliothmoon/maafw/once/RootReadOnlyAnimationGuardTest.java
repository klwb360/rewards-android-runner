package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.BingNavigationFixture.*;
/** Synthetic field/indentation fixtures from the owner's stationary -a schema, not capture-time evidence. */
public class RootReadOnlyAnimationGuardTest {
    static final String C=BingNavigationSnapshot.TEMPLATE;
    static final String P="com.aliothmoon.maafw.productrewardsworking.capturereview";
    static final String O=P+"/com.aliothmoon.maafw.once.DormantCaptureConfirmationActivity";
    static final BingNavigationSnapshot.ReadOnlyGuard G=new BingNavigationSnapshot.ReadOnlyGuard(10087,41,O);
    static String animator(String c,boolean hidden){return "    WindowStateAnimator{abc "+c+"}:\n"
        +"      mDrawState="+(hidden?"NO_SURFACE":"HAS_DRAWN")+"       mLastHidden="+hidden+"\n"
        +"      mEnterAnimationPending=false      mSystemDecorRect=[0,0][0,0]\n";}
    static String ownWindow(){return window(O,"eee",false).replace("rootTaskId=15","rootTaskId=41")
        .replace("mOwnerUid=10073","mOwnerUid=10087").replace("package=com.microsoft.bing","package="+P)
        +"    mActivityRecord=ActivityRecord{fff u0 "+O+" t41}\n"+animator(O,true);}
    static String w(){return windows(C,"aaa","ddd","15").replace(statusBar(),
        "    mActivityRecord=ActivityRecord{ddd u0 "+C+" t15}\n"+animator(C,false)+statusBar())+ownWindow();}
    static String ownActivity(){return activityBlock(O,"fff","41",false)
        .replace("A=10073:com.microsoft.bing","A=10087:"+P);}
    static String a(){return "Display #0 (activities from top to bottom):\n"
        +"  * Task{abc #15 type=standard A=10073:com.microsoft.bing U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}\n"
        +"    isSleeping=false\n    topResumedActivity=ActivityRecord{ddd u0 "+C+" t15}\n"
        +activityBlock(C,"ddd","15",true)
        +"  * Task{abc #41 type=standard A=10087:"+P+" U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}\n"
        +"    isSleeping=false\n"+ownActivity();}
    static BingNavigationSnapshot.Result r(String w,String a){return BingNavigationSnapshot.parseRootReadOnly(w,a,PROFILE,G,1000,1010);}
    static String anim(int spaces,String type){String i=" ".repeat(spaces);return i+"ContainerAnimator:\n"+i+"  mLeash=Surface(test) mAnimationType="+type+"\n"+i+"  Animation: fixture\n";}
    @Test public void exactIdleTargetAndDepartedOwnerAreMetadataReady(){assertEquals(BingNavigationSnapshot.Status.READY,r(w(),a()).status());}
    @Test public void legacyParserStillAcceptsItsOriginalFixture(){assertEquals(BingNavigationSnapshot.Status.READY,result(BingNavigationSnapshot.Kind.TEMPLATE,1000).status());}
    @Test public void legacyParserDoesNotApplyNewDrawPolicy(){assertEquals(BingNavigationSnapshot.Status.READY,BingNavigationSnapshot.parse(w().replace("HAS_DRAWN","DRAW_PENDING"),a(),PROFILE,1000,1010).status());}
    @Test public void drawPendingRejects(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w().replace("HAS_DRAWN","DRAW_PENDING"),a()).status());}
    @Test public void targetLastHiddenRejects(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w().replace("mLastHidden=false","mLastHidden=true"),a()).status());}
    @Test public void enterPendingRejects(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w().replaceFirst("mEnterAnimationPending=false","mEnterAnimationPending=true"),a()).status());}
    @Test public void conditionalAlphaOmissionIsAllowed(){assertEquals(BingNavigationSnapshot.Status.READY,r(w(),a()).status());}
    @Test public void explicitAlphaChangeRejects(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w().replace("mDrawState=HAS_DRAWN","mShownAlpha=0.5 mAlpha=1.0 mLastAlpha=1.0\n      mDrawState=HAS_DRAWN"),a()).status());}
    @Test public void nonfiniteAlphaUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w().replace("mDrawState=HAS_DRAWN","mShownAlpha=NaN\n      mDrawState=HAS_DRAWN"),a()).status());}
    @Test public void duplicateDrawStateUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w().replace("mDrawState=HAS_DRAWN","mDrawState=HAS_DRAWN mDrawState=HAS_DRAWN"),a()).status());}
    @Test public void missingRequiredDrawFieldsUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w().replace("mDrawState=HAS_DRAWN", "noDrawState"),a()).status());}
    @Test public void animationDirectionAloneIsNotActive(){assertEquals(BingNavigationSnapshot.Status.READY,r(w().replace("mDrawState=HAS_DRAWN","mAnimationIsEntrance=true\n      mDrawState=HAS_DRAWN"),a()).status());}
    @Test public void targetWindowAnimatorRejects(){for(String type:new String[]{"app_transition","window_animation","recents_animation","screen_rotation","insets_animation"})assertEquals(type,BingNavigationSnapshot.Status.BING_TRANSITION,r(w().replace("    WindowStateAnimator{abc "+C,anim(4,type)+"    WindowStateAnimator{abc "+C),a()).status());}
    @Test public void ownWindowAnimatorRejectsEvenWithSurfaceGone(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w()+anim(4,"app_transition"),a()).status());}
    @Test public void targetActivityAnimatorRejects(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w(),a().replace("      rootOfTask=false",anim(6,"app_transition")+"      rootOfTask=false")).status());}
    @Test public void ownActivityAnimatorRejects(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w(),a()+anim(6,"app_transition")).status());}
    @Test public void targetTaskAnimatorRejects(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w(),a().replaceFirst("    isSleeping=false",anim(4,"recents_animation")+"    isSleeping=false")).status());}
    @Test public void ownTaskAnimatorRejects(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w(),a().replace("    isSleeping=false\n"+ownActivity(),anim(4,"app_transition")+"    isSleeping=false\n"+ownActivity())).status());}
    @Test public void malformedAnimatorUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w()+"    ContainerAnimator:\n      broken\n",a()).status());}
    @Test public void statusbarInsetsControlIsNotGlobalVeto(){assertEquals(BingNavigationSnapshot.Status.READY,r(w().replace(ownWindow(),anim(4,"insets_animation")+ownWindow()),a()).status());}
    @Test public void hiddenImeInsetsControlIsNotGlobalVeto(){String ime=window("InputMethod","abab",false)+anim(4,"insets_animation");assertEquals(BingNavigationSnapshot.Status.READY,r(w()+ime,a()).status());}
    @Test public void visibleUntrustedImeRemainsUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w()+window("InputMethod","abab",true)+anim(4,"insets_animation"),a()).status());}
    @Test public void ownStillVisibleIsTransitionNotReady(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w().replace(ownWindow(),ownWindow().replace("isVisible=false","isVisible=true")),a()).status());}
    @Test public void ownActivityNotStoppedIsTransition(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w(),a().replace("state=STOPPED","state=STOPPING")).status());}
    @Test public void foreignOwnUidCannotBecomeTransitionException(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w().replace("mOwnerUid=10087","mOwnerUid=10088"),a()).status());}
    @Test public void wrongWindowActivityBindingUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w().replace("mActivityRecord=ActivityRecord{ddd","mActivityRecord=ActivityRecord{bbb"),a()).status());}
    @Test public void noTaskCannotBorrowSummaryIdentity(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w(),a().replace("  * Task{abc #15", "  NotTask{abc #15")).status());}
    @Test public void differentDisplayUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w(),a().replace("Display #0", "Display #1")).status());}
    @Test public void nestedTaskUnsupported(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w(),a().replace("  * Task{abc #15", "    * Task{abc #15")).status());}
    @Test public void noOwnWindowUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w().replace(ownWindow(),""),a()).status());}
    @Test public void enteringStartingWindowRejects(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w(),a().replace("      rootOfTask=false","      startingData=SplashScreenStartingData{abc}\n      rootOfTask=false")).status());}
    @Test public void wrongOwnerComponentCannotIssueGuard(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parseRootReadOnly(w(),a(),PROFILE,new BingNavigationSnapshot.ReadOnlyGuard(10087,41,"other/.Activity"),1000,1010).status());}
    @Test public void stalePairAndSameSourceSemanticsUnchanged(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parseRootReadOnly(w(),a(),PROFILE,G,1000,7000).status());assertTrue(r(w(),a()).ready().sameSource(r(w(),a()).ready()));}
    @Test public void targetTaskTokenMustMatchHist(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w(),a().replace("  * Task{abc #15","  * Task{ffff #15")).status());}
    @Test public void targetTaskUidMismatchUnknown(){assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w(),a().replace("A=10073:com.microsoft.bing U=0", "A=10074:com.microsoft.bing U=0")).status());}
    @Test public void duplicateTaskChainUnknown(){String b=a().substring(a().indexOf("  * Task{abc #41"));assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r(w(),a()+b).status());}
    @Test public void unrelatedTaskAnimatorNotGlobalVeto(){String other="  * Task{bbbb #50 type=standard A=10099:other.app U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=0}\n"+anim(4,"recents_animation");assertEquals(BingNavigationSnapshot.Status.READY,r(w(),a()+other).status());}
    @Test public void explicitRotationPendingRejects(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w().replace("    WindowStateAnimator{abc "+C,"    mForceSeamlesslyRotate=true seamlesslyRotate: pending=Rotator\n    WindowStateAnimator{abc "+C),a()).status());}
    @Test public void noRotationDefaultIsNotGuessedFromOtherWindow(){assertEquals(BingNavigationSnapshot.Status.READY,r(w().replace(ownWindow(),"    mForceSeamlesslyRotate=true\n"+ownWindow()),a()).status());}
    @Test public void missingFieldDominatesKnownTransition(){var result=r(w().replace("HAS_DRAWN","DRAW_PENDING").replace("mDrawState=NO_SURFACE", "missingDraw"),a());assertEquals(BingNavigationSnapshot.Status.UNKNOWN,result.status());assertEquals(BingNavigationSnapshot.ReadOnlyGate.OWN_WINDOW,result.readOnlyGate());}
    @Test public void safeGatePreservesSpecificFailure(){var result=r(w().replace("HAS_DRAWN","DRAW_PENDING"),a());assertEquals(BingNavigationSnapshot.ReadOnlyGate.TARGET_DRAW,result.readOnlyGate());assertNull(result.ready());}
    @Test public void safeReadyGateDoesNotClaimPixels(){assertEquals(BingNavigationSnapshot.ReadOnlyGate.METADATA_STABLE_ONLY,r(w(),a()).readOnlyGate());}
    @Test public void delayedAnimatorStillTransition(){assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r(w().replace("    WindowStateAnimator{abc "+C,"    mAnimationStartDelayed=true\n    WindowStateAnimator{abc "+C),a()).status());}
}