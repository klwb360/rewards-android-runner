package com.aliothmoon.maafw.readingtrust;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.klwb360.rewards.core.RewardsCore.Observation;
/** UNISSUABLE staging capability. No production factory, deserializer or issuer exists.
 * A future NativeReadingAttempt must own actual backend/reader calls before minting.
 * Reflection fixtures are synthetic tests, never evidence of live delivery. */
public final class TrustedReadingSettlementGrant {
 final NormalReadingLedger owner;final NormalReadingLedger.View expected;
 final long boot,receivedTrue,articleReady,returned,verificationStart,firstAt,secondAt;
 final String dispatch;final Observation first,second;
 private final AtomicBoolean used=new AtomicBoolean(),revoked=new AtomicBoolean();
 private TrustedReadingSettlementGrant(NormalReadingLedger owner,NormalReadingLedger.View expected,long boot,
   String dispatch,long receivedTrue,long articleReady,long returned,long verificationStart,
   long firstAt,Observation first,long secondAt,Observation second){
  this.owner=owner;this.expected=expected;this.boot=boot;this.dispatch=dispatch;this.receivedTrue=receivedTrue;
  this.articleReady=articleReady;this.returned=returned;this.verificationStart=verificationStart;
  this.firstAt=firstAt;this.first=first;this.secondAt=secondAt;this.second=second;
 }
 boolean claim(){return used.compareAndSet(false,true);}
 boolean current(){return !revoked.get();}
 @Override public String toString(){return "TrustedReadingSettlementGrant[redacted,unissued]";}
}
