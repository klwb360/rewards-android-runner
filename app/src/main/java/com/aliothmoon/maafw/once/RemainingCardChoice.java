package com.aliothmoon.maafw.once;

import java.util.Locale;

/** Fixed visible choices for local diagrams only. Search, reading and check-in are excluded. */
record RemainingCardChoice(OverviewUiRegions.Role role,int index,boolean repair) {
    RemainingCardChoice(OverviewUiRegions.Role role,int index){this(role,index,false);}
    RemainingCardChoice {
        if(role==null || (role==OverviewUiRegions.Role.DAILY_PROGRESS ? index!=0
                : (role!=OverviewUiRegions.Role.DAILY_ACTIVITIES&&role!=OverviewUiRegions.Role.MORE_ACTIVITIES)||index<1||index>8))
            throw new IllegalArgumentException("Fixed remaining card choice required");
        if(repair&&!(role==OverviewUiRegions.Role.DAILY_PROGRESS
                ||role==OverviewUiRegions.Role.DAILY_ACTIVITIES&&(index==1||index==3)
                ||role==OverviewUiRegions.Role.MORE_ACTIVITIES&&index==1))
            throw new IllegalArgumentException("Fixed repair review choice required");
    }
    String key(){return role.name()+"_"+index;}
    String directory(){return (repair?"rewards-card-repair-v2-":"rewards-remaining-cards-v1-")+role.name().toLowerCase(Locale.ROOT)+"-"+index;}
    String profile(){return repair?"CARD_REPAIR_V2":"REMAINING_CARDS_V1";}
    boolean weekly(){return role==OverviewUiRegions.Role.DAILY_PROGRESS;}
    String label(){return (repair?"修复 V2 · ":"")+(weekly()?"每日活动进度卡（只读标记）":(role==OverviewUiRegions.Role.DAILY_ACTIVITIES?"今日活动卡 ":"更多活动卡 ")+index);}
}
