package com.aliothmoon.maafw.once;

/** Expiry can retire clean read capabilities; it can never erase an explicit refusal. */
final class LivePerceptionBudget {
    static boolean retireCrop(LiveRewardsTypes.Phase phase,AndroidCardRegionCapture.FixedStatus status,
            CardRegionCaptureProtocol.Status helper,boolean clean,boolean expired){
        if(phase==null||phase==LiveRewardsTypes.Phase.BEFORE||!clean||status==AndroidCardRegionCapture.FixedStatus.CANCELLED)return false;
        if(status==AndroidCardRegionCapture.FixedStatus.INSUFFICIENT_BUDGET)return helper==null;
        boolean pastCaptureDeadline=status==AndroidCardRegionCapture.FixedStatus.READ_TIMEOUT
                ||helper==CardRegionCaptureProtocol.Status.DEADLINE_EXCEEDED;
        return crop(status,helper,true,expired||pastCaptureDeadline);
    }
    static boolean ui(AndroidLiveUiReader.FixedStatus status,NativeUiStructureProtocol.Reason helper,boolean clean,boolean expired){
        return clean&&expired&&status==AndroidLiveUiReader.FixedStatus.READ_TIMEOUT
                &&(helper==null||helper==NativeUiStructureProtocol.Reason.OK||helper==NativeUiStructureProtocol.Reason.TIMEOUT);
    }
    static boolean retireUi(LiveRewardsTypes.Phase phase, AndroidLiveUiReader.Result read){
        if(phase==null||phase==LiveRewardsTypes.Phase.BEFORE||!read.knownClean())return false;
        return read.fixedStatus()==AndroidLiveUiReader.FixedStatus.INSUFFICIENT_BUDGET&&!read.invoked()
                ||read.fixedStatus()==AndroidLiveUiReader.FixedStatus.BUDGET_RECEIPT&&read.invoked();
    }
    static boolean crop(AndroidCardRegionCapture.FixedStatus status,CardRegionCaptureProtocol.Status helper,boolean clean,boolean expired){
        if(!clean||!expired)return false;
        if(status==AndroidCardRegionCapture.FixedStatus.CAPTURE_REJECTED)return helper==CardRegionCaptureProtocol.Status.DEADLINE_EXCEEDED;
        return (status==AndroidCardRegionCapture.FixedStatus.READ_TIMEOUT||status==AndroidCardRegionCapture.FixedStatus.CANCELLED)
                &&(helper==null||helper==CardRegionCaptureProtocol.Status.CAPTURED||helper==CardRegionCaptureProtocol.Status.DEADLINE_EXCEEDED);
    }
    private LivePerceptionBudget(){}
}
