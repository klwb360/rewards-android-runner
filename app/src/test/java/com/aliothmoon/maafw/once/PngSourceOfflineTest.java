package com.aliothmoon.maafw.once;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Generates tiny synthetic PNGs only. No Android runtime, network, shell or device. */
public final class PngSourceOfflineTest {
    private static int checks;
    private static void check(boolean yes,String what){checks++;if(!yes)throw new AssertionError(what);}
    interface Action{void run() throws Exception;}
    private static void rejects(Action action,String what)throws Exception{
        boolean rejected=false;try{action.run();}catch(Exception expected){rejected=true;}
        check(rejected,what);
    }
    static final class Clock implements LongSupplier {long now;public long getAsLong(){return now;}}
    static final class Pipe implements BoundedPngGray.Input {
        byte[] data;int p,step;Clock clock;boolean stall;int invalid;
        Pipe(byte[] data,int step,Clock clock){this.data=data;this.step=step;this.clock=clock;}
        public int read(byte[] out,int off,int len,int wait){
            clock.now+=stall?100_000_000L:1;
            if(invalid!=0)return invalid;
            if(stall)return 0;
            if(p==data.length)return -1;
            int n=Math.min(Math.min(len,step),data.length-p);System.arraycopy(data,p,out,off,n);p+=n;return n;
        }
    }
    static final class Sink implements BoundedPngGray.Sink {
        int w,h;List<byte[]> copies=new ArrayList<>();List<byte[]> borrowed=new ArrayList<>();
        public void begin(int width,int height){w=width;h=height;}
        public void row(byte[] bytes){copies.add(bytes.clone());borrowed.add(bytes);}
    }
    static int paeth(int a,int b,int c){int p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);return pa<=pb&&pa<=pc?a:pb<=pc?b:c;}
    static byte[] pixels(int w,int h,int bpp){
        byte[] p=new byte[w*h*bpp];for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int at=(y*w+x)*bpp;p[at]=(byte)(17+x*31+y*73);p[at+1]=(byte)(93+x*17+y*13);p[at+2]=(byte)(181+x*5+y*41);
            if(bpp==4)p[at+3]=(byte)255;
        }return p;
    }
    static byte[] rows(byte[] pixels,int w,int h,int bpp,int filter){
        byte[] rows=new byte[(w*bpp+1)*h];for(int y=0;y<h;y++){
            int row=y*(w*bpp+1);rows[row]=(byte)filter;
            for(int x=0;x<w*bpp;x++){
                int at=y*w*bpp+x,a=x>=bpp?pixels[at-bpp]&255:0,b=y>0?pixels[at-w*bpp]&255:0,c=y>0&&x>=bpp?pixels[at-w*bpp-bpp]&255:0;
                int pred=switch(filter){case 0->0;case 1->a;case 2->b;case 3->(a+b)/2;default->paeth(a,b,c);};
                rows[row+x+1]=(byte)((pixels[at]&255)-pred);
            }
        }return rows;
    }
    static byte[] deflate(byte[] rows){
        Deflater d=new Deflater();d.setInput(rows);d.finish();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[128];
        try{while(!d.finished()){int n=d.deflate(b);out.write(b,0,n);}return out.toByteArray();}finally{d.end();}
    }
    static byte[] chunk(String type,byte[] bytes)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);byte[] t=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        d.writeInt(bytes.length);d.write(t);d.write(bytes);CRC32 crc=new CRC32();crc.update(t);crc.update(bytes);d.writeInt((int)crc.getValue());return out.toByteArray();
    }
    static byte[] header(int w,int h,int bpp,int depth,int interlace)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);d.writeInt(w);d.writeInt(h);d.write(new byte[]{(byte)depth,(byte)(bpp==4?6:2),0,0,(byte)interlace});return out.toByteArray();
    }
    static byte[] png(int w,int h,int bpp,byte[] rowData,int split,byte[] ancillary)throws Exception{
        return pngCustom(header(w,h,bpp,8,0),deflate(rowData),split,ancillary);
    }
    static byte[] pngCustom(byte[] header,byte[] z,int split,byte[] ancillary)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});out.write(chunk("IHDR",header));
        if(ancillary!=null)out.write(ancillary);
        for(int p=0;p<z.length;p+=split)out.write(chunk("IDAT",Arrays.copyOfRange(z,p,Math.min(z.length,p+split))));
        out.write(chunk("IEND",new byte[0]));return out.toByteArray();
    }
    static Sink decode(byte[] png,int w,int h,int step)throws Exception{
        Clock clock=new Clock();Sink sink=new Sink();new BoundedPngGray(new Pipe(png,step,clock),sink,()->false,clock,0,5_000_000_000L,new BoundedPngGray.Geometry(w,h)).decode();return sink;
    }
    static byte[] tiny()throws Exception{return png(2,2,4,rows(pixels(2,2,4),2,2,4,0),7,null);}
    static void parserTests()throws Exception{
        for(int bpp:new int[]{3,4})for(int filter=0;filter<5;filter++)for(int split:new int[]{1,7,8192}){
            int w=5,h=3;byte[] p=pixels(w,h,bpp),png=png(w,h,bpp,rows(p,w,h,bpp,filter),split,null);Sink s=decode(png,w,h,1);
            check(s.copies.size()==h&&s.w==w&&s.h==h,"geometry/rows");
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int i=(y*w+x)*bpp,gray=(77*(p[i]&255)+150*(p[i+1]&255)+29*(p[i+2]&255)+128)>>>8;
                check((s.copies.get(y)[x]&255)==gray,"filter/RGB luminance");
            }
            for(byte[] row:s.borrowed)check(Arrays.equals(row,new byte[w]),"borrowed row wiped");
        }
        byte[] good=tiny(),bad=good.clone();bad[0]=0;byte[] signature=bad;
        rejects(()->decode(signature,2,2,3),"signature");
        bad=good.clone();bad[29]^=1;byte[] crc=bad;rejects(()->decode(crc,2,2,3),"CRC");
        rejects(()->decode(Arrays.copyOf(good,good.length-1),2,2,3),"truncated");
        rejects(()->decode(Arrays.copyOf(good,good.length+1),2,2,3),"trailing");
        rejects(()->decode(good,3,2,3),"geometry mismatch");
        byte[] r=rows(pixels(2,2,4),2,2,4,0);
        rejects(()->decode(pngCustom(header(2,2,4,16,0),deflate(r),7,null),2,2,3),"16-bit rejected");
        rejects(()->decode(pngCustom(header(2,2,4,8,1),deflate(r),7,null),2,2,3),"interlace rejected");
        byte[] invalidFilter=r.clone();invalidFilter[0]=5;
        rejects(()->decode(png(2,2,4,invalidFilter,7,null),2,2,3),"filter rejected");
        byte[] alpha=r.clone();alpha[4]=0;
        rejects(()->decode(png(2,2,4,alpha,7,null),2,2,3),"alpha rejected");
        rejects(()->decode(png(2,2,4,Arrays.copyOf(r,r.length+1),7,null),2,2,3),"inflation overrun");
        rejects(()->decode(png(2,2,4,Arrays.copyOf(r,r.length-1),7,null),2,2,3),"inflation underrun");
        byte[] z=deflate(r),trailingZ=Arrays.copyOf(z,z.length+1);
        rejects(()->decode(pngCustom(header(2,2,4,8,0),trailingZ,8192,null),2,2,3),"zlib trailing");
        rejects(()->decode(png(2,2,4,r,7,chunk("tEXt",new byte[1])),2,2,3),"text metadata rejected");
        check(decode(png(2,2,4,r,7,chunk("sRGB",new byte[]{0})),2,2,3).copies.size()==2,"sRGB accepted");
        byte[] huge=good.clone();huge[33]=0x7f;huge[34]=(byte)0xff;huge[35]=(byte)0xff;huge[36]=(byte)0xff;
        rejects(()->decode(huge,2,2,3),"chunk bounds before allocation");
        rejects(()->new BoundedPngGray.Geometry(4097,1),"dimension cap");
        rejects(()->new BoundedPngGray.Geometry(4096,4096),"pixel cap");
        rejects(()->new BoundedPngGray.Geometry(Integer.MAX_VALUE,Integer.MAX_VALUE),"integer cap");
        Clock c=new Clock();Pipe stalled=new Pipe(good,1,c);stalled.stall=true;
        rejects(()->new BoundedPngGray(stalled,new Sink(),()->false,c,0,5_000_000_000L,new BoundedPngGray.Geometry(2,2)).decode(),"deadline with zero-data pipe");
        check(c.now==5_000_000_000L,"deadline bounded");
        Clock cancelled=new Clock();rejects(()->new BoundedPngGray(new Pipe(good,1,cancelled),new Sink(),()->true,cancelled,0,5_000_000_000L,new BoundedPngGray.Geometry(2,2)).decode(),"cancel before read");
        Clock invalid=new Clock();Pipe invalidPipe=new Pipe(good,1,invalid);invalidPipe.invalid=99;
        rejects(()->new BoundedPngGray(invalidPipe,new Sink(),()->false,invalid,0,5_000_000_000L,new BoundedPngGray.Geometry(2,2)).decode(),"invalid pipe contract");
        // Structural mutations including valid-data rows that later fail CRC must not publish.
        for(int cut=0;cut<good.length;cut++){
            final int end=cut;rejects(()->decode(Arrays.copyOf(good,end),2,2,5),"every truncated prefix");
        }
    }
    static class FakeProcess implements PngCaptureSession.Process {
        final Pipe pipe;int adopts,waits,destroys,closes,code;
        boolean exited=true,closed=true,adoptThrows,destroyThrows;
        long waitAdvance,exitAdvance;
        FakeProcess(byte[] png,Clock c){pipe=new Pipe(png,3,c);}
        public BoundedPngGray.Input adoptStdout()throws Exception{adopts++;if(adoptThrows)throw new IOException();return pipe;}
        public boolean waitForExit(int millis){waits++;pipe.clock.now+=waitAdvance;return exited;}
        public int exitCode(){pipe.clock.now+=exitAdvance;return code;}
        public void destroy()throws Exception{destroys++;if(destroyThrows)throw new IOException();}
        public boolean closeDescriptors(){closes++;return closed;}
    }
    static PngCaptureSession session(FakeProcess p,Clock c){return new PngCaptureSession(()->p,()->{},c,new BoundedPngGray.Geometry(2,2));}
    static void lifecycleTests()throws Exception{
        Clock c=new Clock();FakeProcess p=new FakeProcess(tiny(),c);PngCaptureSession s=session(p,c);s.capture(new Sink(),()->false);
        check(s.quietAndReleased()&&p.adopts==1&&p.waits==1&&p.closes==1&&p.destroys==0,"success exit/FD ordering");
        rejects(()->s.capture(new Sink(),()->false),"no second capture");check(p.adopts==1,"no second open");
        Clock failClock=new Clock();FakeProcess fail=new FakeProcess(new byte[]{1},failClock);PngCaptureSession fs=session(fail,failClock);
        rejects(()->fs.capture(new Sink(),()->false),"parse fails");
        check(fs.quietAndReleased()&&fail.destroys==1&&fail.waits==1&&fail.closes==1,"error cleanup exact once");
        Clock nonzeroClock=new Clock();FakeProcess nonzero=new FakeProcess(tiny(),nonzeroClock);nonzero.code=1;PngCaptureSession ns=session(nonzero,nonzeroClock);
        rejects(()->ns.capture(new Sink(),()->false),"nonzero not success");check(ns.quietAndReleased()&&nonzero.destroys==0,"observed nonzero exit clean");
        AtomicInteger opened=new AtomicInteger();Clock gateClock=new Clock();PngCaptureSession gs=new PngCaptureSession(()->{opened.incrementAndGet();return null;},()->{throw new IOException();},gateClock,new BoundedPngGray.Geometry(2,2));
        rejects(()->gs.capture(new Sink(),()->false),"gate rejection");check(opened.get()==0&&gs.quietAndReleased(),"gate rejection no process");
        // A destroy exception is not a retry trigger; observed exit can still prove quiet.
        Clock destroyClock=new Clock();FakeProcess destroy=new FakeProcess(new byte[]{1},destroyClock);destroy.destroyThrows=true;PngCaptureSession ds=session(destroy,destroyClock);
        rejects(()->ds.capture(new Sink(),()->false),"destroy throws");check(ds.quietAndReleased()&&destroy.destroys==1&&destroy.closes==1,"destroy throw then actual exit");
        Clock lateWaitClock=new Clock();FakeProcess lateWait=new FakeProcess(tiny(),lateWaitClock);lateWait.waitAdvance=5_000_000_000L;PngCaptureSession lw=session(lateWait,lateWaitClock);
        rejects(()->lw.capture(new Sink(),()->false),"late true wait cannot publish");check(lw.quietAndReleased(),"late true exit can still prove cleanup");
        Clock lateExitClock=new Clock();FakeProcess lateExit=new FakeProcess(tiny(),lateExitClock);lateExit.exitAdvance=5_000_000_000L;PngCaptureSession le=session(lateExit,lateExitClock);
        rejects(()->le.capture(new Sink(),()->false),"late exitCode cannot publish");
        Clock lateGateClock=new Clock();FakeProcess lateGate=new FakeProcess(tiny(),lateGateClock);AtomicInteger gates=new AtomicInteger();
        PngCaptureSession lg=new PngCaptureSession(()->lateGate,()->{if(gates.incrementAndGet()==3)lateGateClock.now+=5_000_000_000L;},lateGateClock,new BoundedPngGray.Geometry(2,2));
        rejects(()->lg.capture(new Sink(),()->false),"late final gate cannot publish");
        Clock setupClock=new Clock();FakeProcess setupProcess=new FakeProcess(tiny(),setupClock);
        PngCaptureSession setup=new PngCaptureSession(()->setupProcess,()->{},()->{throw new IllegalStateException("clock unavailable");},new BoundedPngGray.Geometry(2,2));
        rejects(()->setup.capture(new Sink(),()->false),"clock setup exception");check(setup.quietAndReleased()&&setupProcess.adopts==0,"setup exception known no acquisition");
    }
    static void independentAbortTests()throws Exception{
        Clock c=new Clock();CountDownLatch adopting=new CountDownLatch(1),resume=new CountDownLatch(1),destroying=new CountDownLatch(1);
        AtomicReference<Thread> destroyThread=new AtomicReference<>();AtomicReference<Throwable> result=new AtomicReference<>();
        FakeProcess p=new FakeProcess(tiny(),c){
            public BoundedPngGray.Input adoptStdout()throws Exception{adopting.countDown();if(!resume.await(2,TimeUnit.SECONDS))throw new IOException("test owner stalled");return super.adoptStdout();}
            public void destroy()throws Exception{destroyThread.set(Thread.currentThread());super.destroy();destroying.countDown();}
        };
        PngCaptureSession s=session(p,c);Thread owner=new Thread(()->{try{s.capture(new Sink(),()->false);}catch(Throwable e){result.set(e);}},"test-source-owner");
        owner.start();check(adopting.await(1,TimeUnit.SECONDS),"owner is blocked after process publication");
        Thread caller=Thread.currentThread();s.requestStop();
        check(destroying.await(1,TimeUnit.SECONDS)&&destroyThread.get()!=caller&&destroyThread.get()!=owner,"independent abort while owner blocked, no Binder on caller");
        resume.countDown();owner.join(2000);
        check(!owner.isAlive()&&result.get() instanceof BoundedPngGray.Cancelled&&s.quietAndReleased()&&p.destroys==1,"cancelled owner joins actual abort/exit");
        // Unknown in-flight newProcess has no guessed PID. A late returned handle is
        // adopted and aborted once, not discarded or used for a second capture.
        Clock lateClock=new Clock();FakeProcess late=new FakeProcess(tiny(),lateClock);CountDownLatch opening=new CountDownLatch(1),returnOpen=new CountDownLatch(1);AtomicReference<Throwable> lateResult=new AtomicReference<>();
        PngCaptureSession lateSession=new PngCaptureSession(()->{opening.countDown();if(!returnOpen.await(2,TimeUnit.SECONDS))throw new IOException();return late;},()->{},lateClock,new BoundedPngGray.Geometry(2,2));
        Thread lateOwner=new Thread(()->{try{lateSession.capture(new Sink(),()->false);}catch(Throwable e){lateResult.set(e);}},"test-late-owner");lateOwner.start();
        check(opening.await(1,TimeUnit.SECONDS),"newProcess is in flight");lateSession.requestStop();
        check(late.destroys==0&&!lateSession.quietAndReleased(),"no returned handle cannot prove stop");returnOpen.countDown();lateOwner.join(2000);
        check(!lateOwner.isAlive()&&lateResult.get() instanceof BoundedPngGray.Cancelled&&late.destroys==1&&lateSession.quietAndReleased(),"late known handle aborted once");
    }
    static void backendTests()throws Exception{
        Clock c=new Clock();FakeProcess p=new FakeProcess(tiny(),c);BackendPorts.RevocationFence fence=new BackendPorts.RevocationFence();
        ReviewShizukuPngFrameSource src=new ReviewShizukuPngFrameSource(()->p,()->{},c,new BoundedPngGray.Geometry(2,2),fence);
        AtomicInteger recognized=new AtomicInteger();BackendPorts.Recognizer rec=new BackendPorts.Recognizer(){
            public BackendPorts.Recognition recognize(OnceBackend.Owner owner,BackendPorts.FrameView frame,BackendPorts.StopSignal stop){
                check(p.closes==1,"all local PFDs closed before OCR");check(frame.width()==2&&frame.height()==2&&frame.size()==4,"owned gray frame");recognized.incrementAndGet();return new BackendPorts.Recognition(owner,true);
            }
            public void release(OnceBackend.Owner o){}
            public BackendPorts.Completion completion(OnceBackend.Owner o){return new BackendPorts.Completion(o,BackendPorts.Released.CONFIRMED,BackendPorts.Quiet.CONFIRMED);}
        };
        OnceBackend b=new OnceBackend(o->new BackendPorts.ClaimReceipt(o,BackendPorts.ClaimDecision.CLAIMED),src,rec,Runnable::run);
        OnceBackend.Result result=b.start();check(result.phase()==OnceBackend.Phase.COMPLETED&&result.hit()&&result.memory()==OnceBackend.Memory.WIPED&&recognized.get()==1,"OnceBackend successful reuse/wipe");
        Clock revokedClock=new Clock();FakeProcess revoked=new FakeProcess(tiny(),revokedClock);BackendPorts.RevocationFence rf=new BackendPorts.RevocationFence();rf.revoke();
        ReviewShizukuPngFrameSource rs=new ReviewShizukuPngFrameSource(()->revoked,()->{},revokedClock,new BoundedPngGray.Geometry(2,2),rf);
        OnceBackend rb=new OnceBackend(o->new BackendPorts.ClaimReceipt(o,BackendPorts.ClaimDecision.CLAIMED),rs,rec,Runnable::run);
        OnceBackend.Result rr=rb.start();check(rr.phase()==OnceBackend.Phase.CANCELLED&&!rr.hit()&&recognized.get()==1&&revoked.adopts==0,"clean revocation CANCELLED, no capture/OCR");
        Clock badClock=new Clock();FakeProcess bad=new FakeProcess(new byte[]{1},badClock);ReviewShizukuPngFrameSource bs=new ReviewShizukuPngFrameSource(()->bad,()->{},badClock,new BoundedPngGray.Geometry(2,2),new BackendPorts.RevocationFence());
        OnceBackend bb=new OnceBackend(o->new BackendPorts.ClaimReceipt(o,BackendPorts.ClaimDecision.CLAIMED),bs,rec,Runnable::run);
        OnceBackend.Result br=bb.start();check(br.phase()==OnceBackend.Phase.FAILED&&!br.hit()&&recognized.get()==1,"parse failure no OCR");
    }
    static void unknownTests()throws Exception{
        // Four deliberately uncertain instances are retained for this JVM, then cap
        // prevents more process creation. Never reset production retention for tests.
        Clock c1=new Clock();FakeProcess p1=new FakeProcess(tiny(),c1);p1.exited=false;PngCaptureSession s1=session(p1,c1);
        rejects(()->s1.capture(new Sink(),()->false),"exit unknown");check(s1.state()==PngCaptureSession.State.UNKNOWN&&p1.destroys==1&&p1.closes==1&&!s1.quietAndReleased(),"destroy is not exit");
        rejects(()->s1.capture(new Sink(),()->false),"unknown no retry");check(p1.destroys==1,"unknown no second destroy");
        Clock c2=new Clock();PngCaptureSession s2=new PngCaptureSession(()->{throw new IOException();},()->{},c2,new BoundedPngGray.Geometry(2,2));
        rejects(()->s2.capture(new Sink(),()->false),"open IPC throws");check(s2.state()==PngCaptureSession.State.UNKNOWN,"unknown creation retained");
        Clock c3=new Clock();FakeProcess p3=new FakeProcess(tiny(),c3);p3.adoptThrows=true;p3.closed=false;PngCaptureSession s3=session(p3,c3);
        rejects(()->s3.capture(new Sink(),()->false),"FD getter unknown");check(s3.state()==PngCaptureSession.State.UNKNOWN&&p3.closes==1,"unknown descriptor retention");
        Clock c4=new Clock();CountDownLatch unblockAbort=new CountDownLatch(1),abortEnded=new CountDownLatch(1);
        FakeProcess p4=new FakeProcess(new byte[]{1},c4){
            public void destroy()throws Exception{super.destroy();try{unblockAbort.await();}finally{abortEnded.countDown();}}
        };p4.closed=false;PngCaptureSession s4=session(p4,c4);
        rejects(()->s4.capture(new Sink(),()->false),"close unknown");check(s4.state()==PngCaptureSession.State.UNKNOWN&&p4.closes==1,"FD close never retried");
        check(p4.destroys==1&&p4.waits==0&&!s4.quietAndReleased(),"stuck abort is not exit/quiet; no second wait queued");
        unblockAbort.countDown();check(abortEnded.await(1,TimeUnit.SECONDS)&&s4.state()==PngCaptureSession.State.UNKNOWN,"late abort return does not auto-retry/promote UNKNOWN");
        Clock c5=new Clock();FakeProcess p5=new FakeProcess(tiny(),c5);PngCaptureSession s5=session(p5,c5);
        rejects(()->s5.capture(new Sink(),()->false),"retention cap");check(p5.adopts==0&&p5.destroys==0&&s5.quietAndReleased(),"cap known no process call, clean terminal rejection");
        rejects(()->s5.capture(new Sink(),()->false),"cap rejection cannot retry");
    }
    public static void main(String[] args)throws Exception{
        parserTests();lifecycleTests();independentAbortTests();backendTests();unknownTests();System.out.println("PASS "+checks+" offline assertions across 5 scenario groups (not "+checks+" independent test cases); synthetic PNG/fake pipe only; no Gradle, ADB or capture execution.");
    }
}
