package com.aliothmoon.maafw.once;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import com.aliothmoon.maafw.rewards.RewardRegionLocator;
public class RootSeriesDisplayBudgetTest {
 @Test public void maximumGeometryAuditsAndMetadataFitThreeIndependentViews(){
  var rows=new ArrayList<MaaImageBufferAudit.OcrRowBox>();var boxes=new ArrayList<RewardRegionLocator.Box>();
  for(int i=0;i<32;i++)rows.add(new MaaImageBufferAudit.OcrRowBox(4095,4095,4096,4096,.9999999999999999));
  for(int i=0;i<8;i++)boxes.add(new RewardRegionLocator.Box(1000,1000,4096,4096));
  var sample=new MaaImageBufferAudit.Sample(MaaImageBufferAudit.Status.PIXEL_MISMATCH,4096,4096,3,16,4194304,4194304,12582912,255,false);
  var stats=new MaaImageBufferAudit.BandStats(MaaImageBufferAudit.StatsStatus.OBSERVED,4096,1024,0,256,4096,768,2097152,0,255,254.12345678901234,16256.249999999998,2097152);
  var result=new MaaImageBufferAudit.Result(MaaImageBufferAudit.Status.PIXEL_MISMATCH,sample,sample,
      new MaaImageBufferAudit.CvSample(true,RewardRegionLocator.Status.OBSERVED,16,boxes,true),
      new MaaImageBufferAudit.OcrGeometry(true,128,32,true,rows),stats,null,1,true,true,true,true,true,true,true);
  // Bounds include existing source cleanup, terminal fields, nano timestamps, both comparison records and newline labels.
  String frame=result.summary()+"\n"+"m".repeat(1100)+"\n"+"p".repeat(500)+"\n"+"e".repeat(2000);
  assertTrue(result.summary().length()<=3600);assertTrue(frame.length()<=7000);assertTrue(frame.length()*3+500<=22000);
  assertFalse(result.summary().matches("(?s).*[A-Fa-f0-9]{64}.*"));
  System.out.println("V29_MAX_PER_FRAME_WITH_METADATA_AND_ENVELOPE="+frame.length()+" THREE_PLUS_HEADER="+(frame.length()*3+500));
 }
}
