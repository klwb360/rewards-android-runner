package com.aliothmoon.maafw.once;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Opaque in-process root intent. Not an Android Intent, persisted permission or action evidence.
 * Each mode is independently visible-issued. PREFLIGHT cannot produce a second frame or input. */
final class RootCaptureIntent {
    private final RootCaptureScope.Mode mode;
    private final RealCaptureClaim.ProcessEpoch epoch;
    private final Object rootOwner,visibleOwner,request;
    private final long issued,sessionEnd;
    private final boolean live;
    private final LongSupplier utcClock;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final PhasePermit[] permits=new PhasePermit[2];
    private final boolean[] phaseAttempted=new boolean[2],published=new boolean[2];
    private boolean finished,passClaimed,continuationClaimed;
    private PreflightPass preflightPass;
    private Continuation continuation;

    private RootCaptureIntent(VisibleCaptureAuthority.RootApproval approval){
        mode=approval.mode;epoch=approval.epoch;rootOwner=approval.rootOwner;
        visibleOwner=approval.visibleOwner;request=approval.request;
        issued=approval.issued;sessionEnd=approval.sessionEnd;live=approval.live;utcClock=approval.utcClock;
    }
    /** Only the private-constructor visible-issuer token can enter this factory. */
    static RootCaptureIntent fromVisible(VisibleCaptureAuthority.RootApproval approval,
            RealCaptureClaim.ProcessEpoch expected,Object owner,long now,PreflightPass pass){
        if(approval==null||!approval.consume(expected,owner,now))throw new SecurityException("Original visible root approval required");
        if(approval.mode==RootCaptureScope.Mode.PREFLIGHT){if(pass!=null)throw new SecurityException("Preflight cannot consume business authority");}
        else if(pass==null||!pass.consumeForBusiness(expected,approval.visibleOwner,approval.request,now,approval.live))
            throw new SecurityException("A published clean preflight and a new visible business intent are required");
        return new RootCaptureIntent(approval);
    }
    RootCaptureScope.Mode mode(){return mode;}
    long sessionEnd(){return sessionEnd;}
    long deadline(){return sessionEnd;}
    boolean live(){return live;}
    Object rootOwner(){return rootOwner;}
    private boolean authorityCurrent(long now){
        if(live&&(RootBoundedBusinessScope.ACTIVE||RootImageSeriesScope.ACTIVE))return false;
        if(cancelled.get()||now<issued||now>=sessionEnd)return false;
        try{return live&&RootFamilyDiagnosticProfile.ACTIVE?RootFamilyDiagnosticProfile.current(utcClock.getAsLong())
            :RootCaptureScope.current(utcClock.getAsLong());}catch(RuntimeException unavailable){return false;}
    }
    synchronized boolean current(long now){return !finished&&authorityCurrent(now);}
    void cancel(){cancelled.set(true);}

    /** Private phase capability, never reconstructed from a phase name or receipt string. */
    static final class PhasePermit {
        final RootCaptureIntent intent;final RootCaptureScope.Phase phase;
        final RealCaptureClaim.ProcessEpoch epoch;final Object requestOwner;
        final long issued,deadline;final boolean live;
        private PhasePermit(RootCaptureIntent intent,RootCaptureScope.Phase phase,Object owner,long now,long end){
            this.intent=intent;this.phase=phase;epoch=intent.epoch;requestOwner=owner;issued=now;deadline=end;live=intent.live;
        }
        boolean current(RealCaptureClaim.ProcessEpoch exact,Object owner,long now){
            synchronized(intent){return epoch==exact&&requestOwner==owner&&intent.permits[phase.ordinal()]==this
                &&!intent.published[phase.ordinal()]&&now>=issued&&now<deadline&&intent.authorityCurrent(now);}
        }
    }
    synchronized PhasePermit claimPhase(RootCaptureScope.Phase phase,RealCaptureClaim.ProcessEpoch exact,
            Object requestOwner,long now,long absolutePhaseDeadline,Continuation incoming,boolean forLiveProof){
        if(phase==null||phaseAttempted[phase.ordinal()])throw new SecurityException("Root phase attempt already spent");
        phaseAttempted[phase.ordinal()]=true; // A rejected/uncertain attempt is never replayed.
        if(exact!=epoch||requestOwner==null||forLiveProof!=live||!current(now)
                ||!RootCaptureScope.allowed(mode,phase)||absolutePhaseDeadline<=now||absolutePhaseDeadline>sessionEnd
                ||now>Long.MAX_VALUE-RootCaptureScope.CAPTURE_TTL||absolutePhaseDeadline>now+RootCaptureScope.CAPTURE_TTL
                ||!(live&&RootFamilyDiagnosticProfile.ACTIVE?RootFamilyDiagnosticProfile.admissionCurrent(utcClock.getAsLong())
                :RootCaptureScope.admissionCurrent(utcClock.getAsLong())))throw new SecurityException("Current original root phase required");
        if(phase==RootCaptureScope.Phase.FIRST){if(incoming!=null)throw new SecurityException("Initial root phase has no continuation");}
        else if(mode!=RootCaptureScope.Mode.BUSINESS||!published[0]||incoming==null||incoming!=continuation
                ||!incoming.consume(this,exact,now))throw new SecurityException("Original opaque continuation required");
        PhasePermit permit=new PhasePermit(this,phase,requestOwner,now,absolutePhaseDeadline);permits[phase.ordinal()]=permit;return permit;
    }

