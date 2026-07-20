package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData;

import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.AEKeyType;
import appeng.items.storage.StorageTier;
import appeng.items.tools.powered.PortableCellItem;
import appeng.menu.me.common.MEStorageMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 超级磁盘阵列 — 超大型 AE2 便携式存储单元。
 * 继承 PortableCellItem，右键打开 AE2 便携式终端，直接存取物品/流体。
 * 放入 ME 磁盘仓室或奇点数据中枢后，内部物品自动接入 AE 网络。
 * 支持额外的 virtual_cells NBT，用于定义虚拟磁盘（由 KJS 写入）。
 */
public class SuperDiskArrayItem extends PortableCellItem {

    public static final String TAG_VIRTUAL_CELLS = "virtual_cells";
    public static final String TAG_TYPE = "type";
    public static final String TAG_BYTES = "bytes";
    public static final String TAG_ITEMS = "items";
    public static final String TAG_KEYS = "keys";
    public static final String TAG_AMTS = "amts";
    public static final String TAG_HASHES = "hashes";
    public static final String TAG_DATA_UUID = "data_uuid";
    public static final String TYPE_ITEM = "item";
    public static final String TYPE_FLUID = "fluid";

    public static final int TOTAL_BYTES = Integer.MAX_VALUE - 1;
    public static final int BYTES_PER_TYPE = 2;
    public static final int TOTAL_TYPES = Integer.MAX_VALUE;
    public static final double IDLE_DRAIN = 0.1;

    public SuperDiskArrayItem(Properties props) {
        super(
            AEKeyType.items(),
            TOTAL_TYPES,
            MEStorageMenu.PORTABLE_ITEM_CELL_TYPE,
            new StorageTier(
                20, "super",
                TOTAL_BYTES, IDLE_DRAIN,
                () -> net.minecraft.world.item.Items.AIR
            ),
            props,
            0xAA44FF
        );
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltips, TooltipFlag flag) {
        tooltips.add(Component.literal(""));
        tooltips.add(Component.literal("§d§l超级磁盘阵列 §r§7- 便携式存储"));
        tooltips.add(Component.literal(""));
        tooltips.add(Component.literal("§7右键打开 §d便携式终端"));
        tooltips.add(Component.literal("§7右击样板供应器或样板总成可转移内部样板"));
        tooltips.add(Component.literal("§7放入磁盘仓室后自动接入 AE 网络"));
        var vc = getVirtualCellCount(stack);
        if (vc > 0) {
            tooltips.add(Component.literal("§7含 §d" + vc + " §7个虚拟磁盘单元"));
        }
        tooltips.add(Component.literal("§8可染色 — 右击染料改变颜色"));
        tooltips.add(Component.literal(""));
        super.appendHoverText(stack, level, tooltips, flag);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("§d超级磁盘阵列");
    }

    @Override
    public boolean isStorageCell(ItemStack stack) {
        return false;
    }

    /**
     * 取出即分家：SDA 一旦进入真实玩家/实体的物品栏（服务端），立即认领独立随机 UUID，
     * 使其拥有专属后端存储，避免同内容模板包共享确定性 UUID → 共用 backend → 互相溢出。
     * JEI 展示用的 ghost 不在真实世界、不 tick，因此保持确定性 UUID，不影响 JEI 去重。
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (level.isClientSide) return;
        SuperDiskArrayInventory.claimOwnership(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        InternalInventory target = PatternInventoryTargetHelper.find(context);
        if (target == null) return super.useOn(context);
        if (!(context.getPlayer() instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        ItemStack stack = context.getItemInHand();
        SuperDiskArrayInventory.claimOwnership(stack);
        SuperDiskArrayInventory source = SuperDiskArrayInventory.create(stack, null);
        ShanhaiSdaPatternTransfer.TransferResult result =
                ShanhaiSdaPatternTransfer.transfer(source, target);
        if (!result.foundPattern()) {
            serverPlayer.displayClientMessage(Component.translatable(
                    "gt_shanhai.message.sda_pattern_transfer.no_patterns")
                    .withStyle(ChatFormatting.YELLOW), true);
        } else if (result.transferred() == 0) {
            serverPlayer.displayClientMessage(Component.translatable(
                    "gt_shanhai.message.sda_pattern_transfer.target_full")
                    .withStyle(ChatFormatting.YELLOW), true);
        } else {
            serverPlayer.displayClientMessage(Component.translatable(
                    "gt_shanhai.message.sda_pattern_transfer.transferred",
                    result.transferred(), result.failed())
                    .withStyle(result.failed() == 0
                            ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        }
        return InteractionResult.SUCCESS;
    }

    // ============ NBT 读写 ============

    public static int getVirtualCellCount(ItemStack stack) {
        var tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_VIRTUAL_CELLS)) return 0;
        return tag.getList(TAG_VIRTUAL_CELLS, Tag.TAG_COMPOUND).size();
    }

    /** 从 SDA 物品自身的 virtual_cells NBT 读取虚拟磁盘定义 */
    public static List<VirtualCellStorage> readVirtualCells(ItemStack stack) {
        return readVirtualCells(stack, null);
    }

