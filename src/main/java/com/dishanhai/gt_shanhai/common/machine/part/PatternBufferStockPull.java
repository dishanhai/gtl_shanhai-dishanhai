package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.helpers.MultiCraftingTracker;
import appeng.api.stacks.AEFluidKey;

import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 星律库存输入的标签拉取与可请求限额。
 * 标签只填空槽，手动槽保持不动；请求按维持量和批量发到 ME 网络，产物回到网络。
 */
final class PatternBufferStockPull {

    private static final String INITIALIZED = "Initialized";
    private static final long TAG_SCAN_INTERVAL_TICKS = 100L;

    private StellarTagFilter cachedFilter;
    private String cachedWhite;
    private String cachedBlack;
    private final TagScanCache<AEItemKey> tagScanCache = new TagScanCache<>();

    /** 山海：只快取候選 key；網內可用量仍由每次正常 stock 同步讀取。 */
    static final class TagScanCache<T> {
        private List<T> cachedMatches = List.of();
        private Object cachedGrid;
        private Object cachedFilter;
        private long cachedRevision;
        private long cachedTick;
        private int cachedCapacity;
        private boolean initialized;

        List<T> matches(Object grid, Object filter, long revision, long tick, int capacity,
                        Supplier<List<T>> scanner) {
            if (!initialized || cachedGrid != grid || cachedFilter != filter
                    || cachedRevision != revision || cachedCapacity != capacity
                    || tick < cachedTick || tick - cachedTick >= TAG_SCAN_INTERVAL_TICKS) {
                cachedMatches = List.copyOf(scanner.get());
                cachedGrid = grid;
                cachedFilter = filter;
                cachedRevision = revision;
                cachedTick = tick;
                cachedCapacity = capacity;
                initialized = true;
            }
            return cachedMatches;
        }
    }

    static final class Refresh {
        final CompoundTag manual;
        final CompoundTag automatic;
        final CompoundTag suppressed;
        final boolean configsChanged;
        final boolean removedConfig;

        private Refresh(CompoundTag manual, CompoundTag automatic, CompoundTag suppressed,
                        boolean configsChanged, boolean removedConfig) {
            this.manual = manual;
            this.automatic = automatic;
            this.suppressed = suppressed;
            this.configsChanged = configsChanged;
            this.removedConfig = removedConfig;
        }
    }

    Refresh refreshItems(ExportOnlyAESlot[] slots, KeyCounter network, Object grid, long tick,
                         String white, String black,
                         CompoundTag manualTag, CompoundTag automaticTag, CompoundTag suppressedTag) {
        int capacity = slots.length;
        GenericStack[] current = new GenericStack[capacity];
        for (int i = 0; i < capacity; i++) {
            current[i] = slots[i].getConfig();
        }
        if (manualTag == null || !manualTag.contains(INITIALIZED)) {
            return new Refresh(writeStacks(current), emptyInitialized(),
                    suppressedTag == null ? new CompoundTag() : suppressedTag, false, false);
        }

        GenericStack[] manual = readStacks(manualTag, capacity);
        GenericStack[] automatic = readStacks(automaticTag, capacity);
        GenericStack[] nextManual = new GenericStack[capacity];
        Set<AEKey> suppressed = readSuppressed(suppressedTag);
        boolean suppressChanged = false;
        for (int i = 0; i < capacity; i++) {
            nextManual[i] = MEStellarStockConfiguration.reconcile(current[i], manual[i], automatic[i]);
            suppressChanged |= noteEdit(suppressed, applied(manual[i], automatic[i]), current[i]);
        }

        boolean tagging = hasText(white) || hasText(black);
        StellarTagFilter filter = filter(white, black);
        List<AEItemKey> matches = tagScanCache.matches(grid, filter, StellarTagFilter.revision(), tick, capacity,
                () -> tagging ? scan(network, filter, capacity) : List.of());
        Set<AEKey> manualKeys = new HashSet<>();
        List<AEItemKey> manualKeysBySlot = new ArrayList<>(capacity);
        List<AEItemKey> previousAutomatic = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            AEItemKey manualKey = itemKey(nextManual[i]);
            manualKeysBySlot.add(manualKey);
            if (manualKey != null) {
                manualKeys.add(manualKey);
            }
            previousAutomatic.add(itemKey(automatic[i]));
        }
        List<AEItemKey> accepted = new ArrayList<>();
        for (AEItemKey match : matches) {
            if (MEStellarStockConfiguration.acceptTagTarget(match, Set.of(), manualKeys, suppressed)) {
                accepted.add(match);
            }
        }
        List<AEItemKey> assigned = PatternBufferTagSlots.assign(manualKeysBySlot, previousAutomatic, accepted);

