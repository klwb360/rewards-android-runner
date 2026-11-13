package com.aliothmoon.maafw.once;
/** Volatile-lifetime historical scalars only: no grant, frame, raw OCR, disk or replay interface. */
final class SealedReadOnlyReceipt {
 private String historical;
 boolean commit(AppVisibilityFence fence,AppVisibilityFence.Lease lease,boolean hidden,String scalar){
  if(!hidden||scalar==null||scalar.length()>8192)return false;
  return fence.publish(lease,()->{if(historical==null)historical=scalar;});
 }
 String history(){return historical;}
}
