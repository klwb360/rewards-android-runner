package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;

public class AlphabetSearchDeviceTest {
    private static final String IME = "example.keyboard";
    private static final BingNavigationSnapshot.Profile PROFILE = new BingNavigationSnapshot.Profile(
            10501, 10502, new BingNavigationSnapshot.Shape(1440,2560,0));

    @Test public void backOnlyAcceptsANewBingResultInstanceOnTheOriginalTask() {
        var origin = new BingForegroundSnapshot.Snapshot("a1","a2",BingNavigationSnapshot.TEMPLATE,"7");
        var editor = new BingForegroundSnapshot.Snapshot("b1","b2",AlphabetSearchDevice.EDITOR,"7");
        for (String destination : new String[]{AlphabetSearchDevice.RESULTS,BingNavigationSnapshot.BROWSER})
            assertTrue(AlphabetSearchDevice.boundResult(origin,editor,new BingForegroundSnapshot.Snapshot("c1","c2",destination,"7")));
        assertFalse(AlphabetSearchDevice.boundResult(origin,editor,origin));
        assertFalse(AlphabetSearchDevice.boundResult(origin,editor,editor));
        assertFalse(AlphabetSearchDevice.boundResult(origin,editor,new BingForegroundSnapshot.Snapshot("c1","c2",BingNavigationSnapshot.BROWSER,"8")));
        assertFalse(AlphabetSearchDevice.boundResult(origin,editor,new BingForegroundSnapshot.Snapshot("c1","b2",BingNavigationSnapshot.BROWSER,"7")));
        assertFalse(AlphabetSearchDevice.boundResult(origin,editor,new BingForegroundSnapshot.Snapshot("a1","c2",BingNavigationSnapshot.BROWSER,"7")));
        assertFalse(AlphabetSearchDevice.boundResult(origin,editor,new BingForegroundSnapshot.Snapshot("c1","c2",BingNavigationSnapshot.MAIN,"7")));
    }

    @Test public void aTemporaryHostViewCanOnlyWaitForTheExactEditableFocusedField() {
        var wait = AlphabetSearchDevice.EditorFocus.WAIT;
        var reject = AlphabetSearchDevice.EditorFocus.REJECT;
        var field = AlphabetSearchDevice.EditorFocus.FIELD;
        assertEquals(wait, AlphabetSearchDevice.classifyEditorFocus(false,false,"",false,false));
        assertEquals(wait, AlphabetSearchDevice.classifyEditorFocus(true,true,"android.view.View",false,false));
        assertEquals(field, AlphabetSearchDevice.classifyEditorFocus(true,true,"android.widget.EditText",true,true));
        assertEquals(reject, AlphabetSearchDevice.classifyEditorFocus(true,false,"android.view.View",false,false));
        assertEquals(reject, AlphabetSearchDevice.classifyEditorFocus(true,false,"android.widget.EditText",true,true));
        assertEquals(reject, AlphabetSearchDevice.classifyEditorFocus(true,true,"android.widget.Button",true,false));
        assertEquals(reject, AlphabetSearchDevice.classifyEditorFocus(true,true,"android.widget.EditText",false,true));
    }

    @Test public void exactImeOwnerComesFromOneRowForTheSelectedPackage() {
        String rows = "package:example.keyboard.addon uid:10510\npackage:example.keyboard uid:10031\n";
        assertEquals(10031, AlphabetSearchDevice.exactPackageUid(rows, "example.keyboard"));
        for (String invalid : new String[]{rows + rows, "package:example.keyboard.addon uid:10510",
                "package:example.keyboard uid:broken", ""}) {
            assertThrows(SecurityException.class, () -> AlphabetSearchDevice.exactPackageUid(invalid,"example.keyboard"));
        }
    }

    private static String activities(String component) {
        String id = "ActivityRecord{ae1 u0 "+component+" t7}";
        return "topResumedActivity="+id+"\n  * Hist #2: "+id+"\n    state=RESUMED finishing=false inHistory=true\n";
    }

