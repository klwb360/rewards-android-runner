package com.aliothmoon.maafw.once;

import android.content.Context;
import android.os.SystemClock;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.json.JSONArray;

/** Production local-only UI -> reveal -> direct card crop -> native ROI OCR chain. */
final class LiveRewardsPerception {
    @FunctionalInterface interface ArtifactSink { void write(String key,String phase,String filename,byte[] payload)throws Exception; }
    @FunctionalInterface interface UiAttempt<T> { T read(boolean allowRecovery) throws Exception; }
    @FunctionalInterface interface ConsumeCropPermit { boolean consume() throws java.io.IOException; }
    @FunctionalInterface interface HistoryRead { boolean attempted(LiveRewardsTypes.Task seed) throws java.io.IOException; }
    @FunctionalInterface interface PreviewRead { LiveRewardsTypes.Observation read(LiveRewardsTypes.Task seed) throws Exception; }
    static final class RecoveredUiFrame extends Exception {
        private static final long serialVersionUID = 1L;
        private RecoveredUiFrame() {}
    }
    private record Frame(LiveRunAuthority.UiPermit permit,LiveUiProtocol.Report report,
            BingNavigationSnapshot.Ready before,long beforeAt,BingNavigationSnapshot.Ready after){}
    private record Located(Frame frame,LiveTaskMatcher.Match match,CardRevealProbeTarget.Selection selection,
            String reason,int swipes,long revealMillis){}
    record Roster(List<LiveRewardsTypes.Task> tasks,boolean known){}
    private static final class AfterExpired extends Exception {private static final long serialVersionUID=1L;}
    private final Context context;
    private final LiveRunAuthority authority;
    private final LiveRewardsNavigation navigation;
    private final BooleanSupplier hiddenCurrent;
    private final ArtifactSink sink;
    private final LivePreflightHistory history;
    private final ArrayDeque<JSONObject> scrollTrace = new ArrayDeque<>();
    private List<LiveRewardsTypes.Task> cohort=List.of();
    private boolean nativeClean=true,swipeClean=true;
    private volatile String safeState="NEW";
    private volatile LiveUiDiagnostic lastUiDiagnostic;
    private volatile String lastCropDiagnostic="NONE";
    private volatile LiveReadPreparation.Snapshot lastReadPreparation;
    private int readAttemptIndex;
    LiveRewardsPerception(Context context,LiveRunAuthority authority,LiveRewardsNavigation navigation,
            BooleanSupplier hiddenCurrent,ArtifactSink sink,LiveRewardsLedger ledger){
        if(context==null||authority==null||!authority.live()||navigation==null||hiddenCurrent==null||sink==null||ledger==null||!ledger.production()
                ||!authority.day().equals(ledger.day()))
            throw new IllegalArgumentException("Exact live perception owner required");
        this.context=context.getApplicationContext();this.authority=authority;this.navigation=navigation;
        this.hiddenCurrent=hiddenCurrent;this.sink=sink;history=new LivePreflightHistory(ledger,authority.day());
    }
    boolean knownClean(){return nativeClean&&AndroidLiveUiReader.knownClean()&&AndroidCardRegionCapture.knownClean()
            &&swipeClean&&navigation.knownClean();}
    String safeState(){return safeState;}
    String lastCropDiagnostic(){return lastCropDiagnostic;}
    JSONObject lastUiDiagnostic()throws org.json.JSONException{
        var exact=lastUiDiagnostic;return exact==null?null:new JSONObject(exact.json());
    }
    JSONObject lastReadPreparation()throws org.json.JSONException{
        try{var exact=lastReadPreparation;return exact==null?null:new JSONObject(exact.json());}
        catch(Throwable diagnosticUnavailable){return null;}
    }
    private LiveReadPreparation preparation(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,LiveRunAuthority.UiPermit permit){
        try{return new LiveReadPreparation(++readAttemptIndex,scope.task().kind().name(),phase==null?"UNKNOWN":phase.name(),permit.index(),scope.preflight(),scope.scan());}
        catch(Throwable diagnosticUnavailable){return null;}
    }
    private void state(String fixed){safeState=fixed!=null&&fixed.matches("[A-Z0-9_]{1,128}")?fixed:"INVALID_FIXED_STATE";}
    private static String safeNativeReason(String reason){return reason!=null&&reason.matches("[A-Z][A-Z0-9_]{0,79}")?reason:"INVALID_NATIVE_REASON";}
    private boolean current(LiveRunAuthority.CardScope scope){return scope!=null&&scope.run()==authority
            &&scope.current(now())&&hiddenCurrent.getAsBoolean()&&knownClean()&&!Thread.currentThread().isInterrupted();}
    private void require(LiveRunAuthority.CardScope scope){if(!current(scope))throw new SecurityException("Live perception authority unavailable");}
    private static long now(){return SystemClock.elapsedRealtime();}
    private static String nonce(){return UUID.randomUUID().toString().replace("-","");}
    private static boolean afterExpired(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase){
        return phase!=LiveRewardsTypes.Phase.BEFORE&&now()>=scope.observationDeadline(phase);
    }
    private static void timeBudget(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase)throws AfterExpired{
        if(afterExpired(scope,phase))throw new AfterExpired();
    }
    LiveRewardsTypes.Preflight preflight() throws Exception {
        List<LiveRewardsTypes.Task> tasks = new ArrayList<>();
        for (var kind : LiveRewardsTypes.Kind.values()) {
            var region = preflightRegion(authority, kind);
            tasks.addAll(region.tasks());
            if (!region.completeScan() || !region.knownClean() || !region.sourceReady()) {
                return new LiveRewardsTypes.Preflight(tasks, false, region.knownClean(), region.sourceReady());
            }
        }
        state("PREFLIGHT_COMPLETE");
        return new LiveRewardsTypes.Preflight(tasks, true, knownClean(), authority.current() && navigation.current());
    }

