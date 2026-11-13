package com.aliothmoon.maafw.once;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
/** Own-UID private-root staging. Does not claim protection against malicious same-UID/root races. */
final class MaaOnceModelStore implements AutoCloseable {
 public static final int MAX_STAGING=2;public static final long RESERVE_BYTES=3*MaaOnceModelPolicy.MAX_MODEL_BYTES;
 public static final class CapacityException extends IOException {}
 private static final LinkOption[] NOFOLLOW={LinkOption.NOFOLLOW_LINKS};
 private final Path base;private final Object baseKey;private final UserPrincipal owner;private final GroupPrincipal group;
 private static final Set<PosixFilePermission> DIR_MODE=PosixFilePermissions.fromString("rwx------"),FILE_MODE=PosixFilePermissions.fromString("rw-------");
 private final List<Path> dirs=new ArrayList<>();private final List<Object> dirKeys=new ArrayList<>();
 private final Object[] fileKeys=new Object[MaaOnceModelPolicy.count()];private Path bundle,ocr;
 private MaaOnceModelStore(Path root)throws IOException{base=root.toRealPath();baseKey=directoryKey(base);PosixFileAttributes a=Files.readAttributes(base,PosixFileAttributes.class,NOFOLLOW);owner=a.owner();group=a.group();}
 public static MaaOnceModelStore open(Path suppliedRoot)throws IOException{return new MaaOnceModelStore(suppliedRoot);}
 // Called after the recognizer has strongly anchored this object. Partial creation
 // failure is quarantined by the caller, never mistaken for completed cleanup.
 public void prepare()throws IOException{
  bundle=allocate();remember(bundle,base);Path model=Files.createDirectory(bundle.resolve("model"),PosixFilePermissions.asFileAttribute(DIR_MODE));remember(model,bundle);ocr=Files.createDirectory(model.resolve("ocr"),PosixFilePermissions.asFileAttribute(DIR_MODE));remember(ocr,model);verify();
 }
 private Path allocate()throws IOException{
  int count=0;try(DirectoryStream<Path> entries=Files.newDirectoryStream(base,"maa-once-model-v4-*")){for(Path unused:entries)if(++count>=MAX_STAGING)throw new CapacityException();}
  if(!MaaOnceModelPolicy.capacityAllows(count,base.toFile().getUsableSpace()))throw new CapacityException();
  // Fixed exclusive slots give a hard concurrent bound; never reuse or clean an unknown existing slot.
  for(int i=0;i<MAX_STAGING;i++){Path p=base.resolve("maa-once-model-v4-"+i);try{return Files.createDirectory(p,PosixFilePermissions.asFileAttribute(DIR_MODE));}catch(FileAlreadyExistsException occupied){}}
  throw new CapacityException();
 }
 static Object directoryKey(Path p)throws IOException{BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,NOFOLLOW);if(!a.isDirectory()||a.isSymbolicLink()||a.fileKey()==null)throw new IOException();return a.fileKey();}
 public static void requireChild(Path parent,Path child)throws IOException{
  if(parent==null||child==null||!parent.isAbsolute()||!child.isAbsolute()||!parent.equals(parent.normalize())||!child.equals(child.normalize())||!parent.equals(child.getParent())||!child.equals(child.toRealPath()))throw new IOException();
  BasicFileAttributes a=Files.readAttributes(child,BasicFileAttributes.class,NOFOLLOW);if(a.isSymbolicLink()||a.isOther())throw new IOException();
 }
 private void remember(Path p,Path parent)throws IOException{requireChild(parent,p);permissions(p,DIR_MODE);Object key=directoryKey(p);dirs.add(p);dirKeys.add(key);}
 public void verify()throws IOException{
  if(!base.equals(base.toRealPath())||!baseKey.equals(directoryKey(base)))throw new IOException();
  Path parent=base;for(int i=0;i<dirs.size();i++){Path p=dirs.get(i);requireChild(parent,p);permissions(p,DIR_MODE);if(!dirKeys.get(i).equals(directoryKey(p)))throw new IOException();parent=p;}
 }
 public String bundlePath()throws IOException{verify();if(bundle==null||ocr==null)throw new IOException();return bundle.toString();}
 private Path modelPath(int index)throws IOException{verify();if(ocr==null)throw new IOException();return ocr.resolve(MaaOnceModelPolicy.name(index));}
 private void permissions(Path p,Set<PosixFilePermission> mode)throws IOException{PosixFileAttributes a=Files.readAttributes(p,PosixFileAttributes.class,NOFOLLOW);if(!owner.equals(a.owner())||!group.equals(a.group())||!mode.equals(a.permissions()))throw new IOException();}
 private Object regularKey(Path p)throws IOException{requireChild(ocr,p);permissions(p,FILE_MODE);BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,NOFOLLOW);if(!a.isRegularFile()||a.fileKey()==null)throw new IOException();return a.fileKey();}
 private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
 public void write(int index,InputStream input)throws Exception{
  int stage=MaaOnceModelPolicy.stageBase(index);Path p=modelPath(index);MessageDigest md=MessageDigest.getInstance("SHA-256");
  long total=0;byte[] buf=new byte[65536];
  Set<OpenOption> opts=new HashSet<>();opts.add(StandardOpenOption.WRITE);opts.add(StandardOpenOption.CREATE_NEW);opts.add(LinkOption.NOFOLLOW_LINKS);
  try(SeekableByteChannel channel=Files.newByteChannel(p,opts,PosixFilePermissions.asFileAttribute(FILE_MODE));OutputStream out=Channels.newOutputStream(channel)){
   fileKeys[index]=regularKey(p);verify();int n;while((n=input.read(buf))!=-1){if(n==0)continue;total+=n;if(!MaaOnceModelPolicy.allowedBytes(total))throw new IOException();md.update(buf,0,n);out.write(buf,0,n);}
  }finally{Arrays.fill(buf,(byte)0);}
  if(!MaaOnceModelPolicy.digestMatches(index,hex(md.digest())))throw new IOException();verify();if(!fileKeys[index].equals(regularKey(p)))throw new IOException();
  verifyContents(index,p,total);
 }
 private void verifyContents(int index,Path p,long expectedBytes)throws Exception{
  MessageDigest md=MessageDigest.getInstance("SHA-256");long count=0;byte[] buf=new byte[65536];
  try(InputStream in=Files.newInputStream(p,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){int n;while((n=in.read(buf))!=-1){count+=n;if(!MaaOnceModelPolicy.allowedBytes(count))throw new IOException();md.update(buf,0,n);}}
  finally{Arrays.fill(buf,(byte)0);}
  verify();if(count!=expectedBytes||!MaaOnceModelPolicy.digestMatches(index,hex(md.digest()))||!fileKeys[index].equals(regularKey(p)))throw new IOException();
 }
 @Override public void close()throws IOException{
  verify();IOException failure=null;
  if(ocr!=null)for(int i=0;i<MaaOnceModelPolicy.count();i++)if(fileKeys[i]!=null){try{Path p=ocr.resolve(MaaOnceModelPolicy.name(i));if(!fileKeys[i].equals(regularKey(p)))throw new IOException();Files.delete(p);fileKeys[i]=null;}catch(IOException e){failure=e;}}
  if(failure!=null)throw failure;
  for(int i=dirs.size()-1;i>=0;i--){Path p=dirs.get(i);if(!dirKeys.get(i).equals(directoryKey(p)))throw new IOException();Files.delete(p);dirs.remove(i);dirKeys.remove(i);}
 }
}




