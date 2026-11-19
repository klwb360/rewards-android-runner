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
    private Ready latestSource;
    private Ready confirmedArticle;
    private long backAt=-1;
    static final long RETURN_WAIT_MS=10_000;
    private boolean entryTapped, articleTapped;
    private static final String TOKEN="[a-fA-F0-9]+";
    private static final String PKG="com.microsoft.bing";
    private static final Pattern HIST_START=Pattern.compile("\\* Hist\\s+#\\d+: ActivityRecord\\{.*");
    private static final Pattern NORMAL_HIST=Pattern.compile("\\* Hist\\s+#\\d+: ActivityRecord\\{"+TOKEN+" u\\d+ [^\\s}]+ t\\d+[^}\\r\\n]*\\}");

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
                ||latestSource==null||!latestSource.sameLiveWindow(ready)
                ||listWindow!=null&&!listWindow.sameLiveWindow(ready))throw new SecurityException("NEWS_LIST_ANCHOR");
        listWindow=ready;articleTapped=false;confirmedArticle=null;backAt=-1;
    }
    void articleSucceeded(){
        if(listWindow==null||articleTapped)throw new SecurityException("NEWS_ARTICLE_ANCHOR");
        articleTapped=true;
    }
    boolean isCheckedViewer(Ready ready){return articleTapped && ready!=null && ready==latestSource && ready.kind()!=Kind.MAIN;}
    void confirmArticle(Ready ready){
        if(!articleTapped || backAt>=0 || ready==null || listWindow==null || !listWindow.sameTask(ready)
                || latestSource==null || !latestSource.sameLiveWindow(ready)
                || confirmedArticle!=null && !confirmedArticle.sameLiveWindow(ready))throw new SecurityException("NEWS_ARTICLE_WINDOW_CHANGED");
        confirmedArticle=ready;
    }
    void backSucceeded(Ready article,long now){
        if(confirmedArticle==null || !confirmedArticle.sameLiveWindow(article) || latestSource==null
                || !latestSource.sameLiveWindow(article) || backAt>=0 || now<0)
            throw new SecurityException("NEWS_ROUTE_BACK_ANCHOR");
        backAt=now;
    }
    private Observation waitAfterBack(Result parsed,long now){
        if(backAt<0 || now<backAt || now-backAt>=RETURN_WAIT_MS)return hold(parsed,"NEWS_ROUTE_RETURN_WAIT_EXPIRED");
        return new Observation(parsed,null,true,"NEWS_ROUTE_WAIT_ARTICLE_EXIT");
    }

    Observation inspect(String windows,String activities,Profile profile,ReadOnlyGuard guard,long lower,long observed){
        latestSource=null;
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
        if(child && confirmedArticle!=null && !(confirmedArticle.diagnosticWindowMatches(focus.window())
                && confirmedArticle.diagnosticActivityMatches(focus.activity(),focus.component(),focus.task())))
            return hold(parsed,"NEWS_ROUTE_ARTICLE_SUBSTITUTED");
        if(entryTapped && MAIN.equals(focus.component()) && !parent)return hold(parsed,"NEWS_ROUTE_MAIN_SUBSTITUTED");
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
            if(listWindow!=null&&!listWindow.diagnosticWindowMatches(focus.window()))return hold(parsed,"NEWS_ROUTE_MAIN_WINDOW_CHANGED");
            if(stack.history().size()==2 && listWindow==null)
                return departing(parsed,stack,focus,windows,activities,profile,guard,rewards,observed);
            if(stack.history().size()==2 && backAt>=0 && confirmedArticle!=null && confirmedArticle.kind()!=Kind.MAIN)
                return departing(parsed,stack,focus,windows,activities,profile,guard,confirmedArticle,observed);
            if(stack.history().size()!=1)return hold(parsed,"NEWS_ROUTE_MAIN_HISTORY_COUNT");
            if(!member(stack,0,0,focus.activity(),MAIN,true,profile))return hold(parsed,"NEWS_ROUTE_MAIN_MEMBER");
        } else {
            if(stack.history().size()!=2 || !member(stack,0,1,focus.activity(),focus.component(),false,profile)
                    || !predecessor.activity().equals(historyToken(stack.history().get(1),0,MAIN))
                    || !hiddenMain(stack,1,profile) || !hiddenListWindow(windows,profile))
                return hold(parsed,"NEWS_ROUTE_ARTICLE_STACK");
        }
        if(parsed.status()==Status.BING_TRANSITION)return backAt>=0?waitAfterBack(parsed,observed)
                :new Observation(parsed,null,true,"NEWS_ROUTE_TRANSITION");
        if(parsed.ready()==null || parsed.status()!=Status.READY)
            return hold(parsed,"NEWS_ROUTE_"+parsed.firstGate().name()+"_"+parsed.readOnlyGate().name());
        if(child && backAt>=0)return waitAfterBack(parsed,observed);
        latestSource=parsed.ready();
        return new Observation(parsed,parsed.ready(),false,"NEWS_ROUTE_READY");
    }
    private static Observation hold(Result r,String why){return new Observation(r,null,false,why);}

    private Observation departing(Result parsed,Stack stack,BingForegroundSnapshot.Snapshot focus,
            String windows,String activities,Profile profile,ReadOnlyGuard guard,Ready departing,long observed){
        if(!member(stack,1,0,focus.activity(),MAIN,true,profile))return hold(parsed,"NEWS_ROUTE_MAIN_MEMBER");
        boolean returning=backAt>=0;
        String oldComponent=departing.kind()==Kind.BROWSER?BROWSER:TEMPLATE;
        var leaving=stack.history().get(0);
        String[] id=one(leaving.header(),"^\\* Hist +#1: ActivityRecord\\{("+TOKEN+") u0 "+Pattern.quote(oldComponent)
                +" t"+Pattern.quote(rewards.task())+"(?:\\}| f\\}\\}?)$",1);
        if(id==null || !departing.diagnosticActivityMatches(id[0],oldComponent,rewards.task()))
            return hold(parsed,returning?"NEWS_ROUTE_ARTICLE_SUBSTITUTED":"NEWS_ROUTE_TEMPLATE_SUBSTITUTED");
        if(!linked(stack,leaving,false,profile))return hold(parsed,returning?"NEWS_ROUTE_ARTICLE_MEMBER":"NEWS_ROUTE_TEMPLATE_MEMBER");
        boolean headers=parsed.status()==Status.UNKNOWN && parsed.firstGate()==FirstGate.ACTIVITY_HEADERS;
        boolean mainReady=returning && parsed.status()==Status.READY && listWindow.sameLiveWindow(parsed.ready());
        if(!headers && !mainReady && parsed.status()!=Status.BING_TRANSITION)
            return hold(parsed,(returning?"NEWS_ROUTE_RETURN_EXIT_":"NEWS_ROUTE_ENTRY_EXIT_")+parsed.firstGate().name());
        // Some Android finishing records end in " f}}". Only this exact authenticated departing record
        // may carry that extra brace. An unparseable header elsewhere is independently disqualifying.
        if(headers && !onlyDepartingHeader(activities,leaving.header()))
            return hold(parsed,returning?"NEWS_ROUTE_RETURN_OTHER_HEADER":"NEWS_ROUTE_ENTRY_OTHER_HEADER");
        String[] top=one(activities,"^ *topResumedActivity=ActivityRecord\\{("+TOKEN+") u0 ([^\\s}]+) t(\\d+)(?:\\}| f\\}\\}?) *$",3);
        if(top==null || !rewards.task().equals(top[2]) || !(MAIN.equals(top[1]) && focus.activity().equals(top[0])
                || departing.diagnosticActivityMatches(top[0],top[1],top[2])))return hold(parsed,"NEWS_ROUTE_EXIT_TOP");
        String[] state=one(leaving.body(),"(?<![A-Za-z0-9_])state=([A-Z]+)(?![A-Za-z0-9_])",1);
        String[] finishing=one(leaving.body(),"(?<![A-Za-z0-9_])finishing=(true|false)(?![A-Za-z0-9_])",1);
        if(state==null || finishing==null || !state[0].matches("RESUMED|PAUSING|PAUSED|STOPPING|STOPPED|DESTROYING|DESTROYED")
                || !finishing[0].equals("true") && (headers || mainReady || !state[0].matches("PAUSING|STOPPING|DESTROYING|DESTROYED")))
            return hold(parsed,"NEWS_ROUTE_EXIT_LIFECYCLE");
        if(!exitWindows(windows,focus,id[0],oldComponent,departing,profile,guard))return hold(parsed,"NEWS_ROUTE_EXIT_WINDOWS");
        return returning?waitAfterBack(parsed,observed):new Observation(parsed,null,true,"NEWS_ROUTE_WAIT_REWARDS_EXIT");
    }
    private static boolean onlyDepartingHeader(String activities,String leaving){
        int ordinary=0,finishing=0;
        for(String raw:activities.split("\\r?\\n")){
            String line=raw.strip();
            if(!HIST_START.matcher(line).matches())continue;
            if(NORMAL_HIST.matcher(line).matches())ordinary++;
            else if(line.equals(leaving) && line.endsWith(" f}}"))finishing++;
            else return false;
        }
        return finishing==1 && ordinary>0 && ordinary+finishing<=64;
    }
    private boolean exitWindows(String windows,BingForegroundSnapshot.Snapshot focus,String oldActivity,String oldComponent,
            Ready departing,Profile p,ReadOnlyGuard guard){
        int main=0,previous=0,own=0;
        for(var s:sections(windows," *Window #\\d+ Window\\{.*")){
            String[] id=one(s.header(),"^Window #\\d+ Window\\{("+TOKEN+") u\\d+ ([^}\\r\\n]+)\\}:$",2);
            if(id==null)return false;
            var block=new Block(id[0],id[1],null,s.body());
            if(MAIN.equals(id[1]) || oldComponent.equals(id[1])){
                boolean isMain=MAIN.equals(id[1]);
                String[] link=one(s.body(),"^ *mActivityRecord=ActivityRecord\\{("+TOKEN+") u0 ([^\\s}]+) t(\\d+)(?:\\}| f\\}\\}?) *$",3);
                if(!appWindow(block,p,rewards.task(),!BROWSER.equals(id[1])) || link==null || !link[1].equals(id[1])
                        || !link[2].equals(rewards.task()) || !(isMain?focus.activity():oldActivity).equals(link[0]))return false;
                if(isMain){if(!focus.window().equals(id[0]))return false;main++;}
                else{if(!departing.diagnosticWindowMatches(id[0]))return false;previous++;}
            }else if(guard!=null && id[1].equals(guard.ownComponent())){
                String pkg=id[1].substring(0,id[1].indexOf('/'));
                String[] owner=one(s.body(),"^ *mOwnerUid=(\\d+)\\b[^\\r\\n]*\\bpackage=([^\\s]+)\\s+appop=([^\\s]+)[^\\r\\n]*$",3);
                String[] display=one(s.body(),"^ *mDisplayId=(\\d+)\\s+rootTaskId=(\\d+)\\b.*$",2);
                if(!safelyInvisible(block)||owner==null||display==null||!owner[0].equals(Integer.toString(guard.ownUid()))
                        ||!owner[1].equals(pkg)||!owner[2].equals("NONE")||!display[0].equals("0")
                        ||!display[1].equals(Integer.toString(guard.ownTaskId())))return false;
                own++;
            }else if(!"StatusBar".equals(id[1]) && !safelyInvisible(block))return false;
        }
        // Earlier strict parsing has already rejected unidentified visible windows and invalid StatusBar owners.
        return main==1 && previous<=1 && own==1;
    }

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
            if(!item.header().matches("\\* Task\\{[^\\s]+ #"+Pattern.quote(task)+"\\b.*"))continue;
            if(item.indent()!=2||!match.matches())return null;
            if(selected!=null)return null;
            selected=item;object=match.group(1);
        }
        if(selected==null || !selected.header().contains(" mode=fullscreen ")
                || !sections(selected.body()," *\\* Task\\{.*").isEmpty())return null;
        var history=sections(selected.body()," *\\* Hist +#\\d+: ActivityRecord\\{.*");
        if(history.isEmpty() || history.stream().anyMatch(h->h.indent()!=4))return null;
        return new Stack(object,history);
    }
    private String historyToken(Section section,int index,String component){
        String[] fields=one(section.header(),"^\\* Hist +#"+index+": ActivityRecord\\{("+TOKEN+") u0 "
                +Pattern.quote(component)+" t"+Pattern.quote(rewards.task())+"(?:[^}\\r\\n]*)\\}$",1);
        return fields==null?null:fields[0];
    }
    private boolean member(Stack stack,int position,int index,String token,String component,boolean root,Profile profile){
        var line=stack.history().get(position);
        return token!=null && token.equals(historyToken(line,index,component)) && linked(stack,line,root,profile);
    }
    private boolean linked(Stack stack,Section line,boolean root,Profile profile){
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
