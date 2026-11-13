package com.aliothmoon.maafw.readingtrust;

import io.github.klwb360.rewards.core.RewardsCore;
import io.github.klwb360.rewards.core.RewardsCore.*;
import com.aliothmoon.maafw.trial.ReadingReservationGuard.History;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import static com.aliothmoon.maafw.readingtrust.NormalReadingLedger.*;
import static com.aliothmoon.maafw.readingtrust.OfflineReadingV3Envelope.*;

/** Raw fixture encodings deliberately bypass the envelope encoder to test its decoder. */
public final class OfflineReadingV3EnvelopeTest {
    static int checks;
    static final Limits LIMITS=Limits.defaults();static final long BOOT=8,EPOCH=NormalReadingLedgerTest.EPOCH;
    interface Failing { void run() throws Exception; }
    static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    static void reject(Failing f,String label)throws Exception{
        boolean bad=false;try{f.run();}catch(IOException expected){bad=true;}check(bad,label);
    }
    static byte[] checked(byte[] body)throws Exception{
        byte[] out=Arrays.copyOf(body,body.length+32);
        System.arraycopy(MessageDigest.getInstance("SHA-256").digest(body),0,out,body.length,32);return out;
    }
    static byte[] body(byte[] image){return Arrays.copyOf(image,image.length-32);}
    static byte[] raw(Envelope e)throws Exception{return raw(e,NormalReadingLedger.encode(e.settled(),e.boot()));}
    static byte[] raw(Envelope e,byte[] old)throws Exception{
        ByteArrayOutputStream b=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(b)){
            out.writeInt(0x4e524533);out.writeInt(3);out.writeLong(e.boot());out.writeByte(e.phase()==Phase.SETTLED?0:1);
            out.writeInt(old.length);out.write(old);
            if(e.phase()==Phase.PENDING){byte[] p=NormalReadingLedger.encode(e.reserved(),e.boot());out.writeInt(p.length);out.write(p);}
            out.writeLong(e.reservedAt());out.writeLong(e.reservedEpoch());
        }return checked(b.toByteArray());
    }
    static State runStart(State s,long value){return new State(s.day(),value,s.readingStartedAt(),s.readingAttempts(),
            s.readingNoCredit(),s.searchAttempts(),s.searchNoCredit(),s.searchHashes(),s.pendingTask(),s.pendingBefore(),s.pendingTarget(),s.pendingHash());}
    static Receipt receipt(Receipt r,int noCreditBefore,long committed,long second) {
        return new Receipt(r.type(),r.day(),r.boot(),r.generation(),r.fingerprint(),r.article(),r.attempt(),
                r.dispatch(),r.before(),r.after(),r.target(),noCreditBefore,r.noCreditAfter(),r.decision(),
                r.receivedTrue(),r.articleReady(),r.returned(),r.verificationStart(),second-100,second,committed);
    }
    static Envelope receiptEnvelope(View v,int noCreditBefore,long committed,long second) {
        return settled(BOOT,new View(v.generation(),v.state(),v.history(),receipt(v.receipt(),noCreditBefore,committed,second)));
    }
    public static void main(String[] args)throws Exception{
        View seed=OfflineReadingV3StoreTest.seed();
        var p=OfflineReadingV3StoreTest.planned(seed,0,"one",10000,LIMITS);
        View done=OfflineReadingV3StoreTest.completed(p,3,10000,LIMITS);
        Envelope initial=settled(BOOT,seed),reserved=pending(BOOT,p,EPOCH),completed=settled(BOOT,done);
        for(Envelope e:List.of(initial,reserved,completed)){
            byte[] image=encode(e,LIMITS);check(decode(image,BOOT,LIMITS).equals(e),"complete envelope roundtrip");
            check(Arrays.equals(image,raw(e)),"specified canonical image");
            check(Arrays.equals(image,encode(decode(image,BOOT,LIMITS),LIMITS)),"canonical reencoding");
        }
        validateTransition(initial,reserved,LIMITS);check(true,"reserve transition valid");
        validateTransition(reserved,completed,LIMITS);check(true,"settle transition valid");
        reject(()->validateTransition(initial,initial,LIMITS),"same phase blocked");
        reject(()->validateTransition(reserved,reserved,LIMITS),"pending cannot overwrite pending");
        reject(()->validateTransition(completed,initial,LIMITS),"no lifecycle reset");
        reject(()->validateTransition(initial,pending(9,p,EPOCH),LIMITS),"boot transition blocked");
        Envelope changedDone=settled(BOOT,new View(done.generation(),runStart(done.state(),101),done.history(),done.receipt()));
        reject(()->validateTransition(reserved,changedDone,LIMITS),"settlement cannot change unrelated core state");

        byte[] valid=encode(reserved,LIMITS);
        byte[] corrupt=valid.clone();corrupt[22]^=1;reject(()->decode(corrupt,BOOT,LIMITS),"checksum failure");
        reject(()->decode(Arrays.copyOf(valid,valid.length-1),BOOT,LIMITS),"truncation");
        reject(()->decode(new byte[300001],BOOT,LIMITS),"outer bound");
        reject(()->decode(valid,9,LIMITS),"expected boot mismatch");
        byte[] version=body(valid);version[7]=2;reject(()->decode(checked(version),BOOT,LIMITS),"old version no migration");
        byte[] phase=body(valid);phase[16]=2;reject(()->decode(checked(phase),BOOT,LIMITS),"unknown phase");
        byte[] length=body(valid);ByteBuffer.wrap(length).putInt(17,Integer.MAX_VALUE);
        reject(()->decode(checked(length),BOOT,LIMITS),"nested bound before allocation");
        byte[] negative=body(valid);ByteBuffer.wrap(negative).putInt(17,-1);
        reject(()->decode(checked(negative),BOOT,LIMITS),"negative nested length");
        byte[] extra=Arrays.copyOf(body(valid),valid.length-31);
        reject(()->decode(checked(extra),BOOT,LIMITS),"valid checksum cannot hide trailing bytes");

        View changedSeed=new View(0,runStart(seed.state(),101),seed.history(),null);
        Envelope mismatched=new Envelope(BOOT,Phase.PENDING,changedSeed,p.reserved(),10000,EPOCH);
        reject(()->decode(raw(mismatched),BOOT,LIMITS),"individually valid snapshots must correspond");
        View skipped=new View(p.reserved().generation()+1,p.reserved().state(),p.reserved().history(),null);
        reject(()->decode(raw(new Envelope(BOOT,Phase.PENDING,seed,skipped,10000,EPOCH)),BOOT,LIMITS),"pending generation skip");
        reject(()->decode(raw(new Envelope(BOOT,Phase.PENDING,seed,p.reserved(),9999,EPOCH)),BOOT,LIMITS),"reserved clock must match core preparation");
        reject(()->decode(raw(new Envelope(BOOT,Phase.PENDING,seed,p.reserved(),10000,EPOCH+86400000L)),BOOT,LIMITS),"reservation epoch day binding");
        reject(()->decode(raw(new Envelope(BOOT,Phase.PENDING,seed,p.reserved(),0,EPOCH)),BOOT,LIMITS),"zero reservation time");
        reject(()->decode(raw(new Envelope(BOOT,Phase.SETTLED,done,null,10000,EPOCH)),BOOT,LIMITS),"settled phase forbids orphaned reservation times");
        View noReceipt=new View(done.generation(),done.state(),done.history(),null);
        reject(()->decode(raw(settled(BOOT,noReceipt)),BOOT,LIMITS),"nonzero history cannot drop its receipt");
        View wrongGeneration=new View(1,seed.state(),seed.history(),null);
        reject(()->decode(raw(settled(BOOT,wrongGeneration)),BOOT,LIMITS),"seed generation not arbitrary");
        var next=OfflineReadingV3StoreTest.planned(done,3,"two",20000,LIMITS);
        State ns=next.reserved().state();String b=RewardsCore.hashTerm("two"),c=RewardsCore.hashTerm("other");
        History lost=new History(ns.day(),Set.of(b,c),next.reserved().history().pendingState(),b);
        View dropped=new View(next.reserved().generation(),ns,lost,null);
        reject(()->decode(raw(new Envelope(BOOT,Phase.PENDING,done,dropped,20000,EPOCH)),BOOT,LIMITS),"cannot swap old article history while adding one");
        reject(()->decode(raw(receiptEnvelope(done,1,15400,15300)),BOOT,LIMITS),
                "first credited attempt cannot conceal a prior uncredited attempt");
        View fifth=seed;
        for(int i=1;i<=5;i++){
            var pn=OfflineReadingV3StoreTest.planned(fifth,i-1,"credit-"+i,i*10000,LIMITS);
            fifth=OfflineReadingV3StoreTest.completed(pn,i,i*10000,LIMITS);
        }
        final View five=fifth;
        reject(()->decode(raw(receiptEnvelope(five,4,55400,55300)),BOOT,LIMITS),
                "credit cannot conceal a forbidden fourth prior consecutive failure");
        reject(()->decode(raw(receiptEnvelope(done,0,16801,15300)),BOOT,LIMITS),"stale standalone receipt rejected");
        check(decode(raw(receiptEnvelope(done,0,16800,15300)),BOOT,LIMITS).settled().receipt().committedAt()==16800,
                "standalone receipt freshness equality allowed");
        Limits runLimit=new Limits(10,1800000,20000),readingLimit=new Limits(10,6000,7200000);
        reject(()->decode(raw(receiptEnvelope(done,0,20100,20000)),BOOT,runLimit),"standalone exact run deadline rejected");
        check(decode(raw(receiptEnvelope(done,0,20099,19999)),BOOT,runLimit).settled().receipt().committedAt()==20099,
                "standalone one millisecond before run deadline");
        reject(()->decode(raw(receiptEnvelope(done,0,16000,15900)),BOOT,readingLimit),"standalone exact Reading deadline rejected");
        check(decode(raw(receiptEnvelope(done,0,15999,15899)),BOOT,readingLimit).settled().receipt().committedAt()==15999,
                "standalone one millisecond before Reading deadline");
        // Java DataInputStream accepts any nonzero boolean; the outer canonical check must not.
        byte[] inner=NormalReadingLedger.encode(done,BOOT);byte[] ib=body(inner);
        ByteBuffer scan=ByteBuffer.wrap(ib);scan.position(24);int stateLength=scan.getInt();scan.position(scan.position()+stateLength);
        int historyLength=scan.getInt();scan.position(scan.position()+historyLength);int receiptBoolean=scan.position();
        check(ib[receiptBoolean]==1,"fixture locates receipt flag");ib[receiptBoolean]=2;
        reject(()->decode(raw(completed,checked(ib)),BOOT,LIMITS),"noncanonical nested boolean rejected");
        System.out.println("PASS "+checks+" synthetic v3 envelope assertions; CHECKSUM IS NOT AUTHENTICATION");
    }
}
