package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：管理區、列表命中區及背包的實際座標契約。 */
class StellarCloudLibraryUiTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/cloud");

    private static Class<?> layout() throws Exception {
        assertNotNull(StellarCloudLibraryUiTest.class.getResource("StellarCloudLibraryLayout.class"),
                "The cloud library needs a bounded two-column layout");
        return Class.forName("com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudLibraryLayout");
    }

    private static int[] rect(Object value) throws Exception {
        var type = value.getClass();
        return new int[]{(int) type.getMethod("x").invoke(value), (int) type.getMethod("y").invoke(value),
                (int) type.getMethod("width").invoke(value), (int) type.getMethod("height").invoke(value)};
    }

    @Test
    void everyPanelFitsTheRootAndHasItsOwnSpace() throws Exception {
        var layout = layout();
        int width = layout.getField("WIDTH").getInt(null);
        int height = layout.getField("HEIGHT").getInt(null);
        assertTrue(width <= 312 && height <= 322, "Fit the 321x352 GUI shown in the user's screenshot");
        List<int[]> panels = new ArrayList<>();
        for (String name : new String[]{"HEADER", "FILTER", "CATALOG", "PAGER", "TRANSFER", "DETAIL",
                "STATUS", "INVENTORY", "ACCESS"}) {
            int[] box = rect(layout.getField(name).get(null));
            assertTrue(box[0] >= 8 && box[1] >= 8 && box[0] + box[2] <= width - 8
                    && box[1] + box[3] <= height - 8, name);
            for (int[] other : panels) {
                assertFalse(box[0] < other[0] + other[2] && box[0] + box[2] > other[0]
                        && box[1] < other[1] + other[3] && box[1] + box[3] > other[1], name);
            }
            panels.add(box);
        }
    }

    @Test
    void allThirtySixPatternCellsFitInNineColumnsAndFourRows() throws Exception {
        var layout = layout();
        int[] catalog = rect(layout.getField("CATALOG").get(null));
        int count = layout.getField("PAGE_SIZE").getInt(null);
        assertEquals(36, count);
        assertEquals(9, layout.getField("COLUMNS").getInt(null));
        assertEquals(4, layout.getField("ROWS").getInt(null));
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int[] row = rect(layout.getMethod("cell", int.class).invoke(null, i));
            assertTrue(row[0] >= catalog[0] && row[0] + row[2] <= catalog[0] + catalog[2]);
            assertTrue(row[1] >= catalog[1] && row[1] + row[3] <= catalog[1] + catalog[3]);
            assertTrue(row[2] >= 20 && row[3] >= 20);
            for (int[] other : cells) assertFalse(row[0] < other[0] + other[2] && row[0] + row[2] > other[0]
                    && row[1] < other[1] + other[3] && row[1] + row[3] > other[1]);
            cells.add(row);
        }
        int[] inventory = rect(layout.getField("INVENTORY").get(null));
        assertTrue(inventory[2] >= 172 && inventory[3] >= 86, "Do not clip the hotbar");
    }

    @Test
    void glassCompositionKeepsServerActionsAndReadOnlyCatalogSlots() throws Exception {
        assertTrue(Files.exists(ROOT.resolve("StellarCloudLibraryUi.java")), "Extract the library presentation");
        String ui = Files.readString(ROOT.resolve("StellarCloudLibraryUi.java"));
        String shared = Files.readString(ROOT.resolve("StellarCloudUiWidgets.java"));
        String machine = Files.readString(ROOT.resolve("StellarCloudLibraryMachine.java"));
        assertTrue(machine.contains("StellarCloudLibraryUi.create("));
        assertTrue(ui.contains("GlassPanelTexture.smokePanel()"));
        assertTrue(ui.contains("GlassPanelTexture.smokeButtonOverlay()"));
        assertTrue(shared.contains("GlassSurface.smokeControl("));
        assertFalse(ui.contains("GuiTextures."));
        assertTrue(ui.contains("session::uploadOriginal") && ui.contains("session::withdrawSelected"));
        assertTrue(ui.contains("session.access(true)") && ui.contains("session.access(false)"));
        assertTrue(ui.contains("session.select(index, expected)"));
        assertTrue(ui.contains("session.rowPreview(index)"));
        assertTrue(ui.contains("new CachedPatternSlotWidget("), "Render output item/fluid and amount, not a generic pattern name");
        assertTrue(ui.contains("setCanTakeItems(false).setCanPutItems(false)"), "Catalog previews cannot insert or extract originals");
        assertTrue(ui.contains("session.rowTooltip(index)"));
        assertTrue(ui.contains("setSlotBackground("));
        assertTrue(shared.contains("enableScissor(") && shared.contains("disableScissor()"));
        assertTrue(ui.contains("session::uploadDisabledReason") && ui.contains("session::withdrawDisabledReason"));
        assertTrue(ui.contains("session::accessDisabledReason"));
        assertTrue(machine.contains("openingAuthorized(server)") && machine.contains("actionAuthorized(player)"));
    }

    @Test
    void allNewLabelsAndHintsExistInBothLanguages() throws Exception {
        var zh = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/gt_shanhai/lang/zh_cn.json"))).getAsJsonObject();
        var en = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/gt_shanhai/lang/en_us.json"))).getAsJsonObject();
        for (String suffix : new String[]{"ui.catalog", "ui.empty", "ui.transfer", "ui.selected", "ui.no_selection",
                "ui.inventory", "ui.access", "ui.access_hint", "ui.search_hint", "ui.type_hint", "ui.input_hint",
                "ui.output_hint", "ui.selected_type", "ui.selection_hint", "ui.network_online", "ui.network_offline",
                "ui.details_expand", "ui.details_collapse", "ui.access_expand", "ui.access_collapse", "ui.fold_hint"}) {
            String key = "gt_shanhai.cloud." + suffix;
            assertTrue(zh.has(key), key);
            assertTrue(en.has(key), key);
            assertFalse(zh.get(key).getAsString().isBlank(), key);
            assertFalse(en.get(key).getAsString().isBlank(), key);
        }
    }
}
