package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GuixuPenroseContainmentWiringTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai");
    private static final Path LANG = Path.of("src/main/resources/assets/gt_shanhai/lang");
    private static final String GUIXU = "common/machine/primordial/guixu/";
    private static final String PRIMORDIAL = "common/machine/primordial/";

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative)).replace("\r\n", "\n");
    }

    @Test
    void moduleIsRegisteredWithSafeStructure() throws Exception {
        String structure = read(GUIXU + "GuixuPenroseContainmentStructure.java");
        assertTrue(structure.contains("multiblock/forge_of_the_antichrist_module.bin"));
        assertTrue(structure.contains("StructureResourceLoader.INSTANCE.loadFactoryPattern("));
        assertFalse(structure.contains("MultiBlockStructure"), "禁用共享可變單例");
        for (String id : new String[]{"kubejs:hollow_casing", "kubejs:containment_field_generator",
                "dishanhai:particle_beam_guidance_pipe_casing"}) {
            assertTrue(structure.contains(id), id);
        }
        for (char symbol : new char[]{'B', 'D', 'E', 'F', 'G'}) {
            assertTrue(structure.contains("where('" + symbol + "'"), "符號 " + symbol);
        }
        String registration = read(GUIXU + "GuixuVisionCoreMachines.java");
        assertTrue(registration.contains("GuixuPenroseContainmentModule::new"));
        assertTrue(registration.contains("\"guixu_penrose_containment_field\""));
        String modules = read(PRIMORDIAL + "PrimordialOmegaEngineStructure.java");
        assertTrue(modules.contains("\"guixu_penrose_containment_field\""));
        for (String lang : new String[]{"zh_cn.json", "en_us.json"}) {
            String text = Files.readString(LANG.resolve(lang));
            assertTrue(text.contains("\"block.gt_shanhai.guixu_penrose_containment_field\""), lang);
            assertTrue(text.contains("\"gt_shanhai.machine.guixu_penrose_containment_field.name\""), lang);
        }
    }

    @Test
    void hostOwnsLockBonusAndCountMultiplier() throws Exception {
        String engine = read(PRIMORDIAL + "PrimordialOmegaEngineMachine.java");
        assertTrue(engine.contains("protected int moduleCountMultiplier()"));
        assertTrue(engine.contains("* countMultiplier"));
        String host = read(GUIXU + "GuixuVisionCoreMachine.java");
        assertTrue(host.contains("protected int moduleCountMultiplier()"));
        assertTrue(host.contains("\"containmentLockTicks\""), "鎖定字段要列入 holder 自檢");
        assertTrue(host.contains("public GuixuContainmentBonus getContainmentBonus()"));
        assertTrue(host.contains("GuixuContainmentText.hostSummary("));
        assertTrue(host.contains("getContainmentBonus().extraOutput()"));
    }

    @Test
    void moduleConsumesThroughInternalPathAndHostRejectsStaleModules() throws Exception {
        String module = read(GUIXU + "GuixuPenroseContainmentModule.java");
        assertTrue(module.contains("drainInternal("), "流體要走內部路徑實扣 ME 倉");
        assertTrue(module.contains("extractItemInternal("), "物品要走內部路徑實扣 ME 總線");
        assertFalse(module.contains("setFluidInTank"), "ME 輸入倉寫回無效");
        assertFalse(module.contains("setStackInSlot"), "ME 輸入總線寫回無效");
        String host = read(GUIXU + "GuixuVisionCoreMachine.java");
        assertTrue(host.contains("hasChunkAt(module.getPos())"), "排除區塊卸載殘留實例");
        assertTrue(host.contains("containmentLastUnstable"));
        assertFalse(host.contains("containmentWasUnstable"));
    }

    @Test
    void hostRecipeLogicAppliesBonus() throws Exception {
        String logic = read(GUIXU + "GuixuVisionCoreRecipeLogic.java");
        assertTrue(logic.contains("host.isContainmentLocked()"));
        assertTrue(logic.contains(".scaleParallel(super.getTotalParallelLimit())"));
        assertTrue(logic.contains(".scaleEnergy("));
        assertTrue(logic.contains(".scaleDuration(plannedTicks)"));
        assertTrue(logic.contains(".shouldSwallow("));
    }

    @Test
    void moduleRecipeLogicAppliesBonus() throws Exception {
        String logic = read(PRIMORDIAL + "PrimordialModuleRecipeLogic.java");
        assertTrue(logic.contains("containmentBonus().locked()"));
        assertTrue(logic.contains("containmentBonus().scaleParallel(super.getTotalParallelLimit())"));
        assertTrue(logic.contains("containmentBonus().energyFactor(energyConsumer)"));
        assertTrue(logic.contains("containmentBonus().extraOutput()"));
        assertTrue(logic.contains("protected WirelessGTRecipe buildWirelessRecipe("));
        assertTrue(logic.contains("cachedHostCountMultiplier"));
        String base = read(PRIMORDIAL + "PrimordialOmegaEngineModuleBase.java");
        assertTrue(base.contains("isContainmentLocked()"));
    }
}
