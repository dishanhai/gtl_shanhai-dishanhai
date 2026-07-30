package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StellarPatternSlotWarningRenderSourceTest {

    private static final Path ROOT = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part");
    private static final Path SLOT_WIDGET = ROOT.resolve("CachedPatternSlotWidget.java");
    private static final Path PAGINATION = ROOT.resolve("CachedPatternPaginationUIManager.java");
    private static final Path MACHINE = ROOT.resolve("RecipeTypePatternBufferPartMachine.java");

    @Test
    void cachedSlotWidgetDrawsTwoPixelRedWarningBorderWithoutDecodePath() throws IOException {
        String widget = Files.readString(SLOT_WIDGET);

        assertTrue(widget.contains("WARNING_BORDER_WIDTH = 2"));
        assertTrue(widget.contains("WARNING_BORDER_COLOR"));
        assertTrue(widget.contains("BooleanSupplier warningSupplier"));
        assertTrue(widget.contains("gtShanhai$drawWarningBorder(graphics"));
        assertTrue(widget.contains("DrawerHelper.drawSolidRect(graphics, x, y, width, WARNING_BORDER_WIDTH"));
        assertTrue(widget.contains("DrawerHelper.drawSolidRect(graphics, x, y + height - WARNING_BORDER_WIDTH"));
        assertTrue(!widget.contains("gtShanhai$drawWarningBorder(graphics") ||
                widget.indexOf("gtShanhai$drawWarningBorder(graphics") > widget.indexOf("DrawerHelper.drawItemStack"),
                "红框绘制必须在物品绘制之后，避免被样板图标盖住");
    }

    @Test
    void paginationPassesMachineWarningPredicateIntoEachSlot() throws IOException {
        String pagination = Files.readString(PAGINATION);
        String machine = Files.readString(MACHINE);

        assertTrue(pagination.contains("Function<Integer, Boolean> isWarning"));
        assertTrue(pagination.contains("() -> Boolean.TRUE.equals(isWarning.apply(finalSlot))"));
        assertTrue(machine.contains("this::gtShanhai$isPatternSlotWarning"));
    }

    @Test
    void machineSyncsWarningSlotsAndRecomputesOnPatternChange() throws IOException {
        String machine = Files.readString(MACHINE);

        assertTrue(machine.contains("@DescSynced"));
        assertTrue(machine.contains("private String warningSlots"));
        assertTrue(machine.contains("gtShanhai$setPatternSlotWarning"));
        assertTrue(machine.contains("StellarPatternWarningPolicy.encodeWarningSlots"));
        assertTrue(machine.contains("StellarPatternWarningPolicy.decodeWarningSlots"));
        assertTrue(machine.contains("StellarPatternWarningPolicy.isWrongHost"));
        assertTrue(machine.contains("RecipeTypeSharedSearchSets::isShared"));
        assertTrue(machine.contains("gtShanhai$refreshPatternSlotWarning(index)"));
    }

    @Test
    void bulkRecipeTypeRefreshDoesNotMarkSlotsWhenHostMetadataIsStillInitializing() throws IOException {
        String machine = Files.readString(MACHINE);
        int refreshAllStart = machine.indexOf("private void refreshPatternRecipeTypes()");
        int refreshOneStart = machine.indexOf("private void refreshPatternRecipeType", refreshAllStart + 1);
        String refreshAllBody = machine.substring(refreshAllStart, refreshOneStart);

        assertTrue(!refreshAllBody.contains("gtShanhai$refreshPatternSlotWarning"),
                "onLoad 批量刷新配方类型时不得把未恢复的主机元数据当成错主机");
        assertTrue(machine.contains("hostRecipeTypeIds.isEmpty()"));
        assertTrue(machine.contains("gtShanhai$setPatternSlotWarning(slot, false);"));
    }
}
