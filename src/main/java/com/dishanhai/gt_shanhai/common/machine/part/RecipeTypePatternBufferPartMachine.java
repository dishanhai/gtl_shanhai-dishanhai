package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSlotAccess;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.gregtechceu.gtceu.utils.FormattingUtil;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.api.gui.MEPatternCatalystUIManager;
import org.gtlcore.gtlcore.client.gui.widget.AEDualConfigWidget;
import org.gtlcore.gtlcore.common.machine.multiblock.part.PaginationUIManager;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEStockingPatternBufferPartMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * 星律样板总成：在 GTLCore 库存ME样板总成（MEStockingPatternBufferPartMachine）基础上叠加配方类型过滤。
 * 库存输入区域的"只模拟提取判断存在、真扣料时才提取"逻辑完全继承自父类，本类不重复实现。
 */
public class RecipeTypePatternBufferPartMachine extends MEStockingPatternBufferPartMachine
        implements RecipeTypePatternSlotAccess {

    public static final int DEFAULT_PATTERNS_PER_ROW = 9;
    public static final int DEFAULT_ROWS_PER_PAGE = 6;
    public static final int DEFAULT_MAX_PAGES = 3;
    public static final int DEFAULT_PATTERN_COUNT = DEFAULT_PATTERNS_PER_ROW * DEFAULT_ROWS_PER_PAGE * DEFAULT_MAX_PAGES;
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            RecipeTypePatternBufferPartMachine.class, MEStockingPatternBufferPartMachine.MANAGED_FIELD_HOLDER);

    private final PaginationUIManager paginationUIManager;
    private final String[] patternRecipeTypeIds;

    public RecipeTypePatternBufferPartMachine(@Nullable IMachineBlockEntity holder) {
        this(holder, DEFAULT_PATTERNS_PER_ROW, DEFAULT_ROWS_PER_PAGE, DEFAULT_MAX_PAGES);
    }

    public RecipeTypePatternBufferPartMachine(@Nullable IMachineBlockEntity holder, int patternsPerRow,
            int rowsPerPage, int maxPages) {
        super(holder, patternsPerRow * rowsPerPage * maxPages, IO.BOTH);
        this.patternRecipeTypeIds = new String[patternsPerRow * rowsPerPage * maxPages];
        Arrays.fill(this.patternRecipeTypeIds, "");

        int uiWidth = Math.max(patternsPerRow * 18 + 16, 106);
        int uiHeight = rowsPerPage * 18 + 28;
        this.paginationUIManager = new PaginationUIManager(patternsPerRow, rowsPerPage, maxPages, uiWidth, uiHeight,
                this::onPatternChange,
                slot -> Boolean.valueOf(slot != null && slot >= 0 && slot < this.cacheRecipe.length && this.cacheRecipe[slot]),
                getPatternInventory());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        refreshPatternRecipeTypes();
    }

    @Override
    protected void onPatternChange(int index) {
        super.onPatternChange(index);
        refreshPatternRecipeType(index);
    }

    @Override
    protected void refreshAllByProduct() {
        super.refreshAllByProduct();
        refreshPatternRecipeTypes();
    }

    @Override
    public boolean pasteFromTag(net.minecraft.nbt.CompoundTag tag) {
        boolean result = super.pasteFromTag(tag);
        refreshPatternRecipeTypes();
        return result;
    }

    @Override
    public net.minecraft.nbt.CompoundTag cutToTag(net.minecraft.nbt.CompoundTag tags) {
        net.minecraft.nbt.CompoundTag result = super.cutToTag(tags);
        refreshPatternRecipeTypes();
        return result;
    }

    @Override
    public String gtShanhai$getPatternRecipeTypeId(int slot) {
        if (slot < 0 || slot >= patternRecipeTypeIds.length) return "";
        String typeId = patternRecipeTypeIds[slot];
        return typeId == null ? "" : typeId;
    }

    @Override
    public GTRecipe gtShanhai$getPatternRecipe(int slot) {
        if (slot < 0 || slot >= patternRecipeTypeIds.length || slot >= getPatternInventory().getSlots()) return null;
        ItemStack stack = getPatternInventory().getStackInSlot(slot);
        GTRecipe recipe = PatternRecipeTypeHelper.ensureRecipe(stack, getLevel());
        if (recipe != null) {
            patternRecipeTypeIds[slot] = PatternRecipeTypeHelper.readRecipeTypeId(stack);
            if (!patternRecipeTypeIds[slot].isEmpty()) {
                getPatternInventory().setStackInSlot(slot, stack);
            }
        }
        return recipe;
    }

    @Override
    public boolean gtShanhai$slotAllowsRecipe(int slot, GTRecipe recipe) {
        return PatternRecipeTypeHelper.recipeMatchesTypeId(recipe, gtShanhai$getPatternRecipeTypeId(slot));
    }

    @Override
    public void attachConfigurators(@NotNull ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);
    }

    @Override
    public @NotNull Widget createUIWidget() {
        int width = this.paginationUIManager.getUiWidth();
        int patternBottom = this.paginationUIManager.getUiHeight();
        int stockLabelY = patternBottom + 6;
        int stockWidgetY = stockLabelY + 12;
        WidgetGroup group = new WidgetGroup(0, 0, width, stockWidgetY + 95);
        group.addWidget(new LabelWidget(8, 2, () -> this.isOnline ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));
        group.addWidget(new AETextInputButtonWidget(width - 78, 2, 70, 10)
                .setText(this.customName)
                .setOnConfirm(this::setCustomName)
                .setButtonTooltips(new Component[]{Component.translatable("gui.gtceu.rename.desc")}));
        MEPatternCatalystUIManager catalystUIManager = new MEPatternCatalystUIManager(width + 4,
                this.catalystItems, this.catalystFluids, this.cacheRecipeCount, this::removeSlotFromGTRecipeCache);
        group.waitToAdded(catalystUIManager);
        group.addWidget(this.paginationUIManager.createPaginationUI(catalystUIManager::toggleFor));

        group.addWidget(new LabelWidget(8, stockLabelY, () -> Component.translatable("gui.gtlcore.stock_input_config").getString()));
        group.addWidget(new LabelWidget(width - 30, stockLabelY, () ->
                FormattingUtil.formatNumbers(countConfiguredStockSlots()) + " / 32"));
        group.addWidget(new AEDualConfigWidget((width - 144) / 2, stockWidgetY, this.stockItemHandler, this.stockFluidHandler, this::setPage, this.page));
        return group;
    }

    @Override
    public @NotNull ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    /** 统计已配置的库存输入格数（物品+流体），用于 UI 上的 "X / 32" 显示。 */
    private int countConfiguredStockSlots() {
        int count = 0;
        for (int i = 0; i < this.stockItemHandler.getConfigurableSlots(); i++) {
            if (this.stockItemHandler.getConfigurableSlot(i).getConfig() != null) count++;
        }
        for (int i = 0; i < this.stockFluidHandler.getConfigurableSlots(); i++) {
            if (this.stockFluidHandler.getConfigurableSlot(i).getConfig() != null) count++;
        }
        return count;
    }

    private void refreshPatternRecipeTypes() {
        for (int slot = 0; slot < patternRecipeTypeIds.length; slot++) {
            refreshPatternRecipeType(slot);
        }
    }

    private void refreshPatternRecipeType(int slot) {
        if (slot < 0 || slot >= patternRecipeTypeIds.length || slot >= getPatternInventory().getSlots()) return;
        ItemStack stack = getPatternInventory().getStackInSlot(slot);
        patternRecipeTypeIds[slot] = PatternRecipeTypeHelper.ensureRecipeTypeId(stack, getLevel());
        if (!patternRecipeTypeIds[slot].isEmpty()) {
            getPatternInventory().setStackInSlot(slot, stack);
        }
    }
}
