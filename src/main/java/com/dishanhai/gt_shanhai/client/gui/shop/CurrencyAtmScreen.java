package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.common.shop.CurrencyRateConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.client.shop.ClientAeCurrencyBalance;
import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import com.dishanhai.gt_shanhai.network.CurrencyActionPacket;
import com.dishanhai.gt_shanhai.network.CurrencyAeBalanceRequestPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
    // 弹入动画节奏：跟 ShopScreen 系列界面统一（150ms ease-out），见 GuiRenderUtil#popScaleAt/popAnimProgress
    private static final long POP_ANIM_MS = 150L;
    // AE 网络余额预览节流：选中币种切换立即重新请求，之外每 40 刻（2秒）兜底刷新一次
    // （跟 ShopScreen#PREVIEW_REFRESH_TICKS 同一节奏，见 maybeRequestAeBalance）
    private static final long AE_BALANCE_REFRESH_TICKS = 40L;

    private final ShopScreen parent;
    private List<ResourceLocation> currencies = new ArrayList<>();
    private ResourceLocation selected;
    private int targetIdx = -1;   // 兑换目标币在 currencies 里的下标
    private long amount = 1L;
    private AnimatableEditBox amountBox;
    private int scroll = 0;

    // AE 网络余额预览往返节流状态（见 maybeRequestAeBalance）
    private ResourceLocation aeBalanceRequestedFor;
    private boolean aeBalanceRequestedAeMode;
    private long aeBalanceRequestedAtGameTime = Long.MIN_VALUE;

    private int left, top, panelWidth, panelHeight;

    private final long screenOpenAtMs = System.currentTimeMillis(); // 整个面板打开那一下的弹入动画起点
    private long detailSwitchAtMs;        // 切换选中货币那一下，右侧详情面板的弹入动画起点

    private static String flashText;      // 含 § 颜色码，与聊天框一致
    private static long flashUntil;       // System.currentTimeMillis() 到期时刻

    /** 把带 [货币中心] 前缀的系统消息镜像进本屏底部横幅（见 {@link ShopChatMirror}）。 */
    public static void showMessage(Component msg) {
        if (msg == null) return;
        flashText = msg.getString();
        flashUntil = System.currentTimeMillis() + 5000L;
    }

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
        amountBox = new AnimatableEditBox(this.font, cx, detailY(88), detailW() - 16, 12, Component.literal("数量"));
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
        // 整个面板打开那一下弹入一次（跟 ShopScreen 系列大图层同一套节奏，见 GuiRenderUtil#popScaleAt）
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
        // 面板
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 6, top + TOP_BAR_H + 6, left + panelWidth - 6, top + panelHeight - 6, PANEL_INNER);

        g.drawString(this.font, "§6货币中心 §7- ATM", left + 10, top + 5, GOLD, true);
        g.drawString(this.font, "§d星火 §e" + formatBig(ClientWalletAccount.getDigital()), left + 130, top + 5, WHITE, true);
        drawButton(g, backBtnX(), top + 4, BACK_W, TOP_BAR_H, "§e← 返回", mx, my);
        boolean ae = ShopScreen.isAeMode();
        g.drawString(this.font, ae ? "§aAE模式:开" : "§8AE模式:关", backBtnX() - 84, top + 5, ae ? GREEN : GRAY, true);

        drawList(g, mx, my);
        // 右侧详情面板：切换选中货币那一下弹入一次，同 ShopScreen 详情卡片切换的同一套手法——
        // amountBox 是 AnimatableEditBox，自己在 renderWidget 里套用同一锚点/进度做 PoseStack 缩放，
        // 这里只需每帧把动画进度源和锚点告诉它，不用再手动 setY/setAlpha 去追它（那套做法两次都出了
        // 布局错位，见反馈）。锚点须和 drawDetail 外面这层 popScaleAt 用同一个，视觉才同步。
        float detailT = GuiRenderUtil.popAnimProgress(detailSwitchAtMs, POP_ANIM_MS);
        float anchorCx = detailX() + detailW() / 2f, anchorCy = listTop() + listHeight() / 2f;
        if (amountBox != null) amountBox.setAnim(() -> detailT, anchorCx, anchorCy);
        if (detailT < 1f) {
            g.pose().pushPose();
            GuiRenderUtil.popScaleAt(g, anchorCx, anchorCy, detailT);
            drawDetail(g, mx, my);
            g.pose().popPose();
        } else {
            drawDetail(g, mx, my);
        }

        // 底部实时反馈横幅（[货币中心] 系统消息经 ShopChatMirror 镜像至此，聊天框仍保留）
        if (flashText != null && System.currentTimeMillis() < flashUntil) {
            // drawList/drawDetail 里的物品图标走 GuiGraphics 缓冲渲染层，不强制 flush 会在批次刷新时
            // "跳"到横幅上层盖住文字，同 ShopScreen 商店横幅一致的成因，见其对应注释。
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

    /**
     * AE 网络余额预览的服务端往返节流：选中币种切换立即重新请求，
     * 之外每 {@link #AE_BALANCE_REFRESH_TICKS} 刻兜底刷新一次（AE 网络存量变化不会主动推送）。
     * 非 AE 模式不用往返——界面本就不显示这行。
     */
    private void maybeRequestAeBalance() {
        boolean ae = ShopScreen.isAeMode();
        if (selected == null || !ae) return;
        net.minecraft.client.multiplayer.ClientLevel lvl = Minecraft.getInstance().level;
        long gameTime = lvl != null ? lvl.getGameTime() : 0L;
        // AE 模式关闭期间网络存量可能已变化，关闭前的缓存不能带回来当"新鲜"——重新开启后强制立即刷新一次
        boolean stale = !selected.equals(aeBalanceRequestedFor) || ae != aeBalanceRequestedAeMode
                || gameTime - aeBalanceRequestedAtGameTime >= AE_BALANCE_REFRESH_TICKS;
        if (!stale) return;
        aeBalanceRequestedFor = selected;
        aeBalanceRequestedAeMode = ae;
        aeBalanceRequestedAtGameTime = gameTime;
        ShanhaiNetwork.CHANNEL.sendToServer(new CurrencyAeBalanceRequestPacket(selected));
    }

    private void drawDetail(GuiGraphics g, int mx, int my) {
        int dx = detailX(), dy = listTop(), dw = detailW(), dh = listHeight();
        g.fill(dx, dy, dx + dw, dy + dh, BOX_BG);
        // 背景大块填充和后面的图标/文字/按钮走不同 RenderType（fill 用 positioncolor，物品图标/文字各自一套），
        // GuiGraphics 按类型分桶缓冲，实际提交 GPU 的顺序不一定等于调用顺序——不在这里强制 flush，深色背景块
        // 有几率在批次刷新时"晚于"图标/文字提交，同 Z（都是 0）下 LEQUAL 深度测试直接把内容盖成空白
        // （同 renderPanel 里 flashText 横幅注释描述的成因，那边靠 flush()+Z 平移根治，这里量级小只需 flush()）。
        g.flush();
        if (selected == null) {
            g.drawCenteredString(this.font, "§7← 选择货币", dx + dw / 2, dy + 18, GRAY);
            return;
        }
        maybeRequestAeBalance();
        int cx = dx + 8;
        Item item = ForgeRegistries.ITEMS.getValue(selected);
        if (item != null) g.renderItem(new ItemStack(item), cx, dy + 8);
        g.drawString(this.font, ShopPurchase.coinName(selected), cx + 22, dy + 8, WHITE, true);
        BigInteger bal = ClientWalletAccount.getCurrency(selected);
        g.drawString(this.font, "§7钱包余额: §e" + formatBig(bal), cx + 22, dy + 20, CYAN, true);
        boolean special = CurrencyRateConfig.isSpecial(selected);
        String valueLabel = special ? "§c特殊（不参与币值兑换）" : "§f" + CurrencyRateConfig.getValue(selected);
        g.drawString(this.font, "§7币值: " + valueLabel, cx + 22, dy + 32, GRAY, true);

        // 提交全部
        drawButton(g, cx, dy + 48, detailW() - 16, 14, "§a提交全部（背包→钱包）", mx, my);

        // 数量标签（amountBox 由 super.render 画在 dy+88）
        String amountLabel = "§7数量（可输入）:";
        g.drawString(this.font, amountLabel, cx, dy + 76, WHITE, true);
        // AE 网络余额（仅 AE 模式）：往返查询到之前先显示"查询中"，不瞎猜 0——好让玩家知道该输多少再点抽取
        // x 偏移按实际字宽算，不用固定数字——中文标签宽度跟字体/缩放走，硬编码偏移量在别的缩放比例下会重叠
        if (ShopScreen.isAeMode()) {
            Long aeBal = ClientAeCurrencyBalance.get(selected);
            String aeLabel = aeBal == null ? "§7AE网络余额: §8查询中…" : "§7AE网络余额: §b" + formatBig(BigInteger.valueOf(aeBal));
            g.drawString(this.font, aeLabel, cx + this.font.width(amountLabel) + 10, dy + 76, CYAN, true);
        }
        // 步进按钮（三排：+1/+10/+100/+1k，-1/-10/-100/-1k，×10/×100/÷10/÷100，同 ShopScreen 购买次数面板）
        long[] steps = {1, 10, 100, 1000};
        int bw = (detailW() - 16 - 9) / 4;
        String[] scaleLabels = {"§b×10", "§b×100", "§6÷10", "§6÷100"};
        for (int i = 0; i < 4; i++) {
            int bx = cx + i * (bw + 3);
            drawButton(g, bx, dy + 104, bw, 12, "§a+" + compactStep(steps[i]), mx, my);
            drawButton(g, bx, dy + 118, bw, 12, "§c-" + compactStep(steps[i]), mx, my);
            drawButton(g, bx, dy + 132, bw, 12, scaleLabels[i], mx, my);
        }

        // 百分比快填（读选中币种余额的百分比 → 数量框）
        String[] plabels = {"全部", "75%", "50%", "25%", "10%"};
        int pw = (detailW() - 16 - 12) / 5;
        for (int i = 0; i < 5; i++) {
            int px = cx + i * (pw + 3);
            drawButton(g, px, dy + 146, pw, 12, "§b" + plabels[i], mx, my);
        }

        // 百分比快填（AE 网络余额版）：同一行固定占位，未开 AE 模式/余额还没查到时不画按钮（跟下面"从 AE 抽取"
        // 按钮的显隐规则一致），避免玩家在余额未知时按下去却不知道填的是哪个数
        if (ShopScreen.isAeMode() && ClientAeCurrencyBalance.get(selected) != null) {
            String[] aePlabels = {"AE·全部", "AE·75%", "AE·50%", "AE·25%", "AE·10%"};
            for (int i = 0; i < 5; i++) {
                int px = cx + i * (pw + 3);
                drawButton(g, px, dy + 160, pw, 12, "§d" + aePlabels[i], mx, my);
            }
        }

        // AE 抽取（仅 AE 模式）
        boolean ae = ShopScreen.isAeMode();
        if (ae) {
            drawButton(g, cx, dy + 178, detailW() - 16, 14, "§b从 AE 抽取 " + formatBig(BigInteger.valueOf(amount)) + " 枚", mx, my);
        } else {
            g.drawString(this.font, "§8（开启 AE 模式后可从网络抽取）", cx, dy + 181, GRAY, true);
        }

        // 兑换区（特殊货币不参与币值兑换，改引导去兑换中心；下面这串固定 Y 坐标见 ShopScreen 同类注释的教训，
        // 插行/改行高必须连带下移 ey，不能只加行不挪 ey）
        int ey = dy + 200;
        if (special) {
            g.drawString(this.font, "§c特殊货币，不参与比例兑换/星火互转", cx, ey, GRAY, true);
            g.drawString(this.font, "§8请到「兑换中心」使用专属兑换表", cx, ey + 12, GRAY, true);
            drawButton(g, cx, ey + 28, detailW() - 16, 14, "§9→ 前往兑换中心", mx, my);
            // 提取实体币（钱包余额 → 背包实体币）
            drawButton(g, cx, ey + 50, detailW() - 16, 14,
                    bal.signum() > 0 ? "§6提取实体币 §7(钱包→背包，取 " + formatBig(BigInteger.valueOf(amount)) + " 枚)"
                            : "§8提取实体币（钱包余额为0）", mx, my);
            return;
        }
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

        // 币种 ↔ 星火（自动币值）
        long value = CurrencyRateConfig.getValue(selected);
        BigInteger toSpark = value > 0L ? BigInteger.valueOf(amount).multiply(BigInteger.valueOf(value)) : BigInteger.ZERO;
        drawButton(g, cx, ey + 62, detailW() - 16, 14,
                value > 0L ? "§d转成星火 §7(付 " + formatBig(BigInteger.valueOf(amount)) + " → §e+" + formatBig(toSpark) + "星火§7)"
                        : "§8转成星火（币值未配置）", mx, my);
        // 星火转出：amount = 想要的目标币数量（非花的星火数），花费 = amount × 币值
        BigInteger sparkNeeded = value > 0L ? BigInteger.valueOf(amount).multiply(BigInteger.valueOf(value)) : BigInteger.ZERO;
        boolean canFromDigital = value > 0L && ClientWalletAccount.getDigital().compareTo(sparkNeeded) >= 0;
        String fromDigitalLabel;
        if (canFromDigital) {
            fromDigitalLabel = "§d星火转出 §7(付 " + formatBig(sparkNeeded) + "星火 → §f+" + formatBig(BigInteger.valueOf(amount)) + "枚§7)";
        } else if (value <= 0L) {
            fromDigitalLabel = "§8星火转出（币值未配置）";
        } else {
            fromDigitalLabel = "§8星火转出（星火余额不足，需 " + formatBig(sparkNeeded) + "）";
        }
        drawButton(g, cx, ey + 78, detailW() - 16, 14, fromDigitalLabel, mx, my);

        // 提取实体币（钱包余额 → 背包实体币，装不下打包超级磁盘阵列）
        drawButton(g, cx, ey + 94, detailW() - 16, 14,
                bal.signum() > 0 ? "§6提取实体币 §7(钱包→背包，取 " + formatBig(BigInteger.valueOf(amount)) + " 枚)"
                        : "§8提取实体币（钱包余额为0）", mx, my);
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
                    ResourceLocation prev = selected;
                    selected = currencies.get(i);
                    if (!selected.equals(prev)) detailSwitchAtMs = System.currentTimeMillis(); // 换了不同货币才重播弹入
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
        long[] scales = {10, 100, 10, 100};
        int bw = (fullW - 9) / 4;
        for (int i = 0; i < 4; i++) {
            int bx = cx + i * (bw + 3);
            if (GuiRenderUtil.isHovering(mx, my, bx, dy + 104, bw, 12)) { amount = addClamp(amount, steps[i]); syncBox(); return true; }
            if (GuiRenderUtil.isHovering(mx, my, bx, dy + 118, bw, 12)) { amount = addClamp(amount, -steps[i]); syncBox(); return true; }
            if (GuiRenderUtil.isHovering(mx, my, bx, dy + 132, bw, 12)) {
                amount = i < 2 ? mulClamp(amount, scales[i]) : divClamp(amount, scales[i]);
                syncBox();
                return true;
            }
        }
        // 百分比快填（读选中币种余额百分比）
        int[] pcts = {100, 75, 50, 25, 10};
        int pw = (fullW - 12) / 5;
        for (int i = 0; i < 5; i++) {
            int px = cx + i * (pw + 3);
            if (GuiRenderUtil.isHovering(mx, my, px, dy + 146, pw, 12)) { setAmountPct(pcts[i]); return true; }
        }
        // 百分比快填（AE 网络余额版；同一行位置，未开 AE 模式/余额未知时不响应，跟 drawDetail 的显隐规则对齐）
        if (ShopScreen.isAeMode() && ClientAeCurrencyBalance.get(selected) != null) {
            for (int i = 0; i < 5; i++) {
                int px = cx + i * (pw + 3);
                if (GuiRenderUtil.isHovering(mx, my, px, dy + 160, pw, 12)) { setAmountAePct(pcts[i]); return true; }
            }
        }
        // AE 抽取
        if (ShopScreen.isAeMode() && GuiRenderUtil.isHovering(mx, my, cx, dy + 178, fullW, 14)) {
            send(CurrencyActionPacket.Op.AE_EXTRACT, selected, null, amount);
            ClientWalletAccount.optimisticAddCurrency(selected, BigInteger.valueOf(amount)); // 乐观预览：按输入量先加，服务端快照校正
            return true;
        }
        // 兑换区（特殊货币：仅"前往兑换中心" + 提取实体币；ey 必须跟 drawDetail 里的 ey 保持同一个值）
        int ey = dy + 200;
        if (CurrencyRateConfig.isSpecial(selected)) {
            if (GuiRenderUtil.isHovering(mx, my, cx, ey + 28, fullW, 14)) {
                Minecraft.getInstance().setScreen(new ExchangeScreen(parent, parent.canEdit()));
                return true;
            }
            if (GuiRenderUtil.isHovering(mx, my, cx, ey + 50, fullW, 14)) {
                send(CurrencyActionPacket.Op.WITHDRAW, selected, null, amount);
                BigInteger curBal = ClientWalletAccount.getCurrency(selected);
                if (curBal.signum() > 0) {
                    ClientWalletAccount.optimisticAddCurrency(selected, curBal.min(BigInteger.valueOf(amount)).negate());
                }
                return true;
            }
            return super.universalMouseClicked(mx, my, btn);
        }
        // 兑换目标切换 ◀ ▶
        if (GuiRenderUtil.isHovering(mx, my, cx, ey + 12, 14, 14)) { cycleTarget(-1); return true; }
        if (GuiRenderUtil.isHovering(mx, my, cx + fullW - 14, ey + 12, 14, 14)) { cycleTarget(1); return true; }
        // 兑换
        if (GuiRenderUtil.isHovering(mx, my, cx, ey + 42, fullW, 14)) {
            ResourceLocation target = targetIdx >= 0 && targetIdx < currencies.size() ? currencies.get(targetIdx) : null;
            if (target != null) {
                send(CurrencyActionPacket.Op.EXCHANGE, selected, target, amount);
                // 乐观预览：服务端按余额封顶成交（见 ShopPurchase#exchange），这里同样按余额封顶算 consumed 再预览，
                // 否则输入量超余额时（本就是要修的场景）乐观预览会被跳过，等服务端快照回来才更新，体验倒退
                BigInteger consumed = ClientWalletAccount.getCurrency(selected).min(BigInteger.valueOf(amount));
                long consumedLong = consumed.bitLength() < 63 ? consumed.longValue() : Long.MAX_VALUE;
                long gain = previewExchange(selected, target, consumedLong);
                if (gain > 0) {
                    ClientWalletAccount.optimisticAddCurrency(selected, consumed.negate());
                    ClientWalletAccount.optimisticAddCurrency(target, BigInteger.valueOf(gain));
                }
            }
            return true;
        }
        // 转成星火（币种 → 数字余额）
        long value = CurrencyRateConfig.getValue(selected);
        if (GuiRenderUtil.isHovering(mx, my, cx, ey + 62, fullW, 14)) {
            send(CurrencyActionPacket.Op.TO_DIGITAL, selected, null, amount);
            // 乐观预览：同样按余额封顶（见 WalletAccountAPI#convertCurrencyToDigital）
            if (value > 0L) {
                BigInteger consumed = ClientWalletAccount.getCurrency(selected).min(BigInteger.valueOf(amount));
                if (consumed.signum() > 0) {
                    ClientWalletAccount.optimisticAddCurrency(selected, consumed.negate());
                    ClientWalletAccount.optimisticAddDigital(consumed.multiply(BigInteger.valueOf(value)));
                }
            }
            return true;
        }
        // 星火转出（数字余额 → 币种，amount = 想要的目标币数量）
        if (GuiRenderUtil.isHovering(mx, my, cx, ey + 78, fullW, 14)) {
            send(CurrencyActionPacket.Op.FROM_DIGITAL, selected, null, amount);
            // 乐观预览：星火不足时按星火余额能兑的上限封顶（见 WalletAccountAPI#convertDigitalToCurrency）
            if (value > 0L) {
                BigInteger affordable = ClientWalletAccount.getDigital().divide(BigInteger.valueOf(value));
                BigInteger coins = affordable.min(BigInteger.valueOf(amount));
                if (coins.signum() > 0) {
                    ClientWalletAccount.optimisticAddDigital(coins.multiply(BigInteger.valueOf(value)).negate());
                    ClientWalletAccount.optimisticAddCurrency(selected, coins);
                }
            }
            return true;
        }
        // 提取实体币（钱包余额 → 背包）
        if (GuiRenderUtil.isHovering(mx, my, cx, ey + 94, fullW, 14)) {
            send(CurrencyActionPacket.Op.WITHDRAW, selected, null, amount);
            BigInteger curBal = ClientWalletAccount.getCurrency(selected);
            if (curBal.signum() > 0) {
                ClientWalletAccount.optimisticAddCurrency(selected, curBal.min(BigInteger.valueOf(amount)).negate());
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

    /** 百分比快填：把数量设为选中币种余额的 pct%（BigInteger 算，超 long 夹到 Long.MAX）。 */
    private void setAmountPct(int pct) {
        if (selected == null) return;
        BigInteger bal = ClientWalletAccount.getCurrency(selected);
        BigInteger v = bal.multiply(BigInteger.valueOf(pct)).divide(BigInteger.valueOf(100));
        amount = v.signum() <= 0 ? 1L : (v.bitLength() < 63 ? v.longValue() : Long.MAX_VALUE);
        if (amount < 1L) amount = 1L;
        syncBox();
    }

    /** 百分比快填（AE 网络余额版）：把数量设为选中币种「绑定在线 AE 网络」余额的 pct%；余额未知（未查询到）时不动。 */
    private void setAmountAePct(int pct) {
        if (selected == null) return;
        Long aeBal = ClientAeCurrencyBalance.get(selected);
        if (aeBal == null) return;
        BigInteger bal = BigInteger.valueOf(aeBal);
        BigInteger v = bal.multiply(BigInteger.valueOf(pct)).divide(BigInteger.valueOf(100));
        amount = v.signum() <= 0 ? 1L : (v.bitLength() < 63 ? v.longValue() : Long.MAX_VALUE);
        if (amount < 1L) amount = 1L;
        syncBox();
    }

    /** 数量乘法夹取到 [1, Long.MAX]，防乘法溢出（同 ShopScreen#mulClamp）。 */
    private static long mulClamp(long a, long factor) {
        if (factor <= 0) return a;
        if (a > Long.MAX_VALUE / factor) return Long.MAX_VALUE;
        return Math.max(1L, a * factor);
    }

    /** 数量除法夹取到 [1, Long.MAX]（整除向下取整，最小 1，同 ShopScreen#divClamp）。 */
    private static long divClamp(long a, long factor) {
        if (factor <= 0) return a;
        return Math.max(1L, a / factor);
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
