package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSlotAccess;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.api.gui.MEPatternCatalystUIManager;
import org.gtlcore.gtlcore.common.machine.multiblock.part.PaginationUIManager;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public class RecipeTypePatternBufferPartMachine extends MEPatternBufferPartMachine
        implements RecipeTypePatternSlotAccess {

    public static final int DEFAULT_PATTERNS_PER_ROW = 9;
    public static final int DEFAULT_ROWS_PER_PAGE = 6;
    public static final int DEFAULT_MAX_PAGES = 3;
    public static final int DEFAULT_PATTERN_COUNT = DEFAULT_PATTERNS_PER_ROW * DEFAULT_ROWS_PER_PAGE * DEFAULT_MAX_PAGES;
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            RecipeTypePatternBufferPartMachine.class, MEPatternBufferPartMachine.MANAGED_FIELD_HOLDER);

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
        WidgetGroup group = new WidgetGroup(0, 0, this.paginationUIManager.getUiWidth(), this.paginationUIManager.getUiHeight());
        group.addWidget(new LabelWidget(8, 2, () -> this.isOnline ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));
        group.addWidget(new AETextInputButtonWidget(this.paginationUIManager.getUiWidth() - 78, 2, 70, 10)
                .setText(this.customName)
                .setOnConfirm(this::setCustomName)
                .setButtonTooltips(new Component[]{Component.translatable("gui.gtceu.rename.desc")}));
        MEPatternCatalystUIManager catalystUIManager = new MEPatternCatalystUIManager(group.getSizeWidth() + 4,
                this.catalystItems, this.catalystFluids, this.cacheRecipeCount, this::removeSlotFromGTRecipeCache);
        group.waitToAdded(catalystUIManager);
        group.addWidget(this.paginationUIManager.createPaginationUI(catalystUIManager::toggleFor));
        return group;
    }

    @Override
    public @NotNull ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
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
