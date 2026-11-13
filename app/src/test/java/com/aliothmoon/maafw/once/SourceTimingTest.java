package com.aliothmoon.maafw.once;
import org.junit.Test;import static org.junit.Assert.*;
public class SourceTimingTest {
 static final class Source implements BackendPorts.FrameSource {
  boolean cancelled,unreleased;OnceBackend.Owner owner;Runnable cancel=()->{};
  public void produce(OnceBackend.Owner o,BackendPorts.FrameSink sink,BackendPorts.StopSignal stop)throws Exception{owner=o;sink.begin(1,1);sink.append(new byte[]{1},0,1);if(cancelled){cancel.run();throw new BackendPorts.Cancelled();}}
  public void release(OnceBackend.Owner o){}
  public BackendPorts.Completion completion(OnceBackend.Owner o){return new BackendPorts.Completion(o,unreleased?BackendPorts.Released.UNKNOWN:BackendPorts.Released.CONFIRMED,unreleased?BackendPorts.Quiet.UNKNOWN:BackendPorts.Quiet.CONFIRMED);}
  public BackendPorts.SourceTiming sourceTiming(OnceBackend.Owner o){return new BackendPorts.SourceTiming(1,1,2,3,4);}
 }
 static final class Reco implements BackendPorts.Recognizer {
  public BackendPorts.Recognition recognize(OnceBackend.Owner o,BackendPorts.FrameView f,BackendPorts.StopSignal stop){return new BackendPorts.Recognition(o,false);}
  public void release(OnceBackend.Owner o){}
  public BackendPorts.Completion completion(OnceBackend.Owner o){return new BackendPorts.Completion(o,BackendPorts.Released.CONFIRMED,BackendPorts.Quiet.CONFIRMED);}
 }
 static OnceBackend backend(Source s){return new OnceBackend(o->new BackendPorts.ClaimReceipt(o,BackendPorts.ClaimDecision.CLAIMED),s,new Reco(),Runnable::run);}
 @Test public void exactOwnerAndCleanupRequired(){Source s=new Source();OnceBackend b=backend(s);assertNull(b.sourceTiming(null));var r=b.start();assertNotNull(b.sourceTiming(r.owner()));var other=backend(new Source()).start().owner();assertNull(b.sourceTiming(other));assertTrue(b.sourceTiming(r.owner()).summary().contains("compositorPixelAck=NOT_OBSERVED"));}
 @Test public void unconfirmedReleaseSuppressesTiming(){Source s=new Source();s.unreleased=true;OnceBackend b=backend(s);var r=b.start();assertNull(b.sourceTiming(r.owner()));}
 @Test public void cancellationSuppressesTiming(){Source s=new Source();s.cancelled=true;OnceBackend b=backend(s);s.cancel=()->b.cancel(b.snapshot().owner());var r=b.start();assertNull(b.sourceTiming(r.owner()));}
}
