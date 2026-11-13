package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Two independently read system metadata documents, never WebView/body/OCR content.
 * Ready requires the exact focused Window and exact resumed/drawn Activity block.
 * WM-only focus is insufficient. These snapshots do not prove absence of in-window HTML UI. */
final class BingNavigationSnapshot {
    static final String TEMPLATE="com.microsoft.bing/com.microsoft.sapphire.runtime.templates.TemplateActivity";
    static final String BROWSER="com.microsoft.bing/com.microsoft.sapphire.app.browser.BrowserActivity";
    static final String MAIN="com.microsoft.bing/com.microsoft.sapphire.app.main.MainSapphireActivity";
    enum Kind { TEMPLATE, BROWSER, MAIN }
    enum Status { READY, BING_TRANSITION, UNKNOWN }
    record Shape(int width,int height,int rotation) {}
    record Profile(int bingUid,int systemUiUid,Shape shape) {}
    enum ReadOnlyGate { NOT_APPLIED, LEGACY_METADATA, FORMAT_UNKNOWN, TARGET_DRAW, OWN_WINDOW, TARGET_ACTIVITY, OWN_ACTIVITY, TARGET_TASK, OWN_TASK, METADATA_STABLE_ONLY }
    enum FirstGate { NOT_APPLIED, INPUT, FOCUS_FORMAT, FOCUS_COMPONENT_CONFLICT, FOCUS_COMPONENT,
        WINDOW_HEADERS, FOCUSED_WINDOW, VISIBLE_UNKNOWN_WINDOW, HIDDEN_WINDOW_UNPROVEN, WINDOW_IDENTITY,
        TOP_RESUMED_COUNT, TOP_RESUMED_FORMAT, TOP_RESUMED_TASK, ACTIVITY_HEADERS, RESUMED_ACTIVITY,
        ACTIVITY_TASK, KNOWN_TRANSITION, READ_ONLY_STABILITY, READY,
        RECOVERY_BROWSER_REQUIRED, RECOVERY_TEMPLATE_COUNT, RECOVERY_TEMPLATE_TASK,
        RECOVERY_TEMPLATE_STATE, RECOVERY_TEMPLATE_WINDOW, RECOVERY_TEMPLATE_WINDOW_BINDING,
        RECOVERY_ALREADY_TEMPLATE, RECOVERY_BOUND, CURRENT_TEMPLATE_REQUIRED, CURRENT_TEMPLATE_BOUND, SELECTED_BOUND_CROSS_ACTIVITY, SELECTED_CROSS_UNPROVEN, TIMED_TASK_PRESENT, TIMED_TASK_REJECTED, TIMED_RETURN_ANCHOR }
    record Result(Status status,Ready ready,ReadOnlyGate readOnlyGate,FirstGate firstGate) {
        Result(Status status,Ready ready){this(status,ready,ReadOnlyGate.NOT_APPLIED,FirstGate.NOT_APPLIED);}
        Result(Status status,Ready ready,ReadOnlyGate gate){this(status,ready,gate,FirstGate.NOT_APPLIED);}
        static Result unknown(){return new Result(Status.UNKNOWN,null);}
    }
    private static Result unknown(FirstGate gate){return new Result(Status.UNKNOWN,null,ReadOnlyGate.NOT_APPLIED,gate);}
    record RecoveryResult(Status status,Ready ready,RecoverySource source,FirstGate firstGate,ReadOnlyGate readOnlyGate) {
        RecoveryResult(Status status,Ready ready,RecoverySource source,FirstGate firstGate){this(status,ready,source,firstGate,ReadOnlyGate.NOT_APPLIED);}
        String summary(){return "recoveryStatus="+status+" recoveryGate="+firstGate+" readyKind="+(ready==null?"NONE":ready.kind())
            +" browserAndTemplateBound="+(source!=null);}
    }
    /** Only this parser may bind current Browser and its exact stopped in-task Template window. */
    static final class RecoverySource {
        private final Ready browser;
        private final BingForegroundSnapshot.Snapshot template;
        private RecoverySource(Ready browser,BingForegroundSnapshot.Snapshot template){this.browser=browser;this.template=template;}
        Ready browser(){return browser;}
        boolean templateMatches(Ready ready){return ready!=null&&ready.kind==Kind.TEMPLATE&&browser.profile.equals(ready.profile)&&template.equals(ready.focus);}
        boolean browserMatches(Ready ready){return browser.sameBrowser(ready);}
        boolean sameSource(RecoverySource other){return other!=null&&browser.sameBrowser(other.browser)&&template.equals(other.template);}
        String summary(){return "recoveryBrowserReady=true templateInSameTask=true exactTemplateWindowBound=true";}
    }
    /** Strict current-Template evidence, unrelated to Back success or any business result. */
    static final class CurrentTemplateSource {
        private final Ready template;
        private CurrentTemplateSource(Ready template){this.template=template;}
        Ready ready(){return template;}
        boolean matches(Ready other){return template.sameSource(other);}
        boolean fresh(long now){return template.fresh(now);}
        String summary(){return "currentTemplateBound=true metadataOnly=true backEvidence=false";}
    }
    record CurrentTemplateResult(Status status,CurrentTemplateSource source,FirstGate firstGate,ReadOnlyGate readOnlyGate) {
        String summary(){return "currentTemplateStatus="+status+" currentTemplateGate="+firstGate+" strictMetadataGate="+readOnlyGate+" currentTemplateBound="+(source!=null);}
    }
    static CurrentTemplateResult parseCurrentTemplate(String windows,String activities,Profile profile,ReadOnlyGuard guard,long lower,long observed){
        if(guard==null||!guard.valid())return new CurrentTemplateResult(Status.UNKNOWN,null,FirstGate.INPUT,ReadOnlyGate.NOT_APPLIED);
        Result r=parseRootReadOnly(windows,activities,profile,guard,lower,observed);
        if(r.status()!=Status.READY)return new CurrentTemplateResult(r.status(),null,r.firstGate(),r.readOnlyGate());
        if(r.ready()==null||r.ready().kind()!=Kind.TEMPLATE)return new CurrentTemplateResult(Status.UNKNOWN,null,FirstGate.CURRENT_TEMPLATE_REQUIRED,r.readOnlyGate());
        return new CurrentTemplateResult(Status.READY,new CurrentTemplateSource(r.ready()),FirstGate.CURRENT_TEMPLATE_BOUND,r.readOnlyGate());
    }
    /** Unified private-source initial state. No Back or business success is implied. */
    record StatusRecoveryInitial(Status status,CurrentTemplateSource template,RecoverySource browser,FirstGate firstGate,ReadOnlyGate readOnlyGate) {
        String summary(){return "statusRecoveryInitial="+status+" initialGate="+firstGate+" strictMetadataGate="+readOnlyGate
            +" typedTemplate="+(template!=null)+" typedBrowserWithTemplate="+(browser!=null);}
    }
    static StatusRecoveryInitial parseStatusRecovery(String windows,String activities,Profile profile,ReadOnlyGuard guard,long lower,long observed){
        if(guard==null||!guard.valid())return new StatusRecoveryInitial(Status.UNKNOWN,null,null,FirstGate.INPUT,ReadOnlyGate.NOT_APPLIED);
        Result strict=parseRootReadOnly(windows,activities,profile,guard,lower,observed);
        if(strict.status()!=Status.READY)return new StatusRecoveryInitial(strict.status(),null,null,strict.firstGate(),strict.readOnlyGate());
        if(strict.ready().kind()==Kind.TEMPLATE)return new StatusRecoveryInitial(Status.READY,new CurrentTemplateSource(strict.ready()),null,FirstGate.CURRENT_TEMPLATE_BOUND,strict.readOnlyGate());
        RecoveryResult recovery=parseReturnRead(windows,activities,profile,guard,lower,observed);
        return new StatusRecoveryInitial(recovery.status(),null,recovery.source(),recovery.firstGate(),recovery.status()==Status.READY?strict.readOnlyGate():recovery.readOnlyGate());
    }

