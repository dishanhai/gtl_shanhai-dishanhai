package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.shop.ClientShopBank;
import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import com.dishanhai.gt_shanhai.common.shop.ShopMembership;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopBankActionPacket;
import com.dishanhai.gt_shanhai.network.ShopBankQueryRequestPacket;
import com.dishanhai.gt_shanhai.network.ShopMembershipBuyPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.math.BigInteger;

/**
 * 会员中心（山海署名，客户端）：从 {@link ShopScreen} 顶栏「会员中心」按钮唤起，关闭返回 parent。
 *
 * <p>上半区：会员档位购买（青铜/白银/黄金，永久买断，见 {@link ShopMembership}）——纯购买制，
 * 不再从历史消费自动推算（见反馈：改成必须在这个界面里花钱买）。</p>
 * <p>下半区：山海银行（定期存款/贷款，见 {@code WalletAccountAPI} 的 {@code bank*} 系列结算方法，
 * 见反馈：会员中心要集成银行显示，不能只靠命令）。</p>
 *
 * <p>纯客户端界面，结算全发对应 C→S 包给服务端，权威结果由服务端回推快照/查询包刷新。</p>
 */
public class ShopMembershipScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int BOX_BG = -300674028;
    private static final int ROW_BG = -300476649;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;

    private static final int TARGET_W = 640;
    private static final int TARGET_H = 420;
    private static final int TOP_BAR_H = 16;
    private static final int BACK_W = 60;
    private static final int TIER_ROW_H = 26;
    // 弹入动画节奏 + 银行余额往返节流：跟 CurrencyAtmScreen 同一套手法/节奏，保持系列界面观感一致
    private static final long POP_ANIM_MS = 150L;
    private static final long BANK_REFRESH_TICKS = 40L;

    private final ShopScreen parent;
    private int left, top, panelWidth, panelHeight;
    private long amount = 10000L;
    private AnimatableEditBox amountBox;

    private long bankRequestedAtGameTime = Long.MIN_VALUE;
    private final long screenOpenAtMs = System.currentTimeMillis();

    private static String flashText;
    private static long flashUntil;

    /** 把带 [会员中心]/[山海银行] 前缀的系统消息镜像进本屏底部横幅（同 CurrencyAtmScreen 的做法）。 */
    public static void showMessage(Component msg) {
        if (msg == null) return;
        flashText = msg.getString();
        flashUntil = System.currentTimeMillis() + 5000L;
    }

    public ShopMembershipScreen(ShopScreen parent) {
        super(Component.literal("会员中心"));
        this.parent = parent;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.scaleMultiplier = 1.0f;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
    }

    private int backBtnX() { return left + panelWidth - 8 - BACK_W; }
    private int contentX() { return left + 8; }
    private int contentW() { return panelWidth - 16; }
    private int tiersY() { return top + TOP_BAR_H + 22; }
    private int tierRowY(int i) { return tiersY() + i * TIER_ROW_H; }
    private int bankSectionY() { return tiersY() + ShopMembership.tierCount() * TIER_ROW_H + 14; }
    private int bankDepositY() { return bankSectionY() + 12; }
    private int bankDebtY() { return bankSectionY() + 24; }
    private int bankAmountLabelY() { return bankSectionY() + 40; }
    private int bankAmountBoxY() { return bankSectionY() + 52; }
    private int bankStepY() { return bankSectionY() + 68; }
    private int bankButtonsY() { return bankSectionY() + 84; }
    private int bankRateHintY() { return bankButtonsY() + 18; }

    @Override
    protected void initScaled() {
        left = 4;
        top = 8;
        panelWidth = Math.max(500, vWidth - 8);
        panelHeight = Math.max(340, vHeight - 16);

        amountBox = new AnimatableEditBox(this.font, contentX(), bankAmountBoxY(), contentW(), 12, Component.literal("数量"));
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

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        if (amountBox != null) amountBox.setVisible(true);
        float openT = GuiRenderUtil.popAnimProgress(screenOpenAtMs, POP_ANIM_MS);
        if (openT < 1f) {
            g.pose().pushPose();
            GuiRenderUtil.popScaleAt(g, left + panelWidth / 2f, top + panelHeight / 2f, openT);
            renderPanel(g, mx, my);
            g.pose().popPose();
        } else {
            renderPanel(g, mx, my);
        }
    }

    private void renderPanel(GuiGraphics g, int mx, int my) {
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 6, top + TOP_BAR_H + 6, left + panelWidth - 6, top + panelHeight - 6, PANEL_INNER);

        g.drawString(this.font, "§6会员中心", left + 10, top + 5, GOLD, true);
        g.drawString(this.font, "§d星火 §e" + formatBig(ClientWalletAccount.getDigital()), left + 90, top + 5, WHITE, true);
        drawButton(g, backBtnX(), top + 4, BACK_W, TOP_BAR_H, "§e← 返回", mx, my);

        maybeRequestBankQuery();

        int cx = contentX(), cw = contentW();
        int memberTier = ClientWalletAccount.getMemberTier();
        g.drawString(this.font, "§6会员档位 §7(永久买断，购买后立即生效，不会过期/降级)", cx, top + TOP_BAR_H + 10, GOLD, true);
        for (int i = 0; i < ShopMembership.tierCount(); i++) {
            drawTierRow(g, cx, tierRowY(i), cw, i, memberTier, mx, my);
        }

        int by = bankSectionY();
        g.drawString(this.font, "§6山海银行", cx, by, GOLD, true);
        BigInteger deposit = ClientShopBank.getDeposit();
        BigInteger debt = ClientShopBank.getDebt();
        g.drawString(this.font, deposit == null ? "§7定期存款: §8查询中…" : "§7定期存款: §a" + formatBig(deposit) + " §7星火",
                cx, bankDepositY(), WHITE, true);
        g.drawString(this.font, debt == null ? "§7欠款: §8查询中…" : "§7欠款: §c" + formatBig(debt) + " §7星火",
                cx, bankDebtY(), WHITE, true);
        g.drawString(this.font, "§7数量（可输入）:", cx, bankAmountLabelY(), WHITE, true);

        long[] steps = {1000L, 10000L, 100000L, 1000000L};
        String[] stepLabels = {"+1k", "+10k", "+100k", "+1M"};
        int sbw = (cw - 9) / 4;
        for (int i = 0; i < 4; i++) {
            int bx = cx + i * (sbw + 3);
            drawButton(g, bx, bankStepY(), sbw, 12, "§a" + stepLabels[i], mx, my);
        }

        int bbw = (cw - 12) / 4;
        drawButton(g, cx, bankButtonsY(), bbw, 14, "§a存入", mx, my);
        drawButton(g, cx + (bbw + 4), bankButtonsY(), bbw, 14, "§6取出", mx, my);
        drawButton(g, cx + (bbw + 4) * 2, bankButtonsY(), bbw, 14, "§e借款", mx, my);
        drawButton(g, cx + (bbw + 4) * 3, bankButtonsY(), bbw, 14, "§b还款", mx, my);
        g.drawString(this.font, "§8存款利率 0.05%/小时 · 贷款利率 0.15%/小时（吃利差）· 欠款无强制追讨，靠自觉还款",
                cx, bankRateHintY(), GRAY, true);

        // 底部实时反馈横幅（同 CurrencyAtmScreen 的做法：物品图标渲染层跟 fill 不同批次，flush 防止横幅被盖住）
        if (flashText != null && System.currentTimeMillis() < flashUntil) {
            g.flush();
            int flashW = this.font.width(flashText);
            int bannerW = Math.min(panelWidth - 12, flashW + 16);
            int bannerX = left + (panelWidth - bannerW) / 2;
            int bannerY = top + panelHeight - 24;
            g.fill(bannerX, bannerY, bannerX + bannerW, bannerY + 16, 0xE0101010);
            g.fill(bannerX, bannerY, bannerX + bannerW, bannerY + 1, 0xFF00C0C0);
            g.drawCenteredString(this.font, flashText, left + panelWidth / 2, bannerY + 4, 0xFFFFFF);
        }
    }

    private void drawTierRow(GuiGraphics g, int x, int y, int w, int tier, int currentTier, int mx, int my) {
        boolean owned = currentTier >= tier;
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, TIER_ROW_H - 2);
        g.fill(x, y, x + w, y + TIER_ROW_H - 2, hover ? ROW_BG : BOX_BG);
        String name = ShopMembership.tierNameForTier(tier);
        int pct = ShopMembership.discountPercentForTier(tier);
        BigInteger price = BigInteger.valueOf(ShopMembership.priceOf(tier));
        g.drawString(this.font, "§f" + name + "会员 §a-" + pct + "%折扣", x + 4, y + 3, WHITE, true);
        g.drawString(this.font, "§7售价: §e" + formatBig(price) + " 星火", x + 4, y + 14, GRAY, true);
        int btnW = 90, btnX = x + w - 8 - btnW;
        String label = owned ? "§a已拥有"
                : (price.compareTo(ClientWalletAccount.getDigital()) > 0 ? "§8星火不足" : "§6购买");
        drawButton(g, btnX, y + 4, btnW, TIER_ROW_H - 10, label, mx, my);
    }

    /** 银行余额往返节流：打开面板/超过刷新间隔才重新请求，避免每帧都打服务端。 */
    private void maybeRequestBankQuery() {
        net.minecraft.client.multiplayer.ClientLevel lvl = Minecraft.getInstance().level;
        long gameTime = lvl != null ? lvl.getGameTime() : 0L;
        if (gameTime - bankRequestedAtGameTime < BANK_REFRESH_TICKS) return;
        bankRequestedAtGameTime = gameTime;
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopBankQueryRequestPacket());
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (GuiRenderUtil.isHovering(mx, my, backBtnX(), top + 4, BACK_W, TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        int cx = contentX(), cw = contentW();
        int memberTier = ClientWalletAccount.getMemberTier();
        for (int i = 0; i < ShopMembership.tierCount(); i++) {
            int y = tierRowY(i);
            int btnW = 90, btnX = cx + cw - 8 - btnW;
            if (GuiRenderUtil.isHovering(mx, my, btnX, y + 4, btnW, TIER_ROW_H - 10)) {
                if (memberTier < i) {
                    ShanhaiNetwork.CHANNEL.sendToServer(new ShopMembershipBuyPacket(i));
                }
                return true;
            }
        }
        long[] steps = {1000L, 10000L, 100000L, 1000000L};
        int sbw = (cw - 9) / 4;
        for (int i = 0; i < 4; i++) {
            int bx = cx + i * (sbw + 3);
            if (GuiRenderUtil.isHovering(mx, my, bx, bankStepY(), sbw, 12)) {
                amount = addClamp(amount, steps[i]);
                syncBox();
                return true;
            }
        }
        int bbw = (cw - 12) / 4;
        if (GuiRenderUtil.isHovering(mx, my, cx, bankButtonsY(), bbw, 14)) { send(ShopBankActionPacket.Op.DEPOSIT); return true; }
        if (GuiRenderUtil.isHovering(mx, my, cx + (bbw + 4), bankButtonsY(), bbw, 14)) { send(ShopBankActionPacket.Op.WITHDRAW); return true; }
        if (GuiRenderUtil.isHovering(mx, my, cx + (bbw + 4) * 2, bankButtonsY(), bbw, 14)) { send(ShopBankActionPacket.Op.BORROW); return true; }
        if (GuiRenderUtil.isHovering(mx, my, cx + (bbw + 4) * 3, bankButtonsY(), bbw, 14)) { send(ShopBankActionPacket.Op.REPAY); return true; }
        return super.universalMouseClicked(mx, my, btn);
    }

    private void send(ShopBankActionPacket.Op op) {
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopBankActionPacket(op, amount));
    }

    private void syncBox() {
        if (amountBox != null) amountBox.setValue(Long.toString(amount));
    }

    private static long addClamp(long a, long delta) {
        if (delta > 0 && a > Long.MAX_VALUE - delta) return Long.MAX_VALUE;
        return Math.max(1L, a + delta);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }

    private static String formatBig(BigInteger v) {
        if (v == null || v.signum() <= 0) return "0";
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
