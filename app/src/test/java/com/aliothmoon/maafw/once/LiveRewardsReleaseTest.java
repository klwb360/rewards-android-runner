package com.aliothmoon.maafw.once;

import static org.junit.Assert.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;

/** Release evidence is separate from cancellation and from a terminal report. */
public class LiveRewardsReleaseTest {
    private static final class RootFixture implements AutoCloseable {
        final Object graph=new Object();
        final AtomicBoolean closed=new AtomicBoolean();
        final AtomicInteger checks=new AtomicInteger(),failAt=new AtomicInteger();
        final ArrayList<Runnable> queue=new ArrayList<>();
        final CaptureSessionRegistry registry;
        final RealCaptureClaim.ProcessEpoch epoch;
        final CaptureSessionRuntime.AlignmentRoot root;
        RootFixture()throws Exception{
            registry=(CaptureSessionRegistry)field("ROOT");epoch=(RealCaptureClaim.ProcessEpoch)field("EPOCH");
            assertFalse(registry.retaining());assertFalse(epoch.retaining());assertTrue(epoch.retain(graph));
            BooleanSupplier predicate=()->{
                if(checks.incrementAndGet()==failAt.get())throw new IllegalStateException("FIXTURE_RELEASE_FAILURE");
                return closed.get();
            };
            var entry=registry.retain(graph,epoch,1,()->{},()->{},predicate,action->{queue.add(action);return true;});
            var constructor=CaptureSessionRuntime.AlignmentRoot.class.getDeclaredConstructor(
                    Object.class,CaptureSessionRegistry.Entry.class,BooleanSupplier.class);
            constructor.setAccessible(true);root=constructor.newInstance(graph,entry,predicate);
        }
        private Object field(String name)throws Exception{
            var value=CaptureSessionRuntime.class.getDeclaredField(name);value.setAccessible(true);return value.get(null);
        }
        public void close(){closed.set(true);failAt.set(0);root.releaseIfClosed();}
    }
    @Test public void cancellationAndClosedFalseCannotProduceReleaseEvidence()throws Exception{
        try(var f=new RootFixture()){
            assertTrue(f.root.current());assertFalse(f.root.released());
            f.root.request();assertFalse(f.root.current());assertFalse(f.root.released());
            f.root.releaseIfClosed();assertFalse(f.root.released());assertTrue(f.registry.retaining());assertTrue(f.epoch.retaining());
            f.closed.set(true);f.root.releaseIfClosed();
            assertTrue(f.root.released());assertFalse(f.registry.retaining());assertFalse(f.epoch.retaining());
        }
    }
    @Test public void failedRegistryReleaseDoesNotClearHeldOrReleaseEpoch()throws Exception{
        try(var f=new RootFixture()){
            f.closed.set(true);f.failAt.set(2);
            assertThrows(IllegalStateException.class,f.root::releaseIfClosed);
            assertFalse(f.root.released());assertTrue(f.registry.retaining());assertTrue(f.epoch.retaining());
        }
    }
    @Test public void releaseWriterNeverReplacesAnExistingReceipt()throws Exception{
        Path directory=Files.createTempDirectory("live-rewards-release-test-");Path receipt=directory.resolve("RELEASE.json");
        byte[] original="PRESERVED_EVIDENCE".getBytes(StandardCharsets.UTF_8);
        try{
            Files.write(receipt,original,StandardOpenOption.CREATE_NEW);
            boolean directoryIdentityKnown=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()!=null;
            var write=LiveRewardsRuntime.class.getDeclaredMethod("writeFile",Path.class,String.class,byte[].class);write.setAccessible(true);
            var error=assertThrows(InvocationTargetException.class,()->write.invoke(null,directory,"RELEASE.json",new byte[]{1}));
            if(directoryIdentityKnown)assertTrue(error.getCause() instanceof FileAlreadyExistsException);
            else assertTrue(error.getCause() instanceof SecurityException);
            assertArrayEquals(original,Files.readAllBytes(receipt));
        }finally{Files.deleteIfExists(receipt);Files.deleteIfExists(directory);}
    }
}
