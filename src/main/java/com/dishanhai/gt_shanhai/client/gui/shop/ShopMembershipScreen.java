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
 * <p>页签「会员」：青铜/白银/黄金永久买断，付目标档全价。</p>
 * <p>页签「银行」：定期存款与贷款。数字、利率、可借额度来自服务器快照。</p>
 */
public class ShopMembershipScreen extends ScaledScreen {

    private static final int PAGE_MEMBER = 0;
    private static final int PAGE_BANK = 1;

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
    private static final int TAB_W = 72;
    private static final int TIER_ROW_H = 36;
    private static final long POP_ANIM_MS = 150L;
    private static final long BANK_REFRESH_TICKS = 40L;

    private final ShopScreen parent;
    private int left, top, panelWidth, panelHeight;
    private int page = PAGE_MEMBER;
    private long amount = 10000L;
    private AnimatableEditBox amountBox;

    private long bankRequestedAtGameTime = -BANK_REFRESH_TICKS;
    private final long screenOpenAtMs = System.currentTimeMillis();

    private static String flashText;
    private static long flashUntil;

    /** 把带 [会员中心]/[山海银行] 前缀的系统消息镜像进本屏底部横幅。 */
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
    private int bankTabX() { return backBtnX() - 6 - TAB_W; }
    private int memberTabX() { return bankTabX() - 4 - TAB_W; }
    private int contentX() { return left + 8; }
    private int contentW() { return panelWidth - 16; }
    private int tabY() { return top + 4; }
    private int pageY() { return top + TOP_BAR_H + 10; }
    private int tierRowY(int i) { return pageY() + 16 + i * TIER_ROW_H; }
    private int cardY() { return pageY(); }
    private int cardH() { return 78; }
    private int cardW() { return (contentW() - 8) / 2; }
    private int debtCardX() { return contentX() + cardW() + 8; }
    private int amountLabelY() { return cardY() + cardH() + 8; }
    private int bankAmountBoxY() { return amountLabelY() + 12; }
    private int bankStepY() { return bankAmountBoxY() + 16; }
    private int bankButtonsY() { return bankStepY() + 16; }
    private int bankAllY() { return bankButtonsY() + 18; }
    private int bankRateHintY() { return bankAllY() + 18; }

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
        syncAmountBox();
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

    private void syncAmountBox() {
        if (amountBox == null) return;
        boolean bank = page == PAGE_BANK;
        amountBox.setVisible(bank);
        amountBox.active = bank;
    }

    private void renderPanel(GuiGraphics g, int mx, int my) {
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 6, top + TOP_BAR_H + 6, left + panelWidth - 6, top + panelHeight - 6, PANEL_INNER);

        g.drawString(this.font, "§6会员中心", left + 10, top + 5, GOLD, true);
        String spark = fitSpark(formatExact(ClientWalletAccount.getDigital()), memberTabX() - (left + 78) - 8);
        g.drawString(this.font, "§d星火 §e" + spark, left + 78, top + 5, WHITE, true);
        drawButton(g, memberTabX(), tabY(), TAB_W, TOP_BAR_H, page == PAGE_MEMBER ? "§6会员" : "§7会员", mx, my);
        drawButton(g, bankTabX(), tabY(), TAB_W, TOP_BAR_H, page == PAGE_BANK ? "§6银行" : "§7银行", mx, my);
        drawButton(g, backBtnX(), top + 4, BACK_W, TOP_BAR_H, "§e← 返回", mx, my);

