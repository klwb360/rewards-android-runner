package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Exact existing ancestry/card grammar first; only then produce hash-only task identities. */
final class LiveUiParser {
    private static final Pattern POINTS=Pattern.compile("(?s).*[ ,，]([1-9][0-9]{0,3})[ \\u00a0]*积分$");
    /** Only this new mode accepts the other exact check-in header; old parsers remain unchanged. */
    static List<NativeUiBoundPointsParser.Node> labels(List<NativeUiBoundPointsParser.Node> nodes){
        List<NativeUiBoundPointsParser.Node> out=new ArrayList<>(nodes.size());
        for(var n:nodes)out.add(new NativeUiBoundPointsParser.Node(n.index(),n.parentIndex(),n.visible(),n.packageMatches(),n.resourceId(),
                "签到".equals(n.text())?"签入":n.text(),"签到".equals(n.description())?"签入":n.description(),n.left(),n.top(),n.right(),n.bottom()));
        return out;
    }
    static List<LiveUiProtocol.Item> identities(List<NativeUiBoundPointsParser.Node> nodes,OverviewUiProtocol.Report base){
        if(nodes==null||base==null||!base.qualified()||base.cards()==null)return List.of();
        return identities(nodes,base.overview(),base.cards());
    }
    static List<LiveUiProtocol.Item> identities(List<NativeUiBoundPointsParser.Node> nodes,OverviewUiRegions.Result overview,RemainingCardUi.Result cards){
        if(nodes==null||overview==null||cards==null||overview.status()!=OverviewUiRegions.Status.OBSERVED||cards.status()!=RemainingCardUi.Status.OBSERVED)return List.of();
        var verified=RemainingCardUiParser.parse(nodes,overview);
        if(!verified.equals(cards))throw new IllegalArgumentException("Exact same tree required");
        List<LiveUiProtocol.Item> items=new ArrayList<>();
        for(var region:overview.regions())if(region.role()==OverviewUiRegions.Role.CHECK_IN)
            items.add(new LiveUiProtocol.Item(OverviewUiRegions.Role.CHECK_IN,0,LiveCardIdentity.hashText("CHECK_IN"),-1,region.coarse(),region.completeVisible()));
        append(items,nodes,cards,OverviewUiRegions.Role.DAILY_ACTIVITIES,"dailySet",cards.dailyCount(),true);
        append(items,nodes,cards,OverviewUiRegions.Role.MORE_ACTIVITIES,"moreActivities",cards.moreCount(),false);
        return List.copyOf(items);
    }
    private static void append(List<LiveUiProtocol.Item> out,List<NativeUiBoundPointsParser.Node> nodes,RemainingCardUi.Result cards,
            OverviewUiRegions.Role role,String id,int count,boolean heading){
        if(count<0)return;NativeUiBoundPointsParser.Node parent=null;
        for(var n:nodes)if(id.equals(n.resourceId())){if(parent!=null)throw invalid();parent=n;}
        if(parent==null)throw invalid();List<NativeUiBoundPointsParser.Node> children=new ArrayList<>();
        for(var n:nodes)if(n.parentIndex()==parent.index())children.add(n);
        if(heading){if(children.isEmpty())throw invalid();children.remove(0);}
        if(children.size()!=count)throw invalid();
        for(int i=0;i<children.size();i++){
            var n=children.get(i);String text=n.text()!=null&&!n.text().isEmpty()?n.text():n.description();
            var point=POINTS.matcher(text==null?"":text);if(!point.matches())throw invalid();int points=Integer.parseInt(point.group(1));
            RemainingCardUi.Item visible=null;for(var item:cards.items())if(item.role()==role&&item.index()==i+1)visible=item;
            out.add(new LiveUiProtocol.Item(role,i+1,LiveCardIdentity.descriptor(text),points,
                    visible==null?null:visible.coarse(),visible!=null&&visible.completeVisible()));
        }
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid current card identities");}
    private LiveUiParser(){}
}
