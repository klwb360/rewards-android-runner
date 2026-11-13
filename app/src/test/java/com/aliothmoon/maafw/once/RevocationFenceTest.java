package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
public final class RevocationFenceTest {
    static final class Source implements BackendPorts.FrameSource {
        final BackendPorts.RevocationFence fence=new BackendPorts.RevocationFence();
        OnceBackend.Owner owner;String point;Source(String p){point=p;}
        public void produce(OnceBackend.Owner o,BackendPorts.FrameSink sink,BackendPorts.StopSignal stop){owner=o;sink.begin(1,1);sink.append(new byte[]{7},0,1);if(point.equals("source"))fence.revoke();}
        public BackendPorts.RevocationFence revocationFence(OnceBackend.Owner o){if(o!=owner)throw new SecurityException();return fence;}
        public void release(OnceBackend.Owner o){if(point.equals("cleanup"))fence.revoke();}
        public BackendPorts.Completion completion(OnceBackend.Owner o){if(point.equals("evidence"))fence.revoke();return new BackendPorts.Completion(o,BackendPorts.Released.CONFIRMED,BackendPorts.Quiet.CONFIRMED);}
    }
    static final class Reco implements BackendPorts.Recognizer {
        Source source;int calls;Reco(Source s){source=s;}
        public BackendPorts.Recognition recognize(OnceBackend.Owner o,BackendPorts.FrameView frame,BackendPorts.StopSignal stop)throws Exception{
            calls++;if(source.point.equals("ocr")){source.fence.revoke();assertTrue(stop.requested());throw new BackendPorts.Cancelled();}
            if(source.point.equals("result"))source.fence.revoke();return new BackendPorts.Recognition(o,true);
        }
        public void release(OnceBackend.Owner o){}
        public BackendPorts.Completion completion(OnceBackend.Owner o){return new BackendPorts.Completion(o,BackendPorts.Released.CONFIRMED,BackendPorts.Quiet.CONFIRMED);}
    }
    static OnceBackend run(Source s,Reco r){OnceBackend b=new OnceBackend(o->new BackendPorts.ClaimReceipt(o,BackendPorts.ClaimDecision.CLAIMED),s,r,Runnable::run);b.start();return b;}
    @Test public void allObservedPrecommitRevocationsSuppressHit(){for(String p:new String[]{"source","ocr","result","cleanup","evidence"}){Source s=new Source(p);Reco r=new Reco(s);OnceBackend b=run(s,r);assertEquals(p,OnceBackend.Phase.CANCELLED,b.snapshot().phase());assertFalse(b.snapshot().hit());assertEquals(OnceBackend.Memory.WIPED,b.snapshot().memory());if(p.equals("source"))assertEquals(0,r.calls);}}
    @Test public void postcommitIsSealedAndOldFenceCannotPoisonNewOwner(){Source old=new Source("");OnceBackend b=run(old,new Reco(old));OnceBackend.Result sealed=b.snapshot();assertTrue(sealed.hit());old.fence.revoke();assertTrue(old.fence.observed());assertFalse(old.fence.revoked());assertEquals(sealed,b.snapshot());Source fresh=new Source("");assertTrue(run(fresh,new Reco(fresh)).snapshot().hit());}
    @Test public void fenceHasNoResourceGraph(){for(var f:BackendPorts.RevocationFence.class.getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(f.getModifiers()))assertEquals(java.util.concurrent.atomic.AtomicInteger.class,f.getType());}
    @Test public void commitRaceIsOneLinearOrder()throws Exception{for(int i=0;i<200;i++){BackendPorts.RevocationFence f=new BackendPorts.RevocationFence();Thread t=new Thread(f::revoke);t.start();boolean accepted=f.tryCommit();t.join();assertTrue(f.observed());assertEquals(!accepted,f.revoked());assertEquals(accepted,f.tryCommit());}}
}
