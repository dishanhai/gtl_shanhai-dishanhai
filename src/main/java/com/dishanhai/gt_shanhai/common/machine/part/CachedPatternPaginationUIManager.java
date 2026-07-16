package com.dishanhai.gt_shanhai.common.machine.part;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;

import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * 星律专用分页 UI。缓存每个槽位的样板输出，避免渲染时每帧重复解码样板 NBT。
 */
public final class CachedPatternPaginationUIManager {

    private final int uiWidth;
    private final int uiHeight;
    private final int patternsPerRow;
    private final int rowsPerPage;
    private final int maxPages;
    private final int maxPatternCount;
    private final IItemTransfer patternInventory;
    private final Function<Integer, Boolean> isCached;
    private final IntConsumer onPatternChange;

    @DescSynced
    private int currentPageIndex;

    private WidgetGroup paginationUI;

    @Nullable
    private IntConsumer onMiddleClicked;

    public CachedPatternPaginationUIManager(int patternsPerRow, int rowsPerPage, int maxPages,
                                            int uiWidth, int uiHeight,
                                            IntConsumer onPatternChange,
                                            Function<Integer, Boolean> isCached,
                                            IItemTransfer patternInventory) {
        this.patternsPerRow = patternsPerRow;
        this.rowsPerPage = rowsPerPage;
        this.maxPages = maxPages;
        this.maxPatternCount = patternsPerRow * rowsPerPage * maxPages;
        this.uiWidth = uiWidth;
        this.uiHeight = uiHeight;
        this.onPatternChange = onPatternChange;
        this.isCached = isCached;
        this.patternInventory = patternInventory;
    }

    public int getUiWidth() {
        return uiWidth;
    }

    public int getUiHeight() {
        return uiHeight;
    }

    public WidgetGroup createPaginationUI(@Nullable IntConsumer onMiddleClicked) {
        this.onMiddleClicked = onMiddleClicked;
        WidgetGroup basePage = new WidgetGroup(0, 0, uiWidth, uiHeight);
        this.paginationUI = rebuildPatternSlots(currentPageIndex);
        basePage.addWidget(paginationUI);
        createPageControls(basePage);
        return basePage;
    }

    private WidgetGroup rebuildPatternSlots(int pageIndex) {
        int patternAreaHeight = rowsPerPage * 18;
        int startSlot = pageIndex * rowsPerPage * patternsPerRow;
        int endSlot = Math.min(startSlot + rowsPerPage * patternsPerRow, maxPatternCount);
        WidgetGroup pageGroup = new WidgetGroup(0, 16, uiWidth, patternAreaHeight);
        recreatePatternSlots(pageGroup, startSlot, endSlot);
        return pageGroup;
    }

    private void recreatePatternSlots(WidgetGroup pageGroup, int startSlot, int endSlot) {
        for (int slotIndex = startSlot; slotIndex < endSlot; slotIndex++) {
            int finalSlot = slotIndex;
            int slotInPage = slotIndex - startSlot;
            int row = slotInPage / patternsPerRow;
            int column = slotInPage % patternsPerRow;
            int x = uiWidth == 106
                    ? (106 - patternsPerRow * 18) / 2 + column * 18
                    : 8 + column * 18;
            int y = row * 18;

            CachedPatternSlotWidget slot = new CachedPatternSlotWidget(
                    patternInventory, slotIndex, x, y);
            slot.setOnPatternSlotChanged(() -> {
                slot.invalidatePatternCache();
                onPatternChange.accept(finalSlot);
            });
            slot.setOccupiedTexture(GuiTextures.SLOT);
            slot.setBackground(GuiTextures.SLOT, GuiTextures.PATTERN_OVERLAY);

            if (onMiddleClicked != null) {
                slot.setOnMiddleClick(() -> onMiddleClicked.accept(finalSlot));
            }
            slot.setOnAddedTooltips((widget, tooltips) -> {
                if (isCached.apply(finalSlot)) {
                    tooltips.add(Component.translatable("gtceu.machine.pattern.recipe.cache"));
                }
            });
            pageGroup.addWidget(slot);
        }
    }

    private void createPageControls(WidgetGroup parentGroup) {
        int pageControlY = 16 + rowsPerPage * 18 + 4;
        parentGroup.addWidget(new ButtonWidget(8, pageControlY, 30, 12,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("<<")), clickData -> {
                    if (currentPageIndex > 0) {
                        currentPageIndex--;
                        refreshPage(currentPageIndex);
                    }
                }));
        int pageIndicatorX = (uiWidth - 12) / 2;
        parentGroup.addWidget(new LabelWidget(pageIndicatorX, pageControlY + 2,
                () -> (currentPageIndex + 1) + " / " + maxPages));
        parentGroup.addWidget(new ButtonWidget(uiWidth - 38, pageControlY, 30, 12,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(">>")), clickData -> {
                    if (currentPageIndex < maxPages - 1) {
                        currentPageIndex++;
                        refreshPage(currentPageIndex);
                    }
                }));
    }

    private void refreshPage(int pageIndex) {
        paginationUI.clearAllWidgets();
        int startSlot = pageIndex * rowsPerPage * patternsPerRow;
        int endSlot = Math.min(startSlot + rowsPerPage * patternsPerRow, maxPatternCount);
        recreatePatternSlots(paginationUI, startSlot, endSlot);
    }
}
