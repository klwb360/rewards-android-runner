package com.aliothmoon.maafw.once;

import android.app.Application;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit single-run owner for native local perception and typed live actions. */
final class LiveRewardsRuntime {
    static final class ReadingApproval {
        final ReadingNewsChoice.Mode mode;
        private ReadingApproval(ReadingNewsChoice.Mode mode){this.mode=mode;}
    }
    static final String PROFILE="LIVE_REWARDS_CYCLE_V1",DIRECTORY="rewards-live-cycle-v1";
    private final DormantCaptureConfirmationActivity activity;
    private final VisibleCaptureAuthority.LiveRewardsApproval approval;
    private final BingNavigationSnapshot.Shape shape;
    private final String runId=UUID.randomUUID().toString();
    private final HandlerThread loop=new HandlerThread("live-rewards-owner");
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean canceled=new AtomicBoolean(),workerStarted=new AtomicBoolean(),appendActive=new AtomicBoolean();
    private final List<JSONObject> rows=new ArrayList<>(),files=new ArrayList<>(),actions=new ArrayList<>();
    private Handler owner;
    private CaptureSessionRuntime.AlignmentRoot root;
    private LiveRunAuthority authority;
    private LiveRewardsLedger ledger;
    private LiveRewardsCoordinator coordinator;
    private LiveRewardsPerception perception;
    private LiveRewardsNavigation navigation;
    private LiveRewardsInput input;
    private LiveRewardsForegroundLease foreground;
    private final String runDay;
    private final boolean taskInputAllowed,singleTask;
    private final LiveRewardsTaskSelection selection;
    private final LiveRewardsTaskSelection.Workflow workflow;
    private final LiveMoreUiRecheck uiRecheck;
    private final ReadingNewsChoice.Mode newsMode;
    private NewsAndroidPort news;
    private AlphabetSearchCycle search;
    private AlphabetSearchDevice searchDevice;
    private String workflowStep="PENDING",workflowReason="NONE";
    private LiveRewardsCoordinator.Summary summary;
    private LiveRewardsTypes.Preflight readOnlyPreflight;
    private boolean readOnlyPreflightStarted,readOnlyPreflightReturned;
    private Path artifacts;
    private Object artifactKey;
    private volatile String stage="NEW",failure="NONE";
    private volatile boolean workerReturned,closed,released,constructionUnknown,reportPublished,releaseReceiptPublished;
    private boolean notified;
    private int tapCalls,tapSuccesses,returnCalls,returnSuccesses,markedImages,cropImages,tapDispatches,backDispatches,backSuccesses;
    private boolean inputUnknown;
    private long startedElapsed,finishedElapsed=-1,releasedElapsed=-1;
    private String reportSha256;
    private boolean foregroundStopWaiting,reportAttempted;
    private String terminalWorkStage;