    /** Runtime owns the private-constructor publication and the actual native/cleanup/commit checks.
     * No bool, String, old grant or UI state can directly create a pass or continuation here. */
    synchronized boolean acceptPublished(CaptureSessionRuntime.RootPublication publication,long now){
        if(publication==null)return false;
        RootCaptureScope.Phase phase=publication.phase();
        if(phase==null||published[phase.ordinal()])return false;
        PhasePermit permit=permits[phase.ordinal()];
        if(permit==null||publication.intent()!=this||publication.epoch()!=epoch||publication.rootOwner()!=rootOwner
                ||publication.requestOwner()!=permit.requestOwner||publication.phaseDeadline()!=permit.deadline
                ||!permit.current(epoch,permit.requestOwner,now))return false;
        ReviewShizukuPngBinding.CleanReceipt receipt=publication.cleanReceipt();
        if(receipt==null||!receipt.clean()||receipt.liveTransport()!=live||!publication.consume(this,now))return false;
        published[phase.ordinal()]=true;
        if(mode==RootCaptureScope.Mode.PREFLIGHT){
            if(phase!=RootCaptureScope.Phase.FIRST){cancel();return false;}
            preflightPass=new PreflightPass(this);finished=true;
        }else if(phase==RootCaptureScope.Phase.FIRST){
            if(publication.returnReady())continuation=new Continuation(this);
            else finished=true; // A semantic HOLD cannot open another capture or input.
        }else finished=true;
        return true;
    }
    synchronized PreflightPass claimPreflightPass(RealCaptureClaim.ProcessEpoch exact,Object owner,long now){
        if(passClaimed)return null;passClaimed=true;
        return mode==RootCaptureScope.Mode.PREFLIGHT&&exact==epoch&&owner==rootOwner&&preflightPass!=null
            &&published[0]&&authorityCurrent(now)?preflightPass:null;
    }
    synchronized Continuation claimOpaqueContinuation(RealCaptureClaim.ProcessEpoch exact,Object owner,long now){
        if(continuationClaimed)return null;continuationClaimed=true;
        return mode==RootCaptureScope.Mode.BUSINESS&&exact==epoch&&owner==rootOwner&&continuation!=null
            &&published[0]&&!finished&&authorityCurrent(now)?continuation:null;
    }
    static final class PreflightPass {
        private final RootCaptureIntent intent;private boolean consumed;
        private PreflightPass(RootCaptureIntent intent){this.intent=intent;}
        synchronized boolean eligible(RealCaptureClaim.ProcessEpoch epoch,Object visibleOwner,Object request,long now,boolean live){
            synchronized(intent){return !consumed&&intent.epoch==epoch&&intent.visibleOwner==visibleOwner&&intent.request==request
                &&intent.live==live&&intent.published[0]&&intent.authorityCurrent(now);}
        }
        private synchronized boolean consumeForBusiness(RealCaptureClaim.ProcessEpoch epoch,Object visibleOwner,Object request,long now,boolean live){
            boolean valid=eligible(epoch,visibleOwner,request,now,live);consumed=true;return valid;
        }
    }
    static final class Continuation {
        private final RootCaptureIntent intent;private boolean consumed;
        private Continuation(RootCaptureIntent intent){this.intent=intent;}
        private synchronized boolean consume(RootCaptureIntent exact,RealCaptureClaim.ProcessEpoch epoch,long now){
            if(consumed)return false;consumed=true;
            return intent==exact&&intent.epoch==epoch&&intent.published[0]&&intent.authorityCurrent(now);
        }
    }
}