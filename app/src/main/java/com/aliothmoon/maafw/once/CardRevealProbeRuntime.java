package com.aliothmoon.maafw.once;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import com.aliothmoon.maafw.rewards.DailyCheckInRegion;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One independent follow-up: a UI-clipped start must move before one direct region capture. */
final class CardRevealProbeRuntime {
    enum Stage { NEW,HANDOFF,RUN_RECORD,UI_READ,SOURCE_UI_AFTER,TARGET,SCROLL_SOURCE,SCROLL,
        START_NOT_CLIPPED,NO_SCROLL_MOVEMENT,STABILITY_WAIT,IMAGE_BEFORE,REGION_CAPTURE,IMAGE_AFTER,BOUNDARY,PUBLICATION,
        COMPLETE,HOLD,NO_DIRECTION,TOO_LARGE,BUDGET_STOP,CANCELED,CLEANUP_UNKNOWN }
    private final DormantCaptureConfirmationActivity activity;
    private final RealCaptureClaim.ProcessEpoch epoch;
    private final CardRevealProbeIntent intent;
    private final BingNavigationSnapshot.Shape shape;
    private final HandlerThread loop=new HandlerThread("card-reveal-owner");
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean canceled=new AtomicBoolean(),appendActive=new AtomicBoolean(),workerStarted=new AtomicBoolean();
    private Handler owner;
    private CaptureSessionRuntime.AlignmentRoot root;
    private volatile CardRevealProbeIntent.UiPermit observation;
    private AndroidOverviewUiReader.Result uiRead;
    private AndroidCardRegionCapture.Result cropRead;
    private ReviewBingNavigation metadata;
    private BooleanSupplier metadataCurrent;
    private BingNavigationSnapshot.Ready baseline;
    private volatile Stage stage=Stage.NEW,failedAt=Stage.NEW;
    private volatile String uiStatus="NONE",cropStatus="NONE",helperStatus="NONE",boundaryStatus="NONE";
    private volatile OverviewUiProtocol.ScrollHint scrollHint=OverviewUiProtocol.ScrollHint.UNKNOWN;
    private volatile boolean workerReturned,closed,released,constructionUnknown,boundaryConfirmed,initialClipped;
    private boolean notified;
    private int dispatches,swipes,qualifiedCrops,savedImages;
    private long firstScrollRequest=-1,stableAt=-1,revealMs=-1,cropMs=-1,boundaryMs=-1;
    private Path artifacts;
    private Box initialCoarse;