    private LiveRewardsRuntime(DormantCaptureConfirmationActivity activity,
            VisibleCaptureAuthority.LiveRewardsApproval approval,BingNavigationSnapshot.Shape shape,String runDay,
            LiveRewardsTaskSelection.Plan plan,boolean moreUiRecheck,ReadingNewsChoice.Mode newsMode){
        this.activity=activity;this.approval=approval;this.shape=shape;this.runDay=runDay;taskInputAllowed=approval.taskInputAllowed;
        singleTask=approval.singleTask;
        selection=plan.tasks();
        workflow=plan.workflow();
        if(workflow!=LiveRewardsTaskSelection.Workflow.BASIC
                &&(!taskInputAllowed||singleTask||selection.limited()||moreUiRecheck||newsMode!=null))
            throw new SecurityException("Dedicated visible workflow required");
        if(moreUiRecheck&&taskInputAllowed)throw new SecurityException("More UI recheck requires read-only approval");
        uiRecheck=moreUiRecheck?new LiveMoreUiRecheck():null;
        this.newsMode=newsMode;
        if(newsMode!=null&&(singleTask||selection.limited()||moreUiRecheck
                ||newsMode.live!=taskInputAllowed))
            throw new SecurityException("Dedicated news confirmation required");
    }
    static LiveRewardsRuntime start(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,Object approvalOwner){
        return startBound(activity,click,approvalOwner,true);
    }
    static LiveRewardsRuntime startReadOnly(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,Object approvalOwner){
        return startBound(activity,click,approvalOwner,false);
    }
    private static LiveRewardsRuntime startBound(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,Object approvalOwner,boolean requestedTaskInputAllowed){
        CaptureReleaseGate.requireConfigured();
        if(activity==null||click==null||approvalOwner==null||Looper.myLooper()!=Looper.getMainLooper()
                ||android.os.Build.VERSION.SDK_INT<34||android.os.Process.myUid()<10000
                ||!"com.aliothmoon.maafw.productrewardsworking.capturereview".equals(activity.getPackageName())
                ||!(activity.getPackageName()+":capture_once").equals(Application.getProcessName()))
            throw new SecurityException("Explicit live run process required");
        var epoch=CaptureSessionRuntime.alignmentEpoch();long now=SystemClock.elapsedRealtime();
        String approvedDay=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        var approval=requestedTaskInputAllowed?VisibleCaptureAuthority.issueLiveRewardsApproval(activity,click,epoch,approvalOwner,now)
                :VisibleCaptureAuthority.issueReadOnlyRewardsApproval(activity,click,epoch,approvalOwner,now);
        if(approval.taskInputAllowed!=requestedTaskInputAllowed)throw new SecurityException("Exact visible run purpose required");
        var plan=requestedTaskInputAllowed&&!approval.singleTask
                ? LiveRewardsUi.takePlan(activity)
                : new LiveRewardsTaskSelection.Plan(LiveRewardsTaskSelection.ALL,LiveRewardsTaskSelection.Workflow.BASIC);
        var selection=plan.tasks();
        boolean moreUiRecheck=!requestedTaskInputAllowed&&LiveRewardsUi.takeMoreUiRecheck(activity);
        var newsMode=LiveRewardsUi.takeNewsMode(activity,requestedTaskInputAllowed,approval.singleTask||selection.limited()||moreUiRecheck);
        if(!selection.current(approvedDay))throw new SecurityException("Task selection day changed");
        var manager=activity.getSystemService(DisplayManager.class);var display=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=Display.STATE_ON||display.getRotation()!=Surface.ROTATION_0)
            throw new SecurityException("Reviewed display required");
        var wm=activity.createDisplayContext(display).getSystemService(WindowManager.class);
        if(wm==null)throw new SecurityException("Display bounds unavailable");
        var bounds=wm.getMaximumWindowMetrics().getBounds();
        if(bounds.left!=0||bounds.top!=0||bounds.width()!=1440||bounds.height()!=2560)
            throw new SecurityException("Reviewed portrait shape required");
        var run=new LiveRewardsRuntime(activity,approval,new BingNavigationSnapshot.Shape(bounds.width(),bounds.height(),0),approvedDay,plan,moreUiRecheck,newsMode);
        run.root=CaptureSessionRuntime.retainAlignment(run,approval.deadline,run::latchCancel,run::closeOnOwner,()->run.closed,run::postOwner);
        try{
            run.loop.start();run.owner=new Handler(run.loop.getLooper());run.root.arm();run.startedElapsed=approval.issued;
            run.stage="FOREGROUND_STARTING";
            run.foreground=LiveRewardsForegroundService.reserve(run,run.runId,approval.issued,approval.deadline,run.runDay,
                    run::foregroundReady,run::cancel,()->run.postOwner(run::closeOnOwner));
            LiveRewardsForegroundService.requestStart(activity,run.foreground);
            long wait=Math.max(0,run.foreground.startupDeadline-SystemClock.elapsedRealtime());
            if(!run.main.postDelayed(run::checkForegroundStartup,wait))run.cancel();
        }catch(RuntimeException unknown){run.failure="FOREGROUND_START_REJECTED";run.stage="STOPPED";run.cancel();}
        return run;
    }
    private boolean bootstrapCurrent(){return !constructionUnknown&&!workerReturned&&!canceled.get()&&root!=null&&root.current()
            &&SystemClock.elapsedRealtime()<approval.deadline&&runDay.equals(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());}
    private void foregroundReady(){
        try{
            if(Looper.myLooper()!=Looper.getMainLooper()||!bootstrapCurrent()||foreground==null||!foreground.ready())
                throw new SecurityException("Current foreground acknowledgement required");
            authority=new LiveRunAuthority(approval,new LiveRunAuthority.Clock(){
                public long elapsedRealtime(){return SystemClock.elapsedRealtime();}
                public String day(){return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();}
            },this::sessionCurrent);
            stage="HANDOFF";handoff();
        }catch(RuntimeException rejected){failure="FOREGROUND_READY_REJECTED";cancel();}
    }
    private void checkForegroundStartup(){
        var exact=foreground;if(exact==null||exact.everReady()||exact.destroyed())return;
        exact.checkDeadline();if(!exact.everReady()){failure="FOREGROUND_START_TIMEOUT";cancel();}
    }
    private boolean postOwner(Runnable action){
        try{if(owner!=null&&owner.post(action))return true;}catch(RuntimeException unavailable){/* Retain graph. */}
        constructionUnknown=true;latchCancel();return false;
    }
    private boolean sessionCurrent(){return bootstrapCurrent()&&foreground!=null&&foreground.ready();}
    private boolean hiddenCurrent(){return sessionCurrent()&&authority!=null&&authority.current()
            &&activity.rootHiddenScalar()&&activity.hiddenWithSoleTask();}
    private void requireHidden(){if(!hiddenCurrent())throw new SecurityException("Live run hidden owner unavailable");}
    private void handoff(){
        try{
            if(!sessionCurrent()||authority==null||!authority.current())throw new SecurityException("Run revoked");
            activity.requestHandoff(()->{if(!sessionCurrent()||!authority.current())throw new SecurityException("Run revoked");},
                    ()->{if(!postOwner(this::run))cancel();},this::cancel);
        }catch(RuntimeException rejected){cancel();}
    }
    private void run(){
        if(!workerStarted.compareAndSet(false,true)||closed)return;
        try{
            requireHidden();startedElapsed=SystemClock.elapsedRealtime();stage="PREPARING";
            if(newsMode!=null){runNews();return;}
            if(!taskInputAllowed){runReadOnly();return;}
            artifacts=createArtifactDirectory();artifactKey=directoryKey(artifacts);
            navigation=new LiveRewardsNavigation(activity.getApplicationContext(),authority,activity.getTaskId(),
                    activity.getComponentName().flattenToString(),shape,this::hiddenCurrent);
            if(workflow==LiveRewardsTaskSelection.Workflow.SEARCH){
                workflowStep="SEARCH";
                runSearch(navigation.template());
                return;
            }
            workflowStep="BASE";
            ledger=new LiveRewardsLedger(activity.getNoBackupFilesDir().toPath(),authority.day(),runId);
            perception=new LiveRewardsPerception(activity.getApplicationContext(),authority,navigation,this::hiddenCurrent,this::publishArtifact,ledger);
            input=new LiveRewardsInput(activity.getApplicationContext(),authority,navigation,perception::rebind);
            coordinator=new LiveRewardsCoordinator(authority,ledger,new LiveRewardsCoordinator.Ports(){
                public LiveRewardsTypes.Preflight preflightRegion(LiveRunAuthority exact,LiveRewardsTypes.Kind kind)throws Exception{
                    if(exact!=authority)throw new SecurityException("Exact run required");
                    requireHidden();stage="PREFLIGHT";return perception.preflightRegion(exact,kind,selection);
                }
                public LiveRewardsTypes.Observation observe(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,LiveRewardsTypes.Task task)throws Exception{
                    requireHidden();stage=phase.name();return perception.observe(scope,phase,task);
                }
                public LiveRewardsInput.TapReceipt tap(LiveActionPermit permit)throws Exception{
                    requireHidden();
                    if(singleTask&&tapCalls!=0)throw new SecurityException("Single-task tap already attempted");
                    if(!selection.includes(permit.task()) || selection.limited() && tapCalls>=2)
                        throw new SecurityException("Tap outside confirmed task selection");
                    stage="TAP";tapCalls++;
                    LiveRewardsInput.TapReceipt result=null;
                    try{result=input.tap(permit);return result;}
                    finally{recordTap(permit,result);}
                }
                public LiveRewardsInput.ReturnReceipt returnAfterTap(LiveActionPermit permit,LiveRewardsInput.TapReceipt receipt)throws Exception{
                    requireHidden();
                    if(singleTask&&returnCalls!=0)throw new SecurityException("Single-task return already attempted");
                    if(!selection.includes(permit.task()) || selection.limited() && returnCalls>=2)
                        throw new SecurityException("Return outside confirmed task selection");
                    stage="WAIT_AND_RETURN";returnCalls++;
                    LiveRewardsInput.ReturnReceipt result=null;
                    try{result=input.returnAfterTap(permit,receipt);if(result!=null&&result.commandConfirmed()&&result.knownClean())returnSuccesses++;return result;}
                    finally{recordReturn(permit,receipt,result);}
                }
                public void pause(long millis)throws InterruptedException{
                    if(millis<0||millis>1000)throw new IllegalArgumentException("Bounded pause required");
                    requireHidden();Thread.sleep(millis);requireHidden();
                }
                public boolean knownClean(){return nativeResourcesClosed();}
                public void report(LiveRewardsTypes.Task task,LiveRewardsTypes.Outcome outcome,String reason,
                        LiveRewardsTypes.Observation before,LiveRewardsTypes.Observation after){
                    recordOutcome(task,outcome,reason,before,after);
                }
            },singleTask,selection);
            summary=coordinator.run();stage=summary.stopped()?"STOPPED":"COMPLETE";
            if(workflow==LiveRewardsTaskSelection.Workflow.FULL && summary.stopped())workflowReason=summary.reason();
            if(workflow==LiveRewardsTaskSelection.Workflow.FULL && !summary.stopped()){
                requireHidden();
                if(!nativeResourcesClosed()||inputUnknown)throw new SecurityException("BASE_RELEASE_UNCONFIRMED");
                workflowStep="READING";
                readNews(ReadingNewsChoice.Mode.SEVEN);
                workflowStep="SEARCH";
                runSearch(news.searchOrigin());
            }
        }catch(Exception failed){
            if(failed instanceof InterruptedException)Thread.currentThread().interrupt();
            failure=failed.getClass().getSimpleName().toUpperCase(Locale.ROOT);stage=canceled.get()?"CANCELED":"STOPPED";latchCancel();
            if(workflow!=LiveRewardsTaskSelection.Workflow.BASIC){
                String code=failed.getMessage();
                workflowReason=code!=null && code.matches("[A-Z][A-Z0-9_]{0,95}")?code:failure;
            }
        }catch(Error unknown){failure="ERROR_UNKNOWN";constructionUnknown=true;stage="CLEANUP_UNKNOWN";latchCancel();}
        finally{
            finishedElapsed=SystemClock.elapsedRealtime();workerReturned=true;
            closeOnOwner();
        }
    }
    private void runNews()throws Exception{
        requireHidden();
        if(singleTask||uiRecheck!=null||input!=null||coordinator!=null||authority.taskScopeCount()!=0
                ||authority.taskInputAllowed()!=newsMode.live)
            throw new SecurityException("News authority mismatch");
        artifacts=createArtifactDirectory();artifactKey=directoryKey(artifacts);
        ledger=new LiveRewardsLedger(activity.getNoBackupFilesDir().toPath(),authority.day(),runId);
        authority.attachLedger(ledger);
        navigation=new LiveRewardsNavigation(activity.getApplicationContext(),authority,activity.getTaskId(),
                activity.getComponentName().flattenToString(),shape,this::hiddenCurrent);
        readNews(newsMode);
    }
    private void readNews(ReadingNewsChoice.Mode mode)throws Exception{
        stage="NEWS_READING_ENTRY";
        news=new NewsAndroidPort(new ReadingApproval(mode),activity.getApplicationContext(),authority,navigation,shape,runId,
                this::hiddenCurrent,this::publishArtifact);
        try{
            news.run();
            requireHidden();
            if(!nativeResourcesClosed())throw new SecurityException("News cleanup unconfirmed");
            stage="COMPLETE";
        }finally{
            tapCalls+=news.tapCalls();tapSuccesses+=news.tapSuccesses();tapDispatches+=news.tapDispatches();
            returnCalls+=news.backCalls();backSuccesses+=news.backSuccesses();backDispatches+=news.backDispatches();
            returnSuccesses+=news.backSuccesses();inputUnknown|=news.inputUnknown();
        }
    }
    private void runSearch(BingNavigationSnapshot.Ready origin)throws Exception{
        requireHidden();
        if(workflow==LiveRewardsTaskSelection.Workflow.BASIC || !taskInputAllowed || singleTask
                || origin==null || !origin.fresh(authority.now()) || !nativeResourcesClosed() || inputUnknown)
            throw new SecurityException("SEARCH_SOURCE_OR_OWNER");
        stage="SEARCH";
        search=new AlphabetSearchCycle(new LiveRewardsLedger.FileStore(activity.getNoBackupFilesDir().toPath()),
                authority.day(),runId,()->LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        searchDevice=new AlphabetSearchDevice(activity.getApplicationContext(),authority,navigation,origin,shape,this::hiddenCurrent);
        try{
            var result=search.run(searchDevice);
            requireHidden();
            if(!nativeResourcesClosed())throw new SecurityException("SEARCH_CLEANUP_UNCONFIRMED");
            stage=result.status()==AlphabetSearchCycle.Status.COMPLETE?"COMPLETE":"STOPPED";
            if("COMPLETE".equals(stage))workflowStep="FINISHED";
            else workflowReason=result.status().name();
        }finally{
            tapCalls+=searchDevice.openCalls();tapDispatches+=searchDevice.openDispatches();tapSuccesses+=searchDevice.openSuccesses();
            returnCalls+=searchDevice.backCalls();backDispatches+=searchDevice.backDispatches();
            backSuccesses+=search.snapshot().backs();returnSuccesses+=search.snapshot().backs();
            inputUnknown|=searchDevice.inputOutcomeUnknown();
        }
    }
    private void runReadOnly()throws Exception{
        requireHidden();
        if(taskInputAllowed||authority==null||authority.taskInputAllowed()||input!=null||coordinator!=null
                ||authority.taskScopeCount()!=0)throw new SecurityException("Read-only preflight authority required");
        artifacts=createArtifactDirectory();artifactKey=directoryKey(artifacts);
        ledger=new LiveRewardsLedger(activity.getNoBackupFilesDir().toPath(),authority.day(),runId);
        authority.attachLedger(ledger);
        navigation=new LiveRewardsNavigation(activity.getApplicationContext(),authority,activity.getTaskId(),
                activity.getComponentName().flattenToString(),shape,this::hiddenCurrent);
        perception=new LiveRewardsPerception(activity.getApplicationContext(),authority,navigation,this::hiddenCurrent,this::publishArtifact,ledger);
        if(uiRecheck!=null){
            runMoreUiRecheck();
            return;
        }
        stage="PREFLIGHT";readOnlyPreflightStarted=true;
        try{readOnlyPreflight=perception.preflight();readOnlyPreflightReturned=true;}
        catch(Exception original){diagnoseReadOnlyRejection();throw original;}
        if(readOnlyPreflight==null||!readOnlyPreflight.completeScan()||!readOnlyPreflight.knownClean()||!readOnlyPreflight.sourceReady()
                ||!nativeResourcesClosed()||!authority.current()||!navigation.current())
            throw new SecurityException("Read-only preflight unqualified");
        stage="COMPLETE";
    }
    private void runMoreUiRecheck()throws Exception{
        stage="MORE_UI_RECHECK";
        uiRecheck.run(authority,new LiveMoreUiRecheck.Reader(){
            public boolean read(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase)throws Exception{
                requireHidden();
                return perception.readMoreRecheckUi(scope,phase);
            }
            public String diagnostic(){return perception.lastUiReadDiagnostic();}
            public boolean knownClean(){return nativeResourcesClosed();}
        },Thread::sleep);
        requireHidden();
        if(!uiRecheck.verified()||!navigation.current()||!nativeResourcesClosed())
            throw new SecurityException("Unqualified More UI recheck");
        stage="COMPLETE";
    }
    private void diagnoseReadOnlyRejection(){
        try{
            if(!taskInputAllowed&&navigation!=null&&authority!=null&&authority.current()&&nativeResourcesClosed()
                    &&navigation.hasFocusRejection())
                navigation.diagnoseReadOnlyFocus(authority.claimFocusDiagnostics(authority.now()));
        }catch(Throwable diagnosticFailure){
            if(diagnosticFailure instanceof InterruptedException)Thread.currentThread().interrupt();
            // The original preflight exception remains terminal. No ordinary scope is resumed.
        }
    }
    private void recordOutcome(LiveRewardsTypes.Task task,LiveRewardsTypes.Outcome outcome,String reason,
            LiveRewardsTypes.Observation before,LiveRewardsTypes.Observation after){
        try{
            JSONObject row=new JSONObject().put("taskKey",task==null?"NONE":task.key())
                    .put("kind",task==null?"NONE":task.kind().name()).put("outcome",outcome.name())
                    .put("reason",safeEnum(reason)).put("beforeQualified",before!=null&&before.qualified())
                    .put("afterQualified",after!=null&&after.qualified()).put("creditVerified",false)
                    .put("rawTextIncluded",false);
            rows.add(row);
        }catch(org.json.JSONException invalid){throw new IllegalStateException("Scalar outcome unavailable",invalid);}
    }
    private static String safeEnum(String value){return value!=null&&value.matches("[A-Z0-9_]{1,128}")?value:"UNQUALIFIED";}
    private void recordTap(LiveActionPermit permit,LiveRewardsInput.TapReceipt receipt)throws org.json.JSONException{
        var actor=receipt==null?null:receipt.actorResult();boolean invoked=actor!=null&&actor.invocationAttempted();
        if(invoked)tapDispatches++;if(actor!=null&&actor.commandExitZero())tapSuccesses++;
        inputUnknown|=receipt==null||!receipt.knownClean()||actor!=null&&actor.kind()==ReviewShizukuTapActor.Kind.DISPATCH_UNKNOWN;
        actions.add(new JSONObject().put("taskKey",permit.task().key()).put("action","TAP")
                .put("invocationAttempted",invoked).put("invocationKnown",receipt!=null).put("commandExitZero",actor!=null&&actor.commandExitZero())
                .put("actorKind",actor==null?"NO_ACTOR_RESULT":actor.kind().name())
                .put("actorReason",actor==null?"NO_ACTOR_RESULT":actor.reason().name())
                .put("knownClean",receipt!=null&&receipt.knownClean())
                .put("successfulElapsed",receipt==null?-1:receipt.successfulElapsed()));
    }
    private void recordReturn(LiveActionPermit permit,LiveRewardsInput.TapReceipt tap,
            LiveRewardsInput.ReturnReceipt receipt)throws org.json.JSONException{
        var actor=receipt==null?null:receipt.actorResult();boolean invoked=receipt!=null&&receipt.backDispatched();
        if(invoked)backDispatches++;if(actor!=null&&actor.commandExitZero())backSuccesses++;
        inputUnknown|=receipt==null||!receipt.knownClean()||actor!=null&&actor.kind()==ReviewShizukuBackActor.Kind.DISPATCH_UNKNOWN;
        long success=tap==null?-1:tap.successfulElapsed(),gate=receipt==null?-1:receipt.backGateElapsed();
        long decision=SystemClock.elapsedRealtime();
        actions.add(new JSONObject().put("taskKey",permit.task().key()).put("action","RETURN")
                .put("invocationAttempted",invoked).put("invocationKnown",receipt!=null).put("commandExitZero",actor!=null&&actor.commandExitZero())
                .put("knownClean",receipt!=null&&receipt.knownClean()).put("backCommandConfirmed",receipt!=null&&receipt.commandConfirmed())
                .put("reason",safeEnum(receipt==null?"NO_RETURN_RECEIPT":receipt.reason()))
                .put("actorKind",actor==null?"NO_ACTOR_RESULT":actor.kind().name())
                .put("actorReason",actor==null?"NO_ACTOR_RESULT":actor.reason().name())
                .put("successfulTapElapsed",success).put("backGateElapsed",gate)
                .put("returnDecisionElapsed",decision).put("decisionSinceSuccessfulTap",success>=0&&decision>=success?decision-success:-1)
                .put("elapsedSinceSuccessfulTap",success>=0&&gate>=success?gate-success:-1));
        if(receipt!=null&&receipt.commandConfirmed()&&(success<0||decision<success||decision-success<LiveRewardsInput.RETURN_DELAY_MS))
            throw new SecurityException("Early return decision rejected");
        if(invoked&&(success<0||gate<success||gate-success<LiveRewardsInput.RETURN_DELAY_MS))
            throw new SecurityException("Early return rejected");
    }
    private JSONObject reportJson()throws org.json.JSONException{
        JSONArray outcomes=new JSONArray();for(JSONObject row:rows)outcomes.put(row);
        JSONArray artifactsIndex=new JSONArray();for(JSONObject file:files)artifactsIndex.put(file);
        JSONArray actionIndex=new JSONArray();for(JSONObject action:actions)actionIndex.put(action);
        JSONObject uiDiagnostic=perception==null?null:perception.lastUiDiagnostic();
        JSONObject readPreparation=perception==null?null:perception.lastReadPreparation();
        JSONObject navigationDiagnostic=navigation==null?null:navigation.lastNavigationDiagnostic();
        JSONObject value=new JSONObject().put("profile",PROFILE).put("runId",runId).put("day",runDay)
                .put("stage",stage).put("failure",safeEnum(failure)).put("perceptionState",perceptionState())
                .put("lastUiDiagnostic",uiDiagnostic==null?JSONObject.NULL:uiDiagnostic)
                .put("lastReadPreparation",readPreparation==null?JSONObject.NULL:readPreparation)
                .put("lastNavigationDiagnostic",navigationDiagnostic==null?JSONObject.NULL:navigationDiagnostic).put("terminal",workerReturned)
                .put("cleanup",allResourcesClosed()?"CONFIRMED":"UNKNOWN").put("fullScreenCaptures",0)
                .put("tapCalls",tapCalls).put("tapCommandSuccesses",tapSuccesses).put("returnCalls",returnCalls)
                .put("tapDispatchAttempts",tapDispatches).put("backDispatchAttempts",backDispatches).put("backCommandSuccesses",backSuccesses)
                .put("inputOutcomeUnknown",inputUnknown)
                .put("returnCommandConfirmed",returnSuccesses).put("captures",cropImages).put("markedImages",markedImages)
                .put("startedElapsed",startedElapsed).put("finishedElapsed",finishedElapsed).put("minimumVisitDelayMs",visitDelayMillis())
                .put("visitDelayStartsAt","SUCCESSFUL_TAP_COMMAND_RETURN").put("accountCreditVerified",false)
                .put("rawTextIncluded",false).put("rawDataUploaded",false).put("oldRecordsReset",false)
                .put("outcomes",outcomes).put("artifacts",artifactsIndex).put("actions",actionIndex);
        if(summary!=null)value.put("summary",summary.fixedSummary()+(singleTask?" taskBudget=1":selection.limited()?" scope=MORE_PAIR taskBudget=2":""));
        if(workflow!=LiveRewardsTaskSelection.Workflow.BASIC){
            value.put("workflow",workflow.name()).put("workflowStep",workflowStep).put("workflowReason",workflowReason)
                    .put("search",searchReport());
            if(workflow==LiveRewardsTaskSelection.Workflow.FULL)
                value.put("reading",news==null?new JSONObject().put("status","NOT_STARTED"):news.report());
        }
        return value;
    }
    private JSONObject searchReport()throws org.json.JSONException{
        var s=search==null?null:search.snapshot();
        return new JSONObject().put("status",s==null?"NOT_STARTED":s.status().name())
                .put("reason","SEARCH".equals(workflowStep)?workflowReason:"NONE")
                .put("claimed",s==null?0:s.claimed()).put("submitted",s==null?0:s.submitted())
                .put("backs",s==null?0:s.backs()).put("returned",s==null?0:s.returned())
                .put("previousAttempts",s==null?0:s.previousAttempts()).put("previousReturns",s==null?0:s.previousReturns())
                .put("minimumWaitMillis",AlphabetSearchCycle.WAIT_MILLIS).put("accountCreditVerified",false);
    }
    private void publishArtifact(String taskKey,String phase,String filename,byte[] payload)throws Exception{
        if(taskKey==null||!taskKey.matches("[a-f0-9]{64}")||phase==null
                ||!Set.of("PREFLIGHT","BEFORE","AFTER_1","AFTER_2").contains(phase)
                ||filename==null||!Set.of("CARD.png","MARKED.png","CAPTURE.json","DIAGNOSTICS.json","RESULT.json").contains(filename)
                ||payload==null||payload.length==0||payload.length>10*1024*1024)
            throw new SecurityException("Bounded local artifact required");
        requireHidden();if(!nativeResourcesClosed())throw new SecurityException("Native cleanup required before publication");
        if(filename.endsWith(".json"))validateScalarJson(new JSONObject(new String(payload,StandardCharsets.UTF_8)));
        byte[] owned=payload.clone();CountDownLatch done=new CountDownLatch(1);AtomicBoolean published=new AtomicBoolean();appendActive.set(true);
        if(!main.post(()->{
            try{
                AtomicBoolean written=new AtomicBoolean();boolean outer=activity.liveRewardsPublish(this,()->{
                    if(!hiddenCurrent()||!nativeResourcesClosed())return;
                    try{
                        verifyArtifactRoot();Path card=childDirectory(artifacts,taskKey),directory=childDirectory(card,phase);
                        writeFile(directory,filename,owned);verifyArtifactRoot();
                        JSONObject meta=new JSONObject().put("taskKey",taskKey).put("phase",phase).put("file",filename)
                                .put("bytes",owned.length).put("sha256",CardNativeMockReceipt.digest(owned));
                        files.add(meta);if(filename.equals("MARKED.png"))markedImages++;if(filename.equals("CARD.png"))cropImages++;
                        written.set(true);
                    }catch(Exception failed){/* Partial files are retained and never counted as qualified. */}
                });
                published.set(outer&&written.get()&&hiddenCurrent()&&nativeResourcesClosed());
            }finally{Arrays.fill(owned,(byte)0);appendActive.set(false);done.countDown();if(workerReturned)postOwner(this::closeOnOwner);}
        })){appendActive.set(false);Arrays.fill(owned,(byte)0);throw new SecurityException("Artifact queue unavailable");}
        long wait=Math.min(10000,authority.deadline()-SystemClock.elapsedRealtime());
        if(wait<=0||!done.await(wait,TimeUnit.MILLISECONDS)){constructionUnknown=true;latchCancel();throw new SecurityException("Artifact publication unresolved");}
        if(!published.get())throw new SecurityException("Artifact publication rejected");
    }
    private static void validateScalarJson(Object value)throws org.json.JSONException{
        if(value instanceof JSONObject object){
            var keys=object.keys();while(keys.hasNext()){
                String key=keys.next();if(key.matches("(?i)(text|title|description|rawText|ocrText|recognizedText|rawXml|windowDump|activityDump)"))
                    throw new SecurityException("Raw text is not a diagnostic");
                validateScalarJson(object.get(key));
            }
        }else if(value instanceof JSONArray array){for(int i=0;i<array.length();i++)validateScalarJson(array.get(i));}
        else if(value instanceof String string&&string.length()>512)throw new SecurityException("Unbounded scalar string");
    }
    private Path createArtifactDirectory()throws Exception{
        Path cache=activity.getCacheDir().toPath();directoryKey(cache);Path parent=childDirectory(cache,DIRECTORY);
        return Files.createDirectory(parent.resolve(runId));
    }
    private static Path childDirectory(Path parent,String name)throws Exception{
        Object before=directoryKey(parent);Path child=parent.resolve(name);
        try{Files.createDirectory(child);}catch(FileAlreadyExistsException exists){/* Verify below. */}
        directoryKey(child);if(!before.equals(directoryKey(parent)))throw new SecurityException("Parent changed");return child;
    }
    private static Object directoryKey(Path path)throws Exception{
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isDirectory()||a.isSymbolicLink()||a.fileKey()==null)throw new SecurityException("Exact artifact directory required");return a.fileKey();
    }
    private void verifyArtifactRoot()throws Exception{
        if(artifacts==null||artifactKey==null||!artifactKey.equals(directoryKey(artifacts)))throw new SecurityException("Artifact root changed");
    }
    private static void writeFile(Path directory,String name,byte[] bytes)throws Exception{
        Object key=directoryKey(directory);
        try(FileChannel file=FileChannel.open(directory.resolve(name),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())if(file.write(data)<=0)throw new SecurityException("Artifact incomplete");file.force(true);
        }
        if(!key.equals(directoryKey(directory)))throw new SecurityException("Artifact directory changed");
        try(FileChannel parent=FileChannel.open(directory,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){parent.force(true);}
    }
    private boolean nativeResourcesClosed(){return !constructionUnknown&&AndroidCardRegionCapture.knownClean()
            &&(news==null||news.knownClean())
            &&(searchDevice==null||AlphabetSearchDevice.knownClean())
            &&AndroidOverviewUiReader.knownClean()&&AndroidUiStructureReader.processKnownClean()&&ReviewBingNavigation.knownClean()
            &&(navigation==null||navigation.knownClean())&&(perception==null||perception.knownClean())&&(input==null||input.knownClean());}
    private boolean nativeWorkClosed(){return nativeResourcesClosed()&&!appendActive.get();}
    private boolean allResourcesClosed(){return nativeWorkClosed()&&(foreground==null||foreground.destroyed());}
    private void latchCancel(){canceled.set(true);if(authority!=null)authority.cancel();if(coordinator!=null)coordinator.cancel();activity.clearPendingHandoff();}
    void cancel(){if(root!=null)root.request();else latchCancel();}
    private void closeOnOwner(){
        if(!workerStarted.get()&&canceled.get()){workerReturned=true;finishedElapsed=SystemClock.elapsedRealtime();stage="CANCELED";}
        if(closed||!workerReturned)return;
        if(!nativeWorkClosed()){
            stage="CLEANUP_UNKNOWN";LiveRewardsForegroundService.cleanupUnknown(foreground);notifyFinished();return;
        }
        if(foreground!=null&&!foreground.destroyed()){
            if(!foregroundStopWaiting){
                foregroundStopWaiting=true;terminalWorkStage=stage;stage="FOREGROUND_STOPPING";
                LiveRewardsForegroundService.requestClose(activity.getApplicationContext(),foreground);
                if(!owner.postDelayed(this::foregroundStopTimeout,5000))foregroundStopTimeout();
            }
            return;
        }
        finishAfterForeground();
    }
    private void foregroundStopTimeout(){
        if(closed)return;
        if(foreground==null||foreground.destroyed()){finishAfterForeground();return;}
        foreground.stopUnknown();failure="FOREGROUND_STOP_UNKNOWN";stage="CLEANUP_UNKNOWN";
        LiveRewardsForegroundService.cleanupUnknown(foreground);notifyFinished();
    }
    private void finishAfterForeground(){
        if(closed||!workerReturned||!allResourcesClosed())return;
        stage=workStageAfterForeground(stage,terminalWorkStage);
        publishTerminalReports();
        closed=true;
        try{
            if(root==null)throw new SecurityException("Exact live root required");
            root.releaseIfClosed();
            if(!root.released())throw new SecurityException("Root release unconfirmed");
        }catch(RuntimeException unknown){constructionUnknown=true;failure="ROOT_RELEASE_UNCONFIRMED";stage="CLEANUP_UNKNOWN";notifyFinished();return;}
        released=true;releasedElapsed=SystemClock.elapsedRealtime();
        try{writeReleaseReceipt();releaseReceiptPublished=true;}
        catch(Exception failed){failure="RELEASE_PUBLICATION_FAILED";}
        loop.quitSafely();
        notifyFinished();
    }
    static String workStageAfterForeground(String current,String terminalWorkStage){
        if(!"FOREGROUND_STOPPING".equals(current))return current;
        if(!"COMPLETE".equals(terminalWorkStage)&&!"STOPPED".equals(terminalWorkStage)&&!"CANCELED".equals(terminalWorkStage))
            throw new IllegalStateException("Sealed terminal work stage required");
        return terminalWorkStage;
    }
    private void publishTerminalReports(){
        if(reportAttempted)return;reportAttempted=true;
        try{
            if(artifacts==null){artifacts=createArtifactDirectory();artifactKey=directoryKey(artifacts);}
            byte[] bytes=reportJson().toString().getBytes(StandardCharsets.UTF_8);
            verifyArtifactRoot();writeFile(artifacts,"REPORT.json",bytes);verifyArtifactRoot();
            reportSha256=CardNativeMockReceipt.digest(bytes);reportPublished=true;
            writeForegroundReceipt();
            if(newsMode!=null)writeNewsReceipt();
            else if(uiRecheck!=null)writeUiRecheckReceipt();
            else if(!taskInputAllowed)writeReadOnlyReceipt();
            writeFocusReceipt();
            if(taskInputAllowed && !singleTask && newsMode==null && workflow!=LiveRewardsTaskSelection.Workflow.SEARCH)writeScrollTrace();
        }catch(Throwable failed){failure="REPORT_PUBLICATION_FAILED";}
    }
    private void writeScrollTrace()throws Exception{
        JSONObject trace=new JSONObject().put("schemaVersion",1).put("runId",runId).put("day",runDay)
                .put("reportSha256",reportSha256)
                .put("activityScrollTrace",perception==null?new JSONArray():perception.activityScrollTrace());
        verifyArtifactRoot();writeFile(artifacts,"SCROLL.json",trace.toString().getBytes(StandardCharsets.UTF_8));verifyArtifactRoot();
    }
    private void writeReadOnlyReceipt()throws Exception{
        var exact=readOnlyPreflight;
        JSONObject receipt=new JSONObject().put("schemaVersion",1).put("profile",PROFILE).put("runId",runId).put("day",runDay)
                .put("purpose","READ_ONLY_PREFLIGHT").put("taskInputAllowed",taskInputAllowed)
                .put("taskScopeCount",authority==null?0:authority.taskScopeCount())
                .put("inputConstructed",input!=null).put("coordinatorConstructed",coordinator!=null)
                .put("tapCalls",tapCalls).put("tapDispatchAttempts",tapDispatches).put("returnCalls",returnCalls).put("backDispatchAttempts",backDispatches)
                .put("preflightStarted",readOnlyPreflightStarted).put("preflightReturned",readOnlyPreflightReturned)
                .put("completeScan",exact!=null&&exact.completeScan()).put("sourceReady",exact!=null&&exact.sourceReady())
                .put("knownClean",exact!=null&&exact.knownClean()).put("taskCount",exact==null?0:exact.tasks().size())
                .put("scrollAllowed",true).put("issuedElapsed",approval.issued).put("deadlineElapsed",approval.deadline)
                .put("reportSha256",reportSha256).put("rawTextIncluded",false).put("rawDataUploaded",false).put("oldRecordsReset",false);
        verifyArtifactRoot();writeFile(artifacts,"READONLY.json",receipt.toString().getBytes(StandardCharsets.UTF_8));verifyArtifactRoot();
    }
    private long visitDelayMillis(){
        return newsMode==null?LiveRewardsInput.RETURN_DELAY_MS:ReadingNewsCycle.WAIT_MILLIS;
    }
    private void writeNewsReceipt()throws Exception{
        JSONObject receipt=news==null?new JSONObject().put("status","NOT_STARTED").put("listConfirmed",false):news.report();
        receipt.put("schemaVersion",1).put("profile",PROFILE).put("runId",runId).put("day",runDay)
                .put("purpose",newsMode.purpose)
                .put("taskInputAllowed",taskInputAllowed).put("knownClean",nativeResourcesClosed())
                .put("reportSha256",reportSha256).put("rawTextIncluded",false).put("rawDataUploaded",false)
                .put("oldRecordsReset",false).put("accountCreditVerified",false);
        verifyArtifactRoot();writeFile(artifacts,"NEWS.json",receipt.toString().getBytes(StandardCharsets.UTF_8));verifyArtifactRoot();
    }
    private void writeUiRecheckReceipt()throws Exception{
        var attempts=new JSONArray();
        for(var attempt:uiRecheck.attempts()){
            attempts.put(new JSONObject().put("step",attempt.step().name()).put("phase",attempt.phase().name())
                    .put("started",attempt.started()).put("deadline",attempt.deadline()).put("finished",attempt.finished())
                    .put("result",attempt.result().name()).put("reader",attempt.diagnostic()));
        }
        var receipt=new JSONObject().put("schemaVersion",1).put("profile",PROFILE).put("runId",runId).put("day",runDay)
                .put("purpose","MORE_AFTER_UI_RECHECK").put("taskInputAllowed",taskInputAllowed)
                .put("taskScopeCount",authority==null?0:authority.taskScopeCount()).put("inputConstructed",input!=null)
                .put("coordinatorConstructed",coordinator!=null).put("knownClean",nativeResourcesClosed())
                .put("verified",uiRecheck.verified()).put("attempts",attempts).put("reportSha256",reportSha256)
                .put("rawTextIncluded",false).put("rawDataUploaded",false).put("oldRecordsReset",false);
        verifyArtifactRoot();writeFile(artifacts,"UI_RECHECK.json",receipt.toString().getBytes(StandardCharsets.UTF_8));verifyArtifactRoot();
    }
    private void writeFocusReceipt()throws Exception{
        JSONObject focus=navigation==null?null:navigation.focusDiagnostics();
        JSONObject receipt=new JSONObject().put("schemaVersion",1).put("profile",PROFILE).put("runId",runId).put("day",runDay)
                .put("reportSha256",reportSha256).put("focusDiagnostics",focus==null?JSONObject.NULL:focus)
                .put("rawTextIncluded",false).put("rawDataUploaded",false).put("oldRecordsReset",false);
        verifyArtifactRoot();writeFile(artifacts,"FOCUS.json",receipt.toString().getBytes(StandardCharsets.UTF_8));verifyArtifactRoot();
    }
    private void writeForegroundReceipt()throws Exception{
        var exact=foreground;
        JSONObject receipt=new JSONObject().put("schemaVersion",1).put("profile",PROFILE).put("runId",runId).put("day",runDay)
                .put("serviceType","SPECIAL_USE").put("process","CAPTURE_ONCE")
                .put("issuedElapsed",approval.issued).put("deadlineElapsed",approval.deadline)
                .put("startupDeadlineElapsed",exact==null?Math.min(approval.deadline,approval.issued+5000):exact.startupDeadline)
                .put("startRequested",exact!=null&&exact.dispatchAttempted()).put("startReturned",exact!=null&&exact.startReturned())
                .put("readyObserved",exact!=null&&exact.everReady()).put("readyElapsed",exact==null?-1:exact.readyElapsed())
                .put("stopRequested",exact!=null&&exact.closeRequested()).put("destroyObserved",exact!=null&&exact.destroyObserved())
                .put("destroyedElapsed",exact==null?-1:exact.destroyedElapsed())
                .put("state",exact==null?"NOT_STARTED":exact.state().name()).put("reason",exact==null?"NONE":exact.reason().name())
                .put("nativeCleanup",nativeWorkClosed()?"CONFIRMED":"UNKNOWN").put("reportSha256",reportSha256)
                .put("minimumVisitDelayMs",visitDelayMillis())
                .put("rawTextIncluded",false).put("rawDataUploaded",false).put("oldRecordsReset",false);
        verifyArtifactRoot();writeFile(artifacts,"FOREGROUND.json",receipt.toString().getBytes(StandardCharsets.UTF_8));verifyArtifactRoot();
    }
    private boolean releaseConfirmed(){return workerReturned&&closed&&released&&root!=null&&root.released()&&allResourcesClosed();}
    private void writeReleaseReceipt()throws Exception{
        if(!releaseConfirmed()||!reportPublished||reportSha256==null||!reportSha256.matches("[a-f0-9]{64}")
                ||finishedElapsed<0||releasedElapsed<finishedElapsed)
            throw new SecurityException("Released terminal report required");
        verifyArtifactRoot();
        JSONObject receipt=new JSONObject().put("schemaVersion",1).put("profile",PROFILE).put("runId",runId).put("day",runDay)
                .put("terminal",true).put("released",true).put("rootEpochReleased",true).put("cleanup","CONFIRMED")
                .put("reportFile","REPORT.json").put("reportSha256",reportSha256)
                .put("reportFinishedElapsed",finishedElapsed).put("releasedElapsed",releasedElapsed).put("failure",safeEnum(failure))
                .put("minimumVisitDelayMs",visitDelayMillis())
                .put("rawTextIncluded",false).put("rawDataUploaded",false).put("oldRecordsReset",false);
        if(!releaseConfirmed())throw new SecurityException("Release proof changed");
        writeFile(artifacts,"RELEASE.json",receipt.toString().getBytes(StandardCharsets.UTF_8));verifyArtifactRoot();
    }
    private void notifyFinished(){if(notified)return;notified=true;activity.liveRewardsFinished(this,fixedSummary()+userResult());}
    private String userResult(){
        if(workflow==LiveRewardsTaskSelection.Workflow.BASIC)return "";
        var text=new StringBuilder(LiveRewardsUi.RESULT_HEADING);
        boolean finished="COMPLETE".equals(stage) && releaseConfirmed() && releaseReceiptPublished;
        text.append(finished?"本轮已结束。":"本轮已停止。");
        if(workflow==LiveRewardsTaskSelection.Workflow.FULL && summary!=null)
            text.append("\n基础任务尝试点击 ").append(summary.taps()).append(" 次。");
        if(workflow==LiveRewardsTaskSelection.Workflow.FULL && news!=null){
            try{
                var record=news.report();
                text.append("\n本轮打开新闻 ").append(record.optInt("storyTaps")).append(" 篇，自行返回 ")
                        .append(record.optInt("storyBacks")).append(" 次。");
            }catch(org.json.JSONException unavailable){text.append("\n阅读结果未能读取。");}
        }
        if(search!=null){
            var s=search.snapshot();
            text.append("\n本轮已提交 ").append(s.submitted()).append(" 次搜索，返回后已确认入口 ")
                    .append(s.returned()).append(" 次。今天此前已尝试 ").append(s.previousAttempts()).append(" 个字母。");
        }else text.append("\n搜索尚未开始。");
        if(!finished)text.append("\n停止阶段 ").append(workflowStep).append("，原因 ")
                .append("NONE".equals(workflowReason)?safeEnum(failure):workflowReason).append("。");
        return text.toString();
    }
    private String workflowScalars(){
        if(workflow==LiveRewardsTaskSelection.Workflow.BASIC)return "";
        var s=search==null?null:search.snapshot();
        return " workflow="+workflow+" workflowStep="+workflowStep+" searchReason="+workflowReason
                +" searchStatus="+(s==null?"NOT_STARTED":s.status())+" searchSubmitted="+(s==null?0:s.submitted())
                +" searchBacks="+(s==null?0:s.backs())+" searchReturned="+(s==null?0:s.returned());
    }
    boolean finishedAndReleased(){return workerReturned&&closed&&released;}
    private String perceptionState(){return news!=null?safeEnum(news.state()):perception==null?"NOT_CREATED":safeEnum(perception.safeState());}
    String fixedSummary(){return "profile="+PROFILE+" stage="+stage+" terminal="+workerReturned+" released="+released
            +" cleanup="+(allResourcesClosed()?"CONFIRMED":"UNKNOWN")+" failure="+safeEnum(failure)
            +" reportPublished="+reportPublished+" releaseReceiptPublished="+releaseReceiptPublished
            +" perceptionState="+perceptionState()
            +" cropDiagnostic="+(perception==null?"NONE":perception.lastCropDiagnostic())
            +" uiReadDiagnostic="+(perception==null?"NONE":perception.lastUiReadDiagnostic())
            +(uiRecheck==null?"":" uiRecheckStep="+uiRecheck.step()+" uiRecheckResult="+uiRecheck.lastResult())
            +" tapCalls="+tapCalls+" tapCommandSuccesses="+tapSuccesses+" returnCalls="+returnCalls+" returnCommandConfirmed="+returnSuccesses
            +" tapDispatchAttempts="+tapDispatches+" backDispatchAttempts="+backDispatches+" backCommandSuccesses="+backSuccesses+" inputOutcomeUnknown="+inputUnknown
            +(newsMode==null?"":" newsMode="+newsMode)+workflowScalars()
            +" captures="+cropImages+" marks="+markedImages+" fullScreenCaptures=0 minimumVisitDelayMs="+visitDelayMillis()
            +" waitFrom=SUCCESSFUL_TAP_COMMAND_RETURN accountCreditVerified=false rawDataUploaded=false oldRecordsReset=false artifacts="+runId
            +(summary==null?"":" "+summary.fixedSummary().substring(("profile="+PROFILE+" ").length()));}
}
