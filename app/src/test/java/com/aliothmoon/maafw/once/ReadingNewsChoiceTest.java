package com.aliothmoon.maafw.once;

import org.junit.Test;
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
    }
}