    LiveRewardsTypes.Preflight preflightRegion(LiveRunAuthority exact, LiveRewardsTypes.Kind kind) throws Exception {
        return preflightRegion(exact, kind, LiveRewardsTaskSelection.ALL);
    }

    LiveRewardsTypes.Preflight preflightRegion(LiveRunAuthority exact, LiveRewardsTypes.Kind kind,
            LiveRewardsTaskSelection selection) throws Exception {
        if (exact != authority || kind == null || selection == null || !selection.includes(kind)
                || !selection.current(authority.day())) throw new SecurityException("Exact preflight run and region required");
        state("PREFLIGHT_BEGIN_" + kind.name());
        cohort = List.of();
        if (kind == LiveRewardsTypes.Kind.CHECK_IN) {
            var checkIn = LivePreflightHistory.checkInSeed();
            if (attemptedHistory(checkIn)) {
                // An earlier attempt does not prove completion; bind this skip to a fresh Rewards UI read.
                readUi(authority.scanScope(kind), LiveRewardsTypes.Phase.BEFORE);
                return regionResult(List.of(checkIn), true, "PREFLIGHT_CHECK_IN_ALREADY_ATTEMPTED");
            }
        }
        Roster roster = scan(kind);
        if (!roster.known()) return regionResult(roster.tasks(), false, "PREFLIGHT_ROSTER_INCOMPLETE");
        cohort = roster.tasks();
        var result = recognizeRoster(cohort, selection, this::attemptedHistory,
                seed -> observe(authority.preflightScope(seed), LiveRewardsTypes.Phase.BEFORE, seed));
        return regionResult(result.tasks(), result.known(),
                (result.known() ? "PREFLIGHT_READY_" : "PREFLIGHT_CARD_UNRECOGNIZED_") + kind.name());
    }

    static Roster recognizeRoster(List<LiveRewardsTypes.Task> cohort, LiveRewardsTaskSelection selection,
            HistoryRead history, PreviewRead preview) throws Exception {
        List<LiveRewardsTypes.Task> recognized = new ArrayList<>();
        for (var seed : cohort) {
            // Retain every peer for duplicate/compact disambiguation. Without a selected preview
            // the structure supplies only its descriptor and points, never a verified title or tick.
            if (!selection.includes(seed) || !LiveTaskMatcher.uniqueInPlan(seed, cohort) || history.attempted(seed)) {
                recognized.add(LivePreflightHistory.unreadable(seed));
            } else {
                var observed = preview.read(seed);
                recognized.add(observed.task());
                if (!observed.qualified() || !observed.data().uniqueMatch() || !observed.task().sameKey(seed)) {
                    return new Roster(List.copyOf(recognized), false);
                }
            }
        }
        return new Roster(List.copyOf(recognized), true);
    }