    /** 从 SDA 物品自身读取虚拟磁盘定义。服务端会把重型内联 NBT 迁移到 SavedData。 */
    public static List<VirtualCellStorage> readVirtualCells(ItemStack stack, @Nullable Level level) {
        List<VirtualCellStorage> result = new ArrayList<>();
        var tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_VIRTUAL_CELLS)) return result;
        DShanhaiVirtualCellSavedData savedData = level instanceof ServerLevel
                ? DShanhaiVirtualCellSavedData.get(((ServerLevel) level).getServer())
                : null;
        ListTag cellsList = tag.getList(TAG_VIRTUAL_CELLS, Tag.TAG_COMPOUND);
        for (int i = 0; i < cellsList.size(); i++) {
            CompoundTag cellTag = cellsList.getCompound(i);
            String type = cellTag.getString(TAG_TYPE);
            long bytes = cellTag.getLong(TAG_BYTES);
            UUID dataUuid = getOrCreateDataUuid(cellTag, savedData != null);
            var vcs = new VirtualCellStorage(type, bytes, stack, i, savedData, dataUuid);
            if (dataUuid != null && savedData != null) {
                vcs.loadFromExternal();
            }
            if (cellTag.contains(TAG_KEYS)) {
                vcs.loadFromNBT(cellTag);
                vcs.markDirty();
            } else if (cellTag.contains(TAG_ITEMS)) {
                vcs.loadFromNBT(cellTag.getCompound(TAG_ITEMS));
                vcs.markDirty();
            }
            if (vcs.isDirty() && savedData != null) vcs.saveToNBT(cellTag);
            result.add(vcs);
        }
        rootPutVirtualCells(stack, tag, cellsList);
        return result;
    }

    /** 将脏虚拟磁盘数据写回物品 NBT（不触发 onContentsChanged，避免递归） */
    public static void flushVirtualCells(ItemStack stack, List<VirtualCellStorage> cells) {
        if (cells == null || cells.isEmpty()) return;
        boolean anyDirty = false;
        for (var vc : cells) {
            if (vc.isDirty()) { anyDirty = true; break; }
        }
        if (!anyDirty) return;

        CompoundTag root = stack.getOrCreateTag();
        ListTag cellsList = root.contains(TAG_VIRTUAL_CELLS)
            ? root.getList(TAG_VIRTUAL_CELLS, Tag.TAG_COMPOUND)
            : new ListTag();

        for (var vc : cells) {
            if (!vc.isDirty()) continue;
            int idx = vc.getCellIndex();
            while (cellsList.size() <= idx) {
                cellsList.add(new CompoundTag());
            }
            CompoundTag cellTag = cellsList.getCompound(idx);
            cellTag.putString(TAG_TYPE, vc.getCellType());
            cellTag.putLong(TAG_BYTES, vc.getBytesCapacity());
            vc.saveToNBT(cellTag);
            vc.clearDirty();
        }

        root.put(TAG_VIRTUAL_CELLS, cellsList);
        stack.setTag(root);
    }

    private static UUID getOrCreateDataUuid(CompoundTag cellTag, boolean canCreate) {
        if (cellTag.hasUUID(TAG_DATA_UUID)) return cellTag.getUUID(TAG_DATA_UUID);
        if (!canCreate) return null;
        // 确定性 UUID 生成：基于 type + bytes + keys/items 内容
        UUID uuid = generateCellDeterministicUUID(cellTag);
        cellTag.putUUID(TAG_DATA_UUID, uuid);
        return uuid;
    }

    /**
     * 基于虚拟磁盘内容生成确定性 UUID，确保相同内容的磁盘使用相同 UUID。
     */
    private static UUID generateCellDeterministicUUID(CompoundTag cellTag) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");

            // 包含 type（存储类型）
            if (cellTag.contains(TAG_TYPE, Tag.TAG_STRING)) {
                md.update(cellTag.getString(TAG_TYPE).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            // 包含 bytes（容量）
            if (cellTag.contains(TAG_BYTES, Tag.TAG_LONG)) {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(8);
                bb.putLong(cellTag.getLong(TAG_BYTES));
                md.update(bb.array());
            }

            // 包含 keys（内容列表）
            if (cellTag.contains(TAG_KEYS, Tag.TAG_LIST)) {
                ListTag keys = cellTag.getList(TAG_KEYS, Tag.TAG_COMPOUND);
                for (int i = 0; i < keys.size(); i++) {
                    CompoundTag keyTag = keys.getCompound(i);
                    md.update(keyTag.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }

            // 包含 items（旧格式内容）
            if (cellTag.contains(TAG_ITEMS, Tag.TAG_COMPOUND)) {
                CompoundTag items = cellTag.getCompound(TAG_ITEMS);
                md.update(items.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            byte[] hash = md.digest();
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(hash);
            long mostSigBits = bb.getLong();
            long leastSigBits = bb.getLong();
            return new UUID(mostSigBits, leastSigBits);
        } catch (Exception e) {
            return UUID.randomUUID();
        }
    }

    private static void rootPutVirtualCells(ItemStack stack, CompoundTag tag, ListTag cellsList) {
        tag.put(TAG_VIRTUAL_CELLS, cellsList);
        stack.setTag(tag);
    }
}
