package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;

import net.minecraft.client.Minecraft;

/**
 * 兑换条目编辑器入口（山海署名，客户端）。
 *
 * <p>历史上用 FTBLib {@code ConfigGroup} 长列表编辑，现改为 FTBQ 式可视化槽位屏
 * {@link ExchangeEditScreen}。本类仅保留 openNew/openEdit 入口，供 {@link ExchangeScreen} 调用。</p>
 */
public final class ExchangeEditor {

    private ExchangeEditor() {}

    /** 打开「新增兑换条目」编辑屏。 */
    public static void openNew(ExchangeScreen parent) {
        Minecraft.getInstance().setScreen(new ExchangeEditScreen(parent, null, true));
    }

    /** 打开「编辑兑换条目」编辑屏（预填现有条目）。 */
    public static void openEdit(ExchangeScreen parent, ExchangeEntry entry) {
        Minecraft.getInstance().setScreen(new ExchangeEditScreen(parent, entry, false));
    }
}
