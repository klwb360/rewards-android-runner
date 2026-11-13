package com.aliothmoon.maafw.once;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
/** Pure own-UID name inventory. Absence is not proof of no historical work. No I/O or factories. */
final class OwnStateInventory {
 enum Root { FILES, NO_BACKUP, PREFERENCES }
 enum Role { LEGACY_JSON, TRIAL_BINARY, RECOVERY_RESIDUE, BURN, SYNTHETIC_JOURNAL, SYNTHETIC_MARKER }
 record Entry(Root root,String relative,Role role) {
  Entry { if(root==null||role==null||!safe(relative))throw new IllegalArgumentException("Fixed relative name required"); }
 }
 static final String SESSION_RECEIPTS="NOT_PERSISTED";
 private static final List<Entry> FIXED=build();
 private OwnStateInventory(){}
 static List<Entry> fixed(){return FIXED;}
 static List<String> dynamicDirectories(Root root){return root==Root.FILES?List.of(""):root==Root.NO_BACKUP?List.of("","rewards_synthetic_v6"):List.of();}
 static Optional<Entry> dynamic(Root root,String relative){
  if(root==null||!safe(relative))return Optional.empty();
  if(root==Root.FILES&&(relative.matches("search-review-v1-[a-f0-9]{64}\\.json")||relative.matches("search-full-v1-[a-f0-9]{64}-[1-9][0-9]{0,18}\\.json")))return Optional.of(new Entry(root,relative,Role.LEGACY_JSON));
  if(root==Root.NO_BACKUP&&relative.matches("rewards_synthetic_v6/append-(0|[1-9][0-9]{0,4})\\.marker")){
   int n=Integer.parseInt(relative.substring(relative.indexOf("append-")+7,relative.length()-7));
   if(n<=40001)return Optional.of(new Entry(root,relative,Role.SYNTHETIC_MARKER));
  }
  if(root==Root.NO_BACKUP&&relative.matches("synthetic-end-request-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.record"))return Optional.of(new Entry(root,relative,Role.SYNTHETIC_MARKER));
  return Optional.empty();
 }
 static boolean accepts(Entry e){return e!=null&&(FIXED.contains(e)||dynamic(e.root(),e.relative()).filter(e::equals).isPresent());}
 private static boolean safe(String p){return p!=null&&p.length()>0&&p.length()<=240&&p.matches("[a-zA-Z0-9_./-]+")&&!p.startsWith("/")&&!p.endsWith("/")&&!p.contains("//")&&!p.contains("..")&&!p.contains(":" );}
 private static void add(List<Entry> out,Root root,String path,Role role){out.add(new Entry(root,path,role));}
 private static void burns(List<Entry> out,String dir,String slots){for(String slot:slots.split(" "))add(out,Root.NO_BACKUP,dir+"/"+slot+".burned",Role.BURN);}
 private static List<Entry> build(){
  List<Entry> out=new ArrayList<>();
  for(String name:List.of("state-official.json","reading-history-official.json","web-history-official.json","more-history-official.json")){
   add(out,Root.FILES,name,Role.LEGACY_JSON);
   for(String suffix:List.of(".new",".bak"))add(out,Root.FILES,name+suffix,Role.RECOVERY_RESIDUE);
  }
  add(out,Root.PREFERENCES,"checkin-official.xml",Role.LEGACY_JSON);
  add(out,Root.PREFERENCES,"checkin-official.xml.bak",Role.RECOVERY_RESIDUE);
  for(String stem:List.of("trial-state","reading-journal","reading-ledger")){
   String base="rewards-single-trial/"+stem;
   add(out,Root.NO_BACKUP,base+".bin",Role.TRIAL_BINARY);
   for(String suffix:List.of(".new",".bak",".lock",stem.equals("reading-ledger")?".txn":".intent"))add(out,Root.NO_BACKUP,base+suffix,Role.RECOVERY_RESIDUE);
  }
  add(out,Root.NO_BACKUP,"rewards_synthetic_v6/attempts.journal",Role.SYNTHETIC_JOURNAL);
  burns(out,"controlled-whole-frame-20261111-fifth-once","attempt");
  burns(out,"rewards-business-20261111-v13","BASELINE AFTER_ACTION");
  burns(out,"rewards-native-checkin-20261111-v15","BEFORE AFTER TAP");
  burns(out,"rewards-current-status-20261111-v17","CURRENT_STATUS");
  burns(out,"rewards-more-quote-20261111-v18","BEFORE AFTER TAP");
  burns(out,"rewards-readonly-two-stage-20261111-v20","FIRST SECOND");
  burns(out,"rewards-root-business-20261111-v24","BEFORE AFTER TAP RETURN");
  burns(out,"rewards-root-family-business-20261111-v27","BEFORE AFTER TAP RETURN");
  burns(out,"rewards-root-preflight-20261111-v24","FIRST");
  burns(out,"rewards-root-family-preflight-20261111-v27","FIRST");
  burns(out,"rewards-root-family-diagnostic-20261111-v25","FIRST");
  burns(out,"rewards-root-stable-family-diagnostic-20261111-v26","FIRST");
  burns(out,"rewards-root-image-audit-20261111-v28","FIRST");
  burns(out,"rewards-root-image-series-20261112-v30","FRAME_1 FRAME_2 FRAME_3");
  burns(out,"rewards-root-bounded-business-20261112-v31","BEFORE_1 BEFORE_2 BEFORE_3 AFTER_1 AFTER_2 AFTER_3 TAP RETURN");
  burns(out,"rewards-root-return-read-20261112-v32","READ_1 READ_2 READ_3 RETURN");
  burns(out,"rewards-root-current-read-20261112-v33","READ_1 READ_2 READ_3");
  burns(out,"rewards-root-current-cards-20261112-v34","READ_1 READ_2 READ_3");
  burns(out,"rewards-root-selected-pending-20261112-v35","BEFORE_1 BEFORE_2 BEFORE_3 AFTER_1 AFTER_2 AFTER_3 TAP RETURN");
  burns(out,"rewards-root-status-recovery-20261112-v36","READ_1 READ_2 READ_3 RETURN");
  burns(out,"rewards-root-unique-pending-20261112-v37","BEFORE_1 BEFORE_2 BEFORE_3 AFTER_1 AFTER_2 AFTER_3 TAP RETURN");
  burns(out,"rewards-root-unique-refined-20261112-v38","BEFORE_1 BEFORE_2 BEFORE_3 AFTER_1 AFTER_2 AFTER_3 TAP RETURN");
  burns(out,"rewards-root-progress-read-20261112-v39","READ_1 READ_2 READ_3");
  burns(out,"rewards-root-progress-scan-20261112-v40","READ_1 READ_2 READ_3 SCROLL_1 SCROLL_2");
  burns(out,"rewards-root-progress-locate-20261112-v42","READ_1 READ_2 READ_3 SCROLL_1 SCROLL_2");
  burns(out,"rewards-root-progress-layout-20261112-v41","READ_1");
  burns(out,"rewards-root-progress-structure-20261112-v43","READ_1");
  burns(out,"rewards-ui-progress-20261112-v44","UI_READ_1");
  burns(out,"rewards-ui-progress-20261112-v45","UI_READ_1");
  burns(out,"rewards-ui-recovery-20261112-v46","RUN_1 PREPARE_1 NAV_1 UI_READ_1");
  burns(out,"rewards-entry-diagnostic-20261112-v47","RUN_1 DIAGNOSTIC_READ_1");
  burns(out,"rewards-ui-recovery-20261112-v48","RUN_1 PREPARE_1 NAV_1 UI_READ_1");
  burns(out,"rewards-ui-ready-20261112-v49","RUN_1 UI_READ_1");
  burns(out,"rewards-ui-format-20261112-v50","RUN_1 UI_READ_1");
  burns(out,"rewards-ui-field-20261112-v51","RUN_1 UI_READ_1");
  burns(out,"rewards-ui-locate-20261112-v52","RUN_1 PREPARE_1 NAV_1 UI_READ_1");
  burns(out,"rewards-ui-bound-points-20261112-v53","RUN_1 UI_READ_1");
  return List.copyOf(out);
 }
}
