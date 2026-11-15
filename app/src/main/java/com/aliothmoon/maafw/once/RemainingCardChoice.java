package com.aliothmoon.maafw.once;

import java.util.Locale;

/** Fixed visible choices for local diagrams only. Search, reading and check-in are excluded. */
record RemainingCardChoice(OverviewUiRegions.Role role,int index) {
    RemainingCardChoice {
        if(role==null || (role==OverviewUiRegions.Role.DAILY_PROGRESS ? index!=0
                : (role!=OverviewUiRegions.Role.DAILY_ACTIVITIES&&role!=OverviewUiRegions.Role.MORE_ACTIVITIES)||index<1||index>8))
            throw new IllegalArgumentException("Fixed remaining card choice required");
    }
    String key(){return role.name()+"_"+index;}
    String directory(){return "rewards-remaining-cards-v1-"+role.name().toLowerCase(Locale.ROOT)+"-"+index;}
    boolean weekly(){return role==OverviewUiRegions.Role.DAILY_PROGRESS;}
    String label(){return weekly()?"每日活动进度卡（只读标记）":(role==OverviewUiRegions.Role.DAILY_ACTIVITIES?"今日活动卡 ":"更多活动卡 ")+index;}
}
