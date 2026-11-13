package com.aliothmoon.maafw.trial;
import java.io.IOException;
import io.github.klwb360.rewards.core.RewardsCore.State;
/** Pure codecs only. Does not create stores, grants, directories, migration or permission. */
public final class ReadingCodecAccess {
 private ReadingCodecAccess(){} public static String fingerprint(State s){return ReadingReservationGuard.fingerprint(s);}
 public static byte[] state(State s,long boot)throws IOException{return AtomicTrialStore.encode(s,boot);}
 public static State state(byte[] b,long boot)throws IOException{return AtomicTrialStore.decode(b,boot);}
 public static byte[] history(ReadingReservationGuard.History h,long boot)throws IOException{return ReadingJournalFileStore.encode(h,boot);}
 public static ReadingReservationGuard.History history(byte[] b,long boot)throws IOException{return ReadingJournalFileStore.decode(b,boot);}
}
