package com.aliothmoon.maafw.trial;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import static com.aliothmoon.maafw.trial.ReadingReservationGuard.History;

/**
 * Unwired file journal. Supports verified initialization and append-and-bind only; it deliberately
 * cannot clear a pending article or reset a day. Android durability and integration are untested.
 */
public final class ReadingJournalFileStore implements ReadingReservationGuard.HistoryStore {
    private static final int MAGIC=0x4D524A31,VERSION=1,MAX_BYTES=8192;
    private final Path dir,state,marker,next,backup,lock;
    private final AtomicTrialStore.BootId boot;
    private final AtomicTrialStore.DirectorySync sync;

    public ReadingJournalFileStore(Path directory,AtomicTrialStore.BootId boot,AtomicTrialStore.DirectorySync sync) throws IOException {
        dir=Objects.requireNonNull(directory).toAbsolutePath().normalize();this.boot=Objects.requireNonNull(boot);this.sync=Objects.requireNonNull(sync);
        if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir))throw new IOException("private journal directory unavailable");
        state=dir.resolve("reading-journal.bin");marker=dir.resolve("reading-journal.intent");next=dir.resolve("reading-journal.new");
        backup=dir.resolve("reading-journal.bak");lock=dir.resolve("reading-journal.lock");
    }
    @Override public History read() throws IOException{return locked(()->readUnlocked(validBoot()));}
    @Override public boolean compareAndSet(History expected,History replacement) throws IOException {
        Objects.requireNonNull(replacement);validateTransition(expected,replacement);
        return locked(()->{
            long currentBoot=validBoot();History actual=readUnlocked(currentBoot);
            if(!Objects.equals(expected,actual))return false;
            byte[] bytes=encode(replacement,currentBoot);
            createAndSync(marker,new byte[]{'M','R','J','1'});sync.sync(dir);
            createAndSync(next,bytes);Files.move(next,state,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            sync.sync(dir);Files.delete(marker);sync.sync(dir);
            if(validBoot()!=currentBoot || !replacement.equals(readUnlocked(currentBoot)))throw new IOException("journal readback or boot check failed");
            return true;
        });
    }
    private static void validateTransition(History old,History replacement) throws IOException {
        if(old==null){if(!replacement.pendingState().isEmpty())throw new IOException("cannot initialize with a pending article");return;}
        if(!old.pendingState().isEmpty())throw new IOException("pending article requires separate review");
        if(!old.day().equals(replacement.day()) || replacement.pendingState().isEmpty()
                || replacement.hashes().size()!=old.hashes().size()+1 || !replacement.hashes().containsAll(old.hashes())
                || old.hashes().contains(replacement.pendingArticle()))throw new IOException("invalid journal transition");
    }
    private interface IoCall<T>{T run() throws IOException;}
    private <T>T locked(IoCall<T> action) throws IOException {
        try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS))throw new IOException("invalid journal lock");
            try(FileLock ignored=channel.lock()){return action.run();}
        }catch(OverlappingFileLockException | UnsupportedOperationException failure){throw new IOException("journal lock unavailable",failure);}
    }
    private long validBoot() throws IOException{long value=boot.current();if(value<0)throw new IOException("boot identity unavailable");return value;}
    private History readUnlocked(long currentBoot) throws IOException {
        absent(marker);absent(next);absent(backup);
        if(Files.notExists(state,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(state,LinkOption.NOFOLLOW_LINKS))throw new IOException("invalid journal file");
        byte[] bytes;
        try(FileChannel input=FileChannel.open(state,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long size=input.size();if(size<1 || size>MAX_BYTES)throw new IOException("invalid journal length");
            ByteBuffer buffer=ByteBuffer.allocate((int)size);
            while(buffer.hasRemaining()){int read=input.read(buffer);if(read<0)throw new EOFException("short journal");if(read==0)throw new IOException("journal read made no progress");}
            if(input.size()!=size)throw new IOException("journal changed during read");bytes=buffer.array();
        }
        return decode(bytes,currentBoot);
    }
    private static void absent(Path path) throws IOException{if(!Files.notExists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("journal recovery artifact present or inaccessible");}
    private static void createAndSync(Path path,byte[] bytes) throws IOException {
        try(FileChannel output=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())if(output.write(buffer)==0)throw new IOException("journal write made no progress");output.force(true);
        }
    }
    static byte[] encode(History value,long currentBoot) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(currentBoot);out.writeUTF(value.day());
            List<String> hashes=new ArrayList<>(value.hashes());hashes.sort(String::compareTo);out.writeInt(hashes.size());
            for(String hash:hashes)out.writeUTF(hash);out.writeUTF(value.pendingState());out.writeUTF(value.pendingArticle());
        }
        byte[] body=bytes.toByteArray(),checksum=checksum(body);if(body.length+checksum.length>MAX_BYTES)throw new IOException("journal too large");
        byte[] result=Arrays.copyOf(body,body.length+checksum.length);System.arraycopy(checksum,0,result,body.length,checksum.length);return result;
    }
    static History decode(byte[] bytes,long currentBoot) throws IOException {
        if(bytes.length<=32)throw new IOException("short journal checksum");byte[] body=Arrays.copyOf(bytes,bytes.length-32);
        if(!MessageDigest.isEqual(checksum(body),Arrays.copyOfRange(bytes,bytes.length-32,bytes.length)))throw new IOException("journal checksum mismatch");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(body))){
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION || in.readLong()!=currentBoot)throw new IOException("journal schema or boot identity changed");
            String day=in.readUTF();int count=in.readInt();if(count<0 || count>10)throw new IOException("invalid article count");
            Set<String> hashes=new HashSet<>();for(int i=0;i<count;i++)if(!hashes.add(in.readUTF()))throw new IOException("duplicate article hash");
            History value=new History(day,hashes,in.readUTF(),in.readUTF());if(in.read()!=-1)throw new IOException("trailing journal data");return value;
        }catch(RuntimeException failure){throw new IOException("invalid journal contents",failure);}
    }
    private static byte[] checksum(byte[] bytes) throws IOException {
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException failure){throw new IOException("SHA-256 unavailable",failure);}
    }
}
