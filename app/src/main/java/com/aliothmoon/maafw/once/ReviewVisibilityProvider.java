package com.aliothmoon.maafw.once;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.*;
/** Private same-UID scalar lifecycle IPC. Never transports capture authority or OCR. */
public final class ReviewVisibilityProvider extends ContentProvider {
 static final AppVisibilityFence FENCE=new AppVisibilityFence();
 private final Map<IBinder,Client> clients=new HashMap<>();
 private static final class Client {long sequence;final Map<String,Object> activities=new HashMap<>();}
 @Override public boolean onCreate(){return true;}
 @Override public synchronized Bundle call(String method,String arg,Bundle input){
  if(Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException("Same UID only");
  try {
   if(input==null)throw new IllegalArgumentException();IBinder binder=input.getBinder("client");
   if(binder==null)throw new IllegalArgumentException();
   if("register".equals(method)){
    if(clients.containsKey(binder)||clients.size()>=16)throw new IllegalStateException();
    binder.linkToDeath(()->FENCE.unknown(),0);clients.put(binder,new Client());FENCE.invalidate();
   }else{
    Client c=clients.get(binder);long seq=input.getLong("sequence");String id=input.getString("activity");
    if(c==null||seq!=c.sequence+1||id==null||id.length()>80)throw new IllegalStateException();c.sequence=seq;
    Object key=c.activities.get(id);
    switch(method){
     case "create": if(key!=null||c.activities.size()>=32)throw new IllegalStateException();key=new Object();c.activities.put(id,key);FENCE.enter(key);break;
     case "start": if(!c.activities.containsKey(id))throw new IllegalStateException();FENCE.enter(key);break;
     case "stop": if(!c.activities.containsKey(id))throw new IllegalStateException();FENCE.stopped(key);break;
     case "destroy": if(c.activities.remove(id)==null)throw new IllegalStateException();FENCE.destroyed(key);break;
     default: throw new IllegalArgumentException();
    }
   }
   Bundle result=new Bundle();result.putBoolean("ack",true);return result;
  }catch(Exception failure){FENCE.unknown();throw new IllegalStateException("Visibility state unknown");}
 }
 @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
 @Override public String getType(Uri u){return null;}
 @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
 @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
