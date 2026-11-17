package com.aliothmoon.maafw.once;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Passive syntax classification of one original WM document. Never grants a source capability. */
final class BingFocusDiagnostic {
    static final int MAX_DOCUMENT_CHARS=262144,MAX_HISTORY=64;
    enum DocumentState { NOT_OBSERVED, OBSERVED, TOO_LARGE, NUL_PRESENT }
    enum FieldState { MISSING, DUPLICATE, NULL_VALUE, WRONG_DISPLAY, WRONG_USER, NON_BING, UNRECOGNIZED, PRESENT }
    enum Truth { TRUE, FALSE, NOT_OBSERVED }
    enum Role { TEMPLATE, BROWSER, MAIN, OTHER_BING, NON_BING, UNRECOGNIZED }
    private static final Pattern DISPLAY=Pattern.compile("(?m)^\\s*mTopFocusedDisplayId=(\\d+)\\s*$");
    private static final Pattern STRICT_WINDOW=Pattern.compile("(?m)^\\s*mCurrentFocus=Window\\{([a-fA-F0-9]+) u0 (com\\.microsoft\\.bing/[^\\s}]+)\\}\\s*$");
    private static final Pattern STRICT_APP=Pattern.compile("(?m)^\\s*mFocusedApp=ActivityRecord\\{([a-fA-F0-9]+) u0 (com\\.microsoft\\.bing/[^\\s}]+) t([0-9]+)(?:[^}]*?)\\}\\s*$");
    private static final Pattern WINDOW=Pattern.compile("(?m)^[ \\t]*mCurrentFocus=Window\\{([a-fA-F0-9]+) u([0-9]+) ([^\\s}]+)\\}[ \\t]*$");
    private static final Pattern APP=Pattern.compile("(?m)^[ \\t]*mFocusedApp=ActivityRecord\\{([a-fA-F0-9]+) u([0-9]+) ([^\\s}]+) t([0-9]+)(?:[^}\\r\\n]*?)\\}[ \\t]*$");
    private static final String BING="com.microsoft.bing/";
    private final Map<String,Object> values;
    // Opaque in-process equality anchor only; no identity value enters scalars/json/history samples.
    private final BingForegroundSnapshot.Snapshot strictFocus;
    private BingFocusDiagnostic(Map<String,Object> values,BingForegroundSnapshot.Snapshot focus){
        this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values));this.strictFocus=focus;
    }
    static BingFocusDiagnostic inspect(String original){
        DocumentState document=original==null?DocumentState.NOT_OBSERVED:original.length()>MAX_DOCUMENT_CHARS?
                DocumentState.TOO_LARGE:original.indexOf('\0')>=0?DocumentState.NUL_PRESENT:DocumentState.OBSERVED;
        String text=document==DocumentState.OBSERVED?original:"";
        var display=display(text);var window=field(text,"mCurrentFocus=",WINDOW,STRICT_WINDOW);var app=field(text,"mFocusedApp=",APP,STRICT_APP);
        var strict=document==DocumentState.OBSERVED?BingForegroundSnapshot.parse(original):null;
        Truth relation=window.component!=null&&app.component!=null?
                (window.component.equals(app.component)?Truth.TRUE:Truth.FALSE):Truth.NOT_OBSERVED;
        String failure=strict!=null?"ACCEPTED":document!=DocumentState.OBSERVED?"DOCUMENT_"+document.name():
                display.state!=FieldState.PRESENT?"DISPLAY_"+display.state.name():window.state!=FieldState.PRESENT?
                "WINDOW_"+window.state.name():app.state!=FieldState.PRESENT?"APP_"+app.state.name():
                relation==Truth.FALSE?"COMPONENT_MISMATCH":"STRICT_REJECTED";
        var out=new LinkedHashMap<String,Object>();
        out.put("schemaVersion",1);out.put("documentState",document.name());
        out.put("documentChars",original==null?-1:Math.min(original.length(),MAX_DOCUMENT_CHARS+1));
        out.put("displayTokenCount",display.tokens);out.put("displayLineCount",display.lines);
        out.put("displayStrictCount",display.strict);out.put("displayNullCount",display.nil);
        out.put("displayUnrecognizedCount",display.unrecognized);out.put("displayState",display.state.name());
        add(out,"window",window);add(out,"app",app);
        out.put("componentRelation",relation.name());out.put("strictAccepted",strict!=null);out.put("failureClass",failure);
        return new BingFocusDiagnostic(out,strict);
    }
    private record DisplayField(int tokens,int lines,int strict,int nil,int unrecognized,FieldState state){}
    private record Field(int tokens,int lines,int strict,int nil,int generic,int wrongUser,int nonBing,
            int unrecognized,FieldState state,Role role,String component){}
    private static DisplayField display(String text){
        int tokens=count(text,"mTopFocusedDisplayId="),lines=matches(text,"(?m)^[ \\t]*mTopFocusedDisplayId="),strict=0;
        String selected=null;Matcher matcher=DISPLAY.matcher(text);while(matcher.find()){strict++;selected=matcher.group(1);}
        int nil=matches(text,"(?m)^[ \\t]*mTopFocusedDisplayId=null[ \\t]*$");
        // The source parser checks exact display-match cardinality, not raw occurrence cardinality.
        FieldState state=strict>1?FieldState.DUPLICATE:strict==1?
                (!"0".equals(selected)?FieldState.WRONG_DISPLAY:FieldState.PRESENT):tokens==0?FieldState.MISSING:
                nil>0?FieldState.NULL_VALUE:FieldState.UNRECOGNIZED;
        return new DisplayField(tokens,lines,strict,nil,Math.max(0,lines-strict-nil),state);
    }
    private static Field field(String text,String token,Pattern genericPattern,Pattern strictPattern){
        int tokens=count(text,token),lines=matches(text,"(?m)^[ \\t]*"+Pattern.quote(token));
        int nil=matches(text,"(?m)^[ \\t]*"+Pattern.quote(token)+"null[ \\t]*$");
        int generic=0,strict=0,wrong=0,nonBing=0;String selected=null;Role role=Role.UNRECOGNIZED;
        String exactComponent=null;
        Matcher exact=strictPattern.matcher(text);while(exact.find()){strict++;exactComponent=exact.group(2);}
        Matcher matcher=genericPattern.matcher(text);
        while(matcher.find()){
            generic++;String user=matcher.group(2),component=matcher.group(3);
            if(!user.equals("0"))wrong++;
            if(!component.startsWith(BING))nonBing++;
            selected=component;role=role(component);
        }
        if(strict==1&&tokens==1){selected=exactComponent;role=role(exactComponent);}
        else if(generic!=1||tokens!=1||lines!=1){selected=null;role=Role.UNRECOGNIZED;}
        FieldState state=tokens==0?FieldState.MISSING:tokens!=1||lines>1?FieldState.DUPLICATE:nil>0?
                FieldState.NULL_VALUE:strict==1?FieldState.PRESENT:lines!=1||generic!=1?FieldState.UNRECOGNIZED:
                wrong>0?FieldState.WRONG_USER:nonBing>0?FieldState.NON_BING:FieldState.UNRECOGNIZED;
        return new Field(tokens,lines,strict,nil,generic,wrong,nonBing,Math.max(0,lines-generic-nil),state,role,selected);
    }
    private static Role role(String component){
        if(component.equals(BING+"com.microsoft.sapphire.runtime.templates.TemplateActivity"))return Role.TEMPLATE;
        if(component.equals(BING+"com.microsoft.sapphire.app.browser.BrowserActivity"))return Role.BROWSER;
        if(component.equals(BING+"com.microsoft.sapphire.app.main.MainSapphireActivity"))return Role.MAIN;
        return component.startsWith(BING)?Role.OTHER_BING:Role.NON_BING;
    }
    private static void add(Map<String,Object> out,String prefix,Field field){
        out.put(prefix+"TokenCount",field.tokens);out.put(prefix+"LineCount",field.lines);
        out.put(prefix+"StrictCount",field.strict);out.put(prefix+"NullCount",field.nil);
        out.put(prefix+"GenericCount",field.generic);out.put(prefix+"WrongUserCount",field.wrongUser);
        out.put(prefix+"NonBingCount",field.nonBing);out.put(prefix+"UnrecognizedCount",field.unrecognized);
        out.put(prefix+"State",field.state.name());out.put(prefix+"Role",field.role.name());
    }
    private static int count(String text,String token){int n=0,at=0;while((at=text.indexOf(token,at))>=0){n++;at+=token.length();}return n;}
    private static int matches(String text,String regex){int n=0;Matcher matcher=Pattern.compile(regex).matcher(text);while(matcher.find())n++;return n;}
    Map<String,Object> scalars(){return values;}
    boolean strictAccepted(){return strictFocus!=null;}
    String json(){return jsonValue(values);}
    static History history(){try{return new History();}catch(Throwable unavailable){return null;}}

    /** Small ring of scalar receipts. The first non-ready read survives all later diagnostic samples. */
    static final class History {
        private final ArrayDeque<Map<String,Object>> recent=new ArrayDeque<>();
        private Map<String,Object> firstRejected;
        private BingForegroundSnapshot.Snapshot previousAccepted;
        private long totalSamples;
        private boolean unavailable;
        private boolean focusRejected;
        synchronized boolean hasFocusRejection(){return !unavailable&&focusRejected;}
        void record(String call,int pair,long elapsed,BingFocusDiagnostic focus,boolean parsed,String parserStatus,
                String parserGate,boolean parserSourceReady,boolean returnedSourceReady,boolean knownClean){
            record("SOURCE_READ",call,pair,elapsed,focus,parsed,parserStatus,parserGate,parserSourceReady,returnedSourceReady,knownClean);
        }
        synchronized void record(String phase,String call,int pair,long elapsed,BingFocusDiagnostic focus,boolean parsed,String parserStatus,
                String parserGate,boolean parserSourceReady,boolean returnedSourceReady,boolean knownClean){
            record(phase,call,pair,elapsed,focus,parsed,parserStatus,parserGate,parserSourceReady,returnedSourceReady,knownClean,
                    "NOT_OBSERVED","NOT_OBSERVED");
        }
        synchronized void record(String phase,String call,int pair,long elapsed,BingFocusDiagnostic focus,boolean parsed,String parserStatus,
                String parserGate,boolean parserSourceReady,boolean returnedSourceReady,boolean knownClean,String windowsStatus,String activitiesStatus){
            record(phase,call,pair,-1,elapsed,focus,parsed,parserStatus,parserGate,parserSourceReady,returnedSourceReady,knownClean,windowsStatus,activitiesStatus);
        }
        synchronized void record(String phase,String call,int pair,long observedElapsed,long elapsed,BingFocusDiagnostic focus,boolean parsed,String parserStatus,
                String parserGate,boolean parserSourceReady,boolean returnedSourceReady,boolean knownClean,String windowsStatus,String activitiesStatus){
            if(unavailable)return;
            try{
                if(phase==null||!phase.matches("SOURCE_READ|AFTER_REJECTION_DIAGNOSTIC")||call==null
                        ||!call.matches("LIVE_TEMPLATE|LIVE_VISIT|ALIGNMENT|LEGACY|DIAGNOSTIC")||pair< -1||observedElapsed< -1
                        ||elapsed< -1||elapsed>Integer.MAX_VALUE||observedElapsed>=0&&elapsed>=0&&observedElapsed<elapsed||focus==null
                        ||!parserStatus.matches("NOT_OBSERVED|READY|BING_TRANSITION|UNKNOWN")
                        ||parserGate==null||!parserGate.matches("[A-Z0-9_]{1,128}")||!readStatus(windowsStatus)||!readStatus(activitiesStatus))
                    throw new IllegalArgumentException("Fixed focus sample required");
                long sequence=++totalSamples;
                Truth same=previousAccepted==null||focus.strictFocus==null?Truth.NOT_OBSERVED:
                        previousAccepted.equals(focus.strictFocus)?Truth.TRUE:Truth.FALSE;
                var sample=new LinkedHashMap<String,Object>();sample.put("sequence",sequence);sample.put("phase",phase);sample.put("call",call);sample.put("pair",pair);
                sample.put("observedElapsed",observedElapsed);sample.put("elapsedMs",elapsed);sample.put("parsed",parsed);sample.put("parserStatus",parserStatus);
                sample.put("parserGate",parserGate);sample.put("parserSourceReady",parserSourceReady);
                sample.put("returnedSourceReady",returnedSourceReady);sample.put("knownClean",knownClean);
                sample.put("windowsStatus",windowsStatus);sample.put("activitiesStatus",activitiesStatus);
                sample.put("previousAcceptedObserved",previousAccepted!=null);sample.put("samePreviousAccepted",same.name());
                sample.put("focus",focus.values);Map<String,Object> immutable=Collections.unmodifiableMap(sample);
                if(phase.equals("SOURCE_READ")&&!returnedSourceReady&&firstRejected==null)firstRejected=immutable;
                if(phase.equals("SOURCE_READ")&&parsed&&!focus.strictAccepted()
                        &&(parserGate.equals("FOCUS_FORMAT")||parserGate.equals("FOCUS_COMPONENT_CONFLICT")))focusRejected=true;
                recent.addLast(immutable);while(recent.size()>MAX_HISTORY)recent.removeFirst();
                if(phase.equals("SOURCE_READ")&&returnedSourceReady&&parsed&&parserSourceReady&&focus.strictFocus!=null)previousAccepted=focus.strictFocus;
            }catch(Throwable diagnosticUnavailable){unavailable=true;recent.clear();firstRejected=null;previousAccepted=null;}
        }
        synchronized Map<String,Object> scalars(){
            try{
                if(unavailable)return null;
                var samples=new ArrayList<>(recent);
                if(firstRejected!=null&&!samples.contains(firstRejected)){
                    while(samples.size()>=MAX_HISTORY)samples.remove(0);samples.add(0,firstRejected);
                }
                var out=new LinkedHashMap<String,Object>();out.put("schemaVersion",1);out.put("maxSamples",MAX_HISTORY);
                out.put("totalSamples",totalSamples);out.put("retainedSamples",samples.size());
                out.put("droppedSamples",totalSamples-samples.size());out.put("firstRejectedRetained",firstRejected!=null);
                out.put("samples",List.copyOf(samples));return Collections.unmodifiableMap(out);
            }catch(Throwable diagnosticUnavailable){return null;}
        }
        synchronized String json(){try{var exact=scalars();return exact==null?null:jsonValue(exact);}catch(Throwable unavailable){return null;}}
        private static boolean readStatus(String value){return value!=null&&value.matches("NOT_OBSERVED|NOT_INVOKED|AUTHORITY_REJECTED|PROCESS_NULL|STREAM_MISSING|BUFFER_LIMIT|READ_TIMEOUT|EXIT_NONZERO|IO_UNKNOWN|READ_COMPLETE|CLEANUP_UNKNOWN");}
    }
    private static String jsonValue(Object value){
        if(value instanceof String fixed){if(!fixed.matches("[A-Z0-9_]{1,128}"))throw new IllegalArgumentException("Fixed enum required");return "\""+fixed+"\"";}
        if(value instanceof Integer||value instanceof Long||value instanceof Boolean)return value.toString();
        if(value instanceof List<?> list){var out=new StringBuilder("[");boolean first=true;for(Object item:list){if(!first)out.append(',');first=false;out.append(jsonValue(item));}return out.append(']').toString();}
        if(value instanceof Map<?,?> map){var out=new StringBuilder("{");boolean first=true;for(var entry:map.entrySet()){
            if(!(entry.getKey() instanceof String key)||!key.matches("[A-Za-z][A-Za-z0-9]*"))throw new IllegalArgumentException("Fixed diagnostic key required");
            if(!first)out.append(',');first=false;out.append('"').append(key).append("\":").append(jsonValue(entry.getValue()));
        }return out.append('}').toString();}
        throw new IllegalArgumentException("Fixed diagnostic scalar required");
    }
}
