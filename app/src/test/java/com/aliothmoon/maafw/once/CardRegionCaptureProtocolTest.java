package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.aliothmoon.maafw.once.CardRegionCaptureProtocol.Cleanup;
import static com.aliothmoon.maafw.once.CardRegionCaptureProtocol.Status;

/** Synthetic pixels only: no captured page, title, UI dump or machine-specific path. */
public class CardRegionCaptureProtocolTest {
    private static final String NONCE="0123456789abcdef0123456789abcdef";
    private static final Box ROI=new Box(100,600,116,616);
    private static CardRegionCaptureProtocol.Request request(){
        return new CardRegionCaptureProtocol.Request(NONCE,1000,ROI,1440,2560,0);
    }
    private static CardRegionCaptureProtocol.Report success()throws Exception{
        return new CardRegionCaptureProtocol.Report(request(),110,200,Status.CAPTURED,Cleanup.CONFIRMED,true,16,16,png(16,16));
    }
    private static CardRegionCaptureProtocol.Report parse(byte[] wire){
        return CardRegionCaptureProtocol.parse(wire,wire.length,request(),100,300);
    }
    private static void rejected(Runnable task){try{task.run();fail("Expected crop receipt rejection");}catch(IllegalArgumentException expected){}}

    @Test public void cropMustBeStrictlyInsideAndNeverEmptyOrWholeDisplay(){
        assertTrue(CardRegionCaptureProtocol.validCrop(ROI,1440,2560,0));
        Box[] invalid={null,new Box(0,0,1440,2560),new Box(0,600,116,616),new Box(100,0,116,616),
                new Box(100,600,1440,616),new Box(100,600,116,2560),new Box(100,600,100,616),
                new Box(100,600,116,600),new Box(100,600,115,616),new Box(-1,600,116,616),
                new Box(100,600,1500,616),new Box(1,1,1439,2559)};
        for(Box box:invalid)assertFalse(CardRegionCaptureProtocol.validCrop(box,1440,2560,0));
        for(int rotation:new int[]{-1,1,2,3,4})assertFalse(CardRegionCaptureProtocol.validCrop(ROI,1440,2560,rotation));
        assertFalse(CardRegionCaptureProtocol.validCrop(ROI,4097,2560,0));
        assertFalse(CardRegionCaptureProtocol.validCrop(ROI,4096,4096,0));
        assertFalse(CardRegionCaptureProtocol.validCrop(ROI,0,2560,0));
    }
    @Test public void helperArgumentsBindNonceDimensionsAndShortDeadline(){
        assertEquals(request(),CardRegionCaptureProtocol.parseArguments(request().arguments(),100));
        rejected(()->CardRegionCaptureProtocol.parseArguments(request().arguments(),1000));
        rejected(()->CardRegionCaptureProtocol.parseArguments(request().arguments(),-1));
        String[] extra=Arrays.copyOf(request().arguments(),10);extra[9]="extra";
        rejected(()->CardRegionCaptureProtocol.parseArguments(extra,100));
        for(int field:new int[]{1,2,3,4,5,6,7,8}){
            String[] malformed=request().arguments();malformed[field]="0"+malformed[field];
            rejected(()->CardRegionCaptureProtocol.parseArguments(malformed,100));
        }
        String[] late=request().arguments();late[1]="5101";
        rejected(()->CardRegionCaptureProtocol.parseArguments(late,100));
        String[] badNonce=request().arguments();badNonce[0]="bad nonce";
        rejected(()->CardRegionCaptureProtocol.parseArguments(badNonce,100));
    }
    @Test public void completeReceiptRoundTripsExactRegionAndLocalPixels()throws Exception{
        var original=success();byte[] wire=CardRegionCaptureProtocol.encode(original);var read=parse(wire);
        assertEquals(original.request(),read.request());assertEquals(Status.CAPTURED,read.status());
        assertEquals(Cleanup.CONFIRMED,read.cleanup());assertTrue(read.nativeInvoked());
        assertEquals(110,read.started());assertEquals(200,read.finished());
        assertEquals(16,read.pngWidth());assertEquals(16,read.pngHeight());assertArrayEquals(original.png(),read.png());
        assertNotSame(original.png(),read.png());assertTrue(wire.length<CardRegionCaptureProtocol.MAX_WIRE);
    }
    @Test public void replyCannotBeReusedForAnotherNonceRegionShapeOrDeadline()throws Exception{
        byte[] wire=CardRegionCaptureProtocol.encode(success());
        var others=new CardRegionCaptureProtocol.Request[]{
                new CardRegionCaptureProtocol.Request("ffffffffffffffffffffffffffffffff",1000,ROI,1440,2560,0),
                new CardRegionCaptureProtocol.Request(NONCE,1001,ROI,1440,2560,0),
                new CardRegionCaptureProtocol.Request(NONCE,1000,new Box(101,600,117,616),1440,2560,0),
                new CardRegionCaptureProtocol.Request(NONCE,1000,ROI,1439,2560,0),
                new CardRegionCaptureProtocol.Request(NONCE,1000,ROI,1440,2559,0)};
        for(var expected:others)rejected(()->CardRegionCaptureProtocol.parse(wire,wire.length,expected,100,300));
    }
    @Test public void noSuccessWithoutCleanupAndCompletionBeforeDeadline()throws Exception{
        byte[] pixels=png(16,16),wire=CardRegionCaptureProtocol.encode(success());
        rejected(()->new CardRegionCaptureProtocol.Report(request(),110,200,Status.CAPTURED,Cleanup.UNKNOWN,true,16,16,pixels));
        rejected(()->new CardRegionCaptureProtocol.Report(request(),110,200,Status.CAPTURED,Cleanup.CONFIRMED,false,16,16,pixels));
        rejected(()->new CardRegionCaptureProtocol.Report(request(),110,1000,Status.CAPTURED,Cleanup.CONFIRMED,true,16,16,pixels));
        rejected(()->new CardRegionCaptureProtocol.Report(request(),110,200,Status.CAPTURED,Cleanup.CONFIRMED,true,17,16,pixels));
        rejected(()->CardRegionCaptureProtocol.parse(wire,wire.length,request(),111,300));
        rejected(()->CardRegionCaptureProtocol.parse(wire,wire.length,request(),100,199));
    }
    @Test public void fixedFailureRetainsPermissionAndUnknownNativeCleanup(){
        var denied=new CardRegionCaptureProtocol.Report(request(),110,200,Status.PERMISSION_DENIED,
                Cleanup.CONFIRMED,false,0,0,null);
        assertEquals(denied,parse(CardRegionCaptureProtocol.encode(denied)));
        var missing=new CardRegionCaptureProtocol.Report(request(),110,200,Status.CAPTURE_NO_BUFFER,
                Cleanup.UNKNOWN,true,0,0,null);
        assertEquals(missing,parse(CardRegionCaptureProtocol.encode(missing)));
        rejected(()->new CardRegionCaptureProtocol.Report(request(),110,200,Status.CAPTURE_NO_BUFFER,
                Cleanup.CONFIRMED,true,0,0,null));
        rejected(()->new CardRegionCaptureProtocol.Report(request(),110,200,Status.CAPTURE_FAILED,
                Cleanup.CONFIRMED,true,16,16,null));
    }
    @Test public void malformedHeadersAndExtraFramesAreRejected()throws Exception{
        byte[] wire=CardRegionCaptureProtocol.encode(success());String text=new String(wire,StandardCharsets.US_ASCII);
        for(String mutated:new String[]{text+"\n",text+text," "+text,text.replace("CRP1|","CRP2|"),
                text.replace("|1440|","|01440|"),text.replace("|CAPTURED|","|OTHER|"),
                text.replace("|CONFIRMED|1|","|CONFIRMED|2|"),text.replace("|110|200|","|200|110|")}){
            byte[] bytes=mutated.getBytes(StandardCharsets.US_ASCII);rejected(()->parse(bytes));
        }
        rejected(()->CardRegionCaptureProtocol.parse(wire,wire.length-1,request(),100,300));
        rejected(()->CardRegionCaptureProtocol.parse(wire,wire.length+1,request(),100,300));
    }
    @Test public void base64MustHaveExactDeclaredLengthAndCanonicalPayload()throws Exception{
        byte[] wire=CardRegionCaptureProtocol.encode(success());int first=0;while(wire[first]!='\n')first++;
        byte[] space=wire.clone();space[first+2]=' ';rejected(()->parse(space));
        byte[] altered=wire.clone();altered[first+2]=altered[first+2]=='A'?(byte)'B':(byte)'A';rejected(()->parse(altered));
        byte[] truncated=Arrays.copyOf(wire,wire.length-4);truncated[truncated.length-1]='\n';rejected(()->parse(truncated));
        byte[] inserted=new byte[wire.length+4];System.arraycopy(wire,0,inserted,0,wire.length-1);
        Arrays.fill(inserted,wire.length-1,inserted.length-1,(byte)'A');inserted[inserted.length-1]='\n';rejected(()->parse(inserted));
    }
    @Test public void pngFramingBindsDimensionsAndChecksEveryChunk()throws Exception{
        byte[] pixels=png(16,16);assertTrue(CardRegionCaptureProtocol.validPng(pixels,16,16));
        assertFalse(CardRegionCaptureProtocol.validPng(pixels,17,16));
        byte[] crc=pixels.clone();crc[crc.length-1]^=1;assertFalse(CardRegionCaptureProtocol.validPng(crc,16,16));
        assertFalse(CardRegionCaptureProtocol.validPng(Arrays.copyOf(pixels,pixels.length-1),16,16));
        assertFalse(CardRegionCaptureProtocol.validPng(Arrays.copyOf(pixels,pixels.length+1),16,16));
        ByteArrayOutputStream metadata=new ByteArrayOutputStream();metadata.write(pixels,0,pixels.length-12);
        chunk(metadata,"tEXt","local text must not be transported".getBytes(StandardCharsets.US_ASCII));
        metadata.write(pixels,pixels.length-12,12);assertFalse(CardRegionCaptureProtocol.validPng(metadata.toByteArray(),16,16));
        byte[] signature=pixels.clone();signature[0]=0;assertFalse(CardRegionCaptureProtocol.validPng(signature,16,16));
    }
    private static byte[] png(int width,int height)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream header=new ByteArrayOutputStream();DataOutputStream fields=new DataOutputStream(header);
        fields.writeInt(width);fields.writeInt(height);fields.write(new byte[]{8,6,0,0,0});chunk(out,"IHDR",header.toByteArray());
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        try(DeflaterOutputStream z=new DeflaterOutputStream(compressed)){
            for(int y=0;y<height;y++){z.write(0);for(int x=0;x<width;x++)z.write(new byte[]{12,34,56,(byte)255});}
        }
        chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);return out.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream out,String type,byte[] bytes)throws Exception{
        DataOutputStream data=new DataOutputStream(out);byte[] name=type.getBytes(StandardCharsets.US_ASCII);
        data.writeInt(bytes.length);data.write(name);data.write(bytes);CRC32 crc=new CRC32();crc.update(name);crc.update(bytes);
        data.writeInt((int)crc.getValue());
    }
}