        GenericStack[] nextAutomatic = new GenericStack[capacity];
        boolean configsChanged = false;
        boolean removed = false;
        for (int i = 0; i < capacity; i++) {
            GenericStack desired = nextManual[i];
            if (desired == null && assigned.get(i) != null) {
                desired = new GenericStack(assigned.get(i), 1L);
                nextAutomatic[i] = desired;
            }
            GenericStack existing = current[i];
            if (Objects.equals(existing, desired)) {
                continue;
            }
            configsChanged = true;
            if (existing != null && (desired == null || !existing.what().equals(desired.what()))) {
                removed = true;
            }
            ((IMESlot) slots[i]).setConfigWithoutNotify(desired);
            Runnable listener = slots[i].getOnContentsChanged();
            if (listener != null) {
                listener.run();
            }
        }

        CompoundTag nextSuppressed = suppressChanged ? writeSuppressed(suppressed) : suppressedTag;
        return new Refresh(writeStacks(nextManual), writeStacks(nextAutomatic),
                nextSuppressed == null ? new CompoundTag() : nextSuppressed,
                configsChanged, removed);
    }

    void request(ExportOnlyAESlot[] itemSlots, ExportOnlyAESlot[] fluidSlots, CompoundTag limits,
                 MultiCraftingTracker itemTracker, MultiCraftingTracker fluidTracker,
                 Level level, ICraftingService crafting, IActionSource source) {
        if (level == null || crafting == null || limits == null) {
            return;
        }
        requestSide(itemSlots, limits, "I", itemTracker, level, crafting, source);
        requestSide(fluidSlots, limits, "F", fluidTracker, level, crafting, source);
    }

    private static void requestSide(ExportOnlyAESlot[] slots, CompoundTag limits, String prefix,
                                    MultiCraftingTracker tracker, Level level,
                                    ICraftingService crafting, IActionSource source) {
        if (tracker == null) {
            return;
        }
        int count = slots.length;
        for (int i = 0; i < count; i++) {
            GenericStack config = slots[i].getConfig();
            if (config == null || config.what() == null) {
                continue;
            }
            CompoundTag row = limits.getCompound(prefix + i);
            long network = 0L;
            GenericStack stock = slots[i].getStock();
            if (stock != null && config.what().equals(stock.what())) {
                network = stock.amount();
            }
            long amount = PatternBufferRequestMath.toRequest(network, row.getLong("Amount"),
                    row.getLong("Batch"), row.getBoolean("Enabled"), row.getBoolean("Submitted"));
            if (amount <= 0L) {
                continue;
            }
            tracker.handleCrafting(i, config.what(), amount, level, crafting, source);
        }
    }

    static long networkAmount(GenericStack config, GenericStack stock) {
        if (config == null || config.what() == null || stock == null || !config.what().equals(stock.what())) {
            return 0L;
        }
        return Math.max(0L, stock.amount());
    }

    static String limitKey(boolean fluid, int slot) {
        return (fluid ? "F" : "I") + slot;
    }

    static CompoundTag row(CompoundTag limits, boolean fluid, int slot) {
        if (limits == null) {
            return new CompoundTag();
        }
        String key = limitKey(fluid, slot);
        return limits.contains(key) ? limits.getCompound(key) : new CompoundTag();
    }

    static CompoundTag writeRow(CompoundTag limits, boolean fluid, int slot, boolean enabled,
                                long amount, long batch, boolean submitted) {
        CompoundTag next = limits == null ? new CompoundTag() : limits.copy();
        CompoundTag row = new CompoundTag();
        row.putBoolean("Enabled", enabled);
        row.putLong("Amount", Math.max(0L, amount));
        row.putLong("Batch", Math.max(0L, batch));
        row.putBoolean("Submitted", submitted);
        next.put(limitKey(fluid, slot), row);
        return next;
    }

    private StellarTagFilter filter(String white, String black) {
        if (cachedFilter == null || !Objects.equals(cachedWhite, white) || !Objects.equals(cachedBlack, black)) {
            cachedWhite = white;
            cachedBlack = black;
            cachedFilter = new StellarTagFilter(white, black);
        }
        return cachedFilter;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static List<AEItemKey> scan(KeyCounter network, StellarTagFilter filter, int capacity) {
        if (network == null || capacity <= 0) {
            return List.of();
        }
        List<AEItemKey> matches = new ArrayList<>();
        for (Object2LongMap.Entry<AEKey> entry : network) {
            if (matches.size() >= capacity) {
                break;
            }
            AEKey key = entry.getKey();
            if (!(key instanceof AEItemKey) || entry.getLongValue() <= 0L) {
                continue;
            }
            AEItemKey item = (AEItemKey) key;
            if (!filter.isListed(item)) {
                continue;
            }
            matches.add(item);
        }
        return matches;
    }

    private static AEItemKey itemKey(GenericStack stack) {
        if (stack == null || !(stack.what() instanceof AEItemKey)) {
            return null;
        }
        return (AEItemKey) stack.what();
    }

    private static GenericStack applied(GenericStack manual, GenericStack automatic) {
        return automatic != null ? automatic : manual;
    }

    private static boolean noteEdit(Set<AEKey> suppressed, GenericStack applied, GenericStack edited) {
        if (Objects.equals(applied, edited)) {
            return false;
        }
        boolean changed = false;
        if (applied != null && (edited == null || !applied.what().equals(edited.what()))) {
            changed = suppressed.add(applied.what());
        }
        if (edited != null) {
            changed |= suppressed.remove(edited.what());
        }
        return changed;
    }

    private static GenericStack[] readStacks(CompoundTag tag, int capacity) {
        GenericStack[] stacks = new GenericStack[capacity];
        if (tag == null) {
            return stacks;
        }
        for (int i = 0; i < capacity; i++) {
            String key = "Item" + i;
            if (tag.contains(key)) {
                stacks[i] = GenericStack.readTag(tag.getCompound(key));
            }
        }
        return stacks;
    }

    private static CompoundTag writeStacks(GenericStack[] stacks) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(INITIALIZED, true);
        for (int i = 0; i < stacks.length; i++) {
            if (stacks[i] != null) {
                tag.put("Item" + i, GenericStack.writeTag(stacks[i]));
            }
        }
        return tag;
    }

    private static CompoundTag emptyInitialized() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(INITIALIZED, true);
        return tag;
    }

    private static Set<AEKey> readSuppressed(CompoundTag tag) {
        Set<AEKey> keys = new HashSet<>();
        if (tag == null) {
            return keys;
        }
        ListTag list = tag.getList("Keys", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            GenericStack stack = GenericStack.readTag(list.getCompound(i));
            if (stack != null) {
                keys.add(stack.what());
            }
        }
        return keys;
    }

    private static CompoundTag writeSuppressed(Set<AEKey> keys) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (AEKey key : keys) {
            if (key instanceof AEItemKey || key instanceof AEFluidKey) {
                list.add(GenericStack.writeTag(new GenericStack(key, 1L)));
            }
        }
        tag.put("Keys", list);
        return tag;
    }
}
