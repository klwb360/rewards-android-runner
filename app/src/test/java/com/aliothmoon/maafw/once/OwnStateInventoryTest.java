package com.aliothmoon.maafw.once;
import org.junit.Test;
import java.util.HashSet;
import static org.junit.Assert.*;
public class OwnStateInventoryTest {
 @Test public void fixedUniqueImmutableAndAccepted(){var a=OwnStateInventory.fixed();assertEquals(a.size(),new HashSet<>(a).size());for(var e:a)assertTrue(OwnStateInventory.accepts(e));try{a.clear();fail();}catch(UnsupportedOperationException expected){}}
 @Test public void legacyBusinessAndRecoverySeparated(){assertTrue(has("state-official.json",OwnStateInventory.Role.LEGACY_JSON));assertTrue(has("state-official.json.bak",OwnStateInventory.Role.RECOVERY_RESIDUE));assertTrue(has("rewards-single-trial/reading-ledger.txn",OwnStateInventory.Role.RECOVERY_RESIDUE));}
 @Test public void historicalBurnsRemainNonBusiness(){assertTrue(has("rewards-ui-bound-points-20261112-v53/UI_READ_1.burned",OwnStateInventory.Role.BURN));assertTrue(has("rewards-root-unique-refined-20261112-v38/TAP.burned",OwnStateInventory.Role.BURN));assertEquals("NOT_PERSISTED",OwnStateInventory.SESSION_RECEIPTS);}
 @Test public void exactDynamicNamesOnly(){String h="a".repeat(64);assertTrue(dynamic(OwnStateInventory.Root.FILES,"search-review-v1-"+h+".json"));assertTrue(dynamic(OwnStateInventory.Root.FILES,"search-full-v1-"+h+"-123.json"));assertFalse(dynamic(OwnStateInventory.Root.NO_BACKUP,"search-review-v1-"+h+".json"));assertFalse(dynamic(OwnStateInventory.Root.FILES,"search-review-v1-"+h+".json.bak"));}
 @Test public void syntheticMarkersBounded(){assertTrue(dynamic(OwnStateInventory.Root.NO_BACKUP,"rewards_synthetic_v6/append-40001.marker"));assertFalse(dynamic(OwnStateInventory.Root.NO_BACKUP,"rewards_synthetic_v6/append-40002.marker"));assertFalse(dynamic(OwnStateInventory.Root.NO_BACKUP,"rewards_synthetic_v6/append-01.marker"));}
 @Test public void acceptsDynamicEntryOnlyWithMatchingRole(){var e=OwnStateInventory.dynamic(OwnStateInventory.Root.FILES,"search-review-v1-"+"a".repeat(64)+".json").orElseThrow();assertTrue(OwnStateInventory.accepts(e));assertFalse(OwnStateInventory.accepts(new OwnStateInventory.Entry(e.root(),e.relative(),OwnStateInventory.Role.BURN)));assertFalse(OwnStateInventory.accepts(null));}
 @Test public void rejectTraversalUnknownAndForgedRole(){for(String s:new String[]{"../state-official.json","/state-official.json","C:/state-official.json","foo\\bar","foo//bar","x\n"})assertFalse(dynamic(OwnStateInventory.Root.FILES,s));assertFalse(OwnStateInventory.accepts(new OwnStateInventory.Entry(OwnStateInventory.Root.FILES,"arbitrary.json",OwnStateInventory.Role.LEGACY_JSON)));}
 private static boolean dynamic(OwnStateInventory.Root r,String p){return OwnStateInventory.dynamic(r,p).isPresent();}
 private static boolean has(String p,OwnStateInventory.Role role){return OwnStateInventory.fixed().stream().anyMatch(e->e.relative().equals(p)&&e.role()==role);}
}
