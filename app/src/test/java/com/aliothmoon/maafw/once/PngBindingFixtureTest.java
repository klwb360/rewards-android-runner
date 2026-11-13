package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicInteger;

/** Binding's real source/claim/coordinator path with an explicitly non-live transport. */
public final class PngBindingFixtureTest {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static BackendPorts.Recognizer recognizer(AtomicInteger calls){
        return new BackendPorts.Recognizer(){
            public BackendPorts.Recognition recognize(OnceBackend.Owner owner,BackendPorts.FrameView frame,BackendPorts.StopSignal stop){calls.incrementAndGet();return new BackendPorts.Recognition(owner,true);}
            public void release(OnceBackend.Owner owner){}
            public BackendPorts.Completion completion(OnceBackend.Owner owner){return new BackendPorts.Completion(owner,BackendPorts.Released.CONFIRMED,BackendPorts.Quiet.CONFIRMED);}
        };
    }
    private static ReviewShizukuPngBinding.Binding fixture(PngCaptureSession.Transport transport,PngSourceOfflineTest.Clock clock,AtomicInteger claims,AtomicInteger calls){
        return ReviewShizukuPngBinding.fixture(o->{claims.incrementAndGet();return new BackendPorts.ClaimReceipt(o,BackendPorts.ClaimDecision.CLAIMED);},
                transport,()->{},new BackendPorts.RevocationFence(),recognizer(calls),Runnable::run,clock,2,2);
    }
    public static void main(String[] args)throws Exception{
        AtomicInteger claims=new AtomicInteger(),calls=new AtomicInteger();PngSourceOfflineTest.Clock clock=new PngSourceOfflineTest.Clock();
        PngSourceOfflineTest.FakeProcess process=new PngSourceOfflineTest.FakeProcess(PngSourceOfflineTest.tiny(),clock);
        ReviewShizukuPngBinding.Binding binding=fixture(()->process,clock,claims,calls);
        check(binding.cleanReceipt()==null,"unstarted coordinator cannot issue receipt");
        OnceBackend.Result result=binding.backend().start();ReviewShizukuPngBinding.CleanReceipt receipt=binding.cleanReceipt();
        check(claims.get()==1&&calls.get()==1&&result.phase()==OnceBackend.Phase.COMPLETED,"same actual claim/source/recognizer path");
        check(receipt!=null&&receipt.clean()&&!receipt.liveTransport(),"clean fixture receipt cannot qualify live preflight");
        ReviewShizukuPngFrameSource.CleanupSnapshot snapshot=receipt.snapshot();
        check(snapshot.ownerMatched()&&snapshot.produceReturned()&&snapshot.ownerQuiet(),"actual source owner return/quiet");
        check(snapshot.childExitObserved()&&snapshot.exitStatusObserved()&&snapshot.childExitStatus()==0,"actual observed exit0");
        check(snapshot.clientPfdAllClosed()&&snapshot.sourceReleased()&&snapshot.sourceQuiet()&&snapshot.pngAccepted(),"actual PFD/source/png facts");
        check(snapshot.abortState()==PngCaptureSession.AbortState.NOT_REQUESTED&&result.memory()==OnceBackend.Memory.WIPED,"no abort needed and coordinator wiped");
        for(Class<?> type:new Class<?>[]{ReviewShizukuPngBinding.Binding.class,ReviewShizukuPngBinding.CleanReceipt.class,ReviewShizukuPngFrameSource.CleanupSnapshot.class})
            for(var constructor:type.getDeclaredConstructors())check(Modifier.isPrivate(constructor.getModifiers()),"evidence cannot be boolean/String constructed");
        // A valid PNG container with unsupported IHDR yields only safe numeric evidence.
        byte[] header=PngSourceOfflineTest.header(2,2,4,8,0);header[9]=7;
        byte[] wrong=PngSourceOfflineTest.pngCustom(header,PngSourceOfflineTest.deflate(PngSourceOfflineTest.rows(PngSourceOfflineTest.pixels(2,2,4),2,2,4,0)),7,null);
        PngSourceOfflineTest.Clock formatClock=new PngSourceOfflineTest.Clock();PngSourceOfflineTest.FakeProcess format=new PngSourceOfflineTest.FakeProcess(wrong,formatClock);
        ReviewShizukuPngBinding.Binding bad=fixture(()->format,formatClock,claims,calls);OnceBackend.Result badResult=bad.backend().start();
        ReviewShizukuPngFrameSource.CleanupSnapshot badSnapshot=bad.source().cleanupSnapshot(badResult.owner());
        check(bad.cleanReceipt()==null&&calls.get()==1&&!badSnapshot.pngAccepted(),"unsupported format no clean receipt/OCR");
        check(badSnapshot.diagnostic().stage()==PngCaptureSession.Stage.PNG_READ&&badSnapshot.diagnostic().png().stage()==BoundedPngGray.Stage.FORMAT,"safe format phase");
        check(badSnapshot.diagnostic().png().width()==2&&badSnapshot.diagnostic().png().height()==2&&badSnapshot.diagnostic().png().bitDepth()==8&&badSnapshot.diagnostic().png().colorType()==7,"IHDR numeric evidence");
        check(badSnapshot.childExitObserved()&&badSnapshot.clientPfdAllClosed()&&badSnapshot.ownerQuiet(),"failure still reports real cleanup");
        check(!binding.source().cleanupSnapshot(badResult.owner()).ownerMatched(),"wrong source owner never matches");
        // No-handle open failure remains UNKNOWN, with an exact phase and no receipt.
        PngSourceOfflineTest.Clock openClock=new PngSourceOfflineTest.Clock();ReviewShizukuPngBinding.Binding open=fixture(()->{throw new IOException("synthetic");},openClock,claims,calls);
        OnceBackend.Result openResult=open.backend().start();ReviewShizukuPngFrameSource.CleanupSnapshot openSnapshot=open.source().cleanupSnapshot(openResult.owner());
        check(open.cleanReceipt()==null&&openResult.phase()==OnceBackend.Phase.CLEANUP_UNCONFIRMED&&openSnapshot.diagnostic().stage()==PngCaptureSession.Stage.FIXED_ARGV_OPEN,"open unknown isolated, precise stage");
        check(!openSnapshot.childExitObserved()&&!openSnapshot.sourceQuiet()&&calls.get()==1,"unknown no exit/quiet/OCR");
        // Pixel bytes alone do not qualify: absent child exit ack keeps frame retained.
        PngSourceOfflineTest.Clock exitClock=new PngSourceOfflineTest.Clock();PngSourceOfflineTest.FakeProcess noExit=new PngSourceOfflineTest.FakeProcess(PngSourceOfflineTest.tiny(),exitClock);noExit.exited=false;
        ReviewShizukuPngBinding.Binding unknown=fixture(()->noExit,exitClock,claims,calls);OnceBackend.Result unknownResult=unknown.backend().start();
        ReviewShizukuPngFrameSource.CleanupSnapshot unknownSnapshot=unknown.source().cleanupSnapshot(unknownResult.owner());
        check(unknown.cleanReceipt()==null&&unknownResult.memory()==OnceBackend.Memory.RETAINED&&unknownResult.phase()==OnceBackend.Phase.CLEANUP_UNCONFIRMED,"unknown exit retains actual OwnedFrame/graph");
        check(unknownSnapshot.diagnostic().stage()==PngCaptureSession.Stage.CHILD_EXIT&&unknownSnapshot.abortState()==PngCaptureSession.AbortState.RETURNED&&!unknownSnapshot.sourceQuiet()&&calls.get()==1,"abort return is not child exit");
        PngSourceOfflineTest.Clock statusClock=new PngSourceOfflineTest.Clock();AtomicInteger statusCalls=new AtomicInteger();
        PngSourceOfflineTest.FakeProcess noStatus=new PngSourceOfflineTest.FakeProcess(PngSourceOfflineTest.tiny(),statusClock){
            public int exitCode(){statusCalls.incrementAndGet();throw new IllegalStateException("synthetic uncertain status");}
        };
        ReviewShizukuPngBinding.Binding status=fixture(()->noStatus,statusClock,claims,calls);OnceBackend.Result statusResult=status.backend().start();
        var statusSnapshot=status.source().cleanupSnapshot(statusResult.owner());
        check(statusCalls.get()==1&&!statusSnapshot.exitStatusObserved()&&status.cleanReceipt()==null,"uncertain status never retried or qualified");
        check(statusSnapshot.diagnostic().failure()==PngCaptureSession.Failure.CHILD_STATUS&&calls.get()==1,"status fault distinguishable with no OCR");
        System.out.println("PASS "+assertions+" binding/typed-evidence assertions; fake transport shares real source/claim/OnceBackend path, live receipt remains impossible.");
    }
}