    /** A timed-return safety fact only. It is not Ready, Browser identity, page identity or completion. */
    enum TimedTaskGate { INPUT, FOCUS, WINDOW_HEADERS, ACTIVITY_HEADERS, ANCHOR, WINDOW_BINDING, TASK_BINDING, OVERLAY, HANDOFF, PRESENT }
    static final class TimedTaskGuard {
        private final Ready original;
        private final long lower,observed;
        private final TimedTaskGate gate;
        private TimedTaskGuard(Ready original,long lower,long observed,TimedTaskGate gate){this.original=original;this.lower=lower;this.observed=observed;this.gate=gate;}
        static TimedTaskGuard notObserved(){return new TimedTaskGuard(null,-1,-1,TimedTaskGate.INPUT);}
        boolean matches(Ready exact,long now){return gate==TimedTaskGate.PRESENT&&original!=null&&original.sameSource(exact)
            &&lower>=0&&observed>=lower&&now>=observed&&now-lower<=5_000;}
        TimedTaskGate gate(){return gate;}
        String summary(){return "timedTaskGate="+gate+" taskPresenceOnly=true browserReady=NOT_REQUIRED globalTop=NOT_USED pageIdentity=NOT_ESTABLISHED";}
        @Override public String toString(){return summary();}
    }
    private static TimedTaskGuard timedReject(TimedTaskGate gate){return new TimedTaskGuard(null,-1,-1,gate);}
    /** Uses the unmodified bounded documents. Deliberately does not parse global TOP or issue Ready. */
    private static boolean timedBingComponent(String component){return component!=null&&component.matches("com\\.microsoft\\.bing/(?:\\.?[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)");}
    private static Block timedUnique(List<Block> blocks,String component){Block found=null;for(Block b:blocks)if(b.name.equals(component)){if(found!=null)return null;found=b;}return found;}
    private static boolean timedBoundWindow(Block window,Block activity,Profile p,String task){
        String[] display=one(window.text,"^\\s*mDisplayId=(\\d+)\\s+rootTaskId=(\\d+)\\b.*$",2);
        String[] owner=one(window.text,"^\\s*mOwnerUid=(\\d+)\\b[^\\r\\n]*\\bpackage=([^\\s]+)\\s+appop=([^\\s]+)[^\\r\\n]*$",3);
        String[] requested=one(window.text,"^\\s*Requested w=(\\d+) h=(\\d+)(?:\\s.*)?$",2);
        String[] link=one(window.text,"^ *mActivityRecord=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^ ]+) t(\\d+)(?:[^}]*)\\} *$",3);
        if(!timedBingComponent(window.name)||activity==null||!window.name.equals(activity.name)||!task.equals(activity.task)||display==null||!display[0].equals("0")||!display[1].equals(task)
            ||owner==null||!owner[0].equals(Integer.toString(p.bingUid))||!owner[1].equals("com.microsoft.bing")||!owner[2].equals("NONE")
            ||requested==null||!requested[0].equals(Integer.toString(p.shape.width))||!requested[1].equals(Integer.toString(p.shape.height))
            ||!Pattern.compile("(?m)^\\s*mAttrs=\\{[^\\r\\n]*\\bty=BASE_APPLICATION\\b").matcher(window.text).find()
            ||link==null||!link[0].equals(activity.token)||!link[1].equals(activity.name)||!link[2].equals(task))return false;
        for(String key:List.of("mHasSurface","isVisible","isOnScreen")){String v=value(window.text,key);if(v==null||!v.matches("true|false"))return false;}
        return visible(window)||safelyInvisible(window);
    }
    static TimedTaskGuard parseTimedTaskGuard(String windows,String activities,Profile profile,ReadOnlyGuard guard,Ready original,long lower,long observed){
        if(windows==null||activities==null||windows.length()>262144||activities.length()>262144||windows.indexOf('\0')>=0||activities.indexOf('\0')>=0
            ||profile==null||profile.bingUid<10000||profile.systemUiUid<10000||profile.shape==null||profile.shape.width<=0||profile.shape.height<=0
            ||profile.shape.rotation<0||profile.shape.rotation>3||guard==null||!guard.valid()||original==null||original.kind()!=Kind.TEMPLATE
            ||!original.profile.equals(profile)||!original.task().equals(Integer.toString(RootSelectedPendingScope.SOURCE_TASK_ID))
            ||lower<0||observed<lower||observed-lower>5_000)return timedReject(TimedTaskGate.INPUT);
        String[] display=one(windows,"^[ \\t]*mTopFocusedDisplayId=([0-9]+)[ \\t]*$",1);
        String[] focus=one(windows,"^[ \\t]*mCurrentFocus=Window\\{([a-fA-F0-9]+) u0 ([^\\s}]+)\\}[ \\t]*$",2);
        String[] app=one(windows,"^[ \\t]*mFocusedApp=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^\\s}]+) t([0-9]+)(?:[^}\\r\\n]*)\\}[ \\t]*$",3);
        if(display==null||!display[0].equals("0")||focus==null||app==null||!timedBingComponent(focus[1])||!timedBingComponent(app[1])||!original.task().equals(app[2]))return timedReject(TimedTaskGate.FOCUS);
        List<Block> wb=blocks(windows,WINDOW,false),ab=blocks(activities,HIST,true);
        if(wb.isEmpty()||headerCount(windows,"(?m)^\\s*Window #\\d+ Window\\{")!=wb.size())return timedReject(TimedTaskGate.WINDOW_HEADERS);
        if(ab.isEmpty()||headerCount(activities,"(?m)^\\s*\\* Hist\\s+#\\d+: ActivityRecord\\{")!=ab.size())return timedReject(TimedTaskGate.ACTIVITY_HEADERS);
        List<Section> ws=sections(windows," *Window #\\d+ Window\\{.*"),as=sections(activities," *\\* Hist +#\\d+: ActivityRecord\\{.*");
        List<Block> appWindows=new ArrayList<>(),appActivities=new ArrayList<>();
        for(Block raw:wb){
            Section sec=uniqueSection(ws,"Window #\\d+ Window\\{"+Pattern.quote(raw.token)+" u0 "+Pattern.quote(raw.name)+"\\}:");
            if(sec==null)return timedReject(TimedTaskGate.WINDOW_HEADERS);
            Block bound=new Block(raw.token,raw.name,null,sec.body);
            if(timedBingComponent(bound.name)){
                String[] binding=one(bound.text,"^ *mActivityRecord=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^ ]+) t(\\d+)(?:[^}]*)\\} *$",3);
                boolean relevant=bound.token.equals(original.focus.window())||bound.token.equals(focus[0])
                    ||binding!=null&&binding[0].equals(app[0])&&binding[1].equals(app[1])&&binding[2].equals(app[2]);
                if(!relevant){if(!safelyInvisible(bound))return timedReject(TimedTaskGate.OVERLAY);continue;}
                appWindows.add(bound);if(appWindows.size()>8)return timedReject(TimedTaskGate.WINDOW_BINDING);
            }
            else if(visible(bound)){if(!statusBar(bound,profile))return timedReject(TimedTaskGate.OVERLAY);}
            else if(!safelyInvisible(bound))return timedReject(TimedTaskGate.OVERLAY);
        }
        for(Block raw:ab)if(timedBingComponent(raw.name)){
            Section sec=uniqueSection(as,"\\* Hist +#\\d+: ActivityRecord\\{"+Pattern.quote(raw.token)+" u0 "+Pattern.quote(raw.name)+" t"+Pattern.quote(raw.task)+"(?:[^}]*)\\}");
            if(sec==null)return timedReject(TimedTaskGate.ACTIVITY_HEADERS);
            boolean related=raw.token.equals(original.focus.activity())&&raw.name.equals(TEMPLATE)&&raw.task.equals(original.task())
                ||raw.token.equals(app[0])&&raw.name.equals(app[1])&&raw.task.equals(app[2]);
            for(Block window:appWindows){String[] link=one(window.text,"^ *mActivityRecord=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^ ]+) t(\\d+)(?:[^}]*)\\} *$",3);
                if(link!=null&&link[0].equals(raw.token)&&link[1].equals(raw.name)&&link[2].equals(raw.task))related=true;}
            if(related)appActivities.add(new Block(raw.token,raw.name,raw.task,sec.body));
        }
        Block tw=timedUnique(appWindows,TEMPLATE),ta=timedUnique(appActivities,TEMPLATE);
        if(tw==null||ta==null||!original.focus.window().equals(tw.token)||!original.focus.activity().equals(ta.token)||!original.task().equals(ta.task))return timedReject(TimedTaskGate.ANCHOR);
        for(Block window:appWindows){Block activity=timedUnique(appActivities,window.name);
            if(!timedBoundWindow(window,activity,profile,original.task())||!crossActivity(activity))return timedReject(TimedTaskGate.WINDOW_BINDING);
            // Full task/UID/shape/draw-field/lifecycle/hidden-owner chains are still parsed.
            // A recognized ongoing transition is presence, never a Ready or page assertion.
            if(readonlyStability(windows,activities,profile,guard,new BingForegroundSnapshot.Snapshot(window.token,activity.token,activity.name,original.task())).status==Status.UNKNOWN)
                return timedReject(TimedTaskGate.HANDOFF);
        }
        Block focused=timedUnique(appWindows,focus[1]),focusedApp=timedUnique(appActivities,app[1]);
        if(focused==null||focusedApp==null||!focus[0].equals(focused.token)||!app[0].equals(focusedApp.token)||!original.task().equals(focusedApp.task)||!visible(focused))return timedReject(TimedTaskGate.FOCUS);
        // Also require a window chain for the focused app when WM is in a cross-activity transition.
        if(timedUnique(appWindows,app[1])==null)return timedReject(TimedTaskGate.WINDOW_BINDING);
        Section own=uniqueSection(ws,"Window #\\d+ Window\\{[a-fA-F0-9]+ u0 "+Pattern.quote(guard.ownComponent)+"\\}:");
        if(own==null||!safelyInvisible(new Block("",guard.ownComponent,null,own.body)))return timedReject(TimedTaskGate.HANDOFF);
        Section task=timedTask(activities,original.task(),profile.bingUid,ta);
        if(task==null||!"true".equals(value(task.header,"visible"))||!"true".equals(value(task.header,"visibleRequested")))return timedReject(TimedTaskGate.TASK_BINDING);
        return new TimedTaskGuard(original,lower,observed,TimedTaskGate.PRESENT);
    }
    private static Section timedTask(String activities,String task,int uid,Block template){
        Section display=uniqueSection(sections(activities,"Display #\\d+ \\(activities from top to bottom\\):"),"Display #0 \\(activities from top to bottom\\):");
        if(display==null)return null;
        Section found=null;String header="\\* Task\\{([a-fA-F0-9]+) #"+Pattern.quote(task)+" type=standard A="+uid+":com\\.microsoft\\.bing U=0 .*\\}";
        for(Section s:sections(display.body," *\\* Task\\{.*"))if(s.indent==2&&s.header.matches(header)){
            if(found!=null)return null;found=s;
        }
        if(found==null||!"fullscreen".equals(value(found.header,"mode")))return null;
        String[] id=one(found.header,"^\\* Task\\{([a-fA-F0-9]+) #",1);
        Section a=uniqueSection(sections(found.body," *\\* Hist +#\\d+: ActivityRecord\\{.*"),"\\* Hist +#\\d+: ActivityRecord\\{"+Pattern.quote(template.token)+" u0 "+Pattern.quote(TEMPLATE)+" t"+Pattern.quote(task)+"(?:[^}]*)\\}");
        if(id==null||a==null||!Pattern.compile("(?m)^ *rootOfTask=(?:true|false) task=Task\\{"+Pattern.quote(id[0])+" #"+Pattern.quote(task)+" type=standard A="+uid+":com\\.microsoft\\.bing\\} *$").matcher(a.body).find())return null;
        return found;
    }

