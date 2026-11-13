package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.BingNavigationFixture.*;
public class BingNavigationSnapshotTest {
    @Test public void exactFocusedWindowAndMatchingResumedDrawnActivityAreReady(){
        var t=result(BingNavigationSnapshot.Kind.TEMPLATE,1000);assertEquals(BingNavigationSnapshot.Status.READY,t.status());
        assertEquals(BingNavigationSnapshot.Kind.TEMPLATE,t.ready().kind());assertTrue(t.ready().fresh(1100));
        assertEquals(BingNavigationSnapshot.Status.READY,result(BingNavigationSnapshot.Kind.BROWSER,1000).status());
    }
    @Test public void focusAloneCannotBorrowDrawnFromAnotherActivityBlock(){
        String c=BingNavigationSnapshot.TEMPLATE,w=windows(c,"aaa","ddd","15");
        String activities=activityBlock(c,"ddd","15",false)+activityBlock("other.app/.Other","fff","16",true)
            +" topResumedActivity=ActivityRecord{ddd u0 "+c+" t15}\n";
        assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,BingNavigationSnapshot.parse(w,activities,PROFILE,1000,1000).status());
    }
    @Test public void crossReadTransitionBetweenKnownBingActivitiesIsNotReady(){
        var r=BingNavigationSnapshot.parse(windows(BingNavigationSnapshot.BROWSER,"bbb","eee","15"),
            activities(BingNavigationSnapshot.TEMPLATE,"ddd","15",true),PROFILE,1000,1000);
        assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,r.status());assertNull(r.ready());
    }
    @Test public void unknownVisibleWindowCannotHideBeforeFirstParsedHeader(){
        String malformed=" Window #9 Window{bad9 u0 Unexpected Dialog broken\n mHasSurface=true\n isVisible=true\n isOnScreen=true\n";
        assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parse(malformed+windows(BingNavigationSnapshot.TEMPLATE,"aaa","ddd","15"),
            activities(BingNavigationSnapshot.TEMPLATE,"ddd","15",true),PROFILE,1000,1000).status());
    }
    @Test public void exactUnknownVisibleOverlayAndWrongStatusBarOwnerAreRejected(){
        String c=BingNavigationSnapshot.TEMPLATE;
        String overlay=window("evil.app/.Dialog","ffff",true);
        assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parse(windows(c,"aaa","ddd","15")+overlay,activities(c,"ddd","15",true),PROFILE,1000,1000).status());
        String wrong=windows(c,"aaa","ddd","15").replace("mOwnerUid=10049","mOwnerUid=10050");
        assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parse(wrong,activities(c,"ddd","15",true),PROFILE,1000,1000).status());
    }
    @Test public void retainedHiddenSurfaceIsNotAnActivePopup(){
        String c=BingNavigationSnapshot.TEMPLATE;
        String hidden=window("unknown/.Old","ffff",false).replace("mHasSurface=false","mHasSurface=true");
        assertEquals(BingNavigationSnapshot.Status.READY,BingNavigationSnapshot.parse(windows(c,"aaa","ddd","15")+hidden,activities(c,"ddd","15",true),PROFILE,1000,1000).status());
    }
    @Test public void anotherKnownVisibleBingWindowIsTransitionNotActionReady(){
        String c=BingNavigationSnapshot.BROWSER;
        assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,BingNavigationSnapshot.parse(windows(c,"bbb","eee","15")+window(BingNavigationSnapshot.TEMPLATE,"aaa",true),
            activities(c,"eee","15",true),PROFILE,1000,1000).status());
    }
    @Test public void missingSurfaceDrawOrUnknownComponentCannotBeReady(){
        String c=BingNavigationSnapshot.TEMPLATE;
        String noDraw=windows(c,"aaa","ddd","15").replace("mHasSurface=true isReadyForDisplay()=true","mHasSurface=true isReadyForDisplay()=false");
        assertEquals(BingNavigationSnapshot.Status.BING_TRANSITION,BingNavigationSnapshot.parse(noDraw,activities(c,"ddd","15",true),PROFILE,1000,1000).status());
        c="com.microsoft.bing/com.microsoft.sapphire.app.search.SearchActivity";
        assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parse(windows(c,"aaa","ddd","15"),activities(c,"ddd","15",true),PROFILE,1000,1000).status());
    }
    @Test public void foreignUidTaskOrUnknownActivityHeaderFailClosed(){
        String c=BingNavigationSnapshot.TEMPLATE;
        String wrong=windows(c,"aaa","ddd","15").replace("mOwnerUid=10073","mOwnerUid=10074");
        assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parse(wrong,activities(c,"ddd","15",true),PROFILE,1000,1000).status());
        String a=" * Hist  #9: ActivityRecord{unrecognized format\n"+activities(c,"ddd","15",true);
        assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parse(windows(c,"aaa","ddd","15"),a,PROFILE,1000,1000).status());
    }
    @Test public void staleOrOversizedMetadataDoesNotBecomeProof(){
        String c=BingNavigationSnapshot.TEMPLATE;
        assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parse(windows(c,"aaa","ddd","15"),activities(c,"ddd","15",true),PROFILE,1000,7000).status());
        assertFalse(result(BingNavigationSnapshot.Kind.TEMPLATE,1000).ready().fresh(6001));
        assertEquals(BingNavigationSnapshot.Status.UNKNOWN,BingNavigationSnapshot.parse("x".repeat(262145),"",PROFILE,1000,1000).status());
    }
}
