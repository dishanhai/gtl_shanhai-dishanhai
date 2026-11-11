package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.client.gui.shop.ShopScreen;
import net.minecraft.client.Minecraft;

import java.util.Set;

/** 山海：当前连接中自己的隐藏清单，服务端按玩家保存。 */
public final class ClientShopPersonalHidden {
    private static Set<String> stableIds = Set.of();

    private ClientShopPersonalHidden() {}

    public static Set<String> ids() { return stableIds; }

    public static void apply(Set<String> ids) {
        if (stableIds.equals(ids)) return;
        stableIds = Set.copyOf(ids);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen instanceof ShopScreen screen) screen.onPersonalHiddenChanged();
    }

    public static void clear() { stableIds = Set.of(); }
}
