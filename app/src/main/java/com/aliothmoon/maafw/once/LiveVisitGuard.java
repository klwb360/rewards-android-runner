package com.aliothmoon.maafw.once;

/** Passive, bounded labels only. It never clicks, scrolls, captures or retains source text. */
final class LiveVisitGuard {
    enum Hint { REWARDS, SERP, WEB_CONTAINER, UNKNOWN, BLOCKED }
    record Report(NativeUiStructureProtocol.Report source,Hint hint,int webViews,int challenges,int dialogs){
        Report{if(source==null||hint==null||webViews<0||webViews>512||challenges<0||challenges>512||dialogs<0||dialogs>512)throw invalid();}
        boolean qualified(){return source.qualified()&&source.readiness()!=null&&!source.readiness().blocked()&&challenges==0&&dialogs==0;}
    }
    static final class Accumulator {
        int challenges,dialogs,webViews;
        void node(CharSequence className,String text,String description,boolean visible){
            String c=bounded(className);
            if(visible&&c.equals("android.webkit.WebView"))webViews++;
            if(c.equals("android.app.Dialog")||c.equals("android.widget.PopupWindow")||c.endsWith(".Dialog"))dialogs++;
            if(challenge(text)||challenge(description))challenges++;
            if(dialog(text)||dialog(description))dialogs++;
            if(challenges>512||dialogs>512)throw invalid();
        }
        private static String bounded(CharSequence value){
            if(value==null)return "";if(value.length()>256)throw invalid();
            StringBuilder out=new StringBuilder(value.length());for(int i=0;i<value.length();i++)out.append(value.charAt(i));return out.toString();
        }
    }
    private static boolean challenge(String text){return switch(LiveCardIdentity.normalize(text)){
        case "verify you are human","confirm you are human","verify your identity","security check","captcha",
                "验证你是人类","验证您是人类","人机验证","安全验证","验证码","验证身份"->true;
        default->false;
    };}
    private static boolean dialog(String text){return switch(LiveCardIdentity.normalize(text)){
        case "accept all","reject all","manage cookies","cookie consent","同意全部","拒绝全部","管理cookie","隐私和cookie"->true;
        default->false;
    };}
    static Hint classify(java.util.List<NativeUiBoundPointsParser.Node> nodes,Accumulator guard,boolean rewards){
        if(guard.challenges>0||guard.dialogs>0)return Hint.BLOCKED;
        if(rewards)return Hint.REWARDS;
        java.util.Map<Integer,NativeUiBoundPointsParser.Node> map=new java.util.HashMap<>();
        NativeUiBoundPointsParser.Node content=null,results=null;boolean home=false;
        for(var n:nodes){
            map.put(n.index(),n);if(!n.visible())continue;
            String id=n.resourceId();
            if("homePageWrapper".equals(id))home=true;
            if("b_content".equals(id)||"com.microsoft.bing:id/b_content".equals(id)){if(content!=null)return Hint.UNKNOWN;content=n;}
            if("b_results".equals(id)||"com.microsoft.bing:id/b_results".equals(id)){if(results!=null)return Hint.UNKNOWN;results=n;}
        }
        if(home)return Hint.UNKNOWN;
        if(guard.webViews>0&&content!=null&&results!=null){
            for(var n=results;n!=null;n=map.get(n.parentIndex()))if(n.index()==content.index())return Hint.SERP;
        }
        return guard.webViews>0?Hint.WEB_CONTAINER:Hint.UNKNOWN;
    }
    static String encode(Report report){return "LVT1|"+NativeUiStructureProtocol.encode(report.source())+"|GUARD|"+report.hint()+"|"+report.webViews()+"|"+report.challenges()+"|"+report.dialogs();}
    static Report parse(String line,String nonce,long dispatched,long deadline,long now){
        if(line==null||line.length()>8192||!line.startsWith("LVT1|UIS2|")||!line.matches("[A-Z0-9a-f_|-]+"))throw invalid();
        int at=line.indexOf("|GUARD|",5);if(at<0||at!=line.lastIndexOf("|GUARD|"))throw invalid();
        String[] fields=line.substring(at+7).split("\\|",-1);if(fields.length!=4)throw invalid();
        var r=new Report(NativeUiStructureProtocol.parse(line.substring(5,at),nonce,dispatched,deadline,now,true),
                Hint.valueOf(fields[0]),Math.toIntExact(NativeUiStructureProtocol.number(fields[1])),
                Math.toIntExact(NativeUiStructureProtocol.number(fields[2])),Math.toIntExact(NativeUiStructureProtocol.number(fields[3])));
        if(!encode(r).equals(line))throw invalid();return r;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid passive visit guard");}
    private LiveVisitGuard(){}
}
