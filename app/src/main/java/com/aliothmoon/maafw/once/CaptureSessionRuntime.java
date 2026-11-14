package com.aliothmoon.maafw.once;

import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dormant same-private-process routing. Static root survives Activity destruction;
 * never restores consent, projection, proof or claim from disk/Intent. */
final class CaptureSessionRuntime {
    private static final AtomicBoolean START_SPENT=new AtomicBoolean();
    private static final CaptureSessionRegistry ROOT=new CaptureSessionRegistry();
    private static final RealCaptureClaim.ProcessEpoch EPOCH=new RealCaptureClaim.ProcessEpoch();
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"capture-deadline-scalar");t.setDaemon(true);return t;
    });
    private final Object requestOwner=new Object();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final DormantCaptureConfirmationActivity activity;
    private final HandlerThread loop=new HandlerThread("capture-once-owner");
    private Handler owner;
    private CaptureSessionRegistry.Entry entry;
    private RealCaptureClaim.Grant grant;
    private volatile CaptureAdmissionCoordinator<android.media.projection.MediaProjection> coordinator;
    private volatile AndroidCaptureAdmissionPorts ports;
    private volatile OnceBackend backend;
    private MaaRecognizer recognizer;
    private BusinessCaptureScope.Purpose purpose;
    private boolean currentStatus;
    private CurrentCheckInStatus.Result currentObservation;
    private BingForegroundSnapshot.Snapshot currentForeground;
    private NativeCheckInSession nativeSession;
    private NativeAutonomousCardRun autonomousRun;
    private ReadOnlyTwoStageRun readOnlyRun;
    private ReadOnlyTwoStageRun.Phase readOnlyPhase;
    private ReadOnlyTwoStageRun.Capture readOnlyCapture;
    private ReadOnlyTwoStageRun.Observation readOnlyObservation;
    private String readOnlyStage="ADMISSION";
    private long readOnlySourceLower=-1;
    boolean publicationCurrent(){if(returnReadIntent!=null)return false;if(boundedIntent!=null)return boundedPublicationCurrent();if(seriesIntent!=null)return false;if(rootIntent!=null)return rootPublicationCurrent();return autonomousRun!=null?autonomousRun.publicationCurrent(RealCaptureClaim.deadline(grant)):readOnlyRun==null||readOnlyRun.publicationCurrent(readOnlySourceLower,RealCaptureClaim.deadline(grant));}
    static CaptureSessionRuntime startAutonomous(DormantCaptureConfirmationActivity activity,DormantCaptureConfirmationActivity.LiveClick click,NativeAutonomousCardRun run,NativeRewardCardSession.Phase phase){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        throw new SecurityException("v24: Projection business entry permanently disabled; explicit root intent required");
    }
    static CaptureSessionRuntime startReadOnly(DormantCaptureConfirmationActivity activity,DormantCaptureConfirmationActivity.LiveClick click,ReadOnlyTwoStageRun run,ReadOnlyTwoStageRun.Phase phase){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        return startInternal(activity,click,null,null,null,false,null,null,java.util.Objects.requireNonNull(run),java.util.Objects.requireNonNull(phase));
    }
    private NativeRewardCardRun cardRun;
    private NativeRewardCardSession.Phase cardPhase;
    private NativeRewardCardSession.Capture cardCapture;
    private NativeRewardCardSession.Observation cardObservation;
    static CaptureSessionRuntime startCard(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,NativeRewardCardRun run,NativeRewardCardSession.Phase phase){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        throw new SecurityException("v20 read-only profile: business input route disabled");
    }
    static CaptureSessionRuntime startCurrentStatus(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        return startInternal(activity,click,null,null,null,true);
    }
    private NativeCheckInScope.Phase nativePhase;
    private NativeCheckInSession.Capture nativeCapture;
    private NativeCheckInSession.Observation nativeObservation;
    boolean finishedAndReleased(){return reportFinal&&released;}
    String nativePhaseLabel(){if(rootPhase!=null)return rootPhase==RootCaptureScope.Phase.FIRST?"BEFORE":"AFTER";return readOnlyPhase!=null?readOnlyPhase.hardwareLabel():cardPhase!=null?cardPhase.name():currentStatus?"CURRENT_STATUS":nativePhase==null?"NONE":nativePhase.name();}
    static CaptureSessionRuntime startNative(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,NativeCheckInSession session,NativeCheckInScope.Phase phase) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        throw new SecurityException("v16 verification-only build: account input route disabled");
    }
    private int width,height,dpi;
    private volatile boolean reportFinal;
    private String completedReport;
    private String completedDiagnostics;
    private boolean constructionUnknown, closeStarted;
    private volatile boolean released;
    private RootReturnReadIntent returnReadIntent;
    private int returnReadIndex;
    private RootReturnReadController returnController;
    private RootReturnReadController.Result returnResult;
    private ReviewBingNavigation returnMetadata;
    private NativeProgressPublication pendingProgressPublication;
    private String returnSummary="returnNavigation=NOT_INVOKED";
    private boolean returnPhaseGranted;
    private RootBoundedBusinessIntent boundedIntent;
    private int boundedAttempt;
    private NativeRewardCardSession.PreparedCapture preparedCapture;
    private NativeRewardCardSession.PreparedObservation preparedObservation;
    private MaaImageBufferAudit.Result boundedImageResult;
    private MaaRecognizer.RootMarkerDiagnostic boundedMarkers;
    private volatile boolean boundedCommitted;
    private boolean boundedRetired;
    private RootImageSeriesIntent seriesIntent;
    private int seriesIndex;
    private MaaImageBufferAudit.Result seriesImageResult;
    private long seriesSampleStartMono=-1,seriesSampleEndMono=-1;
    private final String seriesMetadata="clientDiagnosticsInvocation=NOT_INVOKED sfDiagnosticsInvocation=NOT_INVOKED clientMetadata=UNOBSERVED layerMetadata=UNOBSERVED";
    private RootCaptureIntent rootIntent;
    private RootCaptureScope.Mode rootMode;
    private RootCaptureScope.Phase rootPhase;
    private Object rootOwner;
    private final BackendPorts.RevocationFence rootFence=new BackendPorts.RevocationFence();
    private volatile ReviewShizukuPngBinding.Binding rootBinding;
    private ReviewShizukuPngBinding.CleanReceipt rootCleanReceipt;
    private ReviewBingNavigation rootPreflightMetadata;
    private RootSourceReadPermit rootSourceReadPermit;
    private BingNavigationSnapshot.Ready rootPreflightBefore;
    private int rootRotation;
    private long rootLower=-1;
    private boolean rootEpochHeld,rootClaimUncertain,rootAcceptanceEntered,rootPreflightValid,rootPreflightSourceConfirmed;
    private volatile boolean rootCandidate,rootPublished;
    private final AtomicBoolean rootPublicationIssued=new AtomicBoolean();
    private String rootStage="ADMISSION";
    private int rootReadinessAttempts,rootReadinessTransitions;
    private String rootReadinessStatus="NOT_EVALUATED",rootPostStatus="NOT_EVALUATED";
    private String rootReadinessGate="NOT_EVALUATED",rootFirstTransitionGate="NOT_EVALUATED",rootPostGate="NOT_EVALUATED";
    static boolean rootPreflightAvailable(DormantCaptureConfirmationActivity activity,RootCaptureIntent.PreflightPass pass){
        return activity!=null&&pass!=null&&pass.eligible(EPOCH,activity,activity.requestIdentity(),SystemClock.elapsedRealtime(),true);
    }
    static RootCaptureIntent approveRoot(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,Object rootOwner,RootCaptureScope.Mode mode,
            RootCaptureIntent.PreflightPass pass){
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read build");
        if(!RootFamilyDiagnosticProfile.modeAllowed(mode))throw new SecurityException("Diagnostic-only root approval");
        return VisibleCaptureAuthority.issueRootIntent(activity,click,EPOCH,rootOwner,mode,SystemClock.elapsedRealtime(),pass);
    }
    static CaptureSessionRuntime startRoot(DormantCaptureConfirmationActivity activity,RootCaptureIntent intent,Object rootOwner,
            RootCaptureScope.Phase phase,RootCaptureIntent.Continuation continuation,NativeAutonomousCardRun business){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live entry only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live entry only");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read build");
        CaptureReleaseGate.requireConfigured();
        if(RootBoundedBusinessScope.ACTIVE||RootImageSeriesScope.ACTIVE)throw new SecurityException("Series-only live entry");
        if(intent==null||!RootFamilyDiagnosticProfile.phaseAllowed(intent.mode(),phase)||RootFamilyDiagnosticProfile.ACTIVE&&business!=null)throw new SecurityException("Diagnostic-only root entry");
        if(Looper.myLooper()!=Looper.getMainLooper()||android.os.Process.myUid()<10000
                ||!Application.getProcessName().equals(activity.getPackageName()+":capture_once")||intent==null||rootOwner==null)
            throw new SecurityException("Private root runtime required");
        CaptureSessionRuntime run=new CaptureSessionRuntime(activity);
        run.rootIntent=intent;run.rootMode=intent.mode();run.rootPhase=java.util.Objects.requireNonNull(phase);run.rootOwner=rootOwner;
        if(!RootCaptureScope.allowed(run.rootMode,phase)||!intent.current(SystemClock.elapsedRealtime()))throw new SecurityException("Root intent expired");
        if(run.rootMode==RootCaptureScope.Mode.BUSINESS){
            run.autonomousRun=java.util.Objects.requireNonNull(business);if(business.fixtureOnly())throw new SecurityException("Fixture run cannot enter live root runtime");run.cardPhase=phase==RootCaptureScope.Phase.FIRST?NativeRewardCardSession.Phase.BEFORE:NativeRewardCardSession.Phase.AFTER;
            business.reserve(run.cardPhase);
        }else if(business!=null)throw new SecurityException("Preflight must not construct a business run");
        if(android.os.Build.VERSION.SDK_INT<34)throw new SecurityException("Reviewed platform required");
        android.graphics.Rect bounds=activity.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        run.width=bounds.width();run.height=bounds.height();run.dpi=activity.getResources().getConfiguration().densityDpi;
        requireGeometry(run.width,run.height,run.dpi);
        var displays=activity.getSystemService(android.hardware.display.DisplayManager.class);
        var display=displays==null?null:displays.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=android.view.Display.STATE_ON)throw new SecurityException("Display unavailable");
        run.rootRotation=display.getRotation();long now=SystemClock.elapsedRealtime();
        long deadline=business==null?(RootFamilyDiagnosticProfile.ACTIVE
            ?RootFamilyDiagnosticProfile.deadline(now,System.currentTimeMillis(),intent.sessionEnd())
            :RootCaptureScope.deadline(now,System.currentTimeMillis(),intent.sessionEnd())):business.deadline(now);
        var proof=VisibleCaptureAuthority.issueRoot(intent,phase,EPOCH,run.requestOwner,now,deadline,continuation);
        run.grant=RealCaptureClaim.visible(proof,EPOCH,run.requestOwner,now);
        if(!EPOCH.retain(run))throw new SecurityException("Existing epoch is retained");run.rootEpochHeld=true;
        try{
            run.entry=ROOT.retain(run,EPOCH,deadline,run::latchCancel,run::closeOnOwner,()->run.released,
                    task->run.owner!=null&&run.owner.post(task));
        }catch(RuntimeException denied){EPOCH.release(run);run.rootEpochHeld=false;throw denied;}
        try{
            run.rootSourceReadPermit=new RootSourceReadPermit(run,new BingNavigationSnapshot.ReadOnlyGuard(
                android.os.Process.myUid(),activity.getTaskId(),activity.getComponentName().flattenToString()));
            run.loop.start();run.owner=new Handler(run.loop.getLooper());
            ROOT.arm(run.entry,(end,task)->DEADLINES.schedule(task,Math.max(0,end-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS),SystemClock::elapsedRealtime);
            if(phase==RootCaptureScope.Phase.FIRST){
                activity.requestHandoff(run::requireRootCurrent,()->{
                    if(!run.owner.post(run::acquiredRootOnOwner))run.cancel();
                },run::rootNavigationRevoked);
            }else if(!activity.rootHiddenScalar()||!run.owner.post(run::acquiredRootOnOwner))throw new SecurityException("Root continuation source hidden state lost");
        }catch(RuntimeException failed){run.constructionUnknown=true;run.cancel();}
        return run;
    }
    static RootReturnReadIntent approveProgressLocate(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,Object root){return VisibleCaptureAuthority.issueProgressLocateIntent(a,click,EPOCH,root,SystemClock.elapsedRealtime());}
    static CaptureSessionRuntime startProgressLocate(DormantCaptureConfirmationActivity a,RootReturnReadIntent intent,Object root,int index,ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Structure-only live build");
        if(!RootProgressLocateScope.ACTIVE||intent==null||!intent.progressLocate()||!intent.currentOnly())throw new SecurityException("Bounded locate intent required");return startReadInternal(a,intent,root,index,previous);
    }
    static RootReturnReadIntent approveCheckpoint(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,Object root){return VisibleCaptureAuthority.issueCheckpointIntent(a,click,EPOCH,root,SystemClock.elapsedRealtime());}
    static CaptureSessionRuntime startCheckpointRead(DormantCaptureConfirmationActivity a,RootReturnReadIntent intent,Object root,int index,ReturnReadCompletion previous){
        if(!ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Fixed structure checkpoint required");
        if(intent==null||intent.checkpoint()!=ReadOnlyCheckpoint.CURRENT||index!=1||previous!=null||!intent.currentOnly())throw new SecurityException("Single-frame diagnostic intent required");return startReadInternal(a,intent,root,index,null);
    }
    static RootReturnReadIntent approveProgressScan(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,Object root){return VisibleCaptureAuthority.issueProgressScanIntent(a,click,EPOCH,root,SystemClock.elapsedRealtime());}
    static CaptureSessionRuntime startProgressScan(DormantCaptureConfirmationActivity a,RootReturnReadIntent intent,Object root,int index,ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(!RootProgressScanScope.ACTIVE||intent==null||!intent.progressScan()||!intent.currentOnly())throw new SecurityException("Bounded scan intent required");return startReadInternal(a,intent,root,index,previous);
    }
    static RootReturnReadIntent approveProgressRead(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,Object root){return VisibleCaptureAuthority.issueProgressReadIntent(a,click,EPOCH,root,SystemClock.elapsedRealtime());}
    static CaptureSessionRuntime startProgressRead(DormantCaptureConfirmationActivity a,RootReturnReadIntent intent,Object root,int index,ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(!RootProgressReadScope.ACTIVE||intent==null||!intent.progressRead()||!intent.currentOnly())throw new SecurityException("Progress read-only intent required");
        return startReadInternal(a,intent,root,index,previous);
    }
    static RootReturnReadIntent approveCurrentRead(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,Object root){return VisibleCaptureAuthority.issueCurrentReadIntent(a,click,EPOCH,root,SystemClock.elapsedRealtime());}
    static CaptureSessionRuntime startCurrentRead(DormantCaptureConfirmationActivity a,RootReturnReadIntent intent,Object root,int index,ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live entry only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live entry only");
        if(!RootCurrentCardScope.ACTIVE||intent==null||!intent.currentOnly())throw new SecurityException("Independent current-only intent required");
        return startReadInternal(a,intent,root,index,previous);
    }
    static RootReturnReadIntent approveReturnRead(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,Object root){
        if(RootCurrentCardScope.ACTIVE)throw new SecurityException("Current-only build");return VisibleCaptureAuthority.issueReturnReadIntent(a,click,EPOCH,root,SystemClock.elapsedRealtime());
    }
    static CaptureSessionRuntime startReturnRead(DormantCaptureConfirmationActivity a,RootReturnReadIntent intent,Object root,int index,ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live entry only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live entry only");
        if(RootCurrentCardScope.ACTIVE||intent==null||intent.currentOnly())throw new SecurityException("Return-read unavailable in current-only build");
        return startReadInternal(a,intent,root,index,previous);
    }
    static RootReturnReadIntent approveStatusRecovery(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,Object root){return VisibleCaptureAuthority.issueStatusRecoveryIntent(a,click,EPOCH,root,SystemClock.elapsedRealtime());}
    static CaptureSessionRuntime startStatusRecovery(DormantCaptureConfirmationActivity a,RootReturnReadIntent intent,Object root,int index,ReturnReadCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        if(!RootStatusRecoveryScope.ACTIVE||intent==null||!intent.statusRecovery()||intent.currentOnly())throw new SecurityException("Independent status recovery intent required");
        return startReadInternal(a,intent,root,index,previous);
    }
    private static CaptureSessionRuntime startReadInternal(DormantCaptureConfirmationActivity a,RootReturnReadIntent intent,Object root,int index,ReturnReadCompletion previous){
        CaptureReleaseGate.requireConfigured();
        if(a==null||intent==null||!(intent.progressLocate()?RootProgressLocateScope.ACTIVE:intent.checkpoint()!=null?intent.checkpoint()==ReadOnlyCheckpoint.CURRENT:intent.progressScan()?RootProgressScanScope.ACTIVE:intent.progressRead()?RootProgressReadScope.ACTIVE:intent.statusRecovery()?RootStatusRecoveryScope.ACTIVE:intent.currentOnly()?RootCurrentCardScope.ACTIVE:RootReturnReadScope.ACTIVE)||!intent.live()||!intent.matchesOwner(root,a,a.requestIdentity())
            ||!RootReturnReadScope.validIndex(index)||Looper.myLooper()!=Looper.getMainLooper()||android.os.Process.myUid()<10000
            ||!Application.getProcessName().equals(a.getPackageName()+":capture_once")||android.os.Build.VERSION.SDK_INT<34)throw new SecurityException("Fresh independent return-read intent required");
        CaptureSessionRuntime run=new CaptureSessionRuntime(a);run.returnReadIntent=intent;run.returnReadIndex=index;run.rootOwner=root;if(intent.statusRecovery())run.returnSummary="independentStatusRecovery=true taskTaps=0 returnInvoked=false oldBusinessContinued=false initialSource=NOT_OBSERVED";if(intent.currentOnly())run.returnSummary="currentOnly=true independentCurrentRead=true returnInvoked=false oldBusinessContinued=false initialTemplate=NOT_OBSERVED";
        run.rootMode=RootCaptureScope.Mode.PREFLIGHT;run.rootPhase=RootCaptureScope.Phase.FIRST;
        var bounds=a.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        run.width=bounds.width();run.height=bounds.height();run.dpi=a.getResources().getConfiguration().densityDpi;requireGeometry(run.width,run.height,run.dpi);
        var manager=a.getSystemService(android.hardware.display.DisplayManager.class);var display=manager==null?null:manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=android.view.Display.STATE_ON)throw new SecurityException("Display unavailable");run.rootRotation=display.getRotation();
        long now=SystemClock.elapsedRealtime();if(!intent.current(now))throw new SecurityException("Recovery expired");
        if(index==1){if(previous!=null)throw new SecurityException("Initial recovery has no completion");intent.claimNavigation(EPOCH,now);}
        else run.grant=RealCaptureClaim.visible(intent.progressLocate()?VisibleCaptureAuthority.issueProgressLocateFrame(intent,index,EPOCH,run.requestOwner,now,previous):intent.checkpoint()!=null?VisibleCaptureAuthority.issueCheckpointFrame(intent,index,EPOCH,run.requestOwner,now,previous):intent.progressScan()?VisibleCaptureAuthority.issueProgressScanFrame(intent,index,EPOCH,run.requestOwner,now,previous):intent.progressRead()?VisibleCaptureAuthority.issueProgressReadFrame(intent,index,EPOCH,run.requestOwner,now,previous):intent.statusRecovery()?VisibleCaptureAuthority.issueStatusRecoveryFrame(intent,index,EPOCH,run.requestOwner,now,previous):intent.currentOnly()?VisibleCaptureAuthority.issueCurrentReadFrame(intent,index,EPOCH,run.requestOwner,now,previous):VisibleCaptureAuthority.issueReturnReadFrame(intent,index,EPOCH,run.requestOwner,now,previous),EPOCH,run.requestOwner,now);
        if(!EPOCH.retain(run))throw new SecurityException("Previous epoch retained");run.rootEpochHeld=true;
        long end=index==1?intent.sessionEnd():intent.readDeadline();
        try{run.entry=ROOT.retain(run,EPOCH,end,run::latchCancel,run::closeOnOwner,()->run.released,task->run.owner!=null&&run.owner.post(task));}
        catch(RuntimeException denied){EPOCH.release(run);run.rootEpochHeld=false;throw denied;}
        try{
            run.loop.start();run.owner=new Handler(run.loop.getLooper());
            ROOT.arm(run.entry,(d,task)->DEADLINES.schedule(task,Math.max(0,d-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS),SystemClock::elapsedRealtime);
            if(index==1)a.requestHandoff(run::requireRootCurrent,()->{if(!run.owner.post(run::returnBeforeReadOnOwner))run.cancel();},()->a.returnReadVisibilityRevoked(intent));
            else{run.returnSummary=intent.navigationSummary();run.returnPhaseGranted=true;run.newReturnSourcePermit();if(!a.rootHiddenScalar()||!run.owner.post(run::acquiredRootOnOwner))throw new SecurityException("Original hidden handoff lost");}
        }catch(RuntimeException failed){run.constructionUnknown=true;run.cancel();}
        return run;
    }
    private void newReturnSourcePermit(){rootSourceReadPermit=new RootSourceReadPermit(this,new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),activity.getTaskId(),activity.getComponentName().flattenToString()));}
    boolean isReturnRead(){return returnReadIntent!=null;}RootReturnReadIntent returnReadIntent(){return returnReadIntent;}int returnReadIndex(){return returnReadIndex;}
    /** Only a rooted independent recovery can create one reader, with seven total navigation pairs. */
    static final class RootRecoveryReadPermit {
        private final CaptureSessionRuntime run;private final BingNavigationSnapshot.ReadOnlyGuard guard;private boolean claimed;private int reads;
        private RootRecoveryReadPermit(CaptureSessionRuntime r){run=r;guard=new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),r.activity.getTaskId(),r.activity.getComponentName().flattenToString());}
        boolean current(){return run.returnReadIntent!=null&&run.returnReadIntent.live()&&run.rootEpochHeld&&run.rootCurrent()&&!run.reportFinal&&run.activity.rootHiddenScalar();}
        synchronized BingNavigationSnapshot.ReadOnlyGuard claimReader(){if(claimed||!current())throw new SecurityException("Recovery reader already spent");claimed=true;return guard;}
        synchronized boolean read(){if(!claimed||reads>=7)return false;reads++;return current();}
    }
    /** One initial current-template reader. It cannot authorize an actor or share navigation budget. */
    static final class RootCurrentReadPermit {
        private final CaptureSessionRuntime run;private final BingNavigationSnapshot.ReadOnlyGuard guard;private boolean claimed;private int reads;
        private RootCurrentReadPermit(CaptureSessionRuntime r){run=r;guard=new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),r.activity.getTaskId(),r.activity.getComponentName().flattenToString());}
        boolean current(){return run.returnReadIntent!=null&&run.returnReadIntent.currentOnly()&&run.returnReadIntent.live()&&run.rootEpochHeld&&run.rootCurrent()&&!run.reportFinal&&run.activity.rootHiddenScalar();}
        synchronized BingNavigationSnapshot.ReadOnlyGuard claimReader(){if(claimed||!current())throw new SecurityException("Current reader already spent");claimed=true;return guard;}
        synchronized boolean read(){if(!claimed||reads>=3)return false;reads++;return current();}
    }
    private void currentBeforeReadOnOwner(){
        if(returnReadIntent.entryDiagnostic()){entryDiagnosticOnOwner();return;}
        if(returnReadIntent.entryRecovery()){entryRecoveryOnOwner();return;}
        boolean start=false;
        try{
            rootSourceGate();rootStage="CURRENT_TEMPLATE_ANCHOR";
            if(!returnReadIntent.currentOnly())throw new SecurityException("Current-only authority required");
            var installed=BingPackageMetadata.read(activity.getPackageManager());
            if(installed.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Bing metadata unavailable");
            int bing=activity.getPackageManager().getApplicationInfo("com.microsoft.bing",0).uid,system=activity.getPackageManager().getApplicationInfo("com.android.systemui",0).uid;
            returnMetadata=ReviewBingNavigation.forCurrentRead(activity.getApplicationContext(),installed,bing,system,new RootCurrentReadPermit(this));
            var shape=new BingNavigationSnapshot.Shape(width,height,rootRotation);BingNavigationSnapshot.CurrentTemplateResult read=null;
            for(int n=0;n<3;n++){
                rootSourceGate();read=returnMetadata.readCurrentTemplate(shape);
                if(read.status()!=BingNavigationSnapshot.Status.BING_TRANSITION)break;
                if(n<2){Thread.sleep(40);rootSourceGate();}
            }
            returnSummary="currentOnly=true independentCurrentRead=true returnInvoked=false oldBusinessContinued=false "+returnMetadata.lastReadDiagnostic().summary();
            if(read==null||read.status()!=BingNavigationSnapshot.Status.READY||read.source()==null||!read.source().fresh(SystemClock.elapsedRealtime())||!ReviewBingNavigation.knownClean())throw new SecurityException("Fresh strict Template required");
            rootSourceGate();long now=SystemClock.elapsedRealtime();returnReadIntent.anchorCurrent(read.source(),now);
            if(returnReadIntent.uiStructure()){readUiStructureOnOwner();return;}
            grant=RealCaptureClaim.visible(returnReadIntent.progressLocate()?VisibleCaptureAuthority.issueProgressLocateFrame(returnReadIntent,1,EPOCH,requestOwner,now,null):returnReadIntent.checkpoint()!=null?VisibleCaptureAuthority.issueCheckpointFrame(returnReadIntent,1,EPOCH,requestOwner,now,null):returnReadIntent.progressScan()?VisibleCaptureAuthority.issueProgressScanFrame(returnReadIntent,1,EPOCH,requestOwner,now,null):returnReadIntent.progressRead()?VisibleCaptureAuthority.issueProgressReadFrame(returnReadIntent,1,EPOCH,requestOwner,now,null):VisibleCaptureAuthority.issueCurrentReadFrame(returnReadIntent,1,EPOCH,requestOwner,now,null),EPOCH,requestOwner,now);returnPhaseGranted=true;
            newReturnSourcePermit();long end=returnReadIntent.readDeadline();DEADLINES.schedule(()->ROOT.request(entry),Math.max(0,end-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);start=true;
        }catch(Exception failed){if(failed instanceof InterruptedException)Thread.currentThread().interrupt();rootStage="CURRENT_TEMPLATE_REJECTED";if(returnMetadata!=null)returnSummary="currentOnly=true independentCurrentRead=true returnInvoked=false oldBusinessContinued=false "+returnMetadata.lastReadDiagnostic().summary();}
        if(start)acquiredRootOnOwner();else closeOnOwner();
    }
    private void returnBeforeReadOnOwner(){
        if(returnReadIntent.currentOnly()){currentBeforeReadOnOwner();return;}
        boolean start=false;
        try{
            rootSourceGate();rootStage="RECOVERY_NAVIGATION";
            var installed=BingPackageMetadata.read(activity.getPackageManager());
            if(installed.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Bing metadata unavailable");
            int bing=activity.getPackageManager().getApplicationInfo("com.microsoft.bing",0).uid,system=activity.getPackageManager().getApplicationInfo("com.android.systemui",0).uid;
            returnMetadata=returnReadIntent.statusRecovery()?ReviewBingNavigation.forStatusRecovery(activity.getApplicationContext(),installed,bing,system,new RootRecoveryReadPermit(this)):ReviewBingNavigation.forReturnRead(activity.getApplicationContext(),installed,bing,system,new RootRecoveryReadPermit(this));
            var shape=new BingNavigationSnapshot.Shape(width,height,rootRotation);
            returnController=new RootReturnReadController(returnReadIntent,new RootReturnReadController.Ports(){
                public long now(){return SystemClock.elapsedRealtime();}
                public boolean current(){return rootCurrent()&&activity.rootHiddenScalar()&&returnMetadata.authorityMatches(shape)&&rootCurrent();}
                public BingNavigationSnapshot.RecoveryResult initial(){return returnMetadata.readRecovery(shape);}
                public BingNavigationSnapshot.StatusRecoveryInitial statusInitial(){return returnMetadata.readStatusRecovery(shape);}
                public BingNavigationSnapshot.Result observe(){return returnMetadata.read(shape);}
                public void burnReturn(ReviewShizukuBackActor.BackTarget exact)throws Exception{rootSourceGate();if(returnReadIntent.statusRecovery())RootStatusRecoveryScope.burnReturn(activity.getNoBackupFilesDir().toPath(),"fixedKey=4 independentStatusRecovery=true\n");else RootReturnReadScope.burnReturn(activity.getNoBackupFilesDir().toPath(),"fixedKey=4 independentReturnRead=true\n");}
                public void pause()throws InterruptedException{Thread.sleep(40);}
                public String diagnostic(){return returnMetadata.lastReadDiagnostic().summary();}
                public boolean metadataClean(){return ReviewBingNavigation.knownClean();}
            },ReviewShizukuBackActor.forApprovedBusiness20261111(activity.getApplicationContext()));
            returnResult=returnController.execute();returnSummary=returnResult.summary();
            if(!(returnReadIntent.statusRecovery()?returnResult.readyForRead():returnResult.returned())||!returnController.resourcesKnown())throw new SecurityException("Return not confirmed");
            rootSourceGate();long now=SystemClock.elapsedRealtime();returnReadIntent.returned(returnResult,now);
            grant=RealCaptureClaim.visible(returnReadIntent.statusRecovery()?VisibleCaptureAuthority.issueStatusRecoveryFrame(returnReadIntent,1,EPOCH,requestOwner,now,null):VisibleCaptureAuthority.issueReturnReadFrame(returnReadIntent,1,EPOCH,requestOwner,now,null),EPOCH,requestOwner,now);returnPhaseGranted=true;
            newReturnSourcePermit();long end=returnReadIntent.readDeadline();
            DEADLINES.schedule(()->ROOT.request(entry),Math.max(0,end-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);start=true;
        }catch(Exception failed){if(failed instanceof InterruptedException)Thread.currentThread().interrupt();rootStage="RECOVERY_REJECTED";if(returnMetadata!=null)returnSummary+=" "+returnMetadata.lastReadDiagnostic().summary();}
        if(start)acquiredRootOnOwner();else closeOnOwner();
    }
    static boolean returnReadUnpresented(ReadOnlyTwoStageRun.Observation observation,MaaImageBufferAudit.Result audit,MaaRecognizer.RootMarkerDiagnostic markers){
        if(observation==null||!observation.validNativeShape()||observation.nativeRowCount()<=0||observation.nativeNonemptyRows()<=0
            ||observation.frame.status()!=LiveRewardCardObservation.Status.HOLD||observation.frame.reason()!=LiveRewardCardObservation.Reason.MORE_HEADING_NOT_UNIQUE
            ||observation.frame.diagnostic()==null||observation.frame.diagnostic().headerCount()!=0||audit==null||markers==null||markers.nativeRows()<=0)return false;
        boolean more=false;for(var marker:markers.markers()){
            if(marker.marker()==MaaRecognizer.RootMarker.MORE){more=true;if(marker.candidates()!=0)return false;}
            if(switch(marker.marker()){case OWN_ROOT_TITLE,OWN_PREFLIGHT_PROMPT,PROJECTION_PROMPT,LOCK_SCREEN_PROMPT,REWARDS_ERROR->true;default->false;}&&marker.candidates()!=0)return false;
        }
        var cv=audit.sameFrameCv();var band=audit.bandStats();return more&&cv!=null&&cv.attempted()
            &&cv.status()==com.aliothmoon.maafw.rewards.RewardRegionLocator.Status.NO_CARDS&&cv.cardTotal()==0&&cv.fullFrameBoxes().isEmpty()&&!cv.truncated()
            &&band!=null&&band.status()==MaaImageBufferAudit.StatsStatus.OBSERVED&&band.count()>0&&band.populationVariance()==0.0&&band.flatCount()==band.count()&&band.min()==band.max();
    }
    /** Report qualification only. This returns no candidate, pass, continuation or business card. */
    static String qualifiedCurrentReadSummary(ReadOnlyTwoStageRun.Observation observation,boolean healthy,boolean currentCards){
        if(observation==null)return "currentStatusEvidence=UNQUALIFIED observation=NOT_OBSERVED";
        if(!healthy)return "currentStatusEvidence=UNQUALIFIED diagnosticOnly=true semanticStatus="+observation.frame.status()+" semanticReason="+observation.frame.reason();
        if(!currentCards)return "currentStatusEvidence=QUALIFIED "+observation.currentStatusSummary();
        var batch=observation.currentCards();
        if(batch==null)return "currentStatusEvidence=UNQUALIFIED currentCards=NOT_OBSERVED completeFamily=false businessEligible=false";
        return "currentStatusEvidence=QUALIFIED independentCurrentCards=true completeFamily=false businessEligible=false visibleKnownSetComplete="+visibleKnownSetComplete(batch,healthy)+" knownSetIdentity=ANONYMOUS_OR_EXACT_OBSERVED_ONLY completionCause=UNPROVEN "+batch.summary();
    }
    /** A finite visible set fact only. This helper grants no capture, input, task identity or causality. */
    static boolean visibleKnownSetComplete(com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentReadBatch batch,boolean healthy){
        if(!healthy||batch==null||batch.status()!=com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentBatchStatus.PROCESSED||batch.truncated()
            ||batch.unassociatedKnownTitles()!=0||batch.unassociatedKnownFragments()!=0)return false;
        int complete=0;for(var item:batch.cards()){
            if(item.status()==com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentReadStatus.UNMODELED)continue;
            if(item.status()!=com.aliothmoon.maafw.rewards.RewardRegionLocator.CurrentReadStatus.COMPLETE||item.points()!=5)return false;
            complete++;
        }
        return complete==2;
    }
    static String qualifiedProgressReadSummary(ReadOnlyTwoStageRun.Observation observation,boolean healthy){
        if(observation==null||observation.progress()==null)return "progressEvidence=UNQUALIFIED SEARCH=UNKNOWN READING=UNKNOWN";
        return observation.progress().summary(healthy);
    }
    private EntryDiagnosticBudget diagnosticBudget;private AndroidEntryDiagnosticReader.Result diagnosticRead;private EntryDiagnosticPublication diagnosticPublication;private boolean diagnosticSourceAfter;private String diagnosticStage="NOT_STARTED";
    private void entryDiagnosticOnOwner(){
        try{
            rootSourceGate();returnReadIntent.beginEntryDiagnostic(SystemClock.elapsedRealtime());String nonce=java.util.UUID.randomUUID().toString().replace("-","");
            diagnosticBudget=EntryDiagnosticBudget.live(activity.getNoBackupFilesDir().toPath(),nonce);diagnosticBudget.burn(EntryDiagnosticBudget.Slot.RUN_1);
            entryInstalled=BingPackageMetadata.read(activity.getPackageManager());if(entryInstalled.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Installed identity unavailable");
            int bing=activity.getPackageManager().getApplicationInfo("com.microsoft.bing",0).uid,system=activity.getPackageManager().getApplicationInfo("com.android.systemui",0).uid;
            entryMetadata=V46EntryMetadataReader.app(new BingNavigationSnapshot.Profile(bing,system,new BingNavigationSnapshot.Shape(width,height,rootRotation)),new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),activity.getTaskId(),activity.getComponentName().flattenToString()));
            diagnosticStage="ANCHOR";entryAnchor=entryRead(V46EntryMetadataReader.Stage.ANCHOR);if(entryAnchor==null||entryAnchor.kind()!=BingNavigationSnapshot.Kind.MAIN)throw new SecurityException("Fresh Main required");entryGateProof=entryAnchor;
            var request=new AndroidEntryDiagnosticReader.Request(nonce,Integer.parseInt(entryAnchor.task()),width,height,rootRotation,bing,system,android.os.Process.myUid(),activity.getTaskId());
            diagnosticStage="READING";diagnosticRead=AndroidEntryDiagnosticReader.read(activity.getApplicationContext(),request,returnReadIntent.readDeadline(),this::entryNavigationCurrent,()->{
                if(!entryMain(V46EntryMetadataReader.Stage.PREPARE_BEFORE))return false;diagnosticBudget.burn(EntryDiagnosticBudget.Slot.DIAGNOSTIC_READ_1);return entryAuthority()&&entryNavigationCurrent();});
            if(diagnosticRead!=null&&diagnosticRead.knownClean())diagnosticSourceAfter=entryMain(V46EntryMetadataReader.Stage.PREPARE_AFTER);
            diagnosticStage="FINISHED";
        }catch(Exception failed){if(failed instanceof InterruptedException)Thread.currentThread().interrupt();diagnosticStage="HOLD";}
        finally{closeEntryDiagnosticOnOwner();}
    }
    private boolean diagnosticClean(){return V46EntryMetadataReader.processKnownClean()&&AndroidEntryDiagnosticReader.processKnownClean()&&(entryMetadata==null||entryMetadata.knownClean())&&(diagnosticRead==null||diagnosticRead.knownClean());}
    private void closeEntryDiagnosticOnOwner(){
        if(closeStarted)return;closeStarted=true;released=!constructionUnknown&&diagnosticClean()&&ReviewBingNavigation.knownClean();
        var report=diagnosticRead==null?null:diagnosticRead.protocolReport();
        diagnosticPublication=new EntryDiagnosticPublication(report,released&&diagnosticSourceAfter&&!cancelled.get(),Math.min(returnReadIntent.readDeadline(),returnReadIntent.sessionEnd()));
        reportFinal=true;if(released){if(rootEpochHeld){EPOCH.release(this);rootEpochHeld=false;}ROOT.releaseIfClosed(entry);}
        String details="profile=ENTRY_DIAGNOSTIC_V47 publisher=NOT_VERIFIED installedSignerContinuityOnly=true navigationInvocations=0 commitAttempts=0 taskTaps=0 back=0 refresh=0 businessCounterReads=0 screenshots=0 ocr=0 ledgerChanged=false counterAuthority=false actionAuthority=false stage="+diagnosticStage+" cleanup="+(released?"CONFIRMED":"UNKNOWN")+" sourceBeforeAfterSame="+diagnosticSourceAfter+" "+(diagnosticBudget==null?"runAttempted=false":diagnosticBudget.summary())+" "+(entryMetadata==null?"entryMetadataPairs=0":entryMetadata.summary());
        if(diagnosticRead!=null)details+=" readerStatus="+diagnosticRead.fixedStatus()+" helperInvoked="+diagnosticRead.invoked()+" stdoutBytes="+diagnosticRead.acquiredBytes()+" processQuiet="+diagnosticRead.processQuiet()+" descriptorsClosed="+diagnosticRead.descriptorsClosed()+" exitObserved="+diagnosticRead.exitObserved()+" exitCode="+diagnosticRead.exitCode();
        if(report!=null)details+=" helperReason="+report.reason()+" helperCleanup="+report.cleanup();
        activity.returnReadFrameFinished(this,null,details);if(released&&loop!=null)loop.quitSafely();
    }
    private String finalizeEntryDiagnosticPublication(String details){
        var token=diagnosticPublication;diagnosticPublication=null;if(token==null)return details+" entryDiagnostic=UNQUALIFIED nodes=SUPPRESSED publicationFence=MISSING";
        boolean source=false,hidden=false,current=false;try{source=released&&reportFinal&&diagnosticSourceAfter&&diagnosticClean()&&entryAuthority();hidden=activity.rootHiddenScalar()&&activity.hiddenWithSoleTask();long now=SystemClock.elapsedRealtime();current=!cancelled.get()&&returnReadIntent.current(now)&&now<returnReadIntent.readDeadline()&&entryGateProof!=null&&entryGateProof.fresh(now);}catch(RuntimeException unknown){source=false;}
        return details+"\n"+token.publish(SystemClock.elapsedRealtime(),current,hidden,source);
    }
    private RewardsEntryBudget entryBudget;private V46EntryMetadataReader entryMetadata;
    private BingIdentityEvidence.Installed entryInstalled;private BingNavigationSnapshot.Ready entryAnchor,entryProof,entryGateProof;
    private AndroidRewardsEntryActor.Result entryNavigation;private boolean entryTemplateConfirmed,entryWaitConfirmed;private int entryUiReads;
    private boolean entryLocalCurrent(){return returnReadIntent!=null&&(returnReadIntent.entryRecovery()||returnReadIntent.entryDiagnostic())&&rootCurrent()&&activity.rootHiddenScalar()&&SystemClock.elapsedRealtime()<returnReadIntent.readDeadline()&&!Thread.currentThread().isInterrupted();}
    private boolean entryAuthority(){
        if(android.os.Build.VERSION.SDK_INT<34)return false;
        try{
            if(entryInstalled==null||!entryInstalled.equals(BingPackageMetadata.read(activity.getPackageManager()))||!rikka.shizuku.Shizuku.pingBinder()||rikka.shizuku.Shizuku.isPreV11()||rikka.shizuku.Shizuku.getUid()!=0||rikka.shizuku.Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED)return false;
            var manager=activity.getSystemService(android.hardware.display.DisplayManager.class);var display=manager==null?null:manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if(display==null||display.getState()!=android.view.Display.STATE_ON||display.getRotation()!=rootRotation)return false;
            var windows=activity.createDisplayContext(display).getSystemService(android.view.WindowManager.class);if(windows==null)return false;
            var bounds=windows.getMaximumWindowMetrics().getBounds();return bounds.left==0&&bounds.top==0&&bounds.width()==width&&bounds.height()==height;
        }catch(RuntimeException unknown){return false;}
    }
    private BingNavigationSnapshot.Ready entryRead(V46EntryMetadataReader.Stage stage){
        if(!entryLocalCurrent()||!activity.hiddenWithSoleTask()||!entryAuthority())return null;
        var result=entryMetadata.read(stage,returnReadIntent.readDeadline(),this::entryLocalCurrent);
        if(result==null||result.status()!=BingNavigationSnapshot.Status.READY||result.ready()==null||!result.ready().fresh(SystemClock.elapsedRealtime())||!entryMetadata.knownClean()||!entryAuthority()||!entryLocalCurrent()||!activity.hiddenWithSoleTask())return null;
        return result.ready();
    }
    private boolean entryNavigationCurrent(){return entryLocalCurrent()&&entryGateProof!=null&&entryGateProof.fresh(SystemClock.elapsedRealtime());}
    private boolean entryMain(V46EntryMetadataReader.Stage stage){var now=entryRead(stage);boolean valid=now!=null&&entryAnchor!=null&&entryAnchor.kind()==BingNavigationSnapshot.Kind.MAIN&&entryAnchor.sameEntrySource(now);entryGateProof=valid?now:null;return valid;}
    private boolean entryLocateSource(V46EntryMetadataReader.Stage stage){var now=entryRead(stage);boolean valid=now!=null&&entryAnchor!=null&&entryAnchor.kind()==BingNavigationSnapshot.Kind.TEMPLATE&&entryAnchor.sameEntrySource(now);entryGateProof=valid?now:null;return valid;}
    private boolean entryCounterSource(V46EntryMetadataReader.Stage stage){var now=entryRead(stage);boolean valid=now!=null&&entryTemplateConfirmed&&returnReadIntent.sourceMatches(now)&&RewardsEntryBudget.sameTaskTemplate(entryAnchor,now,SystemClock.elapsedRealtime());entryProof=valid?now:null;return valid;}
    private void entryRecoveryOnOwner(){
        try{
            rootSourceGate();returnReadIntent.beginEntry(SystemClock.elapsedRealtime());
            String nonce=java.util.UUID.randomUUID().toString().replace("-","");entryBudget=RewardsEntryBudget.live(activity.getNoBackupFilesDir().toPath(),nonce);entryBudget.burn(RewardsEntryBudget.Slot.RUN_1);
            entryInstalled=BingPackageMetadata.read(activity.getPackageManager());if(entryInstalled.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Package unavailable");
            int bing=activity.getPackageManager().getApplicationInfo("com.microsoft.bing",0).uid,system=activity.getPackageManager().getApplicationInfo("com.android.systemui",0).uid;
            var profile=new BingNavigationSnapshot.Profile(bing,system,new BingNavigationSnapshot.Shape(width,height,rootRotation));
            entryMetadata=V46EntryMetadataReader.app(profile,new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),activity.getTaskId(),activity.getComponentName().flattenToString()));
            uiStage="ENTRY_ANCHOR";entryAnchor=entryRead(V46EntryMetadataReader.Stage.ANCHOR);if(entryAnchor==null)throw new SecurityException("Fresh Main or Template required");
            BingNavigationSnapshot.Ready template=entryAnchor;
            if(returnReadIntent.uiReady()&&entryAnchor.kind()!=BingNavigationSnapshot.Kind.TEMPLATE)throw new SecurityException("Read-only fresh Template required");
            if(returnReadIntent.uiLocate()){
                uiStage="SHOW_REGION_PREPARE";entryGateProof=entryAnchor;
                var request=new AndroidRewardsEntryActor.Request(nonce,Integer.parseInt(entryAnchor.task()),width,height,rootRotation,bing,system,android.os.Process.myUid(),activity.getTaskId());
                entryNavigation=AndroidRewardsEntryActor.execute(activity.getApplicationContext(),request,returnReadIntent.readDeadline(),this::entryNavigationCurrent,
                    ()->{if(!entryLocateSource(V46EntryMetadataReader.Stage.PREPARE_BEFORE))return false;entryBudget.burn(RewardsEntryBudget.Slot.PREPARE_1);return entryLocalCurrent()&&entryAuthority();},
                    prepared->{if(!entryLocateSource(V46EntryMetadataReader.Stage.PREPARE_AFTER)||!entryLocateSource(V46EntryMetadataReader.Stage.COMMIT_BEFORE))return false;entryBudget.burn(RewardsEntryBudget.Slot.NAV_1);return entryLocalCurrent()&&entryAuthority();});
                if(entryNavigation==null||!entryNavigation.succeeded()||!AndroidRewardsEntryActor.processKnownClean()||!entryMetadata.knownClean())throw new SecurityException("Show-on-screen outcome unconfirmed");
                uiStage="SHOW_REGION_SETTLE";long received=entryNavigation.receivedAt(),now=SystemClock.elapsedRealtime();
                if(received<0||now<received||!entryLocalCurrent())throw new SecurityException("Invalid show receipt time");
                long wait=Math.max(0,5000-(now-received));if(wait>0)Thread.sleep(wait);
                entryWaitConfirmed=RewardsEntryBudget.waitSatisfied(received,SystemClock.elapsedRealtime());if(!entryWaitConfirmed||!entryLocalCurrent())throw new SecurityException("Show wait incomplete");
                template=entryRead(V46EntryMetadataReader.Stage.AFTER_WAIT);
            }
            if(entryAnchor.kind()==BingNavigationSnapshot.Kind.MAIN){
                uiStage="ENTRY_PREPARE";entryGateProof=entryAnchor;
                var request=new AndroidRewardsEntryActor.Request(nonce,Integer.parseInt(entryAnchor.task()),width,height,rootRotation,bing,system,android.os.Process.myUid(),activity.getTaskId());
                entryNavigation=AndroidRewardsEntryActor.execute(activity.getApplicationContext(),request,returnReadIntent.readDeadline(),this::entryNavigationCurrent,
                    ()->{if(!entryMain(V46EntryMetadataReader.Stage.PREPARE_BEFORE))return false;entryBudget.burn(RewardsEntryBudget.Slot.PREPARE_1);return entryLocalCurrent()&&entryAuthority();},
                    prepared->{if(!entryMain(V46EntryMetadataReader.Stage.PREPARE_AFTER)||!entryMain(V46EntryMetadataReader.Stage.COMMIT_BEFORE))return false;entryBudget.burn(RewardsEntryBudget.Slot.NAV_1);return entryLocalCurrent()&&entryAuthority();});
                if(entryNavigation==null||!entryNavigation.succeeded()||!AndroidRewardsEntryActor.processKnownClean()||!entryMetadata.knownClean())throw new SecurityException("Home navigation unconfirmed");
                uiStage="ENTRY_SETTLE";long received=entryNavigation.receivedAt(),now=SystemClock.elapsedRealtime();
                if(received<0||now<received||!entryLocalCurrent())throw new SecurityException("Invalid action receipt time");
                long wait=Math.max(0,5000-(now-received));if(wait>0)Thread.sleep(wait);
                entryWaitConfirmed=RewardsEntryBudget.waitSatisfied(received,SystemClock.elapsedRealtime());if(!entryWaitConfirmed||!entryLocalCurrent())throw new SecurityException("Entry wait incomplete");
                template=entryRead(V46EntryMetadataReader.Stage.AFTER_WAIT);
            }
            if(!RewardsEntryBudget.sameTaskTemplate(entryAnchor,template,SystemClock.elapsedRealtime()))throw new SecurityException("Same-task Template unconfirmed");
            if(returnReadIntent.uiLocate()&&!entryAnchor.sameEntrySource(template))throw new SecurityException("Original Template continuity lost after SHOW");
            returnReadIntent.anchorEntryTemplate(template,SystemClock.elapsedRealtime());entryTemplateConfirmed=true;entryProof=template;
            uiStage="ENTRY_COUNTER_READ";entryBudget.burn(RewardsEntryBudget.Slot.UI_READ_1);
            AndroidUiStructureReader.DispatchGate counterGate=()->{
                if(!entryCounterSource(V46EntryMetadataReader.Stage.COUNTER_BEFORE))return false;
                if(returnReadIntent.uiBoundPoints()){
                    if(!rootEpochHeld)return false;
                    uiRegionPending=UiRegionSourceEvidence.begin(this,returnReadIntent,EPOCH,nonce,entryProof,SystemClock.elapsedRealtime());
                    return uiRegionPending!=null;
                }
                return true;
            };
            long uiDeadline=Math.min(returnReadIntent.readDeadline(),SystemClock.elapsedRealtime()+10000);
            uiRead=returnReadIntent.uiBoundPoints()
                ?AndroidUiStructureReader.readRegions(activity.getApplicationContext(),nonce,uiDeadline,this::entryLocalCurrent,counterGate)
                :AndroidUiStructureReader.read(activity.getApplicationContext(),nonce,uiDeadline,this::entryLocalCurrent,counterGate);
            if(uiRead!=null&&uiRead.invoked())entryUiReads++;
            if(uiRead!=null&&uiRead.knownClean())uiSourceAfter=entryCounterSource(V46EntryMetadataReader.Stage.COUNTER_AFTER);
            uiStage="ENTRY_FINISHED";
        }catch(Exception failed){if(failed instanceof InterruptedException)Thread.currentThread().interrupt();uiStage="ENTRY_HOLD";}
        finally{closeUiStructureOnOwner();}
    }
    private boolean entryClean(){return V46EntryMetadataReader.processKnownClean()&&AndroidRewardsEntryActor.processKnownClean()&&(entryMetadata==null||entryMetadata.knownClean())&&(entryNavigation==null||entryNavigation.knownClean());}
    private String entryActionOutcome(){if(entryNavigation==null||!entryNavigation.commitAttempted())return "NOT_COMMITTED";var receipt=entryNavigation.receipt();if(receipt==null)return "UNKNOWN";if(!receipt.actionAttempted())return "NOT_ATTEMPTED";if(receipt.reason()==NativeRewardsEntryProtocol.Reason.ACTION_FALSE)return "RETURNED_FALSE";if(receipt.actionReturnedTrue())return "RETURNED_TRUE";return "UNKNOWN";}
    private String entrySummary(){return "profile="+ReadOnlyCheckpoint.CURRENT.name()+" publisher=NOT_VERIFIED installedSignerContinuityOnly=true navigationAllowed="+(returnReadIntent.uiLocate()?"SHOW_REGION_ONCE":returnReadIntent.uiReady()?"NONE":"HOME_ENTRY_ONLY")+" taskTaps=0 swipes=0 back=0 textEntry=0 screenshots=0 ocr=0 imageAudit=NOT_REQUESTED ledgerChanged=false "+(entryBudget==null?"RUN_1Attempted=false":entryBudget.summary())
        +" "+(entryMetadata==null?"entryMetadataPairs=0":entryMetadata.summary())+" prepareInvoked="+(entryNavigation!=null&&entryNavigation.invocationAttempted())+" preparedObserved="+(entryNavigation!=null&&entryNavigation.preparedObserved())
        +" commitAttempted="+(entryNavigation!=null&&entryNavigation.commitAttempted())+" commitFlushed="+(entryNavigation!=null&&entryNavigation.commitFlushed())+" actionAttempted="+(entryNavigation!=null&&entryNavigation.commitAttempted()&&entryNavigation.receipt()==null?"UNKNOWN":Boolean.toString(entryNavigation!=null&&entryNavigation.actionAttempted()))+" actionReturnedTrue="+(entryNavigation!=null&&entryNavigation.commitAttempted()&&entryNavigation.receipt()==null?"UNKNOWN":Boolean.toString(entryNavigation!=null&&entryNavigation.actionReturnedTrue()))
        +" actionType="+(returnReadIntent.uiBoundPoints()?"NONE":returnReadIntent.uiLocate()?"ACTION_SHOW_ON_SCREEN":"HOME_ENTRY_CLICK")+" actorStatus="+(entryNavigation==null?"NOT_INVOKED":entryNavigation.status())+" actionReceiptReason="+(entryNavigation==null||entryNavigation.receipt()==null?"NOT_OBSERVED":entryNavigation.receipt().reason())+" actionOutcome="+entryActionOutcome()+" navigationCleanup="+(entryNavigation==null?"NOT_INVOKED":entryNavigation.knownClean()?"CONFIRMED":"UNKNOWN")+" waitFiveSeconds="+entryWaitConfirmed+" templateConfirmed="+entryTemplateConfirmed+" uiReadsInvoked="+entryUiReads+" stage="+uiStage;}
    private AndroidUiStructureReader.Result uiRead;private UiStructurePublication pendingUiPublication;
    private UiRegionSourceEvidence.Pending uiRegionPending;
    private BingNavigationSnapshot.CurrentTemplateSource uiSourceProof;
    private boolean uiSourceAfter;private String uiStage="NOT_INVOKED";
    private boolean freshUiSource(){
        rootSourceGate();if(returnMetadata==null||!activity.hiddenWithSoleTask())return false;
        var r=returnMetadata.readCurrentTemplate(new BingNavigationSnapshot.Shape(width,height,rootRotation));
        boolean valid=r!=null&&r.status()==BingNavigationSnapshot.Status.READY&&r.source()!=null&&r.source().fresh(SystemClock.elapsedRealtime())
            &&returnReadIntent.sourceMatches(r.source().ready())&&ReviewBingNavigation.knownClean()&&rootCurrent()&&activity.rootHiddenScalar()&&activity.hiddenWithSoleTask();
        uiSourceProof=valid?r.source():null;return valid;
    }
    private boolean uiLocalCurrent(){long now=SystemClock.elapsedRealtime();return rootCurrent()&&now<returnReadIntent.readDeadline()&&activity.rootHiddenScalar()&&(uiSourceProof==null||uiSourceProof.fresh(now));}
    private void readUiStructureOnOwner(){
        try{
            rootSourceGate();if(!returnReadIntent.uiStructure()||grant!=null||returnReadIndex!=1)throw new SecurityException("UI-only checkpoint required");
            String nonce=java.util.UUID.randomUUID().toString().replace("-","");long now=SystemClock.elapsedRealtime();long end=Math.min(returnReadIntent.readDeadline(),now+10000);
            uiStage="READING_UI_STRUCTURE";
            uiRead=AndroidUiStructureReader.read(activity.getApplicationContext(),nonce,end,this::uiLocalCurrent,()->{
                if(!freshUiSource())return false;
                returnReadIntent.checkpoint().burnUiRead(activity.getNoBackupFilesDir().toPath(),nonce);
                return returnMetadata.authorityMatches(new BingNavigationSnapshot.Shape(width,height,rootRotation))&&activity.hiddenWithSoleTask()&&uiSourceProof!=null&&uiLocalCurrent()&&SystemClock.elapsedRealtime()<end;
            });
            uiStage="READER_RETURNED";
            if(uiRead!=null&&uiRead.knownClean())uiSourceAfter=freshUiSource();
        }catch(Exception failed){if(failed instanceof InterruptedException)Thread.currentThread().interrupt();uiStage="READ_REJECTED_OR_UNAVAILABLE";}
        finally{closeUiStructureOnOwner();}
    }
    private void closeUiStructureOnOwner(){
        if(returnReadIntent.entryDiagnostic()){closeEntryDiagnosticOnOwner();return;}
        if(closeStarted)return;closeStarted=true;
        released=!constructionUnknown&&(!returnReadIntent.entryRecovery()||entryClean())&&ReviewBingNavigation.knownClean()&&AndroidUiStructureReader.processKnownClean()&&(uiRead==null||uiRead.knownClean());
        var report=uiRead==null?null:uiRead.protocolReport();
        boolean geometry=report!=null&&report.displayId()==0&&report.left()>=0&&report.top()>=0&&report.right()<=width&&report.bottom()<=height
            &&(report.rotationBefore()==-1||report.rotationBefore()==rootRotation)&&(report.rotationAfter()==-1||report.rotationAfter()==rootRotation);
        boolean qualified=released&&uiSourceAfter&&geometry&&!cancelled.get()&&returnReadIntent.current(SystemClock.elapsedRealtime());
        var regionSource=uiRegionPending==null?null:uiRegionPending.complete(report,entryProof,SystemClock.elapsedRealtime(),qualified&&rootEpochHeld);
        uiRegionPending=null;
        pendingUiPublication=new UiStructurePublication(report,qualified,Math.min(returnReadIntent.readDeadline(),returnReadIntent.sessionEnd()),regionSource,this,returnReadIntent,EPOCH);
        boolean sourceContinuity=report!=null&&report.regions()!=null?regionSource!=null:uiSourceAfter;
        reportFinal=true;if(released){if(rootEpochHeld){EPOCH.release(this);rootEpochHeld=false;}ROOT.releaseIfClosed(entry);}
        String details="profile=UI_STRUCTURE_V45 read=1 screenshots=0 ocr=0 imageAudit=NOT_REQUESTED taskTaps=0 swipes=0 back=0 textEntry=0 oldBusinessContinued=false actionAuthority=false ledgerChanged=false stage="+uiStage
            +" sourceBeforeAfterSame="+sourceContinuity+" sourceGeometryWithinDisplay="+geometry+" cleanup="+(released?"CONFIRMED":"UNKNOWN")+" readPhaseDeadline="+returnReadIntent.readDeadline()+" parentDeadline="+returnReadIntent.sessionEnd();
        if(returnReadIntent.entryRecovery())details=entrySummary()+" sourceBeforeAfterSame="+sourceContinuity+" sourceGeometryWithinDisplay="+geometry+" cleanup="+(released?"CONFIRMED":"UNKNOWN");
        if(uiRead!=null)details+=" readerStatus="+uiRead.fixedStatus()+" helperInvoked="+uiRead.invoked()+" stdoutBytes="+uiRead.acquiredBytes()+" processQuiet="+uiRead.processQuiet()+" exitObserved="+uiRead.exitObserved()+" exitCode="+uiRead.exitCode();
        if(report!=null)details+=" helperReason="+report.reason()+" helperCleanup="+report.cleanup();
        activity.returnReadFrameFinished(this,null,details);
        if(released&&loop!=null)loop.quitSafely();
    }
    private String finalizeUiStructurePublication(String details){
        var token=pendingUiPublication;pendingUiPublication=null;if(token==null)return details+"\ncounterSource=UI_STRUCTURE evidence=UNQUALIFIED SEARCH=UNKNOWN READING=UNKNOWN publicationFence=MISSING";
        boolean source=false,hidden=false,current=false;
        try{source=released&&reportFinal&&uiSourceAfter&&ReviewBingNavigation.knownClean()&&AndroidUiStructureReader.processKnownClean()
                &&(returnReadIntent.entryRecovery()?entryClean()&&entryAuthority():returnMetadata!=null&&returnMetadata.currentPublicationAuthorityMatches(new BingNavigationSnapshot.Shape(width,height,rootRotation)));
            hidden=activity.rootHiddenScalar()&&activity.hiddenWithSoleTask();long now=SystemClock.elapsedRealtime();
            current=!cancelled.get()&&returnReadIntent.live()&&returnReadIntent.current(now)&&now<returnReadIntent.readDeadline()&&(returnReadIntent.entryRecovery()?entryProof!=null&&entryProof.fresh(now):uiSourceProof!=null&&uiSourceProof.fresh(now));
            if(returnReadIntent.uiBoundPoints())current=current&&!EPOCH.retaining();
        }catch(RuntimeException unknown){source=false;}
        return details+"\n"+token.publish(SystemClock.elapsedRealtime(),current,hidden,source);
    }
    /** Called only at the UI append boundary, after owner cleanup. No source recapture or input. */
    String finalizeProgressPublication(String details){
        if(returnReadIntent!=null&&returnReadIntent.entryDiagnostic())return finalizeEntryDiagnosticPublication(details);
        if(returnReadIntent!=null&&returnReadIntent.uiStructure())return finalizeUiStructurePublication(details);
        if(returnReadIntent==null||!returnReadIntent.layoutDiagnostic()&&!returnReadIntent.progressRead())return details;
        NativeProgressPublication token=pendingProgressPublication;pendingProgressPublication=null;
        if(token==null)return details+(returnReadIntent.checkpoint()==ReadOnlyCheckpoint.PROGRESS_STRUCTURE_V43?"\nstructureEvidence=UNQUALIFIED counterAuthority=false actionAuthority=false regions=SUPPRESSED publicationFence=MISSING":returnReadIntent.layoutDiagnostic()?"\nlayoutEvidence=UNQUALIFIED counterAuthority=false actionAuthority=false candidates=SUPPRESSED publicationFence=MISSING":"\nprogressEvidence=UNQUALIFIED SEARCH=UNKNOWN READING=UNKNOWN publicationFence=MISSING");
        boolean source=false,hidden=false,current=false;
        try{
            source=released&&reportFinal&&rootPreflightSourceConfirmed&&rootCleanReceipt!=null&&rootCleanReceipt.clean()
                &&rootPreflightMetadata!=null&&ReviewBingNavigation.knownClean()
                &&rootPreflightMetadata.publicationAuthorityMatches(new BingNavigationSnapshot.Shape(width,height,rootRotation));
            hidden=activity.hiddenWithSoleTask()&&activity.rootHiddenScalar();
            long now=SystemClock.elapsedRealtime();
            current=!cancelled.get()&&returnReadIntent.live()&&returnReadIntent.current(now)&&grant!=null&&RealCaptureClaim.current(grant,now);
        }catch(RuntimeException unknown){source=false;}
        return details+"\n"+token.publish(SystemClock.elapsedRealtime(),current,hidden,source);
    }
    private boolean scanContinuable;private ReviewShizukuSwipeActor scanActor;private String scanReason="NOT_EVALUATED";
    private boolean scanFreshSource(ReviewBingNavigation reader){
        rootSourceGate();var r=reader.readCurrentTemplate(new BingNavigationSnapshot.Shape(width,height,rootRotation));
        return r!=null&&r.status()==BingNavigationSnapshot.Status.READY&&r.source()!=null&&r.source().fresh(SystemClock.elapsedRealtime())
            &&returnReadIntent.sourceMatches(r.source().ready())&&ReviewBingNavigation.knownClean()&&rootCurrent()&&activity.hiddenWithSoleTask()&&activity.rootHiddenScalar();
    }
    private static boolean locateMarkerStop(MaaRecognizer.RootMarkerDiagnostic markers){
        if(markers==null)return true;
        for(var marker:markers.markers())if(marker.candidates()>0&&switch(marker.marker()){
            case OWN_ROOT_TITLE,OWN_PREFLIGHT_PROMPT,PROJECTION_PROMPT,LOCK_SCREEN_PROMPT,REWARDS_ERROR->true;default->false;
        })return true;return false;
    }
    /** Between cleaned captures only. No old business session, card target or Back authority. */
    private boolean advanceProgressScan(boolean healthy,boolean unpresented,MaaRecognizer.RootMarkerDiagnostic markers){
        if(!healthy){scanReason="UNHEALTHY_STOP";return false;}
        if(returnReadIntent.progressLocate()){
            var layout=readOnlyObservation==null?null:readOnlyObservation.layout();
            if(layout==null||!layout.locateRowsUsable()){scanReason="ROWS_UNKNOWN_STOP";return false;}
            if(layout.locateSafetyStop()||locateMarkerStop(markers)){scanReason="LOGIN_ERROR_OR_UNSAFE_PAGE_STOP";return false;}
            if(layout.locateStopClue()){scanReason="EXACT_LAYOUT_CLUE_STOP";return false;}
        }else{
            var progress=readOnlyObservation==null?null:readOnlyObservation.progress();
            if(progress==null||!progress.scanRowsUsable()){scanReason="ROWS_UNKNOWN_STOP";return false;}
            if(progress.scanClue()){scanReason="HEADING_OR_CONTENT_CLUE_STOP";return false;}
        }
        if(returnReadIndex>=(returnReadIntent.progressLocate()?RootProgressLocateScope.MAX_FRAMES:RootProgressScanScope.MAX_FRAMES)){scanReason="CAPTURE_BUDGET_STOP";return false;}
        if(unpresented){scanReason="HEALTHY_UNPRESENTED_NO_SWIPE";return true;}
        try{
            rootSourceGate();var installed=BingPackageMetadata.read(activity.getPackageManager());
            if(installed.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Source unknown");
            int bing=activity.getPackageManager().getApplicationInfo("com.microsoft.bing",0).uid,system=activity.getPackageManager().getApplicationInfo("com.android.systemui",0).uid;
            var metadata=ReviewBingNavigation.forCurrentRead(activity.getApplicationContext(),installed,bing,system,new RootCurrentReadPermit(this));
            if(!scanFreshSource(metadata)){scanReason="SCROLL_SOURCE_REJECTED";return false;}
            var permit=returnReadIntent.claimScroll(returnReadIndex,EPOCH,requestOwner,SystemClock.elapsedRealtime());
            scanActor=returnReadIntent.progressLocate()?ReviewShizukuSwipeActor.forProgressLocate(activity.getApplicationContext()):ReviewShizukuSwipeActor.forProgressScan(activity.getApplicationContext());returnReadIntent.scrollActorCreated();
            var target=returnReadIntent.progressLocate()?new ReviewShizukuSwipeActor.SwipeTarget(width,height,rootRotation,ReviewShizukuSwipeActor.Direction.DOWN):new ReviewShizukuSwipeActor.SwipeTarget(width,height,rootRotation);
            var scroll=scanActor.swipe(target,new ReviewShizukuSwipeActor.DispatchGate(){
                public boolean consume(ReviewShizukuSwipeActor.SwipeTarget exact)throws Exception{
                    rootSourceGate();if(exact!=target||!rootPreflightFresh()||!permit.burn(requestOwner,SystemClock.elapsedRealtime()))return false;
                    if(returnReadIntent.progressLocate())RootProgressLocateScope.burnScroll(activity.getNoBackupFilesDir().toPath(),returnReadIndex,"fixedDownward=true maxSwipes=2 taskTaps=0 back=0 textEntry=0\n");
                    else RootProgressScanScope.burnScroll(activity.getNoBackupFilesDir().toPath(),returnReadIndex,"fixedUpward=true maxSwipes=2 taskTaps=0 back=0 textEntry=0\n");return true;
                }
                public boolean stillValid(ReviewShizukuSwipeActor.SwipeTarget exact){return exact==target&&scanFreshSource(metadata)&&permit.current(requestOwner,SystemClock.elapsedRealtime())&&rootPreflightFresh()&&rootCurrent()&&activity.rootHiddenScalar();}
            });
            returnReadIntent.scrollResult(permit,scroll,SystemClock.elapsedRealtime());
            scanReason="SCROLL_"+scroll.kind()+"_"+scroll.reason();
            if(!scroll.commandExitZero()||!scanActor.knownClean()||!ReviewBingNavigation.knownClean()||!rootCurrent()||!activity.hiddenWithSoleTask())return false;
            Thread.sleep(500); // Bounded scheduling only; never evidence of readiness or movement.
            rootSourceGate();return rootCurrent()&&activity.hiddenWithSoleTask();
        }catch(Exception failed){if(failed instanceof InterruptedException)Thread.currentThread().interrupt();scanReason="SCROLL_REJECTED_OR_UNKNOWN";return false;}
    }
    /** A distinct scan continuation, never an ordinary healthy-unpresented token. */
    private static final class ScanContinuation {
        private final CaptureSessionRuntime run;private final long boundary;private final AtomicBoolean spent=new AtomicBoolean();
        private ScanContinuation(CaptureSessionRuntime run){this.run=run;boundary=run.returnReadIntent.scanBoundary();}
        boolean consume(RootReturnReadIntent intent,long now){return spent.compareAndSet(false,true)&&intent==run.returnReadIntent&&intent.scanNavigation()&&intent.live()
            &&run.scanContinuable&&run.released&&run.reportFinal&&!run.cancelled.get()&&intent.current(now)&&now<intent.readDeadline()
            &&boundary==intent.scanBoundary()&&ReviewBingNavigation.knownClean()&&(run.scanActor==null||run.scanActor.knownClean());}
    }
    private void closeReturnReadOnOwner(){
        if(closeStarted)return;closeStarted=true;var result=backend==null?null:backend.snapshot();boolean resources=rootResourcesClosed(result);
        MaaImageBufferAudit.Result audit=null;MaaRecognizer.RootMarkerDiagnostic markers=null;boolean healthy=false,unpresented=false;
        if(!cancelled.get()&&resources&&rootCleanReceipt!=null&&rootCleanReceipt.clean()&&completedReport!=null){
            try{rootPreflightSourceConfirmed=acceptRootSourcePostcheck();}
            catch(RuntimeException failed){rootStage="SOURCE_POSTCHECK_FAILED";}
            finally{resources=rootResourcesAfterPostcheck(resources,()->rootResourcesClosed(result));}
            if(recognizer!=null&&result!=null){audit=recognizer.imageBufferAudit(result.owner());markers=recognizer.rootMarkerDiagnostic(result.owner());}
            healthy=resources&&rootPreflightSourceConfirmed&&rootPreflightFresh()&&readOnlyObservation!=null&&readOnlyObservation.capture==readOnlyCapture&&readOnlyCapture.owner==requestOwner
                &&readOnlyObservation.validNativeShape()&&readOnlyObservation.nativeNonemptyRows()>0&&completeSeriesAudits(rootBinding.imageAudit(),audit)
                &&audit.positiveTaskBound()&&audit.javaScratchWiped()&&audit.imageDestroyed()&&audit.taskerDestroyed();
            unpresented=healthy&&returnReadUnpresented(readOnlyObservation,audit,markers);
        }
        if(returnReadIntent.scanNavigation()){
            scanContinuable=advanceProgressScan(healthy,unpresented,markers);
            resources=resources&&rootResourcesClosed(result)&&(scanActor==null||scanActor.knownClean())&&ReviewBingNavigation.knownClean();
            scanContinuable=scanContinuable&&resources&&!cancelled.get()&&rootCurrent();
        }
        released=resources;rootCandidate=false;rootPublished=false;reportFinal=true;
        if(released){if(rootEpochHeld){EPOCH.release(this);rootEpochHeld=false;}ROOT.releaseIfClosed(entry);}
        String details="profile="+(returnReadIntent.progressLocate()?"PROGRESS_LOCATE_V42":returnReadIntent.checkpoint()==ReadOnlyCheckpoint.PROGRESS_STRUCTURE_V43?"PROGRESS_STRUCTURE_V43":returnReadIntent.checkpoint()!=null?"PROGRESS_LAYOUT_V41":returnReadIntent.scanNavigation()?"PROGRESS_SCAN_V40":returnReadIntent.progressRead()?"PROGRESS_READ_V39":returnReadIntent.statusRecovery()?"STATUS_RECOVERY_V36":returnReadIntent.currentOnly()?"CURRENT_CARDS_V34":"RETURN_READ_V32")+" currentOnly="+returnReadIntent.currentOnly()+" read="+returnReadIndex+" taskTaps=0 oldBusinessContinued=false completionCause=UNPROVEN pageCandidate=false businessPass=false rootStage="+rootStage
            +" rootCleanup="+(released?"CONFIRMED":"UNKNOWN")+" healthy="+healthy+" explicitUnpresented="+unpresented+" sourcePrePostSame="+rootPreflightSourceConfirmed
            +" readPhaseDeadline="+returnReadIntent.readDeadline()+" parentDeadline="+returnReadIntent.sessionEnd()+" preAttempts="+rootReadinessAttempts+" preStatus="+rootReadinessStatus+" preGate="+rootReadinessGate+" postStatus="+rootPostStatus+" postGate="+rootPostGate
            +" nativePhase="+(result==null?"NOT_STARTED":result.phase())+" nativeFailure="+(result==null?"NONE":result.failure())+"\n"+returnSummary;
        if(rootBinding!=null){details+="\n"+rootBinding.source().cleanupSnapshot(result==null?null:result.owner()).safeSummary()+"\n"+seriesPngSummary(rootBinding.imageAudit());}
        if(audit!=null)details+="\n"+(returnReadIntent.layoutDiagnostic()?audit.compactSummary():audit.summary());if(markers!=null)details+="\n"+markers.summary();
        if(returnReadIntent.checkpoint()==ReadOnlyCheckpoint.PROGRESS_STRUCTURE_V43)pendingProgressPublication=new NativeProgressPublication(
            readOnlyObservation==null?null:readOnlyObservation.regions(),healthy,rootLower,returnReadIntent.readDeadline(),returnReadIntent.sessionEnd());
        else if(returnReadIntent.layoutDiagnostic())pendingProgressPublication=new NativeProgressPublication(
            readOnlyObservation==null?null:readOnlyObservation.layout(),healthy,rootLower,returnReadIntent.readDeadline(),returnReadIntent.sessionEnd());
        else if(returnReadIntent.progressRead())pendingProgressPublication=new NativeProgressPublication(
            readOnlyObservation==null?null:readOnlyObservation.progress(),healthy,rootLower,returnReadIntent.readDeadline(),returnReadIntent.sessionEnd(),returnReadIntent.scrollAttempts(),returnReadIntent.scrollConfirmed(),returnReadIntent.scrollActor());
        else details+="\n"+qualifiedCurrentReadSummary(readOnlyObservation,healthy,returnReadIntent.currentCardsOnly());
        if(!returnReadIntent.layoutDiagnostic()&&readOnlyObservation!=null&&readOnlyObservation.frame.diagnostic()!=null)details+="\nlegacyBusinessDiagnosticOnly=true "+readOnlyObservation.frame.diagnostic().summary();
        if(returnReadIntent.checkpoint()==ReadOnlyCheckpoint.PROGRESS_STRUCTURE_V43)details+="\ncheckpointSingleFrame=true navigationActorConstructed=false swipes=0 back=0 textEntry=0 businessActionAuthority=false";
        details+="\nclientSfProbe=NOT_INVOKED captureAtomic=false";
        if(returnReadIntent.scanNavigation())details+="\nscanReason="+scanReason+" "+returnReadIntent.scanSummary()+" navigationMode="+(returnReadIntent.progressLocate()?"BOUNDED_DOWN_V42":"BOUNDED_UP_V40")+" businessActionAuthority=false taskTaps=0 back=0 textEntry=0";
        ReturnReadCompletion next=unpresented&&released&&!cancelled.get()&&rootCurrent()&&returnReadIndex<RootReturnReadScope.MAX_FRAMES&&returnReadIntent.checkpoint()==null?new ReturnReadCompletion(this):null;
        if(returnReadIntent.scanNavigation())next=scanContinuable?new ReturnReadCompletion(this,new ScanContinuation(this)):null;
        activity.returnReadFrameFinished(this,next,details);
        readOnlyCapture=null;readOnlyObservation=null;
        if(released){recognizer=null;rootBinding=null;backend=null;loop.quitSafely();}
    }
    static final class ReturnReadCompletion {
        private final CaptureSessionRuntime run;private final RootReturnReadIntent intent;private final int index;private final RealCaptureClaim.ProcessEpoch epoch;
        private final ReviewShizukuPngBinding.CleanReceipt fixtureClean;private final AtomicBoolean spent=new AtomicBoolean();private final ScanContinuation scan;
        private ReturnReadCompletion(CaptureSessionRuntime r){run=r;intent=r.returnReadIntent;index=r.returnReadIndex;epoch=EPOCH;fixtureClean=null;scan=null;}
        private ReturnReadCompletion(CaptureSessionRuntime r,ScanContinuation scan){run=r;intent=r.returnReadIntent;index=r.returnReadIndex;epoch=EPOCH;fixtureClean=null;this.scan=scan;}
        private ReturnReadCompletion(RootReturnReadIntent i,int n,RealCaptureClaim.ProcessEpoch e,ReviewShizukuPngBinding.CleanReceipt c){run=null;intent=i;index=n;epoch=e;fixtureClean=c;scan=null;}
        boolean consume(RootReturnReadIntent exact,int previous,RealCaptureClaim.ProcessEpoch e,long now){return spent.compareAndSet(false,true)&&exact==intent&&index==previous&&index<RootReturnReadScope.MAX_FRAMES&&e==epoch&&intent.current(now)&&now<intent.readDeadline()
            &&(scan==null?!intent.scanNavigation():scan.consume(intent,now))
            &&(run!=null?run.released&&run.reportFinal&&!run.cancelled.get()&&run.rootCurrent():!intent.live()&&fixtureClean!=null&&!fixtureClean.liveTransport()&&fixtureClean.clean());}
        static ReturnReadCompletion fixture(RootReturnReadIntent intent,int index,RealCaptureClaim.ProcessEpoch epoch,Object owner,ReviewShizukuPngBinding.Binding binding,ReadOnlyTwoStageRun.Observation observation,MaaImageBufferAudit.Result audit,MaaRecognizer.RootMarkerDiagnostic markers,long now){
            var clean=binding==null?null:binding.cleanReceipt();if(intent==null||intent.live()||epoch==null||owner==null||!RootReturnReadScope.validIndex(index)||index>=RootReturnReadScope.MAX_FRAMES
                ||clean==null||clean.liveTransport()||!clean.clean()||!intent.current(now)||now>=intent.readDeadline()||observation==null||observation.capture.owner!=owner
                ||!completeSeriesAudits(binding.imageAudit(),audit)||!returnReadUnpresented(observation,audit,markers))throw new SecurityException("Actual nonlive healthy unpresented frame required");
            return new ReturnReadCompletion(intent,index,epoch,clean);
        }
    }
    static RootImageSeriesIntent approveSeries(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,Object rootOwner){
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read build");
        return VisibleCaptureAuthority.issueSeriesIntent(activity,click,EPOCH,rootOwner,SystemClock.elapsedRealtime());
    }
    static CaptureSessionRuntime startSeries(DormantCaptureConfirmationActivity activity,RootImageSeriesIntent intent,
            Object rootOwner,int index,SeriesCompletion previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live entry only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live entry only");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read build");
        CaptureReleaseGate.requireConfigured();
        if(RootBoundedBusinessScope.ACTIVE||!RootImageSeriesScope.ACTIVE||intent==null||!intent.live()||!intent.matchesOwner(rootOwner,activity,activity.requestIdentity())
                ||Looper.myLooper()!=Looper.getMainLooper()||android.os.Process.myUid()<10000
                ||!Application.getProcessName().equals(activity.getPackageName()+":capture_once")||android.os.Build.VERSION.SDK_INT<34)
            throw new SecurityException("Original private live series required");
        CaptureSessionRuntime run=new CaptureSessionRuntime(activity);
        run.seriesIntent=intent;run.seriesIndex=index;run.rootOwner=rootOwner;
        // Legacy labels are diagnostic only: actual authority is the independent indexed series permit.
        run.rootMode=RootCaptureScope.Mode.PREFLIGHT;run.rootPhase=RootCaptureScope.Phase.FIRST;
        android.graphics.Rect bounds=activity.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        run.width=bounds.width();run.height=bounds.height();run.dpi=activity.getResources().getConfiguration().densityDpi;
        requireGeometry(run.width,run.height,run.dpi);
        var manager=activity.getSystemService(android.hardware.display.DisplayManager.class);
        var display=manager==null?null:manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=android.view.Display.STATE_ON)throw new SecurityException("Display unavailable");
        run.rootRotation=display.getRotation();long now=SystemClock.elapsedRealtime();
        long deadline=RootImageSeriesScope.deadline(now,System.currentTimeMillis(),intent.sessionEnd());
        var proof=VisibleCaptureAuthority.issueSeriesFrame(intent,index,EPOCH,run.requestOwner,now,deadline,previous);
        run.grant=RealCaptureClaim.visible(proof,EPOCH,run.requestOwner,now);
        if(!EPOCH.retain(run))throw new SecurityException("Previous epoch is retained");run.rootEpochHeld=true;
        try{run.entry=ROOT.retain(run,EPOCH,deadline,run::latchCancel,run::closeOnOwner,()->run.released,
                task->run.owner!=null&&run.owner.post(task));}
        catch(RuntimeException denied){EPOCH.release(run);run.rootEpochHeld=false;throw denied;}
        try{
            run.rootSourceReadPermit=new RootSourceReadPermit(run,new BingNavigationSnapshot.ReadOnlyGuard(
                android.os.Process.myUid(),activity.getTaskId(),activity.getComponentName().flattenToString()));
            run.loop.start();run.owner=new Handler(run.loop.getLooper());
            ROOT.arm(run.entry,(end,task)->DEADLINES.schedule(task,Math.max(0,end-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS),SystemClock::elapsedRealtime);
            if(index==1){
                activity.requestHandoff(()->{run.requireRootCurrent();intent.requestHandoff(System.nanoTime());},()->{
                    intent.hiddenHandoff(System.nanoTime());if(!run.owner.post(run::acquiredRootOnOwner))run.cancel();
                },()->activity.seriesVisibilityRevoked(intent));
            }else if(!activity.rootHiddenScalar()||!run.owner.post(run::acquiredRootOnOwner))throw new SecurityException("Original source hidden state lost");
        }catch(RuntimeException failed){run.constructionUnknown=true;run.cancel();}
        return run;
    }
    static RootBoundedBusinessIntent approveBounded(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,Object rootOwner){
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read build");
        return VisibleCaptureAuthority.issueBoundedIntent(activity,click,EPOCH,rootOwner,SystemClock.elapsedRealtime());
    }
    static CaptureSessionRuntime startBounded(DormantCaptureConfirmationActivity activity,RootBoundedBusinessIntent intent,
            Object rootOwner,NativeAutonomousCardRun business,NativeRewardCardSession.Phase phase,int attempt,BoundedTransition previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live entry only");
        if(RootSelectedPendingScope.ACTIVE)throw new SecurityException("Selected B live entry only");
        if(RootReturnReadScope.ACTIVE)throw new SecurityException("Independent return-read build");
        if(intent==null||intent.selectedPending())throw new SecurityException("Legacy bounded mode required");
        return startBoundedInternal(activity,intent,rootOwner,business,phase,attempt,previous);
    }
    static RootBoundedBusinessIntent approveSelectedPending(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,Object root){
        return VisibleCaptureAuthority.issueSelectedPendingIntent(a,click,EPOCH,root,SystemClock.elapsedRealtime());
    }
    static CaptureSessionRuntime startSelectedPending(DormantCaptureConfirmationActivity activity,RootBoundedBusinessIntent intent,
            Object rootOwner,NativeAutonomousCardRun business,NativeRewardCardSession.Phase phase,int attempt,BoundedTransition previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        if(RootStatusRecoveryScope.ACTIVE)throw new SecurityException("Status recovery live entry only");
        if(!RootSelectedPendingScope.ACTIVE||intent==null||!intent.selectedPending()||business==null||!business.selectedPendingOnly())throw new SecurityException("Exact selected B run required");
        return startBoundedInternal(activity,intent,rootOwner,business,phase,attempt,previous);
    }
    static RootBoundedBusinessIntent approveUniquePending(DormantCaptureConfirmationActivity a,DormantCaptureConfirmationActivity.LiveClick click,Object root){
        return VisibleCaptureAuthority.issueUniquePendingIntent(a,click,EPOCH,root,SystemClock.elapsedRealtime());
    }
    static CaptureSessionRuntime startUniquePending(DormantCaptureConfirmationActivity activity,RootBoundedBusinessIntent intent,
            Object rootOwner,NativeAutonomousCardRun business,NativeRewardCardSession.Phase phase,int attempt,BoundedTransition previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(!RootUniqueRefinedScope.ACTIVE||intent==null||!intent.uniquePending()||intent.selectedPending()||business==null||!business.uniquePendingOnly()||business.selectedPendingOnly())throw new SecurityException("Exact unique pending run required");
        return startBoundedInternal(activity,intent,rootOwner,business,phase,attempt,previous);
    }
    private static CaptureSessionRuntime startBoundedInternal(DormantCaptureConfirmationActivity activity,RootBoundedBusinessIntent intent,
            Object rootOwner,NativeAutonomousCardRun business,NativeRewardCardSession.Phase phase,int attempt,BoundedTransition previous){
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        CaptureReleaseGate.requireConfigured();
        if(intent==null||!(intent.uniquePending()?RootUniqueRefinedScope.ACTIVE:intent.selectedPending()?RootSelectedPendingScope.ACTIVE:RootBoundedBusinessScope.ACTIVE)||!intent.live()||!intent.matchesOwner(rootOwner,activity,activity.requestIdentity())
                ||business==null||business.fixtureOnly()||!business.boundedOnly()||business.selectedPendingOnly()!=intent.selectedPending()||business.uniquePendingOnly()!=intent.uniquePending()||!intent.matchesRun(business)||phase==null
                ||Looper.myLooper()!=Looper.getMainLooper()||android.os.Process.myUid()<10000
                ||!Application.getProcessName().equals(activity.getPackageName()+":capture_once")||android.os.Build.VERSION.SDK_INT<34)
            throw new SecurityException("Original private live series required");
        CaptureSessionRuntime run=new CaptureSessionRuntime(activity);
        run.boundedIntent=intent;run.boundedAttempt=attempt;run.rootOwner=rootOwner;run.autonomousRun=business;run.cardPhase=phase;
        if(attempt==1)business.reserve(phase);
        // Hardware label is diagnostic; authority is the exact new business phase/attempt permit.
        run.rootMode=RootCaptureScope.Mode.BUSINESS;run.rootPhase=phase==NativeRewardCardSession.Phase.BEFORE?RootCaptureScope.Phase.FIRST:RootCaptureScope.Phase.SECOND;
        android.graphics.Rect bounds=activity.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        run.width=bounds.width();run.height=bounds.height();run.dpi=activity.getResources().getConfiguration().densityDpi;
        requireGeometry(run.width,run.height,run.dpi);
        var manager=activity.getSystemService(android.hardware.display.DisplayManager.class);
        var display=manager==null?null:manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=android.view.Display.STATE_ON)throw new SecurityException("Display unavailable");
        run.rootRotation=display.getRotation();long now=SystemClock.elapsedRealtime();
        long deadline=business.deadline(now);
        var proof=intent.uniquePending()?VisibleCaptureAuthority.issueUniquePendingFrame(intent,phase,attempt,EPOCH,run.requestOwner,now,deadline,previous):intent.selectedPending()?VisibleCaptureAuthority.issueSelectedPendingFrame(intent,phase,attempt,EPOCH,run.requestOwner,now,deadline,previous):VisibleCaptureAuthority.issueBoundedFrame(intent,phase,attempt,EPOCH,run.requestOwner,now,deadline,previous);
        run.grant=RealCaptureClaim.visible(proof,EPOCH,run.requestOwner,now);
        if(!EPOCH.retain(run))throw new SecurityException("Previous epoch is retained");run.rootEpochHeld=true;
        try{run.entry=ROOT.retain(run,EPOCH,deadline,run::latchCancel,run::closeOnOwner,()->run.released,
                task->run.owner!=null&&run.owner.post(task));}
        catch(RuntimeException denied){EPOCH.release(run);run.rootEpochHeld=false;throw denied;}
        try{
            run.rootSourceReadPermit=new RootSourceReadPermit(run,new BingNavigationSnapshot.ReadOnlyGuard(
                android.os.Process.myUid(),activity.getTaskId(),activity.getComponentName().flattenToString()));
            run.loop.start();run.owner=new Handler(run.loop.getLooper());
            ROOT.arm(run.entry,(end,task)->DEADLINES.schedule(task,Math.max(0,end-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS),SystemClock::elapsedRealtime);
            if(phase==NativeRewardCardSession.Phase.BEFORE&&attempt==1){
                activity.requestHandoff(run::requireRootCurrent,()->{
                    if(!run.owner.post(run::acquiredRootOnOwner))run.cancel();
                },()->activity.boundedVisibilityRevoked(intent));
            }else if(!activity.rootHiddenScalar()||!run.owner.post(run::acquiredRootOnOwner))throw new SecurityException("Original source hidden state lost");
        }catch(RuntimeException failed){run.constructionUnknown=true;run.cancel();}
        return run;
    }
    boolean isBounded(){return boundedIntent!=null;}
    RootBoundedBusinessIntent boundedIntent(){return boundedIntent;}
    int boundedAttempt(){return boundedAttempt;}
    NativeRewardCardSession.Phase boundedPhase(){return cardPhase;}
    boolean boundedPublicationCurrent(){return boundedIntent!=null&&rootCandidate&&released&&!cancelled.get()&&rootCurrent()
        &&autonomousRun.publicationCurrent(RealCaptureClaim.deadline(grant));}
    boolean commitBounded(){if(!boundedPublicationCurrent()||boundedCommitted)return false;boundedCommitted=true;return true;}
    boolean isSeries(){return seriesIntent!=null;}
    int seriesIndex(){return seriesIndex;}
    RootImageSeriesIntent seriesIntent(){return seriesIntent;}
    MaaImageBufferAudit.Fingerprint takeSeriesFingerprint(){var value=seriesImageResult;seriesImageResult=null;return value==null?null:value.fingerprint();}
    private boolean rootAuthorityLive(){if(returnReadIntent!=null)return returnReadIntent.live();if(boundedIntent!=null)return boundedIntent.live();return seriesIntent!=null?seriesIntent.live():rootIntent!=null&&rootIntent.live();}
    boolean isRoot(){return returnReadIntent!=null||rootIntent!=null||seriesIntent!=null||boundedIntent!=null;}
    RootCaptureScope.Mode rootMode(){return rootMode;}
    RootCaptureScope.Phase rootPhase(){return rootPhase;}
    private boolean rootCurrent(){if(returnReadIntent!=null)return !cancelled.get()&&returnReadIntent.current(SystemClock.elapsedRealtime())&&(grant==null?!returnPhaseGranted&&returnReadIndex==1:RealCaptureClaim.current(grant,SystemClock.elapsedRealtime()));if(boundedIntent!=null)return !cancelled.get()&&boundedIntent.current(SystemClock.elapsedRealtime())&&RealCaptureClaim.current(grant,SystemClock.elapsedRealtime());if(seriesIntent!=null)return !cancelled.get()&&seriesIntent.current(SystemClock.elapsedRealtime())&&RealCaptureClaim.current(grant,SystemClock.elapsedRealtime());return rootIntent!=null&&!cancelled.get()&&rootIntent.current(SystemClock.elapsedRealtime())
            &&RealCaptureClaim.current(grant,SystemClock.elapsedRealtime())
            &&(RootFamilyDiagnosticProfile.ACTIVE?RootFamilyDiagnosticProfile.current(System.currentTimeMillis()):RootCaptureScope.current(System.currentTimeMillis()));}
    private void requireRootCurrent(){if(!rootCurrent())throw new SecurityException("Root phase no longer current");}
    private void rootSourceGate(){requireRootCurrent();if(!activity.rootHiddenScalar())throw new SecurityException("Root source visibility lost");}
    private void rootNavigationRevoked(){if(returnReadIntent!=null){activity.returnReadVisibilityRevoked(returnReadIntent);return;}if(boundedIntent!=null)activity.boundedVisibilityRevoked(boundedIntent);else if(seriesIntent!=null)activity.seriesVisibilityRevoked(seriesIntent);else activity.rootVisibilityRevoked(rootIntent);}
    void rootVisibilityRevoked(){if(!reportFinal||!released||!rootPublished)cancel();}
    private BackendPorts.ClaimReceipt consumeRootClaim(OnceBackend.Owner owner){
        if(owner==null||!rootCurrent()||!rootEpochHeld)return new BackendPorts.ClaimReceipt(owner,BackendPorts.ClaimDecision.DENIED);
        var outcome=RealCaptureClaim.consume(grant,EPOCH,requestOwner,SystemClock.elapsedRealtime(),
                returnReadIntent!=null?(returnReadIntent.progressLocate()?RootProgressLocateScope.store(activity.getNoBackupFilesDir().toPath(),returnReadIndex):returnReadIntent.checkpoint()!=null?returnReadIntent.checkpoint().store(activity.getNoBackupFilesDir().toPath(),returnReadIndex):returnReadIntent.scanNavigation()?RootProgressScanScope.store(activity.getNoBackupFilesDir().toPath(),returnReadIndex):returnReadIntent.progressRead()?RootProgressReadScope.store(activity.getNoBackupFilesDir().toPath(),returnReadIndex):returnReadIntent.statusRecovery()?RootStatusRecoveryScope.store(activity.getNoBackupFilesDir().toPath(),returnReadIndex):returnReadIntent.currentOnly()?RootCurrentCardScope.store(activity.getNoBackupFilesDir().toPath(),returnReadIndex):RootReturnReadScope.store(activity.getNoBackupFilesDir().toPath(),returnReadIndex)):boundedIntent!=null?(boundedIntent.uniquePending()?RootUniqueRefinedScope.store(activity.getNoBackupFilesDir().toPath(),cardPhase,boundedAttempt):boundedIntent.selectedPending()?RootSelectedPendingScope.store(activity.getNoBackupFilesDir().toPath(),cardPhase,boundedAttempt):RootBoundedBusinessScope.store(activity.getNoBackupFilesDir().toPath(),cardPhase,boundedAttempt)):seriesIntent!=null?RootImageSeriesScope.store(activity.getNoBackupFilesDir().toPath(),seriesIndex):RootFamilyDiagnosticProfile.ACTIVE?RootCaptureScope.diagnosticStore(activity.getNoBackupFilesDir().toPath()):RootCaptureScope.store(activity.getNoBackupFilesDir().toPath(),rootMode,rootPhase));
        rootClaimUncertain=outcome==RealCaptureClaim.Outcome.UNCERTAIN;
        return new BackendPorts.ClaimReceipt(owner,switch(outcome){case CONSUMED->BackendPorts.ClaimDecision.CLAIMED;case REJECTED->BackendPorts.ClaimDecision.DUPLICATE;case UNCERTAIN->BackendPorts.ClaimDecision.UNCERTAIN;});
    }
    // Identity anchor from a completed timed return; never a fresh source observation itself.
    private BingNavigationSnapshot.Ready rootTimedAfterAnchor;
    private void prepareRootSource(){
        if(rootMode==RootCaptureScope.Mode.BUSINESS&&cardPhase==NativeRewardCardSession.Phase.AFTER&&autonomousRun!=null)
            rootTimedAfterAnchor=autonomousRun.timedAfterSource();
        var installed=BingPackageMetadata.read(activity.getPackageManager());
        if(installed.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Bing metadata unavailable");
        int bingUid,systemUid;
        try{bingUid=activity.getPackageManager().getApplicationInfo("com.microsoft.bing",0).uid;systemUid=activity.getPackageManager().getApplicationInfo("com.android.systemui",0).uid;}
        catch(android.content.pm.PackageManager.NameNotFoundException missing){throw new SecurityException("Source metadata unavailable");}
        rootPreflightMetadata=ReviewBingNavigation.forRootPhaseSource(activity.getApplicationContext(),installed,bingUid,systemUid,rootSourceReadPermit);
        BingNavigationSnapshot.Result result=null;
        int limit=3; // Exact per-phase source budget; never borrows the navigation reader.
        for(int attempt=0;attempt<limit;attempt++){
            rootSourceGate();rootReadinessAttempts++;
            result=rootTimedAfterAnchor==null?rootPreflightMetadata.readPre(new BingNavigationSnapshot.Shape(width,height,rootRotation)):rootPreflightMetadata.readPreTimedReturn(new BingNavigationSnapshot.Shape(width,height,rootRotation),rootTimedAfterAnchor);rootReadinessStatus=result.status().name();rootReadinessGate=rootPreflightMetadata.lastReadOnlyGate().name();
            if(result.status()!=BingNavigationSnapshot.Status.BING_TRANSITION)break;
            rootReadinessTransitions++;if(rootReadinessTransitions==1)rootFirstTransitionGate=rootReadinessGate;
            if(attempt+1<limit){android.os.SystemClock.sleep(40);rootSourceGate();} // Pacing only; never establishes readiness.
        }
        if(result==null)throw new SecurityException("No source observation");
        if(result.status()!=BingNavigationSnapshot.Status.READY||result.ready().kind()!=BingNavigationSnapshot.Kind.TEMPLATE
                ||!result.ready().fresh(SystemClock.elapsedRealtime())||!rootCurrent()||!activity.hiddenWithSoleTask())throw new SecurityException("Preflight source unavailable");
        if(seriesIntent!=null&&!seriesIntent.sourceMatches(seriesIndex,result.ready()))throw new SecurityException("Series source identity changed");
        if(boundedIntent!=null&&!boundedIntent.sourceMatches(result.ready()))throw new SecurityException("Bounded source identity changed");
        if(returnReadIntent!=null&&!returnReadIntent.sourceMatches(result.ready()))throw new SecurityException("Recovered template changed");
        rootPreflightBefore=result.ready();rootLower=SystemClock.elapsedRealtime();
        if(returnReadIntent!=null&&returnReadIntent.scanNavigation()&&rootLower<=returnReadIntent.scanBoundary())throw new SecurityException("Post-scroll capture must be strictly newer");
        if(returnReadIntent!=null)readOnlyCapture=returnReadIntent.checkpoint()==ReadOnlyCheckpoint.PROGRESS_STRUCTURE_V43?ReadOnlyTwoStageRun.Capture.structureDiagnostic(requestOwner,java.util.UUID.randomUUID().toString(),width,height,rootRotation,rootLower,()->!rootCurrent()):returnReadIntent.layoutDiagnostic()?ReadOnlyTwoStageRun.Capture.layoutDiagnostic(requestOwner,java.util.UUID.randomUUID().toString(),width,height,rootRotation,rootLower,()->!rootCurrent()):returnReadIntent.progressRead()?ReadOnlyTwoStageRun.Capture.progressRead(requestOwner,java.util.UUID.randomUUID().toString(),width,height,rootRotation,rootLower,()->!rootCurrent()):returnReadIntent.currentCardsOnly()?ReadOnlyTwoStageRun.Capture.moreCurrentCards(requestOwner,java.util.UUID.randomUUID().toString(),width,height,rootRotation,rootLower,()->!rootCurrent()):ReadOnlyTwoStageRun.Capture.moreCurrentStatus(requestOwner,java.util.UUID.randomUUID().toString(),width,height,rootRotation,rootLower,()->!rootCurrent());
        else if(rootMode==RootCaptureScope.Mode.PREFLIGHT)readOnlyCapture=ReadOnlyTwoStageRun.Capture.moreFamilyDiagnostic(
            requestOwner,java.util.UUID.randomUUID().toString(),width,height,rootRotation,rootLower,()->!rootCurrent());
    }
    private void acquiredRootOnOwner(){
        try{
            rootSourceGate();rootStage="SOURCE_PRECHECK";
            seriesSampleStartMono=System.nanoTime();prepareRootSource();
            if(seriesIntent!=null)rootSourceGate(); // v30: optional client/SF diagnostics are not invoked.
            if(boundedIntent!=null){
                preparedCapture=autonomousRun.startPrepared(cardPhase,boundedAttempt,requestOwner,rootLower,rootPreflightBefore);
                if(preparedCapture==null||preparedCapture.width()!=width||preparedCapture.height()!=height||preparedCapture.rotation()!=rootRotation)
                    throw new SecurityException("Exact prepared source unavailable");
                recognizer=MaaRecognizer.rootPreparedRewardCardAudit(activity.getApplicationContext(),preparedCapture);
            }else if(rootMode==RootCaptureScope.Mode.PREFLIGHT){recognizer=returnReadIntent!=null?MaaRecognizer.rootCurrentStatusAudit(activity.getApplicationContext(),readOnlyCapture):seriesIntent!=null?MaaRecognizer.rootImageBufferSeriesAudit(activity.getApplicationContext(),readOnlyCapture):RootFamilyDiagnosticProfile.ACTIVE
                ?MaaRecognizer.rootImageBufferAudit(activity.getApplicationContext(),readOnlyCapture)
                :MaaRecognizer.rootPreflight(activity.getApplicationContext(),readOnlyCapture);}
            else{
                cardCapture=autonomousRun.startCapture(cardPhase,width,height);
                if(cardCapture==null||cardCapture.rotation!=rootRotation)throw new SecurityException("Business native source unavailable");
                rootLower=cardCapture.acquiredLowerBound;recognizer=MaaRecognizer.liveRewardCard(activity.getApplicationContext(),cardCapture);
            }
            rootSourceGate();rootStage="SOURCE_BIND";
            rootBinding=returnReadIntent!=null||boundedIntent!=null||seriesIntent!=null||RootFamilyDiagnosticProfile.ACTIVE
                ?ReviewShizukuPngBinding.bindImageAudit(activity.getApplicationContext(),this::consumeRootClaim,this::rootSourceGate,
                    rootFence,recognizer,Runnable::run,width,height,rootRotation)
                :ReviewShizukuPngBinding.bind(activity.getApplicationContext(),this::consumeRootClaim,this::rootSourceGate,
                    rootFence,recognizer,Runnable::run,width,height,rootRotation);
            backend=rootBinding.backend();rootStage="BACKEND_START";
            if(cancelled.get()){rootBinding.source().requestStop();backend.cancel(backend.snapshot().owner());}
            OnceBackend.Result result=backend.start();rootStage="BACKEND_RETURNED";
            if(!cancelled.get()&&result.phase()==OnceBackend.Phase.COMPLETED&&result.failure()==OnceBackend.Failure.NONE
                    &&result.cleanup()==OnceBackend.Cleanup.CONFIRMED&&result.quiescence()==OnceBackend.Quiescence.CONFIRMED&&result.memory()==OnceBackend.Memory.WIPED){
                rootCleanReceipt=rootBinding.cleanReceipt();
                if(rootCleanReceipt!=null&&rootCleanReceipt.liveTransport()&&rootCleanReceipt.clean()&&recognizer.observation()!=null){
                    if(boundedIntent!=null){preparedObservation=recognizer.preparedRewardCardObservation(result.owner());boundedImageResult=recognizer.imageBufferAudit(result.owner());boundedMarkers=recognizer.rootMarkerDiagnostic(result.owner());}
                    else if(rootMode==RootCaptureScope.Mode.PREFLIGHT)readOnlyObservation=recognizer.takeReadOnlyObservation(result.owner());
                    else cardObservation=recognizer.nativeRewardCardObservation(result.owner());
                    completedReport="source=REAL_ROOT_NATIVE mode="+rootMode+" phase="+rootPhase;
                    completedDiagnostics=recognizer.perceptionDiagnostic(result.owner());
                    var markers=recognizer.rootMarkerDiagnostic(result.owner());if(markers!=null)completedDiagnostics+="\n"+markers.summary();
                }
            }
        }catch(RuntimeException failed){rootStage="REJECTED_OR_FAILED";}
        finally{closeOnOwner();}
    }
    private boolean rootResourcesClosed(OnceBackend.Result result){
        if(constructionUnknown||rootClaimUncertain||!ReviewBingNavigation.knownClean())return false;
        if(returnReadIntent!=null&&returnController!=null&&!returnController.resourcesKnown())return false;
        if(rootBinding==null)return true; // No root transport was created.
        if(result==null)return false;
        if(returnReadIntent!=null||boundedIntent!=null||seriesIntent!=null||RootFamilyDiagnosticProfile.ACTIVE){
            try{var audit=rootBinding.imageAudit();if(audit==null||!audit.isCleanupConfirmed())return false;}
            catch(RuntimeException unknown){return false;}
        }
        var life=rootBinding.source().cleanupSnapshot(result.owner());
        boolean backendClosed=result.cleanup()==OnceBackend.Cleanup.CONFIRMED&&result.quiescence()==OnceBackend.Quiescence.CONFIRMED&&result.memory()!=OnceBackend.Memory.RETAINED;
        return backendClosed&&life.ownerMatched()&&life.produceReturned()&&life.ownerQuiet()&&life.sourceReleased()&&life.sourceQuiet()
                &&life.childExitObserved()&&life.exitStatusObserved()&&life.clientPfdAllClosed();
    }
    static boolean rootPreflightPage(ReadOnlyTwoStageRun.Observation observation){
        return observation!=null&&observation.validNativeShape()
                &&(observation.frame.status()==LiveRewardCardObservation.Status.OBSERVED
                ||observation.frame.status()==LiveRewardCardObservation.Status.SECTION_COMPLETE_SKIP);
    }
    /** Live v27 preflight requires the exact two-card family, never a broad semantic HOLD or Quote. */
    static boolean rootFamilyPreflightPage(ReadOnlyTwoStageRun.Observation observation){
        return observation!=null&&observation.validNativeShape()
            &&observation.frame.status()==LiveRewardCardObservation.Status.OBSERVED
            &&observation.frame.route()==LiveRewardCardObservation.Route.MORE_VISIBLE_ITEM&&observation.frame.cards().size()==2;
    }
    /** A counter is not authority. Only the private live permit below can enter a production reader. */
    static final class SourceReadBudget {
        private final Object owner;private final RootCaptureScope.Phase phase;private final java.util.function.BooleanSupplier current;
        private boolean claimed,admitted,post;private int pre;
        SourceReadBudget(Object owner,RootCaptureScope.Phase phase,java.util.function.BooleanSupplier current){
            if(owner==null||phase==null||current==null)throw new IllegalArgumentException("source budget identity");
            this.owner=owner;this.phase=phase;this.current=current;
        }
        private boolean valid(Object exact,RootCaptureScope.Phase selected){
            try{return owner==exact&&phase==selected&&current.getAsBoolean();}catch(RuntimeException unknown){return false;}
        }
        synchronized boolean claim(Object exact,RootCaptureScope.Phase selected){if(claimed)return false;claimed=true;admitted=valid(exact,selected);return admitted;}
        synchronized boolean pre(Object exact,RootCaptureScope.Phase selected){if(!admitted||post||pre>=3)return false;pre++;return valid(exact,selected);}
        synchronized boolean post(Object exact,RootCaptureScope.Phase selected){if(!admitted||post)return false;post=true;return pre>0&&valid(exact,selected);}
    }
    /** Private construction after exact live grant + ROOT/EPOCH retain. Never reconstructed from scope names. */
    static final class RootSourceReadPermit {
        private final CaptureSessionRuntime run;private final RealCaptureClaim.Grant exactGrant;
        private final Object exactOwner;private final RootCaptureScope.Phase exactPhase;
        private final BingNavigationSnapshot.ReadOnlyGuard guard;private final SourceReadBudget budget;
        private RootSourceReadPermit(CaptureSessionRuntime run,BingNavigationSnapshot.ReadOnlyGuard guard){
            this.run=run;this.exactGrant=run.grant;this.exactOwner=run.requestOwner;this.exactPhase=run.rootPhase;this.guard=guard;
            if(guard==null||!guard.valid()||!current())throw new SecurityException("Live root source owner required");
            budget=new SourceReadBudget(exactOwner,exactPhase,this::current);
        }
        boolean current(){return run.grant==exactGrant&&run.requestOwner==exactOwner&&run.rootPhase==exactPhase
            &&RealCaptureClaim.live(exactGrant)&&run.rootEpochHeld&&run.rootAuthorityLive()&&run.rootCurrent();}
        BingNavigationSnapshot.ReadOnlyGuard claimReader(){
            if(!budget.claim(exactOwner,exactPhase))throw new SecurityException("Root source reader already spent");return guard;
        }
        boolean seriesDiagnosticAllowed(){return run.seriesIntent!=null&&run.rootPreflightBefore!=null&&current();}
        boolean readPre(){return budget.pre(exactOwner,exactPhase);}
        boolean readPost(){return budget.post(exactOwner,exactPhase)&&run.rootCleanReceipt!=null
            &&run.rootCleanReceipt.clean()&&run.rootCleanReceipt.liveTransport();}
    }
    private boolean rootPreflightFresh(){long now=SystemClock.elapsedRealtime();return rootCurrent()&&NativeProgressPublication.frameFresh(now,rootLower);}
    private boolean acceptRootSourcePostcheck(){
        if(rootPreflightMetadata==null||rootPreflightBefore==null||!rootPreflightFresh()||!activity.hiddenWithSoleTask())return false;
        if(rootMode==RootCaptureScope.Mode.PREFLIGHT&&(readOnlyCapture==null||readOnlyObservation==null
                ||readOnlyObservation.capture!=readOnlyCapture||readOnlyCapture.owner!=requestOwner))return false;
        if(rootMode==RootCaptureScope.Mode.BUSINESS&&(boundedIntent!=null?(preparedCapture==null||preparedObservation==null||!preparedObservation.matches(preparedCapture)):(cardCapture==null||cardObservation==null)))return false;
        if(rootTimedAfterAnchor!=null){var currentAnchor=autonomousRun==null?null:autonomousRun.timedAfterSource();
            if(currentAnchor==null||!rootTimedAfterAnchor.sameSource(currentAnchor))return false;}
        var after=rootTimedAfterAnchor==null?rootPreflightMetadata.readPost(new BingNavigationSnapshot.Shape(width,height,rootRotation)):rootPreflightMetadata.readPostTimedReturn(new BingNavigationSnapshot.Shape(width,height,rootRotation),rootTimedAfterAnchor);rootPostStatus=after.status().name();rootPostGate=rootPreflightMetadata.lastReadOnlyGate().name();
        return after.status()==BingNavigationSnapshot.Status.READY&&rootPreflightBefore.sameSource(after.ready())
                &&after.ready().fresh(SystemClock.elapsedRealtime())&&ReviewBingNavigation.knownClean()&&rootPreflightFresh()&&activity.hiddenWithSoleTask();
    }
    static boolean rootResourcesAfterPostcheck(boolean before,java.util.function.BooleanSupplier current){
        try{return before&&current.getAsBoolean();}catch(RuntimeException unknown){return false;}
    }
    /** No pixel/readiness shortcut: this decision consumes only this exact completed candidate. */
    static boolean explicitUnpresented(NativeRewardCardSession.PreparedObservation observation,
            MaaImageBufferAudit.Result audit,MaaRecognizer.RootMarkerDiagnostic markers){
        if(observation==null||!observation.validNativeShape()||observation.status()!=LiveRewardCardObservation.Status.HOLD
            ||observation.reason()!=LiveRewardCardObservation.Reason.MORE_HEADING_NOT_UNIQUE||observation.diagnostic()==null
            ||observation.diagnostic().headerCount()!=0||observation.nativeMoreCandidates()!=0||observation.nativeWrongPageCandidates()!=0||audit==null||markers==null||markers.nativeRows()<=0)return false;
        boolean moreSeen=false;
        for(var marker:markers.markers()){
            if(marker.marker()==MaaRecognizer.RootMarker.MORE){moreSeen=true;if(marker.candidates()!=0)return false;}
            if(switch(marker.marker()){case OWN_ROOT_TITLE,OWN_PREFLIGHT_PROMPT,PROJECTION_PROMPT,LOCK_SCREEN_PROMPT,REWARDS_ERROR->true;default->false;}
                &&marker.candidates()!=0)return false;
        }
        var cv=audit.sameFrameCv();var band=audit.bandStats();
        return moreSeen&&cv!=null&&cv.attempted()&&cv.status()==com.aliothmoon.maafw.rewards.RewardRegionLocator.Status.NO_CARDS
            &&cv.cardTotal()==0&&cv.fullFrameBoxes().isEmpty()&&!cv.truncated()
            &&band!=null&&band.status()==MaaImageBufferAudit.StatsStatus.OBSERVED&&band.count()>0
            &&band.populationVariance()==0.0&&band.flatCount()==band.count()&&band.min()==band.max();
    }
    static BoundedCompletion.Disposition boundedDisposition(NativeRewardCardSession.PreparedObservation observation,
            PngImageAudit.Evidence png,MaaImageBufferAudit.Result audit,MaaRecognizer.RootMarkerDiagnostic markers){
        if(observation==null||!observation.validNativeShape()||observation.nativeRowCount()<=0||observation.nativeNonemptyRows()<=0||observation.nativeWrongPageCandidates()!=0
            ||!completeSeriesAudits(png,audit)||!audit.positiveTaskBound()||!audit.javaScratchWiped()||!audit.imageDestroyed()||!audit.taskerDestroyed())return null;
        if(observation.route()!=LiveRewardCardObservation.Route.MORE_VISIBLE_ITEM)return null;
        if(observation.uniquePendingOnly()){
            if(observation.uniqueProofReady()&&(observation.status()==LiveRewardCardObservation.Status.OBSERVED
                    ||observation.status()==LiveRewardCardObservation.Status.SECTION_COMPLETE_SKIP))return BoundedCompletion.Disposition.POSITIVE;
            return explicitUnpresented(observation,audit,markers)?BoundedCompletion.Disposition.UNPRESENTED:null;
        }
        if(observation.selectedPendingOnly()){
            if(observation.status()==LiveRewardCardObservation.Status.OBSERVED&&observation.selectedProofReady())return BoundedCompletion.Disposition.POSITIVE;
            return explicitUnpresented(observation,audit,markers)?BoundedCompletion.Disposition.UNPRESENTED:null;
        }
        if(observation.status()==LiveRewardCardObservation.Status.OBSERVED||observation.status()==LiveRewardCardObservation.Status.SECTION_COMPLETE_SKIP)
            return BoundedCompletion.Disposition.POSITIVE;
        return explicitUnpresented(observation,audit,markers)?BoundedCompletion.Disposition.UNPRESENTED:null;
    }
    private void closeBoundedOnOwner(){
        if(closeStarted)return;closeStarted=true;
        OnceBackend.Result result=backend==null?null:backend.snapshot();
        boolean resources=rootResourcesClosed(result);String candidate=null;BoundedCompletion completion=null;
        if(!cancelled.get()&&resources&&completedReport!=null&&rootCleanReceipt!=null&&rootCleanReceipt.clean()){
            try{rootPreflightSourceConfirmed=acceptRootSourcePostcheck();}
            catch(RuntimeException failed){rootStage="SOURCE_POSTCHECK_FAILED";rootPreflightSourceConfirmed=false;}
            finally{resources=rootResourcesAfterPostcheck(resources,()->rootResourcesClosed(result));}
            if(rootPreflightSourceConfirmed&&resources&&rootCurrent()&&preparedCapture!=null&&preparedObservation!=null
                    &&preparedObservation.matches(preparedCapture)&&preparedCapture.requestOwner()==requestOwner){
                var disposition=boundedDisposition(preparedObservation,rootBinding.imageAudit(),boundedImageResult,boundedMarkers);
                if(disposition!=null){
                    completion=new BoundedCompletion(this,disposition);
                    try{
                        if(disposition==BoundedCompletion.Disposition.UNPRESENTED){
                            boundedRetired=autonomousRun.retireUnpresented(preparedCapture,preparedObservation,completion);
                            rootStage=boundedRetired?"HEALTHY_UNPRESENTED_RETIRED":"UNPRESENTED_REJECTED";
                        }else{
                            rootAcceptanceEntered=true;rootStage="ADOPT_SAME_FRAME";
                            candidate=autonomousRun.acceptPreparedAfterCleanup(preparedCapture,preparedObservation,completion,activity::hiddenWithSoleTask,cancelled::get);
                        }
                    }catch(RuntimeException|Error failed){autonomousRun.cancelPrepared(preparedCapture);rootStage="ADOPT_OR_RETIRE_FAILED";}
                    finally{resources=rootResourcesAfterPostcheck(resources,()->rootResourcesClosed(result)&&autonomousRun.resourcesKnownClosed());}
                }else rootStage="SEMANTIC_OR_AUDIT_STOP";
            }
        }
        if(!rootAcceptanceEntered&&!boundedRetired&&preparedCapture!=null)autonomousRun.cancelPrepared(preparedCapture);
        resources=rootResourcesAfterPostcheck(resources,autonomousRun::resourcesKnownClosed);
        released=resources;rootCandidate=candidate!=null&&released&&!cancelled.get()&&rootCurrent();reportFinal=true;
        if(released){if(rootEpochHeld){EPOCH.release(this);rootEpochHeld=false;}ROOT.releaseIfClosed(entry);}
        BoundedTransition transition=null;
        if(released&&!cancelled.get()&&rootCurrent()&&completion!=null){
            if(boundedRetired&&boundedAttempt<boundedIntent.maxPerPhase())transition=new BoundedTransition(this,false);
            else if(rootCandidate&&cardPhase==NativeRewardCardSession.Phase.BEFORE&&autonomousRun.returnReady()&&autonomousRun.needsAfter())transition=new BoundedTransition(this,true);
        }
        String details="profile="+(boundedIntent.uniquePending()?"UNIQUE_REFINED_V38":boundedIntent.selectedPending()?"SELECTED_PENDING_B_V35":"BOUNDED_BUSINESS_V31")+" phase="+cardPhase+" attempt="+boundedAttempt+" maxPerPhase=3 rootStage="+rootStage
            +" sourceLower="+rootLower+" nativeSequence="+(preparedCapture==null?0:preparedCapture.sequence())+" originalPhaseDeadline="+RealCaptureClaim.deadline(grant)
            +" parentDeadline="+boundedIntent.sessionEnd()+" sourcePrePostSame="+rootPreflightSourceConfirmed+" rootCleanup="+(released?"CONFIRMED":"UNKNOWN")
            +" nativePhase="+(result==null?"NOT_STARTED":result.phase())+" nativeFailure="+(result==null?"NONE":result.failure())
            +" nativeCleanup="+(result==null?"NOT_NEEDED":result.cleanup())+" nativeMemory="+(result==null?"NOT_ALLOCATED":result.memory())
            +" preAttempts="+rootReadinessAttempts+" preTransitions="+rootReadinessTransitions+" preStatus="+rootReadinessStatus+" preGate="+rootReadinessGate
            +" postStatus="+rootPostStatus+" postGate="+rootPostGate+" clientSfProbe=NOT_INVOKED unpresentedRetired="+boundedRetired;
        if(rootBinding!=null){details+="\n"+rootBinding.source().cleanupSnapshot(result==null?null:result.owner()).safeSummary();
            var png=rootBinding.imageAudit();if(png!=null)details+="\n"+seriesPngSummary(png);}
        if(boundedImageResult!=null)details+="\n"+boundedImageResult.summary();
        if(boundedMarkers!=null)details+="\n"+boundedMarkers.summary();
        if(preparedObservation!=null){details+="\nobservation="+preparedObservation.status()+" reason="+preparedObservation.reason();
            if(boundedIntent.uniquePending())details+="\n"+preparedObservation.uniqueEvidenceSummary();
            if(boundedIntent.selectedPending())details+="\n"+preparedObservation.selectedEvidenceSummary();
            if(preparedObservation.diagnostic()!=null)details+="\n"+preparedObservation.diagnostic().summary();}
        details+="\n"+autonomousRun.terminalSummary();
        activity.boundedFrameFinished(this,transition,rootCandidate?candidate:null,details);
        // Exact scalar proof survives; the completed native graph does not need to survive a known-clean phase.
        if(released){recognizer=null;rootBinding=null;backend=null;boundedImageResult=null;boundedMarkers=null;loop.quitSafely();}
    }
    static final class BoundedCompletion {
        enum Disposition { POSITIVE, UNPRESENTED }
        private final CaptureSessionRuntime runtime;
        private final RootBoundedBusinessIntent intent;
        private final NativeAutonomousCardRun business;
        private final NativeRewardCardSession.PreparedCapture capture;
        private final NativeRewardCardSession.PreparedObservation observation;
        private final Disposition disposition;
        private final ReviewShizukuPngBinding.CleanReceipt clean;
        private final RealCaptureClaim.ProcessEpoch epoch;
        private final AtomicBoolean spent=new AtomicBoolean();
        private volatile boolean consumedSuccessfully;
        private BoundedCompletion(CaptureSessionRuntime r,Disposition disposition){runtime=r;intent=r.boundedIntent;business=r.autonomousRun;capture=r.preparedCapture;observation=r.preparedObservation;this.disposition=disposition;clean=r.rootCleanReceipt;epoch=EPOCH;}
        private BoundedCompletion(RootBoundedBusinessIntent i,NativeAutonomousCardRun r,NativeRewardCardSession.PreparedCapture p,
                NativeRewardCardSession.PreparedObservation o,Disposition d,ReviewShizukuPngBinding.CleanReceipt c,RealCaptureClaim.ProcessEpoch e){runtime=null;intent=i;business=r;capture=p;observation=o;disposition=d;clean=c;epoch=e;}
        Disposition consume(NativeAutonomousCardRun r,NativeRewardCardSession.PreparedCapture p,NativeRewardCardSession.PreparedObservation o,long now){
            if(!spent.compareAndSet(false,true)||business!=r||capture!=p||observation!=o||!o.matches(p)||!intent.current(now)
                ||r.selectedPendingOnly()!=intent.selectedPending()||o.selectedPendingOnly()!=intent.selectedPending()
                ||r.uniquePendingOnly()!=intent.uniquePending()||o.uniquePendingOnly()!=intent.uniquePending()
                ||now<p.acquiredLowerBound()||now-p.acquiredLowerBound()>15_000||now>=p.phaseDeadline()||clean==null||!clean.clean())return null;
            if(runtime!=null){if(runtime.cancelled.get()||runtime.released||!runtime.rootEpochHeld||!runtime.rootCurrent()
                    ||!runtime.rootPreflightSourceConfirmed||runtime.rootCleanReceipt!=clean||!clean.liveTransport())return null;}
            else if(intent.live()||!r.fixtureOnly()||clean.liveTransport())return null;
            consumedSuccessfully=true;return disposition;
        }
        static BoundedCompletion fixture(RootBoundedBusinessIntent intent,NativeAutonomousCardRun run,NativeRewardCardSession.PreparedCapture capture,
                NativeRewardCardSession.PreparedObservation observation,ReviewShizukuPngBinding.Binding binding,MaaImageBufferAudit.Result audit,
                MaaRecognizer.RootMarkerDiagnostic markers,RealCaptureClaim.ProcessEpoch epoch,long now){
            var clean=binding==null?null:binding.cleanReceipt();
            var d=binding==null?null:boundedDisposition(observation,binding.imageAudit(),audit,markers);
            if(intent==null||intent.live()||run==null||!run.fixtureOnly()||!intent.matchesRun(run)||capture==null||observation==null
                ||!observation.matches(capture)||clean==null||clean.liveTransport()||!clean.clean()||d==null||epoch==null||!intent.current(now))throw new SecurityException("Actual nonlive completed candidate required");
            return new BoundedCompletion(intent,run,capture,observation,d,clean,epoch);
        }
    }
    /** Only after the exact candidate is closed. AFTER additionally requires actual committed native return history. */
    static final class BoundedTransition {
        private final CaptureSessionRuntime runtime;private final BoundedCompletion fixture;
        private final RootBoundedBusinessIntent intent;private final RealCaptureClaim.ProcessEpoch epoch;
        private final NativeRewardCardSession.Phase target;private final int attempt;private final boolean after;
        private final AtomicBoolean spent=new AtomicBoolean();
        private BoundedTransition(CaptureSessionRuntime run,boolean after){runtime=run;fixture=null;intent=run.boundedIntent;epoch=EPOCH;this.after=after;
            target=after?NativeRewardCardSession.Phase.AFTER:run.cardPhase;attempt=after?1:run.boundedAttempt+1;}
        private BoundedTransition(BoundedCompletion c,boolean after){runtime=null;fixture=c;intent=c.intent;epoch=c.epoch;this.after=after;
            target=after?NativeRewardCardSession.Phase.AFTER:c.capture.phase();attempt=after?1:c.capture.attempt()+1;}
        NativeRewardCardSession.Phase phase(){return target;}int attempt(){return attempt;}
        boolean consume(RootBoundedBusinessIntent exact,NativeRewardCardSession.Phase phase,int n,RealCaptureClaim.ProcessEpoch e,long now){
            if(!spent.compareAndSet(false,true)||intent!=exact||epoch!=e||phase!=target||n!=attempt||!intent.validIndex(n)||!intent.current(now))return false;
            if(runtime!=null)return runtime.released&&runtime.reportFinal&&!runtime.cancelled.get()&&runtime.rootCurrent()
                &&(after?runtime.boundedCommitted&&runtime.autonomousRun.returnReady():runtime.boundedRetired);
            return !intent.live()&&fixture!=null&&fixture.consumedSuccessfully&&fixture.clean.clean()&&!fixture.clean.liveTransport()
                &&fixture.business.current()&&fixture.business.resourcesKnownClosed()
                &&(after?fixture.business.returnReady():!fixture.business.candidateCurrent(fixture.capture));
        }
        static BoundedTransition fixture(BoundedCompletion completed,CaptureSessionRegistry registry,RealCaptureClaim.ProcessEpoch epoch,long now){
            if(completed==null||completed.runtime!=null||completed.intent.live()||!completed.consumedSuccessfully||registry==null||registry.retaining()
                ||completed.epoch!=epoch||epoch.retaining()||!completed.intent.current(now)||!completed.business.current()||now>=completed.capture.phaseDeadline()||!completed.business.resourcesKnownClosed())throw new SecurityException("Closed nonlive candidate required");
            boolean after=completed.disposition==BoundedCompletion.Disposition.POSITIVE;
            if(!after&&completed.capture.attempt()>=completed.intent.maxPerPhase())throw new SecurityException("No further candidate");
            if(after&&(!completed.business.returnReady()||!completed.business.needsAfter())||!after&&completed.business.candidateCurrent(completed.capture))throw new SecurityException("No typed next state");
            return new BoundedTransition(completed,after);
        }
    }
    private void closeRootOnOwner(){
        if(returnReadIntent!=null&&returnReadIntent.uiStructure()){closeUiStructureOnOwner();return;}
        if(returnReadIntent!=null){closeReturnReadOnOwner();return;}
        if(boundedIntent!=null){closeBoundedOnOwner();return;}
        if(closeStarted)return;closeStarted=true;
        OnceBackend.Result result=backend==null?null:backend.snapshot();
        boolean resources=rootResourcesClosed(result);String candidate=null;
        if(!cancelled.get()&&resources&&completedReport!=null&&rootCleanReceipt!=null&&rootCleanReceipt.clean()){
            try{rootPreflightSourceConfirmed=acceptRootSourcePostcheck();}
            catch(RuntimeException failed){rootStage="SOURCE_POSTCHECK_FAILED";rootPreflightSourceConfirmed=false;}
            finally{resources=rootResourcesAfterPostcheck(resources,()->rootResourcesClosed(result));}
            if(rootMode==RootCaptureScope.Mode.PREFLIGHT){
                rootPreflightValid=seriesIntent==null&&!RootFamilyDiagnosticProfile.ACTIVE&&rootPreflightSourceConfirmed&&rootFamilyPreflightPage(readOnlyObservation);
                if(rootPreflightValid){
                    candidate=completedReport+" knownRewardsPage="+rootPreflightValid+" actorConstructed=false taskInputs=0 observation="+readOnlyObservation.frame.status()+" observationReason="+readOnlyObservation.frame.reason();
                    var diagnostic=readOnlyObservation.frame.diagnostic();if(diagnostic!=null)candidate+="\n"+diagnostic.summary();
                }
            }else if(rootPreflightSourceConfirmed&&resources){
                rootAcceptanceEntered=true;
                try{candidate=autonomousRun.acceptAfterCleanup(cardCapture,cardObservation,true,activity::hiddenWithSoleTask,RealCaptureClaim.deadline(grant));}
                catch(RuntimeException failed){autonomousRun.cancel();}
                resources=resources&&candidate!=null&&autonomousRun.publicationCurrent(RealCaptureClaim.deadline(grant));
            }else if(autonomousRun!=null){autonomousRun.cancel();rootStage="SOURCE_POSTCHECK_REJECTED";}
        }
        if(rootAcceptanceEntered&&candidate==null)resources=false;
        released=resources;rootCandidate=seriesIntent==null&&!RootFamilyDiagnosticProfile.ACTIVE&&candidate!=null&&released&&!cancelled.get();reportFinal=true;
        if(released){if(rootEpochHeld){EPOCH.release(this);rootEpochHeld=false;}ROOT.releaseIfClosed(entry);}
        String details="profile="+(seriesIntent!=null?"ROOT_IMAGE_SERIES_V30":RootFamilyDiagnosticProfile.ACTIVE?"ROOT_SAME_FRAME_IMAGE_AUDIT_V28_INPUTS_0":"ROOT_V27")+" rootMode="+rootMode+" rootPhase="+rootPhase+" rootStage="+rootStage+" rootCleanup="+(released?"CONFIRMED":"UNKNOWN")
                +" nativePhase="+(result==null?"NOT_STARTED":result.phase())+" nativeFailure="+(result==null?"NONE":result.failure())
                +" nativeCleanup="+(result==null?"NOT_NEEDED":result.cleanup())+" nativeMemory="+(result==null?"NOT_ALLOCATED":result.memory());
        details+=" diagnosticCapture="+(readOnlyCapture==null?"NONE":readOnlyCapture.runId)+" sourceLower="+rootLower+" nativeSequence="+(readOnlyCapture!=null?readOnlyCapture.sequence:cardCapture!=null?cardCapture.sequence:0)+" sourcePrePostSame="+rootPreflightSourceConfirmed+" knownRewardsPage="+rootPreflightValid+" actorConstructed="+(autonomousRun!=null)+" sourcePrePostOnly=true preMetadataAttempts="+rootReadinessAttempts+" preTransitions="+rootReadinessTransitions+" preStatus="+rootReadinessStatus+" postStatus="+rootPostStatus+" preGate="+rootReadinessGate+" firstTransitionGate="+rootFirstTransitionGate+" postGate="+rootPostGate;
        if(rootBinding!=null)details+="\n"+rootBinding.source().cleanupSnapshot(result==null?null:result.owner()).safeSummary();
        if(seriesIntent!=null||RootFamilyDiagnosticProfile.ACTIVE)details+="\n"+rootImageAuditSummary(result);
        if(autonomousRun!=null)details+="\n"+autonomousRun.terminalSummary();
        if(seriesIntent==null&&!RootFamilyDiagnosticProfile.ACTIVE&&released&&rootMode==RootCaptureScope.Mode.PREFLIGHT&&readOnlyObservation!=null&&readOnlyObservation.frame.diagnostic()!=null)
            details+="\n"+readOnlyObservation.frame.diagnostic().summary();
        if(seriesIntent==null&&!RootFamilyDiagnosticProfile.ACTIVE&&released&&completedDiagnostics!=null)details+="\n"+completedDiagnostics;
        if((seriesIntent!=null||RootFamilyDiagnosticProfile.ACTIVE)&&readOnlyObservation!=null)details+="\nfamilyStatus="+readOnlyObservation.frame.status()
            +" familyReason="+readOnlyObservation.frame.reason()+" familyItems="+readOnlyObservation.frame.cards().size()+" semanticObservationOnly=true";
        if(seriesIntent!=null){
            seriesSampleEndMono=System.nanoTime();
            SeriesCompletion completion=null;
            if(released&&!cancelled.get()&&rootCurrent()&&rootPreflightSourceConfirmed&&completedReport!=null
                    &&readOnlyObservation!=null&&readOnlyObservation.capture==readOnlyCapture&&readOnlyCapture.owner==requestOwner
                    &&readOnlyObservation.validNativeShape()&&completeSeriesAudits(rootBinding==null?null:rootBinding.imageAudit(),seriesImageResult))
                completion=new SeriesCompletion(this);
            details="frame="+seriesIndex+" maxFrames=3 actors=0 pageCandidate=false businessPass=false oldContinuation=false\n"
                +seriesIntent.handoffSummary()+" sampleStartNs="+seriesSampleStartMono+" sampleEndNs="+seriesSampleEndMono
                +" captureAtomic=false probe=UNOBSERVED probeInvocation=NOT_INVOKED probeCleanup=NOT_NEEDED\n"+seriesMetadata+"\n"+details;
            activity.seriesFrameFinished(this,completion,details);
        }else activity.showRootFinalStatus(this,rootCandidate?candidate:null,details);
        readOnlyCapture=null;readOnlyObservation=null;cardCapture=null;cardObservation=null;
        if(released){if(seriesIntent!=null){recognizer=null;rootBinding=null;backend=null;}loop.quitSafely();}
    }
    /** Safe immutable audit results only. These getters do not read native buffers or issue another capture. */
    private String rootImageAuditSummary(OnceBackend.Result result){
        String source="pngImageAudit=NOT_OBSERVED",nativeImage="maaImageBufferAudit=NOT_OBSERVED";
        try{if(rootBinding!=null){var audit=rootBinding.imageAudit();if(audit!=null)source=returnReadIntent!=null||seriesIntent!=null?seriesPngSummary(audit):audit.safeSummary();}}
        catch(RuntimeException unavailable){source="pngImageAudit=UNKNOWN";}
        try{if(recognizer!=null&&result!=null){var audit=recognizer.imageBufferAudit(result.owner());if(audit!=null){nativeImage=returnReadIntent!=null&&returnReadIntent.layoutDiagnostic()?audit.compactSummary():audit.summary();if(returnReadIntent!=null||seriesIntent!=null)seriesImageResult=audit;}}}
        catch(RuntimeException unavailable){nativeImage="maaImageBufferAudit=UNKNOWN";}
        return "sameFrameAudit=true maxRootPng=1 maxMaaOcr=1 actors=0 businessPass=false continuation=false\n"+source+"\n"+nativeImage;
    }
    static boolean completeSeriesAudits(PngImageAudit.Evidence png,MaaImageBufferAudit.Result maa){
        return png!=null&&png.isCleanupConfirmed()&&png.status()==PngImageAudit.Status.MATCH
            &&maa!=null&&maa.cleanupQualified()&&maa.status()==MaaImageBufferAudit.Status.VERIFIED_EQUAL
            &&maa.queryPosts()==1&&maa.exactBindings()&&maa.queryQuiet()&&maa.fingerprint()!=null
            &&maa.allOcrGeometry().observed()&&maa.allOcrGeometry().nativeRows()>0;
    }
    static String seriesPngSummary(PngImageAudit.Evidence a){
        return "PngImageAudit status="+a.status()+" stage="+a.stage()+" cleanup="+a.cleanup()+" width="+a.width()+" height="+a.height()
            +" encodedBytes="+a.encodedBytes()+" bitDepth="+a.bitDepth()+" colorType="+a.colorType()+" firstReplayEqual="+a.firstReplayEqual()
            +" standardEqual="+(a.status()==PngImageAudit.Status.MATCH?"YES":a.status()==PngImageAudit.Status.DIFFERENCE?"NO":"NOT_OBSERVED")
            +" diffCount="+a.diffCount()+" maxDiff="+a.maxDiff()+" platformScratchZeroization=NOT_PROVEN";
    }
    /** Actual closed frame capability. It never grants an actor, page candidate, or old business continuation. */
    static final class SeriesCompletion {
        private final CaptureSessionRuntime run;
        private final RootImageSeriesIntent intent;
        private final int index;
        private final RealCaptureClaim.ProcessEpoch epoch;
        private final long deadline;
        private final ReviewShizukuPngBinding.CleanReceipt fixtureClean;
        private final AtomicBoolean spent=new AtomicBoolean();
        private SeriesCompletion(CaptureSessionRuntime run){this.run=run;intent=run.seriesIntent;index=run.seriesIndex;epoch=EPOCH;deadline=RealCaptureClaim.deadline(run.grant);fixtureClean=null;}
        private SeriesCompletion(RootImageSeriesIntent i,int index,RealCaptureClaim.ProcessEpoch e,long end,ReviewShizukuPngBinding.CleanReceipt clean){run=null;intent=i;this.index=index;epoch=e;deadline=end;fixtureClean=clean;}
        boolean consume(RootImageSeriesIntent exact,int previous,RealCaptureClaim.ProcessEpoch e,long now){
            if(!spent.compareAndSet(false,true)||intent!=exact||index!=previous||epoch!=e||now<0||now>=deadline||!intent.current(now))return false;
            return run!=null?run.released&&run.reportFinal&&!run.cancelled.get()&&run.rootCurrent()
                :!intent.live()&&fixtureClean!=null&&!fixtureClean.liveTransport()&&fixtureClean.clean();
        }
        /** Offline-only actual fixture source/native pipeline; cannot issue live authority. */
        static SeriesCompletion fixture(RootImageSeriesIntent intent,int index,RealCaptureClaim.ProcessEpoch epoch,Object requestOwner,long deadline,
                ReviewShizukuPngBinding.Binding binding,ReadOnlyTwoStageRun.Observation observation,MaaImageBufferAudit.Result audit,long now){
            var clean=binding==null?null:binding.cleanReceipt();
            if(intent==null||intent.live()||!RootImageSeriesScope.validIndex(index)||epoch==null||requestOwner==null
                ||clean==null||clean.liveTransport()||!clean.clean()||observation==null||!observation.validNativeShape()
                ||observation.capture.owner!=requestOwner||!intent.current(now)||deadline<=now||deadline>intent.sessionEnd()
                ||!completeSeriesAudits(binding.imageAudit(),audit))throw new SecurityException("Actual offline completed frame required");
            return new SeriesCompletion(intent,index,epoch,deadline,clean);
        }
    }
    boolean rootPublicationCurrent(){return rootCandidate&&released&&rootCurrent()&&rootCleanReceipt!=null&&rootCleanReceipt.clean()
            &&(rootMode==RootCaptureScope.Mode.PREFLIGHT?rootPreflightValid&&rootPreflightFresh():autonomousRun!=null&&autonomousRun.publicationCurrent(RealCaptureClaim.deadline(grant)));}
    RootPublication rootCommitted(){
        if(returnReadIntent!=null||boundedIntent!=null||seriesIntent!=null||RootFamilyDiagnosticProfile.ACTIVE)return null; // Historical scalar display only; no business pass or continuation.
        if(autonomousRun!=null&&autonomousRun.fixtureOnly())return null;
        if(!rootPublicationCurrent()||!activity.rootCommitMatches(this)||!rootPublicationIssued.compareAndSet(false,true))return null;
        rootPublished=true;return rootMode==RootCaptureScope.Mode.PREFLIGHT&&!rootPreflightValid?null:new RootPublication(this);
    }
    static String boundedRootScalars(String text,int limit){
        if(text==null)return "";if(text.length()<=limit)return text;
        String omitted="\n[bounded scalar detail omitted]\n";int first=(limit-omitted.length())*3/4;
        return text.substring(0,first)+omitted+text.substring(text.length()-(limit-omitted.length()-first));
    }
    static String rootReceiptText(String candidate,String details){return boundedRootScalars(candidate,5100)+"\n"+boundedRootScalars(details,2900);}
    /** Issued only after this exact runtime's actual native/source cleanup and main receipt commit. */
    static final class RootPublication {
        private final CaptureSessionRuntime run;private final AtomicBoolean spent=new AtomicBoolean();
        private final RootCaptureIntent fixtureIntent;private final RootCaptureScope.Phase fixturePhase;
        private final RealCaptureClaim.ProcessEpoch fixtureEpoch;private final Object fixtureRoot,fixtureRequest;
        private final long fixtureDeadline;private final ReviewShizukuPngBinding.CleanReceipt fixtureClean;
        private final ReadOnlyTwoStageRun.Observation fixtureObservation;private final NativeAutonomousCardRun fixtureBusiness;
        private RootPublication(CaptureSessionRuntime run){this.run=run;fixtureIntent=null;fixturePhase=null;fixtureEpoch=null;fixtureRoot=null;fixtureRequest=null;fixtureDeadline=-1;fixtureClean=null;fixtureObservation=null;fixtureBusiness=null;}
        private RootPublication(RootCaptureIntent intent,RootCaptureScope.Phase phase,RealCaptureClaim.ProcessEpoch epoch,
                Object root,Object request,long deadline,ReviewShizukuPngBinding.CleanReceipt clean,
                ReadOnlyTwoStageRun.Observation observation,NativeAutonomousCardRun business){
            run=null;fixtureIntent=intent;fixturePhase=phase;fixtureEpoch=epoch;fixtureRoot=root;fixtureRequest=request;fixtureDeadline=deadline;
            fixtureClean=clean;fixtureObservation=observation;fixtureBusiness=business;
        }
        /** Offline integration only: real fixture backend/native typed observation, never a live pass. */
        static RootPublication fixturePreflight(RootCaptureIntent intent,RootCaptureScope.Phase phase,RealCaptureClaim.ProcessEpoch epoch,
                Object root,Object request,long deadline,ReviewShizukuPngBinding.CleanReceipt clean,ReadOnlyTwoStageRun.Observation observation,long now){
            if(intent==null||intent.live()||intent.mode()!=RootCaptureScope.Mode.PREFLIGHT||phase!=RootCaptureScope.Phase.FIRST
                    ||clean==null||clean.liveTransport()||!clean.clean()||!rootPreflightPage(observation)||observation.capture.owner!=request
                    ||!intent.current(now)||now>=deadline||deadline>intent.sessionEnd())throw new SecurityException("Actual non-live preflight chain required");
            return new RootPublication(intent,phase,epoch,root,request,deadline,clean,observation,null);
        }
        static RootPublication fixtureBusiness(RootCaptureIntent intent,RootCaptureScope.Phase phase,RealCaptureClaim.ProcessEpoch epoch,
                Object root,Object request,long deadline,ReviewShizukuPngBinding.CleanReceipt clean,NativeAutonomousCardRun business,long now){
            if(intent==null||intent.live()||intent.mode()!=RootCaptureScope.Mode.BUSINESS||phase==null||business==null||!business.fixtureOnly()
                    ||clean==null||clean.liveTransport()||!clean.clean()||!business.publicationCurrent(deadline)
                    ||!intent.current(now)||now>=deadline||deadline>intent.sessionEnd())throw new SecurityException("Actual non-live business chain required");
            return new RootPublication(intent,phase,epoch,root,request,deadline,clean,null,business);
        }
        RootCaptureIntent intent(){return run==null?fixtureIntent:run.rootIntent;}
        RootCaptureScope.Phase phase(){return run==null?fixturePhase:run.rootPhase;}
        RealCaptureClaim.ProcessEpoch epoch(){return run==null?fixtureEpoch:EPOCH;}
        Object rootOwner(){return run==null?fixtureRoot:run.rootOwner;}Object requestOwner(){return run==null?fixtureRequest:run.requestOwner;}
        long phaseDeadline(){return run==null?fixtureDeadline:RealCaptureClaim.deadline(run.grant);}
        boolean returnReady(){NativeAutonomousCardRun business=run==null?fixtureBusiness:run.autonomousRun;
            return intent().mode()==RootCaptureScope.Mode.BUSINESS&&business!=null&&business.returnReady()&&business.needsAfter();}
        ReviewShizukuPngBinding.CleanReceipt cleanReceipt(){return run==null?fixtureClean:run.rootCleanReceipt;}
        boolean consume(RootCaptureIntent exact,long now){
            if(!spent.compareAndSet(false,true)||exact!=intent()||now<0)return false;
            if(run!=null)return run.rootPublished&&run.rootPublicationCurrent()&&run.activity.rootCommitMatches(run);
            return !fixtureIntent.live()&&!fixtureClean.liveTransport()&&fixtureClean.clean()&&fixtureIntent.current(now)&&now<fixtureDeadline
                    &&(fixtureBusiness!=null?fixtureBusiness.publicationCurrent(fixtureDeadline):rootPreflightPage(fixtureObservation));
        }
    }
    private CaptureSessionRuntime(DormantCaptureConfirmationActivity activity){this.activity=activity;}
    static CaptureSessionRuntime start(DormantCaptureConfirmationActivity activity,DormantCaptureConfirmationActivity.LiveClick click,BusinessCaptureScope.Purpose purpose) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        if(ReadOnlyCheckpoint.CURRENT!=null)throw new SecurityException("Single-frame diagnostic build");
        if(RootProgressScanScope.ACTIVE)throw new SecurityException("Bounded scan build");
        if(RootProgressReadScope.ACTIVE)throw new SecurityException("Progress read-only build");
        if(RootUniqueRefinedScope.ACTIVE)throw new SecurityException("Unique pending live entry only");
        return startInternal(activity,click,purpose,null,null);
    }
    private static CaptureSessionRuntime startInternal(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,BusinessCaptureScope.Purpose purpose,
            NativeCheckInSession nativeSession,NativeCheckInScope.Phase nativePhase) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        return startInternal(activity,click,purpose,nativeSession,nativePhase,false);
    }
    private static CaptureSessionRuntime startInternal(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,BusinessCaptureScope.Purpose purpose,
            NativeCheckInSession nativeSession,NativeCheckInScope.Phase nativePhase,boolean currentStatus) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        return startInternal(activity,click,purpose,nativeSession,nativePhase,currentStatus,null,null);
    }
    private static CaptureSessionRuntime startInternal(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,BusinessCaptureScope.Purpose purpose,
            NativeCheckInSession nativeSession,NativeCheckInScope.Phase nativePhase,boolean currentStatus,
            NativeRewardCardRun cardRun,NativeRewardCardSession.Phase cardPhase) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        return startInternal(activity,click,purpose,nativeSession,nativePhase,currentStatus,cardRun,cardPhase,null,null);
    }
    private static CaptureSessionRuntime startInternal(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,BusinessCaptureScope.Purpose purpose,
            NativeCheckInSession nativeSession,NativeCheckInScope.Phase nativePhase,boolean currentStatus,
            NativeRewardCardRun cardRun,NativeRewardCardSession.Phase cardPhase,ReadOnlyTwoStageRun readOnlyRun,ReadOnlyTwoStageRun.Phase readOnlyPhase) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        return startInternal(activity,click,purpose,nativeSession,nativePhase,currentStatus,cardRun,cardPhase,readOnlyRun,readOnlyPhase,null);
    }
    private static CaptureSessionRuntime startInternal(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,BusinessCaptureScope.Purpose purpose,
            NativeCheckInSession nativeSession,NativeCheckInScope.Phase nativePhase,boolean currentStatus,
            NativeRewardCardRun cardRun,NativeRewardCardSession.Phase cardPhase,ReadOnlyTwoStageRun readOnlyRun,ReadOnlyTwoStageRun.Phase readOnlyPhase,
            NativeAutonomousCardRun autonomousRun) {
        if(ReadOnlyCheckpoint.blocksLiveInput())throw new SecurityException("Read-only checkpoint build");
        if(RootProgressLocateScope.ACTIVE)throw new SecurityException("Locate-only live build");
        CaptureReleaseGate.requireConfigured();
        if(RootBoundedBusinessScope.ACTIVE)throw new SecurityException("Bounded live entry only");
        if(Looper.myLooper()!=Looper.getMainLooper() || android.os.Process.myUid()<10000
                || !Application.getProcessName().equals(activity.getPackageName()+":capture_once"))
            throw new SecurityException("Dedicated visible private capture process required");
        CaptureSessionRuntime run=new CaptureSessionRuntime(activity);
        if(autonomousRun!=null){AutonomousRewardCardScope.requireAdmissionCurrent();}else if(readOnlyRun!=null){if(!ReadOnlyTwoStageScope.admissionCurrent(System.currentTimeMillis()))throw new SecurityException("Read-only scope expired");}else if(cardRun!=null)NativeRewardCardScope.requireAdmissionCurrent();else if(currentStatus)CurrentCheckInStatus.requireAdmissionCurrent();else if(nativeSession==null)BusinessCaptureScope.requireCurrent();else NativeCheckInScope.requireAdmissionCurrent();
        if(autonomousRun!=null){autonomousRun.reserve(cardPhase);run.autonomousRun=autonomousRun;run.cardPhase=cardPhase;}else if(readOnlyRun!=null){readOnlyRun.reserve(readOnlyPhase);run.readOnlyRun=readOnlyRun;run.readOnlyPhase=readOnlyPhase;}else if(cardRun!=null){cardRun.reserve(cardPhase);run.cardRun=cardRun;run.cardPhase=cardPhase;}else if(currentStatus)run.currentStatus=true;else if(nativeSession==null)run.purpose=java.util.Objects.requireNonNull(purpose);
        else {nativeSession.reserve(nativePhase);run.nativeSession=nativeSession;run.nativePhase=nativePhase;}
        if(android.os.Build.VERSION.SDK_INT<34)throw new UnsupportedOperationException("API34 required");
        android.graphics.Rect bounds=activity.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        run.width=bounds.width();run.height=bounds.height();run.dpi=activity.getResources().getConfiguration().densityDpi;
        requireGeometry(run.width,run.height,run.dpi);
        if(autonomousRun==null&&readOnlyRun==null&&cardRun==null&&nativeSession==null&&!START_SPENT.compareAndSet(false,true))throw new SecurityException("Capture attempt spent for this process");
        long now=SystemClock.elapsedRealtime();
        VisibleCaptureAuthority.Proof proof=autonomousRun!=null?VisibleCaptureAuthority.issueAutonomous(activity,click,EPOCH,run.requestOwner,now,autonomousRun,cardPhase):readOnlyRun!=null?VisibleCaptureAuthority.issueReadOnly(activity,click,EPOCH,run.requestOwner,now,readOnlyRun):cardRun!=null?VisibleCaptureAuthority.issueCard(activity,click,EPOCH,run.requestOwner,now):currentStatus?VisibleCaptureAuthority.issueCurrentStatus(activity,click,EPOCH,run.requestOwner,now):nativeSession==null
            ?VisibleCaptureAuthority.issue(activity,click,EPOCH,run.requestOwner,now)
            :VisibleCaptureAuthority.issueNative(activity,click,EPOCH,run.requestOwner,now);
        run.grant=RealCaptureClaim.visible(proof,EPOCH,run.requestOwner,now);
        // Root before worker creation, posting, grant burn, consent or acquisition.
        run.entry=ROOT.retain(run,EPOCH,RealCaptureClaim.deadline(run.grant),run::latchCancel,run::closeOnOwner,
                ()->run.released,task->run.owner!=null && run.owner.post(task));
        try {
            run.loop.start();run.owner=new Handler(run.loop.getLooper());
            ROOT.arm(run.entry,(deadline,task)->DEADLINES.schedule(task,Math.max(0,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS),SystemClock::elapsedRealtime);
            if(!run.owner.post(run::beginOnOwner))throw new IllegalStateException("Owner queue unavailable");
        } catch(RuntimeException failure){run.constructionUnknown=true;run.cancel();}
        return run;
    }
    static void requireGeometry(int width,int height,int dpi) {
        if(width<=0||height<=0||width>4096||height>4096||(long)width*height>4L*1024*1024||dpi<=0||dpi>2000)
            throw new IllegalArgumentException("Display exceeds bounded one-frame memory budget");
    }
    private void beginOnOwner() {
        if(cancelled.get()){closeOnOwner();return;}
        try {
            ports=new AndroidCaptureAdmissionPorts(activity,owner,this::acquiredOnOwner,this::closeOnOwner,
                    cardPhase==NativeRewardCardSession.Phase.AFTER||nativePhase==NativeCheckInScope.Phase.AFTER||readOnlyPhase==ReadOnlyTwoStageRun.Phase.SECOND
                            ?ForegroundPhaseSlots.Phase.AFTER:ForegroundPhaseSlots.Phase.BEFORE);
            coordinator=CaptureAdmissionCoordinator.live(EPOCH,requestOwner,grant,
                    autonomousRun!=null?autonomousRun.store(cardPhase):readOnlyRun!=null?readOnlyRun.store(readOnlyPhase):cardRun!=null?cardRun.store(cardPhase):currentStatus?CurrentCheckInStatus.store(activity.getNoBackupFilesDir().toPath()):nativeSession==null?BusinessCaptureScope.store(activity.getNoBackupFilesDir().toPath(),purpose):nativeSession.store(nativePhase),SystemClock::elapsedRealtime,ports);
            ports.bind(coordinator);
            if(cancelled.get())coordinator.cancel();
            if(coordinator.begin()==null)closeOnOwner();
        } catch(RuntimeException failure){constructionUnknown=true;closeOnOwner();}
    }
    private void acquiredOnOwner() {
        // Filled by the ownership bridge. This hook is strictly the original owner
        // loop, after actual FGS-ready and getMediaProjection return.
        try {
            if(cancelled.get() || !activity.hiddenWithSoleTask()){latchCancel();return;}
            activity.showStatus("系统同意与前台服务已就绪。正在仅处理一帧；停止请求不是清理证明。",false);
            if(readOnlyRun!=null){
                readOnlyStage="SOURCE_PRECHECK";readOnlyCapture=readOnlyRun.startCapture(readOnlyPhase,width,height);
                if(readOnlyCapture==null)throw new SecurityException("Read-only source unavailable");
                readOnlySourceLower=readOnlyCapture.lower;
            }
            if(autonomousRun!=null){
                cardCapture=autonomousRun.startCapture(cardPhase,width,height);
                if(cardCapture==null)throw new SecurityException("Autonomous source unavailable");
            }
            if(cardRun!=null){
                cardCapture=cardRun.startCapture(cardPhase,width,height);
                if(cardCapture==null)throw new SecurityException("Card foreground scope unavailable");
            }
            if(nativeSession!=null){
                nativeCapture=nativeSession.startCapture(nativePhase,width,height);
                if(nativeCapture==null)throw new SecurityException("Native foreground scope unavailable");
            }
            if(currentStatus){
                if(!currentDisplayMatches())throw new SecurityException("Current-status display mismatch");
                currentForeground=ReviewBingForeground.read(activity);
                if(currentForeground==null||!ReviewBingForeground.knownClean())throw new SecurityException("Current-status foreground unknown");
            }
            readOnlyStage="RECOGNIZER_CREATE";
            recognizer=readOnlyCapture!=null?MaaRecognizer.readOnlyQuote(activity.getApplicationContext(),readOnlyCapture):cardCapture!=null?MaaRecognizer.liveRewardCard(activity.getApplicationContext(),cardCapture):currentStatus?MaaRecognizer.currentCheckInStatus(activity.getApplicationContext()):nativeCapture==null?MaaRecognizer.liveCheckIn(activity.getApplicationContext())
                :MaaRecognizer.liveCheckIn(activity.getApplicationContext(),nativeCapture);
            readOnlyStage="BACKEND_BIND";
            backend=ScreenProducerProvider.bindReal(coordinator,recognizer,Runnable::run,
                    new Handler(Looper.getMainLooper()),width,height,dpi);
            if(cancelled.get())coordinator.cancel();
            readOnlyStage="BACKEND_START";
            OnceBackend.Result result=backend.start();readOnlyStage="BACKEND_RETURNED";
            RewardsObservation observation=recognizer.observation();
            if(!cancelled.get() && result.cleanup()==OnceBackend.Cleanup.CONFIRMED
                    && result.quiescence()==OnceBackend.Quiescence.CONFIRMED && result.memory()!=OnceBackend.Memory.RETAINED){
                if(readOnlyRun!=null)readOnlyObservation=recognizer.takeReadOnlyObservation(result.owner());
                if(autonomousRun!=null||cardRun!=null)cardObservation=recognizer.nativeRewardCardObservation(result.owner());
                if(currentStatus)currentObservation=recognizer.currentCheckInObservation(result.owner());
                if(nativeSession!=null)nativeObservation=recognizer.nativeCheckInObservation(result.owner());
                completedDiagnostics="Capture purpose="+(autonomousRun!=null?"AUTONOMOUS_CARD_"+cardPhase:readOnlyRun!=null?"READ_ONLY_"+readOnlyPhase:cardRun!=null?"REWARD_CARD_"+cardPhase:currentStatus?"CURRENT_STATUS":nativeSession==null?purpose:nativePhase)+" expiresUtc="+(autonomousRun!=null?AutonomousRewardCardScope.EXPIRES_UTC:readOnlyRun!=null?ReadOnlyTwoStageScope.EXPIRES_UTC:cardRun!=null?NativeRewardCardScope.EXPIRES_UTC:currentStatus?CurrentCheckInStatus.EXPIRES_UTC:nativeSession==null?BusinessCaptureScope.EXPIRES_UTC:NativeCheckInScope.EXPIRES_UTC)+"\n"+recognizer.perceptionDiagnostic(result.owner())+(!currentStatus&&nativeSession==null&&cardRun==null&&readOnlyRun==null&&autonomousRun==null?"\n"+recognizer.liveCheckInSummary(result.owner()):"");
                BackendPorts.SourceTiming timing=backend.sourceTiming(result.owner());
                if(completedDiagnostics!=null&&timing!=null)completedDiagnostics+="\n"+timing.summary();
            }
            if(!cancelled.get() && result.phase()==OnceBackend.Phase.COMPLETED
                    && result.cleanup()==OnceBackend.Cleanup.CONFIRMED
                    && result.quiescence()==OnceBackend.Quiescence.CONFIRMED
                    && result.memory()!=OnceBackend.Memory.RETAINED && observation!=null) {
                completedReport=autonomousRun!=null?"Source: REAL_NATIVE_AUTONOMOUS_CARD":readOnlyRun!=null?"Source: REAL_NATIVE_READ_ONLY_TWO_STAGE":cardRun!=null?"Source: REAL_NATIVE_REWARD_CARD_SNAPSHOT":currentStatus?"Source: REAL_NATIVE_CURRENT_STATE_READ_ONLY":nativeSession!=null?"Source: REAL_NATIVE_CHECKIN_SNAPSHOT\n新鲜原生视觉与Bing窗口身份快照；不保证所有像素连续不变。":("Source: REAL_SCREEN_UNBOUND\n"+TargetPageReadiness.report(observation,activity.hiddenWithSoleTask(),recognizer.structure(result.owner()))
                    +"\n"+activity.installedSummary()+"\n"+FramePageEvidence.summary(recognizer.pageEvidence(result.owner())));
            }
        } catch(RuntimeException failure) { /* Keep ownership; close below decides receipt. */ }
        finally {closeOnOwner();}
    }
    private boolean currentDisplayMatches(){
        if(android.os.Build.VERSION.SDK_INT<34)return false;
        var manager=activity.getSystemService(android.hardware.display.DisplayManager.class);
        var display=manager==null?null:manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=android.view.Display.STATE_ON)return false;
        var windows=activity.createDisplayContext(display).getSystemService(android.view.WindowManager.class);
        if(windows==null)return false;
        var bounds=windows.getMaximumWindowMetrics().getBounds();
        return bounds.left==0&&bounds.top==0&&CurrentCheckInStatus.geometryMatches(bounds.width(),bounds.height(),display.getRotation());
    }
    private void latchCancel() {
        if((returnReadIntent!=null||seriesIntent!=null||boundedIntent!=null)&&reportFinal&&released)return;
        // A phase-local timeout never revokes the shared series parent. The exact Activity owns parent cancellation.
        if(rootIntent!=null)rootIntent.cancel();
        if(autonomousRun!=null&&boundedIntent==null)autonomousRun.cancel();
        if(boundedIntent!=null&&preparedCapture!=null)autonomousRun.cancelPrepared(preparedCapture);
        if(readOnlyRun!=null)readOnlyRun.cancel();
        if(cardRun!=null)cardRun.cancel();
        if(nativeSession!=null)nativeSession.cancel();
        cancelCaptureOnly();
    }
    private void cancelCaptureOnly() {
        cancelled.set(true);
        if(rootBinding!=null)rootBinding.source().requestStop();
        activity.clearPendingHandoff(); // Scalar invalidation only, no UI/OS calls.
        CaptureAdmissionCoordinator<?> c=coordinator;if(c!=null)c.cancel();
        OnceBackend b=backend;if(b!=null)b.cancel(b.snapshot().owner());
    }
    void cancel(){
        if((returnReadIntent!=null||seriesIntent!=null||boundedIntent!=null)&&reportFinal&&released)return;
        // A phase-local timeout never revokes the shared series parent. The exact Activity owns parent cancellation.
        if(rootIntent!=null)rootIntent.cancel();
        if(autonomousRun!=null&&boundedIntent==null)autonomousRun.cancel();
        if(boundedIntent!=null&&preparedCapture!=null)autonomousRun.cancelPrepared(preparedCapture);
        if(readOnlyRun!=null)readOnlyRun.cancel();
        if(cardRun!=null)cardRun.cancel();
        if(nativeSession!=null)nativeSession.cancel();
        if(returnReadIntent==null&&boundedIntent==null&&seriesIntent==null&&!reportFinal)activity.showStatus("已请求取消或授权窗口到期。仍等待原操作返回；未证明清理完成，不能重试。",false);
        ROOT.request(entry);
    }
    void onActivityResult(int code,int result,Intent data) {
        CaptureReleaseGate.requireConfigured();
        AndroidCaptureAdmissionPorts p=ports;if(p!=null)p.onActivityResult(code,result,data);
    }
    private void closeOnOwner() {
        if(isRoot()){closeRootOnOwner();return;}
        if(closeStarted)return;closeStarted=true;
        activity.showStatus("正在核验本次清理回执；尚不能证明资源已释放。",false);
        // Snapshot successful scalars before normal teardown revokes the admission.
        // A user/deadline cancellation that won earlier suppresses the candidate.
        String candidate=cancelled.get()?null:completedReport;
        String diagnostics=cancelled.get()?null:completedDiagnostics;
        reportFinal=true;
        cancelCaptureOnly();
        CaptureAdmissionCoordinator<?> c=coordinator;
        if(c!=null)c.close();
        OnceBackend b=backend;
        OnceBackend.Result result=b==null?null:b.snapshot();
        boolean backendClosed=result==null || result.cleanup()==OnceBackend.Cleanup.NOT_NEEDED
                || result.cleanup()==OnceBackend.Cleanup.CONFIRMED && result.quiescence()==OnceBackend.Quiescence.CONFIRMED
                && result.memory()!=OnceBackend.Memory.RETAINED;
        released=!constructionUnknown && backendClosed && (c==null || c.state()==CaptureAdmissionCoordinator.State.CLOSED);
        ROOT.releaseIfClosed(entry);
        if(currentStatus){
            boolean currentCandidateEligible=candidate!=null;
            candidate=null;
            if(currentCandidateEligible&&released&&currentObservation!=null&&currentForeground!=null
                    &&CurrentCheckInStatus.current(System.currentTimeMillis())&&activity.hiddenWithSoleTask()){
                var finalForeground=ReviewBingForeground.read(activity);
                if(currentForeground.equals(finalForeground)&&ReviewBingForeground.knownClean()&&currentDisplayMatches()
                        &&activity.hiddenWithSoleTask()&&SystemClock.elapsedRealtime()<RealCaptureClaim.deadline(grant)
                        &&CurrentCheckInStatus.current(System.currentTimeMillis())){
                    candidate=currentObservation.summary()+"\n"+(currentObservation.state()==CurrentCheckInStatus.State.CURRENT_CHECKIN_SUCCESS_MODAL
                        ?"当前原生帧显示签到成功弹层，未选择提醒或关闭，不推断点击因果或积分到账。":currentObservation.state()==CurrentCheckInStatus.State.CURRENT_ITEM_SHOWS_CHECK
                        ?"当前帧中，参考位置的签到项显示唯一可靠勾选。":"当前帧不足以确认参考位置已完成，保持HOLD。")
                        +" 这是跨run当前状态观察，不是同会话前后比较；不推断积分或点击因果，本次输入为0。";
                }
            }
        }
        String readOnlySafety="";
        if(readOnlyRun!=null){
            var life=ports==null?null:ports.foregroundSnapshot();
            boolean eligible=candidate!=null;
            candidate=readOnlyRun.accept(readOnlyCapture,readOnlyObservation,released&&eligible,activity::hiddenWithSoleTask,life,ports!=null&&ports.projectionReturned(),ports==null?0:ports.consentRequestCode(),RealCaptureClaim.deadline(grant));
            readOnlySafety="\nReadOnlyStage="+readOnlyStage+" admission="+(c==null?"NONE":c.state())+" backendCreated="+(backend!=null)+" ownedCapture="+(readOnlyCapture!=null)+" nativeObservation="+(readOnlyObservation!=null)+" "+(life==null?"fgs=NONE":life.summary())+" projectionReturned="+(ports!=null&&ports.projectionReturned())+" actions=0";
            readOnlyCapture=null;readOnlyObservation=null;
        }
        if(autonomousRun!=null){
            var life=ports==null?null:ports.foregroundSnapshot();
            boolean eligible=candidate!=null&&released&&life!=null&&life.confirmedDestruction()
                &&life.phase()==(cardPhase==NativeRewardCardSession.Phase.AFTER?ForegroundPhaseSlots.Phase.AFTER:ForegroundPhaseSlots.Phase.BEFORE)
                &&ports.projectionReturned()&&ports.consentRequestCode()>0;
            String resultText=null;
            try {resultText=autonomousRun.acceptAfterCleanup(cardCapture,cardObservation,eligible,activity::hiddenWithSoleTask,RealCaptureClaim.deadline(grant));}
            catch(RuntimeException rejected){autonomousRun.cancel();}
            candidate=eligible?resultText:null;
            readOnlySafety="\nAutonomousStage="+cardPhase+" admission="+(c==null?"NONE":c.state())+" ownedCapture="+(cardCapture!=null)+" nativeObservation="+(cardObservation!=null)
                +" "+(life==null?"fgs=NONE":life.summary())+" projectionReturned="+(ports!=null&&ports.projectionReturned())+" consentRequestCode="+(ports==null?0:ports.consentRequestCode())
                +" "+autonomousRun.terminalSummary();
            cardCapture=null;cardObservation=null;
        }
        if(cardRun!=null){
            boolean cardCandidateEligible=candidate!=null;
            String cardResult=cardRun.acceptAfterCleanup(cardCapture,cardObservation,
                released&&cardCandidateEligible,activity::hiddenWithSoleTask);
            if(released&&cardCandidateEligible)candidate=candidate+"\n"+cardResult;
        }
        if(nativeSession!=null){
            boolean nativeCandidateEligible=candidate!=null;
            String nativeResult=nativeSession.acceptAfterCleanup(nativeCapture,nativeObservation,
                released&&nativeCandidateEligible,activity::hiddenWithSoleTask);
            if(released&&nativeCandidateEligible)candidate=candidate+"\n"+nativeResult;
        }
        String details=result==null?"未处理可用帧":("Phase: "+result.phase()+" · Failure: "+result.failure()
            +" · Cleanup: "+result.cleanup()+" · Memory: "+result.memory());
        // Diagnostics use the same final main-thread visibility fence as page
        // candidates. Never append them to unconditionally displayed details.
        if(released&&diagnostics!=null&&((readOnlyRun==null&&autonomousRun==null)||candidate!=null))candidate=(candidate==null
                ?"Source: REAL_SCREEN_UNBOUND\nNo page result; bounded diagnostics only."
                :candidate)+"\n"+diagnostics;
        activity.showFinalStatus(released?candidate:null,details+readOnlySafety+"\n"
            +(released?(nativeSession==null&&cardRun==null&&readOnlyRun==null&&autonomousRun==null?"应用拥有资源的清理已确认；不证明系统或驱动零留存。本进程无重试。":readOnlyRun!=null?"本次只读资源清理已确认；没有构造或执行输入actor。":"本次采集资源清理已确认；触摸结果见独立actor回执，不等同任务完成。"):
                "清理 UNKNOWN。保留本进程资源根和已启动前台服务，不能重新开始；关闭页面不解除隔离。"));
        if(released)loop.quitSafely(); // queue lifetime, never a resource completion proof
    }
}
