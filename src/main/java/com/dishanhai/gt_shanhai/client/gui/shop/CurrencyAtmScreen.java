package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.common.shop.CurrencyRateConfig;
import com.dishanhai.gt_shanhai.common.shop.CurrencyStageConfig;
import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.client.shop.ClientAeCurrencyBalance;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUnlockState;
import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import com.dishanhai.gt_shanhai.network.CurrencyActionPacket;
import com.dishanhai.gt_shanhai.network.CurrencyAeBalanceRequestPacket;
import com.dishanhai.gt_shanhai.network.CurrencyQuickSellPacket;
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
 * 币种兑换（按 {@link CurrencyRateConfig} 汇率）、AE 自动结算（AE 模式下网络余额可直接参与）。
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
    private static final int HINT_H = 36;
    private static final long HINT_ROTATE_MS = 5000L;
    // 弹入动画节奏：跟 ShopScreen 系列界面统一（150ms ease-out），见 GuiRenderUtil#popScaleAt/popAnimProgress
    private static final long POP_ANIM_MS = GuiRenderUtil.MOTION_MS;
    // AE 网络余额预览节流：选中币种切换立即重新请求，之外每 40 刻（2秒）兜底刷新一次
    // （跟 ShopScreen#PREVIEW_REFRESH_TICKS 同一节奏，见 maybeRequestAeBalance）
    private static final long AE_BALANCE_REFRESH_TICKS = 40L;

    private final ShopScreen parent;
    private final ResourceLocation initialFocus;
    private boolean appliedInitialFocus;
    private List<ResourceLocation> currencies = new ArrayList<>();
    private ResourceLocation selected;
    private int targetIdx = -1;   // 兑换目标币在 currencies 里的下标
    private boolean ratesLoadedOnce; // 打开后只读一次盘（initScaled 每次 resize 都重跑，见其注释）
    private long amount = 1L;
    private AnimatableEditBox amountBox;
    private int scroll = 0;

    // AE 网络余额预览往返节流状态（见 maybeRequestAeBalance）
    private long aeBalanceRequestedAtGameTime = Long.MIN_VALUE;

    private int left, top, panelWidth, panelHeight;

    private final long screenOpenAtMs = System.currentTimeMillis();
    private List<Component> hoverTip;     // 本帧悬停到的操作说明，renderTooltips 消费

    private static String flashText;      // 含 § 颜色码，与聊天框一致
    private static long flashUntil;       // System.currentTimeMillis() 到期时刻

    /** 把带 [货币中心] 前缀的系统消息镜像进本屏底部横幅（见 {@link ShopChatMirror}）。 */
    public static void showMessage(Component msg) {
        if (msg == null) return;
        flashText = msg.getString();
        flashUntil = System.currentTimeMillis() + 5000L;
    }

    public CurrencyAtmScreen(ShopScreen parent) {
        this(parent, null);
    }

    public CurrencyAtmScreen(ShopScreen parent, ResourceLocation initialFocus) {
        super(Component.literal("货币中心"));
        this.parent = parent;
        this.initialFocus = initialFocus;
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
    /** 左侧币种列高度。底部留两行操作提示，不挤占右侧详情里已经排满的按钮。 */
    private int listHeight() { return panelHeight - TOP_BAR_H - 18 - HINT_H; }
    private int detailHeight() { return panelHeight - TOP_BAR_H - 18; }
    private int detailX() { return listLeft() + LIST_W + 8; }
    private int detailW() { return left + panelWidth - 8 - detailX(); }
    private int backBtnX() { return left + panelWidth - 8 - BACK_W; }

    @Override
    protected void initScaled() {
        left = 4;
        top = 8;
        panelWidth = Math.max(560, vWidth - 8);
        panelHeight = Math.max(360, vHeight - 16);

        // 只在本次打开首次 init 时读盘：initScaled 每次 resize 都会重跑，无条件 reload 等于 resize 反复走文件 IO
        if (!ratesLoadedOnce) {
            CurrencyRateConfig.reload();
            ratesLoadedOnce = true;
        }
        currencies = CurrencyRateConfig.getCurrencies();
        applyInitialFocus();
        if (selected == null && !currencies.isEmpty()) selected = currencies.get(0);
        if ((targetIdx < 0 || targetIdx >= currencies.size()) && currencies.size() > 1) targetIdx = firstDifferent(0);

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

    /** 从悬停的 GT 币跳进来时，选中该币并把列表滚到它。只应用一次，避免缩放窗口时把玩家滚走的位置拉回去。 */
    private void applyInitialFocus() {
        if (appliedInitialFocus || initialFocus == null) return;
        appliedInitialFocus = true;
        int idx = currencies.indexOf(initialFocus);
        if (idx < 0) return;
        selected = currencies.get(idx);
        targetIdx = currencies.size() > 1 ? firstDifferent(idx) : -1;
        int contentH = currencies.size() * ROW_H + 4;
        int maxScroll = Math.max(0, contentH - listHeight());
        scroll = Math.max(0, Math.min(maxScroll, idx * ROW_H));
    }

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
        float openT = GuiRenderUtil.popAnimProgress(screenOpenAtMs, POP_ANIM_MS);
        if (amountBox != null) {
            amountBox.setVisible(selected != null);
            amountBox.setAnim(() -> GuiRenderUtil.popAnimProgress(screenOpenAtMs, POP_ANIM_MS),
                    left + panelWidth / 2f, top + panelHeight / 2f);
            amountBox.setSlideX(() -> 0f);
        }
        GuiRenderUtil.withPopScale(g, left + panelWidth / 2f, top + panelHeight / 2f, openT,
                () -> renderPanel(g, mx, my));
    }

    private void renderPanel(GuiGraphics g, int mx, int my) {
        hoverTip = null;
        // 面板
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 6, top + TOP_BAR_H + 6, left + panelWidth - 6, top + panelHeight - 6, PANEL_INNER);

        g.drawString(this.font, "§6货币中心 §7- ATM", left + 10, top + 5, GOLD, true);
        String sparkLabel = "§d星火 §e" + formatBig(ClientWalletAccount.getDigital());
        g.drawString(this.font, sparkLabel, left + 130, top + 5, WHITE, true);
        armTip(mx, my, left + 130, top + 2, this.font.width(sparkLabel), 12,
                "星火", "数字余额。普通货币可以按币值转成星火，也可以花星火换回货币。");
        drawButton(g, backBtnX(), top + 4, BACK_W, TOP_BAR_H, "§e← 返回", mx, my,
                "返回商店", "回到山海商店。AE 模式要在商店顶栏开关，这里只显示当前状态。");
        boolean ae = ShopScreen.isAeMode();
        String aeLabel = ae ? "§aAE模式:开" : "§8AE模式:关";
        int aeX = backBtnX() - 84;
        g.drawString(this.font, aeLabel, aeX, top + 5, ae ? GREEN : GRAY, true);
        armTip(mx, my, aeX, top + 2, this.font.width(aeLabel), 12,
                "AE 模式", ae
                        ? "当前已开启。快速售出和转成星火会先从绑定网络吸入货币，百分比也会把网络余额算进去。"
                        : "当前关闭。要开启请回商店顶栏点 AE。开启前需要已绑定的在线商店终端或 FTBQ 提交器。");

        drawList(g, mx, my);
        drawOperationHints(g);
        // 详情列一直在。换币种只换文字，不再次缩放。
        drawDetail(g, mx, my);

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
        if (currencies.isEmpty()) {
            g.drawCenteredString(this.font, "§7还没有货币", lx + lw / 2, ly + 18, GRAY);
            g.drawCenteredString(this.font, "§8检查 currency_rates.json", lx + lw / 2, ly + 32, GRAY);
        }
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
            String badge = stageBadge(cur);
            int badgeW = badge.isEmpty() ? 0 : this.font.width(badge) + 4;
            String name = GuiRenderUtil.trimText(this.font, ShopPurchase.coinName(cur), lw - 28 - badgeW);
            g.drawString(this.font, name, lx + 22, ry + 2, sel ? GOLD : WHITE, true);
            if (!badge.isEmpty()) {
                g.drawString(this.font, badge, lx + lw - 5 - this.font.width(badge), ry + 2, GRAY, true);
            }
            BigInteger bal = ClientWalletAccount.getCurrency(cur);
            Long aeBal = ClientAeCurrencyBalance.get(cur);
            String balanceLabel = "§7钱包 §e" + formatBig(bal);
            if (aeBal != null) {
                balanceLabel += " §bAE §e" + formatBig(BigInteger.valueOf(aeBal));
            } else {
                balanceLabel += " §8AE …";
            }
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, balanceLabel, lw - 28),
                    lx + 22, ry + 11, GRAY, true);
            armCurrencyTip(mx, my, lx + 1, ry, lw - 2, ROW_H - 1, cur, bal, aeBal);
        }
        g.disableScissor();
    }

    private void armCurrencyTip(int mx, int my, int x, int y, int w, int h,
                                ResourceLocation cur, BigInteger bal, Long aeBal) {
        if (hoverTip != null || !GuiRenderUtil.isHovering(mx, my, x, y, w, h)) return;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§6" + ShopPurchase.coinName(cur)));
        lines.add(Component.literal("§7钱包余额: §f" + bal.toString()));
        lines.add(Component.literal("§7AE 网络: §f" + (aeBal == null ? "查询中" : aeBal)));
        if (CurrencyRateConfig.isSpecial(cur)) {
            lines.add(Component.literal("§c特殊货币，不能按币值兑换或转星火"));
            lines.add(Component.literal("§7可以存进钱包、提取回背包。兑换去兑换中心。"));
        } else {
            long face = CurrencyRateConfig.getValue(cur);
            long spark = CurrencyRateConfig.sparkValue(cur);
            lines.add(Component.literal("§7面值: §f" + face));
            lines.add(Component.literal(spark == face
                    ? "§7兑星火: §f" + spark + " §8(原价)"
                    : "§7兑星火: §e" + spark + " §8(高阶折损，面值每×4星火只×2)"));
            lines.add(Component.literal("§8左键选中后，可以提交、兑换、转星火或提取"));
        }
        appendStageTip(lines, cur);
        hoverTip = lines;
    }

    /**
     * 币种阶段锁展示：货币名右侧的短标记只给阶段名和红绿状态，
     * 这里补全阶段路径，并在未解锁时列出该阶段要一次性提交的固定物品，免得玩家点下去才发现被拒。
     */
    private static void appendStageTip(List<Component> lines, ResourceLocation cur) {
        String path = CurrencyStageConfig.stagePath(cur);
        if (path.isEmpty()) return;
        String missing = CurrencyStageConfig.missingStageClient(cur);
        lines.add(Component.literal("§7阶段: §f" + path + (missing == null ? " §a已解锁" : " §c未解锁")));
        if (missing == null) return;
        if (!missing.equals(path)) lines.add(Component.literal("§7当前卡在: §f" + missing));
        List<ExchangeEntry.Ingredient> requirements = ClientShopUnlockState.stageRequirements(missing);
        if (requirements.isEmpty()) return;
        lines.add(Component.literal("§c兑换/转星火前需一次性提交:"));
        int shown = Math.min(6, requirements.size());
        for (int i = 0; i < shown; i++) {
            ExchangeEntry.Ingredient ingredient = requirements.get(i);
            ItemStack stack = ingredient.makeUnitStack();
            String name = stack.isEmpty() ? ingredient.id.toString() : stack.getHoverName().getString();
            lines.add(Component.literal("§8· §f" + name + " §7×" + ingredient.count));
        }
        if (requirements.size() > shown) {
            lines.add(Component.literal("§8… 共 " + requirements.size() + " 种"));
        }
    }

    /** 货币名右侧的阶段短标记（已解锁灰色、未解锁红色）；未配置阶段返回空串。 */
    private static String stageBadge(ResourceLocation currency) {
        String path = CurrencyStageConfig.stagePath(currency);
        if (path.isEmpty()) return "";
        String tier = path;
        int slash = path.lastIndexOf('/');
        if (slash >= 0) tier = path.substring(slash + 1);
        boolean locked = CurrencyStageConfig.missingStageClient(currency) != null;
        return (locked ? "§c" : "§7") + tier.toUpperCase(java.util.Locale.ROOT) + (locked ? "§c锁" : "");
    }

    /** 详情页货币名后面的阶段路径 + 解锁状态；悬停可看该阶段要提交的物品。 */
    private void drawStageInfo(GuiGraphics g, int mx, int my, int x, int y, ResourceLocation currency) {
        String path = CurrencyStageConfig.stagePath(currency);
        if (path.isEmpty()) return;
        String missing = CurrencyStageConfig.missingStageClient(currency);
        String status = missing == null ? "§a已解锁" : "§c未解锁";
        int avail = Math.max(40, detailX() + detailW() - 12 - x);
        String prefix = "§8| §7阶段 " + status + " §f";
        String text = prefix + GuiRenderUtil.trimText(this.font, path, Math.max(8, avail - this.font.width(prefix)));
        g.drawString(this.font, text, x, y, GRAY, true);
        if (hoverTip != null || !GuiRenderUtil.isHovering(mx, my, x, y - 1, this.font.width(text), 12)) return;
        List<Component> lines = new ArrayList<>();
        appendStageTip(lines, currency);
        if (!lines.isEmpty()) hoverTip = lines;
    }

    private void drawOperationHints(GuiGraphics g) {
        List<String> hints = operationHints();
        if (hints.isEmpty()) return;
        int base = (int) ((System.currentTimeMillis() / HINT_ROTATE_MS) % hints.size());
        int x = listLeft();
        int y = listTop() + listHeight() + 1;
        String page = "§8" + (base + 1) + "/" + hints.size();
        int pageW = this.font.width(page);
        int maxW = Math.max(8, LIST_W - pageW - 6);
        int lines = Math.min(2, hints.size());
        for (int i = 0; i < lines; i++) {
            String text = GuiRenderUtil.trimText(this.font, "§8" + hints.get((base + i) % hints.size()), maxW);
            g.drawString(this.font, text, x, y + i * 10, GRAY, false);
        }
        g.drawString(this.font, page, x + LIST_W - pageW, y, GRAY, false);
    }

    private List<String> operationHints() {
        List<String> hints = new ArrayList<>();
        boolean special = selected != null && CurrencyRateConfig.isSpecial(selected);
        if (special) {
            hints.add("这种是特殊货币，不能按币值兑换");
            hints.add("比例兑换请去兑换中心");
        }
        if (selected != null && !CurrencyStageConfig.stagePath(selected).isEmpty()) {
            hints.add("币名右侧是阶段锁，未解锁时兑换/转星火会被拒");
        }
        hints.add("左键左侧货币，再做存入或提取");
        hints.add("提交全部：背包里的这种币进钱包");
        hints.add("提取实体币：钱包余额回到背包");
        hints.add("数量框决定兑换、转星火和提取");
        hints.add("全部和百分比按当前余额填数量");
        if (!special) {
            hints.add("兑换：按兑星火价换成左右箭头选的币");
            hints.add("白金及以下原价兑星火，更高的币会折损");
            hints.add("转成星火，或用星火按同一价格转回");
            hints.add("快速售出：可计价的币全部换星火");
        }
        hints.add("AE 模式在商店顶栏开关");
        return hints;
    }

    private void drawEmptyGuide(GuiGraphics g, int dx, int dy, int dw, int dh) {
        int x = dx + 8;
        int maxW = Math.max(8, dw - 16);
        int y = dy + 8;
        int limit = dy + dh - 12;
        for (String line : emptyGuideLines()) {
            if (y > limit) break;
            if (line.isEmpty()) {
                y += 4;
                continue;
            }
            g.drawString(this.font, GuiRenderUtil.trimText(this.font, line, maxW), x, y, GRAY, false);
            y += 11;
        }
    }

    private static List<String> emptyGuideLines() {
        List<String> lines = new ArrayList<>();
        lines.add("§6怎么用货币中心");
        lines.add("§f1.§7 左键点左侧一种货币");
        lines.add("§f2.§7 提交全部，把背包里的币存进钱包");
        lines.add("§f3.§7 改数量后，再兑换、转星火或提取");
        lines.add("");
        lines.add("§6每个按钮做什么");
        lines.add("§e提交全部");
        lines.add("§7只存当前这一种，背包实体币进钱包");
        lines.add("§e提取实体币");
        lines.add("§7按数量从钱包取回背包");
        lines.add("§e兑换");
        lines.add("§7按两边兑星火价互换，不能先拆成铜币绕过折损");
        lines.add("§e转成星火 / 星火转出");
        lines.add("§7白金及以下原价。更高的币面值每×4，星火只×2");
        lines.add("§e快速售出");
        lines.add("§7所有配了币值的货币一次换成星火");
        lines.add("§e特殊货币");
        lines.add("§7不能按比例换，请去兑换中心");
        lines.add("§eAE 模式");
        lines.add("§7在商店顶栏开关，这里只显示状态");
        return lines;
    }

    /**
     * AE 网络余额预览的服务端往返节流：界面打开后立即请求全部币种，
     * 之外每 {@link #AE_BALANCE_REFRESH_TICKS} 刻兜底刷新一次（AE 网络存量变化不会主动推送）。
     */
    private void maybeRequestAeBalance() {
        net.minecraft.client.multiplayer.ClientLevel lvl = Minecraft.getInstance().level;
        long gameTime = lvl != null ? lvl.getGameTime() : 0L;
        boolean stale = aeBalanceRequestedAtGameTime == Long.MIN_VALUE
                || gameTime - aeBalanceRequestedAtGameTime >= AE_BALANCE_REFRESH_TICKS;
        if (!stale) return;
        aeBalanceRequestedAtGameTime = gameTime;
        ShanhaiNetwork.CHANNEL.sendToServer(new CurrencyAeBalanceRequestPacket());
    }

    private void drawDetail(GuiGraphics g, int mx, int my) {
        int dx = detailX(), dy = listTop(), dw = detailW(), dh = detailHeight();
        g.fill(dx, dy, dx + dw, dy + dh, BOX_BG);
        // 背景大块填充和后面的图标/文字/按钮走不同 RenderType（fill 用 positioncolor，物品图标/文字各自一套），
        // GuiGraphics 按类型分桶缓冲，实际提交 GPU 的顺序不一定等于调用顺序——不在这里强制 flush，深色背景块
        // 有几率在批次刷新时"晚于"图标/文字提交，同 Z（都是 0）下 LEQUAL 深度测试直接把内容盖成空白
        // （同 renderPanel 里 flashText 横幅注释描述的成因，那边靠 flush()+Z 平移根治，这里量级小只需 flush()）。
        g.flush();
        if (selected == null) {
            drawEmptyGuide(g, dx, dy, dw, dh);
            return;
        }
        maybeRequestAeBalance();
        int cx = dx + 8;
        Item item = ForgeRegistries.ITEMS.getValue(selected);
        if (item != null) g.renderItem(new ItemStack(item), cx, dy + 8);
        String selectedName = ShopPurchase.coinName(selected);
        g.drawString(this.font, selectedName, cx + 22, dy + 8, WHITE, true);
        drawStageInfo(g, mx, my, cx + 22 + this.font.width(selectedName) + 10, dy + 8, selected);
        BigInteger bal = ClientWalletAccount.getCurrency(selected);
        Long aeBal = ClientAeCurrencyBalance.get(selected);
        g.drawString(this.font, "§7钱包余额: §e" + formatBig(bal), cx + 22, dy + 20, CYAN, true);
        g.drawString(this.font, "§7AE网络余额: §b" + (aeBal == null ? "查询中…" : formatBig(BigInteger.valueOf(aeBal))),
                cx + 22, dy + 32, CYAN, true);
        boolean special = CurrencyRateConfig.isSpecial(selected);
        long face = special ? 0L : CurrencyRateConfig.getValue(selected);
        long sparkRate = special ? 0L : CurrencyRateConfig.sparkValue(selected);
        String valueText = special ? "§7币值: §c特殊（不参与币值兑换）"
                : "§7币值: §f" + face + (sparkRate == face ? " §8→星火同价" : " §7→星火 §e" + sparkRate);
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, valueText, Math.max(8, detailW() - 30)),
                cx + 22, dy + 44, GRAY, true);
        if (!special) {
            armTip(mx, my, cx + 22, dy + 42, detailW() - 36, 12,
                    "兑星火", "面值 " + face + "，兑星火 " + sparkRate
                            + "。白金及以下（面值≤256）原价。再往上每升一档面值×4，星火只×2。币种互换也按兑星火价，先拆成铜币再转不会更多。");
        }

        // 提交全部
        drawButton(g, cx, dy + 56, detailW() - 16, 14, "§a提交全部（背包→钱包）", mx, my,
                "提交全部", "把背包里这一种货币的实体币全部存进钱包。其他币种不动。");

        // 数量标签（amountBox 由 super.render 画在 dy+88）
        String amountLabel = "§7数量（可输入）:";
        g.drawString(this.font, amountLabel, cx, dy + 76, WHITE, true);
        armTip(mx, my, cx, dy + 74, this.font.width(amountLabel), 12,
                "数量", "这一格决定兑换付出多少、转成星火花多少、星火转出想得到多少，以及提取多少枚。");
        // AE 网络余额：独立显示在货币详情中；转星火时是否自动使用由 AE 模式决定
        // x 偏移按实际字宽算，不用固定数字——中文标签宽度跟字体/缩放走，硬编码偏移量在别的缩放比例下会重叠
        // 步进按钮（三排：+1/+10/+100/+1k，-1/-10/-100/-1k，×10/×100/÷10/÷100，同 ShopScreen 购买次数面板）
        long[] steps = {1, 10, 100, 1000};
        int bw = (detailW() - 16 - 9) / 4;
        String[] scaleLabels = {"§b×10", "§b×100", "§6÷10", "§6÷100"};
        String[] stepTips = {"1", "10", "100", "1000"};
        for (int i = 0; i < 4; i++) {
            int bx = cx + i * (bw + 3);
            drawButton(g, bx, dy + 104, bw, 12, "§a+" + compactStep(steps[i]), mx, my,
                    "增加数量", "给上面的数量加上 " + stepTips[i] + "。");
            drawButton(g, bx, dy + 118, bw, 12, "§c-" + compactStep(steps[i]), mx, my,
                    "减少数量", "给上面的数量减去 " + stepTips[i] + "，最少保留 1。");
            drawButton(g, bx, dy + 132, bw, 12, scaleLabels[i], mx, my,
                    i < 2 ? "放大数量" : "缩小数量",
                    i < 2 ? "把数量乘上 " + (i == 0 ? "10" : "100") + "。"
                            : "把数量除以 " + (i == 2 ? "10" : "100") + "，最少保留 1。");
        }

        // 百分比快填（读选中币种余额的百分比 → 数量框）
        String[] plabels = {"全部", "75%", "50%", "25%", "10%"};
        String[] pctTips = {"100%", "75%", "50%", "25%", "10%"};
        int pw = (detailW() - 16 - 12) / 5;
        String pctScope = ShopScreen.isAeMode() ? "钱包余额加上已查到的 AE 网络余额" : "钱包余额";
        for (int i = 0; i < 5; i++) {
            int px = cx + i * (pw + 3);
            drawButton(g, px, dy + 146, pw, 12, "§b" + plabels[i], mx, my,
                    "按" + pctTips[i] + "填数量", "用当前货币" + pctScope + "的 " + pctTips[i] + " 填进数量框。");
        }

        // 快速售出：服务端按币值处理全部内部余额；AE 模式开启时自动先吸入 AE 同类余额
        drawButton(g, cx, dy + 160, detailW() - 16, 14,
                ShopScreen.isAeMode() ? "§d快速售出全部货币（含 AE）" : "§d快速售出全部货币（钱包）", mx, my,
                "快速售出", ShopScreen.isAeMode()
                        ? "把所有配了币值的货币按兑星火价换成星火。白金以上会折损。会先从绑定网络吸入，再连钱包余额一起换。特殊货币不动。"
                        : "把钱包里所有配了币值的货币按兑星火价一次换成星火。白金以上会折损。特殊货币留在钱包里。");

        // 兑换区（特殊货币不参与币值兑换，改引导去兑换中心；下面这串固定 Y 坐标见 ShopScreen 同类注释的教训，
        // 插行/改行高必须连带下移 ey，不能只加行不挪 ey）
        int ey = dy + 182;
        if (special) {
            g.drawString(this.font, "§c特殊货币，不参与比例兑换/星火互转", cx, ey, GRAY, true);
            g.drawString(this.font, "§8请到「兑换中心」使用专属兑换表", cx, ey + 12, GRAY, true);
            drawButton(g, cx, ey + 28, detailW() - 16, 14, "§9→ 前往兑换中心", mx, my,
                    "前往兑换中心", "特殊货币不走币值比例。兑换中心按单独配的兑换表交换。");
            // 提取实体币（钱包余额 → 背包实体币）
            drawButton(g, cx, ey + 50, detailW() - 16, 14,
                    bal.signum() > 0 ? "§6提取实体币 §7(钱包→背包，取 " + formatBig(BigInteger.valueOf(amount)) + " 枚)"
                            : "§8提取实体币（钱包余额为0）", mx, my,
                    "提取实体币", withdrawTip(bal));
            return;
        }
        g.drawString(this.font, "§7兑换目标:", cx, ey, WHITE, true);
        ResourceLocation target = targetIdx >= 0 && targetIdx < currencies.size() ? currencies.get(targetIdx) : null;
        // ◀ 目标币 ▶
        drawButton(g, cx, ey + 12, 14, 14, "§e◀", mx, my,
                "上一个目标", "换成清单里前一种可以兑换的货币。不会选中当前正在付出的这一种。");
        String tname = target == null ? "§8无" : GuiRenderUtil.trimText(this.font, ShopPurchase.coinName(target), detailW() - 90);
        g.drawString(this.font, tname, cx + 20, ey + 16, GOLD, true);
        if (target != null) {
            String targetBadge = stageBadge(target);
            if (!targetBadge.isEmpty()) {
                g.drawString(this.font, targetBadge, cx + 26 + this.font.width(tname), ey + 16, GRAY, true);
            }
        }
        drawButton(g, cx + detailW() - 16 - 14, ey + 12, 14, 14, "§e▶", mx, my,
                "下一个目标", "换成清单里后一种可以兑换的货币。");
        // 预计得到
        long gain = previewExchange(selected, target, amount);
        g.drawString(this.font, "§7预计得到: §a" + formatBig(BigInteger.valueOf(gain)) + " §7"
                + (target == null ? "" : ShopPurchase.coinName(target)), cx, ey + 30, GREEN, true);
        drawButton(g, cx, ey + 42, detailW() - 16, 14,
                gain > 0 ? "§a兑换" : "§8兑换（数量太小/无目标）", mx, my,
                "兑换", "付出数量框里的当前货币，按两边兑星火价换成目标货币。得到的数量显示在按钮上方。");

        // 币种 ↔ 星火（兑星火价，白金以上折损）
        long value = sparkRate;
        BigInteger toSpark = value > 0L ? BigInteger.valueOf(amount).multiply(BigInteger.valueOf(value)) : BigInteger.ZERO;
        drawButton(g, cx, ey + 62, detailW() - 16, 14,
                value > 0L ? "§d转成星火 " + (ShopScreen.isAeMode() ? "§7(钱包+AE)" : "§7(钱包)")
                        + " §7(付 " + formatBig(BigInteger.valueOf(amount)) + " → §e+" + formatBig(toSpark) + "星火§7)"
                        : "§8转成星火（币值未配置）", mx, my,
                "转成星火", ShopScreen.isAeMode()
                        ? "按兑星火价把数量换成星火。钱包不够时，会从绑定网络补这一种货币。白金以上会折损。"
                        : "按兑星火价把钱包里的数量换成星火。白金及以下等于面值，更高的币会折损。");
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
        drawButton(g, cx, ey + 78, detailW() - 16, 14, fromDigitalLabel, mx, my,
                "星火转出", "数量是想得到的货币枚数。花费的星火等于枚数乘兑星火价，和转成星火是同一价格。星火不够时按余额能换的上限成交。");

        // 提取实体币（钱包余额 → 背包实体币，装不下打包超级磁盘阵列）
        drawButton(g, cx, ey + 94, detailW() - 16, 14,
                bal.signum() > 0 ? "§6提取实体币 §7(钱包→背包，取 " + formatBig(BigInteger.valueOf(amount)) + " 枚)"
                        : "§8提取实体币（钱包余额为0）", mx, my,
                "提取实体币", withdrawTip(bal));
    }

    private static String withdrawTip(BigInteger bal) {
        if (bal.signum() <= 0) return "钱包里没有这种货币，先提交背包里的实体币。";
        return "按数量从钱包取出实体币发到背包。达到打包阈值或背包装不下时，会收成超级磁盘阵列。";
    }

    /** 客户端预览兑换结果（服务端最终裁定）。 */
    private static long previewExchange(ResourceLocation from, ResourceLocation to, long amount) {
        if (from == null || to == null || from.equals(to) || amount <= 0) return 0;
        long fv = CurrencyRateConfig.sparkValue(from), tv = CurrencyRateConfig.sparkValue(to);
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
                    // targetIdx 上界也要挡：resize 重载 currency_rates.json 后 currencies 可能变短，残留旧下标会越界
                    if (targetIdx < 0 || targetIdx >= currencies.size()
                            || currencies.get(targetIdx).equals(selected)) targetIdx = firstDifferent(i);
                    return true;
                }
            }
        }
        if (selected == null) return super.universalMouseClicked(mx, my, btn);

        int dx = detailX(), dy = listTop(), cx = dx + 8;
        int fullW = detailW() - 16;
        // 提交全部
        if (GuiRenderUtil.isHovering(mx, my, cx, dy + 56, fullW, 14)) {
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
        // 快速售出全部货币：服务端按当前 AE 模式决定是否先吸入 AE 余额
        if (GuiRenderUtil.isHovering(mx, my, cx, dy + 160, fullW, 14)) {
            sendQuickSell();
            return true;
        }
        // 兑换区（特殊货币：仅"前往兑换中心" + 提取实体币；ey 必须跟 drawDetail 里的 ey 保持同一个值）
        int ey = dy + 182;
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
        // 转成星火（币种 → 数字余额，白金以上按兑星火价折损）
        long value = CurrencyRateConfig.sparkValue(selected);
        if (GuiRenderUtil.isHovering(mx, my, cx, ey + 62, fullW, 14)) {
            boolean includeAe = ShopScreen.isAeMode();
            send(CurrencyActionPacket.Op.TO_DIGITAL, selected, null, amount, includeAe);
            // 乐观预览：AE 模式下按“钱包 + 已知 AE 缓存”封顶，服务端权威快照会校正。
            if (value > 0L) {
                BigInteger walletBal = ClientWalletAccount.getCurrency(selected);
                Long aeLong = includeAe ? ClientAeCurrencyBalance.get(selected) : null;
                BigInteger aeBal = aeLong == null ? BigInteger.ZERO : BigInteger.valueOf(aeLong);
                BigInteger consumed = walletBal.add(aeBal).min(BigInteger.valueOf(amount));
                if (consumed.signum() > 0) {
                    BigInteger walletConsumed = walletBal.min(consumed);
                    BigInteger aeConsumed = consumed.subtract(walletConsumed);
                    ClientWalletAccount.optimisticAddCurrency(selected, walletConsumed.negate());
                    if (aeConsumed.signum() > 0 && aeConsumed.bitLength() < 63) {
                        ClientAeCurrencyBalance.optimisticAdd(selected, -aeConsumed.longValue());
                    }
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

    // 兑换/转换/提取都是非幂等的资金操作且点击后不关屏：300ms 内的第二次点击按误触双击吞掉，
    // 防止手滑一次双击变成两笔交易；正常连续操作不受影响
    private long lastMoneySendAtMs;

    private void send(CurrencyActionPacket.Op op, ResourceLocation currency, ResourceLocation target, long amt) {
        send(op, currency, target, amt, false);
    }

    private void send(CurrencyActionPacket.Op op, ResourceLocation currency, ResourceLocation target,
                      long amt, boolean includeAe) {
        long now = System.currentTimeMillis();
        if (now - lastMoneySendAtMs < 300L) return;
        lastMoneySendAtMs = now;
        ShanhaiNetwork.CHANNEL.sendToServer(new CurrencyActionPacket(op, currency, target, amt, includeAe));
    }

    private void sendQuickSell() {
        long now = System.currentTimeMillis();
        if (now - lastMoneySendAtMs < 300L) return;
        lastMoneySendAtMs = now;
        aeBalanceRequestedAtGameTime = Long.MIN_VALUE;
        ShanhaiNetwork.CHANNEL.sendToServer(new CurrencyQuickSellPacket(ShopScreen.isAeMode()));
    }

    private void syncBox() {
        if (amountBox != null) amountBox.setValue(Long.toString(amount));
    }

    /** 百分比快填：AE 模式下按钱包+AE 合计余额计算，其他情况只按钱包余额计算。 */
    private void setAmountPct(int pct) {
        if (selected == null) return;
        BigInteger bal = ClientWalletAccount.getCurrency(selected);
        if (ShopScreen.isAeMode()) {
            Long aeBal = ClientAeCurrencyBalance.get(selected);
            if (aeBal != null) bal = bal.add(BigInteger.valueOf(aeBal));
        }
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
    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my,
                            String tipTitle, String tipBody) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
        if (tipTitle != null) armTip(mx, my, x, y, w, h, tipTitle, tipBody);
    }

    private void armTip(int mx, int my, int x, int y, int w, int h, String title, String body) {
        if (hoverTip != null || !GuiRenderUtil.isHovering(mx, my, x, y, w, h)) return;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§6" + title));
        lines.add(Component.literal("§7" + body));
        hoverTip = lines;
    }

    @Override
    protected void renderTooltips(GuiGraphics g, int smx, int smy, int mx, int my) {
        if (hoverTip == null || hoverTip.isEmpty()) return;
        g.renderComponentTooltip(this.font, hoverTip, mx, my);
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