    private static String foreground(String component) {
        String id = "ActivityRecord{ae1 u0 "+component+" t7}";
        return "mTopFocusedDisplayId=0\nmCurrentFocus=Window{a1 u0 "+component+"}\nmFocusedApp="+id+"\n"
                +"  Window #1 Window{a1 u0 "+component+"}:\n    mOwnerUid=10501 package=com.microsoft.bing appop=NONE\n"
                +"    mDisplayId=0 rootTaskId=7\n    Requested w=1440 h=2560\n    mAttrs={ty=BASE_APPLICATION}\n"
                +"    mActivityRecord="+id+"\n    mHasSurface=true isVisible=true isOnScreen=true isReadyForDisplay()=true\n";
    }

    private static String keyboard(String owner, String type) {
        return "  Window #0 Window{bee u0 InputMethod}:\n    mOwnerUid=10432 package="+owner+" appop=NONE\n"
                +"    mDisplayId=0 rootTaskId=1\n    mAttrs={ty="+type+"}\n    isVisible=true isOnScreen=true\n";
    }

    private static boolean accepted(String windows, String activities) {
        try {
            AlphabetSearchDevice.parseSource(windows,activities,PROFILE,"7",IME,10432);
            return true;
        } catch (SecurityException rejected) { return false; }
    }

    @Test public void visibleKeyboardIsAllowedOnlyForTheExactEditorAndConfiguredOwner() {
        String activity=activities(AlphabetSearchDevice.EDITOR);
        String base=foreground(AlphabetSearchDevice.EDITOR);
        assertTrue(accepted(base+keyboard(IME,"INPUT_METHOD"),activity));
        assertFalse(accepted(base+keyboard("example.foreign","INPUT_METHOD"),activity));
        assertFalse(accepted(base+keyboard(IME,"APPLICATION_OVERLAY"),activity));
        assertFalse(accepted((base+keyboard(IME,"INPUT_METHOD")).replace("mOwnerUid=10432","mOwnerUid=10433"),activity));
        assertFalse(accepted(foreground(AlphabetSearchDevice.RESULTS)+keyboard(IME,"INPUT_METHOD"),activities(AlphabetSearchDevice.RESULTS)));
    }

    @Test public void overlapAndUnboundKeyboardRemainBlockedForDifferentReasons() {
        String page = foreground(AlphabetSearchDevice.EDITOR);
        String history = activities(AlphabetSearchDevice.EDITOR);
        String extra = "  Window #2 Window{abc u0 " + BingNavigationSnapshot.MAIN + "}:\n"
                + "    mOwnerUid=10501 package=com.microsoft.bing appop=NONE\n"
                + "    mDisplayId=0 rootTaskId=7\n    mAttrs={ty=BASE_APPLICATION}\n    isVisible=true isOnScreen=true\n";
        assertEquals("SEARCH_BING_WINDOW_OVERLAP", assertThrows(SecurityException.class,
                () -> AlphabetSearchDevice.parseSource(page+extra,history,PROFILE,"7",IME,10432)).getMessage());
        assertEquals("SEARCH_COVERED", assertThrows(SecurityException.class,
                () -> AlphabetSearchDevice.parseSource(page+extra.replace("mOwnerUid=10501","mOwnerUid=10520"),
                        history,PROFILE,"7",IME,10432)).getMessage());
        assertEquals("SEARCH_IME_WINDOW_UNBOUND", assertThrows(SecurityException.class,
                () -> AlphabetSearchDevice.parseSource(page+keyboard(IME,"INPUT_METHOD"),
                        history,PROFILE,"7",IME,-1)).getMessage());
    }

    @Test public void onlyKnownBingComponentsOnTheOriginalTaskCanAuthorizeTheNextStep() {
        String known=AlphabetSearchDevice.RESULTS;
        assertTrue(accepted(foreground(known),activities(known)));
        String other="com.microsoft.bing/com.example.SettingsActivity";
        assertFalse(accepted(foreground(other),activities(other)));
        assertFalse(accepted(foreground(known),activities(known).replace(" t7}"," t8}")));
        assertFalse(accepted(foreground(known).replace("mHasSurface=true","mHasSurface=false"),activities(known)));
    }