    private LiveRewardsTypes.Preflight regionResult(List<LiveRewardsTypes.Task> tasks, boolean complete, String status) {
        cohort = List.copyOf(tasks);
        state(status);
        return new LiveRewardsTypes.Preflight(cohort, complete, knownClean(), authority.current() && navigation.current());
    }
    private boolean attemptedHistory(LiveRewardsTypes.Task seed)throws java.io.IOException{
        state("PREFLIGHT_HISTORY_QUERY_"+seed.kind().name());
        boolean attempted=history.attempted(seed);
        state("PREFLIGHT_HISTORY_"+(attempted?"ALREADY_ATTEMPTED_":"NO_PRIOR_ATTEMPT_")+seed.kind().name());
        return attempted;
    }
    private Roster scan(LiveRewardsTypes.Kind kind)throws Exception{
        var scope=authority.scanScope(kind);ReviewShizukuSwipeActor.SwipeTarget previous=null;
        for(int attempt=0;attempt<=8;attempt++){
            long generation = navigation.recoveryGeneration();
            Frame frame=readUi(scope,LiveRewardsTypes.Phase.BEFORE);var base=frame.report().base();
            if (navigation.recoveryGeneration() != generation) previous = null;
            var role=LiveTaskMatcher.role(kind);
            var region=region(base,role);boolean regionComplete=region!=null&&region.target()>0&&region.current()==region.target();
            if(kind!=LiveRewardsTypes.Kind.CHECK_IN&&regionComplete){
                publish(scope.task(),"PREFLIGHT","RESULT.json",new JSONObject().put("kind",kind.name()).put("outcome","REGION_COMPLETE_SKIP")
                        .put("counterCurrent",region.current()).put("counterTarget",region.target()).put("realExecutions",0).toString().getBytes(StandardCharsets.UTF_8));
                return new Roster(List.of(),true);
            }
            List<LiveRewardsTypes.Task> roster=new ArrayList<>();
            for(var item:frame.report().items())if(item.role()==role)roster.add(new LiveRewardsTypes.Task(kind,item.descriptorHash(),item.index(),"",
                    Math.max(0,item.points()),false,false,-1));
            if(LiveTaskMatcher.regionSnapshot(frame.report(),kind)!=null)
                return new Roster(List.copyOf(roster),true);
            var shape=frame.after().profile().shape();
            var activity=kind==LiveRewardsTypes.Kind.CHECK_IN?null:
                    RemainingCardTarget.select(base,shape.width(),shape.height(),new RemainingCardChoice(role,1),
                            previous==null?null:previous.direction());
            var selected=activity==null?CardRevealProbeTarget.select(base,shape.width(),shape.height()):activity.selection();
            if(attempt==8||selected.state()!=CardRevealProbeTarget.State.SCROLL)return new Roster(List.copyOf(roster),false);
            previous=activity==null?new ReviewShizukuSwipeActor.SwipeTarget(shape.width(),shape.height(),shape.rotation(),selected.direction()):
                    activity.swipeTarget(shape,previous);
            scroll(scope,frame,previous);pause(scope,250);
        }
        return new Roster(List.of(),false);
    }
    LiveRewardsTypes.Observation observe(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,
            LiveRewardsTypes.Task expected)throws Exception{
        require(scope);if(expected==null||!expected.sameKey(scope.task()))throw new SecurityException("Exact observed task required");
        try{
            timeBudget(scope,phase);Located found=locate(scope,phase,expected);
            if(found.selection()==null)return unreadable(scope,phase,expected,found.reason(),found.frame());
            return captureAndRecognize(scope,phase,expected,found);
        }catch(AfterExpired expired){return unreadable(scope,phase,expected,"AFTER_WINDOW_EXPIRED");}
    }
    private LiveRewardsTypes.Observation unreadable(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,
            LiveRewardsTypes.Task expected,String reason)throws Exception{
        return unreadable(scope,phase,expected,reason,null);
    }
    private LiveRewardsTypes.Observation unreadable(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,
            LiveRewardsTypes.Task expected,String reason,Frame frame)throws Exception{
        require(scope);var fresh=navigation.template();
        state("UNREADABLE_"+reason);
        var counter=frame==null?null:region(frame.report().base(),LiveTaskMatcher.role(expected.kind()));
        int current=counter==null?-1:counter.current(),target=counter==null?-1:counter.target();
        if(frame!=null&&(!frame.after().sameSource(fresh)||!NativeProgressPublication.frameFresh(now(),frame.report().base().source().started())))
            throw new SecurityException("Unreadable observation source changed");
        var result=scope.unreadable(phase,expected,fresh,now(),reason,current,target);
        publish(expected,phase(scope,phase),"RESULT.json",new JSONObject().put("kind",expected.kind().name()).put("taskHash",expected.key())
                .put("outcome","UNREADABLE").put("reason",reason).put("counterCurrent",current).put("counterTarget",target)
                .put("realExecutions",0).toString().getBytes(StandardCharsets.UTF_8));
        return result;
    }
    synchronized JSONArray activityScrollTrace() {
        return new JSONArray(scrollTrace);
    }

    private synchronized void recordActivityPosition(LiveRewardsTypes.Task task, String phase, int ordinal, int swipes,
            OverviewUiProtocol.Report report, int height, RemainingCardTarget.Result result,
            ReviewShizukuSwipeActor.SwipeTarget planned, boolean identityMatched) throws org.json.JSONException {
        Box card = null;
        if (identityMatched && report.cards() != null) {
            for (var item : report.cards().items()) {
                if (item.index() == ordinal && item.role() == LiveTaskMatcher.role(task.kind())) {
                    card = item.coarse();
                    break;
                }
            }
        }
        var view = report.overview() == null ? null : report.overview().viewport();
        var selection = result.selection();
        var sample = new JSONObject().put("kind",task.kind().name()).put("phase",phase).put("ordinal",ordinal)
                .put("swipes",swipes).put("state",selection.state().name())
                .put("direction",selection.direction()==null?"NONE":selection.direction().name())
                .put("plannedDistance",planned==null?0:planned.distance()).put("edgeGap",result.edgeGap())
                .put("cardTop",card==null?-1:card.top()).put("cardBottom",card==null?-1:card.bottom())
                .put("safeTop",view==null?-1:Math.max(view.top()+1,height/5))
                .put("safeBottom",view==null?-1:Math.min(view.bottom()-1,height*9/10));
        if (scrollTrace.size() == 96) scrollTrace.removeFirst();
        scrollTrace.addLast(sample);
    }

