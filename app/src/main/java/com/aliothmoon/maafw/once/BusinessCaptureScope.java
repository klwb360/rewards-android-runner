package com.aliothmoon.maafw.once;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** New explicit 2026-11-11 business-test grant. Old capture slots are never touched.
 * Exactly two separately named, visible approvals, one frame per purpose. No action API.
 * Expiry is a fixed UTC ceiling, not a restart-relative renewal. */
final class BusinessCaptureScope {
 static final long EXPIRES_UTC=1794409200000L; // 2026-11-11T15:00:00Z
 static final long TTL=90_000L;
 enum Purpose { BASELINE, AFTER_ACTION }
 static boolean current(long utc){return utc>=1794403800000L && utc<EXPIRES_UTC-TTL;}
 static void requireCurrent(){if(!current(System.currentTimeMillis()))throw new SecurityException("Business scope expired");}
 static RealCaptureClaim.BurnStore store(Path privateFiles,Purpose purpose){
  if(purpose==null)throw new IllegalArgumentException();
  return (id,metadata)->{
   requireCurrent();
   Path dir=privateFiles.resolve("rewards-business-20261111-v13");
   Files.createDirectories(dir);
   Path slot=dir.resolve(purpose.name()+".burned");
   // CREATE_NEW, force and directory force must all succeed. No overwrite/recovery/retry.
   try(FileChannel c=FileChannel.open(slot,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
    ByteBuffer bytes=StandardCharsets.UTF_8.encode("business-v13 purpose="+purpose+" expiresUtc="+EXPIRES_UTC+" maxFrames=1 maxAccountActions=0\n"+metadata);
    while(bytes.hasRemaining())c.write(bytes);c.force(true);
   }
   try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}
   try(FileChannel c=FileChannel.open(privateFiles,StandardOpenOption.READ)){c.force(true);}
  };
 }
 private BusinessCaptureScope(){}
}
