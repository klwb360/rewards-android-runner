package com.aliothmoon.maafw.readingtrust;

import io.github.klwb360.rewards.core.RewardsCore;
import io.github.klwb360.rewards.core.RewardsCore.*;
import com.aliothmoon.maafw.trial.ReadingReservationGuard.History;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static com.aliothmoon.maafw.readingtrust.NormalReadingLedger.*;
import static com.aliothmoon.maafw.readingtrust.OfflineReadingV3Envelope.*;

/** Synthetic file faults only. No external process, device, real directory fsync or action. */
public final class OfflineReadingV3StoreTest {
    static int checks;
    static final long EPOCH = NormalReadingLedgerTest.EPOCH, BOOT = 8, START = 10000;
    static final Limits DEFAULTS = Limits.defaults();
    static final class Clock implements NormalReadingLedger.Clock {
        long now, boot = BOOT, epoch = EPOCH; int reads; int switchAt = -1;
        Runnable change = () -> {}; boolean allowed = true;
        Clock(long now) { this.now = now; }
        public long elapsed() { if (reads++ == switchAt) change.run(); return now; }
        public long boot() { return boot; }
        public long epoch() { return epoch; }
    }
    interface Failing { void run() throws Exception; }
    static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    static void reject(Failing f, String label) throws Exception {
        boolean bad = false; try { f.run(); } catch (IOException expected) { bad = true; }
        check(bad, label);
    }
    static Observation observed(int n) { return new Observation(ParseStatus.OK, new Counter(Task.READING,n,30,true),"synthetic"); }
    static View seed() { return ReadingReceiptLifecycleTest.seed(); }
    static ReadingReceiptLifecycleModel.Pending planned(View old, int before, String article, long start, Limits limits)
            throws Exception {
        var p = ReadingReceiptLifecycleModel.reserve(old, BOOT, observed(before), RewardsCore.hashTerm(article),
                EPOCH, start, false, limits);
        if (p.decision() != Decision.ALLOW) throw new AssertionError("Synthetic plan " + p.decision());
        return p.pending();
    }
    static View completed(ReadingReceiptLifecycleModel.Pending p, int after, long start, Limits limits) throws Exception {
        View old = p.reserved(); State s = old.state();
        Step step = RewardsCore.verify(s, Task.READING, observed(after), EPOCH, start + 5400, false, limits);
        var r = new Receipt("NORMAL_AFTER_DELIVERY", s.day(), BOOT, old.generation()+1,
                old.history().pendingState(), old.history().pendingArticle(), s.readingAttempts(),
                String.format("%032x",s.readingAttempts()),s.pendingBefore(),after,s.pendingTarget(),
                s.readingNoCredit(),step.state().readingNoCredit(),step.decision(),
                start+10,start+20,start+5020,start+5100,start+5200,start+5300,start+5400);
        return new View(old.generation()+1,step.state(),new History(s.day(),old.history().hashes(),"",""),r);
    }
    record Fixture(Path dir,Clock clock,OfflineReadingV3Store store,Envelope old,Envelope next,Limits limits) {}
    static Fixture fixture(boolean settlement, Limits limits, OfflineReadingV3Store.Fault fault) throws Exception {
        Path dir = Files.createTempDirectory("SYNTHETIC-reading-v3-");
        var p = planned(seed(),0,"article",START,limits);
        Envelope initial = settlement ? pending(BOOT,p,EPOCH) : settled(BOOT,seed());
        Envelope replacement = settlement ? settled(BOOT,completed(p,3,START,limits)) : pending(BOOT,p,EPOCH);
        Clock clock = new Clock(settlement ? START+5400 : START);
        Files.write(dir.resolve("synthetic-reading-v3.bin"),encode(initial,limits),StandardOpenOption.CREATE_NEW);
        var store = new OfflineReadingV3Store(dir,clock,
                d -> { if (!Files.isDirectory(d)) throw new IOException("simulated directory sync"); },
                limits,() -> clock.allowed,fault);
        return new Fixture(dir,clock,store,initial,replacement,limits);
    }
    static Envelope raw(Fixture f) throws Exception {
        return decode(Files.readAllBytes(f.dir.resolve("synthetic-reading-v3.bin")),BOOT,f.limits);
    }
    static void sameBytes(Fixture f, byte[] bytes, String label) throws Exception {
        check(Arrays.equals(bytes,Files.readAllBytes(f.dir.resolve("synthetic-reading-v3.bin"))),label);
    }
    static void noResidue(Fixture f, String label) {
        check(!Files.exists(f.dir.resolve("synthetic-reading-v3.txn"))
                && !Files.exists(f.dir.resolve("synthetic-reading-v3.new"))
                && !Files.exists(f.dir.resolve("synthetic-reading-v3.bak")),label);
    }
    static void normal() throws Exception {
        for (boolean settlement : new boolean[]{false,true}) {
            var f = fixture(settlement,DEFAULTS,s -> {});
            check(f.store.read().equals(f.old),"initial envelope roundtrip");
            check(f.store.compareAndReplace(f.old,f.next)==OfflineReadingV3Store.Outcome.STORED,"normal write");
            check(f.store.read().equals(f.next),"exact final envelope readback");
            check(f.store.compareAndReplace(f.old,f.next)==OfflineReadingV3Store.Outcome.HOLD,"stale comparison never replays");
            check(f.store.read().equals(f.next),"replay leaves image unchanged"); noResidue(f,"clean normal transaction");
        }
    }
    static void faults() throws Exception {
        for (boolean settlement : new boolean[]{false,true}) for (var stage:OfflineReadingV3Store.Stage.values()) {
            var f = fixture(settlement,DEFAULTS,s -> { if(s==stage)throw new IOException("synthetic fault"); });
            byte[] before = encode(f.old,DEFAULTS),after = encode(f.next,DEFAULTS);
            check(f.store.compareAndReplace(f.old,f.next)==OfflineReadingV3Store.Outcome.STORAGE_UNCERTAIN,"fault outcome "+stage);
            boolean replaced = stage.ordinal() >= OfflineReadingV3Store.Stage.AFTER_REPLACE.ordinal();
            sameBytes(f,replaced?after:before,"only exact old or new complete envelope "+stage);
            Envelope actual=raw(f);check(actual.equals(replaced?f.next:f.old),"decodable complete image "+stage);
            if(actual.phase()==Phase.PENDING)check(actual.settled().equals(f.next.phase()==Phase.PENDING?f.old.settled():f.old.settled()),
                    "pending retains full prior snapshot "+stage);
            boolean residue=Files.exists(f.dir.resolve("synthetic-reading-v3.txn"))||Files.exists(f.dir.resolve("synthetic-reading-v3.new"));
            if(residue)reject(()->f.store.read(),"residue fails closed "+stage);
            else check(f.store.read().equals(f.next),"uncertain but complete readback is inert "+stage);
            if(stage.ordinal()>=OfflineReadingV3Store.Stage.MARKER_FORCED.ordinal()
                    && stage.ordinal()<OfflineReadingV3Store.Stage.AFTER_MARKER_DELETE.ordinal())
                check(Arrays.equals(Files.readAllBytes(f.dir.resolve("synthetic-reading-v3.txn")),
                        OfflineReadingV3Store.transactionMarker(before,after)),"marker binds full pair before/after "+stage);
            check(f.store.compareAndReplace(f.old,f.next)==OfflineReadingV3Store.Outcome.HOLD,"fault never auto-retries "+stage);
            sameBytes(f,replaced?after:before,"retry leaves exact image "+stage);
            check(!Files.exists(f.dir.resolve("synthetic-reading-v3.bak")),"no unreviewed backup "+stage);
            try(FileChannel c=FileChannel.open(f.dir.resolve("synthetic-reading-v3.lock"),StandardOpenOption.WRITE);
                    FileLock held=c.tryLock()){check(held!=null,"fault releases OS lock "+stage);}
        }
    }
    static void boundaries() throws Exception {
        for(boolean settlement:new boolean[]{false,true})for(int boundary=0;boundary<4;boundary++)
            for(String kind:new String[]{"cancel","boot","day","rollback","stale","run","reading"}){
                long base=settlement?15400:10000;
                Limits limits=kind.equals("run")?new Limits(10,1800000,base):kind.equals("reading")
                        ?new Limits(10,base-START+100,7200000):DEFAULTS;
                var f=fixture(settlement,limits,s->{});f.clock.switchAt=boundary;
                f.clock.change=()->{switch(kind){
                    case "cancel":f.clock.allowed=false;break;case "boot":f.clock.boot=9;break;
                    case "day":f.clock.epoch+=86400000L;break;case "rollback":f.clock.now=base-1;break;
                    case "stale":f.clock.now=settlement?16801:11501;break;
                    case "run":case "reading":f.clock.now=base+100;break;default:throw new AssertionError();}};
                var expected=boundary<2?OfflineReadingV3Store.Outcome.HOLD:OfflineReadingV3Store.Outcome.STORAGE_UNCERTAIN;
                String label=kind+" boundary="+boundary+" settlement="+settlement;
                check(f.store.compareAndReplace(f.old,f.next)==expected,label+" outcome");
                check(f.clock.reads==boundary+1,label+" checkpoint reached");
                sameBytes(f,encode(boundary==3?f.next:f.old,limits),label+" complete expected image");
                check(Files.exists(f.dir.resolve("synthetic-reading-v3.txn"))==(boundary==2),label+" marker state");
                check(Files.exists(f.dir.resolve("synthetic-reading-v3.new"))==(boundary==2),label+" staged state");
            }
        // Freshness allows equality and rejects strictly greater, independently of deadlines.
        for(boolean settlement:new boolean[]{false,true}){
            var f=fixture(settlement,DEFAULTS,s->{});f.clock.now=settlement?16800:11500;
            check(f.store.compareAndReplace(f.old,f.next)==OfflineReadingV3Store.Outcome.STORED,"freshness equality accepted");
        }
    }
    static void syncExceptions() throws Exception {
        for(boolean settlement:new boolean[]{false,true})for(int failAt=0;failAt<4;failAt++){
            var f=fixture(settlement,DEFAULTS,s->{});final int boundary=failAt;int[] syncCalls={0};
            var store=new OfflineReadingV3Store(f.dir,f.clock,d->{if(syncCalls[0]++==boundary)
                throw new IOException("injected directory sync exception");},DEFAULTS,()->true,s->{});
            check(store.compareAndReplace(f.old,f.next)==OfflineReadingV3Store.Outcome.STORAGE_UNCERTAIN,
                    "directory sync exception "+boundary);
            check(syncCalls[0]==boundary+1,"directory sync call reached");
            sameBytes(f,encode(boundary>=2?f.next:f.old,DEFAULTS),"directory sync exact old or new image");
            check(store.compareAndReplace(f.old,f.next)==OfflineReadingV3Store.Outcome.HOLD,
                    "directory sync exception never causes replay");
        }
    }
    static void refusal() throws Exception {
        var f=fixture(false,DEFAULTS,s->{});byte[] original=encode(f.old,DEFAULTS);
        check(f.store.compareAndReplace(f.old,f.old)==OfflineReadingV3Store.Outcome.HOLD,"phase skipping blocked");
        sameBytes(f,original,"invalid transition leaves bytes");noResidue(f,"invalid transition no residue");
        Path ordinary=Files.createTempDirectory("not-a-synthetic-reading-store-");
        reject(()->new OfflineReadingV3Store(ordinary,f.clock,d->{},DEFAULTS,()->true,s->{}),"no arbitrary directory access");
        Files.write(f.dir.resolve("trial-state.bin"),new byte[]{1});
        reject(()->f.store.read(),"legacy collision rejected");
        check(f.store.compareAndReplace(f.old,f.next)==OfflineReadingV3Store.Outcome.HOLD,"legacy never migrated");
        sameBytes(f,original,"legacy collision preserves image");
        var broken=fixture(false,DEFAULTS,s->{});byte[] invalid=new byte[33];
        Files.write(broken.dir.resolve("synthetic-reading-v3.bin"),invalid);
        reject(()->broken.store.read(),"corruption rejected");
        check(broken.store.compareAndReplace(broken.old,broken.next)==OfflineReadingV3Store.Outcome.HOLD,"corruption no repair");
        sameBytes(broken,invalid,"corruption not overwritten");noResidue(broken,"corruption does not start transaction");
        var missing=fixture(false,DEFAULTS,s->{});
        Files.move(missing.dir.resolve("synthetic-reading-v3.bin"),missing.dir.resolve("fixture-retained-original.bin"));
        reject(()->missing.store.read(),"no implicit initialization");
        check(missing.store.compareAndReplace(missing.old,missing.next)==OfflineReadingV3Store.Outcome.HOLD,"missing file remains absent");
        check(!Files.exists(missing.dir.resolve("synthetic-reading-v3.bin")),"not recreated");
        var locked=fixture(false,DEFAULTS,s->{});
        try(FileChannel c=FileChannel.open(locked.dir.resolve("synthetic-reading-v3.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                FileLock held=c.lock()){
            reject(()->locked.store.read(),"same-JVM extra file lock refuses read");
            check(locked.store.compareAndReplace(locked.old,locked.next)==OfflineReadingV3Store.Outcome.HOLD,"same-JVM extra file lock refuses write");
        }
        sameBytes(locked,encode(locked.old,DEFAULTS),"locked image unchanged");
    }
    static void concurrency() throws Exception {
        CountDownLatch atMarker=new CountDownLatch(1),release=new CountDownLatch(1);
        var f=fixture(false,DEFAULTS,s->{if(s==OfflineReadingV3Store.Stage.MARKER_DIRECTORY_SYNCED){
            atMarker.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test wait expired");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}});
        var second=new OfflineReadingV3Store(f.dir.resolve("."),f.clock,d->{},DEFAULTS,()->true,s->{});
        var result=new java.util.concurrent.atomic.AtomicReference<OfflineReadingV3Store.Outcome>();
        Thread worker=new Thread(()->result.set(f.store.compareAndReplace(f.old,f.next)),"synthetic-v3-writer");
        worker.start();
        try{
            check(atMarker.await(5,TimeUnit.SECONDS),"first writer reached marker");
            reject(()->second.read(),"canonical-path in-process exclusion");
            check(second.compareAndReplace(f.old,f.next)==OfflineReadingV3Store.Outcome.HOLD,"concurrent writer denied");
        }finally{release.countDown();worker.join(5000);}
        check(!worker.isAlive(),"synthetic writer exits");
        check(result.get()==OfflineReadingV3Store.Outcome.STORED,"exactly one writer stores");
        check(second.read().equals(f.next),"concurrent final readback");
    }
    static void sequence(int count,boolean uncredited) throws Exception {
        Path dir=Files.createTempDirectory("SYNTHETIC-reading-v3-sequence-");Clock clock=new Clock(START);
        Envelope current=settled(BOOT,seed());Files.write(dir.resolve("synthetic-reading-v3.bin"),encode(current,DEFAULTS),StandardOpenOption.CREATE_NEW);
        var store=new OfflineReadingV3Store(dir,clock,d->{},DEFAULTS,()->clock.allowed,s->{});
        int before=0;
        for(int i=1;i<=count;i++){
            long at=i*10000L;clock.now=at;var p=planned(current.settled(),before,"sequence-"+i,at,DEFAULTS);
            Envelope reserved=pending(BOOT,p,EPOCH);
            check(store.compareAndReplace(current,reserved)==OfflineReadingV3Store.Outcome.STORED,"sequence reservation "+i);
            check(store.read().settled().equals(current.settled()),"previous receipt stays durable while pending "+i);
            check(store.read().reserved().state().readingAttempts()==i,"durable attempt "+i);
            int after=uncredited?0:i;clock.now=at+5400;Envelope done=settled(BOOT,completed(p,after,at,DEFAULTS));
            check(store.compareAndReplace(reserved,done)==OfflineReadingV3Store.Outcome.STORED,"sequence settlement "+i);
            current=store.read();check(current.equals(done),"full settled envelope "+i);
            check(current.settled().state().readingNoCredit()==(uncredited?i:0),"durable no-credit streak "+i);
            check(current.settled().history().hashes().size()==i,"durable history retained "+i);before=after;
        }
        var last=ReadingReceiptLifecycleModel.reserve(current.settled(),BOOT,observed(before),RewardsCore.hashTerm("blocked-next"),
                EPOCH,(count+1)*10000L,false,DEFAULTS);
        check(last.decision()==(uncredited?Decision.READING_NO_CREDIT:Decision.READING_LIMIT)&&last.pending()==null,
                "sequence stops at policy limit");
    }
    public static void main(String[] args) throws Exception {
        normal();faults();boundaries();syncExceptions();refusal();concurrency();sequence(4,true);sequence(10,false);
        System.out.println("PASS "+checks+" synthetic v3 storage assertions; SIMULATED DIRECTORY SYNC, NO LIVE ISSUER OR RECOVERY");
    }
}
