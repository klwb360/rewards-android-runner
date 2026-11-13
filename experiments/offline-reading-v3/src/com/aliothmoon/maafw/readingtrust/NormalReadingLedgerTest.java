package com.aliothmoon.maafw.readingtrust;
import com.aliothmoon.maafw.trial.ReadingReservationGuard;
import com.aliothmoon.maafw.trial.ReadingReservationGuard.History;
import io.github.klwb360.rewards.core.RewardsCore;
import io.github.klwb360.rewards.core.RewardsCore.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.time.Instant;
import java.util.*;
/** All capabilities here are forged by test reflection. No production issuer exists. */
public final class NormalReadingLedgerTest {
 static int checks;static final long EPOCH=Instant.parse("2026-11-12T12:00:00Z").toEpochMilli();
 static class Clock implements NormalReadingLedger.Clock {long now=7300,boot=8;public long elapsed(){return now;}public long epoch(){return EPOCH;}public long boot(){return boot;}}
 record Fixture(Path dir,Clock clock,NormalReadingLedger ledger,NormalReadingLedger.View old){}
 static Fixture fixture(int failures,NormalReadingLedger.Fault fault)throws Exception{
  Path dir=Files.createTempDirectory("SYNTHETIC-normal-ledger-");Clock clock=new Clock();
  NormalReadingLedger ledger=new NormalReadingLedger(dir,clock,p->{if(!Files.isDirectory(p))throw new java.io.IOException("Synthetic sync path");},Limits.defaults(),fault);
  Set<String> articles=new HashSet<>();for(int n=0;n<=failures;n++)articles.add(RewardsCore.hashTerm("synthetic article "+n));
  State state=new State("2026-11-12",100,200,failures+1,failures,0,0,Set.of(),"READING",0,30,"");
  History h=new History(state.day(),articles,com.aliothmoon.maafw.trial.ReadingCodecAccess.fingerprint(state),RewardsCore.hashTerm("synthetic article "+failures));
  NormalReadingLedger.View old=new NormalReadingLedger.View(1,state,h,null);
  Files.write(dir.resolve("normal-reading-v2.bin"),encode(old,8),StandardOpenOption.CREATE_NEW);return new Fixture(dir,clock,ledger,old);
 }
 static byte[] encode(NormalReadingLedger.View v,long boot)throws Exception{Method m=NormalReadingLedger.class.getDeclaredMethod("encode",NormalReadingLedger.View.class,long.class);m.setAccessible(true);return(byte[])m.invoke(null,v,boot);}
 static NormalReadingLedger.View decode(Path p)throws Exception{Method m=NormalReadingLedger.class.getDeclaredMethod("decode",byte[].class,long.class);m.setAccessible(true);return(NormalReadingLedger.View)m.invoke(null,Files.readAllBytes(p),8L);}
 static TrustedReadingSettlementGrant grant(Fixture f,int after,long first,long second)throws Exception{
  Constructor<?> c=TrustedReadingSettlementGrant.class.getDeclaredConstructors()[0];c.setAccessible(true);
  Observation o=new Observation(ParseStatus.OK,new Counter(Task.READING,after,30,true),"synthetic");
  return(TrustedReadingSettlementGrant)c.newInstance(f.ledger,f.old,8L,"a".repeat(32),500L,600L,6000L,6100L,first,o,second,o);
 }
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] args)throws Exception{
  for(int after:new int[]{0,3,30}){
   Fixture f=fixture(0,x->{});var g=grant(f,after,7000,7200);check(f.ledger.settle(g)==NormalReadingLedger.Outcome.CONFIRMED);
   var v=decode(f.dir.resolve("normal-reading-v2.bin"));check(v.state().pendingTask().isEmpty());check(v.history().pendingState().isEmpty());check(v.history().hashes().equals(f.old.history().hashes()));check(v.receipt()!=null&&v.receipt().after()==after);check(v.state().readingNoCredit()==(after==0?1:0));check(f.ledger.settle(g)==NormalReadingLedger.Outcome.HOLD);
  }
  Fixture four=fixture(3,x->{});check(four.ledger.settle(grant(four,0,7000,7200))==NormalReadingLedger.Outcome.CONFIRMED);check(decode(four.dir.resolve("normal-reading-v2.bin")).receipt().decision()==Decision.READING_NO_CREDIT);
  for(long[] times:new long[][]{{500,7200},{7000,7000},{7201,7200},{5000,5100}}){Fixture f=fixture(0,x->{});var g=grant(f,0,times[0],times[1]);check(f.ledger.settle(g)==NormalReadingLedger.Outcome.HOLD);check(decode(f.dir.resolve("normal-reading-v2.bin")).equals(f.old));check(f.ledger.settle(g)==NormalReadingLedger.Outcome.HOLD);}
  Fixture stale=fixture(0,x->{});stale.clock.now=8701;check(stale.ledger.settle(grant(stale,0,7000,7200))==NormalReadingLedger.Outcome.HOLD);
  Fixture boot=fixture(0,x->{});boot.clock.boot=9;check(boot.ledger.settle(grant(boot,0,7000,7200))!=NormalReadingLedger.Outcome.CONFIRMED);
  Fixture wrong=fixture(0,x->{}),other=fixture(0,x->{});check(other.ledger.settle(grant(wrong,0,7000,7200))==NormalReadingLedger.Outcome.HOLD);
  for(NormalReadingLedger.Stage stage:NormalReadingLedger.Stage.values()){
   Fixture f=fixture(0,s->{if(s==stage)throw new java.io.IOException("synthetic crash");});var g=grant(f,3,7000,7200);
   check(f.ledger.settle(g)==NormalReadingLedger.Outcome.STORAGE_UNCERTAIN);check(f.ledger.settle(g)==NormalReadingLedger.Outcome.HOLD);
   var disk=decode(f.dir.resolve("normal-reading-v2.bin"));boolean old=stage==NormalReadingLedger.Stage.MARKER_SYNCED||stage==NormalReadingLedger.Stage.STAGED_SYNCED;
   check(old?disk.equals(f.old):disk.state().pendingTask().isEmpty()&&disk.history().pendingState().isEmpty()&&disk.receipt()!=null);
  }
  for(Constructor<?> c:TrustedReadingSettlementGrant.class.getDeclaredConstructors())check(Modifier.isPrivate(c.getModifiers()));
  check(Arrays.stream(TrustedReadingSettlementGrant.class.getDeclaredMethods()).noneMatch(m->Modifier.isPublic(m.getModifiers())&&m.getReturnType()==TrustedReadingSettlementGrant.class));
  System.out.println("PASS "+checks+" synthetic normal-settlement assertions; NO LIVE ISSUER");
 }
}
