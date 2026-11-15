package com.aliothmoon.maafw.once;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import com.aliothmoon.maafw.rewards.RewardRegionGate;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Processes one same-run committed local crop. No path, capture, navigation or task input adapter. */
final class RemainingCardNativeProcessor implements AutoCloseable {
    private final Context context;
    private final CardNativeMockReceipt receipt;
    private final Object expectedOwner;
    private final RemainingCardChoice choice;
    private final BooleanSupplier allowed;
    private final AtomicBoolean started=new AtomicBoolean(),canceled=new AtomicBoolean();
    private volatile OnceBackend backend;
    private volatile boolean returned,unknown;
    private byte[] png,gray,markedPng;
    private int[] argb;
    private Bitmap image;
    private RemainingCardPlan.Outcome outcome;
    private RemainingCardRoiDiagnostic.Report diagnostic;
    private String reason="NOT_STARTED",backendPhase="NONE";
    private long nativeMs=-1;
    private int queries;
    private boolean diagramProduced;

    /** Takes ownership of png even on an invalid constructor argument. */
    RemainingCardNativeProcessor(Context context,CardNativeMockReceipt receipt,Object expectedOwner,
            byte[] png,RemainingCardChoice choice,BooleanSupplier current){
        if(context==null||receipt==null||expectedOwner==null||png==null||choice==null||current==null){
            if(png!=null)Arrays.fill(png,(byte)0);
            throw new IllegalArgumentException("Exact owned committed crop required");
        }
        this.context=context;this.receipt=receipt;this.expectedOwner=expectedOwner;this.png=png;this.allowed=current;this.choice=choice;
    }
    private boolean current(){
        if(canceled.get()||unknown)return false;
        try{return allowed.getAsBoolean();}catch(RuntimeException failed){return false;}
    }
    private void requireCurrent(){if(!current())throw new SecurityException("Owned crop processing revoked");}

