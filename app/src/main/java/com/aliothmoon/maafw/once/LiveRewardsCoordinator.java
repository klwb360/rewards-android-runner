package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicitly approved run. Inputs are delegated only through sealed per-task permits. */
final class LiveRewardsCoordinator {
    static final long AFTER_WINDOW_MILLIS=5_000L,AFTER_SPACING_MILLIS=1_000L;
    interface Ports {
        LiveRewardsTypes.Preflight preflightRegion(LiveRunAuthority authority,LiveRewardsTypes.Kind kind)throws Exception;
        LiveRewardsTypes.Observation observe(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,
                LiveRewardsTypes.Task expected)throws Exception;
        LiveRewardsInput.TapReceipt tap(LiveActionPermit permit)throws Exception;
        LiveRewardsInput.ReturnReceipt returnAfterTap(LiveActionPermit permit,LiveRewardsInput.TapReceipt tapped)throws Exception;
        void pause(long millis)throws InterruptedException;
        boolean knownClean();
        void report(LiveRewardsTypes.Task task,LiveRewardsTypes.Outcome outcome,String reason,
                LiveRewardsTypes.Observation before,LiveRewardsTypes.Observation after);
    }
    record Summary(int regionsReady,int requiredRegions,int planned,Map<LiveRewardsTypes.Outcome,Integer> counts,
                   int taps,int backs,boolean stopped,String reason) {
        Summary {
            EnumMap<LiveRewardsTypes.Outcome,Integer> copy=new EnumMap<>(LiveRewardsTypes.Outcome.class);
            for(var o:LiveRewardsTypes.Outcome.values())copy.put(o,counts.getOrDefault(o,0));
            counts=Collections.unmodifiableMap(copy);
            if(reason==null||!reason.matches("[A-Z][A-Z0-9_]{0,79}"))throw new IllegalArgumentException("Safe summary reason required");
        }
        boolean preflightPassed(){return regionsReady==requiredRegions;}
        String fixedSummary(){
            StringBuilder value=new StringBuilder("profile=LIVE_REWARDS_CYCLE_V1 preflightPassed="+preflightPassed()
                    +" regionsReady="+regionsReady+" planned="+planned+" tapAttempts="+taps+" backAttempts="+backs+" stopped="+stopped+" reason="+reason+" outcomes=");
            boolean first=true;for(var e:counts.entrySet()){if(!first)value.append(',');first=false;value.append(e.getKey()).append(':').append(e.getValue());}
            return value.toString();
        }
    }
    private final LiveRunAuthority authority;
    private final LiveRewardsLedger ledger;
    private final Ports ports;
    private final boolean singleTask;
    private final LiveRewardsTaskSelection selection;
    private final AtomicBoolean started=new AtomicBoolean();
    private final EnumMap<LiveRewardsTypes.Outcome,Integer> counts=new EnumMap<>(LiveRewardsTypes.Outcome.class);
    private final Set<String> reported=new HashSet<>();
    private int regionsReady,planned,taps,backs;
    private boolean stopped;
    private String reason="NEW";
    private LiveRewardsTypes.Task activeTask;
    private LiveRewardsTypes.Observation activeBefore,activeAfter;

    LiveRewardsCoordinator(LiveRunAuthority authority,LiveRewardsLedger ledger,Ports ports){
        this(authority, ledger, ports, false);
    }
    LiveRewardsCoordinator(LiveRunAuthority authority,LiveRewardsLedger ledger,Ports ports,boolean singleTask){
        this(authority,ledger,ports,singleTask,LiveRewardsTaskSelection.ALL);
    }
    LiveRewardsCoordinator(LiveRunAuthority authority,LiveRewardsLedger ledger,Ports ports,boolean singleTask,
            LiveRewardsTaskSelection selection){
        if(authority==null||ledger==null||ports==null)throw new IllegalArgumentException("Exact run ports required");
        if(selection==null || singleTask && selection.limited() || !selection.current(authority.day()))
            throw new IllegalArgumentException("Selection must match this run");
        this.authority=authority;this.ledger=ledger;this.ports=ports;authority.attachLedger(ledger);
        this.singleTask=singleTask;
        this.selection=selection;
        for(var o:LiveRewardsTypes.Outcome.values())counts.put(o,0);
    }
    void cancel(){authority.cancel();}
    Summary run(){
        if(!started.compareAndSet(false,true))return summary("ALREADY_STARTED",true);
        try{
            reason = singleTask ? "NO_ELIGIBLE_TASK" : "PLAN_FINISHED";
            for (var kind : LiveRewardsTypes.Kind.values()) {
                if (!selection.includes(kind)) continue;
                if (runRegion(kind)) {
                    reason = "SINGLE_TASK_FINISHED";
                    break;
                }
            }
            requireCurrent();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();fail("CANCELED");}
        catch(Stop failed){fail(failed.reason);}
        catch(Exception|LinkageError failed){fail("RUN_FAILED");}
        return summary(reason,stopped);
    }

