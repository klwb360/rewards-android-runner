package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.UiImageGeometryBindingTest.ready;

public class OverviewFrameCaptureTest {
    static final String NONCE="a".repeat(32);
    static final int WIDTH=523,HEIGHT=1000;
    static OverviewUiProtocol.Report report(){
        var source=new NativeUiStructureProtocol.Report(NONCE,1000,2000,2100,NativeUiStructureProtocol.Reason.OK,
                NativeUiStructureProtocol.Cleanup.CONFIRMED,5,120,7,0,0,0,WIDTH,HEIGHT,-1,-1,
                NativeUiStructureProtocol.Task.unknown(),NativeUiStructureProtocol.Task.unknown(),
                new NativeUiReadyDiagnostics.Result(NativeUiReadyDiagnostics.Idle.IDLE_OBSERVED,1,0,0,0,0,1,1,1));
        return new OverviewUiProtocol.Report(source,new OverviewUiRegions.Result(OverviewUiRegions.Status.OBSERVED,
                new Box(0,0,WIDTH,HEIGHT),List.of()),OverviewUiProtocol.ScrollHint.UNKNOWN);
    }
    static final class Fixture implements AutoCloseable {
        final Object owner=new Object(),session=new Object(),epoch=new Object(),recognizer=new Object();
        final AtomicBoolean cancel=new AtomicBoolean();final OverviewFramePreview preview=new OverviewFramePreview(WIDTH,HEIGHT);
        final byte[] gray=new byte[WIDTH*HEIGHT];final OwnedFrame owned=new OwnedFrame();
        final OnceBackend.Owner backendOwner=UiImageAlignmentCaptureTest.owner();
        final OverviewFrameCapture capture;
        Fixture(){this(9000);}
        Fixture(long deadline){
            assertEquals(WIDTH,ready(2350).profile().shape().width());assertEquals(HEIGHT,ready(2350).profile().shape().height());
            int[] row=new int[WIDTH];Arrays.fill(row,0xff000000);for(int y=0;y<HEIGHT;y++)preview.row(y,row);preview.complete();
            capture=OverviewFrameCapture.begin(1,owner,session,epoch,NONCE,report(),ready(900),950,ready(2200),ready(2350),2400,deadline,cancel::get,preview);
            assertNotNull(capture);assertTrue(capture.claimTransport());owned.sink().begin(WIDTH,HEIGHT);owned.sink().append(gray,0,gray.length);assertTrue(owned.seal());
        }
        OverviewFrameCapture.Candidate prepare(){return capture.prepare(recognizer,backendOwner,owned.view(),gray,List.of(),cancel::get,2600);}
        public void close(){owned.wipeAfterQuiescence();preview.close();Arrays.fill(gray,(byte)0);}
    }
    @Test public void nativeCandidateRemainsOnceOwnedAndNeedsAfterSource(){
        try(var f=new Fixture()){
            var c=f.prepare();assertNotNull(c);assertNull(f.prepare());
            assertFalse(c.matches(f.capture,new Object(),f.backendOwner));
            assertFalse(f.capture.publicationCurrent(2650));
            assertTrue(f.capture.seal(c,f.recognizer,f.backendOwner,2450,2500,ready(2700),2800));
            assertTrue(f.capture.publicationCurrent(2800));
            assertFalse(f.capture.seal(c,f.recognizer,f.backendOwner,2450,2500,ready(2700),2800));
        }
    }
    @Test public void sameSizeDifferentPixelsOrColorCannotBecomeCandidate(){
        try(var f=new Fixture()){f.gray[700]=1;assertNull(f.prepare());}
        try(var f=new Fixture()){
            try{f.preview.withPixels((w,h,p)->{p[700]=0xffffffff;return null;});}catch(Exception e){throw new AssertionError(e);}
            assertNull(f.prepare());
        }
    }
    @Test public void wrongAfterAndRevocationCannotSealOrPublish(){
        try(var f=new Fixture()){var c=f.prepare();assertNotNull(c);assertFalse(f.capture.seal(c,f.recognizer,f.backendOwner,2450,2500,null,2800));assertFalse(f.capture.publicationCurrent(2800));}
        try(var f=new Fixture()){var c=f.prepare();assertNotNull(c);f.cancel.set(true);assertFalse(f.capture.seal(c,f.recognizer,f.backendOwner,2450,2500,ready(2700),2800));}
    }
    @Test public void existingFiveSecondUiAgeIsNotExtended(){
        try(var f=new Fixture()){assertTrue(f.capture.current(6900));assertFalse(f.capture.current(7201));}
    }
    @Test public void differentFrameDimensionsCannotBecomeCandidate(){
        try(var f=new Fixture()){
            var wrong=new OwnedFrame();
            try{wrong.sink().begin(400,800);wrong.sink().append(new byte[400*800],0,400*800);assertTrue(wrong.seal());
                assertNull(f.capture.prepare(f.recognizer,f.backendOwner,wrong.view(),f.gray,List.of(),f.cancel::get,2600));
            }finally{wrong.wipeAfterQuiescence();}
        }
    }
    @Test public void originalDeadlineStillRejectsFreshSourceAndCompletedNativeWork(){
        try(var f=new Fixture(2801)){
            var c=f.prepare();assertNotNull(c);
            assertTrue(f.capture.seal(c,f.recognizer,f.backendOwner,2450,2500,ready(2700),2800));
            assertTrue(f.capture.publicationCurrent(2800));assertTrue(ready(2700).fresh(2801));
            assertFalse(f.capture.current(2801));assertFalse(f.capture.publicationCurrent(2801));
        }
        try(var f=new Fixture(2600)){assertNull(f.prepare());}
    }
}
