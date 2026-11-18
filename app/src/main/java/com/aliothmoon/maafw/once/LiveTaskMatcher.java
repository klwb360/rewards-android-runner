package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.List;

/** Rebinds hashes to the current roster. An ordinal is never a task identity. */
final class LiveTaskMatcher {
    enum State { UNIQUE, MISSING, AMBIGUOUS }
    record Match(State state,LiveUiProtocol.Item item,boolean compact){}

    record Identity(String descriptorHash, int points) {}
    /** A complete, ordered list from one qualified region read. Off-screen siblings are included. */
    record RegionSnapshot(LiveRewardsTypes.Kind kind, List<Identity> entries) {
        RegionSnapshot { entries = List.copyOf(entries); }

        boolean matchesPreflight(List<LiveRewardsTypes.Task> tasks) {
            if (tasks == null || tasks.size() != entries.size()) return false;
            var remaining = new ArrayList<>(entries);
            for (var task : tasks) {
                if (task.kind() != kind || !remaining.remove(identity(task))) return false;
            }
            return true;
        }
    }

    static RegionSnapshot regionSnapshot(LiveUiProtocol.Report report, LiveRewardsTypes.Kind kind) {
        if (report == null || !report.qualified() || kind == null) return null;
        int count = switch (kind) {
            case CHECK_IN -> 1;
            case DAILY -> report.base().cards().dailyCount();
            case MORE -> report.base().cards().moreCount();
        };
        if (count < 1 || count > RemainingCardUi.MAX_PER_ROLE) return null;
        var entries = new ArrayList<Identity>(count);
        for (var item : report.items()) {
            if (item.role() != role(kind)) continue;
            int index = kind == LiveRewardsTypes.Kind.CHECK_IN ? 0 : entries.size() + 1;
            if (item.index() != index) return null;
            entries.add(new Identity(item.descriptorHash(), item.points()));
        }
        return entries.size() == count ? new RegionSnapshot(kind, entries) : null;
    }

    private static Identity identity(LiveRewardsTypes.Task task) {
        return new Identity(task.descriptorHash(), task.kind() == LiveRewardsTypes.Kind.CHECK_IN ? -1 : task.points());
    }

    static boolean uniqueInPlan(LiveRewardsTypes.Task task, List<LiveRewardsTypes.Task> cohort) {
        if (task == null || cohort == null) return false;
        var key = identity(task);
        int matches = 0;
        for (var peer : cohort) {
            if (peer.kind() == task.kind() && key.equals(identity(peer))) matches++;
        }
        return matches == 1;
    }

    /** A change to an unrelated sibling cannot remove a full, unique identity from the plan. */
    static boolean admits(LiveUiProtocol.Report report, LiveRewardsTypes.Task expected, List<LiveRewardsTypes.Task> cohort) {
        var match = find(report, expected, cohort);
        return !match.compact() && boundCurrentMatch(report, expected, cohort, match);
    }

    static boolean boundCurrentMatch(LiveUiProtocol.Report report, LiveRewardsTypes.Task expected,
            List<LiveRewardsTypes.Task> cohort, Match match) {
        if (match.state() != State.UNIQUE || !uniqueInPlan(expected, cohort)
                || regionSnapshot(report, expected.kind()) == null) return false;
        if (expected.kind() == LiveRewardsTypes.Kind.CHECK_IN
                || !match.compact() && !expected.descriptorHash().equals(expected.titleHash())) return true;

        // A title-only row could be a different card's compact form. Rule out same-value
        // peers in the preflight AND in the current region, including titles added since.
        if (!uniqueKnownTitle(expected, cohort)) return false;
        for (var item : report.items()) {
            if (item.role() != match.item().role() || item.points() != expected.points()
                    || item.index() == match.item().index()) continue;
            int owners = 0;
            for (var peer : cohort) {
                if (peer.kind() != expected.kind() || peer.points() != expected.points()
                        || peer.descriptorHash().equals(expected.descriptorHash())) continue;
                var other = find(report, peer, cohort);
                if (other.state() == State.UNIQUE && other.item().index() == item.index()) owners++;
            }
            if (owners != 1) return false;
        }
        return true;
    }
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
        if(matches==1){
            // A descriptor without a description is title-only too: another card's completed
            // compact form can have this exact hash. Require the same uniqueness proof.
            if(expected.kind()!=LiveRewardsTypes.Kind.CHECK_IN&&!expected.titleHash().isEmpty()
                    &&expected.descriptorHash().equals(expected.titleHash())&&!uniqueKnownTitle(expected,cohort))
                return new Match(State.AMBIGUOUS,null,false);
            return new Match(State.UNIQUE,exact,false);
        }
        if(expected.kind()==LiveRewardsTypes.Kind.CHECK_IN||expected.titleHash().isEmpty()||cohort==null)
            return new Match(State.MISSING,null,false);
        if(!uniqueKnownTitle(expected,cohort))return new Match(State.AMBIGUOUS,null,false);
        for(var item:report.items())if(item.role()==role&&item.points()==expected.points()&&item.descriptorHash().equals(expected.titleHash())){exact=item;matches++;}
        return matches==1?new Match(State.UNIQUE,exact,true):new Match(matches==0?State.MISSING:State.AMBIGUOUS,null,false);
    }

    /** Strict historical whole-roster comparison, including compact rows. */
    static boolean sameRoster(LiveUiProtocol.Report report, LiveRewardsTypes.Kind kind, List<LiveRewardsTypes.Task> cohort) {
        if (report == null || !report.qualified() || kind == null || cohort == null || cohort.isEmpty()) return false;
        int count = switch (kind) {
            case CHECK_IN -> 1;
            case DAILY -> report.base().cards().dailyCount();
            case MORE -> report.base().cards().moreCount();
        };
        if (count != cohort.size()) return false;
        boolean[] matched = new boolean[count];
        for (var expected : cohort) {
            if (expected.kind() != kind) return false;
            var match = find(report, expected, cohort);
            if (match.state() != State.UNIQUE) return false;
            int index = kind == LiveRewardsTypes.Kind.CHECK_IN ? match.item().index() : match.item().index() - 1;
            if (index < 0 || index >= count || matched[index]) return false;
            matched[index] = true;
        }
        return true;
    }

    private static boolean uniqueKnownTitle(LiveRewardsTypes.Task expected,List<LiveRewardsTypes.Task> cohort){
        if(cohort==null)return false;int titlePeers=0;
        for(var task:cohort)if(task.kind()==expected.kind()&&task.points()==expected.points()){
            // Skipped native reads leave peer titles unknown; those cannot be ruled out.
            if(task.titleHash().isEmpty())return false;
            if(task.titleHash().equals(expected.titleHash()))titlePeers++;
        }
        return titlePeers==1;
    }
    private LiveTaskMatcher(){}
}
