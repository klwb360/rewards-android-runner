package com.aliothmoon.maafw.once;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** v27 root image scopes, disjoint from Projection and every historic namespace.
 * An enum is configuration, not authority. RootCaptureIntent and live claims are required. */
final class RootCaptureScope {
 enum Mode { PREFLIGHT, BUSINESS }
 enum Phase { FIRST, SECOND }
 static final long STARTS_UTC=1794436200000L,EXPIRES_UTC=1794439800000L;
 static final long CAPTURE_TTL=90_000L,SESSION_TTL=180_000L;
 static final String V24_PREFLIGHT_DIRECTORY="rewards-root-preflight-20261111-v24";
 static final String PREFLIGHT_DIRECTORY="rewards-root-family-preflight-20261111-v27";
 static boolean current(long utc){return utc>=STARTS_UTC&&utc<EXPIRES_UTC;}
 static boolean admissionCurrent(long utc){return current(utc)&&utc<EXPIRES_UTC-CAPTURE_TTL;}
 static long sessionDeadline(long now,long utc){
  if(now<0||now>Long.MAX_VALUE-SESSION_TTL||!admissionCurrent(utc))throw new SecurityException("Root intent unavailable");
  return now+Math.min(SESSION_TTL,EXPIRES_UTC-utc);
 }
 static long deadline(long now,long utc,long sessionEnd){
  if(now<0||now>Long.MAX_VALUE-CAPTURE_TTL||sessionEnd<=now||!admissionCurrent(utc))throw new SecurityException("Root phase unavailable");
  return Math.min(now+CAPTURE_TTL,sessionEnd);
 }
 static boolean allowed(Mode mode,Phase phase){return mode!=null&&phase!=null&&(mode==Mode.BUSINESS||phase==Phase.FIRST);}
 static String directory(Mode mode){
  if(mode==null)throw new SecurityException("Root mode required");
  return mode==Mode.PREFLIGHT?PREFLIGHT_DIRECTORY:AutonomousRewardCardScope.DIRECTORY;
 }
 static String slot(Mode mode,Phase phase){
  if(!allowed(mode,phase))throw new SecurityException("Root phase unavailable");
  return mode==Mode.PREFLIGHT?"FIRST":phase==Phase.FIRST?"BEFORE":"AFTER";
 }
 static RealCaptureClaim.BurnStore store(Path files,Mode mode,Phase phase){return storeAt(files,mode,phase,directory(mode));}
 static RealCaptureClaim.BurnStore diagnosticStore(Path files){
  var delegate=storeAt(files,Mode.PREFLIGHT,Phase.FIRST,RootFamilyDiagnosticProfile.DIRECTORY);
  return (id,metadata)->{if(!RootFamilyDiagnosticProfile.current(System.currentTimeMillis()))throw new SecurityException("Diagnostic expired");delegate.burn(id,metadata);};
 }
 private static RealCaptureClaim.BurnStore storeAt(Path files,Mode mode,Phase phase,String directory){
  if(files==null||!allowed(mode,phase))throw new SecurityException("Root claim store unavailable");
  final String slot=slot(mode,phase);
  return (id,metadata)->{
   boolean diagnostic=directory.equals(RootFamilyDiagnosticProfile.DIRECTORY);
   if(!(diagnostic?RootFamilyDiagnosticProfile.current(System.currentTimeMillis()):current(System.currentTimeMillis()))||id==null||!id.matches("[a-f0-9-]{36}")||metadata==null)throw new SecurityException("Root claim unavailable");
   Path dir=files.resolve(directory);Files.createDirectories(dir);
   try(FileChannel c=FileChannel.open(dir.resolve(slot+".burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
    ByteBuffer b=StandardCharsets.UTF_8.encode((diagnostic?"root-image-audit-v28":"root-source-v27")+" mode="+mode+" phase="+phase+" frames="+(mode==Mode.PREFLIGHT?1:2)+" taskTap="+(mode==Mode.PREFLIGHT?0:1)+" returnBack="+(mode==Mode.PREFLIGHT?0:1)+" expiresUtc="+(diagnostic?RootFamilyDiagnosticProfile.EXPIRES_UTC:EXPIRES_UTC)+"\n"+metadata);
    while(b.hasRemaining())c.write(b);c.force(true);
   }
   try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}
   try(FileChannel c=FileChannel.open(files,StandardOpenOption.READ)){c.force(true);}
  };
 }
 private RootCaptureScope(){}
}
