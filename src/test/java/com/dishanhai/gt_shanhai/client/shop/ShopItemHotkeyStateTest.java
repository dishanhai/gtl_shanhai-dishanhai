package com.dishanhai.gt_shanhai.client.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopItemHotkeyStateTest {

    @Test
    void opensAfterTenHeldTicksAndOnlyOnceUntilRelease() {
        ShopItemHotkey.HoldState state = new ShopItemHotkey.HoldState(10);

        for (int tick = 1; tick < 10; tick++) {
            assertEquals(-1L, state.update("minecraft:stone", 7L, true));
        }
        assertEquals(7L, state.update("minecraft:stone", 7L, true));
        assertEquals(-1L, state.update("minecraft:stone", 7L, true));

        assertEquals(-1L, state.update("minecraft:stone", 7L, false));
        for (int tick = 1; tick < 10; tick++) {
            assertEquals(-1L, state.update("minecraft:stone", 7L, true));
        }
        assertEquals(7L, state.update("minecraft:stone", 7L, true));
    }

    @Test
    void changingHoveredItemResetsHoldProgress() {
        ShopItemHotkey.HoldState state = new ShopItemHotkey.HoldState(3);

        assertEquals(-1L, state.update("minecraft:stone", 7L, true));
        assertEquals(-1L, state.update("minecraft:stone", 7L, true));
        assertEquals(-1L, state.update("minecraft:granite", 9L, true));
        assertEquals(-1L, state.update("minecraft:granite", 9L, true));
        assertEquals(9L, state.update("minecraft:granite", 9L, true));
    }

    @Test
    void readsPhysicalKeyStateInsteadOfKeyMappingPressedState() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "client", "shop", "ShopItemHotkey.java"));

        assertTrue(source.contains("InputConstants.isKeyDown("),
                "JEI tooltip 渲染时必须像 GuideME 一样读取窗口实体按键状态");
        assertFalse(source.contains("mapping.isDown()"),
                "KeyMapping.isDown() 在 JEI tooltip 路径不会稳定反映长按状态");
        assertFalse(source.contains("entryKeys.get(0)"),
                "同物品 ID 的不同 NBT 不能再取目录第一条");
        assertTrue(source.contains("keyOfHoveredGoods("),
                "悬停跳转必须按物品 ID 加 NBT 身份解析商品");
    }
}