    /** Returns true when a single-task trial has recorded its one attempted task's outcome. */
    private boolean runRegion(LiveRewardsTypes.Kind kind)throws Exception{
        activeTask=null;activeBefore=null;activeAfter=null;
        requireCurrent();
        var scan=ports.preflightRegion(authority,kind);
        if(scan==null||!scan.completeScan()||!scan.knownClean()||!scan.sourceReady()
                ||scan.tasks().stream().anyMatch(task->task.kind()!=kind))throw stop("PREFLIGHT_UNQUALIFIED_"+kind.name());
        requireCurrent();
        List<LiveRewardsTypes.Task> tasks=new ArrayList<>(scan.tasks());
        tasks.removeIf(task->!selection.includes(task));
        tasks.sort(Comparator.comparingInt(LiveRewardsTypes.Task::ordinal));
        regionsReady++;
        planned+=tasks.size();
        Map<String,Integer> duplicates=new HashMap<>();
        for(var task:tasks)duplicates.merge(task.key(),1,Integer::sum);
        boolean regionFinished=false;
        for(var task:tasks){
            requireCurrent();activeTask=task;activeBefore=null;activeAfter=null;
            if(regionFinished){emit(task,LiveRewardsTypes.Outcome.REGION_COMPLETE_SKIP,"REGION_COMPLETE",null,null);continue;}
            if(duplicates.get(task.key())>1){emit(task,LiveRewardsTypes.Outcome.DUPLICATE_SKIP,"DUPLICATE_IDENTITY",null,null);continue;}
            if(ledger.attempted(task)){emit(task,LiveRewardsTypes.Outcome.ALREADY_ATTEMPTED_SKIP,"DAY_ALREADY_ATTEMPTED",null,null);continue;}
            if(task.complete()){emit(task,LiveRewardsTypes.Outcome.COMPLETE_SKIP,"PREFLIGHT_COMPLETE",null,null);continue;}
            if(!task.readable()){emit(task,LiveRewardsTypes.Outcome.UNREADABLE_SKIP,"PREFLIGHT_UNREADABLE",null,null);continue;}
            var scope=authority.cardScope(task);
            var before=ports.observe(scope,LiveRewardsTypes.Phase.BEFORE,task);activeBefore=before;
            checkObservation(scope,before,LiveRewardsTypes.Phase.BEFORE);
            if(regionComplete(before)||before.reason().equals("REGION_COMPLETE_SKIP")){
                regionFinished=true;emit(task,LiveRewardsTypes.Outcome.REGION_COMPLETE_SKIP,"REGION_COMPLETE",before,null);continue;
            }
            if(before.task().complete()){emit(task,LiveRewardsTypes.Outcome.COMPLETE_SKIP,"CURRENT_TASK_COMPLETE",before,null);continue;}
            if(!before.qualified()||!before.data().uniqueMatch()||!before.task().sameKey(task)){
                emit(task,LiveRewardsTypes.Outcome.UNREADABLE_SKIP,"CURRENT_TASK_UNREADABLE",before,null);continue;
            }
            requireCurrent();
            if(!selection.includes(before.task()) || selection.limited() && taps>=2)throw stop("TASK_OUTSIDE_SELECTION");
            ledger.begin(task);var permit=scope.actionPermit(before,ledger);
            final LiveRewardsInput.TapReceipt tapped;
            try{tapped=ports.tap(permit);}finally{if(permit.tapAttempted())taps++;}
            if(tapped==null||!tapped.success()||!tapped.knownClean()||!ports.knownClean()
                    ||tapped.successfulElapsed()<0||tapped.successfulElapsed()!=permit.successfulTapElapsed())throw stop("TAP_UNCONFIRMED");
            requireCurrent();
            if(task.kind()!=LiveRewardsTypes.Kind.CHECK_IN){
                final LiveRewardsInput.ReturnReceipt returned;
                try{returned=ports.returnAfterTap(permit,tapped);}finally{if(permit.backAttempted())backs++;}
                if(returned==null||!returned.commandConfirmed()||!returned.matchesPermit(permit)
                        ||!permit.backAttempted()||!returned.knownClean()||!ports.knownClean())throw stop("RETURN_UNCONFIRMED");
            }
            LiveRewardsTypes.Outcome outcome=LiveRewardsTypes.Outcome.UNCONFIRMED;String outcomeReason="TASK_NOT_CONFIRMED";
            long afterStarted=authority.now(),previousFinished=-1;scope.startAfterWindow(afterStarted);
            for(int attempt=1;attempt<=2;attempt++){
                requireCurrent();long now=authority.now();
                if(now<afterStarted||now-afterStarted>=AFTER_WINDOW_MILLIS)break;
                if(attempt==2){
                    long wait=Math.max(0,AFTER_SPACING_MILLIS-(now-previousFinished));
                    if(wait>AFTER_WINDOW_MILLIS-(now-afterStarted))break;
                    if(wait>0)ports.pause(wait);
                    requireCurrent();now=authority.now();if(now<afterStarted||now-afterStarted>=AFTER_WINDOW_MILLIS)break;
                }
                var phase=attempt==1?LiveRewardsTypes.Phase.AFTER_1:LiveRewardsTypes.Phase.AFTER_2;
                var after=ports.observe(scope,phase,task);activeAfter=after;
                checkObservation(scope,after,phase);previousFinished=authority.now();
                if(previousFinished<afterStarted||previousFinished-afterStarted>AFTER_WINDOW_MILLIS){outcomeReason="AFTER_WINDOW_EXPIRED";break;}
                if(!before.source().sameSource(after.source()))throw stop("AFTER_SOURCE_CHANGED");
                if(sameTask(before,after)&&after.task().complete()&&after.qualified()){
                    outcome=LiveRewardsTypes.Outcome.OBSERVED_COMPLETE;outcomeReason="SAME_TASK_NEW_TICK";break;
                }
                if(progressChanged(before,after)){
                    outcome=LiveRewardsTypes.Outcome.PROGRESS_CHANGED;outcomeReason="REGION_PROGRESS_CHANGED";break;
                }
            }
            emit(task,outcome,outcomeReason,before,activeAfter);
            if(kind==LiveRewardsTypes.Kind.CHECK_IN&&outcome!=LiveRewardsTypes.Outcome.OBSERVED_COMPLETE)
                throw stop("CHECK_IN_NOT_CONFIRMED");
            if(singleTask)return true;
        }
        return false;
    }
    private void requireCurrent()throws Stop{
        if(!authority.current())throw stop("RUN_REVOKED_OR_EXPIRED");
        if(!ports.knownClean())throw stop("CLEANUP_UNCONFIRMED");
    }
    private void checkObservation(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Observation observation,
            LiveRewardsTypes.Phase expected)throws Stop{
        long now=authority.now();
        if(observation==null||!scope.isSealed(observation)||observation.owner()!=scope.owner()||observation.phase()!=expected
                ||!observation.data().clean()||!ports.knownClean()||!observation.fresh(now)
                ||observation.source().kind()!=BingNavigationSnapshot.Kind.TEMPLATE||!observation.source().fresh(now))
            throw stop("OBSERVATION_OR_SOURCE_UNKNOWN");
    }
    private static boolean regionComplete(LiveRewardsTypes.Observation observation){
        var d=observation.data();return observation.task().kind()!=LiveRewardsTypes.Kind.CHECK_IN
                &&d.counterTarget()>0&&d.counterCurrent()==d.counterTarget();
    }
    private static boolean progressChanged(LiveRewardsTypes.Observation before,LiveRewardsTypes.Observation after){
        var a=before.data();var b=after.data();return before.task().kind()!=LiveRewardsTypes.Kind.CHECK_IN
                &&a.counterTarget()>0&&b.counterTarget()==a.counterTarget()
                &&a.counterCurrent()>=0&&b.counterCurrent()>a.counterCurrent();
    }
    static boolean sameTask(LiveRewardsTypes.Observation before,LiveRewardsTypes.Observation after){
        if(before==null||after==null||before.task().kind()!=after.task().kind()||!before.data().uniqueMatch()||!after.data().uniqueMatch())return false;
        var a=before.task();var b=after.task();
        if(a.kind()==LiveRewardsTypes.Kind.CHECK_IN)return a.day()>0&&a.day()==b.day()
                &&!a.titleHash().isEmpty()&&a.titleHash().equals(b.titleHash());
        return a.sameKey(b)||a.points()==b.points()&&!a.titleHash().isEmpty()&&a.titleHash().equals(b.titleHash());
    }
    private void emit(LiveRewardsTypes.Task task,LiveRewardsTypes.Outcome outcome,String safeReason,
            LiveRewardsTypes.Observation before,LiveRewardsTypes.Observation after)throws Exception{
        // Duplicate roster entries share a single immutable run result, but remain counted diagnostically.
        if(reported.add(task.key())){ledger.finish(task,outcome);ports.report(task,outcome,safeReason,before,after);}
        counts.put(outcome,counts.get(outcome)+1);
    }
    private void fail(String safeReason){
        stopped=true;reason=safeReason;authority.cancel();
        if(activeTask!=null&&!reported.contains(activeTask.key())){
            try{emit(activeTask,LiveRewardsTypes.Outcome.STOPPED,safeReason,activeBefore,activeAfter);}
            catch(Exception ignored){/* No further input or repair after an uncertain journal/report. */}
        }
    }
    private Summary summary(String value,boolean stop){return new Summary(regionsReady,selection.regionCount(),planned,counts,taps,backs,stop,value);}
    private static Stop stop(String reason){return new Stop(reason);}
    private static final class Stop extends Exception {
        private static final long serialVersionUID=1L;
        final String reason;Stop(String reason){this.reason=reason;}
    }
}
