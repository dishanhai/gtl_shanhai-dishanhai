package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import net.minecraft.client.Minecraft;

/**
 * 商店条目编辑器入口（山海署名，客户端）。
 *
 * <p>历史上用 FTBLib {@code ConfigGroup} 列表编辑，现改为 FTBQ 式可视化槽位屏
 * {@link ShopEntryEditScreen}（商品槽 + 货币槽 + 星火结算开关 + 数量/单价/分类）。
 * 本类仅保留 openNew/openEdit 入口，供 {@link ShopScreen} 调用。</p>
 */
public final class ShopEntryEditor {

    private ShopEntryEditor() {}

    /** 打开「新增商品」编辑屏，分类默认继承调用方当前所在的商店子页（如「无限盘区/前期」）。 */
    public static void openNew(ShopScreen parent, String defaultCategory) {
        Minecraft.getInstance().setScreen(new ShopEntryEditScreen(parent, null, true, defaultCategory));
    }

    /** 打开「编辑条目」编辑屏（预填现有条目，保留商品 NBT）。 */
    public static void openEdit(ShopScreen parent, ShopEntry entry) {
        Minecraft.getInstance().setScreen(new ShopEntryEditScreen(parent, entry, false, null));
    }
}
