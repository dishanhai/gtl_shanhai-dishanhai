package com.dishanhai.gt_shanhai.common.machine.misc;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.util.prioritylist.IPartitionList;

import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AEItemConfigWidget;
import com.gregtechceu.gtceu.integration.ae2.machine.MEInputBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlot;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.util.ClickData;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.gtlcore.gtlcore.api.machine.trait.MEPart.IModifiableSyncOffset;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IOptimizedMEList;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DShanhaiBigTagFilterStockBusMachine extends MEInputBusPartMachine
        implements IModifiableSyncOffset, DShanhaiAENetworkMachine {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            DShanhaiBigTagFilterStockBusMachine.class,
            MEInputBusPartMachine.MANAGED_FIELD_HOLDER);

    private static int getSlotCount() {
        return com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.tagBusSlotsCount.get();
    }

    private static int getSlotsPerPage() {
        return com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.tagBusSlotsPerPage.get();
    }

    private int getSlotLimit() { return getSlotsPerPage(); }

    private static int getMaxPages() {
        return com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.tagBusMaxPages.get();
    }
    /** 单次刷新最多扫描 AE 物品数，防卡顿 */
    private static final int MAX_SCAN_ITEMS = 1024;
    private static final long FILTER_CACHE_TICKS = 10L;
    private static final ResourceTexture TEXTURE = new ResourceTexture("gtceu:textures/gui/list.png");

    @Persisted
    private String tagWhite = "";
    @Persisted
    private String tagBlack = "";
    @Persisted
    private int page = 1;
    @Persisted
    private int maxPage = 1;
    private boolean isCountSort = false;
    /** 标签内容哈希，未变则跳过全量刷新 */
    private int tagHash = 0;
    private final ObjectArrayList<CachedAeItem> filteredItemCache = new ObjectArrayList<>();
    private long filteredItemCacheTick = Long.MIN_VALUE;
    private int filteredItemCacheHash = 0;
    private IGrid filteredItemCacheGrid;
    private SimpleTagFilter cachedTagFilter;
    private String cachedTagWhite = null;
    private String cachedTagBlack = null;
    
    // 配方匹配缓存：减少 handleRecipeInner 中的 extract 尝试次数
    private final Map<GTRecipe, List<AEItemKey>> recipeMatchCache = new java.util.WeakHashMap<>();
    private long recipeMatchCacheTick = Long.MIN_VALUE;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("gt_shanhai.TagFilterBus");

    /** AE 扫描配方 handler（无 UI，供配方系统检测多于 16 种的物品） */
    private final AEStockingRecipeHandler recipeHandler;

    public DShanhaiBigTagFilterStockBusMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        this.recipeHandler = new AEStockingRecipeHandler();
        LOG.debug("TagFilterBus created: slotsCount={}, slotsPerPage={}, maxPages={}",
                getSlotCount(), getSlotsPerPage(), getMaxPages());
    }

    // ── 库存 ──────────────────────────────────────────────────────────

    @Override
    protected NotifiableItemStackHandler createInventory(Object... args) {
        return aeItemHandler = new ExportOnlyAEStockingItemList(this, getSlotLimit());
    }

    // ── UI ────────────────────────────────────────────────────────────

    @Override
    public void attachConfigurators(ConfiguratorPanel panel) {
        super.attachConfigurators(panel);

        panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                new TextTexture("A-Z"),
                new TextTexture("数量▼"),
                () -> isCountSort,
                (cd, val) -> setCountSort(val)).setTooltipsSupplier(val ->
                List.of(Component.translatable("tooltip.gtlcore.auto_pull_sort_mode"))));

        panel.attachConfigurators(new FilterIFancyConfigurator(this));
    }

    // ── UI ────────────────────────────────────────────────────────────

    public Widget createUIWidget() {
        var group = new WidgetGroup(new Position(0, 0));
        group.addWidget(new LabelWidget(3, 0, () -> Component.literal(isOnline ? "§a网络在线" : "§c网络离线").getString()));
        var config = new AEItemConfigWidget(
                3, 10, aeItemHandler);
        // 显示 16 个槽位（2行），内部处理 256 个
        int visibleRows = Math.min(getSlotsPerPage() / 8, 6);
        config.setSize(new Size(8 * 18, visibleRows * 38 + 10));
        group.addWidget(config);

        // 换页按钮
        int btnY = 10 + visibleRows * 38 + 4;
        group.addWidget(new ButtonWidget(3, btnY, 16, 16,
                GuiTextures.BUTTON_LEFT, (ClickData cd) -> pageUp()));
        group.addWidget(new ButtonWidget(125, btnY, 16, 16,
                GuiTextures.BUTTON_RIGHT, (ClickData cd) -> pageDown()));
        group.addWidget(new LabelWidget(66, btnY + 1,
                () -> page + "/" + (maxPage > 0 ? maxPage : 1)));

        return group;
    }

    private void pageUp() {
        if (page > 1) {
            page--;
            refreshList(true);
        }
    }

    private void pageDown() {
        if (page < maxPage) {
            page++;
            refreshList(true);
        }
    }

    // ── 自动 IO ───────────────────────────────────────────────────────

    @Override
    public void autoIO() {
        super.autoIO();
        if (getOffsetTimer() % 50 == 0) {
            refreshList();
        }
    }

    // ── 标签刷新 ──────────────────────────────────────────────────────

    private void refreshList() { refreshList(false); }

    private void refreshList(boolean force) {
        var grid = getMainNode().getGrid();
        if (grid == null) {
            aeItemHandler.clearInventory(0);
            return;
        }
        var storage = grid.getStorageService().getInventory();
        if (storage == null) return;

        // 标签未变 && 距上次刷新不足 100 tick → 跳过全量扫描（手动翻页不受限）
        int newHash = (tagWhite + "|" + tagBlack).hashCode();
        if (!force && newHash == tagHash && getOffsetTimer() % 100 != 0) return;
        tagHash = newHash;

        var allItems = new ArrayList<GenericStack>();
        for (CachedAeItem item : getFilteredItemsSnapshot(force)) {
            allItems.add(new GenericStack(item.key, item.amount));
        }

        if (isCountSort) {
            allItems.sort((a, b) -> Long.compare(b.amount(), a.amount()));
        }

        maxPage = Math.min(getMaxPages(), Math.max(1, (allItems.size() + getSlotsPerPage() - 1) / getSlotsPerPage()));
        if (page > maxPage) page = maxPage;

        int start = (page - 1) * getSlotsPerPage();
        var slots = aeItemHandler.getInventory();
        int idx = 0;
        for (int i = start; i < allItems.size() && idx < getSlotsPerPage(); i++) {
            var stack = allItems.get(i);
            if (!(stack.what() instanceof AEItemKey aeKey)) continue;
            long actual = storage.extract(aeKey, stack.amount(), Actionable.SIMULATE, actionSource);
            if (actual <= 0) continue;

            var slot = slots[idx];
            ((IMESlot) slot).setConfigWithoutNotify(new GenericStack(aeKey, actual));
            slot.setStock(new GenericStack(aeKey, actual));
            idx++;
        }

        aeItemHandler.clearInventory(idx);
        ((IOptimizedMEList) aeItemHandler).onConfigChanged();
        LOG.debug("refreshList done: matched={}, filled={}, slotLimit={}", allItems.size(), idx, getSlotLimit());
    }

    // ── 生命周期 ──────────────────────────────────────────────────────

    @Override
    public void onLoad() {
        super.onLoad();
        if (getLevel() instanceof ServerLevel sl) {
            sl.getServer().tell(new TickTask(1, () ->
                    ((IOptimizedMEList) aeItemHandler).onConfigChanged()));
        }
    }

    // ── NBT ───────────────────────────────────────────────────────────

    @Override
    protected CompoundTag writeConfigToTag() {
        var tag = super.writeConfigToTag();
        tag.putString("TagWhite", tagWhite);
        tag.putString("TagBlack", tagBlack);
        tag.putInt("SyncOffset", getOffset());
        tag.putInt("Page", page);
        tag.putInt("MaxPage", maxPage);
        return tag;
    }

    @Override
    protected void readConfigFromTag(CompoundTag tag) {
        super.readConfigFromTag(tag);
        if (tag.contains("TagWhite")) tagWhite = tag.getString("TagWhite");
        if (tag.contains("TagBlack")) tagBlack = tag.getString("TagBlack");
        if (tag.contains("SyncOffset")) setOffset(tag.getInt("SyncOffset"));
        if (tag.contains("Page")) page = tag.getInt("Page");
        if (tag.contains("MaxPage")) maxPage = tag.getInt("MaxPage");
    }

    // ── getter/setter ─────────────────────────────────────────────────

    @Override
    public ManagedFieldHolder getFieldHolder() { return MANAGED_FIELD_HOLDER; }

    @Override
    public String getAeJadeKind() {
        return "标签过滤库存输入总线";
    }

    @Override
    public int getAeTotalSlots() {
        return getSlotCount();
    }

    @Override
    public int getAeConfiguredSlots() {
        return getConfiguredVisibleSlots();
    }

    @Override
    public int getAeStockedSlots() {
        return getStockedVisibleSlots();
    }

    public String getTagWhite() { return tagWhite; }
    public void setTagWhite(String v) {
        tagWhite = v != null ? v : "";
        invalidateFilteredCache();
    }

    public String getTagBlack() { return tagBlack; }
    public void setTagBlack(String v) {
        tagBlack = v != null ? v : "";
        invalidateFilteredCache();
    }

    public boolean isCountSort() { return isCountSort; }
    public void setCountSort(boolean v) { isCountSort = v; }

    private int getConfiguredVisibleSlots() {
        int count = 0;
        for (ExportOnlyAEItemSlot slot : aeItemHandler.getInventory()) {
            if (slot.getConfig() != null) count++;
        }
        return count;
    }

    private int getStockedVisibleSlots() {
        int count = 0;
        for (ExportOnlyAEItemSlot slot : aeItemHandler.getInventory()) {
            if (slot.getStock() != null && slot.getStock().amount() > 0) count++;
        }
        return count;
    }

    private ObjectArrayList<CachedAeItem> getFilteredItemsSnapshot(boolean force) {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            filteredItemCache.clear();
            filteredItemCacheGrid = null;
            filteredItemCacheTick = Long.MIN_VALUE;
            return filteredItemCache;
        }
        long tick = getOffsetTimer();
        int hash = getTagHash();
        if (!force
                && filteredItemCacheGrid == grid
                && filteredItemCacheHash == hash
                && filteredItemCacheTick != Long.MIN_VALUE
                && tick - filteredItemCacheTick >= 0L
                && tick - filteredItemCacheTick < FILTER_CACHE_TICKS) {
            return filteredItemCache;
        }

        filteredItemCache.clear();
        filteredItemCacheGrid = grid;
        filteredItemCacheHash = hash;
        filteredItemCacheTick = tick;

        var storage = grid.getStorageService().getInventory();
        if (storage == null) return filteredItemCache;

        SimpleTagFilter filter = getCachedTagFilter();
        int scanned = 0;
        int accepted = 0;
        int maxAccepted = Math.max(1, getSlotCount());
        var available = storage.getAvailableStacks();
        for (var it = available.iterator(); it.hasNext() && scanned < MAX_SCAN_ITEMS && accepted < maxAccepted; ) {
            var entry = it.next();
            scanned++;
            var key = entry.getKey();
            long amount = entry.getLongValue();
            if (amount <= 0 || !(key instanceof AEItemKey aeKey)) continue;
            if (!filter.isListed(aeKey)) continue;
            filteredItemCache.add(new CachedAeItem(aeKey, amount));
            accepted++;
        }
        return filteredItemCache;
    }

    private SimpleTagFilter getCachedTagFilter() {
        if (cachedTagFilter == null
                || !Objects.equals(cachedTagWhite, tagWhite)
                || !Objects.equals(cachedTagBlack, tagBlack)) {
            cachedTagWhite = tagWhite;
            cachedTagBlack = tagBlack;
            cachedTagFilter = new SimpleTagFilter(tagWhite, tagBlack);
        }
        return cachedTagFilter;
    }

    private void invalidateFilteredCache() {
        filteredItemCache.clear();
        filteredItemCacheTick = Long.MIN_VALUE;
        filteredItemCacheGrid = null;
        cachedTagFilter = null;
        recipeMatchCache.clear();
        recipeMatchCacheTick = Long.MIN_VALUE;
    }

    private int getTagHash() {
        return (tagWhite + "|" + tagBlack).hashCode();
    }

    private static final class CachedAeItem {
        final AEItemKey key;
        final long amount;

        CachedAeItem(AEItemKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // AE 配方 handler（供配方系统检测 >16 种物品，无 UI 介入）
    // ═══════════════════════════════════════════════════════════════════

    private class AEStockingRecipeHandler extends com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler {

        AEStockingRecipeHandler() {
            super(DShanhaiBigTagFilterStockBusMachine.this, 0, IO.IN);
            LOG.debug("AEStockingRecipeHandler created");
        }

        @Override
        public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> ingredients,
                                                   @Nullable String slotName, boolean simulate) {
            LOG.debug("AEStockingRecipeHandler.handleRecipeInner: io={}, ingredients={}, simulate={}, slotsCount={}",
                    io, ingredients.size(), simulate, getSlotCount());
            if (io != IO.IN || ingredients.isEmpty()) return ingredients;
            var grid = DShanhaiBigTagFilterStockBusMachine.this.getMainNode().getGrid();
            if (grid == null) return ingredients;
            var storage = grid.getStorageService().getInventory();
            var candidates = getFilteredItemsSnapshot(false);

            var it = ingredients.listIterator();
            while (it.hasNext()) {
                var ingr = it.next();
                if (ingr.isEmpty()) { it.remove(); continue; }
                long need;
                if (ingr instanceof LongIngredient li) need = li.getActualAmount();
                else if (ingr instanceof SizedIngredient si) need = si.getAmount();
                else need = 1;
                if (need <= 0) { it.remove(); continue; }

                for (int ci = 0; ci < candidates.size() && need > 0; ci++) {
                    var item = candidates.get(ci);
                    if (!item.key.matches(ingr)) continue;
                    long extracted = storage.extract(item.key, need,
                            simulate ? Actionable.SIMULATE : Actionable.MODULATE, actionSource);
                    if (extracted <= 0) continue;
                    need -= extracted;
                }
                if (need <= 0) it.remove();
            }
            boolean satisfied = ingredients.isEmpty();
            LOG.debug("AEStockingRecipeHandler result: satisfied={}, remaining={}", satisfied, ingredients.size());
            return satisfied ? null : ingredients;
        }

        @Override
        public int getSlots() { return getSlotCount(); }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 库存列表（UI 显示用，16 槽）
    // ═══════════════════════════════════════════════════════════════════

    private class ExportOnlyAEStockingItemList extends ExportOnlyAEItemList
            implements IOptimizedMEList {

        final ObjectArrayList<AEItemKey> configList = new ObjectArrayList<>();
        final IntArrayList configIndexList = new IntArrayList();
        // getMEItemMap 按 tick 缓存：同一 tick 内多配方并行计算复用同一份 AE 实时快照，避免重复 extract
        private Object2LongMap<ItemStack> cachedMeItemMap;
        private long cachedMeItemMapTick = Long.MIN_VALUE;

        ExportOnlyAEStockingItemList(DShanhaiBigTagFilterStockBusMachine machine, int size) {
            super(machine, size, (Supplier<ExportOnlyAEItemSlot>) () ->
                    new ExportOnlyAEStockingItemSlot(DShanhaiBigTagFilterStockBusMachine.this));
            for (var slot : getInventory()) {
                ((IMESlot) slot).setOnConfigChanged(this::onConfigChanged);
            }
        }

        @Override
        public int getSlots() { return getConfigurableSlots(); }


        @Override
        public void clearInventory(int startIndex) {
            for (int i = startIndex; i < getConfigurableSlots(); i++) {
                var slot = (IConfigurableSlot) getConfigurableSlot(i);
                ((IMESlot) slot).setConfigWithoutNotify(null);
                slot.setStock(null);
            }
        }

        @Override
        public void onConfigChanged() {
            configList.clear();
            configIndexList.clear();
            for (int i = 0; i < getInventory().length; i++) {
                var config = getInventory()[i].getConfig();
                if (config != null && config.what() instanceof AEItemKey aeKey) {
                    configList.add(aeKey);
                    configIndexList.add(i);
                }
            }
        }

        @Override public boolean isStocking() { return true; }
        @Override public boolean isAutoPull() { return true; }

        @Override
        public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> ingredients,
                                                   @Nullable String slotName, boolean simulate) {
            if (io != IO.IN || ingredients.isEmpty()) return ingredients;
            var grid = getMainNode().getGrid();
            if (grid == null) return ingredients;
            var storage = grid.getStorageService().getInventory();
            boolean changed = false;

            // 配方匹配缓存：预先构建可用 key 列表
            long tick = getOffsetTimer();
            List<AEItemKey> cachedKeys = null;
            if (tick - recipeMatchCacheTick >= 0 && tick - recipeMatchCacheTick < FILTER_CACHE_TICKS) {
                cachedKeys = recipeMatchCache.get(recipe);
            }
            if (cachedKeys == null) {
                cachedKeys = buildRecipeMatchKeys(recipe, ingredients);
                recipeMatchCache.put(recipe, cachedKeys);
                recipeMatchCacheTick = tick;
            }

            var it = ingredients.listIterator();
            while (it.hasNext()) {
                var ingr = it.next();
                if (ingr.isEmpty()) { it.remove(); continue; }

                long need;
                if (ingr instanceof LongIngredient li) need = li.getActualAmount();
                else if (ingr instanceof SizedIngredient si) need = si.getAmount();
                else need = 1;
                if (need <= 0) { it.remove(); continue; }

                // 使用缓存的 key 列表，避免线性遍历
                for (AEItemKey aeKey : cachedKeys) {
                    if (need <= 0) break;
                    if (!aeKey.matches(ingr)) continue;
                    long extracted = storage.extract(aeKey, need,
                            simulate ? Actionable.SIMULATE : Actionable.MODULATE, actionSource);
                    if (extracted <= 0) continue;
                    changed = true;
                    need -= extracted;
                    if (!simulate) {
                        int ci = configList.indexOf(aeKey);
                        if (ci >= 0) {
                            var idx = configIndexList.getInt(ci);
                            var slot = getInventory()[idx];
                            var stock = slot.getStock();
                            long rem = stock != null ? stock.amount() - extracted : 0;
                            slot.setStock(rem > 0 ? new GenericStack(aeKey, rem) : null);
                        }
                    }
                }
                if (need <= 0) it.remove();
            }

            if (!simulate && changed) {
                setChanged(true);
                onContentsChanged();
            }
            return ingredients.isEmpty() ? null : ingredients;
        }

        private List<AEItemKey> buildRecipeMatchKeys(GTRecipe recipe, List<Ingredient> ingredients) {
            List<AEItemKey> keys = new java.util.ArrayList<>();
            // 先添加 configList
            for (int ci = 0; ci < configList.size(); ci++) {
                keys.add(configList.get(ci));
            }
            // 再添加标签过滤结果
            var candidates = getFilteredItemsSnapshot(false);
            for (int ci = 0; ci < candidates.size(); ci++) {
                var item = candidates.get(ci);
                if (!keys.contains(item.key)) {
                    keys.add(item.key);
                }
            }
            return keys;
        }

        @Override
        public Object2LongMap<ItemStack> getMEItemMap() {
            // 同一 tick 缓存命中：直接复用（并行计算每 tick 每配方都会调用本方法）
            long tick = getOffsetTimer();
            if (cachedMeItemMap != null && tick == cachedMeItemMapTick) {
                return cachedMeItemMap.isEmpty() ? null : cachedMeItemMap;
            }

            var grid = getMainNode().getGrid();
            if (grid == null) { cachedMeItemMap = null; return null; }
            var storage = grid.getStorageService().getInventory();
            if (storage == null) { cachedMeItemMap = null; return null; }

            var map = getItemMap();
            map.clear();

            // 关键：并行计算读的可用量必须是 AE 实时真实量，而非 slot.getStock() 残值。
            // stock 残值每 100 tick 才刷新且只减不加，会在 craft-on-demand 持续补货时严重低估
            // 可用料量，导致并行被压死、拉取量极少。对齐 gtlcore 原版直接 extract 实时查询。
            var keys = new java.util.HashSet<AEItemKey>();

            // 1. configList（UI 显示的那些）
            for (int ci = 0; ci < configList.size(); ci++) {
                var aeKey = configList.get(ci);
                if (!keys.add(aeKey)) continue;
                long amount = storage.extract(aeKey, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
                if (amount > 0) map.addTo(aeKey.toStack(), amount);
            }

            // 2. 按标签缓存补足到 slotCount 种
            int remain = getSlotCount() - map.size();
            if (remain > 0) {
                var candidates = getFilteredItemsSnapshot(false);
                for (int ci = 0; ci < candidates.size() && remain > 0; ci++) {
                    var item = candidates.get(ci);
                    if (!keys.add(item.key)) continue;
                    long amount = storage.extract(item.key, Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
                    if (amount > 0) { map.addTo(item.key.toStack(), amount); remain--; }
                }
            }

            cachedMeItemMap = map;
            cachedMeItemMapTick = tick;
            return map.isEmpty() ? null : map;
        }

    }

    // ═══════════════════════════════════════════════════════════════════
    // 库存槽位
    // ═══════════════════════════════════════════════════════════════════

    private class ExportOnlyAEStockingItemSlot extends ExportOnlyAEItemSlot
            implements IMESlot {

        private Runnable onConfigChanged;

        ExportOnlyAEStockingItemSlot(DShanhaiBigTagFilterStockBusMachine outer) {}

        @Override
        public void setOnConfigChanged(Runnable r) { this.onConfigChanged = r; }
        @Override
        public Runnable getOnConfigChanged() { return onConfigChanged; }
        @Override
        public void setConfigWithoutNotify(GenericStack stack) { this.config = stack; }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 标签过滤器 UI
    // ═══════════════════════════════════════════════════════════════════

    private class FilterIFancyConfigurator implements IFancyConfigurator {

        private final DShanhaiBigTagFilterStockBusMachine machine;

        FilterIFancyConfigurator(DShanhaiBigTagFilterStockBusMachine machine) {
            this.machine = machine;
        }

        @Override
        public Component getTitle() {
            return Component.translatable("gui.gtlcore.tag_filter_config");
        }

        @Override
        public IGuiTexture getIcon() { return TEXTURE; }

        @Override
        public WidgetGroup createConfigurator() {
            var group = new WidgetGroup(0, 0, 200, 80);

            group.addWidget(new LabelWidget(3, 5, () ->
                    Component.translatable("gui.gtlcore.tag_whitelist").getString()));
            var white = new TextFieldWidget(3, 18, 194, 14,
                    () -> machine.tagWhite,
                    val -> { machine.setTagWhite(val); refresh(); });
            white.setCurrentString(machine.tagWhite);
            group.addWidget(white);

            group.addWidget(new LabelWidget(3, 38, () ->
                    Component.translatable("gui.gtlcore.tag_blacklist").getString()));
            var black = new TextFieldWidget(3, 50, 194, 14,
                    () -> machine.tagBlack,
                    val -> { machine.setTagBlack(val); refresh(); });
            black.setCurrentString(machine.tagBlack);
            group.addWidget(black);

            return group;
        }

        private void refresh() {
            if (getLevel() instanceof ServerLevel sl) {
                sl.getServer().tell(new TickTask(1, DShanhaiBigTagFilterStockBusMachine.this::refreshList));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 简单标签匹配器
    // ═══════════════════════════════════════════════════════════════════

    private static class SimpleTagFilter implements IPartitionList {

        private final String[] whiteTags;
        private final String[] blackTags;
        private final boolean hasWhite;
        private final java.util.HashMap<Object, Boolean> cache = new java.util.HashMap<>();

        SimpleTagFilter(String white, String black) {
            String w = white != null ? white.trim() : "";
            String b = black != null ? black.trim() : "";
            // 支持 | 和 , 两种分隔符
            this.whiteTags = w.isEmpty() ? new String[0] : w.split("[|,]");
            this.blackTags = b.isEmpty() ? new String[0] : b.split("[|,]");
            this.hasWhite = whiteTags.length > 0;
        }

        @Override
        public boolean isListed(AEKey what) {
            if (!(what instanceof AEItemKey itemKey)) return false;
            return cache.computeIfAbsent(itemKey.getPrimaryKey(), k -> {
                var tags = itemKey.getItem().builtInRegistryHolder().tags()
                        .map(t -> t.location().toString())
                        .collect(Collectors.toSet());

                for (var bt : blackTags) {
                    var t = bt.trim();
                    if (t.isEmpty()) continue;
                    if (matchesAny(t, itemKey, tags)) return false;
                }

                if (hasWhite) {
                    for (var wt : whiteTags) {
                        var t = wt.trim();
                        if (t.isEmpty()) continue;
                        if (matchesAny(t, itemKey, tags)) return true;
                    }
                    return false;
                }
                return true;
            });
        }

        /** 检查 pattern 是否匹配物品的标签或模组 ID */
        private static boolean matchesAny(String pattern, AEItemKey key, Set<String> tags) {
            if (pattern.startsWith("@")) {
                // @modid 匹配：@gtceu 或 @gt* 通配
                return modIdMatches(pattern.substring(1), key.getItem().builtInRegistryHolder().key().location().getNamespace());
            }
            return tagMatchesAny(pattern, tags);
        }

        /** 模组 ID 通配匹配 */
        private static boolean modIdMatches(String pattern, String modId) {
            boolean startWild = pattern.startsWith("*");
            boolean endWild = pattern.endsWith("*");
            if (startWild && endWild && pattern.length() > 2)
                return modId.contains(pattern.substring(1, pattern.length() - 1));
            if (startWild && pattern.length() > 1)
                return modId.endsWith(pattern.substring(1));
            if (endWild && pattern.length() > 1)
                return modId.startsWith(pattern.substring(0, pattern.length() - 1));
            return modId.equals(pattern);
        }

        /**
         * 检查标签模式是否匹配任意物品标签<br>
         * 支持通配符：<br>
         * - {@code *raw*} → 包含 "raw"<br>
         * - {@code *ores} → 以 "ores" 结尾<br>
         * - {@code forge:*} → 以 "forge:" 开头<br>
         * - {@code minecraft:logs} → 精确匹配<br>
         * - {@code @gtceu} → 模组 ID 精确匹配<br>
         * - {@code @gt*} → 模组 ID 通配匹配
         */
        private static boolean tagMatchesAny(String pattern, java.util.Set<String> tags) {
            boolean startWild = pattern.startsWith("*");
            boolean endWild = pattern.endsWith("*");

            if (startWild && endWild && pattern.length() > 2) {
                // *raw* → contains "raw"
                String middle = pattern.substring(1, pattern.length() - 1);
                return tags.stream().anyMatch(t -> t.contains(middle));
            }
            if (startWild && pattern.length() > 1) {
                // *ores → endsWith "ores"
                String suffix = pattern.substring(1);
                return tags.stream().anyMatch(t -> t.endsWith(suffix));
            }
            if (endWild && pattern.length() > 1) {
                // forge:* → startsWith "forge:"
                String prefix = pattern.substring(0, pattern.length() - 1);
                return tags.stream().anyMatch(t -> t.startsWith(prefix));
            }
            // plain → exact match
            return tags.contains(pattern);
        }

        @Override
        public boolean isEmpty() { return false; }

        @Override
        public Iterable<AEKey> getItems() { return List.of(); }
    }
}
