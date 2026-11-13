package com.aliothmoon.maafw.once;

import java.util.*;
import java.util.regex.*;

/** Fixed observed OEM metadata grammar, diagnostic only. No pixel-readiness or authority.
 * Caller owns fixed-command transport/cleanup and capture-time proximity. Input strings
 * and tokens are transient and are never retained in the safe result. */
final class RootSeriesMetadata {
    enum Check { UNOBSERVED, YES, NO }
    enum Association { UNOBSERVED, MATCHED, MISMATCH, AMBIGUOUS }
    enum Visibility { UNOBSERVED, VISIBLE, INVISIBLE, GONE }
    record Client(Association association,int pid,Check resumed,Check stopped,Check finished,
                  Check viewRoot,Check inputWindow,Check added,Check removed,Check rootStopped,
                  Check pausedTransition,Check traversalScheduled,Check reportNextDraw,
                  Check focus,Visibility windowVisibility){
        static Client unknown(Association a){return new Client(a,-1,Check.UNOBSERVED,Check.UNOBSERVED,Check.UNOBSERVED,
            Check.UNOBSERVED,Check.UNOBSERVED,Check.UNOBSERVED,Check.UNOBSERVED,Check.UNOBSERVED,Check.UNOBSERVED,
            Check.UNOBSERVED,Check.UNOBSERVED,Check.UNOBSERVED,Visibility.UNOBSERVED);}
    }
    /** Depth order is buffer, WM window, ActivityRecord, Task; no raw names or layer IDs. */
    record LayerFacts(long flags,Check isProtected,long layerStack) { }
    record Surface(Association association,int targets,Check uid,Check window,Check activity,Check task,
                   List<LayerFacts> chain,int bufferWidth,int bufferHeight,Check dimensions,
                   Check bufferOwnerUid,Check bufferOwnerPid){
        Surface{chain=List.copyOf(chain);}
        static Surface unknown(Association a,int count){return new Surface(a,count,Check.UNOBSERVED,Check.UNOBSERVED,
            Check.UNOBSERVED,Check.UNOBSERVED,List.of(),-1,-1,Check.UNOBSERVED,Check.UNOBSERVED,Check.UNOBSERVED);}
    }
    record Summary(Client client,Surface surface){
        String summary(){
            StringBuilder s=new StringBuilder("RootSeriesMetadata diagnosticOnly=true pixelReady=UNPROVEN client={assoc=")
                .append(client.association()).append(" pid=").append(client.pid()).append(" resumed=").append(client.resumed())
                .append(" stopped=").append(client.stopped()).append(" finished=").append(client.finished())
                .append(" viewRoot=").append(client.viewRoot()).append(" inputWindow=").append(client.inputWindow())
                .append(" added=").append(client.added()).append(" removed=").append(client.removed())
                .append(" rootStopped=").append(client.rootStopped()).append(" pausedTransition=").append(client.pausedTransition())
                .append(" traversal=").append(client.traversalScheduled()).append(" reportDraw=").append(client.reportNextDraw())
                .append(" focus=").append(client.focus()).append(" visibility=").append(client.windowVisibility())
                .append("} sf={assoc=").append(surface.association()).append(" parsedTargets=").append(surface.targets())
                .append(" uid=").append(surface.uid()).append(" window=").append(surface.window()).append(" activity=").append(surface.activity())
                .append(" task=").append(surface.task()).append(" chainOrder=BUFFER_WINDOW_ACTIVITY_TASK chain=[");
            for(int i=0;i<surface.chain().size();i++){
                if(i>0)s.append(';');var f=surface.chain().get(i);
                s.append("flags:").append(f.flags()).append("/protected:").append(f.isProtected()).append("/stack:").append(f.layerStack());
            }
            return s.append("] buffer=").append(surface.bufferWidth()).append('x').append(surface.bufferHeight())
                .append(" dims=").append(surface.dimensions()).append(" bufferUid=").append(surface.bufferOwnerUid())
                .append(" bufferPid=").append(surface.bufferOwnerPid())
                .append(" secure=UNOBSERVED hasProtectedContent=UNOBSERVED frameId=UNOBSERVED generation=UNOBSERVED}").toString();
        }
    }
    private record Block(String header,String body,int start,int end) { }
    private record Layer(String name,int uid,String body) { }
    private static final String COMPONENT=BingNavigationSnapshot.TEMPLATE;
    private static final Pattern ACTIVITY=Pattern.compile("(?m)^  ACTIVITY ([^\\s]+) ([a-fA-F0-9]+) pid=(\\d+) userId=(\\d+) uid=(\\d+) displayId=(\\d+)\\(type=INTERNAL\\)\\s*$");
    private static final Pattern TASK=Pattern.compile("(?m)^TASK (\\d+):([^\\s]+) id=(\\d+) userId=(\\d+) displayId=(\\d+)\\(type=INTERNAL\\)\\s*$");
    private static final Pattern LAYER=Pattern.compile("(?m)^\\+ Layer \\(([^\\r\\n]+)\\) uid=(\\d+)\\s*$");
    static Summary parse(String client,String sf,BingNavigationSnapshot.Ready source){
        if(source==null||source.kind()!=BingNavigationSnapshot.Kind.TEMPLATE)
            return new Summary(Client.unknown(Association.UNOBSERVED),Surface.unknown(Association.UNOBSERVED,-1));
        Client c=parseClient(client,source);
        return new Summary(c,parseSurface(sf,source,c));
    }
    private static boolean document(String value){return value!=null&&value.length()<=262144&&value.indexOf('\0')<0;}
    private static int count(String text,String regex){int n=0;Matcher m=Pattern.compile(regex,Pattern.MULTILINE).matcher(text);while(m.find())n++;return n;}
    private static String[] unique(String text,String regex,int groups){
        Matcher m=Pattern.compile(regex,Pattern.MULTILINE).matcher(text);if(!m.find())return null;
        String[] values=new String[groups];for(int i=0;i<groups;i++)values[i]=m.group(i+1);return m.find()?null:values;
    }
    private static int number(String value){try{long n=Long.parseLong(value);return n>=0&&n<=Integer.MAX_VALUE?(int)n:-1;}catch(RuntimeException invalid){return -1;}}
    private static Check bool(String text,String key){
        String[] v=unique(text,"(?<![A-Za-z0-9_])"+Pattern.quote(key)+"=(true|false)(?![A-Za-z0-9_])",1);
        if(v==null||count(text,"(?<![A-Za-z0-9_])"+Pattern.quote(key)+"=")!=1)return Check.UNOBSERVED;
        return v[0].equals("true")?Check.YES:Check.NO;
    }
    private static int indent(String line){int n=0;while(n<line.length()&&line.charAt(n)==' ')n++;return n<line.length()&&line.charAt(n)=='\t'?-1:n;}
    private static Block section(String text,String exactHeader){
        String[] lines=text.split("\\r?\\n",-1);Block found=null;int offset=0;
        for(int i=0;i<lines.length;i++){
            if(lines[i].strip().equals(exactHeader)){
                if(found!=null)return null;int depth=indent(lines[i]);if(depth<0)return null;
                StringBuilder b=new StringBuilder();int end=offset+lines[i].length();
                for(int j=i+1;j<lines.length;j++){
                    if(!lines[j].isBlank()&&indent(lines[j])<=depth)break;
                    b.append(lines[j]).append('\n');end+=lines[j].length()+1;
                }
                found=new Block(lines[i],b.toString(),offset,end);
            }
            offset+=lines[i].length()+1;
        }
        return found;
    }
    private static Client parseClient(String text,BingNavigationSnapshot.Ready source){
        if(!document(text))return Client.unknown(Association.UNOBSERVED);
        text=text.replace("\r\n","\n");
        Matcher m=ACTIVITY.matcher(text);List<String[]> candidates=new ArrayList<>();List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();
        while(m.find()){
            if(candidates.size()>=64)return Client.unknown(Association.UNOBSERVED);
            candidates.add(new String[]{m.group(1),m.group(2),m.group(3),m.group(4),m.group(5),m.group(6)});starts.add(m.start());ends.add(m.end());
        }
        if(count(text,"^ +ACTIVITY\\s")!=candidates.size())return Client.unknown(Association.UNOBSERVED);
        int selected=-1;boolean mismatched=false;
        for(int i=0;i<candidates.size();i++){
            var v=candidates.get(i);if(!COMPONENT.equals(v[0]))continue;
            if(source.diagnosticActivityMatches(v[1],v[0],source.task())){if(selected>=0)return Client.unknown(Association.AMBIGUOUS);selected=i;}
            else mismatched=true;
        }
        if(selected<0)return Client.unknown(mismatched?Association.MISMATCH:Association.UNOBSERVED);
        var v=candidates.get(selected);Matcher task=TASK.matcher(text);String[] parent=null;int taskEnd=-1,taskStart=-1;
        while(task.find()&&task.start()<starts.get(selected)){parent=new String[]{task.group(1),task.group(2),task.group(3),task.group(4),task.group(5)};taskEnd=task.end();taskStart=task.start();}
        int rawTaskStart=-1;Matcher rawTask=Pattern.compile("(?m)^TASK\\b[^\\r\\n]*$").matcher(text);
        while(rawTask.find()&&rawTask.start()<starts.get(selected))rawTaskStart=rawTask.start();
        if(rawTaskStart!=taskStart)return Client.unknown(Association.UNOBSERVED);
        if(parent==null||!parent[0].equals(Integer.toString(source.profile().bingUid()))||!parent[1].equals("com.microsoft.bing")
                ||!parent[2].equals(source.task())||!parent[3].equals("0")||!parent[4].equals("0")||taskEnd>starts.get(selected)
                ||!v[3].equals("0")||!v[4].equals(Integer.toString(source.profile().bingUid()))||!v[5].equals("0"))
            return Client.unknown(Association.MISMATCH);
        int end=selected+1<starts.size()?starts.get(selected+1):text.length();
        Matcher nextTask=Pattern.compile("(?m)^TASK\\s").matcher(text);if(nextTask.find(ends.get(selected)))end=Math.min(end,nextTask.start());
        String body=text.substring(ends.get(selected),end);Block root=section(body,"ViewRoot:");
        if(root==null)return Client.unknown(Association.UNOBSERVED);
        String state=body.substring(0,Math.min(body.length(),root.start()));
        String[] channel=unique(root.body(),"^\\s*mInputChannel: ([a-fA-F0-9]+) "+Pattern.quote(COMPONENT)+" \\(client\\)\\s*$",1);
        Check input=channel==null||count(root.body(),"^\\s*mInputChannel:")!=1?Check.UNOBSERVED
            :source.diagnosticWindowMatches(channel[0])?Check.YES:Check.NO;
        if(input!=Check.YES)return Client.unknown(input==Check.NO?Association.MISMATCH:Association.UNOBSERVED);
        String[] visibility=unique(root.body(),"(?<![A-Za-z0-9_])mWindowVisibility=(0|4|8)(?![A-Za-z0-9_])",1);
        Visibility visible=Visibility.UNOBSERVED;
        if(visibility!=null&&count(root.body(),"(?<![A-Za-z0-9_])mWindowVisibility=")==1)
            visible=switch(visibility[0]){case "0"->Visibility.VISIBLE;case "4"->Visibility.INVISIBLE;default->Visibility.GONE;};
        int pid=number(v[2]);if(pid<=0)pid=-1;
        return new Client(Association.MATCHED,pid,bool(state,"mResumed"),bool(state,"mStopped"),bool(state,"mFinished"),Check.YES,input,
            bool(root.body(),"mAdded"),bool(root.body(),"mRemoved"),bool(root.body(),"mStopped"),bool(root.body(),"mPausedForTransition"),
            bool(root.body(),"mTraversalScheduled"),bool(root.body(),"mReportNextDraw"),bool(root.body(),"mHasWindowFocus"),visible);
    }
    private static List<Layer> layers(String text){
        Matcher m=LAYER.matcher(text);List<String[]> ids=new ArrayList<>();List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();
        while(m.find()){
            if(ids.size()>=512)return List.of();ids.add(new String[]{m.group(1),m.group(2)});starts.add(m.start());ends.add(m.end());
        }
        if(count(text,"^\\+ Layer\\b")!=ids.size())return List.of();
        List<Layer> out=new ArrayList<>();
        for(int i=0;i<ids.size();i++){
            int end=i+1<starts.size()?starts.get(i+1):text.length();
            // Other top-level SF sections (including * Layer/OutputLayer) cannot bleed in.
            Matcher other=Pattern.compile("(?m)^\\S[^\\r\\n]*$").matcher(text);if(other.find(ends.get(i)))end=Math.min(end,other.start());
            var id=ids.get(i);out.add(new Layer(id[0],number(id[1]),text.substring(ends.get(i),end)));
        }
        return out;
    }
    private static Layer oneLayer(List<Layer> layers,String name){Layer found=null;for(Layer l:layers)if(l.name().equals(name)){if(found!=null)return null;found=l;}return found;}
    private static String parent(Layer layer){String[] p=unique(layer.body(),"^\\s+parent=([^\\r\\n]+)\\s*$",1);return p==null?null:p[0].strip();}
    private static long unsigned(String value,int radix){try{long n=Long.parseLong(value,radix);return n>=0&&n<=0xffffffffL?n:-1;}catch(RuntimeException e){return -1;}}
    private static LayerFacts facts(Layer layer){
        String[] line=unique(layer.body(),"^\\s+(layerStack=[^\\r\\n]+)$",1);if(line==null)return new LayerFacts(-1,Check.UNOBSERVED,-1);
        String[] stack=unique(line[0],"^layerStack=\\s*(\\d+),",1),flags=unique(line[0],"\\bflags=0x([a-fA-F0-9]{1,8})(?:,|\\s|$)",1),protect=unique(line[0],"\\bisProtected=([01])(?:,|\\s|$)",1);
        return new LayerFacts(flags==null||count(line[0],"\\bflags=")!=1?-1:unsigned(flags[0],16),
            protect==null||count(line[0],"\\bisProtected=")!=1?Check.UNOBSERVED:protect[0].equals("1")?Check.YES:Check.NO,
            stack==null||count(line[0],"\\blayerStack=")!=1?-1:unsigned(stack[0],10));
    }
    private static Surface parseSurface(String text,BingNavigationSnapshot.Ready source,Client client){
        if(!document(text))return Surface.unknown(Association.UNOBSERVED,-1);
        text=text.replace("\r\n","\n");
        List<Layer> all=layers(text);List<Layer> targets=new ArrayList<>();
        for(Layer l:all)if(l.name().matches(Pattern.quote(COMPONENT)+"#[0-9]+"))targets.add(l);
        if(targets.size()!=1)return Surface.unknown(targets.size()>1?Association.AMBIGUOUS:Association.UNOBSERVED,targets.size());
        Layer buffer=targets.get(0);if(buffer.uid()<0)return Surface.unknown(Association.UNOBSERVED,1);
        if(buffer.uid()!=source.profile().bingUid())return Surface.unknown(Association.MISMATCH,1);
        Layer window=oneLayer(all,parent(buffer));if(window==null)return Surface.unknown(Association.UNOBSERVED,1);
        String[] w=unique(window.name(),"^([a-fA-F0-9]+) "+Pattern.quote(COMPONENT)+"#[0-9]+$",1);
        if(w==null)return Surface.unknown(Association.UNOBSERVED,1);
        if(!source.diagnosticWindowMatches(w[0]))return Surface.unknown(Association.MISMATCH,1);
        Layer activity=oneLayer(all,parent(window));if(activity==null)return Surface.unknown(Association.UNOBSERVED,1);
        String[] a=unique(activity.name(),"^ActivityRecord\\{([a-fA-F0-9]+) u0 "+Pattern.quote(COMPONENT)+"#[0-9]+$",1);
        if(a==null)return Surface.unknown(Association.UNOBSERVED,1);
        if(!source.diagnosticActivityMatches(a[0],COMPONENT,source.task()))return Surface.unknown(Association.MISMATCH,1);
        Layer task=oneLayer(all,parent(activity));if(task==null)return Surface.unknown(Association.UNOBSERVED,1);
        if(!task.name().matches("Task="+Pattern.quote(source.task())+"#[0-9]+"))return Surface.unknown(Association.MISMATCH,1);
        String[] active=unique(buffer.body(),"^\\s+(activeBuffer=\\[([^\\r\\n]+))$",2);int width=-1,height=-1;Check dims=Check.UNOBSERVED,uid=Check.UNOBSERVED,pid=Check.UNOBSERVED;
        if(active!=null){
            String[] shape=unique(active[0],"^activeBuffer=\\[(\\d+)x(\\d+):(\\d+),[A-Za-z0-9_]+\\]",3);
            if(shape!=null){width=number(shape[0]);height=number(shape[1]);if(width>0&&height>0)
                dims=width==source.profile().shape().width()&&height==source.profile().shape().height()?Check.YES:Check.NO;}
            String[] owner=unique(active[0],"\\bownerUID:(\\d+)(?:,|}|\\s|$)",1),process=unique(active[0],"\\bownerPID:(\\d+)(?:,|}|\\s|$)",1);
            if(owner!=null&&number(owner[0])>=0&&count(active[0],"\\bownerUID:")==1)uid=number(owner[0])==source.profile().bingUid()?Check.YES:Check.NO;
            if(process!=null&&number(process[0])>=0&&count(active[0],"\\bownerPID:")==1&&client.association()==Association.MATCHED&&client.pid()>0)pid=number(process[0])==client.pid()?Check.YES:Check.NO;
        }
        return new Surface(Association.MATCHED,1,Check.YES,Check.YES,Check.YES,Check.YES,
            List.of(facts(buffer),facts(window),facts(activity),facts(task)),width,height,dims,uid,pid);
    }
    private RootSeriesMetadata(){ }
}