package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;

import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.SimpleTextButton;
import dev.ftb.mods.ftblibrary.ui.Theme;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * FTBQ 任务详情页里的「前往商店兑换」按钮（山海署名，仅客户端）。
 *
 * <p>是商店详情页「→ 前置任务」跳转（见 {@link ShopScreen} 的 prereqLink）的反方向：商品把某个任务
 * 配成前置，任务这边就自动多出一条去买它的入口，不需要额外配置。命中多个商品时点开列表让玩家选。</p>
 *
 * <p>样式对齐 FTBQ 自己的 {@code ViewQuestPanel.OpenInGuideButton}：无背景、居中、13 高，
 * 由所在 panelText 的垂直布局排位，不占用顶栏那排图标按钮的任何坐标（见
 * {@code FtbViewQuestPanelShopButtonMixin} 的布局说明）。</p>
 */
public class ShopQuestJumpButton extends SimpleTextButton {

    /** 以该任务为前置的商品 entryKey（至少 1 个，调用方保证非空）。 */
    private final List<Long> entryKeys;

    public ShopQuestJumpButton(Panel panel, List<Long> entryKeys) {
        super(panel, title(entryKeys), ItemIcon.getItemIcon(new ItemStack(GTDishanhaiMod.WALLET.get())));
        this.entryKeys = List.copyOf(entryKeys);
        setHeight(13);
        fitInto(panel.width);
        // 钳住下限：即便裁过，极窄面板下仍可能算出负 X 把按钮推到面板外
        setX(Math.max(0, (panel.width - this.width) / 2));
    }

    private static Component title(List<Long> entryKeys) {
        if (entryKeys.size() == 1) {
            String name = displayName(entryKeys.get(0));
            return Component.literal("→ 前往商店兑换：" + name).withStyle(ChatFormatting.GOLD);
        }
        return Component.literal("→ 前往商店兑换（" + entryKeys.size() + " 件商品）").withStyle(ChatFormatting.GOLD);
    }

    /**
     * 按钮宽度由标题文字撑开，而任务面板的宽度早在本按钮加进来之前就定死了（{@code addWidgets} 里按
     * 标题和任务/奖励格子算的）——商品名一长就撑出面板，居中还会算出负 X 往左溢出，见反馈截图。
     *
     * <p>这里用「实测 chrome 宽度」反推可用文字宽度：{@code width - 文字宽} 就是图标加左右内边距，
     * 不依赖 ftblibrary 内部的 padding 常量，它以后调了这里也跟着对。裁完把宽度收回来，
     * 完整名称仍在 tooltip 里给出（见 {@link #addMouseOverText}）。</p>
     */
    private void fitInto(int maxWidth) {
        if (maxWidth <= 0 || this.width <= maxWidth) return;
        Font font = Minecraft.getInstance().font;
        String full = getTitle().getString();
        int chrome = Math.max(0, this.width - font.width(full));
        String trimmed = GuiRenderUtil.trimText(font, full, Math.max(16, maxWidth - chrome));
        setTitle(Component.literal(trimmed).withStyle(ChatFormatting.GOLD));
        setWidth(font.width(trimmed) + chrome);
    }

    private static String displayName(long entryKey) {
        ShopCatalogManifest.Stub stub = ClientShopCatalog.stub(entryKey);
        if (stub == null || stub.displayName().isEmpty()) return "商店商品";
        return stub.displayName();
    }

    @Override
    public void onClicked(MouseButton button) {
        playClickSound();
        if (entryKeys.size() == 1) {
            openShopAt(entryKeys.get(0));
            return;
        }
        List<ContextMenuItem> items = new ArrayList<>(entryKeys.size());
        for (Long entryKey : entryKeys) {
            long key = entryKey;
            items.add(new ContextMenuItem(Component.literal(displayName(key)), Color4I.empty(),
                    ignored -> openShopAt(key)));
        }
        getGui().openContextMenu(items);
    }

    /**
     * 记下待定位商品后走跟钱包快捷键完全一致的开店链路（C→S 请求 → 服务端校验持有钱包 + 下发目录清单
     * → S→C 开界面）。不在客户端直接 new ShopScreen：那样会绕过持有钱包校验，也拿不到编辑权/最新目录。
     */
    private static void openShopAt(long entryKey) {
        ShopScreenOpener.requestOpenAt(entryKey);
    }

    /** 名称在按钮上可能被裁短（见 {@link #fitInto}），所以这里始终把完整名称列全。 */
    @Override
    public void addMouseOverText(TooltipList list) {
        for (Long entryKey : entryKeys) {
            list.add(Component.literal("· " + displayName(entryKey)).withStyle(ChatFormatting.GRAY));
        }
        list.add(Component.literal("需要携带山海钱包").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 同 OpenInGuideButton：只画图标和文字，不画按钮底板，融进描述文本流。 */
    @Override
    public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
    }
}
