package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Fresh visible run authority with separate bounded observation and per-task input capabilities. */
final class LiveRunAuthority {
    static final long MAX_RUN_MILLIS=1_800_000L,MAX_CARD_MILLIS=180_000L,UI_TTL=9_000L,MAX_FRAME_AGE=15_000L;
    interface Clock { long elapsedRealtime();String day(); }
    private final RealCaptureClaim.ProcessEpoch epoch;
    private final Object owner;
    private final Clock clock;
    private final BooleanSupplier guard;
    private final long issued,deadline;
    private final String day;
    private final boolean live,taskInputAllowed;
    private final AtomicBoolean canceled=new AtomicBoolean();
    private final Map<LiveRewardsTypes.Kind,CardScope> scans=new EnumMap<>(LiveRewardsTypes.Kind.class);
    private final Map<String,CardScope> previews=new HashMap<>(),cards=new HashMap<>();
    private LiveRewardsLedger ledger;
    private BingNavigationSnapshot.Ready original;
    private int scopeSequence;
    private volatile int uiRecheckScopes;
    private volatile boolean diagnosticOnly;
    private FocusDiagnosticPermit focusDiagnostic;

    LiveRunAuthority(VisibleCaptureAuthority.LiveRewardsApproval approval,Clock clock,BooleanSupplier guard){
        if(approval==null||clock==null||guard==null||!approval.live||!approval.consume(clock.elapsedRealtime()))
            throw new SecurityException("Fresh exact visible live approval required");
        this.epoch=approval.epoch;owner=approval.owner;this.clock=clock;this.guard=guard;
        issued=approval.issued;deadline=Math.min(approval.deadline,add(issued,MAX_RUN_MILLIS));day=validDay(clock.day());live=true;taskInputAllowed=approval.taskInputAllowed;
        if(epoch==null||owner==null||deadline<=issued||!current())throw new SecurityException("Live run approval unavailable");
    }
    private LiveRunAuthority(Object owner,RealCaptureClaim.ProcessEpoch epoch,Clock clock,BooleanSupplier guard,boolean taskInputAllowed){
        if(owner==null||epoch==null||clock==null||guard==null)throw new IllegalArgumentException("Fixture owner required");
        this.owner=owner;this.epoch=epoch;this.clock=clock;this.guard=guard;issued=clock.elapsedRealtime();
        deadline=add(issued,MAX_RUN_MILLIS);day=validDay(clock.day());live=false;this.taskInputAllowed=taskInputAllowed;
    }
    static LiveRunAuthority synthetic(Object owner,RealCaptureClaim.ProcessEpoch epoch,Clock clock,BooleanSupplier guard){
        return new LiveRunAuthority(owner,epoch,clock,guard,true);
    }
    static LiveRunAuthority syntheticReadOnly(Object owner,RealCaptureClaim.ProcessEpoch epoch,Clock clock,BooleanSupplier guard){
        return new LiveRunAuthority(owner,epoch,clock,guard,false);
    }
    private static String validDay(String value){LocalDate.parse(value);return value;}
    private static long add(long value,long delta){if(value<0||delta<=0||value>Long.MAX_VALUE-delta)throw new IllegalArgumentException("Clock unavailable");return value+delta;}
    long now(){return clock.elapsedRealtime();}
    String day(){return day;}
    long deadline(){return deadline;}
    Object owner(){return owner;}
    RealCaptureClaim.ProcessEpoch epoch(){return epoch;}
    boolean live(){return live;}
    boolean taskInputAllowed(){return taskInputAllowed;}
    synchronized int taskScopeCount(){return cards.size();}
    boolean diagnosticOnly(){return diagnosticOnly;}
    boolean current(){
        if(canceled.get())return false;
        try{long now=clock.elapsedRealtime();return now>=issued&&now<deadline&&day.equals(clock.day())&&guard.getAsBoolean();}
        catch(RuntimeException failed){return false;}
    }
    void cancel(){canceled.set(true);}
    synchronized void attachLedger(LiveRewardsLedger value){
        if(value==null||ledger!=null||!day.equals(value.day())||live&&!value.production())throw new SecurityException("Exact run ledger required");ledger=value;
    }
    private synchronized void readSlot(String slot)throws IOException{
        if(!current()||ledger==null)throw new IOException("Current attached journal required");
        try{ledger.recordRead(slot);}catch(IOException|RuntimeException failed){cancel();throw failed;}
    }
    synchronized void bindTemplate(BingNavigationSnapshot.Ready source,long now){
        if(diagnosticOnly||source==null||source.kind()!=BingNavigationSnapshot.Kind.TEMPLATE||!source.fresh(now)||!current())
            throw new SecurityException("Fresh original Rewards source required");
        if(original==null)original=source;
        else if(!original.sameSource(source))throw new SecurityException("Original Rewards source changed");
    }
    synchronized CardScope scanScope(LiveRewardsTypes.Kind kind){
        if(diagnosticOnly||uiRecheckScopes!=0||kind==null||!current()||scans.containsKey(kind))throw new SecurityException("One region scan required");
        var task=new LiveRewardsTypes.Task(kind,LiveRewardsTypes.hash("SCAN_"+kind),kind==LiveRewardsTypes.Kind.CHECK_IN?0:1,"",0,false,false,-1);
        var scope=new CardScope(this,task,true,true,++scopeSequence,now());scans.put(kind,scope);return scope;
    }
    synchronized CardScope preflightScope(LiveRewardsTypes.Task task){return scope(task,true);}
    synchronized CardScope cardScope(LiveRewardsTypes.Task task){return scope(task,false);}
    synchronized CardScope moreUiRecheckScope(boolean independent) {
        if (taskInputAllowed || diagnosticOnly || ledger == null || !current() || !scans.isEmpty()
                || !previews.isEmpty() || !cards.isEmpty() || uiRecheckScopes != (independent ? 1 : 0))
            throw new SecurityException("Fresh read-only More UI recheck scope required");
        var kind = LiveRewardsTypes.Kind.MORE;
        var region = new LiveRewardsTypes.Task(kind, LiveRewardsTypes.hash("SCAN_" + kind), 1, "", 0, false, false, -1);
        uiRecheckScopes++;
        return new CardScope(this, region, true, true, ++scopeSequence, now(), independent ? 1 : 2);
    }
    private CardScope scope(LiveRewardsTypes.Task task,boolean preview){
        if(!preview&&!taskInputAllowed)throw new SecurityException("Read-only run cannot issue task scopes");
        if(diagnosticOnly||uiRecheckScopes!=0||task==null||!current())throw new SecurityException("Current task required");var map=preview?previews:cards;
        if(map.size()>=17||map.containsKey(task.key()))throw new SecurityException("One scope per task required");
        long inKind=map.values().stream().filter(s->s.task.kind()==task.kind()).count();
        if(inKind>=(task.kind()==LiveRewardsTypes.Kind.CHECK_IN?1:8))throw new SecurityException("Per-region scope budget exhausted");
        var scope=new CardScope(this,task,preview,false,++scopeSequence,now());map.put(task.key(),scope);return scope;
    }
    synchronized FocusDiagnosticPermit claimFocusDiagnostics(long now){
        if(taskInputAllowed||diagnosticOnly||uiRecheckScopes!=0||focusDiagnostic!=null||ledger==null||!current()||now<issued||now>=deadline)
            throw new SecurityException("One read-only focus diagnostic sequence required");
        diagnosticOnly=true;focusDiagnostic=new FocusDiagnosticPermit(this,now);return focusDiagnostic;
    }
    /** Metadata only after ordinary preflight has stopped. No Ready or UI/input permit is exposed. */
    static final class FocusDiagnosticPermit {
        private static final long[] OFFSETS={0,750,2000};
        private final LiveRunAuthority run;private final long issued;
        private int pairs;private boolean active,burned,closed;
        private FocusDiagnosticPermit(LiveRunAuthority run,long issued){this.run=run;this.issued=issued;}
        boolean live(){return run.live;}
        boolean matches(LiveRunAuthority exact){return run==exact;}
        long issuedElapsed(){return issued;}
        long deadline(){return run.deadline;}
        boolean current(){return current(run.now());}
        synchronized boolean current(long now){return !closed&&!run.taskInputAllowed&&run.diagnosticOnly
                &&run.focusDiagnostic==this&&run.current()&&now>=issued&&now<run.deadline;}
        synchronized int pairCount(){return pairs;}
        synchronized long nextDueElapsed(){return pairs>=OFFSETS.length||issued>Long.MAX_VALUE-OFFSETS[pairs]
                ?Long.MAX_VALUE:issued+OFFSETS[pairs];}
        synchronized boolean claimPair(long now)throws IOException{
            if(active||pairs>=OFFSETS.length||!current(now)||now<nextDueElapsed())return false;
            active=true;burned=false;pairs++;
            try{run.readSlot("FOCUS_DIAGNOSTIC_"+pairs);burned=true;return true;}
            catch(IOException|RuntimeException failed){closed=true;run.cancel();throw failed;}
        }
        synchronized boolean dispatchValid(long now){return active&&burned&&current(now);}
        void finishPair(boolean completed,boolean clean){
            synchronized(this){if(!active)return;active=false;
                if(!burned||!completed||!clean||pairs==OFFSETS.length)closed=true;}
            if(!clean)run.cancel();
        }
    }

