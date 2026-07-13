package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * 精妙背包（Sophisticated Backpacks）商店对接（山海署名）：把玩家 Curios 饰品栏里穿戴的精妙背包
 * 当成跟随身背包等价的第二存储通道——购买交付溢出能塞进去，成本结算（实体币/实物成分）也能从里面抽。
 *
 * <p>背包内容不存在 ItemStack 自身 NBT 里（穿戴物只挂一个 contentsUuid），真实数据按 UUID 存在 SOP
 * 自己的全局 SavedData 里（跟本模组 SDA「取出即分家」同一套思路），但不需要关心这层——SOP 的
 * {@link BackpackItem} 胶囊本身就代理到（不含升级槽的）主仓，直接走 Forge 标准
 * {@link ForgeCapabilities#ITEM_HANDLER} 拿到的就是主仓读写接口，跟拿一个箱子的 IItemHandler 完全一样。</p>
 */
public final class ShopBackpack {

    private ShopBackpack() {}

    /** 玩家 Curios 饰品栏里穿戴的精妙背包主仓（未装备 / 无 Curios 返回空）。只认主仓，不碰升级槽。 */
    public static Optional<IItemHandler> equipped(ServerPlayer player) {
        if (player == null) return Optional.empty();
        return CuriosApi.getCuriosInventory(player)
                .resolve()
                .flatMap(handler -> handler.findFirstCurio(stack -> stack.getItem() instanceof BackpackItem))
                .map(SlotResult::stack)
                .flatMap(stack -> stack.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve());
    }

    /** 统计背包内某物品总数；nbt 非空时精确匹配 NBT。未装备背包返回 0。 */
    public static long countItem(ServerPlayer player, Item item, CompoundTag nbt) {
        return equipped(player).map(inv -> {
            long total = 0L;
            for (int i = 0; i < inv.getSlots(); i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.isEmpty() || !s.is(item)) continue;
                if (nbt != null && !Objects.equals(s.getTag(), nbt)) continue;
                total += s.getCount();
            }
            return total;
        }).orElse(0L);
    }

    /** 从背包移除指定数量（假定已确认足够）；nbt 非空时只移除精确匹配的堆叠。未装备背包直接忽略。 */
    public static void removeItems(ServerPlayer player, Item item, long amount, CompoundTag nbt) {
        if (amount <= 0L) return;
        equipped(player).ifPresent(inv -> {
            long remaining = amount;
            for (int i = 0; i < inv.getSlots() && remaining > 0L; i++) {
                ItemStack s = inv.getStackInSlot(i);
                if (s.isEmpty() || !s.is(item)) continue;
                if (nbt != null && !Objects.equals(s.getTag(), nbt)) continue;
                int take = (int) Math.min(remaining, s.getCount());
                inv.extractItem(i, take, false);
                remaining -= take;
            }
        });
    }

    /**
     * 尽量把 unit×count 塞进背包，返回装不下的余量（语义对齐 {@link ShopPurchase} 里的
     * {@code deliverToInventory}，供交付溢出兜底用）。未装备背包直接把 count 原样退回（一点没塞进去）。
     */
    public static BigInteger insert(ServerPlayer player, ItemStack unit, BigInteger count) {
        if (count == null || count.signum() <= 0) return BigInteger.ZERO;
        Optional<IItemHandler> opt = equipped(player);
        if (opt.isEmpty()) return count;
        IItemHandler inv = opt.get();
        int max = Math.max(1, unit.getMaxStackSize());
        BigInteger remaining = count;
        BigInteger bigMax = BigInteger.valueOf(max);
        while (remaining.signum() > 0) {
            int give = remaining.compareTo(bigMax) >= 0 ? max : remaining.intValue();
            ItemStack stack = unit.copy();
            stack.setCount(give);
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(inv, stack, false);
            int actuallyAdded = give - leftover.getCount();
            remaining = remaining.subtract(BigInteger.valueOf(actuallyAdded));
            if (actuallyAdded <= 0) break; // 背包满
        }
        return remaining;
    }
}
