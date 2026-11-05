package com.dishanhai.gt_shanhai.integration.enhancedcore;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EnhancedCoreCloudLibraryCacheTest {
    private record Child(String id, String mode, String io) {}

    @Test
    void validatorStopsAtFirstValidThenFullExpansionReusesAlreadyEncodedChildren() {
        var cache = new EnhancedCoreCloudLibraryCache<String, Child>(4);
        var encodes = new AtomicInteger(); var ids = List.of("invalid", "one", "two");
        java.util.function.Function<String, Child> encoder = id -> {
            encodes.incrementAndGet(); return id.equals("invalid") ? null : new Child(id, "mode", "same-io");
        };
        var expansion = cache.get("definition", 0, ids);
        assertEquals("one", expansion.first(encoder).id()); assertEquals(2, encodes.get());
        assertEquals(List.of("one", "two"), expansion.all(encoder).stream().map(Child::id).toList());
        assertEquals(3, encodes.get()); expansion.all(encoder); assertEquals(3, encodes.get());
        assertSame(expansion, cache.get("definition", 0, ids));
    }

    @Test
    void exactIdsCannotAliasEvenWithSameIoAndReloadInvalidatesTheWholeDefinition() {
        var cache = new EnhancedCoreCloudLibraryCache<String, Child>(4);
        var ids = List.of("one", "two");
        var expansion = cache.get("definition", 1, ids);
        var one = expansion.find("one", id -> new Child(id, "mode", "same"));
        var two = expansion.find("two", id -> new Child(id, "mode", "same"));
        assertEquals("one", one.id()); assertEquals("two", two.id()); assertNotSame(one, two);
        assertNull(expansion.find("unselected", id -> fail("unselected recipe must not encode")));
        var reloaded = cache.get("definition", 2, ids);
        assertNotSame(expansion, reloaded);
        assertEquals("new-recipe-data", reloaded.find("one", id -> new Child(id, "mode", "new-recipe-data")).io());
        cache.clear(); assertNotSame(expansion, cache.get("definition", 1, ids));
    }

    @Test
    void differentMotherDefinitionsDoNotShareMutableChildrenOrScaleResults() {
        var cache = new EnhancedCoreCloudLibraryCache<String, Child>(4); var ids = List.of("same-id");
        var scaleOne = cache.get("mother-scale-one", 0, ids).find("same-id", id -> new Child(id, "mode", "one-output"));
        var scaleTwo = cache.get("mother-scale-two", 0, ids).find("same-id", id -> new Child(id, "mode", "two-outputs"));
        assertEquals("one-output", scaleOne.io()); assertEquals("two-outputs", scaleTwo.io());
        assertNotSame(scaleOne, scaleTwo);
    }

    @Test
    void everySameModeChildPublishesAndRevocationPublishesNone() {
        var all = List.of(new Child("one", "a", "same"), new Child("two", "b", "same"), new Child("three", "a", "same"));
        assertEquals(List.of("one", "three"), EnhancedCoreCloudLibraryCache.publications(all, true, "a", Child::mode)
                .stream().map(Child::id).toList());
        assertEquals(List.of(), EnhancedCoreCloudLibraryCache.publications(all, false, "a", Child::mode));
    }

    @Test
    void virtualEncodingPreferenceInvalidatesSameOriginalDefinitionWithoutChangingOriginal() {
        var cache = new EnhancedCoreCloudLibraryCache<String, Child>(4); String original = "mother-nbt-unchanged";
        var ids = List.of("one");
        var virtual = cache.get(original, 0, true, ids).find("one", id -> new Child(id, "mode", "presence"));
        var physical = cache.get(original, 0, false, ids).find("one", id -> new Child(id, "mode", "physical"));
        assertEquals("presence", virtual.io()); assertEquals("physical", physical.io()); assertNotSame(virtual, physical);
        assertEquals("mother-nbt-unchanged", original);
    }
}
