package com.aliothmoon.maafw.once;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** In-memory identity receipt for an already-published crop; never display or input authority. */
final class CardNativeMockReceipt {
    static final int MAX_PNG_BYTES=10*1024*1024;
    private final Object owner;
    private final String sha;
    private final int width,height;
    private boolean used,accepted,canceled,planned;

    private CardNativeMockReceipt(Object owner,String sha,int width,int height){
        this.owner=owner;this.sha=sha;this.width=width;this.height=height;
    }
    /** The reveal runtime calls this only after the exact source-bracketed crop is committed. */
    static CardNativeMockReceipt seal(Object owner,String sha,int width,int height){
        if(owner==null||sha==null||!sha.matches("[a-f0-9]{64}")
                ||width<32||height<32||width>4096||height>4096
                ||(long)width*height>LocalCardMockPlan.MAX_PIXELS)
            throw new IllegalArgumentException("Bounded exact-owner crop receipt required");
        return new CardNativeMockReceipt(owner,sha,width,height);
    }
    /** Failure spends the receipt too. No files, paths, old records or image bytes are retained. */
    synchronized boolean claim(Object expectedOwner,byte[] png,int expectedWidth,int expectedHeight){
        boolean first=!used;used=true;
        if(!first){accepted=false;canceled=true;return false;}
        if(canceled||owner!=expectedOwner||png==null||png.length<57||png.length>MAX_PNG_BYTES
                ||width!=expectedWidth||height!=expectedHeight)return false;
        accepted=sha.equals(digest(png));
        return accepted;
    }
    synchronized boolean claimedBy(Object expectedOwner){return accepted&&!canceled&&owner==expectedOwner;}
    synchronized boolean claimPlan(Object expectedOwner){
        boolean first=!planned;planned=true;return first&&claimedBy(expectedOwner);
    }
    synchronized void cancel(){canceled=true;}
    String inputSha256(){return sha;}
    int width(){return width;}
    int height(){return height;}
    static String digest(byte[] bytes){
        byte[] hash;
        try{hash=MessageDigest.getInstance("SHA-256").digest(bytes);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        try{StringBuilder text=new StringBuilder(64);for(byte value:hash){
            text.append(Character.forDigit((value>>>4)&15,16));text.append(Character.forDigit(value&15,16));
        }return text.toString();}
        finally{Arrays.fill(hash,(byte)0);}
    }
}
