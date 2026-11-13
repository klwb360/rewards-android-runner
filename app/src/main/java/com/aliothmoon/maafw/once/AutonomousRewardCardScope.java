package com.aliothmoon.maafw.once;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** One explicitly approved v27 root-source task, not a renewal or fallback for any older scope. */
final class AutonomousRewardCardScope {
 static final long STARTS_UTC=1794436200000L,EXPIRES_UTC=1794439800000L; // 22:30--23:30 UTC, 2026-11-11
 static final long CAPTURE_TTL=90_000L,SESSION_TTL=180_000L;
 static final String V24_DIRECTORY="rewards-root-business-20261111-v24";
 static final String DIRECTORY="rewards-root-family-business-20261111-v27";
 static boolean current(long utc){return utc>=STARTS_UTC&&utc<EXPIRES_UTC;}
 static boolean admissionCurrent(long utc){return current(utc)&&utc<EXPIRES_UTC-CAPTURE_TTL;}
 static void requireAdmissionCurrent(){if(!admissionCurrent(System.currentTimeMillis()))throw new SecurityException("Autonomous card scope unavailable");}
 static long deadline(long now,long utc,long sessionEnd){
  if(now<0||now>Long.MAX_VALUE-CAPTURE_TTL||sessionEnd<=now||!admissionCurrent(utc))throw new SecurityException("Autonomous card scope unavailable");
  return Math.min(now+CAPTURE_TTL,sessionEnd);
 }
 static boolean slotAllowed(String slot){return "BEFORE".equals(slot)||"AFTER".equals(slot)||"TAP".equals(slot)||"RETURN".equals(slot);}
 static void burn(Path files,String slot,String metadata)throws java.io.IOException{
  if(!current(System.currentTimeMillis())||!slotAllowed(slot))throw new SecurityException("Autonomous card slot unavailable");
  Path dir=files.resolve(DIRECTORY);Files.createDirectories(dir);
  try(FileChannel c=FileChannel.open(dir.resolve(slot+".burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   ByteBuffer b=StandardCharsets.UTF_8.encode("root-business-v27 slot="+slot+" maxFrames=2 maxTap=1 maxReturn=1 expiresUtc="+EXPIRES_UTC+"\n"+metadata);
   while(b.hasRemaining())c.write(b);c.force(true);
  }
  try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}
  try(FileChannel c=FileChannel.open(files,StandardOpenOption.READ)){c.force(true);}
 }
 private AutonomousRewardCardScope(){}
}
