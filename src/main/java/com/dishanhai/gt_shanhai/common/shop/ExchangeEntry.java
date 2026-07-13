package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.resources.ResourceLocation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 兑换条目（山海署名）：开发者自定义的「成本 → 产出」，两侧都可混合<b>星火 + 多物品/流体</b>。
 *
 * <p>例：<code>32星火 + 1铜锭 + 1铁锭 = 1钻石</code> →
 * cost={spark:32, [铜锭×1, 铁锭×1]}, result={spark:0, [钻石×1]}。<br>
 * 例：<code>1原木 = 32星火</code> → cost={spark:0, [原木×1]}, result={spark:32, []}。</p>
 *
 * <p>双向：正向消耗 cost 给 result；反向消耗 result 给 cost（见 {@code network/ExchangePacket}）。
 * 星火 = 数字余额（账户，自动币值与货币互转）；物品从背包收发；流体经绑定 AE 网络收发。
 * 价格全由作者手填，不预设固定值，数量一律 BigInteger/long 计。</p>
 */
public class ExchangeEntry {

    /**
     * 单项物料：物品或流体 + 每次数量（物品=个数；流体=mB）+ 可选精确 NBT（仅物品；流体不支持）。
     * nbt 非空时按 {@link net.minecraft.nbt.CompoundTag#equals} 精确匹配（同「无限盘」这类靠 NBT
     * 区分实例的物品），nbt 为空则按物品注册表 ID 匹配、忽略 NBT（旧行为，兼容既有配方）。
     */
    public static final class Ingredient {
        public final ResourceLocation id;
        public final boolean isFluid;
        public final long count;
        private final net.minecraft.nbt.CompoundTag nbt;

        public Ingredient(ResourceLocation id, boolean isFluid, long count) {
            this(id, isFluid, count, null);
        }

        public Ingredient(ResourceLocation id, boolean isFluid, long count, net.minecraft.nbt.CompoundTag nbt) {
            this.id = id;
            this.isFluid = isFluid;
            this.count = Math.max(1L, count);
            this.nbt = (nbt == null || nbt.isEmpty()) ? null : nbt.copy();
        }

        /** 精确匹配用 NBT（可空）。返回副本。 */
        public net.minecraft.nbt.CompoundTag nbt() {
            return nbt == null ? null : nbt.copy();
        }

        public boolean hasNbt() {
            return nbt != null;
        }

        /** 本物料对应的单件 ItemStack（count=1，带上 NBT，若有）；物品不存在/isFluid=true 返回 EMPTY。 */
        public net.minecraft.world.item.ItemStack makeUnitStack() {
            if (isFluid || id == null) return net.minecraft.world.item.ItemStack.EMPTY;
            net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
            if (item == null) return net.minecraft.world.item.ItemStack.EMPTY;
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item, 1);
            if (nbt != null) stack.setTag(nbt.copy());
            return stack;
        }
    }

    /** 一侧：星火量 + 物品/流体清单（任一可空，但整侧不能全空）。 */
    public static final class Side {
        public final BigInteger spark;
        public final List<Ingredient> ingredients;

        public Side(BigInteger spark, List<Ingredient> ingredients) {
            this.spark = (spark == null || spark.signum() < 0) ? BigInteger.ZERO : spark;
            this.ingredients = ingredients == null ? new ArrayList<>() : new ArrayList<>(ingredients);
        }

        public boolean isEmpty() {
            return spark.signum() <= 0 && ingredients.isEmpty();
        }
    }

    private final String id;          // 条目唯一 ID（packet 定位用）
    private final String category;
    private final String name;        // 显示名（空则自动拼）
    private final Side cost;           // 付出
    private final Side result;         // 得到

    public ExchangeEntry(String id, String category, String name, Side cost, Side result) {
        this.id = id == null ? "" : id;
        this.category = category == null ? "" : category;
        this.name = name == null ? "" : name;
        this.cost = cost == null ? new Side(BigInteger.ZERO, null) : cost;
        this.result = result == null ? new Side(BigInteger.ZERO, null) : result;
    }

    public String getId() { return id; }
    public String getCategory() { return category; }
    public String getName() { return name; }
    public Side getCost() { return cost; }
    public Side getResult() { return result; }

    /** 有效性：有 ID、两侧都非空、物料 id 非空。 */
    public boolean isValid() {
        if (id.isEmpty() || cost.isEmpty() || result.isEmpty()) return false;
        for (Ingredient in : cost.ingredients) if (in.id == null) return false;
        for (Ingredient in : result.ingredients) if (in.id == null) return false;
        return true;
    }

    /** 展示名：优先作者填的 name，否则用产出侧自动拼。 */
    public String displayName() {
        if (!name.isEmpty()) return name;
        if (!result.ingredients.isEmpty()) {
            Ingredient first = result.ingredients.get(0);
            String head = first.count + "×" + (first.id == null ? "?" : first.id.getPath());
            return result.ingredients.size() > 1 ? head + " 等" : head;
        }
        if (result.spark.signum() > 0) return result.spark + " 星火";
        return id;
    }
}
