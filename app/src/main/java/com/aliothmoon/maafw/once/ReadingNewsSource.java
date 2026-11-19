package com.aliothmoon.maafw.once;

import java.util.List;
import java.util.regex.Pattern;
import static com.aliothmoon.maafw.once.BingNavigationSnapshot.*;

/** One visible reading run. Rewards may finish and reveal the exact Main instance beneath it. */
final class ReadingNewsSource {
    record Observation(Result parsed, Ready source, boolean waitable, String gate) {}
    private record Stack(String object, List<Section> history) {}
    private record Predecessor(String taskObject,String activity) {}
    private final Ready rewards;
    private Predecessor predecessor;
    private Ready listWindow;
    private Ready checkedViewer;
    private boolean entryTapped, articleTapped;
    private static final String TOKEN="[a-fA-F0-9]+";
    private static final String PKG="com.microsoft.bing";

    ReadingNewsSource(Ready rewards) {
        if(rewards==null||rewards.kind()!=Kind.TEMPLATE)throw new IllegalArgumentException("Expected Rewards source");
        this.rewards=rewards;
    }
    boolean owns(Ready other){return rewards.sameSource(other);}
    void entrySucceeded(){
        if(entryTapped||predecessor==null)throw new SecurityException("NEWS_ENTRY_ANCHOR");
        entryTapped=true;
    }
    void confirmList(Ready ready){
        if(!entryTapped || ready==null || ready.kind()!=Kind.MAIN || predecessor==null
                ||!rewards.sameTask(ready) ||!ready.diagnosticActivityMatches(predecessor.activity(),MAIN,rewards.task())
                ||listWindow!=null&&!listWindow.sameLiveWindow(ready))throw new SecurityException("NEWS_LIST_ANCHOR");
        listWindow=ready;articleTapped=false;
    }
    void articleSucceeded(){
        if(listWindow==null||articleTapped)throw new SecurityException("NEWS_ARTICLE_ANCHOR");
        articleTapped=true;
    }
    boolean isCheckedViewer(Ready ready){return articleTapped && ready!=null && ready==checkedViewer;}

    Observation inspect(String windows,String activities,Profile profile,ReadOnlyGuard guard,long lower,long observed){
        checkedViewer=null;
        var focus=BingForegroundSnapshot.parse(windows);
        boolean browser=focus!=null && BROWSER.equals(focus.component());
        Result parsed=browser?parseRootReadOnly(windows,activities,profile,guard,lower,observed)
                :parseEntrySource(windows,activities,profile,guard,lower,observed);
        if(focus==null||!rewards.profile().equals(profile)||!rewards.task().equals(focus.task()))
            return hold(parsed,"NEWS_ROUTE_FOCUS");
        boolean original = rewards.diagnosticActivityMatches(focus.activity(),focus.component(),focus.task())
                && rewards.diagnosticWindowMatches(focus.window());
        boolean parent = predecessor!=null && MAIN.equals(focus.component()) && predecessor.activity().equals(focus.activity());
        boolean child = articleTapped && listWindow!=null && !original && (browser||TEMPLATE.equals(focus.component()));
        if(!(original&&listWindow==null || parent&&entryTapped || child))return hold(parsed,"NEWS_ROUTE_IDENTITY");
        Stack stack=stack(activities,profile,rewards.task());
        if(stack==null||predecessor!=null&&!predecessor.taskObject().equals(stack.object()))
            return hold(parsed,"NEWS_ROUTE_TASK_OBJECT");
        if(original) {
            if(stack.history().size()!=2 || !member(stack,0,1,focus.activity(),TEMPLATE,false,profile)
                    || !hiddenMain(stack,1,profile))return hold(parsed,"NEWS_ROUTE_PREDECESSOR");
            String mainToken=historyToken(stack.history().get(1),0,MAIN);
            var candidate=new Predecessor(stack.object(),mainToken);
            if(predecessor!=null&&!predecessor.equals(candidate))return hold(parsed,"NEWS_ROUTE_MAIN_CHANGED");
            if(parsed.ready()!=null && rewards.sameSource(parsed.ready()))predecessor=candidate;
        } else if(parent) {
            if(stack.history().size()!=1 || !member(stack,0,0,focus.activity(),MAIN,true,profile)
                    || listWindow!=null&&!listWindow.diagnosticWindowMatches(focus.window()))
                return hold(parsed,"NEWS_ROUTE_VISIBLE_MAIN");
        } else {
            if(stack.history().size()!=2 || !member(stack,0,1,focus.activity(),focus.component(),false,profile)
                    || !predecessor.activity().equals(historyToken(stack.history().get(1),0,MAIN))
                    || !hiddenMain(stack,1,profile) || !hiddenListWindow(windows,profile))
                return hold(parsed,"NEWS_ROUTE_ARTICLE_STACK");
        }
        if(parsed.status()==Status.BING_TRANSITION)return new Observation(parsed,null,true,"NEWS_ROUTE_TRANSITION");
        if(parsed.ready()==null || parsed.status()!=Status.READY)
            return hold(parsed,"NEWS_ROUTE_"+parsed.firstGate().name()+"_"+parsed.readOnlyGate().name());
        if(child)checkedViewer=parsed.ready();
        return new Observation(parsed,parsed.ready(),false,"NEWS_ROUTE_READY");
    }
    private static Observation hold(Result r,String why){return new Observation(r,null,false,why);}

