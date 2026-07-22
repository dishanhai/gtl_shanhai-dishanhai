package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.api.ae.DShanhaiAEKeyCodec;
import com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;

public class SuperDiskArrayInventory implements StorageCell {
    public static final String TAG_UUID = "shanhai_sda_uuid";
    public static final String TAG_TOTAL = "shanhai_sda_total";
    public static final String TAG_TYPES = "shanhai_sda_types";
    public static final String TAG_RUNTIME_UUID = "shanhai_sda_runtime_uuid";
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Map<ItemStack, SuperDiskArrayInventory> INVENTORY_CACHE = new WeakHashMap<>();

    private final ItemStack stack;
    private final ISaveProvider saveProvider;
    private final UUID uuid;
    private final boolean readOnlyTemplate;
    private final Map<AEKey, BigInteger> amounts = new HashMap<>();
    private BigInteger total = BigInteger.ZERO;
    private boolean persisted = true;
    private int batchChangeDepth = 0;
    private boolean batchSavePending = false;

    private SuperDiskArrayInventory(
            ItemStack stack, ISaveProvider saveProvider, UUID uuid, boolean readOnlyTemplate) {
        this.stack = stack;
        this.saveProvider = saveProvider;
        this.uuid = uuid;
        this.readOnlyTemplate = readOnlyTemplate;
        if (readOnlyTemplate) {
            loadReadOnly();
        } else {
            load();
        }
    }

