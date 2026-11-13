package com.aliothmoon.maafw.once;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** Independent V46 durable once-only slots. Any uncertain write poisons this run; no refund. */
final class RewardsEntryBudget {
 enum Slot { RUN_1, PREPARE_1, NAV_1, UI_READ_1 }
 interface Store {void burn(Slot slot,String nonce)throws Exception;}
 private final Store store;private final String nonce;private final boolean[] attempted=new boolean[4],confirmed=new boolean[4];private boolean uncertain;
 RewardsEntryBudget(Store store,String nonce){if(store==null||nonce==null||!nonce.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Fixed run identity");this.store=store;this.nonce=nonce;}
 static RewardsEntryBudget live(Path files,String nonce){return new RewardsEntryBudget((slot,id)->{
  var policy=ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_BOUND_POINTS_V53?ReadOnlyCheckpoint.UI_BOUND_POINTS_V53:ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_LOCATE_V52?ReadOnlyCheckpoint.UI_LOCATE_V52:ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_FIELD_V51?ReadOnlyCheckpoint.UI_FIELD_V51:ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_FORMAT_V50?ReadOnlyCheckpoint.UI_FORMAT_V50:ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.UI_READY_V49?ReadOnlyCheckpoint.UI_READY_V49:ReadOnlyCheckpoint.CURRENT==ReadOnlyCheckpoint.REWARDS_ENTRY_V48?ReadOnlyCheckpoint.REWARDS_ENTRY_V48:ReadOnlyCheckpoint.REWARDS_ENTRY_V46;
  if(policy!=ReadOnlyCheckpoint.CURRENT||!policy.current(System.currentTimeMillis())||files==null)throw new SecurityException("V46 unavailable");
  if((policy==ReadOnlyCheckpoint.UI_READY_V49||policy==ReadOnlyCheckpoint.UI_FORMAT_V50||policy==ReadOnlyCheckpoint.UI_FIELD_V51||policy==ReadOnlyCheckpoint.UI_BOUND_POINTS_V53)&&(slot==Slot.PREPARE_1||slot==Slot.NAV_1))throw new SecurityException("Read-only checkpoint");
  Path directory=files.resolve(policy.directory());Files.createDirectories(directory);
  try(FileChannel channel=FileChannel.open(directory.resolve(slot.name()+".burned"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
   ByteBuffer bytes=StandardCharsets.UTF_8.encode("checkpoint="+policy.name()+" slot="+slot.name()+" nonce="+id+" expiresUtc="+policy.expires()+" maxHomeNavigation="+((policy==ReadOnlyCheckpoint.UI_READY_V49||policy==ReadOnlyCheckpoint.UI_FORMAT_V50||policy==ReadOnlyCheckpoint.UI_FIELD_V51||policy==ReadOnlyCheckpoint.UI_LOCATE_V52||policy==ReadOnlyCheckpoint.UI_BOUND_POINTS_V53)?0:1)+" maxShowOnScreen="+(policy==ReadOnlyCheckpoint.UI_LOCATE_V52?1:0)+" taskTaps=0 screenshots=0 ocr=0\n");while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
  }
  try(FileChannel channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}try(FileChannel channel=FileChannel.open(files,StandardOpenOption.READ)){channel.force(true);}
 },nonce);}
 synchronized void burn(Slot slot)throws Exception{
  if(slot==null||uncertain||attempted[slot.ordinal()]||slot!=Slot.RUN_1&&!confirmed[0]||slot==Slot.NAV_1&&!confirmed[1]||slot==Slot.PREPARE_1&&attempted[3]||slot==Slot.UI_READ_1&&attempted[1]&&!confirmed[2])throw new SecurityException("Exact unspent slot required");
  attempted[slot.ordinal()]=true;try{store.burn(slot,nonce);confirmed[slot.ordinal()]=true;}catch(Exception failed){uncertain=true;throw failed;}
 }
 synchronized boolean confirmed(Slot slot){return confirmed[slot.ordinal()];}
 synchronized boolean uncertain(){return uncertain;}
 synchronized String summary(){StringBuilder out=new StringBuilder("slotWriteUncertain="+uncertain);for(Slot slot:Slot.values())out.append(' ').append(slot.name()).append("Attempted=").append(attempted[slot.ordinal()]).append(' ').append(slot.name()).append("Consumed=").append(confirmed[slot.ordinal()]);return out.toString();}
 static boolean waitSatisfied(long received,long now){return received>=0&&now>=received&&now-received>=5000;}
 static boolean sameTaskTemplate(BingNavigationSnapshot.Ready original,BingNavigationSnapshot.Ready next,long now){return original!=null&&next!=null&&next.kind()==BingNavigationSnapshot.Kind.TEMPLATE&&next.fresh(now)&&original.sameTask(next);}
}
