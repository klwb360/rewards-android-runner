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
    private LiveRewardsCoordinator.Summary summary;
    private Path artifacts;
    private Object artifactKey;
    private volatile String stage="NEW",failure="NONE";
    private volatile boolean workerReturned,closed,released,constructionUnknown,reportPublished,releaseReceiptPublished;
    private boolean notified;
    private int tapCalls,tapSuccesses,returnCalls,returnSuccesses,markedImages,cropImages,tapDispatches,backDispatches,backSuccesses;
    private boolean inputUnknown;
    private long startedElapsed,finishedElapsed=-1,releasedElapsed=-1;
    private String reportSha256;

    private LiveRewardsRuntime(DormantCaptureConfirmationActivity activity,
            VisibleCaptureAuthority.LiveRewardsApproval approval,BingNavigationSnapshot.Shape shape){
        this.activity=activity;this.approval=approval;this.shape=shape;
    }
    static LiveRewardsRuntime start(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,Object approvalOwner){
        CaptureReleaseGate.requireConfigured();
        if(activity==null||click==null||approvalOwner==null||Looper.myLooper()!=Looper.getMainLooper()
                ||android.os.Build.VERSION.SDK_INT<34||android.os.Process.myUid()<10000
                ||!"com.aliothmoon.maafw.productrewardsworking.capturereview".equals(activity.getPackageName())
                ||!(activity.getPackageName()+":capture_once").equals(Application.getProcessName()))
            throw new SecurityException("Explicit live run process required");
        var epoch=CaptureSessionRuntime.alignmentEpoch();long now=SystemClock.elapsedRealtime();
        var approval=VisibleCaptureAuthority.issueLiveRewardsApproval(activity,click,epoch,approvalOwner,now);
        var manager=activity.getSystemService(DisplayManager.class);var display=manager==null?null:manager.getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=Display.STATE_ON||display.getRotation()!=Surface.ROTATION_0)
            throw new SecurityException("Reviewed display required");
        var wm=activity.createDisplayContext(display).getSystemService(WindowManager.class);
        if(wm==null)throw new SecurityException("Display bounds unavailable");
        var bounds=wm.getMaximumWindowMetrics().getBounds();
        if(bounds.left!=0||bounds.top!=0||bounds.width()!=1440||bounds.height()!=2560)
            throw new SecurityException("Reviewed portrait shape required");
        var run=new LiveRewardsRuntime(activity,approval,new BingNavigationSnapshot.Shape(bounds.width(),bounds.height(),0));
        run.root=CaptureSessionRuntime.retainAlignment(run,approval.deadline,run::latchCancel,run::closeOnOwner,()->run.closed,run::postOwner);
        try{
            run.authority=new LiveRunAuthority(approval,new LiveRunAuthority.Clock(){
                public long elapsedRealtime(){return SystemClock.elapsedRealtime();}
                public String day(){return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();}
            },run::sessionCurrent);
            run.loop.start();run.owner=new Handler(run.loop.getLooper());run.root.arm();run.stage="HANDOFF";
            if(!run.main.post(run::handoff))run.cancel();
        }catch(RuntimeException unknown){run.constructionUnknown=true;run.stage="CLEANUP_UNKNOWN";run.cancel();}
        return run;
    }
    private boolean postOwner(Runnable action){
        try{if(owner!=null&&owner.post(action))return true;}catch(RuntimeException unavailable){/* Retain graph. */}
        constructionUnknown=true;latchCancel();return false;
    }
    private boolean sessionCurrent(){return !constructionUnknown&&!workerReturned&&!canceled.get()&&root!=null&&root.current();}
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
            artifacts=createArtifactDirectory();artifactKey=directoryKey(artifacts);
            ledger=new LiveRewardsLedger(activity.getNoBackupFilesDir().toPath(),authority.day(),runId);
            navigation=new LiveRewardsNavigation(activity.getApplicationContext(),authority,activity.getTaskId(),
                    activity.getComponentName().flattenToString(),shape,this::hiddenCurrent);
            perception=new LiveRewardsPerception(activity.getApplicationContext(),authority,navigation,this::hiddenCurrent,this::publishArtifact,ledger);
            input=new LiveRewardsInput(activity.getApplicationContext(),authority,navigation,perception::rebind);
            coordinator=new LiveRewardsCoordinator(authority,ledger,new LiveRewardsCoordinator.Ports(){
                public LiveRewardsTypes.Preflight preflight(LiveRunAuthority exact)throws Exception{
                    if(exact!=authority)throw new SecurityException("Exact run required");
                    requireHidden();stage="PREFLIGHT";return perception.preflight();
                }
                public LiveRewardsTypes.Observation observe(LiveRunAuthority.CardScope scope,LiveRewardsTypes.Phase phase,LiveRewardsTypes.Task task)throws Exception{
                    requireHidden();stage=phase.name();return perception.observe(scope,phase,task);
                }
                public LiveRewardsInput.TapReceipt tap(LiveActionPermit permit)throws Exception{
                    requireHidden();stage="TAP";tapCalls++;
                    LiveRewardsInput.TapReceipt result=null;
                    try{result=input.tap(permit);return result;}
                    finally{recordTap(permit,result);}
                }
                public LiveRewardsInput.ReturnReceipt returnAfterTap(LiveActionPermit permit,LiveRewardsInput.TapReceipt receipt)throws Exception{
                    requireHidden();stage="WAIT_AND_RETURN";returnCalls++;
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
            });
            summary=coordinator.run();stage=summary.stopped()?"STOPPED":"COMPLETE";
        }catch(Exception failed){
            if(failed instanceof InterruptedException)Thread.currentThread().interrupt();
            failure=failed.getClass().getSimpleName().toUpperCase(Locale.ROOT);stage=canceled.get()?"CANCELED":"STOPPED";latchCancel();
        }catch(Error unknown){failure="ERROR_UNKNOWN";constructionUnknown=true;stage="CLEANUP_UNKNOWN";latchCancel();}
        finally{
            finishedElapsed=SystemClock.elapsedRealtime();workerReturned=true;
            try{if(artifacts!=null){
                byte[] bytes=reportJson().toString().getBytes(StandardCharsets.UTF_8);
                verifyArtifactRoot();writeFile(artifacts,"REPORT.json",bytes);verifyArtifactRoot();
                reportSha256=CardNativeMockReceipt.digest(bytes);reportPublished=true;
            }}
            catch(Exception failed){failure="REPORT_PUBLICATION_FAILED";}
            closeOnOwner();
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
        JSONObject value=new JSONObject().put("profile",PROFILE).put("runId",runId).put("day",authority==null?"UNKNOWN":authority.day())
                .put("stage",stage).put("failure",safeEnum(failure)).put("perceptionState",perceptionState())
                .put("lastUiDiagnostic",uiDiagnostic==null?JSONObject.NULL:uiDiagnostic)
                .put("lastReadPreparation",readPreparation==null?JSONObject.NULL:readPreparation)
                .put("lastNavigationDiagnostic",navigationDiagnostic==null?JSONObject.NULL:navigationDiagnostic).put("terminal",workerReturned)
                .put("cleanup",allResourcesClosed()?"CONFIRMED":"UNKNOWN").put("fullScreenCaptures",0)
                .put("tapCalls",tapCalls).put("tapCommandSuccesses",tapSuccesses).put("returnCalls",returnCalls)
                .put("tapDispatchAttempts",tapDispatches).put("backDispatchAttempts",backDispatches).put("backCommandSuccesses",backSuccesses)
                .put("inputOutcomeUnknown",inputUnknown)
                .put("returnCommandConfirmed",returnSuccesses).put("captures",cropImages).put("markedImages",markedImages)
                .put("startedElapsed",startedElapsed).put("finishedElapsed",finishedElapsed).put("minimumVisitDelayMs",LiveRewardsInput.RETURN_DELAY_MS)
                .put("visitDelayStartsAt","SUCCESSFUL_TAP_COMMAND_RETURN").put("accountCreditVerified",false)
                .put("rawTextIncluded",false).put("rawDataUploaded",false).put("oldRecordsReset",false)
                .put("outcomes",outcomes).put("artifacts",artifactsIndex).put("actions",actionIndex);
        if(summary!=null)value.put("summary",summary.fixedSummary());
        return value;
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
            &&AndroidOverviewUiReader.knownClean()&&AndroidUiStructureReader.processKnownClean()&&ReviewBingNavigation.knownClean()
            &&(navigation==null||navigation.knownClean())&&(perception==null||perception.knownClean())&&(input==null||input.knownClean());}
    private boolean allResourcesClosed(){return nativeResourcesClosed()&&!appendActive.get();}
    private void latchCancel(){canceled.set(true);if(authority!=null)authority.cancel();if(coordinator!=null)coordinator.cancel();activity.clearPendingHandoff();}
    void cancel(){if(root!=null)root.request();else latchCancel();}
    private void closeOnOwner(){
        if(!workerStarted.get()&&canceled.get()){workerReturned=true;stage="CANCELED";}
        if(closed||!workerReturned)return;
        if(!allResourcesClosed()){stage="CLEANUP_UNKNOWN";notifyFinished();return;}
        closed=true;
        try{
            if(root==null)throw new SecurityException("Exact live root required");
            root.releaseIfClosed();
            if(!root.released())throw new SecurityException("Root release unconfirmed");
        }catch(RuntimeException unknown){constructionUnknown=true;failure="ROOT_RELEASE_UNCONFIRMED";stage="CLEANUP_UNKNOWN";notifyFinished();return;}
        released=true;releasedElapsed=SystemClock.elapsedRealtime();loop.quitSafely();
        try{writeReleaseReceipt();releaseReceiptPublished=true;}
        catch(Exception failed){failure="RELEASE_PUBLICATION_FAILED";}
        notifyFinished();
    }
    private boolean releaseConfirmed(){return workerReturned&&closed&&released&&root!=null&&root.released()&&allResourcesClosed();}
    private void writeReleaseReceipt()throws Exception{
        if(!releaseConfirmed()||!reportPublished||reportSha256==null||!reportSha256.matches("[a-f0-9]{64}")
                ||finishedElapsed<0||releasedElapsed<finishedElapsed)
            throw new SecurityException("Released terminal report required");
        verifyArtifactRoot();
        JSONObject receipt=new JSONObject().put("schemaVersion",1).put("profile",PROFILE).put("runId",runId).put("day",authority.day())
                .put("terminal",true).put("released",true).put("rootEpochReleased",true).put("cleanup","CONFIRMED")
                .put("reportFile","REPORT.json").put("reportSha256",reportSha256)
                .put("reportFinishedElapsed",finishedElapsed).put("releasedElapsed",releasedElapsed).put("failure",safeEnum(failure))
                .put("minimumVisitDelayMs",LiveRewardsInput.RETURN_DELAY_MS)
                .put("rawTextIncluded",false).put("rawDataUploaded",false).put("oldRecordsReset",false);
        if(!releaseConfirmed())throw new SecurityException("Release proof changed");
        writeFile(artifacts,"RELEASE.json",receipt.toString().getBytes(StandardCharsets.UTF_8));verifyArtifactRoot();
    }
    private void notifyFinished(){if(notified)return;notified=true;activity.liveRewardsFinished(this,fixedSummary());}
    boolean finishedAndReleased(){return workerReturned&&closed&&released;}
    private String perceptionState(){return perception==null?"NOT_CREATED":safeEnum(perception.safeState());}
    String fixedSummary(){return "profile="+PROFILE+" stage="+stage+" terminal="+workerReturned+" released="+released
            +" cleanup="+(allResourcesClosed()?"CONFIRMED":"UNKNOWN")+" failure="+safeEnum(failure)
            +" reportPublished="+reportPublished+" releaseReceiptPublished="+releaseReceiptPublished
            +" perceptionState="+perceptionState()
            +" tapCalls="+tapCalls+" tapCommandSuccesses="+tapSuccesses+" returnCalls="+returnCalls+" returnCommandConfirmed="+returnSuccesses
            +" tapDispatchAttempts="+tapDispatches+" backDispatchAttempts="+backDispatches+" backCommandSuccesses="+backSuccesses+" inputOutcomeUnknown="+inputUnknown
            +" captures="+cropImages+" marks="+markedImages+" fullScreenCaptures=0 minimumVisitDelayMs="+LiveRewardsInput.RETURN_DELAY_MS
            +" waitFrom=SUCCESSFUL_TAP_COMMAND_RETURN accountCreditVerified=false rawDataUploaded=false oldRecordsReset=false artifacts="+runId
            +(summary==null?"":" "+summary.fixedSummary().substring(("profile="+PROFILE+" ").length()));}
}
