package io.github.klwb360.rewards.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Pure deterministic recognition and policy; callers persist an ALLOW state before acting. */
public final class RewardsCore {
    private RewardsCore() {}
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Pattern FRACTION = Pattern.compile("(?<![\\p{N}.])([+-]?\\d+)\\s*/\\s*([+-]?\\d+)(?![\\p{N}.])");
    private static final Map<Task, List<String>> HEADINGS = headings();
    public enum Task { READING, SEARCH, WEB, EDGE }
    public enum ParseStatus { OK, MISSING, AMBIGUOUS, INVALID }
    public enum Decision { ALLOW, COMPLETE, CANCELLED, DAY_CHANGED, RUN_TIMEOUT, READING_TIMEOUT,
        READING_LIMIT, READING_NO_CREDIT, SEARCH_NO_CREDIT, NO_CREDIT, DUPLICATE, UNREADABLE, PENDING }
    public record Node(String id, String parentId, String text, String description,
                       boolean clickable, boolean enabled, boolean visible) {}
    public record Counter(Task task, int current, int target, boolean today) {}
    public record Observation(ParseStatus status, Counter counter, String detail) {}
    public record Limits(int maxReadingArticles, long maxReadingMillis, long maxRunMillis) {
        public Limits {
            if (maxReadingArticles < 1 || maxReadingArticles > 10 || maxReadingMillis < 1
                    || maxReadingMillis > 1_800_000 || maxRunMillis < 1 || maxRunMillis > 7_200_000) {
                throw new IllegalArgumentException("limits may only reduce the approved bounds");
            }
        }
        public static Limits defaults() { return new Limits(10, 1_800_000, 7_200_000); }
    }
    public record State(String day, long runStartedAt, long readingStartedAt,
                        int readingAttempts, int readingNoCredit, int searchAttempts, int searchNoCredit,
                        Set<String> searchHashes, String pendingTask, int pendingBefore, int pendingTarget,
                        String pendingHash) {
        public State {
            LocalDate.parse(day);
            searchHashes = Collections.unmodifiableSet(new LinkedHashSet<>(searchHashes));
            pendingTask = pendingTask == null ? "" : pendingTask;
            pendingHash = pendingHash == null ? "" : pendingHash;
            if (runStartedAt < 0 || readingStartedAt < 0 || readingAttempts < 0 || readingNoCredit < 0
                    || searchAttempts < 0 || searchNoCredit < 0 || readingNoCredit > readingAttempts
                    || searchNoCredit > searchAttempts || searchHashes.size() != searchAttempts) {
                throw new IllegalArgumentException("inconsistent persisted daily state");
            }
            if (!pendingTask.isEmpty()) {
                Task.valueOf(pendingTask);
                if (pendingBefore < 0 || pendingTarget < 1 || pendingBefore >= pendingTarget) {
                    throw new IllegalArgumentException("invalid pending observation");
                }
            }
            for (String hash : searchHashes) {
                if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid persisted term hash");
            }
        }
    }
    public record Step(Decision decision, State state, String detail) {}
    public static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                .replaceAll("[\\s\\p{Z}]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** Stable normalized SHA-256; raw terms need not be written to daily history. */
    public static String hashTerm(String term) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(normalize(term).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Only counters within a recognized task section are evidence; total points are ignored. */
    public static Observation parse(List<Node> nodes, Task task) {
        if (nodes == null || task == null) return observation(ParseStatus.INVALID, "null input");
        Map<String, Node> byId = new HashMap<>();
        for (Node node : nodes) {
            if (node == null || node.id() == null || node.id().trim().isEmpty() || byId.putIfAbsent(node.id(), node) != null) {
                return observation(ParseStatus.INVALID, "node IDs must be nonempty and unique");
            }
        }
        for (Node node : nodes) {
            Set<String> visited = new HashSet<>();
            for (Node ancestor = node; ancestor != null; ancestor = byId.get(ancestor.parentId())) {
                if (!visited.add(ancestor.id())) return observation(ParseStatus.INVALID, "cyclic node tree");
                String parent = ancestor.parentId();
                if (parent != null && !parent.isEmpty() && !byId.containsKey(parent)) {
                    return observation(ParseStatus.INVALID, "missing node parent");
                }
            }
        }
        List<Node> headings = nodes.stream().filter(Node::visible).filter(n -> heading(n) == task).collect(Collectors.toList());
        if (headings.isEmpty()) return observation(ParseStatus.MISSING, "task heading missing");
        if (headings.size() != 1) return observation(ParseStatus.AMBIGUOUS, "multiple task sections");
        Node section = headings.get(0);
        while (section != null) {
            if (section != headings.get(0) && (section.parentId() == null || section.parentId().isEmpty())) break;
            Node scope = section;
            List<Node> scoped = nodes.stream().filter(Node::visible).filter(n -> within(n, scope.id(), byId)).collect(Collectors.toList());
            long sectionHeadings = scoped.stream().filter(n -> heading(n) != null).count();
            if (sectionHeadings > 1) return observation(ParseStatus.MISSING, "counter not isolated in a task section");
            List<int[]> counts = new ArrayList<>();
            boolean today = false;
            boolean complete = false;
            boolean incomplete = false;
            for (Node node : scoped) {
                for (String value : texts(node)) {
                    today |= todayMarker(value);
                    complete |= value.equals("今日已完成") || value.equals("completed today");
                    incomplete |= value.equals("今日未完成") || value.equals("not completed today");
                    Matcher matcher = FRACTION.matcher(value);
                    while (matcher.find()) {
                        try {
                            int current = Integer.parseInt(matcher.group(1));
                            int target = Integer.parseInt(matcher.group(2));
                            if (current < 0 || target <= 0 || current > target) return observation(ParseStatus.INVALID, "invalid task counter");
                            counts.add(new int[]{current, target});
                        } catch (NumberFormatException invalid) { return observation(ParseStatus.INVALID, "counter overflow"); }
                    }
                }
            }
            if (counts.size() > 1) return observation(ParseStatus.AMBIGUOUS, "multiple task counters");
            if (counts.size() == 1 || (task == Task.WEB && complete)) {
                if (task == Task.EDGE && !today) return observation(ParseStatus.MISSING, "Edge today marker missing");
                int[] count = counts.isEmpty() ? new int[]{1, 1} : counts.get(0);
                if (task == Task.WEB && ((complete && incomplete)
                        || (complete && count[0] != count[1]) || (incomplete && count[0] == count[1]))) {
                    return observation(ParseStatus.INVALID, "Web counter and today completion flag conflict");
                }
                if (task == Task.EDGE && ((complete && incomplete)
                        || (count[0] == count[1] && !complete) || (complete && count[0] != count[1]))) {
                    return observation(ParseStatus.INVALID, "Edge minutes and today completion flag conflict or are missing");
                }
                return new Observation(ParseStatus.OK, new Counter(task, count[0], count[1], today), "task-specific counter observed");
            }
            section = byId.get(section.parentId());
        }
        return observation(ParseStatus.MISSING, "task counter missing");
    }

    /** Exact semantic match only; duplicate labels and duplicate IDs are never actionable. */
    public static Optional<Node> uniqueAction(List<Node> nodes, String label) {
        String wanted = normalize(label);
        if (nodes == null || wanted.isEmpty()) return Optional.empty();
        Set<String> ids = new HashSet<>();
        Node found = null;
        for (Node node : nodes) {
            if (node == null || node.id() == null || node.id().trim().isEmpty() || !ids.add(node.id())) return Optional.empty();
            if (node.visible() && node.enabled() && node.clickable() && texts(node).contains(wanted)) {
                if (found != null) return Optional.empty();
                found = node;
            }
        }
        return Optional.ofNullable(found);
    }

    public static State fresh(long epochNow, long elapsedNow) {
        return new State(day(epochNow), elapsedNow, 0, 0, 0, 0, 0, Collections.emptySet(), "", 0, 0, "");
    }

    /** On ALLOW, atomically save returned state before any Android click or search submission. */
    public static Step prepare(State state, Task task, Observation observation, String term, long epochNow, long elapsedNow, boolean cancelled, Limits limits) {
        Step guard = guard(state, task, epochNow, elapsedNow, cancelled, limits);
        if (guard != null) return guard;
        if (!state.pendingTask().isEmpty()) return step(Decision.PENDING, state, "unverified action must not be replayed");
        Counter counter = readable(observation, task);
        if (counter == null) return step(Decision.UNREADABLE, state, "task-specific counter unavailable");
        if (counter.current() >= counter.target()) return step(Decision.COMPLETE, state, "task target already observed");
        if (task == Task.READING) {
            if (state.readingAttempts() >= limits.maxReadingArticles()) return step(Decision.READING_LIMIT, state, "daily reading attempt limit");
            if (state.readingNoCredit() >= 4) return step(Decision.READING_NO_CREDIT, state, "four consecutive uncredited reading attempts");
        }
        if (task == Task.SEARCH && state.searchNoCredit() >= 2) return step(Decision.SEARCH_NO_CREDIT, state, "two consecutive uncredited searches");
        String hash = "";
        Set<String> hashes = new LinkedHashSet<>(state.searchHashes());
        if (task == Task.SEARCH) {
            if (normalize(term).isEmpty()) return step(Decision.UNREADABLE, state, "search term missing");
            hash = hashTerm(term);
            if (!hashes.add(hash)) return step(Decision.DUPLICATE, state, "search term already attempted today");
        }
        State next = new State(state.day(), state.runStartedAt(), task == Task.READING && state.readingStartedAt() == 0 ? elapsedNow : state.readingStartedAt(),
                state.readingAttempts() + (task == Task.READING ? 1 : 0), state.readingNoCredit(),
                state.searchAttempts() + (task == Task.SEARCH ? 1 : 0), state.searchNoCredit(), hashes,
                task.name(), counter.current(), counter.target(), hash);
        return step(Decision.ALLOW, next, "persist intent before acting");
    }

    /** A cancelled/unreadable result keeps pending intent so a restart cannot silently replay it. */
    public static Step verify(State state, Task task, Observation observation, long epochNow, long elapsedNow, boolean cancelled, Limits limits) {
        Step guard = guard(state, task, epochNow, elapsedNow, cancelled, limits);
        if (guard != null) return guard;
        if (!task.name().equals(state.pendingTask())) return step(Decision.PENDING, state, "no matching pending action");
        Counter counter = readable(observation, task);
        if (counter == null || counter.current() < state.pendingBefore() || counter.target() != state.pendingTarget()) {
            return step(Decision.UNREADABLE, state, "verification missing, regressed, or changed target");
        }
        boolean credited = counter.current() > state.pendingBefore();
        int readingFailures = task == Task.READING ? (credited ? 0 : state.readingNoCredit() + 1) : state.readingNoCredit();
        int searchFailures = task == Task.SEARCH ? (credited ? 0 : state.searchNoCredit() + 1) : state.searchNoCredit();
        State next = new State(state.day(), state.runStartedAt(), state.readingStartedAt(), state.readingAttempts(), readingFailures,
                state.searchAttempts(), searchFailures, state.searchHashes(), "", 0, 0, "");
        if (counter.current() >= counter.target()) return step(Decision.COMPLETE, next, "task target observed");
        if (readingFailures >= 4 && task == Task.READING) return step(Decision.READING_NO_CREDIT, next, "four consecutive uncredited reading attempts");
        if (searchFailures >= 2 && task == Task.SEARCH) return step(Decision.SEARCH_NO_CREDIT, next, "two consecutive uncredited searches");
        if (!credited && (task == Task.WEB || task == Task.EDGE)) return step(Decision.NO_CREDIT, next, "no task-specific progress observed");
        return step(Decision.ALLOW, next, credited ? "task-specific increase observed" : "no task-specific increase observed");
    }

    /** Reconcile a prior Search attempt by observing its task counter without replaying the action. */
    public static Step recoverPendingSearch(State state, Observation observation, long epochNow, long elapsedNow,
                                            long observationStarted, Limits limits) {
        if (observationStarted < 0 || elapsedNow < observationStarted)
            return step(Decision.UNREADABLE, state, "invalid recovery observation clock");
        Step checked = verify(state, Task.SEARCH, observation, epochNow, elapsedNow, false, limits);
        if (checked.decision() != Decision.ALLOW && checked.decision() != Decision.COMPLETE
                && checked.decision() != Decision.SEARCH_NO_CREDIT) return checked;
        if (observation.counter().current() == state.pendingBefore())
            return step(Decision.PENDING, state, "unchanged counter cannot resolve an unreviewed action");
        return checked;
    }

    /** Stable binding for an explicit user review, with no raw query text. */
    public static String reviewFingerprint(State state, int bootCount) {
        if (state == null || bootCount < 0 || !Task.SEARCH.name().equals(state.pendingTask())
                || !state.searchHashes().contains(state.pendingHash())) throw new IllegalArgumentException("no verifiable pending search");
        List<String> hashes = new ArrayList<>(state.searchHashes()); Collections.sort(hashes);
        String canonical = String.join("|", "search-review-v1", Integer.toString(bootCount), state.day(),
                Long.toString(state.runStartedAt()), Long.toString(state.readingStartedAt()),
                Integer.toString(state.readingAttempts()), Integer.toString(state.readingNoCredit()),
                Integer.toString(state.searchAttempts()), Integer.toString(state.searchNoCredit()),
                String.join(",", hashes), state.pendingTask(), Integer.toString(state.pendingBefore()),
                Integer.toString(state.pendingTarget()), state.pendingHash());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Caller must first verify the one-use explicit review authorization against the original state. */
    public static Step recoverReviewedSearchUiFailure(State state, Observation observation, long epochNow,
                                                       long elapsedNow, long observationStarted, Limits limits) {
        if (observationStarted < 0 || elapsedNow < observationStarted)
            return step(Decision.UNREADABLE, state, "invalid recovery observation clock");
        Step checked = verify(state, Task.SEARCH, observation, epochNow, elapsedNow, false, limits);
        if ((checked.decision() != Decision.ALLOW && checked.decision() != Decision.SEARCH_NO_CREDIT)
                || observation == null || observation.counter() == null
                || observation.counter().current() != state.pendingBefore()) return checked;
        if (elapsedNow - observationStarted < 30_000L)
            return step(Decision.PENDING, state, "observe unchanged reviewed counter for thirty seconds");
        State reconciled = checked.state();
        State next = new State(reconciled.day(), reconciled.runStartedAt(), reconciled.readingStartedAt(),
                reconciled.readingAttempts(), reconciled.readingNoCredit(), reconciled.searchAttempts(),
                state.searchNoCredit(), reconciled.searchHashes(), "", 0, 0, "");
        return step(Decision.ALLOW, next, "user reviewed UI delivery failure; counter unchanged");
    }

    /** Caller must verify a current, explicit user-observed full-target authorization. */
    public static Step completeUserObservedSearchFull(State state, long epochNow, long elapsedNow, Limits limits) {
        if (state == null) throw new IllegalArgumentException("missing state");
        Step global = guardGlobal(state, epochNow, elapsedNow, false, limits);
        if (global != null) return global;
        if (!Task.SEARCH.name().equals(state.pendingTask()) || !state.searchHashes().contains(state.pendingHash()))
            return step(Decision.PENDING, state, "no bound pending Search");
        State next = new State(state.day(), state.runStartedAt(), state.readingStartedAt(), state.readingAttempts(),
                state.readingNoCredit(), state.searchAttempts(), state.searchNoCredit(), state.searchHashes(), "", 0, 0, "");
        return step(Decision.COMPLETE, next, "user observed search target complete; no action attribution");
    }

    /** Read-only recovery of an interrupted Reading verification after an observed full task counter. */
    public static Step completeObservedPendingReading(State state, Observation observation, long epochNow,
                                                       long elapsedNow, boolean cancelled, Limits limits) {
        Step global = guardGlobal(state, epochNow, elapsedNow, cancelled, limits);
        if (global != null) return global;
        if (!Task.READING.name().equals(state.pendingTask())) return step(Decision.PENDING, state, "no pending Reading action");
        Counter count = readable(observation, Task.READING);
        if (count == null || count.current() != count.target() || count.target() != state.pendingTarget()
                || state.pendingBefore() >= count.target())
            return step(Decision.UNREADABLE, state, "pending Reading completion not proven");
        State next = new State(state.day(), state.runStartedAt(), state.readingStartedAt(),
                state.readingAttempts(), state.readingNoCredit(), state.searchAttempts(), state.searchNoCredit(),
                state.searchHashes(), "", 0, 0, "");
        return step(Decision.COMPLETE, next, "fresh full Reading counter resolves previous pending verification");
    }

    /** Resolve an interrupted Reading attempt only from a strictly increased task-specific counter. */
    public static Step recoverObservedPendingReading(State state, Observation observation, long epochNow,
                                                      long elapsedNow, boolean cancelled, Limits limits) {
        Step global=guardGlobal(state,epochNow,elapsedNow,cancelled,limits);
        if(global!=null)return global;
        if(!Task.READING.name().equals(state.pendingTask()))return step(Decision.PENDING,state,"no pending Reading action");
        Counter count=readable(observation,Task.READING);
        if(count==null || count.target()!=state.pendingTarget() || count.current()<=state.pendingBefore())
            return step(Decision.UNREADABLE,state,"increased Reading counter not proven");
        State next=new State(state.day(),state.runStartedAt(),state.readingStartedAt(),state.readingAttempts(),state.readingNoCredit(),
                state.searchAttempts(),state.searchNoCredit(),state.searchHashes(),"",0,0,"");
        return step(count.current()==count.target()?Decision.COMPLETE:Decision.ALLOW,next,"Reading counter increased; prior action not attributed");
    }

    private static Step guard(State state, Task task, long epochNow, long elapsedNow, boolean cancelled, Limits limits) {
        Step global = guardGlobal(state, epochNow, elapsedNow, cancelled, limits);
        if (global != null) return global;
        if (task == Task.READING && state.readingStartedAt() != 0
                && (elapsedNow < state.readingStartedAt() || elapsedNow - state.readingStartedAt() >= limits.maxReadingMillis())) {
            return step(Decision.READING_TIMEOUT, state, "persistent reading stage deadline reached");
        }
        return null;
    }

    private static Step guardGlobal(State state, long epochNow, long elapsedNow, boolean cancelled, Limits limits) {
        if (cancelled) return step(Decision.CANCELLED, state, "user stopped");
        if (!state.day().equals(day(epochNow))) return step(Decision.DAY_CHANGED, state, "Asia/Shanghai date changed");
        if (elapsedNow < state.runStartedAt() || elapsedNow - state.runStartedAt() >= limits.maxRunMillis()) return step(Decision.RUN_TIMEOUT, state, "run deadline reached or monotonic clock reset");
        return null;
    }

    private static Counter readable(Observation observation, Task task) {
        if (observation == null || observation.status() != ParseStatus.OK || observation.counter() == null) return null;
        Counter count = observation.counter();
        if (count.task() != task || count.current() < 0 || count.target() <= 0 || count.current() > count.target()
                || (task == Task.EDGE && !count.today())) return null;
        return count;
    }

    private static String day(long now) { return Instant.ofEpochMilli(now).atZone(SHANGHAI).toLocalDate().toString(); }
    private static Step step(Decision decision, State state, String detail) { return new Step(decision, state, detail); }
    private static Observation observation(ParseStatus status, String detail) { return new Observation(status, null, detail); }
    private static Set<String> texts(Node node) { return new HashSet<>(Arrays.asList(normalize(node.text()), normalize(node.description()))); }
    private static boolean within(Node node, String ancestor, Map<String, Node> byId) {
        for (Node current = node; current != null; current = byId.get(current.parentId())) if (current.id().equals(ancestor)) return true;
        return false;
    }

    private static Task heading(Node node) {
        for (String text : texts(node)) {
            for (Map.Entry<Task, List<String>> entry : HEADINGS.entrySet()) {
                for (String heading : entry.getValue()) {
                    if (text.equals(heading)) return entry.getKey();
                    if (text.startsWith(heading + " ")) {
                        String suffix = text.substring(heading.length()).trim();
                        if (suffix.matches("(?:今日 |今天 |today )?[+-]?\\d+\\s*/\\s*[+-]?\\d+")) return entry.getKey();
                    }
                }
            }
        }
        return null;
    }

    private static boolean todayMarker(String text) {
        return text.equals("今日") || text.equals("今天") || text.equals("today") || text.equals("今日已完成")
                || text.equals("completed today") || text.contains(" 今日 ") || text.contains(" 今天 ") || text.contains(" today ");
    }

    private static Map<Task, List<String>> headings() {
        Map<Task, List<String>> values = new EnumMap<>(Task.class);
        values.put(Task.READING, Arrays.asList("阅读以赚取", "read to earn"));
        values.put(Task.SEARCH, Arrays.asList("搜索以赚取", "search to earn", "搜索积分", "search points"));
        values.put(Task.WEB, Arrays.asList("网页每日活动", "每日活动", "daily set"));
        values.put(Task.EDGE, Arrays.asList("edge 浏览分钟", "browse with edge"));
        return Collections.unmodifiableMap(values);
    }
}
