package com.aliothmoon.maafw.once;

import android.content.Context;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Runs the same bounded native ROI queries. A result contains no display or input authority. */
final class LiveCardRecognizer {
    static final class Result implements AutoCloseable {
        final RemainingCardPlan.Outcome outcome;
        final String diagnostics,titleHash,reason,captureHash;
        final int width,height,queries;
        final long nativeMillis;
        private byte[] marked;
        Result(RemainingCardPlan.Outcome outcome,String diagnostics,String titleHash,String reason,String hash,
                int width,int height,int queries,long nativeMillis,byte[] marked){
            this.outcome=outcome;this.diagnostics=diagnostics;this.titleHash=titleHash;this.reason=reason;captureHash=hash;
            this.width=width;this.height=height;this.queries=queries;this.nativeMillis=nativeMillis;this.marked=marked;
        }
        boolean qualified(){return outcome!=null&&!outcome.status().equals("HOLD")&&diagnostics!=null
                &&outcome.realExecutions()==0&&Double.isFinite(outcome.confidence())&&outcome.confidence()>=.85;}
        boolean complete(){return qualified()&&(outcome.status().equals("ITEM_COMPLETE_SKIP")||outcome.status().equals("CHECK_IN_COMPLETE_SKIP"));}
        synchronized byte[] takeMarked(){byte[] value=marked;marked=null;return value;}
        @Override public synchronized void close(){if(marked!=null){Arrays.fill(marked,(byte)0);marked=null;}}
    }
    static Result recognize(Context context,CardNativeMockReceipt receipt,Object owner,byte[] ownedPng,
            CardRecognitionSpec spec,BooleanSupplier current){
        var processor=RemainingCardNativeProcessor.forSpec(context,receipt,owner,ownedPng,spec,current);
        byte[] marked=null;
        try{
            processor.run();
            var outcome=processor.outcome();String diagnostics=processor.diagJson(),title=processor.titleHash();
            String reason=processor.reason(),hash=processor.inputSha256();int width=processor.width(),height=processor.height(),queries=processor.queries();
            long millis=processor.nativeMs();marked=processor.takeMarkedPng();processor.close();
            if(!processor.knownClean())throw new SecurityException("Native card recognition cleanup unconfirmed");
            var result=new Result(outcome,diagnostics,title,reason,hash,width,height,queries,millis,marked);marked=null;return result;
        }finally{processor.close();if(marked!=null)Arrays.fill(marked,(byte)0);}
    }
    private LiveCardRecognizer(){}
}
