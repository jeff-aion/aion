package org.aion.evtmgr.impl.mgr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.aion.evtmgr.IEvent;
import org.aion.evtmgr.impl.evt.EventBlock;
import org.aion.evtmgr.impl.evt.EventDummy;
import org.junit.Test;

public class EventMgrA0Test {
    private Properties properties = new Properties();

    @Test(expected = NullPointerException.class)
    public void testNullConfig() {
        EventMgrA0 testManager = new EventMgrA0(null);
    }

    @Test
    public void testRegisterEvent() {
        EventMgrA0 testManager = new EventMgrA0(properties);

        boolean res = testManager.registerEvent(getEventsList());
        assertFalse(res);

        boolean res2 = testManager.registerEvent(getEventsList2());
        assertTrue(res2);
    }

    @Test
    public void tesUnregisterEvent() {
        EventMgrA0 testManager = new EventMgrA0(properties);

        boolean res = testManager.unregisterEvent(getEventsList());
        assertFalse(res);

        boolean res2 = testManager.unregisterEvent(getEventsList2());
        assertTrue(res2);
    }

    @Test
    public void testNewEvent() {
        EventMgrA0 testManager = new EventMgrA0(properties);

        boolean res = testManager.newEvent(new EventDummy());
        assertTrue(res);

        boolean res2 = testManager.newEvent(new EventBlock(EventBlock.CALLBACK.ONBLOCK0));
        assertTrue(res2);
    }

    @Test
    public void testNewEvents() {
        EventMgrA0 testManager = new EventMgrA0(properties);

        boolean res = testManager.newEvents(getEventsList());
        assertTrue(res);

        boolean res2 = testManager.newEvents(getEventsList2());
        assertTrue(res2);
    }

    private List<IEvent> getEventsList() {
        List<IEvent> eventsList = new ArrayList<>();
        eventsList.add(new EventBlock(EventBlock.CALLBACK.ONBEST0));
        eventsList.add(new EventBlock(EventBlock.CALLBACK.ONTRACE0));
        eventsList.add(new EventBlock(EventBlock.CALLBACK.ONBLOCK0));
        eventsList.add(new EventDummy());

        return eventsList;
    }

    private List<IEvent> getEventsList2() {
        List<IEvent> eventsList = new ArrayList<>();
        eventsList.add(new EventBlock(EventBlock.CALLBACK.ONBEST0));
        eventsList.add(new EventBlock(EventBlock.CALLBACK.ONTRACE0));
        eventsList.add(new EventBlock(EventBlock.CALLBACK.ONBLOCK0));

        return eventsList;
    }
}
