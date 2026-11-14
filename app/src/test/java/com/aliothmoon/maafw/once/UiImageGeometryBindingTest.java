package com.aliothmoon.maafw.once;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.BingNavigationFixture.*;

/** Synthetic ownership and time tests only; no image capture or authorization is exercised. */
public class UiImageGeometryBindingTest {
    static final class Fixture {
        final UiRegionSourceEvidenceTest.Fixture ui = new UiRegionSourceEvidenceTest.Fixture();
        final UiRegionSourceEvidence evidence = ui.evidence();
        UiImageGeometryBinding geometry() {
            return evidence.consumeGeometry(ui.owner, ui.session, ui.epoch, ui.report, 2300);
        }
        UiImageAlignmentCapture capture() { return geometry().beginFrame(ready(2350), 2400, 7000, () -> false); }
    }
    static BingNavigationSnapshot.Ready ready(long now) { return UiRegionSourceEvidenceTest.ready(now); }

    @Test public void geometryAndLegacyPublicationCompeteForOneSourceConsumption() {
        var f = new Fixture();
        var geometry = f.geometry();
        assertNotNull(geometry);
        assertNull(f.geometry());
        assertFalse(f.evidence.matches(f.ui.owner, f.ui.session, f.ui.epoch, f.ui.report, 2300));
        assertTrue(f.ui.publication(f.evidence).publish(2300, true, true, true).contains("evidence=UNQUALIFIED"));
        var other = new Fixture();
        assertTrue(other.ui.publication(other.evidence).publish(2300, true, true, true).contains("evidence=QUALIFIED"));
        assertNull(other.geometry());
    }

    @Test public void wrongIdentityOrEqualButDifferentReportConsumesTheRejectedAttempt() {
        for (int changed = 0; changed < 4; changed++) {
            var f = new Fixture();
            assertNull(f.evidence.consumeGeometry(changed == 0 ? new Object() : f.ui.owner,
                    changed == 1 ? new Object() : f.ui.session, changed == 2 ? new Object() : f.ui.epoch,
                    changed == 3 ? UiRegionSourceEvidenceTest.report() : f.ui.report, 2300));
            assertNull(f.geometry());
        }
    }

    @Test public void carriesExactOwnersAbsoluteScreenRectsAndProfileGeometry() {
        var f = new Fixture();
        var geometry = f.geometry();
        assertSame(f.ui.owner, geometry.owner());
        assertEquals(523, geometry.width());assertEquals(1000, geometry.height());assertEquals(0, geometry.rotation());
        assertSame(f.ui.report.regions(), geometry.regions());
        assertEquals(new NativeUiRegions.Rect(20, 40, 380, 200), geometry.regions().search().card());
        assertTrue(geometry.matchesOwners(f.ui.owner, f.ui.session, f.ui.epoch));
        assertFalse(geometry.matchesOwners(new Object(), f.ui.session, f.ui.epoch));
        assertFalse(geometry.matchesOwners(f.ui.owner, new Object(), f.ui.epoch));
        assertFalse(geometry.matchesOwners(f.ui.owner, f.ui.session, new Object()));
    }

    @Test public void beginIsOneShotIncludingAnInvalidFirstAttempt() {
        var geometry = new Fixture().geometry();
        assertNotNull(geometry.beginFrame(ready(2350), 2400, 7000, () -> false));
        assertNull(geometry.beginFrame(ready(2350), 2400, 7000, () -> false));
        geometry = new Fixture().geometry();
        assertNull(geometry.beginFrame(ready(2350), 2400, 2400, () -> false));
        assertNull(geometry.beginFrame(ready(2350), 2400, 7000, () -> false));
    }

    @Test public void beforeImageMustFollowUiMetadataAndPrecedeTheImageEnvelope() {
        assertNull(new Fixture().geometry().beginFrame(ready(2199), 2400, 7000, () -> false));
        assertNull(new Fixture().geometry().beginFrame(ready(2401), 2400, 7000, () -> false));
        assertNull(new Fixture().geometry().beginFrame(ready(2200), 2299, 7000, () -> false));
        assertNull(new Fixture().geometry().beginFrame(null, 2400, 7000, () -> false));
        assertNotNull(new Fixture().geometry().beginFrame(ready(2300), 2300, 7000, () -> false));
    }

    @Test public void sameTaskIsInsufficientAfterWindowActivityOrRotationChange() {
        var changedRotation = BingNavigationSnapshot.parse(windows(BingNavigationSnapshot.TEMPLATE, "aaa", "ddd", "15"),
                activities(BingNavigationSnapshot.TEMPLATE, "ddd", "15", true),
                new BingNavigationSnapshot.Profile(10073, 10049, new BingNavigationSnapshot.Shape(523, 1000, 1)), 2350, 2350).ready();
        for (var before : new BingNavigationSnapshot.Ready[] {
                result(BingNavigationSnapshot.Kind.TEMPLATE, 2350, "bbb", "ddd", "15").ready(),
                result(BingNavigationSnapshot.Kind.TEMPLATE, 2350, "aaa", "eee", "15").ready(),
                result(BingNavigationSnapshot.Kind.TEMPLATE, 2350, "aaa", "ddd", "16").ready(),
                result(BingNavigationSnapshot.Kind.BROWSER, 2350).ready(), changedRotation }) {
            assertNotNull(before);
            assertNull(new Fixture().geometry().beginFrame(before, 2400, 7000, () -> false));
        }
    }

    @Test public void transferNeverExtendsOriginalUiLifetime() {
        var f = new Fixture();
        assertNull(f.evidence.consumeGeometry(f.ui.owner, f.ui.session, f.ui.epoch, f.ui.report, 7201));
        var geometry = new Fixture().geometry();
        assertTrue(geometry.fresh(7200));assertFalse(geometry.fresh(7201));assertFalse(geometry.fresh(2299));
        assertNull(geometry.beginFrame(ready(7201), 7201, Long.MAX_VALUE, () -> false));
    }

    @Test public void revocationAndThrowingPredicateRejectWithoutRearming() {
        AtomicBoolean revoked = new AtomicBoolean(true);
        var geometry = new Fixture().geometry();
        assertNull(geometry.beginFrame(ready(2350), 2400, 7000, revoked::get));
        revoked.set(false);
        assertNull(geometry.beginFrame(ready(2350), 2400, 7000, revoked::get));
        assertNull(new Fixture().geometry().beginFrame(ready(2350), 2400, 7000, () -> { throw new IllegalStateException(); }));
        assertNull(new Fixture().geometry().beginFrame(ready(2350), 2400, 7000, null));
    }

    @Test public void constructorsCannotMintAnUnconsumedGeometryBinding() {
        for (var constructor : UiImageGeometryBinding.class.getDeclaredConstructors())
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        for (var constructor : UiImageAlignmentCapture.class.getDeclaredConstructors())
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
    }
}
