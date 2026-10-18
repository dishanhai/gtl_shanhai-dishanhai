package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.helpers.MultiCraftingTracker;

import com.google.common.collect.ImmutableSet;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.api.gui.configurators.FancyConfiguratorSidebarPage;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeIdMatchPolicy;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeExecutionGuard;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSearchHelper;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSlotAccess;
import com.dishanhai.gt_shanhai.common.item.RecipeTypeSharedSearchSets;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferMachineAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotState;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.dishanhai.gt_shanhai.common.item.WildcardPatternBridge;
import com.dishanhai.gt_shanhai.common.item.terminal.StellarTerminalDetail;
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
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;
import com.gregtechceu.gtceu.integration.ae2.utils.SerializableManagedGridNode;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot;
import com.gregtechceu.gtceu.api.gui.widget.LongInputWidget;
import com.gregtechceu.gtceu.utils.FormattingUtil;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
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
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

import org.gtlcore.gtlcore.api.gui.MEPatternCatalystUIManager;

import org.gtlcore.gtlcore.client.gui.widget.PatternCycleWidget;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTrait;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.utils.NumberUtils;
import com.dishanhai.gt_shanhai.common.machine.output.OutputMultiplierResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 星律样板总成：沿用 GTLCore 库存ME样板总成的库存输入語義，並接入超級樣板總成的 IV 類型入口。
 * 库存输入区域"真扣料时才实时提取"的消耗语义继承父类；周期同步与配方模拟阶段的可用量查询
 * 改走 StorageService 增量缓存（syncStockInput / findStock*Key 覆写），不再对全网做模拟提取遍历。
 */
