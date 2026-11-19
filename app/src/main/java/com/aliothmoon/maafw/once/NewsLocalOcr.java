package com.aliothmoon.maafw.once;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Consumes only the same-frame card PNG supplied by the source-bracketed device port. */
final class NewsLocalOcr {
    record Result(NewsFeedModel.Kind kind,String key,int x,int y,String reason,boolean clean) {}
    static Result recognize(Context context,byte[] ownedPng,Box cropOnScreen,NewsFeedModel.Frame frame,
                             boolean expanded,BooleanSupplier current) {
        Bitmap bitmap=null;
        int[] argb=null;
        byte[] gray=null;
        OnceBackend backend=null;
        boolean clean=true;
        NewsCardOcrPlan.Evidence evidence=NewsCardOcrPlan.Evidence.hold("LOCAL_CROP_UNKNOWN");
        try {
            if(context==null || frame==null || !NewsFeedModel.positive(frame.viewport()) || cropOnScreen==null
                    || !frame.viewport().contains(cropOnScreen) || cropOnScreen.equals(frame.viewport())
                    || ownedPng==null||ownedPng.length<57||ownedPng.length>CardNativeMockReceipt.MAX_PNG_BYTES
                    ||current==null||!current.getAsBoolean())throw new IllegalArgumentException();
            int w=cropOnScreen.width(),h=cropOnScreen.height();
            if(w<32||h<32||w>4096||h>4096||(long)w*h>LocalCardMockPlan.MAX_PIXELS)throw new IllegalArgumentException();
            var bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(ownedPng,0,ownedPng.length,bounds);
            if(bounds.outWidth!=w||bounds.outHeight!=h||!"image/png".equals(bounds.outMimeType))throw new IllegalArgumentException();
            var options=new BitmapFactory.Options();options.inMutable=true;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            bitmap=BitmapFactory.decodeByteArray(ownedPng,0,ownedPng.length,options);
            if(bitmap==null||bitmap.getWidth()!=w||bitmap.getHeight()!=h)throw new IllegalArgumentException();
            argb=new int[w*h];gray=new byte[argb.length];
            bitmap.getPixels(argb,0,w,0,0,w,h);
            for(int i=0;i<gray.length;i++){
                if((i&4095)==0&&!current.getAsBoolean())throw new IllegalStateException();
                int px=argb[i]; gray[i]=(byte)((77*(px>>16&255)+150*(px>>8&255)+29*(px&255))>>>8);
            }
            var plan=NewsCardOcrPlan.prepare(frame,cropOnScreen,argb,gray,expanded,current);
            if(plan==null) {
                evidence=NewsCardOcrPlan.Evidence.hold("BORDER_OR_ROI");
            } else {
                var nativeOcr=MaaRecognizer.newsCard(context,plan);
                var source=new RemainingCardNativeProcessor.OwnedGraySource(gray,w,h);
                AtomicBoolean spent=new AtomicBoolean();
                backend=new OnceBackend(owner->new BackendPorts.ClaimReceipt(owner,
                        current.getAsBoolean() && spent.compareAndSet(false,true)
                                ? BackendPorts.ClaimDecision.CLAIMED : BackendPorts.ClaimDecision.DENIED),
                        source,nativeOcr,Runnable::run);
                var result=backend.start();
                clean=LocalCardMockRuntime.cleanupConfirmed(result);
                if(clean && result.phase()==OnceBackend.Phase.COMPLETED && result.failure()==OnceBackend.Failure.NONE
                        &&result.cancellation()==OnceBackend.Cancellation.NOT_REQUESTED && current.getAsBoolean()) {
                    var seen=nativeOcr.newsEvidence(result.owner());
                    if(seen!=null)evidence=seen;
                } else evidence=NewsCardOcrPlan.Evidence.hold("NATIVE_OCR_"+result.failure().name());
            }
        }catch(RuntimeException failure){evidence=NewsCardOcrPlan.Evidence.hold("LOCAL_CROP_REJECTED");}
        finally {
            if(backend!=null)clean&=LocalCardMockRuntime.cleanupConfirmed(backend.snapshot());
            if(ownedPng!=null)Arrays.fill(ownedPng,(byte)0);
            if(argb!=null)Arrays.fill(argb,0);
            if(gray!=null)Arrays.fill(gray,(byte)0);
            if(bitmap!=null){
                try{bitmap.eraseColor(0);}catch(RuntimeException failed){clean=false;}
                try{bitmap.recycle();}catch(RuntimeException failed){clean=false;}
            }
        }
        if(!clean)evidence=NewsCardOcrPlan.Evidence.hold("OCR_CLEANUP_UNCONFIRMED");
        return new Result(evidence.kind(),evidence.key(),evidence.x(),evidence.y(),evidence.reason(),clean);
    }
    private NewsLocalOcr(){}
}
