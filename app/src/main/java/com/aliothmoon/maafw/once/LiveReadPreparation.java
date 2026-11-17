package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owner-thread diagnostics for one read attempt. No authority, I/O, clock or exception text is read here. */
final class LiveReadPreparation {
    enum Stage { NOT_STARTED, VALIDATE_REQUEST, AUTHORITY_INITIAL, APK_VERIFY, BINDER, AUTHORITY_RECHECK,
        CLAIM_READER, SOURCE_GATE, UI_READ_BURN, SOURCE_CURRENT, POST_GATE, NEW_PROCESS, STREAMS,
        READ, EXIT, AUTHORITY_FINAL, PROTOCOL, CLEANUP }
    enum ExceptionKind { NONE, SECURITY_EXCEPTION, IO_EXCEPTION, INTERRUPTED, PACKAGE_NOT_FOUND,
        ILLEGAL_ARGUMENT, ILLEGAL_STATE, UNSUPPORTED_OPERATION, RUNTIME_EXCEPTION, CHECKED_EXCEPTION }
    enum Termination { COMPLETED, CONTROLLED_STOP, EXCEPTION }
    enum Mode { LIVE_REWARDS_UI_V1, LIVE_VISIT_V1 }
    private enum ScopeKind { CHECK_IN, DAILY, MORE, UNKNOWN }
    private enum ObservationPhase { BEFORE, AFTER_1, AFTER_2, UNKNOWN }
    private enum Status { AUTHORITY_REJECTED, INVALID_REQUEST, ALREADY_CLAIMED, GATE_REJECTED,
        PROCESS_NULL, STREAM_MISSING, BUFFER_LIMIT, READ_TIMEOUT, CANCELLED, EXIT_NONZERO,
        PROTOCOL_REJECTED, IO_UNKNOWN, READ_COMPLETE, CLEANUP_UNKNOWN, UNKNOWN }
    private final int attemptIndex,scopeUiIndex;
    private final ScopeKind scopeKind;
    private final ObservationPhase observationPhase;
    private final boolean preflight,scan;
    @FunctionalInterface interface Clock { long now(); }
    private final long[] millis=new long[Stage.values().length];
    private Clock clock;
    private boolean recordingFailed;
    private Stage active=Stage.NOT_STARTED,terminalStage;
    private ExceptionKind exceptionKind=ExceptionKind.NONE,cleanupExceptionKind=ExceptionKind.NONE;
    private Termination termination=Termination.COMPLETED;
    private Mode mode=Mode.LIVE_REWARDS_UI_V1;
    private long started=-1,last=-1,deadline=-1;
    private int stageVisits;
    private boolean timingValid=true,sourceGateReturned,uiReadBurnReturned;

