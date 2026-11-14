package com.aliothmoon.maafw.once;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class OverviewPublicationCommitTest {
    @Test public void callbackOutputCannotAdvanceWhenOuterFenceRejects(){
        AtomicInteger accepted=new AtomicInteger(),currentChecks=new AtomicInteger();
        assertFalse(OverviewPublicationCommit.commit(true,false,()->{currentChecks.incrementAndGet();return true;},accepted::incrementAndGet));
        assertEquals(0,accepted.get());assertEquals(0,currentChecks.get());
    }
    @Test public void expiredAfterExportOrFailedCallbackCannotAdvance(){
        AtomicInteger accepted=new AtomicInteger();
        assertFalse(OverviewPublicationCommit.commit(true,true,()->false,accepted::incrementAndGet));
        assertFalse(OverviewPublicationCommit.commit(false,true,()->true,accepted::incrementAndGet));
        assertFalse(OverviewPublicationCommit.commit(true,true,()->{throw new IllegalStateException();},accepted::incrementAndGet));
        assertEquals(0,accepted.get());
    }
    @Test public void acceptedOuterFenceAndFreshFrameAdvanceOnce(){
        AtomicInteger accepted=new AtomicInteger(),currentChecks=new AtomicInteger();
        assertTrue(OverviewPublicationCommit.commit(true,true,()->{currentChecks.incrementAndGet();return true;},accepted::incrementAndGet));
        assertEquals(1,accepted.get());assertEquals(1,currentChecks.get());
    }
}
