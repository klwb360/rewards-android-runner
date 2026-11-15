package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;

/** Independent scalar-only overview envelopes. OVR1 and the existing UIS grammars stay unchanged. */
final class OverviewUiProtocol {
    static final int MAX_LINE = 8192;
    enum ScrollHint { UNKNOWN, DECLARED_FORWARD, DECLARED_BACKWARD, DECLARED_BOTH, DECLARED_NONE }
    record Report(NativeUiStructureProtocol.Report source, OverviewUiRegions.Result overview,
                  ScrollHint scrollHint, RemainingCardUi.Result cards) {
        Report(NativeUiStructureProtocol.Report source,OverviewUiRegions.Result overview,ScrollHint scrollHint){this(source,overview,scrollHint,null);}
        Report {
            if (source == null || overview == null || scrollHint == null || source.readiness() == null
                    || source.structureBound() || source.fieldDiagnostics() != null || source.regions() != null
                    || !source.search().equals(NativeUiStructureProtocol.Task.unknown())
                    || !source.reading().equals(NativeUiStructureProtocol.Task.unknown())) throw invalid();
            boolean sourceReady = source.qualified() && !source.readiness().blocked();
            if ((!sourceReady || overview.status() != OverviewUiRegions.Status.OBSERVED)
                    && scrollHint != ScrollHint.UNKNOWN) throw invalid();
            if (!sourceReady && overview.status() == OverviewUiRegions.Status.OBSERVED) throw invalid();
            if (overview.viewport() != null) {
                Box frame = new Box(source.left(),source.top(),source.right(),source.bottom());
                if (!frame.equals(overview.viewport())) throw invalid();
            }
            if(cards!=null){
                if((!sourceReady||overview.status()!=OverviewUiRegions.Status.OBSERVED)&&cards.status()==RemainingCardUi.Status.OBSERVED)throw invalid();
                for(var item:cards.items()){
                    if(overview.viewport()==null||!overview.viewport().contains(item.coarse()))throw invalid();
                    OverviewUiRegions.Region parent=null;
                    for(var region:overview.regions())if(region.role()==item.role())parent=region;
                    if(parent==null||!parent.coarse().contains(item.coarse()))throw invalid();
                }
            }
        }
        boolean qualified() {
            return source.qualified() && !source.readiness().blocked()
                    && overview.status() == OverviewUiRegions.Status.OBSERVED
                    && (cards==null||cards.status()==RemainingCardUi.Status.OBSERVED);
        }
    }