        maybeRequestBankQuery();
        if (page == PAGE_MEMBER) renderMember(g, mx, my);
        else renderBank(g, mx, my);
        renderFlash(g);
    }

    private void renderMember(GuiGraphics g, int mx, int my) {
        int cx = contentX();
        int cw = contentW();
        int memberTier = ClientWalletAccount.getMemberTier();
        g.drawString(this.font, "§6会员档位 §7(永久买断，付该档全价，不会过期或降级)", cx, pageY(), GOLD, true);
        for (int i = 0; i < ShopMembership.tierCount(); i++) {
            drawTierRow(g, cx, tierRowY(i), cw, i, memberTier, mx, my);
        }
    }

    private void renderBank(GuiGraphics g, int mx, int my) {
        int cx = contentX();
        int cw = contentW();
        ClientShopBank.Snapshot bank = ClientShopBank.get();
        drawCard(g, cx, cardY(), cardW(), cardH(), "§6定期存款",
                bank == null ? null : bank.depositPrincipal,
                bank == null ? null : bank.depositInterest,
                bank == null ? null : bank.depositTotal(),
                null);
        String roomLine = bank == null ? "§8查询中…"
                : "§7可借 §e" + formatExact(bank.loanRoom) + " §7/ " + formatExact(BigInteger.valueOf(Math.max(0L, bank.maxLoan)));
        drawCard(g, debtCardX(), cardY(), cardW(), cardH(), "§6贷款欠款",
                bank == null ? null : bank.debtPrincipal,
                bank == null ? null : bank.debtInterest,
                bank == null ? null : bank.debtTotal(),
                roomLine);

        g.drawString(this.font, "§7数量（可输入）:", cx, amountLabelY(), WHITE, true);
        long[] steps = {1000L, 10000L, 100000L, 1000000L};
        String[] stepLabels = {"+1k", "+10k", "+100k", "+1M"};
        int sbw = (cw - 9) / 4;
        for (int i = 0; i < 4; i++) {
            drawButton(g, cx + i * (sbw + 3), bankStepY(), sbw, 12, "§a" + stepLabels[i], mx, my);
        }
        int bbw = (cw - 12) / 4;
        drawButton(g, cx, bankButtonsY(), bbw, 14, "§a存入", mx, my);
        drawButton(g, cx + (bbw + 4), bankButtonsY(), bbw, 14, "§6取出", mx, my);
        drawButton(g, cx + (bbw + 4) * 2, bankButtonsY(), bbw, 14, "§e借款", mx, my);
        drawButton(g, cx + (bbw + 4) * 3, bankButtonsY(), bbw, 14, "§b还款", mx, my);
        int allW = (cw - 8) / 3;
        drawButton(g, cx, bankAllY(), allW, 14, "§a全部存入", mx, my);
        drawButton(g, cx + allW + 4, bankAllY(), allW, 14, "§6全部取出", mx, my);
        drawButton(g, cx + (allW + 4) * 2, bankAllY(), allW, 14, "§b还清", mx, my);
        g.drawString(this.font, rateHint(bank), cx, bankRateHintY(), GRAY, true);
    }

    private void drawCard(GuiGraphics g, int x, int y, int w, int h, String title,
                          BigInteger principal, BigInteger interest, BigInteger total, String extra) {
        g.fill(x, y, x + w, y + h, BOX_BG);
        g.drawString(this.font, title, x + 6, y + 4, GOLD, true);
        if (principal == null) {
            g.drawString(this.font, "§8查询中…", x + 6, y + 18, GRAY, true);
            return;
        }
        g.drawString(this.font, "§7本金 §f" + formatExact(principal), x + 6, y + 18, WHITE, true);
        g.drawString(this.font, "§7利息 §f" + formatExact(interest), x + 6, y + 30, WHITE, true);
        g.drawString(this.font, "§7合计 §e" + formatExact(total), x + 6, y + 42, WHITE, true);
        if (extra != null) g.drawString(this.font, extra, x + 6, y + 56, WHITE, true);
    }

    private void renderFlash(GuiGraphics g) {
        if (flashText == null || System.currentTimeMillis() >= flashUntil) return;
        g.flush();
        int flashW = this.font.width(flashText);
        int bannerW = Math.min(panelWidth - 12, flashW + 16);
        int bannerX = left + (panelWidth - bannerW) / 2;
        int bannerY = top + panelHeight - 24;
        g.fill(bannerX, bannerY, bannerX + bannerW, bannerY + 16, 0xE0101010);
        g.fill(bannerX, bannerY, bannerX + bannerW, bannerY + 1, 0xFF00C0C0);
        g.drawCenteredString(this.font, flashText, left + panelWidth / 2, bannerY + 4, 0xFFFFFF);
    }

    private void drawTierRow(GuiGraphics g, int x, int y, int w, int tier, int currentTier, int mx, int my) {
        boolean owned = currentTier >= tier;
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, TIER_ROW_H - 4);
        g.fill(x, y, x + w, y + TIER_ROW_H - 4, hover ? ROW_BG : BOX_BG);
        String name = ShopMembership.tierNameForTier(tier);
        int pct = ShopMembership.discountPercentForTier(tier);
        BigInteger price = BigInteger.valueOf(ShopMembership.priceOf(tier));
        g.drawString(this.font, "§f" + name + "会员 §a-" + pct + "%折扣", x + 6, y + 6, WHITE, true);
        g.drawString(this.font, "§7售价: §e" + formatExact(price) + " 星火", x + 6, y + 18, GRAY, true);
        int btnW = 90;
        int btnX = x + w - 8 - btnW;
        String label = owned ? "§a已拥有"
                : (price.compareTo(ClientWalletAccount.getDigital()) > 0 ? "§8星火不足" : "§6购买");
        drawButton(g, btnX, y + 8, btnW, 16, label, mx, my);
    }

    private void maybeRequestBankQuery() {
        net.minecraft.client.multiplayer.ClientLevel lvl = Minecraft.getInstance().level;
        long gameTime = lvl != null ? lvl.getGameTime() : 0L;
        if (gameTime - bankRequestedAtGameTime < BANK_REFRESH_TICKS) return;
        bankRequestedAtGameTime = gameTime;
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopBankQueryRequestPacket());
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        syncAmountBox();
        if (GuiRenderUtil.isHovering(mx, my, backBtnX(), top + 4, BACK_W, TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, memberTabX(), tabY(), TAB_W, TOP_BAR_H)) {
            page = PAGE_MEMBER;
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, bankTabX(), tabY(), TAB_W, TOP_BAR_H)) {
            page = PAGE_BANK;
            return true;
        }
        if (page == PAGE_MEMBER) return clickMember(mx, my) || super.universalMouseClicked(mx, my, btn);
        return clickBank(mx, my) || super.universalMouseClicked(mx, my, btn);
    }

    private boolean clickMember(double mx, double my) {
        int cx = contentX();
        int cw = contentW();
        int memberTier = ClientWalletAccount.getMemberTier();
        for (int i = 0; i < ShopMembership.tierCount(); i++) {
            int y = tierRowY(i);
            int btnW = 90;
            int btnX = cx + cw - 8 - btnW;
            if (GuiRenderUtil.isHovering(mx, my, btnX, y + 8, btnW, 16)) {
                if (memberTier < i) ShanhaiNetwork.CHANNEL.sendToServer(new ShopMembershipBuyPacket(i));
                return true;
            }
        }
        return false;
    }

    private boolean clickBank(double mx, double my) {
        int cx = contentX();
        int cw = contentW();
        long[] steps = {1000L, 10000L, 100000L, 1000000L};
        int sbw = (cw - 9) / 4;
        for (int i = 0; i < 4; i++) {
            if (GuiRenderUtil.isHovering(mx, my, cx + i * (sbw + 3), bankStepY(), sbw, 12)) {
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
        int allW = (cw - 8) / 3;
        if (GuiRenderUtil.isHovering(mx, my, cx, bankAllY(), allW, 14)) { send(ShopBankActionPacket.Op.DEPOSIT_ALL); return true; }
        if (GuiRenderUtil.isHovering(mx, my, cx + allW + 4, bankAllY(), allW, 14)) { send(ShopBankActionPacket.Op.WITHDRAW_ALL); return true; }
        if (GuiRenderUtil.isHovering(mx, my, cx + (allW + 4) * 2, bankAllY(), allW, 14)) { send(ShopBankActionPacket.Op.REPAY_ALL); return true; }
        return false;
    }

    private long lastMoneySendAtMs;

    private void send(ShopBankActionPacket.Op op) {
        long now = System.currentTimeMillis();
        if (now - lastMoneySendAtMs < 300L) return;
        lastMoneySendAtMs = now;
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

    private String fitSpark(String text, int maxPx) {
        if (maxPx <= 0 || this.font.width(text) <= maxPx) return text;
        return formatSci(ClientWalletAccount.getDigital());
    }

    private static String rateHint(ClientShopBank.Snapshot bank) {
        if (bank == null) return "§8利率查询中… 欠款无强制追讨，还款先冲利息";
        return "§8存款 " + rateText(bank.depositRateBp) + " · 贷款 " + rateText(bank.loanRateBp)
                + " · 还息优先 · 无强制追讨";
    }

    /** 基点转百分比。5 → 0.05%/小时，15 → 0.15%/小时。 */
    private static String rateText(int bp) {
        int safe = Math.max(0, bp);
        int whole = safe / 100;
        int frac = safe % 100;
        if (frac == 0) return whole + "%/小时";
        if (frac % 10 == 0) return whole + "." + (frac / 10) + "%/小时";
        String tail = frac < 10 ? "0" + frac : Integer.toString(frac);
        return whole + "." + tail + "%/小时";
    }

    private static String formatExact(BigInteger v) {
        if (v == null || v.signum() <= 0) return "0";
        String s = v.toString();
        if (s.length() > 24) return formatSci(v);
        StringBuilder out = new StringBuilder();
        int lead = s.length() % 3;
        if (lead == 0) lead = 3;
        out.append(s, 0, lead);
        for (int i = lead; i < s.length(); i += 3) {
            out.append(',');
            out.append(s, i, i + 3);
        }
        return out.toString();
    }

    private static String formatSci(BigInteger v) {
        if (v == null || v.signum() <= 0) return "0";
        String s = v.toString();
        if (s.length() <= 3) return s;
        return s.charAt(0) + "." + s.substring(1, 3) + "e" + (s.length() - 1);
    }
}
