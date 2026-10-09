package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.loot.RewardTable;
import dev.ftb.mods.ftbquests.quest.loot.WeightedReward;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Client-side JEI products collected from visible shop goods, never from their purchase costs. */
public final class ShopJeiProductCatalog {

    record StackIdentity(ResourceLocation itemId, CompoundTag tag) {
        StackIdentity {
            tag = copyTag(tag);
        }

        private static CompoundTag copyTag(CompoundTag tag) {
            return tag == null ? null : tag.copy();
        }
    }

    private static final Map<StackIdentity, ItemStack> PRODUCTS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, FluidStack> FLUID_PRODUCTS = new LinkedHashMap<>();
    private static final Set<String> PENDING_FTBQ_TABLES = new LinkedHashSet<>();
    private static long revision;

    private ShopJeiProductCatalog() {}

    public static StackIdentity identity(ItemStack stack) {
        return new StackIdentity(ForgeRegistries.ITEMS.getKey(stack.getItem()), stack.getTag());
    }

    public static boolean addEntry(ShopEntry entry, boolean hiddenByCatalog) {
        if (entry == null || hiddenByCatalog || entry.isHidden()) return false;
        boolean changed = false;
        if (entry.getRewardMode() == ShopEntry.RewardMode.FTBQ) {
            List<ItemStack> rewards = resolveFtbqItems(entry.getFtbqTableId());
            if (rewards == null) {
                if (!entry.getFtbqTableId().isEmpty()) PENDING_FTBQ_TABLES.add(entry.getFtbqTableId());
            } else {
                PENDING_FTBQ_TABLES.remove(entry.getFtbqTableId());
                for (ItemStack reward : rewards) changed |= addStack(reward);
            }
        } else if (entry.getRewardMode() != ShopEntry.RewardMode.NONE) {
            for (ShopEntry.RewardOption option : entry.getRewardPool()) {
                if (option != null) changed |= addStack(option.item());
            }
        } else {
            for (ShopEntry.GoodsStack goods : entry.getGoodsList()) {
                if (goods == null) continue;
                if (goods.isFluid()) changed |= addFluid(goods.fluid(), goods.count());
                else changed |= addStack(goods.makeStack());
            }
        }
        if (changed) revision++;
        return changed;
    }

    public static boolean refreshPendingFtbqItems() {
        if (PENDING_FTBQ_TABLES.isEmpty()) return false;
        boolean changed = false;
        Iterator<String> iterator = PENDING_FTBQ_TABLES.iterator();
        while (iterator.hasNext()) {
            String tableId = iterator.next();
            List<ItemStack> rewards = resolveFtbqItems(tableId);
            if (rewards == null) continue;
            iterator.remove();
            for (ItemStack reward : rewards) changed |= addStack(reward);
        }
        if (changed) revision++;
        return changed;
    }

    private static boolean addStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || ForgeRegistries.ITEMS.getKey(stack.getItem()) == null) return false;
        StackIdentity identity = identity(stack);
        if (PRODUCTS.containsKey(identity)) return false;
        PRODUCTS.put(identity, stack.copy());
        return true;
    }

    private static boolean addFluid(Fluid fluid, int amount) {
        if (fluid == null || fluid == Fluids.EMPTY) return false;
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
        if (id == null || FLUID_PRODUCTS.containsKey(id)) return false;
        FLUID_PRODUCTS.put(id, new FluidStack(fluid, Math.max(1, amount)));
        return true;
    }

    private static List<ItemStack> resolveFtbqItems(String tableId) {
        if (tableId == null || tableId.isEmpty()) return List.of();
        ClientQuestFile file = ClientQuestFile.INSTANCE;
        if (file == null) return null;
        long id = QuestObjectBase.parseCodeString(tableId);
        if (id == 0L) return List.of();
        RewardTable table = file.getRewardTable(id);
        if (table == null) return null;
        List<ItemStack> rewards = new ArrayList<>();
        for (WeightedReward weighted : table.getWeightedRewards()) {
            if (weighted.getReward() instanceof ItemReward itemReward) {
                ItemStack stack = itemReward.getItem();
                if (stack != null && !stack.isEmpty()) rewards.add(stack.copy());
            }
        }
        return rewards;
    }

    public static List<ItemStack> stacks() {
        List<ItemStack> result = new ArrayList<>(PRODUCTS.size());
        for (ItemStack stack : PRODUCTS.values()) result.add(stack.copy());
        return List.copyOf(result);
    }

    public static List<FluidStack> fluids() {
        List<FluidStack> result = new ArrayList<>(FLUID_PRODUCTS.size());
        for (FluidStack stack : FLUID_PRODUCTS.values()) result.add(stack.copy());
        return List.copyOf(result);
    }

    public static long revision() {
        return revision;
    }

    public static void clear() {
        if (!PRODUCTS.isEmpty() || !FLUID_PRODUCTS.isEmpty()) {
            PRODUCTS.clear();
            FLUID_PRODUCTS.clear();
            revision++;
        }
        PENDING_FTBQ_TABLES.clear();
    }
}
