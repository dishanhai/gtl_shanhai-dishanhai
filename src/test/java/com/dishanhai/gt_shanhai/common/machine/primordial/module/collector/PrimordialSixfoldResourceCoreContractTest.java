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
    void registrationWiresTheSixRequiredRecipeTypesInOrder() throws Exception {
        String source = Files.readString(MACHINES_SOURCE);
        Pattern registration = Pattern.compile("""
                \\.multiblock\\(\"primordial_sixfold_resource_core\",\\s*PrimordialSixfoldResourceCore::new\\)
                \\s*\\.rotationState\\(RotationState\\.ALL\\)
                \\s*\\.recipeTypes\\(\\s*GTLRecipeTypes\\.ELEMENT_COPYING_RECIPES,\\s*GTLRecipeTypes\\.DRILLING_MODULE_RECIPES,\\s*PrimordialSixfoldResourceRecipeTypes\\.requireLargeVoidPump\\(\\),\\s*GTLRecipeTypes\\.DOOR_OF_CREATE_RECIPES,\\s*GTLRecipeTypes\\.FISSION_REACTOR_RECIPES,\\s*GTLRecipeTypes\\.LARGE_GAS_COLLECTOR_RECIPES\\)
                """, Pattern.COMMENTS);

        assertTrue(registration.matcher(source).find(), "机器注册必须按确认顺序绑定六种实际配方类型");
    }

    @Test
    void engineModuleSlotExplicitlyAcceptsTheCore() throws Exception {
        String source = Files.readString(ENGINE_STRUCTURE_SOURCE);
        Pattern whitelist = Pattern.compile("""
                Block\\s+sixfoldResourceCore\\s*=\\s*ForgeRegistries\\.BLOCKS\\.getValue\\(\\s*
                new\\s+ResourceLocation\\(\"gt_shanhai\",\\s*\"primordial_sixfold_resource_core\"\\)\\s*\\);
                [\\s\\S]*?\\.or\\(Predicates\\.blocks\\(sixfoldResourceCore\\)\\)
                """, Pattern.COMMENTS);

        assertTrue(whitelist.matcher(source).find(), "原始终焉引擎模块位必须显式接受新核心");
    }

    @Test
    void languageFilesProvideChineseAndEnglishBlockMachineModeAndModuleNames() throws Exception {
        assertLanguage("zh_cn.json", "原初六源统御核心", "§6六源统御中");
        assertLanguage("en_us.json", "Primordial Sixfold Resource Core", "§6Sixfold Resource Processing");
    }

    private static void assertLanguage(String fileName, String expectedName, String expectedMode) throws Exception {
        Path path = Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang", fileName);
        JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();

        assertEquals(expectedName, json.get("block.gt_shanhai.primordial_sixfold_resource_core").getAsString());
        assertEquals(expectedName,
                json.get("gt_shanhai.machine.primordial_sixfold_resource_core.name").getAsString());
        assertEquals(expectedMode,
                json.get("gt_shanhai.machine.primordial_sixfold_resource_core.mode").getAsString());
        assertEquals(expectedName, json.get("module.primordial_sixfold_resource_core.name").getAsString());
    }
}
