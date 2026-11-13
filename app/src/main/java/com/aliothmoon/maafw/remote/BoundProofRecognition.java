package com.aliothmoon.maafw.remote;

import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import com.aliothmoon.maafw.maa.MaaFrameworkLibrary;
import com.sun.jna.Pointer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only, single-use diagnostic recognition. No actions or account access. */
public final class BoundProofRecognition implements MaaFrameworkLibrary.MaaCustomRecognitionCallback {
    private final Supplier<MaaFrameworkLibrary> library;
    private final Supplier<String> token;
    private final Supplier<String> task;
    private final BooleanSupplier stopped;

    public BoundProofRecognition(Supplier<MaaFrameworkLibrary> library, Supplier<String> token,
                                 Supplier<String> task, BooleanSupplier stopped) {
        this.library=library; this.token=token; this.task=task; this.stopped=stopped;
    }

    @Override public byte invoke(Pointer context, long taskId, String nodeName, String name,
                                  String param, Pointer ignoredCachedFrame, Pointer ignoredRoi,
                                  Pointer ignoredArgument, Pointer outBox, Pointer outDetail) {
        String key = token.get();
        FreshScreenCapture.Frame frame = null;
        Pointer image = null, details = null;
        MaaFrameworkLibrary lib = null;
        try {
            String expected = task.get();
            if (key == null || !key.matches("[0-9a-f]{64}") || stopped.getAsBoolean()
                    || Process.myUid() != Process.SHELL_UID || Build.VERSION.SDK_INT != 35
                    || !"ranchu".equals(Build.HARDWARE) || !Build.FINGERPRINT.contains("sdk_gphone")
                    || context == null || outBox == null || outDetail == null
                    || !"BoundCardOcr".equals(name) || param == null
                    || !("SearchCheck".equals(expected) || "ReadingCheck".equals(expected))
                    || !expected.equals(new JSONObject(param).optString("task"))) return 0;
            lib = library.get();
            if (lib == null) return 0;
            Bundle first = ProofClient.call("begin", key, null);
            if (first == null || stopped.getAsBoolean()) return 0;
            int width=first.getInt("width",-1), height=first.getInt("height",-1), rotation=first.getInt("rotation",-1);
            frame=FreshScreenCapture.take(SystemClock::elapsedRealtime, stopped,
                    () -> new ProcessBuilder("/system/bin/screencap", "-p").start());
            if (frame == null || frame.width != width || frame.height != height
                    || frame.startMs < first.getLong("firstFinishedMs",Long.MAX_VALUE) || stopped.getAsBoolean()) return 0;
            Bundle input=new Bundle();
            input.putInt("displayId",0); input.putInt("physicalWidth",width); input.putInt("physicalHeight",height);
            input.putInt("rotation",rotation); input.putInt("ocrWidth",width); input.putInt("ocrHeight",height);
            input.putLong("frameStart",frame.startMs); input.putLong("frameEnd",frame.endMs);
            input.putBoolean("fullDisplay",true); input.putBoolean("foregroundVerified",true);
            Bundle sealed=ProofClient.call("seal",key,input);
            if (sealed == null || stopped.getAsBoolean()) return 0;
            int[] roi=sealed.getIntArray("roi");
            if (roi == null || roi.length != 4 || roi[0] < 0 || roi[1] < 0 || roi[2] <= 0 || roi[3] <= 0
                    || (long)roi[0]+roi[2] > width || (long)roi[1]+roi[3] > height) return 0;
            image=lib.MaaImageBufferCreate(); details=lib.MaaStringBufferCreate();
            if (image == null || details == null || lib.MaaImageBufferSetEncoded(image,frame.png,frame.png.length) == 0)
                return 0;
            JSONObject options=new JSONObject().put("roi",new JSONArray(roi));
            long id=lib.MaaContextRunRecognitionDirect(context,"OCR",options.toString(),image);
            if (id == 0 || stopped.getAsBoolean()) return 0;
            Pointer tasker=lib.MaaContextGetTasker(context);
            if (tasker == null || lib.MaaTaskerGetRecognitionDetail(tasker,id,null,null,null,null,details,null,null) == 0)
                return 0;
            String result=lib.MaaStringBufferGet(details);
            if (result == null || result.length() > 128*1024 || stopped.getAsBoolean()) return 0;
            Bundle evidence=new Bundle(); evidence.putString("ocr",result);
            Bundle accepted=ProofClient.call("finish",key,evidence);
            if (accepted == null || stopped.getAsBoolean()) return 0;
            int current=accepted.getInt("current",-1), target=accepted.getInt("target",-1);
            boolean complete=accepted.getBoolean("complete",false);
            if (current < 0 || target <= 0 || current > target || complete != (current == target)) return 0;
            // The task's action is DoNothing; the marker is not used as a click coordinate.
            outBox.setInt(0,0); outBox.setInt(4,0); outBox.setInt(8,1); outBox.setInt(12,1);
            String safe=new JSONObject().put("current",current).put("target",target).put("complete",complete).toString();
            return lib.MaaStringBufferSet(outDetail,safe) != 0 && !stopped.getAsBoolean() ? (byte)1 : 0;
        } catch (Throwable failure) {
            // The callback must fail closed and never throw or log page text into native code.
            return 0;
        } finally {
            try { if (lib != null && details != null) lib.MaaStringBufferDestroy(details); } catch (Throwable ignored) { }
            try { if (lib != null && image != null) lib.MaaImageBufferDestroy(image); } catch (Throwable ignored) { }
            try { if (frame != null) frame.erase(); } catch (Throwable ignored) { }
            try { if (key != null) ProofClient.call("cancel",key,null); } catch (Throwable ignored) { }
        }
    }
}
