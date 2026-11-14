package com.aliothmoon.maafw.once;

import android.content.Context;
import android.os.SystemClock;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Overview-only native join. A result cannot be published from caller-supplied cleanup booleans. */
final class OverviewPngBinding {
    interface OverviewRecognizer extends BackendPorts.Recognizer {
        OverviewFrameCapture.Candidate takeOverviewCandidate(OnceBackend.Owner expected);
        MaaImageBufferAudit.Result overviewAudit(OnceBackend.Owner expected);
    }
    private final OnceBackend backend;
    private final ReviewShizukuPngFrameSource source;
    private final OverviewRecognizer recognizer;
    private final OverviewFrameCapture capture;
    private final boolean live;
    private boolean taken;
    private OverviewPngBinding(BackendPorts.ClaimBoundary claim,PngCaptureSession.Transport transport,
            PngCaptureSession.Gate gate,BackendPorts.RevocationFence fence,OverviewRecognizer recognizer,
            Executor executor,LongSupplier clock,OverviewFrameCapture capture,PngImageAudit.Decoder decoder,boolean live){
        if(claim==null||transport==null||gate==null||fence==null||recognizer==null||executor==null
                ||clock==null||capture==null||decoder==null||!capture.claimTransport())
            throw new SecurityException("Exact new overview binding required");
        this.capture=capture;this.recognizer=recognizer;this.live=live;
        source=new ReviewShizukuPngFrameSource(transport,gate,clock,new BoundedPngGray.Geometry(capture.width(),capture.height()),
                fence,new OverviewPreviewDecoder(decoder,capture.preview(),capture.width(),capture.height()),true);
        backend=new OnceBackend(claim,source,recognizer,executor);
    }
    static OverviewPngBinding bind(Context context,BackendPorts.ClaimBoundary claim,PngCaptureSession.Gate existing,
            BackendPorts.RevocationFence fence,Executor executor,OverviewFrameCapture capture,PreparedAlignmentModels models){
        PngCaptureSession.Gate gate=()->{
            existing.requireCurrent();
            if(!capture.current(SystemClock.elapsedRealtime()))throw new SecurityException("Overview frame source expired");
        };
        var transport=new AndroidReviewShizukuPngTransport(context,new BoundedPngGray.Geometry(capture.width(),capture.height()),capture.rotation(),gate);
        return new OverviewPngBinding(claim,transport,transport,fence,
                MaaRecognizer.rootOverviewAudit(context,capture,models),executor,SystemClock::elapsedRealtimeNanos,
                capture,new AndroidBitmapAuditDecoder(),true);
    }
    static OverviewPngBinding fixture(BackendPorts.ClaimBoundary claim,PngCaptureSession.Transport transport,
            PngCaptureSession.Gate gate,BackendPorts.RevocationFence fence,OverviewRecognizer recognizer,
            Executor executor,LongSupplier clock,OverviewFrameCapture capture,PngImageAudit.Decoder decoder){
        return new OverviewPngBinding(claim,transport,gate,fence,recognizer,executor,clock,capture,decoder,false);
    }
    OnceBackend backend(){return backend;}
    boolean resourcesClosed(){return source.cleanFor(backend.snapshot());}
    synchronized Completion takeCompletion(BingNavigationSnapshot.Ready after,long now){
        if(taken)return null;taken=true;
        OverviewFrameCapture.Candidate candidate=null;OnceBackend.Owner owner=null;
        long entered=-1,returned=-1;boolean healthy=false;
        try{
            var terminal=backend.snapshot();owner=terminal.owner();
            var window=source.sourceWindow(owner);
            if(window!=null){entered=window.entered();returned=window.returned();}
            candidate=recognizer.takeOverviewCandidate(owner);
            var maa=recognizer.overviewAudit(owner);
            healthy=terminal.phase()==OnceBackend.Phase.COMPLETED&&terminal.failure()==OnceBackend.Failure.NONE
                    &&terminal.cancellation()==OnceBackend.Cancellation.NOT_REQUESTED&&resourcesClosed()
                    &&window!=null&&candidate!=null&&candidate.matches(capture,recognizer,owner)
                    &&sameGray(candidate,source.imageAudit(),maa,capture.width(),capture.height());
        }catch(RuntimeException unavailable){healthy=false;}
        boolean sealed=capture.seal(healthy?candidate:null,recognizer,owner,entered,returned,after,now);
        return healthy&&sealed?new Completion(capture,candidate.result(),candidate.graySha256(),live):null;
    }
    private static boolean sameGray(OverviewFrameCapture.Candidate candidate,PngImageAudit.Evidence png,
            MaaImageBufferAudit.Result maa,int width,int height){
        if(png==null||!png.isCleanupConfirmed()||png.status()!=PngImageAudit.Status.MATCH
                ||png.width()!=width||png.height()!=height||!png.firstReplayEqual()
                ||maa==null||!maa.cleanupQualified()||maa.status()!=MaaImageBufferAudit.Status.VERIFIED_EQUAL
                ||maa.queryPosts()!=1||!maa.exactBindings()||!maa.positiveTaskBound()||!maa.queryQuiet()
                ||!maa.javaScratchWiped()||!maa.imageDestroyed()||!maa.taskerDestroyed())return false;
        byte[] expected=null,pngHash=null;
        try{
            expected=candidate.graySha256();pngHash=decode(png.sourceSha256());
            return expected!=null&&expected.length==32&&pngHash!=null&&MessageDigest.isEqual(expected,pngHash)
                    &&png.sourceSha256().equals(png.replaySha256())&&png.sourceSha256().equals(png.standardSha256())
                    &&MaaImageBufferAudit.matchesGraySha256(maa.fingerprint(),width,height,expected);
        }finally{if(expected!=null)Arrays.fill(expected,(byte)0);if(pngHash!=null)Arrays.fill(pngHash,(byte)0);}
    }
    private static byte[] decode(String value){
        if(value==null||value.length()!=64)return null;byte[] result=new byte[32];
        for(int i=0;i<result.length;i++){
            int high=Character.digit(value.charAt(i*2),16),low=Character.digit(value.charAt(i*2+1),16);
            if(high<0||low<0){Arrays.fill(result,(byte)0);return null;}result[i]=(byte)((high<<4)|low);
        }return result;
    }
    static final class Completion {
        private final OverviewFrameCapture capture;private final OverviewFrameResult result;private final byte[] digest;private final boolean live;
        private boolean used;
        private Completion(OverviewFrameCapture capture,OverviewFrameResult result,byte[] digest,boolean live){this.capture=capture;this.result=result;this.digest=digest.clone();this.live=live;}
        boolean samePixels(Completion other){return other!=null&&MessageDigest.isEqual(digest,other.digest);}
        synchronized OverviewFrameResult publish(Object owner,Object session,Object epoch,long now,
                boolean current,boolean hidden,boolean sourceReady){
            boolean first=!used;used=true;
            return first&&live&&capture.matchesOwners(owner,session,epoch)&&current&&hidden&&sourceReady
                    &&capture.publicationCurrent(now)?result:null;
        }
    }
}
