package com.aliothmoon.maafw.once;

/** Fixed local diagnostics only. Retains no owner, image, hash, text, path or exception. */
final class OverviewFailureDiagnostic {
    enum Checkpoint {
        NEW, HANDOFF, RUN_RECORD, FRAME_CLAIM, MODEL_PREPARATION, UI_READ,
        SOURCE_UI_AFTER, SOURCE_IMAGE_BEFORE, FRAME_BINDING, IMAGE_BINDING,
        IMAGE_BACKEND, SOURCE_AFTER, SEAL, PUBLICATION, PUBLICATION_WRITE,
        PUBLICATION_COMMIT, FRAME_FINISH, SCROLL_SOURCE, SCROLL_DISPATCH,
        SCROLL_RESULT, CLEANUP
    }
    private enum AuditRead { NOT_OBSERVED, OBSERVED, UNAVAILABLE }
    private Checkpoint checkpoint=Checkpoint.NEW, failure;
    private int frame;
    private OnceBackend.Phase nativePhase;
    private OnceBackend.Failure nativeFailure;
    private OnceBackend.Cancellation nativeCancellation;
    private OnceBackend.Cleanup nativeCleanup;
    private OnceBackend.Quiescence nativeQuiescence;
    private OnceBackend.Memory nativeMemory;
    private PngImageAudit.Status pngStatus;
    private MaaImageBufferAudit.Status maaStatus;
    private AuditRead auditRead=AuditRead.NOT_OBSERVED;
    private boolean fingerprintPresent;

    synchronized void at(Checkpoint next){if(failure==null&&next!=null)checkpoint=next;}
    /** A later frame cannot inherit a successful earlier frame's native audit. */
    synchronized void beginFrame(int index){
        if(failure!=null)return;
        frame=OverviewScanScope.validFrame(index)?index:0;checkpoint=Checkpoint.FRAME_CLAIM;
        nativePhase=null;nativeFailure=null;nativeCancellation=null;nativeCleanup=null;
        nativeQuiescence=null;nativeMemory=null;pngStatus=null;maaStatus=null;
        auditRead=AuditRead.NOT_OBSERVED;fingerprintPresent=false;
    }
    synchronized void nativeOutcome(OnceBackend.Result result){
        if(failure!=null||result==null)return;
        nativePhase=result.phase();nativeFailure=result.failure();nativeCancellation=result.cancellation();
        nativeCleanup=result.cleanup();nativeQuiescence=result.quiescence();nativeMemory=result.memory();
    }
    synchronized void imageAudits(PngImageAudit.Status png,MaaImageBufferAudit.Status maa,
            boolean fingerprint,boolean readAvailable){
        if(failure!=null)return;
        pngStatus=png;maaStatus=maa;fingerprintPresent=readAvailable&&fingerprint;
        auditRead=readAvailable?AuditRead.OBSERVED:AuditRead.UNAVAILABLE;
    }
    synchronized void failed(){failed(checkpoint);}
    synchronized void failed(Checkpoint exact){
        if(failure==null){if(exact!=null)checkpoint=exact;failure=checkpoint;}
    }
    private static String fixed(Enum<?> value){return value==null?"NOT_OBSERVED":value.name();}
    synchronized String summary(){
        return "diagnosticFrame="+frame+" lastCheckpoint="+checkpoint
                +" failureCheckpoint="+(failure==null?"NONE":failure.name())
                +" nativePhase="+fixed(nativePhase)+" nativeFailure="+fixed(nativeFailure)
                +" nativeCancellation="+fixed(nativeCancellation)+" nativeCleanup="+fixed(nativeCleanup)
                +" nativeQuiescence="+fixed(nativeQuiescence)+" nativeMemory="+fixed(nativeMemory)
                +" imageAuditRead="+auditRead+" pngAuditStatus="+fixed(pngStatus)
                +" maaAuditStatus="+fixed(maaStatus)+" maaFingerprintPresent="+fingerprintPresent;
    }
}
