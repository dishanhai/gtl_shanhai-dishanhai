package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 商店单个商品条目。
 * 商品本体(goods)与结算货币(currency)都用物品 ID 表示，
 * 货币默认复用 KubeJS 注册的 dishanhai:*_coin 体系（见 currency_index.md）。
 *
 * <p>category：分类页签名（如「矿物」「食物」），用于商店界面顶部分类过滤；
 * 缺省为「杂货」。</p>
 */
public class ShopEntry {

    /** 未指定分类时的默认页签名。 */
    public static final String DEFAULT_CATEGORY = "杂货";

    private final ResourceLocation goodsId;
    private final int goodsCount;
    private final ResourceLocation currencyId;
    private final int price;
    private final String category;
    /** 商品物品的完整 NBT 快照（可空）。带 NBT 的商品（无限元件包/超级磁盘阵列/附魔书等）
     *  买到手时会原样复现；null 表示纯物品无附加数据。 */
    private final net.minecraft.nbt.CompoundTag goodsNbt;

    public ShopEntry(ResourceLocation goodsId, int goodsCount, ResourceLocation currencyId, int price) {
        this(goodsId, goodsCount, currencyId, price, DEFAULT_CATEGORY, null);
    }

    public ShopEntry(ResourceLocation goodsId, int goodsCount, ResourceLocation currencyId, int price, String category) {
        this(goodsId, goodsCount, currencyId, price, category, null);
    }

    public ShopEntry(ResourceLocation goodsId, int goodsCount, ResourceLocation currencyId, int price,
                     String category, net.minecraft.nbt.CompoundTag goodsNbt) {
        this.goodsId = goodsId;
        this.goodsCount = Math.max(1, goodsCount);
        this.currencyId = currencyId;
        this.price = Math.max(0, price);
        this.category = (category == null || category.isBlank()) ? DEFAULT_CATEGORY : category;
        this.goodsNbt = (goodsNbt == null || goodsNbt.isEmpty()) ? null : goodsNbt.copy();
    }

    /** 商品 NBT 快照（可空）。返回副本，防止外部改动污染条目。 */
    public net.minecraft.nbt.CompoundTag getGoodsNbt() {
        return goodsNbt == null ? null : goodsNbt.copy();
    }

    public ResourceLocation getGoodsId() {
        return goodsId;
    }

    public int getGoodsCount() {
        return goodsCount;
    }

    public ResourceLocation getCurrencyId() {
        return currencyId;
    }

    public int getPrice() {
        return price;
    }

    public String getCategory() {
        return category;
    }

    /** 商品物品，缺失时返回 AIR 对应的 Item。 */
    public Item getGoodsItem() {
        Item item = ForgeRegistries.ITEMS.getValue(goodsId);
        return item != null ? item : net.minecraft.world.item.Items.AIR;
    }

    /** 货币物品，缺失时返回 AIR 对应的 Item。 */
    public Item getCurrencyItem() {
        Item item = ForgeRegistries.ITEMS.getValue(currencyId);
        return item != null ? item : net.minecraft.world.item.Items.AIR;
    }

    /** 商品是否已在当前注册表中存在（用于跳过缺失物品）。 */
    public boolean isValid() {
        return ForgeRegistries.ITEMS.containsKey(goodsId)
                && ForgeRegistries.ITEMS.containsKey(currencyId);
    }

    /** 一份商品的展示用 ItemStack（数量 = goodsCount），带上商品 NBT 快照（若有）。
     *  SDA 等含 UUID 的存储物买到手后由其 inventoryTick「取出即分家」自动换独立 UUID，
     *  商店侧只需原样带上 NBT，无需特殊处理。 */
    public ItemStack makeGoodsStack() {
        ItemStack stack = new ItemStack(getGoodsItem(), goodsCount);
        if (goodsNbt != null) {
            stack.setTag(goodsNbt.copy());
        }
        return stack;
    }

    /** 一份价格的展示用 ItemStack（数量 = price）。 */
    public ItemStack makePriceStack() {
        return new ItemStack(getCurrencyItem(), Math.max(1, price));
    }

    /** 商品显示名（本地化后的物品名）。 */
    public String goodsDisplayName() {
        return makeGoodsStack().getHoverName().getString();
    }
}
