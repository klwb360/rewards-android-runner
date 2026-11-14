package com.aliothmoon.maafw.once;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.LongSupplier;

/** Own-UID local inventory primitive. No schema parsing, file creation or raw path diagnostics. */
final class OwnStateReadOnlyFiles {
    static final int MAX_BYTES = 64 * 1024;
    enum Status { ABSENT_LOCAL, PRESENT_NOT_INSPECTED, DIRECTORY_NAMES, REGULAR_BYTES, UNSAFE_PATH, OVERSIZE, CHANGED, IO_UNKNOWN, EXPIRED }
    static final class Result {
        private final Status status;
        private final byte[] bytes;
        private final int reservedBytes;
        private Result(Status status, byte[] bytes) { this(status,bytes,0); }
        private Result(Status status,byte[] bytes,int reservedBytes) { this.status=status;this.bytes=bytes;this.reservedBytes=reservedBytes; }
        /** Conservative bytes reserved before open, never refunded on failure; not actual bytes read. */
        int reservedBytes(){return reservedBytes;}
        Status status() { return status; }
        /** Device-internal only; caller must not serialize source bytes. */
        byte[] bytes() { return bytes == null ? null : bytes.clone(); }
        String summary() { return "ownStateFile="+status; }
        void clear() { if(bytes!=null)Arrays.fill(bytes,(byte)0); }
    }
    private record Stamp(Path path,Object key,long size,java.nio.file.attribute.FileTime modified,
                         java.nio.file.attribute.FileTime created,boolean directory) { }
    interface Attributes { BasicFileAttributes read(Path path)throws IOException; }
    private static final Attributes NATIVE = path -> Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
    /** Metadata only: never opens the target or interprets a lock marker as a live owner. */
    static Result inspect(Path trustedRoot,String fixedRelative,long deadline,LongSupplier clock) {
        try {
            live(deadline,clock);Path target=target(trustedRoot,fixedRelative);
            List<Stamp> before=snapshot(trustedRoot,target,deadline,clock,NATIVE);
            if(!before.equals(snapshot(trustedRoot,target,deadline,clock,NATIVE)))return new Result(Status.CHANGED,null);
            live(deadline,clock);return new Result(Status.PRESENT_NOT_INSPECTED,null);
        } catch(Stop stopped){return new Result(stopped.status,null);}
          catch(InvalidPathException|SecurityException invalid){return new Result(Status.UNSAFE_PATH,null);}
          catch(IOException|RuntimeException failure){return new Result(Status.IO_UNKNOWN,null);}
    }
    record ListResult(Status status,List<String> names) {
        ListResult { names=List.copyOf(names); }
        String summary(){return "ownStateDirectory="+status;}
    }
    /** Direct children only, device-internal names. Caller owns any smaller/global shared budget. */
    static ListResult list(Path trustedRoot,String fixedRelative,long deadline,LongSupplier clock) {
        return list(trustedRoot,fixedRelative,256,deadline,clock);
    }
    static ListResult list(Path trustedRoot,String fixedRelative,int maxChildren,long deadline,LongSupplier clock) {
        return listForTest(trustedRoot,fixedRelative,maxChildren,deadline,clock,NATIVE,()->{});
    }
    static ListResult listForTest(Path trustedRoot,String fixedRelative,int maxChildren,long deadline,LongSupplier clock,Attributes attributes,Runnable afterList) {
        List<String> names=new ArrayList<>();
        try {
            live(deadline,clock);
            if(maxChildren<0||maxChildren>256||attributes==null||afterList==null)throw new Stop(Status.UNSAFE_PATH);
            Path directory;
            if("".equals(fixedRelative)) {
                if(trustedRoot==null||!trustedRoot.isAbsolute()||!trustedRoot.equals(trustedRoot.normalize()))throw new Stop(Status.UNSAFE_PATH);
                directory=trustedRoot;
            } else directory=target(trustedRoot,fixedRelative);
            List<Stamp> before=snapshot(trustedRoot,directory,deadline,clock,attributes,true);
            try(DirectoryStream<Path> children=Files.newDirectoryStream(directory)) {
                for(Path child:children){live(deadline,clock);if(names.size()>=maxChildren)throw new Stop(Status.OVERSIZE);names.add(child.getFileName().toString());}
            }
            afterList.run();
            List<Stamp> after;
            try { after=snapshot(trustedRoot,directory,deadline,clock,attributes,true); }
            catch(Stop changed){if(changed.status==Status.EXPIRED)throw changed;throw new Stop(Status.CHANGED);}
            if(!before.equals(after))throw new Stop(Status.CHANGED);
            live(deadline,clock);return new ListResult(Status.DIRECTORY_NAMES,names);
        } catch(Stop stopped){return new ListResult(stopped.status,List.of());}
          catch(InvalidPathException|SecurityException invalid){return new ListResult(Status.UNSAFE_PATH,List.of());}
          catch(IOException|RuntimeException failure){return new ListResult(Status.IO_UNKNOWN,List.of());}
    }
    private static Path target(Path trustedRoot,String fixedRelative)throws Stop {
        if(trustedRoot==null||!trustedRoot.isAbsolute()||!trustedRoot.equals(trustedRoot.normalize())||fixedRelative==null||fixedRelative.isEmpty())throw new Stop(Status.UNSAFE_PATH);
        Path relative=Paths.get(fixedRelative);
        if(relative.isAbsolute()||relative.getRoot()!=null||relative.getNameCount()==0)throw new Stop(Status.UNSAFE_PATH);
        for(Path part:relative)if(part.toString().equals("..")||part.toString().equals(".")||part.toString().isEmpty())throw new Stop(Status.UNSAFE_PATH);
        Path target=trustedRoot.resolve(relative).normalize();
        if(!target.startsWith(trustedRoot)||target.equals(trustedRoot))throw new Stop(Status.UNSAFE_PATH);
        return target;
    }
    static Result read(Path trustedRoot,String fixedRelative,long deadline,LongSupplier clock) {
        return read(trustedRoot,fixedRelative,MAX_BYTES,deadline,clock);
    }
    static Result read(Path trustedRoot,String fixedRelative,int remainingBytes,long deadline,LongSupplier clock) {
        return readForTest(trustedRoot,fixedRelative,deadline,clock,()->{},NATIVE,remainingBytes);
    }
    /** Package-local deterministic race seam; production entrypoint always supplies a no-op. */
    static Result readForTest(Path trustedRoot,String fixedRelative,long deadline,LongSupplier clock,Runnable afterRead) {
        return readForTest(trustedRoot,fixedRelative,deadline,clock,afterRead,NATIVE);
    }
    static Result readForTest(Path trustedRoot,String fixedRelative,long deadline,LongSupplier clock,Runnable afterRead,Attributes attributes) {
        return readForTest(trustedRoot,fixedRelative,deadline,clock,afterRead,attributes,MAX_BYTES);
    }
    static Result readForTest(Path trustedRoot,String fixedRelative,long deadline,LongSupplier clock,Runnable afterRead,Attributes attributes,int remainingBytes) {
        byte[] bytes=null; int reservedBytes=0;
        try {
            live(deadline,clock);
            Path target=target(trustedRoot,fixedRelative);
            if(afterRead==null||attributes==null)throw new Stop(Status.UNSAFE_PATH);
            List<Stamp> before=snapshot(trustedRoot,target,deadline,clock,attributes);
            Stamp file=before.get(before.size()-1);
            if(remainingBytes<0||file.size>MAX_BYTES||file.size>remainingBytes)throw new Stop(Status.OVERSIZE);
            bytes=new byte[(int)file.size];
            live(deadline,clock);
            reservedBytes=(int)file.size;
            try(SeekableByteChannel channel=Files.newByteChannel(target,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
                if(channel.size()!=file.size)throw new Stop(Status.CHANGED);
                ByteBuffer buffer=ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()) {
                    live(deadline,clock);
                    int n=channel.read(buffer);
                    if(n<0)throw new Stop(Status.CHANGED);
                    if(n==0)throw new Stop(Status.IO_UNKNOWN);
                }
                live(deadline,clock);
                if(channel.size()!=file.size)throw new Stop(Status.CHANGED);
                afterRead.run();
                live(deadline,clock);
                List<Stamp> after;
                try { after=snapshot(trustedRoot,target,deadline,clock,attributes); }
                catch(Stop changed) { if(changed.status==Status.EXPIRED)throw changed;throw new Stop(Status.CHANGED); }
                if(!before.equals(after)||channel.size()!=file.size)throw new Stop(Status.CHANGED);
            }
            live(deadline,clock);
            return new Result(Status.REGULAR_BYTES,bytes,reservedBytes);
        } catch(Stop stopped) { wipe(bytes);return new Result(stopped.status,null,reservedBytes); }
          catch(InvalidPathException|SecurityException invalid) { wipe(bytes);return new Result(Status.UNSAFE_PATH,null,reservedBytes); }
          catch(IOException|RuntimeException failure) { wipe(bytes);return new Result(Status.IO_UNKNOWN,null,reservedBytes); }
    }
    private static List<Stamp> snapshot(Path trustedRoot,Path target,long deadline,LongSupplier clock,Attributes attributes)throws IOException,Stop {
        return snapshot(trustedRoot,target,deadline,clock,attributes,false);
    }
    private static List<Stamp> snapshot(Path trustedRoot,Path target,long deadline,LongSupplier clock,Attributes attributes,boolean finalDirectory)throws IOException,Stop {
        List<Stamp> result=new ArrayList<>();Path current=trustedRoot;
        Path relative=trustedRoot.relativize(target);
        add(result,current,true,deadline,clock,attributes);
        for(int i=0;!target.equals(trustedRoot)&&i<relative.getNameCount();i++) {
            current=current.resolve(relative.getName(i));add(result,current,finalDirectory||i<relative.getNameCount()-1,deadline,clock,attributes);
        }
        return result;
    }
    private static void add(List<Stamp> out,Path path,boolean directory,long deadline,LongSupplier clock,Attributes attributes)throws IOException,Stop {
        live(deadline,clock);BasicFileAttributes attrs;
        try { attrs=attributes.read(path); }
        catch(NoSuchFileException absent) { throw new Stop(Status.ABSENT_LOCAL); }
        if(attrs.isSymbolicLink()||attrs.fileKey()==null||directory&&!attrs.isDirectory()||!directory&&!attrs.isRegularFile())throw new Stop(Status.UNSAFE_PATH);
        out.add(new Stamp(path,attrs.fileKey(),attrs.size(),attrs.lastModifiedTime(),attrs.creationTime(),directory));
    }
    private static void live(long deadline,LongSupplier clock)throws Stop {
        if(clock==null)throw new Stop(Status.EXPIRED);
        long now=clock.getAsLong();if(now<0||deadline<=now||Thread.currentThread().isInterrupted())throw new Stop(Status.EXPIRED);
    }
    private static void wipe(byte[] bytes){if(bytes!=null)Arrays.fill(bytes,(byte)0);}
    private static final class Stop extends Exception { final Status status;Stop(Status status){this.status=status;} }
    private OwnStateReadOnlyFiles() { }
}