    static String encode(Report report) {
        StringBuilder out=new StringBuilder(report.cards()==null?"OVR1|":"OVR2|").append(NativeUiStructureProtocol.encode(report.source()))
                .append("|OV|").append(report.overview().status()).append('|').append(report.scrollHint());
        appendBox(out,report.overview().viewport());
        out.append('|').append(report.overview().regions().size());
        for(var region:report.overview().regions()) {
            out.append('|').append(region.role()); appendBox(out,region.coarse());appendBox(out,region.heading());
            appendBox(out,region.counter());out.append('|').append(region.completeVisible()?1:0)
                    .append('|').append(region.current()).append('|').append(region.target());
        }
        if(report.cards()!=null){
            var cards=report.cards();out.append("|CARDS|").append(cards.status()).append('|').append(cards.dailyCount())
                    .append('|').append(cards.moreCount()).append('|').append(cards.items().size());
            for(var item:cards.items()){
                out.append('|').append(item.role()).append('|').append(item.index());appendBox(out,item.coarse());
                appendBox(out,item.title());appendBox(out,item.reward());out.append('|').append(item.completeVisible()?1:0);
            }
        }
        if(out.length()>MAX_LINE)throw invalid();
        return out.toString();
    }
    /** Historical callers accept OVR1 only, even if an OVR2 frame otherwise looks qualified. */
    static Report parse(String line,String nonce,long dispatched,long deadline,long now) {
        return parseVersion(line,nonce,dispatched,deadline,now,false);
    }
    static Report parseRemaining(String line,String nonce,long dispatched,long deadline,long now) {
        return parseVersion(line,nonce,dispatched,deadline,now,true);
    }
    private static Report parseVersion(String line,String nonce,long dispatched,long deadline,long now,boolean cardsMode) {
        if(line==null||line.length()>MAX_LINE||!line.startsWith(cardsMode?"OVR2|UIS2|":"OVR1|UIS2|")
                ||!line.matches("[A-Z0-9a-f_|-]+"))throw invalid();
        int marker=line.indexOf("|OV|",5),cardMarker=line.indexOf("|CARDS|",5);
        if(marker<0||marker!=line.lastIndexOf("|OV|")||cardsMode!=(cardMarker>=0)
                ||cardMarker!=line.lastIndexOf("|CARDS|")||cardsMode&&cardMarker<=marker)throw invalid();
        var source=NativeUiStructureProtocol.parse(line.substring(5,marker),nonce,dispatched,deadline,now,true);
        String[] fields=line.substring(marker+4,cardsMode?cardMarker:line.length()).split("\\|",-1);
        if(fields.length<7)throw invalid();
        try {
            var status=OverviewUiRegions.Status.valueOf(fields[0]);
            var scroll=ScrollHint.valueOf(fields[1]);Box viewport=box(fields,2,true);
            int count=integer(fields[6]);if(count<0||count>OverviewUiRegions.Role.values().length||fields.length!=7+count*16)throw invalid();
            var regions=new ArrayList<OverviewUiRegions.Region>();int previous=-1;
            for(int i=0;i<count;i++) {
                int at=7+i*16;var role=OverviewUiRegions.Role.valueOf(fields[at]);
                if(role.ordinal()<=previous)throw invalid();previous=role.ordinal();
                int visible=integer(fields[at+13]);if(visible!=0&&visible!=1)throw invalid();
                regions.add(new OverviewUiRegions.Region(role,box(fields,at+1,false),box(fields,at+5,true),
                        box(fields,at+9,true),visible==1,integer(fields[at+14]),integer(fields[at+15])));
            }
            RemainingCardUi.Result cards=null;
            if(cardsMode){
                var c=line.substring(cardMarker+7).split("\\|",-1);if(c.length<4)throw invalid();
                var cs=RemainingCardUi.Status.valueOf(c[0]);int daily=integer(c[1]),more=integer(c[2]),n=integer(c[3]);
                if(n<0||n>16||c.length!=4+n*15)throw invalid();var items=new ArrayList<RemainingCardUi.Item>();
                for(int i=0;i<n;i++){
                    int at=4+i*15;int visible=integer(c[at+14]);if(visible!=0&&visible!=1)throw invalid();
                    items.add(new RemainingCardUi.Item(OverviewUiRegions.Role.valueOf(c[at]),integer(c[at+1]),box(c,at+2,false),box(c,at+6,true),box(c,at+10,true),visible==1));
                }
                cards=new RemainingCardUi.Result(cs,daily,more,items);
            }
            var result=new Report(source,new OverviewUiRegions.Result(status,viewport,regions),scroll,cards);
            if(!encode(result).equals(line))throw invalid();
            return result;
        } catch(IllegalArgumentException|ArithmeticException rejected) {throw invalid();}
    }
    private static void appendBox(StringBuilder out,Box box) {
        if(box==null)out.append("|-1|-1|-1|-1");
        else out.append('|').append(box.left()).append('|').append(box.top()).append('|').append(box.right()).append('|').append(box.bottom());
    }
    private static Box box(String[] fields,int at,boolean nullable) {
        int left=integer(fields[at]),top=integer(fields[at+1]),right=integer(fields[at+2]),bottom=integer(fields[at+3]);
        if(left==-1&&top==-1&&right==-1&&bottom==-1&&nullable)return null;
        if(left<0||top<0||right<=left||bottom<=top||right>8192||bottom>8192)throw invalid();
        return new Box(left,top,right,bottom);
    }
    private static int integer(String field){return Math.toIntExact(NativeUiStructureProtocol.number(field));}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid fixed overview protocol");}
    private OverviewUiProtocol() { }
}
