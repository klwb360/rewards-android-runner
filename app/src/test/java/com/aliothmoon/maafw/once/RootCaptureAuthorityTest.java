package com.aliothmoon.maafw.once;

import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

/** Non-live contracts; no real Activity, root transport, durable live marker or business input. */
public class RootCaptureAuthorityTest {
    @Test public void opaqueAuthorityHasOnlyPrivateConstructorsAndNoStringPhaseFactories(){
        for(Class<?> type:new Class<?>[]{RootCaptureIntent.class,RootCaptureIntent.PreflightPass.class,
                RootCaptureIntent.Continuation.class,RootCaptureIntent.PhasePermit.class,VisibleCaptureAuthority.RootApproval.class}){
            for(var ctor:type.getDeclaredConstructors())assertTrue(Modifier.isPrivate(ctor.getModifiers()));
            assertFalse(java.io.Serializable.class.isAssignableFrom(type));
            for(var method:type.getDeclaredMethods())for(Class<?> arg:method.getParameterTypes())assertNotEquals(String.class,arg);
        }
    }
    private static RootCaptureIntent fresh(RealCaptureClaim.ProcessEpoch epoch,Object owner,AtomicLong utc){
        return VisibleCaptureAuthority.fixtureRootIntent(new Object(),new Object(),epoch,owner,RootCaptureScope.Mode.PREFLIGHT,1000,utc::get,null);
    }
    @Test public void fixtureIssuerSharesTypedIntentStateButCannotProduceALiveProof(){
        var epoch=new RealCaptureClaim.ProcessEpoch();Object owner=new Object();var utc=new AtomicLong(RootCaptureScope.STARTS_UTC+1000);
        var intent=fresh(epoch,owner,utc);assertFalse(intent.live());assertEquals(RootCaptureScope.Mode.PREFLIGHT,intent.mode());
        assertTrue(intent.current(1000));assertEquals(181000,intent.sessionEnd());
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.issueRoot(intent,RootCaptureScope.Phase.FIRST,epoch,new Object(),1000,51000,null));
    }
    @Test public void phaseDeadlineIsExactAndBoundedWithoutRecomputingTheCallersAbsoluteValue(){
        var epoch=new RealCaptureClaim.ProcessEpoch();Object owner=new Object();var utc=new AtomicLong(RootCaptureScope.STARTS_UTC+1000);
        var intent=fresh(epoch,owner,utc);Object requestOwner=new Object();
        var permit=intent.claimPhase(RootCaptureScope.Phase.FIRST,epoch,requestOwner,1007,50993,null,false);
        assertEquals(50993,permit.deadline);assertTrue(permit.current(epoch,requestOwner,1008));
        assertFalse(permit.current(epoch,new Object(),1008));assertFalse(permit.current(epoch,requestOwner,50993));
        assertThrows(SecurityException.class,()->intent.claimPhase(RootCaptureScope.Phase.FIRST,epoch,requestOwner,1008,50993,null,false));
        var tooLong=fresh(epoch,owner,utc);
        assertThrows(SecurityException.class,()->tooLong.claimPhase(RootCaptureScope.Phase.FIRST,epoch,new Object(),1000,91001,null,false));
    }
    @Test public void wrongEpochAttemptSpendsThatPhaseAndCannotBeRetried(){
        var epoch=new RealCaptureClaim.ProcessEpoch();Object owner=new Object();var utc=new AtomicLong(RootCaptureScope.STARTS_UTC+1000);
        var intent=fresh(epoch,owner,utc);
        assertThrows(SecurityException.class,()->intent.claimPhase(RootCaptureScope.Phase.FIRST,new RealCaptureClaim.ProcessEpoch(),new Object(),1000,51000,null,false));
        assertThrows(SecurityException.class,()->intent.claimPhase(RootCaptureScope.Phase.FIRST,epoch,new Object(),1000,51000,null,false));
    }
    @Test public void preflightCanNeverGrantSecondOrBusinessWithoutPublishedPass(){
        var epoch=new RealCaptureClaim.ProcessEpoch();Object owner=new Object();var utc=new AtomicLong(RootCaptureScope.STARTS_UTC+1000);
        var intent=fresh(epoch,owner,utc);
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureRootGrant(intent,RootCaptureScope.Phase.SECOND,epoch,new Object(),1000,51000,null));
        assertNull(intent.claimOpaqueContinuation(epoch,owner,1000));assertNull(intent.claimPreflightPass(epoch,owner,1000));
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureRootIntent(new Object(),new Object(),epoch,new Object(),RootCaptureScope.Mode.BUSINESS,1000,utc::get,null));
    }
    @Test public void actualClaimPngOwnedFrameAndTypedPublicationAreRequiredBeforePass()throws Exception{
        var f=new RootAuthorityFixture(false);assertNull(f.binding.cleanReceipt());assertNull(f.intent.claimOpaqueContinuation(f.epoch,f.rootOwner,f.now.get()));
        var result=f.binding.backend().start();assertEquals(OnceBackend.Phase.COMPLETED,result.phase());
        assertEquals(OnceBackend.Memory.WIPED,result.memory());assertEquals(1,f.burns.get());assertEquals(1,f.sourceCalls.get());assertEquals(1,f.recognizerCalls.get());
        assertNotNull(f.observation.get());assertTrue(f.observation.get().validNativeShape());
        assertFalse(f.binding.cleanReceipt().liveTransport());var publication=f.publish();assertNotNull(publication);
        assertTrue(f.intent.acceptPublished(publication,f.now.get()));assertFalse(f.intent.current(f.now.get()));
        var pass=f.intent.claimPreflightPass(f.epoch,f.rootOwner,f.now.get());assertNotNull(pass);
        assertTrue(pass.eligible(f.epoch,f.visible,f.request,f.now.get(),false));assertFalse(pass.eligible(f.epoch,f.visible,f.request,f.now.get(),true));
        assertFalse(f.intent.acceptPublished(publication,f.now.get()));assertNull(f.intent.claimPreflightPass(f.epoch,f.rootOwner,f.now.get()));
        assertNull(f.intent.claimOpaqueContinuation(f.epoch,f.rootOwner,f.now.get()));
    }
    @Test public void newBusinessFixtureIntentNeedsExactPassActivityRequestEpochAndRemainsNonlive()throws Exception{
        var f=new RootAuthorityFixture(false);f.binding.backend().start();assertTrue(f.intent.acceptPublished(f.publish(),f.now.get()));
        var pass=f.intent.claimPreflightPass(f.epoch,f.rootOwner,f.now.get());Object businessOwner=new Object();
        var business=VisibleCaptureAuthority.fixtureRootIntent(f.visible,f.request,f.epoch,businessOwner,RootCaptureScope.Mode.BUSINESS,f.now.get(),f.utc::get,pass);
        assertEquals(RootCaptureScope.Mode.BUSINESS,business.mode());assertFalse(business.live());assertTrue(business.current(f.now.get()));
        var grant=VisibleCaptureAuthority.fixtureRootGrant(business,RootCaptureScope.Phase.FIRST,f.epoch,new Object(),f.now.get(),f.now.get()+50_000,null);
        assertFalse(RealCaptureClaim.live(grant));
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureRootIntent(f.visible,f.request,f.epoch,new Object(),RootCaptureScope.Mode.BUSINESS,f.now.get(),f.utc::get,pass));
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureRootGrant(business,RootCaptureScope.Phase.SECOND,f.epoch,new Object(),f.now.get(),f.now.get()+50_000,null));
    }
    @Test public void wrongVisibleOwnerCannotUseAPassAndThatAttemptCannotRecoverIt()throws Exception{
        var f=new RootAuthorityFixture(false);f.binding.backend().start();assertTrue(f.intent.acceptPublished(f.publish(),f.now.get()));
        var pass=f.intent.claimPreflightPass(f.epoch,f.rootOwner,f.now.get());
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureRootIntent(new Object(),f.request,f.epoch,new Object(),RootCaptureScope.Mode.BUSINESS,f.now.get(),f.utc::get,pass));
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureRootIntent(f.visible,f.request,f.epoch,new Object(),RootCaptureScope.Mode.BUSINESS,f.now.get(),f.utc::get,pass));
    }
    @Test public void uncertainClaimBurnProducesNoSourceNoPublicationNoPassAndNoRetry()throws Exception{
        var f=new RootAuthorityFixture(true);var result=f.binding.backend().start();assertNotEquals(OnceBackend.Phase.COMPLETED,result.phase());
        assertEquals(1,f.burns.get());assertEquals(0,f.sourceCalls.get());assertEquals(0,f.recognizerCalls.get());
        assertNull(f.binding.cleanReceipt());assertThrows(SecurityException.class,f::publish);assertFalse(f.intent.acceptPublished(null,f.now.get()));
        assertNull(f.intent.claimPreflightPass(f.epoch,f.rootOwner,f.now.get()));
        assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(f.grant,f.epoch,f.requestOwner,f.now.get(),(id,m)->fail("no burn retry")));
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureRootGrant(f.intent,RootCaptureScope.Phase.FIRST,f.epoch,f.requestOwner,f.now.get(),f.now.get()+50_000,null));
    }
    @Test public void duplicateGrantConsumptionDoesNotRepeatTheRealClaimBoundary()throws Exception{
        var f=new RootAuthorityFixture(false);f.binding.backend().start();
        assertEquals(RealCaptureClaim.Outcome.REJECTED,RealCaptureClaim.consume(f.grant,f.epoch,f.requestOwner,f.now.get(),(id,m)->fail("duplicate burn")));
        assertEquals(1,f.burns.get());assertEquals(1,f.sourceCalls.get());
    }
    @Test public void explicitStopAndOriginalIntentExpiryInvalidateEvenASealedPreflightPass()throws Exception{
        var f=new RootAuthorityFixture(false);f.binding.backend().start();assertTrue(f.intent.acceptPublished(f.publish(),f.now.get()));
        var pass=f.intent.claimPreflightPass(f.epoch,f.rootOwner,f.now.get());assertNotNull(pass);
        f.intent.cancel();assertFalse(pass.eligible(f.epoch,f.visible,f.request,f.now.get(),false));
        var g=new RootAuthorityFixture(false);g.binding.backend().start();assertTrue(g.intent.acceptPublished(g.publish(),g.now.get()));
        var other=g.intent.claimPreflightPass(g.epoch,g.rootOwner,g.now.get());g.now.set(g.intent.sessionEnd());
        assertFalse(other.eligible(g.epoch,g.visible,g.request,g.now.get(),false));
    }
    @Test public void canceledOrExpiredIntentCannotOpenANewPhase(){
        var epoch=new RealCaptureClaim.ProcessEpoch();Object owner=new Object();var utc=new AtomicLong(RootCaptureScope.STARTS_UTC+1000);
        var canceled=fresh(epoch,owner,utc);canceled.cancel();assertFalse(canceled.current(1001));
        assertThrows(SecurityException.class,()->VisibleCaptureAuthority.fixtureRootGrant(canceled,RootCaptureScope.Phase.FIRST,epoch,new Object(),1001,51001,null));
        var expired=fresh(epoch,owner,utc);assertFalse(expired.current(expired.sessionEnd()));utc.set(RootCaptureScope.EXPIRES_UTC);
        assertFalse(expired.current(1001));
    }
}