    private Located locate(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,LiveRewardsTypes.Task expected)throws Exception{
        CardRevealProbeTarget.Selection previousCandidate=null;ReviewShizukuSwipeActor.SwipeTarget previousSearch=null;
        LiveTaskMatcher.RegionSnapshot previousRoster=null;
        int swipes=0;long firstScroll=-1;
        long generation = navigation.recoveryGeneration();
        for(int count=0;count<20;count++){
            timeBudget(scope,phase);Frame frame=readUi(scope,phase);var base=frame.report().base();
            if (navigation.recoveryGeneration() != generation) {
                // Stability must be demonstrated by two fresh frames after reacquiring focus.
                previousCandidate = null;
                previousRoster = null;
                previousSearch = null;
                generation = navigation.recoveryGeneration();
            }
            var match=LiveTaskMatcher.find(frame.report(),expected,cohort);var shape=frame.after().profile().shape();
            if(match.state()==LiveTaskMatcher.State.AMBIGUOUS)return new Located(frame,match,null,"AMBIGUOUS_TASK",swipes,elapsed(firstScroll));
            var parent=region(base,LiveTaskMatcher.role(expected.kind()));
            if(expected.kind()!=LiveRewardsTypes.Kind.CHECK_IN&&parent!=null&&parent.target()>0&&parent.current()==parent.target())
                return new Located(frame,match,null,"REGION_COMPLETE_SKIP",swipes,elapsed(firstScroll));
            CardRevealProbeTarget.Selection selected;
            ReviewShizukuSwipeActor.SwipeTarget activityMove=null;
            if(expected.kind()==LiveRewardsTypes.Kind.CHECK_IN)selected=CardRevealProbeTarget.select(base,shape.width(),shape.height());
            else{
                // A missing identity is never replaced by the old ordinal. Ordinal 1 is search direction only.
                int ordinal=match.state()==LiveTaskMatcher.State.UNIQUE?match.item().index():1;
                var activity=RemainingCardTarget.select(base,shape.width(),shape.height(),new RemainingCardChoice(LiveTaskMatcher.role(expected.kind()),ordinal),
                        previousSearch==null?null:previousSearch.direction());
                selected=activity.selection();
                if(match.state()==LiveTaskMatcher.State.MISSING){
                    int known=expected.kind()==LiveRewardsTypes.Kind.DAILY?base.cards().dailyCount():base.cards().moreCount();
                    if(known>=0)return new Located(frame,match,null,"TASK_NOT_IN_CURRENT_ROSTER",swipes,elapsed(firstScroll));
                }
                if (selected.state()==CardRevealProbeTarget.State.SCROLL && swipes<8)
                    activityMove=activity.swipeTarget(shape,previousSearch);
                recordActivityPosition(expected,phase(scope,phase),ordinal,swipes,base,shape.height(),activity,
                        activityMove,match.state()==LiveTaskMatcher.State.UNIQUE);
            }
            if(selected.state()==CardRevealProbeTarget.State.CANDIDATE){
                if(match.state()!=LiveTaskMatcher.State.UNIQUE)return new Located(frame,match,null,"TASK_IDENTITY_UNAVAILABLE",swipes,elapsed(firstScroll));
                var roster = LiveTaskMatcher.regionSnapshot(frame.report(), expected.kind());
                if (roster == null) return new Located(frame,match,null,"REGION_ROSTER_INCOMPLETE",swipes,elapsed(firstScroll));
                if (scope.preflight() && !roster.matchesPreflight(cohort))
                    return new Located(frame,match,null,"PREFLIGHT_ROSTER_CHANGED",swipes,elapsed(firstScroll));
                if (!LiveTaskMatcher.boundCurrentMatch(frame.report(), expected, cohort, match))
                    return new Located(frame,match,null,"CURRENT_TASK_IDENTITY_AMBIGUOUS",swipes,elapsed(firstScroll));
                if(phase!=LiveRewardsTypes.Phase.BEFORE||roster.equals(previousRoster)&&selected.sameCandidate(previousCandidate))
                    return new Located(frame,match,selected,"READY",swipes,elapsed(firstScroll));
                previousCandidate=selected;previousRoster=roster;pause(scope,250);continue;
            }
            previousCandidate=null;
            previousRoster=null;
            if(selected.state()!=CardRevealProbeTarget.State.SCROLL||swipes>=8)
                return new Located(frame,match,null,selected.state().name(),swipes,elapsed(firstScroll));
            if(firstScroll<0)firstScroll=now();
            previousSearch=activityMove!=null?activityMove:
                    new ReviewShizukuSwipeActor.SwipeTarget(shape.width(),shape.height(),shape.rotation(),selected.direction());
            scroll(scope,frame,previousSearch);swipes++;pause(scope,250);
        }
        return new Located(null,null,null,"UI_OBSERVATION_LIMIT",swipes,elapsed(firstScroll));
    }
    private static long elapsed(long start){return start<0?0:Math.max(0,now()-start);}
    private Frame readUi(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase)throws Exception{
        return readUi(scope, phase, phase == LiveRewardsTypes.Phase.BEFORE);
    }

    private Frame readUi(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase, boolean allowRecovery)throws Exception{
        return readFreshUi(allowRecovery, recover -> readUiOnce(scope, phase, recover));
    }

    static <T> T readFreshUi(boolean allowRecovery, UiAttempt<T> attempt) throws Exception {
        try {
            return attempt.read(allowRecovery);
        } catch (RecoveredUiFrame stale) {
            if (!allowRecovery) throw stale;
            return attempt.read(false);
        }
    }

    static RecoveredUiFrame discardRecoveredUi(LiveRunAuthority.CardScope scope, LiveRunAuthority.UiPermit stale, boolean clean) {
        scope.discardUi(stale, clean);
        return new RecoveredUiFrame();
    }

