package com.aliothmoon.maafw.once;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** New bounded read-only scope. Never accepts a TAP or any prior scope slot. */
final class ReadOnlyTwoStageScope {
 static final long STARTS_UTC=1794420000000L,EXPIRES_UTC=1794425400000L; // 18:00--19:30 UTC
 static final long CAPTURE_TTL=90_000L,SESSION_TTL=180_000L;
 static final String DIRECTORY="rewards-readonly-two-stage-20261111-v20";
 static boolean current(long utc){return utc>=STARTS_UTC&&utc<EXPIRES_UTC;}
 static boolean admissionCurrent(long utc){return current(utc)&&utc<EXPIRES_UTC-CAPTURE_TTL;}
 static long deadline(long now,long utc,long sessionEnd){
  if(now<0||now>Long.MAX_VALUE-CAPTURE_TTL||sessionEnd<=now||!admissionCurrent(utc))throw new SecurityException("Read-only scope unavailable");
  return Math.min(now+CAPTURE_TTL,sessionEnd);
 }
 static boolean slotAllowed(String phase){return "FIRST".equals(phase)||"SECOND".equals(phase);}
 static void burn(Path files,String phase,String metadata)throws java.io.IOException{
  if(!current(System.currentTimeMillis())||!slotAllowed(phase))throw new SecurityException("Read-only slot unavailable");
  Path dir=files.resolve(DIRECTORY);Files.createDirectories(dir);
  try(FileChannel c=FileChannel.open(dir.resolve(phase+".burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   ByteBuffer b=StandardCharsets.UTF_8.encode("readonly-two-stage-v20 slot="+phase+" maxFrames=2 maxActions=0 expiresUtc="+EXPIRES_UTC+"\n"+metadata);
   while(b.hasRemaining())c.write(b);c.force(true);
  }
  try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}
  try(FileChannel c=FileChannel.open(files,StandardOpenOption.READ)){c.force(true);}
 }
 private ReadOnlyTwoStageScope(){}
}
