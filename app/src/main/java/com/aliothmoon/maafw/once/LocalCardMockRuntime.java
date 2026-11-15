package com.aliothmoon.maafw.once;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native Android OCR on one exact historical ROI. It has no screen or input adapter. */
final class LocalCardMockRuntime {
    private final DormantCaptureConfirmationActivity activity;
    private final long deadline;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final HandlerThread loop=new HandlerThread("local-card-native-ocr");
    private final AtomicBoolean canceled=new AtomicBoolean(),started=new AtomicBoolean(),appendActive=new AtomicBoolean();
    private Handler owner;
    private CaptureSessionRuntime.AlignmentRoot root;
    private volatile OnceBackend backend;
    private MaaRecognizer recognizer;
    private volatile Bitmap image;
    private Path output;
    private LocalCardMockPlan.Outcome outcome;
    private LocalCardRoiDiagnostic.Report diagnostic;
    private volatile String stage="NEW",backendPhase="NONE",reason="NONE";
    private volatile boolean workerReturned,closed,released,unknown;
    private int queries,marks,hypothetical,diagSaved;
    private long nativeMs=-1;
    private boolean notified;
    private LocalCardMockRuntime(DormantCaptureConfirmationActivity activity,long deadline){this.activity=activity;this.deadline=deadline;}
    static LocalCardMockRuntime start(DormantCaptureConfirmationActivity activity,
            DormantCaptureConfirmationActivity.LiveClick click,Object approvalOwner){
        CaptureReleaseGate.requireConfigured();
        var epoch=CaptureSessionRuntime.alignmentEpoch();
        if(activity==null||click==null||approvalOwner==null||Looper.myLooper()!=Looper.getMainLooper()
                ||android.os.Process.myUid()<10000||!activity.getPackageName().equals("com.aliothmoon.maafw.productrewardsworking.capturereview")
                ||!Application.getProcessName().equals(activity.getPackageName()+":capture_once")||!activity.localCardMockSelected()
                ||!activity.localCardMockApprovalMatches(approvalOwner,click)
                ||!click.consume(activity,activity.requestIdentity(),approvalOwner,epoch))
            throw new SecurityException("Fresh visible local mock confirmation required");
        var run=new LocalCardMockRuntime(activity,SystemClock.elapsedRealtime()+180000);
        run.root=CaptureSessionRuntime.retainAlignment(run,run.deadline,run::latchCancel,run::closeOnOwner,()->run.closed,run::postOwner);
        try{run.loop.start();run.owner=new Handler(run.loop.getLooper());run.root.arm();
            if(!run.main.post(()->{if(run.current())run.postOwner(run::run);else run.cancel();}))run.cancel();
        }catch(RuntimeException failed){run.unknown=true;run.cancel();}
        return run;
    }
    private boolean postOwner(Runnable task){try{if(owner!=null&&owner.post(task))return true;}catch(RuntimeException failed){/* Retain graph. */}unknown=true;latchCancel();return false;}
    private boolean current(){return !unknown&&!workerReturned&&!canceled.get()&&root!=null&&root.current()&&SystemClock.elapsedRealtime()<deadline&&activity.localCardMockCurrent(this);}
    private void requireCurrent(){if(!current())throw new SecurityException("Local native mock expired or hidden");}
    private void run(){
        if(!started.compareAndSet(false,true))return;
        try{
            requireCurrent();stage="RUN_RECORD";LocalCardMockFiles.burn(activity.getNoBackupFilesDir().toPath());
            stage="LOCAL_IMAGE";image=LocalCardMockFiles.read(activity);output=LocalCardMockFiles.output(activity);
            int w=image.getWidth(),h=image.getHeight();int[] argb=new int[w*h];byte[] gray=new byte[w*h];
            try{
                image.getPixels(argb,0,w,0,0,w,h);
                for(int i=0;i<argb.length;i++){int p=argb[i];gray[i]=(byte)((77*((p>>>16)&255)+150*((p>>>8)&255)+29*(p&255)+128)>>>8);}
                stage="REGION_PLAN";var plan=LocalCardMockPlan.prepare(gray,argb,w,h,false,()->!current());
                if(!plan.admitted()){reason=plan.reason();stage="HOLD";return;}
                requireCurrent();stage="ANDROID_NATIVE_OCR";recognizer=MaaRecognizer.localCardMock(activity,plan);
                var source=new StaticSource(gray,w,h);AtomicBoolean claim=new AtomicBoolean();
                backend=new OnceBackend(own->new BackendPorts.ClaimReceipt(own,
                        current()&&claim.compareAndSet(false,true)?BackendPorts.ClaimDecision.CLAIMED:BackendPorts.ClaimDecision.DENIED),source,recognizer,Runnable::run);
                long began=SystemClock.elapsedRealtime();var result=backend.start();nativeMs=SystemClock.elapsedRealtime()-began;
                backendPhase=result.phase().name();
                if(!cleanupConfirmed(result)||result.cancellation()!=OnceBackend.Cancellation.NOT_REQUESTED)
                    throw new SecurityException("Native diagnostic cleanup unavailable");
                requireCurrent();diagnostic=recognizer.localCardRoiDiagnostic(result.owner());
                if(diagnostic==null)throw new SecurityException("Exact owner native diagnostic unavailable");
                queries=diagnostic.completedQueries();
                if(result.phase()==OnceBackend.Phase.COMPLETED&&result.failure()==OnceBackend.Failure.NONE){
                    outcome=recognizer.localCardMockOutcome(result.owner());
                    if(outcome==null)throw new SecurityException("Exact owner diagnostic outcome unavailable");
                    reason=outcome.reason();
                    hypothetical=outcome.hypotheticalClicks();
                    if(hypothetical<0||hypothetical>1||outcome.realExecutions()!=0)throw new SecurityException("Fixed fake selection budget required");
                }else reason="NATIVE_"+result.failure().name();
            }finally{Arrays.fill(argb,0);Arrays.fill(gray,(byte)0);}
            if(diagnostic!=null){stage="PUBLICATION";publishResult();
                stage=marks==1?(hypothetical==1?"MOCK_COMPLETE":"VISUAL_PREVIEW_ONLY"):"HOLD";
            }else stage="HOLD";
        }catch(Exception failed){if(failed instanceof InterruptedException)Thread.currentThread().interrupt();stage=canceled.get()?"CANCELED":"HOLD";}
        catch(Error failed){unknown=true;stage="CLEANUP_UNKNOWN";}
        finally{workerReturned=true;closeImage();closeOnOwner();}
    }
    private void publishResult()throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean committed=new AtomicBoolean(),pointWritten=new AtomicBoolean();appendActive.set(true);
        if(!main.post(()->{
            try{
                boolean outer=activity.localCardMockPublish(this,()->{
                    if(!current()||!nativeClosed()||diagnostic==null)return;
                    byte[] bytes=null;
                    try{bytes=diagnosticManifest().getBytes(StandardCharsets.UTF_8);
                        LocalCardMockFiles.write(output,"DIAGNOSTICS.json",bytes);committed.set(true);
                    }catch(Exception failed){return;}
                    finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}
                    if(outcome==null||image==null||!(hypothetical==1||outcome.status().equals("VISUAL_PREVIEW_ONLY"))
                            ||outcome.x()<0||outcome.y()<0||outcome.x()>=image.getWidth()||outcome.y()>=image.getHeight())return;
                    var encoded=new WipingOutput();bytes=null;
                    try{
                        Canvas canvas=new Canvas(image);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                        paint.setColor(Color.RED);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(5);
                        canvas.drawCircle(outcome.x(),outcome.y(),25,paint);
                        canvas.drawLine(outcome.x()-36,outcome.y(),outcome.x()+36,outcome.y(),paint);
                        canvas.drawLine(outcome.x(),outcome.y()-36,outcome.x(),outcome.y()+36,paint);
                        paint.setStyle(Paint.Style.FILL);paint.setTextSize(28);
                        canvas.drawText("1 - MOCK ONLY",Math.min(outcome.x()+45,image.getWidth()-270),Math.max(35,outcome.y()-25),paint);
                        if(!image.compress(Bitmap.CompressFormat.PNG,100,encoded)||!current()||!nativeClosed())return;bytes=encoded.toByteArray();
                        LocalCardMockFiles.write(output,"MARKED.png",bytes);
                        if(!current()||!nativeClosed())return;
                        LocalCardMockFiles.write(output,"RESULT.json",pointManifest().getBytes(StandardCharsets.UTF_8));pointWritten.set(true);
                    }catch(Exception failed){/* A saved diagnostic does not qualify a partial point artifact. */}
                    finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);encoded.wipe();}
                });
                if(!outer||!current()||!nativeClosed()){committed.set(false);pointWritten.set(false);}
                if(committed.get())diagSaved=1;if(committed.get()&&pointWritten.get())marks=1;
            }finally{appendActive.set(false);done.countDown();if(workerReturned){closeImage();postOwner(this::closeOnOwner);}}
        })){appendActive.set(false);throw new SecurityException("Local mock publication queue unavailable");}
        long left=Math.min(10000,deadline-SystemClock.elapsedRealtime());
        if(left<=0||!done.await(left,TimeUnit.MILLISECONDS)){unknown=true;latchCancel();throw new SecurityException("Mock publication unresolved");}
        if(!committed.get())throw new SecurityException("Visible mock diagnostic publication rejected");
    }
    private String diagnosticManifest(){return "{\"profile\":\"NATIVE_CARD_MOCK_V3\",\"mode\":\"ANDROID_NATIVE_DIAGNOSTIC_ON_HISTORICAL_LOCAL_CROP\",\"inputSha256\":\""+LocalCardMockFiles.IMAGE_SHA
            +"\",\"width\":1336,\"height\":588,\"coordinateSpace\":\"CROP_LOCAL_ONLY\",\"reason\":\""+reason
            +"\",\"nativeMs\":"+nativeMs+",\"report\":"+diagnostic.json()
            +",\"hypotheticalClicks\":"+hypothetical+",\"realExecutions\":0,\"captures\":0,\"actionAuthority\":false,\"rawTextIncluded\":false}\n";}
    private String pointManifest(){return "{\"profile\":\"NATIVE_CARD_MOCK_V3\",\"mode\":\"ANDROID_NATIVE_OCR_ON_HISTORICAL_LOCAL_CROP\",\"inputSha256\":\""+LocalCardMockFiles.IMAGE_SHA
            +"\",\"width\":1336,\"height\":588,\"coordinateSpace\":\"CROP_LOCAL_ONLY\",\"status\":\""+outcome.status()+"\",\"reason\":\""+outcome.reason()
            +"\",\"x\":"+outcome.x()+",\"y\":"+outcome.y()+",\"day\":"+outcome.day()+",\"displayedPoints\":"+outcome.displayedPoints()+",\"confidence\":"+outcome.confidence()
            +",\"ocrQueries\":"+queries+",\"nativeMs\":"+nativeMs+",\"hypotheticalClicks\":"+hypothetical+",\"realExecutions\":0,\"captures\":0,\"actionAuthority\":false,\"creditVerified\":false}\n";}
    private boolean nativeClosed(){var b=backend;if(b==null)return true;return cleanupConfirmed(b.snapshot());}
    static boolean cleanupConfirmed(OnceBackend.Result r){return r!=null&&r.terminal()&&(
            r.cleanup()==OnceBackend.Cleanup.CONFIRMED&&r.quiescence()==OnceBackend.Quiescence.CONFIRMED&&r.memory()==OnceBackend.Memory.WIPED
            ||r.cleanup()==OnceBackend.Cleanup.NOT_NEEDED&&r.quiescence()==OnceBackend.Quiescence.NOT_INVOKED&&r.memory()==OnceBackend.Memory.NOT_ALLOCATED);}
    private synchronized void closeImage(){if(appendActive.get())return;var exact=image;if(exact==null)return;
        try{exact.eraseColor(0);}catch(RuntimeException|Error failed){unknown=true;}
        try{exact.recycle();image=null;}catch(RuntimeException|Error failed){unknown=true;}}
    private boolean resourcesClosed(){return !unknown&&LocalCardMockFiles.knownClean()&&nativeClosed()&&!appendActive.get()&&image==null;}
    private void latchCancel(){canceled.set(true);var b=backend;if(b!=null)b.cancel(b.snapshot().owner());}
    void cancel(){if(root!=null)root.request();else latchCancel();}
    private void closeOnOwner(){if(!started.get()&&canceled.get())workerReturned=true;if(closed||!workerReturned)return;
        if(!resourcesClosed()){stage="CLEANUP_UNKNOWN";notifyFinished();return;}closed=true;root.releaseIfClosed();released=true;loop.quitSafely();notifyFinished();}
    private void notifyFinished(){if(notified)return;notified=true;String text=fixedSummary();main.post(()->activity.localCardMockFinished(this,text));}
    boolean finishedAndReleased(){return workerReturned&&closed&&released;}
    String fixedSummary(){return "profile=NATIVE_CARD_MOCK_V3 stage="+stage+" terminal="+workerReturned+" released="+released+" cleanup="+(resourcesClosed()?"CONFIRMED":"UNKNOWN")
            +" backendPhase="+backendPhase+" reason="+reason+" diagSaved="+diagSaved+" ocrQueries="+queries+" nativeMs="+nativeMs+" marks="+marks+" hypotheticalClicks="+hypothetical
            +" "+(diagnostic==null?"roiDiagnostic=UNAVAILABLE":diagnostic.summary())+" realExecutions=0 taskTaps=0 captures=0 fullScreenCaptures=0 inputSource=HISTORICAL_LOCAL_CROP coordinateSpace=CROP_LOCAL_ONLY actionAuthority=false creditVerified=false rawDataUploaded=false artifacts="+(output==null?"NONE":output.getFileName().toString());}
    private static final class WipingOutput extends ByteArrayOutputStream {void wipe(){Arrays.fill(buf,(byte)0);reset();}}
    private static final class StaticSource implements BackendPorts.FrameSource {
        private final byte[] gray;private final int w,h;private OnceBackend.Owner owner;private boolean returned,released;
        StaticSource(byte[] gray,int w,int h){this.gray=gray;this.w=w;this.h=h;}
        public void produce(OnceBackend.Owner own,BackendPorts.FrameSink sink,BackendPorts.StopSignal stop)throws Exception{
            if(owner!=null||own==null)throw new SecurityException("Single local frame required");owner=own;
            try{sink.begin(w,h);for(int i=0;i<gray.length;i+=4096){if(stop.requested())throw new BackendPorts.Cancelled();sink.append(gray,i,Math.min(4096,gray.length-i));}}
            finally{returned=true;}
        }
        public void release(OnceBackend.Owner own){if(owner!=own||!returned)throw new SecurityException("Exact local frame owner required");released=true;}
        public BackendPorts.Completion completion(OnceBackend.Owner own){boolean clean=owner==own&&returned&&released;return new BackendPorts.Completion(own,clean?BackendPorts.Released.CONFIRMED:BackendPorts.Released.UNKNOWN,clean?BackendPorts.Quiet.CONFIRMED:BackendPorts.Quiet.UNKNOWN);}
    }
}
