package com.aliothmoon.maafw.once;

import java.util.List;

/** Rebinds hashes to the current roster. An ordinal is never a task identity. */
final class LiveTaskMatcher {
    enum State { UNIQUE, MISSING, AMBIGUOUS }
    record Match(State state,LiveUiProtocol.Item item,boolean compact){}
    static boolean nativeTitleMatches(LiveRewardsTypes.Task expected,String observedHash){
        return expected!=null&&LiveRewardsTypes.hashValue(observedHash)
                &&(expected.titleHash().isEmpty()||expected.titleHash().equals(observedHash));
    }
    static OverviewUiRegions.Role role(LiveRewardsTypes.Kind kind){return switch(kind){
        case CHECK_IN->OverviewUiRegions.Role.CHECK_IN;case DAILY->OverviewUiRegions.Role.DAILY_ACTIVITIES;case MORE->OverviewUiRegions.Role.MORE_ACTIVITIES;
    };}
    static Match find(LiveUiProtocol.Report report,LiveRewardsTypes.Task expected,List<LiveRewardsTypes.Task> cohort){
        if(report==null||!report.qualified()||expected==null)return new Match(State.MISSING,null,false);
        var role=role(expected.kind());LiveUiProtocol.Item exact=null;int matches=0;
        for(var item:report.items())if(item.role()==role&&item.descriptorHash().equals(expected.descriptorHash())
                &&(expected.kind()==LiveRewardsTypes.Kind.CHECK_IN||item.points()==expected.points())){exact=item;matches++;}
        if(matches>1)return new Match(State.AMBIGUOUS,null,false);
        if(matches==1)return new Match(State.UNIQUE,exact,false);
        if(expected.kind()==LiveRewardsTypes.Kind.CHECK_IN||expected.titleHash().isEmpty()||cohort==null)
            return new Match(State.MISSING,null,false);
        int titlePeers=0;
        for(var task:cohort)if(task.kind()==expected.kind()&&task.points()==expected.points()&&task.titleHash().equals(expected.titleHash()))titlePeers++;
        if(titlePeers!=1)return new Match(State.AMBIGUOUS,null,false);
        for(var item:report.items())if(item.role()==role&&item.points()==expected.points()&&item.descriptorHash().equals(expected.titleHash())){exact=item;matches++;}
        return matches==1?new Match(State.UNIQUE,exact,true):new Match(matches==0?State.MISSING:State.AMBIGUOUS,null,false);
    }
    private LiveTaskMatcher(){}
}
