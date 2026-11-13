package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
public class AutonomousRewardCardScopeTest {
 @Test public void onlyFourExplicitSlots(){for(String s:new String[]{"BEFORE","AFTER","TAP","RETURN"})assertTrue(AutonomousRewardCardScope.slotAllowed(s));for(String s:new String[]{"FIRST","SECOND","SEARCH","ANSWER","SIGNIN","",null})assertFalse(AutonomousRewardCardScope.slotAllowed(s));}
 @Test public void absoluteWindowIsHalfOpen(){long s=AutonomousRewardCardScope.STARTS_UTC,e=AutonomousRewardCardScope.EXPIRES_UTC;assertFalse(AutonomousRewardCardScope.current(s-1));assertTrue(AutonomousRewardCardScope.current(s));assertTrue(AutonomousRewardCardScope.current(e-1));assertFalse(AutonomousRewardCardScope.current(e));assertFalse(AutonomousRewardCardScope.admissionCurrent(e-90000));assertTrue(AutonomousRewardCardScope.admissionCurrent(e-90001));}
 @Test public void eachPhaseCannotExtendSession(){long u=AutonomousRewardCardScope.STARTS_UTC;assertEquals(100000,AutonomousRewardCardScope.deadline(10000,u,200000));assertEquals(12000,AutonomousRewardCardScope.deadline(10000,u,12000));}
 @Test public void expiredAuthorityFailsClosed(){long u=AutonomousRewardCardScope.STARTS_UTC;assertThrows(SecurityException.class,()->AutonomousRewardCardScope.deadline(10000,u,10000));assertThrows(SecurityException.class,()->AutonomousRewardCardScope.deadline(-1,u,10000));assertThrows(SecurityException.class,()->AutonomousRewardCardScope.deadline(10000,AutonomousRewardCardScope.EXPIRES_UTC,20000));}
 @Test public void namespaceCannotReusePriorScopes(){assertEquals("rewards-root-family-business-20261111-v27",AutonomousRewardCardScope.DIRECTORY);assertNotEquals(AutonomousRewardCardScope.V24_DIRECTORY,AutonomousRewardCardScope.DIRECTORY);assertEquals(180000,AutonomousRewardCardScope.SESSION_TTL);assertEquals(90000,AutonomousRewardCardScope.CAPTURE_TTL);}
}
