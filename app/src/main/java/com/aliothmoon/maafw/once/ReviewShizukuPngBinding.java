package com.aliothmoon.maafw.once;

import android.content.Context;
import android.os.SystemClock;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Staged wiring seam, not an admission route. No current runtime references it.
 * A reviewed future caller supplies its existing consumed-once claim boundary,
 * current target-handoff/visibility/expiry gate, fence, Maa recognizer and owner executor.
 * Do not manufacture MediaProjection Tickets or use a synthetic claim for live work. */
final class ReviewShizukuPngBinding {
    static final class CleanReceipt {
        private final boolean liveTransport;
        private final ReviewShizukuPngFrameSource.CleanupSnapshot snapshot;
        private final OnceBackend.Result result;
        private CleanReceipt(boolean live,ReviewShizukuPngFrameSource.CleanupSnapshot snapshot,OnceBackend.Result result){liveTransport=live;this.snapshot=snapshot;this.result=result;}
        boolean liveTransport(){return liveTransport;}
        boolean clean(){return snapshot.cleanFor(result);}
        ReviewShizukuPngFrameSource.CleanupSnapshot snapshot(){return snapshot;}
    }
    static final class Binding {
        private final OnceBackend backend;
        private final ReviewShizukuPngFrameSource source;
        private final boolean live;
        private Binding(OnceBackend backend,ReviewShizukuPngFrameSource source,boolean live){this.backend=backend;this.source=source;this.live=live;}
        OnceBackend backend(){return backend;}
        ReviewShizukuPngFrameSource source(){return source;}
        PngImageAudit.Evidence imageAudit(){return source.imageAudit();}
        /** Reads this binding's actual coordinator, not a caller-supplied Result. */
        CleanReceipt cleanReceipt(){
            OnceBackend.Result result=backend.snapshot();
            return source.cleanFor(result)?new CleanReceipt(live,source.cleanupSnapshot(result.owner()),result):null;
        }
    }
    private ReviewShizukuPngBinding() { }
    static Binding bind(Context context,BackendPorts.ClaimBoundary existingClaim,
            PngCaptureSession.Gate existingGate,BackendPorts.RevocationFence existingFence,
            BackendPorts.Recognizer existingMaaRecognizer,Executor existingOwnerExecutor,
            int width,int height,int rotation) {
        return bindInternal(context,existingClaim,existingGate,existingFence,existingMaaRecognizer,existingOwnerExecutor,width,height,rotation,null);
    }
    static Binding bindImageAudit(Context context,BackendPorts.ClaimBoundary existingClaim,
            PngCaptureSession.Gate existingGate,BackendPorts.RevocationFence existingFence,
            BackendPorts.Recognizer existingMaaRecognizer,Executor existingOwnerExecutor,
            int width,int height,int rotation){
        return bindInternal(context,existingClaim,existingGate,existingFence,existingMaaRecognizer,existingOwnerExecutor,width,height,rotation,new AndroidBitmapAuditDecoder());
    }
    private static Binding bindInternal(Context context,BackendPorts.ClaimBoundary existingClaim,
            PngCaptureSession.Gate existingGate,BackendPorts.RevocationFence existingFence,
            BackendPorts.Recognizer existingMaaRecognizer,Executor existingOwnerExecutor,
            int width,int height,int rotation,PngImageAudit.Decoder decoder){
        if(existingClaim==null||existingMaaRecognizer==null||existingOwnerExecutor==null)
            throw new IllegalArgumentException("existing authority and Maa ports required");
        BoundedPngGray.Geometry geometry=new BoundedPngGray.Geometry(width,height);
        AndroidReviewShizukuPngTransport transport=new AndroidReviewShizukuPngTransport(context,geometry,rotation,existingGate);
        ReviewShizukuPngFrameSource source=new ReviewShizukuPngFrameSource(transport,transport,
                SystemClock::elapsedRealtimeNanos,geometry,existingFence,decoder);
        return new Binding(new OnceBackend(existingClaim,source,existingMaaRecognizer,existingOwnerExecutor),source,true);
    }
    /** Dedicated offline seam. Cannot manufacture a live preflight receipt. */
    static Binding fixture(BackendPorts.ClaimBoundary claim,PngCaptureSession.Transport transport,
            PngCaptureSession.Gate gate,BackendPorts.RevocationFence fence,BackendPorts.Recognizer recognizer,
            Executor executor,LongSupplier clock,int width,int height){
        ReviewShizukuPngFrameSource source=new ReviewShizukuPngFrameSource(transport,gate,clock,new BoundedPngGray.Geometry(width,height),fence);
        return new Binding(new OnceBackend(claim,source,recognizer,executor),source,false);
    }
    static Binding fixtureImageAudit(BackendPorts.ClaimBoundary claim,PngCaptureSession.Transport transport,
            PngCaptureSession.Gate gate,BackendPorts.RevocationFence fence,BackendPorts.Recognizer recognizer,
            Executor executor,LongSupplier clock,int width,int height,PngImageAudit.Decoder decoder){
        if(decoder==null)throw new IllegalArgumentException("Audit decoder required");
        ReviewShizukuPngFrameSource source=new ReviewShizukuPngFrameSource(transport,gate,clock,new BoundedPngGray.Geometry(width,height),fence,decoder);
        return new Binding(new OnceBackend(claim,source,recognizer,executor),source,false);
    }
}
