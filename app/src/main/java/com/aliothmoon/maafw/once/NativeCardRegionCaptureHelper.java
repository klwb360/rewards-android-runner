package com.aliothmoon.maafw.once;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import static com.aliothmoon.maafw.once.CardRegionCaptureProtocol.Status;
import static com.aliothmoon.maafw.once.CardRegionCaptureProtocol.Cleanup;

/** One root helper invocation, one nonempty sourceCrop, no full-display capture fallback. */
public final class NativeCardRegionCaptureHelper {
    private static final class Stop extends Exception {
        private static final long serialVersionUID=1L;
        final Status status;Stop(Status status){this.status=status;}
    }
    private static final class Encoded extends ByteArrayOutputStream {
        Encoded(){super(CardRegionCaptureProtocol.MAX_PNG);}
        @Override public synchronized void write(int value){limit(1);super.write(value);}
        @Override public synchronized void write(byte[] bytes,int offset,int length){limit(length);super.write(bytes,offset,length);}
        private void limit(int length){if(length<0||length>CardRegionCaptureProtocol.MAX_PNG-count)throw new IllegalStateException("Local crop encoding limit");}
        void wipe(){Arrays.fill(buf,(byte)0);reset();}
    }
    public static void main(String[] args){
        long started=SystemClock.elapsedRealtime();CardRegionCaptureProtocol.Request request;
        try{request=CardRegionCaptureProtocol.parseArguments(args,started);}catch(RuntimeException invalid){return;}
        Status status=Status.CAPTURE_FAILED;boolean nativeInvoked=false,closed=true;
        Object nativeResult=null,captureArgs=null;Method releaseArgs=null;
        HardwareBuffer buffer=null;Bitmap hardwareBitmap=null,softwareBitmap=null;Encoded encoded=null;
        byte[] png=null,wire=null;
        try{
            if(android.os.Build.VERSION.SDK_INT<34)throw new Stop(Status.API_UNAVAILABLE);
            if(Process.myUid()!=0)throw new Stop(Status.PERMISSION_DENIED);
            checkTime(request);requireShape(request);
            // All required public methods are resolved before invoking capture. No hidden-API
            // exemption, accessible override, settings mutation, alternate capture or retry.
            Class<?> capture=Class.forName("android.window.ScreenCapture");
            Class<?> argsType=Class.forName("android.window.ScreenCapture$CaptureArgs");
            Class<?> builderType=Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
            Class<?> listenerType=Class.forName("android.window.ScreenCapture$ScreenCaptureListener");
            Class<?> synchronousType=Class.forName("android.window.ScreenCapture$SynchronousScreenCaptureListener");
            Class<?> resultType=Class.forName("android.window.ScreenCapture$ScreenshotHardwareBuffer");
            Class<?> windowType=Class.forName("android.view.IWindowManager");
            Method setCrop=builderType.getMethod("setSourceCrop",Rect.class);
            Method setScale=builderType.getMethod("setFrameScale",float.class);
            Method setSecure=builderType.getMethod("setCaptureSecureLayers",boolean.class);
            Method setProtected=builderType.getMethod("setAllowProtected",boolean.class);
            Method build=builderType.getMethod("build");releaseArgs=argsType.getMethod("release");
            Method newListener=capture.getMethod("createSyncCaptureListener");
            Method getBuffer=synchronousType.getMethod("getBuffer");
            Method hardware=resultType.getMethod("getHardwareBuffer");
            Method secure=resultType.getMethod("containsSecureLayers");
            Method bitmap=resultType.getMethod("asBitmap");
            Method captureDisplay=windowType.getMethod("captureDisplay",int.class,argsType,listenerType);
            Method service=Class.forName("android.os.ServiceManager").getMethod("getService",String.class);
            Method asInterface=Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface",IBinder.class);
            Object binder=service.invoke(null,"window");if(!(binder instanceof IBinder))throw new Stop(Status.API_UNAVAILABLE);
            Object manager=asInterface.invoke(null,binder);if(manager==null)throw new Stop(Status.API_UNAVAILABLE);
            Object builder=builderType.getConstructor().newInstance();var roi=request.roi();
            Rect crop=new Rect(roi.left(),roi.top(),roi.right(),roi.bottom());
            setCrop.invoke(builder,crop);setScale.invoke(builder,1f);
            setSecure.invoke(builder,false);setProtected.invoke(builder,false);captureArgs=build.invoke(builder);
            // Verify the actual immutable request to prevent null/default/ignored crop arguments.
            Object recordedCrop=argsType.getField("mSourceCrop").get(captureArgs);
            if(!(recordedCrop instanceof Rect)||!crop.equals(recordedCrop)||crop.isEmpty()
                    ||argsType.getField("mFrameScaleX").getFloat(captureArgs)!=1f
                    ||argsType.getField("mFrameScaleY").getFloat(captureArgs)!=1f
                    ||argsType.getField("mCaptureSecureLayers").getBoolean(captureArgs)
                    ||argsType.getField("mAllowProtected").getBoolean(captureArgs))throw new Stop(Status.API_UNAVAILABLE);
            Object listener=newListener.invoke(null);if(listener==null)throw new Stop(Status.API_UNAVAILABLE);
            checkTime(request);requireShape(request);checkTime(request);
            nativeInvoked=true;captureDisplay.invoke(manager,0,captureArgs,listener);
            nativeResult=getBuffer.invoke(listener);
            if(nativeResult==null)throw new Stop(Status.CAPTURE_NO_BUFFER);
            Object returned=hardware.invoke(nativeResult);
            if(!(returned instanceof HardwareBuffer))throw new Stop(Status.CAPTURE_NO_BUFFER);
            buffer=(HardwareBuffer)returned;
            if((boolean)secure.invoke(nativeResult))throw new Stop(Status.SECURE_OUTPUT_REJECTED);
            if(buffer.getWidth()!=roi.width()||buffer.getHeight()!=roi.height())throw new Stop(Status.DIMENSION_REJECTED);
            checkTime(request);requireShape(request);
            hardwareBitmap=(Bitmap)bitmap.invoke(nativeResult);
            if(hardwareBitmap==null||hardwareBitmap.getWidth()!=roi.width()||hardwareBitmap.getHeight()!=roi.height())
                throw new Stop(Status.DIMENSION_REJECTED);
            softwareBitmap=hardwareBitmap.copy(Bitmap.Config.ARGB_8888,true);
            if(softwareBitmap==null||softwareBitmap.getWidth()!=roi.width()||softwareBitmap.getHeight()!=roi.height())
                throw new Stop(Status.DIMENSION_REJECTED);
            encoded=new Encoded();
            if(!softwareBitmap.compress(Bitmap.CompressFormat.PNG,100,encoded))throw new Stop(Status.ENCODE_FAILED);
            png=encoded.toByteArray();
            if(!CardRegionCaptureProtocol.validPng(png,roi.width(),roi.height()))throw new Stop(Status.ENCODE_FAILED);
            checkTime(request);requireShape(request);status=Status.CAPTURED;
        }catch(Stop stopped){status=stopped.status;}
        catch(Throwable failure){
            Throwable cause=failure instanceof InvocationTargetException?((InvocationTargetException)failure).getCause():failure;
            status=cause instanceof SecurityException?Status.PERMISSION_DENIED
                    :cause instanceof ReflectiveOperationException||failure instanceof ReflectiveOperationException
                    ?Status.API_UNAVAILABLE:Status.CAPTURE_FAILED;
        }finally{
            try{if(softwareBitmap!=null)softwareBitmap.eraseColor(0);}catch(Throwable failed){closed=false;}
            try{if(softwareBitmap!=null)softwareBitmap.recycle();}catch(Throwable failed){closed=false;}
            try{if(hardwareBitmap!=null)hardwareBitmap.recycle();}catch(Throwable failed){closed=false;}
            try{if(buffer!=null)buffer.close();}catch(Throwable failed){closed=false;}
            try{if(encoded!=null)encoded.wipe();}catch(Throwable failed){closed=false;}
            try{if(captureArgs!=null&&releaseArgs!=null)releaseArgs.invoke(captureArgs);}catch(Throwable failed){closed=false;}
            // A missing callback/buffer may arrive late; process exit is not a close receipt.
            if(nativeInvoked&&(nativeResult==null||buffer==null))closed=false;
        }
        long finished=SystemClock.elapsedRealtime();
        if(!closed&&status==Status.CAPTURED)status=Status.CLEANUP_FAILED;
        if(status==Status.CAPTURED&&finished>=request.deadline())status=Status.DEADLINE_EXCEEDED;
        if(status!=Status.CAPTURED&&png!=null){Arrays.fill(png,(byte)0);png=null;}
        try{
            var report=new CardRegionCaptureProtocol.Report(request,started,finished,status,
                    closed?Cleanup.CONFIRMED:Cleanup.UNKNOWN,nativeInvoked,
                    png==null?0:request.roi().width(),png==null?0:request.roi().height(),png);
            wire=CardRegionCaptureProtocol.encode(report);System.out.write(wire,0,wire.length);System.out.flush();
        }catch(Throwable unavailable){/* No partial success or raw exception text. */}
        finally{if(png!=null)Arrays.fill(png,(byte)0);if(wire!=null)Arrays.fill(wire,(byte)0);}
    }
    private static void checkTime(CardRegionCaptureProtocol.Request request)throws Stop{
        if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>=request.deadline())throw new Stop(Status.DEADLINE_EXCEEDED);
    }
    private static void requireShape(CardRegionCaptureProtocol.Request request)throws Exception{
        // Query the display service itself: no client DisplayInfo cache or callback registration.
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"display");
        if(!(binder instanceof IBinder))throw new Stop(Status.SHAPE_CHANGED);
        Object manager=Class.forName("android.hardware.display.IDisplayManager$Stub")
                .getMethod("asInterface",IBinder.class).invoke(null,binder);
        if(manager==null)throw new Stop(Status.SHAPE_CHANGED);
        Object info=Class.forName("android.hardware.display.IDisplayManager")
                .getMethod("getDisplayInfo",int.class).invoke(manager,0);
        if(info==null)throw new Stop(Status.SHAPE_CHANGED);
        Class<?> infoType=Class.forName("android.view.DisplayInfo");
        if(infoType.getField("logicalWidth").getInt(info)!=request.fullWidth()
                ||infoType.getField("logicalHeight").getInt(info)!=request.fullHeight()
                ||infoType.getField("rotation").getInt(info)!=0
                ||infoType.getField("state").getInt(info)!=android.view.Display.STATE_ON)throw new Stop(Status.SHAPE_CHANGED);
    }
    private NativeCardRegionCaptureHelper(){}
}
