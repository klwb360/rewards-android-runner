package com.aliothmoon.maafw.once;

import android.content.Context;
import android.os.SystemClock;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Production local-only UI -> reveal -> direct card crop -> native ROI OCR chain. */
final class LiveRewardsPerception {
    @FunctionalInterface interface ArtifactSink { void write(String key,String phase,String filename,byte[] payload)throws Exception; }
    private record Frame(LiveRunAuthority.UiPermit permit,LiveUiProtocol.Report report,
            BingNavigationSnapshot.Ready before,long beforeAt,BingNavigationSnapshot.Ready after){}
    private record Located(Frame frame,LiveTaskMatcher.Match match,CardRevealProbeTarget.Selection selection,
            String reason,int swipes,long revealMillis){}
    private record Roster(List<LiveRewardsTypes.Task> tasks,boolean known){}
    private static final class AfterExpired extends Exception {private static final long serialVersionUID=1L;}
    private final Context context;
    private final LiveRunAuthority authority;
    private final LiveRewardsNavigation navigation;
    private final BooleanSupplier hiddenCurrent;
    private final ArtifactSink sink;
    private final LivePreflightHistory history;
    private List<LiveRewardsTypes.Task> cohort=List.of();
    private boolean nativeClean=true,swipeClean=true;
    private volatile String safeState="NEW";
    private volatile LiveUiDiagnostic lastUiDiagnostic;
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
    LiveRewardsTypes.Preflight preflight()throws Exception{return preflight(authority);}
    LiveRewardsTypes.Preflight preflight(LiveRunAuthority exact)throws Exception{
        state("PREFLIGHT_BEGIN");
        if(exact!=authority)throw new SecurityException("Exact preflight run required");
        List<LiveRewardsTypes.Task> result=new ArrayList<>();boolean complete=true;
        for(var kind:LiveRewardsTypes.Kind.values()){
            if(kind==LiveRewardsTypes.Kind.CHECK_IN){
                var seed=LivePreflightHistory.checkInSeed();
                if(attemptedHistory(seed)){
                    // A prior attempt supplies no UI/image authority. Still require one fresh qualified
                    // typed UI/source bracket, without searching for a possibly collapsed check-in card.
                    readUi(authority.scanScope(kind),LiveRewardsTypes.Phase.BEFORE);
                    result.add(seed);state("PREFLIGHT_CHECK_IN_ALREADY_ATTEMPTED");continue;
                }
            }
            Roster roster=scan(kind);complete&=roster.known();
            Map<String,Integer> duplicates=new HashMap<>();for(var task:roster.tasks())duplicates.merge(task.key(),1,Integer::sum);
            for(var seed:roster.tasks()){
                if(attemptedHistory(seed)){result.add(LivePreflightHistory.unreadable(seed));continue;}
                if(seed.complete()){result.add(seed);continue;}
                if(duplicates.get(seed.key())!=1){result.add(seed);continue;}
                var observed=observe(authority.preflightScope(seed),LiveRewardsTypes.Phase.BEFORE,seed);
                result.add(observed.task());
            }
        }
        cohort=List.copyOf(result);
        state(complete?"PREFLIGHT_COMPLETE":"PREFLIGHT_INCOMPLETE");
        return new LiveRewardsTypes.Preflight(cohort,complete,knownClean(),authority.current()&&navigation.current());
    }
    private boolean attemptedHistory(LiveRewardsTypes.Task seed)throws java.io.IOException{
        state("PREFLIGHT_HISTORY_QUERY_"+seed.kind().name());
        boolean attempted=history.attempted(seed);
        state("PREFLIGHT_HISTORY_"+(attempted?"ALREADY_ATTEMPTED_":"NO_PRIOR_ATTEMPT_")+seed.kind().name());
        return attempted;
    }
    private Roster scan(LiveRewardsTypes.Kind kind)throws Exception{
        var scope=authority.scanScope(kind);ReviewShizukuSwipeActor.Direction previous=null;
        for(int attempt=0;attempt<=8;attempt++){
            Frame frame=readUi(scope,LiveRewardsTypes.Phase.BEFORE);var base=frame.report().base();
            var role=LiveTaskMatcher.role(kind);int count=kind==LiveRewardsTypes.Kind.CHECK_IN?0:
                    kind==LiveRewardsTypes.Kind.DAILY?base.cards().dailyCount():base.cards().moreCount();
            var region=region(base,role);boolean regionComplete=region!=null&&region.target()>0&&region.current()==region.target();
            List<LiveRewardsTypes.Task> roster=new ArrayList<>();
            for(var item:frame.report().items())if(item.role()==role)roster.add(new LiveRewardsTypes.Task(kind,item.descriptorHash(),item.index(),"",
                    Math.max(0,item.points()),regionComplete,regionComplete,-1));
            if(kind==LiveRewardsTypes.Kind.CHECK_IN&&!roster.isEmpty()||kind!=LiveRewardsTypes.Kind.CHECK_IN&&count>=0&&roster.size()==count)
                return new Roster(List.copyOf(roster),true);
            if(regionComplete){
                publish(scope.task(),"PREFLIGHT","RESULT.json",new JSONObject().put("kind",kind.name()).put("outcome","REGION_COMPLETE_SKIP")
                        .put("counterCurrent",region.current()).put("counterTarget",region.target()).put("realExecutions",0).toString().getBytes(StandardCharsets.UTF_8));
                return new Roster(List.of(),true);
            }
            var shape=frame.after().profile().shape();
            var selected=kind==LiveRewardsTypes.Kind.CHECK_IN?CardRevealProbeTarget.select(base,shape.width(),shape.height()):
                    RemainingCardTarget.select(base,shape.width(),shape.height(),new RemainingCardChoice(role,1),previous).selection();
            if(attempt==8||selected.state()!=CardRevealProbeTarget.State.SCROLL)return new Roster(List.copyOf(roster),false);
            previous=selected.direction();scroll(scope,frame,previous);pause(scope,250);
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
    private Located locate(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,LiveRewardsTypes.Task expected)throws Exception{
        CardRevealProbeTarget.Selection previousCandidate=null;ReviewShizukuSwipeActor.Direction previousSearch=null;
        int swipes=0;long firstScroll=-1;
        for(int count=0;count<20;count++){
            timeBudget(scope,phase);Frame frame=readUi(scope,phase);var base=frame.report().base();
            var match=LiveTaskMatcher.find(frame.report(),expected,cohort);var shape=frame.after().profile().shape();
            if(match.state()==LiveTaskMatcher.State.AMBIGUOUS)return new Located(frame,match,null,"AMBIGUOUS_TASK",swipes,elapsed(firstScroll));
            var parent=region(base,LiveTaskMatcher.role(expected.kind()));
            if(expected.kind()!=LiveRewardsTypes.Kind.CHECK_IN&&parent!=null&&parent.target()>0&&parent.current()==parent.target())
                return new Located(frame,match,null,"REGION_COMPLETE_SKIP",swipes,elapsed(firstScroll));
            CardRevealProbeTarget.Selection selected;
            if(expected.kind()==LiveRewardsTypes.Kind.CHECK_IN)selected=CardRevealProbeTarget.select(base,shape.width(),shape.height());
            else{
                // A missing identity is never replaced by the old ordinal. Ordinal 1 is search direction only.
                int ordinal=match.state()==LiveTaskMatcher.State.UNIQUE?match.item().index():1;
                var located=RemainingCardTarget.select(base,shape.width(),shape.height(),new RemainingCardChoice(LiveTaskMatcher.role(expected.kind()),ordinal),previousSearch);
                selected=located.selection();
                if(match.state()==LiveTaskMatcher.State.MISSING){
                    int known=expected.kind()==LiveRewardsTypes.Kind.DAILY?base.cards().dailyCount():base.cards().moreCount();
                    if(known>=0)return new Located(frame,match,null,"TASK_NOT_IN_CURRENT_ROSTER",swipes,elapsed(firstScroll));
                }
            }
            if(selected.state()==CardRevealProbeTarget.State.CANDIDATE){
                if(match.state()!=LiveTaskMatcher.State.UNIQUE)return new Located(frame,match,null,"TASK_IDENTITY_UNAVAILABLE",swipes,elapsed(firstScroll));
                if(phase!=LiveRewardsTypes.Phase.BEFORE||selected.sameCandidate(previousCandidate))
                    return new Located(frame,match,selected,"READY",swipes,elapsed(firstScroll));
                previousCandidate=selected;pause(scope,250);continue;
            }
            previousCandidate=null;
            if(selected.state()!=CardRevealProbeTarget.State.SCROLL||swipes>=8)
                return new Located(frame,match,null,selected.state().name(),swipes,elapsed(firstScroll));
            if(firstScroll<0)firstScroll=now();previousSearch=selected.direction();
            scroll(scope,frame,previousSearch);swipes++;pause(scope,250);
        }
        return new Located(null,null,null,"UI_OBSERVATION_LIMIT",swipes,elapsed(firstScroll));
    }
    private static long elapsed(long start){return start<0?0:Math.max(0,now()-start);}
    private Frame readUi(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase)throws Exception{
        lastReadPreparation=null;state("UI_SOURCE_BEFORE");
        require(scope);timeBudget(scope,phase);var permit=scope.claimUi(new Object(),now());
        BingNavigationSnapshot.Ready[] before={null};long[] beforeAt={-1};
        var preparation=preparation(scope,phase,permit);
        var read=AndroidLiveUiReader.read(context,nonce(),Math.min(permit.deadline(),scope.observationDeadline(phase)),
                ()->current(scope),()->{
                    before[0]=navigation.template(()->permit.current(now())&&current(scope));beforeAt[0]=now();if(preparation!=null)preparation.sourceGateReturned();
                    if(preparation!=null)preparation.enter(LiveReadPreparation.Stage.UI_READ_BURN);
                    boolean consumed=permit.consume(now());if(preparation!=null)preparation.uiReadBurnReturned(consumed);
                    if(consumed&&preparation!=null)preparation.enter(LiveReadPreparation.Stage.SOURCE_CURRENT);
                    return consumed&&permit.current(now())&&current(scope);
                },permit,preparation);
        lastReadPreparation=read.preparation();
        var report=read.protocolReport();
        var diagnostic=LiveUiDiagnostic.from(read);lastUiDiagnostic=diagnostic;state(diagnostic.safeState());
        if(!read.knownClean())throw new SecurityException("Live UI cleanup unconfirmed");
        if(LivePerceptionBudget.ui(read.fixedStatus(),report==null?null:report.base().source().reason(),true,afterExpired(scope,phase))){
            scope.discardUi(permit,true);throw new AfterExpired();
        }
        if(read.fixedStatus()!=AndroidLiveUiReader.FixedStatus.READ_COMPLETE||report==null||!report.qualified())
            throw new SecurityException("Live UI rejected: "+read.fixedStatus().name());
        if(afterExpired(scope,phase)){scope.discardUi(permit,true);throw new AfterExpired();}
        state("UI_SOURCE_AFTER");var after=navigation.template(()->current(scope));long observed=now();
        if(afterExpired(scope,phase)){scope.discardUi(permit,true);throw new AfterExpired();}
        if(!CardRevealProbeSource.ui(report.base(),before[0],beforeAt[0],after,after.profile().shape(),observed))
            throw new SecurityException("Live UI source bracket rejected");
        scope.finishUi(permit,true,true,observed);authority.bindTemplate(after,observed);
        state("UI_QUALIFIED");
        return new Frame(permit,report,before[0],beforeAt[0],after);
    }
    private void scroll(LiveRunAuthority.CardScope scope,Frame frame,ReviewShizukuSwipeActor.Direction direction)throws Exception{
        state("SCROLL_BEGIN");
        require(scope);if(frame==null||direction==null)throw new SecurityException("Current scroll frame required");
        var source=frame.after();var shape=source.profile().shape();var permit=scope.claimScroll(frame.permit(),direction,now());
        var target=new ReviewShizukuSwipeActor.SwipeTarget(shape.width(),shape.height(),shape.rotation(),direction);
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
    private LiveRewardsTypes.Observation captureAndRecognize(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,
            LiveRewardsTypes.Task expected,Located found)throws Exception{
        Frame frame=found.frame();var base=frame.report().base();var selected=found.selection();var roi=selected.crop();
        state("CROP_SOURCE_BEFORE");
        var imageBefore=navigation.template();var shape=imageBefore.profile().shape();long lower=now();
        timeBudget(scope,phase);var permit=scope.claimCrop(frame.permit(),phase,now());
        BooleanSupplier fresh=()->current(scope)&&permit.current(now())&&CardRevealProbeSource.beforeCrop(base,frame.before(),frame.beforeAt(),
                frame.after(),imageBefore,shape,lower,permit.deadline(),now());
        if(!fresh.getAsBoolean())throw new SecurityException("Live crop source-before rejected");
        var read=AndroidCardRegionCapture.capture(context,nonce(),Math.min(permit.deadline(),scope.observationDeadline(phase)),roi,
                shape.width(),shape.height(),shape.rotation(),permit,()->current(scope),new AndroidCardRegionCapture.Gate(){
                    public boolean consume()throws Exception{requireCurrent();return permit.consume(now())&&fresh.getAsBoolean();}
                    public void requireCurrent(){
                        if(!fresh.getAsBoolean()||!imageBefore.sameSource(navigation.template(()->fresh.getAsBoolean()))||!fresh.getAsBoolean())
                            throw new SecurityException("Live crop source changed");
                    }
                });
        state("CROP_"+read.status().name()+"_"+(read.helperStatus()==null?"NONE":read.helperStatus().name()));
        byte[] png=read.png(),nativePng=null;
        try{
            if(LivePerceptionBudget.crop(read.status(),read.helperStatus(),read.knownClean(),afterExpired(scope,phase))){
                scope.discardCrop(permit,true);throw new AfterExpired();
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
                boolean complete=qualified&&nativeResult.complete();
                var actual=new LiveRewardsTypes.Task(expected.kind(),expected.descriptorHash(),found.match().item().index(),title,
                        expected.kind()==LiveRewardsTypes.Kind.CHECK_IN?points:expected.points(),qualified,complete,day);
                var counter=region(base,LiveTaskMatcher.role(expected.kind()));int c=counter==null?-1:counter.current(),t=counter==null?-1:counter.target();
                Box target=qualified&&!complete?outcome.target():null;double confidence=qualified?outcome.confidence():-1;
                var data=new LiveRewardsTypes.ObservationData(actual,source,read.helperStartedMs(),roi,roi.width(),roi.height(),target,confidence,hash,
                        qualified,true,true,c,t);
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
        var frame=readUi(permit.scope(),LiveRewardsTypes.Phase.BEFORE);var before=permit.before();
        if(!permit.matchesBeforeSource(frame.after()))throw new SecurityException("Live tap source changed");
        var match=LiveTaskMatcher.find(frame.report(),before.task(),cohort);if(match.state()!=LiveTaskMatcher.State.UNIQUE||match.compact()){
            state("TAP_REBIND_"+match.state().name());return false;
        }
        var shape=frame.after().profile().shape();var selected=before.task().kind()==LiveRewardsTypes.Kind.CHECK_IN?
                CardRevealProbeTarget.select(frame.report().base(),shape.width(),shape.height()):RemainingCardTarget.select(frame.report().base(),shape.width(),shape.height(),
                        new RemainingCardChoice(LiveTaskMatcher.role(before.task().kind()),match.item().index())).selection();
        boolean accepted=selected.state()==CardRevealProbeTarget.State.CANDIDATE&&selected.crop().equals(before.data().screenRoi())
                &&permit.current(now())&&current(permit.scope());
        state(accepted?"TAP_REBIND_QUALIFIED":"TAP_REBIND_GEOMETRY_CHANGED");return accepted;
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
