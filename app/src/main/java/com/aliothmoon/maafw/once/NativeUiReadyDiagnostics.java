package com.aliothmoon.maafw.once;
/** Exact fixed whole-field diagnostics only. Neither idle nor class/label counts prove page readiness. */
final class NativeUiReadyDiagnostics {
 enum Idle { NOT_REQUESTED,IDLE_OBSERVED,TIMEOUT,FAILED }
 enum Label { LOADING_EN,LOADING_DOTS_EN,SIGN_IN_EN,ERROR_EN,LOADING_ZH,SIGN_IN_ZH,ERROR_ZH }
 record Result(Idle idle,int webViews,int loading,int signIn,int error,int labelMask,int searchTitles,int readingTitles,int fractionShapes){
  Result{if(idle==null||!count(webViews)||!count(loading)||!count(signIn)||!count(error)||!count(searchTitles)||!count(readingTitles)||!count(fractionShapes)||labelMask<0||labelMask>127||(loading>0)!=((labelMask&19)!=0)||(signIn>0)!=((labelMask&36)!=0)||(error>0)!=((labelMask&72)!=0))throw new IllegalArgumentException("ready diagnostics");}
  boolean blocked(){return signIn>0||error>0;}
 }
 private static boolean count(int value){return value>=0&&value<=512;}
 static final class Accumulator {
  private int nodes,webViews,loading,signIn,error,mask,searchTitles,readingTitles,fractionShapes;
  void node(CharSequence className,CharSequence text,CharSequence description){
   if(++nodes>512)throw new IllegalArgumentException("node bound");
   if(exact(className,"android.webkit.WebView"))webViews++;
   int present=bit(label(text))|bit(label(description));mask|=present;
   if((present&19)!=0)loading++;if((present&36)!=0)signIn++;if((present&72)!=0)error++;
   if(searchTitle(text)||searchTitle(description))searchTitles++;
   if(readingTitle(text)||readingTitle(description))readingTitles++;
   if(fractionShape(text)||fractionShape(description))fractionShapes++;
  }
  Result result(Idle idle){return new Result(idle,webViews,loading,signIn,error,mask,searchTitles,readingTitles,fractionShapes);}
  boolean blocked(){return signIn>0||error>0;}
 }
 private static int bit(Label label){return label==null?0:1<<label.ordinal();}
 static Label label(CharSequence value){
  if(exact(value,"Loading"))return Label.LOADING_EN;
  if(exact(value,"Loading..."))return Label.LOADING_DOTS_EN;
  if(exact(value,"Sign in"))return Label.SIGN_IN_EN;
  if(exact(value,"Something went wrong"))return Label.ERROR_EN;
  if(exact(value,"正在加载"))return Label.LOADING_ZH;
  if(exact(value,"登录"))return Label.SIGN_IN_ZH;
  if(exact(value,"出错了"))return Label.ERROR_ZH;
  return null;
 }
 static boolean searchTitle(CharSequence v){return exact(v,"Search to earn")||exact(v,"搜索以赚取")||exact(v,"Search points")||exact(v,"搜索积分");}
 static boolean readingTitle(CharSequence v){return exact(v,"Read to earn")||exact(v,"阅读以赚取");}
 static boolean fractionShape(CharSequence value){
  if(value==null||value.length()<3||value.length()>32)return false;
  int i=0,n=value.length(),digits=0;while(i<n&&value.charAt(i)>='0'&&value.charAt(i)<='9'){i++;digits++;}if(digits==0||digits>10)return false;
  while(i<n&&value.charAt(i)==' ')i++;if(i>=n||value.charAt(i++)!='/')return false;while(i<n&&value.charAt(i)==' ')i++;
  digits=0;while(i<n&&value.charAt(i)>='0'&&value.charAt(i)<='9'){i++;digits++;}return i==n&&digits>0&&digits<=10;
 }
 private static boolean exact(CharSequence value,String wanted){return value!=null&&value.length()==wanted.length()&&wanted.contentEquals(value);}
 private NativeUiReadyDiagnostics(){}
}
