package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuixuIntegrateResourcesTest {
    private static final Path JAVA = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/guixu");

    @Test void patternBuffersUseOptionalExplicitBlocksAndPreservePhysicalIo() throws Exception {
        String source = Files.readString(JAVA.resolve("GuixuVisionCoreStructure.java"));
        for (String id : List.of("gtceu:me_pattern_buffer", "gtladditions:me_super_pattern_buffer",
                "gtceu:me_wildcard_pattern_buffer", "gt_shanhai:recipe_type_pattern_buffer")) {
            assertTrue(source.contains('"' + id + '"'), id);
        }
        assertTrue(source.contains("ForgeRegistries.BLOCKS.containsKey("), "未知 ID 不得退化成空氣");
        for (String ability : List.of("INPUT_ENERGY", "OUTPUT_ENERGY", "IMPORT_ITEMS", "EXPORT_ITEMS",
                "IMPORT_FLUIDS", "EXPORT_FLUIDS")) assertTrue(source.contains("PartAbility." + ability));
    }

    @Test void hostDescriptionExistsInBothLanguagesAndIsRegistered() throws Exception {
        String source = Files.readString(JAVA.resolve("GuixuVisionCoreMachines.java"));
        assertTrue(source.contains(".tooltips("));
        for (String locale : List.of("zh_cn", "en_us")) {
            var lang = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/gt_shanhai/lang/" + locale + ".json"))).getAsJsonObject();
            for (int i = 0; i < 8; i++) {
                String key = "gt_shanhai.multiblock.guixu_vision_core.tooltip." + i;
                assertTrue(lang.has(key), locale + "/" + key);
                assertFalse(lang.get(key).getAsString().isBlank());
            }
        }
    }

    @Test void sourceExampleRecipeUsesExistingLocalTypeWithoutReplacingMachineRecipe() throws Exception {
        var example = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/data/gt_shanhai/recipes/guixu_matter_decompression/cobblestone_to_diamond.json")))
                .getAsJsonObject();
        assertEquals("gtceu:guixu_matter_decompression", example.get("type").getAsString());
        assertEquals(20, example.get("duration").getAsInt());
        assertEquals("minecraft:diamond", example.getAsJsonObject("outputs").getAsJsonArray("item")
                .get(0).getAsJsonObject().getAsJsonObject("content").get("item").getAsString());
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/data/gt_shanhai/recipes/assembler_module/guixu_vision_core.json")));
    }
}
