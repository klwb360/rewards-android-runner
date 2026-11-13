package com.aliothmoon.maafw.rewards;
import java.util.*;
import io.github.klwb360.rewards.core.RewardsCore;
import static com.aliothmoon.maafw.rewards.RewardsSemanticEvidence.*;
/** Explicit fixtures, never selected from native fixture ID or OCR page. */
public final class RewardsSemanticSamples {
    private RewardsSemanticSamples() { }
    public static RewardsCore.Node node(String id,String parent,String text,boolean clickable) {
        return new RewardsCore.Node(id,parent,text,"",clickable,true,true);
    }
    public static List<RewardsCore.Node> counters(RewardsPlanning.Task task, RewardsPlanning.Scenario scenario) {
        String heading = switch(task) { case ACTIVITIES -> "每日活动"; case SEARCH -> "搜索以赚取"; case READING -> "阅读以赚取"; };
        List<RewardsCore.Node> n = new ArrayList<>();
        n.add(node("root","","",false)); n.add(node("balance","root","总积分 999999",false));
        n.add(node("task","root",heading,false));
        if (scenario != RewardsPlanning.Scenario.MISSING) n.add(node("counter","task",scenario==RewardsPlanning.Scenario.COUNTER_CONFLICT ? "3/2" : scenario==RewardsPlanning.Scenario.COMPLETE ? "2/2" : "0/2",false));
        if (scenario == RewardsPlanning.Scenario.AMBIGUOUS) n.add(node("duplicate","task","1/2",false));
        if (scenario == RewardsPlanning.Scenario.COUNTER_CONFLICT) n.add(node("flag","task","今日已完成",false));
        return n;
    }
    public static List<RewardsCore.Node> cards(int completed, String scope) {
        List<RewardsCore.Node> n=new ArrayList<>();
        n.add(node("root","","",false)); n.add(node("activities","root","",false));
        n.add(node("activitiesHeading","activities","Daily activities",false));
        n.add(node(scope,"root","",false)); n.add(node("dailyHeading",scope,"Daily set",false));
        for (int i=0;i<3;i++) {
            String label="Sample "+i+(i==completed ? ", 10 points earned" : ", sample description, earn 10 points");
            n.add(node("card"+i,scope,label,i!=completed));
            if(i!=completed)n.add(node("inner"+i,"card"+i,label,true));
        }
        return n;
    }
    public static Assessment assess(RewardsPlanning.Evidence e,RewardsPlanning.Task t,RewardsPlanning.Scenario s) {
        if (e==null) return new Assessment(null,null,Attribution.NOT_AVAILABLE,"FRESH_NATIVE_PAGE_REQUIRED");
        boolean cardScenario=s==RewardsPlanning.Scenario.CARD_CHANGE || s==RewardsPlanning.Scenario.CARD_UNCHANGED
            || s==RewardsPlanning.Scenario.OTHER_CARD_CHANGE || s==RewardsPlanning.Scenario.SCOPE_CHANGE;
        if (!cardScenario) {
            Parsed p=parse(e,t,new Input(provenance(e,1),counters(t,s)));
            return new Assessment(p,null,Attribution.NOT_AVAILABLE,"NO_PAIRED_CARD_OBSERVATIONS; ACCOUNT_UNKNOWN");
        }
        Parsed before=parse(e,t,new Input(provenance(e,1),cards(-1,"daily")));
        Parsed after=parse(e,t,new Input(provenance(e,2),cards(s==RewardsPlanning.Scenario.CARD_UNCHANGED ? -1 : s==RewardsPlanning.Scenario.OTHER_CARD_CHANGE ? 1 : 0,
            s==RewardsPlanning.Scenario.SCOPE_CHANGE ? "changedScope" : "daily")));
        return attribute(e,before,after,"card0");
    }
}
