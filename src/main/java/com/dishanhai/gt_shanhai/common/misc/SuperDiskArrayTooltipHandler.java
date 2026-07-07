package com.dishanhai.gt_shanhai.common.misc;

import com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayItem;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;
import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 实时读取 SDA NBT，显示内联无限元件的真实目标名称。 */
public class SuperDiskArrayTooltipHandler {
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(new SuperDiskArrayTooltipHandler());
    }

    @SubscribeEvent
    public void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !(stack.getItem() instanceof SuperDiskArrayItem)) return;
        boolean shift = Screen.hasShiftDown();
        List<Entry> entries = readCellEntries(stack, shift ? Integer.MAX_VALUE : 5);
        if (entries.isEmpty()) return;

        int max = shift ? entries.size() : Math.min(5, entries.size());
        List<Component> tooltip = event.getToolTip();
        addDynamicLore(stack, tooltip);
        tooltip.add(Component.literal(""));
        tooltip.add(Component.literal(shift ? "§6=== 超级磁盘阵列内容 ===" : "§d超级磁盘阵列内容预览"));
        tooltip.add(Component.literal("§7包含物品 (" + (shift ? "完整" : "前" + max + "项") + "):"));
        for (int i = 0; i < max; i++) {
            Entry entry = entries.get(i);
            tooltip.add(Component.literal(" §8• §7" + formatAmount(entry.amount) + "x " + entry.name + (entry.infinity ? " §d[无限元件]" : "")));
        }
        if (!shift && entries.size() > max) {
            tooltip.add(Component.literal(" §7... 还有 §e" + (entries.size() - max) + "§7 项"));
        }
        tooltip.add(ShanhaiTextAPI.inline("{body_silver}总计: {/}{golden}" + formatAmount(sumAmounts(entries)) + "{/}{body_silver} 个内容，{/}{golden}" + entries.size() + "{/}{body_silver} 种类型{/}"));
        tooltip.add(ShanhaiTextAPI.inline(shift ? "{body_silver}松开{/}{golden}Shift{/}{body_silver}显示简洁视图{/}" : "{body_silver}按住{/}{golden} Shift {/}{body_silver}查看完整列表{/}"));
        tooltip.add(ShanhaiTextAPI.inline("{body_slate}由{/}{sunset}SDA实时解析器{/}{body_slate}生成{/}"));
    }

    private static List<Entry> readCellEntries(ItemStack stack, int resolveNameLimit) {
        List<Entry> result = new ArrayList<>();
        CompoundTag root = stack.getTag();
        if (root == null) return result;
        readInlineEntries(root, resolveNameLimit, result);
        if (!result.isEmpty()) return result;

        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return result;
        DShanhaiVirtualCellSavedData data = DShanhaiVirtualCellSavedData.get(server);
        if (root.hasUUID(SuperDiskArrayInventory.TAG_UUID)) {
            UUID uuid = root.getUUID(SuperDiskArrayInventory.TAG_UUID);
            readExternalBigEntries(data.readCellBigAmounts(uuid), resolveNameLimit, result);
        }
        readExternalVirtualCellEntries(root, data, resolveNameLimit, result);
        return result;
    }

    private static void readInlineEntries(CompoundTag root, int resolveNameLimit, List<Entry> result) {
        if (!root.contains("keys", Tag.TAG_LIST)) return;
        ListTag keys = root.getList("keys", Tag.TAG_COMPOUND);
        long[] amounts = root.contains("amts", Tag.TAG_LONG_ARRAY) ? root.getLongArray("amts") : new long[0];
        for (int i = 0; i < keys.size(); i++) {
            CompoundTag key = keys.getCompound(i);
            long amount = i < amounts.length ? amounts[i] : 1L;
            Entry entry = i < resolveNameLimit
                    ? resolveEntry(key, BigInteger.valueOf(amount))
                    : new Entry(BigInteger.valueOf(amount), "", false);
            if (entry != null) result.add(entry);
        }
    }

    private static void readExternalBigEntries(Map<AEKey, BigInteger> entries, int resolveNameLimit, List<Entry> result) {
        int index = 0;
        for (Map.Entry<AEKey, BigInteger> entry : entries.entrySet()) {
            BigInteger amount = entry.getValue();
            if (amount == null || amount.signum() <= 0) continue;
            result.add(index < resolveNameLimit
                    ? resolveEntry(entry.getKey(), amount)
                    : new Entry(amount, "", false));
            index++;
        }
    }

    private static void readExternalVirtualCellEntries(CompoundTag root, DShanhaiVirtualCellSavedData data,
                                                       int resolveNameLimit, List<Entry> result) {
        if (!root.contains(SuperDiskArrayItem.TAG_VIRTUAL_CELLS, Tag.TAG_LIST)) return;
        ListTag cells = root.getList(SuperDiskArrayItem.TAG_VIRTUAL_CELLS, Tag.TAG_COMPOUND);
        int index = result.size();
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag cell = cells.getCompound(i);
            if (!cell.hasUUID(SuperDiskArrayItem.TAG_DATA_UUID)) continue;
            UUID uuid = cell.getUUID(SuperDiskArrayItem.TAG_DATA_UUID);
            Map<AEKey, Long> amounts = data.readCellAmounts(uuid);
            for (Map.Entry<AEKey, Long> entry : amounts.entrySet()) {
                long amount = entry.getValue() != null ? entry.getValue() : 0L;
                if (amount <= 0) continue;
                BigInteger bigAmount = BigInteger.valueOf(amount);
                result.add(index < resolveNameLimit
                        ? resolveEntry(entry.getKey(), bigAmount)
                        : new Entry(bigAmount, "", false));
                index++;
            }
        }
    }

    private static void addDynamicLore(ItemStack stack, List<Component> tooltip) {
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains("shanhai_fcs_lore", Tag.TAG_LIST)) return;
        ListTag lore = root.getList("shanhai_fcs_lore", Tag.TAG_STRING);
        if (lore.isEmpty()) return;
        tooltip.add(Component.literal(""));
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.getString(i);
            if (line == null || line.isEmpty()) continue;
            try {
                tooltip.add(line.contains("{/}") ? ShanhaiTextAPI.inline(line) : Component.literal(line));
            } catch (Exception ignored) {
                tooltip.add(Component.literal(line));
            }
        }
    }

    private static Entry resolveEntry(CompoundTag key, BigInteger amount) {
        String id = key.getString("id");
        if ("expatternprovider:infinity_cell".equals(id) && key.contains("tag", Tag.TAG_COMPOUND)) {
            CompoundTag tag = key.getCompound("tag");
            if (tag.contains("record", Tag.TAG_COMPOUND)) {
                CompoundTag record = tag.getCompound("record");
                String targetType = record.getString("#c");
                String targetId = record.getString("id");
                String name = "ae2:f".equals(targetType) ? resolveFluidName(targetId) : resolveItemName(targetId);
                return new Entry(amount, name, true);
            }
        }
        return new Entry(amount, resolveItemName(id), false);
    }

    private static Entry resolveEntry(AEKey key, BigInteger amount) {
        if (key instanceof AEItemKey itemKey) {
            ItemStack stack = itemKey.toStack();
            CompoundTag tag = stack.getTag();
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null && "expatternprovider:infinity_cell".equals(id.toString()) && tag != null && tag.contains("record", Tag.TAG_COMPOUND)) {
                CompoundTag record = tag.getCompound("record");
                String targetType = record.getString("#c");
                String targetId = record.getString("id");
                String name = "ae2:f".equals(targetType) ? resolveFluidName(targetId) : resolveItemName(targetId);
                return new Entry(amount, name, true);
            }
            return new Entry(amount, stack.getHoverName().getString(), false);
        }
        if (key instanceof AEFluidKey fluidKey) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluidKey.getFluid());
            return new Entry(amount, id != null ? resolveFluidName(id.toString()) : key.getDisplayName().getString(), false);
        }
        return new Entry(amount, key.getDisplayName().getString(), false);
    }

    private static String resolveItemName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return id;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null) return id;
        return new ItemStack(item).getHoverName().getString();
    }

    private static String resolveFluidName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return id;
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(rl);
        if (fluid == null) return id;
        return fluid.getFluidType().getDescription().getString();
    }

    private static BigInteger sumAmounts(List<Entry> entries) {
        BigInteger sum = BigInteger.ZERO;
        for (Entry entry : entries) sum = sum.add(entry.amount);
        return sum;
    }

    private static String formatAmount(BigInteger amount) {
        if (amount == null) return "0";
        String text = amount.toString();
        if (text.length() <= 18) return text;
        return text.substring(0, 6) + "..." + text.substring(text.length() - 6);
    }

    private record Entry(BigInteger amount, String name, boolean infinity) {}
}
