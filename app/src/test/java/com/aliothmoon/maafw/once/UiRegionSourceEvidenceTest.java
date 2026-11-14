package com.aliothmoon.maafw.once;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.BingNavigationFixture.*;

/** Synthetic metadata only; these fixtures do not establish live UI or image alignment. */
public class UiRegionSourceEvidenceTest {
    static final String NONCE = "0123456789abcdef0123456789abcdef";
    static final String OTHER_NONCE = "1123456789abcdef0123456789abcdef";

    static NativeUiRegions.Pair regions() {
        return new NativeUiRegions.Pair(
                new NativeUiRegions.Region(NativeUiRegions.Role.SEARCH,
                        new NativeUiRegions.Rect(20, 40, 380, 200),
                        new NativeUiRegions.Rect(200, 60, 370, 90)),
                new NativeUiRegions.Region(NativeUiRegions.Role.READING,
                        new NativeUiRegions.Rect(20, 220, 380, 400), null));
    }

    static NativeUiStructureProtocol.Report report() {
        return report(NONCE, 1000, 2100, 400, 800, -1, -1);
    }

    static NativeUiStructureProtocol.Report report(String nonce, long started, long finished,
            int right, int bottom, int rotationBefore, int rotationAfter) {
        return new NativeUiStructureProtocol.Report(nonce, started, finished - 100, finished,
                NativeUiStructureProtocol.Reason.OK, NativeUiStructureProtocol.Cleanup.CONFIRMED,
                5, 120, 7, 0, 0, 0, right, bottom, rotationBefore, rotationAfter,
                new NativeUiStructureProtocol.Task(NativeUiStructureProtocol.Status.KNOWN, 0, 60, 1,
                        NativeUiStructureProtocol.Unit.EXPLICIT_TEXT_POINTS),
                NativeUiStructureProtocol.Task.unknown(),
                new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,
                        1, 0, 0, 0, 0, 1, 1, 1), null, true, regions());
    }

    static BingNavigationSnapshot.Ready ready(long at) {
        return result(BingNavigationSnapshot.Kind.TEMPLATE, at).ready();
    }

    static final class Fixture {
        final Object owner = new Object(), session = new Object(), epoch = new Object();
        final NativeUiStructureProtocol.Report report;
        Fixture() { this(UiRegionSourceEvidenceTest.report()); }
        Fixture(NativeUiStructureProtocol.Report report) { this.report = report; }
        UiRegionSourceEvidence.Pending begin() {
            return UiRegionSourceEvidence.begin(owner, session, epoch, NONCE, ready(900), 950);
        }
        UiRegionSourceEvidence evidence() { return begin().complete(report, ready(2200), 2300, true); }
        UiStructurePublication publication(UiRegionSourceEvidence evidence) {
            return new UiStructurePublication(report, true, 10000, evidence, owner, session, epoch);
        }
    }

    @Test public void bindsTheExactOwnersReportAndNonce() {
        var f = new Fixture();
        var source = f.evidence();
        assertNotNull(source);
        assertTrue(source.matches(f.owner, f.session, f.epoch, f.report, 2300));
        assertFalse(source.matches(new Object(), f.session, f.epoch, f.report, 2300));
        assertFalse(source.matches(f.owner, new Object(), f.epoch, f.report, 2300));
        assertFalse(source.matches(f.owner, f.session, new Object(), f.report, 2300));
        assertEquals(f.report, report());
        assertFalse(source.matches(f.owner, f.session, f.epoch, report(), 2300));
        assertNull(f.begin().complete(report(OTHER_NONCE, 1000, 2100, 400, 800, -1, -1),
                ready(2200), 2300, true));
    }

    @Test public void beginRejectsMissingIdentityNonceAndTemplateOrFreshness() {
        Object identity = new Object();
        assertNull(UiRegionSourceEvidence.begin(null, identity, identity, NONCE, ready(900), 950));
        assertNull(UiRegionSourceEvidence.begin(identity, null, identity, NONCE, ready(900), 950));
        assertNull(UiRegionSourceEvidence.begin(identity, identity, null, NONCE, ready(900), 950));
        assertNull(UiRegionSourceEvidence.begin(identity, identity, identity, "bad", ready(900), 950));
        assertNull(UiRegionSourceEvidence.begin(identity, identity, identity, NONCE, null, 950));
        assertNull(UiRegionSourceEvidence.begin(identity, identity, identity, NONCE,
                result(BingNavigationSnapshot.Kind.BROWSER, 900).ready(), 950));
        assertNull(UiRegionSourceEvidence.begin(identity, identity, identity, NONCE, ready(900), 899));
        assertNull(UiRegionSourceEvidence.begin(identity, identity, identity, NONCE, ready(900), 5901));
    }

    @Test public void completionCannotBeRetriedAfterSuccessOrFailure() {
        var f = new Fixture();
        var success = f.begin();
        assertNotNull(success.complete(f.report, ready(2200), 2300, true));
        assertNull(success.complete(f.report, ready(2200), 2300, true));
        var failed = f.begin();
        assertNull(failed.complete(f.report, ready(2200), 2300, false));
        assertNull(failed.complete(f.report, ready(2200), 2300, true));
    }

    @Test public void consumptionCannotBeReplayedEvenAfterRejectedIdentity() {
        var f = new Fixture();
        var source = f.evidence();
        assertTrue(source.consume(f.owner, f.session, f.epoch, f.report, 2300));
        assertFalse(source.matches(f.owner, f.session, f.epoch, f.report, 2300));
        assertFalse(source.consume(f.owner, f.session, f.epoch, f.report, 2300));
        var rejected = f.evidence();
        assertFalse(rejected.consume(new Object(), f.session, f.epoch, f.report, 2300));
        assertFalse(rejected.consume(f.owner, f.session, f.epoch, f.report, 2300));
    }

    @Test public void taskEqualityDoesNotHideAChangedWindowActivityOrProfile() {
        var f = new Fixture();
        for (var changed : new BingNavigationSnapshot.Ready[] {
                result(BingNavigationSnapshot.Kind.TEMPLATE, 2200, "bbb", "ddd", "15").ready(),
                result(BingNavigationSnapshot.Kind.TEMPLATE, 2200, "aaa", "eee", "15").ready(),
                result(BingNavigationSnapshot.Kind.TEMPLATE, 2200, "aaa", "ddd", "16").ready(),
                result(BingNavigationSnapshot.Kind.BROWSER, 2200).ready(),
                BingNavigationSnapshot.parse(windows(BingNavigationSnapshot.TEMPLATE, "aaa", "ddd", "15"),
                        activities(BingNavigationSnapshot.TEMPLATE, "ddd", "15", true),
                        new BingNavigationSnapshot.Profile(10073, 10049,
                                new BingNavigationSnapshot.Shape(523, 1000, 1)), 2200, 2200).ready()
        }) {
            assertNotNull(changed);
            assertNull(f.begin().complete(f.report, changed, 2300, true));
        }
    }

    @Test public void beforeMustPrecedeHelperAndRemainFreshAtHelperStart() {
        var f = new Fixture();
        assertNull(f.begin().complete(report(NONCE, 949, 2100, 400, 800, -1, -1), ready(2200), 2300, true));
        assertNull(f.begin().complete(report(NONCE, 6000, 7100, 400, 800, -1, -1), ready(7200), 7300, true));
    }

    @Test public void afterReadCannotOverlapHelperOrComeFromTheFuture() {
        var f = new Fixture();
        var overlap = BingNavigationSnapshot.parse(
                windows(BingNavigationSnapshot.TEMPLATE, "aaa", "ddd", "15"),
                activities(BingNavigationSnapshot.TEMPLATE, "ddd", "15", true), PROFILE, 2099, 2200).ready();
        assertNotNull(overlap);
        assertNull(f.begin().complete(f.report, overlap, 2300, true));
        assertNull(f.begin().complete(f.report, ready(2301), 2300, true));
        assertNull(f.begin().complete(f.report, null, 2300, true));
        assertNull(f.begin().complete(f.report, ready(2200), 7201, true));
    }

    @Test public void appendRechecksTheAfterMetadataAndFrameFreshness() {
        var f = new Fixture();
        var source = f.evidence();
        assertTrue(source.matches(f.owner, f.session, f.epoch, f.report, 7200));
        assertFalse(source.matches(f.owner, f.session, f.epoch, f.report, 7201));
        assertFalse(source.matches(f.owner, f.session, f.epoch, f.report, 2199));
        assertNull(f.begin().complete(f.report, ready(17000), 17000, true));
    }

    @Test public void geometryMustFitTheActualSourceDisplayAndKnownRotation() {
        var f = new Fixture();
        assertNotNull(new Fixture(report(NONCE, 1000, 2100, 523, 1000, 0, 0)).evidence());
        assertNotNull(new Fixture(report(NONCE, 1000, 2100, 400, 800, -1, 0)).evidence());
        assertNull(f.begin().complete(report(NONCE, 1000, 2100, 524, 1000, -1, -1), ready(2200), 2300, true));
        assertNull(f.begin().complete(report(NONCE, 1000, 2100, 523, 1001, -1, -1), ready(2200), 2300, true));
        assertNull(f.begin().complete(report(NONCE, 1000, 2100, 400, 800, 1, -1), ready(2200), 2300, true));
        assertNull(f.begin().complete(report(NONCE, 1000, 2100, 400, 800, -1, 1), ready(2200), 2300, true));
    }

    @Test public void legacyCounterReportCannotBecomeRegionEvidence() {
        var f = new Fixture();
        var r = f.report;
        var legacy = new NativeUiStructureProtocol.Report(r.nonce(), r.started(), r.acquired(), r.finished(),
                r.reason(), r.cleanup(), r.nodeCount(), r.charCount(), r.windowId(), r.displayId(),
                r.left(), r.top(), r.right(), r.bottom(), r.rotationBefore(), r.rotationAfter(),
                r.search(), r.reading(), r.readiness(), null, true);
        assertNull(f.begin().complete(legacy, ready(2200), 2300, true));
    }
}
