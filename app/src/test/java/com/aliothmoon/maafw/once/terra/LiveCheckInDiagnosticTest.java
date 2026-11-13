package com.aliothmoon.maafw.once;
import java.util.*;
public final class LiveCheckInDiagnosticTest {
 static int W=552,H=201;
 static void fill(byte[] p,int l,int t,int r,int b,int v){for(int y=t;y<b;y++)Arrays.fill(p,y*W+l,y*W+r,(byte)v);}
 static void disk(byte[] p,int x,int y,int r,int v){for(int yy=y-r;yy<=y+r;yy++)for(int xx=x-r;xx<=x+r;xx++)if((xx-x)*(xx-x)+(yy-y)*(yy-y)<=r*r)p[yy*W+xx]=(byte)v;}
 static byte[] pixels(){byte[] p=new byte[W*H];Arrays.fill(p,(byte)235);fill(p,10,17,536,193,250);disk(p,272,115,27,190);disk(p,272,115,25,250);disk(p,272,115,17,230);fill(p,264,109,268,122,90);fill(p,274,109,280,122,90);return p;}
 static FrameStructureEvidence.Line l(String t,int x,int y,int w,int h,double c){return new FrameStructureEvidence.Line(t,x,y,w,h,c);}
 static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  var title=l("签入",30,42,49,23,.99);var r=new ArrayList<FrameStructureEvidence.Line>();r.add(title);
  String missing=LiveCheckInObservation.observe(pixels(),W,H,r,()->false);
  require(missing.contains("visual=BAD_NUMBER")&&missing.contains("diagnosticRing=245,88,300,143")&&missing.contains("diagnosticSafe=256,99,289,132")&&missing.contains("diagnosticIntersectingRows=0"),"missing row diagnosis");
  r.add(l("10",264,109,16,13,.95));String normal=LiveCheckInObservation.observe(pixels(),W,H,r,()->false);
  require(normal.contains("visual=CANDIDATE")&&normal.contains("diagnosticRowClass=EXACT_ASCII_10 rowBox=264,109,280,122 rowScore=0.95 insideSafe=true"),"valid row evidence");
  r.add(l("private text",250,110,10,12,.31));String conflict=LiveCheckInObservation.observe(pixels(),W,H,r,()->false);
  require(conflict.contains("visual=BAD_OCR")&&conflict.contains("diagnosticRowClass=OTHER rowBox=250,110,260,122 rowScore=0.31 insideSafe=false")&&conflict.contains("diagnosticIntersectingRows=2"),"conflict remains visible");
  require(!conflict.contains("private text")&&!conflict.contains("签入"),"no OCR text emitted");
  System.out.println("PASS 4 diagnostic assertions (synthetic data; no native/device test)");
 }
}
