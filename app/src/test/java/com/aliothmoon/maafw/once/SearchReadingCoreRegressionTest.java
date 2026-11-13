package com.aliothmoon.maafw.once;

import io.github.klwb360.rewards.core.RewardsCore;
import io.github.klwb360.rewards.core.RewardsCore.*;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic counter/state regression only. No persistence, native authority or device action. */
public class SearchReadingCoreRegressionTest {
 private static final long UTC=Instant.parse("2026-11-12T06:00:00Z").toEpochMilli();
 private static final Limits LIMITS=Limits.defaults();
 private Observation observed(Task task,int n,int d) {
  var binding=new FrameStructureEvidence.Binding(new Object(),new Object(),new Object(),new Object());
  String heading=task==Task.SEARCH?"Search to earn":"Read to earn";
  var rows=List.of(new FrameStructureEvidence.Line(heading+" "+n+"/"+d,20,20,280,30,.99));
  var r=FrameStructureEvidence.parseForTask(binding,binding,rows,400,300,task);
  if(!r.supported())return new Observation(ParseStatus.INVALID,null,"fixture rejected");
  return new Observation(ParseStatus.OK,new Counter(r.task(),r.current(),r.target(),false),"synthetic counter only");
 }
 private Step reserve(State state,Task task,int n,int d,long now,String term) {
  return RewardsCore.prepare(state,task,observed(task,n,d),term,UTC,now,false,LIMITS);
 }
 @Test public void fullCountersSkipWithoutReservingOrChangingState() {
  for(Task t:new Task[]{Task.SEARCH,Task.READING}){
   State s=RewardsCore.fresh(UTC,1000);
   Step r=reserve(s,t,17,17,1001,"synthetic example");
   assertEquals(Decision.COMPLETE,r.decision());assertSame(s,r.state());assertEquals("",r.state().pendingTask());
   assertEquals(0,r.state().searchAttempts());assertEquals(0,r.state().readingAttempts());
  }
 }
 @Test public void incompleteNeedsPersistedReservationAndCannotReplay() {
  State s=RewardsCore.fresh(UTC,1000);Step first=reserve(s,Task.SEARCH,1,17,1001,"Example One");
  assertEquals(Decision.ALLOW,first.decision());assertEquals("SEARCH",first.state().pendingTask());
  Step second=reserve(first.state(),Task.SEARCH,1,17,1002,"Different example");
  assertEquals(Decision.PENDING,second.decision());assertSame(first.state(),second.state());
 }
 @Test public void completedVerificationIsBasedOnObservedTargetNotAssumedIncrement() {
  State s=reserve(RewardsCore.fresh(UTC,1000),Task.SEARCH,1,17,1001,"example").state();
  Step partial=RewardsCore.verify(s,Task.SEARCH,observed(Task.SEARCH,2,17),UTC,1002,false,LIMITS);
  assertEquals(Decision.ALLOW,partial.decision());assertEquals("",partial.state().pendingTask());
  State next=reserve(partial.state(),Task.SEARCH,2,17,1003,"another example").state();
  assertEquals(Decision.COMPLETE,RewardsCore.verify(next,Task.SEARCH,observed(Task.SEARCH,17,17),UTC,1004,false,LIMITS).decision());
 }
 @Test public void changedRegressedAndWrongTaskCountersRetainPending() {
  State s=reserve(RewardsCore.fresh(UTC,1000),Task.READING,4,17,1001,"").state();
  for(Observation bad:List.of(observed(Task.READING,3,17),observed(Task.READING,5,18),observed(Task.SEARCH,5,17),new Observation(ParseStatus.MISSING,null,"unknown"))){
   Step r=RewardsCore.verify(s,Task.READING,bad,UTC,1002,false,LIMITS);
   assertEquals(Decision.UNREADABLE,r.decision());assertSame(s,r.state());
  }
 }
 @Test public void cancelledVerificationRetainsPending() {
  State s=reserve(RewardsCore.fresh(UTC,1000),Task.READING,0,17,1001,"").state();
  Step r=RewardsCore.verify(s,Task.READING,observed(Task.READING,17,17),UTC,1002,true,LIMITS);
  assertEquals(Decision.CANCELLED,r.decision());assertSame(s,r.state());
 }
 @Test public void repeatedSearchQueryRemainsExcludedAfterVerification() {
  State s=reserve(RewardsCore.fresh(UTC,1000),Task.SEARCH,0,17,1001,"  Example One ").state();
  s=RewardsCore.verify(s,Task.SEARCH,observed(Task.SEARCH,1,17),UTC,1002,false,LIMITS).state();
  Step r=reserve(s,Task.SEARCH,1,17,1003,"example one");
  assertEquals(Decision.DUPLICATE,r.decision());assertSame(s,r.state());
 }
 @Test public void twoUncreditedSearchesStop() {
  State s=RewardsCore.fresh(UTC,1000);
  for(int i=0;i<2;i++){
   s=reserve(s,Task.SEARCH,0,17,1001+i*2,"synthetic "+i).state();
   Step v=RewardsCore.verify(s,Task.SEARCH,observed(Task.SEARCH,0,17),UTC,1002+i*2,false,LIMITS);
   assertEquals(i==1?Decision.SEARCH_NO_CREDIT:Decision.ALLOW,v.decision());s=v.state();
  }
  assertEquals(Decision.SEARCH_NO_CREDIT,reserve(s,Task.SEARCH,0,17,1010,"new synthetic").decision());
 }
 @Test public void fourUncreditedReadsStop() {
  State s=RewardsCore.fresh(UTC,1000);
  for(int i=0;i<4;i++){
   s=reserve(s,Task.READING,0,17,1001+i*2,"").state();
   Step v=RewardsCore.verify(s,Task.READING,observed(Task.READING,0,17),UTC,1002+i*2,false,LIMITS);
   assertEquals(i==3?Decision.READING_NO_CREDIT:Decision.ALLOW,v.decision());s=v.state();
  }
  assertEquals(Decision.READING_NO_CREDIT,reserve(s,Task.READING,0,17,1010,"").decision());
 }
 @Test public void unknownCounterNeverReservesAndFullOtherRoleCannotComplete() {
  State s=RewardsCore.fresh(UTC,1000);
  for(Observation bad:List.of(observed(Task.READING,30,30),observed(Task.SEARCH,1,0),new Observation(ParseStatus.MISSING,null,"unknown"))){
   Step r=RewardsCore.prepare(s,Task.SEARCH,bad,"example",UTC,1001,false,LIMITS);
   assertEquals(Decision.UNREADABLE,r.decision());assertSame(s,r.state());
  }
 }
 @Test public void unchangedRecoveryDoesNotClearAnUnreviewedSearch() {
  State s=reserve(RewardsCore.fresh(UTC,1000),Task.SEARCH,0,17,1001,"example").state();
  Step r=RewardsCore.recoverPendingSearch(s,observed(Task.SEARCH,0,17),UTC,50000,1100,LIMITS);
  assertEquals(Decision.PENDING,r.decision());assertSame(s,r.state());
 }
}
