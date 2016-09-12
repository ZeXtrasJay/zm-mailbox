package com.zimbra.cs.ephemeral;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.zimbra.cs.ephemeral.EphemeralInput.Expiration;

public class EphemeralStoreTest {

    private EphemeralStore store;

    @Before
    public void setUp() throws Exception {
        EphemeralStore.setFactory(InMemoryEphemeralStore.Factory.class);
        store = EphemeralStore.getFactory().getStore();
    }

    @After
    public void tearDown() throws Exception {
        EphemeralStore.getFactory().shutdown();
    }

    @Test
    public void testSet() throws Exception {
        EphemeralLocation target = new TestLocation();
        store.set(new EphemeralInput("foo", "bar"), target);
        assertEquals("bar", store.get("foo", target).getValue());
        store.set(new EphemeralInput("foo", "baz"), target);
        assertEquals("baz", store.get("foo", target).getValue());
    }

    @Test
    public void testUpdate() throws Exception {
        EphemeralLocation target = new TestLocation();
        store.update(new EphemeralInput("foo", "bar"), target);
        store.update(new EphemeralInput("foo", "baz"), target);
        EphemeralResult result = store.get("foo", target);
        String[] values = result.getValues();
        assertEquals("bar", values[0]);
        assertEquals("baz", values[1]);
    }

    @Test
    public void testDelete() throws Exception {
        EphemeralLocation target = new TestLocation();
        store.set(new EphemeralInput("foo", "bar"), target);
        store.update(new EphemeralInput("foo", "baz"), target);
        store.delete("foo", "bar", target);
        EphemeralResult result = store.get("foo", target);
        assertEquals(1, result.getValues().length);
        assertEquals("baz", result.getValue());
        store.delete("foo", "baz", target);
        result = store.get("foo", target);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testExpiry() throws Exception {
        EphemeralLocation target = new TestLocation();
        EphemeralInput input = new EphemeralInput("foo", "bar");
        input.setExpiration(new Expiration(1L, TimeUnit.SECONDS));
        store.set(input, target);
        assertEquals("bar", store.get("foo", target).getValue());
        Thread.sleep(1500);
        store.purgeExpired("foo", target);
        assertTrue(store.get("foo", target).isEmpty());
    }

    @Test
    public void testHas() throws Exception {
        EphemeralLocation target = new TestLocation();
        store.set(new EphemeralInput("foo", "bar"), target);
        assertTrue(store.has("foo", "bar", target));
        assertFalse(store.has("foo", "baz", target));
        store.delete("foo", "bar", target);
        assertFalse(store.has("foo", "bar", target));
    }

    @Test
    public void testDataTypes() throws Exception {
        EphemeralLocation target = new TestLocation();
        Integer intValue = 1;
        Boolean boolValue = true;
        Long longValue = 10000000000L;
        store.set(new EphemeralInput("integer", intValue), target);
        store.set(new EphemeralInput("boolean", boolValue), target);
        store.set(new EphemeralInput("long", longValue), target);
        assertEquals(intValue, store.get("integer", target).getIntValue());
        assertEquals(boolValue, store.get("boolean", target).getBoolValue());
        assertEquals(longValue, store.get("long", target).getLongValue());
    }

    @Test
    public void testIncorrectDataTypes() throws Exception {
        EphemeralLocation target = new TestLocation();
        store.set(new EphemeralInput("foo", "bar"), target);
        store.update(new EphemeralInput("foo", "1"), target);
        store.update(new EphemeralInput("foo", "true"), target);
        EphemeralResult result = store.get("foo", target);

        assertEquals((Integer) null, result.getIntValue());
        assertEquals(new Integer(1), result.getIntValue(1));
        assertArrayEquals(new Integer[] {null, 1, null}, result.getIntValues());
        assertArrayEquals(new Integer[] {0, 1, 0}, result.getIntValues(0));

        assertEquals((Boolean) null, store.get("foo", target).getBoolValue());
        assertEquals(true, store.get("foo", target).getBoolValue(true));
        assertArrayEquals(new Boolean[] {null, null, true}, result.getBoolValues());
        assertArrayEquals(new Boolean[] {false, false, true}, result.getBoolValues(false));


        assertEquals((Long) null, store.get("foo", target).getLongValue());
        assertEquals(new Long(1), store.get("foo", target).getLongValue(1L));
        assertArrayEquals(new Long[] {null, 1L, null}, result.getLongValues());
        assertArrayEquals(new Long[] {0L, 1L, 0L}, result.getLongValues(0L));
    }

    @Test
    public void testDifferentLocation() throws Exception {
        EphemeralLocation target = new TestLocation();
        store.set(new EphemeralInput("foo", "bar"), target);

        EphemeralLocation differentLocation = new EphemeralLocation() {

            @Override
            public String[] getLocation() { return new String[] { "different" }; }
        };

        assertTrue(store.get("foo", differentLocation).isEmpty());
    }

    @Test
    public void testDefaults() throws Exception {
        EphemeralResult result = new EphemeralResult("foo", (String) null);
        assertEquals(null, result.getValue());
        assertEquals("bar", result.getValue("bar"));
        assertEquals(null, result.getIntValue());
        assertEquals((Integer) 1, result.getIntValue(1));
        assertEquals(null, result.getBoolValue());
        assertEquals(true, result.getBoolValue(true));
        assertTrue(result.isEmpty());
    }

    static class TestLocation extends EphemeralLocation {

        @Override
        public String[] getLocation() {
            return new String[] { "test" };
        }
    }
}
