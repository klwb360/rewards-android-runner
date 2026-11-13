package com.aliothmoon.maafw.once;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import java.security.MessageDigest;
import java.util.ArrayList;
/** Ordinary public PackageManager read only. No launch, permissions, hidden APIs or trust pin. */
final class BingPackageMetadata {
 static BingIdentityEvidence.Installed read(PackageManager pm){
  try {
   PackageInfo info=pm.getPackageInfo(BingIdentityEvidence.PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES);
   if(info.signingInfo==null)return BingIdentityEvidence.unavailable(BingIdentityEvidence.PackageState.UNKNOWN);
   // Current signer set only. Never confuse certificate history with multiple current signers.
   Signature[] signatures=info.signingInfo.getApkContentsSigners();
   if(signatures==null||signatures.length==0||signatures.length>8)return BingIdentityEvidence.unavailable(BingIdentityEvidence.PackageState.UNKNOWN);
   ArrayList<String> hashes=new ArrayList<>();
   for(Signature signature:signatures){
    if(signature==null)return BingIdentityEvidence.unavailable(BingIdentityEvidence.PackageState.UNKNOWN);
    byte[] bytes=signature.toByteArray();
    if(bytes.length==0||bytes.length>16384)return BingIdentityEvidence.unavailable(BingIdentityEvidence.PackageState.UNKNOWN);
    byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hash=new StringBuilder(64);
    for(byte b:digest)hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
    hashes.add(hash.toString());
   }
   java.util.Collections.sort(hashes);
   return BingIdentityEvidence.observed(info.packageName,info.getLongVersionCode(),hashes);
  } catch(PackageManager.NameNotFoundException missing){return BingIdentityEvidence.unavailable(BingIdentityEvidence.PackageState.NOT_OBSERVABLE);}
    catch(RuntimeException|java.security.NoSuchAlgorithmException unavailable){return BingIdentityEvidence.unavailable(BingIdentityEvidence.PackageState.UNKNOWN);}
 }
}
