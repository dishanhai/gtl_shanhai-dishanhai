package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StableFilteredSetCacheTest {

    @Test
    void retainIfReturnsSameSetWhenAllEntriesStayValid() {
        StableFilteredSetCache<String> cache = new StableFilteredSetCache<>();
        Set<String> source = new LinkedHashSet<>(Arrays.asList("a", "b"));

        cache.set(source);
        Set<String> result = cache.retainIf(value -> true);

        assertSame(source, result);
        assertSame(source, cache.get());
    }

    @Test
    void retainIfRebuildsSetWhenSomeEntriesBecomeInvalid() {
        StableFilteredSetCache<String> cache = new StableFilteredSetCache<>();
        Set<String> source = new LinkedHashSet<>(Arrays.asList("a", "b", "c"));

        cache.set(source);
        Set<String> result = cache.retainIf(value -> !"b".equals(value));

        assertEquals(new LinkedHashSet<>(Arrays.asList("a", "c")), result);
        assertTrue(result != source);
        assertSame(result, cache.get());
    }

    @Test
    void retainIfClearsCacheWhenNothingSurvives() {
        StableFilteredSetCache<String> cache = new StableFilteredSetCache<>();
        cache.set(new LinkedHashSet<>(Arrays.asList("a")));

        Set<String> result = cache.retainIf(value -> false);

        assertNull(result);
        assertNull(cache.get());
    }
}
