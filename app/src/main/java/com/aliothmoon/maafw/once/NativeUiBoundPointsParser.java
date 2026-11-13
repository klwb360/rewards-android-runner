package com.aliothmoon.maafw.once;
import java.util.*;
import java.util.regex.*;
/** Explicit points grammar bound to exact original resource/parent structure; never infers middle-text meaning. */
final class NativeUiBoundPointsParser {
 record Node(int index,int parentIndex,boolean visible,boolean packageMatches,String resourceId,String text,String description,int left,int top,int right,int bottom){}
 private static final String[] IDS={"homePageWrapper","dailyTaskPanel","dailyActivities","search","read"};
 private static final Pattern SEARCH=summary("搜索以赚取"),READ=summary("阅读以赚取");
 private static Pattern summary(String title){return Pattern.compile("\\A"+title+", ([\\p{L} ]{1,128}), 已赚取 (\\S+) 积分\\(需要 (\\S+) 积分\\)\\z");}
 static NativeUiStructureParser.Result parse(List<Node> nodes){
  Map<Integer,Node> byIndex=new HashMap<>();Node[] chain=new Node[5];
  if(!shape(nodes,byIndex)||!chain(nodes,chain))return bothInvalid();
  if(wrongTaskSummary(nodes,byIndex,chain[3],"阅读以赚取")||wrongTaskSummary(nodes,byIndex,chain[4],"搜索以赚取"))return bothInvalid();
  return new NativeUiStructureParser.Result(scan(nodes,byIndex,chain[3],true),scan(nodes,byIndex,chain[4],false));
 }
 private static boolean shape(List<Node> nodes,Map<Integer,Node> map){
  if(nodes==null||nodes.isEmpty()||nodes.size()>512)return false;
  Map<Integer,Integer> depths=new HashMap<>();Node root=null;int chars=0;
  for(Node n:nodes){
   if(n==null||n.index()<0||map.containsKey(n.index())||!n.packageMatches())return false;
   int depth;
   if(n.parentIndex()==-1){if(root!=null)return false;root=n;depth=0;if(!n.visible()||!bounds(n))return false;}
   else{Integer parentDepth=depths.get(n.parentIndex());if(parentDepth==null)return false;depth=parentDepth+1;if(depth>32)return false;}
   if(n.resourceId()!=null&&n.resourceId().length()>2048)return false;
   for(String field:new String[]{n.text(),n.description()})if(field!=null){if(field.length()>2048)return false;chars+=field.length();if(chars>32768)return false;}
   if(n.visible()&&(!bounds(n)||root==null||!contains(root,n)))return false;
   map.put(n.index(),n);depths.put(n.index(),depth);
  }
  return root!=null;
 }
 private static boolean chain(List<Node> nodes,Node[] c){
  for(Node n:nodes)for(int i=0;i<IDS.length;i++)if(IDS[i].equals(n.resourceId())){if(c[i]!=null)return false;c[i]=n;}
  for(Node n:c)if(n==null||!n.visible()||!n.packageMatches()||!bounds(n))return false;
  return direct(c[0],c[1])&&direct(c[1],c[2])&&direct(c[2],c[3])&&direct(c[2],c[4]);
 }
 private static boolean direct(Node parent,Node child){return child.parentIndex()==parent.index()&&contains(parent,child);}
 private static boolean bounds(Node n){return n.left()>=0&&n.top()>=0&&n.right()>n.left()&&n.bottom()>n.top()&&n.right()<=8192&&n.bottom()<=8192;}
 private static boolean contains(Node parent,Node child){return child.left()>=parent.left()&&child.top()>=parent.top()&&child.right()<=parent.right()&&child.bottom()<=parent.bottom();}
 private static boolean descendant(Node n,Node ancestor,Map<Integer,Node> map){
  int parent=n.parentIndex();for(int i=0;i<32&&parent>=0;i++){if(parent==ancestor.index())return true;Node p=map.get(parent);if(p==null)return false;parent=p.parentIndex();}return false;
 }
 /** Any task-bearing summary under the other exact card is a whole-observation ownership conflict. */
 private static boolean wrongTaskSummary(List<Node> nodes,Map<Integer,Node> map,Node card,String other){
  for(Node n:nodes){if(n.index()!=card.index()&&!descendant(n,card,map))continue;
   for(String field:new String[]{n.text(),n.description()})if(field!=null){int start=leadingIgnored(field);if(field.startsWith(other,start)&&field.length()>start+other.length())return true;}
  }return false;
 }
 private static NativeUiStructureParser.TaskResult scan(List<Node> nodes,Map<Integer,Node> map,Node card,boolean search){
  int count=0,current=0,target=0;boolean invalid=false,eligible=false;
  String own=search?"搜索以赚取":"阅读以赚取",other=search?"阅读以赚取":"搜索以赚取";
  for(Node n:nodes){
   if(n.index()!=card.index()&&!descendant(n,card,map))continue;
   String[] fields={n.text(),n.description()};
   for(int i=0;i<fields.length;i++){
    String field=fields[i];if(field==null||i==1&&field.equals(fields[0]))continue;
    int start=leadingIgnored(field);
    if(start>0&&(field.startsWith(own,start)||field.startsWith(other,start))){invalid=true;continue;}
    if(field.equals(other))continue;
    if(field.startsWith(other)){invalid=true;continue;}
    if(!field.startsWith(own)||field.equals(own))continue;
    Matcher m=(search?SEARCH:READ).matcher(field);
    if(!m.matches()||!middle(m.group(1))){invalid=true;continue;}
    count++;Integer value=number(m.group(2)),total=number(m.group(3));
    if(value==null||total==null||total==0||value>total){invalid=true;continue;}
    if(n.visible()&&(!contains(card,n)||!bounds(n))){invalid=true;continue;}
    if(n.visible()&&n.parentIndex()==card.index()){eligible=true;current=value;target=total;}
   }
  }
  if(invalid)return result(NativeUiStructureParser.Status.INVALID,0,0,count);
  if(count>1)return result(NativeUiStructureParser.Status.AMBIGUOUS,0,0,count);
  if(count==0||!eligible)return result(NativeUiStructureParser.Status.UNKNOWN,0,0,count);
  return result(NativeUiStructureParser.Status.KNOWN,current,target,1);
 }
 /** Classification only: suspicious title prefixes never become acceptable normalized evidence. */
 private static int leadingIgnored(String field){
  int offset=0;while(offset<field.length()){int c=field.codePointAt(offset);if(!Character.isWhitespace(c)&&!Character.isSpaceChar(c)&&!Character.isISOControl(c)&&Character.getType(c)!=Character.FORMAT&&c!=0xfeff)break;offset+=Character.charCount(c);}return offset;
 }
 private static boolean middle(String text){
  if(text.length()<1||text.length()>128||text.contains("已赚取")||text.contains("需要"))return false;
  String numerals="零〇一二三四五六七八九十百千万两壱壹贰貳叁參肆伍陆陸柒捌玖拾佰仟萬億亿廿卅卌兩";boolean letter=false;
  for(int offset=0;offset<text.length();){int c=text.codePointAt(offset);offset+=Character.charCount(c);if(c!=' '&&!Character.isLetter(c)||numerals.indexOf(c)>=0)return false;if(Character.isLetter(c))letter=true;}
  return letter;
 }
 private static Integer number(String token){if(token.length()>5||!token.matches("[0-9]+"))return null;try{int n=Integer.parseInt(token);return n<=10000?n:null;}catch(NumberFormatException rejected){return null;}}
 private static NativeUiStructureParser.TaskResult result(NativeUiStructureParser.Status status,int n,int d,int count){return new NativeUiStructureParser.TaskResult(status,n,d,count,status==NativeUiStructureParser.Status.KNOWN?NativeUiStructureParser.Unit.EXPLICIT_TEXT_POINTS:NativeUiStructureParser.Unit.UNESTABLISHED);}
 private static NativeUiStructureParser.Result bothInvalid(){var r=result(NativeUiStructureParser.Status.INVALID,0,0,0);return new NativeUiStructureParser.Result(r,r);}
 private NativeUiBoundPointsParser(){}
}
