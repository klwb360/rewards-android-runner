package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.*;
public class PartitionCrossingDiagnosticTest {
 static void ok(boolean b,String m){assertTrue(m,b);}
 static TitleFamily one(int x,int w,double score){List<Row> r=MoreScopedPartitionTest.rows();r.set(2,MoreScopedPartitionTest.r("SECRET",score,x,305,w,39));return MoreScopedPartitionTest.run(r);}
 static void expect(int x,int w,PartitionBoundary boundary,String name){TitleFamily f=one(x,w,.33);FailureDiagnostic d=f.failure();ok(f.status()==FamilyStatus.HOLD&&d!=null&&d.gate()==FailureGate.PARTITION_CROSSING&&d.unclassifiedBoundary1()==boundary,name);ok(d.unclassifiedBox1().equals(new Box(x,305,x+w,344))&&d.unclassifiedScore1()==.33&&d.reportedUnclassified()==1&&!d.unclassifiedTruncated()&&d.crossingCoordinateSpace()==CrossingCoordinateSpace.LOCATOR_INPUT,"exact native-shaped box and score "+name);}
 @Test public void crossingGeometryOnlyAndLocalEvidenceAreBounded(){
  expect(60,44,PartitionBoundary.U_TO_GUTTER,"U edge crossing");expect(104,6,PartitionBoundary.GUTTER_ONLY,"gutter only");expect(105,10,PartitionBoundary.GUTTER_TO_C,"gutter to C");expect(95,20,PartitionBoundary.U_TO_C,"U to C");
  List<Row> r=MoreScopedPartitionTest.rows();r.add(MoreScopedPartitionTest.r("SECRET",.99,380,370,10,10));TitleFamily f=MoreScopedPartitionTest.run(r);FailureDiagnostic d=f.failure();ok(d.gate()==FailureGate.PARTITION_CROSSING&&d.unclassifiedBoundary1()==PartitionBoundary.C_TO_R,"C/R crossing");ok(d.unclassifiedScore1()==.99&&d.lowConfidenceRows()==1,"unknown high score distinct from other low row");
  r=MoreScopedPartitionTest.rows();r.add(MoreScopedPartitionTest.r("SECRET",.55,100,390,290,15));d=MoreScopedPartitionTest.run(r).failure();ok(d.unclassifiedBoundary1()==PartitionBoundary.MULTIPLE_BOUNDARIES,"multiple boundary crossing");
  r=MoreScopedPartitionTest.rows();r.add(MoreScopedPartitionTest.r("SECRET1",.31,104,255,6,10));r.add(MoreScopedPartitionTest.r("SECRET2",.32,104,275,6,10));r.add(MoreScopedPartitionTest.r("SECRET3",.33,104,350,6,10));d=MoreScopedPartitionTest.run(r).failure();ok(d.unclassifiedRows()==3&&d.reportedUnclassified()==2&&d.unclassifiedTruncated(),"bounded two boxes plus total/truncation");ok(d.unclassifiedBox1().equals(new Box(104,255,110,265))&&d.unclassifiedBox2().equals(new Box(104,275,110,285))&&d.unclassifiedScore1()==.31&&d.unclassifiedScore2()==.32,"raw boxes and scores in batch order");ok(!d.toString().contains("SECRET"),"diagnostic never contains OCR text");
  f=one(104,6,.33);d=f.failure();ok(d.localRows()==6&&d.localRowDiagnostics().size()==6&&!d.localRowsTruncated(),"all six local geometry rows reported");
  ok(d.localCoordinateSpace()==CrossingCoordinateSpace.LOCATOR_INPUT&&Math.abs(d.uBoundary()-103.085)<1e-9&&Math.abs(d.cBoundary()-112.3)<1e-9&&Math.abs(d.rBoundary()-388.75)<1e-9,"original coordinate space and three exact boundaries");
  ok(d.localRowDiagnostics().stream().anyMatch(v->v.box().equals(new Box(104,305,110,344))&&v.confidence()==.33&&v.region()==RowRegion.UNCLASSIFIED),"unclassified row geometry and original score retained");
  ok(d.uniquePointsValue()==5&&d.uniquePointsScore()==.999242,"unique points candidate preserves score without implying acceptance");
  r=MoreScopedPartitionTest.rows();r.add(MoreScopedPartitionTest.r("+5",.91,465,380,15,15));d=MoreScopedPartitionTest.run(r).failure();ok(d.gate()==FailureGate.POINTS_COUNT&&d.uniquePointsValue()==-1&&d.uniquePointsScore()==-1&&d.numberBox()==null,"nonunique numeric candidates have no invented value or score");
  r=MoreScopedPartitionTest.rows();r.add(MoreScopedPartitionTest.r("SECRET1",.31,104,255,6,10));r.add(MoreScopedPartitionTest.r("SECRET2",.32,104,275,6,10));r.add(MoreScopedPartitionTest.r("SECRET3",.33,104,350,6,10));d=MoreScopedPartitionTest.run(r).failure();
  ok(d.localRows()==9&&d.localRowDiagnostics().size()==8&&d.localRowsTruncated(),"all-row list capped at eight with total and truncation");
  try{d.localRowDiagnostics().clear();throw new AssertionError("mutable evidence");}catch(UnsupportedOperationException expected){System.out.println("PASS immutable local row evidence");}
  for(var c:FailureDiagnostic.class.getRecordComponents()){Class<?> t=c.getType();ok(t.isPrimitive()||t.isEnum()||t==Box.class||(t==List.class&&c.getName().equals("localRowDiagnostics")),"safe field type "+c.getName());}
  for(var c:LocalRowDiagnostic.class.getRecordComponents()){Class<?> t=c.getType();ok(t.isPrimitive()||t.isEnum()||t==Box.class,"safe local field "+c.getName());}
 }
}
