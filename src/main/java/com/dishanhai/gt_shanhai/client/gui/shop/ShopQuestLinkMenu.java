package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.client.shop.ClientShopQuestLinks;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopQuestLinkEditPacket;

import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务详情页「编辑 ▼」菜单里的商店绑定项（山海署名，仅客户端）。
 *
 * <p>手动指定「这个任务去商店买什么」，跟商品自己配的前置任务（被动反查）是两条独立来源，
 * 最终一起显示在跳转按钮上（见 {@code FtbViewQuestPanelShopButtonMixin}）。绑定关系存服务端
 * {@code config/gt_shanhai/shop_quest_links.json}，不写进 FTBQ 的任务数据，任务书导入导出不受影响。</p>
 */
public final class ShopQuestLinkMenu {

    private ShopQuestLinkMenu() {}

    /** 菜单项图标统一用钱包，跟任务页上那条跳转入口对齐。 */
    private static Icon walletIcon() {
        return ItemIcon.getItemIcon(new ItemStack(GTDishanhaiMod.WALLET.get()));
    }

    /** 往「编辑 ▼」菜单尾部追加绑定/解绑项。{@code items} 由 FTBQ 现构现用，可直接改。 */
    public static void appendTo(List<ContextMenuItem> items, BaseScreen gui, String questHexId) {
        if (items == null || questHexId == null || questHexId.isEmpty()) return;
        items.add(ContextMenuItem.SEPARATOR);
        items.add(new ContextMenuItem(
                Component.literal("绑定商店商品").withStyle(ChatFormatting.GOLD), walletIcon(),
                button -> openPicker(questHexId)));
        List<String> bound = ClientShopQuestLinks.stableIdsOf(questHexId);
        if (!bound.isEmpty()) {
            items.add(new ContextMenuItem(
                    Component.literal("解除商店绑定 (" + bound.size() + ")").withStyle(ChatFormatting.RED),
                    walletIcon(), button -> openUnbindMenu(gui, questHexId, bound)));
        }
    }

    /** 打开商品选择器；选中即发包绑定，服务端落盘后广播新表，任务页按钮下次刷新就带上它。 */
    private static void openPicker(String questHexId) {
        Minecraft minecraft = Minecraft.getInstance();
        var parent = minecraft.screen; // 任务书界面，选完/取消都回到它
        minecraft.setScreen(new ShopEntrySelectScreen(parent, stableId ->
                ShanhaiNetwork.CHANNEL.sendToServer(new ShopQuestLinkEditPacket(questHexId, stableId, true))));
    }

    private static void openUnbindMenu(BaseScreen gui, String questHexId, List<String> bound) {
        if (gui == null) return;
        List<ContextMenuItem> items = new ArrayList<>(bound.size());
        for (String stableId : bound) {
            items.add(new ContextMenuItem(Component.literal(displayName(stableId)), Color4I.empty(),
                    button -> ShanhaiNetwork.CHANNEL.sendToServer(
                            new ShopQuestLinkEditPacket(questHexId, stableId, false))));
        }
        gui.openContextMenu(items);
    }

    /** 按 stableId 取商品显示名；商品已从目录里删掉时退回显示 ID，至少还能解绑掉这条死链接。 */
    private static String displayName(String stableId) {
        long entryKey = ClientShopCatalog.keyOfStableId(stableId);
        ShopCatalogManifest.Stub stub = entryKey < 0L ? null : ClientShopCatalog.stub(entryKey);
        if (stub == null) return "§8(已失效) " + stableId;
        return stub.displayName().isEmpty() ? "(未命名商品)" : stub.displayName();
    }
}
