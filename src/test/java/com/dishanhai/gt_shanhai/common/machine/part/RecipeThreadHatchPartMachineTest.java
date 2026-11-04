package com.dishanhai.gt_shanhai.common.machine.part;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：容量包含一次基础线程；关闭配置只收回额外接单容量。 */
class RecipeThreadHatchPartMachineTest {
    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeThreadHatchPartMachine.java");
    private static final Path MACHINES = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "DShanhaiMachines.java");

    @Test
    void threadHatchMustNotClaimNativeParallelHatchCapability() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("implements IRecipeThreadSource"));
        assertFalse(source.contains("IParallelHatch"),
                "线程仓不能注册为 GTLCore 的原生并行仓，否则会覆盖实际并行仓选择");

        String machines = Files.readString(MACHINES);
        int registration = machines.indexOf("\"recipe_thread_hatch\"");
        int nextRegistration = machines.indexOf("\n        // ========== 山海维护仓", registration);
        assertTrue(registration >= 0 && nextRegistration > registration, "线程仓机器注册块缺失");
        String registrationBlock = machines.substring(registration, nextRegistration);
        assertTrue(registrationBlock.contains(".abilities(PartAbility.PARALLEL_HATCH, PartAbility.IMPORT_ITEMS)"),
                "线程仓仍需匹配并行仓及物品输入仓结构位置");
    }

    @Test
    void enabledCapacityIncludesOneBaseThreadAndSaturates() {
        assertEquals(1L, RecipeThreadHatchPartMachine.capacityForBoost(true, 0L));
        assertEquals(1L, RecipeThreadHatchPartMachine.capacityForBoost(true, -1L));
        assertEquals(65L, RecipeThreadHatchPartMachine.capacityForBoost(true, 64L));
        assertEquals(Long.MAX_VALUE, RecipeThreadHatchPartMachine.capacityForBoost(true, Long.MAX_VALUE));
    }

    @Test
    void disablingOnlyRemovesAdditionalCapacity() {
        assertEquals(1L, RecipeThreadHatchPartMachine.capacityForBoost(false, 64L));
        assertEquals(1L, RecipeThreadHatchPartMachine.capacityForBoost(false, Long.MAX_VALUE));
    }

}