    /** Single synchronous invocation on the reveal owner; no independent lifecycle or source lease. */
    void run(){
        if(!started.compareAndSet(false,true))return;
        try{
            requireCurrent();
            if(!receipt.claim(expectedOwner,png,receipt.width(),receipt.height())){
                reason="CAPTURE_RECEIPT_REJECTED";return;
            }
            var bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(png,0,png.length,bounds);
            if(bounds.outWidth!=receipt.width()||bounds.outHeight!=receipt.height()){
                reason="CAPTURE_SHAPE_REJECTED";return;
            }
            var options=new BitmapFactory.Options();options.inMutable=true;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            image=BitmapFactory.decodeByteArray(png,0,png.length,options);
            if(image==null||!image.isMutable()||image.getWidth()!=receipt.width()||image.getHeight()!=receipt.height()){
                reason="CAPTURE_DECODE_REJECTED";return;
            }
            int width=image.getWidth(),height=image.getHeight();
            argb=new int[width*height];gray=new byte[argb.length];
            image.getPixels(argb,0,width,0,0,width,height);
            for(int i=0;i<argb.length;i++){
                if((i&4095)==0)requireCurrent();
                int pixel=argb[i];gray[i]=(byte)((77*((pixel>>>16)&255)+150*((pixel>>>8)&255)+29*(pixel&255)+128)>>>8);
            }
            var plan=RemainingCardPlan.prepare(gray,argb,width,height,receipt,expectedOwner,choice,()->!current());
            if(!plan.admitted()){
                reason=plan.reason();outcome=plan.hold(reason,0);diagnostic=RemainingCardRoiDiagnostic.empty(width,height);
                if(reviewReady(plan)){requireCurrent();drawPoint();requireCurrent();}return;
            }
            if(plan.source()!=RewardRegionGate.Source.REAL_DISPLAY_NATIVE){reason="CAPTURE_PROVENANCE_REJECTED";return;}
            requireCurrent();
            var recognizer=MaaRecognizer.remainingCardMock(context,plan);
            var source=new OwnedGraySource(gray,width,height);
            AtomicBoolean claimed=new AtomicBoolean();
            backend=new OnceBackend(own->new BackendPorts.ClaimReceipt(own,
                    current()&&receipt.claimedBy(expectedOwner)&&claimed.compareAndSet(false,true)
                            ?BackendPorts.ClaimDecision.CLAIMED:BackendPorts.ClaimDecision.DENIED),source,recognizer,Runnable::run);
            long began=SystemClock.elapsedRealtime();var result=backend.start();nativeMs=SystemClock.elapsedRealtime()-began;
            backendPhase=result.phase().name();
            if(!LocalCardMockRuntime.cleanupConfirmed(result)){
                reason="NATIVE_CLEANUP_UNCONFIRMED";return;
            }
            if(result.cancellation()!=OnceBackend.Cancellation.NOT_REQUESTED){reason="CANCELED";return;}
            requireCurrent();
            diagnostic=recognizer.remainingCardDiagnostic(result.owner());
            if(diagnostic==null){reason="NATIVE_DIAGNOSTIC_UNAVAILABLE";return;}
            queries=diagnostic.completedQueries();
            if(result.phase()!=OnceBackend.Phase.COMPLETED||result.failure()!=OnceBackend.Failure.NONE){
                reason="NATIVE_"+result.failure().name();return;
            }
            outcome=recognizer.remainingCardOutcome(result.owner());
            if(outcome==null){reason="NATIVE_OUTCOME_UNAVAILABLE";return;}
            reason=outcome.reason();
            if(outcome.realExecutions()!=0||outcome.hypotheticalClicks()<0||outcome.hypotheticalClicks()>1){
                reason="MOCK_BUDGET_REJECTED";outcome=null;return;
            }
            if(!reviewReady(plan))return;
            requireCurrent();drawPoint();requireCurrent();
        }catch(Exception failed){
            if(failed instanceof InterruptedException)Thread.currentThread().interrupt();
            reason=canceled.get()||!current()?"CANCELED":"PROCESSING_FAILED";
        }catch(Error failed){unknown=true;reason="CLEANUP_UNKNOWN";}
        finally{wipeInputs();returned=true;}
    }
    private boolean reviewReady(RemainingCardPlan.Plan plan){
        if(outcome==null||outcome.boundary()==null||outcome.realExecutions()!=0||outcome.hypotheticalClicks()<0||outcome.hypotheticalClicks()>1)return false;
        var box=outcome.boundary();if(box.left()<1||box.top()<1||box.right()>=receipt.width()||box.bottom()>=receipt.height())return false;
        if(outcome.status().equals("HOLD"))return outcome.hypotheticalClicks()==0&&outcome.target()==null;
        if(outcome.status().equals("ITEM_COMPLETE_SKIP"))return outcome.hypotheticalClicks()==0&&outcome.displayedPoints()>0&&outcome.target()==null;
        if(!Double.isFinite(outcome.confidence())||outcome.confidence()<.85||outcome.confidence()>1||outcome.displayedPoints()<1)return false;
        if(outcome.status().equals("VISUAL_PREVIEW_ONLY"))return choice.weekly()&&outcome.hypotheticalClicks()==0&&queries==9;
        if(!outcome.status().equals("PREVIEW_ONLY")||choice.weekly()||outcome.hypotheticalClicks()!=1||queries!=2)return false;
        var target=outcome.target();return target!=null&&box.contains(target)&&outcome.x()>target.left()&&outcome.x()<target.right()
                &&outcome.y()>target.top()&&outcome.y()<target.bottom();
    }
    private void drawPoint(){
        var encoded=new WipingOutput();byte[] bytes=null;
        try{
            Canvas canvas=new Canvas(image);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean candidate=outcome.status().equals("PREVIEW_ONLY"),weekly=outcome.status().equals("VISUAL_PREVIEW_ONLY"),skip=outcome.status().equals("ITEM_COMPLETE_SKIP");
            paint.setColor(candidate?Color.RED:skip?Color.GREEN:weekly?0xffff9800:Color.GRAY);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(5);
            var boundary=outcome.boundary();canvas.drawRect(boundary.left(),boundary.top(),boundary.right(),boundary.bottom(),paint);
            if(candidate||weekly){
                canvas.drawCircle(outcome.x(),outcome.y(),25,paint);
                if(candidate){canvas.drawLine(outcome.x()-36,outcome.y(),outcome.x()+36,outcome.y(),paint);
                    canvas.drawLine(outcome.x(),outcome.y()-36,outcome.x(),outcome.y()+36,paint);}
            }
            paint.setStyle(Paint.Style.FILL);paint.setTextSize(24);
            String label=candidate?"1 - MOCK ONLY":skip?"SKIP - COMPLETE":weekly?"VISUAL - NO TASK":"HOLD - NO TARGET";
            canvas.drawText(label,boundary.left()+12,Math.max(30,boundary.top()+32),paint);
            if(!image.compress(Bitmap.CompressFormat.PNG,100,encoded)){reason="MARK_ENCODING_FAILED";return;}
            requireCurrent();bytes=encoded.toByteArray();
            synchronized(this){if(!current())return;markedPng=bytes;diagramProduced=true;bytes=null;}
        }finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);encoded.wipe();}
    }
    private synchronized void wipeInputs(){
        if(png!=null){Arrays.fill(png,(byte)0);png=null;}
        if(gray!=null){Arrays.fill(gray,(byte)0);gray=null;}
        if(argb!=null){Arrays.fill(argb,0);argb=null;}
        var exact=image;
        if(exact!=null){
            try{exact.eraseColor(0);}catch(RuntimeException|Error failed){unknown=true;}
            try{exact.recycle();image=null;}catch(RuntimeException|Error failed){unknown=true;}
        }
    }
    private boolean nativeClosed(){var exact=backend;return exact==null||LocalCardMockRuntime.cleanupConfirmed(exact.snapshot());}
    private boolean resultAvailable(){return returned&&!unknown&&nativeClosed()&&current()&&receipt.claimedBy(expectedOwner);}
    synchronized RemainingCardPlan.Outcome outcome(){return resultAvailable()?outcome:null;}
    synchronized String diagJson(){return resultAvailable()&&diagnostic!=null?diagnostic.json():null;}
    synchronized int candidatePoints(){return resultAvailable()&&diagramProduced&&outcome!=null&&outcome.status().equals("PREVIEW_ONLY")?1:0;}
    RemainingCardChoice choice(){return choice;}
    synchronized RemainingCardRoiDiagnostic.Report diagnostic(){return resultAvailable()?diagnostic:null;}
    String reason(){return reason;}
    String backendPhase(){return backendPhase;}
    long nativeMs(){return nativeMs;}
    int queries(){return queries;}
    int width(){return receipt.width();}
    int height(){return receipt.height();}
    String inputSha256(){return receipt.inputSha256();}
    /** A single ownership transfer. The reveal publication must wipe this array in its finally. */
    synchronized byte[] takeMarkedPng(){
        if(!resultAvailable()||outcome==null||!diagramProduced)return null;
        byte[] value=markedPng;markedPng=null;return value;
    }
    synchronized boolean knownClean(){return returned&&!unknown&&nativeClosed()&&png==null&&gray==null&&argb==null&&image==null&&markedPng==null;}
    void cancel(){
        canceled.set(true);receipt.cancel();var exact=backend;
        if(exact!=null)exact.cancel(exact.snapshot().owner());
    }
    @Override public synchronized void close(){
        if(!started.get()&&started.compareAndSet(false,true)){
            cancel();wipeInputs();returned=true;
        }else if(!returned){cancel();return;}
        if(markedPng!=null){Arrays.fill(markedPng,(byte)0);markedPng=null;}
    }
    private static final class WipingOutput extends ByteArrayOutputStream {
        void wipe(){Arrays.fill(buf,(byte)0);reset();}
    }
    private static final class OwnedGraySource implements BackendPorts.FrameSource {
        private final byte[] gray;
        private final int width,height;
        private OnceBackend.Owner owner;
        private boolean returned,released;
        OwnedGraySource(byte[] gray,int width,int height){this.gray=gray;this.width=width;this.height=height;}
        @Override public void produce(OnceBackend.Owner own,BackendPorts.FrameSink sink,BackendPorts.StopSignal stop)throws Exception{
            if(owner!=null||own==null)throw new SecurityException("Single owned local frame required");owner=own;
            try{sink.begin(width,height);for(int i=0;i<gray.length;i+=4096){
                if(stop.requested())throw new BackendPorts.Cancelled();sink.append(gray,i,Math.min(4096,gray.length-i));
            }}finally{returned=true;}
        }
        @Override public void release(OnceBackend.Owner own){
            if(owner!=own||!returned)throw new SecurityException("Exact returned frame owner required");released=true;
        }
        @Override public BackendPorts.Completion completion(OnceBackend.Owner own){
            boolean clean=owner==own&&returned&&released;
            return new BackendPorts.Completion(own,clean?BackendPorts.Released.CONFIRMED:BackendPorts.Released.UNKNOWN,
                    clean?BackendPorts.Quiet.CONFIRMED:BackendPorts.Quiet.UNKNOWN);
        }
    }
}
