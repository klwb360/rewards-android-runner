package com.aliothmoon.maafw.once;

/** Coarse hints, never point balances or proof of a completed reward.
 * UNKNOWN means insufficient catalogue evidence, not no text or native error.
 */
record RewardsObservation(Page page, boolean rewardsMarker, boolean signInPrompt,
                          boolean dailySetHint, boolean progressHint) {
    enum Page { UNKNOWN, REWARDS_HOME, SIGN_IN_NEEDED, CONFLICT }
    static RewardsObservation classify(boolean marker, boolean signIn, boolean daily, boolean progress) {
        Page page = Page.UNKNOWN;
        if (marker) {
            if (signIn && (daily || progress)) page = Page.CONFLICT;
            else if (signIn) page = Page.SIGN_IN_NEEDED;
            else if (daily || progress) page = Page.REWARDS_HOME;
        }
        return new RewardsObservation(page, marker, signIn, daily, progress);
    }
}
