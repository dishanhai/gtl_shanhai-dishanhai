package com.dishanhai.gt_shanhai.common.machine.primordial.module.core;

import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    @Test
    void tooltipBuilderUsesOnlyDedicatedTranslationKeys() throws Exception {
        String source = Files.readString(MACHINES_SOURCE);
        String invocation = extractInvocation(source,
                "PRIMORDIAL_MYRIAD_PROLIFERATION_CORE.setTooltipBuilder(");
        Matcher keyMatcher = Pattern.compile(
                "Component\\.translatable\\(\"(gt_shanhai\\.multiblock\\.primordial_myriad_proliferation_core\\.tooltip\\.[0-6])\"\\)")
                .matcher(invocation);
        List<String> keys = new ArrayList<>();
        while (keyMatcher.find()) keys.add(keyMatcher.group(1));

        assertEquals(List.of(
                tooltipKey(0), tooltipKey(1), tooltipKey(2), tooltipKey(3),
                tooltipKey(4), tooltipKey(5), tooltipKey(6)), keys);
        assertEquals(7, countExact(invocation, "tooltips.add("));
        assertEquals(0, countExact(invocation, "Component.literal("));
    }

    @Test
    void languageFilesProvideAllRuntimeDisplayTooltipAndRecipeTypeKeys() throws Exception {
        assertLanguage("zh_cn.json", new LanguageExpectation(
                "原初万象衍生核心", "§d万象衍生中",
                "§7基础衍生", "§b二级晋升", "§d一级晋升",
                "§7当前阶段：%s", "§6配方产出倍率：%s×", "§7当前配方类型：%s",
                "二级原初万象晋升", "一级原初万象晋升",
                List.of(
                        "§d万象衍生，令一切配方产物跨越数量的极限",
                        "§7需挂载于原始终焉引擎的模块位",
                        "§7核心空闲时提供10倍产出；实际运行二级晋升配方时提升至100倍",
                        "§7实际运行一级晋升配方时提升至1000倍；多核心不叠加，只取最高倍率",
                        "§7仅放大物品、流体与持续配方输出，并强制绝对100%产出",
                        "§8不改变配方输入、EU、时长与直接Tick产出",
                        "§7两种晋升配方均为4物品+4流体输入，不需要任何输出槽")));
        assertLanguage("en_us.json", new LanguageExpectation(
                "Primordial Myriad Proliferation Core", "§dMyriad Proliferation",
                "§7Base Proliferation", "§bTier II Ascension", "§dTier I Ascension",
                "§7Current Stage: %s", "§6Recipe Output Multiplier: %s×", "§7Current Recipe Type: %s",
                "Primordial Myriad Ascension Tier II", "Primordial Myriad Ascension Tier I",
                List.of(
                        "§dProliferates all recipe products beyond quantitative limits",
                        "§7Must be mounted in a Primordial Omega Engine module slot",
                        "§7Provides 10x output while idle; rises to 100x while a Tier II ascension recipe is running",
                        "§7Rises to 1000x while a Tier I ascension recipe is running; multiple cores do not stack, only the highest applies",
                        "§7Amplifies only item, fluid, and sustained recipe outputs and makes their chance an absolute 100%",
                        "§8Does not change recipe inputs, EU, duration, or direct tick outputs",
                        "§7Both ascension recipe types accept 4 item and 4 fluid inputs and require no output slots")));
    }

    private static void assertLanguage(String fileName, LanguageExpectation expected) throws Exception {
        Path path = Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang", fileName);
        JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();

        assertEquals(expected.name(), value(json, "block.gt_shanhai." + ID));
        assertEquals(expected.name(), value(json, "gt_shanhai.machine." + ID + ".name"));
        assertEquals(expected.mode(), value(json, "gt_shanhai.machine." + ID + ".mode"));
        assertEquals(expected.name(), value(json, "module." + ID + ".name"));
        assertEquals(expected.baseStage(), value(json, machineKey("stage.base")));
        assertEquals(expected.tier2Stage(), value(json, machineKey("stage.tier_2")));
        assertEquals(expected.tier1Stage(), value(json, machineKey("stage.tier_1")));
        assertEquals(expected.stageDisplay(), value(json, machineKey("stage")));
        assertEquals(expected.multiplierDisplay(), value(json, machineKey("multiplier")));
        assertEquals(expected.recipeTypeDisplay(), value(json, machineKey("recipe_type")));
        for (int i = 0; i < expected.tooltips().size(); i++) {
            assertEquals(expected.tooltips().get(i), value(json, tooltipKey(i)));
        }
        assertRecipeTypeTranslations(json, "primordial_myriad_ascension_tier_2", expected.tier2RecipeType());
        assertRecipeTypeTranslations(json, "primordial_myriad_ascension_tier_1", expected.tier1RecipeType());
    }

    private static void assertRecipeTypeTranslations(JsonObject json, String recipeTypeId, String expected) {
        assertEquals(expected, value(json, "gtceu." + recipeTypeId));
        assertEquals(expected, value(json, "gtceu.recipe_type." + recipeTypeId));
        assertEquals(expected, value(json, "recipe_type." + recipeTypeId));
    }

    private static String value(JsonObject json, String key) {
        assertTrue(json.has(key), "缺少语言键: " + key);
        return json.get(key).getAsString();
    }

    private static String machineKey(String suffix) {
        return "gt_shanhai.machine." + ID + "." + suffix;
    }

    private static String tooltipKey(int index) {
        return "gt_shanhai.multiblock." + ID + ".tooltip." + index;
    }

    private static String extractStatement(String source, String marker) {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, "缺少语句锚点: " + marker);
        int end = source.indexOf(';', start);
        assertTrue(end >= 0, "语句未闭合: " + marker);
        return source.substring(start, end + 1);
    }

    private static String extractInvocation(String source, String marker) {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, "缺少调用锚点: " + marker);
        int openParenthesis = source.indexOf('(', start);
        int depth = 0;
        for (int i = openParenthesis; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '(') depth++;
            if (current == ')' && --depth == 0) {
                int semicolon = source.indexOf(';', i);
                return source.substring(start, semicolon + 1);
            }
        }
        throw new AssertionError("调用未闭合: " + marker);
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

    private record LanguageExpectation(
            String name,
            String mode,
            String baseStage,
            String tier2Stage,
            String tier1Stage,
            String stageDisplay,
            String multiplierDisplay,
            String recipeTypeDisplay,
            String tier2RecipeType,
            String tier1RecipeType,
            List<String> tooltips) {}
}
