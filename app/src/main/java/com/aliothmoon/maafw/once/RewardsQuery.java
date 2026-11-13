package com.aliothmoon.maafw.once;

/** Compiled catalogue only. Maa OCR expected is a regex string/list.
 * Explicit ASCII letter classes avoid engine-specific inline case flags.
 */
enum RewardsQuery {
    REWARDS_MARKER("[Rr][Ee][Ww][Aa][Rr][Dd][Ss]|微软奖励"),
    SIGN_IN_PROMPT("[Ss][Ii][Gg][Nn][ -]*[Ii][Nn]|登录|登入"),
    DAILY_SET("[Dd][Aa][Ii][Ll][Yy][ ]*[Ss][Ee][Tt]|每日任务|每日集|每日活动|网页每日活动"),
    PROGRESS_HINT("[Pp][Rr][Oo][Gg][Rr][Ee][Ss][Ss]|[Pp][Oo][Ii][Nn][Tt][Ss][ ]*[Ee][Aa][Rr][Nn][Ee][Dd]|进度|已赚取");

    private final String parameters;
    RewardsQuery(String expected) {
        // Only enum literals reach this constructor. No dynamic OCR input.
        parameters = "{\"expected\":[\"" + expected + "\"],\"threshold\":0.3}";
    }
    String parameters() { return parameters; }
}
