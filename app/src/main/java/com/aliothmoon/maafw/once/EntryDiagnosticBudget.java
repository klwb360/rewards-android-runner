package com.aliothmoon.maafw.once;
import java.nio.ByteBuffer;import java.nio.channels.FileChannel;import java.nio.charset.StandardCharsets;import java.nio.file.*;
/** V47 slots only; never authorizes navigation or a business read. */
final class EntryDiagnosticBudget {
 enum Slot {RUN_1,DIAGNOSTIC_READ_1}
 interface Store {void burn(Slot slot)throws Exception;}
 private final Store store;private final boolean[] attempted=new boolean[2],confirmed=new boolean[2];private boolean uncertain;
 EntryDiagnosticBudget(Store store){if(store==null)throw new IllegalArgumentException();this.store=store;}
 static EntryDiagnosticBudget live(Path files,String nonce){if(files==null||nonce==null||!nonce.matches("[a-f0-9]{32}"))throw new IllegalArgumentException();return new EntryDiagnosticBudget(slot->{var policy=ReadOnlyCheckpoint.ENTRY_DIAGNOSTIC_V47;if(policy!=ReadOnlyCheckpoint.CURRENT||!policy.current(System.currentTimeMillis()))throw new SecurityException("V47 expired");Path dir=files.resolve(policy.directory());Files.createDirectories(dir);try(FileChannel c=FileChannel.open(dir.resolve(slot.name()+".burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer data=StandardCharsets.UTF_8.encode("checkpoint=ENTRY_DIAGNOSTIC_V47 slot="+slot+" nonce="+nonce+" expiresUtc="+policy.expires()+" maxReads=1 maxActions=0\n");while(data.hasRemaining())c.write(data);c.force(true);}try(FileChannel c=FileChannel.open(dir,StandardOpenOption.READ)){c.force(true);}try(FileChannel c=FileChannel.open(files,StandardOpenOption.READ)){c.force(true);}});}
 synchronized void burn(Slot slot)throws Exception{if(slot==null||uncertain||attempted[slot.ordinal()]||slot==Slot.DIAGNOSTIC_READ_1&&!confirmed[0])throw new SecurityException("Unspent V47 slot required");attempted[slot.ordinal()]=true;try{store.burn(slot);confirmed[slot.ordinal()]=true;}catch(Exception e){uncertain=true;throw e;}}
 synchronized String summary(){return "runAttempted="+attempted[0]+" runConsumed="+confirmed[0]+" diagnosticReadAttempted="+attempted[1]+" diagnosticReadConsumed="+confirmed[1]+" slotWriteUncertain="+uncertain;}
}