    /** Diagnostic syntax counts only; not a Ready policy or a stored token/tuple. */
    record TopDiagnostic(boolean observed,int tokens,int lineStarts,int validLines,int nullLines,int distinctTuples) {
        static TopDiagnostic notObserved(){return new TopDiagnostic(false,-1,-1,-1,-1,-1);}
    }
    static TopDiagnostic topDiagnostic(String activities){
        if(activities==null||activities.length()>262144||activities.indexOf('\0')>=0)return TopDiagnostic.notObserved();
        int tokens=count(activities,"topResumedActivity="),starts=headerCount(activities,"(?m)^[ \\t]*topResumedActivity="),valid=0;
        Matcher records=Pattern.compile("(?m)^[ \\t]*topResumedActivity=ActivityRecord\\{([a-fA-F0-9]+) u([0-9]+) ([^\\s}/]+/[^\\s}]+) t([0-9]+)(?:[^}\\r\\n]*?)\\}[ \\t]*$").matcher(activities);
        java.util.Set<String> tuples=new java.util.HashSet<>();
        try{while(records.find()){valid++;tuples.add(records.group(1)+"\n"+records.group(2)+"\n"+records.group(3)+"\n"+records.group(4));}
            int nil=headerCount(activities,"(?m)^[ \\t]*topResumedActivity=null[ \\t]*$");return new TopDiagnostic(true,tokens,starts,valid,nil,tuples.size());
        }finally{tuples.clear();}
    }
    static final class Ready {
        private final BingForegroundSnapshot.Snapshot focus;
        private final Kind kind;
        private final Profile profile;
        private final long lower,observed;
        private Ready(BingForegroundSnapshot.Snapshot f,Kind k,Profile p,long lower,long observed){
            focus=f;kind=k;profile=p;this.lower=lower;this.observed=observed;
        }
        boolean diagnosticActivityMatches(String record,String component,String task){return focus.activity().equals(record)&&focus.component().equals(component)&&focus.task().equals(task);}
        boolean diagnosticWindowMatches(String window){return focus.window().equals(window);}
        Kind kind(){return kind;}
        String task(){return focus.task();}
        Profile profile(){return profile;}
        long lower(){return lower;}
        long observed(){return observed;}
        boolean sameSource(Ready other){return other!=null&&kind==Kind.TEMPLATE&&other.kind==kind
            &&profile.equals(other.profile)&&focus.equals(other.focus);}
        boolean sameBrowser(Ready other){return other!=null&&kind==Kind.BROWSER&&other.kind==kind
            &&profile.equals(other.profile)&&focus.equals(other.focus);}
        boolean sameEntrySource(Ready other){return other!=null&&(kind==Kind.MAIN||kind==Kind.TEMPLATE)&&kind==other.kind&&profile.equals(other.profile)&&focus.equals(other.focus);}
        boolean sameTask(Ready other){return other!=null&&profile.equals(other.profile)&&task().equals(other.task());}
        boolean fresh(long now){return lower>=0&&observed>=lower&&now>=observed&&now-lower<=5_000;}
        // No generated toString(): do not export tokens/window/Activity metadata.
    }
    private record Block(String token,String name,String task,String text) {}
    private static final Pattern WINDOW=Pattern.compile("(?m)^\\s*Window #\\d+ Window\\{([a-fA-F0-9]+) u\\d+ ([^}\\r\\n]+)\\}:\\s*$");
    private static final Pattern HIST=Pattern.compile("(?m)^\\s*\\* Hist\\s+#\\d+: ActivityRecord\\{([a-fA-F0-9]+) u\\d+ ([^\\s}]+) t([0-9]+)(?:[^}]*?)\\}\\s*$");
    private static final Pattern TOP=Pattern.compile("(?m)^\\s*topResumedActivity=ActivityRecord\\{([a-fA-F0-9]+) u0 (com\\.microsoft\\.bing/[^\\s}]+) t([0-9]+)(?:[^}]*?)\\}\\s*$");
    private static List<Block> blocks(String text,Pattern header,boolean activity){
        Matcher m=header.matcher(text);List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();List<String[]> ids=new ArrayList<>();
        while(m.find()){
            if(ids.size()>=64)return List.of();starts.add(m.start());ends.add(m.end());
            ids.add(new String[]{m.group(1),m.group(2),activity?m.group(3):null});
        }
        List<Block> out=new ArrayList<>();
        for(int i=0;i<ids.size();i++){String[] id=ids.get(i);out.add(new Block(id[0],id[1],id[2],text.substring(ends.get(i),i+1<ids.size()?starts.get(i+1):text.length())));}
        return out;
    }
    private static int headerCount(String text,String pattern){Matcher m=Pattern.compile(pattern).matcher(text);int n=0;while(m.find())n++;return n;}
    private static int count(String text,String token){int n=0,at=0;while((at=text.indexOf(token,at))>=0){n++;at+=token.length();}return n;}
    private static boolean token(String text,String key,String value){return Pattern.compile("(?<![A-Za-z0-9_])"+Pattern.quote(key)+"="+Pattern.quote(value)+"(?![A-Za-z0-9_])").matcher(text).find();}
    private static boolean exactTrue(String text,String key){return token(text,key,"true")&&!token(text,key,"false");}
    private static String[] one(String text,String pattern,int groups){
        Matcher m=Pattern.compile(pattern,Pattern.MULTILINE).matcher(text);if(!m.find())return null;
        String[] values=new String[groups];for(int i=0;i<groups;i++)values[i]=m.group(i+1);
        return m.find()?null:values;
    }
    private static boolean namedComponent(String component){return TEMPLATE.equals(component)||BROWSER.equals(component);}
    private static boolean entryComponent(String component){return TEMPLATE.equals(component)||MAIN.equals(component);}
    private static boolean visible(Block block){return token(block.text,"isVisible","true")||token(block.text,"isOnScreen","true")
        ||Pattern.compile("(?m)^\\s*Surface: shown=true\\b").matcher(block.text).find();}
    private static boolean safelyInvisible(Block block){return token(block.text,"isOnScreen","false")&&!token(block.text,"isOnScreen","true")
        &&token(block.text,"isVisible","false")&&!token(block.text,"isVisible","true")
        &&(token(block.text,"mHasSurface","false")||Pattern.compile("(?m)^\\s*Surface: shown=false\\b").matcher(block.text).find());}
    private static boolean appWindow(Block b,Profile p,String task){return appWindow(b,p,task,false);}
    private static boolean appWindow(Block b,Profile p,String task,boolean entry){
        String[] display=one(b.text,"^\\s*mDisplayId=(\\d+)\\s+rootTaskId=(\\d+)\\b.*$",2);
        String[] owner=one(b.text,"^\\s*mOwnerUid=(\\d+)\\b[^\\r\\n]*\\bpackage=([^\\s]+)\\s+appop=([^\\s]+)[^\\r\\n]*$",3);
        String[] requested=one(b.text,"^\\s*Requested w=(\\d+) h=(\\d+)(?:\\s.*)?$",2);
        return (entry?entryComponent(b.name):namedComponent(b.name))&&display!=null&&display[0].equals("0")&&display[1].equals(task)
            &&owner!=null&&owner[0].equals(Integer.toString(p.bingUid))&&owner[1].equals("com.microsoft.bing")&&owner[2].equals("NONE")
            &&requested!=null&&requested[0].equals(Integer.toString(p.shape.width))&&requested[1].equals(Integer.toString(p.shape.height))
            &&Pattern.compile("(?m)^\\s*mAttrs=\\{[^\\r\\n]*\\bty=BASE_APPLICATION\\b").matcher(b.text).find();
    }
    private static boolean drawnWindow(Block b){return exactTrue(b.text,"mHasSurface")&&exactTrue(b.text,"isOnScreen")&&exactTrue(b.text,"isVisible")
        &&Pattern.compile("isReadyForDisplay\\(\\)=true").matcher(b.text).find()
        &&Pattern.compile("(?m)^\\s*Surface: shown=true\\b[^\\r\\n]*\\balpha=1\\.0(?:\\s|$)").matcher(b.text).find();}
    private static boolean statusBar(Block b,Profile p){
        String[] owner=one(b.text,"^\\s*mOwnerUid=(\\d+)\\b[^\\r\\n]*\\bpackage=([^\\s]+)\\s+appop=([^\\s]+)[^\\r\\n]*$",3);
        String[] display=one(b.text,"^\\s*mDisplayId=(\\d+)\\s+rootTaskId=(\\d+)\\b.*$",2);
        return b.name.equals("StatusBar")&&owner!=null&&owner[0].equals(Integer.toString(p.systemUiUid))
            &&owner[1].equals("com.android.systemui")&&owner[2].equals("NONE")&&display!=null&&display[0].equals("0")
            &&Pattern.compile("(?m)^\\s*mAttrs=\\{[^\\r\\n]*\\bty=STATUS_BAR\\b").matcher(b.text).find();
    }
    static Result parse(String windows,String activities,Profile profile,long lower,long observed){
        return parseInternal(windows,activities,profile,null,lower,observed);
    }
    static Result parseRootReadOnly(String windows,String activities,Profile profile,ReadOnlyGuard guard,long lower,long observed){
        if(guard==null||!guard.valid())return Result.unknown();
        return parseInternal(windows,activities,profile,guard,lower,observed);
    }
    /** V46 only: actual Main/Template metadata; no task ID is supplied or inferred. */
    static Result parseEntrySource(String windows,String activities,Profile profile,ReadOnlyGuard guard,long lower,long observed){
        if(guard==null||!guard.valid())return Result.unknown();
        return parseInternal(windows,activities,profile,guard,lower,observed,null,true);
    }
    /** New selected profile only. A complete Template/Browser cross-state is a transition,
     * never Ready. No document is rewritten and the original Template comes from this Run. */
    static Result parseSelectedNavigation(String windows,String activities,Profile profile,ReadOnlyGuard guard,Ready original,long lower,long observed){
        Result ordinary=parseRootReadOnly(windows,activities,profile,guard,lower,observed);
        if(ordinary.status()==Status.READY||original==null)return ordinary;
        if(ordinary.firstGate()==FirstGate.INPUT||guard==null||!guard.valid()||original.kind()!=Kind.TEMPLATE
            ||!original.profile().equals(profile)||!original.task().equals(Integer.toString(RootSelectedPendingScope.SOURCE_TASK_ID)))return ordinary;
        if(windows==null||activities==null||windows.length()>262144||activities.length()>262144||windows.indexOf('\0')>=0||activities.indexOf('\0')>=0)return ordinary;
        String[] display=one(windows,"^\\s*mTopFocusedDisplayId=([0-9]+)\\s*$",1);if(display==null||!display[0].equals("0"))return ordinary;
        String[] focus=one(windows,"^\\s*mCurrentFocus=Window\\{([a-fA-F0-9]+) u0 ([^\\s}]+)\\}\\s*$",2);
        String[] app=one(windows,"^\\s*mFocusedApp=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^\\s}]+) t([0-9]+)(?:[^}]*)\\}\\s*$",3);
        String[] top=one(activities,"^\\s*topResumedActivity=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^\\s}]+) t([0-9]+)(?:[^}]*)\\}\\s*$",3);
        if(focus==null||app==null||top==null||count(activities,"topResumedActivity=")!=1||!namedComponent(focus[1])||!namedComponent(app[1])||!namedComponent(top[1])
            ||!original.task().equals(app[2])||!original.task().equals(top[2]))return ordinary;
        if(focus[1].equals(app[1])&&app[1].equals(top[1]))return ordinary;
        Result rejected=new Result(Status.UNKNOWN,null,ReadOnlyGate.FORMAT_UNKNOWN,FirstGate.SELECTED_CROSS_UNPROVEN);
        List<Block> wb=blocks(windows,WINDOW,false),ab=blocks(activities,HIST,true);
        if(wb.isEmpty()||ab.isEmpty()||headerCount(windows,"(?m)^\\s*Window #\\d+ Window\\{")!=wb.size()
            ||headerCount(activities,"(?m)^\\s*\\* Hist\\s+#\\d+: ActivityRecord\\{")!=ab.size())return rejected;
        List<Section> ws=sections(windows," *Window #\\d+ Window\\{.*"),as=sections(activities," *\\* Hist +#\\d+: ActivityRecord\\{.*");
        Block tw=null,bw=null,ta=null,ba=null;
        for(Block raw:wb){
            Section section=uniqueSection(ws,"Window #\\d+ Window\\{"+Pattern.quote(raw.token)+" u0 "+Pattern.quote(raw.name)+"\\}:");
            if(section==null)return rejected;
            Block bound=new Block(raw.token,raw.name,null,section.body);
            if(TEMPLATE.equals(bound.name)){if(tw!=null)return rejected;tw=bound;}
            else if(BROWSER.equals(bound.name)){if(bw!=null)return rejected;bw=bound;}
            else if(visible(bound)){if(!statusBar(bound,profile)&&!ownWindow(bound,guard))return rejected;}
            else if(!safelyInvisible(bound))return rejected;
        }
        for(Block raw:ab)if(namedComponent(raw.name)){
            Section section=uniqueSection(as,"\\* Hist +#\\d+: ActivityRecord\\{"+Pattern.quote(raw.token)+" u0 "+Pattern.quote(raw.name)+" t"+Pattern.quote(raw.task)+"(?:[^}]*)\\}");
            if(section==null||!original.task().equals(raw.task))return rejected;
            Block bound=new Block(raw.token,raw.name,raw.task,section.body);
            if(TEMPLATE.equals(bound.name)){if(ta!=null)return rejected;ta=bound;}else{if(ba!=null)return rejected;ba=bound;}
        }
        if(tw==null||bw==null||ta==null||ba==null||!original.focus.window().equals(tw.token)||!original.focus.activity().equals(ta.token))return rejected;
        if(!crossWindow(tw,ta,profile,original.task())||!crossWindow(bw,ba,profile,original.task())
            ||!crossActivity(ta)||!crossActivity(ba))return rejected;
        Block focused=focus[1].equals(TEMPLATE)?tw:bw,wmActivity=app[1].equals(TEMPLATE)?ta:ba,amActivity=top[1].equals(TEMPLATE)?ta:ba;
        if(!focus[0].equals(focused.token)||!app[0].equals(wmActivity.token)||!top[0].equals(amActivity.token))return rejected;
        if(!token(amActivity.text,"state","RESUMED"))return rejected;
        // These inspect the original, indentation-bounded Window/Activity/Task and departed own task.
        // UNKNOWN in any chain is not turned into transition just because another field differs.
        var tg=readonlyStability(windows,activities,profile,guard,new BingForegroundSnapshot.Snapshot(tw.token,ta.token,TEMPLATE,original.task()));
        var bg=readonlyStability(windows,activities,profile,guard,new BingForegroundSnapshot.Snapshot(bw.token,ba.token,BROWSER,original.task()));
        if(tg.status==Status.UNKNOWN||bg.status==Status.UNKNOWN)return rejected;
        return new Result(Status.BING_TRANSITION,null,ReadOnlyGate.TARGET_ACTIVITY,FirstGate.SELECTED_BOUND_CROSS_ACTIVITY);
    }
    private static boolean crossWindow(Block window,Block activity,Profile profile,String task){
        if(!appWindow(window,profile,task))return false;
        String[] link=one(window.text,"^ *mActivityRecord=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^ ]+) t(\\d+)(?:[^}]*)\\} *$",3);
        if(link==null||!link[0].equals(activity.token)||!link[1].equals(activity.name)||!link[2].equals(task))return false;
        for(String key:List.of("mHasSurface","isVisible","isOnScreen")){String v=value(window.text,key);if(v==null||!v.matches("true|false"))return false;}
        return visible(window)||safelyInvisible(window);
    }
    private static boolean crossActivity(Block activity){
        String state=value(activity.text,"state"),finishing=value(activity.text,"finishing"),history=value(activity.text,"inHistory");
        if(state==null||!state.matches("INITIALIZING|STARTED|RESUMED|PAUSING|PAUSED|STOPPING|STOPPED")||!"false".equals(finishing)||!"true".equals(history))return false;
        for(String key:List.of("mVisibleRequested","mVisible","mClientVisible","reportedDrawn","reportedVisible")){
            String v=value(activity.text,key);if(v==null||!v.matches("true|false"))return false;
        }
        return true;
    }
    private static FirstGate focusFailure(String windows){
        String[] w=one(windows,"^\\s*mCurrentFocus=Window\\{[a-fA-F0-9]+ u0 (com\\.microsoft\\.bing/[^\\s}]+)\\}\\s*$",1);
        String[] a=one(windows,"^\\s*mFocusedApp=ActivityRecord\\{[a-fA-F0-9]+ u0 (com\\.microsoft\\.bing/[^\\s}]+) t[0-9]+(?:[^}]*?)\\}\\s*$",1);
        return w!=null&&a!=null&&!w[0].equals(a[0])?FirstGate.FOCUS_COMPONENT_CONFLICT:FirstGate.FOCUS_FORMAT;
    }
    static Result parseTimedReturn(String windows,String activities,Profile profile,ReadOnlyGuard guard,Ready original,long lower,long observed){
        if(guard==null||!guard.valid()||original==null||original.kind()!=Kind.TEMPLATE||!original.profile().equals(profile)
            ||!original.task().equals(Integer.toString(RootSelectedPendingScope.SOURCE_TASK_ID)))return unknown(FirstGate.TIMED_RETURN_ANCHOR);
        return parseInternal(windows,activities,profile,guard,lower,observed,original);
    }
    private static Result parseInternal(String windows,String activities,Profile profile,ReadOnlyGuard guard,long lower,long observed){
        return parseInternal(windows,activities,profile,guard,lower,observed,null);
    }
    private static Result parseInternal(String windows,String activities,Profile profile,ReadOnlyGuard guard,long lower,long observed,Ready timedOriginal){
        return parseInternal(windows,activities,profile,guard,lower,observed,timedOriginal,false);
    }
    private static Result parseInternal(String windows,String activities,Profile profile,ReadOnlyGuard guard,long lower,long observed,Ready timedOriginal,boolean entry){
        if(windows==null||activities==null||windows.length()>262144||activities.length()>262144||windows.indexOf('\0')>=0||activities.indexOf('\0')>=0
            ||profile==null||profile.bingUid<10000||profile.systemUiUid<10000||profile.shape==null||profile.shape.width<=0||profile.shape.height<=0
            ||profile.shape.rotation<0||profile.shape.rotation>3||lower<0||observed<lower||observed-lower>5_000)return unknown(FirstGate.INPUT);
        var focus=BingForegroundSnapshot.parse(windows);if(focus==null)return unknown(focusFailure(windows));
        if(!(entry?entryComponent(focus.component()):namedComponent(focus.component())))return unknown(FirstGate.FOCUS_COMPONENT);
        List<Block> windowBlocks=blocks(windows,WINDOW,false);
        if(windowBlocks.isEmpty()||headerCount(windows,"(?m)^\\s*Window #\\d+ Window\\{")!=windowBlocks.size())return unknown(FirstGate.WINDOW_HEADERS);
        Block focused=null;boolean anotherBingVisible=false;
        for(Block b:windowBlocks){
            if(b.token.equals(focus.window())){if(focused!=null||!b.name.equals(focus.component()))return unknown(FirstGate.FOCUSED_WINDOW);focused=b;}
            else if(visible(b)){
                if(statusBar(b,profile))continue;
                if(appWindow(b,profile,focus.task(),entry)||(guard!=null&&ownWindow(b,guard)))anotherBingVisible=true;else return unknown(FirstGate.VISIBLE_UNKNOWN_WINDOW);
            }else if(!safelyInvisible(b))return unknown(FirstGate.HIDDEN_WINDOW_UNPROVEN);
        }
        if(focused==null||!appWindow(focused,profile,focus.task(),entry))return unknown(FirstGate.WINDOW_IDENTITY);
        String topToken,topComponent,topTask;
        if(timedOriginal!=null){
            // The existing exact focus/Window/Activity/Task/draw/lifecycle checks below still run.
            // Only global TOP cardinality is replaced by this private original-Template anchor.
            if(!timedOriginal.focus.equals(focus))return unknown(FirstGate.TIMED_RETURN_ANCHOR);
            topToken=focus.activity();topComponent=focus.component();topTask=focus.task();
        }else{
            if(count(activities,"topResumedActivity=")!=1)return unknown(FirstGate.TOP_RESUMED_COUNT);
            Matcher top=TOP.matcher(activities);if(!top.find())return unknown(FirstGate.TOP_RESUMED_FORMAT);
            topToken=top.group(1);topComponent=top.group(2);topTask=top.group(3);
            if(!(entry?entryComponent(topComponent):namedComponent(topComponent))||!focus.task().equals(topTask)||top.find())return unknown(FirstGate.TOP_RESUMED_TASK);
        }
        List<Block> activityBlocks=blocks(activities,HIST,true);
        if(headerCount(activities,"(?m)^\\s*\\* Hist\\s+#\\d+: ActivityRecord\\{")!=activityBlocks.size())return unknown(FirstGate.ACTIVITY_HEADERS);
        Block resumed=null;
        for(Block b:activityBlocks)if(b.token.equals(topToken)&&b.name.equals(topComponent)&&b.task.equals(topTask)){
            if(resumed!=null)return unknown(FirstGate.RESUMED_ACTIVITY);resumed=b;
        }
        if(resumed==null)return unknown(FirstGate.RESUMED_ACTIVITY);
        String[] task=one(resumed.text,"^\\s*rootOfTask=(?:true|false)\\s+task=Task\\{[a-fA-F0-9]+ #(\\d+) type=standard A=(\\d+):com\\.microsoft\\.bing\\}\\s*$",2);
        if(task==null||!task[0].equals(topTask)||!task[1].equals(Integer.toString(profile.bingUid)))return unknown(FirstGate.ACTIVITY_TASK);
        boolean activityReady=Pattern.compile("(?m)^\\s*state=RESUMED\\b[^\\r\\n]*\\bfinishing=false\\b").matcher(resumed.text).find()
            &&exactTrue(resumed.text,"mVisibleRequested")&&exactTrue(resumed.text,"mVisible")&&exactTrue(resumed.text,"mClientVisible")
            &&exactTrue(resumed.text,"reportedDrawn")&&exactTrue(resumed.text,"reportedVisible");
        if(anotherBingVisible||!drawnWindow(focused)||!activityReady||!topToken.equals(focus.activity())||!topComponent.equals(focus.component()))
            return new Result(Status.BING_TRANSITION,null,ReadOnlyGate.NOT_APPLIED,FirstGate.KNOWN_TRANSITION);
        if(guard!=null){GuardResult stable=readonlyStability(windows,activities,profile,guard,focus);
            if(stable.status!=Status.READY)return new Result(stable.status,null,stable.gate,FirstGate.READ_ONLY_STABILITY); }
        return new Result(Status.READY,new Ready(focus,focus.component().equals(TEMPLATE)?Kind.TEMPLATE:entry?Kind.MAIN:Kind.BROWSER,profile,lower,observed),
            guard==null?ReadOnlyGate.NOT_APPLIED:ReadOnlyGate.METADATA_STABLE_ONLY,FirstGate.READY);
    }
    static RecoveryResult parseReturnRead(String windows,String activities,Profile profile,ReadOnlyGuard guard,long lower,long observed){
        Result r=guard==null?parse(windows,activities,profile,lower,observed):parseRootReadOnly(windows,activities,profile,guard,lower,observed);
        if(r.status()!=Status.READY)return new RecoveryResult(r.status(),null,null,r.firstGate(),r.readOnlyGate());
        if(r.ready().kind()==Kind.TEMPLATE)return new RecoveryResult(Status.READY,r.ready(),null,FirstGate.RECOVERY_ALREADY_TEMPLATE);
        if(r.ready().kind()!=Kind.BROWSER)return new RecoveryResult(Status.UNKNOWN,null,null,FirstGate.RECOVERY_BROWSER_REQUIRED);
        Ready browser=r.ready();Block template=null;
        for(Block b:blocks(activities,HIST,true))if(TEMPLATE.equals(b.name)){
            if(template!=null||!b.task.equals(browser.task()))return new RecoveryResult(Status.UNKNOWN,null,null,FirstGate.RECOVERY_TEMPLATE_COUNT);
            template=b;
        }
        if(template==null)return new RecoveryResult(Status.UNKNOWN,null,null,FirstGate.RECOVERY_TEMPLATE_COUNT);
        String[] linked=one(template.text,"^\\s*rootOfTask=(?:true|false)\\s+task=Task\\{[a-fA-F0-9]+ #(\\d+) type=standard A=(\\d+):com\\.microsoft\\.bing\\}\\s*$",2);
        if(linked==null||!linked[0].equals(browser.task())||!linked[1].equals(Integer.toString(profile.bingUid)))
            return new RecoveryResult(Status.UNKNOWN,null,null,FirstGate.RECOVERY_TEMPLATE_TASK);
        if(!token(template.text,"state","STOPPED")||token(template.text,"finishing","true")||!token(template.text,"finishing","false")
            ||!exactTrue(template.text,"inHistory")||!token(template.text,"mVisibleRequested","false")||!token(template.text,"mVisible","false")
            ||!token(template.text,"mClientVisible","false")||!token(template.text,"reportedVisible","false")
            ||token(template.text,"mVisibleRequested","true")||token(template.text,"mVisible","true")||token(template.text,"mClientVisible","true")||token(template.text,"reportedVisible","true"))
            return new RecoveryResult(Status.UNKNOWN,null,null,FirstGate.RECOVERY_TEMPLATE_STATE);
        Block hidden=null;
        for(Block b:blocks(windows,WINDOW,false))if(TEMPLATE.equals(b.name)){
            if(hidden!=null)return new RecoveryResult(Status.UNKNOWN,null,null,FirstGate.RECOVERY_TEMPLATE_WINDOW);hidden=b;
        }
        if(hidden==null||!appWindow(hidden,profile,browser.task())||!safelyInvisible(hidden))
            return new RecoveryResult(Status.UNKNOWN,null,null,FirstGate.RECOVERY_TEMPLATE_WINDOW);
        String[] binding=one(hidden.text,"^\\s*mActivityRecord=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^\\s}]+) t([0-9]+)(?:[^}]*)\\}\\s*$",3);
        if(binding==null||!binding[0].equals(template.token)||!binding[1].equals(TEMPLATE)||!binding[2].equals(browser.task()))
            return new RecoveryResult(Status.UNKNOWN,null,null,FirstGate.RECOVERY_TEMPLATE_WINDOW_BINDING);
        var anchor=new BingForegroundSnapshot.Snapshot(hidden.token,template.token,TEMPLATE,browser.task());
        return new RecoveryResult(Status.READY,browser,new RecoverySource(browser,anchor),FirstGate.RECOVERY_BOUND);
    }
    /** Exact handoff owner, not an Intent-provided component or a pixel identity claim. */
    record ReadOnlyGuard(int ownUid,int ownTaskId,String ownComponent) {
        boolean valid(){return ownUid>=10000&&ownTaskId>0&&OWN_COMPONENT.equals(ownComponent);}
    }
    private static final String OWN_PACKAGE="com.aliothmoon.maafw.productrewardsworking.capturereview";
    private static final String OWN_COMPONENT=OWN_PACKAGE+"/com.aliothmoon.maafw.once.DormantCaptureConfirmationActivity";
    private static boolean ownWindow(Block b,ReadOnlyGuard g){
        String[] display=one(b.text,"^\\s*mDisplayId=(\\d+)\\s+rootTaskId=(\\d+)\\b.*$",2);
        String[] owner=one(b.text,"^\\s*mOwnerUid=(\\d+)\\b[^\\r\\n]*\\bpackage=([^\\s]+)\\s+appop=([^\\s]+)[^\\r\\n]*$",3);
        return b.name.equals(g.ownComponent)&&display!=null&&display[0].equals("0")&&display[1].equals(Integer.toString(g.ownTaskId))
            &&owner!=null&&owner[0].equals(Integer.toString(g.ownUid))&&owner[1].equals(OWN_PACKAGE)&&owner[2].equals("NONE");
    }
    private record Section(String header,String body,int indent) {}
    // Indentation-bounded sections prevent later Task/Activity/summary fields bleeding into a window.
    private static List<Section> sections(String document,String headerPattern){
        String[] lines=document.split("\\r?\\n",-1);List<Section> result=new ArrayList<>();Pattern pattern=Pattern.compile(headerPattern);
        for(int i=0;i<lines.length;i++)if(pattern.matcher(lines[i]).matches()){
            int indent=indent(lines[i]);if(indent<0||result.size()>=128)return List.of();
            StringBuilder body=new StringBuilder();
            for(int j=i+1;j<lines.length;j++){
                if(!lines[j].isBlank()&&indent(lines[j])<=indent)break;
                body.append(lines[j]).append('\n');
            }
            result.add(new Section(lines[i].strip(),body.toString(),indent));
        }
        return result;
    }
    private static int indent(String line){int n=0;while(n<line.length()&&line.charAt(n)==' ')n++;return n<line.length()&&line.charAt(n)=='\t'?-1:n;}
    private static Section uniqueSection(List<Section> sections,String header){
        Section found=null;for(Section s:sections)if(s.header.matches(header)){if(found!=null)return null;found=s;}return found;
    }
    private static String value(String text,String name){
        String[] v=one(text,"(?<![A-Za-z0-9_])"+Pattern.quote(name)+"=([^\\s]+)",1);return v==null?null:v[0];
    }
    private static Status combine(Status a,Status b){return a==Status.UNKNOWN||b==Status.UNKNOWN?Status.UNKNOWN
        :a==Status.BING_TRANSITION||b==Status.BING_TRANSITION?Status.BING_TRANSITION:Status.READY;}
    private static Status animation(String text){
        // In a bound app/Activity/Task only. SystemUI and hidden IME control leashes are never scanned globally.
        List<Section> animators=sections(text," *ContainerAnimator: *");
        if(headerCount(text,"(?m)^ *ContainerAnimator:")!=animators.size())return Status.UNKNOWN;
        for(Section a:animators){
            String type=value(a.body,"mAnimationType");
            if(type==null||!Pattern.compile("(?m)^ *Animation: .+\\S *$").matcher(a.body).find())return Status.UNKNOWN;
            // Any active animator on the exact application chain is conservatively a transition.
            return Status.BING_TRANSITION;
        }
        if(token(text,"mAnimationStartDelayed","true")||token(text,"mAnimatingExit","true")
            ||token(text,"mIsExiting","true")||token(text,"mWaitingForTransitionStart","true")
            ||token(text,"isAnimating","true")||token(text,"mAnimating","true"))return Status.BING_TRANSITION;
        return Status.READY;
    }
    private static Status windowDraw(Section window,String component,boolean hidden){
        Section animator=uniqueSection(sections(window.body," *WindowStateAnimator\\{[a-fA-F0-9]+ .+\\}: *"),
            "WindowStateAnimator\\{[a-fA-F0-9]+ "+Pattern.quote(component)+"\\}:");
        if(animator==null)return Status.UNKNOWN;
        String draw=value(animator.body,"mDrawState"),last=value(animator.body,"mLastHidden"),enter=value(animator.body,"mEnterAnimationPending");
        if(draw==null||last==null||enter==null||!last.matches("true|false")||!enter.matches("true|false"))return Status.UNKNOWN;
        if(!draw.matches("NO_SURFACE|DRAW_PENDING|COMMIT_DRAW_PENDING|READY_TO_SHOW|HAS_DRAWN"))return Status.UNKNOWN;
        Status status=animation(window.body);
        if(enter.equals("true")||(!hidden&&(!draw.equals("HAS_DRAWN")||last.equals("true"))))status=combine(status,Status.BING_TRANSITION);
        // NO_SURFACE/mLastHidden=true is expected for the departed own window, not a perpetual transition.
        for(String name:List.of("mShownAlpha","mAlpha","mLastAlpha")){
            if(count(animator.body,name+"=")==0)continue; // AOSP dump omits the entire line when all values are 1.
            String alpha=value(animator.body,name);if(alpha==null)return Status.UNKNOWN;
            try{double n=Double.parseDouble(alpha);if(!Double.isFinite(n))return Status.UNKNOWN;
                if(!hidden&&n!=1.0)status=combine(status,Status.BING_TRANSITION);
            }catch(NumberFormatException invalid){return Status.UNKNOWN;}
        }
        if(token(window.body,"mForceSeamlesslyRotate","true")||Pattern.compile("seamlesslyRotate: pending=(?!null\\b)\\S+").matcher(window.body).find())
            status=combine(status,Status.BING_TRANSITION);
        return status;
    }
    private static Status activityState(Section activity,String token,String component,String task,int uid,boolean hidden){
        String pkg=component.substring(0,component.indexOf('/'));
        String[] linked=one(activity.body,"^ *rootOfTask=(?:true|false) task=Task\\{([a-fA-F0-9]+) #(\\d+) type=standard A=(\\d+):([^}]+)\\} *$",4);
        if(linked==null||!linked[1].equals(task)||!linked[2].equals(Integer.toString(uid))||!linked[3].equals(pkg))return Status.UNKNOWN;
        Status status=animation(activity.body);
        if(!hidden&&(token(activity.body,"firstWindowDrawn","false")||token(activity.body,"allDrawn","false")
            ||Pattern.compile("(?<![A-Za-z0-9_])startingData=(?!null\\b)\\S+").matcher(activity.body).find()))status=combine(status,Status.BING_TRANSITION);
        if(hidden){
            for(String key:List.of("mVisibleRequested","mVisible","mClientVisible","reportedVisible")){
                String v=value(activity.body,key);if(v==null||!v.matches("true|false"))return Status.UNKNOWN;
                if(v.equals("true"))status=combine(status,Status.BING_TRANSITION);
            }
            String state=value(activity.body,"state");if(state==null)return Status.UNKNOWN;
            if(!state.equals("STOPPED"))status=combine(status,Status.BING_TRANSITION);
        }
        return status;
    }
    private static Status taskState(String activities,String task,int uid,String pkg,String activityToken,String component,boolean hidden){
        Section display=uniqueSection(sections(activities,"Display #\\d+ \\(activities from top to bottom\\):"),
            "Display #0 \\(activities from top to bottom\\):");
        if(display==null)return Status.UNKNOWN;
        List<Section> tasks=sections(display.body," *\\* Task\\{.*");Section selected=null;
        String header="\\* Task\\{[a-fA-F0-9]+ #"+Pattern.quote(task)+" type=standard A="+uid+":"+Pattern.quote(pkg)+" U=0 .*\\}";
        for(Section s:tasks)if(s.indent==2&&s.header.matches(header)
            &&Pattern.compile("(?m)^ *\\* Hist +#\\d+: ActivityRecord\\{"+Pattern.quote(activityToken)+" u0 "+Pattern.quote(component)+" t"+Pattern.quote(task)+"(?:[^}]*)\\} *$").matcher(s.body).find()){
            if(selected!=null)return Status.UNKNOWN;selected=s;
        }
        if(selected==null)return Status.UNKNOWN;
        String[] taskToken=one(selected.header,"^\\* Task\\{([a-fA-F0-9]+) #",1);
        Section linkedActivity=uniqueSection(sections(selected.body," *\\* Hist +#\\d+: ActivityRecord\\{.*"),
            "\\* Hist +#\\d+: ActivityRecord\\{"+Pattern.quote(activityToken)+" u0 "+Pattern.quote(component)+" t"+Pattern.quote(task)+"(?:[^}]*)\\}");
        if(taskToken==null||linkedActivity==null||!Pattern.compile("(?m)^ *rootOfTask=(?:true|false) task=Task\\{"
            +Pattern.quote(taskToken[0])+" #"+Pattern.quote(task)+" type=standard A="+uid+":"+Pattern.quote(pkg)+"\\} *$").matcher(linkedActivity.body).find())return Status.UNKNOWN;
        String visible=value(selected.header,"visible"),requested=value(selected.header,"visibleRequested"),mode=value(selected.header,"mode");
        if(visible==null||requested==null||!visible.matches("true|false")||!requested.matches("true|false")||!"fullscreen".equals(mode))return Status.UNKNOWN;
        // Check only this task's own prefix; its exact Activity is checked separately, not unrelated siblings.
        String prefix=selected.body;Matcher child=Pattern.compile("(?m)^ *\\* (?:Hist|Task)\\b").matcher(prefix);
        if(child.find())prefix=prefix.substring(0,child.start());
        Status status=animation(prefix);
        if(!visible.equals(Boolean.toString(!hidden))||!requested.equals(Boolean.toString(!hidden)))status=combine(status,Status.BING_TRANSITION);
        return status;
    }
    private record GuardResult(Status status,ReadOnlyGate gate) {}
    private static GuardResult readonlyStability(String windows,String activities,Profile p,ReadOnlyGuard guard,BingForegroundSnapshot.Snapshot focus){
        List<Section> ws=sections(windows," *Window #\\d+ Window\\{.*");
        Section target=uniqueSection(ws,"Window #\\d+ Window\\{"+Pattern.quote(focus.window())+" u0 "+Pattern.quote(focus.component())+"\\}:");
        Section own=uniqueSection(ws,"Window #\\d+ Window\\{[a-fA-F0-9]+ u0 "+Pattern.quote(guard.ownComponent)+"\\}:");
        if(target==null||own==null)return new GuardResult(Status.UNKNOWN,ReadOnlyGate.FORMAT_UNKNOWN);
        Block ownBlock=new Block("",guard.ownComponent,Integer.toString(guard.ownTaskId),own.body);
        if(!ownWindow(ownBlock,guard))return new GuardResult(Status.UNKNOWN,ReadOnlyGate.FORMAT_UNKNOWN);
        String[] bind=one(target.body,"^ *mActivityRecord=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^ ]+) t(\\d+)(?:[^}]*)\\} *$",3);
        String[] ownBind=one(own.body,"^ *mActivityRecord=ActivityRecord\\{([a-fA-F0-9]+) u0 ([^ ]+) t(\\d+)(?:[^}]*)\\} *$",3);
        if(bind==null||!bind[0].equals(focus.activity())||!bind[1].equals(focus.component())||!bind[2].equals(focus.task())
            ||ownBind==null||!ownBind[1].equals(guard.ownComponent)||!ownBind[2].equals(Integer.toString(guard.ownTaskId)))return new GuardResult(Status.UNKNOWN,ReadOnlyGate.FORMAT_UNKNOWN);
        List<Section> as=sections(activities," *\\* Hist +#\\d+: ActivityRecord\\{.*");
        Section active=uniqueSection(as,"\\* Hist +#\\d+: ActivityRecord\\{"+Pattern.quote(bind[0])+" u0 "+Pattern.quote(bind[1])+" t"+bind[2]+"(?:[^}]*)\\}");
        Section departed=uniqueSection(as,"\\* Hist +#\\d+: ActivityRecord\\{"+Pattern.quote(ownBind[0])+" u0 "+Pattern.quote(ownBind[1])+" t"+ownBind[2]+"(?:[^}]*)\\}");
        if(active==null||departed==null)return new GuardResult(Status.UNKNOWN,ReadOnlyGate.FORMAT_UNKNOWN);
        Status targetDraw=windowDraw(target,focus.component(),false),ownDraw=windowDraw(own,guard.ownComponent,true);
        if(!safelyInvisible(ownBlock))ownDraw=combine(ownDraw,Status.BING_TRANSITION);
        Status[] checks={targetDraw,ownDraw,
            activityState(active,bind[0],bind[1],bind[2],p.bingUid,false),
            activityState(departed,ownBind[0],ownBind[1],ownBind[2],guard.ownUid,true),
            taskState(activities,bind[2],p.bingUid,"com.microsoft.bing",bind[0],bind[1],false),
            taskState(activities,ownBind[2],guard.ownUid,OWN_PACKAGE,ownBind[0],ownBind[1],true)};
        ReadOnlyGate[] gates={ReadOnlyGate.TARGET_DRAW,ReadOnlyGate.OWN_WINDOW,ReadOnlyGate.TARGET_ACTIVITY,
            ReadOnlyGate.OWN_ACTIVITY,ReadOnlyGate.TARGET_TASK,ReadOnlyGate.OWN_TASK};
        // Unknown format always stops polling, even if a different bound section is in transition.
        for(int i=0;i<checks.length;i++)if(checks[i]==Status.UNKNOWN)return new GuardResult(Status.UNKNOWN,gates[i]);
        for(int i=0;i<checks.length;i++)if(checks[i]!=Status.READY)return new GuardResult(checks[i],gates[i]);
        // READY here means bounded metadata has no observed transition; it is not compositor/pixel identity.
        return new GuardResult(Status.READY,ReadOnlyGate.METADATA_STABLE_ONLY);
    }
    private BingNavigationSnapshot(){}
}




