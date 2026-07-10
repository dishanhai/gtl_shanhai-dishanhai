package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.common.shop.CurrencyRateConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import com.dishanhai.gt_shanhai.network.CurrencyActionPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 货币中心（ATM 子页面，客户端）。从 {@link ShopScreen} 顶栏「货币中心」按钮唤起，
 * 关闭返回 parent。左侧列各币种+余额，右侧对选中币做：提交（背包→钱包）、
 * 币种兑换（按 {@link CurrencyRateConfig} 汇率）、AE 抽取（AE 模式，网络→钱包）。
 * 纯客户端界面，结算全发 {@link CurrencyActionPacket} 给服务端。
 */
public class CurrencyAtmScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int BOX_BG = -300674028;
    private static final int ROW_BG = -300476649;
    private static final int SELECT_BG = -299421158;
    private static final int GREEN = -11141291;
    private static final int GREEN_DARK = -13661393;
    private static final int CYAN = -11141121;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;

    private static final int TARGET_W = 900;
    private static final int TARGET_H = 430;
    private static final int TOP_BAR_H = 16;
    private static final int LIST_W = 220;
    private static final int ROW_H = 20;
    private static final int BACK_W = 60;

    private final ShopScreen parent;
    private List<ResourceLocation> currencies = new ArrayList<>();
    private ResourceLocation selected;
    private int targetIdx = -1;   // 兑换目标币在 currencies 里的下标
    private long amount = 1L;
    private EditBox amountBox;
    private int scroll = 0;

    private int left, top, panelWidth, panelHeight;

    public CurrencyAtmScreen(ShopScreen parent) {
        super(Component.literal("货币中心"));
        this.parent = parent;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.scaleMultiplier = 1.0f;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
    }

    // ===== 坐标 =====
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
        panelWidth = Math.max(560, vWidth - 8);
        panelHeight = Math.max(360, vHeight - 16);

        CurrencyRateConfig.reload();
        currencies = CurrencyRateConfig.getCurrencies();
        if (selected == null && !currencies.isEmpty()) selected = currencies.get(0);
        if (targetIdx < 0 && currencies.size() > 1) targetIdx = firstDifferent(0);

        int cx = detailX() + 8;
        amountBox = new EditBox(this.font, cx, detailY(88), detailW() - 16, 12, Component.literal("数量"));
        amountBox.setValue(Long.toString(Math.max(1L, amount)));
        amountBox.setBordered(true);
        amountBox.setTextColor(0xFFFFFF);
        amountBox.setMaxLength(19);
        amountBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        amountBox.setResponder(s -> {
            long v;
            try { v = s.isEmpty() ? 1L : Long.parseLong(s); }
            catch (NumberFormatException ex) { v = Long.MAX_VALUE; }
            amount = Math.max(1L, v);
        });
        addRenderableWidget(amountBox);
    }

    private int detailY(int off) { return listTop() + off; }

    /** 从 start 起找第一个与 selected 不同的币下标（兑换目标）。 */
    private int firstDifferent(int start) {
        for (int i = 0; i < currencies.size(); i++) {
            int idx = (start + i) % currencies.size();
            if (!currencies.get(idx).equals(selected)) return idx;
        }
        return -1;
    }

    // ===== 渲染 =====
    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        if (amountBox != null) amountBox.setVisible(selected != null);
        // 面板
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 6, top + TOP_BAR_H + 6, left + panelWidth - 6, top + panelHeight - 6, PANEL_INNER);

        g.drawString(this.font, "§6货币中心 §7- ATM", left + 10, top + 5, GOLD, true);
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
        for (int i = 0; i < currencies.size(); i++) {
            ResourceLocation cur = currencies.get(i);
            int ry = ly + 2 + i * ROW_H - scroll;
            if (ry + ROW_H <= ly || ry >= ly + lh) continue;
            boolean sel = cur.equals(selected);
            boolean hover = GuiRenderUtil.isHovering(mx, my, lx + 1, ry, lw - 2, ROW_H - 1);
            g.fill(lx + 1, ry, lx + lw - 1, ry + ROW_H - 1, sel ? SELECT_BG : (hover ? ROW_BG : 0));
            Item item = ForgeRegistries.ITEMS.getValue(cur);
            if (item != null) g.renderItem(new ItemStack(item), lx + 3, ry + 1);
            String name = GuiRenderUtil.trimText(this.font, ShopPurchase.coinName(cur), lw - 28);
            g.drawString(this.font, name, lx + 22, ry + 2, sel ? GOLD : WHITE, true);
            BigInteger bal = ClientWalletAccount.getCurrency(cur);
            g.drawString(this.font, "§7余额 §e" + formatBig(bal), lx + 22, ry + 11, GRAY, true);
        }
        g.disableScissor();
    }

    private void drawDetail(GuiGraphics g, int mx, int my) {
        int dx = detailX(), dy = listTop(), dw = detailW(), dh = listHeight();
        g.fill(dx, dy, dx + dw, dy + dh, BOX_BG);
        if (selected == null) {
            g.drawCenteredString(this.font, "§7← 选择货币", dx + dw / 2, dy + 18, GRAY);
            return;
        }
        int cx = dx + 8;
        Item item = ForgeRegistries.ITEMS.getValue(selected);
        if (item != null) g.renderItem(new ItemStack(item), cx, dy + 8);
        g.drawString(this.font, ShopPurchase.coinName(selected), cx + 22, dy + 8, WHITE, true);
        BigInteger bal = ClientWalletAccount.getCurrency(selected);
        g.drawString(this.font, "§7钱包余额: §e" + formatBig(bal), cx + 22, dy + 20, CYAN, true);
        g.drawString(this.font, "§7币值: §f" + CurrencyRateConfig.getValue(selected), cx + 22, dy + 32, GRAY, true);

        // 提交全部
        drawButton(g, cx, dy + 48, detailW() - 16, 14, "§a提交全部（背包→钱包）", mx, my);

        // 数量标签（amountBox 由 super.render 画在 dy+88）
        g.drawString(this.font, "§7数量（可输入）:", cx, dy + 76, WHITE, true);
        // 步进按钮
        long[] steps = {1, 10, 100, 1000};
        int bw = (detailW() - 16 - 9) / 4;
        for (int i = 0; i < 4; i++) {
            int bx = cx + i * (bw + 3);
            drawButton(g, bx, dy + 104, bw, 12, "§a+" + compactStep(steps[i]), mx, my);
            drawButton(g, bx, dy + 118, bw, 12, "§c-" + compactStep(steps[i]), mx, my);
        }

        // AE 抽取（仅 AE 模式）
        boolean ae = ShopScreen.isAeMode();
        if (ae) {
            drawButton(g, cx, dy + 136, detailW() - 16, 14, "§b从 AE 抽取 " + formatBig(BigInteger.valueOf(amount)) + " 枚", mx, my);
        } else {
            g.drawString(this.font, "§8（开启 AE 模式后可从网络抽取）", cx, dy + 139, GRAY, true);
        }

        // 兑换区
        int ey = dy + 158;
        g.drawString(this.font, "§7兑换目标:", cx, ey, WHITE, true);
        ResourceLocation target = targetIdx >= 0 && targetIdx < currencies.size() ? currencies.get(targetIdx) : null;
        // ◀ 目标币 ▶
        drawButton(g, cx, ey + 12, 14, 14, "§e◀", mx, my);
        String tname = target == null ? "§8无" : GuiRenderUtil.trimText(this.font, ShopPurchase.coinName(target), detailW() - 60);
        g.drawString(this.font, tname, cx + 20, ey + 16, GOLD, true);
        drawButton(g, cx + detailW() - 16 - 14, ey + 12, 14, 14, "§e▶", mx, my);
        // 预计得到
        long gain = previewExchange(selected, target, amount);
        g.drawString(this.font, "§7预计得到: §a" + formatBig(BigInteger.valueOf(gain)) + " §7"
                + (target == null ? "" : ShopPurchase.coinName(target)), cx, ey + 30, GREEN, true);
        drawButton(g, cx, ey + 42, detailW() - 16, 14,
                gain > 0 ? "§a兑换" : "§8兑换（数量太小/无目标）", mx, my);
    }

    /** 客户端预览兑换结果（服务端最终裁定）。 */
    private static long previewExchange(ResourceLocation from, ResourceLocation to, long amount) {
        if (from == null || to == null || from.equals(to) || amount <= 0) return 0;
        long fv = CurrencyRateConfig.getValue(from), tv = CurrencyRateConfig.getValue(to);
        if (fv <= 0 || tv <= 0) return 0;
        BigInteger dst = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(fv)).divide(BigInteger.valueOf(tv));
        return dst.bitLength() < 63 ? dst.longValue() : Long.MAX_VALUE;
    }

    // ===== 交互 =====
    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        // 返回
        if (GuiRenderUtil.isHovering(mx, my, backBtnX(), top + 4, BACK_W, TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        // 列表选中
        int lx = listLeft(), ly = listTop(), lw = LIST_W, lh = listHeight();
        if (my >= ly && my <= ly + lh) {
            for (int i = 0; i < currencies.size(); i++) {
                int ry = ly + 2 + i * ROW_H - scroll;
                if (ry + ROW_H <= ly || ry >= ly + lh) continue;
                if (GuiRenderUtil.isHovering(mx, my, lx + 1, ry, lw - 2, ROW_H - 1)) {
                    selected = currencies.get(i);
                    if (targetIdx < 0 || currencies.get(targetIdx).equals(selected)) targetIdx = firstDifferent(i);
                    return true;
                }
            }
        }
        if (selected == null) return super.universalMouseClicked(mx, my, btn);

        int dx = detailX(), dy = listTop(), cx = dx + 8;
        int fullW = detailW() - 16;
        // 提交全部
        if (GuiRenderUtil.isHovering(mx, my, cx, dy + 48, fullW, 14)) {
            send(CurrencyActionPacket.Op.DEPOSIT, selected, null, 0L);
            optimisticDeposit(selected);
            return true;
        }
        // 步进
        long[] steps = {1, 10, 100, 1000};
        int bw = (fullW - 9) / 4;
        for (int i = 0; i < 4; i++) {
            int bx = cx + i * (bw + 3);
            if (GuiRenderUtil.isHovering(mx, my, bx, dy + 104, bw, 12)) { amount = addClamp(amount, steps[i]); syncBox(); return true; }
            if (GuiRenderUtil.isHovering(mx, my, bx, dy + 118, bw, 12)) { amount = addClamp(amount, -steps[i]); syncBox(); return true; }
        }
        // AE 抽取
        if (ShopScreen.isAeMode() && GuiRenderUtil.isHovering(mx, my, cx, dy + 136, fullW, 14)) {
            send(CurrencyActionPacket.Op.AE_EXTRACT, selected, null, amount);
            ClientWalletAccount.optimisticAddCurrency(selected, BigInteger.valueOf(amount)); // 乐观预览：按输入量先加，服务端快照校正
            return true;
        }
        // 兑换目标切换 ◀ ▶
        int ey = dy + 158;
        if (GuiRenderUtil.isHovering(mx, my, cx, ey + 12, 14, 14)) { cycleTarget(-1); return true; }
        if (GuiRenderUtil.isHovering(mx, my, cx + fullW - 14, ey + 12, 14, 14)) { cycleTarget(1); return true; }
        // 兑换
        if (GuiRenderUtil.isHovering(mx, my, cx, ey + 42, fullW, 14)) {
            ResourceLocation target = targetIdx >= 0 && targetIdx < currencies.size() ? currencies.get(targetIdx) : null;
            if (target != null) {
                long gain = previewExchange(selected, target, amount);
                send(CurrencyActionPacket.Op.EXCHANGE, selected, target, amount);
                // 乐观预览：与服务端同一条件（余额≥amount 且 gain>0），扣源加目标，服务端快照校正
                if (gain > 0 && ClientWalletAccount.getCurrency(selected).compareTo(BigInteger.valueOf(amount)) >= 0) {
                    ClientWalletAccount.optimisticAddCurrency(selected, BigInteger.valueOf(amount).negate());
                    ClientWalletAccount.optimisticAddCurrency(target, BigInteger.valueOf(gain));
                }
            }
            return true;
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double delta) {
        int lx = listLeft(), ly = listTop(), lw = LIST_W, lh = listHeight();
        if (GuiRenderUtil.isHovering(mx, my, lx, ly, lw, lh)) {
            int contentH = currencies.size() * ROW_H + 4;
            int maxScroll = Math.max(0, contentH - lh);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * ROW_H)));
            return true;
        }
        return super.universalMouseScrolled(mx, my, delta);
    }

    private void cycleTarget(int dir) {
        if (currencies.size() < 2) return;
        int idx = targetIdx < 0 ? 0 : targetIdx;
        for (int k = 0; k < currencies.size(); k++) {
            idx = (idx + dir + currencies.size()) % currencies.size();
            if (!currencies.get(idx).equals(selected)) { targetIdx = idx; return; }
        }
    }

    private void send(CurrencyActionPacket.Op op, ResourceLocation currency, ResourceLocation target, long amt) {
        ShanhaiNetwork.CHANNEL.sendToServer(new CurrencyActionPacket(op, currency, target, amt));
    }

    private void syncBox() {
        if (amountBox != null) amountBox.setValue(Long.toString(amount));
    }

    private static long addClamp(long a, long delta) {
        if (delta > 0 && a > Long.MAX_VALUE - delta) return Long.MAX_VALUE;
        return Math.max(1L, a + delta);
    }

    /** 乐观预览：把背包里该币的实体数量先加进钱包显示（服务端权威快照同步后一致）。 */
    private void optimisticDeposit(ResourceLocation cur) {
        var p = Minecraft.getInstance().player;
        Item item = ForgeRegistries.ITEMS.getValue(cur);
        if (p == null || item == null) return;
        int have = ShopPurchase.countItem(p, item);
        if (have > 0) ClientWalletAccount.optimisticAddCurrency(cur, BigInteger.valueOf(have));
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
