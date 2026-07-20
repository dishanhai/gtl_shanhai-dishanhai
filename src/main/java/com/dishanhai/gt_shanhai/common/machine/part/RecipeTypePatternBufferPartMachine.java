package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.api.gui.configurators.FancyConfiguratorSidebarPage;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeExecutionGuard;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSearchHelper;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSlotAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferMachineAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.dishanhai.gt_shanhai.common.item.WildcardPatternBridge;
import com.dishanhai.gt_shanhai.common.item.WildcardPatternRecipeTypeBinding;
import com.dishanhai.gt_shanhai.mixin.MEPatternBufferSlot2PatternAccessor;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.UITemplate;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.gregtechceu.gtceu.utils.FormattingUtil;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMaps;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.api.gui.MEPatternCatalystUIManager;
import org.gtlcore.gtlcore.client.gui.widget.AEDualConfigWidget;
import org.gtlcore.gtlcore.client.gui.widget.PatternCycleWidget;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTrait;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEStockingPatternBufferPartMachine;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    public static final int WILDCARD_PATTERN_SLOT_COUNT = 5;
    private static final IGuiTexture STOCK_INPUT_ICON =
            new ResourceTexture("gt_shanhai:textures/gui/stock_input_panel.png");
    private static final IGuiTexture WILDCARD_INPUT_ICON = new TextTexture("*");
    private static final int[] NO_ACTIVE_UNCACHED_SLOTS = new int[0];
    private static final int PARENT_REFUND = 0;
    private static final int PARENT_SHARED_ITEM = 1;
    private static final int PARENT_SHARED_FLUID = 2;
    private static final int PARENT_SHARED_CIRCUIT = 3;
    private static final int PARENT_BYPRODUCT = 4;
    private static final int PARENT_TERMINAL_VISIBILITY = 5;
    private static final int PARENT_PATTERN_CIRCUIT = 6;
    private static final int PARENT_ADVANCED_ME = 7;
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            RecipeTypePatternBufferPartMachine.class, MEStockingPatternBufferPartMachine.MANAGED_FIELD_HOLDER);

    private final CachedPatternPaginationUIManager paginationUIManager;
    private final String[] patternRecipeTypeIds;

    @Persisted
    private final ItemStackTransfer wildcardPatternInventory;
    @Persisted
    private boolean outputMultiplierModeEnabled;
    @Persisted
    private int patternOutputMultiplier = 1;
    @DescSynced
    @Persisted
    private int cachedHostOutputMultiplier = 1;
    private final List<IPatternDetails> wildcardPatterns;
    private final List<ItemStack> wildcardPatternStacks;
    private final List<MEPatternBufferPartMachineBase.InternalSlot> wildcardInternalSlots;
    private final List<GTRecipe> wildcardResolvedRecipes;
    private final List<String> wildcardAssignedRecipeTypeIds;
    private final Object2IntMap<IPatternDetails> wildcardPatternToSlot;
    private final Int2ReferenceMap<GTRecipe> wildcardRecipeCache;
    private final Int2ReferenceMap<OutputMultiplierPatternCacheEntry> outputMultiplierPatternCache;
    private final RecipeTypePatternWildcardPersistence wildcardPersistence;
    private final InternalInventory combinedTerminalPatternInventory;
    private IntConsumer wildcardRemoveSlotFromMap;
    private boolean rebuildingWildcardPatterns;
    private int selectedWildcardMotherSlot;
    private int[] activeUncachedSlotsScratch;

    public RecipeTypePatternBufferPartMachine(@Nullable IMachineBlockEntity holder) {
        this(holder, DEFAULT_PATTERNS_PER_ROW, DEFAULT_ROWS_PER_PAGE, DEFAULT_MAX_PAGES);
    }

    public RecipeTypePatternBufferPartMachine(@Nullable IMachineBlockEntity holder, int patternsPerRow,
            int rowsPerPage, int maxPages) {
        super(holder, patternsPerRow * rowsPerPage * maxPages, IO.BOTH);
        this.activeUncachedSlotsScratch =
                new int[patternsPerRow * rowsPerPage * maxPages + WILDCARD_PATTERN_SLOT_COUNT];
        this.patternRecipeTypeIds = new String[patternsPerRow * rowsPerPage * maxPages];
        Arrays.fill(this.patternRecipeTypeIds, "");
        this.wildcardPatterns = new ObjectArrayList<>();
        this.wildcardPatternStacks = new ObjectArrayList<>();
        this.wildcardInternalSlots = new ObjectArrayList<>();
        this.wildcardResolvedRecipes = new ObjectArrayList<>();
        this.wildcardAssignedRecipeTypeIds = new ObjectArrayList<>();
        this.wildcardPatternToSlot = new Object2IntOpenHashMap<>();
        this.wildcardPatternToSlot.defaultReturnValue(-1);
        this.wildcardRecipeCache = new Int2ReferenceOpenHashMap<>();
        this.outputMultiplierPatternCache = new Int2ReferenceOpenHashMap<>();
        this.wildcardPersistence = new RecipeTypePatternWildcardPersistence();
        this.wildcardPatternInventory = new ItemStackTransfer(WILDCARD_PATTERN_SLOT_COUNT);
        this.wildcardPatternInventory.setFilter(WildcardPatternBridge::isWildcardPattern);
        this.wildcardPatternInventory.setOnContentsChanged(this::onWildcardPatternInventoryChanged);
        this.combinedTerminalPatternInventory = createCombinedTerminalPatternInventory(super.getTerminalPatternInventory());

        int uiWidth = Math.max(patternsPerRow * 18 + 16, 106);
        int uiHeight = rowsPerPage * 18 + 28;
        this.paginationUIManager = new CachedPatternPaginationUIManager(
                patternsPerRow, rowsPerPage, maxPages, uiWidth, uiHeight,
                this::onPatternChange,
                slot -> Boolean.valueOf(slot != null && slot >= 0 && slot < this.cacheRecipe.length && this.cacheRecipe[slot]),
                getPatternInventory());
    }

    @Override
    protected MEPatternBufferRecipeHandlerTrait createRecipeHandler(IO io) {
        return new RecipeTypePatternRecipeHandler(this, io);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if ((Object) this instanceof VirtualPatternBufferMachineAccess access) {
            access.gtShanhai$restoreVirtualTargetsFromPatterns(getAvailablePatterns());
        }
        refreshPatternRecipeTypes();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(1, () -> refreshWildcardPatterns(true)));
        }
    }

    @Override
    protected void onPatternChange(int index) {
        clearOutputMultiplierPatternCache();
        super.onPatternChange(index);
        if (!isRemote()) {
            refreshVisibleOutputMultiplierPattern(index, true);
            RecipeTypePatternSearchHelper.clearPatternSlotState(this, index);
        }
        refreshPatternRecipeType(index);
    }

    @Override
    protected void refreshAllByProduct() {
        clearOutputMultiplierPatternCache();
        super.refreshAllByProduct();
        refreshVisibleOutputMultiplierPatterns(true);
        refreshPatternRecipeTypes();
        refreshWildcardRecipeTypes();
    }

    @Override
    protected void invalidateRecipeCaches() {
        clearOutputMultiplierPatternCache();
        super.invalidateRecipeCaches();
        clearWildcardRecipeCache();
    }

    @Override
    public void onMachineRemoved() {
        rebuildingWildcardPatterns = true;
        refundWildcardSlots();
        clearInventory(wildcardPatternInventory);
        clearWildcardPatternData(true);
        rebuildingWildcardPatterns = false;
        super.onMachineRemoved();
    }

    @Override
    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        List<CompoundTag> internalSlotData = new ArrayList<>(wildcardInternalSlots.size());
        for (MEPatternBufferPartMachineBase.InternalSlot internalSlot : wildcardInternalSlots) {
            internalSlotData.add(internalSlot.isActive() ? internalSlot.serializeNBT() : null);
        }
        wildcardPersistence.save(tag, maxPatternCount, wildcardPatternStacks, internalSlotData, wildcardRecipeCache);
    }

    @Override
    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        wildcardPersistence.load(tag);
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
        if (slot < 0) return "";
        String typeId;
        if (slot < patternRecipeTypeIds.length) {
            typeId = patternRecipeTypeIds[slot];
        } else {
            int localSlot = slot - maxPatternCount;
            if (localSlot < 0 || localSlot >= wildcardResolvedRecipes.size()) return "";
            GTRecipe recipe = wildcardResolvedRecipes.get(localSlot);
            typeId = recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null
                    ? "" : recipe.recipeType.registryName.toString();
        }
        return typeId == null ? "" : typeId;
    }

    @Override
    public GTRecipe gtShanhai$getPatternRecipe(int slot) {
        return gtShanhai$getPatternRecipe(slot, gtShanhai$getPatternInferenceInputs());
    }

    @Override
    public GTRecipe gtShanhai$getPatternRecipe(int slot, GenericStack[] availableCatalystInputs) {
        if (slot >= maxPatternCount) {
            int localSlot = slot - maxPatternCount;
            return localSlot >= 0 && localSlot < wildcardResolvedRecipes.size()
                    ? wildcardResolvedRecipes.get(localSlot) : null;
        }
        if (slot < 0 || slot >= patternRecipeTypeIds.length || slot >= getPatternInventory().getSlots()) return null;
        ItemStack stack = getPatternInventory().getStackInSlot(slot);
        // 只读：本槽同时在向 AE 网络供货，绝不能像过去那样借"自愈"之机悄悄改写 NBT
        // （AEItemKey 含 NBT，改动即变身份，见 PatternRecipeTypeHelper.peekRecipe 文档）。
        return PatternRecipeTypeHelper.peekRecipe(stack, getLevel(), availableCatalystInputs);
    }

    @Override
    public GenericStack[] gtShanhai$getPatternInferenceInputs() {
        List<GenericStack> inputs = new ArrayList<>();
        appendVisibleStock(inputs, stockItemHandler);
        appendVisibleStock(inputs, stockFluidHandler);
        for (int slot = 0; slot < getSharedCatalystInventory().getSlots(); slot++) {
            ItemStack stack = getSharedCatalystInventory().getStackInSlot(slot);
            if (!stack.isEmpty()) {
                inputs.add(new GenericStack(AEItemKey.of(stack), stack.getCount()));
            }
        }
        for (int tank = 0; tank < getSharedCatalystTank().getTanks(); tank++) {
            FluidStack stack = getSharedCatalystTank().getFluidInTank(tank);
            if (stack == null || stack.isEmpty()) continue;
            AEFluidKey key = stack.getTag() == null
                    ? AEFluidKey.of(stack.getFluid())
                    : AEFluidKey.of(stack.getFluid(), stack.getTag());
            inputs.add(new GenericStack(key, stack.getAmount()));
        }
        return inputs.toArray(GenericStack[]::new);
    }

    private static void appendVisibleStock(List<GenericStack> inputs,
            com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlotList handler) {
        for (int slot = 0; slot < handler.getConfigurableSlots(); slot++) {
            GenericStack stock = handler.getConfigurableSlot(slot).getStock();
            if (stock != null && stock.what() != null && stock.amount() > 0L) {
                inputs.add(stock);
            }
        }
    }

    public IPatternDetails gtShanhai$applyOutputMultiplier(IPatternDetails pattern, ItemStack stack) {
        if (!outputMultiplierModeEnabled || pattern == null || getLevel() == null) return pattern;
        String recipeTypeId = PatternRecipeTypeHelper.readRecipeTypeId(stack);
        int multiplier = getPatternOutputMultiplier();
        long recipeRevision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        int cacheKey = makeOutputMultiplierPatternCacheKey(pattern, stack, recipeTypeId, multiplier, recipeRevision);
        OutputMultiplierPatternCacheEntry cached = outputMultiplierPatternCache.get(cacheKey);
        if (cached != null && cached.matches(pattern, stack, recipeTypeId, multiplier, recipeRevision)) {
            return cached.pattern();
        }
        IPatternDetails rewritten = VirtualPatternEncodingHelper.rewritePatternOutputMultiplier(
                pattern, getLevel(), recipeTypeId, multiplier);
        outputMultiplierPatternCache.put(cacheKey,
                new OutputMultiplierPatternCacheEntry(pattern, stack, recipeTypeId, multiplier, recipeRevision, rewritten));
        return rewritten;
    }

    public boolean isOutputMultiplierModeEnabled() {
        return outputMultiplierModeEnabled;
    }

    public int getPatternOutputMultiplier() {
        return Math.max(1, Math.min(1000, patternOutputMultiplier));
    }

    public void setOutputMultiplierModeEnabled(boolean enabled) {
        applyOutputMultiplierSettings(enabled, patternOutputMultiplier);
    }

    public void setPatternOutputMultiplier(int multiplier) {
        applyOutputMultiplierSettings(outputMultiplierModeEnabled, multiplier);
    }

    public int getConnectedHostOutputMultiplier() {
        if (isRemote()) return getCachedHostOutputMultiplier();
        int multiplier = resolveConnectedHostOutputMultiplier();
        updateCachedHostOutputMultiplier(multiplier);
        return getCachedHostOutputMultiplier();
    }

    private int resolveConnectedHostOutputMultiplier() {
        int multiplier = 1;
        for (var controller : getControllers()) {
            multiplier = Math.max(multiplier, readControllerHostOutputMultiplier(controller));
        }
        return clampOutputMultiplier(multiplier);
    }

    private static int readControllerHostOutputMultiplier(Object controller) {
        if (controller instanceof PrimordialOmegaEngineMachine host) {
            return host.getMountedOutputMultiplier();
        }
        if (controller instanceof PrimordialOmegaEngineModuleBase module) {
            return module.getHostOutputMultiplier();
        }
        return 1;
    }

    private int getCachedHostOutputMultiplier() {
        return clampOutputMultiplier(cachedHostOutputMultiplier);
    }

    private void updateCachedHostOutputMultiplier(int multiplier) {
        int clamped = clampOutputMultiplier(multiplier);
        if (cachedHostOutputMultiplier == clamped) return;
        cachedHostOutputMultiplier = clamped;
        markDirty();
    }

    private static int clampOutputMultiplier(int multiplier) {
        return Math.max(1, Math.min(1000, multiplier));
    }

    public void syncOutputMultiplierFromHost() {
        if (isRemote()) return;
        int multiplier = resolveConnectedHostOutputMultiplier();
        updateCachedHostOutputMultiplier(multiplier);
        applyOutputMultiplierSettings(true, multiplier);
    }

    public void syncOutputMultiplierFromPattern() {
        if (getLevel() == null) return;
        for (int slot = 0; slot < getPatternInventory().getSlots(); slot++) {
            ItemStack stack = getPatternInventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            IPatternDetails pattern = PatternDetailsHelper.decodePattern(stack, getLevel());
            int multiplier = VirtualPatternEncodingHelper.detectPatternOutputMultiplier(
                    pattern, PatternRecipeTypeHelper.readRecipeTypeId(stack));
            if (multiplier > 0) {
                applyOutputMultiplierSettings(true, multiplier);
                return;
            }
        }
    }

    public void refreshOutputMultiplierPatterns() {
        if (isRemote()) return;
        for (int slot = 0; slot < getPatternInventory().getSlots(); slot++) {
            if (!getPatternInventory().getStackInSlot(slot).isEmpty()) {
                onPatternChange(slot);
            }
        }
        onWildcardPatternInventoryChanged();
        invalidateRecipeCaches();
        RecipeTypePatternSearchHelper.clearPatternState(this);
    }

    private void refreshVisibleOutputMultiplierPatterns(boolean resetSlotCaches) {
        if (isRemote() || !outputMultiplierModeEnabled) return;
        boolean changed = false;
        for (int slot = 0; slot < getPatternInventory().getSlots() && slot < maxPatternCount; slot++) {
            if (refreshVisibleOutputMultiplierPattern(slot, resetSlotCaches)) {
                changed = true;
            }
        }
        if (changed) {
            reCalculatePatternSlotMap();
            needPatternSync = true;
        }
    }

    private boolean refreshVisibleOutputMultiplierPattern(int slot, boolean resetSlotCaches) {
        if (isRemote() || !outputMultiplierModeEnabled) return false;
        if (slot < 0 || slot >= maxPatternCount || slot >= getPatternInventory().getSlots()) return false;
        ItemStack patternStack = getPatternInventory().getStackInSlot(slot);
        if (patternStack.isEmpty()) return false;
        if (!((Object) this instanceof MEPatternBufferSlot2PatternAccessor access)) return false;

        Int2ObjectMap<IPatternDetails> slot2PatternMap = access.gtShanhai$getSlot2PatternMap();
        IPatternDetails currentPattern = slot2PatternMap.get(slot);
        IPatternDetails basePattern = createOutputMultiplierBasePattern(slot, patternStack);
        if (basePattern == null) return false;
        IPatternDetails effectivePattern = gtShanhai$applyOutputMultiplier(basePattern, patternStack);
        if (effectivePattern == null || samePatternDefinition(currentPattern, effectivePattern)) return false;

        slot2PatternMap.put(slot, effectivePattern);
        if (resetSlotCaches) {
            MEPatternBufferPartMachineBase.InternalSlot internalSlot = getInternalSlot(slot);
            internalSlot.getCacheManager().clearAllCaches();
            removeSlotFromGTRecipeCache(slot);
            if ((Object) this instanceof VirtualPatternBufferMachineAccess virtualAccess) {
                virtualAccess.gtShanhai$indexRefundSlot(internalSlot,
                        internalSlot.getItemInventory(), internalSlot.getFluidInventory());
            }
            refundSlot(internalSlot.getItemInventory(), internalSlot.getFluidInventory());
            if (!buffer.isEmpty()) AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource);
        }
        reCalculatePatternSlotMap();
        needPatternSync = true;
        return true;
    }

    private IPatternDetails createOutputMultiplierBasePattern(int slot, ItemStack patternStack) {
        if (getLevel() == null || patternStack.isEmpty()) return null;
        MEPatternBufferPartMachineBase.InternalSlot internalSlot = getInternalSlot(slot);
        return realPatternHelper.processPatternWithCircuit(patternStack,
                circuit -> internalSlot.getCacheManager().setCircuitCache(circuit),
                getLevel(), keepByProduct);
    }

    private static boolean samePatternDefinition(IPatternDetails first, IPatternDetails second) {
        return first == second || Objects.equals(
                first == null ? null : first.getDefinition(),
                second == null ? null : second.getDefinition());
    }

    private void applyOutputMultiplierSettings(boolean enabled, int multiplier) {
        int clamped = Math.max(1, Math.min(1000, multiplier));
        if (outputMultiplierModeEnabled == enabled && patternOutputMultiplier == clamped) return;
        outputMultiplierModeEnabled = enabled;
        patternOutputMultiplier = clamped;
        clearOutputMultiplierPatternCache();
        markDirty();
        refreshOutputMultiplierPatterns();
    }

    private int makeOutputMultiplierPatternCacheKey(IPatternDetails pattern, ItemStack stack,
            String recipeTypeId, int multiplier, long recipeRevision) {
        int result = 31 + patternDefinitionHash(pattern);
        result = 31 * result + stackKeyHash(stack);
        result = 31 * result + Objects.hashCode(recipeTypeId);
        result = 31 * result + multiplier;
        result = 31 * result + Long.hashCode(recipeRevision);
        return result;
    }

    private void clearOutputMultiplierPatternCache() {
        outputMultiplierPatternCache.clear();
    }

    private static int stackKeyHash(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        int result = 31 + Objects.hashCode(stack.getItem());
        result = 31 * result + stack.getDamageValue();
        result = 31 * result + Objects.hashCode(stack.getTag());
        return result;
    }

    private static int patternDefinitionHash(IPatternDetails pattern) {
        return pattern == null ? 0 : Objects.hashCode(pattern.getDefinition());
    }

    @Override
    public boolean gtShanhai$slotAllowsRecipe(int slot, GTRecipe recipe) {
        if (slot >= maxPatternCount && gtShanhai$getPatternRecipe(slot) == null) return false;
        if (PatternRecipeExecutionGuard.isAuxiliaryIORecipe(recipe)) return true;
        return PatternRecipeTypeHelper.recipeMatchesTypeId(recipe, gtShanhai$getPatternRecipeTypeId(slot));
    }

    @Override
    public int gtShanhai$getPatternSlotCount() {
        return maxPatternCount + wildcardPatternStacks.size();
    }

    @Override
    public ItemStack gtShanhai$getPatternStack(int slot) {
        if (slot >= 0 && slot < maxPatternCount) {
            return getPatternInventory().getStackInSlot(slot);
        }
        int localSlot = slot - maxPatternCount;
        return localSlot >= 0 && localSlot < wildcardPatternStacks.size()
                ? wildcardPatternStacks.get(localSlot) : ItemStack.EMPTY;
    }

    @Override
    protected @Nullable Integer getSlotIndexForPattern(IPatternDetails pattern) {
        Integer normalSlot = super.getSlotIndexForPattern(pattern);
        if (normalSlot != null) return normalSlot;
        if (wildcardPatternToSlot == null) return null;
        int slot = wildcardPatternToSlot.getInt(pattern);
        return slot >= 0 ? slot : null;
    }

    @Override
    protected int getInternalSlotCount() {
        return maxPatternCount + (wildcardInternalSlots == null ? 0 : wildcardInternalSlots.size());
    }

    @Override
    protected MEPatternBufferPartMachineBase.InternalSlot getInternalSlot(int slot) {
        if (slot >= 0 && slot < maxPatternCount) return super.getInternalSlot(slot);
        int localSlot = slot - maxPatternCount;
        if (wildcardInternalSlots != null && localSlot >= 0 && localSlot < wildcardInternalSlots.size()) {
            return wildcardInternalSlots.get(localSlot);
        }
        throw new IndexOutOfBoundsException("Wildcard pattern slot: " + slot);
    }

    @Override
    protected int[] getActiveAndUnCachedSlots() {
        int slotCount = getInternalSlotCount();
        if (activeUncachedSlotsScratch.length < slotCount) {
            activeUncachedSlotsScratch = Arrays.copyOf(activeUncachedSlotsScratch, slotCount);
        }

        int activeCount = 0;
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            MEPatternBufferPartMachineBase.InternalSlot internalSlot = getInternalSlot(slotIndex);
            if (internalSlot.isActive() && !hasRecipeCacheInSlot(slotIndex)) {
                activeUncachedSlotsScratch[activeCount++] = slotIndex;
            }
        }
        return activeCount == 0
                ? NO_ACTIVE_UNCACHED_SLOTS
                : Arrays.copyOf(activeUncachedSlotsScratch, activeCount);
    }

    @Override
    protected boolean hasRecipeCacheInSlot(int slot) {
        if (slot >= 0 && slot < maxPatternCount) return super.hasRecipeCacheInSlot(slot);
        return wildcardRecipeCache != null && wildcardRecipeCache.containsKey(slot);
    }

    @Override
    protected boolean hasPatternInSlot(int slot) {
        if (slot >= 0 && slot < maxPatternCount) return super.hasPatternInSlot(slot);
        int localSlot = slot - maxPatternCount;
        return wildcardPatterns != null && localSlot >= 0 && localSlot < wildcardPatterns.size();
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        refreshVisibleOutputMultiplierPatterns(false);
        List<IPatternDetails> patterns = new ArrayList<>(super.getAvailablePatterns());
        if (wildcardPatterns != null) patterns.addAll(wildcardPatterns);
        return List.copyOf(patterns);
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return combinedTerminalPatternInventory == null
                ? super.getTerminalPatternInventory() : combinedTerminalPatternInventory;
    }

    @Override
    protected @NotNull MEPatternTrait createMETrait() {
        return new CombinedPatternTrait();
    }

    @Override
    public void attachConfigurators(@NotNull ConfiguratorPanel configuratorPanel) {
        List<IFancyConfigurator> parentConfigurators = collectParentConfigurators();
        if (PARENT_REFUND < parentConfigurators.size()) {
            configuratorPanel.attachConfigurators(parentConfigurators.get(PARENT_REFUND));
        }
    }

    @Override
    public void attachSideTabs(TabsWidget sideTabs) {
        super.attachSideTabs(sideTabs);
        List<IFancyConfigurator> parentConfigurators = collectParentConfigurators();
        sideTabs.attachSubTab(createStockInputSidebarPage(parentConfigurators));
        sideTabs.attachSubTab(FancyConfiguratorSidebarPage.single(new WildcardPatternConfigurator()));
        sideTabs.attachSubTab(FancyConfiguratorSidebarPage.single(new OutputMultiplierConfigurator()));
        sideTabs.attachSubTab(createGroupedSidebarPage(
                "gui.gt_shanhai.pattern_buffer_shared_inputs", 2, parentConfigurators,
                PARENT_SHARED_ITEM, PARENT_SHARED_FLUID, PARENT_SHARED_CIRCUIT));
        sideTabs.attachSubTab(createGroupedSidebarPage(
                "gui.gt_shanhai.pattern_buffer_pattern_behavior", 1, parentConfigurators,
                PARENT_BYPRODUCT, PARENT_TERMINAL_VISIBILITY, PARENT_PATTERN_CIRCUIT));
    }

    private List<IFancyConfigurator> collectParentConfigurators() {
        List<IFancyConfigurator> result = new ArrayList<>();
        ConfiguratorPanel collector = new ConfiguratorPanel(0, 0) {
            @Override
            public void attachConfigurators(IFancyConfigurator... configurators) {
                result.addAll(Arrays.asList(configurators));
            }
        };
        super.attachConfigurators(collector);
        return result;
    }

    private FancyConfiguratorSidebarPage createStockInputSidebarPage(List<IFancyConfigurator> configurators) {
        StockInputConfigurator stockInput = new StockInputConfigurator();
        List<IFancyConfigurator> selected = new ArrayList<>(2);
        selected.add(stockInput);
        if (PARENT_ADVANCED_ME < configurators.size()) {
            selected.add(configurators.get(PARENT_ADVANCED_ME));
        }
        return new FancyConfiguratorSidebarPage(stockInput.getTitle(), stockInput.getIcon(),
                stockInput.getTooltips(), selected);
    }

    private FancyConfiguratorSidebarPage createGroupedSidebarPage(
            String titleKey, int firstRowColumns, List<IFancyConfigurator> configurators, int... indices) {
        List<IFancyConfigurator> selected = new ArrayList<>(indices.length);
        for (int index : indices) {
            if (index >= 0 && index < configurators.size()) {
                selected.add(configurators.get(index));
            }
        }
        Component title = Component.translatable(titleKey);
        IGuiTexture icon = selected.isEmpty() ? IGuiTexture.EMPTY : selected.get(selected.size() - 1).getIcon();
        return new FancyConfiguratorSidebarPage(title, icon, List.of(title), selected, firstRowColumns);
    }

    @Override
    public @NotNull Widget createUIWidget() {
        int width = this.paginationUIManager.getUiWidth();
        int patternBottom = this.paginationUIManager.getUiHeight();
        WidgetGroup group = new WidgetGroup(0, 0, width, patternBottom);
        group.addWidget(new LabelWidget(8, 2, () -> this.isOnline ? "gtceu.gui.me_network.online" : "gtceu.gui.me_network.offline"));
        group.addWidget(new AETextInputButtonWidget(width - 78, 2, 70, 10)
                .setText(this.customName)
                .setOnConfirm(this::setCustomName)
                .setButtonTooltips(new Component[]{Component.translatable("gui.gtceu.rename.desc")}));
        MEPatternCatalystUIManager catalystUIManager = new MEPatternCatalystUIManager(width + 4,
                this.catalystItems, this.catalystFluids, this.cacheRecipeCount, this::removeSlotFromGTRecipeCache);
        group.waitToAdded(catalystUIManager);
        group.addWidget(this.paginationUIManager.createPaginationUI(catalystUIManager::toggleFor));
        return group;
    }

    @Override
    public @NotNull ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    private class StockInputConfigurator implements IFancyConfigurator {

        @Override
        public Component getTitle() {
            return Component.translatable("gui.gtlcore.stock_input_config");
        }

        @Override
        public IGuiTexture getIcon() {
            return STOCK_INPUT_ICON;
        }

        @Override
        public Widget createConfigurator() {
            WidgetGroup group = new WidgetGroup(0, 0, 160, 109);
            group.addWidget(new LabelWidget(8, 2,
                    () -> Component.translatable("gui.gtlcore.stock_input_config").getString()));
            group.addWidget(new LabelWidget(130, 2,
                    () -> FormattingUtil.formatNumbers(countConfiguredStockSlots()) + " / 32"));
            group.addWidget(new AEDualConfigWidget(8, 14, stockItemHandler, stockFluidHandler,
                    RecipeTypePatternBufferPartMachine.this::setPage, page));
            return group;
        }
    }

    private class OutputMultiplierConfigurator implements IFancyConfigurator {

        @Override
        public Component getTitle() {
            return Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.foa_config.title");
        }

        @Override
        public IGuiTexture getIcon() {
            return new TextTexture("x");
        }

        @Override
        public Widget createConfigurator() {
            WidgetGroup group = new WidgetGroup(0, 0, 160, 100);
            group.addWidget(new LabelWidget(6, 4, () -> outputMultiplierModeEnabled
                    ? "§a" + Component.translatable(
                            "gt_shanhai.machine.recipe_type_pattern_buffer.foa_mode.enabled").getString()
                    : "§7" + Component.translatable(
                            "gt_shanhai.machine.recipe_type_pattern_buffer.foa_mode.disabled").getString()));
            group.addWidget(new LabelWidget(6, 22, () -> Component.translatable(
                    "gt_shanhai.machine.recipe_type_pattern_buffer.foa_host_multiplier",
                    getConnectedHostOutputMultiplier()).getString()));
            group.addWidget(new LabelWidget(6, 40,
                    Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.foa_multiplier").getString()));
            group.addWidget(new IntInputWidget(78, 35, 76, 18,
                    RecipeTypePatternBufferPartMachine.this::getPatternOutputMultiplier,
                    RecipeTypePatternBufferPartMachine.this::setPatternOutputMultiplier)
                    .setMin(1).setMax(1000));
            group.addWidget(new ButtonWidget(6, 58, 46, 16, new TextTexture("§f开关", -1),
                    click -> setOutputMultiplierModeEnabled(!outputMultiplierModeEnabled)));
            group.addWidget(new ButtonWidget(56, 58, 46, 16, new TextTexture("§b读宿主", -1),
                    click -> syncOutputMultiplierFromHost()));
            group.addWidget(new ButtonWidget(106, 58, 48, 16, new TextTexture("§d读样板", -1),
                    click -> syncOutputMultiplierFromPattern()));
            group.addWidget(new ButtonWidget(6, 78, 148, 16, new TextTexture("§e刷新内部样板", -1),
                    click -> refreshOutputMultiplierPatterns()));
            return group;
        }
    }

    private class WildcardPatternConfigurator implements IFancyConfigurator {

        private WidgetGroup configuratorPage;
        private DraggableScrollableWidgetGroup recipeTypeList;

        @Override
        public Component getTitle() {
            return Component.translatable("gui.gt_shanhai.wildcard_pattern_config");
        }

        @Override
        public IGuiTexture getIcon() {
            return WILDCARD_INPUT_ICON;
        }

        @Override
        public Widget createConfigurator() {
            configuratorPage = new WidgetGroup(0, 0, 160, 155);
            fillConfigurator(configuratorPage);
            return configuratorPage;
        }

        private void fillConfigurator(WidgetGroup group) {
            List<GTRecipeType> hostTypes =
                    WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(getControllers());
            group.addWidget(new LabelWidget(8, 2,
                    () -> Component.translatable("gui.gt_shanhai.wildcard_pattern_config").getString()));
            for (int slot = 0; slot < WILDCARD_PATTERN_SLOT_COUNT; slot++) {
                int motherSlot = slot;
                group.addWidget(new SlotWidget(wildcardPatternInventory, slot, 34 + slot * 18, 14)
                        .setBackground(new IGuiTexture[]{com.gregtechceu.gtceu.api.gui.GuiTextures.SLOT,
                                com.gregtechceu.gtceu.api.gui.GuiTextures.PATTERN_OVERLAY}));
                String slotText = (selectedWildcardMotherSlot == slot ? "§a" : "§7") + (slot + 1);
                group.addWidget(new ButtonWidget(35 + slot * 18, 33, 16, 10,
                        new TextTexture(slotText, -1), clickData -> {
                            selectedWildcardMotherSlot = motherSlot;
                            rebuildConfigurator();
                        }));
            }
            group.addWidget(new LabelWidget(8, 46,
                    () -> Component.translatable("gui.gt_shanhai.wildcard_pattern_slot",
                            selectedWildcardMotherSlot + 1, currentWildcardRecipeTypeText(hostTypes)).getString()));
            group.addWidget(new LabelWidget(8, 59,
                    () -> Component.translatable("gui.gt_shanhai.wildcard_pattern_expanded",
                            wildcardPatterns.size()).getString()));
            group.addWidget(new PatternCycleWidget(8, 71, 142, 36, () -> wildcardPatterns));

            recipeTypeList = new DraggableScrollableWidgetGroup(8, 111, 142, 42);
            recipeTypeList.setBackground(com.gregtechceu.gtceu.api.gui.GuiTextures.DISPLAY);
            recipeTypeList.setYScrollBarWidth(4).setYBarStyle(
                    new ColorRectTexture(0xFF3F4252),
                    new ColorRectTexture(0xFFE0E2EA).setRadius(2));
            group.addWidget(recipeTypeList);
            addRecipeTypeButtons(hostTypes);
        }

        private void addRecipeTypeButtons(List<GTRecipeType> hostTypes) {
            String assignedTypeId = currentWildcardRecipeTypeId();
            int y = 2;
            String autoText = (assignedTypeId.isEmpty() ? "§a> " : "§7")
                    + Component.translatable("gui.gt_shanhai.wildcard_pattern_auto").getString();
            recipeTypeList.addWidget(new ButtonWidget(2, y, 134, 16,
                    new TextTexture(autoText, -1), clickData -> {
                        clearWildcardRecipeType(selectedWildcardMotherSlot);
                        rebuildConfigurator();
                    }));
            y += 18;

            if (hostTypes.isEmpty()) {
                recipeTypeList.addWidget(new ImageWidget(2, y, 134, 16,
                        new TextTexture(Component.translatable(
                                "gui.gt_shanhai.wildcard_pattern_no_host_types").getString(), -1)));
                return;
            }

            for (GTRecipeType type : hostTypes) {
                if (type == null || type.registryName == null) continue;
                boolean selected = type.registryName.toString().equals(assignedTypeId);
                String text = (selected ? "§a> " : "§f") + wildcardRecipeTypeDisplayName(type);
                recipeTypeList.addWidget(new ButtonWidget(2, y, 134, 16,
                        new TextTexture(text, -1), clickData -> {
                            assignWildcardRecipeType(selectedWildcardMotherSlot, type);
                            rebuildConfigurator();
                        }));
                y += 18;
            }
        }

        private void rebuildConfigurator() {
            if (configuratorPage == null) return;
            int scrollY = recipeTypeList == null ? 0 : recipeTypeList.getScrollYOffset();
            configuratorPage.clearAllWidgets();
            fillConfigurator(configuratorPage);
            if (recipeTypeList != null) recipeTypeList.setScrollYOffset(scrollY);
        }
    }

    private String currentWildcardRecipeTypeId() {
        if (selectedWildcardMotherSlot < 0 || selectedWildcardMotherSlot >= WILDCARD_PATTERN_SLOT_COUNT) return "";
        return PatternRecipeTypeHelper.readRecipeTypeId(
                wildcardPatternInventory.getStackInSlot(selectedWildcardMotherSlot));
    }

    private String currentWildcardRecipeTypeText(List<GTRecipeType> hostTypes) {
        String typeId = currentWildcardRecipeTypeId();
        if (typeId.isEmpty()) {
            return Component.translatable("gui.gt_shanhai.wildcard_pattern_auto").getString();
        }
        for (GTRecipeType type : hostTypes) {
            if (type != null && type.registryName != null && typeId.equals(type.registryName.toString())) {
                return wildcardRecipeTypeDisplayName(type);
            }
        }
        return Component.translatable("gui.gt_shanhai.wildcard_pattern_unavailable_type", typeId).getString();
    }

    private void assignWildcardRecipeType(int slot, GTRecipeType type) {
        if (slot < 0 || slot >= WILDCARD_PATTERN_SLOT_COUNT || type == null || type.registryName == null) return;
        ItemStack stack = wildcardPatternInventory.getStackInSlot(slot);
        if (stack.isEmpty()) return;
        wildcardPatternInventory.setStackInSlot(slot,
                WildcardPatternRecipeTypeBinding.assign(stack, type.registryName.toString()));
    }

    private void clearWildcardRecipeType(int slot) {
        if (slot < 0 || slot >= WILDCARD_PATTERN_SLOT_COUNT) return;
        ItemStack stack = wildcardPatternInventory.getStackInSlot(slot);
        if (stack.isEmpty()) return;
        wildcardPatternInventory.setStackInSlot(slot, WildcardPatternRecipeTypeBinding.clear(stack));
    }

    private static String wildcardRecipeTypeDisplayName(GTRecipeType type) {
        if (type == null || type.registryName == null) return "unknown";
        String namespace = type.registryName.getNamespace();
        String path = type.registryName.getPath();
        String[] keys = {
                type.registryName.toLanguageKey(),
                "gtceu." + path,
                "gtceu.recipe_type." + path,
                "recipe_type." + path,
                "gtceu.recipe_type." + namespace + "." + path
        };
        for (String key : keys) {
            String translated = Component.translatable(key).getString();
            if (!translated.equals(key)) return translated;
        }
        return path;
    }

    private class CombinedPatternTrait extends MEPatternTrait {

        CombinedPatternTrait() {
            super(RecipeTypePatternBufferPartMachine.this);
        }

        @Override
        public RecipeTypePatternBufferPartMachine getMachine() {
            return RecipeTypePatternBufferPartMachine.this;
        }

        @Override
        public @NotNull ObjectSet<GTRecipe> getCachedGTRecipe() {
            ObjectOpenHashSet<GTRecipe> result = new ObjectOpenHashSet<>();
            for (Int2ReferenceMap.Entry<ObjectSet<GTRecipe>> entry : Int2ReferenceMaps.fastIterable(recipeMultipleCacheMap)) {
                int slot = entry.getIntKey();
                if (slot >= 0 && slot < maxPatternCount && cacheRecipe[slot]
                        && internalInventory[slot].isActive()) {
                    result.addAll(entry.getValue());
                }
            }
            if (wildcardRecipeCache != null && wildcardInternalSlots != null) {
                for (Int2ReferenceMap.Entry<GTRecipe> entry : Int2ReferenceMaps.fastIterable(wildcardRecipeCache)) {
                    int slot = entry.getIntKey() - maxPatternCount;
                    if (slot >= 0 && slot < wildcardInternalSlots.size()
                            && wildcardInternalSlots.get(slot).isActive()) {
                        result.add(entry.getValue());
                    }
                }
            }
            return result;
        }

        @Override
        public void setSlotCacheRecipe(int index, GTRecipe recipe) {
            if (index < maxPatternCount) {
                if (recipe != null && recipe.recipeType != GTRecipeTypes.DUMMY_RECIPES) {
                    ObjectSet<GTRecipe> recipes = recipeMultipleCacheMap.computeIfAbsent(index,
                            ignored -> new ObjectArraySet<>());
                    if (recipes.add(recipe)) {
                        cacheRecipe[index] = recipes.size() >= cacheRecipeCount[index];
                    }
                }
                return;
            }
            if (recipe != null && recipe.recipeType != GTRecipeTypes.DUMMY_RECIPES
                    && wildcardInternalSlots != null && wildcardRecipeCache != null
                    && index >= maxPatternCount
                    && index < maxPatternCount + wildcardInternalSlots.size()) {
                wildcardRecipeCache.put(index, recipe);
                int localSlot = index - maxPatternCount;
                wildcardResolvedRecipes.set(localSlot, recipe);
                ItemStack stack = wildcardPatternStacks.get(localSlot);
                PatternRecipeTypeHelper.writeRecipeType(stack, recipe);
            }
        }

        @Override
        public @NotNull Int2ReferenceMap<ObjectSet<GTRecipe>> getSlot2RecipesCache() {
            Int2ReferenceMap<ObjectSet<GTRecipe>> result = new Int2ReferenceOpenHashMap<>();
            result.putAll(recipeMultipleCacheMap);
            if (wildcardRecipeCache != null) {
                for (Int2ReferenceMap.Entry<GTRecipe> entry : Int2ReferenceMaps.fastIterable(wildcardRecipeCache)) {
                    ObjectSet<GTRecipe> recipes = new ObjectArraySet<>();
                    recipes.add(entry.getValue());
                    result.put(entry.getIntKey(), recipes);
                }
            }
            return result;
        }

        @Override
        public void setOnPatternChange(IntConsumer removeMapOnSlot) {
            wildcardRemoveSlotFromMap = removeMapOnSlot;
            RecipeTypePatternBufferPartMachine.this.removeSlotFromMap = removeMapOnSlot;
        }

        @Override
        public boolean hasCacheInSlot(int slot) {
            return slot >= 0 && slot < maxPatternCount ? cacheRecipe[slot]
                    : wildcardRecipeCache != null && wildcardRecipeCache.containsKey(slot);
        }
    }

    private InternalInventory createCombinedTerminalPatternInventory(InternalInventory normalInventory) {
        return new InternalInventory() {

            @Override
            public int size() {
                return normalInventory.size() + WILDCARD_PATTERN_SLOT_COUNT;
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                if (slot < normalInventory.size()) return normalInventory.getStackInSlot(slot);
                int wildcardSlot = slot - normalInventory.size();
                return wildcardSlot >= 0 && wildcardSlot < WILDCARD_PATTERN_SLOT_COUNT
                        ? wildcardPatternInventory.getStackInSlot(wildcardSlot) : ItemStack.EMPTY;
            }

            @Override
            public void setItemDirect(int slot, ItemStack stack) {
                if (slot < normalInventory.size()) {
                    normalInventory.setItemDirect(slot, stack);
                    return;
                }
                int wildcardSlot = slot - normalInventory.size();
                if (wildcardSlot >= 0 && wildcardSlot < WILDCARD_PATTERN_SLOT_COUNT) {
                    wildcardPatternInventory.setStackInSlot(wildcardSlot, stack);
                    wildcardPatternInventory.onContentsChanged(wildcardSlot);
                }
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                if (slot < normalInventory.size()) return normalInventory.isItemValid(slot, stack);
                int wildcardSlot = slot - normalInventory.size();
                return wildcardSlot >= 0 && wildcardSlot < WILDCARD_PATTERN_SLOT_COUNT
                        && WildcardPatternBridge.isWildcardPattern(stack);
            }
        };
    }

    private void onWildcardPatternInventoryChanged() {
        if (rebuildingWildcardPatterns || isRemote() || getLevel() == null) return;
        rebuildingWildcardPatterns = true;
        refundWildcardSlots();
        clearWildcardPatternData(true);
        rebuildingWildcardPatterns = false;
        refreshWildcardPatterns(false);
    }

    private void refreshWildcardPatterns(boolean restorePersistedData) {
        if (isRemote() || getLevel() == null) return;
        rebuildingWildcardPatterns = true;
        clearWildcardPatternData(false);

        Map<CompoundTag, ExpandedWildcardPattern> uniquePatterns = new LinkedHashMap<>();
        for (int motherSlot = 0; motherSlot < WILDCARD_PATTERN_SLOT_COUNT; motherSlot++) {
            ItemStack wildcardStack = wildcardPatternInventory.getStackInSlot(motherSlot);
            String assignedTypeId = PatternRecipeTypeHelper.readRecipeTypeId(wildcardStack);
            for (IPatternDetails pattern : WildcardPatternBridge.expandPatterns(wildcardStack, getLevel())) {
                ItemStack patternStack = pattern.getDefinition().toStack();
                if (patternStack.isEmpty()) continue;
                GTRecipe recipe = WildcardPatternRecipeTypeBinding.findRecipe(pattern, assignedTypeId);
                if (!assignedTypeId.isEmpty() && recipe == null) continue;
                if (recipe != null) PatternRecipeTypeHelper.writeRecipeType(patternStack, recipe);
                IPatternDetails effectivePattern = gtShanhai$applyOutputMultiplier(pattern, patternStack);
                ItemStack effectiveStack = effectivePattern.getDefinition().toStack();
                if (recipe != null) PatternRecipeTypeHelper.writeRecipeType(effectiveStack, recipe);
                uniquePatterns.putIfAbsent(effectiveStack.serializeNBT(),
                        new ExpandedWildcardPattern(effectivePattern, effectiveStack, recipe, assignedTypeId));
            }
        }

        for (ExpandedWildcardPattern expanded : uniquePatterns.values()) {
            int localSlot = wildcardPatterns.size();
            int globalSlot = maxPatternCount + localSlot;
            MEPatternBufferPartMachineBase.InternalSlot internalSlot =
                    new StockingPatternBufferInternalSlot(globalSlot);
            internalSlot.setOnContentsChanged(
                    () -> ((RecipeTypePatternRecipeHandler) recipeHandler).notifyPatternListeners());
            wildcardPatterns.add(expanded.pattern());
            wildcardPatternStacks.add(expanded.stack());
            wildcardResolvedRecipes.add(expanded.recipe());
            wildcardAssignedRecipeTypeIds.add(expanded.assignedTypeId());
            wildcardInternalSlots.add(internalSlot);
            wildcardPatternToSlot.put(expanded.pattern(), globalSlot);
        }

        if (restorePersistedData) {
            wildcardPersistence.restore(maxPatternCount, wildcardPatternStacks, wildcardInternalSlots.size(),
                    (slot, slotData) -> wildcardInternalSlots.get(slot).deserializeNBT(slotData),
                    wildcardResolvedRecipes, wildcardRecipeCache, this::refundPersistedWildcardSlots);
        }
        rebuildingWildcardPatterns = false;
        RecipeTypePatternSearchHelper.clearPatternState(this);
        needPatternSync = true;
    }

    private void refreshWildcardRecipeTypes() {
        if (wildcardPatterns == null) return;
        clearWildcardRecipeCache();
        for (int i = 0; i < wildcardPatterns.size(); i++) {
            String assignedTypeId = i < wildcardAssignedRecipeTypeIds.size()
                    ? wildcardAssignedRecipeTypeIds.get(i) : "";
            GTRecipe recipe = WildcardPatternRecipeTypeBinding.findRecipe(wildcardPatterns.get(i), assignedTypeId);
            wildcardResolvedRecipes.set(i, recipe);
            ItemStack stack = wildcardPatterns.get(i).getDefinition().toStack();
            if (recipe != null) PatternRecipeTypeHelper.writeRecipeType(stack, recipe);
            wildcardPatternStacks.set(i, stack);
        }
        RecipeTypePatternSearchHelper.clearPatternState(this);
        needPatternSync = true;
    }

    private void clearWildcardPatternData(boolean clearPendingPersistence) {
        if (wildcardPatterns != null) {
            for (int localSlot = 0; localSlot < wildcardPatterns.size(); localSlot++) {
                int globalSlot = maxPatternCount + localSlot;
                if (wildcardRemoveSlotFromMap != null) wildcardRemoveSlotFromMap.accept(globalSlot);
                notifyProxySlotRemoved(globalSlot);
            }
        }
        if (wildcardRecipeCache != null) wildcardRecipeCache.clear();
        if (wildcardPatterns != null) wildcardPatterns.clear();
        if (wildcardPatternStacks != null) wildcardPatternStacks.clear();
        if (wildcardInternalSlots != null) wildcardInternalSlots.clear();
        if (wildcardResolvedRecipes != null) wildcardResolvedRecipes.clear();
        if (wildcardAssignedRecipeTypeIds != null) wildcardAssignedRecipeTypeIds.clear();
        if (wildcardPatternToSlot != null) wildcardPatternToSlot.clear();
        if ((Object) this instanceof VirtualPatternBufferMachineAccess access) {
            access.gtShanhai$invalidateRefundSlotIndex();
        }
        if (clearPendingPersistence && wildcardPersistence != null) wildcardPersistence.clearPending();
        RecipeTypePatternSearchHelper.clearPatternState(this);
    }

    private void clearWildcardRecipeCache() {
        if (wildcardRecipeCache == null || wildcardRecipeCache.isEmpty()) return;
        for (int slot : wildcardRecipeCache.keySet().toIntArray()) {
            if (wildcardRemoveSlotFromMap != null) wildcardRemoveSlotFromMap.accept(slot);
            notifyProxySlotRemoved(slot);
        }
        wildcardRecipeCache.clear();
    }

    private void refundWildcardSlots() {
        if (wildcardInternalSlots == null) return;
        for (MEPatternBufferPartMachineBase.InternalSlot slot : wildcardInternalSlots) {
            if ((Object) this instanceof VirtualPatternBufferMachineAccess access) {
                access.gtShanhai$indexRefundSlot(slot, slot.getItemInventory(), slot.getFluidInventory());
            }
            refundSlot(slot.getItemInventory(), slot.getFluidInventory());
        }
        if (!buffer.isEmpty()) AEUtils.reFunds(buffer, getMainNode().getGrid(), actionSource);
    }

    private void refundPersistedWildcardSlots(List<CompoundTag> slotDataList) {
        Object2LongOpenHashMap<AEItemKey> itemInventory = new Object2LongOpenHashMap<>();
        Object2LongOpenHashMap<AEFluidKey> fluidInventory = new Object2LongOpenHashMap<>();
        for (CompoundTag slotData : slotDataList) {
            AEUtils.appendPersistedInventory(slotData.getList("inventory", Tag.TAG_COMPOUND),
                    AEItemKey::fromTag, itemInventory);
            AEUtils.appendPersistedInventory(slotData.getList("fluidInventory", Tag.TAG_COMPOUND),
                    AEFluidKey::fromTag, fluidInventory);
        }
        refundSlot(itemInventory, fluidInventory);
    }

    private record ExpandedWildcardPattern(IPatternDetails pattern, ItemStack stack, GTRecipe recipe,
                                           String assignedTypeId) {}

    private static final class RecipeTypePatternRecipeHandler extends MEPatternBufferRecipeHandlerTrait {

        RecipeTypePatternRecipeHandler(RecipeTypePatternBufferPartMachine machine, IO io) {
            super(machine, io);
        }

        void notifyPatternListeners() {
            getMeFluidHandler().notifyListeners();
            getMeItemHandler().notifyListeners();
        }
    }

    public RecipeTypePatternBufferPartMachine resolveRemotePatternOwner() {
        return this;
    }

    public boolean isValidRemotePatternSlot(int slot) {
        return slot >= 0 && slot < this.maxPatternCount;
    }

    public ModularUI createRemotePatternUI(Player player, IUIHolder holder) {
        return new ModularUI(176, 166, holder, player)
                .widget(new FancyMachineUIWidget(this, 176, 166));
    }

    public ModularUI createRemoteSlotCatalystUI(Player player, int slot, IUIHolder holder) {
        if (!isValidRemotePatternSlot(slot)) return null;

        WidgetGroup root = new WidgetGroup(0, 0, 176, 180);
        root.addWidget(new LabelWidget(8, 4, () -> Component.translatable(
                "gui.gt_shanhai.remote_pattern_catalyst", slot + 1).getString()));
        MEPatternCatalystUIManager manager = new MEPatternCatalystUIManager(
                8, this.catalystItems, this.catalystFluids,
                this.cacheRecipeCount, this::removeSlotFromGTRecipeCache);
        root.addWidget(manager);
        manager.toggleFor(slot);
        layoutRemoteCatalystContainers(manager);
        root.addWidget(new ButtonWidget(112, 158, 56, 16, new TextTexture(
                Component.translatable("gui.gt_shanhai.remote_pattern_return").getString()), click -> {
                    if (player instanceof ServerPlayer serverPlayer) {
                        com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternTerminalOpenHelper
                                .openFirst(serverPlayer);
                    }
                }));
        return new ModularUI(176, 266, holder, player)
                .widget(root)
                .widget(UITemplate.bindPlayerInventory(player.getInventory(), GuiTextures.SLOT, 7, 184, true))
                .background(GuiTextures.BACKGROUND);
    }

    private static void layoutRemoteCatalystContainers(MEPatternCatalystUIManager manager) {
        if (manager.widgets.size() < 3) return;
        Widget itemContainer = manager.widgets.get(1);
        Widget fluidContainer = manager.widgets.get(2);
        fluidContainer.setSelfPosition(
                itemContainer.getPositionX() + itemContainer.getSizeWidth() + 4,
                itemContainer.getPositionY());
        manager.setSize(
                Math.max(manager.widgets.get(0).getSizeWidth(),
                        fluidContainer.getPositionX() + fluidContainer.getSizeWidth()),
                Math.max(itemContainer.getPositionY() + itemContainer.getSizeHeight(),
                        fluidContainer.getPositionY() + fluidContainer.getSizeHeight()));
    }

    public ModularUI createRemoteStockInputUI(Player player, IUIHolder holder) {
        WidgetGroup root = new WidgetGroup(0, 0, 176, 132);
        root.addWidget(new LabelWidget(8, 4, () -> Component.translatable(
                "gui.gtlcore.stock_input_config").getString()));
        root.addWidget(new LabelWidget(138, 4,
                () -> FormattingUtil.formatNumbers(countConfiguredStockSlots()) + " / 32"));
        root.addWidget(new AEDualConfigWidget(16, 18, this.stockItemHandler, this.stockFluidHandler,
                this::setPage, this.page));
        root.addWidget(new ButtonWidget(112, 112, 56, 16, new TextTexture(
                Component.translatable("gui.gt_shanhai.remote_pattern_return").getString()), click -> {
                    if (player instanceof ServerPlayer serverPlayer) {
                        com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternTerminalOpenHelper
                                .openFirst(serverPlayer);
                    }
                }));
        return new ModularUI(176, 132, holder, player)
                .widget(root)
                .background(GuiTextures.BACKGROUND);
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
        // 只读：本槽同时在向 AE 网络供货，刷新本地过滤缓存不得改写样板 NBT（见
        // PatternRecipeTypeHelper.peekRecipeTypeId 文档——AEItemKey 含 NBT，改动即变身份）。
        patternRecipeTypeIds[slot] = PatternRecipeTypeHelper.peekRecipeTypeId(stack, getLevel());
    }

    private record OutputMultiplierPatternCacheEntry(
            int sourcePatternDefinitionHash,
            int sourceStackHash,
            String recipeTypeId,
            int multiplier,
            long recipeRevision,
            IPatternDetails pattern) {

        OutputMultiplierPatternCacheEntry(IPatternDetails sourcePattern, ItemStack sourceStack,
                String recipeTypeId, int multiplier, long recipeRevision, IPatternDetails pattern) {
            this(patternDefinitionHash(sourcePattern), stackKeyHash(sourceStack),
                    recipeTypeId, multiplier, recipeRevision, pattern);
        }

        boolean matches(IPatternDetails sourcePattern, ItemStack sourceStack,
                String recipeTypeId, int multiplier, long recipeRevision) {
            return sourcePatternDefinitionHash == patternDefinitionHash(sourcePattern)
                    && sourceStackHash == stackKeyHash(sourceStack)
                    && Objects.equals(this.recipeTypeId, recipeTypeId)
                    && this.multiplier == multiplier
                    && this.recipeRevision == recipeRevision;
        }
    }
}
