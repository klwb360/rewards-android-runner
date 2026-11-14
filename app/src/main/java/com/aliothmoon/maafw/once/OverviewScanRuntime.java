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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static com.aliothmoon.maafw.once.OverviewFailureDiagnostic.Checkpoint;

/** Independently approved homepage review. Only bounded vertical scrolling is executable. */
final class OverviewScanRuntime {
    enum Stage { NEW, HANDOFF, MODEL_PREPARATION, UI_READ, IMAGE_READ, PUBLICATION, SCROLL,
        BUDGET_STOP, NO_MOVEMENT_STOP, NO_SCROLL_EVIDENCE, HOLD, CANCELED, CLEANUP_UNKNOWN, FINISHED }
    private final DormantCaptureConfirmationActivity activity;
    private final Object approvalOwner;
    private final RealCaptureClaim.ProcessEpoch epoch;
    private final OverviewScanIntent intent;
    private final BingNavigationSnapshot.Shape shape;
    private final HandlerThread loop=new HandlerThread("overview-review-owner");
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean canceled=new AtomicBoolean(),appendActive=new AtomicBoolean();
    private final AtomicBoolean workerStarted=new AtomicBoolean();
    private Handler owner;
    private CaptureSessionRuntime.AlignmentRoot root;
    private volatile OverviewScanIntent.FramePermit framePermit;
    private volatile OnceBackend backend;
    private BackendPorts.RevocationFence fence;
    private OverviewPngBinding binding;
    private OverviewFrameCapture capture;
    private OverviewFramePreview preview;
    private PreparedAlignmentModels models;
    private AndroidOverviewUiReader.Result uiRead;
    private ReviewBingNavigation metadata;
    private BooleanSupplier metadataCurrent;
    private RealCaptureClaim.Grant grant;
    private BingNavigationSnapshot.Ready baseline;
    private volatile Stage stage=Stage.NEW;
    private volatile OverviewUiProtocol.ScrollHint lastScrollHint=OverviewUiProtocol.ScrollHint.UNKNOWN;
    private final OverviewFailureDiagnostic diagnostic=new OverviewFailureDiagnostic();
    private volatile boolean workerReturned,closed,released,constructionUnknown,claimUncertain,bindingConstruction;
    private boolean notified;
    private int qualifiedFrames,regions,points,seenRoles,scrollDispatchAttempts,scrollExitedZero;
    private final int[] roleSeen=new int[6],roleAccepted=new int[6],roleHold=new int[6];
    private Path artifacts;
    private OverviewPngBinding.Completion previous;
    private OverviewUiRegions.Result previousStructure;
    private ReviewShizukuSwipeActor.Direction previousDirection;
    private boolean seekingTop=true;

