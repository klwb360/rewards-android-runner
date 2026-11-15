package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.UiImageGeometryBindingTest.ready;

public class CardRevealProbeSourceTest {
    private boolean before(long now,long deadline){return CardRevealProbeSource.beforeCrop(
            OverviewFrameCaptureTest.report(),ready(900),950,ready(2200),ready(2350),ready(2350).profile().shape(),2400,deadline,now);}
    private boolean after(long started,long finished,long afterAt,long now){return CardRevealProbeSource.afterCrop(
            OverviewFrameCaptureTest.report(),ready(900),950,ready(2200),ready(2350),ready(afterAt),ready(2350).profile().shape(),2400,started,finished,9000,now);}
    @Test public void croppedCaptureStillNeedsBothOrderedFreshSourceBrackets(){
        assertTrue(before(2400,9000));assertTrue(after(2450,2500,2700,2800));
        assertFalse(after(2399,2500,2700,2800));assertFalse(after(2500,2499,2700,2800));
        assertFalse(after(2450,2750,2700,2800));
    }
    @Test public void newerAfterMetadataDoesNotRefreshAnExpiredBeforeBracket(){
        assertTrue(before(6900,9000));assertFalse(before(7201,9000));
        assertFalse(after(2450,2500,7300,7400));
    }
    @Test public void deadlineAndMissingSourceRemainFailures(){
        assertFalse(before(2800,2800));
        assertFalse(CardRevealProbeSource.ui(OverviewFrameCaptureTest.report(),ready(900),950,null,ready(2350).profile().shape(),2400));
        assertFalse(CardRevealProbeSource.ui(OverviewFrameCaptureTest.report(),ready(900),1100,ready(2200),ready(2350).profile().shape(),2400));
    }
}
