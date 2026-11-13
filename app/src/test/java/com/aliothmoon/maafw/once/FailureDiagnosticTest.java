package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.*;
import java.util.*;
public class FailureDiagnosticTest {
 static void ok(boolean b,String s){assertTrue(s,b);}
 static void gate(TitleFamily f,FailureGate g,String s){ok(f.status()==FamilyStatus.HOLD&&f.failure()!=null&&f.failure().gate()==g,s);}
 @Test public void failureGatesPreserveRulesAndAreTextFree(){List<Row> r=TitlePartsLocatorTest.rows();TitleFamily f=TitlePartsLocatorTest.run(r);
  ok(f.status()==FamilyStatus.OBSERVED&&f.failure()==null,"observed result has no failure");
  r=TitlePartsLocatorTest.rows();r.set(2,TitlePartsLocatorTest.row(TitlePartsLocatorTest.T,r.get(2).box(),.84));f=TitlePartsLocatorTest.run(r);gate(f,FailureGate.TITLE_LOW_CONFIDENCE,"low title gate");ok(f.diagnostics().size()==2&&f.failure().cardIndex()==0,"all title diagnostics survive first failure");
  r=TitlePartsLocatorTest.rows();r.set(3,TitlePartsLocatorTest.row("secret text should not appear",r.get(3).box(),.84));f=TitlePartsLocatorTest.run(r);gate(f,FailureGate.ROW_LOW_CONFIDENCE,"local low gate");ok(f.failure().lowConfidenceRows()==1&&!f.failure().toString().contains("secret"),"safe counts and no raw text");
  r=TitlePartsLocatorTest.rows();r.add(TitlePartsLocatorTest.row("x",new Box(10,260,40,275),.2));f=TitlePartsLocatorTest.run(r);gate(f,FailureGate.ROW_CROSSES_CARD,"crossing checked before low score");ok(f.failure().crossingRows()==1,"crossing counted");
  r=TitlePartsLocatorTest.rows();r.add(TitlePartsLocatorTest.row("x",new Box(100,170,120,180),.95));f=TitlePartsLocatorTest.run(r);gate(f,FailureGate.ROW_OVERLAP,"overlap gate");ok(f.failure().overlappingPairs()>=1,"overlap count");
  r=TitlePartsLocatorTest.rows();r.set(4,TitlePartsLocatorTest.row("+5pts",r.get(4).box(),.95));f=TitlePartsLocatorTest.run(r);gate(f,FailureGate.POINTS_COUNT,"point token count gate");ok(f.failure().pointsCount()==0,"point token count zero");
  r=TitlePartsLocatorTest.rows();r.set(4,TitlePartsLocatorTest.row("+5",new Box(388,218,465,245),.95));f=TitlePartsLocatorTest.run(r);gate(f,FailureGate.POINTS_OUTSIDE_SAFE,"unsafe badge edge gate");ok(f.failure().pointsInsideSafe()==DiagnosticCheck.NO,"safe containment false");
  r=TitlePartsLocatorTest.rows();r.remove(3);gate(TitlePartsLocatorTest.run(r),FailureGate.BODY_MISSING,"missing body gate");
  r=TitlePartsLocatorTest.rows();r.set(6,TitlePartsLocatorTest.row("First synthetic body",r.get(6).box(),.95));gate(TitlePartsLocatorTest.run(r),FailureGate.BODY_DUPLICATE,"duplicate body gate");
  r=TitlePartsLocatorTest.rows();r.set(3,TitlePartsLocatorTest.row("completed",r.get(3).box(),.95));gate(TitlePartsLocatorTest.run(r),FailureGate.COMPLETION_TEXT,"completion text gate");
  r=TitlePartsLocatorTest.rows();r.add(TitlePartsLocatorTest.row("locked",new Box(469,215,485,225),.95));gate(TitlePartsLocatorTest.run(r),FailureGate.PROTECTED_LABEL,"protected label gate");
  r=TitlePartsLocatorTest.rows();r.set(2,TitlePartsLocatorTest.row(TitlePartsLocatorTest.T,new Box(55,165,388,181),.95));gate(TitlePartsLocatorTest.run(r),FailureGate.TITLE_AT_INDICATOR,"title indicator gate");
  r=TitlePartsLocatorTest.rows();r.add(TitlePartsLocatorTest.row("extra",new Box(305,165,320,181),.95));gate(TitlePartsLocatorTest.run(r),FailureGate.BODY_ABOVE_TITLE,"body above title gate");
  r=TitlePartsLocatorTest.rows();r.set(2,TitlePartsLocatorTest.row("different",r.get(2).box(),.95));r.set(5,TitlePartsLocatorTest.row("different",r.get(5).box(),.95));gate(TitlePartsLocatorTest.run(r),FailureGate.NO_TITLE_MATCH,"no title gate");
  for(var component:FailureDiagnostic.class.getRecordComponents()){
   Class<?> t=component.getType();
   if(t==List.class){
    assertEquals("localRowDiagnostics",component.getName());
    ok(component.getGenericType() instanceof java.lang.reflect.ParameterizedType,"precise list type");
    var generic=(java.lang.reflect.ParameterizedType)component.getGenericType();
    assertArrayEquals(new java.lang.reflect.Type[]{LocalRowDiagnostic.class},generic.getActualTypeArguments());
    for(var scalar:LocalRowDiagnostic.class.getRecordComponents())ok(scalar.getType().isPrimitive()||scalar.getType().isEnum()||scalar.getType()==Box.class,"safe local field "+scalar.getName());
   }else ok(t.isPrimitive()||t.isEnum()||t==Box.class,"safe field type "+component.getName());
  }
 }
}
