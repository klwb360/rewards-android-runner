package com.aliothmoon.maafw.once;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/** Exact historical local crop. No caller path, capture, live source proof or input capability. */
final class LocalCardMockFiles {
    static final String DIRECTORY="rewards-native-card-roi-diag-v2";
    static final String IMAGE_SHA="375e41a92ecb00751832ddcd4f2ea1dd31e04033ba18be7b34ebb0a53db573ec";
    static final String MANIFEST_SHA="9cd4b198b1d5e18170ad859f64ddd8753b081c9c0c11d54ed46e257143ab8378";
    static final int WIDTH=1336,HEIGHT=588;
    private static final String INPUT="rewards-card-reveal-v2/666d9ccd-2d8e-42ae-af67-69b1aeef814b";
    private static volatile Object quarantine;
    static boolean knownClean(){return quarantine==null;}
    static Object directory(Path path)throws Exception{
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isDirectory()||a.isSymbolicLink()||a.fileKey()==null)throw new SecurityException("Exact local directory required");
        return a.fileKey();
    }
    static byte[] exactFile(Path path,String hash,int limit)throws Exception{
        var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.isSymbolicLink()||before.fileKey()==null||before.size()<1||before.size()>limit)
            throw new SecurityException("Exact bounded local file required");
        byte[] bytes=new byte[(int)before.size()];boolean adopted=false;
        try{
            try(var file=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())if(file.read(data)<=0)throw new SecurityException("Local input incomplete");
                if(file.read(ByteBuffer.allocate(1))!=-1)throw new SecurityException("Local input changed");
            }
            var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!after.isRegularFile()||after.isSymbolicLink()||!before.fileKey().equals(after.fileKey())
                    ||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!sha(bytes).equals(hash))
                throw new SecurityException("Historical input identity changed");
            adopted=true;return bytes;
        }finally{if(!adopted)Arrays.fill(bytes,(byte)0);}
    }
    static String sha(byte[] bytes)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        try{StringBuilder s=new StringBuilder();for(byte v:digest)s.append(Character.forDigit((v>>>4)&15,16)).append(Character.forDigit(v&15,16));return s.toString();}
        finally{Arrays.fill(digest,(byte)0);}
    }
    static Bitmap read(Context context)throws Exception{
        Path cache=context.getCacheDir().toPath(),parent=cache.resolve("rewards-card-reveal-v2"),input=cache.resolve(INPUT);
        Object cacheId=directory(cache),parentId=directory(parent),inputId=directory(input);
        byte[] manifest=exactFile(input.resolve("RESULT.json"),MANIFEST_SHA,16384);Arrays.fill(manifest,(byte)0);
        byte[] png=exactFile(input.resolve("CHECK_IN.png"),IMAGE_SHA,10*1024*1024);Bitmap image=null;boolean adopted=false;
        try{
            var bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(png,0,png.length,bounds);
            if(bounds.outWidth!=WIDTH||bounds.outHeight!=HEIGHT)throw new SecurityException("Historical crop shape changed");
            var options=new BitmapFactory.Options();options.inMutable=true;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            image=BitmapFactory.decodeByteArray(png,0,png.length,options);
            if(image==null||!image.isMutable()||image.getWidth()!=WIDTH||image.getHeight()!=HEIGHT
                    ||!cacheId.equals(directory(cache))||!parentId.equals(directory(parent))||!inputId.equals(directory(input)))
                throw new SecurityException("Historical crop decode rejected");
            adopted=true;return image;
        }finally{Arrays.fill(png,(byte)0);if(!adopted&&image!=null){
            try{image.eraseColor(0);}catch(RuntimeException|Error failed){quarantine=image;}
            try{image.recycle();}catch(RuntimeException|Error failed){quarantine=image;}
        }}
    }
    static void burn(Path files)throws Exception{
        Object parent=directory(files);Path scope=files.resolve(DIRECTORY);
        try{Files.createDirectory(scope);}catch(FileAlreadyExistsException exists){/* Verify exact path. */}
        Object child=directory(scope);
        write(scope,"RUN.burned","NATIVE_CARD_ROI_DIAG_V2 historicalLocalCrop=true maxMock=0 diagnosticOnly=true realClicks=0 captures=0\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        try(var file=FileChannel.open(scope,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){file.force(true);}
        try(var file=FileChannel.open(files,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){file.force(true);}
        if(!parent.equals(directory(files))||!child.equals(directory(scope)))throw new SecurityException("Mock record directory changed");
    }
    static Path output(Context context)throws Exception{
        Path cache=context.getCacheDir().toPath(),parent=cache.resolve(DIRECTORY);Object cacheId=directory(cache);
        try{Files.createDirectory(parent);}catch(FileAlreadyExistsException exists){/* Verify exact path. */}
        directory(parent);Path out=Files.createDirectory(parent.resolve(UUID.randomUUID().toString()));
        if(!cacheId.equals(directory(cache)))throw new SecurityException("Local cache changed");return out;
    }
    static void write(Path parent,String name,byte[] bytes)throws Exception{
        if(!name.equals("DIAGNOSTICS.json")&&!name.equals("RUN.burned")&&!name.equals("MARKED.png")&&!name.equals("RESULT.json"))throw new SecurityException("Fixed local output required");
        Object identity=directory(parent);
        try(var file=FileChannel.open(parent.resolve(name),StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())if(file.write(data)<=0)throw new SecurityException("Local output incomplete");file.force(true);
        }
        if(!identity.equals(directory(parent)))throw new SecurityException("Output directory changed");
    }
    private LocalCardMockFiles(){}
}
