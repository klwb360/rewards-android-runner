package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.BingNavigationFixture.*;
/** Synthetic grammar only. No saved device metadata or old authorization. */
public class RecoveryNavigationSnapshotTest {
 static String w(){return windows(BingNavigationSnapshot.BROWSER,"bbb","eee","15")
  +window(BingNavigationSnapshot.TEMPLATE,"aaa",false)+"    mActivityRecord=ActivityRecord{ddd u0 "+BingNavigationSnapshot.TEMPLATE+" t15}\n";}
 static String a(){return activityBlock(BingNavigationSnapshot.BROWSER,"eee","15",true)
  +activityBlock(BingNavigationSnapshot.TEMPLATE,"ddd","15",false)+"    topResumedActivity=ActivityRecord{eee u0 "+BingNavigationSnapshot.BROWSER+" t15}\n";}
 static BingNavigationSnapshot.RecoveryResult parse(String w,String a){return BingNavigationSnapshot.parseReturnRead(w,a,PROFILE,null,1000,1000);}
 @Test public void currentBrowserAndStoppedSameTaskTemplateAreOpaqueBound(){var r=parse(w(),a());assertEquals(BingNavigationSnapshot.Status.READY,r.status());assertNotNull(r.source());
  assertTrue(r.source().browserMatches(result(BingNavigationSnapshot.Kind.BROWSER,1000).ready()));
  assertTrue(r.source().templateMatches(result(BingNavigationSnapshot.Kind.TEMPLATE,1000).ready()));
  assertFalse(r.source().templateMatches(result(BingNavigationSnapshot.Kind.TEMPLATE,1000,"abc","ddd","15").ready()));
  assertFalse(r.source().templateMatches(result(BingNavigationSnapshot.Kind.TEMPLATE,1000,"aaa","d11","15").ready()));}
 @Test public void stableBrowserCannotHideChangedStoppedTemplateAnchor(){var original=parse(w(),a()).source();
  assertTrue(original.sameSource(parse(w(),a()).source()));var changed=parse(w().replace("Window{aaa u0","Window{abc u0"),a()).source();
  assertNotNull(changed);assertTrue(original.browserMatches(changed.browser()));assertFalse(original.sameSource(changed));}
 @Test public void missingDuplicateOrForeignTemplateCannotMintSource(){
  assertNull(parse(w(),activities(BingNavigationSnapshot.BROWSER,"eee","15",true)).source());
  assertNull(parse(w(),a()+activityBlock(BingNavigationSnapshot.TEMPLATE,"aaa","15",false)).source());
  assertNull(parse(w(),a().replace("ddd u0 "+BingNavigationSnapshot.TEMPLATE+" t15","ddd u0 "+BingNavigationSnapshot.TEMPLATE+" t16")).source());}
 @Test public void stoppedInvisibleHistoryAndExactWindowBindingAreAllRequired(){
  for(String bad:new String[]{a().replace("state=STOPPED","state=PAUSED"),a().replace("inHistory=true","inHistory=false"),a().replace("state=STOPPED delayedResume=false finishing=false","state=STOPPED delayedResume=false finishing=true")}){
   assertEquals(BingNavigationSnapshot.FirstGate.RECOVERY_TEMPLATE_STATE,parse(w(),bad).firstGate());assertNull(parse(w(),bad).source());}
  assertEquals(BingNavigationSnapshot.FirstGate.RECOVERY_TEMPLATE_WINDOW_BINDING,parse(w().replace("mActivityRecord=","otherRecord="),a()).firstGate());
  assertNull(parse(w().replace("ActivityRecord{ddd","ActivityRecord{abc"),a()).source());}
 @Test public void alreadyTemplateIsReadReadyWithoutAnyReturnAuthority(){var r=parse(windows(BingNavigationSnapshot.TEMPLATE,"aaa","ddd","15"),activities(BingNavigationSnapshot.TEMPLATE,"ddd","15",true));
  assertEquals(BingNavigationSnapshot.Status.READY,r.status());assertEquals(BingNavigationSnapshot.FirstGate.RECOVERY_ALREADY_TEMPLATE,r.firstGate());assertNull(r.source());}
 @Test public void actualUnknownFocusConflictNeverBecomesPollableTransition(){String w=w().replace("mFocusedApp=ActivityRecord{eee u0 "+BingNavigationSnapshot.BROWSER,"mFocusedApp=ActivityRecord{ddd u0 "+BingNavigationSnapshot.TEMPLATE);
  var r=parse(w,a());assertEquals(BingNavigationSnapshot.Status.UNKNOWN,r.status());assertEquals(BingNavigationSnapshot.FirstGate.FOCUS_COMPONENT_CONFLICT,r.firstGate());}
 @Test public void knownCrossReadTransitionRetainsOriginalClassification(){var r=BingNavigationSnapshot.parse(windows(BingNavigationSnapshot.BROWSER,"bbb","eee","15"),activities(BingNavigationSnapshot.TEMPLATE,"ddd","15",true),PROFILE,1000,1000);
  assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r.status());assertEquals(BingNavigationSnapshot.FirstGate.KNOWN_TRANSITION,r.firstGate());}
 @Test public void diagnosticsContainNoTokensOrDocuments(){var r=parse(w(),a());String text=r.summary()+r.source().summary();assertFalse(text.contains("eee"));assertFalse(text.contains("ddd"));assertFalse(text.contains("bbb"));assertFalse(text.contains("mActivityRecord"));assertTrue(text.length()<500);}
}

