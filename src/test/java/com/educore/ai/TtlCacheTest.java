package com.educore.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TtlCacheTest {

    @Test
    void storesAndReturnsValues() {
        TtlCache c = new TtlCache(60_000, 10);
        c.put("k1", "v1");
        assertEquals("v1", c.get("k1"));
        assertNull(c.get("missing"));
    }

    @Test
    void expiresEntries() throws Exception {
        TtlCache c = new TtlCache(30, 10);
        c.put("k", "v");
        assertEquals("v", c.get("k"));
        Thread.sleep(60);
        assertNull(c.get("k"));
        assertEquals(0, c.size());
    }

    @Test
    void userScopedKeysDoNotCollide() {
        // simulates per-user cache namespacing: same content, different users
        TtlCache c = new TtlCache(60_000, 10);
        c.put("ai:userA|hash", "answer-for-A");
        c.put("ai:userB|hash", "answer-for-B");
        assertEquals("answer-for-A", c.get("ai:userA|hash"));
        assertEquals("answer-for-B", c.get("ai:userB|hash"));
        c.invalidatePrefix("ai:userA|");
        assertNull(c.get("ai:userA|hash"));
        assertEquals("answer-for-B", c.get("ai:userB|hash"));
    }

    @Test
    void respectsMaxSize() {
        TtlCache c = new TtlCache(60_000, 3);
        for (int i = 0; i < 10; i++) c.put("k" + i, "v" + i);
        assertTrue(c.size() <= 3);
    }

    @Test
    void nullSafety() {
        TtlCache c = new TtlCache(60_000, 10);
        c.put(null, "x");
        c.put("k", null);
        assertNull(c.get(null));
        c.invalidate(null);
        c.invalidatePrefix(null);
    }
}
