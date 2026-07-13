package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopActionPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * FTBQ 表自选奖励界面（山海署名，客户端）：{@link ShopEntry.RewardMode#FTBQ} 且
 * {@link ShopEntry#getFtbqSubMode()} == CHOICE 的商品购买前弹出，从所选 FTBQ 表内「仅物品类奖励」
 * 子序列单选一项，确认后连同购买动作一起发给服务端结算（服务端按同样规则重新遍历表解出实际交付内容，
 * 见 {@code ShopPurchase#resolveFtbqItemRewardByIndex}，不信任客户端物品数据）。与 {@link RewardChoiceScreen}
 * 是同一套交互，只是奖励来源从本地奖励池换成 FTBQ 表内容。
 */
public class FtbqRewardChoiceScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;
    private static final int SEL_BG = -12285748;

    private static final int TARGET_W = 280;
    private static final int TARGET_H = 340;
    private static final int ROW_H = 20;

    private final ShopScreen parent;
    private final ShopEntry entry;
    private final long times;
    private final boolean aeMode;
    private final boolean backpackMode;
    private int selected = -1;
    private int scroll = 0;

    private int left, top, panelWidth, panelHeight;
    private int listX, listY, listW, listH;

    public FtbqRewardChoiceScreen(ShopScreen parent, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        super(Component.literal("选择奖励"));
        this.parent = parent;
        this.entry = entry;
        this.times = times;
        this.aeMode = aeMode;
        this.backpackMode = backpackMode;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
    }

    @Override
    protected void initScaled() {
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);
        listX = left + 8;
        listY = top + 26;
        listW = panelWidth - 16;
        listH = panelHeight - 26 - 30;
    }

    /** 表内「仅物品类奖励」子序列（与服务端 {@code resolveFtbqItemRewardByIndex} 同规则遍历，下标必须对得上）。 */
    private List<dev.ftb.mods.ftbquests.quest.reward.ItemReward> pool() {
        List<dev.ftb.mods.ftbquests.quest.reward.ItemReward> list = new ArrayList<>();
        dev.ftb.mods.ftbquests.quest.loot.RewardTable table = resolveClientTable();
        if (table == null) return list;
        for (dev.ftb.mods.ftbquests.quest.loot.WeightedReward wr : table.getWeightedRewards()) {
            if (wr.getReward() instanceof dev.ftb.mods.ftbquests.quest.reward.ItemReward ir) list.add(ir);
        }
        return list;
    }

    private dev.ftb.mods.ftbquests.quest.loot.RewardTable resolveClientTable() {
        String id = entry.getFtbqTableId();
        if (id == null || id.isEmpty()) return null;
        dev.ftb.mods.ftbquests.client.ClientQuestFile file = dev.ftb.mods.ftbquests.client.ClientQuestFile.INSTANCE;
        if (file == null) return null;
        long code = dev.ftb.mods.ftbquests.quest.QuestObjectBase.parseCodeString(id);
        if (code == 0L) return null;
        return file.getRewardTable(code);
    }

    private int visibleRows() {
        return Math.max(1, listH / ROW_H);
    }

    private int maxScroll(int poolSize) {
        return Math.max(0, poolSize - visibleRows());
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 5, top + 18, left + panelWidth - 5, top + panelHeight - 5, PANEL_INNER);

        g.drawString(this.font, "§6选择奖励", left + 8, top + 5, GOLD, true);
        g.drawString(this.font, "§7" + GuiRenderUtil.trimText(this.font, entry.goodsDisplayName(), panelWidth - 16), left + 8, top + 14, GRAY, true);

        List<dev.ftb.mods.ftbquests.quest.reward.ItemReward> pool = pool();
        int visible = visibleRows();
        int maxScroll = maxScroll(pool.size());
        if (scroll > maxScroll) scroll = maxScroll;
        if (pool.isEmpty()) {
            g.drawString(this.font, "§c表未同步/不存在或无物品类奖励", listX, listY, GRAY, true);
        }
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= pool.size()) break;
            dev.ftb.mods.ftbquests.quest.reward.ItemReward ir = pool.get(idx);
            ItemStack st = ir.getItem();
            int ry = listY + i * ROW_H;
            boolean hv = GuiRenderUtil.isHovering(mx, my, listX, ry, listW, ROW_H);
            if (idx == selected) g.fill(listX, ry, listX + listW, ry + ROW_H, SEL_BG);
            else if (hv) g.fill(listX, ry, listX + listW, ry + ROW_H, 0x33FFFFFF);
            g.renderItem(st, listX + 1, ry + 1);
            g.renderItemDecorations(this.font, st, listX + 1, ry + 1);
            String nm = st.getHoverName().getString();
            String prefix = idx == selected ? "§a✔ §f" : "§f";
            g.drawString(this.font, prefix + GuiRenderUtil.trimText(this.font, nm, listW - 22),
                    listX + 22, ry + 6, WHITE, true);
        }
        if (pool.size() > visible) {
            g.drawString(this.font, "§8" + (scroll + 1) + "-" + Math.min(scroll + visible, pool.size()) + "/" + pool.size()
                    + " 滚轮翻页", listX, listY + listH + 2, GRAY, true);
        }

        int btnY = top + panelHeight - 22;
        boolean canConfirm = selected >= 0;
        int confirmW = panelWidth - 16 - 60;
        drawBtn(g, left + 8, btnY, confirmW, 18, canConfirm ? "§a确认购买" : "§8请先选择", mx, my, canConfirm);
        drawBtn(g, left + panelWidth - 8 - 52, btnY, 52, 18, "§c取消", mx, my, true);
    }

    private void drawBtn(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, boolean enabled) {
        boolean hv = enabled && GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hv ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        List<dev.ftb.mods.ftbquests.quest.reward.ItemReward> pool = pool();
        int visible = visibleRows();
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= pool.size()) break;
            int ry = listY + i * ROW_H;
            if (GuiRenderUtil.isHovering(mx, my, listX, ry, listW, ROW_H)) {
                selected = idx;
                return true;
            }
        }
        int btnY = top + panelHeight - 22;
        int confirmW = panelWidth - 16 - 60;
        if (selected >= 0 && GuiRenderUtil.isHovering(mx, my, left + 8, btnY, confirmW, 18)) {
            confirm();
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, left + panelWidth - 8 - 52, btnY, 52, 18)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        if (GuiRenderUtil.isHovering(mx, my, listX, listY, listW, listH)) {
            scroll = Math.max(0, Math.min(maxScroll(pool().size()), scroll - (int) Math.signum(d)));
            return true;
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    private void confirm() {
        if (selected < 0) return;
        int entryIndex = ShopConfig.getEntries().indexOf(entry);
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopActionPacket(
                ShopActionPacket.Action.BUY, entry.getGoodsId(), entry.getCategory(), times, aeMode, backpackMode, entryIndex, selected));
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
