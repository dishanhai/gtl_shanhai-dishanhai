package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiBlockSelfDropMixinTest {

    @Test
    void missingLootTableDropsTheBlockItself() throws Exception {
        String mixin = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "mixin", "ShanhaiBlockSelfDropMixin.java"));
        String config = Files.readString(Path.of("src", "main", "resources", "gt_shanhai.mixin.json"));

        assertTrue(config.contains("\"ShanhaiBlockSelfDropMixin\""));
        assertTrue(mixin.contains("@Mixin(Block.class)"));
        assertTrue(mixin.contains("method = \"getDrops\""));
        assertTrue(mixin.contains("GTDishanhaiMod.MOD_ID.equals(id.getNamespace())"));
        assertTrue(mixin.contains("loot_tables/blocks/"));
        assertTrue(mixin.contains("new ItemStack(item)"));
        assertTrue(mixin.contains("new ArrayList"));
    }
}
