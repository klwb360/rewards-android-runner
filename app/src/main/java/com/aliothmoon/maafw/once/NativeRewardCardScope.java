package com.aliothmoon.maafw.once;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** Independent visible More Quote item test. Never reopens check-in or current-status slots. */
final class NativeRewardCardScope {
 static final long STARTS_UTC=1794416400000L; // 2026-11-11 17:00 UTC
 static final long EXPIRES_UTC=1794420000000L; // 2026-11-11 18:00 UTC
 static final long CAPTURE_TTL=90_000L,SESSION_TTL=180_000L;
 static final String DIRECTORY="rewards-more-quote-20261111-v18";
 static boolean current(long utc){return utc>=STARTS_UTC&&utc<EXPIRES_UTC;}
 static boolean admissionCurrent(long utc){return current(utc)&&utc<EXPIRES_UTC-CAPTURE_TTL;}
 static void requireAdmissionCurrent(){if(!admissionCurrent(System.currentTimeMillis()))throw new SecurityException("Card admission expired");}
 static long deadline(long elapsed,long utc){
  if(elapsed<0||elapsed>Long.MAX_VALUE-CAPTURE_TTL||!admissionCurrent(utc))throw new SecurityException("Card deadline unavailable");
  return elapsed+CAPTURE_TTL;
 }
 static boolean slotAllowed(String slot){return "BEFORE".equals(slot)||"AFTER".equals(slot)||"TAP".equals(slot);}
 static void burn(Path privateFiles,String slot,String metadata)throws java.io.IOException{
  if(!current(System.currentTimeMillis())||!slotAllowed(slot))throw new SecurityException("Card scope unavailable");
  Path dir=privateFiles.resolve(DIRECTORY);Files.createDirectories(dir);
  try(FileChannel c=FileChannel.open(dir.resolve(slot+".burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   ByteBuffer b=StandardCharsets.UTF_8.encode("more-quote-v18 slot="+slot+" maxFrames=2 maxAccountActions=1 expiresUtc="+EXPIRES_UTC+"\n"+metadata);
   while(b.hasRemaining())c.write(b);c.force(true);
  }
  try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}
  try(FileChannel c=FileChannel.open(privateFiles,StandardOpenOption.READ)){c.force(true);}
 }
 static RealCaptureClaim.BurnStore store(Path files,NativeRewardCardSession.Phase phase){
  if(phase==null)throw new IllegalArgumentException("Card phase required");
  return (id,metadata)->burn(files,phase.name(),metadata);
 }
 private NativeRewardCardScope(){}
}