    private OverviewScanRuntime(DormantCaptureConfirmationActivity activity,Object approvalOwner,
            RealCaptureClaim.ProcessEpoch epoch,OverviewScanIntent intent,BingNavigationSnapshot.Shape shape){
        this.activity=activity;this.approvalOwner=approvalOwner;this.epoch=epoch;this.intent=intent;this.shape=shape;
    }
    static OverviewScanRuntime start(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,Object approvalOwner){
        CaptureReleaseGate.requireConfigured();
        if(activity==null||click==null||approvalOwner==null||Looper.myLooper()!=Looper.getMainLooper()
                ||android.os.Build.VERSION.SDK_INT<34||android.os.Process.myUid()<10000
                ||!"com.aliothmoon.maafw.productrewardsworking.capturereview".equals(activity.getPackageName())
                ||!(activity.getPackageName()+":capture_once").equals(Application.getProcessName())||!activity.overviewSelected())
            throw new SecurityException("Independent visible overview confirmation required");
        var epoch=CaptureSessionRuntime.alignmentEpoch();long now=SystemClock.elapsedRealtime();
        var intent=VisibleCaptureAuthority.issueOverviewIntent(activity,click,epoch,approvalOwner,now);
        var displays=activity.getSystemService(DisplayManager.class);var display=displays==null?null:displays.getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=Display.STATE_ON)throw new SecurityException("Display unavailable");
        var windows=activity.createDisplayContext(display).getSystemService(WindowManager.class);
        if(windows==null)throw new SecurityException("Display geometry unavailable");
        var bounds=windows.getMaximumWindowMetrics().getBounds();
        if(bounds.left!=0||bounds.top!=0||bounds.width()<=0||bounds.height()<=bounds.width()
                ||bounds.width()>4096||bounds.height()>4096||(long)bounds.width()*bounds.height()>4L*1024*1024||display.getRotation()!=Surface.ROTATION_0)
            throw new SecurityException("Bounded portrait overview required");
        var run=new OverviewScanRuntime(activity,approvalOwner,epoch,intent,new BingNavigationSnapshot.Shape(bounds.width(),bounds.height(),Surface.ROTATION_0));
        run.root=CaptureSessionRuntime.retainAlignment(run,intent.deadline(),run::latchCancel,run::closeOnOwner,()->run.closed,run::postOwner);
        try{
            run.loop.start();run.owner=new Handler(run.loop.getLooper());run.root.arm();run.stage=Stage.HANDOFF;
            run.diagnostic.at(Checkpoint.HANDOFF);
            if(!run.main.post(run::handoff))run.cancel();
        }catch(RuntimeException unknown){run.diagnostic.failed();run.constructionUnknown=true;run.stage=Stage.CLEANUP_UNKNOWN;run.cancel();}
        return run;
    }
    private boolean postOwner(Runnable job){
        try{if(owner!=null&&owner.post(job))return true;}catch(RuntimeException unknown){/* Retain the graph. */}
        diagnostic.failed();constructionUnknown=true;latchCancel();return false;
    }
    private void handoff(){
        try{requireSession();activity.requestHandoff(this::requireSession,()->{
            try{requireSession();if(!postOwner(this::scan))cancel();}catch(RuntimeException rejected){diagnostic.failed();cancel();}
        },this::cancel);}catch(RuntimeException rejected){diagnostic.failed();cancel();}
    }
    private boolean sessionCurrent(){long now=SystemClock.elapsedRealtime();return !canceled.get()&&root!=null&&root.current()&&intent.current(now);}
    private void requireSession(){if(!sessionCurrent())throw new SecurityException("Overview expired or revoked");}
    private boolean hiddenCurrent(){return sessionCurrent()&&activity.rootHiddenScalar()&&activity.hiddenWithSoleTask();}
    private boolean frameCurrent(OverviewScanIntent.FramePermit exact){
        return exact!=null&&framePermit==exact&&exact.current(epoch,exact.owner,SystemClock.elapsedRealtime())&&hiddenCurrent();
    }
    private void sourceGate(){
        if(!hiddenCurrent()||metadataCurrent==null||!metadataCurrent.getAsBoolean()
                ||metadata==null||!ReviewBingNavigation.knownClean()||!metadata.authorityMatches(shape))
            throw new SecurityException("Overview source unavailable");
    }
    private void newMetadata(BooleanSupplier current)throws Exception{
        var installed=BingPackageMetadata.read(activity.getPackageManager());
        if(installed.state()!=BingIdentityEvidence.PackageState.METADATA_OBSERVED)throw new SecurityException("Bing metadata unavailable");
        int bing=activity.getPackageManager().getApplicationInfo("com.microsoft.bing",0).uid;
        int system=activity.getPackageManager().getApplicationInfo("com.android.systemui",0).uid;
        metadataCurrent=current;
        var permit=new UiImageAlignmentRuntime.MetadataPermit(new BingNavigationSnapshot.ReadOnlyGuard(android.os.Process.myUid(),
                activity.getTaskId(),activity.getComponentName().flattenToString()),current);
        metadata=ReviewBingNavigation.forUiImageAlignment(activity.getApplicationContext(),installed,bing,system,permit);
    }
    private BingNavigationSnapshot.Ready readSource(UiImageAlignmentRuntime.MetadataStage expected){
        sourceGate();var read=metadata.readAlignment(shape,expected);long now=SystemClock.elapsedRealtime();
        if(read==null||read.status()!=BingNavigationSnapshot.Status.READY||read.ready()==null
                ||read.ready().kind()!=BingNavigationSnapshot.Kind.TEMPLATE||!read.ready().fresh(now))return null;
        sourceGate();if(baseline==null)baseline=read.ready();
        return baseline.sameSource(read.ready())?read.ready():null;
    }
    private void scan(){
        if(!workerStarted.compareAndSet(false,true)||closed)return;
        try{
            requireSession();if(!hiddenCurrent())throw new SecurityException("Hidden overview required");
            diagnostic.at(Checkpoint.RUN_RECORD);
            OverviewScanScope.burnRun(activity.getNoBackupFilesDir().toPath());artifacts=createArtifactDirectory();
            while(sessionCurrent()){
                diagnostic.beginFrame(intent.frames()+1);
                resetReleasedFrame();
                Object frameOwner=new Object();framePermit=intent.claimFrame(frameOwner,SystemClock.elapsedRealtime());
                var exact=framePermit;
                if(!main.postDelayed(()->{if(framePermit==exact&&!workerReturned&&(!nativeResourcesClosed()||appendActive.get()))cancel();},
                        Math.max(0,exact.deadline-SystemClock.elapsedRealtime())))throw new SecurityException("Frame deadline unavailable");
                grant=RealCaptureClaim.visible(VisibleCaptureAuthority.issueOverviewFrame(exact,SystemClock.elapsedRealtime()),epoch,frameOwner,SystemClock.elapsedRealtime());
                fence=new BackendPorts.RevocationFence();stage=Stage.MODEL_PREPARATION;
                diagnostic.at(Checkpoint.MODEL_PREPARATION);
                models=PreparedAlignmentModels.create(frameOwner,MaaOnceModelStore::open);
                if(!models.prepare(frameOwner,activity.getNoBackupFilesDir().toPath(),
                        index->activity.getAssets().open("bundle/model/ocr/"+MaaOnceModelPolicy.name(index)),()->!frameCurrent(exact)))
                    throw new SecurityException("Overview models unavailable");
                newMetadata(()->frameCurrent(exact));String nonce=UUID.randomUUID().toString().replace("-","");
                BingNavigationSnapshot.Ready[] before=new BingNavigationSnapshot.Ready[1];long[] beforeAt={-1};stage=Stage.UI_READ;
                diagnostic.at(Checkpoint.UI_READ);
                uiRead=AndroidOverviewUiReader.read(activity.getApplicationContext(),nonce,
                        Math.min(exact.deadline,SystemClock.elapsedRealtime()+9000),()->frameCurrent(exact),()->{
                            before[0]=readSource(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE);if(before[0]==null)return false;
                            OverviewScanScope.burnUi(activity.getNoBackupFilesDir().toPath(),exact.index);
                            beforeAt[0]=SystemClock.elapsedRealtime();sourceGate();return true;
                        },exact);
                if(uiRead==null||!uiRead.knownClean()||uiRead.protocolReport()==null||!uiRead.protocolReport().qualified())
                    throw new SecurityException("Overview UI observation unqualified");
                lastScrollHint=uiRead.protocolReport().scrollHint();
                diagnostic.at(Checkpoint.SOURCE_UI_AFTER);
                var afterUi=readSource(UiImageAlignmentRuntime.MetadataStage.UI_AFTER);
                if(afterUi==null)diagnostic.failed(Checkpoint.SOURCE_UI_AFTER);
                diagnostic.at(Checkpoint.SOURCE_IMAGE_BEFORE);
                var imageBefore=readSource(UiImageAlignmentRuntime.MetadataStage.IMAGE_BEFORE);long lower=SystemClock.elapsedRealtime();
                if(imageBefore==null)diagnostic.failed(Checkpoint.SOURCE_IMAGE_BEFORE);
                diagnostic.at(Checkpoint.FRAME_BINDING);
                preview=new OverviewFramePreview(shape.width(),shape.height());
                capture=OverviewFrameCapture.begin(exact.index,frameOwner,approvalOwner,epoch,nonce,uiRead.protocolReport(),
                        before[0],beforeAt[0],afterUi,imageBefore,lower,exact.deadline,
                        ()->!frameCurrent(exact),preview);
                if(capture==null)throw new SecurityException("Overview source bracket rejected");
                stage=Stage.IMAGE_READ;sourceGate();bindingConstruction=true;
                diagnostic.at(Checkpoint.IMAGE_BINDING);
                binding=OverviewPngBinding.bind(activity.getApplicationContext(),this::consumeFrame,this::sourceGate,fence,Runnable::run,capture,models);
                bindingConstruction=false;backend=binding.backend();diagnostic.at(Checkpoint.IMAGE_BACKEND);var result=backend.start();
                diagnostic.nativeOutcome(result);binding.recordAuditDiagnostic(diagnostic,result.owner());
                if(result.phase()!=OnceBackend.Phase.COMPLETED||result.failure()!=OnceBackend.Failure.NONE
                        ||result.cancellation()!=OnceBackend.Cancellation.NOT_REQUESTED||!nativeResourcesClosed())
                    throw new SecurityException("Overview native outcome unconfirmed");
                diagnostic.at(Checkpoint.SOURCE_AFTER);
                var after=readSource(UiImageAlignmentRuntime.MetadataStage.IMAGE_AFTER);
                if(after==null)diagnostic.failed(Checkpoint.SOURCE_AFTER);
                diagnostic.at(Checkpoint.SEAL);
                var completion=binding.takeCompletion(after,SystemClock.elapsedRealtime());
                if(completion==null)throw new SecurityException("Overview same-frame proof unavailable");
                stage=Stage.PUBLICATION;diagnostic.at(Checkpoint.PUBLICATION);publishFrame(completion,exact);
                diagnostic.at(Checkpoint.FRAME_FINISH);
                preview.close();if(!preview.knownReleased())throw new SecurityException("Overview preview retained");
                intent.frameFinished(exact,nativeResourcesClosed(),true,SystemClock.elapsedRealtime());
                var structure=uiRead.protocolReport().overview();var hint=uiRead.protocolReport().scrollHint();
                boolean stationary=previous!=null&&completion.samePixels(previous)&&structure.equals(previousStructure);
                if(seekingTop&&(hint==OverviewUiProtocol.ScrollHint.DECLARED_FORWARD
                        ||stationary&&previousDirection==ReviewShizukuSwipeActor.Direction.DOWN)){
                    intent.beginDownwardScan(exact,SystemClock.elapsedRealtime());seekingTop=false;
                }else if(!seekingTop&&stationary&&previousDirection==ReviewShizukuSwipeActor.Direction.UP){stage=Stage.NO_MOVEMENT_STOP;break;}
                if(!intent.budgetRemaining()){stage=Stage.BUDGET_STOP;break;}
                var direction=intent.direction();
                boolean declared=direction==ReviewShizukuSwipeActor.Direction.UP
                        ?hint==OverviewUiProtocol.ScrollHint.DECLARED_FORWARD||hint==OverviewUiProtocol.ScrollHint.DECLARED_BOTH
                        :hint==OverviewUiProtocol.ScrollHint.DECLARED_BACKWARD||hint==OverviewUiProtocol.ScrollHint.DECLARED_BOTH;
                if(!declared){stage=Stage.NO_SCROLL_EVIDENCE;break;}
                previous=completion;previousStructure=structure;previousDirection=direction;
                scroll(exact);
            }
            if(stage==Stage.SCROLL||stage==Stage.PUBLICATION)stage=canceled.get()?Stage.CANCELED:Stage.BUDGET_STOP;
        }catch(Exception failed){diagnostic.failed();if(failed instanceof InterruptedException)Thread.currentThread().interrupt();stage=canceled.get()?Stage.CANCELED:Stage.HOLD;}
        catch(Error unknown){diagnostic.failed();constructionUnknown=true;stage=Stage.CLEANUP_UNKNOWN;}
        finally{
            workerReturned=true;
            diagnostic.at(Checkpoint.CLEANUP);
            try{if(models!=null&&framePermit!=null)models.closeBeforeAdoption(framePermit.owner);}catch(RuntimeException|Error unknown){diagnostic.failed();constructionUnknown=true;}
            try{if(preview!=null&&!appendActive.get())preview.close();}catch(RuntimeException|Error unknown){diagnostic.failed();constructionUnknown=true;}
            if(!allResourcesClosed()){diagnostic.failed();stage=Stage.CLEANUP_UNKNOWN;}
            closeOnOwner();
        }
    }
    private BackendPorts.ClaimReceipt consumeFrame(OnceBackend.Owner owner){
        if(owner==null||framePermit==null||capture==null||!frameCurrent(framePermit)||!capture.current(SystemClock.elapsedRealtime()))
            return new BackendPorts.ClaimReceipt(owner,BackendPorts.ClaimDecision.DENIED);
        var outcome=RealCaptureClaim.consume(grant,epoch,framePermit.owner,SystemClock.elapsedRealtime(),
                OverviewScanScope.frameStore(activity.getNoBackupFilesDir().toPath(),framePermit.index));
        claimUncertain=outcome==RealCaptureClaim.Outcome.UNCERTAIN;
        return new BackendPorts.ClaimReceipt(owner,switch(outcome){case CONSUMED->BackendPorts.ClaimDecision.CLAIMED;case REJECTED->BackendPorts.ClaimDecision.DUPLICATE;case UNCERTAIN->BackendPorts.ClaimDecision.UNCERTAIN;});
    }
    private void publishFrame(OverviewPngBinding.Completion completion,OverviewScanIntent.FramePermit exact)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean published=new AtomicBoolean();appendActive.set(true);
        if(!main.post(()->{
            try{
                OverviewFrameResult[] savedResult={null};OverviewReviewArtifacts.Saved[] savedArtifact={null};
                boolean outer=activity.overviewPublish(this,()->{
                var result=completion.publish(exact.owner,approvalOwner,epoch,SystemClock.elapsedRealtime(),
                        nativeResourcesClosed()&&frameCurrent(exact),activity.rootHiddenScalar()&&activity.hiddenWithSoleTask(),
                        metadata!=null&&metadata.alignmentPublicationAuthorityMatches(shape));
                if(result==null){diagnostic.failed(Checkpoint.PUBLICATION);return;}
                diagnostic.at(Checkpoint.PUBLICATION_WRITE);
                try{savedArtifact[0]=OverviewReviewArtifacts.write(artifacts,exact.index,result,preview);
                    savedResult[0]=result;
                }catch(Exception failed){diagnostic.failed();/* Fixed HOLD, no partial artifact grants a point or further scroll. */}
                });
                if(!outer)diagnostic.failed(Checkpoint.PUBLICATION);
                diagnostic.at(Checkpoint.PUBLICATION_COMMIT);
                // A rejected outer fence leaves partial files local and unqualified. They are not
                // counted or delivered: only the contiguous qualified frame prefix is reviewable.
                published.set(OverviewPublicationCommit.commit(savedResult[0]!=null&&savedArtifact[0]!=null,outer,
                        ()->frameCurrent(exact)&&nativeResourcesClosed()
                            &&capture.publicationCurrent(SystemClock.elapsedRealtime())
                            &&metadata.alignmentPublicationAuthorityMatches(shape),()->{
                            regions+=savedArtifact[0].regions();points+=savedArtifact[0].points();qualifiedFrames++;
                            for(var item:savedResult[0].items()){
                                int role=item.role().ordinal();seenRoles|=1<<role;roleSeen[role]++;
                                if(item.status()==OverviewFrameResult.Status.HOLD)roleHold[role]++;else roleAccepted[role]++;
                            }
                        }));
                if(!published.get())diagnostic.failed(Checkpoint.PUBLICATION_COMMIT);
            }finally{appendActive.set(false);done.countDown();}
        })){appendActive.set(false);throw new SecurityException("Overview publication queue unavailable");}
        long left=Math.min(10000,exact.deadline-SystemClock.elapsedRealtime());
        if(left<=0||!done.await(left,TimeUnit.MILLISECONDS)){constructionUnknown=true;throw new SecurityException("Overview publication unresolved");}
        if(!published.get())throw new SecurityException("Overview publication rejected");
    }
    private void scroll(OverviewScanIntent.FramePermit exact)throws Exception{
        diagnostic.at(Checkpoint.SCROLL_SOURCE);
        stage=Stage.SCROLL;newMetadata(this::hiddenCurrent);var before=readSource(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE);
        if(before==null)throw new SecurityException("Fresh overview scroll source required");
        var permit=intent.claimScroll(exact,SystemClock.elapsedRealtime());
        var actor=ReviewShizukuSwipeActor.forOverview(activity.getApplicationContext(),permit);
        var target=new ReviewShizukuSwipeActor.SwipeTarget(shape.width(),shape.height(),shape.rotation(),permit.direction);
        diagnostic.at(Checkpoint.SCROLL_DISPATCH);
        var result=actor.swipe(target,new ReviewShizukuSwipeActor.DispatchGate(){
            @Override public boolean consume(ReviewShizukuSwipeActor.SwipeTarget t)throws Exception{
                if(!target.equals(t)||!hiddenCurrent()||!nativeResourcesClosed()||!preview.knownReleased()||!permit.consume(SystemClock.elapsedRealtime()))return false;
                OverviewScanScope.burnScroll(activity.getNoBackupFilesDir().toPath(),permit.index);return hiddenCurrent();
            }
            @Override public boolean stillValid(ReviewShizukuSwipeActor.SwipeTarget t){
                var fresh=readSource(UiImageAlignmentRuntime.MetadataStage.UI_AFTER);
                return target.equals(t)&&fresh!=null&&before.sameSource(fresh)&&permit.current(SystemClock.elapsedRealtime())&&hiddenCurrent();
            }
        });
        if(result.invocationAttempted())scrollDispatchAttempts++;
        if(result.commandExitZero())scrollExitedZero++;
        if(!actor.knownClean())constructionUnknown=true;
        diagnostic.at(Checkpoint.SCROLL_RESULT);
        intent.scrollFinished(permit,result,SystemClock.elapsedRealtime());
    }
    private void resetReleasedFrame(){
        if(framePermit!=null&&!allResourcesClosed())throw new SecurityException("Previous frame retained");
        backend=null;binding=null;capture=null;preview=null;models=null;uiRead=null;metadata=null;metadataCurrent=null;grant=null;fence=null;
    }
    private boolean nativeResourcesClosed(){
        return (models==null||models.knownReleased())&&UiImageAlignmentRuntime.resourcesClosed(
                AndroidOverviewUiReader.processKnownClean()&&AndroidUiStructureReader.processKnownClean()&&(uiRead==null||uiRead.knownClean()),
                ReviewBingNavigation.knownClean(),constructionUnknown||bindingConstruction,claimUncertain,
                backend!=null,backend==null?null:backend.snapshot());
    }
    private boolean allResourcesClosed(){return nativeResourcesClosed()&&!appendActive.get()&&(preview==null||preview.knownReleased());}
    private Path createArtifactDirectory()throws Exception{
        Path cache=activity.getCacheDir().toPath();Path parent=cache.resolve("rewards-overview-v7");
        try{Files.createDirectory(parent);}catch(FileAlreadyExistsException exists){/* Verify exact directory. */}
        for(Path p:new Path[]{cache,parent}){var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.fileKey()==null)throw new SecurityException("Local review directory unavailable");}
        return Files.createDirectory(parent.resolve(UUID.randomUUID().toString()));
    }
    private void latchCancel(){canceled.set(true);intent.cancel();activity.clearPendingHandoff();
        var f=fence;if(f!=null)f.revoke();var exact=backend;if(exact!=null)exact.cancel(exact.snapshot().owner());}
    void cancel(){if(root!=null)root.request();else latchCancel();}
    private void closeOnOwner(){
        if(!workerStarted.get()&&canceled.get()){workerReturned=true;stage=Stage.CANCELED;}
        if(closed||!workerReturned)return;
        if(!allResourcesClosed()){diagnostic.failed();stage=Stage.CLEANUP_UNKNOWN;notifyFinished();return;}
        closed=true;if(root!=null)root.releaseIfClosed();released=true;loop.quitSafely();notifyFinished();
    }
    private void notifyFinished(){
        if(notified)return;notified=true;String details=fixedSummary();
        activity.runOnUiThread(()->activity.overviewFinished(this,details));
    }
    boolean finishedAndReleased(){return workerReturned&&closed&&released;}
    String fixedSummary(){return "profile=OVERVIEW_V7 stage="+stage+" stopReason="+stage+" terminal="+workerReturned+" released="+released
            +" cleanup="+(allResourcesClosed()?"CONFIRMED":"UNKNOWN")+" frames="+intent.frames()+" qualifiedFrames="+qualifiedFrames
            +" scrollSlotsSpent="+intent.swipes()+" scrollDispatchAttempts="+scrollDispatchAttempts+" swipes="+scrollExitedZero
            +" maxFrames=12 maxSwipes=11 taskTaps=0 back=0 textEntry=0"
            +" regions="+regions+" mockPoints="+points+" rolesObserved="+Integer.bitCount(seenRoles)
            +" countUnit=FRAME_ITEM_OBSERVATIONS_NOT_UNIQUE_TASKS"+roleSummary()
            +" "+diagnostic.summary()
            +" lastScrollHint="+lastScrollHint
            +" coverageIncomplete=true actionAuthority=false counterAuthority=false taskCompletion=NOT_EVALUATED"
            +" rawDataUploaded=false artifacts="+(artifacts==null?"NONE":artifacts.getFileName().toString());}
    private String roleSummary(){
        StringBuilder result=new StringBuilder();
        for(var role:OverviewUiRegions.Role.values()){
            int i=role.ordinal();result.append(' ').append(role).append("Seen=").append(roleSeen[i]);
            result.append(' ').append(role).append("Accepted=").append(roleAccepted[i]);
            result.append(' ').append(role).append("Hold=").append(roleHold[i]);
        }
        return result.toString();
    }
}