    private static Stack stack(String activities,Profile profile,String task){
        if(activities==null || profile==null || activities.length()>262144)return null;
        Section display=uniqueSection(sections(activities,"Display #\\d+ \\(activities from top to bottom\\):"),
                "Display #0 \\(activities from top to bottom\\):");
        if(display==null)return null;
        String head="\\* Task\\{("+TOKEN+") #"+Pattern.quote(task)+" type=standard A="+profile.bingUid()
                +":"+Pattern.quote(PKG)+" U=0 .*\\}";
        Section selected=null;
        String object=null;
        for(var item:sections(display.body()," *\\* Task\\{.*")){
            var match=Pattern.compile(head).matcher(item.header());
            if(item.indent()!=2||!match.matches())continue;
            if(selected!=null)return null;
            selected=item;object=match.group(1);
        }
        if(selected==null || !selected.header().contains(" mode=fullscreen ")
                || !sections(selected.body()," *\\* Task\\{.*").isEmpty())return null;
        var history=sections(selected.body()," *\\* Hist +#\\d+: ActivityRecord\\{.*");
        if(history.isEmpty()||history.size()>2 || history.stream().anyMatch(h->h.indent()!=4))return null;
        return new Stack(object,history);
    }
    private String historyToken(Section section,int index,String component){
        String[] fields=one(section.header(),"^\\* Hist +#"+index+": ActivityRecord\\{("+TOKEN+") u0 "
                +Pattern.quote(component)+" t"+Pattern.quote(rewards.task())+"(?:[^}\\r\\n]*)\\}$",1);
        return fields==null?null:fields[0];
    }
    private boolean member(Stack stack,int position,int index,String token,String component,boolean root,Profile profile){
        var line=stack.history().get(position);
        if(token==null||!token.equals(historyToken(line,index,component)))return false;
        String[] link=one(line.body(),"^ *rootOfTask=(true|false) task=Task\\{("+TOKEN+") #(\\d+) type=standard A=(\\d+):([^}]+)\\} *$",5);
        return link!=null && link[0].equals(Boolean.toString(root)) && link[1].equals(stack.object())
                && link[2].equals(rewards.task()) && link[3].equals(Integer.toString(profile.bingUid())) && link[4].equals(PKG);
    }
    private boolean hiddenMain(Stack stack,int position,Profile profile){
        var main=stack.history().get(position);
        String token=historyToken(main,0,MAIN);
        if(!member(stack,position,0,token,MAIN,true,profile))return false;
        String[] finishing=one(main.body(),"(?<![A-Za-z0-9_])finishing=(true|false)(?![A-Za-z0-9_])",1);
        String[] history=one(main.body(),"(?<![A-Za-z0-9_])inHistory=(true|false)(?![A-Za-z0-9_])",1);
        return finishing!=null && finishing[0].equals("false") && history!=null && history[0].equals("true")
                && activityState(main,token,MAIN,rewards.task(),profile.bingUid(),true)==Status.READY;
    }
    private boolean hiddenListWindow(String windows,Profile profile){
        String head="Window #\\d+ Window\\{("+TOKEN+") u0 "+Pattern.quote(MAIN)+"\\}:";
        Section main=uniqueSection(sections(windows," *Window #\\d+ Window\\{.*"),head);
        if(main==null)return false;
        String[] window=one(main.header(),"^"+head+"$",1);
        String[] link=one(main.body(),"^ *mActivityRecord=ActivityRecord\\{("+TOKEN+") u0 ([^ ]+) t(\\d+)(?:[^}\\r\\n]*)\\} *$",3);
        if(window==null||link==null||!listWindow.diagnosticWindowMatches(window[0])
                ||!listWindow.diagnosticActivityMatches(link[0],link[1],link[2]))return false;
        var block=new Block(window[0],MAIN,null,main.body());
        return appWindow(block,profile,rewards.task(),true) && safelyInvisible(block);
    }
}
