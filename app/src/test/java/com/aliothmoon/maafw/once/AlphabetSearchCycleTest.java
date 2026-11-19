package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class AlphabetSearchCycleTest {
    private static final String TODAY = "2026-11-20";
    private static final String ALPHABET = "abcdefghijklmnopqrst";

    private static String path(String day, char letter, String name) {
        return AlphabetSearchCycle.DIRECTORY + "/" + day + "/letters/" + letter + "/" + name + ".burned";
    }

    private static class Device implements AlphabetSearchCycle.Ports {
        final LiveRewardsLedger.Store store;
        final StringBuilder searches = new StringBuilder();
        final List<Long> waits = new ArrayList<>();
        String day = TODAY;
        long clock = 1_000, success;
        char prepared;
        boolean inResults, current = true;
        int origins;

        Device(LiveRewardsLedger.Store store) { this.store = store; }
        AlphabetSearchCycle cycle() {
            return new AlphabetSearchCycle(store, day, UUID.randomUUID().toString(), () -> day);
        }
        @Override public void requireOrigin() throws Exception {
            assertFalse(inResults);
            assertEquals(0, prepared);
            origins++;
        }
        @Override public void prepare(char letter) throws Exception {
            assertFalse(inResults);
            assertEquals(0, prepared);
            assertFalse(store.exists(path(day, letter, "PENDING")));
            prepared = letter;
        }
        @Override public void submit(char letter) throws Exception {
            assertEquals(prepared, letter);
            assertTrue(store.exists(path(day, letter, "PENDING")));
            searches.append(letter);
            // Simulate time spent validating and actually issuing Enter.
            clock += 1_750;
            success = clock;
            inResults = true;
        }
        @Override public void back() throws Exception {
            assertTrue(inResults);
            assertTrue(store.exists(path(day, prepared, "BACK")));
            assertTrue(clock - success >= AlphabetSearchCycle.WAIT_MILLIS);
            waits.add(clock - success);
            inResults = false;
            prepared = 0;
        }
        @Override public boolean current() { return current; }
        @Override public long now() { return clock; }
        @Override public void pause(long millis) {
            assertTrue(millis > 0 && millis <= 5_000);
            // Wake up early to exercise the elapsed-time check.
            clock += Math.min(1_800, millis);
        }
    }

    @Test public void aThroughTWaitAfterCommandSuccessThenReturnExactlyOnceEach() throws Exception {
        Device device = new Device(new LiveRewardsLedger.MemoryStore());
        AlphabetSearchCycle cycle = device.cycle();
        var result = cycle.run(device);
        assertEquals(AlphabetSearchCycle.Status.COMPLETE, result.status());
        assertEquals(ALPHABET, device.searches.toString());
        assertEquals(20, result.claimed());
        assertEquals(20, result.submitted());
        assertEquals(20, result.backs());
        assertEquals(20, result.returned());
        assertEquals(21, device.origins);
        assertEquals(20, device.waits.size());
        for (long waited : device.waits) assertEquals(5_000L, waited);
        assertThrows(IllegalStateException.class, () -> cycle.run(device));

        Device sameDay = new Device(device.store);
        var repeated = sameDay.cycle().run(sameDay);
        assertEquals(AlphabetSearchCycle.Status.COMPLETE, repeated.status());
        assertEquals(20, repeated.previousReturns());
        assertEquals(20, repeated.previousAttempts());
        assertEquals(0, repeated.claimed());
        assertEquals(0, sameDay.origins);
        assertEquals("", sameDay.searches.toString());

        Device tomorrow = new Device(device.store);
        tomorrow.day = "2026-11-21";
        assertEquals(20, tomorrow.cycle().run(tomorrow).returned());
        assertEquals(ALPHABET, tomorrow.searches.toString());
    }

    @Test public void uncertainSubmissionStaysSpentThroughRestart() throws Exception {
        var store = new LiveRewardsLedger.MemoryStore();
        Device interrupted = new Device(store) {
            @Override public void submit(char letter) throws Exception {
                super.submit(letter);
                throw new IOException("Command outcome unknown");
            }
        };
        var run = interrupted.cycle();
        assertThrows(IOException.class, () -> run.run(interrupted));
        assertEquals(AlphabetSearchCycle.Status.STOPPED, run.snapshot().status());
        assertEquals(1, run.snapshot().claimed());
        assertEquals(0, run.snapshot().submitted());
        assertEquals("a", interrupted.searches.toString());
        assertTrue(interrupted.waits.isEmpty());
        Device next = new Device(store);
        var result = next.cycle().run(next);
        assertEquals(ALPHABET.substring(1), next.searches.toString());
        assertEquals(19, result.returned());
        assertEquals(1, result.previousAttempts());
        assertEquals(0, result.previousReturns());
        assertEquals(AlphabetSearchCycle.Status.EXHAUSTED_WITH_UNCONFIRMED, result.status());
    }

    @Test public void unqualifiedEditorConsumesNoLetter() throws Exception {
        var store = new LiveRewardsLedger.MemoryStore();
        Device covered = new Device(store) {
            @Override public void prepare(char letter) { throw new SecurityException("Search editor covered"); }
        };
        var run = covered.cycle();
        assertThrows(SecurityException.class, () -> run.run(covered));
        assertEquals(0, run.snapshot().claimed());
        assertFalse(store.exists(path(TODAY, 'a', "PENDING")));
        Device next = new Device(store);
        assertEquals(20, next.cycle().run(next).returned());
    }

    @Test public void dateChangeAndCancellationDuringWaitIssueNoBack() {
        for (boolean midnight : new boolean[]{false,true}) {
            Device interrupted = new Device(new LiveRewardsLedger.MemoryStore()) {
                @Override public void pause(long millis) {
                    clock += millis;
                    if (midnight) day = "2026-11-21"; else current = false;
                }
            };
            var run = interrupted.cycle();
            var failure = assertThrows(IllegalStateException.class, () -> run.run(interrupted));
            assertEquals(midnight ? "SEARCH_DATE_CHANGED" : "SEARCH_RUN_INACTIVE", failure.getMessage());
            assertEquals(1, run.snapshot().submitted());
            assertEquals(0, run.snapshot().backs());
            assertTrue(interrupted.waits.isEmpty());
        }
    }

    @Test public void stalledOrBackwardsMonotonicClockCannotShortenDwell() {
        for (long change : new long[]{0, -1}) {
            Device device = new Device(new LiveRewardsLedger.MemoryStore()) {
                @Override public void pause(long millis) { clock += change; }
            };
            var run = device.cycle();
            assertThrows(IllegalStateException.class, () -> run.run(device));
            assertEquals("a", device.searches.toString());
            assertEquals(0, run.snapshot().backs());
        }
    }

    @Test public void lostBackReceiptNeverDispatchesAnotherReturn() throws Exception {
        Device uncertain = new Device(new LiveRewardsLedger.MemoryStore()) {
            @Override public void back() throws Exception {
                super.back();
                throw new IOException("Back outcome unknown");
            }
        };
        var run = uncertain.cycle();
        assertThrows(IOException.class, () -> run.run(uncertain));
        assertEquals(1, uncertain.waits.size());
        assertTrue(uncertain.store.exists(path(TODAY, 'a', "BACK")));
        assertFalse(uncertain.store.exists(path(TODAY, 'a', "RETURNED")));
        Device next = new Device(uncertain.store);
        assertEquals(19, next.cycle().run(next).backs());
        assertEquals(ALPHABET.substring(1), next.searches.toString());
    }

    @Test public void missingOriginStopsBeforePreparingAnotherLetter() throws Exception {
        Device device = new Device(new LiveRewardsLedger.MemoryStore()) {
            @Override public void requireOrigin() throws Exception {
                if (origins != 0) throw new SecurityException("Original Bing page not visible");
                super.requireOrigin();
            }
        };
        var run = device.cycle();
        assertThrows(SecurityException.class, () -> run.run(device));
        assertEquals(1, run.snapshot().backs());
        assertEquals(0, run.snapshot().returned());
        assertFalse(device.store.exists(path(TODAY, 'b', "PENDING")));
    }

    @Test public void durableReservationFailurePreventsTheCorrespondingInput() {
        for (String failed : new String[]{"PENDING", "BACK"}) {
            var store = new LiveRewardsLedger.MemoryStore();
            Device device = new Device(new LiveRewardsLedger.Store() {
                public boolean exists(String path) throws IOException { return store.exists(path); }
                public void create(String path, String value) throws IOException {
                    if (path.endsWith("/" + failed + ".burned")) throw new IOException("Storage unavailable");
                    store.create(path,value);
                }
            });
            var run = device.cycle();
            assertThrows(IOException.class, () -> run.run(device));
            assertEquals(failed.equals("PENDING") ? "" : "a", device.searches.toString());
            assertEquals(0, run.snapshot().backs());
        }
    }

    @Test public void overlappingCyclesCannotChangeTheActiveEditor() throws Exception {
        var store = new LiveRewardsLedger.MemoryStore();
        Device competing = new Device(store);
        Device owner = new Device(store) {
            @Override public void prepare(char letter) throws Exception {
                if (letter == 'a') {
                    var failure=assertThrows(IllegalStateException.class, () -> competing.cycle().run(competing));
                    assertEquals("SEARCH_ALREADY_RUNNING", failure.getMessage());
                }
                super.prepare(letter);
            }
        };
        assertEquals(20, owner.cycle().run(owner).returned());
        assertEquals(0, competing.origins);
        assertEquals("", competing.searches.toString());
    }
}