    private Frame readUiOnce(LiveRunAuthority.CardScope scope, LiveRewardsTypes.Phase phase, boolean allowRecovery) throws Exception {
        lastReadPreparation=null;state("UI_SOURCE_BEFORE");
        require(scope);timeBudget(scope,phase);var permit=scope.claimUi(new Object(),now());
        BingNavigationSnapshot.Ready[] before={null};long[] beforeAt={-1};
        long generation = navigation.recoveryGeneration();
        BooleanSupplier withinBudget = () -> permit.current(now()) && current(scope)
                && now() < scope.observationDeadline(phase);
        var preparation=preparation(scope,phase,permit);
        var read=AndroidLiveUiReader.read(context,nonce(),Math.min(permit.deadline(),scope.observationDeadline(phase)),
                ()->current(scope),()->{
                    before[0] = allowRecovery ? navigation.templateForObservation(withinBudget) : navigation.template(withinBudget);
                    beforeAt[0]=now();if(preparation!=null)preparation.sourceGateReturned();
                    if (allowRecovery && navigation.recoveryGeneration() != generation) return false;
                    if(preparation!=null)preparation.enter(LiveReadPreparation.Stage.UI_READ_BURN);
                    boolean consumed=permit.consume(now());if(preparation!=null)preparation.uiReadBurnReturned(consumed);
                    if(consumed&&preparation!=null)preparation.enter(LiveReadPreparation.Stage.SOURCE_CURRENT);
                    return consumed&&permit.current(now())&&current(scope);
                },permit,preparation);
        lastReadPreparation=read.preparation();
        var report=read.protocolReport();
        var diagnostic=LiveUiDiagnostic.from(read);lastUiDiagnostic=diagnostic;state(diagnostic.safeState());
        if(!read.knownClean())throw new SecurityException("Live UI cleanup unconfirmed");
        if (allowRecovery && navigation.recoveryGeneration() != generation && !read.invoked()
                && read.fixedStatus() == AndroidLiveUiReader.FixedStatus.GATE_REJECTED) {
            throw discardRecoveredUi(scope, permit, knownClean());
        }
        if(LivePerceptionBudget.ui(read.fixedStatus(),report==null?null:report.base().source().reason(),true,afterExpired(scope,phase))){
            scope.discardUi(permit,true);throw new AfterExpired();
        }
        if(read.fixedStatus()!=AndroidLiveUiReader.FixedStatus.READ_COMPLETE||report==null||!report.qualified())
            throw new SecurityException("Live UI rejected: "+read.fixedStatus().name());
        if(afterExpired(scope,phase)){scope.discardUi(permit,true);throw new AfterExpired();}
        state("UI_SOURCE_AFTER");
        var after = allowRecovery ? navigation.templateForObservation(withinBudget) : navigation.template(() -> current(scope));
        long observed=now();
        if (allowRecovery && navigation.recoveryGeneration() != generation) {
            // The tree and its bounds were acquired across a focus gap; only a new bracket may supply a card or scroll.
            throw discardRecoveredUi(scope, permit, knownClean());
        }
        if(afterExpired(scope,phase)){scope.discardUi(permit,true);throw new AfterExpired();}
        if(!CardRevealProbeSource.ui(report.base(),before[0],beforeAt[0],after,after.profile().shape(),observed))
            throw new SecurityException("Live UI source bracket rejected");
        scope.finishUi(permit,true,true,observed);authority.bindTemplate(after,observed);
        state("UI_QUALIFIED");
        return new Frame(permit,report,before[0],beforeAt[0],after);
    }
    private void scroll(LiveRunAuthority.CardScope scope,Frame frame,ReviewShizukuSwipeActor.SwipeTarget target)throws Exception{
        state("SCROLL_BEGIN");
        require(scope);if(frame==null||target==null)throw new SecurityException("Current scroll frame required");
        var source=frame.after();var shape=source.profile().shape();
        if(target.fullDisplayWidth()!=shape.width()||target.fullDisplayHeight()!=shape.height()||target.rotation()!=shape.rotation())
            throw new SecurityException("Scroll target requires the current display");
        var permit=scope.claimScroll(frame.permit(),target,now());
        var actor=ReviewShizukuSwipeActor.forLiveRun(context,permit);
        var result=actor.swipe(target,new ReviewShizukuSwipeActor.DispatchGate(){
            private boolean valid(){return permit.current(now())&&current(scope)&&source.sameSource(navigation.template(()->permit.current(now())&&current(scope)));}
            public boolean consume(ReviewShizukuSwipeActor.SwipeTarget exact)throws Exception{return exact==target&&valid()&&permit.consume(now())&&permit.current(now());}
            public boolean stillValid(ReviewShizukuSwipeActor.SwipeTarget exact){return exact==target&&valid()&&permit.dispatchValid(now());}
        });
        state("SCROLL_"+(result==null?"NO_RESULT":result.reason().name()));
        swipeClean&=actor.knownClean();boolean clean=swipeClean&&knownClean();scope.finishScroll(permit,result,clean,now());
        if(!source.sameSource(navigation.template()))throw new SecurityException("Scroll changed source");
    }
    static AndroidCardRegionCapture.Gate cropGate(BooleanSupplier fresh, BingNavigationSnapshot.Ready imageBefore,
            Supplier<BingNavigationSnapshot.Ready> readCurrent, ConsumeCropPermit consumePermit) {
        return cropGate(fresh,imageBefore,readCurrent,consumePermit,()->false);
    }
    static AndroidCardRegionCapture.Gate cropGate(BooleanSupplier fresh, BingNavigationSnapshot.Ready imageBefore,
            Supplier<BingNavigationSnapshot.Ready> readCurrent, ConsumeCropPermit consumePermit, BooleanSupplier onlyExpired) {
        return new AndroidCardRegionCapture.Gate() {
            private void requireFresh() {
                if(fresh.getAsBoolean())return;
                if(onlyExpired.getAsBoolean())throw new AndroidCardRegionCapture.InsufficientBudget();
                throw new SecurityException("Live crop source changed");
            }
            public void requireCurrent() {
                requireFresh();
                if (!imageBefore.sameSource(readCurrent.get()))
                    throw new SecurityException("Live crop source changed");
                requireFresh();
            }
            public boolean consume() throws java.io.IOException {
                return consumeWithin(()->true);
            }
            public boolean consumeWithin(BooleanSupplier mayDispatch) throws java.io.IOException {
                requireCurrent();
                if(!mayDispatch.getAsBoolean())throw new AndroidCardRegionCapture.InsufficientBudget();
                if(!consumePermit.consume())return false;
                requireFresh();
                return true;
            }
        };
    }

