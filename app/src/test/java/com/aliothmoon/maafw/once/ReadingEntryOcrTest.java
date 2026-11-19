package com.aliothmoon.maafw.once;

import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadingEntryOcrTest {
    private static final Box SEARCH=new Box(5,27,295,64);
    private static final Box CROP=new Box(110,870,410,970);
    private static FrameStructureEvidence.Line row(String s,int x,int y,double confidence){
        return new FrameStructureEvidence.Line(s,x,y,65,12,confidence);
    }
    @Test public void targetsConsentLabelAmongInCardDescriptionsAndKeepsOriginalCoordinates(){
        var exact=row("阅读以赚取",87,32,.85);
        var other=row("描述",SEARCH.left(),32,.60);
        var result=ReadingEntryOcr.match(List.of(other,exact),SEARCH,CROP);
        assertEquals(NewsFeedModel.Kind.ENTRY,result.kind());
        assertEquals(CROP.left()+87+65/2,result.x());
        assertEquals(CROP.top()+32+6,result.y());
        var scalar=ReadingEntryOcr.diagnostic(false,SEARCH,List.of(other,exact));
        assertEquals("ENTRY1 mode=TIGHT roi=5,27,290,37 rows=2 r0=O,600,5,32,65,12,1 r1=T,850,87,32,65,12,0",scalar);
        assertFalse(scalar.contains("描述"));assertFalse(scalar.contains("阅读"));
    }
    @Test public void uncertainClippedMissingDuplicateOrOutsideRowsCannotProduceAPoint(){
        var exact=row("阅读以赚取",80,34,.95);
        assertEquals("ENTRY_TITLE_LOW_CONFIDENCE",ReadingEntryOcr.match(List.of(row("阅读以赚取",80,34,.849)),SEARCH,CROP).reason());
        assertEquals("ENTRY_TITLE_ON_EDGE",ReadingEntryOcr.match(List.of(row("阅读以赚取",80,SEARCH.top(),.99)),SEARCH,CROP).reason());
        assertEquals("ENTRY_TITLE_DUPLICATE",ReadingEntryOcr.match(List.of(exact,exact),SEARCH,CROP).reason());
        assertEquals("ENTRY_TITLE_MISSING",ReadingEntryOcr.match(List.of(row("阅读其他",80,34,.99)),SEARCH,CROP).reason());
        assertEquals("ENTRY_ROW_OUTSIDE_ROI",ReadingEntryOcr.match(List.of(exact,row("其他",3,34,.6)),SEARCH,CROP).reason());
    }
    @Test public void tightSearchFollowsGlyphRowsAndWideSearchStaysInsideTheSameCard(){
        int w=300,h=100;
        byte[] gray=new byte[w*h];Arrays.fill(gray,(byte)244);
        // Solid thumbnail and five two-stroke glyphs in the upper content line.
        for(int y=18;y<78;y++)for(int x=15;x<55;x++)gray[y*w+x]=(byte)60;
        for(int y=28;y<41;y++)for(int x=76;x<136;x++)if(x%12<3||(x+6)%12<3)gray[y*w+x]=30;
        var card=new Box(2,2,298,98);
        var tight=ReadingEntryOcr.window(gray,w,card,false);
        var wide=ReadingEntryOcr.window(gray,w,card,true);
        assertTrue(tight.contains(new Box(76,28,136,41)));assertTrue(tight.bottom()<65);
        assertTrue(wide.contains(tight));assertNotEquals(tight,wide);assertTrue(card.contains(wide));
        Arrays.fill(gray,(byte)244);
        assertNotEquals(ReadingEntryOcr.window(gray,w,card,false),wide);
    }
}
