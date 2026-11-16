package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import org.junit.Test;

public class LivePerceptionBudgetTest {
    @Test public void cleanTimeoutCanRetireOnlyAfterTheAfterWindowHasEnded(){
        assertTrue(LivePerceptionBudget.ui(AndroidLiveUiReader.FixedStatus.READ_TIMEOUT,null,true,true));
        assertFalse(LivePerceptionBudget.ui(AndroidLiveUiReader.FixedStatus.READ_TIMEOUT,null,true,false));
        assertFalse(LivePerceptionBudget.ui(AndroidLiveUiReader.FixedStatus.READ_TIMEOUT,null,false,true));
        assertTrue(LivePerceptionBudget.crop(AndroidCardRegionCapture.FixedStatus.CAPTURE_REJECTED,CardRegionCaptureProtocol.Status.DEADLINE_EXCEEDED,true,true));
    }
    @Test public void explicitPermissionOrSourceOrCleanupRefusalsNeverBecomeBudgetExpiry(){
        for(var status:new AndroidLiveUiReader.FixedStatus[]{AndroidLiveUiReader.FixedStatus.AUTHORITY_REJECTED,AndroidLiveUiReader.FixedStatus.GATE_REJECTED,
                AndroidLiveUiReader.FixedStatus.PROTOCOL_REJECTED,AndroidLiveUiReader.FixedStatus.CLEANUP_UNKNOWN})
            assertFalse(LivePerceptionBudget.ui(status,null,true,true));
        for(var reason:new NativeUiStructureProtocol.Reason[]{NativeUiStructureProtocol.Reason.REFLECTION_DENIED,NativeUiStructureProtocol.Reason.UI_BLOCKER,
                NativeUiStructureProtocol.Reason.FOREIGN_NODE,NativeUiStructureProtocol.Reason.CLEANUP_UNKNOWN})
            assertFalse(LivePerceptionBudget.ui(AndroidLiveUiReader.FixedStatus.CANCELLED,reason,true,true));
        for(var status:CardRegionCaptureProtocol.Status.values())if(status!=CardRegionCaptureProtocol.Status.CAPTURED&&status!=CardRegionCaptureProtocol.Status.DEADLINE_EXCEEDED)
            assertFalse(LivePerceptionBudget.crop(AndroidCardRegionCapture.FixedStatus.CANCELLED,status,true,true));
        assertFalse(LivePerceptionBudget.crop(AndroidCardRegionCapture.FixedStatus.PERMISSION_DENIED,CardRegionCaptureProtocol.Status.PERMISSION_DENIED,true,true));
    }
}
