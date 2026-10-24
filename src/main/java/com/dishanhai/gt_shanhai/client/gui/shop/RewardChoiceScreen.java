package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 自选奖励选择界面（山海署名，客户端）：{@link ShopEntry.RewardMode#CHOICE} 类型商品购买前弹出，
 * 从 {@link ShopEntry#getRewardPool()} 单选一项，确认后连同购买动作一起发给服务端结算
 * （服务端按下标从奖励池解出实际交付内容，不信任客户端物品数据）。仿 FTBQuests 自选奖励表交互。
 *
 * <p>名字换行不截断（行随之长高）；↑↓ 选、Enter 确认、Esc 回商店；确认经 {@link ShopScreen#submitRewardChoice}
 * 带请求号发包，回到商店后确认按钮接着显示等待与成交结果。按钮与滚动条走 {@link ShopDialogUi}。</p>
 */
public class RewardChoiceScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;
    private static final int SEL_BG = -12285748;

    private static final int TARGET_W = 280;
    private static final int TARGET_H = 340;
    private static final int ROW_H = 20;
    private static final int LINE_H = 9;
    private static final int TEXT_X = 22;
    /** 列表下面：页码行（10）+ 间隔 + 按钮（18）+ 底边，页码不再压到按钮上。 */
    private static final int FOOTER_H = 36;

    /** 一行奖励：名字已按可用宽度换好行，行高随行数长。 */
    private record Row(ItemStack stack, List<FormattedCharSequence> name, String right, int height) {}

    private final ShopScreen parent;
    private final ShopEntry entry;
    private final long catalogRevision;
    private final long entryKey;
    private final long times;
    private final boolean aeMode;
    private final boolean backpackMode;
    private final ShopDialogUi ui = new ShopDialogUi(GOLD_DARK, GOLD, BTN_BG, BTN_HOVER);
    private int selected = -1;
    private int scroll = 0;

    private int left, top, panelWidth, panelHeight;
    private int listX, listY, listW, listH;
    private List<FormattedCharSequence> subtitle = List.of();

    public RewardChoiceScreen(ShopScreen parent, ShopEntry entry, long times, boolean aeMode, boolean backpackMode) {
        super(Component.literal("选择奖励"));
        this.parent = parent;
        this.entry = entry;
        this.catalogRevision = ClientShopCatalog.revision();
        this.entryKey = ClientShopCatalog.keyOf(entry);
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
        ui.cancel();
        ui.syncEnterHeld();
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);
        listX = left + 8;
        listW = panelWidth - 16;
        // 商品名太长时副标题换行，列表跟着往下让
        subtitle = this.font.split(DShanhaiTextUtil.fromLegacy(
                "§7" + entry.goodsDisplayName() + "  §8百分比为表内权重"), panelWidth - 16);
        listY = top + 26 + Math.max(0, subtitle.size() - 1) * LINE_H;
        listH = Math.max(ROW_H, top + panelHeight - FOOTER_H - listY);
    }

    private List<ShopEntry.RewardOption> pool() {
        return entry.getRewardPool();
    }

    private int rowW() {
        return listW - ShopDialogUi.BAR_W - 3;
    }

    private int barX() {
        return listX + listW - ShopDialogUi.BAR_W;
    }

    private int btnY() {
        return top + panelHeight - 22;
    }

    private int confirmW() {
        return panelWidth - 16 - 60;
    }

    private List<Row> rows() {
        List<ShopEntry.RewardOption> pool = pool();
        float totalWeight = 0f;
        for (int w = 0; w < pool.size(); w++) totalWeight += pool.get(w).weight();
        int checkW = this.font.width("✔ ");
        List<Row> rows = new ArrayList<>(pool.size());
        for (ShopEntry.RewardOption opt : pool) {
            ItemStack st = opt.item();
            String amount = opt.minCount() == opt.maxCount() ? "§7×" + opt.minCount()
                    : "§7×" + opt.minCount() + "-" + opt.maxCount();
            String chance = "§e" + dev.ftb.mods.ftbquests.quest.loot.WeightedReward.chanceString(opt.weight(), totalWeight);
            String right = amount + " " + chance;
            int nameW = Math.max(16, rowW() - TEXT_X - this.font.width(right) - 4 - checkW);
            List<FormattedCharSequence> name = this.font.split(st.getHoverName(), nameW);
            rows.add(new Row(st, name, right, Math.max(ROW_H, name.size() * LINE_H + 4)));
        }
        return rows;
    }

    private static int[] heights(List<Row> rows) {
        int[] h = new int[rows.size()];
        for (int i = 0; i < h.length; i++) h[i] = rows.get(i).height();
        return h;
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        if (!ShopTheme.current().renderBackdropDim(g, this.width, this.height)) super.renderBackground(g);
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        ui.frame(mx, my);
        ShopTheme.current().rootPanel(g, left, top, panelWidth, panelHeight,
                left + 5, top + 18, panelWidth - 10, panelHeight - 23);

        g.drawString(this.font, "§6选择奖励", left + 8, top + 5, GOLD, true);
        for (int i = 0; i < subtitle.size(); i++) {
            g.drawString(this.font, subtitle.get(i), left + 8, top + 14 + i * LINE_H, GRAY, true);
        }

        List<Row> rows = rows();
        int[] heights = heights(rows);
        int maxScroll = ShopDialogRows.maxFirst(heights, listH);
        if (scroll > maxScroll) scroll = maxScroll;
        int visible = ShopDialogRows.fitFrom(heights, scroll, listH);
        int checkW = this.font.width("✔ ");
        int textRight = listX + rowW();
        int ry = listY;
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            Row row = rows.get(idx);
            int rh = row.height();
            float hv = ui.rowHover(idx, GuiRenderUtil.isHovering(mx, my, listX, ry, rowW(), rh));
            if (idx == selected) g.fill(listX, ry, textRight, ry + rh, SEL_BG);
            else if (hv > 0.01f) g.fill(listX, ry, textRight, ry + rh, ((int) (0x33 * hv) << 24) | 0xFFFFFF);
            int iy = ry + (rh - 16) / 2;
            g.renderItem(row.stack(), listX + 1, iy);
            g.renderItemDecorations(this.font, row.stack(), listX + 1, iy);
            int ty = ry + (rh - (row.name().size() * LINE_H - 1)) / 2;
            int nx = listX + TEXT_X;
            if (idx == selected) {
                g.drawString(this.font, "§a✔", nx, ty, WHITE, true);
                nx += checkW;
            }
            for (int l = 0; l < row.name().size(); l++) {
                g.drawString(this.font, row.name().get(l), nx, ty + l * LINE_H, WHITE, true);
            }
            g.drawString(this.font, row.right(), textRight - this.font.width(row.right()), ry + (rh - 8) / 2, WHITE, true);
            ry += rh;
        }
        ui.drawScrollbar(g, barX(), listY, listH, rows.size(), visible, scroll, maxScroll, mx, my);
        if (maxScroll > 0) {
            g.drawString(this.font, "§8" + (scroll + 1) + "-" + (scroll + visible) + "/" + rows.size()
                    + " 滚轮或拖动右侧滚动条", listX, listY + listH + 2, GRAY, true);
        }

        boolean canConfirm = selected >= 0;
        ui.draw(g, this.font, left + 8, btnY(), confirmW(), 18, canConfirm ? "§a确认购买" : "§8请先选择", mx, my, canConfirm);
        ui.draw(g, this.font, left + panelWidth - 8 - 52, btnY(), 52, 18, "§c取消", mx, my, true);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        List<Row> rows = rows();
        int[] heights = heights(rows);
        int maxScroll = ShopDialogRows.maxFirst(heights, listH);
        int visible = ShopDialogRows.fitFrom(heights, scroll, listH);
        if (btn == 0) {
            int first = ui.beginDrag(mx, my, barX(), listY, listH, rows.size(), visible, maxScroll);
            if (first >= 0) {
                scroll = first;
                return true;
            }
        }
        int ry = listY;
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (GuiRenderUtil.isHovering(mx, my, listX, ry, rowW(), heights[idx])) {
                selected = idx;
                ui.select();
                return true;
            }
            ry += heights[idx];
        }
        if (ui.hit(mx, my, left + 8, btnY(), confirmW(), 18, selected >= 0)) {
            if (selected >= 0) confirm();
            return true;
        }
        if (ui.hit(mx, my, left + panelWidth - 8 - 52, btnY(), 52, 18, true)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        int[] heights = heights(rows());
        int first = ui.drag(my, listY, listH, heights.length, ShopDialogRows.fitFrom(heights, scroll, listH),
                ShopDialogRows.maxFirst(heights, listH));
        if (first >= 0) {
            scroll = first;
            return true;
        }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected boolean universalMouseReleased(double mx, double my, int btn) {
        ui.release();
        return super.universalMouseReleased(mx, my, btn);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        if (GuiRenderUtil.isHovering(mx, my, listX, listY, listW, listH)) {
            int maxScroll = ShopDialogRows.maxFirst(heights(rows()), listH);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(d)));
            return true;
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            // 按住 Enter 的系统重复只算一次；在商店里按 Enter 打开本界面时，松开前也不算
            if (ui.takeEnter()) {
                ui.press(left + 8, btnY(), confirmW(), 18, selected >= 0);
                if (selected >= 0) confirm();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            int n = pool().size();
            if (n > 0) {
                int step = keyCode == GLFW.GLFW_KEY_DOWN ? 1 : -1;
                int next = selected < 0 ? (step > 0 ? 0 : n - 1) : Math.max(0, Math.min(n - 1, selected + step));
                if (next != selected) {
                    selected = next;
                    ui.select();
                }
                scroll = ShopDialogRows.scrollToShow(heights(rows()), scroll, selected, listH);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        ui.enterReleased(keyCode);
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        if (selected < 0) return;
        if (!parent.submitRewardChoice(catalogRevision, entryKey, times, aeMode, backpackMode, selected)) {
            ui.busy(left + 8, btnY(), confirmW(), 18);
            return;
        }
        Minecraft.getInstance().setScreen(parent);
    }

    /** Esc 回商店，不是把整个界面关掉。 */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void removed() {
        ui.cancel();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
