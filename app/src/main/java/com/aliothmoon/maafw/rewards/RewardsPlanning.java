package com.aliothmoon.maafw.rewards;

import io.github.klwb360.rewards.core.RewardsCore;
import java.util.List;
import java.util.Objects;

/** Pure synthetic preparation only. No Android, network, input, persistence or real executor. */
public final class RewardsPlanning {
    private RewardsPlanning() { }
    public enum Page { UNKNOWN, REWARDS_HOME, SIGN_IN_NEEDED, CONFLICT }
    public enum Task { ACTIVITIES, SEARCH, READING }
    public enum Scenario { READY, COMPLETE, MISSING, AMBIGUOUS, PENDING, NO_CREDIT, COUNTER_CONFLICT, CARD_CHANGE, CARD_UNCHANGED, OTHER_CARD_CHANGE, SCOPE_CHANGE }
    public enum Decision { NO_OBSERVATION, MANUAL_SIGN_IN, CONFLICT, UNKNOWN_PAGE,
        PROPOSED, SAMPLE_COMPLETE, NEEDS_EVIDENCE, PENDING_VERIFICATION, BLOCKED }
    public enum Action { WOULD_OPEN_DAILY_SET, WOULD_SELECT_UNIQUE_CARD,
        WOULD_OPEN_SEARCH, WOULD_ENTER_SAMPLE_QUERY, WOULD_SUBMIT_SAMPLE_QUERY,
        WOULD_OPEN_READING_FEED, WOULD_SELECT_UNIQUE_ARTICLE, WOULD_WAIT_FOR_CONTENT,
        REQUIRE_NEW_TASK_COUNTER, REQUIRE_SAME_CARD_SCOPE }
    public enum Outcome { PREVIEW_ONLY_UNVERIFIED }

