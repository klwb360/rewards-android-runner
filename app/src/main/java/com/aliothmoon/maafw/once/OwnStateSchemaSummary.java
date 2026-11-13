package com.aliothmoon.maafw.once;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Pure bounded byte inspection only. Never opens storage, repairs residue, or grants actions.
 * JSON/XML formats have no checksum: syntax validity is neither authenticity nor complete history. */
public final class OwnStateSchemaSummary {
 public enum Schema { STATE_OFFICIAL_JSON, READING_HISTORY_JSON, WEB_HISTORY_JSON, MORE_HISTORY_JSON, CHECKIN_XML,
  TRIAL_STATE_BINARY, READING_HISTORY_BINARY, READING_LEDGER_BINARY, SYNTHETIC_ATTEMPT_JOURNAL, SEARCH_REVIEW_JSON, SEARCH_FULL_JSON, UNKNOWN }
 public enum Status { SCHEMA_VALID_HISTORY_UNPROVEN, PENDING_REQUIRES_REVIEW, EMPTY_PREFS_HISTORY_UNPROVEN, UNSUPPORTED_SCHEMA, MALFORMED_OR_CHECKSUM_FAILED, RECOVERY_ARTIFACT_PRESENT, BOOT_MISMATCH, FUTURE_DAY }
 public enum Pending { UNKNOWN, NONE, READING, SEARCH, WEB, EDGE, MORE, CHECKIN }
 public record Result(Schema schema,Status status,String day,long bootCount,Pending pending,int readingAttempts,int readingNoCredit,int searchAttempts,int searchNoCredit,int hashCount,boolean checkinDone) {
  public Result {require(schema!=null&&status!=null&&pending!=null&&day!=null);if(!day.isEmpty())OwnStateSchemaSummary.day(day);require(bootCount>=-1&&bootCount<=Integer.MAX_VALUE);for(int n:new int[]{readingAttempts,readingNoCredit,searchAttempts,searchNoCredit,hashCount})require(n>=-1&&n<=100000);}
  public boolean actionAllowed(){return false;}
  public boolean hold(){return true;}
  public String summary(){return "schema="+schema+" status="+status+" day="+day+" bootCount="+bootCount+" pending="+pending+" readingAttempts="+readingAttempts+" readingNoCredit="+readingNoCredit+" searchAttempts="+searchAttempts+" searchNoCredit="+searchNoCredit+" hashCount="+hashCount+" checkinDone="+checkinDone+" actionAllowed=false actionAuthority=false historyComplete=false checksum=NOT_PRESENT_IN_SUPPORTED_SCHEMAS";}
 }
 public static Result parse(Schema schema,byte[] bytes){
  if(schema==null)schema=Schema.UNKNOWN;
  if(!supported(schema))return failure(schema,Status.UNSUPPORTED_SCHEMA);
  try{
   int limit=schema==Schema.STATE_OFFICIAL_JSON?65536:8192;
   require(bytes!=null&&bytes.length>0&&bytes.length<=limit);
   String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
   if(schema==Schema.CHECKIN_XML)return checkin(text);
   Map<String,Object> j=new Json(text).object();
   require(number(j,"schema")==1);
   String day=day(string(j,"day"));
   if(schema==Schema.STATE_OFFICIAL_JSON)return state(j,day);
   boolean more=schema==Schema.MORE_HISTORY_JSON;
   keys(j,more?new String[]{"schema","day","hashes","pendingHash","pendingBefore","pendingTarget","pendingPoints"}:new String[]{"schema","day","hashes"});
   Set<String> hashes=hashes(j,"hashes",more?20:schema==Schema.WEB_HISTORY_JSON?3:10);
   Pending pending=Pending.NONE;
   if(more){String hash=string(j,"pendingHash");long before=number(j,"pendingBefore"),target=number(j,"pendingTarget"),points=number(j,"pendingPoints");
    if(hash.isEmpty())require(before==0&&target==0&&points==0);
    else{require(validHash(hash)&&hashes.contains(hash)&&target>=1&&target<=200&&before>=0&&before<target&&points>=1&&points<=target);pending=Pending.MORE;}}
   return result(schema,day,-1,pending,0,0,0,0,hashes.size(),false);
  }catch(Exception invalid){return failure(schema,Status.MALFORMED_OR_CHECKSUM_FAILED);}
 }
 private static Result state(Map<String,Object> j,String day){
  keys(j,"schema","bootCount","day","runStartedAt","readingStartedAt","readingAttempts","readingNoCredit","searchAttempts","searchNoCredit","searchHashes","pendingTask","pendingBefore","pendingTarget","pendingHash");
  long boot=number(j,"bootCount");require(boot>=-1&&boot<=Integer.MAX_VALUE);require(number(j,"runStartedAt")>=0&&number(j,"readingStartedAt")>=0);
  int ra=count(j,"readingAttempts"),rn=count(j,"readingNoCredit"),sa=count(j,"searchAttempts"),sn=count(j,"searchNoCredit");require(rn<=ra&&sn<=sa);
  Set<String> hashes=hashes(j,"searchHashes",512);require(hashes.size()==sa);
  String task=string(j,"pendingTask"),hash=string(j,"pendingHash");long before=number(j,"pendingBefore"),target=number(j,"pendingTarget");Pending pending=Pending.NONE;
  if(task.isEmpty())require(hash.isEmpty()&&before==0&&target==0);
  else{pending=switch(task){case "READING"->Pending.READING;case "SEARCH"->Pending.SEARCH;case "WEB"->Pending.WEB;case "EDGE"->Pending.EDGE;default->throw new IllegalArgumentException();};
   require(before>=0&&target>=1&&before<target&&target<=Integer.MAX_VALUE);
   if(pending==Pending.SEARCH)require(validHash(hash)&&hashes.contains(hash)&&sa>0);
   else require(hash.isEmpty());
   if(pending==Pending.READING)require(ra>0);
  }
  return result(Schema.STATE_OFFICIAL_JSON,day,boot,pending,ra,rn,sa,sn,hashes.size(),false);
 }
 private static Result checkin(String text){
  // Exact persisted SharedPreferences string-map subset. No DTD/entities, namespaces,
  // nested tags, alternate value types, comments, or external-resource-capable XML parser.
  require(!text.contains("<!")&&!text.contains("&"));
  String s=text.trim();if(s.startsWith("<?xml")){int end=s.indexOf("?>");require(end>0);String declaration=s.substring(0,end+2);require(declaration.matches("<\\?xml\\s+version=['\"]1\\.0['\"](?:\\s+encoding=['\"]utf-8['\"])?(?:\\s+standalone=['\"](?:yes|no)['\"])?\\s*\\?>"));s=s.substring(end+2).trim();}
  if(s.matches("<map\\s*/>"))return new Result(Schema.CHECKIN_XML,Status.EMPTY_PREFS_HISTORY_UNPROVEN,"",-1,Pending.NONE,0,0,0,0,0,false);
  require(s.startsWith("<map>")&&s.endsWith("</map>"));s=s.substring(5,s.length()-6);
  Pattern item=Pattern.compile("\\s*<string\\s+name=([\"'])(day|status)\\1>([^<>]*)</string>");Matcher m=item.matcher(s);Map<String,String> fields=new HashMap<>();int end=0;
  while(m.find()){require(m.start()==end&&fields.put(m.group(2),m.group(3))==null);end=m.end();}
  require(s.substring(end).trim().isEmpty());
  if(fields.isEmpty())return new Result(Schema.CHECKIN_XML,Status.EMPTY_PREFS_HISTORY_UNPROVEN,"",-1,Pending.NONE,0,0,0,0,0,false);
  require(fields.size()==2);String d=day(fields.get("day")),status=fields.get("status");require("pending".equals(status)||"done".equals(status));
  return result(Schema.CHECKIN_XML,d,-1,"pending".equals(status)?Pending.CHECKIN:Pending.NONE,0,0,0,0,0,"done".equals(status));
 }
 private static boolean supported(Schema s){return switch(s){case STATE_OFFICIAL_JSON,READING_HISTORY_JSON,WEB_HISTORY_JSON,MORE_HISTORY_JSON,CHECKIN_XML->true;default->false;};}
 private static Result result(Schema s,String d,long b,Pending p,int ra,int rn,int sa,int sn,int h,boolean done){return new Result(s,p==Pending.NONE?Status.SCHEMA_VALID_HISTORY_UNPROVEN:Status.PENDING_REQUIRES_REVIEW,d,b,p,ra,rn,sa,sn,h,done);}
 private static Result failure(Schema s,Status status){return new Result(s,status,"",-1,Pending.UNKNOWN,-1,-1,-1,-1,-1,false);}
 private static Result withStatus(Result r,Status status){return new Result(r.schema(),status,r.day(),r.bootCount(),r.pending(),r.readingAttempts(),r.readingNoCredit(),r.searchAttempts(),r.searchNoCredit(),r.hashCount(),r.checkinDone());}
 private static void require(boolean ok){if(!ok)throw new IllegalArgumentException();}
 private static String day(String d){require(d!=null&&d.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")&&LocalDate.parse(d).toString().equals(d));return d;}
 private static void keys(Map<String,Object> j,String... keys){require(j.keySet().equals(new HashSet<>(Arrays.asList(keys))));}
 private static String string(Map<String,Object> j,String k){Object o=j.get(k);require(o instanceof String);return(String)o;}
 private static long number(Map<String,Object> j,String k){Object o=j.get(k);require(o instanceof Long);return(Long)o;}
 private static int count(Map<String,Object> j,String k){long n=number(j,k);require(n>=0&&n<=100000);return(int)n;}
 private static boolean validHash(String h){return h!=null&&h.matches("[0-9a-f]{64}");}
 private static Set<String> hashes(Map<String,Object> j,String k,int limit){Object o=j.get(k);require(o instanceof List<?>);List<?> list=(List<?>)o;require(list.size()<=limit);Set<String> hashes=new HashSet<>();for(Object e:list){require(e instanceof String&&validHash((String)e)&&hashes.add((String)e));}return hashes;}
 /** Deliberately small JSON grammar: one object, integer/string/string-array values only.
  * Duplicate keys, extra depth, noninteger numbers, trailing data and invalid escapes fail closed. */
 private static final class Json {
  final String s;int p;Json(String s){this.s=s;}
  void ws(){while(p<s.length()&&" \t\r\n".indexOf(s.charAt(p))>=0)p++;}
  char next(){require(p<s.length());return s.charAt(p++);}
  boolean eat(char c){ws();if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;}
  Map<String,Object> object(){require(eat('{'));Map<String,Object> map=new HashMap<>();if(!eat('}')){do{ws();String key=str();require(key.length()<=64&&!map.containsKey(key)&&map.size()<32);require(eat(':'));map.put(key,value());}while(eat(','));require(eat('}'));}ws();require(p==s.length());return map;}
  Object value(){ws();require(p<s.length());char c=s.charAt(p);if(c=='"')return str();if(c=='['){p++;List<String> list=new ArrayList<>();if(!eat(']')){do{ws();require(list.size()<512);list.add(str());}while(eat(','));require(eat(']'));}return list;}int start=p;if(c=='-')p++;require(p<s.length());if(s.charAt(p)=='0')p++;else{require(s.charAt(p)>='1'&&s.charAt(p)<='9');while(p<s.length()&&s.charAt(p)>='0'&&s.charAt(p)<='9')p++;}require(p-start<=20);return Long.parseLong(s.substring(start,p));}
  String str(){require(next()=='"');StringBuilder b=new StringBuilder();while(true){char c=next();if(c=='"')return b.toString();require(c>=32);if(c=='\\'){char e=next();c=switch(e){case '"','\\','/'->e;case 'b'->'\b';case 'f'->'\f';case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case 'u'->{require(p+4<=s.length());String h=s.substring(p,p+4);require(h.matches("[0-9a-fA-F]{4}"));p+=4;yield(char)Integer.parseInt(h,16);}default->throw new IllegalArgumentException();};}require(!Character.isSurrogate(c)&&b.length()<2048);b.append(c);}}
 }
 private OwnStateSchemaSummary(){}
}
