package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.once.LiveRewardsTypes.Kind;
import com.aliothmoon.maafw.once.LiveRewardsTypes.Outcome;
import com.aliothmoon.maafw.once.LiveRewardsTypes.Phase;
import com.aliothmoon.maafw.once.LiveRewardsTypes.Task;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveRegionReconciliationTest {
    private static final int WIDTH = 523, HEIGHT = 1000;

    private static Task task(Kind kind, int ordinal, String title, String description, int points) {
        String label = description.isEmpty() ? title : title + ", " + description;
        return new Task(kind, LiveCardIdentity.descriptor(label + ", + " + points + " 积分"), ordinal,
                LiveCardIdentity.hashText(title), points, true, false, -1);
    }

    private static Task compact(Task task) {
        return new Task(task.kind(), task.titleHash(), task.ordinal(), task.titleHash(), task.points(), true, true, -1);
    }

    private static LiveUiProtocol.Report page(Kind kind, List<Task> rows, int offset) {
        var role = LiveTaskMatcher.role(kind);
        var region = new OverviewUiRegions.Region(role, new Box(20, 170, 503, 940),
                new Box(32, 185, 180, 210), null, true, -1, -1);
        var overview = new OverviewUiRegions.Result(OverviewUiRegions.Status.OBSERVED,
                new Box(0, 0, WIDTH, HEIGHT), List.of(region));
        var cards = new ArrayList<RemainingCardUi.Item>();
        var items = new ArrayList<LiveUiProtocol.Item>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            assertEquals(kind, row.kind());
            int top = 250 + 100 * i + offset;
            var box = new Box(40, top, 483, top + 76);
            cards.add(new RemainingCardUi.Item(role, i + 1, box, null, null, true));
            items.add(new LiveUiProtocol.Item(role, i + 1, row.descriptorHash(), row.points(), box, true));
        }
        var parsed = new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED,
                kind == Kind.DAILY ? rows.size() : -1, kind == Kind.MORE ? rows.size() : -1, cards);
        var base = new OverviewUiProtocol.Report(OverviewFrameCaptureTest.report().source(), overview,
                OverviewUiProtocol.ScrollHint.DECLARED_BOTH, parsed);
        return new LiveUiProtocol.Report(base, items);
    }

    private static Box crop(LiveUiProtocol.Report report, LiveUiProtocol.Item item) {
        var selection = RemainingCardTarget.select(report.base(), WIDTH, HEIGHT,
                new RemainingCardChoice(item.role(), item.index())).selection();
        assertEquals(CardRevealProbeTarget.State.CANDIDATE, selection.state());
        return selection.crop();
    }

    @Test public void fullIdentityMustBeUniqueInBothTheInitialPlanAndTheCurrentRegion() {
        var alpha = task(Kind.MORE, 1, "Shared", "Alpha", 5);
        var beta = task(Kind.MORE, 2, "Shared", "Beta", 5);
        var differentPoints = task(Kind.MORE, 3, "Shared", "Alpha", 10);
        var plan = List.of(alpha, beta, differentPoints);
        var refreshed = task(Kind.MORE, 1, "Refreshed", "New task", 5);
        var current = page(Kind.MORE, List.of(differentPoints, beta, refreshed), 0);
        assertTrue(LiveTaskMatcher.admits(current, beta, plan));
        assertTrue(LiveTaskMatcher.admits(current, differentPoints, plan));
        assertFalse(LiveTaskMatcher.admits(current, alpha, plan));
        assertFalse(LiveTaskMatcher.admits(current, refreshed, plan));
        assertFalse(LiveTaskMatcher.admits(current, beta, List.of(beta, beta)));
        assertFalse(LiveTaskMatcher.admits(page(Kind.MORE, List.of(beta, beta), 0), beta, plan));
        assertFalse(LiveTaskMatcher.admits(page(Kind.MORE, List.of(compact(beta)), 0), beta, plan));
        assertFalse(LiveTaskMatcher.admits(current, task(Kind.DAILY, 1, "Shared", "Beta", 5), plan));
    }

    @Test public void everyIdentityIncludingOffscreenRowsMustHaveAContiguousRegionIndex() {
        var first = task(Kind.MORE, 1, "First", "Alpha", 5);
        var second = task(Kind.MORE, 2, "Second", "Beta", 5);
        var plan = List.of(first, second);
        var visible = page(Kind.MORE, plan, 0);
        var original = visible.base();
        var visibleFirstOnly = new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED, -1, 2,
                List.of(original.cards().items().get(0)));
        var scrolled = new OverviewUiProtocol.Report(original.source(), original.overview(),
                original.scrollHint(), visibleFirstOnly);
        var row = visible.items().get(1);
        var offscreen = new LiveUiProtocol.Item(row.role(), 2, row.descriptorHash(), row.points(), null, false);
        var complete = new LiveUiProtocol.Report(scrolled, List.of(visible.items().get(0), offscreen));
        var snapshot = LiveTaskMatcher.regionSnapshot(complete, Kind.MORE);
        assertNotNull(snapshot);
        assertTrue(snapshot.matchesPreflight(List.of(second, first)));
        assertFalse(snapshot.matchesPreflight(List.of(first, first)));
        var gap = new LiveUiProtocol.Item(row.role(), 3, row.descriptorHash(), row.points(), null, false);
        var incomplete = new LiveUiProtocol.Report(scrolled, List.of(visible.items().get(0), gap));
        assertNull(LiveTaskMatcher.regionSnapshot(incomplete, Kind.MORE));
        assertFalse(LiveTaskMatcher.admits(incomplete, first, plan));
    }

    @Test public void compactCompletionRequiresUniqueKnownTitleAndIsReadOnly() {
        var original = task(Kind.DAILY, 1, "First", "First description", 10);
        var second = task(Kind.DAILY, 2, "Next", "Second description", 10);
        var plan = List.of(original, second);
        var current = page(Kind.DAILY, List.of(second, compact(original)), 0);
        var match = LiveTaskMatcher.find(current, original, plan);
        assertTrue(match.compact());
        assertTrue(LiveTaskMatcher.boundCurrentMatch(current, original, plan, match));
        assertFalse(LiveTaskMatcher.admits(current, original, plan));
        var unknown = task(Kind.DAILY, 2, "First", "Unplanned description", 10);
        var changed = page(Kind.DAILY, List.of(unknown, compact(original)), 0);
        assertFalse(LiveTaskMatcher.boundCurrentMatch(changed, original, plan, LiveTaskMatcher.find(changed, original, plan)));
    }

    @Test public void finalInputBindingRejectsAChangedSiblingAChangedPositionAndAnUnqualifiedSource() {
        var first = task(Kind.DAILY, 1, "First", "Alpha", 10);
        var second = task(Kind.DAILY, 2, "Second", "Beta", 10);
        var third = task(Kind.DAILY, 3, "Third", "Gamma", 10);
        var plan = List.of(first, second, third);
        var captured = page(Kind.DAILY, plan, 0);
        var snapshot = LiveTaskMatcher.regionSnapshot(captured, Kind.DAILY);
        var roi = crop(captured, captured.items().get(1));
        assertEquals(LiveRewardsPerception.TapBinding.QUALIFIED,
                LiveRewardsPerception.tapBinding(captured, second, plan, snapshot, roi, WIDTH, HEIGHT));
        // The intended card occupies the same pixels. A sibling changed since those pixels were read.
        var reordered = page(Kind.DAILY, List.of(third, second, first), 0);
        assertEquals(LiveRewardsPerception.TapBinding.ROSTER_CHANGED_SINCE_CROP,
                LiveRewardsPerception.tapBinding(reordered, second, plan, snapshot, roi, WIDTH, HEIGHT));
        assertEquals(LiveRewardsPerception.TapBinding.GEOMETRY_CHANGED,
                LiveRewardsPerception.tapBinding(page(Kind.DAILY, plan, 2), second, plan, snapshot, roi, WIDTH, HEIGHT));
        var invalid = new OverviewUiProtocol.Report(captured.base().source(),
                OverviewUiRegions.Result.rejected(OverviewUiRegions.Status.WRONG_STRUCTURE), OverviewUiProtocol.ScrollHint.UNKNOWN,
                RemainingCardUi.Result.rejected(RemainingCardUi.Status.WRONG_STRUCTURE));
        var unqualified = new LiveUiProtocol.Report(invalid, List.of());
        assertNotEquals(LiveRewardsPerception.TapBinding.QUALIFIED,
                LiveRewardsPerception.tapBinding(unqualified, second, plan, snapshot, roi, WIDTH, HEIGHT));
    }

    /** Synthetic local OCR receipts and passive returns; the coordinator and final layout check are real. */
    private static final class ChangingList extends LiveRewardsCoordinatorTest.Rig {
        final EnumMap<Kind, List<Task>> rows = new EnumMap<>(Kind.class);
        final List<String> dispatched = new ArrayList<>();
        final List<Integer> ordinals = new ArrayList<>();

        ChangingList(List<Task> plan, LiveRewardsLedger resumed) throws Exception {
            super(plan, resumed);
            for (var task : plan) rows.computeIfAbsent(task.kind(), kind -> new ArrayList<>()).add(task);
        }

        @Override public LiveRewardsTypes.Observation observe(LiveRunAuthority.CardScope scope, Phase phase, Task expected)
                throws Exception {
            var current = page(expected.kind(), rows.get(expected.kind()), 0);
            var match = LiveTaskMatcher.find(current, expected, tasks);
            if (!LiveTaskMatcher.boundCurrentMatch(current, expected, tasks, match)) {
                return scope.unreadable(phase, expected, f.source(), f.clock.now, "CURRENT_TASK_UNAVAILABLE");
            }
            var item = match.item();
            var row = rows.get(expected.kind()).get(item.index() - 1);
            var actual = new Task(row.kind(), expected.descriptorHash(), item.index(), row.titleHash(),
                    row.points(), true, row.complete(), -1);
            var roi = crop(current, item);
            var permit = scope.claimCrop(f.ui(scope), phase, f.clock.now);
            assertTrue(permit.bindRequest(roi, WIDTH, HEIGHT, 0, f.clock.now));
            assertTrue(permit.consume(f.clock.now));
            assertTrue(permit.claimDispatch(f.clock.now));
            scope.finishCrop(permit, true, true, f.clock.now);
            return scope.sealObservation(permit, new LiveRewardsTypes.ObservationData(actual, f.source(), f.clock.now,
                    roi, roi.width(), roi.height(), actual.complete() ? null : new Box(320, 24, 370, 55),
                    .95, LiveRewardsTypes.hash(actual.key() + phase), true, true, true,
                    -1, -1, LiveTaskMatcher.regionSnapshot(current, expected.kind())));
        }

        @Override public LiveRewardsInput.TapReceipt tap(LiveActionPermit permit) throws Exception {
            var before = permit.before();
            var current = page(permit.kind(), rows.get(permit.kind()), 0);
            assertTrue(permit.matchesBeforeSource(f.source()));
            assertFalse(permit.matchesBeforeSource(LiveNavigationSnapshotTest.original(80, f.clock.now)));
            assertEquals(LiveRewardsPerception.TapBinding.QUALIFIED, LiveRewardsPerception.tapBinding(current,
                    before.task(), tasks, before.data().regionSnapshot(), before.data().screenRoi(), WIDTH, HEIGHT));
            dispatched.add(permit.task().key());
            ordinals.add(before.task().ordinal());
            return super.tap(permit);
        }

        @Override public LiveRewardsInput.ReturnReceipt returnAfterTap(LiveActionPermit permit,
                LiveRewardsInput.TapReceipt tapped) throws Exception {
            var receipt = super.returnAfterTap(permit, tapped);
            var current = rows.get(permit.kind());
            assertTrue(current.removeIf(row -> row.sameKey(permit.task())));
            Collections.reverse(current);
            if (permit.kind() == Kind.MORE) current.add(compact(permit.task()));
            return receipt;
        }
    }

    @Test public void returnsReconcileBothRegionsAndContinueAllFourRemainingCardsWithoutReopeningHistory() throws Exception {
        var plan = new ArrayList<Task>();
        var eligible = new ArrayList<String>();
        var names = List.of("One", "Two", "Three");
        for (var kind : List.of(Kind.DAILY, Kind.MORE)) {
            for (int i = 0; i < names.size(); i++) {
                var name = names.get(i);
                var task = task(kind, i + 1, name, "Description " + name, 10);
                plan.add(task);
                eligible.add(task.key());
            }
        }
        var prior = task(Kind.DAILY, 4, "Earlier", "Already attempted", 10);
        var done = LiveCoreFixture.completed(task(Kind.DAILY, 5, "Completed", "Already checked", 10));
        plan.add(prior);
        plan.add(done);
        var previous = LiveRewardsLedger.synthetic(LiveCoreFixture.DAY, LiveCoreFixture.RUN);
        previous.begin(prior);
        previous.burnTap(prior);
        var resumed = previous.syntheticNextRun("00000000-0000-4000-8000-000000000002");
        var rig = new ChangingList(plan, resumed);
        var summary = rig.run();
        assertFalse(summary.reason(), summary.stopped());
        assertEquals(eligible, rig.dispatched);
        assertEquals(List.of(1, 4, 1, 1, 2, 2), rig.ordinals);
        assertEquals(6, summary.taps());
        assertEquals(6, summary.backs());
        assertEquals(Integer.valueOf(3), summary.counts().get(Outcome.UNCONFIRMED));
        assertEquals(Integer.valueOf(3), summary.counts().get(Outcome.OBSERVED_COMPLETE));
        assertEquals(Integer.valueOf(1), summary.counts().get(Outcome.ALREADY_ATTEMPTED_SKIP));
        assertEquals(Integer.valueOf(1), summary.counts().get(Outcome.COMPLETE_SKIP));
        assertTrue(resumed.attempted(prior));
        assertFalse(resumed.attempted(done));
    }
}
