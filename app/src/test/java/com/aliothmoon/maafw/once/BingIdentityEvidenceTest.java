package com.aliothmoon.maafw.once;
import org.junit.Test;import java.util.List;import static org.junit.Assert.*;
public class BingIdentityEvidenceTest {
 @Test public void observedSignerNeverProvesPublisherOrFrame(){var r=BingIdentityEvidence.observed("com.microsoft.bing",123,List.of("a".repeat(64)));assertEquals(BingIdentityEvidence.PackageState.METADATA_OBSERVED,r.state());assertEquals(BingIdentityEvidence.Publisher.NOT_VERIFIED,r.publisher());assertEquals(BingIdentityEvidence.PixelOrigin.UNKNOWN,r.pixelOrigin());}
 @Test public void wrongPackageAndInvalidCertRejected(){assertEquals(BingIdentityEvidence.PackageState.UNKNOWN,BingIdentityEvidence.observed("fake",1,List.of("a".repeat(64))).state());assertEquals(BingIdentityEvidence.PackageState.UNKNOWN,BingIdentityEvidence.observed("com.microsoft.bing",1,List.of("abc")).state());}
 @Test public void duplicateSignerAndNegativeVersionRejected(){assertEquals(BingIdentityEvidence.PackageState.UNKNOWN,BingIdentityEvidence.observed("com.microsoft.bing",1,List.of("a".repeat(64),"a".repeat(64))).state());assertEquals(BingIdentityEvidence.PackageState.UNKNOWN,BingIdentityEvidence.observed("com.microsoft.bing",-1,List.of("a".repeat(64))).state());}
 @Test public void metadataImmutableAndSummaryNoSignerContents(){var hashes=new java.util.ArrayList<String>();hashes.add("a".repeat(64));var r=BingIdentityEvidence.observed("com.microsoft.bing",1,hashes);hashes.clear();assertEquals(1,r.signerSha256().size());assertFalse(BingIdentityEvidence.summary(r).contains("a".repeat(64)));}
}
