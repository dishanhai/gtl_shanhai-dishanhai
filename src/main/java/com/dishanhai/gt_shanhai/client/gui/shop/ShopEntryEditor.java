package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopEditPacket;

import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.config.ui.EditConfigScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 商店条目编辑器（山海署名，客户端）。
 *
 * <p>复用 FTBLib 的 {@link ConfigGroup} + {@link EditConfigScreen} 搭编辑界面：
 * {@code addItemStack} 直接调出 FTB 物品选择器（可搜索翻页），{@code addInt}/{@code addString}
 * 采集数量/价格/分类。确认后把草稿打成 {@link ShopEditPacket} 发服务端持久化，
 * 服务端 {@code canEdit} 强校验。取消或确认后都返回 {@link ShopScreen}。</p>
 *
 * <p>采集字段对应 {@link ShopEntry} 的 5 项：商品物品、每份数量、货币物品、单价、分类。</p>
 */
public final class ShopEntryEditor {

    private ShopEntryEditor() {}

    /** 可变草稿，供 FTBLib 各配置项的 setter 回填。 */
    private static final class Draft {
        ItemStack goods;
        int count;
        ItemStack currency;
        int price;
        String category;
    }

    /** 打开「新增商品」编辑器。 */
    public static void openNew(ShopScreen parent) {
        Draft d = new Draft();
        d.goods = new ItemStack(net.minecraft.world.item.Items.DIAMOND);
        d.count = 1;
        d.currency = defaultCurrencyStack();
        d.price = 1;
        d.category = ShopEntry.DEFAULT_CATEGORY;
        open(parent, d, null, null, true);
    }

    /** 打开「编辑条目」编辑器（预填现有条目，保留商品 NBT）。 */
    public static void openEdit(ShopScreen parent, ShopEntry entry) {
        Draft d = new Draft();
        d.goods = entry.makeGoodsStack(); // 带 NBT 的商品 stack
        d.goods.setCount(1);              // 选择器只关心物品类型/NBT，数量单独由 count 管
        d.count = entry.getGoodsCount();
        d.currency = new ItemStack(entry.getCurrencyItem());
        d.price = entry.getPrice();
        d.category = entry.getCategory();
        open(parent, d, entry.getGoodsId(), entry.getCategory(), false);
    }

    private static void open(ShopScreen parent, Draft d,
                             ResourceLocation oldGoods, String oldCategory, boolean isNew) {
        ConfigGroup group = new ConfigGroup("gt_shanhai.shop_editor", accepted -> {
            if (accepted) {
                submit(d, oldGoods, oldCategory, isNew);
            }
            // 无论确认/取消都回到商店界面
            Minecraft.getInstance().setScreen(parent);
        });

        // 商品物品（FTB 物品选择器：singleItem=true 只取单个物品类型，allowEmpty=false 必填）
        group.addItemStack("goods", d.goods, v -> d.goods = v, ItemStack.EMPTY, true, false)
                .setNameKey("gt_shanhai.shop.editor.goods");
        // 每份数量
        group.addInt("count", d.count, v -> d.count = v, 1, 1, 8192)
                .setNameKey("gt_shanhai.shop.editor.count");
        // 货币物品（同样用物品选择器）
        group.addItemStack("currency", d.currency, v -> d.currency = v, ItemStack.EMPTY, true, false)
                .setNameKey("gt_shanhai.shop.editor.currency");
        // 单价
        group.addInt("price", d.price, v -> d.price = v, 1, 0, 1_000_000)
                .setNameKey("gt_shanhai.shop.editor.price");
        // 分类
        group.addString("category", d.category, v -> d.category = v, ShopEntry.DEFAULT_CATEGORY)
                .setNameKey("gt_shanhai.shop.editor.category");

        new EditConfigScreen(group).openGui();
    }

    private static void submit(Draft d, ResourceLocation oldGoods, String oldCategory, boolean isNew) {
        ResourceLocation goodsId = idOf(d.goods);
        ResourceLocation currencyId = idOf(d.currency);
        if (goodsId == null || currencyId == null) {
            return; // 物品为空则忽略（不该发生，allowEmpty=false）
        }
        String category = (d.category == null || d.category.isBlank())
                ? ShopEntry.DEFAULT_CATEGORY : d.category;
        // 商品 NBT 快照（无限元件包/SDA/附魔书等）；货币只按 ID 结算不带 NBT
        net.minecraft.nbt.CompoundTag goodsNbt = d.goods.getTag();
        ShopEditPacket pkt = new ShopEditPacket(
                isNew ? ShopEditPacket.Action.ADD : ShopEditPacket.Action.EDIT,
                goodsId, Math.max(1, d.count), currencyId, Math.max(0, d.price), category,
                goodsNbt, oldGoods, oldCategory == null ? "" : oldCategory);
        ShanhaiNetwork.CHANNEL.sendToServer(pkt);
    }

    private static ResourceLocation idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return ForgeRegistries.ITEMS.getKey(stack.getItem());
    }

    /** 默认货币：取商店已用货币的第一种，无则回退钻石（避免空手起步）。 */
    private static ItemStack defaultCurrencyStack() {
        for (ShopEntry e : ShopConfig.getEntries()) {
            var item = e.getCurrencyItem();
            if (item != net.minecraft.world.item.Items.AIR) {
                return new ItemStack(item);
            }
        }
        return new ItemStack(net.minecraft.world.item.Items.DIAMOND);
    }
}