    private CardRevealProbeRuntime(DormantCaptureConfirmationActivity activity,RealCaptureClaim.ProcessEpoch epoch,
            CardRevealProbeIntent intent,BingNavigationSnapshot.Shape shape){
        this.activity=activity;this.epoch=epoch;this.intent=intent;this.shape=shape;
    }
    static CardRevealProbeRuntime start(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,Object approvalOwner){
        CaptureReleaseGate.requireConfigured();
        if(activity==null||click==null||approvalOwner==null||Looper.myLooper()!=Looper.getMainLooper()
                ||android.os.Build.VERSION.SDK_INT<34||android.os.Process.myUid()<10000
                ||!"com.aliothmoon.maafw.productrewardsworking.capturereview".equals(activity.getPackageName())
                ||!(activity.getPackageName()+":capture_once").equals(Application.getProcessName())||!activity.cardRevealSelected())
            throw new SecurityException("Independent visible card reveal confirmation required");
        var epoch=CaptureSessionRuntime.alignmentEpoch();long now=SystemClock.elapsedRealtime();
        var intent=VisibleCaptureAuthority.issueCardRevealIntent(activity,click,epoch,approvalOwner,now);
        var displays=activity.getSystemService(DisplayManager.class);var display=displays==null?null:displays.getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null||display.getState()!=Display.STATE_ON)throw new SecurityException("Display unavailable");
        var windows=activity.createDisplayContext(display).getSystemService(WindowManager.class);
        if(windows==null)throw new SecurityException("Display geometry unavailable");
        var bounds=windows.getMaximumWindowMetrics().getBounds();
        if(bounds.left!=0||bounds.top!=0||bounds.width()<=0||bounds.height()<=bounds.width()
                ||bounds.width()>4096||bounds.height()>4096||(long)bounds.width()*bounds.height()>4L*1024*1024||display.getRotation()!=Surface.ROTATION_0)
            throw new SecurityException("Bounded portrait probe required");
        if(bounds.width()!=1440||bounds.height()!=2560)
            throw new SecurityException("Reviewed follow-up display geometry required");
        var run=new CardRevealProbeRuntime(activity,epoch,intent,new BingNavigationSnapshot.Shape(bounds.width(),bounds.height(),0));
        run.root=CaptureSessionRuntime.retainAlignment(run,intent.deadline(),run::latchCancel,run::closeOnOwner,()->run.closed,run::postOwner);
        try{
            run.loop.start();run.owner=new Handler(run.loop.getLooper());run.root.arm();run.stage=Stage.HANDOFF;
            if(!run.main.post(run::handoff))run.cancel();
        }catch(RuntimeException unknown){run.constructionUnknown=true;run.stage=Stage.CLEANUP_UNKNOWN;run.cancel();}
        return run;
    }
    private boolean postOwner(Runnable job){
        try{if(owner!=null&&owner.post(job))return true;}catch(RuntimeException unknown){/* Retain graph. */}
        constructionUnknown=true;latchCancel();return false;
    }
    private void handoff(){
        try{requireSession();activity.requestHandoff(this::requireSession,()->{
            try{requireSession();if(!postOwner(this::run))cancel();}catch(RuntimeException rejected){cancel();}
        },this::cancel);}catch(RuntimeException rejected){cancel();}
    }
    private boolean sessionCurrent(){return !canceled.get()&&root!=null&&root.current()&&intent.current(SystemClock.elapsedRealtime());}
    private void requireSession(){if(!sessionCurrent())throw new SecurityException("Card reveal expired or revoked");}
    private boolean hiddenCurrent(){return sessionCurrent()&&activity.rootHiddenScalar()&&activity.hiddenWithSoleTask();}
    private boolean observationCurrent(CardRevealProbeIntent.UiPermit exact){
        return exact!=null&&observation==exact&&exact.current(epoch,exact.owner,SystemClock.elapsedRealtime())&&hiddenCurrent();
    }
    private void sourceGate(){
        if(!hiddenCurrent()||metadataCurrent==null||!metadataCurrent.getAsBoolean()||metadata==null
                ||!ReviewBingNavigation.knownClean()||!metadata.authorityMatches(shape))
            throw new SecurityException("Card reveal source unavailable");
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
        sourceGate();if(!read.ready().fresh(SystemClock.elapsedRealtime()))return null;
        if(baseline==null)baseline=read.ready();
        return baseline.sameSource(read.ready())?read.ready():null;
    }
    private void run(){
        if(!workerStarted.compareAndSet(false,true)||closed)return;
        try{
            requireSession();if(!hiddenCurrent())throw new SecurityException("Hidden card reveal required");
            stage=Stage.RUN_RECORD;CardRevealProbeScope.burnRun(activity.getNoBackupFilesDir().toPath());
            artifacts=createArtifactDirectory();CardRevealProbeTarget.Selection previous=null;
            while(sessionCurrent()&&intent.uiCount()<CardRevealProbeScope.MAX_UI_OBSERVATIONS){
                if(!nativeResourcesClosed())throw new SecurityException("Previous observation retained");
                observation=intent.claimUi(new Object(),SystemClock.elapsedRealtime());var exact=observation;
                newMetadata(()->observationCurrent(exact));String nonce=UUID.randomUUID().toString().replace("-","");
                BingNavigationSnapshot.Ready[] before={null};long[] beforeAt={-1};stage=Stage.UI_READ;
                uiRead=AndroidOverviewUiReader.readCardReveal(activity.getApplicationContext(),nonce,exact.deadline,
                        ()->observationCurrent(exact),()->{
                            before[0]=readSource(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE);if(before[0]==null)return false;
                            CardRevealProbeScope.burnUi(activity.getNoBackupFilesDir().toPath(),exact.index);
                            beforeAt[0]=SystemClock.elapsedRealtime();sourceGate();return true;
                        },exact);
                uiStatus=uiRead==null?"NULL":uiRead.fixedStatus().name();
                if(uiRead==null||!uiRead.knownClean()||uiRead.protocolReport()==null||!uiRead.protocolReport().qualified())
                    throw new SecurityException("UI observation unqualified");
                var report=uiRead.protocolReport();scrollHint=report.scrollHint();stage=Stage.SOURCE_UI_AFTER;
                var afterUi=readSource(UiImageAlignmentRuntime.MetadataStage.UI_AFTER);
                if(!CardRevealProbeSource.ui(report,before[0],beforeAt[0],afterUi,shape,SystemClock.elapsedRealtime()))
                    throw new SecurityException("UI source bracket rejected");
                intent.finishUi(exact,true,true,SystemClock.elapsedRealtime());stage=Stage.TARGET;
                var selected=CardRevealProbeTarget.select(report,shape.width(),shape.height());
                if(exact.index==1){
                    initialClipped=CardRevealProbeTarget.initialClipped(report,shape.width(),shape.height());
                    if(!initialClipped){stage=Stage.START_NOT_CLIPPED;break;}
                    for(var region:report.overview().regions())if(region.role()==OverviewUiRegions.Role.CHECK_IN)initialCoarse=region.coarse();
                }
                if(selected.state()==CardRevealProbeTarget.State.SCROLL){
                    if(intent.swipeCount()>=CardRevealProbeScope.MAX_SWIPES){stage=Stage.BUDGET_STOP;break;}
                    scroll(exact,selected.direction());previous=null;settle();continue;
                }
                if(selected.state()!=CardRevealProbeTarget.State.CANDIDATE){
                    stage=selected.state()==CardRevealProbeTarget.State.NO_DIRECTION?Stage.NO_DIRECTION
                            :selected.state()==CardRevealProbeTarget.State.TOO_LARGE?Stage.TOO_LARGE:Stage.HOLD;break;
                }
                if(!selected.sameCandidate(previous)){previous=selected;stage=Stage.STABILITY_WAIT;settle();continue;}
                if(!initialClipped||swipes<1||dispatches<1||firstScrollRequest<0||selected.region().coarse().equals(initialCoarse)){stage=Stage.NO_SCROLL_MOVEMENT;break;}
                stableAt=SystemClock.elapsedRealtime();revealMs=stableAt-firstScrollRequest;
                capture(exact,report,before[0],beforeAt[0],afterUi,selected);break;
            }
            if(stage==Stage.UI_READ||stage==Stage.SCROLL||stage==Stage.STABILITY_WAIT)stage=Stage.BUDGET_STOP;
        }catch(Exception failed){failedAt=stage;if(failed instanceof InterruptedException)Thread.currentThread().interrupt();stage=canceled.get()?Stage.CANCELED:Stage.HOLD;}
        catch(Error unknown){failedAt=stage;constructionUnknown=true;stage=Stage.CLEANUP_UNKNOWN;}
        finally{workerReturned=true;if(!allResourcesClosed())stage=Stage.CLEANUP_UNKNOWN;closeOnOwner();}
    }
    private void settle()throws InterruptedException{Thread.sleep(250);requireSession();}
    private void scroll(CardRevealProbeIntent.UiPermit exact,ReviewShizukuSwipeActor.Direction direction)throws Exception{
        stage=Stage.SCROLL_SOURCE;newMetadata(this::hiddenCurrent);var before=readSource(UiImageAlignmentRuntime.MetadataStage.UI_BEFORE);
        if(before==null)throw new SecurityException("Fresh scroll source required");
        var permit=intent.claimScroll(exact,direction,SystemClock.elapsedRealtime());
        var actor=ReviewShizukuSwipeActor.forCardReveal(activity.getApplicationContext(),permit);
        var target=new ReviewShizukuSwipeActor.SwipeTarget(shape.width(),shape.height(),shape.rotation(),direction);
        long[] requested={-1};stage=Stage.SCROLL;
        var result=actor.swipe(target,new ReviewShizukuSwipeActor.DispatchGate(){
            @Override public boolean consume(ReviewShizukuSwipeActor.SwipeTarget t)throws Exception{
                if(!target.equals(t)||!hiddenCurrent()||!nativeResourcesClosed()||!permit.consume(SystemClock.elapsedRealtime()))return false;
                CardRevealProbeScope.burnScroll(activity.getNoBackupFilesDir().toPath(),permit.index);return hiddenCurrent();
            }
            @Override public boolean stillValid(ReviewShizukuSwipeActor.SwipeTarget t){
                var fresh=readSource(UiImageAlignmentRuntime.MetadataStage.UI_AFTER);
                boolean allowed=target.equals(t)&&fresh!=null&&before.sameSource(fresh)&&hiddenCurrent();
                long now=SystemClock.elapsedRealtime();
                allowed=allowed&&permit.current(now)&&before.fresh(now)&&fresh.fresh(now);
                if(allowed)requested[0]=now;return allowed;
            }
        });
        if(result.invocationAttempted()){dispatches++;if(firstScrollRequest<0)firstScrollRequest=requested[0];}
        if(result.commandExitZero())swipes++;
        if(!actor.knownClean())constructionUnknown=true;
        intent.finishScroll(permit,result,actor.knownClean(),SystemClock.elapsedRealtime());
    }
    private void capture(CardRevealProbeIntent.UiPermit exact,OverviewUiProtocol.Report report,
            BingNavigationSnapshot.Ready before,long beforeAt,BingNavigationSnapshot.Ready afterUi,
            CardRevealProbeTarget.Selection selected)throws Exception{
        if(!initialClipped||swipes<1||dispatches<1||firstScrollRequest<0||revealMs<=0)throw new SecurityException("Actual reveal scroll required");
        stage=Stage.IMAGE_BEFORE;var imageBefore=readSource(UiImageAlignmentRuntime.MetadataStage.IMAGE_BEFORE);
        long lower=SystemClock.elapsedRealtime();
        BooleanSupplier fresh=()->observationCurrent(exact)&&CardRevealProbeSource.beforeCrop(report,before,beforeAt,afterUi,
                imageBefore,shape,lower,exact.deadline,SystemClock.elapsedRealtime());
        if(!fresh.getAsBoolean())throw new SecurityException("Crop source bracket rejected");
        stage=Stage.REGION_CAPTURE;long started=SystemClock.elapsedRealtime();
        cropRead=AndroidCardRegionCapture.capture(activity.getApplicationContext(),UUID.randomUUID().toString().replace("-",""),
                exact.deadline,selected.crop(),shape.width(),shape.height(),shape.rotation(),exact,
                ()->observationCurrent(exact),new AndroidCardRegionCapture.Gate(){
                    @Override public boolean consume()throws Exception{
                        requireCurrent();if(!intent.claimCrop(exact,SystemClock.elapsedRealtime()))return false;
                        CardRevealProbeScope.burnCrop(activity.getNoBackupFilesDir().toPath());sourceGate();return fresh.getAsBoolean();
                    }
                    @Override public void requireCurrent(){sourceGate();if(!fresh.getAsBoolean())throw new SecurityException("Crop source expired");}
                });
        cropMs=SystemClock.elapsedRealtime()-started;cropStatus=cropRead.status().name();
        helperStatus=cropRead.helperStatus()==null?"NONE":cropRead.helperStatus().name();
        byte[] png=cropRead.png();boolean transferred=false;
        try{
            if(!cropRead.knownClean()||cropRead.status()!=AndroidCardRegionCapture.FixedStatus.CAPTURED||png==null)
                throw new SecurityException("Direct crop unavailable");
            // Close the source bracket before decoding or observing the cropped pixels.
            stage=Stage.IMAGE_AFTER;var imageAfter=readSource(UiImageAlignmentRuntime.MetadataStage.IMAGE_AFTER);
            BooleanSupplier qualified=()->observationCurrent(exact)&&CardRevealProbeSource.afterCrop(report,before,beforeAt,afterUi,
                    imageBefore,imageAfter,shape,lower,cropRead.helperStartedMs(),cropRead.helperFinishedMs(),exact.deadline,SystemClock.elapsedRealtime());
            if(!qualified.getAsBoolean())throw new SecurityException("Crop source-after rejected");
            stage=Stage.BOUNDARY;long checking=SystemClock.elapsedRealtime();
            boundaryConfirmed=checkBoundary(png,selected,()->!qualified.getAsBoolean());boundaryMs=SystemClock.elapsedRealtime()-checking;
            stage=Stage.PUBLICATION;
            // publish owns and wipes the byte array even if its queued callback outlives the wait.
            transferred=true;publish(png,selected,qualified);
            stage=boundaryConfirmed?Stage.COMPLETE:Stage.HOLD;
        }finally{if(!transferred&&png!=null)Arrays.fill(png,(byte)0);}
    }
    private boolean checkBoundary(byte[] png,CardRevealProbeTarget.Selection selected,BooleanSupplier stop){
        Bitmap bitmap=null;int[] argb=null;byte[] gray=null;
        try{
            var roi=selected.crop();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(png,0,png.length,bounds);
            if(bounds.outWidth!=roi.width()||bounds.outHeight!=roi.height())throw new SecurityException("Crop dimensions changed");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inMutable=true;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            bitmap=BitmapFactory.decodeByteArray(png,0,png.length,options);
            if(bitmap==null||bitmap.getWidth()!=roi.width()||bitmap.getHeight()!=roi.height())throw new SecurityException("Crop decode rejected");
            argb=new int[roi.width()*roi.height()];gray=new byte[argb.length];bitmap.getPixels(argb,0,roi.width(),0,0,roi.width(),roi.height());
            for(int i=0;i<argb.length;i++){int p=argb[i];gray[i]=(byte)((77*((p>>>16)&255)+150*((p>>>8)&255)+29*(p&255)+128)>>>8);}
            var key=new RewardRegionGate.Key(RewardRegionGate.Source.REAL_DISPLAY_NATIVE,"card-reveal-v2",1,"CHECK_IN");
            var geometry=DailyCheckInRegion.observeGray8(key,gray,roi.width(),roi.height(),stop);boundaryStatus=geometry.status().name();
            var heading=selected.region().heading();Box local=new Box(heading.left()-roi.left(),heading.top()-roi.top(),heading.right()-roi.left(),heading.bottom()-roi.top());
            int count=0;for(var surface:geometry.surfaces())if(surface.complete()&&surface.box().contains(local))count++;
            return geometry.status()==DailyCheckInRegion.Status.OBSERVED&&count==1&&!stop.getAsBoolean();
        }finally{
            if(argb!=null)Arrays.fill(argb,0);if(gray!=null)Arrays.fill(gray,(byte)0);
            if(bitmap!=null){
                try{if(bitmap.isMutable())bitmap.eraseColor(0);}catch(RuntimeException|Error unknown){constructionUnknown=true;}
                try{bitmap.recycle();}catch(RuntimeException|Error unknown){constructionUnknown=true;}
            }
        }
    }
    private void publish(byte[] png,CardRevealProbeTarget.Selection selected,BooleanSupplier qualified)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean published=new AtomicBoolean();appendActive.set(true);
        if(!main.post(()->{
            try{
                AtomicBoolean written=new AtomicBoolean();
                boolean outer=activity.cardRevealPublish(this,()->{
                    if(!qualified.getAsBoolean()||!nativeResourcesClosed()||!metadata.alignmentPublicationAuthorityMatches(shape))return;
                    try{writeFile("CHECK_IN.png",png);writeFile("RESULT.json",manifest(selected).getBytes(StandardCharsets.UTF_8));written.set(true);}
                    catch(Exception failed){/* Partial files remain local and unqualified. */}
                });
                if(outer&&written.get()&&qualified.getAsBoolean()&&nativeResourcesClosed()&&metadata.alignmentPublicationAuthorityMatches(shape)){
                    qualifiedCrops=1;savedImages=1;published.set(true);
                }
            }finally{Arrays.fill(png,(byte)0);appendActive.set(false);done.countDown();if(workerReturned)postOwner(this::closeOnOwner);}
        })){appendActive.set(false);Arrays.fill(png,(byte)0);throw new SecurityException("Crop publication queue unavailable");}
        long left=Math.min(10000,intent.deadline()-SystemClock.elapsedRealtime());
        if(left<=0||!done.await(left,TimeUnit.MILLISECONDS)){constructionUnknown=true;throw new SecurityException("Crop publication unresolved");}
        if(!published.get())throw new SecurityException("Crop publication rejected");
    }
    private String manifest(CardRevealProbeTarget.Selection selected){
        Box b=selected.crop();return "{\"profile\":\"CARD_REVEAL_V2\",\"region\":\"CHECK_IN\",\"roi\":["+b.left()+","+b.top()+","+b.right()+","+b.bottom()
                +"],\"width\":"+b.width()+",\"height\":"+b.height()+",\"timingLabel\":\"UI_CONFIRMED_FULL_REVEAL\",\"revealMs\":"+revealMs
                +",\"cropMs\":"+cropMs+",\"boundaryMs\":"+boundaryMs+",\"fullCardBoundaryConfirmed\":"+boundaryConfirmed
                +",\"boundaryStatus\":\""+boundaryStatus+"\",\"sourceBracketQualified\":true,\"publicationRequiresTerminalQualifiedCrops\":1"
                +",\"initialClipped\":"+initialClipped+",\"initialEvidence\":\"UI_GEOMETRY\",\"swipes\":"+swipes+",\"fullScreenCaptures\":0,\"taskTaps\":0,\"ocrQueries\":0,\"taskCompletion\":\"NOT_EVALUATED\"}\n";
    }
    private void writeFile(String name,byte[] bytes)throws Exception{
        var before=Files.readAttributes(artifacts,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isDirectory()||before.isSymbolicLink()||before.fileKey()==null)throw new SecurityException("Artifact directory unavailable");
        try(FileChannel file=FileChannel.open(artifacts.resolve(name),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())if(file.write(data)<=0)throw new SecurityException("Artifact incomplete");file.force(true);
        }
        var after=Files.readAttributes(artifacts,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!after.isDirectory()||after.isSymbolicLink()||!before.fileKey().equals(after.fileKey()))throw new SecurityException("Artifact directory changed");
    }
    private Path createArtifactDirectory()throws Exception{
        Path cache=activity.getCacheDir().toPath(),parent=cache.resolve(CardRevealProbeScope.DIRECTORY);
        try{Files.createDirectory(parent);}catch(FileAlreadyExistsException exists){/* Verify exact directory. */}
        for(Path p:new Path[]{cache,parent}){var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.fileKey()==null)throw new SecurityException("Local review directory unavailable");}
        return Files.createDirectory(parent.resolve(UUID.randomUUID().toString()));
    }
    private boolean nativeResourcesClosed(){return !constructionUnknown&&AndroidOverviewUiReader.processKnownClean()
            &&AndroidUiStructureReader.processKnownClean()&&ReviewBingNavigation.knownClean()&&AndroidCardRegionCapture.knownClean()
            &&(uiRead==null||uiRead.knownClean())&&(cropRead==null||cropRead.knownClean());}
    private boolean allResourcesClosed(){return nativeResourcesClosed()&&!appendActive.get();}
    private void latchCancel(){canceled.set(true);intent.cancel();activity.clearPendingHandoff();}
    void cancel(){if(root!=null)root.request();else latchCancel();}
    private void closeOnOwner(){
        if(!workerStarted.get()&&canceled.get()){workerReturned=true;stage=Stage.CANCELED;}
        if(closed||!workerReturned)return;
        if(!allResourcesClosed()){stage=Stage.CLEANUP_UNKNOWN;notifyFinished();return;}
        closed=true;if(root!=null)root.releaseIfClosed();released=true;loop.quitSafely();notifyFinished();
    }
    private void notifyFinished(){if(notified)return;notified=true;String details=fixedSummary();activity.runOnUiThread(()->activity.cardRevealFinished(this,details));}
    boolean finishedAndReleased(){return workerReturned&&closed&&released;}
    String fixedSummary(){return "profile=CARD_REVEAL_V2 stage="+stage+" failedAt="+failedAt+" terminal="+workerReturned+" released="+released
            +" cleanup="+(allResourcesClosed()?"CONFIRMED":"UNKNOWN")+" uiObservations="+intent.uiCount()+" scrollSlotsSpent="+intent.swipeCount()
            +" scrollDispatchAttempts="+dispatches+" swipes="+swipes+" cropAttempts="+(intent.cropSpent()?1:0)+" qualifiedCrops="+qualifiedCrops+" savedImages="+savedImages
            +" initialClipped="+initialClipped+" initialEvidence=UI_GEOMETRY revealMs="+revealMs+" cropMs="+cropMs+" boundaryMs="+boundaryMs+" fullCardBoundary="+boundaryConfirmed
            +" timingLabel=UI_CONFIRMED_FULL_REVEAL uiStatus="+uiStatus+" cropStatus="+cropStatus+" helperStatus="+helperStatus+" boundaryStatus="+boundaryStatus
            +" lastScrollHint="+scrollHint+" maxUi=24 maxSwipes=8 maxCrops=1 fullScreenCaptures=0 taskTaps=0 back=0 textEntry=0 ocrQueries=0"
            +" actionAuthority=false taskCompletion=NOT_EVALUATED rawDataUploaded=false artifacts="+(artifacts==null?"NONE":artifacts.getFileName().toString());}
}