    private LiveRewardsTypes.Observation captureAndRecognize(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,
            LiveRewardsTypes.Task expected,Located found)throws Exception{
        Frame frame=found.frame();var base=frame.report().base();var selected=found.selection();var roi=selected.crop();
        state("CROP_SOURCE_BEFORE");
        var imageBefore=navigation.template();var shape=imageBefore.profile().shape();long lower=now();
        timeBudget(scope,phase);var permit=scope.claimCrop(frame.permit(),phase,now());
        BooleanSupplier fresh=()->current(scope)&&permit.current(now())&&CardRevealProbeSource.beforeCrop(base,frame.before(),frame.beforeAt(),
                frame.after(),imageBefore,shape,lower,permit.deadline(),now());
        BooleanSupplier onlyExpired=()->{
            long at=now();
            return phase!=LiveRewardsTypes.Phase.BEFORE&&current(scope)&&permit.activeForRetirement(at)
                    &&CardRevealProbeSource.expiredBeforeCrop(base,frame.before(),frame.beforeAt(),frame.after(),
                    imageBefore,shape,lower,permit.deadline(),at);
        };
        if(!fresh.getAsBoolean()&&!onlyExpired.getAsBoolean())throw new SecurityException("Live crop source-before rejected");
        var read=AndroidCardRegionCapture.capture(context,nonce(),Math.min(permit.deadline(),scope.observationDeadline(phase)),roi,
                shape.width(),shape.height(),shape.rotation(),permit,()->current(scope),
                cropGate(fresh,imageBefore,()->navigation.template(fresh),()->permit.consume(now()),onlyExpired));
        lastCropDiagnostic=read.diagnostic();
        state("CROP_"+read.status().name()+"_"+(read.helperStatus()==null?"NONE":read.helperStatus().name()));
        byte[] png=read.png(),nativePng=null;
        try{
            if(LivePerceptionBudget.retireCrop(phase,read.status(),read.helperStatus(),read.knownClean(),afterExpired(scope,phase))){
                scope.discardCrop(permit,true);
                return unreadable(scope,phase,expected,"AFTER_CROP_BUDGET_EXHAUSTED",frame);
            }
            if(!read.knownClean()||read.status()!=AndroidCardRegionCapture.FixedStatus.CAPTURED||png==null)
                throw new SecurityException("Live crop rejected: "+read.status().name());
            if(afterExpired(scope,phase)){scope.discardCrop(permit,true);throw new AfterExpired();}
            state("CROP_SOURCE_AFTER");var imageAfter=navigation.template();
            if(!CardRevealProbeSource.afterCrop(base,frame.before(),frame.beforeAt(),frame.after(),imageBefore,imageAfter,shape,lower,
                    read.helperStartedMs(),read.helperFinishedMs(),permit.deadline(),now()))throw new SecurityException("Live crop source-after rejected");
            if(afterExpired(scope,phase)){scope.discardCrop(permit,true);throw new AfterExpired();}
            scope.finishCrop(permit,true,true,now());
            String hash=CardNativeMockReceipt.digest(png);String stage=phase(scope,phase);
            publish(expected,stage,"CARD.png",png);
            publish(expected,stage,"CAPTURE.json",new JSONObject().put("taskHash",expected.key()).put("captureHash",hash)
                    .put("captureSource","REAL_DIRECT_CROP").put("left",roi.left()).put("top",roi.top()).put("right",roi.right()).put("bottom",roi.bottom())
                    .put("width",roi.width()).put("height",roi.height()).put("swipes",found.swipes()).put("revealMillis",found.revealMillis())
                    .put("fullScreenCaptures",0).put("helperStarted",read.helperStartedMs()).put("helperFinished",read.helperFinishedMs())
                    .toString().getBytes(StandardCharsets.UTF_8));
            // This receipt proves only the exact newly committed card crop; the opaque scope seals live evidence later.
            var receipt=CardNativeMockReceipt.seal(scope.owner(),hash,roi.width(),roi.height());nativePng=png.clone();
            var spec=expected.kind()==LiveRewardsTypes.Kind.CHECK_IN?CardRecognitionSpec.checkIn(expected.day()<1?0:expected.day()):
                    CardRecognitionSpec.activity(LiveTaskMatcher.role(expected.kind()),found.match().item().index());
            nativeClean=false;
            state("NATIVE_BEGIN");LiveCardRecognizer.Result nativeResult;
            try{nativeResult=LiveCardRecognizer.recognize(context,receipt,scope.owner(),nativePng,spec,
                        ()->scope.current(now())&&hiddenCurrent.getAsBoolean()&&authority.current()
                                &&!Thread.currentThread().isInterrupted()&&now()-lower<=LiveRunAuthority.MAX_FRAME_AGE
                                &&(phase==LiveRewardsTypes.Phase.BEFORE||now()<scope.observationDeadline(phase)));
            }catch(RuntimeException|Error failed){state("NATIVE_RESOURCES_UNCONFIRMED");throw failed;}
            nativeClean=true;nativePng=null;
            state("NATIVE_"+safeNativeReason(nativeResult.reason));
            try(nativeResult){
                state("NATIVE_SOURCE_AFTER");
                var source=navigation.template();
                if(!imageAfter.sameSource(source))throw new SecurityException("Native recognition source changed");
                if(afterExpired(scope,phase))throw new AfterExpired();
                if(now()-read.helperStartedMs()>LiveRunAuthority.MAX_FRAME_AGE)return unreadable(scope,phase,expected,"NATIVE_FRAME_EXPIRED");
                var outcome=nativeResult.outcome;
                boolean qualified=nativeResult.qualified()&&hash.equals(nativeResult.captureHash)&&nativeResult.width==roi.width()&&nativeResult.height==roi.height();
                String admissionReason=safeNativeReason(nativeResult.reason);
                int points=outcome==null?expected.points():outcome.displayedPoints()>0?outcome.displayedPoints():expected.points();
                if(expected.kind()!=LiveRewardsTypes.Kind.CHECK_IN&&points!=expected.points()){qualified=false;admissionReason="TASK_POINTS_CHANGED";}
                int day=expected.kind()==LiveRewardsTypes.Kind.CHECK_IN&&outcome!=null&&outcome.day()>0?outcome.day():expected.day();
                if(expected.kind()==LiveRewardsTypes.Kind.CHECK_IN&&expected.day()>0&&day!=expected.day()){qualified=false;admissionReason="CHECK_IN_DAY_CHANGED";}
                String title=nativeResult.titleHash;
                if(qualified&&!LiveTaskMatcher.nativeTitleMatches(expected,title)){qualified=false;admissionReason="TASK_TITLE_HASH_CHANGED";}
                if(title==null||title.isEmpty())title=expected.titleHash();
                // A title-only compact match must also be confirmed again by fresh native OCR.
                if(found.match().compact()&&!expected.titleHash().equals(title)){qualified=false;admissionReason="COMPACT_TITLE_HASH_CHANGED";}
                if(qualified&&found.match().compact()&&!nativeResult.complete()){qualified=false;admissionReason="COMPACT_TICK_UNCONFIRMED";}
                boolean complete=qualified&&nativeResult.complete();
                var actual=new LiveRewardsTypes.Task(expected.kind(),expected.descriptorHash(),found.match().item().index(),title,
                        expected.kind()==LiveRewardsTypes.Kind.CHECK_IN?points:expected.points(),qualified,complete,day);
                var counter=region(base,LiveTaskMatcher.role(expected.kind()));int c=counter==null?-1:counter.current(),t=counter==null?-1:counter.target();
                Box target=qualified&&!complete?outcome.target():null;double confidence=qualified?outcome.confidence():-1;
                var data=new LiveRewardsTypes.ObservationData(actual,source,read.helperStartedMs(),roi,roi.width(),roi.height(),target,confidence,hash,
                        qualified,true,true,c,t,LiveTaskMatcher.regionSnapshot(frame.report(),expected.kind()));
                var observation=scope.sealObservation(permit,data);
                state("NATIVE_"+safeNativeReason(admissionReason));
                if(nativeResult.diagnostics!=null)publish(expected,stage,"DIAGNOSTICS.json",nativeResult.diagnostics.getBytes(StandardCharsets.UTF_8));
                byte[] marked=nativeResult.takeMarked();try{if(marked!=null)publish(expected,stage,"MARKED.png",marked);}finally{if(marked!=null)Arrays.fill(marked,(byte)0);}
                publish(expected,stage,"RESULT.json",new JSONObject().put("taskHash",actual.key()).put("descriptorHash",actual.descriptorHash())
                        .put("titleHash",actual.titleHash()).put("kind",actual.kind().name()).put("ordinal",actual.ordinal()).put("points",actual.points())
                        .put("day",actual.day()).put("qualified",qualified).put("complete",complete).put("reason",admissionReason)
                        .put("nativeMillis",nativeResult.nativeMillis).put("queries",nativeResult.queries).put("captureHash",hash)
                        .put("realExecutions",0).toString().getBytes(StandardCharsets.UTF_8));
                return observation;
            }
        }finally{if(png!=null)Arrays.fill(png,(byte)0);if(nativePng!=null)Arrays.fill(nativePng,(byte)0);}
    }
    /** Final input checks use a new source-bracketed tree and never an old diagram or ordinal. */
    boolean rebind(LiveActionPermit permit)throws Exception{
        state("TAP_REBIND_BEGIN");
        if(permit==null||permit.scope().run()!=authority||!permit.live()||!permit.current(now()))return false;
        var frame=readUi(permit.scope(),LiveRewardsTypes.Phase.BEFORE,false);var before=permit.before();
        if(!permit.matchesBeforeSource(frame.after()))throw new SecurityException("Live tap source changed");
        var shape=frame.after().profile().shape();
        var binding=tapBinding(frame.report(),before.task(),cohort,before.data().regionSnapshot(),
                before.data().screenRoi(),shape.width(),shape.height());
        state("TAP_REBIND_"+binding.name());
        return binding==TapBinding.QUALIFIED&&permit.current(now())&&current(permit.scope());
    }

