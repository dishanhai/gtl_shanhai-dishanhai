package com.dishanhai.gt_shanhai.common.machine.primordial.module.core;

import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialMyriadProliferationCoreRegistrationTest {

    private static final String ID = "primordial_myriad_proliferation_core";
    private static final Path MACHINES_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "DShanhaiMachines.java");
    private static final Path ENGINE_STRUCTURE_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialOmegaEngineStructure.java");

    @Test
    void machineDefinitionFieldIsPublicStaticAndUsesTheExpectedType() throws Exception {
        Field field = DShanhaiMachines.class.getField("PRIMORDIAL_MYRIAD_PROLIFERATION_CORE");

        assertTrue(Modifier.isPublic(field.getModifiers()));
        assertTrue(Modifier.isStatic(field.getModifiers()));
        assertEquals(MultiblockMachineDefinition.class, field.getType());
    }

    @Test
    void registrationUsesTheDedicatedMachineStructureAndJavaRecipeTypeFields() throws Exception {
        String source = Files.readString(MACHINES_SOURCE);
        String initializer = extractStatement(source,
                "PRIMORDIAL_MYRIAD_PROLIFERATION_CORE = GTDishanhaiRegistration.REGISTRATE");

        assertPatternCount(initializer,
                "\\.multiblock\\(\\s*\"primordial_myriad_proliferation_core\"\\s*,\\s*PrimordialMyriadProliferationCore::new\\s*\\)",
                1);
        assertPatternCount(initializer, "\\.rotationState\\(\\s*RotationState\\.ALL\\s*\\)", 1);
        assertPatternCount(initializer, """
                \\.recipeTypes\\(\\s*
                DShanhaiRecipeTypes\\.PRIMORDIAL_MYRIAD_ASCENSION_TIER_2\\s*,\\s*
                DShanhaiRecipeTypes\\.PRIMORDIAL_MYRIAD_ASCENSION_TIER_1\\s*\\)
                """, 1);
        assertPatternCount(initializer,
                "\\.pattern\\(\\s*PrimordialMyriadProliferationCoreStructure::createPattern\\s*\\)", 1);
        assertPatternCount(initializer, "\\.register\\(\\s*\\)\\s*;\\s*$", 1);
        assertEquals(0, countExact(initializer, "GTRegistries.RECIPE_TYPES"),
                "注册必须直接使用 Java 正式配方类型字段，禁止运行时查表");
    }

    @Test
    void engineModuleSlotExplicitlyAcceptsTheCoreExactlyOnce() throws Exception {
        String source = Files.readString(ENGINE_STRUCTURE_SOURCE);
        String methodBody = extractBlock(source,
                "public static BlockPattern createPattern(MultiblockMachineDefinition definition) {");
        String moduleDefinition = extractStatement(methodBody, "Block myriadProliferationCore =");
        String moduleSlotPredicate = extractBetween(methodBody, ".where('J',", "// K:");

        assertPatternCount(moduleDefinition, """
                Block\\s+myriadProliferationCore\\s*=\\s*ForgeRegistries\\.BLOCKS\\.getValue\\(\\s*
                new\\s+ResourceLocation\\(\\s*"gt_shanhai"\\s*,\\s*"primordial_myriad_proliferation_core"\\s*\\)
                \\s*\\)\\s*;
                """, 1);
        assertPatternCount(moduleSlotPredicate,
                "\\.or\\(\\s*Predicates\\.blocks\\(\\s*myriadProliferationCore\\s*\\)\\s*\\)", 1);
        assertEquals(1, countExact(source, "\"gt_shanhai\", \"primordial_myriad_proliferation_core\""),
                "模块方块只应查表一次");
        assertTrue(moduleSlotPredicate.startsWith(".where('J',"), "新核心必须绑定 J 模块位谓词");
    }

    private static String extractStatement(String source, String marker) {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, "缺少语句锚点: " + marker);
        int end = source.indexOf(';', start);
        assertTrue(end >= 0, "语句未闭合: " + marker);
        return source.substring(start, end + 1);
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

    private static void assertPatternCount(String source, String regex, int expected) {
        Matcher matcher = Pattern.compile(regex, Pattern.COMMENTS | Pattern.DOTALL).matcher(source);
        int count = 0;
        while (matcher.find()) count++;
        assertEquals(expected, count, "模式出现次数不符: " + regex);
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
