package com.aliothmoon.maafw.once;
import java.util.*;
/** Bounded diagnostic enums/numbers only. No action or counter authority. */
final class NativeEntryDiagnosticProtocol {
 static final int MAX_LINE=8192,MAX_CANDIDATES=16;
 enum Reason { OK,INVALID_ARGUMENT,NOT_ROOT,REFLECTION_UNAVAILABLE,WINDOW_UNAVAILABLE,TREE_FAILED,TREE_OVERFLOW,SOURCE_REJECTED,TIMEOUT,CLEANUP_UNKNOWN }
 enum Cleanup { CONFIRMED,UNKNOWN,NOT_STARTED }
 enum Role { CANDIDATE,ANCESTOR_ONLY,BOTH }
 enum Id { GLANCE_CARD_CONTAINER,OTHER,NONE }
 enum Label { REWARDS_EN,OTHER,NONE }
 record Candidate(int index,int parent,int depth,int left,int top,int right,int bottom,boolean visible,boolean enabled,boolean clickable,Id id,Label text,Label description,Role role){
  Candidate {if(index<0||index>=512||parent< -1||parent>=index||depth<0||depth>32||id==null||text==null||description==null||role==null||left< -32768||top< -32768||right>32768||bottom>32768||right<left||bottom<top)throw new IllegalArgumentException("candidate");}
 }
 record Projection(int nodes,int characters,int idMatches,int textMatches,int descriptionMatches,int intersection,int visible,int enabled,int clickable,int strictMatches,int candidateTotal,int outputTotal,List<Candidate> candidates){
  Projection {candidates=List.copyOf(candidates);if(nodes<1||nodes>512||characters<0||characters>32768||candidateTotal<0||candidateTotal>nodes||outputTotal<candidateTotal||outputTotal>nodes||candidates.size()!=Math.min(outputTotal,16))throw new IllegalArgumentException("projection");for(int n:new int[]{idMatches,textMatches,descriptionMatches,intersection,visible,enabled,clickable,strictMatches})if(n<0||n>nodes)throw new IllegalArgumentException("counts");if(intersection>idMatches||intersection>descriptionMatches||visible>intersection||enabled>intersection||clickable>intersection||strictMatches>Math.min(visible,Math.min(enabled,clickable)))throw new IllegalArgumentException("funnel");int last=-1;for(var c:candidates){if(c.index()<=last||c.index()>=nodes||c.role()!=Role.ANCESTOR_ONLY&&!(c.id()==Id.GLANCE_CARD_CONTAINER||c.text()==Label.REWARDS_EN||c.description()==Label.REWARDS_EN))throw new IllegalArgumentException("candidate order");last=c.index();}}
  boolean truncated(){return outputTotal>candidates.size();}
 }
 static final class Accumulator {
  int nodes,chars,ids,texts,descs,intersection,visible,enabled,clickable,strict,total;final List<Candidate> all=new ArrayList<>();
  void add(int index,int parent,int depth,int left,int top,int right,int bottom,boolean v,boolean e,boolean c,CharSequence resource,CharSequence text,CharSequence desc){
   if(index!=nodes||nodes>=512)throw new IllegalArgumentException("node order");int tl=text==null?0:text.length(),dl=desc==null?0:desc.length();if(tl>2048||dl>2048||chars+tl+dl>32768)throw new IllegalArgumentException("text budget");chars+=tl+dl;nodes++;
   Id id=exact(resource,"com.microsoft.bing:id/glance_card_container")?Id.GLANCE_CARD_CONTAINER:empty(resource)?Id.NONE:Id.OTHER;Label t=label(text),d=label(desc);boolean match=id==Id.GLANCE_CARD_CONTAINER&&d==Label.REWARDS_EN;
   if(id==Id.GLANCE_CARD_CONTAINER)ids++;if(t==Label.REWARDS_EN)texts++;if(d==Label.REWARDS_EN)descs++;if(match){intersection++;if(v)visible++;if(e)enabled++;if(c)clickable++;if(v&&e&&c)strict++;}
   boolean candidate=id==Id.GLANCE_CARD_CONTAINER||t==Label.REWARDS_EN||d==Label.REWARDS_EN;if(candidate)total++;
   all.add(new Candidate(index,parent,depth,left,top,right,bottom,v,e,c,id,t,d,candidate?Role.CANDIDATE:Role.ANCESTOR_ONLY));
  }
  Projection finish(){
   boolean[] include=new boolean[nodes],ancestor=new boolean[nodes],selectedIndex=new boolean[nodes];
   for(Candidate c:all)if(c.role()==Role.CANDIDATE){include[c.index()]=true;int parent=c.parent();for(int level=0;level<3&&parent>=0;level++){include[parent]=true;ancestor[parent]=true;parent=all.get(parent).parent();}}
   int kept=0;for(int priority=0;priority<2;priority++)for(Candidate c:all){boolean label=c.text()==Label.REWARDS_EN||c.description()==Label.REWARDS_EN;if(c.role()!=Role.CANDIDATE||label!=(priority==0))continue;int index=c.index();for(int level=0;level<4&&index>=0;level++){if(!selectedIndex[index]&&kept<16){selectedIndex[index]=true;kept++;}index=all.get(index).parent();}}
   int output=0;List<Candidate> selected=new ArrayList<>();for(Candidate c:all){if(include[c.index()])output++;if(selectedIndex[c.index()])selected.add(new Candidate(c.index(),c.parent(),c.depth(),c.left(),c.top(),c.right(),c.bottom(),c.visible(),c.enabled(),c.clickable(),c.id(),c.text(),c.description(),c.role()==Role.CANDIDATE?(ancestor[c.index()]?Role.BOTH:Role.CANDIDATE):Role.ANCESTOR_ONLY));}
   return new Projection(nodes,chars,ids,texts,descs,intersection,visible,enabled,clickable,strict,total,output,selected);
  }

 }
 static Label label(CharSequence s){return exact(s,"Rewards")?Label.REWARDS_EN:empty(s)?Label.NONE:Label.OTHER;}
 private static boolean empty(CharSequence s){return s==null||s.length()==0;}
 private static boolean exact(CharSequence s,String e){return s!=null&&s.length()==e.length()&&e.contentEquals(s);}
 record Report(String nonce,long started,long acquired,long finished,Reason reason,Cleanup cleanup,int task,int windowId,int displayId,Projection projection){
  Report {if(!NativeRewardsEntryProtocol.nonceValid(nonce)||started<0||acquired<started||finished<acquired||reason==null||cleanup==null||task<1||task>1000000||windowId< -1||displayId< -1||displayId>0||reason==Reason.OK&&(cleanup!=Cleanup.CONFIRMED||projection==null||windowId<0||displayId!=0))throw new IllegalArgumentException("report");}
  boolean qualified(){return reason==Reason.OK&&cleanup==Cleanup.CONFIRMED&&projection!=null;}
  String summary(boolean qualified){if(!qualified||!qualified())return "selectorDiagnostic=UNQUALIFIED candidates=SUPPRESSED actionAuthority=false counterAuthority=false";return "selectorDiagnostic=QUALIFIED source=UI_STRUCTURE actionAuthority=false counterAuthority=false nodes="+projection.nodes()+" characters="+projection.characters()+" idMatches="+projection.idMatches()+" textRewards="+projection.textMatches()+" descriptionRewards="+projection.descriptionMatches()+" idDescriptionIntersection="+projection.intersection()+" intersectionVisible="+projection.visible()+" intersectionEnabled="+projection.enabled()+" intersectionClickable="+projection.clickable()+" strictMatches="+projection.strictMatches()+" candidateTotal="+projection.candidateTotal()+" outputUnionTotal="+projection.outputTotal()+" candidatesTruncated="+projection.truncated()+" candidates="+projection.candidates();}
 }
 static String encode(Report r){StringJoiner j=new StringJoiner("|");for(Object v:new Object[]{"ED1",r.nonce(),r.task(),r.started(),r.acquired(),r.finished(),r.reason(),r.cleanup(),r.windowId(),r.displayId()})j.add(v.toString());Projection p=r.projection();if(p==null){j.add("NONE");}else{for(int n:new int[]{p.nodes(),p.characters(),p.idMatches(),p.textMatches(),p.descriptionMatches(),p.intersection(),p.visible(),p.enabled(),p.clickable(),p.strictMatches(),p.candidateTotal(),p.outputTotal(),p.candidates().size()})j.add(Integer.toString(n));for(Candidate c:p.candidates())j.add(c.index()+","+c.parent()+","+c.depth()+","+c.left()+","+c.top()+","+c.right()+","+c.bottom()+","+(c.visible()?1:0)+","+(c.enabled()?1:0)+","+(c.clickable()?1:0)+","+c.id()+","+c.text()+","+c.description()+","+c.role());}String line=j.toString();if(line.length()>MAX_LINE)throw new IllegalArgumentException("line");return line;}
 static Report parse(String line,String nonce,int task,long dispatch,long deadline,long now){
  if(line==null||line.length()>MAX_LINE||!line.matches("[A-Za-z0-9_|,\\-]+"))throw new IllegalArgumentException("line");String[] a=line.split("\\|",-1);if(a.length<11||!a[0].equals("ED1")||!a[1].equals(nonce))throw new IllegalArgumentException("identity");Projection p=null;
  if(!a[10].equals("NONE")){if(a.length<23)throw new IllegalArgumentException("fields");int count=num(a[22]);if(count<0||count>16||a.length!=23+count)throw new IllegalArgumentException("count");List<Candidate> cs=new ArrayList<>();for(int i=0;i<count;i++){String[] c=a[23+i].split(",",-1);if(c.length!=14)throw new IllegalArgumentException("candidate fields");cs.add(new Candidate(num(c[0]),num(c[1]),num(c[2]),num(c[3]),num(c[4]),num(c[5]),num(c[6]),bit(c[7]),bit(c[8]),bit(c[9]),Id.valueOf(c[10]),Label.valueOf(c[11]),Label.valueOf(c[12]),Role.valueOf(c[13])));}p=new Projection(num(a[10]),num(a[11]),num(a[12]),num(a[13]),num(a[14]),num(a[15]),num(a[16]),num(a[17]),num(a[18]),num(a[19]),num(a[20]),num(a[21]),cs);}else if(a.length!=11)throw new IllegalArgumentException("extra");
  Report r=new Report(a[1],number(a[3]),number(a[4]),number(a[5]),Reason.valueOf(a[6]),Cleanup.valueOf(a[7]),num(a[2]),num(a[8]),num(a[9]),p);if(r.task()!=task||r.started()<dispatch||r.finished()>now||r.finished()>=deadline||now>=deadline)throw new IllegalArgumentException("time");return r;
 }
 private static long number(String s){if(!s.matches("-?(0|[1-9][0-9]{0,18})")||s.equals("-0"))throw new IllegalArgumentException("number");return Long.parseLong(s);}
 private static int num(String s){return Math.toIntExact(number(s));}private static boolean bit(String s){if(!s.equals("0")&&!s.equals("1"))throw new IllegalArgumentException("bit");return s.equals("1");}
 private NativeEntryDiagnosticProtocol(){}
}
