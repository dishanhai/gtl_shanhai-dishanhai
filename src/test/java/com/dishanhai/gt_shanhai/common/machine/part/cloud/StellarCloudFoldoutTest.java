package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：詳情／權限只有獨立呈現狀態，視口不能佔用原件與背包命中區。 */
class StellarCloudFoldoutTest {
    private static final String PACKAGE = "com.dishanhai.gt_shanhai.common.machine.part.cloud.";

    @Test void bothPanelsStartClosedAndCanBeToggledIndependently() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "StellarCloudLibraryLayout$Foldouts");
        Object state = type.getDeclaredConstructor().newInstance();
        assertEquals(false, type.getMethod("detailsOpen").invoke(state));
        assertEquals(false, type.getMethod("accessOpen").invoke(state));
        type.getMethod("toggleDetails").invoke(state);
        assertEquals(true, type.getMethod("detailsOpen").invoke(state));
        assertEquals(false, type.getMethod("accessOpen").invoke(state));
        type.getMethod("toggleAccess").invoke(state);
        assertEquals(true, type.getMethod("detailsOpen").invoke(state));
        assertEquals(true, type.getMethod("accessOpen").invoke(state));
        type.getMethod("toggleDetails").invoke(state);
        assertEquals(false, type.getMethod("detailsOpen").invoke(state));
        assertEquals(true, type.getMethod("accessOpen").invoke(state));
    }

    @Test void viewportBoundsReserveAHeaderAndNeverReachSlotsOrInventory() throws Exception {
        Class<?> layout = Class.forName(PACKAGE + "StellarCloudLibraryLayout");
        Class<?> rect = Class.forName(PACKAGE + "StellarCloudLibraryLayout$Rect");
        for (String panelName : new String[]{"DETAIL", "ACCESS"}) {
            Object panel = layout.getField(panelName).get(null);
            Object viewport = layout.getMethod("viewport", rect).invoke(null, panel);
            int x = number(rect, viewport, "x"), y = number(rect, viewport, "y");
            int width = number(rect, viewport, "width"), height = number(rect, viewport, "height");
            assertTrue(x >= number(rect, panel, "x") + 4);
            assertTrue(y >= number(rect, panel, "y") + 22);
            assertTrue(x + width <= number(rect, panel, "x") + number(rect, panel, "width") - 4);
            assertTrue(y + height <= number(rect, panel, "y") + number(rect, panel, "height") - 4);
            assertTrue(width >= 80 && height >= 50);
            for (String persistent : new String[]{"TRANSFER", "CATALOG", "PAGER", "INVENTORY"}) {
                Object other = layout.getField(persistent).get(null);
                assertFalse(x < number(rect, other, "x") + number(rect, other, "width")
                        && x + width > number(rect, other, "x")
                        && y < number(rect, other, "y") + number(rect, other, "height")
                        && y + height > number(rect, other, "y"));
            }
        }
    }

    @Test void selectionPacketsCarryDisplayedIdentityForBothNormalAndShiftClicks() throws Exception {
        String ui = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/cloud/StellarCloudLibraryUi.java"));
        String sync = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/cloud/StellarCloudUiSync.java"));
        assertTrue(ui.contains("!identity.equals(rows.get(index).id())"));
        assertTrue(ui.contains("controller.quickWithdraw(index)"));
        assertTrue(ui.contains("controller.select(index)"));
        assertTrue(sync.contains("buffer.writeUUID(expected)"));
        assertTrue(sync.contains("session.select(buffer.readVarInt(), buffer.readUUID())"));
        assertTrue(sync.contains("request(id, ActionKind.QUICK_WITHDRAW, target, index"));
    }

    @Test void openMenusOwnTheForegroundAndFoldoutHeadersConsumeOtherButtons() throws Exception {
        String ui = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/cloud/StellarCloudLibraryUi.java"));
        assertTrue(ui.contains("selector.isExpanded()"), "An open menu must suppress slot and inventory tooltips below it");
        assertTrue(ui.contains("selector.drawInForeground(graphics, mouseX, mouseY, partialTicks)"));
        assertTrue(ui.contains("select.setHoverTooltips(recipeTypeName(candidate))"));
        assertTrue(ui.contains("if (button != 0) return true"));
    }

    @Test void anEmptyCellCanClearSelectionWithoutSelectingANewlyArrivingOriginal() throws Exception {
        String ui = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/cloud/StellarCloudLibraryUi.java"));
        assertTrue(ui.contains("if (!isShiftDown()) writeClientAction(2, buffer -> {})"),
                "Only a normal empty-cell click requests clearing the selected original");
        assertTrue(ui.contains("if (id == 2) session.clearSelection(index)"),
                "The server must recheck that the clicked cell remains empty");
        assertFalse(ui.contains("session.select(index)"), "An empty cell cannot select a newly arrived row by index");
    }

    @Test void serverRejectionsReplacePreviousActionFeedback() throws Exception {
        String ui = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/cloud/StellarCloudLibraryUi.java"));
        String sync = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part/cloud/StellarCloudUiSync.java"));
        assertTrue(ui.contains("controller.feedbackKey()"));
        assertTrue(sync.contains("controller.acceptResult(result)"));
        assertTrue(sync.contains("session.uiAction(kind, target, slot, expected)"));
    }

    private int number(Class<?> type, Object value, String field) throws Exception {
        return (int) type.getMethod(field).invoke(value);
    }
}