    enum TapBinding { QUALIFIED, MISSING, AMBIGUOUS, COMPACT, ROSTER_CHANGED_SINCE_CROP,
        TASK_IDENTITY_CHANGED, REGION_COMPLETE, GEOMETRY_CHANGED }

    static TapBinding tapBinding(LiveUiProtocol.Report current, LiveRewardsTypes.Task expected,
            List<LiveRewardsTypes.Task> cohort, LiveTaskMatcher.RegionSnapshot capturedRoster,
            Box capturedRoi, int width, int height) {
        var match = LiveTaskMatcher.find(current, expected, cohort);
        if (match.state() == LiveTaskMatcher.State.MISSING) return TapBinding.MISSING;
        if (match.state() == LiveTaskMatcher.State.AMBIGUOUS) return TapBinding.AMBIGUOUS;
        if (match.compact()) return TapBinding.COMPACT;
        var roster = LiveTaskMatcher.regionSnapshot(current, expected.kind());
        if (roster == null || !roster.equals(capturedRoster)) return TapBinding.ROSTER_CHANGED_SINCE_CROP;
        if (!LiveTaskMatcher.admits(current,expected,cohort) || match.item().index()!=expected.ordinal())
            return TapBinding.TASK_IDENTITY_CHANGED;
        var role=LiveTaskMatcher.role(expected.kind());
        var parent=region(current.base(),role);
        if (expected.kind()!=LiveRewardsTypes.Kind.CHECK_IN && parent!=null && parent.target()>0 && parent.current()==parent.target())
            return TapBinding.REGION_COMPLETE;
        var selected=expected.kind()==LiveRewardsTypes.Kind.CHECK_IN?
                CardRevealProbeTarget.select(current.base(),width,height):RemainingCardTarget.select(current.base(),width,height,
                        new RemainingCardChoice(role,match.item().index())).selection();
        return selected.state()==CardRevealProbeTarget.State.CANDIDATE && selected.crop().equals(capturedRoi)
                ? TapBinding.QUALIFIED : TapBinding.GEOMETRY_CHANGED;
    }
    /** Called only after the three-second passive wait. The helper itself is UI-read-only. */
    LiveVisitGuard.Report visitGuard(LiveActionPermit action)throws Exception{
        lastReadPreparation=null;state("VISIT_BEGIN");
        if(action==null||action.scope().run()!=authority||!action.live()||action.kind()==LiveRewardsTypes.Kind.CHECK_IN
                ||action.successfulTapElapsed()<0||now()-action.successfulTapElapsed()<3_000)throw new SecurityException("Due bound visit required");
        var scope=action.scope();require(scope);var permit=scope.claimUi(new Object(),now());
        BingNavigationSnapshot.LiveVisitSource[] before={null};long[] beforeAt={-1};
        var preparation=preparation(scope,null,permit);
        var read=AndroidLiveUiReader.readVisit(context,nonce(),permit.deadline(),()->current(scope),()->{
            var visit=navigation.visit(action.source());before[0]=visit.source();beforeAt[0]=now();
            if(before[0]==null||!before[0].fresh(beforeAt[0]))throw new SecurityException("Bound visit source unavailable");
            if(preparation!=null)preparation.sourceGateReturned();if(preparation!=null)preparation.enter(LiveReadPreparation.Stage.UI_READ_BURN);
            boolean consumed=permit.consume(now());if(preparation!=null)preparation.uiReadBurnReturned(consumed);
            if(consumed&&preparation!=null)preparation.enter(LiveReadPreparation.Stage.SOURCE_CURRENT);
            return consumed&&permit.current(now())&&current(scope);
        },permit,preparation);
        lastReadPreparation=read.preparation();
        var report=read.visitReport();
        state("VISIT_"+read.fixedStatus().name()+"_"+(report==null?"NONE":report.source().reason().name()+"_"+report.hint().name()));
        if(!read.knownClean()||read.fixedStatus()!=AndroidLiveUiReader.FixedStatus.READ_COMPLETE||report==null||!report.source().qualified())
            throw new SecurityException("Visit UI source or cleanup rejected");
        state("VISIT_SOURCE_AFTER");var after=navigation.visit(action.source());var source=after.source();var nativeSource=report.source();
        if(before[0]==null||source==null||!before[0].sameSource(source)||!source.fresh(now())
                ||before[0].ready().observed()>beforeAt[0]||beforeAt[0]>nativeSource.started()
                ||nativeSource.finished()>source.ready().lower())throw new SecurityException("Visit source bracket changed");
        var shape=source.ready().profile().shape();
        if(nativeSource.displayId()!=0||nativeSource.left()<0||nativeSource.top()<0||nativeSource.right()>shape.width()||nativeSource.bottom()>shape.height()
                ||nativeSource.right()<=nativeSource.left()||nativeSource.bottom()<=nativeSource.top()
                ||nativeSource.rotationBefore()!=-1&&nativeSource.rotationBefore()!=shape.rotation()
                ||nativeSource.rotationAfter()!=-1&&nativeSource.rotationAfter()!=shape.rotation())throw new SecurityException("Visit UI geometry changed");
        scope.finishUi(permit,true,true,now());state("VISIT_QUALIFIED_"+report.hint().name());return report;
    }
    private void pause(LiveRunAuthority.CardScope scope,long millis)throws InterruptedException{require(scope);Thread.sleep(millis);require(scope);}
    private void publish(LiveRewardsTypes.Task task,String phase,String filename,byte[] payload)throws Exception{
        if(!knownClean()||!authority.current()||!hiddenCurrent.getAsBoolean())throw new SecurityException("Local artifact publication rejected");
        sink.write(task.key(),phase,filename,payload);
    }
    private static String phase(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase){return scope.preflight()?"PREFLIGHT":phase.name();}
    private static OverviewUiRegions.Region region(OverviewUiProtocol.Report base,OverviewUiRegions.Role role){
        OverviewUiRegions.Region result=null;for(var region:base.overview().regions())if(region.role()==role){if(result!=null)throw new SecurityException("Ambiguous live region");result=region;}return result;
    }
}
