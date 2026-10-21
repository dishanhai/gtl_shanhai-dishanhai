package com.dishanhai.gt_shanhai.common.machine.part;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeTypePatternCacheCountGuardTest {

    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");

    @Test
    void longerSavedArrayIsTruncatedToCurrentSlotCount() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("cacheRecipeCount", new byte[] { 1, 2, 3, 4, 5, 6 });

        RecipeTypePatternCacheCountGuard.clampToSlotCount(tag, 4);

        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, tag.getByteArray("cacheRecipeCount"));
    }

    @Test
    void savedArrayThatFitsIsLeftUntouched() {
        byte[] saved = { 3, 2, 1 };
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("cacheRecipeCount", saved);

        RecipeTypePatternCacheCountGuard.clampToSlotCount(tag, 3);
        assertArrayEquals(saved, tag.getByteArray("cacheRecipeCount"));

        RecipeTypePatternCacheCountGuard.clampToSlotCount(tag, 10);
        assertArrayEquals(saved, tag.getByteArray("cacheRecipeCount"));
    }

    @Test
    void missingKeyIsNotCreated() {
        CompoundTag tag = new CompoundTag();

        RecipeTypePatternCacheCountGuard.clampToSlotCount(tag, 4);

        assertFalse(tag.contains("cacheRecipeCount"));
    }

    @Test
    void otherTagEntriesSurviveTruncation() {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("cacheRecipeCount", new byte[] { 1, 1, 1, 1 });
        tag.putInt("other", 42);

        RecipeTypePatternCacheCountGuard.clampToSlotCount(tag, 2);

        assertEquals(2, tag.getByteArray("cacheRecipeCount").length);
        assertEquals(42, tag.getInt("other"));
    }

    @Test
    void stellarBufferClampsSavedCacheCountsBeforeGtlcoreLoadsThem() throws IOException {
        String source = Files.readString(MACHINE).replace("\r\n", "\n");
        int method = source.indexOf("public void loadCustomPersistedData(@NotNull CompoundTag tag)");
        assertTrue(method >= 0, "星律应覆写 loadCustomPersistedData");
        int clamp = source.indexOf("RecipeTypePatternCacheCountGuard.clampToSlotCount(tag, maxPatternCount)", method);
        int superCall = source.indexOf("super.loadCustomPersistedData(tag)", method);
        assertTrue(clamp > method && superCall > clamp,
                "调小槽位配置后 GTLCore 按存档旧长度 System.arraycopy 会越界，必须在 super 之前截断 cacheRecipeCount");
    }
}
