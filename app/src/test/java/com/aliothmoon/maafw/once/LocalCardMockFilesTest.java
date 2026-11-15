package com.aliothmoon.maafw.once;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalCardMockFilesTest {
 @Test public void exactBytesAndHashAreRequired()throws Exception{
  Path file=Files.createTempFile("native-mock-exact",".bin");
  try{byte[] bytes="local-only".getBytes(StandardCharsets.US_ASCII);Files.write(file,bytes);
   if(Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()==null)
    assertThrows(SecurityException.class,()->LocalCardMockFiles.exactFile(file,LocalCardMockFiles.sha(bytes),64));
   else assertArrayEquals(bytes,LocalCardMockFiles.exactFile(file,LocalCardMockFiles.sha(bytes),64));
   assertThrows(SecurityException.class,()->LocalCardMockFiles.exactFile(file,"0".repeat(64),64));
  }finally{Files.delete(file);}
 }
 @Test public void boundedInputCannotUseDirectoryOrOversizedFile()throws Exception{
  Path directory=Files.createTempDirectory("native-mock-bounds"),file=directory.resolve("input.bin");
  try{Files.write(file,new byte[65]);
   assertThrows(SecurityException.class,()->LocalCardMockFiles.exactFile(file,"0".repeat(64),64));
   assertThrows(SecurityException.class,()->LocalCardMockFiles.exactFile(directory,"0".repeat(64),1024));
  }finally{Files.delete(file);Files.delete(directory);}
 }
 @Test public void publicationCannotOverwriteOrChooseArbitraryName()throws Exception{
  Path directory=Files.createTempDirectory("native-mock-output");Path file=directory.resolve("RESULT.json");
  try{
   if(Files.readAttributes(directory,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()==null){
    assertThrows(SecurityException.class,()->LocalCardMockFiles.write(directory,"RESULT.json",new byte[]{1,2,3}));
    assertFalse(Files.exists(file));
   }else{
    LocalCardMockFiles.write(directory,"RESULT.json",new byte[]{1,2,3});
    assertThrows(FileAlreadyExistsException.class,()->LocalCardMockFiles.write(directory,"RESULT.json",new byte[]{4}));
    assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(file));
   }
   assertThrows(SecurityException.class,()->LocalCardMockFiles.write(directory,"../old-record",new byte[]{4}));
  }finally{Files.deleteIfExists(file);Files.delete(directory);}
 }
 @Test public void inputIsFixedHistoricalCropWithIndependentRecordDirectory(){
  assertEquals("rewards-native-card-roi-diag-v2",LocalCardMockFiles.DIRECTORY);
  assertNotEquals(CardRevealProbeScope.DIRECTORY,LocalCardMockFiles.DIRECTORY);
  assertEquals(1336,LocalCardMockFiles.WIDTH);assertEquals(588,LocalCardMockFiles.HEIGHT);
  assertEquals(64,LocalCardMockFiles.IMAGE_SHA.length());assertNotEquals(LocalCardMockFiles.IMAGE_SHA,LocalCardMockFiles.MANIFEST_SHA);
 }
}
