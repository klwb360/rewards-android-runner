package com.aliothmoon.maafw.once;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Passive snapshots only. Recording failures never replace a source result or exception. */
final class LiveNavigationDiagnostic {
    enum Call { TEMPLATE, VISIT }
    enum Truth { TRUE, FALSE, NOT_OBSERVED }
    enum Stage { BEGIN, EXTRA_BEFORE, CURRENT_BEFORE, METADATA_READ, READ_CLEAN, RESULT_PRESENT,
        STATUS_READY, SOURCE_PRESENT, SOURCE_TEMPLATE, SOURCE_FRESH, EXTRA_AFTER, CURRENT_AFTER,
        BASELINE_BIND, BASELINE_SAME, ORIGINAL_PRESENT, ORIGINAL_SAME, RETURNED }
    enum Check { EXTRA_BEFORE, CURRENT_BEFORE, READ_CLEAN, RESULT_PRESENT, STATUS_READY, SOURCE_PRESENT,
        SOURCE_TEMPLATE, SOURCE_FRESH, EXTRA_AFTER, CURRENT_AFTER, BASELINE_PRESENT, BASELINE_SAME,
        ORIGINAL_PRESENT, ORIGINAL_SAME }
    enum ExceptionKind { NOT_OBSERVED, NONE, SECURITY, ILLEGAL_STATE, ILLEGAL_ARGUMENT,
        IO, INTERRUPTED, REMOTE, OTHER_RUNTIME, OTHER_EXCEPTION, OTHER_ERROR }
    private final Map<String,Object> values;
    private LiveNavigationDiagnostic(Map<String,Object> values){
        validate(values);
        this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    static final class Trace {
        private final LinkedHashMap<String,Object> values=new LinkedHashMap<>();
        private boolean disabled;
        private Trace(Call call){
            values.put("version",1);values.put("call",call.name());values.put("stage",Stage.BEGIN.name());
            values.put("exceptionKind",ExceptionKind.NONE.name());values.put("elapsedMs",-1L);
            for(Check check:Check.values())values.put(key(check),Truth.NOT_OBSERVED.name());
            values.put("metadataObserved",Truth.NOT_OBSERVED.name());values.put("metadataPair",-1);
            values.put("metadataPairGate","NOT_OBSERVED");values.put("metadataParserStatus","NOT_OBSERVED");
            values.put("metadataFirstGate","NOT_OBSERVED");values.put("metadataReadOnlyGate","NOT_OBSERVED");
            values.put("metadataVisitGate","NOT_OBSERVED");values.put("metadataExceptionKind",ExceptionKind.NOT_OBSERVED.name());
            values.put("metadataElapsedMs",-1L);values.put("windowsElapsedMs",-1L);values.put("activitiesElapsedMs",-1L);
            for(String prefix:new String[]{"windows","activities"}){
                values.put(prefix+"Observed",Truth.NOT_OBSERVED.name());values.put(prefix+"Status","NOT_OBSERVED");
                values.put(prefix+"Bytes",-1);values.put(prefix+"Invoked",Truth.NOT_OBSERVED.name());
                values.put(prefix+"ExitObserved",Truth.NOT_OBSERVED.name());values.put(prefix+"ExitCode",-1);
                values.put(prefix+"Quiet",Truth.NOT_OBSERVED.name());values.put(prefix+"Clean",Truth.NOT_OBSERVED.name());
                values.put(prefix+"ExceptionKind",ExceptionKind.NOT_OBSERVED.name());
            }
        }
        private void put(String key,Object value){
            if(disabled)return;
            try{values.put(key,value);}catch(Throwable unavailable){disabled=true;}
        }
    }
    static Trace begin(Call call){try{return new Trace(call);}catch(Throwable unavailable){return null;}}
    static long clock(java.util.function.LongSupplier clock){try{return clock.getAsLong();}catch(Throwable unavailable){return -1;}}
    static long clock(){try{return android.os.SystemClock.elapsedRealtime();}catch(Throwable unavailable){return -1;}}
    static long elapsed(long before,long after){return before<0||after<before?-1:after-before;}
    static void stage(Trace t,Stage stage){try{if(t!=null)t.put("stage",stage.name());}catch(Throwable unavailable){if(t!=null)t.disabled=true;}}
    static boolean check(Trace t,Check check,boolean value){
        try{if(t!=null)t.put(key(check),(value?Truth.TRUE:Truth.FALSE).name());}catch(Throwable unavailable){if(t!=null)t.disabled=true;}
        return value;
    }
    static boolean observe(Trace t,Stage stage,Check check,java.util.function.BooleanSupplier operation){
        stage(t,stage);boolean value=operation.getAsBoolean();return check(t,check,value);
    }
    static void exception(Trace t,Throwable failure){try{if(t!=null)t.put("exceptionKind",kind(failure).name());}catch(Throwable unavailable){if(t!=null)t.disabled=true;}}
    static void metadataStarted(Trace t,int pair){
        try{if(t==null)return;t.put("metadataObserved",Truth.TRUE.name());t.put("metadataPair",pair);
            t.put("metadataExceptionKind",ExceptionKind.NONE.name());}catch(Throwable unavailable){if(t!=null)t.disabled=true;}
    }
    static void pairGate(Trace t,ReviewBingNavigation.PairGate gate){try{if(t!=null)t.put("metadataPairGate",gate.name());}catch(Throwable unavailable){if(t!=null)t.disabled=true;}}
    static void parser(Trace t,BingNavigationSnapshot.Result result){
        try{if(t==null||result==null)return;t.put("metadataParserStatus",result.status().name());
            t.put("metadataFirstGate",result.firstGate().name());t.put("metadataReadOnlyGate",result.readOnlyGate().name());
        }catch(Throwable unavailable){if(t!=null)t.disabled=true;}
    }
    static void visitParser(Trace t,BingNavigationSnapshot.LiveVisitResult result){
        try{if(t==null||result==null)return;
            t.put("metadataVisitGate",result.gate().name());
            t.put("metadataParserStatus",switch(result.kind()){
                case ORIGINAL_REWARDS,BOUND_CHILD->"READY";case TRANSITION->"BING_TRANSITION";default->"UNKNOWN";
            });
        }catch(Throwable unavailable){if(t!=null)t.disabled=true;}
    }
    static void fixed(Trace t,boolean windows,ReviewBingNavigation.FixedRead read,ExceptionKind exception,long millis){
        try{if(t==null||read==null)return;String p=windows?"windows":"activities";
            t.put(p+"Observed",Truth.TRUE.name());t.put(p+"Status",read.status().name());t.put(p+"Bytes",read.bytes());
            t.put(p+"Invoked",truth(read.invoked()));t.put(p+"ExitObserved",truth(read.exitObserved()));t.put(p+"ExitCode",read.exitCode());
            t.put(p+"Quiet",truth(read.quiet()));t.put(p+"Clean",truth(read.knownClean()));
            t.put(p+"ExceptionKind",exception.name());t.put(p+"ElapsedMs",millis<0?-1L:millis);
        }catch(Throwable unavailable){if(t!=null)t.disabled=true;}
    }
    static void metadataException(Trace t,Throwable failure){try{if(t!=null)t.put("metadataExceptionKind",kind(failure).name());}catch(Throwable unavailable){if(t!=null)t.disabled=true;}}
    static void metadataElapsed(Trace t,long millis){try{if(t!=null)t.put("metadataElapsedMs",millis<0?-1L:millis);}catch(Throwable unavailable){if(t!=null)t.disabled=true;}}
    static void metadata(Trace t,LiveNavigationDiagnostic metadata){
        if(t==null||metadata==null)return;
        try{for(var entry:metadata.values.entrySet())if(entry.getKey().startsWith("metadata")
                ||entry.getKey().startsWith("windows")||entry.getKey().startsWith("activities"))t.put(entry.getKey(),entry.getValue());}
        catch(Throwable unavailable){t.disabled=true;}
    }
    static LiveNavigationDiagnostic finish(Trace t,long millis){
        if(t==null)return null;
        try{t.put("elapsedMs",millis<0?-1L:millis);return t.disabled?null:new LiveNavigationDiagnostic(t.values);}
        catch(Throwable unavailable){return null;}
    }
    private static String truth(boolean value){return (value?Truth.TRUE:Truth.FALSE).name();}
    private static String key(Check check){return "check"+check.name();}
    static ExceptionKind kind(Throwable failure){
        if(failure==null)return ExceptionKind.NONE;
        if(failure instanceof SecurityException)return ExceptionKind.SECURITY;
        if(failure instanceof IllegalStateException)return ExceptionKind.ILLEGAL_STATE;
        if(failure instanceof IllegalArgumentException)return ExceptionKind.ILLEGAL_ARGUMENT;
        if(failure instanceof InterruptedException)return ExceptionKind.INTERRUPTED;
        if(failure instanceof android.os.RemoteException)return ExceptionKind.REMOTE;
        if(failure instanceof java.io.IOException)return ExceptionKind.IO;
        if(failure instanceof RuntimeException)return ExceptionKind.OTHER_RUNTIME;
        return failure instanceof Error?ExceptionKind.OTHER_ERROR:ExceptionKind.OTHER_EXCEPTION;
    }
    Map<String,Object> scalars(){return values;}
    private static void validate(Map<String,Object> values){
        var defaults=new Trace(Call.TEMPLATE).values;
        if(values==null||!values.keySet().equals(defaults.keySet()))throw invalid();
        for(var entry:values.entrySet()){
            String key=entry.getKey();Object value=entry.getValue(),sample=defaults.get(key);
            if(sample instanceof Integer){
                if(!(value instanceof Integer n))throw invalid();
                if(key.equals("version")?n!=1:key.equals("metadataPair")?n< -1||n>4097
                        :key.endsWith("Bytes")?n< -1||n>262144:n< -1||n>65535)throw invalid();
            }else if(sample instanceof Long){
                if(!(value instanceof Long n)||n< -1||n>Integer.MAX_VALUE)throw invalid();
            }else{
                if(!(value instanceof String s)||!s.matches("[A-Z0-9_]+"))throw invalid();
                Class<? extends Enum<?>> type;
                if(key.equals("call"))type=Call.class;
                else if(key.equals("stage"))type=Stage.class;
                else if(key.endsWith("ExceptionKind")||key.equals("exceptionKind"))type=ExceptionKind.class;
                else if(key.equals("metadataPairGate"))type=ReviewBingNavigation.PairGate.class;
                else if(key.equals("metadataParserStatus"))type=BingNavigationSnapshot.Status.class;
                else if(key.equals("metadataFirstGate"))type=BingNavigationSnapshot.FirstGate.class;
                else if(key.equals("metadataReadOnlyGate"))type=BingNavigationSnapshot.ReadOnlyGate.class;
                else if(key.equals("metadataVisitGate"))type=BingNavigationSnapshot.LiveVisitGate.class;
                else if(key.endsWith("Status"))type=ReviewBingNavigation.FixedStatus.class;
                else type=Truth.class;
                boolean known=!key.equals("call")&&!key.equals("stage")&&s.equals("NOT_OBSERVED");
                for(var item:type.getEnumConstants())known|=item.name().equals(s);
                if(!known)throw invalid();
            }
        }
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Fixed navigation diagnostic required");}
    String json(){
        StringBuilder out=new StringBuilder("{");boolean first=true;
        for(var entry:values.entrySet()){
            if(!first)out.append(',');first=false;out.append('"').append(entry.getKey()).append("\":");
            Object value=entry.getValue();
            if(value instanceof String fixed){
                if(!fixed.matches("[A-Z0-9_]+"))throw new IllegalStateException("Fixed diagnostic enum required");
                out.append('"').append(fixed).append('"');
            }else if(value instanceof Integer||value instanceof Long)out.append(value);
            else throw new IllegalStateException("Fixed diagnostic scalar required");
        }
        return out.append('}').toString();
    }
    private LiveNavigationDiagnostic(){throw new AssertionError();}
}
