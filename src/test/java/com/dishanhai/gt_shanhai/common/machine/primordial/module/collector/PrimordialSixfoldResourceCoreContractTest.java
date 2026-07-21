package com.dishanhai.gt_shanhai.common.machine.primordial.module.collector;

import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialSixfoldResourceCoreContractTest {

    private static final Path MACHINES_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "DShanhaiMachines.java");
    private static final Path ENGINE_STRUCTURE_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialOmegaEngineStructure.java");

    @Test
    void moduleAndLogicUseTheStandardPrimordialParallelProcessingHierarchy() throws Exception {
        assertEquals(PrimordialParallelProcessingModuleBase.class,
                PrimordialSixfoldResourceCore.class.getSuperclass());
        assertEquals(PrimordialModuleRecipeLogic.class,
                PrimordialSixfoldResourceCoreLogic.class.getSuperclass());

        Method createLogic = PrimordialSixfoldResourceCore.class.getMethod("createRecipeLogic", Object[].class);
        assertEquals(PrimordialSixfoldResourceCoreLogic.class, createLogic.getReturnType());

        Method getLogic = PrimordialSixfoldResourceCore.class.getMethod("getRecipeLogic");
        assertEquals(PrimordialSixfoldResourceCoreLogic.class, getLogic.getReturnType());
    }

    @Test
    void machineDefinitionFieldIsPublicStaticAndUsesTheExpectedType() throws Exception {
        Field field = DShanhaiMachines.class.getField("PRIMORDIAL_SIXFOLD_RESOURCE_CORE");

        assertTrue(Modifier.isPublic(field.getModifiers()));
        assertTrue(Modifier.isStatic(field.getModifiers()));
        assertEquals(MultiblockMachineDefinition.class, field.getType());
    }

    @Test
    void registrationWiresRequiredTypesAndOptionalLargeVoidPumpInOrder() throws Exception {
        String source = Files.readString(MACHINES_SOURCE);
        String initializer = extractStatement(source,
                "PRIMORDIAL_SIXFOLD_RESOURCE_CORE = GTDishanhaiRegistration.REGISTRATE");
        String recipeTypesFactory = extractBlock(source,
                "private static GTRecipeType[] buildSixfoldResourceRecipeTypes() {");

        assertPatternCount(initializer,
                "\\.multiblock\\(\\s*\"primordial_sixfold_resource_core\"\\s*,\\s*PrimordialSixfoldResourceCore::new\\s*\\)",
                1);
        assertPatternCount(initializer, "\\.rotationState\\(\\s*RotationState\\.ALL\\s*\\)", 1);
        assertPatternCount(initializer,
                "\\.recipeTypes\\(\\s*buildSixfoldResourceRecipeTypes\\(\\)\\s*\\)", 1);
        assertTrue(recipeTypesFactory.indexOf("GTLRecipeTypes.ELEMENT_COPYING_RECIPES")
                < recipeTypesFactory.indexOf("GTLRecipeTypes.DRILLING_MODULE_RECIPES"));
        assertTrue(recipeTypesFactory.indexOf("GTLRecipeTypes.DRILLING_MODULE_RECIPES")
                < recipeTypesFactory.indexOf("PrimordialSixfoldResourceRecipeTypes.findLargeVoidPumpIfExtendLoaded()"));
        assertTrue(recipeTypesFactory.indexOf("PrimordialSixfoldResourceRecipeTypes.findLargeVoidPumpIfExtendLoaded()")
                < recipeTypesFactory.indexOf("GTLRecipeTypes.DOOR_OF_CREATE_RECIPES"));
        assertTrue(recipeTypesFactory.contains("if (largeVoidPump != null)"));
        assertTrue(recipeTypesFactory.contains("types.add(largeVoidPump);"));
        assertTrue(recipeTypesFactory.contains("GTLRecipeTypes.FISSION_REACTOR_RECIPES"));
        assertTrue(recipeTypesFactory.contains("GTLRecipeTypes.LARGE_GAS_COLLECTOR_RECIPES"));
        assertPatternCount(initializer,
                "\\.pattern\\(\\s*PrimordialAssemblyLineModuleStructure::createPattern\\s*\\)", 1);
        assertPatternCount(initializer, "\\.register\\(\\s*\\)\\s*;\\s*$", 1);
    }

    @Test
    void engineModuleSlotExplicitlyAcceptsTheCore() throws Exception {
        String source = Files.readString(ENGINE_STRUCTURE_SOURCE);
        String methodBody = extractBlock(source,
                "public static BlockPattern createPattern(MultiblockMachineDefinition definition) {");
        String moduleDefinition = extractStatement(methodBody, "Block sixfoldResourceCore =");
        String moduleSlotPredicate = extractBetween(methodBody, ".where('J',", "// K:");

        assertPatternCount(moduleDefinition, """
                Block\\s+sixfoldResourceCore\\s*=\\s*ForgeRegistries\\.BLOCKS\\.getValue\\(\\s*
                new\\s+ResourceLocation\\(\\s*\"gt_shanhai\"\\s*,\\s*\"primordial_sixfold_resource_core\"\\s*\\)
                \\s*\\)\\s*;
                """, 1);
        assertPatternCount(moduleSlotPredicate,
                "\\.or\\(\\s*Predicates\\.blocks\\(\\s*sixfoldResourceCore\\s*\\)\\s*\\)", 1);
        assertTrue(moduleSlotPredicate.startsWith(".where('J',"), "新核心必须绑定 J 模块位谓词");
    }

    @Test
    void languageFilesProvideChineseAndEnglishBlockMachineModeAndModuleNames() throws Exception {
        assertLanguage("zh_cn.json", "原初六源统御核心", "§6六源统御中", List.of(
                "§6统御六源，汇聚元素、星海与虚空资源",
                "§7配方类型：元素复制 / 太空钻井 / 大型虚空泵",
                "§7创造之门 / 裂变反应堆 / 大型集气室",
                "§7只提供原初模块通用并行加工，不模拟裂变堆专属机制",
                "§7需安装在引擎模块位",
                "§d按模块等级提供并行处理能力，直接从电网取电"));
        assertLanguage("en_us.json", "Primordial Sixfold Resource Core", "§6Sixfold Resource Processing", List.of(
                "§6Rules the six sources, gathering elemental, cosmic, and void resources",
                "§7Recipe types: Element Copying / Space Drilling / Large Void Pump",
                "§7Door of Creation / Fission Reactor / Large Gas Collector",
                "§7Provides only standard primordial parallel processing; does not emulate fission reactor mechanics",
                "§7Must be installed in an engine module slot",
                "§dParallel capacity scales with the installed module tier; draws power directly from the grid"));
    }

    private static void assertLanguage(String fileName, String expectedName, String expectedMode,
                                       List<String> expectedTooltips) throws Exception {
        Path path = Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang", fileName);
        JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();

        assertEquals(expectedName, json.get("block.gt_shanhai.primordial_sixfold_resource_core").getAsString());
        assertEquals(expectedName,
                json.get("gt_shanhai.machine.primordial_sixfold_resource_core.name").getAsString());
        assertEquals(expectedMode,
                json.get("gt_shanhai.machine.primordial_sixfold_resource_core.mode").getAsString());
        assertEquals(expectedName, json.get("module.primordial_sixfold_resource_core.name").getAsString());
        for (int i = 0; i < expectedTooltips.size(); i++) {
            assertEquals(expectedTooltips.get(i), json.get(
                    "gt_shanhai.multiblock.primordial_sixfold_resource_core.tooltip." + i).getAsString());
        }
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
        return source.substring(start, end).trim();
    }

    private static void assertPatternCount(String source, String regex, int expectedCount) {
        Matcher matcher = Pattern.compile(regex, Pattern.COMMENTS | Pattern.DOTALL).matcher(source);
        int actualCount = 0;
        while (matcher.find()) actualCount++;
        assertEquals(expectedCount, actualCount, "语义契约匹配次数异常: " + regex);
    }
}
