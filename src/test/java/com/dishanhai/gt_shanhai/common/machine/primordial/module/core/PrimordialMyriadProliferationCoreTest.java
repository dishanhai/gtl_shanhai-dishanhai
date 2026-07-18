package com.dishanhai.gt_shanhai.common.machine.primordial.module.core;

import com.dishanhai.gt_shanhai.api.machine.primordial.IPrimordialOutputMultiplierModule;
import com.dishanhai.gt_shanhai.api.recipe.PrimordialMyriadRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialMyriadProliferationCoreTest {

    private static final Path CORE_SOURCE = sourcePath("PrimordialMyriadProliferationCore.java");
    private static final Path STRUCTURE_SOURCE = sourcePath("PrimordialMyriadProliferationCoreStructure.java");

    @Test
    void stageRequiresAnAttachedCoreAndActivelyRunningRecipe() {
        assertEquals(1, PrimordialMyriadProliferationCore.resolveOutputMultiplier(false, false, null));
        assertEquals(1, PrimordialMyriadProliferationCore.resolveOutputMultiplier(
                false, true, PrimordialMyriadRecipeTypes.TIER_1_ID));
        assertEquals(10, PrimordialMyriadProliferationCore.resolveOutputMultiplier(true, false, null));
        assertEquals(10, PrimordialMyriadProliferationCore.resolveOutputMultiplier(
                true, false, PrimordialMyriadRecipeTypes.TIER_1_ID));
        assertEquals(100, PrimordialMyriadProliferationCore.resolveOutputMultiplier(
                true, true, PrimordialMyriadRecipeTypes.TIER_2_ID));
        assertEquals(1000, PrimordialMyriadProliferationCore.resolveOutputMultiplier(
                true, true, PrimordialMyriadRecipeTypes.TIER_1_ID));
        assertEquals(10, PrimordialMyriadProliferationCore.resolveOutputMultiplier(
                true, true, new ResourceLocation("gtceu:electric_furnace")));
        assertEquals(10, PrimordialMyriadProliferationCore.resolveOutputMultiplier(true, true, null));
    }

    @Test
    void coreAndLogicKeepTheNarrowPrimordialHierarchy() throws Exception {
        assertEquals(PrimordialOmegaEngineModuleBase.class,
                PrimordialMyriadProliferationCore.class.getSuperclass());
        assertTrue(IPrimordialOutputMultiplierModule.class.isAssignableFrom(
                PrimordialMyriadProliferationCore.class));
        assertEquals(PrimordialModuleRecipeLogic.class,
                PrimordialMyriadProliferationCoreLogic.class.getSuperclass());
        assertEquals(0, PrimordialMyriadProliferationCoreLogic.class.getDeclaredMethods().length,
                "晋升逻辑不得覆写配方搜索、并行、概率、时长或 EU 行为");

        Method createLogic = PrimordialMyriadProliferationCore.class
                .getMethod("createRecipeLogic", Object[].class);
        assertEquals(PrimordialMyriadProliferationCoreLogic.class, createLogic.getReturnType());
        Method getLogic = PrimordialMyriadProliferationCore.class.getMethod("getRecipeLogic");
        assertEquals(PrimordialMyriadProliferationCoreLogic.class, getLogic.getReturnType());
    }

    @Test
    void structureUsesTheStandardModulePatternWithInputAbilitiesOnly() throws Exception {
        Method createPattern = PrimordialMyriadProliferationCoreStructure.class.getMethod(
                "createPattern", MultiblockMachineDefinition.class);
        assertTrue(Modifier.isPublic(createPattern.getModifiers()));
        assertTrue(Modifier.isStatic(createPattern.getModifiers()));
        assertEquals(BlockPattern.class, createPattern.getReturnType());

        String method = extractBlock(Files.readString(STRUCTURE_SOURCE),
                "public static BlockPattern createPattern(MultiblockMachineDefinition definition) {");
        assertEquals(1, countExact(method,
                "MultiBlockStructure.INSTANCE.getFORGE_OF_THE_ANTICHRIST_MODULE()"));
        String inputAbilitySlot = extractBetween(method, ".where('B',", ".where('C',");
        assertEquals(1, countExact(inputAbilitySlot,
                "Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1)"));
        assertEquals(1, countExact(inputAbilitySlot,
                "Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1)"));
        assertFalse(inputAbilitySlot.contains("EXPORT_ITEMS"));
        assertFalse(inputAbilitySlot.contains("EXPORT_FLUIDS"));
    }

    @Test
    void displayUsesDedicatedTranslationKeysForStageMultiplierAndRecipeType() throws Exception {
        String method = extractBlock(Files.readString(CORE_SOURCE),
                "public void addDisplayText(");

        assertEquals(1, countExact(method,
                "gt_shanhai.machine.primordial_myriad_proliferation_core.stage.base\""));
        assertEquals(1, countExact(method,
                "gt_shanhai.machine.primordial_myriad_proliferation_core.stage.tier_2\""));
        assertEquals(1, countExact(method,
                "gt_shanhai.machine.primordial_myriad_proliferation_core.stage.tier_1\""));
        assertEquals(1, countExact(method,
                "gt_shanhai.machine.primordial_myriad_proliferation_core.stage\""));
        assertEquals(1, countExact(method,
                "gt_shanhai.machine.primordial_myriad_proliferation_core.multiplier\""));
        assertEquals(1, countExact(method,
                "gt_shanhai.machine.primordial_myriad_proliferation_core.recipe_type\""));
    }

    private static Path sourcePath(String fileName) {
        return Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai", "common", "machine",
                "primordial", "module", "core", fileName);
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return source.substring(openBrace, i + 1);
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }

    private static String extractBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "缺少片段起点: " + startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(end >= 0, "缺少片段终点: " + endMarker);
        return source.substring(start, end);
    }

    private static int countExact(String source, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }
}
