package com.aliothmoon.maafw.once;

/** Expiry can retire clean read capabilities; it can never erase an explicit refusal. */
final class LivePerceptionBudget {
    static boolean ui(AndroidLiveUiReader.FixedStatus status,NativeUiStructureProtocol.Reason helper,boolean clean,boolean expired){
        return clean&&expired&&(status==AndroidLiveUiReader.FixedStatus.READ_TIMEOUT||status==AndroidLiveUiReader.FixedStatus.CANCELLED)
                &&(helper==null||helper==NativeUiStructureProtocol.Reason.OK||helper==NativeUiStructureProtocol.Reason.TIMEOUT);
    }
    static boolean crop(AndroidCardRegionCapture.FixedStatus status,CardRegionCaptureProtocol.Status helper,boolean clean,boolean expired){
        if(!clean||!expired)return false;
        if(status==AndroidCardRegionCapture.FixedStatus.CAPTURE_REJECTED)return helper==CardRegionCaptureProtocol.Status.DEADLINE_EXCEEDED;
        return (status==AndroidCardRegionCapture.FixedStatus.READ_TIMEOUT||status==AndroidCardRegionCapture.FixedStatus.CANCELLED)
                &&(helper==null||helper==CardRegionCaptureProtocol.Status.CAPTURED||helper==CardRegionCaptureProtocol.Status.DEADLINE_EXCEEDED);
    }
    private LivePerceptionBudget(){}
}