    static final class CardScope {
        private final LiveRunAuthority run;
        private final LiveRewardsTypes.Task task;
        private final Object owner=new Object();
        private final boolean preflight,scan;
        private final int sequence, uiRecheckReads;
        private final long issued,deadline;
        private long afterDeadline=-1;
        private int observations,swipes,uiRecheckPhases;
        private UiPermit activeUi,latestUi;
        private ScrollPermit activeScroll;
        private CropPermit activeCrop;
        private final CropPermit[] crops=new CropPermit[3];
        private final LiveRewardsTypes.Observation[] sealed=new LiveRewardsTypes.Observation[3];
        private LiveActionPermit action;
        private CardScope(LiveRunAuthority run,LiveRewardsTypes.Task task,boolean preflight,boolean scan,int sequence,long now){
            this(run,task,preflight,scan,sequence,now,0);
        }
        private CardScope(LiveRunAuthority run,LiveRewardsTypes.Task task,boolean preflight,boolean scan,int sequence,long now,int uiRecheckReads){
            this.run=run;this.task=task;this.preflight=preflight;this.scan=scan;this.sequence=sequence;this.uiRecheckReads=uiRecheckReads;issued=now;
            deadline=Math.min(run.deadline,add(now,MAX_CARD_MILLIS));
        }
        boolean uiRecheckOnly(){return uiRecheckReads>0;}
        synchronized boolean claimUiRecheckPhase(LiveRewardsTypes.Phase phase){
            if(!uiRecheckOnly()||!current(run.now())||afterDeadline<0||uiRecheckPhases!=observations
                    ||activeUi!=null||uiRecheckPhases>=uiRecheckReads)return false;
            var expected=uiRecheckReads==2&&uiRecheckPhases==0?LiveRewardsTypes.Phase.AFTER_1:LiveRewardsTypes.Phase.AFTER_2;
            if(phase!=expected)return false;
            uiRecheckPhases++;
            return true;
        }
        Object owner(){return owner;}
        LiveRewardsTypes.Task task(){return task;}
        boolean preflight(){return preflight;}
        boolean scan(){return scan;}
        long deadline(){return deadline;}
        LiveRunAuthority run(){return run;}
        synchronized void startAfterWindow(long started){
            require(run.now());
            if(afterDeadline>=0||started<issued||started>run.now())throw new SecurityException("One after window required");
            afterDeadline=Math.min(deadline,add(started,LiveRewardsCoordinator.AFTER_WINDOW_MILLIS));
        }
        synchronized long afterDeadline(){return afterDeadline;}
        synchronized long remainingAfterBudget(long now){return afterDeadline<0?0:Math.max(0,afterDeadline-now);}
        synchronized long observationDeadline(LiveRewardsTypes.Phase phase){
            return phase==LiveRewardsTypes.Phase.BEFORE?deadline:afterDeadline<0?deadline:Math.min(deadline,afterDeadline);
        }
        boolean current(long now){return !run.diagnosticOnly&&run.current()&&now>=issued&&now<deadline
                &&(!uiRecheckOnly()||run.uiRecheckScopes==(uiRecheckReads==2?1:2));}
        private void require(long now){if(!current(now))throw new SecurityException("Task scope expired or revoked");}
        synchronized UiPermit claimUi(Object observationOwner,long now){
            require(now);
            if(observationOwner==null||activeUi!=null||activeScroll!=null||activeCrop!=null||observations>=24
                    ||uiRecheckOnly()&&uiRecheckPhases!=observations+1)
                throw new SecurityException("Bounded next observation required");
            long limit=afterDeadline<0?deadline:Math.min(deadline,afterDeadline);
            if(now>=limit)throw new SecurityException("After observation budget exhausted");
            latestUi=null;activeUi=new UiPermit(this,++observations,observationOwner,now,Math.min(limit,add(now,UI_TTL)));return activeUi;
        }
        synchronized void finishUi(UiPermit exact,boolean clean,boolean qualified,long now){
            if(exact==null||activeUi!=exact||!exact.dispatchValid(now)||!clean||!qualified){run.cancel();throw new SecurityException("Exact clean qualified UI required");}
            exact.finished=true;activeUi=null;latestUi=exact;
        }
        synchronized void discardUi(UiPermit exact,boolean clean){
            if(exact==null||activeUi!=exact||!clean){run.cancel();throw new SecurityException("UI cleanup unconfirmed");}
            exact.finished=true;activeUi=null;latestUi=null;
        }
        private synchronized boolean uiCurrent(UiPermit exact,long now){return exact!=null&&exact.scope==this
                &&(activeUi==exact&&!exact.finished||latestUi==exact&&exact.finished)&&now>=exact.issued&&now<exact.deadline&&current(now);}
        synchronized ScrollPermit claimScroll(UiPermit exact,ReviewShizukuSwipeActor.Direction direction,long now){
            return claimScroll(exact,direction,null,now);
        }
        synchronized ScrollPermit claimScroll(UiPermit exact,ReviewShizukuSwipeActor.SwipeTarget target,long now){
            if(!ReviewShizukuSwipeActor.valid(target)||task.kind()==LiveRewardsTypes.Kind.CHECK_IN&&!target.standard())
                throw new SecurityException("Invalid live scroll geometry");
            return claimScroll(exact,target.direction(),target,now);
        }
        private ScrollPermit claimScroll(UiPermit exact,ReviewShizukuSwipeActor.Direction direction,
                ReviewShizukuSwipeActor.SwipeTarget target,long now){
            require(now);
            if(uiRecheckOnly()||exact==null||latestUi!=exact||!exact.finished||!exact.current(now)||direction==null
                    ||activeUi!=null||activeScroll!=null||activeCrop!=null||swipes>=8)
                throw new SecurityException("Fresh scroll observation required");
            latestUi=null;activeScroll=new ScrollPermit(this,++swipes,direction,target,now,Math.min(deadline,exact.deadline));return activeScroll;
        }
        synchronized void finishScroll(ScrollPermit exact,ReviewShizukuSwipeActor.Result result,boolean clean,long now){
            if(exact==null||activeScroll!=exact||!exact.dispatchValid(now)||result==null||!result.commandExitZero()
                    ||!result.authorityConsumed()||!result.invocationAttempted()||!clean){run.cancel();throw new SecurityException("Scroll cleanup unconfirmed");}
            exact.finished=true;activeScroll=null;
        }
        synchronized CropPermit claimCrop(UiPermit exact,LiveRewardsTypes.Phase phase,long now){
            require(now);
            if(scan||phase==null||preflight&&phase!=LiveRewardsTypes.Phase.BEFORE||crops[phase.ordinal()]!=null
                    ||exact==null||latestUi!=exact||!exact.finished||!exact.current(now)
                    ||activeUi!=null||activeScroll!=null||activeCrop!=null)
                throw new SecurityException("One current phase crop required");
            activeCrop=new CropPermit(this,exact,phase,now,Math.min(deadline,exact.deadline));crops[phase.ordinal()]=activeCrop;
            latestUi=null;return activeCrop;
        }
        synchronized void finishCrop(CropPermit exact,boolean clean,boolean qualified,long now){
            if(exact==null||activeCrop!=exact||!exact.dispatched||!exact.burned||!exact.current(now)||!clean||!qualified){
                run.cancel();throw new SecurityException("Exact clean qualified crop required");
            }
            exact.finished=true;activeCrop=null;
        }
        synchronized void discardCrop(CropPermit exact,boolean clean){
            if(exact==null||activeCrop!=exact||!clean){run.cancel();throw new SecurityException("Crop cleanup unconfirmed");}
            exact.discarded=true;activeCrop=null;
        }
        synchronized LiveRewardsTypes.Observation sealObservation(CropPermit exact,LiveRewardsTypes.ObservationData data){
            long now=run.now();require(now);
            if(exact==null||exact.scope!=this||crops[exact.phase.ordinal()]!=exact||!exact.finished||exact.discarded||data==null
                    ||sealed[exact.phase.ordinal()]!=null||data.task().kind()!=task.kind()
                    ||exact.phase==LiveRewardsTypes.Phase.BEFORE&&!data.task().sameKey(task)
                    ||now<data.acquiredElapsed()||now-data.acquiredElapsed()>MAX_FRAME_AGE)
                throw new SecurityException("Exact current native crop evidence required");
            run.bindTemplate(data.source(),now);
            var shape=data.source().profile().shape();var roi=data.screenRoi();
            if(data.nativeQualified()&&(roi==null||!exact.requestBound||!roi.equals(exact.roi)||!shape.equals(exact.shape)
                    ||roi.left()<0||roi.top()<0||roi.right()>shape.width()||roi.bottom()>shape.height()
                    ||data.localTarget()!=null&&!strictLocal(data.localTarget(),data.cropWidth(),data.cropHeight())))
                throw new SecurityException("Native crop geometry changed");
            var result=LiveRewardsTypes.Observation.sealed(this,exact.phase,data,data.nativeQualified()?"OBSERVED":"NATIVE_UNREADABLE");
            sealed[exact.phase.ordinal()]=result;return result;
        }
        synchronized LiveRewardsTypes.Observation unreadable(LiveRewardsTypes.Phase phase,LiveRewardsTypes.Task expected,
                BingNavigationSnapshot.Ready source,long observed,String reason){
            return unreadable(phase,expected,source,observed,reason,-1,-1);
        }
        synchronized LiveRewardsTypes.Observation unreadable(LiveRewardsTypes.Phase phase,LiveRewardsTypes.Task expected,
                BingNavigationSnapshot.Ready source,long observed,String reason,int counterCurrent,int counterTarget){
            long now=run.now();require(now);
            if(phase==null||expected==null||!expected.sameKey(task)||sealed[phase.ordinal()]!=null
                    ||activeUi!=null||activeCrop!=null||activeScroll!=null||now<observed||now-observed>MAX_FRAME_AGE
                    ||reason==null||!reason.matches("[A-Z][A-Z0-9_]{0,79}"))throw new SecurityException("Bounded unreadable evidence required");
            run.bindTemplate(source,now);
            var t=new LiveRewardsTypes.Task(expected.kind(),expected.descriptorHash(),expected.ordinal(),expected.titleHash(),expected.points(),false,false,expected.day());
            var data=new LiveRewardsTypes.ObservationData(t,source,observed,null,0,0,null,-1,"",false,true,false,counterCurrent,counterTarget);
            var result=LiveRewardsTypes.Observation.sealed(this,phase,data,reason);sealed[phase.ordinal()]=result;return result;
        }
        synchronized boolean isSealed(LiveRewardsTypes.Observation value){return value!=null&&value.scope()==this&&sealed[value.phase().ordinal()]==value;}
        synchronized LiveActionPermit actionPermit(LiveRewardsTypes.Observation before,LiveRewardsLedger ledger){
            if(!run.taskInputAllowed||preflight||scan||action!=null||ledger==null||ledger!=run.ledger||!ledger.begunHere(task)
                    ||!isSealed(before)||before.phase()!=LiveRewardsTypes.Phase.BEFORE)
                throw new SecurityException("Fresh pending live task required");
            action=LiveActionPermit.create(this,before,ledger);return action;
        }
        private String slot(String suffix){return "S"+sequence+"_"+suffix;}
    }
    private static boolean strictLocal(com.aliothmoon.maafw.rewards.RewardRegionLocator.Box b,int w,int h){
        return b.left()>0&&b.top()>0&&b.right()<w&&b.bottom()<h&&b.width()>0&&b.height()>0;
    }

