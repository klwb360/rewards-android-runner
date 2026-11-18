package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;

/** One exact structural slot. It never substitutes the whole activity container for a card. */
final class RemainingCardTarget {
    record Result(CardRevealProbeTarget.Selection selection,String reason,int dailyCount,int moreCount,
            int edgeGap,int verticalSlack){
        Result(CardRevealProbeTarget.Selection selection,String reason,int dailyCount,int moreCount){
            this(selection,reason,dailyCount,moreCount,0,0);
        }
        ReviewShizukuSwipeActor.SwipeTarget swipeTarget(BingNavigationSnapshot.Shape shape,
                ReviewShizukuSwipeActor.SwipeTarget previous){
            if(shape==null||selection.state()!=CardRevealProbeTarget.State.SCROLL||selection.direction()==null)
                throw new IllegalStateException("Current scroll selection required");
            int height=shape.height();
            int full=ReviewShizukuSwipeActor.SwipeTarget.standardDistance(height);
            int distance=full;
            if(edgeGap>0){
                // Move just past the violated edge. Leave room for the opposite border on tall cards.
                int inset=Math.min(height/20,verticalSlack/2);
                distance=Math.max(ReviewShizukuSwipeActor.MIN_DISTANCE,edgeGap+inset);
            }
            if(previous!=null && previous.fullDisplayWidth()==shape.width() && previous.fullDisplayHeight()==height
                    && previous.rotation()==shape.rotation()){
                if(previous.direction()!=selection.direction()){
                    // Crossing the card brackets it between two observations. Reduce the return stride.
                    distance=Math.min(distance,Math.max(ReviewShizukuSwipeActor.MIN_DISTANCE,previous.distance()/2));
                }else if(edgeGap==0){
                    // While searching without an edge, do not immediately undo a fine correction.
                    distance=Math.min(distance,previous.distance());
                }
            }
            return new ReviewShizukuSwipeActor.SwipeTarget(shape.width(),height,shape.rotation(),selection.direction(),Math.min(full,distance));
        }
    }
    static Result select(OverviewUiProtocol.Report report,int width,int height,RemainingCardChoice choice){
        return select(report,width,height,choice,null);
    }
    /** Search memory chooses travel only. It never supplies a source permit, crop or point. */
    static Result select(OverviewUiProtocol.Report report,int width,int height,RemainingCardChoice choice,
            ReviewShizukuSwipeActor.Direction previousSearch){
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
                if(last>0){
                    if(choice.index()<first)return scroll(report,ReviewShizukuSwipeActor.Direction.DOWN,daily,more);
                    if(choice.index()>last)return scroll(report,ReviewShizukuSwipeActor.Direction.UP,daily,more);
                    return held(CardRevealProbeTarget.State.UNQUALIFIED,"AMBIGUOUS_CARD_GAP",daily,more);
                }
                return search(report,choice,parent,previousSearch,daily,more);
            }
            selected=new OverviewUiRegions.Region(choice.role(),target.coarse(),target.title(),null,target.completeVisible(),-1,-1);
        }
        if(selected==null)return search(report,choice,parent,previousSearch,daily,more);
        var view=report.overview().viewport();Box coarse=selected.coarse();
        if(view==null||view.left()<0||view.top()<0||view.right()>width||view.bottom()>height)
            return held(CardRevealProbeTarget.State.UNQUALIFIED,"INVALID_VIEWPORT",daily,more);
        int top=Math.max(view.top()+1,height/5),bottom=Math.min(view.bottom()-1,height*9/10);
        if(bottom<=top||coarse.height()+24>bottom-top)return held(CardRevealProbeTarget.State.TOO_LARGE,"CARD_TOO_LARGE",daily,more);
        int slack=bottom-top-coarse.height()-24;
        if(coarse.top()-12<top)return scroll(report,ReviewShizukuSwipeActor.Direction.DOWN,daily,more,top-(coarse.top()-12),slack);
        if(coarse.bottom()+12>bottom)return scroll(report,ReviewShizukuSwipeActor.Direction.UP,daily,more,coarse.bottom()+12-bottom,slack);
        if(!selected.completeVisible())return held(CardRevealProbeTarget.State.UNQUALIFIED,"CARD_PARTIAL",daily,more);
        Box crop=new Box(coarse.left()-12,coarse.top()-12,coarse.right()+12,coarse.bottom()+12);
        if(crop.left()<=view.left()||crop.right()>=view.right()||crop.top()<=view.top()||crop.bottom()>=view.bottom()
                ||(long)crop.width()*crop.height()>LocalCardMockPlan.MAX_PIXELS)
            return held(CardRevealProbeTarget.State.UNQUALIFIED,"INVALID_CARD_CROP",daily,more);
        return new Result(new CardRevealProbeTarget.Selection(CardRevealProbeTarget.State.CANDIDATE,selected,crop,null),"READY",daily,more);
    }
    private static Result search(OverviewUiProtocol.Report report,RemainingCardChoice choice,OverviewUiRegions.Region parent,
            ReviewShizukuSwipeActor.Direction previousSearch,int daily,int more){
        // A current heading of this exact group locates its entrance even if card children
        // have not expanded. Continue into the group, never borrow another group's card.
        if(parent!=null&&parent.heading()!=null)return scroll(report,ReviewShizukuSwipeActor.Direction.UP,daily,more);
        boolean before=false,after=false;int target=order(choice.role());
        for(var region:report.overview().regions())if(region.heading()!=null){
            int observed=order(region.role());before|=observed<target;after|=observed>target;
        }
        for(var item:report.cards().items()){
            int observed=order(item.role());before|=observed<target;after|=observed>target;
        }
        if(before&&after)return held(CardRevealProbeTarget.State.UNQUALIFIED,"CONFLICTING_SEARCH_ANCHORS",daily,more);
        if(after)return scroll(report,ReviewShizukuSwipeActor.Direction.DOWN,daily,more);
        if(before)return scroll(report,ReviewShizukuSwipeActor.Direction.UP,daily,more);
        var hint=report.scrollHint();
        if(hint==OverviewUiProtocol.ScrollHint.DECLARED_BACKWARD)return scroll(report,ReviewShizukuSwipeActor.Direction.DOWN,daily,more);
        if(hint==OverviewUiProtocol.ScrollHint.DECLARED_FORWARD)return scroll(report,ReviewShizukuSwipeActor.Direction.UP,daily,more);
        if(hint==OverviewUiProtocol.ScrollHint.DECLARED_BOTH)return scroll(report,
                previousSearch==null?ReviewShizukuSwipeActor.Direction.UP:previousSearch,daily,more);
        return held(CardRevealProbeTarget.State.NO_DIRECTION,"NO_DECLARED_DIRECTION",daily,more);
    }
    private static int order(OverviewUiRegions.Role role){return switch(role){
        case CHECK_IN,STREAK_SUMMARY->0;
        case DAILY_PROGRESS->1;
        case POINTS_SUMMARY->2;
        case DAILY_ACTIVITIES->3;
        case MORE_ACTIVITIES->4;
    };}
    private static Result scroll(OverviewUiProtocol.Report report,ReviewShizukuSwipeActor.Direction direction,int daily,int more){
        return scroll(report,direction,daily,more,0,0);
    }
    private static Result scroll(OverviewUiProtocol.Report report,ReviewShizukuSwipeActor.Direction direction,int daily,int more,int gap,int slack){
        var hint=report.scrollHint();boolean allowed=direction==ReviewShizukuSwipeActor.Direction.UP
                ?hint==OverviewUiProtocol.ScrollHint.DECLARED_FORWARD||hint==OverviewUiProtocol.ScrollHint.DECLARED_BOTH
                :hint==OverviewUiProtocol.ScrollHint.DECLARED_BACKWARD||hint==OverviewUiProtocol.ScrollHint.DECLARED_BOTH;
        return new Result(new CardRevealProbeTarget.Selection(allowed?CardRevealProbeTarget.State.SCROLL:CardRevealProbeTarget.State.NO_DIRECTION,
                null,null,allowed?direction:null),allowed?"SCROLL":"NO_DECLARED_DIRECTION",daily,more,gap,slack);
    }
    private static Result held(CardRevealProbeTarget.State state,String reason,int daily,int more){return new Result(new CardRevealProbeTarget.Selection(state,null,null,null),reason,daily,more);}
    private RemainingCardTarget(){}
}
