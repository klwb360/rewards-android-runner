package com.aliothmoon.maafw.once;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One independently issued card scope: two fresh native observations, at most one typed tap.
 * No default permission, scope name, disk fallback, navigation, retry, text-input or tasker action.
 * Caller owns visible issuance and BEFORE/AFTER durable capture admission in a NEW namespace.
 * Native/bitmap/frame/projection cleanup must be confirmed before acceptAfterCleanup(). */
final class NativeRewardCardSession {
    static final long MAX_FRAME_AGE=15_000L,MAX_SESSION_TTL=180_000L;
    enum Phase { BEFORE, AFTER }
    enum Outcome { AWAITING_NEW_OBSERVATION, OBSERVED_TASK_COMPLETE_UNATTRIBUTED,
        SECTION_ALREADY_COMPLETE_SKIP, TASK_ALREADY_COMPLETE_SKIP, OBSERVED_VISIBLE_KNOWN_SET_COMPLETE, OBSERVED_VISIBLE_MORE_SET_COMPLETE, HOLD }
    enum Reason { TAP_EXITED_NOT_COMPLETION, SAME_TASK_NATIVE_TICK, SAME_TASK_COMPACT_NATIVE_TICK, SAME_TASK_ROSTER_COMPACT_NATIVE_TICK, RETURN_READY_NOT_COMPLETION, RETURN_NOT_READY, REGION_COMPLETE,
        TASK_TICK_ALREADY_VISIBLE, VISIBLE_KNOWN_SET_TICKS, VISIBLE_MORE_SET_TICKS, BAD_CAPTURE_OR_CLEANUP, SOURCE_OR_AUTHORITY_LOST,
        OBSERVATION_BLOCKED, EXACT_SELECTION_REQUIRED, ACTOR_NOT_CONFIRMED,
        AFTER_NOT_FRESH, CONTEXT_CHANGED, SAME_TASK_NOT_COMPLETE, CANCELED }
    record Receipt(Outcome outcome,Reason reason,ReviewShizukuTapActor.Result actorResult) {
        boolean accountCreditKnown(){return false;}
        boolean causedByTap(){return false;}
    }
    interface Authority {
        long elapsedRealtime();
        /** Independently issued absolute UTC/elapsed permission, never the expired check-in scope. */
        boolean scopeCurrent();
        /** Exact Bing package/certificate + foreground activity/window + display/rotation.
         * Must fail on unknown source, other apps, overlays or lost permission. */
        boolean sourceStillMatches(int width,int height,int rotation);
        /** Required durable CREATE_NEW + file/directory fsync in a fresh card namespace.
         * Return only after the exact one-tap scope is consumed. Exception/partial/existing
         * burn is terminal. Do not persist titles, task identity or raw native OCR. */
        void consumeOneTap(ReviewShizukuTapActor.TapTarget exactTarget) throws Exception;
    }
    static final class Observation {
        private final Capture capture;
        private final LiveRewardCardObservation.Frame frame;
        private Observation(Capture c,LiveRewardCardObservation.Frame f){capture=c;frame=f;}
        List<LiveRewardCardObservation.Card> cards(){return frame.cards();}
        boolean uniquePendingOnly(){return capture.session.spec.uniquePending();}
        boolean uniqueProofReady(){return uniquePendingOnly()&&frame.uniqueProofReady();}
        String uniqueEvidenceSummary(){return frame.uniqueEvidenceSummary();}
        String selectedEvidenceSummary(){return frame.selectedEvidenceSummary(capture.phase==Phase.BEFORE);}
        boolean visibleSetComplete(){return frame.selectedAllComplete();}
        LiveRewardCardObservation.Card configuredTarget(){
            if(capture.session.spec.uniquePending())return frame.uniqueTarget();
            LiveRewardCardObservation.Card found=null;int count=0;
            for(var card:frame.cards())if(card.configuredTarget(capture.session.spec)){found=card;count++;}
            return count==1?found:null;
        }
        List<LiveRewardCardObservation.Card> incompleteCards(){
            List<LiveRewardCardObservation.Card> out=new ArrayList<>();
            for(var c:frame.cards())if(!c.complete())out.add(c);
            return List.copyOf(out);
        }
        LiveRewardCardObservation.Route route(){return frame.route();}
        LiveRewardCardObservation.Diagnostic diagnostic(){return frame.diagnostic();}
        LiveRewardCardObservation.Status status(){return frame.status();}
        LiveRewardCardObservation.Reason reason(){return frame.reason();}
    }
    static final class Capture {
        private final NativeRewardCardSession session;
        final Phase phase;
        final int width,height,rotation;
        final long acquiredLowerBound,sequence;
        private final AtomicBoolean prepared=new AtomicBoolean();
        private Capture(NativeRewardCardSession s,Phase p,int w,int h,int r,long lower){this(s,p,w,h,r,lower,p.ordinal()+1);}
        private Capture(NativeRewardCardSession s,Phase p,int w,int h,int r,long lower,long sequence){
            session=s;phase=p;width=w;height=h;rotation=r;acquiredLowerBound=lower;this.sequence=sequence;
        }
        /** Call exactly once from the owning Maa full-image native batch before wiping pixels.
         * lines are all[] in original full-display coordinates, not a copied older result or
         * OCR-selected subset. No input pixels/list are retained in the returned observation. */
        Observation prepare(byte[] gray,int w,int h,List<FrameStructureEvidence.Line> lines,BooleanSupplier canceled){
            if(w!=width||h!=height||canceled==null||!prepared.compareAndSet(false,true)
                ||canceled.getAsBoolean()||session.revoked.get())return null;
            var f=LiveRewardCardObservation.observe(session.runId,sequence,gray,w,h,lines,
                ()->canceled.getAsBoolean()||session.revoked.get(),session.spec.forObservation(phase==Phase.AFTER));
            return canceled.getAsBoolean()||session.revoked.get()?null:new Observation(this,f);
        }
    }
    /** Observation-only holder. No Session capture/action budget is spent until adopt. */
    static final class PreparedCapture {
        private final NativeRewardCardSession session;private final Phase phase;private final int attempt,width,height,rotation;
        private final Object requestOwner;private final long lower,sequence,deadline;
        private final BingNavigationSnapshot.Ready source;
        private final AtomicBoolean prepared=new AtomicBoolean(),closed=new AtomicBoolean();
        private PreparedCapture(NativeRewardCardSession session,Phase phase,int attempt,Object owner,long lower,long sequence,long deadline,BingNavigationSnapshot.Ready source){
            this.session=session;this.phase=phase;this.attempt=attempt;requestOwner=owner;this.lower=lower;this.sequence=sequence;this.deadline=deadline;this.source=source;
            var shape=source.profile().shape();width=shape.width();height=shape.height();rotation=shape.rotation();
        }
        Phase phase(){return phase;}int attempt(){return attempt;}Object requestOwner(){return requestOwner;}
        int width(){return width;}int height(){return height;}int rotation(){return rotation;}
        BingNavigationSnapshot.Shape shape(){return source.profile().shape();}BingNavigationSnapshot.Ready source(){return source;}
        long acquiredLowerBound(){return lower;}long sequence(){return sequence;}long phaseDeadline(){return deadline;}
        boolean open(){return !closed.get()&&!session.revoked.get();}void close(){closed.set(true);}
        PreparedObservation prepare(byte[] gray,int w,int h,List<FrameStructureEvidence.Line> rows,BooleanSupplier canceled){
            if(w!=width||h!=height||canceled==null||!prepared.compareAndSet(false,true)||!open()||canceled.getAsBoolean())return null;
            Object token=new Object();var binding=new FrameStructureEvidence.Binding(this,requestOwner,token,token);
            var assessment=OcrRowsAssessment.assess(binding,binding,rows,w,h);boolean valid=assessment.shapeValid();
            int nonempty=assessment.nonempty(),more=-1,wrong=-1;
            if(valid){more=0;wrong=0;for(var marker:MaaRecognizer.rootMarkers(rows).markers()){
                if(marker.marker()==MaaRecognizer.RootMarker.MORE)more+=marker.candidates();
                if(marker.marker()==MaaRecognizer.RootMarker.OWN_ROOT_TITLE||marker.marker()==MaaRecognizer.RootMarker.OWN_PREFLIGHT_PROMPT
                    ||marker.marker()==MaaRecognizer.RootMarker.PROJECTION_PROMPT||marker.marker()==MaaRecognizer.RootMarker.LOCK_SCREEN_PROMPT
                    ||marker.marker()==MaaRecognizer.RootMarker.REWARDS_ERROR)wrong+=marker.candidates();
            }}
            var frame=LiveRewardCardObservation.observe(session.runId,sequence,gray,w,h,rows,()->canceled.getAsBoolean()||!open(),session.spec.forObservation(phase==Phase.AFTER));
            return canceled.getAsBoolean()||!open()?null:new PreparedObservation(this,frame,valid,rows==null?-1:rows.size(),nonempty,more,wrong);
        }
    }
    static final class PreparedObservation {
        private final PreparedCapture capture;private final LiveRewardCardObservation.Frame frame;
        private final boolean valid;private final int rows,nonempty,more,wrong;
        private PreparedObservation(PreparedCapture capture,LiveRewardCardObservation.Frame frame,boolean valid,int rows,int nonempty,int more,int wrong){
            this.capture=capture;this.frame=frame;this.valid=valid;this.rows=rows;this.nonempty=nonempty;this.more=more;this.wrong=wrong;
        }
        boolean selectedPendingOnly(){return capture.session.spec.selectedPending();}
        boolean selectedProofReady(){return selectedPendingOnly()&&frame.selectedProofReady();}
        boolean uniquePendingOnly(){return capture.session.spec.uniquePending();}
        boolean uniqueProofReady(){return uniquePendingOnly()&&frame.uniqueProofReady();}
        String uniqueEvidenceSummary(){return frame.uniqueEvidenceSummary();}
        String selectedEvidenceSummary(){return frame.selectedEvidenceSummary(capture.phase==Phase.BEFORE);}
        boolean visibleSetComplete(){return frame.selectedAllComplete();}
        boolean matches(PreparedCapture p){return capture==p;}boolean validNativeShape(){return valid;}
        int nativeRowCount(){return rows;}int nativeNonemptyRows(){return nonempty;}int nativeMoreCandidates(){return more;}int nativeWrongPageCandidates(){return wrong;}
        LiveRewardCardObservation.Frame frame(){return frame;}LiveRewardCardObservation.Status status(){return frame.status();}
        LiveRewardCardObservation.Reason reason(){return frame.reason();}LiveRewardCardObservation.Route route(){return frame.route();}
        LiveRewardCardObservation.Diagnostic diagnostic(){return frame.diagnostic();}List<LiveRewardCardObservation.Card> cards(){return frame.cards();}
        LiveRewardCardObservation.Card configuredTarget(){if(capture.session.spec.uniquePending())return frame.uniqueTarget();LiveRewardCardObservation.Card selected=null;int n=0;
            for(var card:frame.cards())if(card.configuredTarget(capture.session.spec)){selected=card;n++;}return n==1?selected:null;}
    }
    static final class Adopted {
        private final Capture capture;private final Observation observation;
        private Adopted(Capture c,Observation o){capture=c;observation=o;}Capture capture(){return capture;}Observation observation(){return observation;}
    }
    PreparedCapture prepareCandidate(Phase phase,int attempt,Object owner,long lower,long sequence,long deadline,BingNavigationSnapshot.Ready source){
        if(phase==null||owner==null||source==null||source.kind()!=BingNavigationSnapshot.Kind.TEMPLATE||attempt<1||attempt>3
            ||sequence<1||lower<0||deadline>expires||deadline<=lower||!shape(source.profile().shape().width(),source.profile().shape().height(),source.profile().shape().rotation())
            ||revoked.get()||finished||!current()||phase==Phase.BEFORE&&beforeSpent||phase==Phase.AFTER&&(!waitingAfter||afterSpent))return null;
        return new PreparedCapture(this,phase,attempt,owner,lower,sequence,deadline,source);
    }
    /** Only Run calls this after consuming exact private Runtime cleanup/audit proof. */
    synchronized Adopted adoptPrepared(PreparedCapture p,PreparedObservation o,BooleanSupplier hidden){
        if(p==null||p.session!=this||o==null||o.capture!=p||!p.prepared.get()||active!=null||!p.open()||!o.valid
            ||o.frame.route()!=spec.route()||o.frame.status()==LiveRewardCardObservation.Status.HOLD||spec.selectedPending()&&!o.frame.selectedProofReady()||spec.uniquePending()&&!o.frame.uniqueProofReady()
            ||p.phase==Phase.BEFORE&&beforeSpent||p.phase==Phase.AFTER&&(!waitingAfter||afterSpent))return null;
        // Reserve forever before any source IPC, as the legacy startCapture contract does.
        if(p.phase==Phase.BEFORE)beforeSpent=true;else afterSpent=true;p.close();
        if(p.phase==Phase.AFTER&&(p.width!=width||p.height!=height||p.rotation!=rotation||p.lower<=actionElapsed))return stopAdoption();
        long now=authority.elapsedRealtime();
        if(hidden==null||!hidden.getAsBoolean()||!current()||now<p.lower||now-p.lower>MAX_FRAME_AGE||now>=p.deadline
            ||!source(p.width,p.height,p.rotation)||!hidden.getAsBoolean()||!current())return stopAdoption();
        now=authority.elapsedRealtime();if(now<p.lower||now-p.lower>MAX_FRAME_AGE||now>=p.deadline)return stopAdoption();
        if(p.phase==Phase.BEFORE){width=p.width;height=p.height;rotation=p.rotation;}
        Capture c=new Capture(this,p.phase,p.width,p.height,p.rotation,p.lower,p.sequence);c.prepared.set(true);active=c;
        return new Adopted(c,new Observation(c,o.frame));
    }
    private Adopted stopAdoption(){finish();return null;}
    /** Issued only after this exact session's one typed task tap exited successfully. */
    static final class ReturnPermit {
        private final NativeRewardCardSession session;
        private final BingNavigationSnapshot.Shape shape;
        private final long action,successfulTap,deadline;
        private final BooleanSupplier hidden;
        private ReturnPermit(NativeRewardCardSession s,long deadline,BooleanSupplier hidden){
            session=s;shape=new BingNavigationSnapshot.Shape(s.width,s.height,s.rotation);action=s.actionElapsed;successfulTap=s.successfulTapElapsed;this.deadline=deadline;this.hidden=hidden;
        }
        String runId(){return session.runId;}
        BingNavigationSnapshot.Shape shape(){return shape;}
        long actionElapsed(){return action;}
        long successfulTapElapsed(){return successfulTap;}
        boolean current(){return !session.revoked.get()&&!session.finished&&session.current()
            &&session.authority.elapsedRealtime()<deadline&&hidden.getAsBoolean();}
    }
    private final String runId;
    private final LiveRewardCardObservation.TargetSpec spec;
    private final Authority authority;
    private final ReviewShizukuTapActor actor;
    private final NativeBingReturnController returner;
    private final long expires;
    private final AtomicBoolean revoked=new AtomicBoolean();
    private Capture active;
    private boolean beforeSpent,afterSpent,tapAttempted;
    private volatile boolean waitingAfter,finished,returned;
    private volatile String returnSummary="returnStatus=NOT_REQUESTED";
    private int width,height,rotation;
    private long actionElapsed=-1,successfulTapElapsed=-1;
    private LiveRewardCardObservation.Card selected;
    private LiveRewardCardObservation.Frame beforeFrame;
    NativeRewardCardSession(String runId,long expiresElapsed,Authority authority,ReviewShizukuTapActor actor){
        this(runId,expiresElapsed,authority,actor,LiveRewardCardObservation.TargetSpec.dailyRegionCounter());
    }
    NativeRewardCardSession(String runId,long expiresElapsed,Authority authority,ReviewShizukuTapActor actor,
                            LiveRewardCardObservation.TargetSpec spec){this(runId,expiresElapsed,authority,actor,spec,null);}
    NativeRewardCardSession(String runId,long expiresElapsed,Authority authority,ReviewShizukuTapActor actor,
                            LiveRewardCardObservation.TargetSpec spec,NativeBingReturnController returner){
        if(spec!=null&&spec.requiresNativeReturn()&&returner==null)throw new SecurityException("Native return controller required");
        if(runId==null||runId.isEmpty()||runId.length()>160||authority==null||actor==null||spec==null)
            throw new IllegalArgumentException("explicit scope and typed actor required");
        long now=authority.elapsedRealtime();
        if(now<0||expiresElapsed<=now||expiresElapsed-now>MAX_SESSION_TTL||!authority.scopeCurrent())
            throw new SecurityException("fresh independently issued scope required");
        this.runId=runId;expires=expiresElapsed;this.authority=authority;this.actor=actor;this.spec=spec;this.returner=returner;
    }
    synchronized Capture startCapture(Phase phase,int w,int h,int r){
        if(phase==null||revoked.get()||finished||active!=null||!shape(w,h,r)
            ||!current()||phase==Phase.BEFORE&&beforeSpent
            ||phase==Phase.AFTER&&(!waitingAfter||afterSpent))return null;
        // Reserve before any uncertain external check. Failed starts cannot be retried.
        if(phase==Phase.BEFORE)beforeSpent=true;else afterSpent=true;
        if(phase==Phase.AFTER&&(w!=width||h!=height||r!=rotation))return stopCapture();
        if(!source(w,h,r)||!current())return stopCapture();
        if(phase==Phase.BEFORE){width=w;height=h;rotation=r;}
        long lower=authority.elapsedRealtime();
        if(phase==Phase.AFTER&&lower<=actionElapsed)return stopCapture();
        return active=new Capture(this,phase,w,h,r,lower);
    }
    private Capture stopCapture(){finished=true;return null;}
    private boolean current(){
        try {
            long now=authority.elapsedRealtime();
            return !revoked.get()&&now>=0&&now<expires&&authority.scopeCurrent();
        } catch(RuntimeException unknown){return false;}
    }
    private boolean source(int w,int h,int r){
        try{return authority.sourceStillMatches(w,h,r);}
        catch(RuntimeException unknown){return false;}
    }
    private boolean fresh(Capture capture){
        long now=authority.elapsedRealtime();
        return now>=capture.acquiredLowerBound&&now-capture.acquiredLowerBound<=MAX_FRAME_AGE&&now<expires;
    }
    private boolean valid(Capture capture,BooleanSupplier ownUiHidden){
        if(revoked.get()||finished||ownUiHidden==null||!ownUiHidden.getAsBoolean()||!current()||!fresh(capture))return false;
        // End with local freshness/expiry/cancellation after the potentially slow source read.
        return source(capture.width,capture.height,capture.rotation)&&ownUiHidden.getAsBoolean()
            &&current()&&fresh(capture)&&!revoked.get();
    }
    Receipt acceptAfterCleanup(Capture capture,Observation observation,boolean released,
            BooleanSupplier ownUiHidden,LiveRewardCardObservation.Card exactSelection){
        return acceptAfterCleanup(capture,observation,released,ownUiHidden,exactSelection,expires);
    }
    synchronized Receipt acceptAfterCleanup(Capture capture,Observation observation,boolean released,
            BooleanSupplier ownUiHidden,LiveRewardCardObservation.Card exactSelection,long phaseDeadline){
        if(capture==null||capture!=active||observation==null||observation.capture!=capture||!released||phaseDeadline>expires||authority.elapsedRealtime()>=phaseDeadline) {
            finish();return receipt(Outcome.HOLD,Reason.BAD_CAPTURE_OR_CLEANUP,null);
        }
        active=null;
        if(!valid(capture,ownUiHidden)){finish();return receipt(Outcome.HOLD,Reason.SOURCE_OR_AUTHORITY_LOST,null);}
        var frame=observation.frame;
        if(frame.route()!=spec.route()||frame.status()==LiveRewardCardObservation.Status.HOLD){finish();return receipt(Outcome.HOLD,Reason.OBSERVATION_BLOCKED,null);}
        if(capture.phase==Phase.AFTER)return verifyAfter(capture,frame);
        if(spec.uniquePending()){
            if(!frame.uniqueProofReady()){finish();return receipt(Outcome.HOLD,Reason.OBSERVATION_BLOCKED,null);}
            if(frame.status()==LiveRewardCardObservation.Status.SECTION_COMPLETE_SKIP){finish();return receipt(Outcome.SECTION_ALREADY_COMPLETE_SKIP,Reason.REGION_COMPLETE,null);}
            if(!frame.uniqueBefore()||exactSelection==null||exactSelection!=frame.uniqueTarget()||exactSelection.complete()){finish();return receipt(Outcome.HOLD,Reason.EXACT_SELECTION_REQUIRED,null);}
        }
        if(spec.selectedPending()){
            if(!frame.selectedProofReady()){finish();return receipt(Outcome.HOLD,Reason.OBSERVATION_BLOCKED,null);}
            if(frame.selectedAllComplete()){finish();return receipt(Outcome.OBSERVED_VISIBLE_KNOWN_SET_COMPLETE,Reason.VISIBLE_KNOWN_SET_TICKS,null);}
            if(!frame.selectedBeforeB()){finish();return receipt(Outcome.HOLD,Reason.OBSERVATION_BLOCKED,null);}
        }
        if(frame.status()==LiveRewardCardObservation.Status.SECTION_COMPLETE_SKIP){
            finish();return receipt(Outcome.SECTION_ALREADY_COMPLETE_SKIP,Reason.REGION_COMPLETE,null);
        }
        if(frame.route()==LiveRewardCardObservation.Route.MORE_VISIBLE_ITEM&&frame.cards().size()==1&&frame.cards().get(0).complete()){
            // The explicit More spec has already uniquely identified this one completed item.
            // A caller intentionally selecting no incomplete task must still skip, not click.
            finish();return receipt(Outcome.TASK_ALREADY_COMPLETE_SKIP,Reason.TASK_TICK_ALREADY_VISIBLE,null);
        }
        boolean exact=false;
        for(var candidate:frame.cards())if(candidate==exactSelection)exact=true;
        if(!exact){finish();return receipt(Outcome.HOLD,Reason.EXACT_SELECTION_REQUIRED,null);}
        if(spec.selectedPending()&&(!exactSelection.configuredTarget(spec)||exactSelection.complete())){finish();return receipt(Outcome.HOLD,Reason.EXACT_SELECTION_REQUIRED,null);}
        if(exactSelection.complete()){
            finish();return receipt(Outcome.TASK_ALREADY_COMPLETE_SKIP,Reason.TASK_TICK_ALREADY_VISIBLE,null);
        }
        if(spec.boundedProfile()&&!LiveRewardCardObservation.pendingBoundedFirstFamily(frame)){
            finish();return receipt(Outcome.HOLD,Reason.OBSERVATION_BLOCKED,null);
        }
        selected=exactSelection;beforeFrame=frame;
        var b=selected.indicatorBox();
        var target=new ReviewShizukuTapActor.TapTarget((int)Math.floor(b.centerX()),(int)Math.floor(b.centerY()),width,height,rotation);
        var result=actor.tap(target,new ReviewShizukuTapActor.DispatchGate(){
            @Override public boolean consume(ReviewShizukuTapActor.TapTarget exact)throws Exception{
                if(tapAttempted)return false;
                tapAttempted=true; // Any gate entry, including partial/unknown durable burn, is spent.
                if(exact!=target||!valid(capture,ownUiHidden))return false;
                authority.consumeOneTap(exact);
                return true;
            }
            @Override public boolean stillValid(ReviewShizukuTapActor.TapTarget exact){
                if(exact!=target||!tapAttempted||!valid(capture,ownUiHidden))return false;
                actionElapsed=authority.elapsedRealtime();
                return actionElapsed>=capture.acquiredLowerBound&&actionElapsed<expires&&!revoked.get();
            }
        });
        if(!result.commandExitZero()||actionElapsed<0||revoked.get()){
            finish();return receipt(Outcome.HOLD,Reason.ACTOR_NOT_CONFIRMED,result);
        }
        if(spec.requiresNativeReturn()){
            // actor.tap has returned only after wait/exit/close. A cleanup failure changes
            // commandExitZero to DISPATCH_UNKNOWN, so it cannot issue this successful stamp.
            long success=authority.elapsedRealtime();
            if(success<actionElapsed||success>=expires||!current()){finish();return receipt(Outcome.HOLD,Reason.RETURN_NOT_READY,result);}
            successfulTapElapsed=success;
            var permit=new ReturnPermit(this,Math.min(expires,phaseDeadline),ownUiHidden);
            NativeBingReturnController.Result returnedResult;
            try{returnedResult=returner.returnAfterTap(permit);}
            catch(RuntimeException unknown){finish();return receipt(Outcome.HOLD,Reason.RETURN_NOT_READY,result);}
            returnSummary=returnedResult.summary();
            if(!returnedResult.readyFor(permit,authority.elapsedRealtime())){finish();return receipt(Outcome.HOLD,Reason.RETURN_NOT_READY,result);}
            returned=true;
        }
        waitingAfter=true;
        return receipt(Outcome.AWAITING_NEW_OBSERVATION,spec.requiresNativeReturn()?Reason.RETURN_READY_NOT_COMPLETION:Reason.TAP_EXITED_NOT_COMPLETION,result);
    }
    private Receipt verifyAfter(Capture capture,LiveRewardCardObservation.Frame after){
        if(!waitingAfter||selected==null||beforeFrame==null||capture.sequence<=1
            ||capture.acquiredLowerBound<=actionElapsed) {
            finish();return receipt(Outcome.HOLD,Reason.AFTER_NOT_FRESH,null);
        }
        if(beforeFrame.route()!=after.route()||after.route()!=spec.route()) {
            finish();return receipt(Outcome.HOLD,Reason.CONTEXT_CHANGED,null);
        }
        var before=beforeFrame.fraction();var next=after.fraction();
        boolean daily=after.route()==LiveRewardCardObservation.Route.DAILY_REGION_COUNTER;
        if(daily&&(before==null||next==null)
            ||(before==null)!=(next==null)
            ||before!=null&&(before.denominator()!=next.denominator()||next.numerator()<before.numerator())) {
            finish();return receipt(Outcome.HOLD,Reason.CONTEXT_CHANGED,null);
        }
        // Daily observes a whole bounded roster. More explicitly observes only its selected
        // visible item: offscreen/changed siblings cannot impersonate or block that item's tick.
        if(daily){
            if(beforeFrame.cards().size()!=after.cards().size()) {
                finish();return receipt(Outcome.HOLD,Reason.CONTEXT_CHANGED,null);
            }
            for(var prior:beforeFrame.cards()) {
                int matches=0;
                for(var card:after.cards())if(prior.sameTask(card))matches++;
                if(matches!=1){finish();return receipt(Outcome.HOLD,Reason.CONTEXT_CHANGED,null);}
            }
        }
        if(spec.uniquePending()){
            boolean complete=returned&&beforeFrame.uniqueBefore()&&after.uniqueAllComplete()&&after.cards().size()==beforeFrame.cards().size();finish();
            return receipt(complete?Outcome.OBSERVED_VISIBLE_MORE_SET_COMPLETE:Outcome.HOLD,complete?Reason.VISIBLE_MORE_SET_TICKS:Reason.SAME_TASK_NOT_COMPLETE,null);
        }
        if(spec.selectedPending()){
            boolean complete=returned&&beforeFrame.selectedBeforeB()&&after.selectedAllComplete();finish();
            return receipt(complete?Outcome.OBSERVED_VISIBLE_KNOWN_SET_COMPLETE:Outcome.HOLD,complete?Reason.VISIBLE_KNOWN_SET_TICKS:Reason.SAME_TASK_NOT_COMPLETE,null);
        }
        if(spec.requiresNativeReturn())return verifyFamilyAfter(after);
        LiveRewardCardObservation.Card same=null;int count=0;boolean compact=false;
        for(var card:after.cards()){
            if(selected.sameTask(card)){same=card;count++;}
            else if(!daily&&selected.matchesControlledCompactCompletion(card,spec)){same=card;count++;compact=true;}
        }
        boolean complete=count==1&&same.complete();
        finish();
        return receipt(complete?Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED:Outcome.HOLD,
            complete?(compact?Reason.SAME_TASK_COMPACT_NATIVE_TICK:Reason.SAME_TASK_NATIVE_TICK):Reason.SAME_TASK_NOT_COMPLETE,null);
    }
    private Receipt verifyFamilyAfter(LiveRewardCardObservation.Frame after){
        if(!returned||beforeFrame.cards().size()!=2||after.cards().size()!=2){finish();return receipt(Outcome.HOLD,Reason.CONTEXT_CHANGED,null);}
        java.util.HashSet<LiveRewardCardObservation.Card> unchanged=new java.util.HashSet<>();
        for(var before:beforeFrame.cards())if(before!=selected){
            LiveRewardCardObservation.Card match=null;int count=0;
            for(var candidate:after.cards())if(before.sameTask(candidate)&&before.complete()==candidate.complete()){match=candidate;count++;}
            if(count!=1||!unchanged.add(match)){finish();return receipt(Outcome.HOLD,Reason.CONTEXT_CHANGED,null);}
        }
        LiveRewardCardObservation.Card remaining=null;int count=0;
        for(var card:after.cards())if(!unchanged.contains(card)){remaining=card;count++;}
        boolean strict=count==1&&remaining.complete()&&selected.sameTask(remaining);
        boolean compact=count==1&&remaining.complete()&&selected.matchesRosterCompactCompletion(remaining,spec);
        finish();return receipt(strict||compact?Outcome.OBSERVED_TASK_COMPLETE_UNATTRIBUTED:Outcome.HOLD,
            strict?Reason.SAME_TASK_NATIVE_TICK:compact?Reason.SAME_TASK_ROSTER_COMPACT_NATIVE_TICK:Reason.SAME_TASK_NOT_COMPLETE,null);
    }
    boolean returnReady(){return returned&&!revoked.get()&&authority.elapsedRealtime()<expires;}
    String returnSummary(){return returnSummary;}
    /** Nonblocking UI/dump hint, not capture/action authorization. No source IPC or scope I/O.
     * Worker-owned startCapture/acceptAfterCleanup recheck full authority independently. */
    boolean needsAfter(){
        if(!waitingAfter||finished||revoked.get()||spec.requiresNativeReturn()&&!returned)return false;
        try{long now=authority.elapsedRealtime();return now>=0&&now<expires;}
        catch(RuntimeException unknown){return false;}
    }
    void cancel(){revoked.set(true);}
    private void finish(){finished=true;waitingAfter=false;selected=null;beforeFrame=null;active=null;}
    private static Receipt receipt(Outcome o,Reason r,ReviewShizukuTapActor.Result actor){return new Receipt(o,r,actor);}
    private static boolean shape(int w,int h,int r){return w>0&&h>0&&w<=4096&&h<=4096
        &&(long)w*h<=4L*1024*1024&&r>=0&&r<=3;}
}