    /** Scalar observation accepted only from the private synthetic worker's completed generation.
     * Native OCR establishes the coarse page, never task counters/account credit.
     */
    public record Evidence(long epoch, int attempt, int requestId, Page page, int ownerUid) {
        public Evidence {
            if (epoch < 1 || attempt < 1 || requestId < 1 || ownerUid < 10000) throw new IllegalArgumentException("invalid generation");
            Objects.requireNonNull(page);
        }
    }
    public static Evidence accept(long epoch, int attempt, int requestId, String phase,
            String cleanup, String origin, int uid, String page) {
        if (!"COMPLETED".equals(phase) || !"CONFIRMED".equals(cleanup) || !"SYNTHETIC".equals(origin)
                || uid < 10000 || epoch < 1 || attempt < 1 || requestId < 1) return null;
        try { return new Evidence(epoch, attempt, requestId, Page.valueOf(page), uid); }
        catch (IllegalArgumentException | NullPointerException invalid) { return null; }
    }
    public record Sample(Task task, Scenario scenario, RewardsCore.State state,
                         RewardsCore.Observation observation, RewardsSemanticEvidence.Assessment semantic) { }
    public record Plan(Evidence evidence, Task task, Scenario scenario, Decision decision,
                       String reason, List<Action> actions, RewardsSemanticEvidence.Assessment semantic) {
        public Plan { actions = List.copyOf(actions); }
        public boolean previewable() { return decision == Decision.PROPOSED; }
    }
    public record Preview(Evidence evidence, Task task, Scenario scenario, Outcome outcome,
                          List<Action> wouldDo, boolean accountProgressKnown, int executedActions) {
        public Preview { wouldDo = List.copyOf(wouldDo); }
    }
    // Deterministic fixture clock only; never the user's live daily state or authorization.
    public static final long SAMPLE_EPOCH = 1794268800000L;
    public static final long SAMPLE_ELAPSED = 1000L;
    private static final String SAMPLE_TERM = "synthetic sample query";
    private static RewardsCore.Task coreTask(Task task) {
        return switch (task) { case ACTIVITIES -> RewardsCore.Task.WEB;
            case SEARCH -> RewardsCore.Task.SEARCH; case READING -> RewardsCore.Task.READING; };
    }
    private static Sample sample(Task task, Scenario scenario, RewardsSemanticEvidence.Assessment semantic) {
        Objects.requireNonNull(task); Objects.requireNonNull(scenario);
        RewardsCore.Task kind = coreTask(task);
        RewardsCore.State state = RewardsCore.fresh(SAMPLE_EPOCH, SAMPLE_ELAPSED);
        RewardsCore.Observation observation = semantic.before() == null
            ? new RewardsCore.Observation(RewardsCore.ParseStatus.MISSING,null,"NO_SEMANTIC_INPUT")
            : semantic.before().counter();
        if (scenario == Scenario.PENDING) state = RewardsCore.prepare(state, kind, observation,
            SAMPLE_TERM, SAMPLE_EPOCH, SAMPLE_ELAPSED, false, RewardsCore.Limits.defaults()).state();
        if (scenario == Scenario.NO_CREDIT && task != Task.ACTIVITIES) {
            int attempts = task == Task.SEARCH ? 2 : 4;
            for (int n = 0; n < attempts; n++) {
                state = RewardsCore.prepare(state, kind, observation, SAMPLE_TERM + n,
                    SAMPLE_EPOCH, SAMPLE_ELAPSED, false, RewardsCore.Limits.defaults()).state();
                state = RewardsCore.verify(state, kind, observation, SAMPLE_EPOCH, SAMPLE_ELAPSED,
                    false, RewardsCore.Limits.defaults()).state();
            }
        }
        return new Sample(task, scenario, state, observation, semantic);
    }
    public static Plan plan(Evidence evidence, Task task, Scenario scenario) {
        Sample sample = sample(task, scenario, RewardsSemanticSamples.assess(evidence,task,scenario));
        if (evidence == null) return result(null, sample, Decision.NO_OBSERVATION, "FRESH_CLEAN_NATIVE_OBSERVATION_REQUIRED", List.of());
        if (evidence.page() != Page.REWARDS_HOME) return result(evidence, sample,
            switch(evidence.page()) { case SIGN_IN_NEEDED -> Decision.MANUAL_SIGN_IN;
                case CONFLICT -> Decision.CONFLICT; default -> Decision.UNKNOWN_PAGE; }, "NO_ACTIONS_FOR_THIS_PAGE", List.of());
        if (sample.semantic().attribution() == RewardsSemanticEvidence.Attribution.CONFLICT
            || (sample.semantic().before() != null &&
                (sample.semantic().before().confidence() == RewardsSemanticEvidence.Confidence.CONFLICT
                 || sample.semantic().before().confidence() == RewardsSemanticEvidence.Confidence.REJECTED)))
            return result(evidence,sample,Decision.CONFLICT,"SEMANTIC_EVIDENCE_CONFLICT",List.of());
        if (scenario == Scenario.NO_CREDIT && task == Task.ACTIVITIES)
            return result(evidence, sample, Decision.BLOCKED, "NO_CREDIT_REQUIRES_CARD_SCOPE_REVIEW", List.of());
        RewardsCore.Step prepared = RewardsCore.prepare(sample.state(), coreTask(task), sample.observation(),
            SAMPLE_TERM, SAMPLE_EPOCH, SAMPLE_ELAPSED, false, RewardsCore.Limits.defaults());
        // ALLOW in the historical policy is only a proposal here. Returned pending state is NOT committed.
        Decision decision = switch(prepared.decision()) {
            case ALLOW -> Decision.PROPOSED;
            case COMPLETE -> Decision.SAMPLE_COMPLETE;
            case UNREADABLE -> Decision.NEEDS_EVIDENCE;
            case PENDING -> Decision.PENDING_VERIFICATION;
            default -> Decision.BLOCKED;
        };
        List<Action> actions = decision != Decision.PROPOSED ? List.of() : switch(task) {
            case ACTIVITIES -> List.of(Action.WOULD_OPEN_DAILY_SET, Action.WOULD_SELECT_UNIQUE_CARD,
                Action.REQUIRE_NEW_TASK_COUNTER, Action.REQUIRE_SAME_CARD_SCOPE);
            case SEARCH -> List.of(Action.WOULD_OPEN_SEARCH, Action.WOULD_ENTER_SAMPLE_QUERY,
                Action.WOULD_SUBMIT_SAMPLE_QUERY, Action.REQUIRE_NEW_TASK_COUNTER);
            case READING -> List.of(Action.WOULD_OPEN_READING_FEED, Action.WOULD_SELECT_UNIQUE_ARTICLE,
                Action.WOULD_WAIT_FOR_CONTENT, Action.REQUIRE_NEW_TASK_COUNTER);
        };
        return result(evidence, sample, decision, decision == Decision.NEEDS_EVIDENCE ? "NEED_TASK_COUNTERS" : prepared.decision().name(), actions);
    }
    private static Plan result(Evidence evidence, Sample sample, Decision decision, String reason, List<Action> actions) {
        return new Plan(evidence, sample.task(), sample.scenario(), decision, reason, actions, sample.semantic());
    }
    /** Fail closed on stale/modified plans. Returns a description; performs ZERO actions and awards ZERO credit. */
    public static Preview fakePreview(Plan plan, Evidence current, Task task, Scenario scenario) {
        if (plan == null || current == null || !current.equals(plan.evidence()) || task != plan.task()
                || scenario != plan.scenario() || !plan.previewable() || !plan.equals(plan(current, task, scenario)))
            throw new IllegalStateException("stale or blocked synthetic proposal");
        return new Preview(current, task, scenario, Outcome.PREVIEW_ONLY_UNVERIFIED, plan.actions(), false, 0);
    }
    /** In-memory UI coordinator. Updating evidence/selection invalidates both outputs. No replay/persistence. */
    public static final class Session {
        private Evidence evidence;
        private Task task = Task.ACTIVITIES;
        private Scenario scenario = Scenario.MISSING;
        private Plan plan;
        private Preview preview;
        public Evidence evidence() { return evidence; }
        public Task task() { return task; }
        public Scenario scenario() { return scenario; }
        public Plan plan() { return plan; }
        public Preview preview() { return preview; }
        public void observe(Evidence next) { if (!Objects.equals(evidence, next)) { evidence = next; scenario = Scenario.MISSING; clear(); } }
        public void invalidate() { evidence = null; scenario = Scenario.MISSING; clear(); }
        public void select(Task next, Scenario fixture) {
            Objects.requireNonNull(next); Objects.requireNonNull(fixture);
            if (task != next || scenario != fixture) { task = next; scenario = fixture; clear(); }
        }
        public void prepare() { plan = RewardsPlanning.plan(evidence, task, scenario); preview = null; }
        public void previewExplicitly() { preview = fakePreview(plan, evidence, task, scenario); }
        private void clear() { plan = null; preview = null; }
    }
}