    public static SuperDiskArrayInventory create(ItemStack stack, ISaveProvider saveProvider) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof SuperDiskArrayItem)) return null;
        CompoundTag tag = stack.getTag();
        boolean readOnlyTemplate = saveProvider == null && (tag == null || !tag.hasUUID(TAG_UUID));
        // 取出即分家：任何被真实 AE 上下文访问（saveProvider≠null）的副本都认领独立所有权，
        // 不再依赖 inline 态。避免同内容模板包共享确定性 UUID → 共用后端存储 → 互相溢出。
        if (saveProvider != null) {
            claimOwnership(stack);
            tag = stack.getTag();
        }

        // 无保存器的 UUID-less 栈只使用内存中的确定性身份，不得因 JEI/探测读取而认领所有权。
        UUID uuid;
        if (tag != null && tag.hasUUID(TAG_UUID)) {
            uuid = tag.getUUID(TAG_UUID);
        } else {
            CompoundTag identityTag = tag == null ? new CompoundTag() : tag;
            uuid = generateDeterministicUUID(identityTag);
            if (!readOnlyTemplate) {
                stack.getOrCreateTag().putUUID(TAG_UUID, uuid);
            }
        }

        SuperDiskArrayInventory cached = INVENTORY_CACHE.get(stack);
        if (cached != null && uuid.equals(cached.uuid) && cached.saveProvider == saveProvider
                && cached.readOnlyTemplate == readOnlyTemplate) {
            return cached;
        }
        SuperDiskArrayInventory inventory =
                new SuperDiskArrayInventory(stack, saveProvider, uuid, readOnlyTemplate);
        INVENTORY_CACHE.put(stack, inventory);
        return inventory;
    }

    /**
     * 取出即分家：为一个被玩家真实持有/被 AE 访问的 SDA 副本认领独立随机 UUID，
     * 使其拥有专属后端存储，避免与同内容的模板包（确定性 UUID）共享 backend 而互相溢出。
     *
     * <p>已认领（TAG_RUNTIME_UUID=true）的副本直接跳过，防止二次 fork。
     * JEI ghost 不在真实世界、不 tick、不经 saveProvider≠null 的 create，
     * 因此只使用内存中的确定性身份，不把 UUID 写回展示栈。
     */
    public static void claimOwnership(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof SuperDiskArrayItem)) return;
        CompoundTag tag = stack.getOrCreateTag();
        if (tag.getBoolean(TAG_RUNTIME_UUID)) return; // 已认领，跳过

        UUID newUuid = UUID.randomUUID();

        if (hasInlineCellNbt(tag)) {
            // inline 态：换成随机 UUID，inline 内容保留，首次 load() 时会迁进 backend[newUuid]
            tag.putUUID(TAG_UUID, newUuid);
            tag.putBoolean(TAG_RUNTIME_UUID, true);
            return;
        }

        // 轻量态（已破坏的旧物品，内容在共享 backend）：需要服务器复制一份到独立 UUID
        DShanhaiVirtualCellSavedData data = getSavedData();
        if (data == null) return; // 服务器不可用（如客户端 tick）：暂不认领，下次 tick 重试

        if (tag.hasUUID(TAG_UUID)) {
            UUID oldUuid = tag.getUUID(TAG_UUID);
            Map<AEKey, BigInteger> content = data.readCellBigAmounts(oldUuid);
            // world/data 无内容时，尝试从 ContentStore（kubejs/data）兜底
            // 适用于整合包分发场景：新服务器玩家首次领取 FTBQ 任务奖励
            if (content.isEmpty()
                    && com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.hasStored(oldUuid)) {
                content = com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.restore(oldUuid);
                if (!content.isEmpty()) {
                    // 同步写入新 UUID 的 ContentStore，使 tooltip 第三级兜底也能读到
                    com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.persist(newUuid, content);
                }
            }
            if (!content.isEmpty()) {
                data.updateCellBig(newUuid, "sda", SuperDiskArrayItem.TOTAL_BYTES, content);
            }
        }
        tag.putUUID(TAG_UUID, newUuid);
        tag.putBoolean(TAG_RUNTIME_UUID, true);
        INVENTORY_CACHE.remove(stack); // 旧 UUID 的缓存作废
    }

    /**
     * 基于 NBT 内容生成确定性 UUID，确保相同内容的 SDA 包使用相同 UUID。
     * 用于 JEI 注册和配方归一化，避免同一个包被识别为不同物品。
     */
    public static UUID generateDeterministicUUID(CompoundTag tag) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");

            // 包含完整 keys，避免无限元件 record 不同但 id 相同导致 JEI 合并
            if (tag.contains("keys", Tag.TAG_LIST)) {
                ListTag keys = tag.getList("keys", Tag.TAG_COMPOUND);
                for (int i = 0; i < keys.size(); i++) {
                    CompoundTag keyTag = keys.getCompound(i);
                    md.update(keyTag.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }

            // 包含 amts（数量数组）
            if (tag.contains("amts", Tag.TAG_LONG_ARRAY)) {
                long[] amts = tag.getLongArray("amts");
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(amts.length * 8);
                for (long amt : amts) buffer.putLong(amt);
                md.update(buffer.array());
            }

            // 包含 display.Name（包名称）
            if (tag.contains("display", Tag.TAG_COMPOUND)) {
                CompoundTag display = tag.getCompound("display");
                if (display.contains("Name")) {
                    md.update(display.getString("Name").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                if (display.contains("Lore", Tag.TAG_LIST)) {
                    md.update(display.getList("Lore", Tag.TAG_STRING).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }

            if (tag.contains("shanhai_fcs_lore", Tag.TAG_LIST)) {
                md.update(tag.getList("shanhai_fcs_lore", Tag.TAG_STRING).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            if (tag.contains(SuperDiskArrayItem.TAG_VIRTUAL_CELLS, Tag.TAG_LIST)) {
                md.update(tag.getList(SuperDiskArrayItem.TAG_VIRTUAL_CELLS, Tag.TAG_COMPOUND).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            byte[] hash = md.digest();
            // 从 hash 的前 16 字节构造 UUID
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(hash);
            long mostSigBits = bb.getLong();
            long leastSigBits = bb.getLong();
            return new UUID(mostSigBits, leastSigBits);
        } catch (Exception e) {
            // 如果哈希失败，返回基于时间戳的 UUID（兜底方案）
            return UUID.randomUUID();
        }
    }

    /**
     * JEI 子类型身份：真实 SDA 只按 UUID 区分；无 UUID 的展示模板额外按电量区分。
     * 这样动态电量不会拆分玩家物品，同时保留默认、空电和满电三种基础展示项。
     */
    public static String getJeiSubtypeKey(CompoundTag tag) {
        if (tag != null && tag.hasUUID(TAG_UUID)) {
            return tag.getUUID(TAG_UUID).toString();
        }
        CompoundTag identityTag = tag == null ? new CompoundTag() : tag;
        String baseKey = "template:" + generateDeterministicUUID(identityTag);
        if (tag == null || !tag.contains("internalCurrentPower", Tag.TAG_ANY_NUMERIC)) {
            return baseKey + ":power=absent";
        }
        return baseKey + ":power=" + Double.toString(tag.getDouble("internalCurrentPower"));
    }

    @Override
    public CellState getStatus() {
        return total.signum() <= 0 ? CellState.EMPTY : CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        return SuperDiskArrayItem.IDLE_DRAIN;
    }

    @Override
    public void persist() {
        if (readOnlyTemplate) return;
        if (persisted) return;
        DShanhaiVirtualCellSavedData data = getSavedData();
        if (data != null) {
            data.updateCellBig(uuid, "sda", SuperDiskArrayItem.TOTAL_BYTES, amounts);
        }
        // 同步写入 kubejs/data 持久层：仅刷新已显式导出过的 UUID，不自动创建新文件
        if (com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.hasStored(uuid)) {
            com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.persist(uuid, amounts);
        }
        writeLightweightStatsInternal(true); // fromPersist=true：主动写入，结果可信
        persisted = true;
    }

    @Override
    public boolean canFitInsideCell() {
        // SDA 是顶层容器，不能再作为另一个存储单元的子载体；否则挂载扫描会形成递归库存。
        return false;
    }

    /**
     * 原子替换父 SDA 中一个嵌套载体的 AEKey。
     *
     * <p>嵌套磁盘持久化后 NBT 会变化，必须在同一张数量表内把旧 key 的一个计数迁到新 key。
     * 这里不能走 extract + insert：非空存储元件会被常规 insert 拒绝，先删后插会直接丢失载体。
     */
    public boolean replaceOneStoredKey(AEKey oldKey, AEKey newKey) {
        if (readOnlyTemplate) return false;
        if (oldKey == null || newKey == null) return false;
        BigInteger oldAmount = amounts.get(oldKey);
        if (oldAmount == null || oldAmount.signum() <= 0) return false;
        if (oldKey.equals(newKey)) return true;

        if (BigInteger.ONE.equals(oldAmount)) {
            amounts.remove(oldKey);
        } else {
            amounts.put(oldKey, oldAmount.subtract(BigInteger.ONE));
        }
        amounts.put(newKey, amounts.getOrDefault(newKey, BigInteger.ZERO).add(BigInteger.ONE));
        saveChanges();
        return true;
    }

    boolean restoreExtractedKey(AEKey key) {
        if (readOnlyTemplate || key == null) return false;
        amounts.put(key, amounts.getOrDefault(key, BigInteger.ZERO).add(BigInteger.ONE));
        total = total.add(BigInteger.ONE);
        saveChanges();
        return true;
    }

    void beginBatchChanges() {
        batchChangeDepth++;
    }

    void endBatchChanges() {
        if (batchChangeDepth <= 0) return;
        batchChangeDepth--;
        if (batchChangeDepth == 0 && batchSavePending) {
            batchSavePending = false;
            flushSaveChanges();
        }
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (what == null || amount <= 0) return 0L;
        if (readOnlyTemplate && mode == Actionable.MODULATE) return 0L;
        if (what instanceof AEItemKey itemKey) {
            if (itemKey.getItem() instanceof SuperDiskArrayItem) return 0L;
            StorageCell nested = StorageCells.getCellInventory(itemKey.toStack(), null);
            if (nested != null && !nested.canFitInsideCell()) return 0L;
        }
        if (mode == Actionable.MODULATE) {
            BigInteger current = amounts.getOrDefault(what, BigInteger.ZERO);
            BigInteger delta = BigInteger.valueOf(amount);
            amounts.put(what, current.add(delta));
            total = total.add(delta);
            saveChanges();
        }
        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (what == null || amount <= 0) return 0L;
        if (readOnlyTemplate && mode == Actionable.MODULATE) return 0L;
        BigInteger current = amounts.getOrDefault(what, BigInteger.ZERO);
        if (current.signum() <= 0) return 0L;
        BigInteger requested = BigInteger.valueOf(amount);
        BigInteger moved = current.min(requested);
        if (mode == Actionable.MODULATE) {
            BigInteger remaining = current.subtract(moved);
            if (remaining.signum() <= 0) amounts.remove(what);
            else amounts.put(what, remaining);
            total = total.subtract(moved);
            if (total.signum() < 0) total = BigInteger.ZERO;
            saveChanges();
        }
        return moved.min(LONG_MAX).longValue();
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (Map.Entry<AEKey, BigInteger> entry : amounts.entrySet()) {
            BigInteger amount = entry.getValue();
            if (amount == null || amount.signum() <= 0) continue;
            if (amount.compareTo(LONG_MAX) > 0) out.set(entry.getKey(), Long.MAX_VALUE);
            else out.add(entry.getKey(), amount.longValue());
        }
    }

    @Override
    public Component getDescription() {
        return stack.getHoverName();
    }

    private void loadReadOnly() {
        DShanhaiVirtualCellSavedData data = getSavedData();
        boolean hasInline = readInlineCellNbt();
        if (!hasInline && data != null) {
            amounts.putAll(data.readCellBigAmounts(uuid));
        }
        if (!hasInline && amounts.isEmpty()
                && com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.hasStored(uuid)) {
            amounts.putAll(
                    com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.restore(uuid));
        }
        recalculateTotal();
    }

    private void load() {
        DShanhaiVirtualCellSavedData data = getSavedData();
        boolean migratedInline = migrateInlineCellNbt();
        if (!migratedInline && data != null) {
            amounts.putAll(data.readCellBigAmounts(uuid));
        }
        // 兜底：若 world/data 里该 UUID 无内容，尝试从 kubejs/data 持久层还原
        // 适用于新存档/世界重置/整合包分发场景
        if (!migratedInline && amounts.isEmpty()
                && com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.hasStored(uuid)) {
            amounts.putAll(
                    com.dishanhai.gt_shanhai.api.ae.DShanhaiSdaContentStore.restore(uuid));
        }
        recalculateTotal();
        if (migratedInline) {
            persist();
            if (saveProvider != null) saveProvider.saveChanges();
        } else {
            writeLightweightStats();
        }
    }

    private boolean migrateInlineCellNbt() {
        if (!readInlineCellNbt()) return false;
        CompoundTag tag = stack.getOrCreateTag();
        tag.remove("keys");
        tag.remove("amts");
        tag.remove("ic");
        persisted = false;
        return true;
    }

    private boolean readInlineCellNbt() {
        CompoundTag tag = stack.getTag();
        if (tag == null || !hasInlineCellNbt(tag)) return false;
        amounts.clear();
        ListTag keys = tag.getList("keys", Tag.TAG_COMPOUND);
        long[] amts = tag.getLongArray("amts");
        for (int i = 0; i < keys.size(); i++) {
            long amount = i < amts.length ? amts[i] : 0L;
            if (amount <= 0) continue;
            CompoundTag keyTag = keys.getCompound(i);
            AEKey key = DShanhaiAEKeyCodec.fromNormalizedTag(keyTag);
            if (key == null) continue;
            if (isInlineInfinityCellKey(keyTag)) {
                amounts.putIfAbsent(key, BigInteger.ONE);
            } else {
                BigInteger current = amounts.getOrDefault(key, BigInteger.ZERO);
                amounts.put(key, current.add(BigInteger.valueOf(amount)));
            }
        }
        return true;
    }

    private static boolean hasInlineCellNbt(CompoundTag tag) {
        return tag.contains("keys", Tag.TAG_LIST) && tag.contains("amts", Tag.TAG_LONG_ARRAY);
    }

    private static boolean isInlineInfinityCellKey(CompoundTag keyTag) {
        if (!"expatternprovider:infinity_cell".equals(keyTag.getString("id"))) return false;
        if (!keyTag.contains("tag", Tag.TAG_COMPOUND)) return false;
        CompoundTag tag = keyTag.getCompound("tag");
        return tag.contains("record", Tag.TAG_COMPOUND) && tag.getCompound("record").contains("id", Tag.TAG_STRING);
    }

    private void saveChanges() {
        persisted = false;
        if (batchChangeDepth > 0) {
            batchSavePending = true;
            return;
        }
        flushSaveChanges();
    }

    private void flushSaveChanges() {
        if (saveProvider != null) saveProvider.saveChanges();
        else persist();
    }

    private void recalculateTotal() {
        total = BigInteger.ZERO;
        for (BigInteger amount : amounts.values()) {
            if (amount != null && amount.signum() > 0) total = total.add(amount);
        }
    }

    private void writeLightweightStats() {
        writeLightweightStatsInternal(false);
    }

    /**
     * 写入轻量态统计字段到 ItemStack NBT。
     *
     * @param fromPersist true = 从 persist() 调用（主动写入，结果可信），
     *                    false = 从 load() 调用（读取时同步，可能因 world/data 临时为空而 amounts=0）。
     *                    当 fromPersist=false 且 amounts 为空时，若 NBT 中已有非零 types，
     *                    则保留原值，防止分发/新存档场景下把历史有效统计覆写为 0。
     */
    private void writeLightweightStatsInternal(boolean fromPersist) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID(TAG_UUID, uuid);
        tag.remove("keys");
        tag.remove("amts");
        tag.remove("ic");
        if (!amounts.isEmpty() || fromPersist || tag.getInt(TAG_TYPES) == 0) {
            // 内容非空：写实际值；fromPersist：主动写入，即使空也可信；原本为0：不存在历史数据，正常写
            tag.putByteArray(TAG_TOTAL, total.toByteArray());
            tag.putInt(TAG_TYPES, amounts.size());
        }
        // 否则：amounts 为空 + load 调用 + 已有非零 types → 保留现有统计，不覆盖
    }

    private static DShanhaiVirtualCellSavedData getSavedData() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return DShanhaiVirtualCellSavedData.get(server);
    }
}
