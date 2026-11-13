package com.aliothmoon.maafw.once;

/** In-memory visible confirmation, never an Intent, Bundle, package-name or disk capability. */
final class VisibleCaptureAuthority {
    static final class Proof {
        private final RealCaptureClaim.ProcessEpoch epoch;
        private final Object owner, request;
        private final long issued, deadline;
        private boolean used;
        private final RootCaptureIntent.PhasePermit rootPermit;
        private final RootImageSeriesIntent.FramePermit seriesPermit;
        private final RootBoundedBusinessIntent.FramePermit boundedPermit;
        private final RootReturnReadIntent.FramePermit returnReadPermit;
        private Proof(RealCaptureClaim.ProcessEpoch epoch,Object owner,Object request,long now) {
            this(epoch,owner,request,now,now+BusinessCaptureScope.TTL);
        }
        private Proof(RealCaptureClaim.ProcessEpoch epoch,Object owner,Object request,long now,long deadline) {
            this.epoch=epoch;this.owner=owner;this.request=request;this.issued=now;this.deadline=deadline;this.rootPermit=null;this.seriesPermit=null;this.boundedPermit=null;this.returnReadPermit=null;
        }
        private Proof(RootCaptureIntent.PhasePermit permit,Object request,long now){
            epoch=permit.epoch;owner=permit.requestOwner;this.request=request;issued=now;deadline=permit.deadline;rootPermit=permit;seriesPermit=null;boundedPermit=null;returnReadPermit=null;
        }
        private Proof(RootImageSeriesIntent.FramePermit permit,Object request,long now){
            epoch=permit.epoch;owner=permit.owner;this.request=request;issued=now;deadline=permit.deadline;rootPermit=null;seriesPermit=permit;boundedPermit=null;returnReadPermit=null;
        }
        private Proof(RootBoundedBusinessIntent.FramePermit permit,Object request,long now){
            epoch=permit.epoch;owner=permit.owner;this.request=request;issued=now;deadline=permit.deadline;rootPermit=null;seriesPermit=null;boundedPermit=permit;returnReadPermit=null;
        }
        private Proof(RootReturnReadIntent.FramePermit permit,Object request,long now){
            epoch=permit.epoch;owner=permit.owner;this.request=request;issued=now;deadline=permit.deadline;rootPermit=null;seriesPermit=null;boundedPermit=null;returnReadPermit=permit;
        }
        synchronized boolean consume(RealCaptureClaim.ProcessEpoch e,Object o,long now) {
            if(used)return false;used=true;
            return e==epoch && o==owner && request!=null && now>=issued && now<deadline
                &&(rootPermit==null||rootPermit.live&&rootPermit.current(e,o,now))
                &&(seriesPermit==null||seriesPermit.live&&seriesPermit.current(e,o,now))
                &&(boundedPermit==null||boundedPermit.live&&boundedPermit.current(e,o,now))
                &&(returnReadPermit==null||returnReadPermit.live&&returnReadPermit.current(e,o,now));
        }
        long deadline(){return deadline;}
    }
    static Proof issue(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch epoch,Object owner,long now) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        if(RootBoundedBusinessScope.ACTIVE)throw new SecurityException("Bounded business live issuer only");
        CaptureReleaseGate.requireConfigured(); BusinessCaptureScope.requireCurrent();
        if(now<0 || now>Long.MAX_VALUE-BusinessCaptureScope.TTL || activity==null || click==null || epoch==null || owner==null
                || !click.consume(activity,activity.requestIdentity(),owner,epoch))
            throw new SecurityException("Fresh first-party visible confirmation required");
        return new Proof(epoch,owner,activity.requestIdentity(),now);
    }
    /** Independent native-v15 visible grant. The expired v13 issue() path remains unchanged. */
    static Proof issueNative(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch epoch,Object owner,long now) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        if(RootBoundedBusinessScope.ACTIVE)throw new SecurityException("Bounded business live issuer only");
        CaptureReleaseGate.requireConfigured();
        long deadline=NativeCheckInScope.captureDeadline(now,System.currentTimeMillis());
        if(activity==null||click==null||epoch==null||owner==null
                ||!click.consume(activity,activity.requestIdentity(),owner,epoch))
            throw new SecurityException("Fresh first-party native confirmation required");
        return new Proof(epoch,owner,activity.requestIdentity(),now,deadline);
    }
    static Proof issueCurrentStatus(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch epoch,Object owner,long now) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        if(RootBoundedBusinessScope.ACTIVE)throw new SecurityException("Bounded business live issuer only");
        CaptureReleaseGate.requireConfigured();
        long deadline=CurrentCheckInStatus.deadline(now,System.currentTimeMillis());
        if(activity==null||click==null||epoch==null||owner==null
                ||!click.consume(activity,activity.requestIdentity(),owner,epoch))
            throw new SecurityException("Fresh first-party current-status confirmation required");
        return new Proof(epoch,owner,activity.requestIdentity(),now,deadline);
    }
    static Proof issueCard(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch epoch,Object owner,long now) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        if(RootBoundedBusinessScope.ACTIVE)throw new SecurityException("Bounded business live issuer only");
        CaptureReleaseGate.requireConfigured();
        long deadline=NativeRewardCardScope.deadline(now,System.currentTimeMillis());
        if(activity==null||click==null||epoch==null||owner==null
                ||!click.consume(activity,activity.requestIdentity(),owner,epoch))
            throw new SecurityException("Fresh first-party card confirmation required");
        return new Proof(epoch,owner,activity.requestIdentity(),now,deadline);
    }
    static Proof issueReadOnly(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch epoch,Object owner,long now,ReadOnlyTwoStageRun run){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        if(RootBoundedBusinessScope.ACTIVE)throw new SecurityException("Bounded business live issuer only");
        CaptureReleaseGate.requireConfigured();
        if(run==null||!run.current())throw new SecurityException("Read-only run required");
        long deadline=run.deadline(now);
        if(activity==null||click==null||epoch==null||owner==null||!click.consume(activity,activity.requestIdentity(),owner,epoch))
            throw new SecurityException("Fresh visible read-only stage required");
        return new Proof(epoch,owner,activity.requestIdentity(),now,deadline);
    }
    static Proof issueAutonomous(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch epoch,Object owner,long now,
            NativeAutonomousCardRun run,NativeRewardCardSession.Phase phase){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        if(RootBoundedBusinessScope.ACTIVE)throw new SecurityException("Bounded business live issuer only");
        CaptureReleaseGate.requireConfigured();
        if(run==null||phase==null||!run.current())throw new SecurityException("Live autonomous run required");
        long deadline=run.deadline(now);
        if(activity==null||click==null||epoch==null||owner==null
                ||!activity.autonomousApprovalMatches(run,phase,click)
                ||!click.consume(activity,activity.requestIdentity(),owner,epoch))
            throw new SecurityException("Original two-stage intent and fresh visible approval required");
        return new Proof(epoch,owner,activity.requestIdentity(),now,deadline);
    }
    static final class ReturnReadApproval {
        final RealCaptureClaim.ProcessEpoch epoch;final Object root,visible,request;final long issued,end;final boolean live,currentOnly,statusRecovery;boolean progressRead,progressScan,progressLocate;ReadOnlyCheckpoint checkpoint;final java.util.function.LongSupplier utc;private boolean spent;
        private ReturnReadApproval(RealCaptureClaim.ProcessEpoch e,Object r,Object v,Object q,long n,long d,boolean l,java.util.function.LongSupplier c){this(e,r,v,q,n,d,l,c,false);}
        private ReturnReadApproval(RealCaptureClaim.ProcessEpoch e,Object r,Object v,Object q,long n,long d,boolean l,java.util.function.LongSupplier c,boolean currentOnly){this(e,r,v,q,n,d,l,c,currentOnly,false);}
        private ReturnReadApproval(RealCaptureClaim.ProcessEpoch e,Object r,Object v,Object q,long n,long d,boolean l,java.util.function.LongSupplier c,boolean currentOnly,boolean recovery){epoch=e;root=r;visible=v;request=q;issued=n;end=d;live=l;utc=c;this.currentOnly=currentOnly;statusRecovery=recovery;}
        synchronized boolean consume(long now){if(spent)return false;spent=true;return epoch!=null&&root!=null&&visible!=null&&request!=null&&utc!=null&&now>=issued&&now<end;}
    }
    static RootReturnReadIntent issueReturnReadIntent(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch e,Object root,long now){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        CaptureReleaseGate.requireConfigured();
        if(!RootReturnReadScope.ACTIVE||a==null||click==null||e==null||root==null||!a.returnReadApprovalMatches(root,click)||!click.consume(a,a.requestIdentity(),root,e))throw new SecurityException("Fresh independent recovery click required");
        return RootReturnReadIntent.fromVisible(new ReturnReadApproval(e,root,a,a.requestIdentity(),now,RootReturnReadScope.sessionDeadline(now,System.currentTimeMillis()),true,System::currentTimeMillis),now);
    }
    static Proof issueReturnReadFrame(RootReturnReadIntent i,int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        CaptureReleaseGate.requireConfigured();if(!RootReturnReadScope.ACTIVE||i==null||!i.live())throw new SecurityException("Live recovery intent required");
        return new Proof(i.claimFrame(index,e,owner,now,previous,true),i,now);
    }
    static RootReturnReadIntent fixtureReturnReadIntent(Object visible,Object request,RealCaptureClaim.ProcessEpoch e,Object root,long now,java.util.function.LongSupplier utc){
        return RootReturnReadIntent.fromVisible(new ReturnReadApproval(e,root,visible,request,now,Math.min(now+50_000,RootReturnReadScope.sessionDeadline(now,utc.getAsLong())),false,utc),now);
    }
    static RealCaptureClaim.Grant fixtureReturnReadGrant(RootReturnReadIntent i,int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous){
        if(i==null||i.live()||i.currentOnly()||i.statusRecovery())throw new SecurityException("Nonlive return fixture required");var permit=i.claimFrame(index,e,owner,now,previous,false);
        return RealCaptureClaim.fixture(e,owner,now,permit.deadline-now,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
    }
    static RootReturnReadIntent issueProgressLocateIntent(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch e,Object root,long now){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Structure-only live build");
        CaptureReleaseGate.requireConfigured();if(!RootProgressLocateScope.ACTIVE||a==null||click==null||e==null||root==null||!a.progressLocateApprovalMatches(root,click)||!click.consume(a,a.requestIdentity(),root,e))throw new SecurityException("Fresh bounded locate approval required");
        var approval=new ReturnReadApproval(e,root,a,a.requestIdentity(),now,RootProgressLocateScope.sessionDeadline(now,System.currentTimeMillis()),true,System::currentTimeMillis,true);approval.progressLocate=true;return RootReturnReadIntent.fromCurrentVisible(approval,now);
    }
    static Proof issueProgressLocateFrame(RootReturnReadIntent i,int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Structure-only live build");
        CaptureReleaseGate.requireConfigured();if(!RootProgressLocateScope.ACTIVE||i==null||!i.progressLocate()||!i.currentOnly()||!i.live())throw new SecurityException("Live bounded locate intent required");return new Proof(i.claimFrame(index,e,owner,now,previous,true),i,now);
    }
    static RootReturnReadIntent issueCheckpointIntent(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch e,Object root,long now){
        if(!ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Fixed structure checkpoint required");
        CaptureReleaseGate.requireConfigured();var policy=ReadOnlyCheckpoint.CURRENT;
        if(policy!=ReadOnlyCheckpoint.CURRENT||a==null||click==null||e==null||root==null||!a.checkpointApprovalMatches(root,click)||!click.consume(a,a.requestIdentity(),root,e))throw new SecurityException("Fresh compiled checkpoint approval required");
        var approval=new ReturnReadApproval(e,root,a,a.requestIdentity(),now,policy.sessionDeadline(now,System.currentTimeMillis()),true,System::currentTimeMillis,true);approval.checkpoint=policy;return RootReturnReadIntent.fromCurrentVisible(approval,now);
    }
    static Proof issueCheckpointFrame(RootReturnReadIntent i,int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous){
        if(!ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Fixed structure checkpoint required");
        CaptureReleaseGate.requireConfigured();if(i==null||i.uiStructure()||i.checkpoint()!=ReadOnlyCheckpoint.CURRENT||index!=1||previous!=null||!i.currentOnly()||!i.live())throw new SecurityException("Only one compiled checkpoint frame");return new Proof(i.claimFrame(index,e,owner,now,null,true),i,now);
    }
    static RootReturnReadIntent issueProgressScanIntent(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch e,Object root,long now){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        CaptureReleaseGate.requireConfigured();if(!RootProgressScanScope.ACTIVE||a==null||click==null||e==null||root==null||!a.progressScanApprovalMatches(root,click)||!click.consume(a,a.requestIdentity(),root,e))throw new SecurityException("Fresh bounded scan approval required");
        var approval=new ReturnReadApproval(e,root,a,a.requestIdentity(),now,RootProgressScanScope.sessionDeadline(now,System.currentTimeMillis()),true,System::currentTimeMillis,true);approval.progressRead=true;approval.progressScan=true;return RootReturnReadIntent.fromCurrentVisible(approval,now);
    }
    static Proof issueProgressScanFrame(RootReturnReadIntent i,int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        CaptureReleaseGate.requireConfigured();if(!RootProgressScanScope.ACTIVE||i==null||!i.progressScan()||!i.currentOnly()||!i.live())throw new SecurityException("Live bounded scan intent required");return new Proof(i.claimFrame(index,e,owner,now,previous,true),i,now);
    }
    static RootReturnReadIntent issueProgressReadIntent(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch e,Object root,long now){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        CaptureReleaseGate.requireConfigured();
        if(!RootProgressReadScope.ACTIVE||a==null||click==null||e==null||root==null||!a.progressReadApprovalMatches(root,click)||!click.consume(a,a.requestIdentity(),root,e))throw new SecurityException("Fresh progress-only approval required");
        var approval=new ReturnReadApproval(e,root,a,a.requestIdentity(),now,RootProgressReadScope.sessionDeadline(now,System.currentTimeMillis()),true,System::currentTimeMillis,true);approval.progressRead=true;
        return RootReturnReadIntent.fromCurrentVisible(approval,now);
    }
    static Proof issueProgressReadFrame(RootReturnReadIntent i,int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        CaptureReleaseGate.requireConfigured();if(!RootProgressReadScope.ACTIVE||i==null||!i.progressRead()||!i.currentOnly()||!i.live())throw new SecurityException("Live progress-only intent required");
        return new Proof(i.claimFrame(index,e,owner,now,previous,true),i,now);
    }
    static RootReturnReadIntent issueCurrentReadIntent(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch e,Object root,long now){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        CaptureReleaseGate.requireConfigured();if(!RootCurrentCardScope.ACTIVE||a==null||click==null||e==null||root==null||!a.currentReadApprovalMatches(root,click)||!click.consume(a,a.requestIdentity(),root,e))throw new SecurityException("Fresh current-only visible approval required");
        return RootReturnReadIntent.fromCurrentVisible(new ReturnReadApproval(e,root,a,a.requestIdentity(),now,RootCurrentCardScope.sessionDeadline(now,System.currentTimeMillis()),true,System::currentTimeMillis,true),now);
    }
    static Proof issueCurrentReadFrame(RootReturnReadIntent i,int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        CaptureReleaseGate.requireConfigured();if(!RootCurrentCardScope.ACTIVE||i==null||!i.currentOnly()||!i.live())throw new SecurityException("Live current-only intent required");
        return new Proof(i.claimFrame(index,e,owner,now,previous,true),i,now);
    }
    static RootReturnReadIntent fixtureCurrentReadIntent(Object visible,Object request,RealCaptureClaim.ProcessEpoch e,Object root,long now,java.util.function.LongSupplier utc){
        return RootReturnReadIntent.fromCurrentVisible(new ReturnReadApproval(e,root,visible,request,now,Math.min(now+50_000,RootCurrentCardScope.sessionDeadline(now,utc.getAsLong())),false,utc,true),now);
    }
    static RealCaptureClaim.Grant fixtureCurrentReadGrant(RootReturnReadIntent i,int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous){
        if(i==null||i.live()||!i.currentOnly()||i.statusRecovery())throw new SecurityException("Nonlive current-only fixture required");var permit=i.claimFrame(index,e,owner,now,previous,false);
        return RealCaptureClaim.fixture(e,owner,now,permit.deadline-now,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
    }
    static RootReturnReadIntent issueStatusRecoveryIntent(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch e,Object root,long now){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        CaptureReleaseGate.requireConfigured();if(!RootStatusRecoveryScope.ACTIVE||a==null||click==null||e==null||root==null||!a.statusRecoveryApprovalMatches(root,click)||!click.consume(a,a.requestIdentity(),root,e))throw new SecurityException("Fresh status recovery approval required");
        return RootReturnReadIntent.fromStatusVisible(new ReturnReadApproval(e,root,a,a.requestIdentity(),now,RootStatusRecoveryScope.sessionDeadline(now,System.currentTimeMillis()),true,System::currentTimeMillis,false,true),now);
    }
    static Proof issueStatusRecoveryFrame(RootReturnReadIntent i,int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        CaptureReleaseGate.requireConfigured();if(!RootStatusRecoveryScope.ACTIVE||i==null||!i.statusRecovery()||!i.live())throw new SecurityException("Live status recovery intent required");
        return new Proof(i.claimFrame(index,e,owner,now,previous,true),i,now);
    }
    static RootReturnReadIntent fixtureStatusRecoveryIntent(Object visible,Object request,RealCaptureClaim.ProcessEpoch e,Object root,long now,java.util.function.LongSupplier utc){
        return RootReturnReadIntent.fromStatusVisible(new ReturnReadApproval(e,root,visible,request,now,Math.min(now+50_000,RootStatusRecoveryScope.sessionDeadline(now,utc.getAsLong())),false,utc,false,true),now);
    }
    static RealCaptureClaim.Grant fixtureStatusRecoveryGrant(RootReturnReadIntent i,int index,RealCaptureClaim.ProcessEpoch e,Object owner,long now,CaptureSessionRuntime.ReturnReadCompletion previous){
        if(i==null||i.live()||!i.statusRecovery())throw new SecurityException("Nonlive status recovery fixture required");var permit=i.claimFrame(index,e,owner,now,previous,false);
        return RealCaptureClaim.fixture(e,owner,now,permit.deadline-now,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
    }
    static final class BoundedApproval {
        final RealCaptureClaim.ProcessEpoch epoch;final Object rootOwner,visibleOwner,request;
        final long issued,sessionEnd;final boolean live,selectedPending,uniquePending;final java.util.function.LongSupplier utcClock;
        private boolean spent;
        private BoundedApproval(RealCaptureClaim.ProcessEpoch e,Object root,Object visible,Object req,long now,long end,boolean live,java.util.function.LongSupplier utc){this(e,root,visible,req,now,end,live,utc,false);}
        private BoundedApproval(RealCaptureClaim.ProcessEpoch e,Object root,Object visible,Object req,long now,long end,boolean live,java.util.function.LongSupplier utc,boolean selected){this(e,root,visible,req,now,end,live,utc,selected,false);}
        private BoundedApproval(RealCaptureClaim.ProcessEpoch e,Object root,Object visible,Object req,long now,long end,boolean live,java.util.function.LongSupplier utc,boolean selected,boolean unique){if(selected&&unique)throw new IllegalArgumentException("Exclusive bounded mode");epoch=e;rootOwner=root;visibleOwner=visible;request=req;issued=now;sessionEnd=end;this.live=live;utcClock=utc;selectedPending=selected;uniquePending=unique;}
        synchronized boolean consume(long now){if(spent)return false;spent=true;return epoch!=null&&rootOwner!=null&&visibleOwner!=null&&request!=null&&utcClock!=null&&now>=issued&&now<sessionEnd;}
    }
    static RootBoundedBusinessIntent issueBoundedIntent(DormantCaptureConfirmationActivity activity,DormantCaptureConfirmationActivity.LiveClick click,
            RealCaptureClaim.ProcessEpoch epoch,Object root,long now){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        CaptureReleaseGate.requireConfigured();
        if(!RootBoundedBusinessScope.ACTIVE||activity==null||click==null||epoch==null||root==null
            ||!activity.boundedApprovalMatches(root,click)||!click.consume(activity,activity.requestIdentity(),root,epoch))throw new SecurityException("Fresh bounded business approval required");
        return RootBoundedBusinessIntent.fromVisible(new BoundedApproval(epoch,root,activity,activity.requestIdentity(),now,
            RootBoundedBusinessScope.sessionDeadline(now,System.currentTimeMillis()),true,System::currentTimeMillis),now);
    }
    static Proof issueBoundedFrame(RootBoundedBusinessIntent intent,NativeRewardCardSession.Phase phase,int attempt,
            RealCaptureClaim.ProcessEpoch epoch,Object owner,long now,long deadline,CaptureSessionRuntime.BoundedTransition previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        CaptureReleaseGate.requireConfigured();if(!RootBoundedBusinessScope.ACTIVE||intent==null||!intent.live())throw new SecurityException("Live bounded intent required");
        return new Proof(intent.claimFrame(phase,attempt,epoch,owner,now,deadline,previous,true),intent,now);
    }
    static RootBoundedBusinessIntent fixtureBoundedIntent(Object visible,Object request,RealCaptureClaim.ProcessEpoch epoch,Object root,long now,java.util.function.LongSupplier utc){
        long end=Math.min(RootBoundedBusinessScope.sessionDeadline(now,utc.getAsLong()),now+50_000);
        return RootBoundedBusinessIntent.fromVisible(new BoundedApproval(epoch,root,visible,request,now,end,false,utc),now);
    }
    static RealCaptureClaim.Grant fixtureBoundedGrant(RootBoundedBusinessIntent intent,NativeRewardCardSession.Phase phase,int attempt,
            RealCaptureClaim.ProcessEpoch epoch,Object owner,long now,long deadline,CaptureSessionRuntime.BoundedTransition previous){
        if(intent==null||intent.live()||intent.selectedPending()||intent.uniquePending())throw new SecurityException("Nonlive legacy bounded fixture only");
        intent.claimFrame(phase,attempt,epoch,owner,now,deadline,previous,false);
        return RealCaptureClaim.fixture(epoch,owner,now,deadline-now,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
    }
    static RootBoundedBusinessIntent issueSelectedPendingIntent(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,
            RealCaptureClaim.ProcessEpoch e,Object root,long now){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        CaptureReleaseGate.requireConfigured();
        if(!RootSelectedPendingScope.ACTIVE||a==null||click==null||e==null||root==null||!a.selectedPendingApprovalMatches(root,click)
            ||!click.consume(a,a.requestIdentity(),root,e))throw new SecurityException("Fresh B-only visible approval required");
        return RootBoundedBusinessIntent.fromSelectedVisible(new BoundedApproval(e,root,a,a.requestIdentity(),now,
            RootSelectedPendingScope.sessionDeadline(now,System.currentTimeMillis()),true,System::currentTimeMillis,true),now);
    }
    static Proof issueSelectedPendingFrame(RootBoundedBusinessIntent intent,NativeRewardCardSession.Phase phase,int attempt,
            RealCaptureClaim.ProcessEpoch e,Object owner,long now,long deadline,CaptureSessionRuntime.BoundedTransition previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        CaptureReleaseGate.requireConfigured();if(!RootSelectedPendingScope.ACTIVE||intent==null||!intent.live()||!intent.selectedPending()||intent.uniquePending())throw new SecurityException("Live selected B intent required");
        return new Proof(intent.claimFrame(phase,attempt,e,owner,now,deadline,previous,true),intent,now);
    }
    static RootBoundedBusinessIntent fixtureSelectedPendingIntent(Object visible,Object request,RealCaptureClaim.ProcessEpoch e,Object root,long now,java.util.function.LongSupplier utc){
        return RootBoundedBusinessIntent.fromSelectedVisible(new BoundedApproval(e,root,visible,request,now,
            Math.min(RootSelectedPendingScope.sessionDeadline(now,utc.getAsLong()),now+50_000),false,utc,true),now);
    }
    static RealCaptureClaim.Grant fixtureSelectedPendingGrant(RootBoundedBusinessIntent intent,NativeRewardCardSession.Phase phase,int attempt,
            RealCaptureClaim.ProcessEpoch e,Object owner,long now,long deadline,CaptureSessionRuntime.BoundedTransition previous){
        if(intent==null||intent.live()||!intent.selectedPending()||intent.uniquePending())throw new SecurityException("Nonlive selected B fixture required");
        intent.claimFrame(phase,attempt,e,owner,now,deadline,previous,false);
        return RealCaptureClaim.fixture(e,owner,now,deadline-now,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
    }
    static RootBoundedBusinessIntent issueUniquePendingIntent(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,
            RealCaptureClaim.ProcessEpoch e,Object root,long now){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        CaptureReleaseGate.requireConfigured();
        if(!RootUniqueRefinedScope.ACTIVE||a==null||click==null||e==null||root==null||!a.uniquePendingApprovalMatches(root,click)
            ||!click.consume(a,a.requestIdentity(),root,e))throw new SecurityException("Fresh unique pending visible approval required");
        return RootBoundedBusinessIntent.fromUniqueVisible(new BoundedApproval(e,root,a,a.requestIdentity(),now,
            RootUniqueRefinedScope.sessionDeadline(now,System.currentTimeMillis()),true,System::currentTimeMillis,false,true),now);
    }
    static Proof issueUniquePendingFrame(RootBoundedBusinessIntent intent,NativeRewardCardSession.Phase phase,int attempt,
            RealCaptureClaim.ProcessEpoch e,Object owner,long now,long deadline,CaptureSessionRuntime.BoundedTransition previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        CaptureReleaseGate.requireConfigured();if(!RootUniqueRefinedScope.ACTIVE||intent==null||!intent.live()||!intent.uniquePending()||intent.selectedPending())throw new SecurityException("Live unique pending intent required");
        return new Proof(intent.claimFrame(phase,attempt,e,owner,now,deadline,previous,true),intent,now);
    }
    static RootBoundedBusinessIntent fixtureUniquePendingIntent(Object visible,Object request,RealCaptureClaim.ProcessEpoch e,Object root,long now,java.util.function.LongSupplier utc){
        return RootBoundedBusinessIntent.fromUniqueVisible(new BoundedApproval(e,root,visible,request,now,
            Math.min(RootUniqueRefinedScope.sessionDeadline(now,utc.getAsLong()),now+50_000),false,utc,false,true),now);
    }
    static RealCaptureClaim.Grant fixtureUniquePendingGrant(RootBoundedBusinessIntent intent,NativeRewardCardSession.Phase phase,int attempt,
            RealCaptureClaim.ProcessEpoch e,Object owner,long now,long deadline,CaptureSessionRuntime.BoundedTransition previous){
        if(intent==null||intent.live()||!intent.uniquePending()||intent.selectedPending())throw new SecurityException("Nonlive unique pending fixture required");
        intent.claimFrame(phase,attempt,e,owner,now,deadline,previous,false);
        return RealCaptureClaim.fixture(e,owner,now,deadline-now,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
    }
    /** Distinct from RootApproval: this never grants a business action or old phase. */
    static final class SeriesApproval {
        final RealCaptureClaim.ProcessEpoch epoch;final Object rootOwner,visibleOwner,request;
        final long issued,sessionEnd;final boolean live;final java.util.function.LongSupplier utcClock;
        private boolean spent;
        private SeriesApproval(RealCaptureClaim.ProcessEpoch e,Object root,Object visible,Object request,long now,long end,boolean live,java.util.function.LongSupplier utc){
            epoch=e;rootOwner=root;visibleOwner=visible;this.request=request;issued=now;sessionEnd=end;this.live=live;utcClock=utc;
        }
        synchronized boolean consume(long now){if(spent)return false;spent=true;return epoch!=null&&rootOwner!=null&&visibleOwner!=null&&request!=null&&utcClock!=null&&now>=issued&&now<sessionEnd;}
    }
    static RootImageSeriesIntent issueSeriesIntent(DormantCaptureConfirmationActivity activity,DormantCaptureConfirmationActivity.LiveClick click,
            RealCaptureClaim.ProcessEpoch epoch,Object root,long now){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        CaptureReleaseGate.requireConfigured();
        if(RootBoundedBusinessScope.ACTIVE||!RootImageSeriesScope.ACTIVE||activity==null||click==null||epoch==null||root==null
                ||!activity.seriesApprovalMatches(root,click)||!click.consume(activity,activity.requestIdentity(),root,epoch))
            throw new SecurityException("Fresh visible three-frame series approval required");
        long end=RootImageSeriesScope.sessionDeadline(now,System.currentTimeMillis());
        return RootImageSeriesIntent.fromVisible(new SeriesApproval(epoch,root,activity,activity.requestIdentity(),now,end,true,System::currentTimeMillis),now);
    }
    static Proof issueSeriesFrame(RootImageSeriesIntent series,int index,RealCaptureClaim.ProcessEpoch epoch,Object owner,long now,long deadline,CaptureSessionRuntime.SeriesCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        CaptureReleaseGate.requireConfigured();if(RootBoundedBusinessScope.ACTIVE||series==null||!series.live()||!RootImageSeriesScope.ACTIVE)throw new SecurityException("Live series required");
        return new Proof(series.claimFrame(index,epoch,owner,now,deadline,previous,true),series,now);
    }
    static RootImageSeriesIntent fixtureSeriesIntent(Object visible,Object request,RealCaptureClaim.ProcessEpoch epoch,Object root,long now,java.util.function.LongSupplier utc){
        long end=Math.min(RootImageSeriesScope.sessionDeadline(now,utc.getAsLong()),now+50_000);
        return RootImageSeriesIntent.fromVisible(new SeriesApproval(epoch,root,visible,request,now,end,false,utc),now);
    }
    static RealCaptureClaim.Grant fixtureSeriesGrant(RootImageSeriesIntent series,int index,RealCaptureClaim.ProcessEpoch epoch,Object owner,long now,long deadline,CaptureSessionRuntime.SeriesCompletion previous){
        if(series==null||series.live())throw new SecurityException("Nonlive series only");
        series.claimFrame(index,epoch,owner,now,deadline,previous,false);
        return RealCaptureClaim.fixture(epoch,owner,now,deadline-now,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
    }
    /** A different private-constructor token, never a legacy Proof or Android Intent. */
    static final class RootApproval {
        final RealCaptureClaim.ProcessEpoch epoch;final Object rootOwner,visibleOwner,request;
        final RootCaptureScope.Mode mode;final long issued,sessionEnd;final boolean live;
        final java.util.function.LongSupplier utcClock;private boolean used;
        private RootApproval(RealCaptureClaim.ProcessEpoch epoch,Object rootOwner,Object visibleOwner,Object request,
                RootCaptureScope.Mode mode,long issued,long sessionEnd,boolean live,java.util.function.LongSupplier utcClock){
            this.epoch=epoch;this.rootOwner=rootOwner;this.visibleOwner=visibleOwner;this.request=request;this.mode=mode;
            this.issued=issued;this.sessionEnd=sessionEnd;this.live=live;this.utcClock=utcClock;
        }
        synchronized boolean consume(RealCaptureClaim.ProcessEpoch exact,Object owner,long now){
            if(used)return false;used=true;
            return exact==epoch&&owner==rootOwner&&epoch!=null&&rootOwner!=null&&visibleOwner!=null&&request!=null
                &&mode!=null&&utcClock!=null&&now>=issued&&now<sessionEnd;
        }
    }
    static RootCaptureIntent issueRootIntent(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,RealCaptureClaim.ProcessEpoch epoch,Object rootOwner,
            RootCaptureScope.Mode mode,long now,RootCaptureIntent.PreflightPass pass){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        CaptureReleaseGate.requireConfigured();
        if(RootBoundedBusinessScope.ACTIVE||RootImageSeriesScope.ACTIVE)throw new SecurityException("Old root issuer disabled in series build");
        long end=RootFamilyDiagnosticProfile.ACTIVE?RootFamilyDiagnosticProfile.sessionDeadline(now,System.currentTimeMillis())
            :RootCaptureScope.sessionDeadline(now,System.currentTimeMillis());
        if(activity==null||click==null||epoch==null||rootOwner==null||mode==null
                ||!activity.rootApprovalMatches(mode,rootOwner,click)
                ||!click.consume(activity,activity.requestIdentity(),rootOwner,epoch))
            throw new SecurityException("Fresh mode-specific visible root intent required");
        if(!RootFamilyDiagnosticProfile.modeAllowed(mode)||(RootFamilyDiagnosticProfile.ACTIVE&&!RootFamilyDiagnosticProfile.current(System.currentTimeMillis())))throw new SecurityException("Diagnostic-only live issuer");
        end=RootFamilyDiagnosticProfile.visibleDeadline(end,now);
        RootApproval approval=new RootApproval(epoch,rootOwner,activity,activity.requestIdentity(),mode,now,end,true,System::currentTimeMillis);
        return RootCaptureIntent.fromVisible(approval,epoch,rootOwner,now,pass);
    }
    static Proof issueRoot(RootCaptureIntent intent,RootCaptureScope.Phase phase,RealCaptureClaim.ProcessEpoch epoch,
            Object requestOwner,long now,long absolutePhaseDeadline,RootCaptureIntent.Continuation continuation){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live issuer only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live issuer only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live issuer only");
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Independent current-only build");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read only");
        CaptureReleaseGate.requireConfigured();
        if(RootBoundedBusinessScope.ACTIVE||intent==null||!intent.live())throw new SecurityException("Live original root intent required");
        RootCaptureIntent.PhasePermit permit=intent.claimPhase(phase,epoch,requestOwner,now,absolutePhaseDeadline,continuation,true);
        return new Proof(permit,intent,now);
    }
    /** Non-live unit/integration issuer. Never accepts a fixture as a live Proof or grant.
     * Tests supply an in-memory BurnStore and Binding.fixture, not a live durable namespace. */
    static RootCaptureIntent fixtureRootIntent(Object visibleOwner,Object request,RealCaptureClaim.ProcessEpoch epoch,
            Object rootOwner,RootCaptureScope.Mode mode,long now,java.util.function.LongSupplier utcClock,RootCaptureIntent.PreflightPass pass){
        if(visibleOwner==null||request==null||epoch==null||rootOwner==null||mode==null||utcClock==null)
            throw new SecurityException("Fixture root identities required");
        long end=RootCaptureScope.sessionDeadline(now,utcClock.getAsLong());
        RootApproval approval=new RootApproval(epoch,rootOwner,visibleOwner,request,mode,now,end,false,utcClock);
        return RootCaptureIntent.fromVisible(approval,epoch,rootOwner,now,pass);
    }
    static RealCaptureClaim.Grant fixtureRootGrant(RootCaptureIntent intent,RootCaptureScope.Phase phase,
            RealCaptureClaim.ProcessEpoch epoch,Object requestOwner,long now,long absolutePhaseDeadline,RootCaptureIntent.Continuation continuation){
        if(intent==null||intent.live())throw new SecurityException("Fixture-only root intent required");
        intent.claimPhase(phase,epoch,requestOwner,now,absolutePhaseDeadline,continuation,false);
        return RealCaptureClaim.fixture(epoch,requestOwner,now,absolutePhaseDeadline-now,RealCaptureClaim.Scope.WHOLE_DISPLAY_READ_ONLY_ONCE);
    }
    private VisibleCaptureAuthority() { }
}
