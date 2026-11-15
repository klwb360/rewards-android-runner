package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;

/** One exact structural slot. It never substitutes the whole activity container for a card. */
final class RemainingCardTarget {
    record Result(CardRevealProbeTarget.Selection selection,String reason,int dailyCount,int moreCount){}
    static Result select(OverviewUiProtocol.Report report,int width,int height,RemainingCardChoice choice){
        if(choice==null||report==null||!report.qualified()||report.cards()==null
                ||report.cards().status()!=RemainingCardUi.Status.OBSERVED||width<=0||height<=width)
            return held(CardRevealProbeTarget.State.UNQUALIFIED,"UNQUALIFIED_CARD_UI",-1,-1);
        int daily=report.cards().dailyCount(),more=report.cards().moreCount();
        OverviewUiRegions.Region parent=null;
        for(var r:report.overview().regions())if(r.role()==choice.role()){
            if(parent!=null)return held(CardRevealProbeTarget.State.UNQUALIFIED,"AMBIGUOUS_PARENT",daily,more);parent=r;
        }
        if(parent!=null&&parent.target()>0&&parent.current()==parent.target())
            return held(CardRevealProbeTarget.State.UNQUALIFIED,"REGION_COMPLETE_SKIP",daily,more);
        OverviewUiRegions.Region selected=parent;
        if(!choice.weekly()){
            int count=choice.role()==OverviewUiRegions.Role.DAILY_ACTIVITIES?daily:more;
            if(count>=0&&choice.index()>count)return held(CardRevealProbeTarget.State.UNQUALIFIED,"NO_SUCH_CARD",daily,more);
            RemainingCardUi.Item target=null;int first=9,last=0;
            for(var item:report.cards().items())if(item.role()==choice.role()){
                first=Math.min(first,item.index());last=Math.max(last,item.index());
                if(item.index()==choice.index()){if(target!=null)return held(CardRevealProbeTarget.State.UNQUALIFIED,"AMBIGUOUS_CARD",daily,more);target=item;}
            }
            if(target==null){
                var direction=last>0&&choice.index()<first?ReviewShizukuSwipeActor.Direction.DOWN:ReviewShizukuSwipeActor.Direction.UP;
                return scroll(report,direction,daily,more);
            }
            selected=new OverviewUiRegions.Region(choice.role(),target.coarse(),target.title(),null,target.completeVisible(),-1,-1);
        }
        if(selected==null)return scroll(report,ReviewShizukuSwipeActor.Direction.UP,daily,more);
        var view=report.overview().viewport();Box coarse=selected.coarse();
        if(view==null||view.left()<0||view.top()<0||view.right()>width||view.bottom()>height)
            return held(CardRevealProbeTarget.State.UNQUALIFIED,"INVALID_VIEWPORT",daily,more);
        int top=Math.max(view.top()+1,height/5),bottom=Math.min(view.bottom()-1,height*9/10);
        if(bottom<=top||coarse.height()+24>bottom-top)return held(CardRevealProbeTarget.State.TOO_LARGE,"CARD_TOO_LARGE",daily,more);
        if(coarse.top()-12<top)return scroll(report,ReviewShizukuSwipeActor.Direction.DOWN,daily,more);
        if(coarse.bottom()+12>bottom)return scroll(report,ReviewShizukuSwipeActor.Direction.UP,daily,more);
        if(!selected.completeVisible())return held(CardRevealProbeTarget.State.UNQUALIFIED,"CARD_PARTIAL",daily,more);
        Box crop=new Box(coarse.left()-12,coarse.top()-12,coarse.right()+12,coarse.bottom()+12);
        if(crop.left()<=view.left()||crop.right()>=view.right()||crop.top()<=view.top()||crop.bottom()>=view.bottom()
                ||(long)crop.width()*crop.height()>LocalCardMockPlan.MAX_PIXELS)
            return held(CardRevealProbeTarget.State.UNQUALIFIED,"INVALID_CARD_CROP",daily,more);
        return new Result(new CardRevealProbeTarget.Selection(CardRevealProbeTarget.State.CANDIDATE,selected,crop,null),"READY",daily,more);
    }
    private static Result scroll(OverviewUiProtocol.Report report,ReviewShizukuSwipeActor.Direction direction,int daily,int more){
        var hint=report.scrollHint();boolean allowed=direction==ReviewShizukuSwipeActor.Direction.UP
                ?hint==OverviewUiProtocol.ScrollHint.DECLARED_FORWARD||hint==OverviewUiProtocol.ScrollHint.DECLARED_BOTH
                :hint==OverviewUiProtocol.ScrollHint.DECLARED_BACKWARD||hint==OverviewUiProtocol.ScrollHint.DECLARED_BOTH;
        return new Result(new CardRevealProbeTarget.Selection(allowed?CardRevealProbeTarget.State.SCROLL:CardRevealProbeTarget.State.NO_DIRECTION,
                null,null,allowed?direction:null),allowed?"SCROLL":"NO_DECLARED_DIRECTION",daily,more);
    }
    private static Result held(CardRevealProbeTarget.State state,String reason,int daily,int more){return new Result(new CardRevealProbeTarget.Selection(state,null,null,null),reason,daily,more);}
    private RemainingCardTarget(){}
}
