package com.aliothmoon.maafw.once;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** One visible diagnostic job, at most three new independently burned frames. No input authority. */
final class RootImageSeriesScope {
 static final boolean ACTIVE=true;
 static final int MAX_FRAMES=3;
 static final long STARTS_UTC=1794443400000L,EXPIRES_UTC=1794447000000L;
 static final long SESSION_TTL=180_000L,CAPTURE_TTL=90_000L;
 static final String DIRECTORY="rewards-root-image-series-20261112-v30";
 static boolean current(long utc){return utc>=STARTS_UTC&&utc<EXPIRES_UTC;}
 static boolean admissionCurrent(long utc){return current(utc)&&utc<EXPIRES_UTC-SESSION_TTL;}
 static long sessionDeadline(long now,long utc){
  if(now<0||now>Long.MAX_VALUE-SESSION_TTL||!admissionCurrent(utc))throw new SecurityException("Image series intent unavailable");
  return now+SESSION_TTL;
 }
 static long deadline(long now,long utc,long sessionEnd){
  if(now<0||now>Long.MAX_VALUE-CAPTURE_TTL||sessionEnd<=now||!current(utc))throw new SecurityException("Image series frame unavailable");
  return Math.min(Math.min(now+CAPTURE_TTL,sessionEnd),now+Math.min(CAPTURE_TTL,EXPIRES_UTC-utc));
 }
 static boolean validIndex(int index){return index>=1&&index<=MAX_FRAMES;}
 static String slot(int index){if(!validIndex(index))throw new SecurityException("Image series index invalid");return "FRAME_"+index;}
 static RealCaptureClaim.BurnStore store(Path files,int index){
  if(files==null)throw new SecurityException("Private files required");final String slot=slot(index);
  return (id,metadata)->{
   if(!ACTIVE||!current(System.currentTimeMillis())||id==null||!id.matches("[a-f0-9-]{36}")||metadata==null||metadata.length()>2048)throw new SecurityException("Image series claim unavailable");
   Path dir=files.resolve(DIRECTORY);Files.createDirectories(dir);
   try(FileChannel c=FileChannel.open(dir.resolve(slot+".burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
    ByteBuffer b=StandardCharsets.UTF_8.encode("root-image-series-v30 index="+index+" maxJobFrames=3 thisClaimFrames=1 inputs=0 maxOcrPerFrame=1 expiresUtc="+EXPIRES_UTC+"\n"+metadata);
    while(b.hasRemaining())c.write(b);c.force(true);
   }
   try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}
   try(FileChannel c=FileChannel.open(files,StandardOpenOption.READ)){c.force(true);}
  };
 }
 private RootImageSeriesScope(){}
}