public class RecipeTypePatternBufferPartMachine extends StellarSuperPatternBufferPartMachine
        implements RecipeTypePatternSlotAccess, ICraftingRequester {

    public static final int DEFAULT_PATTERNS_PER_ROW = 9;
    public static final int DEFAULT_ROWS_PER_PAGE = 6;
    public static final int DEFAULT_MAX_PAGES = 3;
    public static final int DEFAULT_PATTERN_COUNT = DEFAULT_PATTERNS_PER_ROW * DEFAULT_ROWS_PER_PAGE * DEFAULT_MAX_PAGES;
    public static final int DEFAULT_WILDCARD_SLOTS_PER_PAGE = 10;
    public static final int DEFAULT_WILDCARD_MAX_PAGES = 2;
    public static final int DEFAULT_WILDCARD_SLOT_COUNT =
            DEFAULT_WILDCARD_SLOTS_PER_PAGE * DEFAULT_WILDCARD_MAX_PAGES;
    private static final int REMOTE_PLAYER_INVENTORY_HEIGHT = 80;
    private static final IGuiTexture STOCK_INPUT_ICON =
            new ResourceTexture("gt_shanhai:textures/gui/stock_input_panel.png");
    private static final IGuiTexture WILDCARD_INPUT_ICON = new TextTexture("*");
    /**
     * 宿主倍率来源是多方聚合（万象核心、维护仓、其他来源及固定广播），不能只靠某一个来源的回调。
     * 每 tick 重新读取聚合器，配合下面的两次一致防抖，来源上线后最多两个 tick 即可同步。
     */
    private static final long OUTPUT_MULTIPLIER_HOST_CHECK_TICKS = 1L;
    /**
     * 主机配方类型只服务红框告警，不参与发配数量。量子计算机每个成功推送会读两次，
     * 收集本身会展开全部配方类型。缓存 20 tick，告警最多晚 1 秒跟上主机切换。
     */
    private static final long HOST_RECIPE_TYPE_ID_CACHE_TICKS = 20L;
    private static final int[] NO_ACTIVE_SLOTS = new int[0];
    private static final int[] NO_ACTIVE_UNCACHED_SLOTS = new int[0];
    private static final int PARENT_REFUND = 0;
    private static final int PARENT_SHARED_ITEM = 1;
    private static final int PARENT_SHARED_FLUID = 2;
    private static final int PARENT_SHARED_CIRCUIT = 3;
    private static final int PARENT_BYPRODUCT = 4;
    private static final int PARENT_TERMINAL_VISIBILITY = 5;
    private static final int PARENT_PATTERN_CIRCUIT = 6;
    private static final int PARENT_ADVANCED_ME = 8;
    /** 库存页视口。窗口另加 8 像素边框和 86 像素背包，232 在 GUI 比例 3 下能留在 1080p 屏幕内。 */
    private static final int STOCK_PAGE_MAX_HEIGHT = 232;
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            RecipeTypePatternBufferPartMachine.class, StellarSuperPatternBufferPartMachine.MANAGED_FIELD_HOLDER);
    private static final String STOCK_ITEM_TRACKER = "ShanhaiPatternBufferItemCraftingTracker";
    private static final String STOCK_FLUID_TRACKER = "ShanhaiPatternBufferFluidCraftingTracker";

    @Persisted
    private String stockTagWhite = "";
    @Persisted
    private String stockTagBlack = "";
    @DescSynced
    @Persisted
    private boolean stockRequestEnabled;
    @Persisted
    private CompoundTag stockManualConfiguration = new CompoundTag();
    @Persisted
    private CompoundTag stockAutomaticConfiguration = new CompoundTag();
    @Persisted
    private CompoundTag stockSuppressedKeys = new CompoundTag();
    @Persisted
    private CompoundTag stockRequestLimits = new CompoundTag();

    private final PatternBufferStockPull stockPull = new PatternBufferStockPull();
    private final long[] stockDraftAmount = new long[CONFIG_SIZE * 2];
    private final long[] stockDraftBatch = new long[CONFIG_SIZE * 2];
    private final boolean[] stockDraftAmountSet = new boolean[CONFIG_SIZE * 2];
    private final boolean[] stockDraftBatchSet = new boolean[CONFIG_SIZE * 2];
    private MultiCraftingTracker stockItemCraftingTracker;
    private MultiCraftingTracker stockFluidCraftingTracker;

    private final CachedPatternPaginationUIManager paginationUIManager;
    private final String[] patternRecipeTypeIds;
    private final int[] patternRecipeTypeStackHashes;
    private final int[] strictRecipeIdWarningStackHashes;
    @DescSynced
    private String warningSlots = "";
    private BitSet cachedWarningSlots = new BitSet();
    private String cachedWarningSlotsEncoded = "";
    private List<String> cachedHostRecipeTypeIds = List.of();
    private long cachedHostRecipeTypeTick = Long.MIN_VALUE;
    private final BitSet stuckRuntimeWarningSlots = new BitSet();
    private final BitSet strictRecipeIdWarningSlots = new BitSet();
    private final Int2ReferenceOpenHashMap<Object2LongMap<AEItemKey>> stuckRuntimeItemSnapshots =
            new Int2ReferenceOpenHashMap<>();
    private final Int2ReferenceOpenHashMap<Object2LongMap<AEFluidKey>> stuckRuntimeFluidSnapshots =
            new Int2ReferenceOpenHashMap<>();
    private final Int2ReferenceOpenHashMap<String> stuckRuntimeBroadcasts = new Int2ReferenceOpenHashMap<>();

    @Persisted
    private final ItemStackTransfer wildcardPatternInventory;
    @DescSynced
    @Persisted
    private boolean strongPatternMatchMode;
    @Persisted
    private boolean outputMultiplierModeEnabled;
    @Persisted
    private long patternOutputMultiplier = 1L;
    @DescSynced
    @Persisted
    private long cachedHostOutputMultiplier = 1L;
    private long lastDetectedHostOutputMultiplier = Long.MIN_VALUE;
    private long pendingDetectedHostOutputMultiplier = Long.MIN_VALUE;
    private long lastDetectedUniversalHostOutputMultiplier = Long.MIN_VALUE;
    private long pendingDetectedUniversalHostOutputMultiplier = Long.MIN_VALUE;
    private int lastDetectedForgeRecipeTypeFingerprint = Integer.MIN_VALUE;
    private int pendingDetectedForgeRecipeTypeFingerprint = Integer.MIN_VALUE;
    @Nullable
    private TickableSubscription outputMultiplierHostSyncSubscription;
    private final List<IPatternDetails> wildcardPatterns;
    private final List<List<IPatternDetails>> wildcardPreviewByMother;
    private final List<ItemStack> wildcardPatternStacks;
    private final List<MEPatternBufferPartMachineBase.InternalSlot> wildcardInternalSlots;
    private final List<GTRecipe> wildcardResolvedRecipes;
    private final List<String> wildcardAssignedRecipeTypeIds;
    private final Object2IntMap<IPatternDetails> wildcardPatternToSlot;
    private final Int2ReferenceMap<GTRecipe> wildcardRecipeCache;
    private final Int2ReferenceMap<OutputMultiplierPatternCacheEntry> outputMultiplierPatternCache;
    private final Object2LongOpenHashMap<String> confirmedPatternOutputMultipliers = new Object2LongOpenHashMap<>();
    private final RecipeTypePatternWildcardPersistence wildcardPersistence;
    @Nullable
    private Supplier<Level> patternPreviewLevelFallback;
    private final InternalInventory combinedTerminalPatternInventory;
    private boolean outputMultiplierPatternsRefreshNeeded = true;
    private int[] visibleOutputMultiplierStamps;
    private IntConsumer wildcardRemoveSlotFromMap;
    private boolean rebuildingWildcardPatterns;
    private final int wildcardSlotsPerPage;
    private final int wildcardMaxPages;
    private final int wildcardSlotCount;
    private int selectedWildcardPage;
    private int selectedWildcardMotherSlot;
    private int[] activeSlotsScratch;
    private int[] activeUncachedSlotsScratch;

    public RecipeTypePatternBufferPartMachine(@Nullable IMachineBlockEntity holder) {
        this(holder, DEFAULT_PATTERNS_PER_ROW, DEFAULT_ROWS_PER_PAGE, DEFAULT_MAX_PAGES,
                DEFAULT_WILDCARD_SLOTS_PER_PAGE, DEFAULT_WILDCARD_MAX_PAGES);
    }

    public RecipeTypePatternBufferPartMachine(@Nullable IMachineBlockEntity holder, int patternsPerRow,
            int rowsPerPage, int maxPages) {
        this(holder, patternsPerRow, rowsPerPage, maxPages,
                DEFAULT_WILDCARD_SLOTS_PER_PAGE, DEFAULT_WILDCARD_MAX_PAGES);
    }

    public RecipeTypePatternBufferPartMachine(@Nullable IMachineBlockEntity holder, int patternsPerRow,
            int rowsPerPage, int maxPages, int wildcardSlotsPerPage, int wildcardMaxPages) {
        super(holder, patternsPerRow * rowsPerPage * maxPages, IO.BOTH);
        this.wildcardSlotsPerPage = Math.max(1, wildcardSlotsPerPage);
        this.wildcardMaxPages = Math.max(1, wildcardMaxPages);
        this.wildcardSlotCount = this.wildcardSlotsPerPage * this.wildcardMaxPages;
        this.activeSlotsScratch =
                new int[patternsPerRow * rowsPerPage * maxPages + this.wildcardSlotCount];
        this.activeUncachedSlotsScratch =
                new int[patternsPerRow * rowsPerPage * maxPages + this.wildcardSlotCount];
        this.patternRecipeTypeIds = new String[patternsPerRow * rowsPerPage * maxPages];
        Arrays.fill(this.patternRecipeTypeIds, "");
        this.patternRecipeTypeStackHashes = new int[patternsPerRow * rowsPerPage * maxPages];
        Arrays.fill(this.patternRecipeTypeStackHashes, Integer.MIN_VALUE);
        this.strictRecipeIdWarningStackHashes = new int[patternsPerRow * rowsPerPage * maxPages];
        Arrays.fill(this.strictRecipeIdWarningStackHashes, Integer.MIN_VALUE);
        this.wildcardPatterns = new ObjectArrayList<>();
        this.wildcardPreviewByMother = new ObjectArrayList<>();
        for (int previewSlot = 0; previewSlot < this.wildcardSlotCount; previewSlot++) {
            this.wildcardPreviewByMother.add(new ObjectArrayList<>());
        }
        this.wildcardPatternStacks = new ObjectArrayList<>();
        this.wildcardInternalSlots = new ObjectArrayList<>();
        this.wildcardResolvedRecipes = new ObjectArrayList<>();
        this.wildcardAssignedRecipeTypeIds = new ObjectArrayList<>();
        this.wildcardPatternToSlot = new Object2IntOpenHashMap<>();
        this.wildcardPatternToSlot.defaultReturnValue(-1);
        this.wildcardRecipeCache = new Int2ReferenceOpenHashMap<>();
        this.outputMultiplierPatternCache = new Int2ReferenceOpenHashMap<>();
        this.visibleOutputMultiplierStamps = new int[patternsPerRow * rowsPerPage * maxPages];
        this.wildcardPersistence = new RecipeTypePatternWildcardPersistence();
        this.wildcardPatternInventory = new ItemStackTransfer(this.wildcardSlotCount);
        this.wildcardPatternInventory.setFilter(RecipeTypePatternBufferPartMachine::acceptsWildcardMother);
        this.wildcardPatternInventory.setOnContentsChanged(this::onWildcardPatternInventoryChanged);
        this.combinedTerminalPatternInventory = createCombinedTerminalPatternInventory(super.getTerminalPatternInventory());

        int uiWidth = Math.max(patternsPerRow * 18 + 16, 106);
        int uiHeight = rowsPerPage * 18 + 28;
        this.paginationUIManager = new CachedPatternPaginationUIManager(
                patternsPerRow, rowsPerPage, maxPages, uiWidth, uiHeight,
                this::onPatternChange,
                slot -> Boolean.valueOf(slot != null && slot >= 0 && slot < this.cacheRecipe.length && this.cacheRecipe[slot]),
                this::gtShanhai$isPatternSlotWarning,
                getPatternInventory(),
                this::patternPreviewLevel);
    }

    @Override
    protected MEPatternBufferRecipeHandlerTrait createRecipeHandler(IO io) {
        return new RecipeTypePatternRecipeHandler(this, io);
    }

    @Override
    protected MEPatternBufferPartMachineBase.InternalSlot createInternalSlot(int slotIndex) {
        return new StellarPatternBufferInternalSlot(slotIndex);
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        Integer slotIndex = getSlotIndexForPattern(patternDetails);
        boolean accepted = super.pushPattern(patternDetails, inputHolder);
        if (accepted && slotIndex != null && gtShanhai$isRuntimeTrackableSlot(slotIndex) && !isRemote()) {
            int slot = slotIndex;
            gtShanhai$clearPatternSlotRuntimeWarning(slot);
            StellarPatternStuckWatch.schedule(
                    this,
                    slot,
                    patternDetails,
                    gtShanhai$getSlotItemInventory(slot),
                    gtShanhai$getSlotFluidInventory(slot),
                    gtShanhai$hostRecipeTypeIds(),
                    gtShanhai$getPatternRecipeTypeId(slot),
                    StellarPatternCraftingContext.currentAePlayerId());
        }
        return accepted;
    }

    private final class StellarPatternBufferInternalSlot extends StockingPatternBufferInternalSlot {

        private StellarPatternBufferInternalSlot(int slotIndex) {
            super(slotIndex);
        }

        @Override
        public boolean isItemActive(boolean simulate) {
            return hasPatternInSlot(getSlotIndex());
        }

        @Override
        public boolean isFluidActive(boolean simulate) {
            return hasPatternInSlot(getSlotIndex());
        }
    }

    /**
     * 虚拟供料桥接实现：InternalSlot 是父类 protected 内部类型，类体内部直接访问合法，
     * 供 {@code RecipeTypePatternSearchHelper.topUpVirtualSupply} 星律路径绕开反射。
     */
    @Override
    public Object2LongMap<AEItemKey> gtShanhai$getSlotItemInventory(int slot) {
        if (slot < 0 || slot >= getInternalSlotCount()) return null;
        return getInternalSlot(slot).getItemInventory();
    }

    @Override
    public Object2LongMap<AEFluidKey> gtShanhai$getSlotFluidInventory(int slot) {
        if (slot < 0 || slot >= getInternalSlotCount()) return null;
        return getInternalSlot(slot).getFluidInventory();
    }

    @Override
    public boolean gtShanhai$addToSlot(int slot, AEKey key, long amount) {
        if (slot < 0 || slot >= getInternalSlotCount() || key == null || amount <= 0) return false;
        getInternalSlot(slot).add(key, amount);
        return true;
    }

    @Override
    public Pair<Object2LongOpenHashMap<Item>, Object2LongOpenHashMap<Fluid>> getMergedInternalSlot() {
        Pair<Object2LongOpenHashMap<Item>, Object2LongOpenHashMap<Fluid>> merged = super.getMergedInternalSlot();
        Object2LongOpenHashMap<Item> items = merged.left();
        Object2LongOpenHashMap<Fluid> fluids = merged.right();
        for (int slot = 0; slot < getInternalSlotCount(); slot++) {
            MEPatternBufferPartMachineBase.InternalSlot internalSlot = getInternalSlot(slot);
            boolean active = internalSlot.isActive();
            Object2LongMap<AEItemKey> itemTargets = VirtualPatternBufferSlotState
                    .getVirtualTargets(internalSlot.getItemInventory());
            for (Object2LongMap.Entry<AEItemKey> entry : itemTargets.object2LongEntrySet()) {
                AEItemKey key = entry.getKey();
                long internalAmount = active
                        ? Math.min(entry.getLongValue(), internalSlot.getItemInventory().getLong(key)) : 0L;
                long catalystAmount = Math.min(entry.getLongValue(),
                        internalSlot.getItemCatalystInventory().getLong(key));
                gtShanhai$subtractMergedAmount(items, key.toStack().getItem(), internalAmount + catalystAmount);
            }
            Object2LongOpenHashMap<AEItemKey> itemInventory = internalSlot.getItemInventory();
            int virtualCircuit = VirtualPatternBufferSlotState.getVirtualCircuit(itemInventory);
            if (virtualCircuit >= 0 && virtualCircuit <= IntCircuitBehaviour.CIRCUIT_MAX) {
                AEItemKey circuitKey = AEItemKey.of(IntCircuitBehaviour.stack(virtualCircuit));
                if (!itemTargets.containsKey(circuitKey)) {
                    long internalAmount = active ? Math.min(1L, itemInventory.getLong(circuitKey)) : 0L;
                    long catalystAmount = Math.min(1L,
                            internalSlot.getItemCatalystInventory().getLong(circuitKey));
                    gtShanhai$subtractMergedAmount(items, circuitKey.toStack().getItem(),
                            internalAmount + catalystAmount);
                }
            }
            for (Object2LongMap.Entry<AEFluidKey> entry : VirtualPatternBufferSlotState
                    .getVirtualTargets(internalSlot.getFluidInventory()).object2LongEntrySet()) {
                AEFluidKey key = entry.getKey();
                long internalAmount = active
                        ? Math.min(entry.getLongValue(), internalSlot.getFluidInventory().getLong(key)) : 0L;
                long catalystAmount = Math.min(entry.getLongValue(),
                        internalSlot.getFluidCatalystInventory().getLong(key));
                gtShanhai$subtractMergedAmount(fluids, key.getFluid(), internalAmount + catalystAmount);
            }
        }
        return merged;
    }

    private static <T> void gtShanhai$subtractMergedAmount(Object2LongOpenHashMap<T> merged, T key, long amount) {
        if (key == null || amount <= 0L) return;
        long remaining = merged.getLong(key) - amount;
        if (remaining > 0L) {
            merged.put(key, remaining);
        } else {
            merged.removeLong(key);
        }
    }

    /**
     * 存档槽位数与当前配置对账。patternsPerRow/rowsPerPage/maxPages 在放置后被改小时：
     * patternInventory 反序列化按存档旧 Size 重建，而父类 hasPatternArray 等定长数组按当前配置分配，
     * 父类 onLoad 按 patternInventory.getSlots() 遍历并写 hasPatternArray[i]，越界直接 AIOOBE 崩服。
     * 必须在父类 onLoad 之前收缩库存；越界槽的样板延迟 1 tick 掉落在机器位置（此时 AE 网络未就绪无法退回）。
     * 被裁槽位的内部缓冲料在父类反序列化阶段已按新长度截断，此处无法挽回。
     */
    private void gtShanhai$reconcilePatternInventorySize() {
        ItemStackTransfer inv = getPatternInventory();
        int expected = maxPatternCount;
        int actual = inv.getSlots();
        if (actual == expected) return;
        int keptCount = Math.min(actual, expected);
        List<ItemStack> kept = new ArrayList<>(keptCount);
        for (int i = 0; i < keptCount; i++) {
            kept.add(inv.getStackInSlot(i));
        }
        List<ItemStack> overflow = new ArrayList<>();
        for (int i = expected; i < actual; i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) overflow.add(stack.copy());
        }
        inv.setSize(expected);
        for (int i = 0; i < keptCount; i++) {
            inv.setStackInSlot(i, kept.get(i));
        }
        if (!overflow.isEmpty() && getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(1, () -> {
                for (ItemStack stack : overflow) {
                    Block.popResource(serverLevel, getPos(), stack);
                }
            }));
        }
    }

    /** 通配符母槽按当前每页格数和页数对账。调小配置时，高位槽里的样板掉在机器位置。 */
    private void gtShanhai$reconcileWildcardInventorySize() {
        int actual = wildcardPatternInventory.getSlots();
        if (actual == wildcardSlotCount) return;
        int keptCount = Math.min(actual, wildcardSlotCount);
        List<ItemStack> kept = new ArrayList<>(keptCount);
        for (int i = 0; i < keptCount; i++) {
            kept.add(wildcardPatternInventory.getStackInSlot(i));
        }
        List<ItemStack> overflow = new ArrayList<>();
        for (int i = wildcardSlotCount; i < actual; i++) {
            ItemStack stack = wildcardPatternInventory.getStackInSlot(i);
            if (!stack.isEmpty()) overflow.add(stack.copy());
        }
        wildcardPatternInventory.setSize(wildcardSlotCount);
        for (int i = 0; i < keptCount; i++) {
            wildcardPatternInventory.setStackInSlot(i, kept.get(i));
        }
        if (selectedWildcardMotherSlot >= wildcardSlotCount) selectedWildcardMotherSlot = 0;
        if (selectedWildcardPage >= wildcardMaxPages) selectedWildcardPage = 0;
        if (!overflow.isEmpty() && getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(1, () -> {
                for (ItemStack stack : overflow) {
                    Block.popResource(serverLevel, getPos(), stack);
                }
            }));
        }
    }

    @Override
    public void onLoad() {
        gtShanhai$reconcilePatternInventorySize();
        gtShanhai$reconcileWildcardInventorySize();
        super.onLoad();
        // 父类 onLoad 已通过 getRealPattern 构造并发布倍率样板，无需在首次 AE 查询时再解码一遍。
        outputMultiplierPatternsRefreshNeeded = false;
        if ((Object) this instanceof VirtualPatternBufferMachineAccess access) {
            access.gtShanhai$restoreVirtualTargetsFromPatterns(getAvailablePatterns());
        }
        refreshPatternRecipeTypes();
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().tell(new TickTask(1, () -> refreshWildcardPatterns(true)));
        }
        updateOutputMultiplierHostSyncSubscription();
    }

    @Override
    public void onUnload() {
        if (outputMultiplierHostSyncSubscription != null) {
            outputMultiplierHostSyncSubscription.unsubscribe();
            outputMultiplierHostSyncSubscription = null;
        }
        super.onUnload();
    }

    // ==== 库存输入 AE 查询降载：同步/模拟走 StorageService 增量缓存，真扣料保持父类实时 ====

    /**
     * 只读缓存视图：extract(SIMULATE) 直接返回缓存计数，喂给父类 syncStock 复用其槽位/stockMap 回写。
     * 父类原实现对每个已配置条目做 extract(key, Long.MAX_VALUE, SIMULATE)——请求量永远凑不满，
     * NetworkStorage 必然遍历全网全部挂载存储（性能监测实测单次 syncStockInput ~3ms，
     * 摊成 Jade 平均延迟 73µs）。StorageService 缓存有 watcher 时每 tick 末刷新，
     * 无 watcher 时按需每 tick 至多重建一次，最多旧 1 tick。
     */
    private static final class CachedNetworkAmountView implements MEStorage {

        private static final Component DESCRIPTION = Component.literal("gt_shanhai:cached_stock_view");

        private final KeyCounter cached;

        private CachedNetworkAmountView(KeyCounter cached) {
            this.cached = cached;
        }

        @Override
        public Component getDescription() {
            return DESCRIPTION;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            // 视图背后没有真实存储，MODULATE 一律拒绝，防止被误当可扣料库存
            if (mode != Actionable.SIMULATE) return 0L;
            long available = cached.get(what);
            return available <= 0L ? 0L : Math.min(available, amount);
        }
    }

    @Nullable
    private KeyCounter gtShanhai$cachedNetworkInventory() {
        IGrid grid = getMainNode().getGrid();
        return grid == null ? null : grid.getStorageService().getCachedInventory();
    }

    @Override
    protected void syncStockInput() {
        KeyCounter cached = gtShanhai$cachedNetworkInventory();
        if (!isRemote() && cached != null) {
            refreshStockTagConfiguration(cached);
        }
        if (cached == null) {
            stockItemHandler.clearStocks();
            stockFluidHandler.clearStocks();
            return;
        }
        MEStorage view = new CachedNetworkAmountView(cached);
        stockItemHandler.syncStock(view);
        stockFluidHandler.syncStock(view);
        if (!isRemote()) {
            requestStockShortages();
        }
    }

    private void refreshStockTagConfiguration(KeyCounter network) {
        PatternBufferStockPull.Refresh refresh = stockPull.refreshItems(
                stockItemHandler.getInventory(), network, stockTagWhite, stockTagBlack,
                stockManualConfiguration, stockAutomaticConfiguration, stockSuppressedKeys);
        boolean dirty = false;
        if (refresh.manual != null && !refresh.manual.equals(stockManualConfiguration)) {
            stockManualConfiguration = refresh.manual;
            dirty = true;
        }
        if (refresh.automatic != null && !refresh.automatic.equals(stockAutomaticConfiguration)) {
            stockAutomaticConfiguration = refresh.automatic;
            dirty = true;
        }
        if (refresh.suppressed != null && !refresh.suppressed.equals(stockSuppressedKeys)) {
            stockSuppressedKeys = refresh.suppressed;
            dirty = true;
        }
        if (dirty) {
            markDirty();
        }
        if (refresh.configsChanged) {
            boolean removed = stockItemHandler.onConfigChanged();
            if (removed || refresh.removedConfig) {
                invalidateRecipeCaches();
            }
        }
    }

    private void requestStockShortages() {
        if (!stockRequestEnabled || !isOnline()) {
            return;
        }
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        ensureStockTrackers();
        stockPull.request(stockItemHandler.getInventory(), stockFluidHandler.getInventory(),
                stockRequestLimits, stockItemCraftingTracker, stockFluidCraftingTracker,
                getLevel(), grid.getCraftingService(), this.actionSource);
    }

    private void ensureStockTrackers() {
        if (stockItemCraftingTracker != null) {
            return;
        }
        stockItemCraftingTracker = new MultiCraftingTracker(this, stockItemHandler.getInventory().length);
        stockFluidCraftingTracker = new MultiCraftingTracker(this, stockFluidHandler.getInventory().length);
    }

    /** 已配置条目才允许由库存输入区供料（对齐父类 getAvailableAmount 的 configList 门控）。 */
    private long gtShanhai$cachedConfiguredItemAmount(KeyCounter cached, AEItemKey key) {
        for (ExportOnlyAEItemSlot slot : stockItemHandler.getInventory()) {
            GenericStack config = slot.getConfig();
            if (config != null && key.equals(config.what())) {
                return Math.max(0L, cached.get(key));
            }
        }
        return 0L;
    }

    private long gtShanhai$cachedConfiguredFluidAmount(KeyCounter cached, AEFluidKey key) {
        for (ExportOnlyAEFluidSlot slot : stockFluidHandler.getInventory()) {
            GenericStack config = slot.getConfig();
            if (config != null && key.equals(config.what())) {
                return Math.max(0L, cached.get(key));
            }
        }
        return 0L;
    }

    /**
     * includeCatalyst=true 恰为配方搜索的模拟阶段（handleItemInternal 把 simulate 原样传入）：
     * 每原料一次的全网 Long.MAX_VALUE 模拟提取改为缓存 O(1)。缓存高估会在真扣料阶段被父类
     * 实时复查拦下（check 循环先于消耗，最多浪费一次尝试，不会部分消耗后吞料）；
     * 低估最多 1 tick 后自愈。includeCatalyst=false 是真扣料阶段：委托父类实时查询，不走缓存。
     */
    @Override
    @Nullable
    protected AEItemKey findStockItemKey(Ingredient ingredient, Object2LongMap<AEItemKey> internal,
            Object2LongMap<AEItemKey> catalyst, long needAmount, boolean includeCatalyst) {
        if (!includeCatalyst) {
            return super.findStockItemKey(ingredient, internal, catalyst, needAmount, false);
        }
        Object2LongMap<AEItemKey> virtualTargets = internal instanceof Object2LongOpenHashMap<AEItemKey> inventory
                ? VirtualPatternBufferSlotState.getVirtualTargets(inventory) : Object2LongMaps.emptyMap();
        Object2LongMap<AEItemKey> distinctCatalyst =
                VirtualPatternBufferSlotState.withoutVirtualCatalystMirrors(virtualTargets, internal, catalyst);
        KeyCounter cached = gtShanhai$cachedNetworkInventory();
        if (cached == null) {
            AEItemKey key = super.findStockItemKey(ingredient, internal, distinctCatalyst, needAmount, true);
            if (key != null && key.matches(ingredient)) return key;
        } else {
            for (ItemStack item : ingredient.getItems()) {
                if (item.isEmpty()) continue;
                AEItemKey key = AEItemKey.of(item);
                long amount = NumberUtils.saturatedAdd(internal.getLong(key),
                        gtShanhai$cachedConfiguredItemAmount(cached, key));
                amount = NumberUtils.saturatedAdd(amount, distinctCatalyst.getLong(key));
                if (amount >= needAmount) return key;
            }
            for (ExportOnlyAEItemSlot slot : stockItemHandler.getInventory()) {
                GenericStack config = slot.getConfig();
                if (config == null || !(config.what() instanceof AEItemKey key) || !key.matches(ingredient)) {
                    continue;
                }
                long amount = NumberUtils.saturatedAdd(internal.getLong(key), Math.max(0L, cached.get(key)));
                amount = NumberUtils.saturatedAdd(amount, distinctCatalyst.getLong(key));
                if (amount >= needAmount) return key;
            }
        }
        return VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                virtualTargets, internal, catalyst, needAmount, key -> key.matches(ingredient));
    }

    @Override
    @Nullable
    protected AEFluidKey findStockFluidKey(FluidIngredient ingredient, Object2LongMap<AEFluidKey> internal,
            Object2LongMap<AEFluidKey> catalyst, long needAmount, boolean includeCatalyst) {
        if (!includeCatalyst) {
            return super.findStockFluidKey(ingredient, internal, catalyst, needAmount, false);
        }
        Object2LongMap<AEFluidKey> virtualTargets = internal instanceof Object2LongOpenHashMap<AEFluidKey> inventory
                ? VirtualPatternBufferSlotState.getVirtualTargets(inventory) : Object2LongMaps.emptyMap();
        Object2LongMap<AEFluidKey> distinctCatalyst =
                VirtualPatternBufferSlotState.withoutVirtualCatalystMirrors(virtualTargets, internal, catalyst);
        KeyCounter cached = gtShanhai$cachedNetworkInventory();
        if (cached == null) {
            AEFluidKey key = super.findStockFluidKey(ingredient, internal, distinctCatalyst, needAmount, true);
            if (key != null && AEUtils.testFluidIngredient(ingredient, key)) return key;
        } else {
            for (FluidStack stack : ingredient.getStacks()) {
                if (stack.isEmpty()) continue;
                AEFluidKey key = AEFluidKey.of(stack.getFluid(), stack.getTag());
                long amount = NumberUtils.saturatedAdd(internal.getLong(key),
                        gtShanhai$cachedConfiguredFluidAmount(cached, key));
                amount = NumberUtils.saturatedAdd(amount, distinctCatalyst.getLong(key));
                if (amount >= needAmount) return key;
            }
            for (ExportOnlyAEFluidSlot slot : stockFluidHandler.getInventory()) {
                GenericStack config = slot.getConfig();
                if (config == null || !(config.what() instanceof AEFluidKey key)
                        || !AEUtils.testFluidIngredient(ingredient, key)) {
                    continue;
                }
                long amount = NumberUtils.saturatedAdd(internal.getLong(key), Math.max(0L, cached.get(key)));
                amount = NumberUtils.saturatedAdd(amount, distinctCatalyst.getLong(key));
                if (amount >= needAmount) return key;
            }
        }
        return VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                virtualTargets, internal, catalyst, needAmount,
                key -> AEUtils.testFluidIngredient(ingredient, key));
    }

    @Override
    protected void onPatternChange(int index) {
        clearOutputMultiplierPatternCache();
        super.onPatternChange(index);
        if (!isRemote()) {
            RecipeTypePatternSearchHelper.clearPatternSlotState(this, index);
        }
        outputMultiplierPatternsRefreshNeeded = false;
        if (outputMultiplierModeEnabled && publishedProductOutputsMissMultiplier()) {
            outputMultiplierPatternsRefreshNeeded = true;
        }
        refreshPatternRecipeType(index);
        if (index >= 0 && index < strictRecipeIdWarningStackHashes.length) {
            strictRecipeIdWarningStackHashes[index] = Integer.MIN_VALUE;
            strictRecipeIdWarningSlots.clear(index);
        }
        if (!isRemote() && strongPatternMatchMode && index >= 0 && index < maxPatternCount
                && index < getPatternInventory().getSlots()) {
            ItemStack stack = getPatternInventory().getStackInSlot(index);
            gtShanhai$setStrongPatternMatchWarning(index,
                    !stack.isEmpty() && !gtShanhai$hasStrongPatternMetadata(stack));
        }
        gtShanhai$refreshPatternSlotWarning(index);
    }

    @Override
    protected void refreshAllByProduct() {
        clearOutputMultiplierPatternCache();
        super.refreshAllByProduct();
        outputMultiplierPatternsRefreshNeeded = false;
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
        if (!forDrop && stockItemCraftingTracker != null && stockFluidCraftingTracker != null) {
            CompoundTag itemTag = new CompoundTag();
            CompoundTag fluidTag = new CompoundTag();
            stockItemCraftingTracker.writeToNBT(itemTag);
            stockFluidCraftingTracker.writeToNBT(fluidTag);
            tag.put(STOCK_ITEM_TRACKER, itemTag);
            tag.put(STOCK_FLUID_TRACKER, fluidTag);
        }
        if (wildcardPersistence.hasPending()) {
            // onLoad 后延迟 1 tick 的恢复还没执行，内存列表是空的：直接回写未消费的
            // pendingSlots，避免这次保存把存档里的通配符槽数据抹掉（保存+崩服组合会永久丢料）。
            wildcardPersistence.writePending(tag);
            return;
        }
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
        if (tag.contains(STOCK_ITEM_TRACKER) || tag.contains(STOCK_FLUID_TRACKER)) {
            ensureStockTrackers();
            if (tag.contains(STOCK_ITEM_TRACKER)) {
                stockItemCraftingTracker.readFromNBT(tag.getCompound(STOCK_ITEM_TRACKER));
            }
            if (tag.contains(STOCK_FLUID_TRACKER)) {
                stockFluidCraftingTracker.readFromNBT(tag.getCompound(STOCK_FLUID_TRACKER));
            }
        }
    }

    @Override
    public boolean pasteFromTag(net.minecraft.nbt.CompoundTag tag) {
        boolean result = super.pasteFromTag(tag);
        // 数据棒可能从不同槽位配置的总成复制而来，同样存在尺寸错配。
        gtShanhai$reconcilePatternInventorySize();
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
            if (slot < getPatternInventory().getSlots()) {
                ItemStack stack = getPatternInventory().getStackInSlot(slot);
                if (patternRecipeTypeStackHashes[slot] != stackKeyHash(stack)) {
                    refreshPatternRecipeType(slot);
                }
            }
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
        if (stack.isEmpty()) return null;
        String recipeId = PatternRecipeTypeHelper.readRecipeId(stack);
        String recipeTypeId = PatternRecipeTypeHelper.readRecipeTypeId(stack);
        if (strongPatternMatchMode && (recipeId.isEmpty() || recipeTypeId.isEmpty())) {
            gtShanhai$setStrongPatternMatchWarning(slot, true);
            return null;
        }
        GTRecipe recipe = PatternRecipeTypeHelper.resolveRecipeForPattern(getLevel(), recipeTypeId, recipeId);
        if (recipe != null && PatternRecipeTypeHelper.storedRecipeFitsPattern(stack, getLevel(), recipe)) {
            patternRecipeTypeIds[slot] = recipe.recipeType.registryName.toString();
            patternRecipeTypeStackHashes[slot] = stackKeyHash(stack);
            if (strongPatternMatchMode) {
                gtShanhai$setStrongPatternMatchWarning(slot, false);
            }
            return recipe;
        }
        if (strongPatternMatchMode) {
            gtShanhai$setStrongPatternMatchWarning(slot, true);
            return null;
        }
        // 只读：本槽同时在向 AE 网络供货，绝不能像过去那样借"自愈"之机悄悄改写 NBT
        // （AEItemKey 含 NBT，改动即变身份，见 PatternRecipeTypeHelper.peekRecipe 文档）。
        recipe = PatternRecipeTypeHelper.peekRecipe(
                stack, getLevel(), gtShanhai$mergePatternInferenceInputs(slot, availableCatalystInputs));
        if (recipe != null && recipe.recipeType != null && recipe.recipeType.registryName != null) {
            patternRecipeTypeIds[slot] = recipe.recipeType.registryName.toString();
            patternRecipeTypeStackHashes[slot] = stackKeyHash(stack);
        }
        return recipe;
    }

    @Override
    public GenericStack[] gtShanhai$getPatternInferenceInputs() {
        List<GenericStack> inputs = new ArrayList<>();
        // 反推缺省催化剂只能看玩家显式放入的共享催化仓/罐。
        // stockItemHandler/stockFluidHandler 的 getStock() 会混入 AE 可见库存、主机并行池或运行中缓存，
        // 用它做 presence 会把当前任务原料误当成可省略输入，导致误缓存和误红框。
        for (int slot = 0; slot < getSharedCatalystInventory().getSlots(); slot++) {
            ItemStack stack = getSharedCatalystInventory().getStackInSlot(slot);
            if (!stack.isEmpty()) {
                inputs.add(new GenericStack(AEItemKey.of(stack), stack.getCount()));
            }
        }
        for (int tank = 0; tank < getSharedCatalystTank().getTanks(); tank++) {
            FluidStack stack = getSharedCatalystTank().getFluidInTank(tank);
            if (stack == null || stack.isEmpty()) continue;
            inputs.add(new GenericStack(gtShanhai$fluidKey(stack), stack.getAmount()));
        }
        return inputs.toArray(GenericStack[]::new);
    }

    @Override
    public long gtShanhai$getPatternInferenceFingerprint(int slot, long sharedFingerprint) {
        long hash = sharedFingerprint;
        if (slot < 0 || slot >= maxPatternCount) return hash;
        for (int i = 0; i < this.catalystItems[slot].getSlots(); i++) {
            ItemStack stack = this.catalystItems[slot].getStackInSlot(i);
            if (stack.isEmpty()) continue;
            hash = 31L * hash + AEItemKey.of(stack).hashCode();
            hash = 31L * hash + Long.hashCode(stack.getCount());
        }
        for (int tank = 0; tank < this.catalystFluids[slot].getTanks(); tank++) {
            FluidStack stack = this.catalystFluids[slot].getFluidInTank(tank);
            if (stack == null || stack.isEmpty()) continue;
            hash = 31L * hash + gtShanhai$fluidKey(stack).hashCode();
            hash = 31L * hash + Long.hashCode(stack.getAmount());
        }
        return hash;
    }

    private GenericStack[] gtShanhai$mergePatternInferenceInputs(int slot,
            @Nullable GenericStack[] sharedInputs) {
        int sharedCount = sharedInputs == null ? 0 : sharedInputs.length;
        List<GenericStack> inputs = new ArrayList<>(sharedCount + 18);
        if (sharedInputs != null) {
            for (GenericStack input : sharedInputs) {
                if (input != null && input.what() != null && input.amount() > 0L) {
                    inputs.add(input);
                }
            }
        }
        for (int i = 0; i < this.catalystItems[slot].getSlots(); i++) {
            ItemStack stack = this.catalystItems[slot].getStackInSlot(i);
            if (!stack.isEmpty()) {
                inputs.add(new GenericStack(AEItemKey.of(stack), stack.getCount()));
            }
        }
        for (int tank = 0; tank < this.catalystFluids[slot].getTanks(); tank++) {
            FluidStack stack = this.catalystFluids[slot].getFluidInTank(tank);
            if (stack != null && !stack.isEmpty()) {
                inputs.add(new GenericStack(gtShanhai$fluidKey(stack), stack.getAmount()));
            }
        }
        return inputs.toArray(GenericStack[]::new);
    }

    private static AEFluidKey gtShanhai$fluidKey(FluidStack stack) {
        return stack.getTag() == null
                ? AEFluidKey.of(stack.getFluid())
                : AEFluidKey.of(stack.getFluid(), stack.getTag());
    }

    public IPatternDetails gtShanhai$applyOutputMultiplier(IPatternDetails pattern, ItemStack stack) {
        if (!outputMultiplierModeEnabled || pattern == null || getLevel() == null) return pattern;
        String recipeTypeId = PatternRecipeTypeHelper.readRecipeTypeId(stack);
        long multiplier = resolvePatternOutputMultiplier(recipeTypeId);
        long recipeRevision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        int cacheKey = makeOutputMultiplierPatternCacheKey(pattern, stack, recipeTypeId, multiplier, recipeRevision);
        OutputMultiplierPatternCacheEntry cached = outputMultiplierPatternCache.get(cacheKey);
        if (cached != null && cached.matches(pattern, stack, recipeTypeId, multiplier, recipeRevision)) {
            return cached.pattern();
        }
        IPatternDetails rewritten = VirtualPatternEncodingHelper.rewritePatternOutputMultiplier(
                pattern, getLevel(), recipeTypeId, multiplier);
        // 改寫失敗也要記住。否則 AE 每次重建提供者都會再掃整份配方表。
        outputMultiplierPatternCache.put(cacheKey,
                new OutputMultiplierPatternCacheEntry(pattern, stack, recipeTypeId, multiplier, recipeRevision, rewritten));
        return rewritten;
    }

    public boolean isOutputMultiplierModeEnabled() {
        return outputMultiplierModeEnabled;
    }

    public boolean isStrongPatternMatchMode() {
        return strongPatternMatchMode;
    }

    public void setStrongPatternMatchMode(boolean enabled) {
        if (strongPatternMatchMode == enabled) return;
        strongPatternMatchMode = enabled;
        markDirty();
        Arrays.fill(strictRecipeIdWarningStackHashes, Integer.MIN_VALUE);
        int[] activeSlots = gtShanhai$getActiveSlots();
        for (int slot : activeSlots) {
            if (slot >= 0 && slot < maxPatternCount) {
                removeSlotFromGTRecipeCache(slot);
                if (enabled) {
                    ItemStack stack = getPatternInventory().getStackInSlot(slot);
                    if (!stack.isEmpty() && !gtShanhai$hasStrongPatternMetadata(stack)) {
                        gtShanhai$setStrongPatternMatchWarning(slot, true);
                    }
                }
            }
        }
        RecipeTypePatternSearchHelper.clearPatternState(this);
        if (!enabled && !strictRecipeIdWarningSlots.isEmpty()) {
            BitSet slots = (BitSet) strictRecipeIdWarningSlots.clone();
            strictRecipeIdWarningSlots.clear();
            for (int slot = slots.nextSetBit(0); slot >= 0; slot = slots.nextSetBit(slot + 1)) {
                gtShanhai$refreshPatternSlotWarning(slot);
            }
        }
    }

    public long getPatternOutputMultiplier() {
        return clampOutputMultiplier(patternOutputMultiplier);
    }

    public void setOutputMultiplierModeEnabled(boolean enabled) {
        if (enabled && !outputMultiplierModeEnabled) {
            lastDetectedHostOutputMultiplier = Long.MIN_VALUE;
            pendingDetectedHostOutputMultiplier = Long.MIN_VALUE;
            lastDetectedForgeRecipeTypeFingerprint = Integer.MIN_VALUE;
            pendingDetectedForgeRecipeTypeFingerprint = Integer.MIN_VALUE;
            lastDetectedUniversalHostOutputMultiplier = Long.MIN_VALUE;
            pendingDetectedUniversalHostOutputMultiplier = Long.MIN_VALUE;
            if (!isRemote()) {
                syncOutputMultiplierFromHost();
                return;
            }
        }
        applyOutputMultiplierSettings(enabled, patternOutputMultiplier);
    }

    public void setPatternOutputMultiplier(long multiplier) {
        applyOutputMultiplierSettings(outputMultiplierModeEnabled, multiplier);
    }

    public long getConnectedHostOutputMultiplier() {
        if (isRemote()) return getCachedHostOutputMultiplier();
        long multiplier = resolveConnectedHostOutputMultiplier();
        updateCachedHostOutputMultiplier(multiplier);
        return getCachedHostOutputMultiplier();
    }

    private long resolveConnectedHostOutputMultiplier() {
        return clampOutputMultiplier(OutputMultiplierResolver.resolveMaxHostOutputMultiplier(
                getControllers(), getLevel(), getPos()));
    }

    private long resolveConnectedUniversalHostOutputMultiplier() {
        return clampOutputMultiplier(OutputMultiplierResolver.resolveUniversalHostOutputMultiplier(
                getControllers(), getLevel(), getPos()));
    }

    private long resolveConnectedHostOutputMultiplier(@Nullable String recipeTypeId) {
        return clampOutputMultiplier(OutputMultiplierResolver.resolveHostOutputMultiplier(
                getControllers(), getLevel(), getPos(), recipeTypeId));
    }

    private long resolvePatternOutputMultiplier(@Nullable String recipeTypeId) {
        String key = recipeTypeId == null ? "" : recipeTypeId;
        if (confirmedPatternOutputMultipliers.containsKey(key)) {
            return confirmedPatternOutputMultipliers.getLong(key);
        }
        long resolved = Math.max(getPatternOutputMultiplier(), resolveConnectedHostOutputMultiplier(recipeTypeId));
        confirmedPatternOutputMultipliers.put(key, resolved);
        return resolved;
    }

    private void invalidateConfirmedPatternOutputMultipliers() {
        confirmedPatternOutputMultipliers.clear();
    }

    private long getCachedHostOutputMultiplier() {
        return clampOutputMultiplier(cachedHostOutputMultiplier);
    }

    private void updateCachedHostOutputMultiplier(long multiplier) {
        long clamped = clampOutputMultiplier(multiplier);
        if (cachedHostOutputMultiplier == clamped) return;
        cachedHostOutputMultiplier = clamped;
        markDirty();
    }

    private static long clampOutputMultiplier(long multiplier) {
        return multiplier <= 1L ? 1L : multiplier;
    }

    public void syncOutputMultiplierFromHost() {
        if (isRemote()) return;
        long multiplier = resolveConnectedHostOutputMultiplier();
        long universalMultiplier = resolveConnectedUniversalHostOutputMultiplier();
        int fingerprint = resolveForgeRecipeTypeFingerprint();
        lastDetectedHostOutputMultiplier = multiplier;
        pendingDetectedHostOutputMultiplier = multiplier;
        lastDetectedUniversalHostOutputMultiplier = universalMultiplier;
        pendingDetectedUniversalHostOutputMultiplier = universalMultiplier;
        lastDetectedForgeRecipeTypeFingerprint = fingerprint;
        pendingDetectedForgeRecipeTypeFingerprint = fingerprint;
        updateCachedHostOutputMultiplier(multiplier);
        if (!outputMultiplierModeEnabled || getPatternOutputMultiplier() != universalMultiplier) {
            applyOutputMultiplierSettings(true, universalMultiplier);
            return;
        }
        invalidateConfirmedPatternOutputMultipliers();
        clearOutputMultiplierPatternCache();
        refreshOutputMultiplierPatterns();
        updateOutputMultiplierHostSyncSubscription();
    }

    public void syncOutputMultiplierFromPattern() {
        if (getLevel() == null) return;
        for (int slot = 0; slot < getPatternInventory().getSlots(); slot++) {
            ItemStack stack = getPatternInventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            IPatternDetails pattern = PatternDetailsHelper.decodePattern(stack, getLevel());
            long multiplier = VirtualPatternEncodingHelper.detectPatternOutputMultiplier(
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
        if (!resetSlotCaches && !outputMultiplierPatternsRefreshNeeded) return;
        boolean changed = false;
        for (int slot = 0; slot < getPatternInventory().getSlots() && slot < maxPatternCount; slot++) {
            if (refreshVisibleOutputMultiplierPattern(slot, resetSlotCaches)) {
                changed = true;
            }
        }
        outputMultiplierPatternsRefreshNeeded = publishedProductOutputsMissMultiplier();
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

        long multiplier = resolvePatternOutputMultiplier(PatternRecipeTypeHelper.readRecipeTypeId(patternStack));
        if (!resetSlotCaches && visibleOutputMultiplierStampMatches(slot, patternStack, multiplier)) {
            return false;
        }

        Int2ObjectMap<IPatternDetails> slot2PatternMap = access.gtShanhai$getSlot2PatternMap();
        IPatternDetails currentPattern = slot2PatternMap.get(slot);
        IPatternDetails basePattern = createOutputMultiplierBasePattern(slot, patternStack);
        if (basePattern == null) return false;
        IPatternDetails effectivePattern = gtShanhai$applyOutputMultiplier(basePattern, patternStack);
        if (effectivePattern == null) return false;
        if (VirtualPatternEncodingHelper.productOutputsReflectMultiplier(effectivePattern, multiplier)) {
            rememberVisibleOutputMultiplierStamp(slot, patternStack, multiplier);
        }
        // 視圖和 1x 樣板共用 definition。產出數量沒變就不要再標 needPatternSync，否則 AE 每個 tick 重建提供者。
        if (sameVisiblePattern(currentPattern, effectivePattern)) {
            return false;
        }

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

    private boolean publishedProductOutputsMissMultiplier() {
        if (!outputMultiplierModeEnabled || isRemote()) return false;
        if (!((Object) this instanceof MEPatternBufferSlot2PatternAccessor access)) return false;
        Int2ObjectMap<IPatternDetails> slot2PatternMap = access.gtShanhai$getSlot2PatternMap();
        for (int slot = 0; slot < maxPatternCount && slot < getPatternInventory().getSlots(); slot++) {
            ItemStack stack = getPatternInventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            long multiplier = resolvePatternOutputMultiplier(PatternRecipeTypeHelper.readRecipeTypeId(stack));
            IPatternDetails published = slot2PatternMap.get(slot);
            if (!VirtualPatternEncodingHelper.productOutputsReflectMultiplier(published, multiplier)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameVisiblePattern(IPatternDetails first, IPatternDetails second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        if (!Objects.equals(first.getDefinition(), second.getDefinition())) return false;
        return sameGenericStacks(first.getOutputs(), second.getOutputs());
    }

    private static boolean sameGenericStacks(GenericStack[] first, GenericStack[] second) {
        if (first == second) return true;
        if (first == null || second == null || first.length != second.length) return false;
        for (int i = 0; i < first.length; i++) {
            GenericStack left = first[i];
            GenericStack right = second[i];
            if (left == right) continue;
            if (left == null || right == null
                    || left.amount() != right.amount()
                    || !Objects.equals(left.what(), right.what())) {
                return false;
            }
        }
        return true;
    }

    private void applyOutputMultiplierSettings(boolean enabled, long multiplier) {
        long clamped = clampOutputMultiplier(multiplier);
        if (outputMultiplierModeEnabled == enabled && patternOutputMultiplier == clamped) return;
        outputMultiplierModeEnabled = enabled;
        patternOutputMultiplier = clamped;
        invalidateConfirmedPatternOutputMultipliers();
        clearOutputMultiplierPatternCache();
        markDirty();
        refreshOutputMultiplierPatterns();
        updateOutputMultiplierHostSyncSubscription();
    }

    private void updateOutputMultiplierHostSyncSubscription() {
        if (isRemote()) return;
        if (outputMultiplierModeEnabled) {
            outputMultiplierHostSyncSubscription = subscribeServerTick(
                    outputMultiplierHostSyncSubscription, this::pollOutputMultiplierHostState);
        } else if (outputMultiplierHostSyncSubscription != null) {
            outputMultiplierHostSyncSubscription.unsubscribe();
            outputMultiplierHostSyncSubscription = null;
        }
    }

    private void pollOutputMultiplierHostState() {
        if (!outputMultiplierModeEnabled) return;
        if (getOffsetTimer() % OUTPUT_MULTIPLIER_HOST_CHECK_TICKS != 0L) return;
        long detected = resolveConnectedHostOutputMultiplier();
        long detectedUniversal = resolveConnectedUniversalHostOutputMultiplier();
        int fingerprint = resolveForgeRecipeTypeFingerprint();
        boolean hostStateChanged = false;
        boolean universalStateChanged = false;
        if (detected == lastDetectedHostOutputMultiplier
                && fingerprint == lastDetectedForgeRecipeTypeFingerprint) {
            // 读值回落到已应用值时必须复位 pending，否则交替翻转会被残留的旧 pending 误"确认"
            pendingDetectedHostOutputMultiplier = detected;
            pendingDetectedForgeRecipeTypeFingerprint = fingerprint;
        } else if (lastDetectedHostOutputMultiplier != Long.MIN_VALUE
                && (detected != pendingDetectedHostOutputMultiplier
                || fingerprint != pendingDetectedForgeRecipeTypeFingerprint)) {
            // 防抖：宿主倍率在模块工作↔空闲转换间会瞬时跳变（如增殖核心 idle=10 / working=1000），
            // 每次跳变都触发全量样板重编码（spark 实测单窗口累计 930ms+ 的尖峰热点）。
            // 除首次同步外，要求连续两次轮询读到同一新值才应用；交替翻转永不满足，彻底静音。
            pendingDetectedHostOutputMultiplier = detected;
            pendingDetectedForgeRecipeTypeFingerprint = fingerprint;
        } else {
            pendingDetectedHostOutputMultiplier = detected;
            lastDetectedHostOutputMultiplier = detected;
            pendingDetectedForgeRecipeTypeFingerprint = fingerprint;
            lastDetectedForgeRecipeTypeFingerprint = fingerprint;
            hostStateChanged = true;
        }

        if (detectedUniversal == lastDetectedUniversalHostOutputMultiplier) {
            pendingDetectedUniversalHostOutputMultiplier = detectedUniversal;
        } else if (lastDetectedUniversalHostOutputMultiplier != Long.MIN_VALUE
                && detectedUniversal != pendingDetectedUniversalHostOutputMultiplier) {
            pendingDetectedUniversalHostOutputMultiplier = detectedUniversal;
        } else {
            pendingDetectedUniversalHostOutputMultiplier = detectedUniversal;
            lastDetectedUniversalHostOutputMultiplier = detectedUniversal;
            universalStateChanged = true;
        }

        if (hostStateChanged) {
            updateCachedHostOutputMultiplier(detected);
        }
        if (universalStateChanged && getPatternOutputMultiplier() != detectedUniversal) {
            applyOutputMultiplierSettings(true, detectedUniversal);
            return;
        }
        if (hostStateChanged) {
            invalidateConfirmedPatternOutputMultipliers();
            clearOutputMultiplierPatternCache();
            refreshOutputMultiplierPatterns();
        }
    }

    private int resolveForgeRecipeTypeFingerprint() {
        return OutputMultiplierResolver.resolveForgeRecipeTypeFingerprint(getControllers());
    }

    private int makeOutputMultiplierPatternCacheKey(IPatternDetails pattern, ItemStack stack,
            String recipeTypeId, long multiplier, long recipeRevision) {
        int result = 31 + patternDefinitionHash(pattern);
        result = 31 * result + stackKeyHash(stack);
        result = 31 * result + Objects.hashCode(recipeTypeId);
        result = 31 * result + Long.hashCode(multiplier);
        result = 31 * result + Long.hashCode(recipeRevision);
        return result;
    }

    private void clearOutputMultiplierPatternCache() {
        outputMultiplierPatternCache.clear();
        if (visibleOutputMultiplierStamps != null) {
            Arrays.fill(visibleOutputMultiplierStamps, 0);
        }
    }

    private boolean visibleOutputMultiplierStampMatches(int slot, ItemStack stack, long multiplier) {
        int[] stamps = visibleOutputMultiplierStamps;
        if (stamps == null || slot < 0 || slot >= stamps.length) return false;
        int stamp = stamps[slot];
        return stamp != 0 && stamp == visibleOutputMultiplierStamp(stack, multiplier);
    }

    private void rememberVisibleOutputMultiplierStamp(int slot, ItemStack stack, long multiplier) {
        if (visibleOutputMultiplierStamps == null || slot < 0 || slot >= visibleOutputMultiplierStamps.length) {
            return;
        }
        int stamp = visibleOutputMultiplierStamp(stack, multiplier);
        visibleOutputMultiplierStamps[slot] = stamp == 0 ? 1 : stamp;
    }

    private int visibleOutputMultiplierStamp(ItemStack stack, long multiplier) {
        int result = 31 + stackKeyHash(stack);
        result = 31 * result + Long.hashCode(multiplier);
        result = 31 * result + Long.hashCode(DShanhaiRecipeModifierAPI.getPatternCacheRevision());
        result = 31 * result + Boolean.hashCode(keepByProduct);
        return result;
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
        if (strongPatternMatchMode && slot >= 0 && slot < gtShanhai$getPatternSlotCount()) {
            ItemStack stack = gtShanhai$getPatternStack(slot);
            String patternRecipeId = PatternRecipeTypeHelper.readRecipeId(stack);
            String patternRecipeTypeId = PatternRecipeTypeHelper.readRecipeTypeId(stack);
            String candidateRecipeTypeId = recipe == null || recipe.recipeType == null
                    || recipe.recipeType.registryName == null ? "" : recipe.recipeType.registryName.toString();
            String candidateRecipeId = recipe == null || recipe.getId() == null ? "" : recipe.getId().toString();
            if (!PatternRecipeIdMatchPolicy.matches(
                    patternRecipeTypeId, patternRecipeId, candidateRecipeTypeId, candidateRecipeId)) {
                return false;
            }
        }
        if (PatternRecipeExecutionGuard.isAuxiliaryIORecipe(recipe)) return true;
        String taggedTypeId = gtShanhai$getPatternRecipeTypeId(slot);
        if (PatternRecipeTypeHelper.recipeMatchesTypeId(recipe, taggedTypeId)) return true;
        // 星律共享搜索集：宿主原生搜索命中同组类型的等价配方（如大型化反命中化反槽）时也允许从本槽扣料；
        // 样板身份指纹校验（PatternIdentityMatcher）不受影响，跨槽串配方仍会被拦。
        return recipe != null && recipe.recipeType != null && recipe.recipeType.registryName != null
                && RecipeTypeSharedSearchSets.isShared(taggedTypeId, recipe.recipeType.registryName.toString());
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

    public boolean gtShanhai$isPatternSlotWarning(int slot) {
        if (slot < 0 || slot >= maxPatternCount) return false;
        if (strongPatternMatchMode && slot < getPatternInventory().getSlots()) {
            ItemStack stack = getPatternInventory().getStackInSlot(slot);
            int stackHash = stackKeyHash(stack);
            if (strictRecipeIdWarningStackHashes[slot] != stackHash) {
                strictRecipeIdWarningStackHashes[slot] = stackHash;
                boolean strictWarning = !stack.isEmpty() && !gtShanhai$hasStrongPatternMetadata(stack);
                if (isRemote()) {
                    strictRecipeIdWarningSlots.set(slot, strictWarning);
                } else {
                    gtShanhai$setStrongPatternMatchWarning(slot, strictWarning);
                }
            }
        }
        return gtShanhai$warningSlotSet().get(slot) || strictRecipeIdWarningSlots.get(slot);
    }

    public int gtShanhai$getWarningSlotCount() {
        return gtShanhai$warningSlotSet().cardinality();
    }

    public int gtShanhai$getStuckWarningSlotCount() {
        return stuckRuntimeWarningSlots.cardinality();
    }

    public void gtShanhai$setPatternSlotWarning(int slot, boolean warning) {
        if (slot < 0 || slot >= maxPatternCount) return;
        BitSet slots = (BitSet) gtShanhai$warningSlotSet().clone();
        if (slots.get(slot) == warning) return;
        slots.set(slot, warning);
        warningSlots = StellarPatternWarningPolicy.encodeWarningSlots(slots);
        cachedWarningSlots = (BitSet) slots.clone();
        cachedWarningSlotsEncoded = warningSlots;
        if (!isRemote()) {
            markDirty();
        }
    }

    public void gtShanhai$setPatternSlotStuckWarning(int slot,
            Object2LongMap<AEItemKey> itemSnapshot, Object2LongMap<AEFluidKey> fluidSnapshot) {
        if (!gtShanhai$isRuntimeTrackableSlot(slot)) return;
        Object2LongMap<AEItemKey> items = gtShanhai$copyPositiveSnapshot(itemSnapshot);
        Object2LongMap<AEFluidKey> fluids = gtShanhai$copyPositiveSnapshot(fluidSnapshot);
        if (items.isEmpty() && fluids.isEmpty()) {
            gtShanhai$clearPatternSlotRuntimeWarning(slot);
            return;
        }
        stuckRuntimeWarningSlots.set(slot);
        stuckRuntimeItemSnapshots.put(slot, items);
        stuckRuntimeFluidSnapshots.put(slot, fluids);
        if (slot < maxPatternCount) {
            gtShanhai$setPatternSlotWarning(slot, true);
        }
    }

    public void gtShanhai$rememberStuckBroadcast(int slot, String text) {
        if (!gtShanhai$isRuntimeTrackableSlot(slot) || text == null || text.isEmpty()) return;
        stuckRuntimeBroadcasts.put(slot, text);
    }

    public void gtShanhai$clearPatternSlotRuntimeWarning(int slot) {
        if (!gtShanhai$isRuntimeTrackableSlot(slot)) return;
        gtShanhai$clearPatternSlotRuntimeSnapshot(slot);
        if (slot < maxPatternCount) {
            gtShanhai$refreshPatternSlotWarning(slot);
        }
    }

    public void gtShanhai$clearPatternSlotWarnings() {
        boolean hadWarnings = warningSlots != null && !warningSlots.isEmpty();
        gtShanhai$clearPatternSlotRuntimeSnapshots();
        strictRecipeIdWarningSlots.clear();
        if (!hadWarnings) return;
        warningSlots = "";
        cachedWarningSlots = new BitSet();
        cachedWarningSlotsEncoded = "";
        if (!isRemote()) {
            markDirty();
        }
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
        gtShanhai$clearResolvedRuntimeWarnings();
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
    protected int[] getActiveSlots() {
        gtShanhai$clearResolvedRuntimeWarnings();
        return super.getActiveSlots();
    }

    @Override
    public int[] gtShanhai$getActiveSlots() {
        gtShanhai$clearResolvedRuntimeWarnings();
        int slotCount = getInternalSlotCount();
        if (activeSlotsScratch.length < slotCount) {
            activeSlotsScratch = Arrays.copyOf(activeSlotsScratch, slotCount);
        }

        int activeCount = 0;
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            if (getInternalSlot(slotIndex).isActive()) {
                activeSlotsScratch[activeCount++] = slotIndex;
            }
        }
        return activeCount == 0
                ? NO_ACTIVE_SLOTS
                : Arrays.copyOf(activeSlotsScratch, activeCount);
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
    public int getPatternPriority() {
        // 同物品若還有別處的 1x 樣板，AE 會先用優先級高的。倍率模式必須壓過 1x，否則計畫仍顯示合成 1 個。
        if (outputMultiplierModeEnabled
                && (getPatternOutputMultiplier() > 1L || cachedHostOutputMultiplier > 1L)) {
            return 1;
        }
        return 0;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        if (outputMultiplierModeEnabled && !isRemote() && publishedProductOutputsMissMultiplier()) {
            outputMultiplierPatternsRefreshNeeded = true;
        }
        refreshVisibleOutputMultiplierPatterns(false);
        List<IPatternDetails> patterns = super.getAvailablePatterns();
        if (wildcardPatterns == null || wildcardPatterns.isEmpty()) return patterns;
        List<IPatternDetails> combined = new ArrayList<>(patterns.size() + wildcardPatterns.size());
        combined.addAll(patterns);
        combined.addAll(wildcardPatterns);
        return List.copyOf(combined);
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
    protected GridNodeHolder createNodeHolder() {
        return new StockRequestGridNodeHolder(this);
    }

    @Override
    public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
        IGrid grid = getMainNode().getGrid();
        if (grid == null || what == null || amount <= 0L) {
            return 0L;
        }
        MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) {
            return 0L;
        }
        return storage.insert(what, amount, mode, this.actionSource);
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        if (stockItemCraftingTracker != null) {
            stockItemCraftingTracker.jobStateChange(link);
        }
        if (stockFluidCraftingTracker != null) {
            stockFluidCraftingTracker.jobStateChange(link);
        }
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        if (stockItemCraftingTracker == null && stockFluidCraftingTracker == null) {
            return ImmutableSet.of();
        }
        ImmutableSet.Builder<ICraftingLink> jobs = ImmutableSet.builder();
        if (stockItemCraftingTracker != null) {
            jobs.addAll(stockItemCraftingTracker.getRequestedJobs());
        }
        if (stockFluidCraftingTracker != null) {
            jobs.addAll(stockFluidCraftingTracker.getRequestedJobs());
        }
        return jobs.build();
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
        sideTabs.attachSubTab(FancyConfiguratorSidebarPage.single(new StrongPatternMatchConfigurator()));
        sideTabs.attachSubTab(sharedInputsPage(parentConfigurators));
        sideTabs.attachSubTab(patternBehaviorPage(parentConfigurators));
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
        PatternBufferStockRequestConfigurator request = new PatternBufferStockRequestConfigurator(this);
        StockInputConfigurator stockInput = new StockInputConfigurator(request);
        List<IFancyConfigurator> selected = new ArrayList<>(2);
        selected.add(stockInput);
        selected.add(request);
        if (PARENT_ADVANCED_ME < configurators.size()) {
            selected.add(configurators.get(PARENT_ADVANCED_ME));
        }
        return new FancyConfiguratorSidebarPage(stockInput.getTitle(), stockInput.getIcon(),
                stockInput.getTooltips(), selected, 1, STOCK_PAGE_MAX_HEIGHT);
    }

    private FancyConfiguratorSidebarPage sharedInputsPage(List<IFancyConfigurator> configurators) {
        return createGroupedSidebarPage(
                "gui.gt_shanhai.pattern_buffer_shared_inputs", 2, configurators,
                PARENT_SHARED_ITEM, PARENT_SHARED_FLUID, PARENT_SHARED_CIRCUIT);
    }

    private FancyConfiguratorSidebarPage patternBehaviorPage(List<IFancyConfigurator> configurators) {
        return createGroupedSidebarPage(
                "gui.gt_shanhai.pattern_buffer_pattern_behavior", 1, configurators,
                PARENT_BYPRODUCT, PARENT_TERMINAL_VISIBILITY, PARENT_PATTERN_CIRCUIT);
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

        private final PatternBufferStockRequestConfigurator request;

        private StockInputConfigurator(PatternBufferStockRequestConfigurator request) {
            this.request = request;
        }

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
            WidgetGroup group = new WidgetGroup(0, 0, 176, 146);
            group.addWidget(new LabelWidget(8, 2,
                    () -> Component.translatable("gui.gtlcore.stock_input_config").getString()));
            group.addWidget(new LabelWidget(130, 2,
                    () -> FormattingUtil.formatNumbers(countConfiguredStockSlots()) + " / 32"));
            group.addWidget(new PatternBufferStockSlotWidget(8, 14, stockItemHandler, stockFluidHandler,
                    RecipeTypePatternBufferPartMachine.this::setPage, page,
                    RecipeTypePatternBufferPartMachine.this));
            group.addWidget(request.createPickPanel(0, 110));
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
            group.addWidget(new LongInputWidget(78, 35, 76, 18,
                    RecipeTypePatternBufferPartMachine.this::getPatternOutputMultiplier,
                    RecipeTypePatternBufferPartMachine.this::setPatternOutputMultiplier)
                    .setMin(1L).setMax(Long.MAX_VALUE));
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

    private class StrongPatternMatchConfigurator implements IFancyConfigurator {

        @Override
        public Component getTitle() {
            return Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.strong_match.title");
        }

        @Override
        public IGuiTexture getIcon() {
            return new TextTexture("!", -1);
        }

        @Override
        public Widget createConfigurator() {
            WidgetGroup group = new WidgetGroup(0, 0, 160, 58);
            group.addWidget(new LabelWidget(6, 4, () -> Component.translatable(
                    isStrongPatternMatchMode()
                            ? "gt_shanhai.machine.recipe_type_pattern_buffer.strong_match.enabled"
                            : "gt_shanhai.machine.recipe_type_pattern_buffer.strong_match.disabled").getString()));
            group.addWidget(new SwitchWidget(6, 24, 14, 12,
                    (click, enabled) -> setStrongPatternMatchMode(enabled))
                    .setTexture(new ColorBorderTexture(-1, 0xFFFF3030), new ColorBorderTexture(-1, 0xFF20B24B))
                    .setPressed(isStrongPatternMatchMode())
                    .setSupplier(RecipeTypePatternBufferPartMachine.this::isStrongPatternMatchMode)
                    .setHoverTooltips(
                            Component.translatable(
                                    "gt_shanhai.machine.recipe_type_pattern_buffer.strong_match.on"),
                            Component.translatable(
                                    "gt_shanhai.machine.recipe_type_pattern_buffer.strong_match.off")));
            group.addWidget(new LabelWidget(24, 26, () -> Component.translatable(
                    "gt_shanhai.machine.recipe_type_pattern_buffer.strong_match.toggle").getString()));
            return group;
        }
    }

    private int wildcardPanelWidth() {
        return Math.max(176, 16 + wildcardSlotsPerPage * 18);
    }

    private void addWildcardPageControls(WidgetGroup group, int y, int width, Runnable onChange) {
        group.addWidget(new ButtonWidget(8, y, 24, 12, new TextTexture("<<", -1), click -> {
            if (selectedWildcardPage <= 0) return;
            selectedWildcardPage--;
            onChange.run();
        }));
        group.addWidget(new LabelWidget(width / 2 - 12, y + 2,
                () -> (selectedWildcardPage + 1) + " / " + wildcardMaxPages));
        group.addWidget(new ButtonWidget(width - 32, y, 24, 12, new TextTexture(">>", -1), click -> {
            if (selectedWildcardPage >= wildcardMaxPages - 1) return;
            selectedWildcardPage++;
            onChange.run();
        }));
    }

    private void addWildcardMotherSlots(WidgetGroup group, int originX, int slotY, int buttonY, Runnable onSelect) {
        if (selectedWildcardPage < 0 || selectedWildcardPage >= wildcardMaxPages) selectedWildcardPage = 0;
        int start = selectedWildcardPage * wildcardSlotsPerPage;
        int end = Math.min(start + wildcardSlotsPerPage, wildcardSlotCount);
        for (int slot = start; slot < end; slot++) {
            int column = slot - start;
            int motherSlot = slot;
            group.addWidget(new SlotWidget(wildcardPatternInventory, slot, originX + column * 18, slotY)
                    .setBackground(new IGuiTexture[]{GuiTextures.SLOT, GuiTextures.PATTERN_OVERLAY}));
            String slotText = (selectedWildcardMotherSlot == slot ? "§a" : "§7") + (slot + 1);
            group.addWidget(new ButtonWidget(originX + 1 + column * 18, buttonY, 16, 10,
                    new TextTexture(slotText, -1), click -> {
                        selectedWildcardMotherSlot = motherSlot;
                        onSelect.run();
                    }));
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
            configuratorPage = new WidgetGroup(0, 0, wildcardPanelWidth(), 168);
            fillConfigurator(configuratorPage);
            return configuratorPage;
        }

        private void fillConfigurator(WidgetGroup group) {
            int width = group.getSize().width;
            List<GTRecipeType> hostTypes =
                    WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(getControllers());
            group.addWidget(new LabelWidget(8, 2,
                    () -> Component.translatable("gui.gt_shanhai.wildcard_pattern_config").getString()));
            addWildcardPageControls(group, 14, width, this::rebuildConfigurator);
            addWildcardMotherSlots(group, 8, 28, 47, this::rebuildConfigurator);
            group.addWidget(new LabelWidget(8, 60,
                    () -> Component.translatable("gui.gt_shanhai.wildcard_pattern_slot",
                            selectedWildcardMotherSlot + 1, currentWildcardRecipeTypeText(hostTypes)).getString()));
            group.addWidget(new LabelWidget(8, 73,
                    () -> Component.translatable("gui.gt_shanhai.wildcard_pattern_expanded",
                            selectedWildcardPreviewPatterns().size()).getString()));
            group.addWidget(new PatternCycleWidget(8, 85, width - 16, 36,
                    () -> selectedWildcardPreviewPatterns()));

            recipeTypeList = new DraggableScrollableWidgetGroup(8, 123, width - 16, 42);
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

    private List<IPatternDetails> selectedWildcardPreviewPatterns() {
        int slot = selectedWildcardMotherSlot;
        if (slot < 0 || slot >= wildcardPreviewByMother.size()) return List.of();
        List<IPatternDetails> preview = wildcardPreviewByMother.get(slot);
        return preview == null ? List.of() : preview;
    }

    private void addWildcardPreview(int motherSlot, @Nullable IPatternDetails pattern) {
        if (pattern == null || motherSlot < 0 || motherSlot >= wildcardPreviewByMother.size()) return;
        wildcardPreviewByMother.get(motherSlot).add(pattern);
    }

    private String currentWildcardRecipeTypeId() {
        if (selectedWildcardMotherSlot < 0 || selectedWildcardMotherSlot >= wildcardSlotCount) return "";
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
        if (slot < 0 || slot >= wildcardSlotCount || type == null || type.registryName == null) return;
        ItemStack stack = wildcardPatternInventory.getStackInSlot(slot);
        if (stack.isEmpty()) return;
        wildcardPatternInventory.setStackInSlot(slot,
                WildcardPatternRecipeTypeBinding.assign(stack, type.registryName.toString()));
    }

    private void clearWildcardRecipeType(int slot) {
        if (slot < 0 || slot >= wildcardSlotCount) return;
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
                return normalInventory.size() + wildcardSlotCount;
            }

            @Override
            public ItemStack getStackInSlot(int slot) {
                if (slot < normalInventory.size()) return normalInventory.getStackInSlot(slot);
                int wildcardSlot = slot - normalInventory.size();
                return wildcardSlot >= 0 && wildcardSlot < wildcardSlotCount
                        ? wildcardPatternInventory.getStackInSlot(wildcardSlot) : ItemStack.EMPTY;
            }

            @Override
            public void setItemDirect(int slot, ItemStack stack) {
                if (slot < normalInventory.size()) {
                    normalInventory.setItemDirect(slot, stack);
                    return;
                }
                int wildcardSlot = slot - normalInventory.size();
                if (wildcardSlot >= 0 && wildcardSlot < wildcardSlotCount) {
                    wildcardPatternInventory.setStackInSlot(wildcardSlot, stack);
                    wildcardPatternInventory.onContentsChanged(wildcardSlot);
                }
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                if (slot < normalInventory.size()) return normalInventory.isItemValid(slot, stack);
                int wildcardSlot = slot - normalInventory.size();
                return wildcardSlot >= 0 && wildcardSlot < wildcardSlotCount
                        && acceptsWildcardMother(stack);
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

    private static boolean acceptsWildcardMother(ItemStack stack) {
        return WildcardPatternBridge.isWildcardPattern(stack) || SelectedRecipePattern.isPattern(stack);
    }

    @Nullable
    private IPatternDetails rememberWildcardPattern(Map<CompoundTag, ExpandedWildcardPattern> uniquePatterns,
            IPatternDetails pattern, ItemStack patternStack, GTRecipe recipe, String assignedTypeId) {
        if (pattern == null || patternStack == null || patternStack.isEmpty()) return null;
        String storedRecipeId = PatternRecipeTypeHelper.readRecipeId(patternStack);
        if (recipe != null) {
            PatternRecipeTypeHelper.writeRecipeType(patternStack, recipe);
            if (!storedRecipeId.isEmpty()) {
                PatternRecipeTypeHelper.writeRecipeId(patternStack, storedRecipeId);
            }
        }
        IPatternDetails effectivePattern = gtShanhai$applyOutputMultiplier(pattern, patternStack);
        if (effectivePattern == null) return null;
        ItemStack effectiveStack = effectivePattern.getDefinition().toStack();
        if (effectiveStack.isEmpty()) return null;
        if (recipe != null) {
            PatternRecipeTypeHelper.writeRecipeType(effectiveStack, recipe);
            if (!storedRecipeId.isEmpty()) {
                PatternRecipeTypeHelper.writeRecipeId(effectiveStack, storedRecipeId);
            }
        }
        uniquePatterns.putIfAbsent(effectiveStack.serializeNBT(),
                new ExpandedWildcardPattern(effectivePattern, effectiveStack, recipe, assignedTypeId));
        return effectivePattern;
    }

    private void refreshWildcardPatterns(boolean restorePersistedData) {
        if (isRemote() || getLevel() == null) return;
        rebuildingWildcardPatterns = true;
        clearWildcardPatternData(false);

        Map<CompoundTag, ExpandedWildcardPattern> uniquePatterns = new LinkedHashMap<>();
        for (int motherSlot = 0; motherSlot < wildcardSlotCount; motherSlot++) {
            ItemStack wildcardStack = wildcardPatternInventory.getStackInSlot(motherSlot);
            String assignedTypeId = PatternRecipeTypeHelper.readRecipeTypeId(wildcardStack);
            if (SelectedRecipePattern.isPattern(wildcardStack)) {
                for (SelectedRecipePattern.Expanded expanded : SelectedRecipePattern.expand(wildcardStack, getLevel())) {
                    GTRecipe recipe = expanded.recipe();
                    if (!assignedTypeId.isEmpty() && (recipe == null || recipe.recipeType == null
                            || recipe.recipeType.registryName == null
                            || !assignedTypeId.equals(recipe.recipeType.registryName.toString()))) {
                        continue;
                    }
                    addWildcardPreview(motherSlot, rememberWildcardPattern(uniquePatterns,
                            expanded.pattern(), expanded.stack(), recipe, assignedTypeId));
                }
                continue;
            }
            for (IPatternDetails pattern : WildcardPatternBridge.expandPatterns(wildcardStack, getLevel())) {
                ItemStack patternStack = pattern.getDefinition().toStack();
                if (patternStack.isEmpty()) continue;
                GTRecipe recipe = WildcardPatternRecipeTypeBinding.findRecipe(pattern, assignedTypeId);
                if (!assignedTypeId.isEmpty() && recipe == null) continue;
                addWildcardPreview(motherSlot, rememberWildcardPattern(uniquePatterns,
                        pattern, patternStack, recipe, assignedTypeId));
            }
        }

        for (ExpandedWildcardPattern expanded : uniquePatterns.values()) {
            int localSlot = wildcardPatterns.size();
            int globalSlot = maxPatternCount + localSlot;
            MEPatternBufferPartMachineBase.InternalSlot internalSlot =
                    new StellarPatternBufferInternalSlot(globalSlot);
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
            ItemStack stack = wildcardPatterns.get(i).getDefinition().toStack();
            String storedRecipeId = PatternRecipeTypeHelper.readRecipeId(stack);
            GTRecipe recipe = SelectedRecipePattern.hasRecipeId(stack)
                    ? SelectedRecipePattern.recipeOf(stack, getLevel(), assignedTypeId)
                    : WildcardPatternRecipeTypeBinding.findRecipe(wildcardPatterns.get(i), assignedTypeId);
            wildcardResolvedRecipes.set(i, recipe);
            if (recipe != null) {
                PatternRecipeTypeHelper.writeRecipeType(stack, recipe);
                if (!storedRecipeId.isEmpty()) {
                    PatternRecipeTypeHelper.writeRecipeId(stack, storedRecipeId);
                }
            }
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
        if (wildcardPreviewByMother != null) {
            for (int motherSlot = 0; motherSlot < wildcardPreviewByMother.size(); motherSlot++) {
                List<IPatternDetails> preview = wildcardPreviewByMother.get(motherSlot);
                if (preview != null) preview.clear();
            }
        }
        if (wildcardPatternStacks != null) wildcardPatternStacks.clear();
        if (wildcardInternalSlots != null) wildcardInternalSlots.clear();
        if (wildcardResolvedRecipes != null) wildcardResolvedRecipes.clear();
        if (wildcardAssignedRecipeTypeIds != null) wildcardAssignedRecipeTypeIds.clear();
        if (wildcardPatternToSlot != null) wildcardPatternToSlot.clear();
        gtShanhai$clearWildcardRuntimeWarnings();
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

    public void setPatternPreviewLevelFallback(@Nullable Supplier<Level> fallback) {
        this.patternPreviewLevelFallback = fallback;
    }

    private Level patternPreviewLevel() {
        Level level = getLevel();
        if (level != null) return level;
        Supplier<Level> fallback = patternPreviewLevelFallback;
        return fallback == null ? null : fallback.get();
    }

    public StellarTerminalDetail terminalDetail(long serverId) {
        Component name = this.customName == null || this.customName.isEmpty()
                ? Component.translatable(getDefinition().getDescriptionId())
                : Component.literal(this.customName);
        Component host = Component.empty();
        int hostCount = 0;
        boolean hostFormed = false;
        int hostX = 0;
        int hostY = 0;
        int hostZ = 0;
        List<? extends IMultiController> controllers = getControllers();
        if (controllers != null) {
            for (int index = 0; index < controllers.size(); index++) {
                IMultiController controller = controllers.get(index);
                if (controller == null) continue;
                hostCount++;
                if (controller.isFormed()) hostFormed = true;
                MultiblockControllerMachine machine = controller.self();
                if (machine == null || machine.getDefinition() == null || !host.getString().isEmpty()) continue;
                host = Component.translatable(machine.getDefinition().getDescriptionId());
                hostX = machine.getPos().getX();
                hostY = machine.getPos().getY();
                hostZ = machine.getPos().getZ();
            }
        }
        int capacity = Math.max(0, maxPatternCount);
        int filled = 0;
        ItemStackTransfer patterns = getPatternInventory();
        if (patterns != null) {
            int slots = Math.min(capacity, patterns.getSlots());
            for (int slot = 0; slot < slots; slot++) {
                if (!patterns.getStackInSlot(slot).isEmpty()) filled++;
            }
        }
        int mothers = 0;
        for (int slot = 0; slot < wildcardSlotCount; slot++) {
            if (!wildcardPatternInventory.getStackInSlot(slot).isEmpty()) mothers++;
        }
        ItemStack circuitStack = getSharedCircuitInventory().getStackInSlot(0);
        int circuit = circuitStack.isEmpty() ? -1 : IntCircuitBehaviour.getCircuitConfiguration(circuitStack);
        String hostStatus = hostCount == 0 ? "" : clip(StellarPatternStuckWatch.currentHostStatus(this), 180);
        String hostTypes = hostCount == 0 ? "" : terminalHostRecipeTypes();
        List<BlockPos> proxyPositions = getBoundProxyPositions();
        int proxyCount = proxyPositions == null ? 0 : proxyPositions.size();
        return new StellarTerminalDetail(serverId, name, host,
                getPos().getX(), getPos().getY(), getPos().getZ(),
                filled, capacity, mothers, wildcardPatterns.size(),
                isOutputMultiplierModeEnabled(), getPatternOutputMultiplier(),
                circuit, gtShanhai$getWarningSlotCount(), isOnline(),
                hostCount, hostFormed, hostX, hostY, hostZ,
                hostStatus, hostTypes, terminalStuckBroadcast(),
                proxyCount, terminalProxyLines(proxyPositions, proxyCount));
    }

    private String terminalProxyLines(List<BlockPos> positions, int proxyCount) {
        if (positions == null || proxyCount <= 0) return "";
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        Level level = getLevel();
        int limit = Math.min(proxyCount, 8);
        for (int index = 0; index < limit; index++) {
            BlockPos pos = positions.get(index);
            if (pos == null) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ());
            builder.append(' ').append(proxyStateLabel(level, pos));
            shown++;
        }
        int hidden = proxyCount - shown;
        if (hidden > 0) {
            if (builder.length() > 0) builder.append('\n');
            builder.append("还有 ").append(hidden).append(" 个代理");
        }
        return clip(builder.toString(), 480);
    }

    private static String proxyStateLabel(Level level, BlockPos pos) {
        if (level == null || !level.hasChunkAt(pos)) return "区块未加载";
        if (MetaMachine.getMachine(level, pos) instanceof MultiblockPartMachine part && part.isFormed()) {
            return "已成型";
        }
        return "未成型";
    }

    private String terminalHostRecipeTypes() {
        List<String> ids = gtShanhai$hostRecipeTypeIds();
        if (ids.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (int index = 0; index < ids.size() && shown < 3; index++) {
            if (builder.length() > 0) builder.append(" / ");
            GTRecipeType type = PatternRecipeTypeHelper.resolveRecipeType(ids.get(index));
            builder.append(type == null ? ids.get(index) : wildcardRecipeTypeDisplayName(type));
            shown++;
        }
        if (ids.size() > shown) {
            builder.append(" 等").append(ids.size() - shown).append("项");
        }
        return clip(builder.toString(), 120);
    }

    private String terminalStuckBroadcast() {
        if (stuckRuntimeBroadcasts.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (int slot = stuckRuntimeWarningSlots.nextSetBit(0); slot >= 0 && shown < 4;
                slot = stuckRuntimeWarningSlots.nextSetBit(slot + 1)) {
            String line = stuckRuntimeBroadcasts.get(slot);
            if (line == null || line.isEmpty()) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(line);
            shown++;
        }
        int hidden = stuckRuntimeWarningSlots.cardinality() - shown;
        if (hidden > 0) {
            if (builder.length() > 0) builder.append('\n');
            builder.append("还有 ").append(hidden).append(" 个卡死槽");
        }
        return clip(builder.toString(), 960);
    }

    private static String clip(String text, int max) {
        if (text == null || text.isEmpty()) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    public boolean isValidRemotePatternSlot(int slot) {
        return slot >= 0 && slot < this.maxPatternCount;
    }

    public void snapshotWildcardHostTypes(List<String> ids, List<String> names) {
        for (GTRecipeType type : WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(getControllers())) {
            if (type == null || type.registryName == null || ids.size() >= 64) continue;
            ids.add(type.registryName.toString());
            names.add(wildcardRecipeTypeDisplayName(type));
        }
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
        return createRemoteSidebarUI(player, holder,
                createStockInputSidebarPage(collectParentConfigurators()));
    }

    public ModularUI createRemoteOutputMultiplierUI(Player player, IUIHolder holder) {
        return createRemoteSidebarUI(player, holder,
                FancyConfiguratorSidebarPage.single(new OutputMultiplierConfigurator()));
    }

    public ModularUI createRemoteStrongPatternMatchUI(Player player, IUIHolder holder) {
        return createRemoteSidebarUI(player, holder,
                FancyConfiguratorSidebarPage.single(new StrongPatternMatchConfigurator()));
    }

    public ModularUI createRemoteSharedInputsUI(Player player, IUIHolder holder) {
        return createRemoteSidebarUI(player, holder, sharedInputsPage(collectParentConfigurators()));
    }

    public ModularUI createRemotePatternBehaviorUI(Player player, IUIHolder holder) {
        return createRemoteSidebarUI(player, holder, patternBehaviorPage(collectParentConfigurators()));
    }

    public ModularUI createRemoteRefundUI(Player player, IUIHolder holder) {
        List<IFancyConfigurator> parent = collectParentConfigurators();
        if (PARENT_REFUND >= parent.size()) return null;
        return createRemoteSidebarUI(player, holder,
                FancyConfiguratorSidebarPage.single(parent.get(PARENT_REFUND)));
    }

    public ModularUI createRemoteWildcardUI(Player player, IUIHolder holder,
            List<String> typeIds, List<String> typeNames) {
        int width = Math.max(176, wildcardPanelWidth());
        int inventoryY = 182;
        int height = inventoryY + REMOTE_PLAYER_INVENTORY_HEIGHT;
        WidgetGroup root = new WidgetGroup(0, 0, width, height);
        WidgetGroup slotHost = new WidgetGroup(0, 30, width, 30);
        Runnable[] rebuildSlots = new Runnable[1];
        rebuildSlots[0] = () -> {
            slotHost.clearAllWidgets();
            addWildcardMotherSlots(slotHost, 8, 0, 18, () -> rebuildSlots[0].run());
        };
        root.addWidget(new LabelWidget(8, 4, () -> Component.translatable(
                "gui.gt_shanhai.wildcard_pattern_config").getString()));
        addWildcardPageControls(root, 16, width, () -> rebuildSlots[0].run());
        rebuildSlots[0].run();
        root.addWidget(slotHost);
        root.addWidget(new LabelWidget(8, 62, () -> Component.translatable(
                "gui.gt_shanhai.wildcard_pattern_slot",
                selectedWildcardMotherSlot + 1, remoteWildcardTypeText()).getString()));
        root.addWidget(new LabelWidget(8, 74, () -> Component.translatable(
                "gui.gt_shanhai.wildcard_pattern_expanded", selectedWildcardPreviewPatterns().size()).getString()));

        DraggableScrollableWidgetGroup typeList = new DraggableScrollableWidgetGroup(8, 86, width - 16, 72);
        typeList.setBackground(GuiTextures.DISPLAY);
        typeList.setYScrollBarWidth(4).setYBarStyle(
                new ColorRectTexture(0xFF3F4252),
                new ColorRectTexture(0xFFE0E2EA).setRadius(2));
        int count = Math.min(typeIds.size(), typeNames.size());
        int y = 2;
        typeList.addWidget(new ButtonWidget(2, y, 148, 16,
                new TextTexture(Component.translatable("gui.gt_shanhai.wildcard_pattern_auto").getString(), -1),
                click -> clearWildcardRecipeType(selectedWildcardMotherSlot)));
        y += 18;
        for (int index = 0; index < count; index++) {
            String typeId = typeIds.get(index);
            String typeName = typeNames.get(index);
            typeList.addWidget(new ButtonWidget(2, y, 148, 16,
                    new TextTexture(typeName, -1),
                    click -> assignWildcardRecipeTypeId(selectedWildcardMotherSlot, typeId)));
            y += 18;
        }
        root.addWidget(typeList);
        root.addWidget(remoteReturnButton(player, width - 64, 162));
        root.addWidget(remotePlayerInventory(player, width, inventoryY));
        return new ModularUI(width, height, holder, player)
                .widget(root)
                .background(GuiTextures.BACKGROUND);
    }

    private ModularUI createRemoteSidebarUI(Player player, IUIHolder holder, FancyConfiguratorSidebarPage page) {
        Widget content = page.createMainPage(null);
        int width = Math.max(176, content.getSize().width);
        int inventoryY = content.getSize().height + 22;
        int height = inventoryY + REMOTE_PLAYER_INVENTORY_HEIGHT;
        WidgetGroup root = new WidgetGroup(0, 0, width, height);
        root.addWidget(content);
        root.addWidget(remoteReturnButton(player, width - 64, content.getSize().height + 2));
        root.addWidget(remotePlayerInventory(player, width, inventoryY));
        return new ModularUI(width, height, holder, player)
                .widget(root)
                .background(GuiTextures.BACKGROUND);
    }

    private static Widget remotePlayerInventory(Player player, int width, int y) {
        int x = Math.max(7, (width - 162) / 2);
        return UITemplate.bindPlayerInventory(player.getInventory(), GuiTextures.SLOT, x, y, true);
    }

    private ButtonWidget remoteReturnButton(Player player, int x, int y) {
        return new ButtonWidget(x, y, 56, 16, new TextTexture(
                Component.translatable("gui.gt_shanhai.remote_pattern_return").getString()), click -> {
            if (player instanceof ServerPlayer serverPlayer) {
                com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternTerminalOpenHelper
                        .openFirst(serverPlayer);
            }
        });
    }

    private void assignWildcardRecipeTypeId(int slot, String typeId) {
        if (slot < 0 || slot >= wildcardSlotCount || typeId == null || typeId.isEmpty()) return;
        ItemStack stack = wildcardPatternInventory.getStackInSlot(slot);
        if (stack.isEmpty()) return;
        wildcardPatternInventory.setStackInSlot(slot, WildcardPatternRecipeTypeBinding.assign(stack, typeId));
    }

    private String remoteWildcardTypeText() {
        String typeId = currentWildcardRecipeTypeId();
        if (typeId.isEmpty()) {
            return Component.translatable("gui.gt_shanhai.wildcard_pattern_auto").getString();
        }
        GTRecipeType type = PatternRecipeTypeHelper.resolveRecipeType(typeId);
        if (type == null) {
            return Component.translatable("gui.gt_shanhai.wildcard_pattern_unavailable_type", typeId).getString();
        }
        return wildcardRecipeTypeDisplayName(type);
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
        Arrays.fill(patternRecipeTypeIds, "");
        Arrays.fill(patternRecipeTypeStackHashes, Integer.MIN_VALUE);
    }

    private void refreshPatternRecipeType(int slot) {
        if (slot < 0 || slot >= patternRecipeTypeIds.length) return;
        if (slot >= getPatternInventory().getSlots()) {
            patternRecipeTypeIds[slot] = "";
            patternRecipeTypeStackHashes[slot] = Integer.MIN_VALUE;
            return;
        }
        ItemStack stack = getPatternInventory().getStackInSlot(slot);
        patternRecipeTypeIds[slot] = PatternRecipeTypeHelper.readRecipeTypeId(stack);
        patternRecipeTypeStackHashes[slot] = stackKeyHash(stack);
    }

    private void gtShanhai$setStrongPatternMatchWarning(int slot, boolean warning) {
        if (isRemote() || slot < 0 || slot >= maxPatternCount) return;
        if (slot < getPatternInventory().getSlots()) {
            strictRecipeIdWarningStackHashes[slot] =
                    stackKeyHash(getPatternInventory().getStackInSlot(slot));
        }
        if (warning) {
            strictRecipeIdWarningSlots.set(slot);
        } else {
            strictRecipeIdWarningSlots.clear(slot);
        }
        gtShanhai$refreshPatternSlotWarning(slot);
    }

    private boolean gtShanhai$hasStrongPatternMetadata(ItemStack stack) {
        return PatternRecipeIdMatchPolicy.hasTypePrefix(
                PatternRecipeTypeHelper.readRecipeTypeId(stack),
                PatternRecipeTypeHelper.readRecipeId(stack));
    }

    private void gtShanhai$refreshPatternSlotWarning(int slot) {
        if (isRemote() || slot < 0 || slot >= maxPatternCount || slot >= getPatternInventory().getSlots()) return;
        ItemStack stack = getPatternInventory().getStackInSlot(slot);
        if (stack.isEmpty()) {
            gtShanhai$clearPatternSlotRuntimeSnapshot(slot);
            strictRecipeIdWarningSlots.clear(slot);
            gtShanhai$setPatternSlotWarning(slot, false);
            return;
        }
        List<String> hostRecipeTypeIds = gtShanhai$hostRecipeTypeIds();
        if (hostRecipeTypeIds.isEmpty()) {
            gtShanhai$setPatternSlotWarning(slot,
                    strictRecipeIdWarningSlots.get(slot) || stuckRuntimeWarningSlots.get(slot));
            return;
        }
        boolean wrongHost = StellarPatternWarningPolicy.isWrongHost(
                gtShanhai$getPatternRecipeTypeId(slot),
                true,
                true,
                hostRecipeTypeIds,
                RecipeTypeSharedSearchSets::isShared);
        gtShanhai$setPatternSlotWarning(slot,
                strictRecipeIdWarningSlots.get(slot) || stuckRuntimeWarningSlots.get(slot) || wrongHost);
    }

    private void gtShanhai$clearResolvedRuntimeWarnings() {
        if (isRemote() || stuckRuntimeWarningSlots.isEmpty()) return;
        BitSet slots = (BitSet) stuckRuntimeWarningSlots.clone();
        for (int slot = slots.nextSetBit(0); slot >= 0; slot = slots.nextSetBit(slot + 1)) {
            if (gtShanhai$isRuntimeWarningResolved(slot)) {
                gtShanhai$clearPatternSlotRuntimeWarning(slot);
            }
        }
    }

    /** 通配展開槽沒有主頁樣板格。告警裡改寫成展開序號和母槽，普通槽仍用原來的槽號。 */
    public String gtShanhai$describeStuckSlot(int slot) {
        if (slot < maxPatternCount || wildcardPatterns == null) return "";
        int localSlot = slot - maxPatternCount;
        if (localSlot < 0 || localSlot >= wildcardPatterns.size()) return "";
        StringBuilder text = new StringBuilder("通配展开 ").append(localSlot + 1);
        IPatternDetails pattern = wildcardPatterns.get(localSlot);
        if (pattern == null || wildcardPreviewByMother == null) return text.toString();
        StringBuilder mothers = new StringBuilder();
        for (int mother = 0; mother < wildcardPreviewByMother.size(); mother++) {
            List<IPatternDetails> preview = wildcardPreviewByMother.get(mother);
            if (preview == null || !preview.contains(pattern)) continue;
            if (mothers.length() > 0) mothers.append(',');
            mothers.append(mother + 1);
        }
        if (mothers.length() > 0) {
            text.append(" 母槽 ").append(mothers);
        }
        return text.toString();
    }

    /** 普通樣板槽與通配展開槽都要能登記卡死。展開槽從 {@code maxPatternCount} 起編。 */
    private boolean gtShanhai$isRuntimeTrackableSlot(int slot) {
        return slot >= 0 && slot < getInternalSlotCount();
    }

    private void gtShanhai$clearWildcardRuntimeWarnings() {
        if (stuckRuntimeWarningSlots.isEmpty()) return;
        int slot = stuckRuntimeWarningSlots.nextSetBit(maxPatternCount);
        while (slot >= 0) {
            int next = stuckRuntimeWarningSlots.nextSetBit(slot + 1);
            gtShanhai$clearPatternSlotRuntimeSnapshot(slot);
            slot = next;
        }
    }

    private boolean gtShanhai$isRuntimeWarningResolved(int slot) {
        if (!gtShanhai$isRuntimeTrackableSlot(slot)) return true;
        Object2LongMap<AEItemKey> itemSnapshot = stuckRuntimeItemSnapshots.get(slot);
        Object2LongMap<AEFluidKey> fluidSnapshot = stuckRuntimeFluidSnapshots.get(slot);
        if ((itemSnapshot == null || itemSnapshot.isEmpty()) && (fluidSnapshot == null || fluidSnapshot.isEmpty())) {
            return true;
        }
        MEPatternBufferPartMachineBase.InternalSlot internalSlot = getInternalSlot(slot);
        return !gtShanhai$stillContainsAll(itemSnapshot, internalSlot.getItemInventory())
                || !gtShanhai$stillContainsAll(fluidSnapshot, internalSlot.getFluidInventory());
    }

    private void gtShanhai$clearPatternSlotRuntimeSnapshot(int slot) {
        stuckRuntimeWarningSlots.clear(slot);
        stuckRuntimeItemSnapshots.remove(slot);
        stuckRuntimeFluidSnapshots.remove(slot);
        stuckRuntimeBroadcasts.remove(slot);
    }

    private void gtShanhai$clearPatternSlotRuntimeSnapshots() {
        stuckRuntimeWarningSlots.clear();
        stuckRuntimeItemSnapshots.clear();
        stuckRuntimeFluidSnapshots.clear();
        stuckRuntimeBroadcasts.clear();
    }

    private static <T> Object2LongOpenHashMap<T> gtShanhai$copyPositiveSnapshot(@Nullable Object2LongMap<T> source) {
        Object2LongOpenHashMap<T> result = new Object2LongOpenHashMap<>();
        if (source == null) return result;
        for (Object2LongMap.Entry<T> entry : source.object2LongEntrySet()) {
            if (entry.getLongValue() > 0L) {
                result.put(entry.getKey(), entry.getLongValue());
            }
        }
        return result;
    }

    private static <T> boolean gtShanhai$stillContainsAll(@Nullable Object2LongMap<T> snapshot,
            @Nullable Object2LongMap<T> current) {
        if (snapshot == null || snapshot.isEmpty()) return true;
        if (current == null) return false;
        for (Object2LongMap.Entry<T> entry : snapshot.object2LongEntrySet()) {
            if (current.getLong(entry.getKey()) < entry.getLongValue()) {
                return false;
            }
        }
        return true;
    }

    private List<String> gtShanhai$hostRecipeTypeIds() {
        long tick = getOffsetTimer();
        if (tick - cachedHostRecipeTypeTick >= 0L
                && tick - cachedHostRecipeTypeTick < HOST_RECIPE_TYPE_ID_CACHE_TICKS) {
            return cachedHostRecipeTypeIds;
        }
        List<GTRecipeType> hostTypes = WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(getControllers());
        if (hostTypes.isEmpty()) {
            cachedHostRecipeTypeIds = List.of();
        } else {
            List<String> result = new ArrayList<>(hostTypes.size());
            for (GTRecipeType type : hostTypes) {
                if (type != null && type.registryName != null) {
                    result.add(type.registryName.toString());
                }
            }
            cachedHostRecipeTypeIds = List.copyOf(result);
        }
        cachedHostRecipeTypeTick = tick;
        return cachedHostRecipeTypeIds;
    }

    private BitSet gtShanhai$warningSlotSet() {
        String encoded = warningSlots == null ? "" : warningSlots;
        if (!Objects.equals(cachedWarningSlotsEncoded, encoded)) {
            cachedWarningSlots = StellarPatternWarningPolicy.decodeWarningSlots(encoded);
            cachedWarningSlotsEncoded = encoded;
        }
        return cachedWarningSlots;
    }

    private record OutputMultiplierPatternCacheEntry(
            int sourcePatternDefinitionHash,
            int sourceStackHash,
            String recipeTypeId,
            long multiplier,
            long recipeRevision,
            IPatternDetails pattern) {

        OutputMultiplierPatternCacheEntry(IPatternDetails sourcePattern, ItemStack sourceStack,
                String recipeTypeId, long multiplier, long recipeRevision, IPatternDetails pattern) {
            this(patternDefinitionHash(sourcePattern), stackKeyHash(sourceStack),
                    recipeTypeId, multiplier, recipeRevision, pattern);
        }

        boolean matches(IPatternDetails sourcePattern, ItemStack sourceStack,
                String recipeTypeId, long multiplier, long recipeRevision) {
            return sourcePatternDefinitionHash == patternDefinitionHash(sourcePattern)
                    && sourceStackHash == stackKeyHash(sourceStack)
                    && Objects.equals(this.recipeTypeId, recipeTypeId)
                    && this.multiplier == multiplier
                    && this.recipeRevision == recipeRevision;
        }
    }

    String stockTagWhite() {
        return stockTagWhite == null ? "" : stockTagWhite;
    }

    String stockTagBlack() {
        return stockTagBlack == null ? "" : stockTagBlack;
    }

    void setStockTagWhite(String value) {
        String next = value == null ? "" : value;
        if (next.equals(stockTagWhite)) {
            return;
        }
        stockTagWhite = next;
        if (!isRemote()) {
            markDirty();
        }
    }

    void setStockTagBlack(String value) {
        String next = value == null ? "" : value;
        if (next.equals(stockTagBlack)) {
            return;
        }
        stockTagBlack = next;
        if (!isRemote()) {
            markDirty();
        }
    }

    private boolean stockPickSet;
    private boolean stockPickFluid;
    private int stockPickSlot = -1;
    private PatternBufferStockRequestConfigurator stockRequestUi;

    void bindStockRequestUi(PatternBufferStockRequestConfigurator ui) {
        stockRequestUi = ui;
    }

    boolean stockPickSet() {
        return stockPickSet;
    }

    boolean stockPickFluid() {
        return stockPickFluid;
    }

    int stockPickSlot() {
        return stockPickSlot;
    }

    void noteStockPick(boolean set, boolean fluid, int slot) {
        if (stockPickSet == set && (!set || stockPickFluid == fluid && stockPickSlot == slot)) {
            return;
        }
        stockPickSet = set;
        stockPickFluid = fluid;
        stockPickSlot = slot;
        if (stockRequestUi != null) {
            stockRequestUi.rebuildPickPanel();
        }
    }

    boolean stockRequestEnabled() {
        return stockRequestEnabled;
    }

    void setStockRequestEnabled(boolean enabled) {
        if (isRemote() || stockRequestEnabled == enabled) {
            return;
        }
        stockRequestEnabled = enabled;
        markDirty();
    }

    int stockTagFilledCount() {
        int count = 0;
        for (int i = 0; i < CONFIG_SIZE; i++) {
            if (stockAutomaticConfiguration.contains("Item" + i)) {
                count++;
            }
        }
        return count;
    }

    boolean stockRequestRowEnabled(boolean fluid, int slot) {
        CompoundTag row = PatternBufferStockPull.row(stockRequestLimits, fluid, slot);
        return !row.contains("Enabled") || row.getBoolean("Enabled");
    }

    void setStockRequestRowEnabled(boolean fluid, int slot, boolean enabled) {
        if (isRemote() || stockDraftIndex(fluid, slot) < 0) {
            return;
        }
        CompoundTag row = PatternBufferStockPull.row(stockRequestLimits, fluid, slot);
        if (row.contains("Enabled") && row.getBoolean("Enabled") == enabled) {
            return;
        }
        stockRequestLimits = PatternBufferStockPull.writeRow(stockRequestLimits, fluid, slot, enabled,
                row.getLong("Amount"), row.getLong("Batch"), row.getBoolean("Submitted"));
        markDirty();
    }

    String stockRequestDraft(boolean fluid, int slot, boolean amount) {
        int index = stockDraftIndex(fluid, slot);
        CompoundTag row = PatternBufferStockPull.row(stockRequestLimits, fluid, slot);
        if (index >= 0 && (amount ? stockDraftAmountSet[index] : stockDraftBatchSet[index])) {
            return Long.toString(amount ? stockDraftAmount[index] : stockDraftBatch[index]);
        }
        return Long.toString(amount ? row.getLong("Amount") : row.getLong("Batch"));
    }

    void setStockRequestDraft(boolean fluid, int slot, boolean amount, String text) {
        int index = stockDraftIndex(fluid, slot);
        if (index < 0 || isRemote()) {
            return;
        }
        long parsed = parseStockDraft(text);
        if (parsed < 0L) {
            return;
        }
        if (amount) {
            stockDraftAmount[index] = parsed;
            stockDraftAmountSet[index] = true;
        } else {
            stockDraftBatch[index] = parsed;
            stockDraftBatchSet[index] = true;
        }
    }

    void submitStockRequest(boolean fluid, int slot) {
        int index = stockDraftIndex(fluid, slot);
        if (index < 0 || isRemote()) {
            return;
        }
        CompoundTag row = PatternBufferStockPull.row(stockRequestLimits, fluid, slot);
        long amount = stockDraftAmountSet[index] ? stockDraftAmount[index] : row.getLong("Amount");
        long batch = stockDraftBatchSet[index] ? stockDraftBatch[index] : row.getLong("Batch");
        boolean enabled = !row.contains("Enabled") || row.getBoolean("Enabled");
        stockRequestLimits = PatternBufferStockPull.writeRow(stockRequestLimits, fluid, slot,
                enabled, amount, batch, true);
        stockDraftAmountSet[index] = false;
        stockDraftBatchSet[index] = false;
        markDirty();
    }

    private int stockDraftIndex(boolean fluid, int slot) {
        int items = stockItemHandler.getInventory().length;
        if (slot < 0 || slot >= items) {
            return -1;
        }
        int index = fluid ? items + slot : slot;
        return index >= 0 && index < stockDraftAmount.length ? index : -1;
    }

    private static long parseStockDraft(String text) {
        if (text == null) {
            return 0L;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0L;
        }
        try {
            long value = Long.parseLong(trimmed);
            return value < 0L ? -1L : value;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static final class StockRequestGridNodeHolder extends GridNodeHolder {

        private StockRequestGridNodeHolder(RecipeTypePatternBufferPartMachine machine) {
            super(machine);
        }

        @Override
        protected SerializableManagedGridNode createManagedNode() {
            SerializableManagedGridNode node = super.createManagedNode();
            node.addService(ICraftingRequester.class, (RecipeTypePatternBufferPartMachine) this.machine);
            return node;
        }
    }
}
