package com.aliothmoon.maafw.once;

import static com.aliothmoon.maafw.once.AndroidLiveUiReader.FixedStatus.*;
import static com.aliothmoon.maafw.once.LiveUiReadStream.Resource.*;
import static com.aliothmoon.maafw.once.NativeUiStructureProtocol.Reason.*;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class LiveUiReadStreamTest {
    private static final String NONCE="a".repeat(32);

    @Test public void exitedProcessCanHaveAQuietIntervalBeforeItsTailAndTrueEof()throws Exception{
        var stream=new LiveUiReadStream();
        byte[] bytes=new byte[32];
        long[] time={0};
        int[] reads={0};
        LiveCropStream.Input pipe=(dest,offset,length)->{
            time[0]+=3;
            return switch(++reads[0]){
                case 1->{dest[offset]='A';yield 1;}
                case 2,3->0;
                case 4->{dest[offset]='B';dest[offset+1]='\n';yield 2;}
                default->-1;
            };
        };
        assertNull(stream.drain(bytes,100,()->time[0],()->true,pipe,process(true,0),ms->time[0]+=ms));
        assertEquals("AB\n",new String(bytes,0,stream.count,StandardCharsets.US_ASCII));
        assertEquals(5,reads[0]);
        assertTrue(stream.eof && stream.quiet && stream.exitObserved);
        assertFalse(stream.released(true)); // No native receipt has been validated.
        stream.completeReceipt=true;stream.frameCleanup=NativeUiStructureProtocol.Cleanup.CONFIRMED;
        assertTrue(stream.released(true));assertFalse(stream.released(false));
    }

    @Test public void missingEofOrExitNeverReleasesAnInvokedReader()throws Exception{
        for(boolean eof:new boolean[]{false,true}){
            var stream=new LiveUiReadStream();long[] time={0};
            var result=stream.drain(new byte[8],8,()->time[0],()->true,
                    (b,o,n)->{time[0]++;return eof?-1:0;},process(!eof,0),ms->time[0]+=ms);
            assertEquals(READ_TIMEOUT,result);
            assertEquals(eof,stream.eof);
            assertEquals(eof?LiveUiReadStream.Stage.PROCESS_EXIT:LiveUiReadStream.Stage.FIRST_BYTE,stream.stage);
            assertFalse(stream.released(true));
        }
        var failed=new LiveUiReadStream();
        assertEquals(EXIT_NONZERO,failed.drain(new byte[8],100,()->0,()->true,(b,o,n)->-1,process(true,2),ms->{}));
        assertFalse(failed.released(true));
    }

    @Test public void cancellationDuringDrainIsRememberedEvenIfTheOwnerReturns()throws Exception{
        var stream=new LiveUiReadStream();int[] checks={0};int[] reads={0};
        assertNull(stream.drain(new byte[8],100,()->1,()->++checks[0]!=1,
                (b,o,n)->++reads[0]==1?0:-1,process(true,0),ms->{}));
        assertTrue(stream.currentLost);
        assertFalse(LivePerceptionBudget.ui(CANCELLED,null,true,true));
    }

    @Test public void shortAfterWindowIsRejectedOnlyByAPassedCurrentGate(){
        assertEquals(INSUFFICIENT_BUDGET,post(4790,5000,true,true,false,true,true));
        assertNull(post(1500,5000,true,true,false,true,true));
        assertEquals(INSUFFICIENT_BUDGET,post(1501,5000,true,true,false,true,true));
        assertEquals(AUTHORITY_REJECTED,post(4790,5000,true,true,false,false,true));
        assertEquals(CANCELLED,post(4790,5000,false,true,false,true,true));
        assertEquals(CANCELLED,post(4790,5000,true,false,false,true,true));
        assertEquals(CANCELLED,post(4790,5000,true,true,true,true,true));
        assertNull(post(4790,5000,true,true,false,true,false));
        for(var status:AndroidLiveUiReader.FixedStatus.values()){
            boolean invoked=status!=INSUFFICIENT_BUDGET;
            assertEquals(status==BUDGET_RECEIPT||status==INSUFFICIENT_BUDGET,
                    LivePerceptionBudget.retireUi(LiveRewardsTypes.Phase.AFTER_2,result(status,invoked,true)));
            assertFalse(LivePerceptionBudget.retireUi(LiveRewardsTypes.Phase.AFTER_2,result(status,invoked,false)));
            assertFalse(LivePerceptionBudget.retireUi(LiveRewardsTypes.Phase.BEFORE,result(status,invoked,true)));
        }
        assertFalse(LivePerceptionBudget.retireUi(LiveRewardsTypes.Phase.AFTER_2,result(INSUFFICIENT_BUDGET,true,true)));
        assertFalse(LivePerceptionBudget.retireUi(LiveRewardsTypes.Phase.AFTER_2,result(BUDGET_RECEIPT,false,true)));
    }

    @Test public void cleanupOnlyReceiptBindsNonceModeRequestAndBothEndsOfTheClock(){
        var receipt=new LiveUiReadStream.CleanupReceipt(NONCE,LiveUiReadStream.Mode.AFTER,5000,4600,4910,TIMEOUT,NOT_OPENED,NOT_OPENED,NOT_OPENED);
        String wire=receipt.encode();assertEquals(receipt,parse(wire,NONCE,false,4550,5000,4950));assertTrue(receipt.released());
        expectInvalid(()->parse(wire,"b".repeat(32),false,4550,5000,4950));
        expectInvalid(()->parse(wire,NONCE,true,4550,5000,4950));
        expectInvalid(()->LiveUiReadStream.CleanupReceipt.parse(wire,NONCE,LiveUiReadStream.Mode.UI,4550,5000,4950));
        expectInvalid(()->parse(wire,NONCE,false,4601,5000,4950));
        expectInvalid(()->parse(wire,NONCE,false,4550,4999,4950));
        expectInvalid(()->parse(wire,NONCE,false,4550,5000,4909));
        expectInvalid(()->parse(wire,NONCE,false,4550,5000,5000));
        expectInvalid(()->parse(wire+"|EXTRA",NONCE,false,4550,5000,4950));
        expectInvalid(()->parse(wire.replace("|TIMEOUT|","|OK|"),NONCE,false,4550,5000,4950));
        expectInvalid(()->LiveUiProtocol.parse(wire,NONCE,4550,5000,4950));
        expectInvalid(()->LiveVisitGuard.parse(wire,NONCE,4550,5000,4950));
        for(int i=0;i<3;i++){
            var partial=new LiveUiReadStream.CleanupReceipt(NONCE,LiveUiReadStream.Mode.AFTER,5000,4000,4820,TIMEOUT,
                    i==0?UNKNOWN:RELEASED,i==1?UNKNOWN:RELEASED,i==2?UNKNOWN:RELEASED);
            assertFalse(parse(partial.encode(),NONCE,false,3900,5000,4900).released());
        }
    }

    @Test public void earlierReadAndVisitRetainTheirFullOriginalFrameWindow(){
        for(var mode:LiveUiReadStream.Mode.values()){
            boolean after=mode==LiveUiReadStream.Mode.AFTER;
            assertEquals(READ_COMPLETE,AndroidLiveUiReader.receivedStatus(mode,4499,5000,OK,true));
            assertEquals(after?BUDGET_RECEIPT:READ_COMPLETE,AndroidLiveUiReader.receivedStatus(mode,4999,5000,OK,true));
            assertEquals(after?HELPER_REJECTED:READ_COMPLETE,AndroidLiveUiReader.receivedStatus(mode,4999,5000,FOREIGN_ROOT,false));
            assertEquals(CANCELLED,AndroidLiveUiReader.receivedStatus(mode,5000,5000,OK,true));
        }
    }

    @Test public void timeCannotTurnAnObservedRefusalIntoAnOrdinaryWindowExpiry(){
        assertEquals(UI_BLOCKER,LiveUiReadStream.cleanupReason(TIMEOUT,true,true,false));
        assertEquals(TREE_FAILED,LiveUiReadStream.cleanupReason(OK,true,false,true));
        assertEquals(TREE_FAILED,LiveUiReadStream.cleanupReason(TIMEOUT,true,false,true));
        assertEquals(NativeUiStructureProtocol.Reason.CLEANUP_UNKNOWN,LiveUiReadStream.cleanupReason(OK,false,false,true));
        assertEquals(REFLECTION_DENIED,LiveUiReadStream.cleanupReason(REFLECTION_DENIED,true,false,false));
        assertEquals(IDLE_TIMEOUT,LiveUiReadStream.cleanupReason(IDLE_TIMEOUT,true,false,false));
        assertEquals(FOREIGN_NODE,LiveUiReadStream.cleanupReason(FOREIGN_NODE,true,false,false));
        assertEquals(TIMEOUT,LiveUiReadStream.cleanupReason(TIMEOUT,true,false,false));
    }

    private static AndroidLiveUiReader.FixedStatus post(long now,long end,boolean owned,boolean permit,
            boolean interrupted,boolean binder,boolean after){
        return AndroidLiveUiReader.postGate(now,end,owned,permit,interrupted,binder,true,after);
    }
    private static AndroidLiveUiReader.Result result(AndroidLiveUiReader.FixedStatus status,boolean invoked,boolean clean){
        return new AndroidLiveUiReader.Result(null,null,status,invoked,clean,0,true,invoked,0);
    }
    private static LiveUiReadStream.CleanupReceipt parse(String wire,String nonce,boolean visit,long start,long end,long now){
        return LiveUiReadStream.CleanupReceipt.parse(wire,nonce,visit?LiveUiReadStream.Mode.VISIT:LiveUiReadStream.Mode.AFTER,start,end,now);
    }
    private static void expectInvalid(Runnable action){
        try{action.run();fail("Expected rejection");}catch(IllegalArgumentException expected){/* fixed protocol rejection */}
    }
    private static LiveCropStream.ProcessExit process(boolean quiet,int exit){
        return new LiveCropStream.ProcessExit(){
            public boolean quiet(){return quiet;}
            public int exitValue(){return exit;}
        };
    }
}
