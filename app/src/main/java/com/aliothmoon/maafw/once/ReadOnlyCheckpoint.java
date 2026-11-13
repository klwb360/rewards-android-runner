package com.aliothmoon.maafw.once;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** Explicit compiled read-only checkpoints. No external config, argument or disk value can define one. */
enum ReadOnlyCheckpoint {
 PROGRESS_LAYOUT_V41("rewards-root-progress-layout-20261112-v41",1794466200000L,1794474000000L),
 PROGRESS_STRUCTURE_V43("rewards-root-progress-structure-20261112-v43",1794468900000L,1794474000000L),
 UI_STRUCTURE_V44("rewards-ui-progress-20261112-v44",1794472380000L,1794474000000L),
 UI_STRUCTURE_V45("rewards-ui-progress-20261112-v45",1794474000000L,1794477600000L),
 REWARDS_ENTRY_V46("rewards-ui-recovery-20261112-v46",1794476700000L,1794483900000L),
 ENTRY_DIAGNOSTIC_V47("rewards-entry-diagnostic-20261112-v47",1794479100000L,1794484800000L),
 REWARDS_ENTRY_V48("rewards-ui-recovery-20261112-v48",1794480000000L,1794486600000L),
 UI_READY_V49("rewards-ui-ready-20261112-v49",1794480600000L,1794488400000L),
 UI_FORMAT_V50("rewards-ui-format-20261112-v50",1794481200000L,1794490200000L),
 UI_FIELD_V51("rewards-ui-field-20261112-v51",1794482100000L,1794492000000L),
 UI_LOCATE_V52("rewards-ui-locate-20261112-v52",1794483000000L,1794493800000L),
 UI_BOUND_POINTS_V53("rewards-ui-bound-points-20261112-v53",1794484200000L,1794494700000L);
 static final ReadOnlyCheckpoint CURRENT=UI_BOUND_POINTS_V53;
 enum Projection { LAYOUT, STRUCTURE, UI_STRUCTURE, ENTRY_RECOVERY, ENTRY_DIAGNOSTIC, UI_READY }
 Projection projection(){return switch(this){case PROGRESS_LAYOUT_V41->Projection.LAYOUT;case PROGRESS_STRUCTURE_V43->Projection.STRUCTURE;case UI_STRUCTURE_V44,UI_STRUCTURE_V45->Projection.UI_STRUCTURE;case REWARDS_ENTRY_V46,REWARDS_ENTRY_V48->Projection.ENTRY_RECOVERY;case ENTRY_DIAGNOSTIC_V47->Projection.ENTRY_DIAGNOSTIC;case UI_READY_V49,UI_FORMAT_V50,UI_FIELD_V51,UI_LOCATE_V52,UI_BOUND_POINTS_V53->Projection.UI_READY;};}
 static boolean blocksLiveInput(){return switch(CURRENT){case PROGRESS_LAYOUT_V41,PROGRESS_STRUCTURE_V43,UI_STRUCTURE_V44,UI_STRUCTURE_V45,REWARDS_ENTRY_V46,ENTRY_DIAGNOSTIC_V47,REWARDS_ENTRY_V48,UI_READY_V49,UI_FORMAT_V50,UI_FIELD_V51,UI_LOCATE_V52,UI_BOUND_POINTS_V53->true;};}
 void burnUiRead(Path files,String nonce)throws java.io.IOException{
  if(this!=CURRENT||projection()!=Projection.UI_STRUCTURE||files==null||nonce==null||!nonce.matches("[a-f0-9]{32}")||!current(System.currentTimeMillis()))throw new SecurityException("Exact current UI read required");
  Path dir=files.resolve(directory);Files.createDirectories(dir);
  try(FileChannel c=FileChannel.open(dir.resolve("UI_READ_1.burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   ByteBuffer data=StandardCharsets.UTF_8.encode("checkpoint="+name()+" slot=UI_READ_1 maxUiReads=1 maxFrames=0 maxOcr=0 maxInput=0 expiresUtc="+expires+" nonce="+nonce+"\n");while(data.hasRemaining())c.write(data);c.force(true);
  }
  try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}try(FileChannel c=FileChannel.open(files,StandardOpenOption.READ)){c.force(true);}
 }
 static final long SESSION_TTL=180_000L,CAPTURE_TTL=90_000L;
 private final String directory;private final long starts,expires;
 ReadOnlyCheckpoint(String directory,long starts,long expires){this.directory=directory;this.starts=starts;this.expires=expires;}
 String directory(){return directory;}long starts(){return starts;}long expires(){return expires;}int sourceTask(){return this==UI_BOUND_POINTS_V53||this==UI_LOCATE_V52||this==UI_FIELD_V51||this==UI_FORMAT_V50||this==UI_READY_V49||this==REWARDS_ENTRY_V48||this==REWARDS_ENTRY_V46||this==ENTRY_DIAGNOSTIC_V47?-1:15;}
 boolean current(long utc){return utc>=starts&&utc<expires;}
 boolean admissionCurrent(long utc){return current(utc)&&utc<expires-SESSION_TTL;}
 boolean validIndex(int index){return index==1;}
 long sessionDeadline(long now,long utc){if(now<0||now>Long.MAX_VALUE-SESSION_TTL||!admissionCurrent(utc))throw new SecurityException("Checkpoint unavailable");return now+SESSION_TTL;}
 long deadline(long now,long utc,long parentEnd){if(now<0||now>Long.MAX_VALUE-CAPTURE_TTL||parentEnd<=now||!current(utc))throw new SecurityException("Checkpoint expired");return Math.min(Math.min(now+CAPTURE_TTL,parentEnd),now+Math.min(CAPTURE_TTL,expires-utc));}
 RealCaptureClaim.BurnStore store(Path files,int index){
  if(projection()==Projection.UI_READY||projection()==Projection.UI_STRUCTURE||projection()==Projection.ENTRY_RECOVERY||projection()==Projection.ENTRY_DIAGNOSTIC)throw new SecurityException("UI checkpoint cannot create an image claim");
  if(files==null||!validIndex(index))throw new SecurityException("Only one checkpoint frame");
  return (id,metadata)->{
   if(id==null||!id.matches("[a-f0-9-]{36}")||metadata==null||metadata.length()>2048||!current(System.currentTimeMillis()))throw new SecurityException("Bounded fresh checkpoint claim required");
   Path dir=files.resolve(directory);Files.createDirectories(dir);
   String receipt="checkpoint="+name()+" slot=READ_1 maxFrames=1 maxSwipe=0 maxTap=0 maxReturn=0 maxText=0 maxOcrPerFrame=1 expiresUtc="+expires+"\nclaim="+id+"\n"+metadata;
   try(FileChannel c=FileChannel.open(dir.resolve("READ_1.burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer data=StandardCharsets.UTF_8.encode(receipt);while(data.hasRemaining())c.write(data);c.force(true);}
   try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}try(FileChannel c=FileChannel.open(files,StandardOpenOption.READ)){c.force(true);}
  };
 }
}
