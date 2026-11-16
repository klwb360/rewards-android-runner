package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.Proxy;
import org.junit.Test;

/** In-memory journal history only. No Android reader, screenshot, OCR or input is executed. */
public class LivePreflightHistoryTest {
    private static final String DAY="2026-11-17",RUN="11111111-1111-1111-1111-111111111111",NEXT="22222222-2222-2222-2222-222222222222";
    private static LiveRewardsTypes.Task priorCheckIn(){return new LiveRewardsTypes.Task(LiveRewardsTypes.Kind.CHECK_IN,
            LiveCardIdentity.hashText("CHECK_IN"),0,LiveCardIdentity.hashText("CHECK_IN"),10,true,false,3);}
    private static LiveRewardsTypes.Task activity(){return new LiveRewardsTypes.Task(LiveRewardsTypes.Kind.DAILY,
            LiveCardIdentity.hashText("synthetic descriptor"),2,LiveCardIdentity.hashText("synthetic title"),10,true,false,-1);}
    @Test public void fixedUnreadableCheckInSeedMatchesThePreviouslyAttemptedNumericDayAcrossRestart()throws Exception{
        for(int phase=0;phase<3;phase++){
            var prior=LiveRewardsLedger.synthetic(DAY,RUN);var old=priorCheckIn();prior.begin(old);
            if(phase>0)prior.burnTap(old);if(phase>1)prior.finish(old,LiveRewardsTypes.Outcome.UNCONFIRMED);
            var resumed=prior.syntheticNextRun(NEXT);var history=new LivePreflightHistory(resumed,DAY);var seed=LivePreflightHistory.checkInSeed();
            assertTrue(seed.sameKey(old));assertEquals("31c1fc87aaf6bfe3631dd8fc0ed0dd39b561840602c8868d03b10bbad610a393",seed.key());
            assertTrue(history.attempted(seed));assertFalse(seed.readable());assertFalse(seed.complete());
            assertEquals(0,seed.ordinal());assertEquals(0,seed.points());assertEquals(-1,seed.day());assertEquals("",seed.titleHash());
            assertTrue(prior.attempted(old));assertTrue(resumed.attempted(seed));
        }
    }
    @Test public void priorActivityAttemptIsUnreadableHistoryEvidenceAndNeverInventedCompletion()throws Exception{
        var prior=LiveRewardsLedger.synthetic(DAY,RUN);var task=activity();prior.begin(task);prior.burnTap(task);
        var resumed=prior.syntheticNextRun(NEXT);var history=new LivePreflightHistory(resumed,DAY);
        var current=new LiveRewardsTypes.Task(task.kind(),task.descriptorHash(),1,task.titleHash(),10,true,true,-1);
        assertTrue(history.attempted(current));var seed=LivePreflightHistory.unreadable(current);
        assertTrue(task.sameKey(seed));assertEquals(1,seed.ordinal());assertEquals(10,seed.points());
        assertFalse(seed.readable());assertFalse(seed.complete());assertEquals("",seed.titleHash());
        var otherValue=new LiveRewardsTypes.Task(task.kind(),task.descriptorHash(),1,"",5,false,false,-1);
        assertFalse(history.attempted(otherValue));assertTrue(prior.attempted(task));
    }
    @Test public void noHistoryDoesNotCreateAnAttemptAndJournalDayMustMatch()throws Exception{
        var ledger=LiveRewardsLedger.synthetic(DAY,RUN);var history=new LivePreflightHistory(ledger,DAY);
        assertFalse(history.attempted(LivePreflightHistory.checkInSeed()));assertFalse(history.attempted(activity()));
        assertFalse(ledger.attempted(activity()));
        assertThrows(IllegalArgumentException.class,()->new LivePreflightHistory(ledger,"2026-11-18"));
    }
    @Test public void uncertainJournalQueryPropagatesWithoutAssumingTheTaskIsNew()throws Exception{
        Class<?> store=Class.forName(LiveRewardsLedger.class.getName()+"$Store");
        Object failing=Proxy.newProxyInstance(store.getClassLoader(),new Class<?>[]{store},(proxy,method,args)->{
            if(method.getName().equals("exists"))throw new IOException("SYNTHETIC_QUERY_FAILURE");
            if(method.getName().equals("create"))return null;
            throw new AssertionError("Unexpected synthetic store operation");
        });
        var constructor=LiveRewardsLedger.class.getDeclaredConstructor(store,String.class,String.class,boolean.class);constructor.setAccessible(true);
        var ledger=(LiveRewardsLedger)constructor.newInstance(failing,DAY,RUN,false);var history=new LivePreflightHistory(ledger,DAY);
        assertThrows(IOException.class,()->history.attempted(LivePreflightHistory.checkInSeed()));assertFalse(ledger.production());
    }
}
