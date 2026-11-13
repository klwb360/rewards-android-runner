package com.aliothmoon.maafw.once;
/** Scalar receipts only. Never an authorization or completion gate. */
final class CleanupEvidence {
 enum State { NOT_INVOKED, WAITING, CONFIRMED, UNKNOWN }
 enum Item { SOURCE_RELEASE, SOURCE_QUIET, RECOGNIZER_RELEASE, RECOGNIZER_QUIET, FRAME_WIPE, PRODUCER_CLOSE, MEDIA_IMAGE_CLOSE, READER_CLOSE, RECOGNIZE_RETURN, POST_STOP_RETURN, TASKER_RUNNING_FALSE, TASKER_DESTROY, RESOURCE_DESTROY, IMAGE_DESTROY, NATIVE_ENCODED_WIPE_CLOSE, NODES_WIPE_CLOSE, ENCODED_WIPE, ROW_WIPE, BITMAP_WIPE_RECYCLE, MODELS_CLOSE, SESSION_OPEN_EXIT, SESSION_PRODUCE_EXIT, SESSION_CLOSE_EXIT, SESSION_APP_QUIET, OCR_DETAIL_CLEAR_DESTROY }
 interface Provider { Snapshot evidenceSnapshot(OnceBackend.Owner owner); }
 record Snapshot(boolean ownerMatched, long waiting, long confirmed, long unknown) {
  State state(Item item) {
   long bit=1L<<item.ordinal();
   if(!ownerMatched || (unknown&bit)!=0) return State.UNKNOWN;
   if((confirmed&bit)!=0) return State.CONFIRMED;
   return (waiting&bit)!=0?State.WAITING:State.NOT_INVOKED;
  }
  Snapshot plus(Snapshot other) {
   if(!ownerMatched || !other.ownerMatched) return unmatched();
   return new Snapshot(true,waiting|other.waiting,confirmed|other.confirmed,unknown|other.unknown);
  }
 }
 static Snapshot unmatched(){return new Snapshot(false,0,0,0);}
 static final class Ledger {
  private OnceBackend.Owner owner;
  private long waiting,confirmed,unknown;
  synchronized void bind(OnceBackend.Owner expected){
   if(expected==null || owner!=null)throw new IllegalStateException();
   owner=expected;
  }
  synchronized void set(Item item,State state){
   long bit=1L<<item.ordinal();waiting&=~bit;confirmed&=~bit;unknown&=~bit;
   switch(state){case WAITING -> waiting|=bit;case CONFIRMED -> confirmed|=bit;case UNKNOWN -> unknown|=bit;default -> {}}
  }
  synchronized void unknownOutstanding(){unknown|=waiting;waiting=0;}
  synchronized Snapshot snapshot(OnceBackend.Owner expected){
   return expected!=null && expected==owner?new Snapshot(true,waiting,confirmed,unknown):unmatched();
  }
 }
}
