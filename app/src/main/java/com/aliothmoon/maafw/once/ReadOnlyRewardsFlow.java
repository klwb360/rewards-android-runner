package com.aliothmoon.maafw.once;

/** Read-only planning only. Has no input, URL, tasker action, account or claim API. */
final class ReadOnlyRewardsFlow {
    enum Step { NO_RESULT, MANUAL_REVIEW_DAILY_SET, MANUAL_SIGN_IN, STOP_UNKNOWN, STOP_CONFLICT }
    static Step plan(RewardsObservation observation) {
        if (observation == null) return Step.NO_RESULT;
        return switch (observation.page()) {
            case REWARDS_HOME -> Step.MANUAL_REVIEW_DAILY_SET;
            case SIGN_IN_NEEDED -> Step.MANUAL_SIGN_IN;
            case UNKNOWN -> Step.STOP_UNKNOWN;
            case CONFLICT -> Step.STOP_CONFLICT;
        };
    }
    private ReadOnlyRewardsFlow() { }
}
