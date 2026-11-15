package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.List;

/** Scalar card roots under the current known Rewards containers. No title text or input grant. */
final class RemainingCardUi {
    static final int MAX_PER_ROLE=8;
    enum Status { OBSERVED, WRONG_STRUCTURE, AMBIGUOUS_STRUCTURE }
    record Item(OverviewUiRegions.Role role,int index,Box coarse,Box title,Box reward,boolean completeVisible) {
        Item {
            if(!activity(role)||index<1||index>MAX_PER_ROLE||!valid(coarse)
                    ||title!=null&&(!valid(title)||!coarse.contains(title))
                    ||reward!=null&&(!valid(reward)||!coarse.contains(reward)))throw invalid();
        }
    }
    record Result(Status status,int dailyCount,int moreCount,List<Item> items) {
        Result {
            if(status==null||items==null||dailyCount< -1||dailyCount>MAX_PER_ROLE||moreCount< -1||moreCount>MAX_PER_ROLE
                    ||items.size()>Math.max(0,dailyCount)+Math.max(0,moreCount)||status!=Status.OBSERVED&&(dailyCount!=-1||moreCount!=-1||!items.isEmpty()))throw invalid();
            items=List.copyOf(items);int previousRole=-1,previousIndex=-1;
            for(int i=0;i<items.size();i++) {
                Item item=items.get(i);if(item==null||item.index()>(item.role()==OverviewUiRegions.Role.DAILY_ACTIVITIES?dailyCount:moreCount)
                        ||item.role().ordinal()<previousRole||item.role().ordinal()==previousRole&&item.index()<=previousIndex)throw invalid();
                previousRole=item.role().ordinal();previousIndex=item.index();
                for(int j=0;j<i;j++)if(item.coarse().overlaps(items.get(j).coarse()))throw invalid();
            }
        }
        static Result rejected(Status status){if(status==Status.OBSERVED)throw invalid();return new Result(status,-1,-1,List.of());}
    }
    static boolean activity(OverviewUiRegions.Role role){return role==OverviewUiRegions.Role.DAILY_ACTIVITIES||role==OverviewUiRegions.Role.MORE_ACTIVITIES;}
    private static boolean valid(Box box){return box!=null&&box.left()>=0&&box.top()>=0&&box.right()>box.left()&&box.bottom()>box.top()&&box.right()<=8192&&box.bottom()<=8192;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid fixed remaining card geometry");}
    private RemainingCardUi(){}
}
