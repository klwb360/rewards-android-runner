package com.aliothmoon.maafw.once;

import java.util.function.LongSupplier;

/** Staged source replacement only. Does not create/consume any grant or add an entry point. */
final class ReviewShizukuPngFrameSource implements BackendPorts.FrameSource {
    /** The actual produce call's monotonic envelope, not a compositor timestamp. */
    static final class SourceWindow {
        private final long entered, returned;
        private SourceWindow(long entered,long returned){this.entered=entered;this.returned=returned;}
        long entered(){return entered;} long returned(){return returned;}
    }
    /** Opaque actual-source evidence. No caller can construct one from booleans. */
    static final class CleanupSnapshot {
        private final OnceBackend.Owner owner;
        private final boolean ownerMatched,produceReturned,ownerQuiet,sourceReleased,sourceQuiet;
        private final PngCaptureSession.Snapshot capture;
        private CleanupSnapshot(OnceBackend.Owner owner,boolean matched,boolean returned,boolean quiet,
                boolean released,boolean sourceQuiet,PngCaptureSession.Snapshot capture) {
            this.owner=owner;ownerMatched=matched;produceReturned=returned;ownerQuiet=quiet;
            sourceReleased=released;this.sourceQuiet=sourceQuiet;this.capture=capture;
        }
        boolean ownerMatched(){return ownerMatched;}
        boolean produceReturned(){return produceReturned;}
        boolean ownerQuiet(){return ownerQuiet;}
        boolean childExitObserved(){return ownerMatched&&capture.childExitObserved();}
        boolean exitStatusObserved(){return ownerMatched&&capture.exitStatusObserved();}
        int childExitStatus(){return exitStatusObserved()?capture.childExitStatus():Integer.MIN_VALUE;}
        boolean clientPfdAllClosed(){return ownerMatched&&capture.clientPfdAllClosed();}
        PngCaptureSession.AbortState abortState(){return capture.abortState();}
        boolean sourceReleased(){return sourceReleased;}
        boolean sourceQuiet(){return sourceQuiet;}
        boolean pngAccepted(){return ownerMatched&&capture.pngAccepted();}
        PngCaptureSession.Snapshot diagnostic(){return capture;}
        boolean cleanFor(OnceBackend.Result result) {
            return result!=null&&result.owner()==owner&&ownerMatched&&produceReturned&&ownerQuiet
                    &&sourceReleased&&sourceQuiet&&childExitObserved()&&exitStatusObserved()&&childExitStatus()==0
                    &&clientPfdAllClosed()&&pngAccepted()&&result.phase()==OnceBackend.Phase.COMPLETED
                    &&result.cleanup()==OnceBackend.Cleanup.CONFIRMED&&result.quiescence()==OnceBackend.Quiescence.CONFIRMED
                    &&result.memory()==OnceBackend.Memory.WIPED;
        }
        String safeSummary(){
            return "rootStage="+capture.stage()+" rootFailure="+capture.failure()+" rootState="+capture.state()+" ownerMatched="+ownerMatched
                    +" produceReturned="+produceReturned+" ownerQuiet="+ownerQuiet+" childExit="+childExitObserved()
                    +" statusKnown="+exitStatusObserved()+" status="+childExitStatus()+" clientFdsClosed="+clientPfdAllClosed()
                    +" abort="+abortState()+" sourceReleased="+sourceReleased+" sourceQuiet="+sourceQuiet
                    +" png="+capture.png();
        }
    }
    private final PngCaptureSession capture;
    private final BackendPorts.RevocationFence fence;
    private final PngImageAudit audit;
    private final LongSupplier clock;
    private final boolean captureTiming;
    private OnceBackend.Owner owner;
    private boolean returned, releaseRequested, active;
    private long sourceEntered=-1,sourceReturned=-1;
    ReviewShizukuPngFrameSource(PngCaptureSession.Transport transport,PngCaptureSession.Gate existingGate,
            LongSupplier clock,BoundedPngGray.Geometry geometry,BackendPorts.RevocationFence existingFence) {
        this(transport,existingGate,clock,geometry,existingFence,null);
    }
    ReviewShizukuPngFrameSource(PngCaptureSession.Transport transport,PngCaptureSession.Gate existingGate,
            LongSupplier clock,BoundedPngGray.Geometry geometry,BackendPorts.RevocationFence existingFence,PngImageAudit.Decoder diagnosticDecoder) {
        this(transport,existingGate,clock,geometry,existingFence,diagnosticDecoder,false);
    }
    ReviewShizukuPngFrameSource(PngCaptureSession.Transport transport,PngCaptureSession.Gate existingGate,
            LongSupplier clock,BoundedPngGray.Geometry geometry,BackendPorts.RevocationFence existingFence,
            PngImageAudit.Decoder diagnosticDecoder,boolean captureTiming) {
        if(existingFence==null) throw new IllegalArgumentException("existing admission fence required");
        this.clock=clock;this.captureTiming=captureTiming;
        capture=new PngCaptureSession(transport,existingGate,clock,geometry);fence=existingFence;
        audit=diagnosticDecoder==null?null:new PngImageAudit(geometry,diagnosticDecoder,clock);
    }
    synchronized SourceWindow sourceWindow(OnceBackend.Owner expected){
        return captureTiming&&expected!=null&&expected==owner&&returned&&!active
                &&sourceEntered>=0&&sourceReturned>=sourceEntered
                ?new SourceWindow(sourceEntered,sourceReturned):null;
    }
    private long elapsedMillis(){long nanos=clock.getAsLong();return nanos<0?-1:nanos/1_000_000L;}
    PngImageAudit.Evidence imageAudit(){return audit==null?PngImageAudit.notInvoked():audit.evidence();}
    private boolean allQuiet(OnceBackend.Owner expected){return capture.quietAndReleased()&&(audit==null||audit.quietAndReleased(expected));}
    /** Future runtime latchCancel hook: scalar + independent async abort initiation.
     * Does not block UI on Binder and is not cleanup/exit evidence. */
    void requestStop(){fence.revoke();capture.requestStop();}
    synchronized CleanupSnapshot cleanupSnapshot(OnceBackend.Owner expected){
        PngCaptureSession.Snapshot snapshot=capture.snapshot();
        boolean matched=expected!=null&&expected==owner;
        boolean quiet=matched&&returned&&!active&&snapshot.ownerQuiet();
        boolean completed=quiet&&releaseRequested&&allQuiet(expected);
        return new CleanupSnapshot(owner,matched,matched&&returned,quiet,completed,completed,snapshot);
    }
    boolean cleanFor(OnceBackend.Result result){return cleanupSnapshot(result==null?null:result.owner()).cleanFor(result);}
    @Override public void produce(OnceBackend.Owner expected,BackendPorts.FrameSink sink,BackendPorts.StopSignal stop) throws Exception {
        synchronized(this) {
            if(expected==null||owner!=null||sink==null||stop==null) throw new IllegalStateException();
            owner=expected;active=true;
        }
        try {
            if(captureTiming){sourceEntered=elapsedMillis();if(sourceEntered<0)throw new IllegalStateException("Source clock unavailable");}
            BackendPorts.StopSignal combined=()->stop.requested()||fence.revoked();
            BoundedPngGray.Sink rows=new BoundedPngGray.Sink(){
                public void begin(int w,int h){sink.begin(w,h);}
                public void row(byte[] bytes){sink.append(bytes,0,bytes.length);}
            };
            if(audit==null)capture.capture(rows,combined::requested);
            else{
                audit.begin(expected,combined);
                BoundedPngGray.Receipt receipt=capture.capture(audit.forward(rows),combined::requested,audit::tee);
                audit.compare(capture,receipt);
            }
        } catch(BoundedPngGray.Cancelled cancelled) {
            if(stop.requested()||fence.revoked()) throw new BackendPorts.Cancelled();
            throw cancelled; // Internal deadline abort remains a source failure.
        } catch(Exception failed){
            if(audit!=null&&audit.evidence().status()==PngImageAudit.Status.CANCELLED&&(stop.requested()||fence.revoked()))throw new BackendPorts.Cancelled();
            throw failed;
        } finally {
            try{if(audit!=null){
                audit.closeOnce();if(!audit.quietAndReleased(expected))throw new java.io.IOException("Image audit cleanup unconfirmed");
                if(audit.evidence().status()==PngImageAudit.Status.CANCELLED)throw new BackendPorts.Cancelled();
                if(audit.evidence().status()==PngImageAudit.Status.DEADLINE)throw new java.io.IOException("Image audit deadline");
            }}
            finally{synchronized(this){
                if(captureTiming){try{sourceReturned=elapsedMillis();}catch(RuntimeException unavailable){sourceReturned=-1;}}
                returned=true;active=false;
            }}
        }
    }
    @Override public synchronized BackendPorts.RevocationFence revocationFence(OnceBackend.Owner expected) {
        require(expected);return fence;
    }
    @Override public synchronized void release(OnceBackend.Owner expected) {
        require(expected);
        if(!returned||active||releaseRequested) throw new IllegalStateException();
        releaseRequested=true; // Not another close/destroy attempt.
    }
    @Override public synchronized BackendPorts.Completion completion(OnceBackend.Owner expected) {
        require(expected);
        boolean confirmed=returned&&!active&&releaseRequested&&allQuiet(expected);
        return new BackendPorts.Completion(owner,confirmed?BackendPorts.Released.CONFIRMED:BackendPorts.Released.UNKNOWN,
                confirmed?BackendPorts.Quiet.CONFIRMED:BackendPorts.Quiet.UNKNOWN);
    }
    private void require(OnceBackend.Owner expected) {
        if(expected==null||expected!=owner) throw new SecurityException("wrong source owner");
    }
}
