package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmicCleanGravityMaintenanceHatchSourceTest {

    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "CosmicCleanGravityMaintenanceHatchMachine.java");
    private static final Path GRAVITY_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "GravityConditionMaintenanceMixin.java");
    private static final Path MACHINES = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "DShanhaiMachines.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path ZH_CN = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "lang", "zh_cn.json");
    private static final Path EN_US = Path.of("src", "main", "resources", "assets", "gt_shanhai",
            "lang", "en_us.json");

    @Test
    void partProvidesOnlyAutomaticCleanroomAndUniversalGravityMaintenance() throws IOException {
        assertTrue(Files.exists(MACHINE), "缺少寰宇洁净重力维护仓机器类");
        String source = Files.readString(MACHINE);

        assertTrue(source.contains("extends GTLCleaningMaintenanceHatchPartMachine"));
        assertTrue(source.contains("implements IUniversalGravityMaintenancePart"));
        assertTrue(source.contains("ICleaningRoom.LAW_DUMMY_CLEANROOM"));
        assertFalse(source.contains("IMaintenanceBypassPart"), "低级维护仓不得继承枢纽全能绕过接口");
        assertFalse(source.contains("IParallelHatch"));
        assertFalse(source.contains("IThreadModifierPart"));
        assertFalse(source.contains("NotifiableEnergyContainer"));
    }

    @Test
    void gravityMixinBypassesOnlyGravityForFormedControllers() throws IOException {
        assertTrue(Files.exists(GRAVITY_MIXIN), "缺少 GTLCore 重力条件定向 mixin");
        String source = Files.readString(GRAVITY_MIXIN);

        assertTrue(source.contains("@Mixin(value = GravityCondition.class, remap = false)"));
        assertTrue(source.contains("@Inject(method = \"test\""));
        assertTrue(source.contains("controller.isFormed()"));
        assertTrue(source.contains("part instanceof IUniversalGravityMaintenancePart"));
        assertTrue(source.contains("cir.setReturnValue(!((GravityCondition) (Object) this).isReverse());"));
        assertFalse(source.contains("GTRecipe.class"));
        assertFalse(source.contains("checkConditions"));
    }

    @Test
    void machineRegistersThreeCompatibilityAbilitiesAndFunctionalTooltip() throws IOException {
        String source = Files.readString(MACHINES);

        assertTrue(source.contains("COSMIC_CLEAN_GRAVITY_MAINTENANCE_HATCH"));
        assertTrue(source.contains("\"cosmic_clean_gravity_maintenance_hatch\""));
        assertTrue(source.contains("CosmicCleanGravityMaintenanceHatchMachine::new"));
        assertTrue(source.contains("MaintenanceHatchPartRenderer"));
        assertTrue(source.contains("var cosmicHatchBlock = COSMIC_CLEAN_GRAVITY_MAINTENANCE_HATCH.getBlock()"));
        assertTrue(source.contains("PartAbility.MAINTENANCE.register(0, cosmicHatchBlock)"));
        assertTrue(source.contains("PartAbility.IMPORT_ITEMS.register(0, cosmicHatchBlock)"));
        assertTrue(source.contains("PartAbility.IMPORT_FLUIDS.register(0, cosmicHatchBlock)"));
        assertTrue(source.contains("不提供实际物品或流体容量"));
        assertTrue(source.contains("不包含终焉聚合枢纽的高级功能"));
    }

    @Test
    void mixinAndTranslationsAreRegistered() throws IOException {
        String mixinConfig = Files.readString(MIXIN_CONFIG);
        String zhCn = Files.readString(ZH_CN);
        String enUs = Files.readString(EN_US);
        String key = "block.gt_shanhai.cosmic_clean_gravity_maintenance_hatch";

        assertTrue(mixinConfig.contains("\"GravityConditionMaintenanceMixin\""));
        assertTrue(zhCn.contains("\"" + key + "\": \"寰宇洁净重力维护仓\""));
        assertTrue(enUs.contains("\"" + key + "\": \"Cosmic Clean Gravity Maintenance Hatch\""));
    }
}
