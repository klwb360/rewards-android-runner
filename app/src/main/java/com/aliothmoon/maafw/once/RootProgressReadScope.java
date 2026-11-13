package com.aliothmoon.maafw.once;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** New independent current-page read-only scope; no Back or task-tap authority. */
final class RootProgressReadScope {
 static final boolean ACTIVE=true;
 static final int MAX_FRAMES=3,SOURCE_TASK_ID=15;
 static final long STARTS_UTC=1794459600000L,EXPIRES_UTC=1794474000000L;
 static final long SESSION_TTL=180_000L,CAPTURE_TTL=90_000L;
 static final String DIRECTORY="rewards-root-progress-read-20261112-v39";
 static boolean current(long utc){return utc>=STARTS_UTC&&utc<EXPIRES_UTC;}
 static boolean admissionCurrent(long utc){return current(utc)&&utc<EXPIRES_UTC-SESSION_TTL;}
 static long sessionDeadline(long now,long utc){
  if(now<0||now>Long.MAX_VALUE-SESSION_TTL||!admissionCurrent(utc))throw new SecurityException("Current/read intent unavailable");
  return now+SESSION_TTL;
 }
 /** Called once for the read phase, never renewed for another observation. */
 static long deadline(long now,long utc,long parentEnd){
  if(now<0||now>Long.MAX_VALUE-CAPTURE_TTL||parentEnd<=now||!current(utc))throw new SecurityException("Current/read phase unavailable");
  return Math.min(Math.min(now+CAPTURE_TTL,parentEnd),now+Math.min(CAPTURE_TTL,EXPIRES_UTC-utc));
 }
 static boolean validIndex(int index){return index>=1&&index<=MAX_FRAMES;}
 static String slot(int index){
  if(!validIndex(index))throw new SecurityException("Read observation invalid");return "READ_"+index;
 }
 static RealCaptureClaim.BurnStore store(Path files,int index){
  if(files==null)throw new SecurityException("Private files required");final String name=slot(index);
  return (id,metadata)->{
   if(id==null||!id.matches("[a-f0-9-]{36}"))throw new SecurityException("Exact new capture claim required");
   burn(files,name,"claim="+id+" thisClaimFrames=1\n"+checkedMetadata(metadata));
  };
 }
 private static String checkedMetadata(String metadata){
  if(metadata==null||metadata.length()>2048)throw new SecurityException("Safe bounded metadata required");return metadata;
 }
 private static void burn(Path files,String slot,String metadata)throws java.io.IOException{
  if(!ACTIVE||files==null||!current(System.currentTimeMillis()))throw new SecurityException("Current/read scope unavailable");
  Path dir=files.resolve(DIRECTORY);Files.createDirectories(dir);
  try(FileChannel c=FileChannel.open(dir.resolve(slot+".burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   ByteBuffer b=StandardCharsets.UTF_8.encode("root-progress-read-v39 slot="+slot+" maxFrames=3 maxTap=0 maxReturn=0 maxOcrPerFrame=1 expiresUtc="+EXPIRES_UTC+"\n"+metadata);
   while(b.hasRemaining())c.write(b);c.force(true);
  }
  try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}
  try(FileChannel c=FileChannel.open(files,StandardOpenOption.READ)){c.force(true);}
 }
 private RootProgressReadScope(){}
}
