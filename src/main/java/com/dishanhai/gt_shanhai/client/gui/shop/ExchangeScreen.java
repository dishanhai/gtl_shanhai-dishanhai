package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import com.dishanhai.gt_shanhai.common.shop.ExchangeConfig;
import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.network.ExchangePacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 兑换中心（子页面，客户端，山海署名）。从 {@link ShopScreen} 顶栏「兑换中心」唤起，关闭返回 parent。
 *
 * <p>左侧列 {@link ExchangeConfig} 兑换条目，右侧显示选中条目的「付出 → 得到」两侧（星火 + 多物品/流体），
 * 正向/反向按钮各发一个 {@link ExchangePacket}。编辑权玩家可新增/编辑/删除。星火余额读客户端账户缓存。</p>
 */
public class ExchangeScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int BOX_BG = -300674028;
    private static final int ROW_BG = -300476649;
    private static final int SELECT_BG = -299421158;
    private static final int GREEN = -11141291;
    private static final int CYAN = -11141121;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;

    private static final int TARGET_W = 980;
    private static final int TARGET_H = 470;
    private static final int TOP_BAR_H = 16;
    private static final int LIST_W = 260;
    private static final int ROW_H = 22;
    private static final int BACK_W = 60;

    private final ShopScreen parent;
    private final boolean canEdit;
    private List<ExchangeEntry> entries = new ArrayList<>();
    private ExchangeEntry selected;
    private long times = 1L;
    private EditBox timesBox;
    private int scroll = 0;

    private int left, top, panelWidth, panelHeight;

    public ExchangeScreen(ShopScreen parent, boolean canEdit) {
        super(Component.literal("兑换中心"));
        this.parent = parent;
        this.canEdit = canEdit;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.scaleMultiplier = 1.0f;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
    }

    private int listLeft() { return left + 8; }
    private int listTop() { return top + TOP_BAR_H + 10; }
    private int listHeight() { return panelHeight - TOP_BAR_H - 18; }
    private int detailX() { return listLeft() + LIST_W + 8; }
    private int detailW() { return left + panelWidth - 8 - detailX(); }
    private int backBtnX() { return left + panelWidth - 8 - BACK_W; }

    @Override
    protected void initScaled() {
        left = 4;
        top = 8;
        panelWidth = Math.max(600, vWidth - 8);
        panelHeight = Math.max(380, vHeight - 16);

        ExchangeConfig.reload();
        refreshEntries();
        if (selected != null && !entries.contains(selected)) selected = null;
        if (selected == null && !entries.isEmpty()) selected = entries.get(0);

        int cx = detailX() + 8;
        timesBox = new EditBox(this.font, cx, detailY(150), 120, 12, Component.literal("数量"));
        timesBox.setValue(Long.toString(Math.max(1L, times)));
        timesBox.setBordered(true);
        timesBox.setTextColor(0xFFFFFF);
        timesBox.setMaxLength(19);
        timesBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        timesBox.setResponder(s -> {
            long v;
            try { v = s.isEmpty() ? 1L : Long.parseLong(s); }
            catch (NumberFormatException ex) { v = Long.MAX_VALUE; }
            times = Math.max(1L, v);
        });
        addRenderableWidget(timesBox);
    }

    private void refreshEntries() {
        entries = new ArrayList<>();
        for (ExchangeEntry e : ExchangeConfig.getEntries()) {
            if (e.isValid()) entries.add(e);
        }
    }

    private int detailY(int off) { return listTop() + off; }

    // ===== 渲染 =====
    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        if (timesBox != null) timesBox.setVisible(selected != null);
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 6, top + TOP_BAR_H + 6, left + panelWidth - 6, top + panelHeight - 6, PANEL_INNER);

        String spark = formatBig(ClientWalletAccount.getDigital());
        g.drawString(this.font, "§6兑换中心 §7· 星火 §e" + spark, left + 10, top + 5, GOLD, true);
        drawButton(g, backBtnX(), top + 4, BACK_W, TOP_BAR_H, "§e← 返回", mx, my);
        boolean ae = ShopScreen.isAeMode();
        g.drawString(this.font, ae ? "§aAE模式:开" : "§8AE模式:关", backBtnX() - 84, top + 5, ae ? GREEN : GRAY, true);

        drawList(g, mx, my);
        drawDetail(g, mx, my);
    }

    private void drawList(GuiGraphics g, int mx, int my) {
        int lx = listLeft(), ly = listTop(), lw = LIST_W, lh = listHeight();
        g.fill(lx, ly, lx + lw, ly + lh, BOX_BG);
        g.enableScissor(
                (int) (lx * guiScale) + offsetX, (int) (ly * guiScale) + offsetY,
                (int) ((lx + lw) * guiScale) + offsetX, (int) ((ly + lh) * guiScale) + offsetY);
        if (entries.isEmpty()) {
            g.drawString(this.font, "§8暂无兑换条目", lx + 8, ly + 8, GRAY, true);
            if (canEdit) g.drawString(this.font, "§7点右下「新增」创建", lx + 8, ly + 20, GRAY, true);
        }
        for (int i = 0; i < entries.size(); i++) {
            ExchangeEntry e = entries.get(i);
            int ry = ly + 2 + i * ROW_H - scroll;
            if (ry + ROW_H <= ly || ry >= ly + lh) continue;
            boolean sel = e == selected;
            boolean hover = GuiRenderUtil.isHovering(mx, my, lx + 1, ry, lw - 2, ROW_H - 1);
            g.fill(lx + 1, ry, lx + lw - 1, ry + ROW_H - 1, sel ? SELECT_BG : (hover ? ROW_BG : 0));
            String name = GuiRenderUtil.trimText(this.font, e.displayName(), lw - 10);
            g.drawString(this.font, name, lx + 5, ry + 2, sel ? GOLD : WHITE, true);
            String summary = GuiRenderUtil.trimText(this.font, sideSummary(e.getCost()) + " §7→ " + sideSummary(e.getResult()), lw - 10);
            g.drawString(this.font, summary, lx + 5, ry + 12, GRAY, true);
        }
        g.disableScissor();
    }

    /** 一侧的紧凑文字摘要（列表用）。 */
    private static String sideSummary(ExchangeEntry.Side side) {
        StringBuilder sb = new StringBuilder();
        if (side.spark.signum() > 0) sb.append("§e").append(formatBig(side.spark)).append("星火");
        for (ExchangeEntry.Ingredient in : side.ingredients) {
            if (sb.length() > 0) sb.append("§7+");
            sb.append(in.isFluid ? "§b" : "§f").append(in.count).append("×").append(in.id.getPath());
        }
        return sb.length() == 0 ? "§8空" : sb.toString();
    }

    private void drawDetail(GuiGraphics g, int mx, int my) {
        int dx = detailX(), dy = listTop(), dw = detailW(), dh = listHeight();
        g.fill(dx, dy, dx + dw, dy + dh, BOX_BG);
        int cx = dx + 8;
        if (selected == null) {
            g.drawCenteredString(this.font, "§7← 选择兑换条目", dx + dw / 2, dy + 18, GRAY);
            if (canEdit) drawButton(g, cx, dy + dh - 20, 90, 14, "§a新增条目", mx, my);
            return;
        }
        g.drawString(this.font, "§6" + selected.displayName(), cx, dy + 6, GOLD, true);

        g.drawString(this.font, "§7付出：", cx, dy + 22, WHITE, true);
        drawSide(g, selected.getCost(), cx + 40, dy + 20);
        g.drawString(this.font, "§7得到：", cx, dy + 42, WHITE, true);
        drawSide(g, selected.getResult(), cx + 40, dy + 40);

        // 数量输入 + 步进（timesBox 由 super.render 画在 detailY(150)）
        g.drawString(this.font, "§7次数（可输入）:", cx, dy + 138, WHITE, true);
        long[] steps = {1, 10, 100, 1000};
        int bw = 60;
        for (int i = 0; i < 4; i++) {
            int bx = cx + 130 + i * (bw + 3);
            drawButton(g, bx, dy + 146, bw, 12, "§a+" + compactStep(steps[i]), mx, my);
            drawButton(g, bx, dy + 160, bw, 12, "§c-" + compactStep(steps[i]), mx, my);
        }

        int fullW = detailW() - 16;
        drawButton(g, cx, dy + 180, fullW, 16, "§a正向兑换 §7(付出 → 得到) ×" + ShopPurchase.formatCount(times), mx, my);
        drawButton(g, cx, dy + 200, fullW, 16, "§e反向兑换 §7(得到 → 付出) ×" + ShopPurchase.formatCount(times), mx, my);

        if (canEdit) {
            int ey = dy + dh - 20;
            drawButton(g, cx, ey, 90, 14, "§a新增", mx, my);
            drawButton(g, cx + 96, ey, 90, 14, "§b编辑", mx, my);
            drawButton(g, cx + 192, ey, 90, 14, "§c删除", mx, my);
        }
    }

    /** 画一侧的物料（星火文字 + 物品图标 + 流体文字），返回不关心宽度。 */
    private void drawSide(GuiGraphics g, ExchangeEntry.Side side, int x, int y) {
        int px = x;
        if (side.spark.signum() > 0) {
            String s = "§e" + formatBig(side.spark) + "星火";
            g.drawString(this.font, s, px, y + 4, GREEN, true);
            px += this.font.width(s.replace("§e", "")) + 10;
        }
        for (ExchangeEntry.Ingredient in : side.ingredients) {
            if (in.isFluid) {
                Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
                String fname = fluid == null ? in.id.getPath() : in.id.getPath();
                String s = "§b" + in.count + "×" + fname;
                g.drawString(this.font, s, px, y + 4, CYAN, true);
                px += this.font.width(in.count + "×" + fname) + 10;
            } else {
                Item item = ForgeRegistries.ITEMS.getValue(in.id);
                if (item != null) g.renderItem(new ItemStack(item), px, y);
                String s = "§f×" + in.count;
                g.drawString(this.font, s, px + 17, y + 4, WHITE, true);
                px += 17 + this.font.width("×" + in.count) + 8;
            }
        }
    }

    // ===== 交互 =====
    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (GuiRenderUtil.isHovering(mx, my, backBtnX(), top + 4, BACK_W, TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        // 列表选中
        int lx = listLeft(), ly = listTop(), lw = LIST_W, lh = listHeight();
        if (my >= ly && my <= ly + lh && mx >= lx && mx <= lx + lw) {
            for (int i = 0; i < entries.size(); i++) {
                int ry = ly + 2 + i * ROW_H - scroll;
                if (ry + ROW_H <= ly || ry >= ly + lh) continue;
                if (GuiRenderUtil.isHovering(mx, my, lx + 1, ry, lw - 2, ROW_H - 1)) {
                    selected = entries.get(i);
                    return true;
                }
            }
        }

        int dx = detailX(), dy = listTop(), dh = listHeight(), cx = dx + 8;
        int fullW = detailW() - 16;

        // 无选中时也可新增
        if (selected == null) {
            if (canEdit && GuiRenderUtil.isHovering(mx, my, cx, dy + dh - 20, 90, 14)) {
                ExchangeEditor.openNew(this);
                return true;
            }
            return super.universalMouseClicked(mx, my, btn);
        }

        // 步进
        long[] steps = {1, 10, 100, 1000};
        int bw = 60;
        for (int i = 0; i < 4; i++) {
            int bx = cx + 130 + i * (bw + 3);
            if (GuiRenderUtil.isHovering(mx, my, bx, dy + 146, bw, 12)) { times = addClamp(times, steps[i]); syncBox(); return true; }
            if (GuiRenderUtil.isHovering(mx, my, bx, dy + 160, bw, 12)) { times = addClamp(times, -steps[i]); syncBox(); return true; }
        }
        // 正向 / 反向兑换
        if (GuiRenderUtil.isHovering(mx, my, cx, dy + 180, fullW, 16)) {
            send(selected.getId(), false);
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, cx, dy + 200, fullW, 16)) {
            send(selected.getId(), true);
            return true;
        }
        // 编辑权按钮
        if (canEdit) {
            int ey = dy + dh - 20;
            if (GuiRenderUtil.isHovering(mx, my, cx, ey, 90, 14)) { ExchangeEditor.openNew(this); return true; }
            if (GuiRenderUtil.isHovering(mx, my, cx + 96, ey, 90, 14)) { ExchangeEditor.openEdit(this, selected); return true; }
            if (GuiRenderUtil.isHovering(mx, my, cx + 192, ey, 90, 14)) {
                ShanhaiNetwork.CHANNEL.sendToServer(new com.dishanhai.gt_shanhai.network.ExchangeEditPacket(
                        com.dishanhai.gt_shanhai.network.ExchangeEditPacket.Action.DELETE, selected, null));
                selected = null;
                return true;
            }
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double delta) {
        int lx = listLeft(), ly = listTop(), lw = LIST_W, lh = listHeight();
        if (GuiRenderUtil.isHovering(mx, my, lx, ly, lw, lh)) {
            int contentH = entries.size() * ROW_H + 4;
            int maxScroll = Math.max(0, contentH - lh);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * ROW_H)));
            return true;
        }
        return super.universalMouseScrolled(mx, my, delta);
    }

    private void send(String entryId, boolean reverse) {
        ShanhaiNetwork.CHANNEL.sendToServer(new ExchangePacket(entryId, times, reverse, ShopScreen.isAeMode()));
    }

    private void syncBox() {
        if (timesBox != null) timesBox.setValue(Long.toString(times));
    }

    private static long addClamp(long a, long delta) {
        if (delta > 0 && a > Long.MAX_VALUE - delta) return Long.MAX_VALUE;
        return Math.max(1L, a + delta);
    }

    // ===== 小工具 =====
    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }

    private static String compactStep(long step) {
        return step >= 1000 ? (step / 1000) + "k" : String.valueOf(step);
    }

    private static String formatBig(BigInteger v) {
        if (v.signum() <= 0) return "0";
        if (v.bitLength() < 63) {
            long n = v.longValue();
            if (n >= 1_000_000_000_000L) return (n / 1_000_000_000_000L) + "T+";
            if (n >= 1_000_000_000L) return (n / 1_000_000_000L) + "B+";
            if (n >= 1_000_000L) return (n / 1_000_000L) + "M+";
            if (n >= 1_000L) return (n / 1_000L) + "K+";
            return String.valueOf(n);
        }
        String s = v.toString();
        return s.charAt(0) + "." + s.substring(1, Math.min(3, s.length())) + "e" + (s.length() - 1);
    }
}
