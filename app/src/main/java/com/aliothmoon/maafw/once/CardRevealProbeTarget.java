package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;

/** Conservative first probe: one check-in panel, with no screenshots used while locating it. */
final class CardRevealProbeTarget {
    enum State { CANDIDATE, SCROLL, NO_DIRECTION, UNQUALIFIED, TOO_LARGE }
    record Selection(State state,OverviewUiRegions.Region region,Box crop,ReviewShizukuSwipeActor.Direction direction) {
        boolean sameCandidate(Selection other){return state==State.CANDIDATE&&other!=null
                &&other.state==State.CANDIDATE&&region.equals(other.region)&&crop.equals(other.crop);}
    }
    static Selection select(OverviewUiProtocol.Report report,int width,int height) {
        if(report==null||!report.qualified()||width<=0||height<=width)return held(State.UNQUALIFIED);
        var view=report.overview().viewport();
        if(view==null||view.left()<0||view.top()<0||view.right()>width||view.bottom()>height)return held(State.UNQUALIFIED);
        OverviewUiRegions.Region target=null;
        for(var region:report.overview().regions())if(region.role()==OverviewUiRegions.Role.CHECK_IN){
            if(target!=null)return held(State.UNQUALIFIED);target=region;
        }
        var hint=report.scrollHint();
        if(target==null)return scroll(hint,backward(hint)?ReviewShizukuSwipeActor.Direction.DOWN:ReviewShizukuSwipeActor.Direction.UP);
        Box coarse=target.coarse();
        int safeTop=Math.max(view.top()+1,height/5),safeBottom=Math.min(view.bottom()-1,height*9/10);
        if(safeBottom<=safeTop||coarse.height()+24>safeBottom-safeTop)return held(State.TOO_LARGE);
        if(coarse.top()-12<safeTop)return scroll(hint,ReviewShizukuSwipeActor.Direction.DOWN);
        if(coarse.bottom()+12>safeBottom)return scroll(hint,ReviewShizukuSwipeActor.Direction.UP);
        if(!target.completeVisible()||target.heading()==null||!coarse.contains(target.heading()))return held(State.UNQUALIFIED);
        Box crop=new Box(coarse.left()-12,coarse.top()-12,coarse.right()+12,coarse.bottom()+12);
        if(crop.left()<=view.left()||crop.right()>=view.right()||crop.top()<=view.top()||crop.bottom()>=view.bottom()
                ||(long)crop.width()*crop.height()>2_097_152L)return held(State.UNQUALIFIED);
        return new Selection(State.CANDIDATE,target,crop,null);
    }
    private static boolean backward(OverviewUiProtocol.ScrollHint hint){return hint==OverviewUiProtocol.ScrollHint.DECLARED_BACKWARD||hint==OverviewUiProtocol.ScrollHint.DECLARED_BOTH;}
    private static Selection scroll(OverviewUiProtocol.ScrollHint hint,ReviewShizukuSwipeActor.Direction direction){
        boolean allowed=direction==ReviewShizukuSwipeActor.Direction.DOWN?backward(hint)
                :hint==OverviewUiProtocol.ScrollHint.DECLARED_FORWARD||hint==OverviewUiProtocol.ScrollHint.DECLARED_BOTH;
        return new Selection(allowed?State.SCROLL:State.NO_DIRECTION,null,null,allowed?direction:null);
    }
    private static Selection held(State state){return new Selection(state,null,null,null);}
    private CardRevealProbeTarget(){}
}
