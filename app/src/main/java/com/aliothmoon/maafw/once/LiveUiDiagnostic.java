package com.aliothmoon.maafw.once;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A scalar-only snapshot of an existing protocol result. It never qualifies or repairs evidence. */
final class LiveUiDiagnostic {
    private final Map<String,Object> values;
    private final String safeState;
    private LiveUiDiagnostic(Map<String,Object> values,String safeState){
        this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values));this.safeState=safeState;
    }
    static LiveUiDiagnostic from(AndroidLiveUiReader.Result read){
        if(read==null)throw new IllegalArgumentException("Exact UI result required");
        var report=read.protocolReport();var base=report==null?null:report.base();var source=base==null?null:base.source();
        var ready=source==null?null:source.readiness();var overview=base==null?null:base.overview();var cards=base==null?null:base.cards();
        Map<String,Object> v=new LinkedHashMap<>();
        v.put("version",1);v.put("fixedStatus",read.fixedStatus().name());v.put("invoked",read.invoked());v.put("knownClean",read.knownClean());
        v.put("acquiredBytes",read.acquiredBytes());v.put("reportPresent",report!=null);v.put("reportQualified",report!=null&&report.qualified());
        v.put("helperReason",source==null?"NONE":source.reason().name());v.put("helperCleanup",source==null?"NONE":source.cleanup().name());
        v.put("sourceQualified",source!=null&&source.qualified());v.put("nodeCount",source==null?-1:source.nodeCount());
        v.put("readinessPresent",ready!=null);v.put("readinessIdle",ready==null?"NONE":ready.idle().name());
        v.put("readinessBlocked",ready!=null&&ready.blocked());v.put("loadingPresent",ready!=null&&ready.loading()>0);
        v.put("signInPresent",ready!=null&&ready.signIn()>0);v.put("errorPresent",ready!=null&&ready.error()>0);
        v.put("webViewCount",ready==null?-1:ready.webViews());v.put("loadingCount",ready==null?-1:ready.loading());
        v.put("signInCount",ready==null?-1:ready.signIn());v.put("errorCount",ready==null?-1:ready.error());v.put("labelMask",ready==null?-1:ready.labelMask());
        v.put("searchTitleCount",ready==null?-1:ready.searchTitles());v.put("readingTitleCount",ready==null?-1:ready.readingTitles());
        v.put("fractionShapeCount",ready==null?-1:ready.fractionShapes());
        v.put("overviewStatus",overview==null?"NONE":overview.status().name());v.put("cardsStatus",cards==null?"NONE":cards.status().name());
        int mask=0;if(overview!=null)for(var region:overview.regions())mask|=1<<region.role().ordinal();
        v.put("roleMask",mask);v.put("regionCount",overview==null?-1:overview.regions().size());
        v.put("dailyCount",cards==null?-1:cards.dailyCount());v.put("moreCount",cards==null?-1:cards.moreCount());
        v.put("visibleCardCount",cards==null?-1:cards.items().size());v.put("identityCount",report==null?-1:report.items().size());
        String state;
        if(!read.knownClean())state="UI_CLEANUP_UNCONFIRMED";
        else if(read.fixedStatus()!=AndroidLiveUiReader.FixedStatus.READ_COMPLETE)state="UI_"+read.fixedStatus().name()+"_"+(source==null?"NONE":source.reason().name());
        else if(report==null)state="UI_NO_REPORT";
        else if(!source.qualified())state="UI_SOURCE_"+source.reason().name()+"_"+source.cleanup().name();
        else if(ready==null)state="UI_READINESS_MISSING";
        else if(ready.blocked())state=ready.signIn()>0?(ready.error()>0?"UI_READINESS_SIGN_IN_ERROR":"UI_READINESS_SIGN_IN"):"UI_READINESS_ERROR";
        else if(overview.status()!=OverviewUiRegions.Status.OBSERVED)state="UI_OVERVIEW_"+overview.status().name();
        else if(cards==null||cards.status()!=RemainingCardUi.Status.OBSERVED)state="UI_CARDS_"+(cards==null?"NONE":cards.status().name());
        else state="UI_READ_COMPLETE_OK_OV_OBSERVED_CARDS_OBSERVED";
        if(!state.matches("[A-Z0-9_]{1,128}"))throw new IllegalArgumentException("Fixed diagnostic state required");
        return new LiveUiDiagnostic(v,state);
    }
    String safeState(){return safeState;}
    Object value(String key){return values.get(key);}
    Map<String,Object> scalars(){return values;}
    String json(){
        StringBuilder out=new StringBuilder("{");boolean first=true;
        for(var item:values.entrySet()){
            if(!first)out.append(',');first=false;out.append('"').append(item.getKey()).append("\":");
            Object value=item.getValue();
            if(value instanceof String fixed){if(!fixed.matches("[A-Z0-9_]+"))throw new IllegalStateException("Fixed enum required");out.append('"').append(fixed).append('"');}
            else if(value instanceof Boolean||value instanceof Integer)out.append(value);
            else throw new IllegalStateException("Scalar-only diagnostic required");
        }
        return out.append('}').toString();
    }
}
