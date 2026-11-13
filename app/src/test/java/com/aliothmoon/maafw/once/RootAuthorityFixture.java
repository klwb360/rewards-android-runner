package com.aliothmoon.maafw.once;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.*;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** Non-live actual claim -> PNG source -> OwnedFrame -> typed native-observation fixture. */
final class RootAuthorityFixture {
    final Object visible=new Object(),request=new Object(),rootOwner=new Object(),requestOwner=new Object();
    final RealCaptureClaim.ProcessEpoch epoch=new RealCaptureClaim.ProcessEpoch();
    final AtomicLong now=new AtomicLong(1000),utc=new AtomicLong(RootCaptureScope.STARTS_UTC+1000);
    final AtomicInteger burns=new AtomicInteger(),sourceCalls=new AtomicInteger(),recognizerCalls=new AtomicInteger();
    final RootCaptureIntent intent;
    final RealCaptureClaim.Grant grant;
    final ReadOnlyTwoStageRun.Capture capture;
    final AtomicReference<ReadOnlyTwoStageRun.Observation> observation=new AtomicReference<>();
    final ReviewShizukuPngBinding.Binding binding;
    RootAuthorityFixture(boolean uncertainBurn)throws Exception{
        intent=VisibleCaptureAuthority.fixtureRootIntent(visible,request,epoch,rootOwner,RootCaptureScope.Mode.PREFLIGHT,now.get(),utc::get,null);
        grant=VisibleCaptureAuthority.fixtureRootGrant(intent,RootCaptureScope.Phase.FIRST,epoch,requestOwner,now.get(),now.get()+50_000,null);
        capture=new ReadOnlyTwoStageRun.Capture(requestOwner,ReadOnlyTwoStageRun.Phase.FIRST,"fixture-root-preflight",
            AutonomousCardFixture.W,AutonomousCardFixture.H,0,now.get(),()->!intent.current(now.get()));
        byte[] png=png(AutonomousCardFixture.pixels(false,false),AutonomousCardFixture.W,AutonomousCardFixture.H);
        BackendPorts.ClaimBoundary claim=owner->{
            RealCaptureClaim.Outcome result=RealCaptureClaim.consume(grant,epoch,requestOwner,now.get(),(id,metadata)->{
                burns.incrementAndGet();if(uncertainBurn)throw new IOException("fixture uncertain burn");
            });
            return new BackendPorts.ClaimReceipt(owner,result==RealCaptureClaim.Outcome.CONSUMED?BackendPorts.ClaimDecision.CLAIMED:
                result==RealCaptureClaim.Outcome.UNCERTAIN?BackendPorts.ClaimDecision.UNCERTAIN:BackendPorts.ClaimDecision.DENIED);
        };
        BackendPorts.Recognizer recognizer=new BackendPorts.Recognizer(){
            public BackendPorts.Recognition recognize(OnceBackend.Owner owner,BackendPorts.FrameView view,BackendPorts.StopSignal stop){
                recognizerCalls.incrementAndGet();byte[] gray=new byte[view.size()];
                try{for(int i=0;i<gray.length;i++)gray[i]=(byte)view.unsignedByte(i);
                    var value=capture.prepare(gray,view.width(),view.height(),AutonomousCardFixture.rows(false,false),stop::requested);
                    observation.set(value);return new BackendPorts.Recognition(owner,value!=null&&value.validNativeShape());
                }finally{Arrays.fill(gray,(byte)0);}
            }
            public void release(OnceBackend.Owner owner){}
            public BackendPorts.Completion completion(OnceBackend.Owner owner){return new BackendPorts.Completion(owner,BackendPorts.Released.CONFIRMED,BackendPorts.Quiet.CONFIRMED);}
        };
        binding=ReviewShizukuPngBinding.fixture(claim,()->{
            sourceCalls.incrementAndGet();ByteArrayInputStream input=new ByteArrayInputStream(png);
            return new PngCaptureSession.Process(){
                public BoundedPngGray.Input adoptStdout(){return (b,off,len,wait)->input.read(b,off,len);}
                public boolean waitForExit(int millis){return true;}public int exitCode(){return 0;}
                public void destroy(){}public boolean closeDescriptors()throws Exception{input.close();return true;}
            };
        },()->{if(!intent.current(now.get())||!RealCaptureClaim.current(grant,now.get()))throw new SecurityException("fixture expired");},
            new BackendPorts.RevocationFence(),recognizer,Runnable::run,()->0L,AutonomousCardFixture.W,AutonomousCardFixture.H);
    }
    CaptureSessionRuntime.RootPublication publish() {
        return CaptureSessionRuntime.RootPublication.fixturePreflight(intent,RootCaptureScope.Phase.FIRST,epoch,rootOwner,requestOwner,
            RealCaptureClaim.deadline(grant),binding.cleanReceipt(),observation.get(),now.get());
    }
    static byte[] png(byte[] gray,int width,int height)throws Exception{
        ByteArrayOutputStream headerBytes=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(headerBytes);
        h.writeInt(width);h.writeInt(height);h.write(new byte[]{8,2,0,0,0});
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        try(DeflaterOutputStream z=new DeflaterOutputStream(compressed)){
            for(int y=0;y<height;y++){z.write(0);for(int x=0;x<width;x++){int value=gray[y*width+x]&255;z.write(value);z.write(value);z.write(value);}}
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        chunk(out,"IHDR",headerBytes.toByteArray());chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);return out.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream out,String kind,byte[] payload)throws Exception{
        byte[] type=kind.getBytes(StandardCharsets.US_ASCII);DataOutputStream d=new DataOutputStream(out);CRC32 crc=new CRC32();
        crc.update(type);crc.update(payload);d.writeInt(payload.length);d.write(type);d.write(payload);d.writeInt((int)crc.getValue());
    }
}