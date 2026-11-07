package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：獨立 AE 部件的註冊、結構及身份邊界。 */
class StellarCloudAECoreTest {
    private static final Path JAVA = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine");

    @Test
    void dedicatedPartIsRegisteredBeforeTheLibraryAndRequiredExactlyOnceInIridiumCasings() throws Exception {
        String machines = Files.readString(JAVA.resolve("DShanhaiMachines.java"));
        assertTrue(machines.contains("CLOUD_PATTERN_LIBRARY_AE_CORE = StellarCloudAECoreMachine.register()"));
        assertTrue(machines.indexOf("CLOUD_PATTERN_LIBRARY_AE_CORE =") < machines.indexOf("CLOUD_PATTERN_LIBRARY ="));
        String structure = Files.readString(JAVA.resolve("part/cloud/StellarCloudLibraryStructure.java"));
        assertTrue(structure.contains("palette.put('I', palette.get('I').or("),
                "AE 核心必須替換銥強化機械方塊位置");
        assertFalse(structure.contains("palette.put('H', palette.get('H').or("),
                "中空機械方塊位置必須保持普通機殼");
        assertTrue(structure.contains("CLOUD_PATTERN_LIBRARY_AE_CORE.getBlock()") && structure.contains(".setExactLimit(1)"));
        assertTrue(structure.contains("'H', \"kubejs:hollow_casing\""));
    }

    @Test
    void structureAndCoreTooltipsDescribeIridiumReplacementInBothLanguages() throws Exception {
        Path languageRoot = Path.of("src/main/resources/assets/gt_shanhai/lang");
        for (String language : new String[]{"zh_cn", "en_us"}) {
            var translations = JsonParser.parseString(Files.readString(languageRoot.resolve(language + ".json")))
                    .getAsJsonObject();
            String casingName = language.equals("zh_cn") ? "铱强化机械方块" : "Iridium Casing";
            String oldCasingName = language.equals("zh_cn") ? "中空机械方块" : "Hollow Casing";
            for (String key : new String[]{"gt_shanhai.cloud.ae_core.tooltip.role", "gt_shanhai.cloud.tooltip.structure"}) {
                String tooltip = translations.get(key).getAsString();
                assertTrue(tooltip.contains(casingName), language + ": " + key);
                assertFalse(tooltip.contains(oldCasingName), language + ": " + key);
            }
        }
    }

    @Test
    void controllerUsesOnlyTheAttachedCoresGridAndKeepsItsExistingLibraryOwner() throws Exception {
        String machine = Files.readString(JAVA.resolve("part/cloud/StellarCloudLibraryMachine.java"));
        assertTrue(machine.contains("public boolean aeBindingReady()"));
        assertTrue(machine.contains("public @Nullable UUID boundPlayerId()"));
        assertFalse(machine.contains("new GridNodeHolder(this)"), "The controller cannot bypass its AE part");
        assertTrue(machine.contains("core.readyFor(this, owner)"));
        assertTrue(machine.contains("if (core == null || !aeBindingReady()) return false;"));
        assertTrue(machine.contains("var node = core.getMainNode().getNode()"));
        assertTrue(machine.contains("node.getGrid() == grid"));
        assertTrue(machine.contains("data.defaultLibrary(owner).id()"));
    }

    @Test
    void coreBindingIsPersistedOwnerCheckedAndRemovedFromDroppedItems() throws Exception {
        Path source = JAVA.resolve("part/cloud/StellarCloudAECoreMachine.java");
        assertTrue(Files.exists(source), "A real multiblock AE part must provide the connection");
        String core = Files.readString(source);
        assertTrue(core.contains("extends MultiblockPartMachine"));
        assertTrue(core.contains("GridNodeHolder(this)"));
        assertTrue(core.contains("EnumSet.allOf(Direction.class)"));
        assertTrue(core.contains("@Persisted private UUID owner, boundPlayer"));
        assertTrue(core.contains("owner.equals(player.getUUID())"));
        assertTrue(core.contains("getMainNode().setOwningPlayer(player)"));
        assertTrue(core.contains("getMainNode().setOwningPlayerId(-1)"));
        assertTrue(core.contains("tag.remove(\"owner\")") && core.contains("tag.remove(\"boundPlayer\")"));
        assertTrue(core.contains("getControllers().size() == 1"));
        assertTrue(core.contains("boundPlayer.equals(libraryOwner)") && core.contains("getOwningPlayerProfileId()"));
        assertTrue(core.contains("StellarCloudAECoreUi.create("));
        String ui = Files.readString(JAVA.resolve("part/cloud/StellarCloudAECoreUi.java"));
        assertTrue(ui.contains("GlassPanelTexture.smokePanel()"));
    }

    @Test
    void partHasARecipeAndAnItemModelBackedByASixFaceCube() throws Exception {
        var recipe = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/data/gt_shanhai/recipes/crafting_shaped/me_system/cloud_pattern_library_ae_core.json")))
                .getAsJsonObject();
        assertEquals("gt_shanhai:cloud_pattern_library_ae_core", recipe.getAsJsonObject("result").get("item").getAsString());
        var item = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/gt_shanhai/models/item/cloud_pattern_library_ae_core.json"))).getAsJsonObject();
        assertEquals("gt_shanhai:block/cloud_pattern_library_ae_core", item.get("parent").getAsString());
        var block = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/gt_shanhai/models/block/cloud_pattern_library_ae_core.json"))).getAsJsonObject();
        assertEquals("minecraft:block/cube_all", block.get("parent").getAsString());
        assertEquals("gt_shanhai:block/casings/recipe_type_pattern_buffer_casing", block.getAsJsonObject("textures").get("all").getAsString());
        assertTrue(Files.exists(Path.of("src/main/resources/assets/gt_shanhai/textures/block/casings/recipe_type_pattern_buffer_casing.png")));
    }
}
