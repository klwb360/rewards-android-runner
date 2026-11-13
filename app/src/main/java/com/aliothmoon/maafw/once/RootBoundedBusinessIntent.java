package com.aliothmoon.maafw.once;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Original visible business intent. Six separate observation claims, one logical adopt per phase. */
final class RootBoundedBusinessIntent {
    private final RealCaptureClaim.ProcessEpoch epoch;
    private final Object rootOwner,visibleOwner,request;
    private final long issued,sessionEnd;
    private final LongSupplier utcClock;
    private final boolean live,selectedPending,uniquePending;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final Object[][] owners=new Object[2][3];
    private final long[] phaseEnds=new long[2];
    private NativeAutonomousCardRun business;
    private NativeRewardCardSession.Phase nextPhase=NativeRewardCardSession.Phase.BEFORE;
    private int nextAttempt=1;
    private BingNavigationSnapshot.Ready original;
    private RootBoundedBusinessIntent(VisibleCaptureAuthority.BoundedApproval a){
        epoch=a.epoch;rootOwner=a.rootOwner;visibleOwner=a.visibleOwner;request=a.request;
        issued=a.issued;sessionEnd=a.sessionEnd;utcClock=a.utcClock;live=a.live;selectedPending=a.selectedPending;uniquePending=a.uniquePending;
    }
    static RootBoundedBusinessIntent fromVisible(VisibleCaptureAuthority.BoundedApproval a,long now){
        if(a==null||a.selectedPending||a.uniquePending||!a.consume(now))throw new SecurityException("Original bounded business approval required");
        return new RootBoundedBusinessIntent(a);
    }
    static RootBoundedBusinessIntent fromSelectedVisible(VisibleCaptureAuthority.BoundedApproval a,long now){
        if(a==null||!a.selectedPending||a.uniquePending||!a.consume(now))throw new SecurityException("New selected B approval required");return new RootBoundedBusinessIntent(a);
    }
    static RootBoundedBusinessIntent fromUniqueVisible(VisibleCaptureAuthority.BoundedApproval a,long now){
        if(a==null||!a.uniquePending||a.selectedPending||!a.consume(now))throw new SecurityException("New unique pending approval required");return new RootBoundedBusinessIntent(a);
    }
    boolean uniquePending(){return uniquePending;}
    int maxPerPhase(){return uniquePending?RootUniqueRefinedScope.MAX_PER_PHASE:selectedPending?RootSelectedPendingScope.MAX_PER_PHASE:RootBoundedBusinessScope.MAX_PER_PHASE;}
    boolean validIndex(int n){return uniquePending?RootUniqueRefinedScope.validIndex(n):selectedPending?RootSelectedPendingScope.validIndex(n):RootBoundedBusinessScope.validIndex(n);}
    long captureTtl(){return uniquePending?RootUniqueRefinedScope.CAPTURE_TTL:selectedPending?RootSelectedPendingScope.CAPTURE_TTL:RootBoundedBusinessScope.CAPTURE_TTL;}
    boolean selectedPending(){return selectedPending;}
    boolean live(){return live;}long sessionEnd(){return sessionEnd;}
    boolean current(long now){try{return !cancelled.get()&&now>=issued&&now<sessionEnd&&(uniquePending?RootUniqueRefinedScope.current(utcClock.getAsLong()):selectedPending?RootSelectedPendingScope.current(utcClock.getAsLong()):RootBoundedBusinessScope.current(utcClock.getAsLong()));}catch(RuntimeException unknown){return false;}}
    void cancel(){cancelled.set(true);}
    boolean matchesOwner(Object root,Object visible,Object req){return root==rootOwner&&visible==visibleOwner&&req==request;}
    boolean authorizes(DormantCaptureConfirmationActivity activity){return live&&activity==visibleOwner&&activity.requestIdentity()==request&&activity.isResumedForApproval();}
    synchronized void bindRun(NativeAutonomousCardRun exact){if(exact==null||business!=null||exact.selectedPendingOnly()!=selectedPending||exact.uniquePendingOnly()!=uniquePending)throw new SecurityException("One business run required");business=exact;}
    synchronized boolean matchesRun(NativeAutonomousCardRun exact){return exact!=null&&business==exact;}
    synchronized boolean sourceMatches(BingNavigationSnapshot.Ready source){
        if(source==null||source.kind()!=BingNavigationSnapshot.Kind.TEMPLATE||(uniquePending||selectedPending)&&!source.task().equals(Integer.toString(uniquePending?RootUniqueRefinedScope.SOURCE_TASK_ID:RootSelectedPendingScope.SOURCE_TASK_ID)))return false;
        if(original==null){if(nextPhase!=NativeRewardCardSession.Phase.BEFORE)return false;original=source;return true;}
        return original.sameSource(source);
    }
    synchronized FramePermit claimFrame(NativeRewardCardSession.Phase phase,int attempt,RealCaptureClaim.ProcessEpoch e,
            Object owner,long now,long deadline,CaptureSessionRuntime.BoundedTransition previous,boolean forLive){
        if(forLive!=live||phase==null||e!=epoch||owner==null||business==null||!current(now)||deadline<=now||deadline>sessionEnd
                ||deadline>now+captureTtl()||!validIndex(attempt))throw new SecurityException("Exact bounded phase required");
        boolean first=phase==NativeRewardCardSession.Phase.BEFORE&&attempt==1;
        if(first){if(previous!=null||nextAttempt!=1||owners[0][0]!=null)throw new SecurityException("First claim already spent");}
        else {
            if(previous==null||!previous.consume(this,phase,attempt,epoch,now))throw new SecurityException("Exact prior closed candidate required");
            if(phase==NativeRewardCardSession.Phase.AFTER&&attempt==1){nextPhase=phase;nextAttempt=1;}
        }
        if(phase!=nextPhase||attempt!=nextAttempt)throw new SecurityException("Sequential phase candidate required");
        for(Object[] row:owners)for(Object used:row)if(used==owner)throw new SecurityException("Distinct candidate owner required");
        int i=phase.ordinal();if(attempt==1){if(phaseEnds[i]!=0)throw new SecurityException("Logical phase already reserved");phaseEnds[i]=deadline;}
        else if(deadline!=phaseEnds[i])throw new SecurityException("Logical phase deadline cannot change");
        owners[i][attempt-1]=owner;nextAttempt++;
        return new FramePermit(this,phase,attempt,e,owner,now,deadline,live);
    }
    static final class FramePermit {
        final RootBoundedBusinessIntent intent;final NativeRewardCardSession.Phase phase;final int attempt;
        final RealCaptureClaim.ProcessEpoch epoch;final Object owner;final long issued,deadline;final boolean live;
        private FramePermit(RootBoundedBusinessIntent i,NativeRewardCardSession.Phase p,int n,RealCaptureClaim.ProcessEpoch e,Object o,long now,long end,boolean live){intent=i;phase=p;attempt=n;epoch=e;owner=o;issued=now;deadline=end;this.live=live;}
        boolean current(RealCaptureClaim.ProcessEpoch e,Object o,long now){return e==epoch&&o==owner&&now>=issued&&now<deadline&&intent.current(now);}
    }
}
