package com.aliothmoon.maafw.once;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** Two source-backed additional complete fields; original nodes/fields are never joined or rewritten. */
final class NativeUiStructureV50Parser {
 private static final String SEARCH_DESCRIPTION="Search in Bing everyday to earn Rewards points";
 private static final String READ_DESCRIPTION="Read news everyday to earn Rewards points";
 private static final Pattern SEARCH=Pattern.compile("\\ASearch to earn, "+SEARCH_DESCRIPTION+", (\\S+) out of (\\S+) points earned\\z");
 private static final Pattern READ=Pattern.compile("\\ARead to earn, "+READ_DESCRIPTION+", (\\S+) out of (\\S+) points earned\\z");
 static NativeUiStructureParser.Result parse(List<NativeUiStructureParser.Node> nodes){
  var old=NativeUiStructureParser.parse(nodes);
  // Every malformed shape is INVALID for both tasks. Existing semantic invalidity also cannot be repaired here.
  if(old.search().status()==NativeUiStructureParser.Status.INVALID&&old.reading().status()==NativeUiStructureParser.Status.INVALID)return old;
  return new NativeUiStructureParser.Result(scan(nodes,true,old.search()),scan(nodes,false,old.reading()));
 }
 private static NativeUiStructureParser.TaskResult scan(List<NativeUiStructureParser.Node> nodes,boolean search,NativeUiStructureParser.TaskResult old){
  int count=old.matchCount(),current=old.current(),target=old.target();boolean invalid=old.status()==NativeUiStructureParser.Status.INVALID;
  for(var node:nodes){
   if(!node.visible()||!node.packageMatches())continue;
   String[] fields={node.text(),node.description()};
   for(int i=0;i<fields.length;i++){
    String field=fields[i];if(field==null||i==1&&field.equals(fields[0]))continue;
    Matcher match=(search?SEARCH:READ).matcher(field);
    if(!match.matches()){if(newFamily(field,search))invalid=true;continue;}
    count++;Integer n=number(match.group(1)),d=number(match.group(2));
    if(n==null||d==null||d==0||n>d)invalid=true;else{current=n;target=d;}
   }
  }
  if(invalid)return result(NativeUiStructureParser.Status.INVALID,0,0,count);
  if(count>1)return result(NativeUiStructureParser.Status.AMBIGUOUS,0,0,count);
  if(count==0)return result(NativeUiStructureParser.Status.UNKNOWN,0,0,0);
  return result(NativeUiStructureParser.Status.KNOWN,current,target,1);
 }
 private static boolean newFamily(String field,boolean search){
  String prefix=(search?"Search":"Read")+" to earn, ";
  if(!field.startsWith(prefix)||field.startsWith(prefix+","))return false; // Old empty-description grammar stays with its parser.
  String suffix=field.substring(prefix.length());
  return suffix.startsWith("Search in Bing")||suffix.startsWith("Read news")
      ||suffix.contains(" out of ")&&suffix.endsWith(" points earned");
 }
 private static Integer number(String value){
  if(value.length()>5||!value.matches("[0-9]+"))return null;
  try{int n=Integer.parseInt(value);return n<=10000?n:null;}catch(NumberFormatException rejected){return null;}
 }
 private static NativeUiStructureParser.TaskResult result(NativeUiStructureParser.Status status,int current,int target,int count){
  return new NativeUiStructureParser.TaskResult(status,current,target,count,status==NativeUiStructureParser.Status.KNOWN?NativeUiStructureParser.Unit.EXPLICIT_TEXT_POINTS:NativeUiStructureParser.Unit.UNESTABLISHED);
 }
 private NativeUiStructureV50Parser(){}
}
