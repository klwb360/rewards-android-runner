package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class LiveTaskMatcherTest {
    static LiveUiProtocol.Report roster(String... descriptors){
        var role=OverviewUiRegions.Role.MORE_ACTIVITIES;List<RemainingCardUi.Item> cards=new ArrayList<>();List<LiveUiProtocol.Item> items=new ArrayList<>();
        for(int i=0;i<descriptors.length;i++){
            Box box=new Box(112,400+i*200,1328,540+i*200);cards.add(new RemainingCardUi.Item(role,i+1,box,null,null,true));
            items.add(new LiveUiProtocol.Item(role,i+1,LiveCardIdentity.descriptor(descriptors[i]),5,box,true));
        }
        var region=new OverviewUiRegions.Region(role,new Box(64,200,1376,2200),new Box(80,210,500,250),null,true,-1,-1);
        var overview=new OverviewUiRegions.Result(OverviewUiRegions.Status.OBSERVED,new Box(0,0,1440,2560),List.of(region));
        var base=new OverviewUiProtocol.Report(LiveUiProtocolTest.source(2560),overview,OverviewUiProtocol.ScrollHint.DECLARED_BOTH,
                new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED,-1,descriptors.length,cards));
        return new LiveUiProtocol.Report(base,items);
    }
    static LiveRewardsTypes.Task task(String descriptor,String title,int ordinal){return new LiveRewardsTypes.Task(LiveRewardsTypes.Kind.MORE,
            LiveCardIdentity.descriptor(descriptor),ordinal,LiveCardIdentity.hashText(title),5,true,false,-1);}
    @Test public void completedCompressionAndReorderingUseFreshIdentityInsteadOfOldIndex(){
        var expected=task("Second task, Its description, + 5 积分","Second task",2);
        var other=task("First task, Another description, + 5 积分","First task",1);
        var match=LiveTaskMatcher.find(roster("Second task, 5 积分","First task, Another description, + 5 积分"),expected,List.of(expected,other));
        assertEquals(LiveTaskMatcher.State.UNIQUE,match.state());assertTrue(match.compact());assertEquals(1,match.item().index());
    }
    @Test public void repeatedDescriptorsAndAmbiguousCompactTitlesNeverProduceAUniqueMatch(){
        var a=task("Repeated title, Alpha, + 5 积分","Repeated title",1);var b=task("Repeated title, Beta, + 5 积分","Repeated title",2);
        assertEquals(LiveTaskMatcher.State.AMBIGUOUS,LiveTaskMatcher.find(roster("Repeated title, 5 积分"),a,List.of(a,b)).state());
        var repeated=roster("Repeated title, Alpha, + 5 积分","Repeated title, Alpha, + 5 积分");
        assertEquals(LiveTaskMatcher.State.AMBIGUOUS,LiveTaskMatcher.find(repeated,a,List.of(a)).state());
    }
    @Test public void aNewDailyTitleOrSameOrdinalIsNeverSubstitutedForTheExpectedCard(){
        var expected=task("Old task, Old description, + 5 积分","Old task",1);
        assertEquals(LiveTaskMatcher.State.MISSING,LiveTaskMatcher.find(roster("New task, New description, + 5 积分"),expected,List.of(expected)).state());
        var daily=new LiveRewardsTypes.Task(LiveRewardsTypes.Kind.DAILY,expected.descriptorHash(),1,expected.titleHash(),5,true,false,-1);
        assertEquals(LiveTaskMatcher.State.MISSING,LiveTaskMatcher.find(roster("Old task, Old description, + 5 积分"),daily,List.of(daily)).state());
    }
    @Test public void titleOnlyFallbackRequiresTheNativePreflightTitleCohort(){
        var expected=task("Task name, Description, + 5 积分","Task name",1);
        assertEquals(LiveTaskMatcher.State.AMBIGUOUS,LiveTaskMatcher.find(roster("Task name, 5 积分"),expected,List.of()).state());
        var unread=new LiveRewardsTypes.Task(expected.kind(),expected.descriptorHash(),1,"",5,false,false,-1);
        assertEquals(LiveTaskMatcher.State.MISSING,LiveTaskMatcher.find(roster("Task name, 5 积分"),unread,List.of(unread)).state());
    }
    @Test public void aFreshExactUiDescriptorDoesNotAuthorizeADifferentNativeTitleAfterPreflight(){
        var expected=task("Task name, Description, + 5 积分","Task name",1);
        var sameUi=LiveTaskMatcher.find(roster("Task name, Description, + 5 积分"),expected,List.of(expected));
        assertEquals(LiveTaskMatcher.State.UNIQUE,sameUi.state());assertFalse(sameUi.compact());
        assertTrue(LiveTaskMatcher.nativeTitleMatches(expected,LiveCardIdentity.hashText(" Task  NAME ")));
        assertFalse(LiveTaskMatcher.nativeTitleMatches(expected,LiveCardIdentity.hashText("A refreshed different task")));
        assertFalse(LiveTaskMatcher.nativeTitleMatches(expected,""));assertFalse(LiveTaskMatcher.nativeTitleMatches(expected,null));
        var seed=new LiveRewardsTypes.Task(expected.kind(),expected.descriptorHash(),1,"",5,false,false,-1);
        assertTrue(LiveTaskMatcher.nativeTitleMatches(seed,expected.titleHash()));
        assertFalse(LiveTaskMatcher.nativeTitleMatches(seed,""));
    }
}
