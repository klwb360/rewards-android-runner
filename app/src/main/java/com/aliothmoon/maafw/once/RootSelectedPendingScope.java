package com.aliothmoon.maafw.once;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** New selected-pending B configuration. Never authority or recovery of an earlier namespace. */
final class RootSelectedPendingScope {
 static final boolean ACTIVE=true;
 static final int MAX_PER_PHASE=3,MAX_FRAMES=6,SOURCE_TASK_ID=15;
 static final long STARTS_UTC=1794452400000L,EXPIRES_UTC=1794459600000L;
 static final long SESSION_TTL=180_000L,CAPTURE_TTL=90_000L;
 static final String DIRECTORY="rewards-root-selected-pending-20261112-v35";
 static boolean current(long utc){return utc>=STARTS_UTC&&utc<EXPIRES_UTC;}
 static boolean admissionCurrent(long utc){return current(utc)&&utc<EXPIRES_UTC-SESSION_TTL;}
 static long sessionDeadline(long now,long utc){
  if(now<0||now>Long.MAX_VALUE-SESSION_TTL||!admissionCurrent(utc))throw new SecurityException("Bounded business intent unavailable");
  return now+SESSION_TTL;
 }
 /** Called once for each logical phase, not again for each observation candidate. */
 static long deadline(long now,long utc,long parentEnd){
  if(now<0||now>Long.MAX_VALUE-CAPTURE_TTL||parentEnd<=now||!current(utc))throw new SecurityException("Bounded business phase unavailable");
  return Math.min(Math.min(now+CAPTURE_TTL,parentEnd),now+Math.min(CAPTURE_TTL,EXPIRES_UTC-utc));
 }
 static boolean validIndex(int index){return index>=1&&index<=MAX_PER_PHASE;}
 static String slot(NativeRewardCardSession.Phase phase,int index){
  if(phase==null||!validIndex(index))throw new SecurityException("Bounded candidate invalid");
  return phase.name()+"_"+index;
 }
 static RealCaptureClaim.BurnStore store(Path files,NativeRewardCardSession.Phase phase,int index){
  if(files==null)throw new SecurityException("Private files required");final String name=slot(phase,index);
  return (id,metadata)->{
   if(id==null||!id.matches("[a-f0-9-]{36}"))throw new SecurityException("Exact new capture claim required");
   burn(files,name,"claim="+id+" thisClaimFrames=1\n"+checkedMetadata(metadata));
  };
 }
 static void burnAction(Path files,String slot,String metadata)throws java.io.IOException{
  if(!"TAP".equals(slot)&&!"RETURN".equals(slot))throw new SecurityException("Fixed one-shot action required");
  burn(files,slot,checkedMetadata(metadata));
 }
 private static String checkedMetadata(String metadata){
  if(metadata==null||metadata.length()>2048)throw new SecurityException("Safe bounded metadata required");return metadata;
 }
 private static void burn(Path files,String slot,String metadata)throws java.io.IOException{
  if(!ACTIVE||files==null||!current(System.currentTimeMillis()))throw new SecurityException("Bounded business scope unavailable");
  Path dir=files.resolve(DIRECTORY);Files.createDirectories(dir);
  try(FileChannel c=FileChannel.open(dir.resolve(slot+".burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   ByteBuffer b=StandardCharsets.UTF_8.encode("root-selected-pending-v35 slot="+slot+" maxFrames=6 maxPerPhase=3 maxTap=1 maxReturn=1 selectedBody=EXACT_B maxOcrPerFrame=1 expiresUtc="+EXPIRES_UTC+"\n"+metadata);
   while(b.hasRemaining())c.write(b);c.force(true);
  }
  try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}
  try(FileChannel c=FileChannel.open(files,StandardOpenOption.READ)){c.force(true);}
 }
 private RootSelectedPendingScope(){}
}
