package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;
import java.util.List;

/** Hash-only companion to the unchanged OVR2 source/geometry envelope. No input authority. */
final class LiveUiProtocol {
    static final int MAX_LINE=8192;
    record Item(OverviewUiRegions.Role role,int index,String descriptorHash,int points,Box coarse,boolean full){
        Item {
            if(role==null||!supported(role)||descriptorHash==null||!descriptorHash.matches("[a-f0-9]{64}")
                    ||(role==OverviewUiRegions.Role.CHECK_IN?index!=0||points!=-1:index<1||index>8||points<1||points>9999)
                    ||coarse!=null&&(coarse.left()<0||coarse.top()<0||coarse.right()>8192||coarse.bottom()>8192||coarse.width()<1||coarse.height()<1)
                    ||full&&coarse==null)throw invalid();
        }
    }
    record Report(OverviewUiProtocol.Report base,List<Item> items){
        Report {
            if(base==null||base.cards()==null||items==null||items.size()>17)throw invalid();items=List.copyOf(items);
            int previous=-1,index=-1,daily=0,more=0,check=0;
            for(var item:items){
                if(item.role().ordinal()<previous||item.role().ordinal()==previous&&item.index()<=index)throw invalid();
                previous=item.role().ordinal();index=item.index();
                if(item.role()==OverviewUiRegions.Role.CHECK_IN){
                    check++;OverviewUiRegions.Region found=null;
                    for(var r:base.overview().regions())if(r.role()==OverviewUiRegions.Role.CHECK_IN)found=r;
                    if(found==null||!found.coarse().equals(item.coarse())||item.full()!=found.completeVisible())throw invalid();
                }else{
                    if(item.role()==OverviewUiRegions.Role.DAILY_ACTIVITIES)daily++;else more++;
                    RemainingCardUi.Item visible=null;
                    for(var card:base.cards().items())if(card.role()==item.role()&&card.index()==item.index())visible=card;
                    if(visible==null?item.coarse()!=null:item.coarse()==null||!visible.coarse().equals(item.coarse())||visible.completeVisible()!=item.full())throw invalid();
                }
            }
            if(check>1||daily!=Math.max(0,base.cards().dailyCount())||more!=Math.max(0,base.cards().moreCount()))throw invalid();
            if(!base.qualified()&&!items.isEmpty())throw invalid();
        }
        boolean qualified(){return base.qualified();}
    }
    static String encode(Report report){
        StringBuilder out=new StringBuilder("LVR1|").append(OverviewUiProtocol.encode(report.base())).append("|IDENTITIES|").append(report.items().size());
        for(var item:report.items()){
            out.append('|').append(item.role()).append('|').append(item.index()).append('|').append(item.descriptorHash()).append('|').append(item.points());
            Box b=item.coarse();if(b==null)out.append("|-1|-1|-1|-1");else out.append('|').append(b.left()).append('|').append(b.top()).append('|').append(b.right()).append('|').append(b.bottom());
            out.append('|').append(item.full()?1:0);
        }
        if(out.length()>MAX_LINE)throw invalid();return out.toString();
    }
    static Report parse(String line,String nonce,long dispatched,long deadline,long now){
        if(line==null||line.length()>MAX_LINE||!line.startsWith("LVR1|OVR2|UIS2|")||!line.matches("[A-Z0-9a-f_|-]+"))throw invalid();
        int split=line.indexOf("|IDENTITIES|",5);if(split<0||split!=line.lastIndexOf("|IDENTITIES|"))throw invalid();
        var base=OverviewUiProtocol.parseRemaining(line.substring(5,split),nonce,dispatched,deadline,now);
        String[] fields=line.substring(split+12).split("\\|",-1);int count=number(fields[0]);
        if(count<0||count>17||fields.length!=1+count*9)throw invalid();List<Item> items=new ArrayList<>();
        for(int i=0;i<count;i++){
            int at=1+i*9,full=number(fields[at+8]);if(full!=0&&full!=1)throw invalid();
            int l=number(fields[at+4]),t=number(fields[at+5]),r=number(fields[at+6]),b=number(fields[at+7]);
            Box box=l==-1&&t==-1&&r==-1&&b==-1?null:new Box(l,t,r,b);
            items.add(new Item(OverviewUiRegions.Role.valueOf(fields[at]),number(fields[at+1]),fields[at+2],number(fields[at+3]),box,full==1));
        }
        var report=new Report(base,items);if(!encode(report).equals(line))throw invalid();return report;
    }
    private static int number(String value){return Math.toIntExact(NativeUiStructureProtocol.number(value));}
    private static boolean supported(OverviewUiRegions.Role role){return role==OverviewUiRegions.Role.CHECK_IN||RemainingCardUi.activity(role);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid hash-only live UI envelope");}
    private LiveUiProtocol(){}
}
