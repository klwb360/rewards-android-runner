package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Counts the complete DOM roster before visibility filtering, under exact current Rewards ancestry. */
final class RemainingCardUiParser {
    private final List<NativeUiBoundPointsParser.Node> nodes;
    private final Map<Integer,NativeUiBoundPointsParser.Node> byIndex=new HashMap<>();
    private final List<RemainingCardUi.Item> items=new ArrayList<>();
    private NativeUiBoundPointsParser.Node home,panel;
    private static final class Rejected extends RuntimeException {private static final long serialVersionUID=1L;}
    private RemainingCardUiParser(List<NativeUiBoundPointsParser.Node> nodes){this.nodes=nodes;for(var node:nodes)byIndex.put(node.index(),node);}
    static RemainingCardUi.Result parse(List<NativeUiBoundPointsParser.Node> nodes){
        var overview=OverviewUiParser.parse(nodes);return parse(nodes,overview);
    }
    static RemainingCardUi.Result parse(List<NativeUiBoundPointsParser.Node> nodes,OverviewUiRegions.Result overview){
        // Re-establish the same whole-tree validation even if a caller supplies an unrelated result.
        var actual=OverviewUiParser.parse(nodes);
        if(actual.status()!=OverviewUiRegions.Status.OBSERVED||overview==null||!actual.equals(overview))
            return RemainingCardUi.Result.rejected(RemainingCardUi.Status.WRONG_STRUCTURE);
        try{return new RemainingCardUiParser(nodes).parse();}
        catch(Rejected|IllegalArgumentException invalid){return RemainingCardUi.Result.rejected(RemainingCardUi.Status.AMBIGUOUS_STRUCTURE);}
    }
    private RemainingCardUi.Result parse(){
        home=unique("homePageWrapper");panel=unique("dailyTaskPanel");
        if(home==null||panel==null||panel.parentIndex()!=home.index())throw new Rejected();
        int daily=-1,more=-1,kept=items.size();
        try{daily=daily();}catch(Rejected invalid){while(items.size()>kept)items.remove(items.size()-1);}
        kept=items.size();
        try{more=more();}catch(Rejected invalid){while(items.size()>kept)items.remove(items.size()-1);}
        return new RemainingCardUi.Result(RemainingCardUi.Status.OBSERVED,daily,more,items);
    }
    private int daily(){
        var dailySet=unique("dailySet");if(dailySet==null||!visible(dailySet)||dailySet.parentIndex()!=panel.index())throw new Rejected();
        var children=children(dailySet);NativeUiBoundPointsParser.Node heading=null;
        for(var child:children)if(exact(child,"每日活动")){if(heading!=null)throw new Rejected();heading=child;}
        if(heading==null||children.isEmpty()||children.get(0)!=heading||!children(heading).isEmpty())throw new Rejected();
        List<NativeUiBoundPointsParser.Node> cards=new ArrayList<>();for(var child:children)if(child!=heading)cards.add(child);
        return cards(cards,OverviewUiRegions.Role.DAILY_ACTIVITIES,dailySet);
    }
    private int more(){
        var moreTitle=unique("more-activities-panel-title");var moreActivities=unique("moreActivities");
        if(moreTitle==null||moreActivities==null||!visible(moreActivities)||!exact(moreTitle,"更多活动")||!children(moreTitle).isEmpty())throw new Rejected();
        var container=byIndex.get(moreTitle.parentIndex());
        if(container==null||container.parentIndex()!=panel.index()||moreActivities.parentIndex()!=container.index())throw new Rejected();
        List<NativeUiBoundPointsParser.Node> siblings=children(container);
        if(siblings.size()!=3||siblings.get(0)!=moreTitle||siblings.get(2)!=moreActivities)throw new Rejected();
        var counter=siblings.get(1);
        if(content(counter)||children(counter).isEmpty()||children(counter).size()>2)throw new Rejected();
        for(var child:children(counter))if(!children(child).isEmpty()||!emptyId(child))throw new Rejected();
        return cards(children(moreActivities),OverviewUiRegions.Role.MORE_ACTIVITIES,moreActivities);
    }
    private int cards(List<NativeUiBoundPointsParser.Node> cards,OverviewUiRegions.Role role,NativeUiBoundPointsParser.Node container){
        if(cards.isEmpty()||cards.size()>RemainingCardUi.MAX_PER_ROLE)throw new Rejected();
        if(cards.size()==1){
            // An offscreen/lazy aggregate is not evidence that a section has exactly one card.
            var only=cards.get(0);
            if(!visible(only)||only.top()<=home.top()||only.bottom()>=home.bottom()
                    ||container.top()<=home.top()||container.bottom()>=home.bottom()
                    ||only.bottom()-only.top()>.60*(only.right()-only.left()))throw new Rejected();
        }
        int ordinal=0;
        for(var card:cards){
            ordinal++;if(!emptyId(card)||!aggregateCard(card))throw new Rejected();
            var inner=children(card);
            // The measured DOM has one duplicated-description content child for pending cards,
            // or a single compact text leaf for completed cards. It has no separate title/reward node.
            if(nonempty(card.description())&&!nonempty(card.text())){
                if(inner.size()!=1)throw new Rejected();var child=inner.get(0);
                if(!emptyId(child)||nonempty(child.text())||!card.description().equals(child.description())||!children(child).isEmpty())throw new Rejected();
                if(visible(card)&&visible(child)&&!box(card).contains(box(child)))throw new Rejected();
            }else if(nonempty(card.text())&&!nonempty(card.description())){
                if(!inner.isEmpty())throw new Rejected();
            }else throw new Rejected();
            if(!visible(card))continue;
            if(!visible(container)||!box(container).contains(box(card))||!box(home).contains(box(card)))throw new Rejected();
            Box coarse=box(card);boolean complete=coarse.top()>home.top()&&coarse.bottom()<home.bottom()
                    &&coarse.left()>home.left()&&coarse.right()<home.right();
            items.add(new RemainingCardUi.Item(role,ordinal,coarse,null,null,complete));
        }
        return cards.size();
    }
    private NativeUiBoundPointsParser.Node unique(String id){
        NativeUiBoundPointsParser.Node result=null;
        for(var node:nodes)if(id.equals(node.resourceId())){if(result!=null)throw new Rejected();result=node;}
        return result;
    }
    private List<NativeUiBoundPointsParser.Node> children(NativeUiBoundPointsParser.Node parent){
        List<NativeUiBoundPointsParser.Node> result=new ArrayList<>();for(var node:nodes)if(node.parentIndex()==parent.index())result.add(node);return result;
    }
    private static boolean exact(NativeUiBoundPointsParser.Node node,String text){return text.equals(node.text())||text.equals(node.description());}
    private static boolean aggregateCard(NativeUiBoundPointsParser.Node node){
        String value=nonempty(node.text())?node.text():node.description();
        if(value==null||value.length()>2048||(value.indexOf(',')<0&&value.indexOf('，')<0)
                ||!value.matches("(?s).*[1-9][0-9]{0,3} 积分$"))return false;
        var tokens=java.util.regex.Pattern.compile("(?<![0-9])[1-9][0-9]{0,3}[ \u00a0]*积分").matcher(value);
        int count=0;while(tokens.find())count++;return count==1;
    }
    private static boolean nonempty(String text){return text!=null&&!text.isEmpty();}
    private static boolean content(NativeUiBoundPointsParser.Node node){return nonempty(node.text())||nonempty(node.description());}
    private static boolean emptyId(NativeUiBoundPointsParser.Node node){return node.resourceId()==null||node.resourceId().isEmpty();}
    private static boolean visible(NativeUiBoundPointsParser.Node node){return node!=null&&node.visible()&&node.right()>node.left()&&node.bottom()>node.top();}
    private static Box box(NativeUiBoundPointsParser.Node node){return new Box(node.left(),node.top(),node.right(),node.bottom());}
    private RemainingCardUiParser(){throw new AssertionError();}
}
