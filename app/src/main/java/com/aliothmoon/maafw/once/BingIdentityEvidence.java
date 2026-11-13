package com.aliothmoon.maafw.once;
import java.util.List;
/** Installed-package facts do not authenticate a publisher or the pixels in a frame. */
final class BingIdentityEvidence {
 static final String PACKAGE = "com.microsoft.bing";
 enum PackageState { METADATA_OBSERVED, NOT_OBSERVABLE, UNKNOWN }
 enum Publisher { NOT_VERIFIED }
 enum PixelOrigin { UNKNOWN }
 record Installed(PackageState state,long version,List<String> signerSha256) {
  Installed { signerSha256=List.copyOf(signerSha256); }
  Publisher publisher(){return Publisher.NOT_VERIFIED;}
  PixelOrigin pixelOrigin(){return PixelOrigin.UNKNOWN;}
 }
 static Installed unavailable(PackageState state){return new Installed(state,-1,List.of());}
 static Installed observed(String name,long version,List<String> hashes){
  if(!PACKAGE.equals(name)||version<0||hashes==null||hashes.isEmpty()||hashes.size()>8
     ||hashes.stream().anyMatch(h->h==null||!h.matches("[0-9a-f]{64}"))
     ||hashes.stream().distinct().count()!=hashes.size())return unavailable(PackageState.UNKNOWN);
  return new Installed(PackageState.METADATA_OBSERVED,version,hashes);
 }
 static String summary(Installed value){
  return "Installed package: "+(value==null?PackageState.UNKNOWN:value.state())
   +"; version="+(value==null?-1:value.version())+"; observed signers="+(value==null?0:value.signerSha256().size())
   +"; publisher=NOT_VERIFIED; frame package=UNKNOWN";
 }
}
