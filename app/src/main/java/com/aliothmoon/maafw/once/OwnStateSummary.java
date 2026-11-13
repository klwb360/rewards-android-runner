package com.aliothmoon.maafw.once;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
/** Local observation only. No lease, migration, store construction, or action admission. */
final class OwnStateSummary {
 static final int MAX_ENTRIES=512,MAX_OUTPUT=24,MAX_TOTAL_BYTES=1024*1024;
 static final long MAX_MILLIS=4000;
 static String inspect(String dataDir,int assignedUid,int actualUid,LongSupplier clock,BooleanSupplier cancelled){
  if(dataDir==null||assignedUid!=actualUid||actualUid<10000||clock==null||cancelled==null)return rejected("ROOT_UNAVAILABLE");
  try{return inspectRoot(Path.of(dataDir),clock,cancelled);}catch(RuntimeException failure){return rejected("IO_UNKNOWN");}
 }
 static String rejected(String reason){return "profile=OWN_STATE_V54 status="+reason+" HOLD actionAuthority=false historyComplete=false businessWrites=0 bingReads=0";}
 static String inspectRoot(Path root,LongSupplier clock,BooleanSupplier cancelled){
  long start=clock.getAsLong();if(start<0||start>Long.MAX_VALUE-MAX_MILLIS)return rejected("CLOCK_INVALID");long end=start+MAX_MILLIS;
  List<OwnStateInventory.Entry> entries=new ArrayList<>(OwnStateInventory.fixed());int directoryUnknown=0,unknownNames=0,listed=0;
  for(var r:OwnStateInventory.Root.values())for(String dir:OwnStateInventory.dynamicDirectories(r)){
   if(cancelled.getAsBoolean()||clock.getAsLong()>=end)return rejected("CANCELLED_OR_EXPIRED");
   String rel=prefix(r)+(dir.isEmpty()?"":"/"+dir);var list=OwnStateReadOnlyFiles.list(root,rel,256-listed,end,clock);
   if(list.status()==OwnStateReadOnlyFiles.Status.ABSENT_LOCAL)continue;
   if(list.status()!=OwnStateReadOnlyFiles.Status.DIRECTORY_NAMES){directoryUnknown++;continue;}
   listed+=list.names().size();if(listed>256)return rejected("DIRECTORY_BUDGET_EXCEEDED");
   for(String name:list.names()){
    String candidate=dir.isEmpty()?name:dir+"/"+name;var entry=OwnStateInventory.dynamic(r,candidate);
    if(entry.isPresent()){if(!entries.contains(entry.get()))entries.add(entry.get());}
    else if(relevantUnknown(r,candidate,entries))unknownNames++;
   }
  }
  if(entries.size()>MAX_ENTRIES)return rejected("ENTRY_BUDGET_EXCEEDED");
  int absent=0,present=0,unknown=directoryUnknown+unknownNames,burns=0,residue=0,locks=0,synthetic=0,bytes=0,observed=0,omitted=0,schemaMalformed=0,schemaUnsupported=0,pending=0;
  List<String> details=new ArrayList<>();
  for(int i=0;i<entries.size();i++){
   if(cancelled.getAsBoolean()||clock.getAsLong()<start||clock.getAsLong()>=end)return rejected("CANCELLED_OR_EXPIRED");
   var e=entries.get(i);if(!OwnStateInventory.accepts(e))return rejected("INVENTORY_INVALID");
   String rel=prefix(e.root())+"/"+e.relative();boolean metadata=e.role()!=OwnStateInventory.Role.LEGACY_JSON;
   var read=metadata?OwnStateReadOnlyFiles.inspect(root,rel,end,clock):OwnStateReadOnlyFiles.read(root,rel,MAX_TOTAL_BYTES-bytes,end,clock);observed++;bytes+=read.reservedBytes();if(bytes>MAX_TOTAL_BYTES)return rejected("BYTE_BUDGET_EXCEEDED");
   if(read.status()==OwnStateReadOnlyFiles.Status.ABSENT_LOCAL){absent++;continue;}
   String detail="entry="+i+" root="+e.root()+" role="+e.role()+" fileStatus="+read.status();
   boolean good=read.status()==OwnStateReadOnlyFiles.Status.REGULAR_BYTES||read.status()==OwnStateReadOnlyFiles.Status.PRESENT_NOT_INSPECTED;
   if(!good){unknown++;}
   else {
    present++;
    switch(e.role()){
     case BURN -> {burns++;detail+=" meaning=CONSUMED_EXISTENCE_ONLY contentValidation=NOT_INSPECTED";}
     case RECOVERY_RESIDUE -> {if(e.relative().endsWith(".lock")){locks++;detail+=" meaning=LOCK_FILE_ONLY owner=UNKNOWN";}else{residue++;detail+=" meaning=RECOVERY_ARTIFACT_PRESENT";}}
     case SYNTHETIC_JOURNAL,SYNTHETIC_MARKER -> {synthetic++;detail+=" meaning=SYNTHETIC_ONLY contentValidation=NOT_INSPECTED owner=UNKNOWN";}
     case TRIAL_BINARY -> {unknown++;schemaUnsupported++;detail+=" schemaStatus=UNSUPPORTED_SCHEMA contentValidation=NOT_INSPECTED";}
     case LEGACY_JSON -> {
      byte[] data=read.bytes();try {
       var parsed=OwnStateSchemaSummary.parse(schema(e),data);
       if(parsed.status()==OwnStateSchemaSummary.Status.MALFORMED_OR_CHECKSUM_FAILED){schemaMalformed++;unknown++;}
       if(parsed.status()==OwnStateSchemaSummary.Status.UNSUPPORTED_SCHEMA){schemaUnsupported++;unknown++;}
       if(parsed.pending()!=OwnStateSchemaSummary.Pending.NONE&&parsed.pending()!=OwnStateSchemaSummary.Pending.UNKNOWN)pending++;
       detail+=" "+parsed.summary();
      }finally{if(data!=null)Arrays.fill(data,(byte)0);read.clear();}
     }
    }
   }
   // Business/unknown rows are retained; known burn metadata stays aggregate-only.
   if(e.role()!=OwnStateInventory.Role.BURN||!good){if(details.size()<MAX_OUTPUT)details.add(detail);else omitted++;}
  }
  if(cancelled.getAsBoolean()||clock.getAsLong()>=end)return rejected("CANCELLED_OR_EXPIRED");
  return "profile=OWN_STATE_V54 status=OBSERVED_LOCAL_ONLY HOLD actionAuthority=false historyComplete=false writerLease=NOT_ACQUIRED sessionReceipts=NOT_PERSISTED businessWrites=0 bingReads=0 screenshots=0 ocr=0\n"
   +"inventoryEntries="+entries.size()+" observed="+observed+" absentLocal="+absent+" present="+present+" unknown="+unknown+" schemaMalformed="+schemaMalformed+" schemaUnsupported="+schemaUnsupported+" pending="+pending+" consumedBurnFiles="+burns+" recoveryArtifacts="+residue+" lockFiles="+locks+" syntheticMetadataOnly="+synthetic+" bytesReserved="+bytes+" listedNames="+listed+" outputRows="+details.size()+" omitted="+omitted+" truncated="+(omitted>0)+"\n"+String.join("\n",details);
 }
 static String prefix(OwnStateInventory.Root root){return switch(root){case FILES->"files";case NO_BACKUP->"no_backup";case PREFERENCES->"shared_prefs";};}
 private static boolean relevantUnknown(OwnStateInventory.Root root,String name,List<OwnStateInventory.Entry> known){
  if(known.stream().anyMatch(e->e.root()==root&&e.relative().equals(name)))return false;
  return name.startsWith("search-review-")||name.startsWith("search-full-")||name.startsWith("synthetic-end-request-")||name.startsWith("rewards_synthetic_v6/")||name.startsWith("state-official")||name.startsWith("reading-history-official")||name.startsWith("web-history-official")||name.startsWith("more-history-official");
 }
 private static OwnStateSchemaSummary.Schema schema(OwnStateInventory.Entry e){String n=e.relative();return switch(n){
  case "state-official.json"->OwnStateSchemaSummary.Schema.STATE_OFFICIAL_JSON;
  case "reading-history-official.json"->OwnStateSchemaSummary.Schema.READING_HISTORY_JSON;
  case "web-history-official.json"->OwnStateSchemaSummary.Schema.WEB_HISTORY_JSON;
  case "more-history-official.json"->OwnStateSchemaSummary.Schema.MORE_HISTORY_JSON;
  case "checkin-official.xml"->OwnStateSchemaSummary.Schema.CHECKIN_XML;
  default->n.startsWith("search-review-")?OwnStateSchemaSummary.Schema.SEARCH_REVIEW_JSON:n.startsWith("search-full-")?OwnStateSchemaSummary.Schema.SEARCH_FULL_JSON:OwnStateSchemaSummary.Schema.UNKNOWN;};}
 private OwnStateSummary(){}
}
