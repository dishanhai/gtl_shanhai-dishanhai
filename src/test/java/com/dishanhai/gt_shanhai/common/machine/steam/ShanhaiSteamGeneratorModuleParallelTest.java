package com.dishanhai.gt_shanhai.common.machine.steam;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：原初物質模組並行乘四，精確整數及 long 飽和邊界。 */
class ShanhaiSteamGeneratorModuleParallelTest {
    @Test
    void earlyMatterModuleParallelsAreMultipliedByFour() throws Exception {
        assertEquals(256L, scale(64L));
        assertEquals(512L, scale(128L));
        assertEquals(1024L, scale(256L));
        assertEquals(2048L, scale(512L));
        assertEquals(8589934588L, scale(Integer.MAX_VALUE));
    }

    @Test
    void multiplicationSaturatesWithoutWrappingOrDoubleRounding() throws Exception {
        assertEquals(Long.MAX_VALUE - 3L, scale(Long.MAX_VALUE / 4L));
        assertEquals(Long.MAX_VALUE, scale(Long.MAX_VALUE / 4L + 1L));
        assertEquals(Long.MAX_VALUE, scale(Long.MAX_VALUE / 2L));
        assertEquals(Long.MAX_VALUE, scale(Long.MAX_VALUE));
        assertEquals(36028797018963972L, scale(9007199254740993L));
    }

    @Test
    void moduleSlotReusesPrimordialTableAndPersistsAndDropsItsContents() throws Exception {
        Path root = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine");
        String machine = Files.readString(root.resolve("steam/ShanhaiSteamGeneratorMachine.java"));
        assertTrue(machine.contains("PrimordialTaixuCosmicForgeModule.getMatterModuleParallel("),
                "必須直接復用原初模組並行表與堆疊規則");
        assertFalse(machine.contains("ITEM_PARALLEL_MAP"), "不另造會漂移的模組並行表");
        assertTrue(machine.contains("PrimordialOmegaEngineMachine::isMatterModuleStack"));
        assertTrue(machine.contains("@Persisted"));
        assertTrue(machine.contains("ShanhaiSteamGeneratorMachine.class, WorkableElectricMultipleRecipesMachine.MANAGED_FIELD_HOLDER"));
        assertTrue(machine.contains("new SlotWidget("));
        assertTrue(machine.contains("clearInventory(moduleSlot.storage)"));
        assertTrue(machine.contains("updateTickSubscription()"), "更換模組須喚醒配方查找");
        String reference = Files.readString(root.resolve("primordial/module/taixu/PrimordialTaixuCosmicForgeModule.java"));
        assertTrue(reference.contains("public static long getMatterModuleParallel("));
        assertTrue(reference.contains("ITEM_PARALLEL_MAP.put(ITEM_WZRM, 128L)"));
    }

    private long scale(long base) throws Exception {
        var method = ShanhaiSteamGeneratorMachine.class.getDeclaredMethod("scaleModuleParallel", long.class);
        method.setAccessible(true);
        return (long) method.invoke(null, base);
    }
}