    @Test public void originalActivityMustStillBeRetainedAndUnfinished() {
        String component=BingNavigationSnapshot.TEMPLATE;
        var origin=new BingForegroundSnapshot.Snapshot("ab1","ae1",component,"7");
        String history=activities(component);
        assertTrue(AlphabetSearchDevice.retainsOrigin(history,origin));
        assertFalse(AlphabetSearchDevice.retainsOrigin(history.replace("finishing=false","finishing=true"),origin));
        assertFalse(AlphabetSearchDevice.retainsOrigin(history.replace(" t7}"," t8}"),origin));
    }

    @Test public void searchLaunchRequiresTheExactHandedOffComponentActivityWindowAndTask() {
        String template = BingNavigationSnapshot.TEMPLATE;
        var initial = BingNavigationFixture.result(BingNavigationSnapshot.Kind.TEMPLATE,1000,"aaa","ddd","15").ready();
        assertNotNull(initial);
        assertTrue(AlphabetSearchDevice.exactStart(initial,new BingForegroundSnapshot.Snapshot("aaa","ddd",template,"15")));
        assertFalse(AlphabetSearchDevice.exactStart(initial,new BingForegroundSnapshot.Snapshot("aaa","ddd",BingNavigationSnapshot.MAIN,"15")));
        assertFalse(AlphabetSearchDevice.exactStart(initial,new BingForegroundSnapshot.Snapshot("bbb","ddd",template,"15")));
        assertFalse(AlphabetSearchDevice.exactStart(initial,new BingForegroundSnapshot.Snapshot("aaa","eee",template,"15")));
        assertFalse(AlphabetSearchDevice.exactStart(initial,new BingForegroundSnapshot.Snapshot("aaa","ddd",template,"16")));
    }

    @Test public void textIsExactlyOneLowercaseLetterWithinTheConfiguredAlphabet() {
        assertTrue(AlphabetSearchDevice.allowedLetter('a'));
        assertTrue(AlphabetSearchDevice.allowedLetter('t'));
        for (char c : new char[]{'A','u',' ','&','\n','0'}) assertFalse(AlphabetSearchDevice.allowedLetter(c));
    }

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String RECEIPT = "S1|" + NONCE + "|";

    @Test public void onlyACleanBoundCompletionReleasesAnInvokedProbe() {
        var unopened = new AlphabetSearchDevice.ProbeAttempt();
        assertTrue(unopened.clean());
        unopened.invoked = true;
        // Also covers a transport failure or deadline after invocation with no receipt.
        assertFalse(unopened.clean());
        for (String body : new String[]{"OK|320|120|MATCH", "MISS|0|0|NONE"}) {
            var completed = new AlphabetSearchDevice.ProbeAttempt();
            completed.invoked = true;
            completed.accept(RECEIPT + body + "\n", NONCE, "EDITOR", 1440, 2560);
            assertTrue(completed.clean());
        }
    }

    @Test public void ambiguousOutputNeverAcknowledgesProbeCleanup() {
        String ok = RECEIPT + "OK|320|120|MATCH\n";
        for (String receipt : new String[]{
                null, "", ok.trim(), ok + "\n", ok + ok, ok.replace(NONCE,"11111111111111111111111111111111"),
                ok.replace("S1|","S2|"), RECEIPT + "UNCLEAN|0|0|NONE\n", RECEIPT + "UNKNOWN|0|0|NONE\n",
                RECEIPT + "MISS|1|0|NONE\n", RECEIPT + "MISS|0|0|MATCH\n", RECEIPT + "OK|bad|120|MATCH\n",
                RECEIPT + "OK|0320|120|MATCH\n", RECEIPT + "OK|1440|120|MATCH\n", RECEIPT + "OK|320|1280|MATCH\n",
                RECEIPT + "OK|320|120|SELECTED\n", RECEIPT + "OK|320|120|BAR\n"
        }) {
            var attempt = new AlphabetSearchDevice.ProbeAttempt();
            attempt.invoked = true;
            try {
                attempt.accept(receipt, NONCE, "EDITOR", 1440, 2560);
                fail("malformed receipt accepted");
            } catch (SecurityException expected) {
                assertFalse(attempt.clean());
            }
        }
    }
}
