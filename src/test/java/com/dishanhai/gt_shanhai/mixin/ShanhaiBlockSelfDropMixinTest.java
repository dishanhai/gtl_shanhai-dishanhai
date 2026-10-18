package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiBlockSelfDropMixinTest {

    @Test
    void missingLootTableDropsTheBlockItself() throws Exception {
        String mixin = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "mixin", "ShanhaiBlockSelfDropMixin.java"));
        String config = MixinConfigs.readAll();

        assertTrue(config.contains("\"ShanhaiBlockSelfDropMixin\""));
        assertTrue(mixin.contains("@Mixin(BlockBehaviour.BlockStateBase.class)"));
        assertTrue(mixin.contains("method = \"m_287290_\""),
                "1.20.1 的掉落入口是 BlockStateBase.getDrops(LootParams.Builder)，产线名 m_287290_");
        assertTrue(mixin.contains("remap = false"),
                "无 refmap 时必须用 SRG 名 + remap = false");
        assertTrue(mixin.contains("GTDishanhaiMod.MOD_ID.equals(id.getNamespace())"));
        assertTrue(mixin.contains("loot_tables/blocks/"));
        assertTrue(mixin.contains("new ItemStack(item)"));
        assertTrue(mixin.contains("new ArrayList"));
    }
}
