package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.ArrayList;

/** Independent scalar-only overview envelope. The existing UIS1-5 grammar stays unchanged. */
final class OverviewUiProtocol {
    static final int MAX_LINE = 8192;
    enum ScrollHint { UNKNOWN, DECLARED_FORWARD, DECLARED_BACKWARD, DECLARED_BOTH, DECLARED_NONE }
    record Report(NativeUiStructureProtocol.Report source, OverviewUiRegions.Result overview,
                  ScrollHint scrollHint) {
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
        }
        boolean qualified() {
            return source.qualified() && !source.readiness().blocked()
                    && overview.status() == OverviewUiRegions.Status.OBSERVED;
        }
    }

    static String encode(Report report) {
        StringBuilder out=new StringBuilder("OVR1|").append(NativeUiStructureProtocol.encode(report.source()))
                .append("|OV|").append(report.overview().status()).append('|').append(report.scrollHint());
        appendBox(out,report.overview().viewport());
        out.append('|').append(report.overview().regions().size());
        for(var region:report.overview().regions()) {
            out.append('|').append(region.role()); appendBox(out,region.coarse());appendBox(out,region.heading());
            appendBox(out,region.counter());out.append('|').append(region.completeVisible()?1:0)
                    .append('|').append(region.current()).append('|').append(region.target());
        }
        if(out.length()>MAX_LINE)throw invalid();
        return out.toString();
    }
    static Report parse(String line,String nonce,long dispatched,long deadline,long now) {
        if(line==null||line.length()>MAX_LINE||!line.startsWith("OVR1|UIS2|")
                ||!line.matches("[A-Z0-9a-f_|-]+"))throw invalid();
        int marker=line.indexOf("|OV|",5);
        if(marker<0||marker!=line.lastIndexOf("|OV|"))throw invalid();
        var source=NativeUiStructureProtocol.parse(line.substring(5,marker),nonce,dispatched,deadline,now,true);
        String[] fields=line.substring(marker+4).split("\\|",-1);
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
            var result=new Report(source,new OverviewUiRegions.Result(status,viewport,regions),scroll);
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
