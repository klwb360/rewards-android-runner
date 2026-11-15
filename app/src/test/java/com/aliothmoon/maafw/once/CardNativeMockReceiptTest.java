package com.aliothmoon.maafw.once;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic bytes exercise ownership, immutability and consumption, without OCR or image decoding. */
public class CardNativeMockReceiptTest {
    private static final int W=600,H=300;
    private byte[] bytes(){byte[] value=new byte[128];for(int i=0;i<value.length;i++)value[i]=(byte)(i*13);return value;}
    private CardNativeMockReceipt receipt(Object owner,byte[] bytes){return CardNativeMockReceipt.seal(owner,CardNativeMockReceipt.digest(bytes),W,H);}
    @Test public void exactOwnerDigestAndShapeCanBeClaimedOnlyOnce(){
        Object owner=new Object();byte[] bytes=bytes();var receipt=receipt(owner,bytes);
        assertTrue(receipt.claim(owner,bytes,W,H));assertTrue(receipt.claimedBy(owner));
        assertFalse(receipt.claim(owner,bytes,W,H));assertFalse(receipt.claimedBy(owner));assertFalse(receipt.claimedBy(new Object()));
        assertEquals(W,receipt.width());assertEquals(H,receipt.height());assertEquals(CardNativeMockReceipt.digest(bytes),receipt.inputSha256());
    }
    @Test public void wrongOwnerSpendsTheReceiptBeforeAnyCorrection(){
        Object owner=new Object();byte[] bytes=bytes();var receipt=receipt(owner,bytes);
        assertFalse(receipt.claim(new Object(),bytes,W,H));assertFalse(receipt.claim(owner,bytes,W,H));assertFalse(receipt.claimedBy(owner));
    }
    @Test public void changedByteCannotBeReplacedWithTheOriginalAfterAFailure(){
        Object owner=new Object();byte[] bytes=bytes(),changed=bytes.clone();changed[70]^=1;var receipt=receipt(owner,bytes);
        assertFalse(receipt.claim(owner,changed,W,H));assertFalse(receipt.claim(owner,bytes,W,H));assertFalse(receipt.claimedBy(owner));
    }
    @Test public void dimensionsArePartOfTheClaimAndFailureIsPermanent(){
        for(int[] shape:new int[][]{{W-1,H},{W,H-1},{H,W}}){
            Object owner=new Object();byte[] bytes=bytes();var receipt=receipt(owner,bytes);
            assertFalse(receipt.claim(owner,bytes,shape[0],shape[1]));assertFalse(receipt.claim(owner,bytes,W,H));
        }
    }
    @Test public void nullTruncatedAndOversizedBuffersBurnTheirReceipt(){
        Object owner=new Object();byte[] bytes=bytes();
        for(byte[] invalid:new byte[][]{null,new byte[0],new byte[56],new byte[CardNativeMockReceipt.MAX_PNG_BYTES+1]}){
            var receipt=receipt(owner,bytes);assertFalse(receipt.claim(owner,invalid,W,H));assertFalse(receipt.claim(owner,bytes,W,H));
        }
    }
    @Test public void aClaimedCaptureReservesNativePlanningOnlyOnce(){
        Object owner=new Object();byte[] bytes=bytes();var receipt=receipt(owner,bytes);
        assertTrue(receipt.claim(owner,bytes,W,H));assertTrue(receipt.claimPlan(owner));assertFalse(receipt.claimPlan(owner));
        var wrong=receipt(owner,bytes);assertTrue(wrong.claim(owner,bytes,W,H));
        assertFalse(wrong.claimPlan(new Object()));assertFalse(wrong.claimPlan(owner));
    }
    @Test public void cancellationBeforeAndAfterClaimRevokesTheOwnedCapture(){
        Object owner=new Object();byte[] bytes=bytes();var before=receipt(owner,bytes);before.cancel();
        assertFalse(before.claim(owner,bytes,W,H));assertFalse(before.claimedBy(owner));
        var after=receipt(owner,bytes);assertTrue(after.claim(owner,bytes,W,H));after.cancel();
        assertFalse(after.claimedBy(owner));assertFalse(after.claim(owner,bytes,W,H));
    }
    @Test public void twoConcurrentConsumersCannotBothAcquireOneCapture()throws Exception{
        Object owner=new Object();byte[] bytes=bytes();var receipt=receipt(owner,bytes);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var done=new CountDownLatch(2);var successes=new AtomicInteger();
        Runnable consume=()->{ready.countDown();try{go.await();if(receipt.claim(owner,bytes,W,H))successes.incrementAndGet();}
            catch(InterruptedException failed){Thread.currentThread().interrupt();}finally{done.countDown();}};
        new Thread(consume).start();new Thread(consume).start();ready.await();go.countDown();done.await();
        assertEquals(1,successes.get());assertFalse(receipt.claimedBy(owner));
    }
    @Test public void hashingDoesNotMutateOrRetainCallerByteAuthority(){
        Object owner=new Object();byte[] bytes=bytes(),unchanged=bytes.clone();var receipt=receipt(owner,bytes);
        assertArrayEquals(unchanged,bytes);assertTrue(receipt.claim(owner,bytes,W,H));assertArrayEquals(unchanged,bytes);
        Arrays.fill(bytes,(byte)0);assertEquals(CardNativeMockReceipt.digest(unchanged),receipt.inputSha256());
    }
    @Test public void sealingRejectsMissingIdentityInvalidDigestAndUnboundedShape(){
        String sha=CardNativeMockReceipt.digest(bytes());Object owner=new Object();
        for(Object[] values:new Object[][]{{null,sha,W,H},{owner,null,W,H},{owner,"bad",W,H},
                {owner,sha.toUpperCase(java.util.Locale.ROOT),W,H},{owner,sha,31,H},{owner,sha,W,31},
                {owner,sha,4097,H},{owner,sha,2048,2048}}){
            try{CardNativeMockReceipt.seal(values[0],(String)values[1],(Integer)values[2],(Integer)values[3]);fail("Unbounded receipt accepted");}
            catch(IllegalArgumentException expected){/* Fixed bounded identity required. */}
        }
    }
}