    LiveReadPreparation(){this(0,"UNKNOWN","UNKNOWN",0,false,false);}
    LiveReadPreparation(int attemptIndex,String scopeKind,String phase,int scopeUiIndex,boolean preflight,boolean scan){
        this.attemptIndex=Math.max(0,attemptIndex);this.scopeUiIndex=Math.max(0,scopeUiIndex);
        this.scopeKind=scopeKind(scopeKind);observationPhase=phase(phase);this.preflight=preflight;this.scan=scan;
    }
    private static ScopeKind scopeKind(String value){
        try{return ScopeKind.valueOf(value);}catch(IllegalArgumentException|NullPointerException invalid){return ScopeKind.UNKNOWN;}
    }
    private static ObservationPhase phase(String value){
        try{return ObservationPhase.valueOf(value);}catch(IllegalArgumentException|NullPointerException invalid){return ObservationPhase.UNKNOWN;}
    }
    void begin(long now,long absoluteDeadline,Mode mode){begin(now,absoluteDeadline,mode,null);}
    void begin(long now,long absoluteDeadline,Mode mode,Clock clock){
        try{
            started=last=now;deadline=absoluteDeadline;this.mode=mode==null?Mode.LIVE_REWARDS_UI_V1:mode;this.clock=clock;
            timingValid=now>=0&&absoluteDeadline>=0;enterDirect(Stage.VALIDATE_REQUEST,now);
        }catch(Throwable ignored){recordingFailed=true;}
    }
    void enter(Stage next){
        try{enterDirect(next,clock.now());}catch(Throwable ignored){recordingFailed=true;}
    }
    void enter(Stage next,long now){
        try{enterDirect(next,now);}catch(Throwable ignored){recordingFailed=true;}
    }
    private void enterDirect(Stage next,long now){
        if(next==null){recordingFailed=true;return;}advance(now);active=next;if(stageVisits<Integer.MAX_VALUE)stageVisits++;
    }
    private void advance(long now){
        if(last<0||now<last){timingValid=false;return;}
        long delta=now-last,old=millis[active.ordinal()];
        if(old>Long.MAX_VALUE-delta){millis[active.ordinal()]=Long.MAX_VALUE;timingValid=false;}
        else millis[active.ordinal()]=old+delta;
        last=now;
    }
    // Only the navigation-source substep is known here; this is not whole-gate approval.
    void sourceGateReturned(){sourceGateReturned=true;}
    void uiReadBurnReturned(boolean returned){uiReadBurnReturned=returned;}
    void controlledStop(){terminalStage=active;termination=Termination.CONTROLLED_STOP;}
    void exception(Throwable failure){
        try{exception(classify(failure));}catch(Throwable ignored){recordingFailed=true;}
    }
    void exception(ExceptionKind kind){
        try{
            if(kind==ExceptionKind.NONE)return;
            if(exceptionKind==ExceptionKind.NONE){exceptionKind=kind==null?ExceptionKind.CHECKED_EXCEPTION:kind;terminalStage=active;}
            termination=Termination.EXCEPTION;
        }catch(Throwable ignored){recordingFailed=true;}
    }
    void beginCleanup(){
        try{beginCleanupDirect(clock.now());}catch(Throwable ignored){recordingFailed=true;}
    }
    void beginCleanup(long now){
        try{beginCleanupDirect(now);}catch(Throwable ignored){recordingFailed=true;}
    }
    private void beginCleanupDirect(long now){if(terminalStage==null)terminalStage=active;enterDirect(Stage.CLEANUP,now);}
    void cleanupException(Throwable failure){
        try{
            var kind=classify(failure);if(kind==ExceptionKind.NONE)return;
            if(cleanupExceptionKind==ExceptionKind.NONE)cleanupExceptionKind=kind;
            if(exceptionKind==ExceptionKind.NONE&&termination==Termination.COMPLETED){terminalStage=Stage.CLEANUP;termination=Termination.EXCEPTION;}
        }catch(Throwable ignored){recordingFailed=true;}
    }
    static ExceptionKind classify(Throwable failure){
        if(failure==null)return ExceptionKind.NONE;
        if(failure instanceof InterruptedException)return ExceptionKind.INTERRUPTED;
        if(failure instanceof SecurityException)return ExceptionKind.SECURITY_EXCEPTION;
        if(failure instanceof IOException)return ExceptionKind.IO_EXCEPTION;
        if(failure instanceof UnsupportedOperationException)return ExceptionKind.UNSUPPORTED_OPERATION;
        if(failure instanceof IllegalArgumentException)return ExceptionKind.ILLEGAL_ARGUMENT;
        if(failure instanceof IllegalStateException)return ExceptionKind.ILLEGAL_STATE;
        if(failure instanceof RuntimeException)return ExceptionKind.RUNTIME_EXCEPTION;
        return ExceptionKind.CHECKED_EXCEPTION;
    }
    Snapshot finish(String fixedStatus,boolean invoked,boolean knownClean,int acquiredBytes,boolean processQuiet,
            boolean exitObserved,int exitCode){
        try{return finishDirect(fixedStatus,invoked,knownClean,acquiredBytes,processQuiet,exitObserved,exitCode,clock.now());}
        catch(Throwable ignored){recordingFailed=true;return null;}
    }
    Snapshot finish(String fixedStatus,boolean invoked,boolean knownClean,int acquiredBytes,boolean processQuiet,
            boolean exitObserved,int exitCode,long now){
        try{return finishDirect(fixedStatus,invoked,knownClean,acquiredBytes,processQuiet,exitObserved,exitCode,now);}
        catch(Throwable ignored){recordingFailed=true;return null;}
    }
    private Snapshot finishDirect(String fixedStatus,boolean invoked,boolean knownClean,int acquiredBytes,boolean processQuiet,
            boolean exitObserved,int exitCode,long now){
        if(recordingFailed)return null;
        advance(now);if(terminalStage==null)terminalStage=active;
        Status status;try{status=Status.valueOf(fixedStatus);}catch(IllegalArgumentException|NullPointerException invalid){status=Status.UNKNOWN;}
        Map<String,Object> v=new LinkedHashMap<>();
        v.put("version",1);v.put("attemptIndex",attemptIndex);v.put("scopeKind",scopeKind.name());
        v.put("observationPhase",observationPhase.name());v.put("scopeUiIndex",scopeUiIndex);v.put("preflight",preflight);v.put("scan",scan);
        v.put("requestMode",mode.name());v.put("stage",terminalStage.name());v.put("exceptionKind",exceptionKind.name());
        v.put("cleanupExceptionKind",cleanupExceptionKind.name());v.put("termination",termination.name());v.put("fixedStatus",status.name());
        v.put("invoked",invoked);v.put("knownClean",knownClean);v.put("acquiredBytes",acquiredBytes);v.put("processQuiet",processQuiet);
        v.put("exitObserved",exitObserved);v.put("exitCode",exitCode);v.put("elapsedMs",difference(last,started));
        v.put("deadlineBudgetMs",difference(deadline,started));v.put("remainingMsAtFinish",difference(deadline,last));
        v.put("timingValid",timingValid);v.put("stageVisits",stageVisits);v.put("sourceGateReturned",sourceGateReturned);v.put("uiReadBurnReturned",uiReadBurnReturned);
        Map<String,Object> durations=new LinkedHashMap<>();for(Stage stage:Stage.values())durations.put(stage.name(),millis[stage.ordinal()]);
        v.put("stageMillis",Collections.unmodifiableMap(durations));return new Snapshot(v);
    }
    private static long difference(long end,long start){return start<0||end<start?0:end-start;}

    static final class Snapshot {
        private final Map<String,Object> values;
        private Snapshot(Map<String,Object> values){this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values));}
        Object value(String key){return values.get(key);}
        Map<String,Object> scalars(){return values;}
        String json(){StringBuilder out=new StringBuilder();append(out,values);return out.toString();}
        private static void append(StringBuilder out,Object value){
            if(value instanceof Map<?,?> map){
                out.append('{');boolean first=true;for(var item:map.entrySet()){
                    if(!first)out.append(',');first=false;out.append('"').append(item.getKey()).append("\":");append(out,item.getValue());
                }out.append('}');
            }else if(value instanceof String fixed)out.append('"').append(fixed).append('"');
            else out.append(value);
        }
    }
}
