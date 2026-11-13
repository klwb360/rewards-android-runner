package com.aliothmoon.maafw.once;

import io.github.klwb360.rewards.core.RewardsCore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded native-row metadata only. This projection establishes no task counter,
 * row relationship, source authenticity, freshness or input authority. */
final class NativeProgressLayoutDiagnostics {
    static final int MAX_CANDIDATES=24;
    private static final int MAX_FRACTION=10000;
    enum Role {
        EXACT_REWARD_SEARCH_TITLE, EXACT_REWARD_READ_TITLE, STRICT_SAME_ROW_COUNTER,
        HISTORICAL_SUMMARY_FORMAT_MATCH, HYPOTHESIS_LABEL,
        GENERIC_SEARCH_TOKEN, GENERIC_READ_TOKEN, OTHER_SEARCH_CONTAINING, OTHER_READ_CONTAINING,
        BARE_FRACTION, MALFORMED_FRACTION
    }
    enum Provenance { SOURCE_BACKED, HISTORICAL_SOURCE, HYPOTHESIS_ONLY, TOKEN_ONLY, UNATTRIBUTED }
    enum Alias {
        NONE, SEARCH_TO_EARN, SEARCH_EARN_ZH, SEARCH_POINTS, SEARCH_POINTS_ZH,
        READ_TO_EARN, READ_EARN_ZH,
        SEARCH_AND_EARN, SEARCH_WITH_BING, SEARCH_ON_BING, SEARCH_THE_WEB, SEARCH_NOW,
        SEARCH_TO_EARN_POINTS, READ_AND_EARN,
        SEARCH_POINTS_HYP_ZH, SEARCH_EARN_POINTS_HYP_ZH, SEARCH_VIA_POINTS_HYP_ZH,
        READ_POINTS_HYP_ZH, READ_EARN_POINTS_HYP_ZH
    }
    private record Label(String text,Alias alias,boolean search) {}
    private static final List<Label> SOURCES=List.of(
        new Label("search to earn",Alias.SEARCH_TO_EARN,true),new Label("搜索以赚取",Alias.SEARCH_EARN_ZH,true),
        new Label("search points",Alias.SEARCH_POINTS,true),new Label("搜索积分",Alias.SEARCH_POINTS_ZH,true),
        new Label("read to earn",Alias.READ_TO_EARN,false),new Label("阅读以赚取",Alias.READ_EARN_ZH,false));
    private static final List<Label> HYPOTHESES=List.of(
        new Label("search and earn",Alias.SEARCH_AND_EARN,true),new Label("search with bing",Alias.SEARCH_WITH_BING,true),
        new Label("search on bing",Alias.SEARCH_ON_BING,true),new Label("search the web",Alias.SEARCH_THE_WEB,true),
        new Label("search now",Alias.SEARCH_NOW,true),new Label("search to earn points",Alias.SEARCH_TO_EARN_POINTS,true),
        new Label("read and earn",Alias.READ_AND_EARN,false),new Label("搜索赚积分",Alias.SEARCH_POINTS_HYP_ZH,true),
        new Label("搜索赚取积分",Alias.SEARCH_EARN_POINTS_HYP_ZH,true),new Label("通过搜索赚取积分",Alias.SEARCH_VIA_POINTS_HYP_ZH,true),
        new Label("阅读赚积分",Alias.READ_POINTS_HYP_ZH,false),new Label("阅读赚取积分",Alias.READ_EARN_POINTS_HYP_ZH,false));
    private record Kind(Role role,Provenance provenance,Alias alias,int numerator,int denominator) {}
    private record Candidate(int index,Kind kind,int x,int y,int width,int height,int score10000,boolean low) {
        String summary(){
            String fraction=kind.role()==Role.BARE_FRACTION?" n="+kind.numerator()+" d="+kind.denominator()+" fractionRole=UNATTRIBUTED":"";
            return "row="+index+" role="+kind.role()+" origin="+kind.provenance()+" alias="+kind.alias()
                +((kind.role()==Role.STRICT_SAME_ROW_COUNTER||kind.role()==Role.HISTORICAL_SUMMARY_FORMAT_MATCH)?" formatOnly=true":"")+" box="+x+","+y+","+width+","+height+" score="+String.format(Locale.ROOT,"%.4f",score10000/10000.0)+" low="+low+fraction;
        }
    }
    private final OcrRowsAssessment quality;
    private final List<Candidate> candidates;
    private final int matched;
    private final boolean locateStopClue,locateSafetyStop;
    private NativeProgressLayoutDiagnostics(OcrRowsAssessment quality,List<Candidate> candidates,int matched,boolean locateStopClue,boolean locateSafetyStop){
        this.quality=quality;this.candidates=List.copyOf(candidates);this.matched=matched;this.locateStopClue=locateStopClue;this.locateSafetyStop=locateSafetyStop;
    }
    static NativeProgressLayoutDiagnostics observe(FrameStructureEvidence.Binding actual,FrameStructureEvidence.Binding expected,
            List<FrameStructureEvidence.Line> fullRows,int width,int height){
        var quality=OcrRowsAssessment.assess(actual,expected,fullRows,width,height);
        if(!quality.shapeValid())return new NativeProgressLayoutDiagnostics(quality,List.of(),0,false,false);
        List<Candidate> result=new ArrayList<>();int matched=0;boolean stop=false,safety=false;
        for(int index=0;index<fullRows.size();index++){
            var row=fullRows.get(index);safety|=safetyClue(RewardsCore.normalize(row.text()));Kind kind=classify(row.text());
            if(kind==null)continue;matched++;
            // Evaluate every original row before the independent presentation cap.
            stop|=switch(kind.role()){
                case EXACT_REWARD_SEARCH_TITLE,EXACT_REWARD_READ_TITLE,STRICT_SAME_ROW_COUNTER,
                     HISTORICAL_SUMMARY_FORMAT_MATCH,HYPOTHESIS_LABEL->true;
                default->false;
            };
            if(result.size()<MAX_CANDIDATES)result.add(new Candidate(index,kind,row.x(),row.y(),row.width(),row.height(),
                (int)Math.round(row.score()*10000),row.score()<.8));
        }
        return new NativeProgressLayoutDiagnostics(quality,result,matched,stop,safety);
    }
    boolean locateRowsUsable(){return quality.shapeValid()&&quality.nonempty()>0;}
    /** Conservative navigation stop only, including low-confidence exact fields.
     * Never counter authority, and never limited to the rows emitted in summary(). */
    boolean locateStopClue(){return locateStopClue;}
    /** Known-error/login heuristic only: vetoes navigation, never establishes page identity. */
    boolean locateSafetyStop(){return locateSafetyStop;}
    private static boolean safetyClue(String text){
        return text.matches("(?s).*\\b(?:sign in|log in|login)\\b.*")||text.contains("登录")||text.contains("登入")
            ||text.contains("出现问题")||text.contains("尝试刷新页面")||text.equals("image load error");
    }
    /** Enum-only view for validated original-row region diagnostics. No values or authority. */
    record RegionTag(Role role,Provenance provenance,Alias alias) {}
    static RegionTag regionTag(String original){
        if(original==null||original.length()>512)return null;Kind value=classify(original);
        return value==null?null:new RegionTag(value.role(),value.provenance(),value.alias());
    }
    private static Kind kind(Role role,Provenance provenance,Alias alias){return new Kind(role,provenance,alias,-1,-1);}
    private static Kind classify(String original){
        Kind historical=historical(original);if(historical!=null)return historical;
        String text=RewardsCore.normalize(original);
        for(Label label:SOURCES){
            if(text.equals(label.text()))return kind(label.search()?Role.EXACT_REWARD_SEARCH_TITLE:Role.EXACT_REWARD_READ_TITLE,Provenance.SOURCE_BACKED,label.alias());
            String prefix=label.text()+" ";
            if(text.startsWith(prefix)){
                String rest=text.substring(prefix.length());
                if(strictFractionFormat(rest))return kind(Role.STRICT_SAME_ROW_COUNTER,Provenance.SOURCE_BACKED,label.alias());
            }

        }
        for(Label label:HYPOTHESES)if(text.equals(label.text()))return kind(Role.HYPOTHESIS_LABEL,Provenance.HYPOTHESIS_ONLY,label.alias());
        int[] fraction=fraction(text,true);
        if(fraction!=null)return new Kind(Role.BARE_FRACTION,Provenance.UNATTRIBUTED,Alias.NONE,fraction[0],fraction[1]);
        if(text.indexOf('/')>=0&&text.matches("[+0-9., /-]+"))return kind(Role.MALFORMED_FRACTION,Provenance.UNATTRIBUTED,Alias.NONE);
        if(text.matches("(?s).*\\bsearch\\b.*"))return kind(Role.GENERIC_SEARCH_TOKEN,Provenance.TOKEN_ONLY,Alias.NONE);
        if(text.matches("(?s).*\\bread\\b.*"))return kind(Role.GENERIC_READ_TOKEN,Provenance.TOKEN_ONLY,Alias.NONE);
        if(text.contains("search")||text.contains("搜索"))return kind(Role.OTHER_SEARCH_CONTAINING,Provenance.TOKEN_ONLY,Alias.NONE);
        if(text.contains("read")||text.contains("阅读"))return kind(Role.OTHER_READ_CONTAINING,Provenance.TOKEN_ONLY,Alias.NONE);
        return null;
    }
    /** Verified recovered BingRewardsPage.java lines103-108. Match the original complete
     * field with literal spaces/case; do not normalize a different field into this grammar.
     * Numeric form is deliberately a narrower unsigned subset. No completion-total fallback. */
    private static Kind historical(String original){
        if(original.matches("Search to earn, , [0-9]+ out of [0-9]+ points earned"))return kind(Role.HISTORICAL_SUMMARY_FORMAT_MATCH,Provenance.HISTORICAL_SOURCE,Alias.SEARCH_TO_EARN);
        if(original.matches("Read to earn, , [0-9]+ out of [0-9]+ points earned"))return kind(Role.HISTORICAL_SUMMARY_FORMAT_MATCH,Provenance.HISTORICAL_SOURCE,Alias.READ_TO_EARN);
        if(original.matches("搜索以赚取, , 已赚取 [0-9]+ 积分\\(需要 [0-9]+ 积分\\)"))return kind(Role.HISTORICAL_SUMMARY_FORMAT_MATCH,Provenance.HISTORICAL_SOURCE,Alias.SEARCH_EARN_ZH);
        if(original.matches("阅读以赚取, , 已赚取 [0-9]+ 积分\\(需要 [0-9]+ 积分\\)"))return kind(Role.HISTORICAL_SUMMARY_FORMAT_MATCH,Provenance.HISTORICAL_SOURCE,Alias.READ_EARN_ZH);
        return null;
    }
    /** Exact same-row syntax and integer bounds only, never full-frame semantic acceptance. */
    private static boolean strictFractionFormat(String value){
        if(!value.matches("[0-9]{1,10}/[0-9]{1,10}"))return false;
        int at=value.indexOf('/');long n=Long.parseLong(value.substring(0,at)),d=Long.parseLong(value.substring(at+1));
        return d>0&&d<=Integer.MAX_VALUE&&n<=d;
    }
    private static int[] fraction(String value,boolean spaces){
        if(!value.matches(spaces?"[0-9]{1,5} */ *[0-9]{1,5}":"[0-9]{1,5}/[0-9]{1,5}"))return null;
        int at=value.indexOf('/');int n=Integer.parseInt(value.substring(0,at).trim()),d=Integer.parseInt(value.substring(at+1).trim());
        return n<=MAX_FRACTION&&d>0&&d<=MAX_FRACTION?new int[]{n,d}:null;
    }
    String summary(boolean qualified){
        boolean publish=qualified&&quality.shapeValid();
        String head="layoutEvidence="+(publish?"QUALIFIED_NATIVE_DIAGNOSTIC":"UNQUALIFIED")
            +" counterAuthority=false actionAuthority=false rowQuality="+quality.reason()+" nativeRows="+quality.rows()
            +" nonemptyRows="+quality.nonempty()+" lowConfidenceRows="+quality.lowConfidence();
        if(!publish)return head+" candidates=SUPPRESSED";
        StringBuilder out=new StringBuilder(head).append(" candidateTotal=").append(matched).append(" emitted=").append(candidates.size())
            .append(" truncated=").append(matched>candidates.size()).append(" association=NONE");
        for(Candidate candidate:candidates)out.append('\n').append(candidate.summary());
        return out.toString();
    }
}
