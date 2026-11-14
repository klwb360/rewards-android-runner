package com.aliothmoon.maafw.once;

import android.content.Context;
import android.os.SystemClock;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Native wiring seam, not an admission route.
 * The independent alignment entry supplies its consumed-once claim boundary,
 * current target-handoff/visibility/expiry gate, fence, Maa recognizer and owner executor.
 * Do not manufacture MediaProjection Tickets or use a synthetic claim for live work. */
final class ReviewShizukuPngBinding {
    /** Native Maa and explicit offline adapters share the ownership-checked result boundary. */
    interface AlignmentRecognizer extends BackendPorts.Recognizer {
        UiImageAlignmentCapture.Candidate takeAlignmentCandidate(OnceBackend.Owner expected);
        MaaImageBufferAudit.Result alignmentAudit(OnceBackend.Owner expected);
    }
    static final class AlignmentCompletion {
        private final UiImageAlignmentCapture capture;
        private final UiRegionImageRefiner.Result result;
        private final boolean live;
        private boolean used;
        private AlignmentCompletion(UiImageAlignmentCapture capture,UiRegionImageRefiner.Result result,boolean live){
            this.capture=capture;this.result=result;this.live=live;
        }
        synchronized boolean consume(Object uiOwner,Object session,Object epoch,long now){
            boolean first=!used;used=true;
            return first&&capture.matchesOwners(uiOwner,session,epoch)&&capture.publicationCurrent(now);
        }
        boolean liveTransport(){return live;}
        UiRegionImageRefiner.Result result(){return result;}
        long lower(){return capture.lower();}
        long deadline(){return capture.deadline();}
    }
    /** No caller-supplied Result or cleanup assertion can issue the completion. */
    static final class AlignmentBinding {
        private final Binding binding;
        private final AlignmentRecognizer recognizer;
        private final UiImageAlignmentCapture capture;
        private boolean taken;
        private AlignmentBinding(Binding binding,AlignmentRecognizer recognizer,UiImageAlignmentCapture capture){
            this.binding=binding;this.recognizer=recognizer;this.capture=capture;
        }
        OnceBackend backend(){return binding.backend();}
        UiImageAlignmentPublication publication(BingNavigationSnapshot.Ready after,long now,
                Object uiOwner,Object session,Object epoch,long deadline){
            return new UiImageAlignmentPublication(takeReceipt(after,now),uiOwner,session,epoch,
                Math.min(deadline,capture.deadline()));
        }
        synchronized AlignmentCompletion takeReceipt(BingNavigationSnapshot.Ready after,long now){
            if(taken)return null;taken=true;
            UiImageAlignmentCapture.Candidate candidate=null;
            OnceBackend.Owner owner=null;
            long entered=-1,returned=-1;
            boolean healthy=false;
            try{
                var terminal=binding.backend.snapshot();owner=terminal.owner();
                var clean=binding.cleanReceipt();
                var window=binding.source.sourceWindow(owner);
                if(window!=null){entered=window.entered();returned=window.returned();}
                candidate=recognizer.takeAlignmentCandidate(owner);
                var maa=recognizer.alignmentAudit(owner);
                healthy=terminal.phase()==OnceBackend.Phase.COMPLETED
                    &&terminal.failure()==OnceBackend.Failure.NONE
                    &&terminal.cancellation()==OnceBackend.Cancellation.NOT_REQUESTED
                    &&clean!=null&&clean.clean()&&clean.liveTransport()==binding.live
                    &&candidate!=null&&candidate.matches(capture,recognizer,owner)
                    &&window!=null&&sameGray(candidate,binding.imageAudit(),maa,capture.width(),capture.height());
            }catch(RuntimeException unavailable){healthy=false;}
            boolean sealed=capture.sealSource(healthy?candidate:null,recognizer,owner,entered,returned,after,now);
            return healthy&&sealed?new AlignmentCompletion(capture,candidate.result(),binding.live):null;
        }
    }
    private static boolean sameGray(UiImageAlignmentCapture.Candidate candidate,PngImageAudit.Evidence png,
            MaaImageBufferAudit.Result maa,int width,int height){
        if(png==null||!png.isCleanupConfirmed()||png.status()!=PngImageAudit.Status.MATCH
                ||png.width()!=width||png.height()!=height||!png.firstReplayEqual()
                ||maa==null||!maa.cleanupQualified()||maa.status()!=MaaImageBufferAudit.Status.VERIFIED_EQUAL
                ||maa.queryPosts()!=1||!maa.exactBindings()||!maa.positiveTaskBound()||!maa.queryQuiet()
                ||!maa.javaScratchWiped()||!maa.imageDestroyed()||!maa.taskerDestroyed())return false;
        byte[] expected=null,pngHash=null;
        try{
            expected=candidate.graySha256();pngHash=decodeHash(png.sourceSha256());
            return expected!=null&&expected.length==32&&pngHash!=null&&MessageDigest.isEqual(expected,pngHash)
                &&png.sourceSha256().equals(png.replaySha256())&&png.sourceSha256().equals(png.standardSha256())
                &&MaaImageBufferAudit.matchesGraySha256(maa.fingerprint(),width,height,expected);
        }finally{if(expected!=null)Arrays.fill(expected,(byte)0);if(pngHash!=null)Arrays.fill(pngHash,(byte)0);}
    }
    private static byte[] decodeHash(String value){
        if(value==null||value.length()!=64)return null;byte[] result=new byte[32];
        for(int i=0;i<result.length;i++){
            int hi=Character.digit(value.charAt(i*2),16),lo=Character.digit(value.charAt(i*2+1),16);
            if(hi<0||lo<0){Arrays.fill(result,(byte)0);return null;}result[i]=(byte)((hi<<4)|lo);
        }
        return result;
    }
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
    /** A caller must already possess its own capture authority before using this native seam. */
    static AlignmentBinding bindReadOnlyAlignment(Context context,BackendPorts.ClaimBoundary existingClaim,
            PngCaptureSession.Gate existingGate,BackendPorts.RevocationFence existingFence,
            Executor existingOwnerExecutor,UiImageAlignmentCapture capture){
        if(capture==null||existingGate==null)throw new IllegalArgumentException("Alignment capture and gate required");
        var recognizer=MaaRecognizer.rootUiRegionAlignmentAudit(context,capture);
        var geometry=new BoundedPngGray.Geometry(capture.width(),capture.height());
        PngCaptureSession.Gate gate=()->{
            existingGate.requireCurrent();
            if(!capture.captureCurrent(SystemClock.elapsedRealtime()))throw new SecurityException("Alignment source expired");
        };
        var transport=new AndroidReviewShizukuPngTransport(context,geometry,capture.rotation(),gate);
        return bindAlignmentInternal(existingClaim,transport,transport,existingFence,recognizer,existingOwnerExecutor,
            SystemClock::elapsedRealtimeNanos,capture,new AndroidBitmapAuditDecoder(),true);
    }
    /** Exercises the actual source/coordinator join, but can never issue live evidence. */
    static AlignmentBinding fixtureReadOnlyAlignment(BackendPorts.ClaimBoundary claim,
            PngCaptureSession.Transport transport,PngCaptureSession.Gate gate,BackendPorts.RevocationFence fence,
            AlignmentRecognizer recognizer,Executor executor,LongSupplier clock,
            UiImageAlignmentCapture capture,PngImageAudit.Decoder decoder){
        return bindAlignmentInternal(claim,transport,gate,fence,recognizer,executor,clock,capture,decoder,false);
    }
    private static AlignmentBinding bindAlignmentInternal(BackendPorts.ClaimBoundary claim,
            PngCaptureSession.Transport transport,PngCaptureSession.Gate gate,BackendPorts.RevocationFence fence,
            AlignmentRecognizer recognizer,Executor executor,LongSupplier clock,
            UiImageAlignmentCapture capture,PngImageAudit.Decoder decoder,boolean live){
        if(claim==null||recognizer==null||executor==null||capture==null||decoder==null
                ||transport==null||gate==null||fence==null||clock==null)
            throw new IllegalArgumentException("Existing alignment authority and native ports required");
        if(!capture.claimTransport())throw new SecurityException("Alignment source already claimed");
        var source=new ReviewShizukuPngFrameSource(transport,gate,clock,
            new BoundedPngGray.Geometry(capture.width(),capture.height()),fence,decoder,true);
        var binding=new Binding(new OnceBackend(claim,source,recognizer,executor),source,live);
        return new AlignmentBinding(binding,recognizer,capture);
    }
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
