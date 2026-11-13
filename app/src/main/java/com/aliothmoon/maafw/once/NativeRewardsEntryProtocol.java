package com.aliothmoon.maafw.once;
/** Fixed scalar protocol only. No UI field or node identifier is serialized. */
final class NativeRewardsEntryProtocol {
 static final int MAX_LINE=512;
 static final String HELPER="com.aliothmoon.maafw.once.NativeRewardsEntryHelper";
 static final String MAIN="com.microsoft.bing/com.microsoft.sapphire.app.main.MainSapphireActivity";
 static final String OWN="com.aliothmoon.maafw.productrewardsworking.capturereview/com.aliothmoon.maafw.once.DormantCaptureConfirmationActivity";
 enum Reason { OK,INVALID_ARGUMENT,NOT_ROOT,REFLECTION_UNAVAILABLE,CONNECT_FAILED,WINDOW_UNAVAILABLE,TREE_FAILED,TREE_OVERFLOW,ENTRY_NOT_UNIQUE,SOURCE_REJECTED,COMMIT_REJECTED,TIMEOUT,ACTION_FALSE,CLEANUP_UNKNOWN }
 enum Cleanup { CONFIRMED,UNKNOWN,NOT_STARTED }
 record Binding(String nonce,long deadline,int task,int width,int height,int rotation,int bingUid,int systemUiUid,int ownUid,int ownTask){
  Binding{if(!nonceValid(nonce)||deadline<1||task<1||task>1000000||width<1||width>8192||height<1||height>8192||rotation<0||rotation>3||bingUid<10000||systemUiUid<10000||ownUid<10000||ownTask<1||ownTask>1000000||task==ownTask)throw new IllegalArgumentException("binding");}
  String[] helperArgs(){return new String[]{nonce,Long.toString(deadline),Integer.toString(task),Integer.toString(width),Integer.toString(height),Integer.toString(rotation),Integer.toString(bingUid),Integer.toString(systemUiUid),Integer.toString(ownUid),Integer.toString(ownTask)};}
  String[] helperArgs(long end){return new Binding(nonce,end,task,width,height,rotation,bingUid,systemUiUid,ownUid,ownTask).helperArgs();}
 }
 record Prepared(String nonce,int task,int display,int windowId,long prepared,int nodeCount,int candidateIndex){
  Prepared{if(!nonceValid(nonce)||task<1||display!=0||windowId<0||prepared<0||nodeCount<1||nodeCount>512||candidateIndex<0||candidateIndex>=nodeCount)throw new IllegalArgumentException("prepared");}
 }
 record Result(String nonce,int task,Reason reason,Cleanup cleanup,boolean actionAttempted,boolean actionReturnedTrue,long prepared,long actionFinished,long finished){
  Result{if(!nonceValid(nonce)||task<1||reason==null||cleanup==null||prepared< -1||actionFinished< -1||finished<0||prepared>finished||actionFinished>finished||actionReturnedTrue&&!actionAttempted||actionAttempted&&(prepared<0||actionFinished<prepared)||!actionAttempted&&actionFinished!=-1||reason==Reason.OK&&(!actionReturnedTrue||cleanup!=Cleanup.CONFIRMED))throw new IllegalArgumentException("result");}
 }
 static boolean nonceValid(String n){return n!=null&&n.matches("[a-f0-9]{32}");}
 static String commit(String nonce){if(!nonceValid(nonce))throw new IllegalArgumentException("nonce");return "ERC1|"+nonce+"\n";}
 static boolean validCommit(byte[] bytes,int length,String nonce){if(bytes==null||length<0||length>bytes.length||!nonceValid(nonce))return false;byte[] exact=commit(nonce).getBytes(java.nio.charset.StandardCharsets.US_ASCII);if(length!=exact.length)return false;for(int i=0;i<length;i++)if(bytes[i]!=exact[i])return false;return true;}
 static String encode(Prepared p){return "ERP1|"+p.nonce+"|MAIN|"+p.task+"|"+p.display+"|"+p.windowId+"|"+p.prepared+"|"+p.nodeCount+"|"+p.candidateIndex;}
 static String encodeTemplatePrepared(Prepared p){return "ERP2|"+p.nonce+"|TEMPLATE|"+p.task+"|"+p.display+"|"+p.windowId+"|"+p.prepared+"|"+p.nodeCount+"|"+p.candidateIndex;}
 static String encode(Result r){return "ERR1|"+r.nonce+"|"+r.task+"|"+r.reason+"|"+r.cleanup+"|"+(r.actionAttempted?1:0)+"|"+(r.actionReturnedTrue?1:0)+"|"+r.prepared+"|"+r.actionFinished+"|"+r.finished;}
 private static String[] fields(String line,int n){if(line==null||line.length()>MAX_LINE||!line.matches("[A-Za-z0-9_|-]+"))throw new IllegalArgumentException("frame");String[] a=line.split("\\|",-1);if(a.length!=n)throw new IllegalArgumentException("fields");return a;}
 private static int integer(String s){return Math.toIntExact(number(s));}
 private static long number(String s){if(!s.matches("-1|0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException("number");return Long.parseLong(s);}
 private static boolean bit(String s){if(!s.equals("0")&&!s.equals("1"))throw new IllegalArgumentException("bit");return s.equals("1");}
 static Prepared parsePrepared(String line,String nonce,int task,long dispatch,long deadline,long now){String[] a=fields(line,9);if(!a[0].equals("ERP1")||!a[1].equals(nonce)||!a[2].equals("MAIN"))throw new IllegalArgumentException("identity");Prepared p=new Prepared(a[1],integer(a[3]),integer(a[4]),integer(a[5]),number(a[6]),integer(a[7]),integer(a[8]));if(p.task!=task||p.prepared<dispatch||p.prepared>now||now>=deadline)throw new IllegalArgumentException("time");return p;}
 static Prepared parseTemplatePrepared(String line,String nonce,int task,long dispatch,long deadline,long now){String[] a=fields(line,9);if(!a[0].equals("ERP2")||!a[1].equals(nonce)||!a[2].equals("TEMPLATE"))throw new IllegalArgumentException("identity");Prepared p=new Prepared(a[1],integer(a[3]),integer(a[4]),integer(a[5]),number(a[6]),integer(a[7]),integer(a[8]));if(p.task!=task||p.prepared<dispatch||p.prepared>now||now>=deadline)throw new IllegalArgumentException("time");return p;}
 static Result parseResult(String line,String nonce,int task,long prepared,long deadline,long now){String[] a=fields(line,10);if(!a[0].equals("ERR1")||!a[1].equals(nonce))throw new IllegalArgumentException("identity");Result r=new Result(a[1],integer(a[2]),Reason.valueOf(a[3]),Cleanup.valueOf(a[4]),bit(a[5]),bit(a[6]),number(a[7]),number(a[8]),number(a[9]));if(r.task!=task||r.prepared!=prepared||r.finished>now||r.finished>=deadline||now>=deadline)throw new IllegalArgumentException("time");return r;}
 static boolean eligible(CharSequence pkg,CharSequence id,CharSequence description,boolean visible,boolean enabled,boolean clickable){return visible&&enabled&&clickable&&pkg!=null&&"com.microsoft.bing".contentEquals(pkg)&&id!=null&&"com.microsoft.bing:id/glance_card_container".contentEquals(id)&&description!=null&&"Rewards".contentEquals(description);}
 private NativeRewardsEntryProtocol(){}
}
