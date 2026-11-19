package com.aliothmoon.maafw.once;

import org.junit.Test;
import com.aliothmoon.maafw.rewards.RewardRegionLocator.Box;
import static org.junit.Assert.*;

public final class ReadingNewsChoiceTest {
    @Test public void existingButtonsRemainSeparateAndReadingConfirmationIsConsumedOnce() {
        var choice=new ReadingNewsChoice();
        Object activity=new Object();
        assertNull(choice.take(activity,true,false));
        choice.confirm(activity,ReadingNewsChoice.Mode.LIVE,()->{
            assertThrows(SecurityException.class,()->choice.take(new Object(),true,false));
            assertThrows(SecurityException.class,()->choice.take(activity,false,false));
            assertThrows(SecurityException.class,()->choice.take(activity,true,true));
            assertEquals(ReadingNewsChoice.Mode.LIVE,choice.take(activity,true,false));
            assertThrows(SecurityException.class,()->choice.take(activity,true,false));
        });
        assertNull(choice.take(activity,true,false));
        choice.confirm(activity,ReadingNewsChoice.Mode.PREFLIGHT,()->{
            assertThrows(SecurityException.class,()->choice.take(activity,true,false));
            assertEquals(ReadingNewsChoice.Mode.PREFLIGHT,choice.take(activity,false,false));
        });
        choice.confirm(activity,ReadingNewsChoice.Mode.PROGRESS_FIVE,()->{
            assertThrows(SecurityException.class,()->choice.take(activity,false,false));
            assertThrows(SecurityException.class,()->choice.take(activity,true,true));
            assertEquals(ReadingNewsChoice.Mode.PROGRESS_FIVE,choice.take(activity,true,false));
            assertEquals("NEWS_PROGRESS_FIVE",ReadingNewsChoice.Mode.PROGRESS_FIVE.purpose);
            assertThrows(SecurityException.class,()->choice.take(activity,true,false));
        });
    }
    @Test public void progressReceiptRequiresACompleteRewardsCardAndAnAgreeingReadingCounter(){
        var view=new Box(0,0,800,1600);var card=new Box(40,320,760,550);
        var partial=new NativeUiStructureProtocol.Task(NativeUiStructureProtocol.Status.KNOWN,6,30,1,
                NativeUiStructureProtocol.Unit.EXPLICIT_TEXT_POINTS);
        var full=new NativeUiStructureProtocol.Task(NativeUiStructureProtocol.Status.KNOWN,30,30,1,
                NativeUiStructureProtocol.Unit.EXPLICIT_TEXT_POINTS);
        var ambiguous=new NativeUiStructureProtocol.Task(NativeUiStructureProtocol.Status.AMBIGUOUS,-1,-1,2,
                NativeUiStructureProtocol.Unit.UNESTABLISHED);
        var incompleteTask=new NewsFeedModel.Frame(NewsFeedModel.Page.REWARDS,view,card,false,null,0,false,"READING_ENTRY");
        var completeTask=new NewsFeedModel.Frame(NewsFeedModel.Page.REWARDS,view,card,true,null,0,false,"READING_ENTRY");
        var offscreen=new NewsFeedModel.Frame(NewsFeedModel.Page.REWARDS,view,null,false,null,0,false,"ENTRY_ABOVE");
        assertSame(partial,NewsAndroidPort.checkedEntryProgress(incompleteTask,partial));
        assertSame(full,NewsAndroidPort.checkedEntryProgress(completeTask,full));
        assertThrows(SecurityException.class,()->NewsAndroidPort.checkedEntryProgress(incompleteTask,full));
        assertThrows(SecurityException.class,()->NewsAndroidPort.checkedEntryProgress(completeTask,partial));
        assertThrows(SecurityException.class,()->NewsAndroidPort.checkedEntryProgress(incompleteTask,ambiguous));
        assertThrows(SecurityException.class,()->NewsAndroidPort.checkedEntryProgress(offscreen,partial));
    }
}
