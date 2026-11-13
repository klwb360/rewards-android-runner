package com.aliothmoon.maafw.readingtrust;
import com.aliothmoon.maafw.trial.ReadingCodecAccess;
import com.aliothmoon.maafw.trial.ReadingReservationGuard;
import com.aliothmoon.maafw.trial.ReadingReservationGuard.History;
import io.github.klwb360.rewards.core.RewardsCore;
import io.github.klwb360.rewards.core.RewardsCore.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/** Unwired v2 proposal. No initialization, migration, reservation, recovery or actor API.
 * Operates only on independently provisioned v2 views. Unknown commits stop, never replay.
 * Desktop fsync tests do not establish Android durability or trusted issuance. */
public final class NormalReadingLedger {
 private static final int MAGIC=0x4e524c32,VERSION=2,MAX=131072;
 private static final Set<Path> HELD=ConcurrentHashMap.newKeySet();
 public interface Clock {long elapsed();long epoch();long boot();}
 public interface DirectorySync {void sync(Path directory)throws IOException;}
 enum Stage {MARKER_SYNCED,STAGED_SYNCED,REPLACED_SYNCED,MARKER_REMOVED}
 interface Fault {void after(Stage stage)throws IOException;}
 public enum Outcome {HOLD,CONFIRMED,STORAGE_UNCERTAIN}
 /** Inert audit, never a capability. Counts indicate observed progress, not attribution. */
 public record Receipt(String type,String day,long boot,long generation,String fingerprint,String article,int attempt,
   String dispatch,int before,int after,int target,int noCreditBefore,int noCreditAfter,Decision decision,
   long receivedTrue,long articleReady,long returned,long verificationStart,long firstAt,long secondAt,long committedAt){ @Override public String toString(){return "NormalReadingReceipt[redacted]";} }
 public record View(long generation,State state,History history,Receipt receipt){
  @Override public String toString(){return "NormalReadingView[redacted]";}
 }
 private final Path dir,data,txn,next,bak,lock;private final Clock clock;private final DirectorySync sync;
 private final Limits limits;private final Fault fault;
 public NormalReadingLedger(Path directory,Clock clock,DirectorySync sync,Limits limits)throws IOException{
  this(directory,clock,sync,limits,stage->{});
 }
 NormalReadingLedger(Path directory,Clock clock,DirectorySync sync,Limits limits,Fault fault)throws IOException{
  dir=Objects.requireNonNull(directory).toAbsolutePath().normalize();
  if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(dir))throw new IOException("Existing private directory required");
  this.clock=Objects.requireNonNull(clock);this.sync=Objects.requireNonNull(sync);this.limits=Objects.requireNonNull(limits);this.fault=Objects.requireNonNull(fault);
  data=dir.resolve("normal-reading-v2.bin");txn=dir.resolve("normal-reading-v2.txn");next=dir.resolve("normal-reading-v2.new");bak=dir.resolve("normal-reading-v2.bak");lock=dir.resolve("normal-reading-v2.lock");
 }
 public Outcome settle(TrustedReadingSettlementGrant g){
  if(g==null||!g.claim())return Outcome.HOLD;
  if(g.owner!=this||!HELD.add(dir))return Outcome.HOLD;
  try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
   try(FileLock held=channel.tryLock()){
    if(held==null)return Outcome.HOLD;
    long boot=clock.boot(),now=clock.elapsed();View old=read(boot);
    if(!Objects.equals(old,g.expected)||!eligible(g,old,boot,now))return Outcome.HOLD;
    Step step=RewardsCore.verify(old.state(),Task.READING,g.second,clock.epoch(),now,false,limits);
    if(step.decision()!=Decision.ALLOW&&step.decision()!=Decision.COMPLETE&&step.decision()!=Decision.READING_NO_CREDIT)return Outcome.HOLD;
    long commit=clock.elapsed();if(commit<now||!eligible(g,old,clock.boot(),commit))return Outcome.HOLD;
    State before=old.state();History history=new History(old.history().day(),old.history().hashes(),"","");
    Receipt receipt=new Receipt("NORMAL_AFTER_DELIVERY",before.day(),boot,Math.addExact(old.generation(),1),old.history().pendingState(),old.history().pendingArticle(),before.readingAttempts(),g.dispatch,before.pendingBefore(),g.second.counter().current(),before.pendingTarget(),before.readingNoCredit(),step.state().readingNoCredit(),step.decision(),g.receivedTrue,g.articleReady,g.returned,g.verificationStart,g.firstAt,g.secondAt,commit);
    View after=new View(old.generation()+1,step.state(),history,receipt);byte[] oldBytes=encode(old,boot),newBytes=encode(after,boot);
    long beforeWrite=clock.elapsed();if(beforeWrite<commit||!eligible(g,old,clock.boot(),beforeWrite))return Outcome.HOLD;
    create(txn,marker(oldBytes,newBytes));sync.sync(dir);fault.after(Stage.MARKER_SYNCED);
    create(next,newBytes);sync.sync(dir);fault.after(Stage.STAGED_SYNCED);
    long beforeReplace=clock.elapsed();if(beforeReplace<beforeWrite||!eligible(g,old,clock.boot(),beforeReplace))return Outcome.STORAGE_UNCERTAIN;
    Files.move(next,data,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync.sync(dir);fault.after(Stage.REPLACED_SYNCED);
    Files.delete(txn);sync.sync(dir);fault.after(Stage.MARKER_REMOVED);
    View confirmed=read(boot);long confirmedAt=clock.elapsed();if(confirmedAt<beforeReplace||!after.equals(confirmed)||!eligible(g,old,clock.boot(),confirmedAt))return Outcome.STORAGE_UNCERTAIN;
    return Outcome.CONFIRMED;
   }
  }catch(IOException|RuntimeException failed){return Outcome.STORAGE_UNCERTAIN;}
  finally{HELD.remove(dir);}
 }
 private boolean eligible(TrustedReadingSettlementGrant g,View old,long boot,long now){
  if(old==null||boot<0||boot!=g.boot||now<0||!g.current()||old.generation()<0||old.generation()==Long.MAX_VALUE)return false;
  State s=old.state();History h=old.history();
  // Match RewardsCore.guardGlobal/guard at every pre-write and post-write checkpoint.
  if(now<s.runStartedAt()||now-s.runStartedAt()>=limits.maxRunMillis())return false;
  if(s.readingStartedAt()!=0&&(now<s.readingStartedAt()||now-s.readingStartedAt()>=limits.maxReadingMillis()))return false;
  if(!s.day().equals(Instant.ofEpochMilli(clock.epoch()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString())||!Task.READING.name().equals(s.pendingTask())||h.pendingState().isEmpty()||!h.pendingState().equals(com.aliothmoon.maafw.trial.ReadingCodecAccess.fingerprint(s))||!h.hashes().contains(h.pendingArticle()))return false;
  if(g.dispatch==null||!g.dispatch.matches("[a-f0-9]{32}")||g.receivedTrue<0||g.articleReady<g.receivedTrue||g.returned<g.articleReady||g.returned-g.articleReady<5000||g.verificationStart<g.returned||g.firstAt<=g.verificationStart||g.secondAt<=g.firstAt||now<g.secondAt||now-g.secondAt>1500||now<s.runStartedAt()||now<s.readingStartedAt())return false;
  return readable(g.first)&&readable(g.second)&&g.first.counter().equals(g.second.counter())&&g.second.counter().target()==s.pendingTarget()&&g.second.counter().current()>=s.pendingBefore();
 }
 private static boolean readable(Observation o){return o!=null&&o.status()==ParseStatus.OK&&o.counter()!=null&&o.counter().task()==Task.READING&&o.counter().today()&&o.counter().current()>=0&&o.counter().current()<=o.counter().target()&&o.counter().target()>0;}
 private View read(long boot)throws IOException{
  for(Path residue:List.of(txn,next,bak))if(!Files.notExists(residue,LinkOption.NOFOLLOW_LINKS))throw new IOException("Transaction unresolved");
  for(String legacy:List.of("reading-ledger.bin","reading-ledger.txn","reading-ledger.new","reading-ledger.bak","trial-state.bin","trial-state.intent","trial-state.new","trial-state.bak","reading-journal.bin","reading-journal.intent","reading-journal.new","reading-journal.bak"))if(!Files.notExists(dir.resolve(legacy),LinkOption.NOFOLLOW_LINKS))throw new IOException("No implicit migration");
  if(!Files.isRegularFile(data,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(data))throw new IOException("Existing v2 image required");
  try(FileChannel c=FileChannel.open(data,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
   long size=c.size();if(size<33||size>MAX)throw new IOException("Bounds");ByteBuffer b=ByteBuffer.allocate((int)size);
   while(b.hasRemaining())if(c.read(b)<=0)throw new IOException("Short image");if(c.size()!=size)throw new IOException("Changed image");return decode(b.array(),boot);
  }
 }
 static void validate(View v,long boot)throws IOException{
  if(v==null||v.generation()<0||boot<0||v.state()==null||v.history()==null)throw new IOException("View missing");
  State s=v.state();History h=v.history();ReadingCodecAccess.state(s,boot);ReadingCodecAccess.history(h,boot);
  boolean pending=Task.READING.name().equals(s.pendingTask());
  if(!s.day().equals(h.day())||s.readingAttempts()!=h.hashes().size()||pending!=!h.pendingState().isEmpty()||pending&&(!com.aliothmoon.maafw.trial.ReadingCodecAccess.fingerprint(s).equals(h.pendingState())||!h.hashes().contains(h.pendingArticle())))throw new IOException("Bound history mismatch");
  Receipt r=v.receipt();if(r!=null){
   if(!r.type().equals("NORMAL_AFTER_DELIVERY")||!r.day().equals(s.day())||r.boot()!=boot||r.generation()!=v.generation()||!r.fingerprint().matches("[a-f0-9]{64}")||!r.article().matches("[a-f0-9]{64}")||!h.hashes().contains(r.article())||!r.dispatch().matches("[a-f0-9]{32}")||r.attempt()!=s.readingAttempts()||r.before()<0||r.after()<r.before()||r.target()<r.after()||r.target()<1||r.noCreditBefore()<0||r.noCreditAfter()!=s.readingNoCredit()||r.noCreditAfter()!=(r.after()>r.before()?0:r.noCreditBefore()+1)||!s.pendingTask().isEmpty()||!h.pendingState().isEmpty()||r.receivedTrue()<0||r.articleReady()<r.receivedTrue()||r.returned()<r.articleReady()||r.returned()-r.articleReady()<5000||r.verificationStart()<r.returned()||r.firstAt()<=r.verificationStart()||r.secondAt()<=r.firstAt()||r.committedAt()<r.secondAt())throw new IOException("Normal receipt invalid");
   Decision expected=r.after()==r.target()?Decision.COMPLETE:r.noCreditAfter()>=4?Decision.READING_NO_CREDIT:Decision.ALLOW;if(r.decision()!=expected)throw new IOException("Decision mismatch");
  }
 }
 static byte[] encode(View v,long boot)throws IOException{
  validate(v,boot);ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream o=new DataOutputStream(bytes)){
   o.writeInt(MAGIC);o.writeInt(VERSION);o.writeLong(boot);o.writeLong(v.generation());blob(o,ReadingCodecAccess.state(v.state(),boot));blob(o,ReadingCodecAccess.history(v.history(),boot));o.writeBoolean(v.receipt()!=null);
   if(v.receipt()!=null){Receipt r=v.receipt();o.writeUTF(r.type());o.writeUTF(r.day());o.writeLong(r.boot());o.writeLong(r.generation());o.writeUTF(r.fingerprint());o.writeUTF(r.article());o.writeInt(r.attempt());o.writeUTF(r.dispatch());o.writeInt(r.before());o.writeInt(r.after());o.writeInt(r.target());o.writeInt(r.noCreditBefore());o.writeInt(r.noCreditAfter());o.writeUTF(r.decision().name());o.writeLong(r.receivedTrue());o.writeLong(r.articleReady());o.writeLong(r.returned());o.writeLong(r.verificationStart());o.writeLong(r.firstAt());o.writeLong(r.secondAt());o.writeLong(r.committedAt());}
  }return checked(bytes.toByteArray());
 }
 static View decode(byte[] bytes,long boot)throws IOException{
  try(DataInputStream i=new DataInputStream(new ByteArrayInputStream(body(bytes)))){
   if(i.readInt()!=MAGIC||i.readInt()!=VERSION||i.readLong()!=boot)throw new IOException("No migration/schema mismatch");long generation=i.readLong();State state=ReadingCodecAccess.state(blob(i,65536),boot);History history=ReadingCodecAccess.history(blob(i,8192),boot);Receipt receipt=null;
   if(i.readBoolean())receipt=new Receipt(i.readUTF(),i.readUTF(),i.readLong(),i.readLong(),i.readUTF(),i.readUTF(),i.readInt(),i.readUTF(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),Decision.valueOf(i.readUTF()),i.readLong(),i.readLong(),i.readLong(),i.readLong(),i.readLong(),i.readLong(),i.readLong());
   if(i.read()!=-1)throw new IOException("Trailing bytes");View v=new View(generation,state,history,receipt);validate(v,boot);return v;
  }catch(RuntimeException e){throw new IOException("Invalid image",e);}
 }
 private static void blob(DataOutputStream o,byte[] b)throws IOException{o.writeInt(b.length);o.write(b);}
 private static byte[] blob(DataInputStream i,int max)throws IOException{int n=i.readInt();if(n<1||n>max)throw new IOException("Field bounds");byte[] b=new byte[n];i.readFully(b);return b;}
 private static byte[] marker(byte[] old,byte[] next)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();try(DataOutputStream o=new DataOutputStream(b)){o.writeInt(0x4e525432);o.writeInt(VERSION);o.write(sha(old));o.write(sha(next));}return checked(b.toByteArray());}
 private static byte[] sha(byte[] b)throws IOException{try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
 private static byte[] checked(byte[] b)throws IOException{if(b.length+32>MAX)throw new IOException("Size");byte[] out=Arrays.copyOf(b,b.length+32);System.arraycopy(sha(b),0,out,b.length,32);return out;}
 private static byte[] body(byte[] b)throws IOException{if(b.length<=32||b.length>MAX)throw new IOException("Size");byte[] body=Arrays.copyOf(b,b.length-32);if(!MessageDigest.isEqual(sha(body),Arrays.copyOfRange(b,b.length-32,b.length)))throw new IOException("Checksum");return body;}
 private static void create(Path p,byte[] b)throws IOException{try(FileChannel c=FileChannel.open(p,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer bytes=ByteBuffer.wrap(b);while(bytes.hasRemaining())if(c.write(bytes)<=0)throw new IOException("Write stalled");c.force(true);}}
}