    static final class UiPermit {
        private final CardScope scope;private final int index;private final Object owner;private final long issued,deadline;
        private boolean readerAttempted,readerClaimed,consumed,burned,finished;
        private UiPermit(CardScope scope,int index,Object owner,long issued,long deadline){this.scope=scope;this.index=index;this.owner=owner;this.issued=issued;this.deadline=deadline;}
        boolean live(){return scope.run.live;}
        boolean current(long now){return scope.uiCurrent(this,now);}
        synchronized boolean claimReader(long now){boolean first=!readerAttempted;readerAttempted=true;
            boolean accepted=first&&!finished&&current(now);if(accepted)readerClaimed=true;return accepted;}
        synchronized boolean consume(long now)throws IOException{
            boolean first=!consumed;consumed=true;
            if(!first||!readerClaimed||finished||!current(now))return false;
            scope.run.readSlot(scope.slot("UI_"+index));burned=true;return true;
        }
        synchronized boolean dispatchValid(long now){return readerClaimed&&burned&&!finished&&current(now);}
        long deadline(){return deadline;}int index(){return index;}Object owner(){return owner;}
        RealCaptureClaim.ProcessEpoch epoch(){return scope.run.epoch;}CardScope scope(){return scope;}
    }
    static final class ScrollPermit {
        private final CardScope scope;private final int index;private final ReviewShizukuSwipeActor.Direction direction;
        private final ReviewShizukuSwipeActor.SwipeTarget target;
        private final long issued,deadline;private boolean actorAttempted,actorClaimed,consumed,burned,finished;
        private ScrollPermit(CardScope scope,int index,ReviewShizukuSwipeActor.Direction direction,
                ReviewShizukuSwipeActor.SwipeTarget target,long issued,long deadline){
            this.scope=scope;this.index=index;this.direction=direction;this.target=target;this.issued=issued;this.deadline=deadline;
        }
        boolean matchesTarget(ReviewShizukuSwipeActor.SwipeTarget candidate){
            return ReviewShizukuSwipeActor.valid(candidate)&&candidate.direction()==direction
                    &&(target==null?candidate.standard():target==candidate);
        }
        boolean live(){return scope.run.live;}
        boolean current(long now){return !finished&&scope.activeScroll==this&&scope.current(now)&&now>=issued&&now<deadline;}
        synchronized boolean claimActor(long now){boolean first=!actorAttempted;actorAttempted=true;
            boolean accepted=first&&current(now);if(accepted)actorClaimed=true;return accepted;}
        synchronized boolean consume(long now)throws IOException{
            boolean first=!consumed;consumed=true;if(!first||!actorClaimed||!current(now))return false;
            scope.run.readSlot(scope.slot("SCROLL_"+index));burned=true;return true;
        }
        synchronized boolean dispatchValid(long now){return actorClaimed&&burned&&current(now);}
        ReviewShizukuSwipeActor.Direction direction(){return direction;}long deadline(){return deadline;}
        int index(){return index;}CardScope scope(){return scope;}
    }
    static final class CropPermit {
        private final CardScope scope;private final UiPermit ui;private final LiveRewardsTypes.Phase phase;
        private final long issued,deadline;private boolean consumed,burned,dispatchAttempted,dispatched,finished,discarded,requestBound;
        private com.aliothmoon.maafw.rewards.RewardRegionLocator.Box roi;
        private BingNavigationSnapshot.Shape shape;
        private CropPermit(CardScope scope,UiPermit ui,LiveRewardsTypes.Phase phase,long issued,long deadline){this.scope=scope;this.ui=ui;this.phase=phase;this.issued=issued;this.deadline=deadline;}
        boolean live(){return scope.run.live;}
        synchronized boolean bindRequest(com.aliothmoon.maafw.rewards.RewardRegionLocator.Box roi,
                BingNavigationSnapshot.Shape shape,long now){
            return shape!=null&&bindRequest(roi,shape.width(),shape.height(),shape.rotation(),now);
        }
        synchronized boolean bindRequest(com.aliothmoon.maafw.rewards.RewardRegionLocator.Box roi,
                int width,int height,int rotation,long now){
            if(requestBound||consumed||dispatched||!current(now)
                    ||!CardRegionCaptureProtocol.validCrop(roi,width,height,rotation))return false;
            this.roi=roi;shape=new BingNavigationSnapshot.Shape(width,height,rotation);requestBound=true;return true;
        }
        // Ownership alone allows callers to discard an expired request. It cannot dispatch or read pixels.
        boolean activeForRetirement(long now){return !discarded&&!finished&&scope.activeCrop==this
                &&scope.crops[phase.ordinal()]==this&&scope.current(now)&&now>=issued;}
        boolean current(long now){return !discarded&&scope.current(now)&&now>=issued&&now<deadline
                &&(scope.activeCrop==this&&!finished||finished&&scope.crops[phase.ordinal()]==this);}
        synchronized boolean consume(long now)throws IOException{
            boolean first=!consumed;consumed=true;if(!first||finished||!requestBound||!current(now))return false;
            scope.run.readSlot(scope.slot("CROP_"+phase));burned=true;return true;
        }
        synchronized boolean claimDispatch(long now){boolean first=!dispatchAttempted;dispatchAttempted=true;
            boolean accepted=first&&burned&&!finished&&current(now);if(accepted)dispatched=true;return accepted;}
        synchronized boolean dispatchValid(long now){return dispatched&&burned&&!finished&&current(now);}
        long deadline(){return deadline;}LiveRewardsTypes.Phase phase(){return phase;}CardScope scope(){return scope;}
        UiPermit ui(){return ui;}Object owner(){return scope.owner;}
    }
}
